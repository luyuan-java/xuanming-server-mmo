package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.contract.MessageIdRegistry;
import com.game.login.dispatch.ClientMessageDispatcher;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.login.metrics.LoginMetrics;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.login.LoginNodeDisconnectRequest;
import com.game.table.LoginErrorTip;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** 覆盖 Batch 4S-2 登录侧改动：EnterGame 队列满回 2058、未绑定 LeaveGame 幂等、Disconnect 以会话头 session_id 为准。 */
class Batch4S2LoginCodesTest {

    @Test
    void EnterGame队列满返回2058_kLoginEnterGameBusy() throws Exception {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        LoginMetrics metrics = new LoginMetrics(new SimpleMeterRegistry());
        ClientMessageHandler<EnterGameRequest> enter = new ClientMessageHandler<>() {
            @Override public String methodName() { return "EnterGame"; }
            @Override public Class<EnterGameRequest> requestType() { return EnterGameRequest.class; }
            @Override public Class<EnterGameResponse> responseType() { return EnterGameResponse.class; }
            @Override public CompletableFuture<HandlerReply> handle(SessionContext session, EnterGameRequest request) {
                return CompletableFuture.completedFuture(HandlerReply.of(EnterGameResponse.getDefaultInstance()));
            }
            @Override public Optional<Message> failureBody(TipInfoMessage tip) {
                return Optional.of(EnterGameResponse.newBuilder().setErrorMessage(tip).build());
            }
        };
        Executor full = task -> { throw new RejectedExecutionException("full"); };
        ClientMessageDispatcher dispatcher = new ClientMessageDispatcher(registry, List.of(enter), full, metrics);

        ClientCall call = ClientCall.newBuilder()
                .setMessageId(26)
                .setSession(SessionContext.newBuilder().setSessionId(1).setAccount("a").build())
                .setBody(EnterGameRequest.newBuilder().setPlayerId(10L).build().toByteString())
                .build();
        ClientReply reply = dispatcher.dispatch(call).join();
        EnterGameResponse resp = EnterGameResponse.parseFrom(reply.getBody());

        assertThat(resp.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginEnterGameBusy_VALUE);
        assertThat(resp.getErrorMessage().getId()).isEqualTo(2058);
    }

    @Test
    void 未绑定角色的LeaveGame幂等成功且不发指令() {
        LeaveGameHandler handler = new LeaveGameHandler();
        HandlerReply reply = handler.handle(SessionContext.newBuilder().setSessionId(9).setAccount("acc").build(),
                LeaveGameRequest.getDefaultInstance()).join();
        assertThat(reply.body()).isEmpty();
        assertThat(reply.directives()).isEmpty();
    }

    @Test
    void Disconnect请求体sessionId不一致时仍按会话头收尾不报错() {
        DisconnectHandler handler = new DisconnectHandler();
        SessionContext header = SessionContext.newBuilder().setGateNodeId(2).setSessionId(88).setAccount("acc").build();
        LoginNodeDisconnectRequest body = LoginNodeDisconnectRequest.newBuilder().setSessionId(999).build();

        HandlerReply reply = handler.handle(header, body).join();
        assertThat(reply.body()).isEmpty();
        assertThat(reply.directives()).isEmpty();
    }
}
