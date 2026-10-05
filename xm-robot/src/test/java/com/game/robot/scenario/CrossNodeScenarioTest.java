package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.MessageContent;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.robot.RobotOptions;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.table.ConfigTables;
import com.game.table.MessageLimiterTable;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** cross-node 场景里不连服务端就能钉住的部分：落位判定、分工、不存在的场景号、63 应答码、下行归属判定、连发与 gate 限频、账号与子命令。 */
class CrossNodeScenarioTest {

    private static final MessageIds IDS = MessageIds.resolve(MessageIdRegistry.loadFromClasspath());
    /** 大于 Long.MAX_VALUE 的场景号（uint64），输出要按无符号。 */
    private static final long BIG = 0x8000_0000_0000_0001L;

    private static SceneInfoComp scene(int config, long id) {
        return SceneInfoComp.newBuilder().setSceneConfigId(config).setSceneId(id).build();
    }

    private static Received frame(int index, int messageId, long requestId, Message body) {
        return new Received(index, 0, MessageContent.newBuilder().setMessageId(messageId).setId(requestId)
                .setSerializedMessage(body.toByteString()).build());
    }

    private static ActorCreateS2C actor(long entity, long guid) {
        return ActorCreateS2C.newBuilder().setEntity(entity).setGuid(guid).build();
    }

