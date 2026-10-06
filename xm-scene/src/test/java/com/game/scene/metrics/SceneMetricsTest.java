package com.game.scene.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.NodeLinkFrame;
import com.game.scene.metrics.SceneMetrics.BroadcastKind;
import com.game.scene.metrics.SceneMetrics.LinkDrop;
import com.game.scene.metrics.SceneMetrics.MoveResult;
import com.game.scene.metrics.SceneMetrics.StorageOp;
import com.game.scene.metrics.SceneMetrics.WriteResult;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SceneMetricsTest {

    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final SceneMetrics metrics = new SceneMetrics(meters);

    @Test
    void 逻辑任务_排队等待从投递起算_执行耗时从开始起算() {
        Runnable timed = metrics.timeLogicTask(() -> clock.add(Duration.ofMillis(3)));
        clock.add(Duration.ofMillis(40));

        timed.run();

        Timer wait = meters.get("xm.scene.logic.task.wait").timer();
        Timer run = meters.get("xm.scene.logic.task.run").timer();
        assertThat(wait.count()).isEqualTo(1);
        assertThat(wait.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(40);
        assertThat(run.count()).isEqualTo(1);
        assertThat(run.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(3);
    }

    @Test
    void 逻辑任务抛异常_执行耗时照记_异常照抛() {
        Runnable timed = metrics.timeLogicTask(() -> {
            clock.add(Duration.ofMillis(2));
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(timed::run).isInstanceOf(IllegalStateException.class);
        assertThat(meters.get("xm.scene.logic.task.run").timer().count()).isEqualTo(1);
    }

    @Test
    void 场景人数_按配置各一条Gauge_推的是绝对值() {
        metrics.scenePlayers(1, 3);
        metrics.scenePlayers(2, 0);
        metrics.scenePlayers(1, 5);

        assertThat(meters.find("xm.scene.players").gauges()).hasSize(2);
        assertThat(meters.get("xm.scene.players").tag("scene_config", "1").gauge().value()).isEqualTo(5);
        assertThat(meters.get("xm.scene.players").tag("scene_config", "2").gauge().value()).isZero();
    }

    @Test
    void 状态量回调由抓取时读取() {
        AtomicInteger pending = new AtomicInteger(7);
        AtomicInteger links = new AtomicInteger(2);
        metrics.bindLogicQueue(pending::get);
        metrics.bindGateLinkCount(links::get);

        pending.set(11);

        assertThat(meters.get("xm.scene.logic.pending.tasks").gauge().value()).isEqualTo(11);
        assertThat(meters.get("xm.scene.gate.links").gauge().value()).isEqualTo(2);
    }

    @Test
    void 存储线程池_导出标准线程池指标_名字是scene_storage() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
        try {
            metrics.bindStorageExecutor(executor);

            assertThat(meters.get("executor.queued").tag("name", "scene-storage").gauge().value()).isZero();
            assertThat(meters.get("executor.queue.remaining").tag("name", "scene-storage").gauge().value()).isEqualTo(8);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 固化 architecture.md §11 里写的 Prometheus 名字与标签：换 Micrometer 版本或改名时这里先红。
     * 同时检查导出的标签只有本类定义的低基数维度（不会冒出 player_id / session_id 之类）。
     */
    @Test
    void Prometheus导出名与标签() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SceneMetrics exported = new SceneMetrics(prometheus);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
        try {
            exported.bindLogicQueue(() -> 0);
            exported.bindGateLinkCount(() -> 1);
            exported.bindStorageExecutor(executor);
            exported.scenePlayers(1, 2);
            exported.timeLogicTask(() -> { }).run();
            exported.tick(TimeUnit.MILLISECONDS.toNanos(3));
            exported.broadcast(BroadcastKind.VIEW_CHANGES, 1_000);
            exported.move(MoveResult.ACCEPTED);
            exported.aoiEntered(2);
            exported.aoiLeft(1);
            exported.storageWrite(StorageOp.SAVE, WriteResult.RELEASED, TimeUnit.MILLISECONDS.toNanos(8));
            exported.linkFrameIn(NodeLinkFrame.BodyCase.HELLO);
            exported.linkFrameOut(NodeLinkFrame.BodyCase.TO_CLIENT);
            exported.linkFrameDropped(LinkDrop.LINK_GONE);
            exported.linkReadPaused();

            String text = prometheus.scrape();

            assertThat(text).contains(
                    "xm_scene_players{scene_config=\"1\"} 2",
                    "xm_scene_logic_pending_tasks ",
                    "xm_scene_gate_links 1",
                    "xm_scene_logic_task_wait_seconds_count 1",
                    "xm_scene_logic_task_run_seconds_count 1",
                    "xm_scene_tick_seconds_bucket{le=\"0.05\"} 1",
                    "xm_scene_broadcast_seconds_count{kind=\"view_changes\"} 1",
                    "xm_scene_broadcast_seconds_count{kind=\"attribute_sync\"} 0",
                    "xm_scene_moves_total{result=\"accepted\"} 1",
                    "xm_scene_moves_total{result=\"invalid\"} 0",
                    "xm_scene_aoi_changes_total{change=\"enter\"} 2",
                    "xm_scene_aoi_changes_total{change=\"leave\"} 1",
                    "xm_scene_storage_writes_seconds_count{op=\"save\",result=\"released\"} 1",
                    "xm_scene_storage_writes_seconds_count{op=\"release\",result=\"fenced\"} 0",
                    "xm_scene_link_frames_total{direction=\"in\",type=\"hello\"} 1",
                    "xm_scene_link_frames_total{direction=\"out\",type=\"to_client\"} 1",
                    "xm_scene_link_dropped_total{reason=\"link_gone\"} 1",
                    "xm_scene_link_backpressure_pauses_total 1",
                    "executor_queued_tasks{name=\"scene-storage\"}");
            assertThat(labelNames(text, "xm_scene_"))
                    .isSubsetOf("scene_config", "kind", "result", "change", "op", "direction", "type", "reason", "le",
                            "rpc", "outcome", "state", "event", "gate");
        } finally {
            executor.shutdownNow();
            prometheus.close();
        }
    }

    /**
     * 镜像 / 副本实例的指标（批次 5.3，dungeon-mirror-spec §8.2）启动即注册（初值 0），导出名与标签只有 kind / state / event / result
     * （没有 scene_id、玩家号、节点号）；实例数是逻辑线程推的绝对值。
     */
    @Test
    void 实例指标_启动即注册_导出名_标签有界() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            SceneMetrics exported = new SceneMetrics(prometheus);
            String before = prometheus.scrape();
            for (String kind : new String[] {"mirror", "dungeon"}) {
                for (String state : new String[] {"active", "reclaiming", "draining"}) {
                    assertThat(before).contains("xm_scene_instances{kind=\"" + kind + "\",state=\"" + state + "\"} 0");
                }
                for (String event : new String[] {"created", "rejected", "reclaim_started", "revived", "cascade_started",
                        "destroyed_idle", "destroyed_cascade", "destroyed_admin"}) {
                    assertThat(before).contains(
                            "xm_scene_instance_lifecycle_total{event=\"" + event + "\",kind=\"" + kind + "\"} 0");
                }
            }
            for (String result : new String[] {"accepted", "bad_source", "bad_mirror_config", "source_draining",
                    "not_accepting", "node_cap", "creator_cap"}) {
                assertThat(before).contains("xm_scene_mirror_requests_total{result=\"" + result + "\"} 0");
            }
            for (String result : new String[] {"created", "rejected", "error", "stale", "wrong_node", "source_moved",
                    "create_rejected"}) {
                assertThat(before).contains("xm_scene_mirror_resolves_total{result=\"" + result + "\"} 0");
            }

            exported.instances(SceneMetrics.InstanceKind.MIRROR, 3, 1, 2);
            exported.instanceLifecycle(SceneMetrics.InstanceKind.DUNGEON, SceneMetrics.InstanceEvent.DESTROYED_ADMIN);
            exported.mirrorRequest(SceneMetrics.MirrorRequest.CREATOR_CAP);
            exported.mirrorResolve(SceneMetrics.MirrorResolve.SOURCE_MOVED);
            exported.adminRequest(SceneMetrics.ADMIN_OP_INSTANCE_CREATE, 403);
            String after = prometheus.scrape();

            assertThat(after).contains(
                    "xm_scene_instances{kind=\"mirror\",state=\"active\"} 3",
                    "xm_scene_instances{kind=\"mirror\",state=\"reclaiming\"} 1",
                    "xm_scene_instances{kind=\"mirror\",state=\"draining\"} 2",
                    "xm_scene_instance_lifecycle_total{event=\"destroyed_admin\",kind=\"dungeon\"} 1",
                    "xm_scene_mirror_requests_total{result=\"creator_cap\"} 1",
                    "xm_scene_mirror_resolves_total{result=\"source_moved\"} 1",
                    "xm_scene_admin_requests_total{op=\"instance_create\",status=\"403\"} 1");
            assertThat(labelNames(after, "xm_scene_instance")).containsOnly("kind", "state", "event");
            assertThat(labelNames(after, "xm_scene_mirror_")).containsOnly("result");
        } finally {
            prometheus.close();
        }
    }

    /** 存储写只登记每种写可能出现的结局：交出 / 探测的结局启动即注册，恒为 0 的组合（写回 × 租约不足等）不建。 */
    @Test
    void 存储写指标_交出与探测的结局启动即注册_不可能的组合不建() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            SceneMetrics exported = new SceneMetrics(prometheus);
            String before = prometheus.scrape();
            for (String result : new String[] {"handed_off", "lease_too_short", "fenced", "failed", "rejected"}) {
                assertThat(before).contains("xm_scene_storage_writes_seconds_count{op=\"handoff\",result=\"" + result + "\"} 0");
            }
            for (String result : new String[] {"handed_off", "not_committed", "lost", "rejected"}) {
                assertThat(before).contains("xm_scene_storage_writes_seconds_count{op=\"probe\",result=\"" + result + "\"} 0");
            }
            assertThat(before).doesNotContain("op=\"save\",result=\"lease_too_short\"", "op=\"probe\",result=\"fenced\"",
                    "op=\"progress\",result=\"released\"", "op=\"handoff\",result=\"saved\"");

            exported.storageWrite(StorageOp.HANDOFF, WriteResult.HANDED_OFF, TimeUnit.MILLISECONDS.toNanos(30));
            exported.storageWrite(StorageOp.SAVE, WriteResult.LOST, 1);
            String after = prometheus.scrape();
            assertThat(after).contains("xm_scene_storage_writes_seconds_count{op=\"handoff\",result=\"handed_off\"} 1");
            assertThat(after).as("不可能的组合不记、不抛").doesNotContain("op=\"save\",result=\"lost\"");
            for (StorageOp op : StorageOp.values()) {
                assertThat(op.results()).as(op.name()).contains(WriteResult.REJECTED);
            }
        } finally {
            prometheus.close();
        }
    }

    /** 组队跟随的结局启动即注册（初值 0），导出名 {@code xm_scene_team_follow_total{result}}（team-spec §6.10 第 8 条）。 */
    @Test
    void 组队跟随指标_启动即注册_导出名() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            SceneMetrics exported = new SceneMetrics(prometheus);
            String before = prometheus.scrape();
            for (String result : new String[] {"followed", "same_scene", "not_in_team", "projection_missing",
                    "not_member", "leader_not_on_node", "leader_scene_draining", "is_leader", "stale", "read_error"}) {
                assertThat(before).contains("xm_scene_team_follow_total{result=\"" + result + "\"} 0");
            }
            exported.teamFollow(SceneMetrics.TeamFollowResult.FOLLOWED);
            assertThat(prometheus.scrape()).contains("xm_scene_team_follow_total{result=\"followed\"} 1");
        } finally {
            prometheus.close();
        }
    }

    /**
     * 资产通道（E13）：rpc × outcome 启动即注册（初值 0），导出名 {@code xm_scene_asset_ops_total{rpc, outcome}} 与
     * {@code xm_scene_asset_ops_inflight}；标签只有这两个（不带 player_id / seq）。
     */
    @Test
    void 资产通道指标_启动即注册_导出名与标签() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            SceneMetrics exported = new SceneMetrics(prometheus);
            AtomicInteger inFlight = new AtomicInteger(3);
            exported.bindAssetOpsInFlight(inFlight::get);
            String before = prometheus.scrape();
            for (String rpc : new String[] {"debit", "abort_debit", "credit"}) {
                for (String outcome : new String[] {"applied", "rejected", "retry", "not_here", "unknown", "overloaded",
                        "error"}) {
                    assertThat(before).contains("xm_scene_asset_ops_total{outcome=\"" + outcome + "\",rpc=\"" + rpc + "\"} 0");
                }
            }
            assertThat(labelNames(before, "xm_scene_asset_ops_total")).containsExactly("outcome", "rpc");
            assertThat(before).contains("xm_scene_asset_ops_inflight 3");
            exported.assetOp(com.game.api.asset.AssetRpc.ABORT_DEBIT, SceneMetrics.AssetOpResult.OVERLOADED);
            inFlight.set(0);
            String after = prometheus.scrape();
            assertThat(after).contains("xm_scene_asset_ops_total{outcome=\"overloaded\",rpc=\"abort_debit\"} 1");
            assertThat(after).contains("xm_scene_asset_ops_inflight 0");
        } finally {
            prometheus.close();
        }
    }

    /**
     * 主世界频道（批次 5.1，scene-channels-spec §6.2）：启动即注册（初值 0），导出名与标签；标签只有 state / result，
     * 不带 zone / scene_id / 节点号。
     */
    @Test
    void 主世界频道指标_启动即注册_导出名与标签() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            SceneMetrics exported = new SceneMetrics(prometheus);
            String before = prometheus.scrape();
            assertThat(before).contains("xm_scene_channels{state=\"active\"} 0", "xm_scene_channels{state=\"draining\"} 0",
                    "xm_scene_channel_plan_poll_failures_total 0");
            for (String result : new String[] {"applied", "rejected", "skipped_lease"}) {
                assertThat(before).contains("xm_scene_channel_plan_applies_total{result=\"" + result + "\"} 0");
            }
            for (String result : new String[] {"same_map", "default_world", "blocked", "enter_redirect"}) {
                assertThat(before).contains("xm_scene_channel_relocations_total{result=\"" + result + "\"} 0");
            }

            exported.channels(3, 1);
            exported.channelPlanApply(SceneMetrics.ChannelPlanApply.APPLIED);
            exported.channelPlanPollFailed();
            exported.channelRelocation(SceneMetrics.ChannelRelocation.SAME_MAP);

            String after = prometheus.scrape();
            assertThat(after).contains("xm_scene_channels{state=\"active\"} 3", "xm_scene_channels{state=\"draining\"} 1",
                    "xm_scene_channel_plan_applies_total{result=\"applied\"} 1",
                    "xm_scene_channel_plan_poll_failures_total 1",
                    "xm_scene_channel_relocations_total{result=\"same_map\"} 1");
            assertThat(labelNames(after, "xm_scene_channel")).containsExactlyInAnyOrder("state", "result");
        } finally {
            prometheus.close();
        }
    }

    /**
     * 跨节点换图（批次 5.2，scene-handoff-spec §7.2）：启动即注册（初值 0），导出名与标签；标签只有 result，不带 player / zone /
     * 场景实例号 / 节点号。冻结时长与在途数一并导出。
     */
    @Test
    void 跨节点换图指标_启动即注册_导出名与标签() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            SceneMetrics exported = new SceneMetrics(prometheus);
            String before = prometheus.scrape();
            for (String result : new String[] {"local", "remote", "same", "rejected", "error", "stale"}) {
                assertThat(before).contains("xm_scene_switch_resolves_total{result=\"" + result + "\"} 0");
            }
            for (String result : new String[] {"handed_off", "lease_too_short", "fenced", "aborted_in_place",
                    "lost_unknown", "left", "taken_over", "link_gone"}) {
                assertThat(before).contains("xm_scene_transfers_total{result=\"" + result + "\"} 0");
            }
            for (String result : new String[] {"ok", "failed"}) {
                assertThat(before).contains("xm_scene_transfer_enters_total{result=\"" + result + "\"} 0");
            }
            assertThat(before).contains("xm_scene_transfers_in_flight 0", "xm_scene_transfer_post_freeze_mutations_total 0",
                    "xm_scene_transfer_freeze_seconds_count 0",
                    "xm_scene_team_follow_total{result=\"switching\"} 0",
                    "xm_scene_channel_relocations_total{result=\"switching\"} 0",
                    "xm_scene_link_dropped_total{reason=\"write_failed\"} 0");

            exported.switchResolve(SceneMetrics.SwitchResolve.REMOTE);
            exported.transfersInFlight(1);
            exported.transfer(SceneMetrics.TransferResult.HANDED_OFF, TimeUnit.MILLISECONDS.toNanos(40));
            exported.transfersInFlight(0);
            exported.transferEnter(SceneMetrics.TransferEnter.OK);
            exported.postFreezeMutation();

            String after = prometheus.scrape();
            assertThat(after).contains("xm_scene_switch_resolves_total{result=\"remote\"} 1",
                    "xm_scene_transfers_total{result=\"handed_off\"} 1",
                    "xm_scene_transfer_freeze_seconds_count 1",
                    "xm_scene_transfer_freeze_seconds_bucket{le=\"0.05\"} 1",
                    "xm_scene_transfers_in_flight 0",
                    "xm_scene_transfer_enters_total{result=\"ok\"} 1",
                    "xm_scene_transfer_post_freeze_mutations_total 1");
            assertThat(labelNames(after, "xm_scene_transfer")).containsExactlyInAnyOrder("result", "le");
            assertThat(labelNames(after, "xm_scene_switch")).containsExactly("result");
        } finally {
            prometheus.close();
        }
    }

    /** 冻结闸（scene-handoff-spec §5.9、§7.2）：入口拒绝按 kind 计，移动丢弃另在 moves{result=frozen} 里计，启动即注册。 */
    @Test
    void 冻结闸指标_启动即注册_导出名与标签() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            SceneMetrics exported = new SceneMetrics(prometheus);
            String before = prometheus.scrape();
            for (String kind : new String[] {"request", "asset_op", "move"}) {
                assertThat(before).contains("xm_scene_frozen_rejections_total{kind=\"" + kind + "\"} 0");
            }
            assertThat(before).contains("xm_scene_moves_total{result=\"frozen\"} 0");

            exported.frozenRejection(SceneMetrics.FrozenRejection.REQUEST);
            exported.frozenRejection(SceneMetrics.FrozenRejection.MOVE);
            exported.move(MoveResult.FROZEN);

            String after = prometheus.scrape();
            assertThat(after).contains("xm_scene_frozen_rejections_total{kind=\"request\"} 1",
                    "xm_scene_frozen_rejections_total{kind=\"move\"} 1",
                    "xm_scene_frozen_rejections_total{kind=\"asset_op\"} 0",
                    "xm_scene_moves_total{result=\"frozen\"} 1");
            assertThat(labelNames(after, "xm_scene_frozen")).containsExactly("kind");
        } finally {
            prometheus.close();
        }
    }

    /**
     * 回合制战斗在途闸（批次 6.3，scene-battle-spec §9）：{@code xm_scene_battle_gate_rejects_total{gate}} 八个闸启动即注册（初值 0）、标签只有 gate；
     * 现有指标补的取值也预建：移动 / 组队跟随 / 选目标 / 排空改派的 {@code in_battle}、组队跟随的 {@code battle_lock}。放技能补的是
     * {@code caster_in_battle} / {@code target_in_battle} 两个取值（按两个不同的回码拆开，没有 {@code in_battle}；审计 OPS-14）。
     */
    @Test
    void 战斗在途闸指标_八个闸启动即注册_现有指标补的in_battle取值() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            SceneMetrics exported = new SceneMetrics(prometheus);
            String before = prometheus.scrape();
            String[] gates = {"enter_scene", "skill", "attribute", "pet", "bag_sort", "asset", "move", "default"};
            for (String gate : gates) {
                assertThat(before).contains("xm_scene_battle_gate_rejects_total{gate=\"" + gate + "\"} 0");
            }
            assertThat(before.lines().filter(line -> line.startsWith("xm_scene_battle_gate_rejects_total{")).count())
                    .as("恰好规格列的八个闸").isEqualTo(gates.length);
            assertThat(SceneMetrics.BattleGate.values()).hasSize(gates.length);
            assertThat(before).contains(
                    "xm_scene_moves_total{result=\"in_battle\"} 0",
                    "xm_scene_team_follow_total{result=\"in_battle\"} 0",
                    "xm_scene_team_follow_total{result=\"battle_lock\"} 0",
                    "xm_scene_switch_resolves_total{result=\"in_battle\"} 0",
                    "xm_scene_channel_relocations_total{result=\"in_battle\"} 0",
                    "xm_scene_skill_releases_total{result=\"caster_in_battle\"} 0",
                    "xm_scene_skill_releases_total{result=\"target_in_battle\"} 0");
            assertThat(before).doesNotContain("xm_scene_skill_releases_total{result=\"in_battle\"}");

            for (SceneMetrics.BattleGate gate : SceneMetrics.BattleGate.values()) {
                exported.battleGateReject(gate);
            }
            exported.battleGateReject(SceneMetrics.BattleGate.ASSET);
            exported.move(MoveResult.IN_BATTLE);
            exported.teamFollow(SceneMetrics.TeamFollowResult.IN_BATTLE);
            exported.teamFollow(SceneMetrics.TeamFollowResult.BATTLE_LOCK);
            exported.switchResolve(SceneMetrics.SwitchResolve.IN_BATTLE);
            exported.channelRelocation(SceneMetrics.ChannelRelocation.IN_BATTLE);
            exported.skillRelease(SceneMetrics.SkillResult.CASTER_IN_BATTLE);
            exported.skillRelease(SceneMetrics.SkillResult.TARGET_IN_BATTLE);

            String after = prometheus.scrape();
            for (String gate : gates) {
                assertThat(after).contains("xm_scene_battle_gate_rejects_total{gate=\"" + gate + "\"} " + (gate.equals("asset") ? "2" : "1"));
            }
            assertThat(after).contains(
                    "xm_scene_moves_total{result=\"in_battle\"} 1",
                    "xm_scene_team_follow_total{result=\"in_battle\"} 1",
                    "xm_scene_team_follow_total{result=\"battle_lock\"} 1",
                    "xm_scene_switch_resolves_total{result=\"in_battle\"} 1",
                    "xm_scene_channel_relocations_total{result=\"in_battle\"} 1",
                    "xm_scene_skill_releases_total{result=\"caster_in_battle\"} 1",
                    "xm_scene_skill_releases_total{result=\"target_in_battle\"} 1");
            assertThat(labelNames(after, "xm_scene_battle_gate")).as("不带 player_id / battle_id / 消息号").containsExactly("gate");
            assertThat(after.lines().filter(line -> line.startsWith("xm_scene_battle_gate_rejects_total{")).count())
                    .as("发射不新增时间序列").isEqualTo(gates.length);
        } finally {
            prometheus.close();
        }
    }

    private static Set<String> labelNames(String scrape, String prefix) {
        Set<String> names = new TreeSet<>();
        Pattern label = Pattern.compile("([a-z_]+)=\"");
        for (String line : scrape.split("\n")) {
            int brace = line.indexOf('{');
            if (!line.startsWith(prefix) || brace < 0) {
                continue;
            }
            Matcher m = label.matcher(line.substring(brace));
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }
}
