package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.dispatch.MatchMethods;
import com.game.match.gather.GatherHooks;
import com.game.match.lifecycle.SweeperControl;
import com.game.match.proto.BattlePlacement;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 164、开局钩子与观战清扫的装配。<b>先行件阶段钉的是三个占位</b>：164 仍是 6.4 的空列表、开局钩子是空实现、清扫口什么都不做。
 * W3 把占位换成真实现时，这个文件随之改成对真装配的断言（164 / 钩子 / 清扫的真语义在各自的测试类）。
 */
class WatchableConfigurationTest {

    private static final SessionContext PLAYER = SessionContext.newBuilder().setGateNodeId(1).setSessionId(7).setPlayerId(1001).setAccount("acc").build();
    private static final SessionContext NOT_IN_GAME = SessionContext.newBuilder().setGateNodeId(1).setSessionId(8).build();

    private final WatchableConfiguration configuration = new WatchableConfiguration();
    private final MatchMethodHandler list = configuration.listWatchableBattlesHandler();

    private static ByteString bytes(Reply reply) {
        assertThat(reply).isInstanceOf(Reply.Body.class);
        return ((Reply.Body) reply).bytes();
    }

    @Test
    void 占位的164_管的是ListWatchableBattles_当场回_不带自己的执行器() {
        assertThat(list.method()).isEqualTo(MatchMethods.LIST_WATCHABLE_BATTLES);
        assertThat(list.inline()).isTrue();
        assertThat(list.executor()).isNull();
        assertThat(list.onOverload()).as("164 没有 in-band 错误字段：过载只能回信封").isEqualTo(Reply.envelope(1003));
    }

    @Test
    void 占位的164_回空列表_应答体0字节_不看会话有没有绑定玩家() throws Exception {
        ByteString reply = bytes(list.handle(PLAYER, ListWatchableBattlesRequest.newBuilder().setLimit(20).build().toByteString(), Deadline.after(1000)));

        assertThat(reply.isEmpty()).as("全默认值的应答 = 0 字节；应答类型不是 Empty，gate 照常回包").isTrue();
        assertThat(ListWatchableBattlesResponse.parseFrom(reply).getBattlesList()).isEmpty();
        assertThat(bytes(list.handle(NOT_IN_GAME, ByteString.EMPTY, Deadline.after(1000)))).isEqualTo(reply);
    }

    @Test
    void 占位的164_照样解析请求体_解析失败把异常交给派发器() {
        ByteString truncated = ByteString.copyFrom(new byte[] {0x0A, 0x7F, 0x01});

        assertThatThrownBy(() -> list.handle(PLAYER, truncated, Deadline.after(1000))).isInstanceOf(InvalidProtocolBufferException.class);
    }

    @Test
    void 占位的开局钩子是空实现_两个钩子都不抛() {
        GatherHooks hooks = configuration.gatherHooks();

        assertThat(hooks).isSameAs(GatherHooks.NOOP);
        assertThatCode(() -> hooks.beforePrepare(List.of(1001L, 1002L))).doesNotThrowAnyException();
        assertThatCode(() -> hooks.onStarted(BattlePlacement.newBuilder().setBattleId(77).setAttempt(1).build())).doesNotThrowAnyException();
    }

    @Test
    void 占位的清扫口什么都不做_启停任意次都不抛() {
        SweeperControl sweeper = configuration.sweeperControl();

        assertThat(sweeper).isSameAs(SweeperControl.NOOP);
        assertThatCode(() -> {
            sweeper.stop();
            sweeper.start();
            sweeper.stop();
            sweeper.stop();
        }).doesNotThrowAnyException();
    }
}
