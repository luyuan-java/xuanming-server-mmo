package com.game.battle.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.protocol.BattleMessageIds.Upstream;
import com.game.battle.push.PushCategory;
import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientRequest;
import com.game.proto.MessageContent;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.TurnResultS2C;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

/** 消息号解析与下行帧形状（battle-node-spec §3.2、§3.4、§5.1）。 */
class BattleProtocolTest {

    private final BattleMessageIds ids = BattleMessageIds.loadFromClasspath();

    @Test
    void 契约里的消息号与基线一致() {
        assertThat(ids.id(Upstream.GET_BATTLE_STATE)).isEqualTo(140);
        assertThat(ids.id(Upstream.SUBMIT_BATTLE_ACTION)).isEqualTo(149);
        assertThat(ids.id(Upstream.SET_AUTO_BATTLE)).isEqualTo(162);
        assertThat(ids.id(Upstream.STOP_WATCH_BATTLE)).isEqualTo(165);
        assertThat(ids.id(Notify.TURN_RESULT)).isEqualTo(139);
        assertThat(ids.id(Notify.BATTLE_START)).isEqualTo(143);
        assertThat(ids.id(Notify.BATTLE_END)).isEqualTo(150);
        assertThat(ids.id(Notify.SPECTATE_TURN_RESULT)).isEqualTo(158);
        assertThat(ids.id(Notify.SPECTATE_STATE)).isEqualTo(161);
        assertThat(ids.id(Notify.SPECTATE_END)).isEqualTo(166);
        assertThat(ids.id(Notify.BATTLE_ASSIGNED)).isEqualTo(177);
    }

    @Test
    void 白名单只有四条上行_Notify号与别的服务的号都不在内() {
        assertThat(ids.upstream(149)).contains(Upstream.SUBMIT_BATTLE_ACTION);
        assertThat(ids.upstream(140)).contains(Upstream.GET_BATTLE_STATE);
        assertThat(ids.upstream(162)).contains(Upstream.SET_AUTO_BATTLE);
        assertThat(ids.upstream(165)).contains(Upstream.STOP_WATCH_BATTLE);
        assertThat(ids.upstream(139)).as("Notify 号").isEmpty();
        assertThat(ids.upstream(177)).isEmpty();
        assertThat(ids.upstream(157)).isEmpty();
        assertThat(ids.upstream(179)).as("补签走大厅 → match").isEmpty();
        assertThat(ids.upstream(0)).isEmpty();
    }

    @Test
    void 只有177与143是大厅公告() {
        for (Notify n : Notify.values()) {
            boolean lobby = n == Notify.BATTLE_ASSIGNED || n == Notify.BATTLE_START;
            assertThat(n.category()).as(n.name())
                    .isEqualTo(lobby ? PushCategory.LOBBY_ANNOUNCEMENT : PushCategory.BATTLE_FRAME);
        }
    }

    @Test
    void 缺号即抛异常拒绝启动() {
        // 契约是完整的；解析走 requireId，缺号时抛 IllegalStateException（这里用不存在的方法名验证这条失败路径）
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        assertThatThrownBy(() -> registry.requireId(BattleMessageIds.SERVICE, "NotifyNoSuchThing"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(BattleMessageIds.resolve(registry).id(Notify.TURN_RESULT)).isEqualTo(139);
    }

    @Test
    void 推送形状_id为0_没有error_message() {
        TurnResultS2C body = TurnResultS2C.newBuilder().setBattleId(7).setRoundIndex(1).build();
        MessageContent push = BattleFrames.push(139, body);
        assertThat(push.getId()).isZero();
        assertThat(push.getMessageId()).isEqualTo(139);
        assertThat(push.getSerializedMessage()).isEqualTo(body.toByteString());
        assertThat(push.hasErrorMessage()).isFalse();
    }

    @Test
    void 应答形状_回显id与号_成功时体为0字节且没有error_message() {
        ClientRequest req = ClientRequest.newBuilder().setId(42).setMessageId(149).build();
        MessageContent reply = BattleFrames.reply(req, SubmitBattleActionResponse.getDefaultInstance());
        assertThat(reply.getId()).isEqualTo(42);
        assertThat(reply.getMessageId()).isEqualTo(149);
        assertThat(reply.getSerializedMessage()).isEqualTo(ByteString.EMPTY);
        assertThat(reply.hasErrorMessage()).isFalse();
    }

    @Test
    void 信封错误形状_回显id与号_带tip_没有体() {
        ClientRequest req = ClientRequest.newBuilder().setId(43).setMessageId(157).setBody(ByteString.copyFromUtf8("x")).build();
        MessageContent error = BattleFrames.envelopeError(req, 1005);
        assertThat(error.getId()).isEqualTo(43);
        assertThat(error.getMessageId()).isEqualTo(157);
        assertThat(error.getErrorMessage().getId()).isEqualTo(1005);
        assertThat(error.getErrorMessage().getParametersList()).isEmpty();
        assertThat(error.getSerializedMessage()).isEmpty();
    }

    @Test
    void 握手应答形状() {
        BattleTokenVerifyResponse ok = BattleFrames.verifyAccepted(99);
        assertThat(ok.getSuccess()).isTrue();
        assertThat(ok.getBattleId()).isEqualTo(99);
        assertThat(ok.getError()).isEmpty();

        BattleTokenVerifyResponse rejected = BattleFrames.verifyRejected(BattleFrames.REJECT_NOT_IN_ROSTER);
        assertThat(rejected.getSuccess()).isFalse();
        assertThat(rejected.getBattleId()).as("battle_id 不在线上").isZero();
        assertThat(rejected.getError()).isEqualTo("battle not found or player not in this battle");
        assertThat(BattleFrames.REJECT_INVALID_SIGNATURE).isEqualTo("invalid ticket signature");
        assertThat(BattleFrames.REJECT_MALFORMED_PAYLOAD).isEqualTo("malformed ticket payload");
    }
}
