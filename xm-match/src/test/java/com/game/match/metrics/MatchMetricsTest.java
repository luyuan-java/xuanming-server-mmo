package com.game.match.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.presence.PlayerPushes;
import com.game.match.dispatch.MatchMethods;
import com.game.match.gather.FingerprintMode;
import com.game.match.gather.GatherOutcome;
import com.game.match.metrics.MatchMetrics.ActivityResult;
import com.game.match.metrics.MatchMetrics.AdminOp;
import com.game.match.metrics.MatchMetrics.ChallengeResult;
import com.game.match.metrics.MatchMetrics.ChallengeStage;
import com.game.match.metrics.MatchMetrics.EvictReason;
import com.game.match.metrics.MatchMetrics.EvictResult;
import com.game.match.metrics.MatchMetrics.IndexEviction;
import com.game.match.metrics.MatchMetrics.JoinOutcome;
import com.game.match.metrics.MatchMetrics.ListResult;
import com.game.match.metrics.MatchMetrics.MatcherRound;
import com.game.match.metrics.MatchMetrics.ObserverMethod;
import com.game.match.metrics.MatchMetrics.ObserverResult;
import com.game.match.metrics.MatchMetrics.PushKind;
import com.game.match.metrics.MatchMetrics.QueueAnomaly;
import com.game.match.metrics.MatchMetrics.RatingOutcome;
import com.game.match.metrics.MatchMetrics.ReissueResult;
import com.game.match.metrics.MatchMetrics.RequestResult;
import com.game.match.metrics.MatchMetrics.RequeueReason;
import com.game.match.metrics.MatchMetrics.TeamCallResult;
import com.game.match.metrics.MatchMetrics.TeamMethod;
import com.game.match.metrics.MatchMetrics.WatchAnomaly;
import com.game.match.metrics.MatchMetrics.WatchOutcome;
import com.game.match.metrics.MatchMetrics.ZoneMix;
import com.game.match.spectate.SpectateRules;
import com.game.match.ticket.TicketStore.DropReason;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 指标的名字、标签与预建（match-spec §11 整张表）：看板与告警按这些名字写，改名即破坏；标签集合有界；常见序列启动即存在、计数为 0。
 */
class MatchMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> id >= 1 && id <= 3));

    private double count(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    @Test
    void 指标名与规格表一致() {
        Set<String> names = new TreeSet<>();
        metrics.queueDepth(3, 0, 1);
        metrics.starvedAnchorWait(3, 0, 1);
        metrics.matchWait(3, 1);
        metrics.groupRatingSpread(3, 100);
        metrics.gatherCompleted(3, GatherOutcome.SUCCESS, Duration.ofMillis(5));
        for (Meter meter : meters.getMeters()) {
            String name = meter.getId().getName();
            // SimpleMeterRegistry 把带 SLO 桶的计时 / 分布另暴露成 <名字>.histogram 的计量，不是独立的指标
            names.add(name.endsWith(".histogram") ? name.substring(0, name.length() - ".histogram".length()) : name);
        }

        assertThat(names).containsExactlyInAnyOrder(
                "xm.match.requests",
                "xm.match.join.queue",
                "xm.match.queue.depth",
                "xm.match.starved.anchor.wait",
                "xm.match.wait",
                "xm.match.group.rating.spread",
                "xm.match.matcher.rounds",
                "xm.match.requeued",
                "xm.match.queue.dropped",
                "xm.match.queue.anomalies",
                "xm.match.gathers",
                "xm.match.gather",
                "xm.match.gathers.inflight",
                "xm.match.gather.zone.mix",
                "xm.match.table.fingerprint.mismatches",
                "xm.match.battle.ticket.reissues",
                "xm.match.challenges",
                "xm.match.pushes",
                "xm.match.activity.battles",
                "xm.match.team.calls",
                "xm.match.rating.updates",
                "xm.match.rating.round.cap.draws",
                "xm.match.rating.consumer.paused",
                "xm.match.battle.nodes",
                "xm.match.lease.lost",
                "xm.match.admin.requests",
                // 批次 6.5 观战（spectate-spec §6）
                "xm.match.watch.battle",
                "xm.match.list.watchable",
                "xm.match.spectate.evictions",
                "xm.match.watchable.index.evictions",
                "xm.match.watchable.anomalies",
                "xm.match.watchable.battles",
                "xm.match.observer.rpc",
                "xm.match.spectate.inflight");
        assertThat(meters.get("xm.match.starved.anchor.wait").gauge().getId().getBaseUnit()).as("导出成 …_wait_seconds").isEqualTo("seconds");
        assertThat(meters.get("xm.match.wait").summary().getId().getBaseUnit()).isEqualTo("seconds");
    }

    @Test
    void 常见序列启动即预建为0() {
        for (String method : MatchMethods.ALL) {
            for (String result : List.of("ok", "failed", "overloaded", "bad_request", "error")) {
                assertThat(meters.get("xm.match.requests").tag("method", method).tag("result", result).timer().count()).as(method + "/" + result).isZero();
            }
        }
        assertThat(meters.get("xm.match.requests").tag("method", "unknown").tag("result", "unsupported").timer().count()).isZero();
        for (String mode : List.of("MATCH_MODE_5V5", "MATCH_MODE_1V1", "MATCH_MODE_PVE_SOLO", "MATCH_MODE_PVE_TEAM")) {
            for (String outcome : List.of("ok", "in_battle", "already_queued", "mode_not_open", "no_team_size", "not_in_scene", "internal", "overloaded")) {
                assertThat(count("xm.match.join.queue", "mode", mode, "outcome", outcome)).as(mode + "/" + outcome).isZero();
            }
        }
        for (String result : List.of("ok", "paused_no_battle", "paused_no_lease", "paused_saturated", "error")) {
            assertThat(count("xm.match.matcher.rounds", "result", result)).isZero();
        }
        for (String outcome : List.of("success", "internal", "overloaded", "no_battle_node", "no_location", "prepare_failed", "fingerprint_mismatch",
                "index_failed", "not_allocatable", "create_rejected", "create_not_sent", "create_failed", "create_failed_room_alive")) {
            for (String mode : List.of("MATCH_MODE_5V5", "MATCH_MODE_1V1", "MATCH_MODE_PVE_SOLO", "MATCH_MODE_PVE_TEAM", "MATCH_MODE_PVP_CHALLENGE")) {
                assertThat(count("xm.match.gathers", "mode", mode, "outcome", outcome)).as(mode + "/" + outcome).isZero();
            }
        }
        for (String result : List.of("ok", "rejected", "not_found", "no_session", "internal", "rpc_error", "rpc_timeout", "instance_changed")) {
            assertThat(count("xm.match.battle.ticket.reissues", "result", result)).isZero();
        }
        for (String result : List.of("ok", "internal", "self", "self_busy", "target_busy", "target_offline", "pending", "push_failed", "overloaded")) {
            assertThat(count("xm.match.challenges", "stage", "invite", "result", result)).isZero();
        }
        for (String result : List.of("internal", "expired", "not_target", "declined", "challenger_busy", "responder_busy", "accepted", "overloaded")) {
            assertThat(count("xm.match.challenges", "stage", "respond", "result", result)).isZero();
        }
        for (String kind : List.of("156", "154")) {
            for (String outcome : List.of("sent", "offline", "gate_unreachable", "error")) {
                assertThat(count("xm.match.pushes", "kind", kind, "outcome", outcome)).isZero();
            }
        }
        for (String kind : List.of("guild_trial", "none", "unknown")) {
            for (String result : List.of("started", "invalid", "offline", "in_battle", "not_ready", "internal", "gather_ok", "gather_failed")) {
                assertThat(count("xm.match.activity.battles", "kind", kind, "result", result)).isZero();
            }
        }
        for (String outcome : List.of("applied", "duplicate", "ignored", "error", "decode_error", "rejected")) {
            assertThat(count("xm.match.rating.updates", "mode", "MATCH_MODE_1V1", "outcome", outcome)).isZero();
            assertThat(count("xm.match.rating.updates", "mode", "MATCH_MODE_5V5", "outcome", outcome)).isZero();
        }
        assertThat(count("xm.match.rating.updates", "mode", "unknown", "outcome", "decode_error")).isZero();
        assertThat(count("xm.match.team.calls", "method", "checkTeamMatch", "result", "member_in_battle")).isZero();
        assertThat(count("xm.match.team.calls", "method", "createTeamTickets", "result", "expired")).isZero();
        assertThat(count("xm.match.team.calls", "method", "runTeamGather", "result", "gather_failed")).isZero();
        assertThat(count("xm.match.table.fingerprint.mismatches", "fp_mode", "warn")).isZero();
        assertThat(count("xm.match.table.fingerprint.mismatches", "fp_mode", "enforce")).isZero();
        assertThat(meters.get("xm.match.rating.consumer.paused").gauge().value()).isZero();
        assertThat(meters.get("xm.match.lease.lost").gauge().value()).isZero();
        assertThat(meters.get("xm.match.gathers.inflight").gauge().value()).isZero();
        assertThat(meters.get("xm.match.battle.nodes").tag("state", "accepting").gauge().value()).isZero();
        assertThat(meters.get("xm.match.battle.nodes").tag("state", "not_accepting").gauge().value()).isZero();
    }

    @Test
    void 请求计时_方法名净化() {
        metrics.requestCompleted(metrics.startTimer(), MatchMethods.JOIN_QUEUE, RequestResult.OK);
        metrics.requestCompleted(metrics.startTimer(), "客户端造的名字", RequestResult.UNSUPPORTED);
        metrics.requestCompleted(metrics.startTimer(), null, RequestResult.UNSUPPORTED);

        assertThat(meters.get("xm.match.requests").tag("method", "JoinQueue").tag("result", "ok").timer().count()).isEqualTo(1);
        assertThat(meters.get("xm.match.requests").tag("method", "unknown").tag("result", "unsupported").timer().count()).isEqualTo(2);
    }

    @Test
    void 排队计数_模式净化() {
        metrics.joinQueue(3, JoinOutcome.OK);
        metrics.joinQueue(2, JoinOutcome.MODE_NOT_OPEN);
        metrics.joinQueue(424242, JoinOutcome.MODE_NOT_OPEN);
        metrics.joinQueue(-9, JoinOutcome.MODE_NOT_OPEN);

        assertThat(count("xm.match.join.queue", "mode", "MATCH_MODE_1V1", "outcome", "ok")).isEqualTo(1);
        assertThat(count("xm.match.join.queue", "mode", "MATCH_MODE_3V3", "outcome", "mode_not_open")).isEqualTo(1);
        assertThat(count("xm.match.join.queue", "mode", "unknown", "outcome", "mode_not_open")).isEqualTo(2);
    }

    @Test
    void 队列深度与饥饿等待_按净化后的标签_非持锁实例置0() {
        metrics.queueDepth(3, 0, 7);
        metrics.queueDepth(5, 1, 4);
        metrics.queueDepth(3, 99999, 2);
        metrics.queueDepth(3, 88888, 5);
        metrics.starvedAnchorWait(3, 0, 46);

        assertThat(meters.get("xm.match.queue.depth").tag("mode", "MATCH_MODE_1V1").tag("config", "0").gauge().value()).isEqualTo(7);
        assertThat(meters.get("xm.match.queue.depth").tag("mode", "MATCH_MODE_PVE_TEAM").tag("config", "1").gauge().value()).isEqualTo(4);
        assertThat(meters.get("xm.match.queue.depth").tag("mode", "MATCH_MODE_1V1").tag("config", "other").gauge().value())
                .as("表外的副本号共用 other 这一条序列（后写的覆盖先写的）").isEqualTo(5);
        assertThat(meters.get("xm.match.starved.anchor.wait").tag("mode", "MATCH_MODE_1V1").tag("config", "0").gauge().value()).isEqualTo(46);

        metrics.queueDepth(3, 0, 0);
        metrics.starvedAnchorWait(3, 0, -5);
        assertThat(meters.get("xm.match.queue.depth").tag("mode", "MATCH_MODE_1V1").tag("config", "0").gauge().value()).isZero();
        assertThat(meters.get("xm.match.starved.anchor.wait").tag("mode", "MATCH_MODE_1V1").tag("config", "0").gauge().value()).as("负值夹到 0").isZero();
        assertThat(meters.get("xm.match.queue.depth").gauges()).as("两个表外副本号没有各占一条序列").hasSize(3);
    }

    @Test
    void 成组观测_等待秒数与评分极差_极差从centi换算成评分点() {
        metrics.matchWait(3, 12);
        metrics.matchWait(3, 48);
        metrics.groupRatingSpread(3, 15_050);
        metrics.groupRatingSpread(1, 0);

        assertThat(meters.get("xm.match.wait").tag("mode", "MATCH_MODE_1V1").summary().count()).isEqualTo(2);
        assertThat(meters.get("xm.match.wait").tag("mode", "MATCH_MODE_1V1").summary().totalAmount()).isEqualTo(60);
        assertThat(meters.get("xm.match.group.rating.spread").tag("mode", "MATCH_MODE_1V1").summary().totalAmount()).isEqualTo(150.5);
        assertThat(meters.get("xm.match.group.rating.spread").tag("mode", "MATCH_MODE_5V5").summary().count()).isEqualTo(1);
    }

    @Test
    void 凑单各计数() {
        metrics.matcherRound(MatcherRound.PAUSED_NO_BATTLE);
        metrics.matcherRound(MatcherRound.OK);
        metrics.requeued(RequeueReason.GATHER_NO_OFFENDER, 10);
        metrics.requeued(RequeueReason.GATHER_OFFENDER, 0);
        metrics.queueDropped(DropReason.OFFLINE);
        metrics.queueAnomaly(QueueAnomaly.REPICK_EXHAUSTED);
        metrics.battleNodes(2, 1);

        assertThat(count("xm.match.matcher.rounds", "result", "paused_no_battle")).isEqualTo(1);
        assertThat(count("xm.match.matcher.rounds", "result", "ok")).isEqualTo(1);
        assertThat(count("xm.match.requeued", "reason", "gather_no_offender")).as("按人数计").isEqualTo(10);
        assertThat(count("xm.match.requeued", "reason", "gather_offender")).isZero();
        assertThat(count("xm.match.queue.dropped", "reason", "offline")).isEqualTo(1);
        assertThat(count("xm.match.queue.dropped", "reason", "invalid")).isZero();
        assertThat(count("xm.match.queue.anomalies", "reason", "repick_exhausted")).isEqualTo(1);
        assertThat(meters.get("xm.match.battle.nodes").tag("state", "accepting").gauge().value()).isEqualTo(2);
        assertThat(meters.get("xm.match.battle.nodes").tag("state", "not_accepting").gauge().value()).isEqualTo(1);
    }

    @Test
    void gather计数与计时_在途数接到管线上() {
        metrics.gatherCompleted(3, GatherOutcome.SUCCESS, Duration.ofMillis(40));
        metrics.gatherCompleted(3, GatherOutcome.CREATE_FAILED_ROOM_ALIVE, Duration.ofSeconds(9));
        metrics.gatherCompleted(77, GatherOutcome.OVERLOADED, Duration.ofMillis(-3));
        metrics.gatherZoneMix(3, ZoneMix.CROSS);
        metrics.fingerprintMismatch(FingerprintMode.WARN);
        metrics.fingerprintMismatch(FingerprintMode.OFF);
        metrics.bindInflightGathers(() -> 5);

        assertThat(count("xm.match.gathers", "mode", "MATCH_MODE_1V1", "outcome", "success")).isEqualTo(1);
        assertThat(count("xm.match.gathers", "mode", "MATCH_MODE_1V1", "outcome", "create_failed_room_alive")).isEqualTo(1);
        assertThat(count("xm.match.gathers", "mode", "unknown", "outcome", "overloaded")).isEqualTo(1);
        assertThat(meters.get("xm.match.gather").tag("mode", "MATCH_MODE_1V1").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(meters.get("xm.match.gather").tag("mode", "unknown").tag("outcome", "overloaded").timer().count()).as("负的耗时按 0 记，不抛").isEqualTo(1);
        assertThat(count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "cross")).isEqualTo(1);
        assertThat(count("xm.match.table.fingerprint.mismatches", "fp_mode", "warn")).isEqualTo(1);
        assertThat(meters.find("xm.match.table.fingerprint.mismatches").tag("fp_mode", "off").counter()).as("off 不比对，不计").isNull();
        assertThat(meters.get("xm.match.gathers.inflight").gauge().value()).isEqualTo(5);
    }

    @Test
    void 补签_切磋_推送_内部接口_评分_管理口() {
        metrics.reissue(ReissueResult.INSTANCE_CHANGED);
        metrics.challenge(ChallengeStage.INVITE, ChallengeResult.PUSH_FAILED);
        metrics.challenge(ChallengeStage.RESPOND, ChallengeResult.ACCEPTED);
        metrics.push(PushKind.INVITE, PlayerPushes.Outcome.GATE_UNREACHABLE);
        metrics.push(PushKind.RESULT, PlayerPushes.Outcome.SENT);
        metrics.pushError(PushKind.RESULT);
        metrics.activityBattle(1, ActivityResult.STARTED);
        metrics.activityBattle(57, ActivityResult.INVALID);
        metrics.teamCall(TeamMethod.CHECK_TEAM_MATCH, TeamCallResult.MEMBER_OFFLINE);
        metrics.teamCall(TeamMethod.RUN_TEAM_GATHER, TeamCallResult.GATHER_OK);
        metrics.ratingUpdate(3, RatingOutcome.APPLIED);
        metrics.ratingUpdate(5, RatingOutcome.IGNORED);
        metrics.ratingUpdate(-1, RatingOutcome.DECODE_ERROR);
        metrics.ratingUpdate(1, RatingOutcome.REJECTED);
        metrics.ratingRoundCapDraw(1);
        metrics.ratingConsumerPaused(true);
        metrics.leaseLost(true);
        metrics.adminRequest(AdminOp.RATING, 200);
        metrics.adminRequest(AdminOp.ACTIVITY_BATTLE, 418);

        assertThat(count("xm.match.battle.ticket.reissues", "result", "instance_changed")).isEqualTo(1);
        assertThat(count("xm.match.challenges", "stage", "invite", "result", "push_failed")).isEqualTo(1);
        assertThat(count("xm.match.challenges", "stage", "respond", "result", "accepted")).isEqualTo(1);
        assertThat(count("xm.match.pushes", "kind", "156", "outcome", "gate_unreachable")).isEqualTo(1);
        assertThat(count("xm.match.pushes", "kind", "154", "outcome", "sent")).isEqualTo(1);
        assertThat(count("xm.match.pushes", "kind", "154", "outcome", "error")).isEqualTo(1);
        assertThat(count("xm.match.activity.battles", "kind", "guild_trial", "result", "started")).isEqualTo(1);
        assertThat(count("xm.match.activity.battles", "kind", "unknown", "result", "invalid")).isEqualTo(1);
        assertThat(count("xm.match.team.calls", "method", "checkTeamMatch", "result", "member_offline")).isEqualTo(1);
        assertThat(count("xm.match.team.calls", "method", "runTeamGather", "result", "gather_ok")).isEqualTo(1);
        assertThat(count("xm.match.rating.updates", "mode", "MATCH_MODE_1V1", "outcome", "applied")).isEqualTo(1);
        assertThat(count("xm.match.rating.updates", "mode", "MATCH_MODE_PVE_TEAM", "outcome", "ignored")).isEqualTo(1);
        assertThat(count("xm.match.rating.updates", "mode", "unknown", "outcome", "decode_error")).isEqualTo(1);
        assertThat(count("xm.match.rating.updates", "mode", "MATCH_MODE_5V5", "outcome", "rejected")).as("被数据库拒绝而永久跳过的局").isEqualTo(1);
        assertThat(count("xm.match.rating.round.cap.draws", "mode", "MATCH_MODE_5V5")).isEqualTo(1);
        assertThat(meters.get("xm.match.rating.consumer.paused").gauge().value()).isEqualTo(1);
        assertThat(meters.get("xm.match.lease.lost").gauge().value()).isEqualTo(1);
        assertThat(count("xm.match.admin.requests", "op", "rating", "status", "200")).isEqualTo(1);
        assertThat(count("xm.match.admin.requests", "op", "activity_battle", "status", "418")).as("没预建的状态码首次出现时再建").isEqualTo(1);

        metrics.ratingConsumerPaused(false);
        assertThat(meters.get("xm.match.rating.consumer.paused").gauge().value()).isZero();
    }

    // ================================================================ 观战（批次 6.5，spectate-spec §6 的 8 个指标）

    @Test
    void 观战_各计数的标签取值与规格表逐个相同_启动即预建为0() {
        List<String> outcomes = List.of("ok", "internal", "queued", "in_battle", "already_watching", "offline", "no_battle", "not_found", "rejected",
                "overloaded");
        for (String outcome : outcomes) {
            assertThat(count("xm.match.watch.battle", "outcome", outcome)).as(outcome).isZero();
        }
        assertThat(meters.find("xm.match.watch.battle").counters()).as("163 的出口恰好十种").hasSize(outcomes.size());

        for (String result : List.of("ok", "error", "overloaded")) {
            assertThat(count("xm.match.list.watchable", "result", result)).as(result).isZero();
        }
        assertThat(meters.find("xm.match.list.watchable").counters()).hasSize(3);

        List<String> evictReasons = List.of("enter_gather", "rewatch", "concurrent_queue");
        List<String> evictResults = List.of("removed", "no_record", "invalid_mark", "read_failed", "rpc_failed");
        for (String reason : evictReasons) {
            for (String result : evictResults) {
                assertThat(count("xm.match.spectate.evictions", "reason", reason, "result", result)).as(reason + "/" + result).isZero();
            }
        }
        assertThat(meters.find("xm.match.spectate.evictions").counters()).hasSize(15);

        List<String> indexReasons = List.of("room_missing", "dead_node", "stale", "missing_record", "invalid_member", "sweep");
        for (String reason : indexReasons) {
            assertThat(count("xm.match.watchable.index.evictions", "reason", reason)).as(reason).isZero();
        }
        assertThat(meters.find("xm.match.watchable.index.evictions").counters()).hasSize(indexReasons.size());

        List<String> anomalies = List.of("corrupt_record", "record_read_failed", "publish_failed", "mark_read_failed");
        for (String reason : anomalies) {
            assertThat(count("xm.match.watchable.anomalies", "reason", reason)).as(reason).isZero();
        }
        assertThat(meters.find("xm.match.watchable.anomalies").counters()).hasSize(anomalies.size());

        for (String method : List.of("add", "remove")) {
            for (String result : List.of("replied", "dead", "not_delivered", "unknown")) {
                assertThat(count("xm.match.observer.rpc", "method", method, "result", result)).as(method + "/" + result).isZero();
            }
        }
        assertThat(meters.find("xm.match.observer.rpc").counters()).hasSize(8);

        assertThat(meters.get("xm.match.watchable.battles").gauge().value()).isZero();
        assertThat(meters.get("xm.match.spectate.inflight").gauge().value()).isZero();
        assertThat(meters.get("xm.match.watchable.battles").gauge().getId().getTags()).as("两个 gauge 都不带标签").isEmpty();
        assertThat(meters.get("xm.match.spectate.inflight").gauge().getId().getTags()).isEmpty();
    }

    @Test
    void 观战_163与164的出口各记各的_already_watching只在调用方记16016时才涨() {
        metrics.watchBattle(WatchOutcome.OK);
        metrics.watchBattle(WatchOutcome.OK);
        metrics.watchBattle(WatchOutcome.ALREADY_WATCHING);
        metrics.watchBattle(WatchOutcome.OVERLOADED);
        metrics.listWatchable(ListResult.OK);
        metrics.listWatchable(ListResult.ERROR);
        // 入口处对旧标记的懒清退不算 already_watching（W14）：它记的是清退
        metrics.spectateEviction(EvictReason.REWATCH, EvictResult.REMOVED);

        assertThat(count("xm.match.watch.battle", "outcome", "ok")).isEqualTo(2);
        assertThat(count("xm.match.watch.battle", "outcome", "already_watching")).isEqualTo(1);
        assertThat(count("xm.match.watch.battle", "outcome", "overloaded")).isEqualTo(1);
        assertThat(count("xm.match.watch.battle", "outcome", "internal")).isZero();
        assertThat(count("xm.match.list.watchable", "result", "ok")).isEqualTo(1);
        assertThat(count("xm.match.list.watchable", "result", "error")).isEqualTo(1);
        assertThat(count("xm.match.list.watchable", "result", "overloaded")).isZero();
        assertThat(count("xm.match.spectate.evictions", "reason", "rewatch", "result", "removed")).isEqualTo(1);
    }

    @Test
    void 观战_清退按起因与结局_索引剔除按摘掉的成员数_异常与观众RPC() {
        metrics.spectateEviction(EvictReason.ENTER_GATHER, EvictResult.REMOVED);
        metrics.spectateEviction(EvictReason.ENTER_GATHER, EvictResult.RPC_FAILED);
        metrics.spectateEviction(EvictReason.CONCURRENT_QUEUE, EvictResult.NO_RECORD);
        metrics.spectateEviction(EvictReason.REWATCH, EvictResult.INVALID_MARK);
        metrics.spectateEviction(EvictReason.REWATCH, EvictResult.READ_FAILED);
        metrics.watchableIndexEvicted(IndexEviction.SWEEP, 7);
        metrics.watchableIndexEvicted(IndexEviction.SWEEP, 0);
        metrics.watchableIndexEvicted(IndexEviction.SWEEP, -3);
        metrics.watchableIndexEvicted(IndexEviction.ROOM_MISSING, 1);
        metrics.watchableIndexEvicted(IndexEviction.DEAD_NODE, 1);
        metrics.watchableIndexEvicted(IndexEviction.STALE, 2);
        metrics.watchableIndexEvicted(IndexEviction.MISSING_RECORD, 1);
        metrics.watchableIndexEvicted(IndexEviction.INVALID_MEMBER, 1);
        metrics.watchableAnomaly(WatchAnomaly.CORRUPT_RECORD);
        metrics.watchableAnomaly(WatchAnomaly.RECORD_READ_FAILED);
        metrics.watchableAnomaly(WatchAnomaly.PUBLISH_FAILED);
        metrics.watchableAnomaly(WatchAnomaly.MARK_READ_FAILED);
        metrics.watchableAnomaly(WatchAnomaly.MARK_READ_FAILED);
        metrics.observerRpc(ObserverMethod.ADD, ObserverResult.REPLIED);
        metrics.observerRpc(ObserverMethod.ADD, ObserverResult.UNKNOWN);
        metrics.observerRpc(ObserverMethod.REMOVE, ObserverResult.DEAD);
        metrics.observerRpc(ObserverMethod.REMOVE, ObserverResult.NOT_DELIVERED);

        assertThat(count("xm.match.spectate.evictions", "reason", "enter_gather", "result", "removed")).isEqualTo(1);
        assertThat(count("xm.match.spectate.evictions", "reason", "enter_gather", "result", "rpc_failed")).isEqualTo(1);
        assertThat(count("xm.match.spectate.evictions", "reason", "concurrent_queue", "result", "no_record")).isEqualTo(1);
        assertThat(count("xm.match.spectate.evictions", "reason", "rewatch", "result", "invalid_mark")).isEqualTo(1);
        assertThat(count("xm.match.spectate.evictions", "reason", "rewatch", "result", "read_failed")).isEqualTo(1);
        assertThat(count("xm.match.watchable.index.evictions", "reason", "sweep")).as("清扫一轮摘掉几条就加几；0 与负数不计").isEqualTo(7);
        assertThat(count("xm.match.watchable.index.evictions", "reason", "room_missing")).isEqualTo(1);
        assertThat(count("xm.match.watchable.index.evictions", "reason", "dead_node")).isEqualTo(1);
        assertThat(count("xm.match.watchable.index.evictions", "reason", "stale")).isEqualTo(2);
        assertThat(count("xm.match.watchable.index.evictions", "reason", "missing_record")).isEqualTo(1);
        assertThat(count("xm.match.watchable.index.evictions", "reason", "invalid_member")).isEqualTo(1);
        assertThat(count("xm.match.watchable.anomalies", "reason", "corrupt_record")).isEqualTo(1);
        assertThat(count("xm.match.watchable.anomalies", "reason", "record_read_failed")).isEqualTo(1);
        assertThat(count("xm.match.watchable.anomalies", "reason", "publish_failed")).isEqualTo(1);
        assertThat(count("xm.match.watchable.anomalies", "reason", "mark_read_failed")).isEqualTo(2);
        assertThat(count("xm.match.observer.rpc", "method", "add", "result", "replied")).isEqualTo(1);
        assertThat(count("xm.match.observer.rpc", "method", "add", "result", "unknown")).isEqualTo(1);
        assertThat(count("xm.match.observer.rpc", "method", "remove", "result", "dead")).isEqualTo(1);
        assertThat(count("xm.match.observer.rpc", "method", "remove", "result", "not_delivered")).isEqualTo(1);
        assertThat(count("xm.match.observer.rpc", "method", "remove", "result", "replied")).isZero();
    }

    @Test
    void 观战_索引大小是采样值_在途数接到163的执行器上() {
        metrics.watchableBattles(12);
        assertThat(meters.get("xm.match.watchable.battles").gauge().value()).isEqualTo(12);
        metrics.watchableBattles(3);
        assertThat(meters.get("xm.match.watchable.battles").gauge().value()).as("后一次采样覆盖前一次，不累加").isEqualTo(3);
        metrics.watchableBattles(-1);
        assertThat(meters.get("xm.match.watchable.battles").gauge().value()).as("负数夹到 0").isZero();

        int[] inflight = {5};
        metrics.bindSpectateInflight(() -> inflight[0]);
        assertThat(meters.get("xm.match.spectate.inflight").gauge().value()).isEqualTo(5);
        inflight[0] = 0;
        assertThat(meters.get("xm.match.spectate.inflight").gauge().value()).as("每次导出现读").isZero();
        assertThat(meters.get("xm.match.gathers.inflight").gauge().value()).as("与 gather 的在途数是两个互不相干的来源").isZero();
    }

    @Test
    void 观战_清退原因的标签值与发给battle的reason逐字相同() {
        assertThat(MatchMetrics.tag(EvictReason.REWATCH)).isEqualTo(SpectateRules.REASON_REWATCH).isEqualTo("rewatch");
        assertThat(MatchMetrics.tag(EvictReason.ENTER_GATHER)).isEqualTo(SpectateRules.REASON_ENTER_GATHER).isEqualTo("enter_gather");
        assertThat(MatchMetrics.tag(EvictReason.CONCURRENT_QUEUE)).isEqualTo(SpectateRules.REASON_CONCURRENT_QUEUE).isEqualTo("concurrent_queue");
    }

    @Test
    void 标签键的集合有界_没有任何id或zone() {
        metrics.queueDepth(3, 0, 1);
        metrics.starvedAnchorWait(3, 0, 1);
        metrics.matchWait(3, 1);
        metrics.groupRatingSpread(3, 1);
        metrics.gatherCompleted(3, GatherOutcome.SUCCESS, Duration.ofMillis(1));
        Set<String> tagKeys = new HashSet<>();
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag -> tagKeys.add(tag.getKey()));
        }
        tagKeys.remove("le"); // SLO 桶的边界（SimpleMeterRegistry 把直方图桶暴露成带 le 的计量），取值固定

        assertThat(tagKeys).containsExactlyInAnyOrder("method", "result", "mode", "outcome", "config", "reason", "mix", "fp_mode", "stage", "kind",
                "state", "op", "status");
    }
}
