package com.game.trade;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.trade.dispatch.TradeDispatcher;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * {@link ClientMessageService} 的 Dubbo 提供方（group {@code trade}，Triple，缺省端口 20887；trade-spec §5.1）。只做协议适配：客户端消息交给
 * {@link TradeDispatcher}（在 trade 工作线程池上执行）。trade 进程不持有会话状态（基线不推送、不读会话键）：会话结束、进场未送达都与它无关，直接确认。
 */
@DubboService(group = DubboGroups.TRADE)
public class TradeClientMessageService implements ClientMessageService {

    private final TradeDispatcher dispatcher;

    public TradeClientMessageService(TradeDispatcher dispatcher) {
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
