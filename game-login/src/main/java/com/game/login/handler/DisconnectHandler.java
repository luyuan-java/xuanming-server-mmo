package com.game.login.handler;

import com.game.api.proto.SessionContext;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.LoginEmptyResponse;
import com.game.proto.login.LoginNodeDisconnectRequest;
import com.google.protobuf.Message;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disconnect（58，客户端主动发）：接受空请求，只记日志，不回包。
 *
 * <p>请求里的 {@code session_id} 由客户端填写（机器人发 0），不可信，不用它做任何事；只信任 gate 填的会话上下文。
 * 真正的离线信号是 TCP 断开，由 gate 经 {@code ClientMessageService.sessionClosed} 通知。幂等。
 */
public final class DisconnectHandler implements ClientMessageHandler<LoginNodeDisconnectRequest> {

    private static final Logger log = LoggerFactory.getLogger(DisconnectHandler.class);

    @Override
    public String methodName() {
        return "Disconnect";
    }

    @Override
    public Class<LoginNodeDisconnectRequest> requestType() {
        return LoginNodeDisconnectRequest.class;
    }

    @Override
    public Class<LoginEmptyResponse> responseType() {
        return LoginEmptyResponse.class;
    }

    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, LoginNodeDisconnectRequest request) {
        log.info("客户端主动断开 gate={} session={} account={} player={}",
                session.getGateNodeId(), session.getSessionId(), session.getAccount(), session.getPlayerId());
        return CompletableFuture.completedFuture(HandlerReply.none());
    }

    @Override
    public Optional<Message> failureBody(TipInfoMessage tip) {
        return Optional.empty();
    }
}
