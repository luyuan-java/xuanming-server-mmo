package com.game.battle.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.api.proto.DevGatherMember;
import com.game.api.proto.DevGatherMode;
import com.game.api.proto.DevGatherPrepareResult;
import com.game.api.proto.DevGatherRequest;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.discovery.location.SceneAssetLocator.Failure;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.NoHolder;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import com.game.discovery.proto.PlayerLocation;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.TipInfoMessage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * dev gather 的编排（scene-battle-spec §7.18、§13.5 {@code DevGatherControllerTest} 的纯逻辑部分）：按 player_id 升序逐人备战、第 k 人失败按升序取消
 * 前 k − 1 人、结局不明的那一人也取消、零副作用的拒绝不取消、PREPARE_ONLY 不建房、CREATE 用 scene 的快照（team_index 改写）建 DEV_GATHER 房、
 * 不可分配 / 业务错误全部取消、结局不明先 destroy 再取消、指纹不一致不建房。
 */
class DevGatherTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long BATTLE = 777;
    private static final int NODE = 9;

    /** scene 侧假实现：按玩家编排定位与备战结局，记下全部调用（带全局序号）。 */
    static final class FakeScenes implements DevGather.Scenes {
        final Map<Long, Resolution> where = new HashMap<>();
        final Map<Long, CompletableFuture<SceneBattleReply>> prepareReplies = new HashMap<>();
        CompletableFuture<SceneBattleReply> cancelReply = CompletableFuture.completedFuture(handled(null));
        final List<String> calls = new ArrayList<>();
        final List<PrepareBattleRequest> prepares = new ArrayList<>();
        final List<CancelBattlePrepareRequest> cancels = new ArrayList<>();
        final List<String> targets = new ArrayList<>();

        FakeScenes online(long playerId, int node, String instance) {
            where.put(playerId, new Found(PlayerLocation.newBuilder().setPlayerId(playerId).setZoneId(1).setSceneNodeId(node).build(),
                    new SceneAssetEndpoint(1, node, instance, "127.0.0.1", 21100 + node)));
            prepareReplies.put(playerId, CompletableFuture.completedFuture(handled(ok(playerId, "fp"))));
            return this;
        }

        @Override
        public CompletableFuture<Resolution> locate(long playerId) {
            calls.add("locate:" + playerId);
            return CompletableFuture.completedFuture(where.getOrDefault(playerId, new NoHolder(ResolveResult.NOT_ONLINE)));
        }

        @Override
        public CompletableFuture<SceneBattleReply> prepare(SceneAssetEndpoint endpoint, SceneBattleCall call, Duration timeout) {
            calls.add("prepare:" + call.getPlayerId());
            targets.add(call.getTargetInstanceId());
            prepares.add(parse(PrepareBattleRequest::parseFrom, call));
            return prepareReplies.get(call.getPlayerId());
        }

        @Override
        public CompletableFuture<SceneBattleReply> cancel(SceneAssetEndpoint endpoint, SceneBattleCall call, Duration timeout) {
            calls.add("cancel:" + call.getPlayerId());
            targets.add(call.getTargetInstanceId());
            cancels.add(parse(CancelBattlePrepareRequest::parseFrom, call));
            return cancelReply;
        }
    }

    /** 控制面假实现。 */
    static final class FakePlane implements DevGather.Plane {
        CompletableFuture<CreateBattleResult> createReply = CompletableFuture.completedFuture(admitted(0));
        final List<CreateBattleRequest> creates = new ArrayList<>();
        final List<DestroyBattleRequest> destroys = new ArrayList<>();
        final List<String> calls;

        FakePlane(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public int battleNodeId() {
            return NODE;
        }

        @Override
        public CompletableFuture<CreateBattleResult> create(CreateBattleRequest request) {
            calls.add("create");
            creates.add(request);
            return createReply;
        }

        @Override
        public CompletableFuture<?> destroy(DestroyBattleRequest request) {
            calls.add("destroy");
            destroys.add(request);
            return CompletableFuture.completedFuture(null);
        }
    }

    private interface BytesParser<T> {
        T parse(byte[] bytes) throws IOException;
    }

    private static <T> T parse(BytesParser<T> parser, SceneBattleCall call) {
        try {
            return parser.parse(call.getBody().toByteArray());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static SceneBattleReply handled(PrepareBattleResponse body) {
        SceneBattleReply.Builder reply = SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        if (body != null) {
            reply.setBody(body.toByteString());
        }
        return reply.build();
    }

    static PrepareBattleResponse ok(long playerId, String fingerprint) {
        return PrepareBattleResponse.newBuilder()
                .setSnapshot(BattlePlayerSnapshot.newBuilder().setPlayerId(playerId).setPlayerName("p" + playerId).setLevel(3)
                        .setTableFingerprint(fingerprint)
                        .setRouting(BattleRouting.newBuilder().setGateInstanceId("gate").setSceneInstanceId("scene")))
                .setTableFingerprint(fingerprint)
                .build();
    }

    static PrepareBattleResponse rejected(int tip) {
        return PrepareBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(tip)).build();
    }

    static CreateBattleResult admitted(int tip) {
        CreateBattleResponse.Builder response = CreateBattleResponse.newBuilder().setBattleId(BATTLE);
        if (tip != 0) {
            response.setErrorMessage(TipInfoMessage.newBuilder().setId(tip));
        }
        return CreateBattleResult.newBuilder().setAdmission(BattleAdmission.BATTLE_ADMISSION_ADMITTED)
                .setResponse(response.build().toByteString()).build();
    }

    private static DevGatherRequest request(DevGatherMode mode, long... members) {
        DevGatherRequest.Builder request = DevGatherRequest.newBuilder().setMode(mode).setBattleId(BATTLE).setMatchMode(4)
                .setBattleConfigId(1).setSeed(42).setDeadlineMs(NOW + 300_000).setPrepareDeadlineMs(NOW + 60_000);
        for (int i = 0; i < members.length; i++) {
            request.addMembers(DevGatherMember.newBuilder().setPlayerId(members[i]).setTeamIndex(i % 2));
        }
        return request.build();
    }

    private final FakeScenes scenes = new FakeScenes();
    private final FakePlane plane = new FakePlane(scenes.calls);
    private final DevGather gather = new DevGather(scenes, () -> NOW);

    @Test
    void 只备战_按player_id无符号升序逐人备战_回快照_不建房() {
        scenes.online(30, 1, "s1").online(10, 2, "s2").online(-5L, 1, "s1");

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_PREPARE_ONLY, 30, -5L, 10), plane);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.response().getFailure()).isEmpty();
        assertThat(scenes.calls).containsExactly("locate:10", "prepare:10", "locate:30", "prepare:30", "locate:-5", "prepare:-5");
        assertThat(outcome.response().getPrepareResultsList()).extracting(DevGatherPrepareResult::getPlayerId).containsExactly(10L, 30L, -5L);
        DevGatherPrepareResult first = outcome.response().getPrepareResults(0);
        assertThat(first.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(first.getSceneNodeId()).isEqualTo(2);
        assertThat(first.getSceneInstanceId()).isEqualTo("s2");
        assertThat(first.getResponse()).isEqualTo(ok(10, "fp").toByteString());
        assertThat(plane.creates).isEmpty();
        assertThat(scenes.cancels).isEmpty();
        assertThat(scenes.targets).containsExactly("s2", "s1", "s1");
    }

    @Test
    void 备战请求_带本节点号与两个期限() {
        scenes.online(10, 2, "s2");

        gather.gather(request(DevGatherMode.DEV_GATHER_PREPARE_ONLY, 10), plane);

        assertThat(scenes.prepares).containsExactly(PrepareBattleRequest.newBuilder().setPlayerId(10).setBattleId(BATTLE)
                .setBattleNodeId(NODE).setDeadlineMs(NOW + 300_000).setPrepareDeadlineMs(NOW + 60_000).build());
    }

    @Test
    void 第k人备战被拒_按升序取消前k减1人_被拒的人零副作用不取消_422() {
        scenes.online(10, 1, "s1").online(20, 2, "s2").online(30, 1, "s1");
        scenes.prepareReplies.put(30L, CompletableFuture.completedFuture(handled(rejected(1006))));

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_CREATE, 30, 20, 10), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.response().getFailure()).contains("tip=1006").contains("30");
        assertThat(scenes.calls).containsExactly("locate:10", "prepare:10", "locate:20", "prepare:20", "locate:30", "prepare:30",
                "cancel:10", "cancel:20");
        assertThat(scenes.cancels).allSatisfy(c -> assertThat(c.getBattleId()).isEqualTo(BATTLE));
        assertThat(outcome.response().getCancelledPlayerIdsList()).containsExactly(10L, 20L);
        assertThat(prepareResponseOf(outcome.response().getPrepareResults(2)).getErrorMessage().getId()).isEqualTo(1006);
        assertThat(plane.creates).as("有人失败就不建房").isEmpty();
    }

    @Test
    void 定位不到_不发备战_取消前面的人() {
        scenes.online(10, 1, "s1");

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_CREATE, 10, 20), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.response().getFailure()).contains("not_online");
        assertThat(scenes.calls).containsExactly("locate:10", "prepare:10", "locate:20", "cancel:10");
        assertThat(outcome.response().getPrepareResults(1).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_STATUS_UNSPECIFIED);
    }

    @Test
    void 定位故障_同样失败() {
        scenes.where.put(10L, new Failure("redis down"));

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_PREPARE_ONLY, 10), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.response().getFailure()).contains("redis down");
        assertThat(scenes.cancels).isEmpty();
    }

    @Test
    void 传输失败_结局不明_这一人也补发取消() {
        scenes.online(10, 1, "s1").online(20, 2, "s2");
        scenes.prepareReplies.put(20L, CompletableFuture.failedFuture(new IllegalStateException("连不上")));

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_CREATE, 10, 20), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(scenes.calls).containsSubsequence("prepare:20", "cancel:10", "cancel:20");
        assertThat(outcome.response().getCancelledPlayerIdsList()).containsExactly(10L, 20L);
        assertThat(scenes.targets).last().isEqualTo("s2");
    }

    @Test
    void UNSPECIFIED应答按结局不明_补发取消() {
        scenes.online(10, 1, "s1");
        scenes.prepareReplies.put(10L, CompletableFuture.completedFuture(SceneBattleReply.getDefaultInstance()));

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_PREPARE_ONLY, 10), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.response().getCancelledPlayerIdsList()).containsExactly(10L);
    }

    @Test
    void NOT_HERE与OVERLOADED零副作用_不取消这一人() {
        scenes.online(10, 1, "s1").online(20, 2, "s2");
        scenes.prepareReplies.put(20L, CompletableFuture.completedFuture(SceneBattleReply.newBuilder()
                .setStatus(SceneBattleStatus.SCENE_BATTLE_NOT_HERE).build()));

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_CREATE, 10, 20), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.response().getCancelledPlayerIdsList()).containsExactly(10L);
        assertThat(outcome.response().getPrepareResults(1).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_NOT_HERE);
    }

    @Test
    void 建房_用scene的快照_team_index按请求改写_指纹取全员一致的那个() {
        scenes.online(20, 1, "s1").online(10, 2, "s2");

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_CREATE, 20, 10), plane);

        assertThat(outcome.ok()).isTrue();
        assertThat(scenes.calls).containsExactly("locate:10", "prepare:10", "locate:20", "prepare:20", "create");
        CreateBattleRequest create = plane.creates.get(0);
        assertThat(create.getBattleId()).isEqualTo(BATTLE);
        assertThat(create.getBattleConfigId()).isEqualTo(1);
        assertThat(create.getMatchMode()).isEqualTo(4);
        assertThat(create.getSeed()).isEqualTo(42);
        assertThat(create.getDeadlineMs()).isEqualTo(NOW + 300_000);
        assertThat(create.getCreatedAtMs()).isEqualTo(NOW);
        assertThat(create.getTableFingerprint()).isEqualTo("fp");
        assertThat(create.hasActivityContext()).as("dev gather 不接受活动上下文").isFalse();
        // 请求里 20 在 team 0、10 在 team 1；快照按 player_id 升序
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getPlayerId).containsExactly(10L, 20L);
        assertThat(create.getPlayers(0).getTeamIndex()).isEqualTo(1);
        assertThat(create.getPlayers(1).getTeamIndex()).isZero();
        assertThat(create.getPlayers(0).getPlayerName()).isEqualTo("p10");
        assertThat(outcome.response().getCreateResult()).isEqualTo(admitted(0));
        assertThat(scenes.cancels).isEmpty();
    }

    @Test
    void 建房不可分配_全部取消_不destroy() {
        scenes.online(10, 1, "s1").online(20, 2, "s2");
        plane.createReply = CompletableFuture.completedFuture(CreateBattleResult.newBuilder()
                .setAdmission(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE).setReason("closed").build());

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_CREATE, 10, 20), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.response().getFailure()).contains("closed");
        assertThat(scenes.calls).endsWith("create", "cancel:10", "cancel:20");
        assertThat(plane.destroys).isEmpty();
        assertThat(outcome.response().getCreateResult().getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
    }

    @Test
    void 建房业务错误_全部取消_不destroy() {
        scenes.online(10, 1, "s1");
        plane.createReply = CompletableFuture.completedFuture(admitted(1005));

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_CREATE, 10), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.response().getFailure()).contains("tip=1005");
        assertThat(scenes.calls).endsWith("create", "cancel:10");
        assertThat(plane.destroys).isEmpty();
    }

    @Test
    void 建房结局不明_先destroy再取消() {
        scenes.online(10, 1, "s1");
        plane.createReply = CompletableFuture.failedFuture(new IllegalStateException("逻辑线程已停"));

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_CREATE, 10), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(scenes.calls).endsWith("create", "destroy", "cancel:10");
        assertThat(plane.destroys.get(0).getBattleId()).isEqualTo(BATTLE);
    }

    @Test
    void 指纹不一致_不建房_全部取消() {
        scenes.online(10, 1, "s1").online(20, 2, "s2");
        scenes.prepareReplies.put(20L, CompletableFuture.completedFuture(handled(ok(20, "other"))));

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_CREATE, 10, 20), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.response().getFailure()).contains("指纹不一致");
        assertThat(plane.creates).isEmpty();
        assertThat(outcome.response().getCancelledPlayerIdsList()).containsExactly(10L, 20L);
    }

    @Test
    void 备战成功但快照缺失_按失败处理并取消() {
        scenes.online(10, 1, "s1");
        scenes.prepareReplies.put(10L, CompletableFuture.completedFuture(handled(PrepareBattleResponse.newBuilder()
                .setTableFingerprint("fp").build())));

        DevGather.Outcome outcome = gather.gather(request(DevGatherMode.DEV_GATHER_PREPARE_ONLY, 10), plane);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.response().getCancelledPlayerIdsList()).containsExactly(10L);
    }

    @Test
    void 请求形状校验() {
        assertThat(DevGather.invalidReason(request(DevGatherMode.DEV_GATHER_CREATE, 1, 2))).isNull();
        assertThat(DevGather.invalidReason(request(DevGatherMode.DEV_GATHER_MODE_UNSPECIFIED, 1))).contains("mode");
        assertThat(DevGather.invalidReason(request(DevGatherMode.DEV_GATHER_CREATE, 1).toBuilder().setBattleId(0).build()))
                .contains("battle_id");
        assertThat(DevGather.invalidReason(request(DevGatherMode.DEV_GATHER_CREATE, 1).toBuilder().setDeadlineMs(0).build()))
                .contains("deadline_ms");
        assertThat(DevGather.invalidReason(request(DevGatherMode.DEV_GATHER_CREATE))).contains("members");
        assertThat(DevGather.invalidReason(request(DevGatherMode.DEV_GATHER_CREATE, 1, 1))).contains("重复");
        assertThat(DevGather.invalidReason(request(DevGatherMode.DEV_GATHER_CREATE, 0))).contains("player_id 为 0");
        assertThat(DevGather.invalidReason(request(DevGatherMode.DEV_GATHER_CREATE, 1).toBuilder()
                .setMembers(0, DevGatherMember.newBuilder().setPlayerId(1).setTeamIndex(2)).build())).contains("team_index");
        assertThat(DevGather.invalidReason(request(DevGatherMode.DEV_GATHER_CREATE, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))).contains("超过");
    }

    @Test
    void 取消_定位到就发往那个节点_204() {
        scenes.online(10, 2, "s2");

        DevGather.CancelOutcome outcome = gather.cancelPrepare(CancelBattlePrepareRequest.newBuilder().setPlayerId(10).setBattleId(BATTLE)
                .build());

        assertThat(outcome.status()).isEqualTo(204);
        assertThat(scenes.cancels).containsExactly(CancelBattlePrepareRequest.newBuilder().setPlayerId(10).setBattleId(BATTLE).build());
        assertThat(scenes.targets).containsExactly("s2");
    }

    @Test
    void 取消_没有持有者422_定位故障503_传输失败503_参数为0则400() {
        assertThat(gather.cancelPrepare(CancelBattlePrepareRequest.newBuilder().setPlayerId(10).setBattleId(BATTLE).build()).status())
                .isEqualTo(422);
        scenes.where.put(11L, new Failure("redis down"));
        assertThat(gather.cancelPrepare(CancelBattlePrepareRequest.newBuilder().setPlayerId(11).setBattleId(BATTLE).build()).status())
                .isEqualTo(503);
        scenes.online(12, 1, "s1");
        scenes.cancelReply = CompletableFuture.failedFuture(new IllegalStateException("超时"));
        assertThat(gather.cancelPrepare(CancelBattlePrepareRequest.newBuilder().setPlayerId(12).setBattleId(BATTLE).build()).status())
                .isEqualTo(503);
        assertThat(gather.cancelPrepare(CancelBattlePrepareRequest.newBuilder().setPlayerId(12).build()).status()).isEqualTo(400);
    }

    @Test
    void 剩余预算取上限与截止的较小者() {
        assertThat(DevGather.remaining(System.nanoTime() - 1, Duration.ofSeconds(5))).isZero();
        assertThat(DevGather.remaining(System.nanoTime() + Duration.ofSeconds(60).toNanos(), Duration.ofSeconds(5)))
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(DevGather.remaining(System.nanoTime() + Duration.ofSeconds(2).toNanos(), Duration.ofSeconds(5)))
                .isLessThanOrEqualTo(Duration.ofSeconds(2)).isPositive();
        assertThat(DevGather.SCENE_CALL_TIMEOUT).as("必须大于 scene 侧最坏 4.2 s（§7.3）").isGreaterThan(Duration.ofMillis(4200));
    }

    private static PrepareBattleResponse prepareResponseOf(DevGatherPrepareResult result) {
        try {
            return PrepareBattleResponse.parseFrom(result.getResponse());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
