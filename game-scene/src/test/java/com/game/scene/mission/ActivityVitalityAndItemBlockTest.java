package com.game.scene.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerEnter;
import com.game.player.store.state.ActivityClaimRecordState;
import com.game.player.store.state.ItemGainBlockState;
import com.game.player.store.state.PlayerState;
import com.game.proto.ClaimActivityVitalityRewardRequest;
import com.game.proto.ClaimActivityVitalityRewardResponse;
import com.game.proto.PlayerActivityVitalitySnapshot;
import com.game.proto.player_inbox.GmItemGainPayload;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.inbox.InboxConsumers;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.PlayerData;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.game.table.ActivityScheduleTable;
import com.game.table.ActivityVitalityRewardTable;
import com.game.table.ActivityVitalityRuleTable;
import com.game.table.CommonErrorTip;
import com.game.table.ConditionTable;
import com.game.table.MissionTable;
import com.game.table.PlayerInboxErrorTip;
import com.game.table.RewardErrorTip;
import com.game.table.RewardTable;
import com.game.table.Rewardreward;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 个人物品获取封锁（GM 收件箱类型 5/6）与活动中心活跃度积分/档位领奖（348）端到端单测。
 */
class ActivityVitalityAndItemBlockTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 2001;
    private static final long PERIOD_START = ManualClock.EPOCH_MILLIS_START;
    private static final long PERIOD_DURATION = 86_400_000L;
    private static final int ITEM_ID = 10;
    private static final int MONSTER_ID = 1;

    private record Harness(SceneWorld world, FakePlayerRepository repo, ManualClock clock,
                           CurrencyService currency, BagService bags, MissionService missions, Scene scene) {
        ScenePlayer enter(PlayerState state) {
            repo.put(new PlayerData(PLAYER, 1, 1, 1, "", 30, scene.configId(), Vec3.ORIGIN, state));
            world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                    .setSessionId(SESSION).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
            repo.completeAll();
            return world.playerById(PLAYER);
        }
    }

    private Harness createHarness(List<MissionTable> missionRows, List<ConditionTable> conditionRows,
                                  List<RewardTable> rewardRows, List<ActivityScheduleTable> schedules,
                                  List<ActivityVitalityRuleTable> rules, List<ActivityVitalityRewardTable> vitalityRewards) {
        ManualClock clock = new ManualClock();
        clock.advanceMillis(10_000L);
        RecordingAssetAudit audit = new RecordingAssetAudit();
        SceneMetrics metrics = new SceneMetrics(new SimpleMeterRegistry());
        AtomicLong guids = new AtomicLong(10_000);
        CurrencyService currency = new CurrencyService(audit, GainAnomalyDetector.off(), metrics, clock);
        BagService bags = new BagService(BagTables.from(MissionFixtures.SHIPPED),
                n -> {
                    long[] out = new long[n];
                    for (int i = 0; i < n; i++) out[i] = guids.incrementAndGet();
                    return out;
                }, audit, GainAnomalyDetector.off(), metrics);
        MissionTables missionTables = new MissionTables(missionRows, conditionRows, rewardRows, schedules,
                Set.of(MONSTER_ID), true, MissionFixtures.SHIPPED.item()::contains,
                rules, vitalityRewards, Map.of(ITEM_ID, new MissionTables.ItemDisplay("灵石", "icon_stone", 0)));
        MissionService missions = new MissionService(missionTables, bags, clock);
        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(), repo,
                new AtomicLong(5000)::incrementAndGet, clock, metrics, p -> {
            bags.initializeOnLoad(p);
            missions.initializeOnLoad(p);
        }, com.game.scene.world.PlayerSnapshots.NONE);
        Scene scene = world.createScene(1);
        return new Harness(world, repo, clock, currency, bags, missions, scene);
    }

    @Test
    void 个人物品获取封锁与解封_走收件箱执行器并落盘往返且保留未知字段() {
        Harness h = createHarness(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        UnknownFieldSet unknown = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(777L).build())
                .build();
        PlayerState initial = PlayerState.newBuilder()
                .setItemGainBlock(ItemGainBlockState.newBuilder().setUnknownFields(unknown).build())
                .build();
        ScenePlayer player = h.enter(initial);
        InboxConsumers consumers = InboxConsumers.gmSanction(h.currency, h.bags);
        assertThat(consumers.selfCheck()).isNull();

        int gmDomain = PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_GM_SANCTION_VALUE;
        InboxConsumers.Executor blockExec = consumers.executor(
                PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_BLOCK_ITEM_GAIN_VALUE, gmDomain);
        InboxConsumers.Executor unblockExec = consumers.executor(
                PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_UNBLOCK_ITEM_GAIN_VALUE, gmDomain);
        assertThat(blockExec).isNotNull();
        assertThat(unblockExec).isNotNull();

        // item_config_id == 0 或非法载荷一律 REJECTED(PAYLOAD_INVALID)
        int payloadInvalid = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxPayloadInvalid_VALUE;
        assertThat(blockExec.execute(player, GmItemGainPayload.newBuilder().setItemConfigId(0).build().toByteString()))
                .isEqualTo(new InboxConsumers.Rejected(payloadInvalid));
        assertThat(blockExec.execute(player, ByteString.copyFrom(new byte[]{(byte) 0xFF, (byte) 0xFF})))
                .isEqualTo(new InboxConsumers.Rejected(payloadInvalid));

        // 封锁后入包被拒（1005）
        ByteString payload = GmItemGainPayload.newBuilder().setItemConfigId(ITEM_ID).build().toByteString();
        assertThat(blockExec.execute(player, payload)).isEqualTo(new InboxConsumers.Applied());
        assertThat(player.itemGainBlock().isBlocked(ITEM_ID)).isTrue();
        Bag.AddResult blocked = h.bags.addItems(player, BagType.INVENTORY, Map.of(ITEM_ID, 2L), Reason.GM_GRANT, 0, "");
        assertThat(blocked.tip()).isEqualTo(BagService.REFUSED);
        assertThat(player.bags().bag(BagType.INVENTORY).total(ITEM_ID)).isZero();

        // 未知字段与封锁列表一起往返落盘
        PlayerState saved = WorldTestAccess.persistentState(player);
        assertThat(saved.getItemGainBlock().getBlockedItemConfigIdsList()).containsExactly(ITEM_ID);
        assertThat(saved.getItemGainBlock().getUnknownFields()).isEqualTo(unknown);

        // 解封后恢复入包
        assertThat(unblockExec.execute(player, payload)).isEqualTo(new InboxConsumers.Applied());
        assertThat(player.itemGainBlock().isBlocked(ITEM_ID)).isFalse();
        Bag.AddResult granted = h.bags.addItems(player, BagType.INVENTORY, Map.of(ITEM_ID, 3L), Reason.GM_GRANT, 0, "");
        assertThat(granted.tip()).isZero();
        assertThat(player.bags().bag(BagType.INVENTORY).total(ITEM_ID)).isEqualTo(3L);
    }

    @Test
    void 活跃度积分累计_档位领奖_幂等重放_跨期结束与64条裁剪() {
        ConditionTable killOne = MissionFixtures.newCondition(701, MissionTables.CATEGORY_KILL, 1, MONSTER_ID).build();
        MissionTable actMission1 = MissionFixtures.newMission(901, MissionTables.TYPE_ACTIVITY, 1, 0, 701).build();
        MissionTable actMission2 = MissionFixtures.newMission(902, MissionTables.TYPE_ACTIVITY, 2, 0, 701).build();
        ActivityScheduleTable sched1 = ActivityScheduleTable.newBuilder()
                .setId(901).setEnabled(true).setBaselineStartAtMs(1).setBaselineEndAtMs(Long.MAX_VALUE)
                .setDefinitionKey("act_901").setConfigurationRevision(1).setVitalityPoints(60).build();
        ActivityScheduleTable sched2 = ActivityScheduleTable.newBuilder()
                .setId(902).setEnabled(true).setBaselineStartAtMs(1).setBaselineEndAtMs(Long.MAX_VALUE)
                .setDefinitionKey("act_902").setConfigurationRevision(1).setVitalityPoints(80).build();
        RewardTable reward = RewardTable.newBuilder().setId(801)
                .addReward(Rewardreward.newBuilder().setRewardItem(ITEM_ID).setRewardCount(5)).build();
        ActivityVitalityRuleTable rule = ActivityVitalityRuleTable.newBuilder()
                .setId(1).setEnabled(true).setConfigurationRevision(2)
                .setFirstPeriodStartsAtMs(PERIOD_START).setPeriodDurationMs(PERIOD_DURATION)
                .setPointsLimit(100).build();
        ActivityVitalityRewardTable threshold1 = ActivityVitalityRewardTable.newBuilder()
                .setId(11).setRuleId(1).setRequiredPoints(50).setRewardId(801).build();
        ActivityVitalityRewardTable threshold2 = ActivityVitalityRewardTable.newBuilder()
                .setId(12).setRuleId(1).setRequiredPoints(100).setRewardId(801).build();

        Harness h = createHarness(List.of(actMission1, actMission2), List.of(killOne), List.of(reward),
                List.of(sched1, sched2), List.of(rule), List.of(threshold1, threshold2));
        ScenePlayer player = h.enter(PlayerState.getDefaultInstance());
        long nowMs = h.clock.epochMillis();

        // 接取并完成两个活动任务：60 + 80 封顶到 points_limit=100，且同任务位图去重不重复记分
        assertThat(h.missions.accept(player, 0, 901, nowMs)).isZero();
        assertThat(h.missions.accept(player, 0, 902, nowMs)).isZero();
        h.missions.onMonsterKilled(player, MONSTER_ID, 1);

        PlayerActivityVitalitySnapshot snap = h.missions.vitality().buildSnapshot(player, nowMs);
        assertThat(snap.getAvailable()).isTrue();
        assertThat(snap.getPoints()).isEqualTo(100);
        assertThat(snap.getPointsLimit()).isEqualTo(100);
        assertThat(snap.getRewardsList()).hasSize(2);
        assertThat(snap.getRewards(0).getItems(0).getName()).isEqualTo("灵石");
        assertThat(snap.getRewards(0).getCanClaim()).isTrue();

        // 首次领第 1 档 → 成功入包 5 个
        ClaimActivityVitalityRewardRequest req1 = ClaimActivityVitalityRewardRequest.newBuilder()
                .setRuleId(1).setConfigurationRevision(2).setPeriodId(PERIOD_START).setThresholdId(11)
                .setRequestId("req-t1").build();
        ClaimActivityVitalityRewardResponse resp1 = h.missions.vitality().claim(player, req1, nowMs);
        assertThat(resp1.getErrorMessage().getId()).isZero();
        assertThat(resp1.getClaimed()).isTrue();
        assertThat(resp1.getReplayed()).isFalse();
        assertThat(player.bags().bag(BagType.INVENTORY).total(ITEM_ID)).isEqualTo(5L);

        // 同 request_id 幂等重放 → 不重复入包
        ClaimActivityVitalityRewardResponse replay = h.missions.vitality().claim(player, req1, nowMs);
        assertThat(replay.getErrorMessage().getId()).isZero();
        assertThat(replay.getReplayed()).isTrue();
        assertThat(player.bags().bag(BagType.INVENTORY).total(ITEM_ID)).isEqualTo(5L);

        // 同 request_id 但冲突档位 → kInvalidParameter
        ClaimActivityVitalityRewardResponse conflict = h.missions.vitality().claim(player,
                req1.toBuilder().setThresholdId(12).build(), nowMs);
        assertThat(conflict.getErrorMessage().getId()).isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);

        // 新 request_id 重复领同一档 → kRewardAlreadyClaimed
        ClaimActivityVitalityRewardResponse dup = h.missions.vitality().claim(player,
                req1.toBuilder().setRequestId("req-t1-other").build(), nowMs);
        assertThat(dup.getErrorMessage().getId()).isEqualTo(RewardErrorTip.reward_error.kRewardAlreadyClaimed_VALUE);

        // 跨入下一周期：原 request_id 仍优先重放成功，而未领取的旧周期请求回 kRewardPeriodEnded(12001)
        long nextPeriodNow = PERIOD_START + PERIOD_DURATION + 5_000L;
        assertThat(h.missions.vitality().claim(player, req1, nextPeriodNow).getReplayed()).isTrue();
        ClaimActivityVitalityRewardResponse ended = h.missions.vitality().claim(player,
                req1.toBuilder().setThresholdId(12).setRequestId("req-old-unclaimed").build(), nextPeriodNow);
        assertThat(ended.getErrorMessage().getId()).isEqualTo(RewardErrorTip.reward_error.kRewardPeriodEnded_VALUE);

        // 模拟 70 条已结束周期领取记录：领新周期奖励前裁剪保留最新 64 条
        for (int i = 0; i < 70; i++) {
            player.activityVitality().addClaim(ActivityClaimRecordState.newBuilder()
                    .setRuleId(1).setConfigurationRevision(2)
                    .setPeriodId(PERIOD_START - PERIOD_DURATION)
                    .setThresholdId(100 + i)
                    .setRequestId("past-" + i)
                    .setRewardId(801)
                    .setClaimedAtMs(PERIOD_START - 1000L + i)
                    .build());
        }
        player.activityVitality().prunePastClaims(1, PERIOD_START + PERIOD_DURATION, ActivityVitalityService.KEPT_PAST_CLAIMS);
        long pastRemaining = player.activityVitality().claims().stream()
                .filter(c -> Long.compareUnsigned(c.getPeriodId(), PERIOD_START + PERIOD_DURATION) < 0)
                .count();
        assertThat(pastRemaining).isEqualTo(ActivityVitalityService.KEPT_PAST_CLAIMS);
        // PlayerState 持久化往返保留去重位图与领取记录
        PlayerState saved = WorldTestAccess.persistentState(player);
        assertThat(saved.hasActivityVitality()).isTrue();
        assertThat(saved.getActivityVitality().getCreditedMissions()).isNotEmpty();
        assertThat(saved.getActivityVitality().getClaimsCount()).isEqualTo(ActivityVitalityService.KEPT_PAST_CLAIMS);
    }
}
