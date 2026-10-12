package com.game.scene.mission;

import static com.game.scene.mission.MissionFixtures.edit;
import static com.game.scene.mission.MissionFixtures.mission;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.RunMode;
import com.game.player.store.state.ActiveMission;
import com.game.player.store.state.BagState;
import com.game.player.store.state.MissionState;
import com.game.player.store.state.PlayerState;
import com.game.proto.GetActivityListRequest;
import com.game.proto.GetActivityListResponse;
import com.game.proto.GetMissionListRequest;
import com.game.proto.GetMissionListResponse;
import com.game.proto.MessageContent;
import com.game.proto.MissionActionRequest;
import com.game.proto.MissionObjectiveInfo;
import com.game.proto.PlayerActivityInfo;
import com.game.proto.PlayerActivityStatus;
import com.game.proto.PlayerMissionInfo;
import com.game.proto.PlayerMissionStatus;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.BagType;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 193 / 194 / 195 任务与 190 活动列表走真实分发（ClientRequestHandler），配表用正式表；存盘往返走 SceneWorld 写回。 */
class MissionFeatureTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final String MISSIONS = "SceneMissionClientPlayer";
    private static final String ACTIVITIES = "SceneActivityClientPlayer";

    private final RecordingSink sink = new RecordingSink();
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final RecordingAssetAudit audit = new RecordingAssetAudit();
    private final ManualClock clock = new ManualClock();
    private final AtomicLong guidSeq = new AtomicLong(1L << 60);
    private MissionService service;
    private SceneWorld world;
    private ClientRequestHandler handler;

    private void start(PlayerState state) {
        start(state, MissionFixtures.shippedTables());
    }

    private void start(PlayerState state, MissionTables tables) {
        BagService bags = new BagService(BagTables.from(MissionFixtures.SHIPPED), count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = guidSeq.incrementAndGet();
            }
            return out;
        }, audit, GainAnomalyDetector.off(), SceneMetrics.noop());
        service = new MissionService(tables, bags, clock);
        FakeSceneTables sceneTables = new FakeSceneTables();
        world = new SceneWorld(sceneTables, Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet, clock,
                SceneMetrics.noop(), player -> {
                    bags.initializeOnLoad(player);
                    service.initializeOnLoad(player);
                }, PlayerSnapshots.NONE);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV,
                List.of(new MissionFeature(service), new ActivityFeature(service)));
        Scene scene = world.createScene(1);
        repo.put(new PlayerData(PLAYER, 1, 3, 1, "", 1, 0, Vec3.ORIGIN, state));
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
        assertThat(player()).isNotNull();
        sink.clear();
    }

    private ScenePlayer player() {
        return WorldTestAccess.player(world, LINK, SESSION);
    }

    private PlayerSave leave() {
        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true)
                .build());
        return repo.saves().getLast();
    }

    private GetMissionListResponse list() throws Exception {
        return GetMissionListResponse.parseFrom(call(MISSIONS, "GetMissionList", GetMissionListRequest.getDefaultInstance())
                .getSerializedMessage());
    }

    private GetMissionListResponse accept(int scope, int missionId) throws Exception {
        return GetMissionListResponse.parseFrom(call(MISSIONS, "AcceptMission",
                MissionActionRequest.newBuilder().setScope(scope).setMissionId(missionId).build()).getSerializedMessage());
    }

    private GetMissionListResponse claim(int scope, int missionId) throws Exception {
        return GetMissionListResponse.parseFrom(call(MISSIONS, "ClaimMissionReward",
                MissionActionRequest.newBuilder().setScope(scope).setMissionId(missionId).build()).getSerializedMessage());
    }

    private GetActivityListResponse activities() throws Exception {
        return GetActivityListResponse.parseFrom(call(ACTIVITIES, "GetActivityList",
                GetActivityListRequest.getDefaultInstance()).getSerializedMessage());
    }

    private static PlayerMissionInfo row(GetMissionListResponse response, int missionId) {
        return response.getMissionsList().stream().filter(info -> info.getMissionId() == missionId).findFirst()
                .orElseThrow();
    }

    private static PlayerActivityInfo activity(GetActivityListResponse response, int activityId) {
        return response.getActivitiesList().stream().filter(info -> info.getActivityId() == activityId).findFirst()
                .orElseThrow();
    }

    private static Map<Integer, String> reasons(GetMissionListResponse response) {
        return response.getMissionsList().stream()
                .collect(Collectors.toMap(PlayerMissionInfo::getMissionId, PlayerMissionInfo::getUnavailableReason));
    }

    private static void assertFailure(GetMissionListResponse response, int tip) {
        assertThat(response.getErrorMessage().getId()).isEqualTo(tip);
        assertThat(response.getMissionsList()).isEmpty();
        assertThat(response.getStatePersistent()).isFalse();
    }

    private long items() {
        return player().bags().bag(BagType.INVENTORY).total(1);
    }

    // ------------------------------------------------------------------ 列表

    @Test
    void 新号任务列表_全部17行_可接与不可接原因同基线() throws Exception {
        start(null);

        GetMissionListResponse response = list();

        assertThat(response.getErrorMessage().getId()).isZero();
        assertThat(response.hasErrorMessage()).isTrue();
        assertThat(response.getStatePersistent()).isTrue();
        assertThat(response.getMissionsList()).extracting(PlayerMissionInfo::getMissionId)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17);
        assertThat(response.getMissionsList()).allSatisfy(info -> {
            assertThat(info.getStatus()).isEqualTo(PlayerMissionStatus.PLAYER_MISSION_NOT_ACCEPTED);
            assertThat(info.getConfigured()).isTrue();
            assertThat(info.getCanClaim()).isFalse();
            assertThat(info.getName()).isEmpty();
            assertThat(info.getDescription()).isEmpty();
            assertThat(info.getScope()).isZero();
        });
        assertThat(response.getMissionsList()).filteredOn(PlayerMissionInfo::getCanAccept)
                .extracting(PlayerMissionInfo::getMissionId).containsExactly(4, 7, 8, 9, 12, 13, 14);
        Map<Integer, String> reasons = reasons(response);
        for (int id : new int[] {4, 7, 8, 9, 12, 13, 14}) {
            assertThat(reasons.get(id)).isEmpty();
        }
        for (int id : new int[] {1, 2, 6, 10, 11}) {
            assertThat(reasons.get(id)).as("mission %d", id).isEqualTo("任务所需玩法暂未开放");
        }
        for (int id : new int[] {3, 5}) {
            assertThat(reasons.get(id)).as("mission %d", id).isEqualTo("当前条件下暂不可接取");
        }
        for (int id : new int[] {15, 16, 17}) {
            assertThat(reasons.get(id)).as("mission %d", id).isEqualTo("活动尚未排期，敬请期待");
        }
        PlayerMissionInfo seven = row(response, 7);
        assertThat(seven.getMissionType()).isEqualTo(1);
        assertThat(seven.getMissionSubType()).isEqualTo(1);
        assertThat(seven.getRewardId()).isEqualTo(1);
        assertThat(seven.getAutoReward()).isTrue();
        assertThat(seven.getObjectivesList()).containsExactly(MissionObjectiveInfo.newBuilder()
                .setObjectiveIndex(0).setConditionId(1).setCategory(1).setTarget(8).build());
        assertThat(row(response, 12).getAutoReward()).isFalse();
        assertThat(row(response, 2).getObjectivesList()).extracting(MissionObjectiveInfo::getTarget)
                .containsExactly(1, 2, 1, 2, 2, 2);
        assertThat(row(response, 3).getObjectivesList()).isEmpty();
        assertThat(row(response, 14).getObjectivesList()).extracting(MissionObjectiveInfo::getCategory)
                .containsExactly(8, 8);
    }

    @Test
    void 新号活动列表_三个未排期活动_不带窗口_不可参与_带服务器时间() throws Exception {
        start(null);

        GetActivityListResponse response = activities();

        assertThat(response.getErrorMessage().getId()).isZero();
        assertThat(response.getServerTimeMs()).isEqualTo(clock.epochMillis());
        assertThat(response.getActivitiesList()).extracting(PlayerActivityInfo::getActivityId).containsExactly(15, 16, 17);
        assertThat(response.getActivitiesList()).allSatisfy(info -> {
            assertThat(info.getMissionId()).isEqualTo(info.getActivityId());
            assertThat(info.getRewardId()).isEqualTo(1);
            assertThat(info.getStatus()).isEqualTo(PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED);
            assertThat(info.getStartsAtMs()).isZero();
            assertThat(info.getEndsAtMs()).isZero();
            assertThat(info.getCanParticipate()).isFalse();
            assertThat(info.getUnavailableReason()).isEqualTo("活动尚未排期，敬请期待");
            assertThat(info.getName()).isEmpty();
        });
    }

    @Test
    void 只读列表不改状态_存档不带任务数据() throws Exception {
        start(null);
        list();
        activities();
        assertThat(leave().state().hasMission()).isFalse();
    }

    // ------------------------------------------------------------------ 接取 / 领奖

    @Test
    void 接取成功回完整列表_同类型的其它任务变5000原因_失败只回tip() throws Exception {
        start(null);

        GetMissionListResponse response = accept(0, 12);

        assertThat(response.getErrorMessage().getId()).isZero();
        assertThat(response.getStatePersistent()).isTrue();
        PlayerMissionInfo twelve = row(response, 12);
        assertThat(twelve.getStatus()).isEqualTo(PlayerMissionStatus.PLAYER_MISSION_ACTIVE);
        assertThat(twelve.getCanAccept()).isFalse();
        assertThat(twelve.getCanClaim()).isFalse();
        assertThat(twelve.getUnavailableReason()).isEqualTo("完成任务目标后领取奖励");
        assertThat(twelve.getObjectivesList()).containsExactly(MissionObjectiveInfo.newBuilder()
                .setObjectiveIndex(0).setConditionId(1).setCategory(1).setTarget(1).build());
        Map<Integer, String> reasons = reasons(response);
        for (int id : new int[] {1, 4, 6, 7, 8, 9, 10, 11}) {
            assertThat(reasons.get(id)).as("mission %d", id).isEqualTo("请先完成同类型任务");
        }
        assertThat(reasons.get(2)).isEqualTo("任务所需玩法暂未开放");
        assertThat(reasons.get(5)).isEqualTo("当前条件下暂不可接取");
        assertThat(response.getMissionsList()).filteredOn(PlayerMissionInfo::getCanAccept)
                .extracting(PlayerMissionInfo::getMissionId).containsExactly(13, 14);

        assertFailure(accept(0, 12), 5004);
        assertFailure(accept(0, 4), 5000);
        assertFailure(accept(1, 13), 1005);
        assertFailure(accept(0, 999), 1001);
        assertFailure(accept(0, 3), 1002);
        assertFailure(accept(0, 2), 1003);
        assertFailure(accept(0, 15), 1006);
        assertFailure(claim(0, 12), 5002);
        assertFailure(claim(1, 12), 1005);
        assertThat(row(list(), 12).getStatus()).isEqualTo(PlayerMissionStatus.PLAYER_MISSION_ACTIVE);
        assertThat(items()).isZero();
    }

    @Test
    void 完成后待领可领_领奖回已完成_物品到账一条任务奖励流水_重复领12000() throws Exception {
        start(null);
        accept(0, 12);
        service.onMonsterKilled(player(), 1, 1);

        PlayerMissionInfo ready = row(list(), 12);
        assertThat(ready.getStatus()).isEqualTo(PlayerMissionStatus.PLAYER_MISSION_CLAIMABLE);
        assertThat(ready.getCanClaim()).isTrue();
        assertThat(ready.getUnavailableReason()).isEmpty();
        assertThat(ready.getObjectives(0).getCompleted()).isTrue();
        assertThat(ready.getObjectives(0).getProgress()).as("完成后没有进行中条目，进度不伪造").isZero();

        GetMissionListResponse response = claim(0, 12);

        assertThat(response.getErrorMessage().getId()).isZero();
        assertThat(response.getStatePersistent()).isTrue();
        PlayerMissionInfo done = row(response, 12);
        assertThat(done.getStatus()).isEqualTo(PlayerMissionStatus.PLAYER_MISSION_COMPLETED);
        assertThat(done.getCanClaim()).isFalse();
        assertThat(done.getUnavailableReason()).isEqualTo("任务已完成");
        assertThat(items()).isEqualTo(4);
        assertThat(audit.items).filteredOn(item -> item.reason() == Reason.QUEST_REWARD).singleElement()
                .satisfies(item -> {
                    assertThat(item.quantity()).isEqualTo(4);
                    assertThat(item.extra()).isEqualTo("{\"mission_id\":12}");
                });
        assertFailure(claim(0, 12), 12000);
        assertThat(items()).isEqualTo(4);
    }

    @Test
    void 满包领奖6006_列表照样显示可领_保留领奖资格() throws Exception {
        start(PlayerState.newBuilder()
                .setBag(BagState.newBuilder().addCapacities(3))
                .setMission(MissionState.newBuilder().addCompletedIds(12).addClaimableIds(12))
                .build());

        assertFailure(claim(0, 12), 6006);

        assertThat(row(list(), 12).getCanClaim()).isTrue();
        assertThat(items()).isZero();
    }

    @Test
    void 待领却未完成_列表显示待领不可领_原因稍后重试_只读不改存档() throws Exception {
        MissionState saved = MissionState.newBuilder().addClaimableIds(12).build();
        start(PlayerState.newBuilder().setMission(saved).build());

        PlayerMissionInfo twelve = row(list(), 12);

        assertThat(twelve.getConfigured()).isTrue();
        assertThat(twelve.getStatus()).isEqualTo(PlayerMissionStatus.PLAYER_MISSION_CLAIMABLE);
        assertThat(twelve.getCanAccept()).isFalse();
        assertThat(twelve.getCanClaim()).as("领奖闸 1002：待领却未完成").isFalse();
        assertThat(twelve.getUnavailableReason()).isEqualTo("当前状态暂不可领奖，请稍后重试");
        assertThat(twelve.getObjectives(0).getCompleted()).isTrue();
        assertThat(items()).isZero();
        assertThat(leave().state().getMission()).as("只读列表不改任务状态").isEqualTo(saved);
    }

    // ------------------------------------------------------------------ 存档 / 异常数据

    @Test
    void 任务状态随离场写回_重新进场列表原样() throws Exception {
        start(null);
        accept(0, 12);
        accept(0, 13);
        service.onMonsterKilled(player(), 1, 1);
        GetMissionListResponse before = list();
        assertThat(row(before, 13).getObjectives(0).getProgress()).isEqualTo(1);

        PlayerSave save = leave();
        assertThat(save.state().getMission().getActiveList()).extracting(ActiveMission::getMissionId)
                .containsExactly(13);
        assertThat(save.state().getMission().getClaimableIdsList()).containsExactly(12);

        sink.clear();
        start(save.state());
        assertThat(list()).isEqualTo(before);
    }

    @Test
    void 表里没有的任务照样列出_排在后面_不可接不可领_不带类型与目标() throws Exception {
        start(PlayerState.newBuilder().setMission(MissionState.newBuilder()
                .addActive(ActiveMission.newBuilder().setMissionId(999_999).addProgress(5))
                .addCompletedIds(-2)
                .addClaimableIds(-2)).build());

        GetMissionListResponse response = list();

        assertThat(response.getMissionsCount()).isEqualTo(19);
        PlayerMissionInfo unknown = response.getMissions(17);
        assertThat(unknown.getMissionId()).isEqualTo(999_999);
        assertThat(unknown.getStatus()).isEqualTo(PlayerMissionStatus.PLAYER_MISSION_ACTIVE);
        assertThat(unknown.getConfigured()).isFalse();
        assertThat(unknown.getUnavailableReason()).isEqualTo("任务配置暂不可用");
        assertThat(unknown.getMissionType()).isZero();
        assertThat(unknown.getObjectivesList()).isEmpty();
        PlayerMissionInfo huge = response.getMissions(18);
        assertThat(huge.getMissionId()).as("uint32 任务号按无符号排序").isEqualTo(-2);
        assertThat(huge.getStatus()).isEqualTo(PlayerMissionStatus.PLAYER_MISSION_CLAIMABLE);
        assertThat(huge.getCanClaim()).isFalse();
    }

    @Test
    void 目标里条件缺失的格子只填序号与条件号_后面的格子不移位() throws Exception {
        start(PlayerState.newBuilder().setMission(MissionState.newBuilder()
                        .addActive(ActiveMission.newBuilder().setMissionId(7).addProgress(1).addProgress(44).addProgress(3)))
                        .build(),
                edit().mission(mission(7).clearConditionId().addConditionId(1).addConditionId(999_999).addConditionId(1))
                        .build());

        List<MissionObjectiveInfo> objectives = row(list(), 7).getObjectivesList();

        assertThat(objectives).containsExactly(
                MissionObjectiveInfo.newBuilder().setObjectiveIndex(0).setConditionId(1).setCategory(1).setTarget(8)
                        .setProgress(1).build(),
                MissionObjectiveInfo.newBuilder().setObjectiveIndex(1).setConditionId(999_999).build(),
                MissionObjectiveInfo.newBuilder().setObjectiveIndex(2).setConditionId(1).setCategory(1).setTarget(1)
                        .setProgress(3).setCompleted(true).build());
    }

    // ------------------------------------------------------------------ 开放中的活动

    @Test
    void 开放中的活动_可参与_接了以后去任务页_同类型占用_玩法未开放() throws Exception {
        start(null, edit().openSchedule(15).openSchedule(16).openSchedule(17)
                .mission(mission(16).clearConditionId().addConditionId(3))
                .build());

        GetActivityListResponse fresh = activities();
        PlayerActivityInfo fifteen = activity(fresh, 15);
        assertThat(fifteen.getStatus()).isEqualTo(PlayerActivityStatus.PLAYER_ACTIVITY_OPEN);
        assertThat(fifteen.getCanParticipate()).isTrue();
        assertThat(fifteen.getUnavailableReason()).isEmpty();
        assertThat(fifteen.getStartsAtMs()).isEqualTo(1);
        assertThat(fifteen.getEndsAtMs()).isEqualTo(Long.MAX_VALUE);
        assertThat(activity(fresh, 16).getUnavailableReason()).isEqualTo("活动所需玩法或当前状态暂不满足参与条件");
        assertThat(row(list(), 16).getUnavailableReason()).as("开放中但接取闸 1003：任务页原因是空串（同基线）").isEmpty();
        assertThat(row(list(), 15).getCanAccept()).isTrue();

        assertThat(accept(0, 15).getErrorMessage().getId()).isZero();
        assertThat(accept(0, 17).getErrorMessage().getId()).isZero();
        assertFailure(accept(0, 16), 5000);

        GetActivityListResponse after = activities();
        assertThat(activity(after, 15).getCanParticipate()).isFalse();
        assertThat(activity(after, 15).getUnavailableReason()).isEqualTo("请在任务页查看活动进度");
        assertThat(activity(after, 17).getUnavailableReason()).isEqualTo("请在任务页查看活动进度");
        GetMissionListResponse list = list();
        assertThat(row(list, 15).getStatus()).isEqualTo(PlayerMissionStatus.PLAYER_MISSION_ACTIVE);
    }

    @Test
    void 开放中的活动被同类型占用_活动页与任务页各自的原因() throws Exception {
        start(null, edit().openSchedule(16).openSchedule(17).build());
        assertThat(accept(0, 16).getErrorMessage().getId()).isZero();

        assertThat(activity(activities(), 17).getUnavailableReason()).isEqualTo("请先完成同类型活动任务");
        assertThat(row(list(), 17).getUnavailableReason()).as("任务页的类型占用先于活动分支").isEqualTo("请先完成同类型任务");
    }

    private MessageContent call(String service, String method, Message request) {
        int messageId = Contracts.REGISTRY.requireId(service, method);
        int before = sink.to(LINK, SESSION).size();
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(SESSION)
                .setPlayerId(PLAYER)
                .setMessageId(messageId)
                .setBody(request.toByteString())
                .setRequestId(7)
                .build());
        List<MessageContent> sent = sink.to(LINK, SESSION);
        assertThat(sent).hasSize(before + 1);
        MessageContent reply = sent.getLast();
        assertThat(reply.getMessageId()).isEqualTo(messageId);
        return reply;
    }
}