    @Test
    void 账号带xn标签与序号_子命令写作cross_node() throws Exception {
        assertThat(CrossNodeScenario.accountName("robot_java_", "x1", "1")).isEqualTo("robot_java_xnx1_1");
        RobotOptions o = RobotOptions.parse(List.of("cross-node", "--run-tag", "x1"),
                Map.of(RobotOptions.PASSWORD_ENV, "p"), 1L);
        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.CROSS_NODE);
        assertThat(RobotOptions.usage()).contains("cross-node", "XM_SCENE_NODES=2");
    }

    @Test
    void 落位_同图两个不同的非0场景号才算两个节点() {
        assertThat(CrossNodeScenario.onDifferentChannels(scene(1, 10), scene(1, 11))).isTrue();
        assertThat(CrossNodeScenario.onDifferentChannels(scene(1, BIG), scene(1, 11))).isTrue();
        assertThat(CrossNodeScenario.onDifferentChannels(scene(1, 10), scene(1, 10))).as("同场景").isFalse();
        assertThat(CrossNodeScenario.onDifferentChannels(scene(1, 10), scene(2, 11))).as("不同地图推不出不同节点").isFalse();
        assertThat(CrossNodeScenario.onDifferentChannels(scene(0, 10), scene(0, 11))).isFalse();
        assertThat(CrossNodeScenario.onDifferentChannels(scene(1, 0), scene(1, 11))).isFalse();
    }

    @Test
    void 分工_账号3与谁同场景谁当A_都不同时没有观察者() {
        assertThat(CrossNodeScenario.assignRoles(10, 11, 10)).isEqualTo(new CrossNodeScenario.Roles(1, 2, 3));
        assertThat(CrossNodeScenario.assignRoles(10, 11, 11)).isEqualTo(new CrossNodeScenario.Roles(2, 1, 3));
        assertThat(CrossNodeScenario.assignRoles(10, 11, 12)).isEqualTo(new CrossNodeScenario.Roles(1, 2, 0));
    }

    @Test
    void 不存在的场景号_非0且避开已知的() {
        assertThat(CrossNodeScenario.missingSceneId(List.of(BIG, 77L))).isEqualTo(1);
        assertThat(CrossNodeScenario.missingSceneId(List.of(1L, 2L, 4L))).isEqualTo(3);
        assertThat(CrossNodeScenario.missingSceneId(List.of())).isEqualTo(1);
    }

    @Test
    void 换场景请求只带配置号与场景号() {
        EnterSceneC2SRequest request = CrossNodeScenario.switchRequest(0, BIG);
        assertThat(request.getSceneInfo()).isEqualTo(SceneInfoComp.newBuilder().setSceneId(BIG).build());
        assertThat(CrossNodeScenario.switchRequest(3, 9).getSceneInfo()).isEqualTo(scene(3, 9));
    }

    @Test
    void 应答码_信封错误优先_没有error_message为0_体解析失败为负一() {
        int id = 63;
        assertThat(CrossNodeScenario.replyTip(frame(0, id, 1, EnterSceneC2SResponse.getDefaultInstance()))).isZero();
        assertThat(CrossNodeScenario.replyTip(frame(0, id, 1, EnterSceneC2SResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(0)).build()))).isZero();
        assertThat(CrossNodeScenario.replyTip(frame(0, id, 1, EnterSceneC2SResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(CrossNodeScenario.CHANGING_SCENE)).build())))
                .isEqualTo(3014);
        Received envelope = new Received(0, 0, MessageContent.newBuilder().setMessageId(id).setId(1)
                .setErrorMessage(TipInfoMessage.newBuilder().setId(1003)).build());
        assertThat(CrossNodeScenario.replyTip(envelope)).isEqualTo(1003);
        Received garbage = new Received(0, 0, MessageContent.newBuilder().setMessageId(id).setId(1)
                .setSerializedMessage(ByteString.copyFrom(new byte[] {(byte) 0xff})).build());
        assertThat(CrossNodeScenario.replyTip(garbage)).isEqualTo(-1);
    }

    @Test
    void 拒绝码取自同步来的tip表() {
        assertThat(CrossNodeScenario.ENTER_FAILED).isEqualTo(3023);
        assertThat(CrossNodeScenario.CHANGING_SCENE).isEqualTo(3014);
        assertThat(CrossNodeScenario.KICKED_BY_ANOTHER).isEqualTo(2017);
        assertThat(CrossNodeScenario.tipId(frame(0, IDS.sendTip(), 0, TipInfoMessage.newBuilder().setId(3023).build())))
                .isEqualTo(3023);
    }

    @Test
    void 按guid在21与47里找角色_别的消息号不算() {
        Received single = frame(0, IDS.notifyActorCreate(), 0, actor(5, BIG));
        Received list = frame(1, IDS.notifyActorListCreate(), 0, ActorListCreateS2C.newBuilder()
                .addActorList(actor(6, 7)).addActorList(actor(8, BIG)).build());
        Received other = frame(2, IDS.notifyEnterScene(), 0, actor(5, BIG));
        assertThat(CrossNodeScenario.findActor(IDS, single, BIG).getEntity()).isEqualTo(5);
        assertThat(CrossNodeScenario.findActor(IDS, single, 7)).isNull();
        assertThat(CrossNodeScenario.findActor(IDS, list, BIG).getEntity()).isEqualTo(8);
        assertThat(CrossNodeScenario.findActor(IDS, list, 9)).isNull();
        assertThat(CrossNodeScenario.findActor(IDS, other, BIG)).isNull();
    }

    @Test
    void 销毁判定认51与批量52_实体号0不算() {
        Received one = frame(0, IDS.notifyActorDestroy(), 0, ActorDestroyS2C.newBuilder().setEntity(BIG).build());
        Received batch = frame(1, IDS.notifyActorListDestroy(), 0, ActorListDestroyS2C.newBuilder()
                .addEntity(3).addEntity(BIG).build());
        assertThat(CrossNodeScenario.destroys(IDS, one, BIG)).isTrue();
        assertThat(CrossNodeScenario.destroys(IDS, one, 3)).isFalse();
        assertThat(CrossNodeScenario.destroys(IDS, batch, 3)).isTrue();
        assertThat(CrossNodeScenario.destroys(IDS, batch, 4)).isFalse();
        assertThat(CrossNodeScenario.destroys(IDS, frame(2, IDS.notifyActorDestroy(), 0,
                ActorDestroyS2C.getDefaultInstance()), 0)).isFalse();
    }

    @Test
    void 六十六按entity_id归属玩家() {
        Received mine = frame(0, IDS.syncBaseAttribute(), 0, ActorBaseAttributesS2C.newBuilder().setEntityId(BIG).build());
        Received others = frame(1, IDS.syncBaseAttribute(), 0, ActorBaseAttributesS2C.newBuilder().setEntityId(7).build());
        assertThat(CrossNodeScenario.syncOf(IDS, mine, BIG)).isNotNull();
        assertThat(CrossNodeScenario.syncOf(IDS, others, BIG)).isNull();
        assertThat(CrossNodeScenario.syncOf(IDS, frame(2, IDS.notifyActorCreate(), 0, actor(1, BIG)), BIG)).isNull();
    }

    @Test
    void 连发两条63不会被gate限频挡下_空出的窗口超过一秒() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        int enterScene = MessageIdRegistry.loadFromClasspath().requireId("SceneSceneClientPlayer", "EnterScene");
        // 表里没有 63 时 gate 取缺省每秒 3 条（MessageLimit.DEFAULT）；有行时也必须容得下连发的两条
        Optional<MessageLimiterTable> row = ConfigTables.load(dir).messageLimiter().all().stream()
                .filter(r -> r.getId() == enterScene).findFirst();
        row.ifPresent(r -> assertThat(r.getMaxRequests()).as("63 每窗口至少放行两条").isGreaterThanOrEqualTo(2));
        long windowMillis = row.map(r -> r.getTimeWindow() * 1000L).orElse(1000L);
        assertThat(CrossNodeScenario.GATE_RATE_WINDOW.toMillis()).isGreaterThan(windowMillis);
    }

    @Test
    void 计数与场景比较_描述按无符号() {
        List<Received> received = List.of(frame(0, IDS.notifyEnterScene(), 0, scene(1, 2)),
                frame(1, IDS.sendTip(), 0, TipInfoMessage.getDefaultInstance()),
                frame(2, IDS.notifyEnterScene(), 0, scene(1, 2)));
        assertThat(CrossNodeScenario.countOf(received, IDS.notifyEnterScene())).isEqualTo(2);
        assertThat(CrossNodeScenario.countOf(received, IDS.notifyActorCreate())).isZero();
        assertThat(CrossNodeScenario.sameScene(scene(1, BIG), scene(1, BIG))).isTrue();
        assertThat(CrossNodeScenario.sameScene(scene(1, BIG), scene(2, BIG))).isFalse();
        assertThat(CrossNodeScenario.describe(scene(1, BIG)))
                .isEqualTo("scene_config_id=1 scene_id=9223372036854775809").doesNotContain("-");
    }
}
