package com.game.login.handler;

import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.UnbindPlayer;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.login.LoginEmptyResponse;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LeaveGame（17）：接受空请求，不回包（契约允许「空应答或不回应答」，机器人发完即关连接、不等应答）。
 *
 * <p>会话已绑定玩家时，离开成功后下发会话指令 {@code UnbindPlayer}：gate 向玩家所在 scene 发
 * {@code PlayerLeave{voluntary=true}}（scene 写回并移除玩家），并清掉会话上的玩家 / 场景绑定、保留账号，
 * 客户端可以回到选角（之后 CreatePlayer / EnterGame 按「已登录、未进游戏」处理）。
 * 会话没绑定玩家时没有要离开的东西，不下发指令。
 *
 * <p>Java 版 login 不持有登录态（会话状态在 gate），这里只产出指令，天然幂等。
 */
public final class LeaveGameHandler implements ClientMessageHandler<LeaveGameRequest> {

    private static final Logger log = LoggerFactory.getLogger(LeaveGameHandler.class);

    private static final SessionDirective UNBIND_PLAYER = SessionDirective.newBuilder()
            .setUnbindPlayer(UnbindPlayer.getDefaultInstance())
            .build();

    @Override
    public String methodName() {
        return "LeaveGame";
    }

    @Override
    public Class<LeaveGameRequest> requestType() {
        return LeaveGameRequest.class;
    }

    @Override
    public Class<LoginEmptyResponse> responseType() {
        return LoginEmptyResponse.class;
    }

    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, LeaveGameRequest request) {
        log.info("客户端离开游戏 gate={} session={} account={} player={}",
                session.getGateNodeId(), session.getSessionId(), session.getAccount(), session.getPlayerId());
        if (session.getPlayerId() == 0) {
            return CompletableFuture.completedFuture(HandlerReply.none());
        }
        return CompletableFuture.completedFuture(new HandlerReply(Optional.empty(), List.of(UNBIND_PLAYER)));
    }

    @Override
    public Optional<Message> failureBody(TipInfoMessage tip) {
        return Optional.empty();
    }
}
