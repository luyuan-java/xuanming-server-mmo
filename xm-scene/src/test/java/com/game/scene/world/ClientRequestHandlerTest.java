package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.assertLocation;
import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientForward;
import com.game.common.RunMode;
import com.game.proto.MessageContent;
import com.game.proto.PlayerSkillComp;
import com.game.proto.Vector3;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.EnterSceneS2C;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.proto.ReleaseSkillRequest;
import com.game.proto.ReleaseSkillResponse;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoRequest;
import com.game.proto.SceneInfoS2C;
import com.game.proto.SkillUsedS2C;
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
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS, tables);
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
    void ReleaseSkill_拥有的技能_先广播70再回空tip() throws Exception {
        enter(12, 1002, scene1);
        long entityA = entityOf(11);
        long entityB = entityOf(12);
        sink.clear();

        Vector3 position = Vector3.newBuilder().setX(1).setY(2).setZ(3).build();
        forward(11, 1001, IDS.releaseSkill(), ReleaseSkillRequest.newBuilder()
                .setSkillTableId(2).setTargetId(entityB).setPosition(position).build(), 7);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(70, 84);
        SkillUsedS2C used = SkillUsedS2C.parseFrom(toA.get(0).getSerializedMessage());
        assertThat(used.getEntity()).isEqualTo(entityA);
        assertThat(used.getTargetEntityList()).containsExactly(entityB);
        assertThat(used.getSkillTableId()).isEqualTo(2);
        assertThat(used.getPosition()).isEqualTo(position);
        assertThat(used.getTimeStamp()).isZero();

        MessageContent reply = toA.get(1);
        assertThat(reply.getId()).isEqualTo(7L);
        ReleaseSkillResponse response = ReleaseSkillResponse.parseFrom(reply.getSerializedMessage());
        assertThat(response.hasErrorMessage()).isTrue();
        assertThat(response.getErrorMessage().getId()).isZero();

        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(70);
    }

    @Test
    void ReleaseSkill_未拥有或不存在的技能_回1001且不广播() throws Exception {
        forward(11, 1001, IDS.releaseSkill(), ReleaseSkillRequest.newBuilder().setSkillTableId(5).build(), 1);
        forward(11, 1001, IDS.releaseSkill(), ReleaseSkillRequest.newBuilder().setSkillTableId(99).build(), 2);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(84, 84);
        for (MessageContent reply : toA) {
            assertThat(ReleaseSkillResponse.parseFrom(reply.getSerializedMessage()).getErrorMessage().getId())
                    .isEqualTo(1001);
        }
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
    void EnterScene_参数全0回3005_指定当前场景回3008_目标不在本节点回3023() throws Exception {
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(0, 0, 0), 1);
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(1, scene1.sceneId(), 0), 2);
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(9, 0, 0), 3);
        forward(11, 1001, IDS.enterScene(), enterSceneRequest(0, 0, 4), 4);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(63, 63, 63, 63);
        assertThat(toA).extracting(m -> tipOf(m)).containsExactly(3005, 3008, 3023, 3023);
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
            assertThatThrownBy(() -> new ClientRequestHandler(world, Contracts.REGISTRY, IDS, tables, RunMode.DEV,
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
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS, new FakeSceneTables(), RunMode.DEV,
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
