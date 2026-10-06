package com.game.match.dispatch;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * {@link ClientMessageService} 的 Dubbo 提供方（group {@code match}，Triple，缺省端口 20888；match-spec §9.1、§9.9）：gate 把
 * {@code match.MatchService} 的 10 个消息号转到这里。只做协议适配，客户端消息交给 {@link MatchDispatcher}。
 * match 不持有会话状态（排队票据按玩家号存、靠 TTL 与取消收尾；断线的玩家由凑单的位置检查处理）：会话结束、进场未送达都直接确认。
 */
@DubboService(group = DubboGroups.MATCH)
public class MatchClientMessageService implements ClientMessageService {

    private final MatchDispatcher dispatcher;

    public MatchClientMessageService(MatchDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public CompletableFuture<ClientReply> handle(ClientCall call) {
        return dispatcher.dispatch(call);
    }

    @Override
    public CompletableFuture<Ack> sessionClosed(SessionClosed event) {
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
    }

    @Override
    public CompletableFuture<Ack> abandonEnter(AbandonedEnter event) {
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
    }
}
