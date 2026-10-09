package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.assertLocation;
import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientForward;
import com.game.common.RunMode;
import com.game.proto.MessageContent;
import com.game.proto.PlayerSkillComp;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.EnterSceneS2C;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoRequest;
import com.game.proto.SceneInfoS2C;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientRequestHandlerTest {

    private static final long LINK = 1;
    private static final SceneMessageIds IDS = Contracts.IDS;

    private RecordingSink sink;
    private FakePlayerRepository repo;
    private SceneWorld world;
    private ClientRequestHandler handler;
    private Scene scene1;
    private Scene scene2;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        repo = new FakePlayerRepository();
        AtomicLong ids = new AtomicLong(5000);
        FakeSceneTables tables = new FakeSceneTables();
        world = new SceneWorld(tables, IDS, sink, repo, ids::incrementAndGet, new ManualClock(), SceneMetrics.noop());
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS);
        scene1 = world.createScene(1);
        scene2 = world.createScene(2);
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1002, 1);
        enter(11, 1001, scene1);
    }

    @Test
    void ListSkills_应答回显请求号_技能为配表并集_error_message在且为0() throws Exception {
        forward(11, 1001, IDS.listSkills(), ListSkillsRequest.getDefaultInstance(), 42);

        MessageContent reply = sink.to(LINK, 11).getLast();
        assertThat(reply.getMessageId()).isEqualTo(77);
        assertThat(reply.getId()).isEqualTo(42L);
        assertThat(reply.hasErrorMessage()).isFalse();
        // 线上形态：error_message 是字段 1 的空子消息（0a 00），与基线一致。
        assertThat(reply.getSerializedMessage().substring(0, 2).toByteArray())
                .containsExactly((byte) 0x0a, (byte) 0x00);

        ListSkillsResponse response = ListSkillsResponse.parseFrom(reply.getSerializedMessage());
        assertThat(response.hasErrorMessage()).isTrue();
        assertThat(response.getErrorMessage().getId()).isZero();
        assertThat(response.hasSkillList()).isTrue();
        assertThat(response.getSkillList().getSkillListList())
                .extracting(PlayerSkillComp::getSkillTableId).containsExactly(1, 2, 13);
        assertThat(response.getSkillList().getSkillListList())
                .extracting(PlayerSkillComp::getId).containsOnly(0L);
    }

    @Test
    void EnterScene_同配置_受理且不重发79() throws Exception {
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(1, 0, 0), 9);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(63);
        assertThat(toA.get(0).getId()).isEqualTo(9L);
        EnterSceneC2SResponse response = EnterSceneC2SResponse.parseFrom(toA.get(0).getSerializedMessage());
        assertThat(response.hasErrorMessage()).isTrue();
        assertThat(response.getErrorMessage().getId()).isZero();
        assertThat(entitySceneOf(11)).isSameAs(scene1);
    }

    @Test
    void EnterScene_参数全0回3005_指定当前场景回3008_目标不在本节点回3023_镜像号不在表里回3005() throws Exception {
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(0, 0, 0), 1);
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(1, scene1.sceneId(), 0), 2);
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(9, 0, 0), 3);
        // 镜像分支（批次 5.3 D7）：mirror_config_id 4 不在 Mirror 表 → 3005，不进取号
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(0, 0, 4), 4);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(63, 63, 63, 63);
        assertThat(toA).extracting(m -> tipOf(m)).containsExactly(3005, 3008, 3023, 3005);
        assertThat(entitySceneOf(11)).isSameAs(scene1);
    }

    @Test
    void EnterScene_切到本节点另一张地图_先回应答_旧视野收到51_本人收到79和21且落到出生点() throws Exception {
        enter(12, 1002, scene1);
        long entityA = entityOf(11);
        sink.clear();

        forward(11, 1001, IDS.enterScene(), enterSceneRequest(2, 0, 0), 5);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(63, 79, 21);
        assertThat(tipOf(toA.get(0))).isZero();
        SceneInfoComp info = EnterSceneS2C.parseFrom(toA.get(1).getSerializedMessage()).getSceneInfo();
        assertThat(info.getSceneConfigId()).isEqualTo(2);
        assertThat(info.getSceneId()).isEqualTo(scene2.sceneId());
        ActorCreateS2C self = ActorCreateS2C.parseFrom(toA.get(2).getSerializedMessage());
        assertThat(self.getEntity()).isEqualTo(entityA);
        assertLocation(self, 170, 220, 0);

        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(ActorDestroyS2C.parseFrom(sink.to(LINK, 12).get(0).getSerializedMessage()).getEntity())
                .isEqualTo(entityA);
        assertThat(scene1.playerCount()).isEqualTo(1);
        assertThat(scene2.playerCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 63 与主世界频道（批次 5.1，scene-channels-spec §4.12）

    @Test
    void EnterScene_只带当前地图_同图有更空的频道_跳过去发79_坐标保留() throws Exception {
        Scene channel2 = world.createScene(1);
        scene1.relocate(world.playerBySession(new SessionKey(LINK, 11)), new Vec3(120, 130, 0));

        forward(11, 1001, IDS.enterScene(), enterSceneRequest(1, 0, 0), 6);

        // 当前频道的人数含自己（1）> 兄弟（0）：同基线「全 zone 该图频道预占最少者」，只是限本节点（D17）
        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(63, 79, 21);
        assertThat(tipOf(toA.get(0))).isZero();
        assertThat(EnterSceneS2C.parseFrom(toA.get(1).getSerializedMessage()).getSceneInfo().getSceneId())
                .isEqualTo(channel2.sceneId());
        assertLocation(ActorCreateS2C.parseFrom(toA.get(2).getSerializedMessage()), 120, 130, 0);
        assertThat(entitySceneOf(11)).isSameAs(channel2);
    }

    @Test
    void EnterScene_只带当前地图_并列留在原地_不发79() {
        Scene channel2 = world.createScene(1);
        enter(12, 1002, channel2);

        forward(11, 1001, IDS.enterScene(), enterSceneRequest(1, 0, 0), 7);

        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(63);
        assertThat(tipOf(sink.to(LINK, 11).get(0))).isZero();
        assertThat(entitySceneOf(11)).isSameAs(scene1);
    }

    @Test
    void EnterScene_只带当前地图_当前在排空_排除自己换到兄弟_没有兄弟回3023() {
        Scene channel2 = world.createScene(1);
        enter(12, 1002, channel2);
        enter(13, 1003, channel2);
        scene1.setDraining(true);

        forward(11, 1001, IDS.enterScene(), enterSceneRequest(1, 0, 0), 8);
        assertThat(sink.messageIdsTo(LINK, 11)).as("兄弟有 2 人也换：排空中的当前频道不参与").containsExactly(63, 79, 21, 47);
        assertThat(entitySceneOf(11)).isSameAs(channel2);
        sink.clear();

        channel2.setDraining(true);
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(1, 0, 0), 9);
        assertThat(tipOf(sink.to(LINK, 11).get(0))).isEqualTo(3023);
        assertThat(entitySceneOf(11)).isSameAs(channel2);
    }

    @Test
    void EnterScene_指定排空中的频道回3023_指定承载中的同图频道放行() {
        Scene channel2 = world.createScene(1);
        channel2.setDraining(true);

        forward(11, 1001, IDS.enterScene(), enterSceneRequest(1, channel2.sceneId(), 0), 10);
        assertThat(tipOf(sink.to(LINK, 11).get(0))).as("D11：基线放行（B5）").isEqualTo(3023);
        assertThat(entitySceneOf(11)).isSameAs(scene1);
        sink.clear();

        channel2.setDraining(false);
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(0, channel2.sceneId(), 0), 11);
        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(63, 79, 21);
        assertThat(entitySceneOf(11)).isSameAs(channel2);
    }

    @Test
    void EnterScene_只带别的地图_取人数最少的_排空中的不选() throws Exception {
        Scene map2b = world.createScene(2);
        Scene map2c = world.createScene(2);
        enter(12, 1002, scene2);
        map2b.setDraining(true);

        forward(11, 1001, IDS.enterScene(), enterSceneRequest(2, 0, 0), 12);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(EnterSceneS2C.parseFrom(toA.get(1).getSerializedMessage()).getSceneInfo().getSceneId())
                .isEqualTo(map2c.sceneId());
    }

    @Test
    void SceneInfoC2S_不回43_改推31() throws Exception {
        forward(11, 1001, IDS.sceneInfoC2S(), SceneInfoRequest.getDefaultInstance(), 3);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(31);
        assertThat(toA.get(0).getId()).isZero();
        SceneInfoS2C push = SceneInfoS2C.parseFrom(toA.get(0).getSerializedMessage());
        assertThat(push.getSceneInfoList()).containsExactly(scene1.info());
    }

    @Test
    void 未实现的方法_回1006且回显请求号() throws Exception {
        int getCurrencyList = Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");

        forward(11, 1001, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 77);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).hasSize(1);
        assertThat(toA.get(0).getMessageId()).isEqualTo(getCurrencyList);
        assertThat(toA.get(0).getId()).isEqualTo(77L);
        GetCurrencyListResponse response = GetCurrencyListResponse.parseFrom(toA.get(0).getSerializedMessage());
        assertThat(response.getErrorMessage().getId()).isEqualTo(1006);
    }

    @Test
    void 功能注册_重复_请求类型不符_非scene客户端服务_启动即失败() {
        FakeSceneTables tables = new FakeSceneTables();
        SceneFeature duplicate = r -> r.on("SceneSkillClientPlayer", "ListSkills", ListSkillsRequest.class, (call, req) -> { });
        SceneFeature wrongType = r -> r.on("SceneCurrencyClientPlayer", "GetCurrencyList", ListSkillsRequest.class,
                (call, req) -> { });
        SceneFeature notScene = r -> r.on("ClientPlayerLogin", "Login", Message.class, (call, req) -> { });

        for (SceneFeature feature : List.of(duplicate, wrongType, notScene)) {
            assertThatThrownBy(() -> new ClientRequestHandler(world, Contracts.REGISTRY, IDS, RunMode.DEV,
                    List.of(feature))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 处理器没回_抛异常_回两次_应答类型不符_客户端都恰好收到一个应答() throws Exception {
        int getCurrencyList = Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
        AtomicInteger mode = new AtomicInteger();
        GetCurrencyListResponse ok = GetCurrencyListResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(0)).build();
        SceneFeature buggy = r -> r.on("SceneCurrencyClientPlayer", "GetCurrencyList", GetCurrencyListRequest.class,
                (call, req) -> {
                    switch (mode.get()) {
                        case 0 -> { }
                        case 1 -> throw new IllegalArgumentException("boom");
                        case 2 -> {
                            call.reply(ok);
                            call.reply(ok);
                        }
                        default -> call.reply(ListSkillsResponse.getDefaultInstance());
                    }
                });
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS, RunMode.DEV,
                List.of(buggy));

        for (int i = 0; i < 4; i++) {
            mode.set(i);
            forward(11, 1001, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 100 + i);
        }

        List<MessageContent> replies = sink.to(LINK, 11);
        assertThat(replies).extracting(MessageContent::getId).containsExactly(100L, 101L, 102L, 103L);
        assertThat(replies).extracting(r -> GetCurrencyListResponse.parseFrom(r.getSerializedMessage())
                .getErrorMessage().getId()).containsExactly(1006, 1006, 0, 1006);
    }

    /**
     * 延迟应答（批次 5.4 先行件）：处理器 {@code defer()} 之后返回，分发处<b>不补 1006</b>；应答稍后经 {@link DeferredReply} 回，
     * 恰好一个、回显原请求号。期间来的另一条请求照常同步回，不受影响。
     */
    @Test
    void 已延迟的调用_处理器返回后不补1006_稍后经延迟应答回_恰好一个应答() throws Exception {
        int getCurrencyList = Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
        List<DeferredReply> held = new java.util.ArrayList<>();
        SceneFeature deferring = r -> r.on("SceneCurrencyClientPlayer", "GetCurrencyList", GetCurrencyListRequest.class,
                (call, req) -> held.add(call.defer()));
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS, RunMode.DEV, List.of(deferring));

        forward(11, 1001, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 500);

        assertThat(held).hasSize(1);
        assertThat(sink.to(LINK, 11)).as("已延迟：处理器返回时没有应答、也没有被补 1006").isEmpty();
        assertThat(held.get(0).pending()).isTrue();

        forward(11, 1001, IDS.listSkills(), ListSkillsRequest.getDefaultInstance(), 501);
        assertThat(sink.to(LINK, 11)).extracting(MessageContent::getId).as("延迟期间别的请求照常同步回").containsExactly(501L);

        GetCurrencyListResponse refused = GetCurrencyListResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(3026)).build();
        assertThat(held.get(0).reply(refused)).isTrue();

        List<MessageContent> replies = sink.to(LINK, 11);
        assertThat(replies).extracting(MessageContent::getId).containsExactly(501L, 500L);
        MessageContent late = replies.get(1);
        assertThat(late.getMessageId()).isEqualTo(getCurrencyList);
        assertThat(GetCurrencyListResponse.parseFrom(late.getSerializedMessage()).getErrorMessage().getId()).isEqualTo(3026);
        assertThat(held.get(0).reply(refused)).as("第二次回：不发").isFalse();
        assertThat(sink.to(LINK, 11)).hasSize(2);
    }

    /**
     * 延迟之后处理器又抛了异常：后续的异步步骤多半没发起，没人会再回这条请求——分发处经同一个延迟应答补 1006，
     * 之后迟到的 {@code reply} 返回 false、不产生第二个应答。延迟、回完再抛的不补（已经有应答了）。
     */
    @Test
    void 延迟之后处理器抛异常_还待回的经延迟应答补1006_已回过的不再补() throws Exception {
        int getCurrencyList = Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
        AtomicInteger mode = new AtomicInteger();
        List<DeferredReply> held = new java.util.ArrayList<>();
        GetCurrencyListResponse ok = GetCurrencyListResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(0)).build();
        SceneFeature buggy = r -> r.on("SceneCurrencyClientPlayer", "GetCurrencyList", GetCurrencyListRequest.class,
                (call, req) -> {
                    DeferredReply deferred = call.defer();
                    held.add(deferred);
                    if (mode.get() == 1) {
                        deferred.reply(ok);
                    }
                    throw new IllegalArgumentException("boom");
                });
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS, RunMode.DEV, List.of(buggy));

        mode.set(0);
        forward(11, 1001, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 600);
        mode.set(1);
        forward(11, 1001, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 601);

        List<MessageContent> replies = sink.to(LINK, 11);
        assertThat(replies).extracting(MessageContent::getId).as("两条请求各恰好一个应答").containsExactly(600L, 601L);
        assertThat(replies).extracting(r -> GetCurrencyListResponse.parseFrom(r.getSerializedMessage())
                .getErrorMessage().getId()).as("待回的补 1006；已回过的保持处理器回的 0").containsExactly(1006, 0);
        assertThat(replies).allSatisfy(r -> assertThat(r.getMessageId()).isEqualTo(getCurrencyList));
        assertThat(held).hasSize(2);
        assertThat(held.get(0).pending()).as("补过 1006 之后不再待回").isFalse();
        assertThat(held.get(0).reply(ok)).as("迟到的结果不会造成第二个应答").isFalse();
        assertThat(sink.to(LINK, 11)).hasSize(2);
    }

    /** 延迟之后作废（确定不该回）：分发处不补、之后也没有应答——作废是持有者的决定，不是「忘了回」。 */
    @Test
    void 延迟之后处理器自己作废_不补1006_没有应答() {
        int getCurrencyList = Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
        SceneFeature cancelling = r -> r.on("SceneCurrencyClientPlayer", "GetCurrencyList", GetCurrencyListRequest.class,
                (call, req) -> call.defer().cancel());
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS, RunMode.DEV, List.of(cancelling));

        forward(11, 1001, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 700);

        assertThat(sink.to(LINK, 11)).isEmpty();
    }

    @Test
    void 静默丢弃_未知会话_player不符_非scene域_Empty应答方法_请求体坏() {
        byte[] truncated = {0x0a, 0x05};
        forward(99, 1001, IDS.listSkills(), ListSkillsRequest.getDefaultInstance(), 1);
        forward(11, 2002, IDS.listSkills(), ListSkillsRequest.getDefaultInstance(), 2);
        forward(11, 1001, Contracts.REGISTRY.requireId("ClientPlayerLogin", "Login"), ByteString.EMPTY, 3);
        forward(11, 1001, IDS.notifyEnterScene(), EnterSceneS2C.getDefaultInstance(), 4);
        forward(11, 1001, 999_999, ByteString.EMPTY, 5);
        forward(11, 1001, IDS.releaseSkill(), ByteString.copyFrom(truncated), 6);

        assertThat(sink.events()).isEmpty();
    }

    @Test
    void 加载中的会话发来的消息_静默丢弃() {
        world.onPlayerEnter(LINK, enterFrame(12, 1002, scene1.sceneId(), 1));

        forward(12, 1002, IDS.listSkills(), ListSkillsRequest.getDefaultInstance(), 1);

        assertThat(sink.to(LINK, 12)).isEmpty();
    }

    // ------------------------------------------------------------------ 工具

    private void enter(int sessionId, long playerId, Scene scene) {
        world.onPlayerEnter(LINK, enterFrame(sessionId, playerId, scene.sceneId(), 1));
        repo.completeAll();
        sink.clear();
    }

    private long entityOf(int sessionId) {
        return world.playerBySession(new SessionKey(LINK, sessionId)).entity();
    }

    private Scene entitySceneOf(int sessionId) {
        return world.playerBySession(new SessionKey(LINK, sessionId)).scene();
    }

    private void forward(int sessionId, long playerId, int messageId, Message body, long requestId) {
        forward(sessionId, playerId, messageId, body.toByteString(), requestId);
    }

    private void forward(int sessionId, long playerId, int messageId, ByteString body, long requestId) {
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(sessionId)
                .setPlayerId(playerId)
                .setMessageId(messageId)
                .setBody(body)
                .setRequestId(requestId)
                .build());
    }

    private static EnterSceneC2SRequest enterSceneRequest(int configId, long sceneId, int mirrorConfigId) {
        return EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder()
                        .setSceneConfigId(configId)
                        .setSceneId(sceneId)
                        .setMirrorConfigId(mirrorConfigId))
                .build();
    }

    private static int tipOf(MessageContent reply) {
        try {
            EnterSceneC2SResponse response = EnterSceneC2SResponse.parseFrom(reply.getSerializedMessage());
            assertThat(response.hasErrorMessage()).isTrue();
            return response.getErrorMessage().getId();
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }
}
