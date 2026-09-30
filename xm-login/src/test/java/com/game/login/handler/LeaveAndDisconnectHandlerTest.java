package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.UnbindPlayer;
import com.game.login.dispatch.HandlerReply;
import com.game.login.dispatch.Tips;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.login.LoginNodeDisconnectRequest;
import org.junit.jupiter.api.Test;

class LeaveAndDisconnectHandlerTest {

    private static final SessionContext SESSION = SessionContext.newBuilder()
            .setGateNodeId(1).setSessionId(77).setAccount("robot_0001").setPlayerId(5).build();

    @Test
    void 已进游戏的会话离开_不回包并下发UnbindPlayer_可重复调用() {
        LeaveGameHandler handler = new LeaveGameHandler();
        for (int i = 0; i < 2; i++) {
            HandlerReply reply = handler.handle(SESSION, LeaveGameRequest.getDefaultInstance()).join();
            assertThat(reply.body()).isEmpty();
            assertThat(reply.directives()).containsExactly(SessionDirective.newBuilder()
                    .setUnbindPlayer(UnbindPlayer.getDefaultInstance()).build());
        }
        assertThat(handler.failureBody(Tips.of(1003))).isEmpty();
    }

    @Test
    void 未绑定玩家的会话离开_不回包也不下发指令() {
        LeaveGameHandler handler = new LeaveGameHandler();
        HandlerReply reply = handler.handle(SESSION.toBuilder().clearPlayerId().build(),
                LeaveGameRequest.getDefaultInstance()).join();
        assertThat(reply.body()).isEmpty();
        assertThat(reply.directives()).isEmpty();
    }

    @Test
    void 主动断开不回包_未登录会话也接受() {
        DisconnectHandler handler = new DisconnectHandler();
        HandlerReply reply = handler.handle(SessionContext.getDefaultInstance(),
                LoginNodeDisconnectRequest.newBuilder().setSessionId(0).build()).join();
        assertThat(reply.body()).isEmpty();
        assertThat(reply.directives()).isEmpty();
        assertThat(handler.failureBody(Tips.of(1003))).isEmpty();
    }
}
