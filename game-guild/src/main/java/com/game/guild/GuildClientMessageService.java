package com.game.guild;

import com.game.api.CallerDeadline;
import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.guild.dispatch.GuildDispatcher;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * {@link ClientMessageService} 的 Dubbo 提供方（group {@code guild}，Triple，默认端口 20886；guild-spec §7.1）。只做协议适配：客户端消息交给
 * {@link GuildDispatcher}（在 guild 工作线程池上执行）。guild 进程不持有会话状态：会话结束、进场未送达都与它无关，直接确认。
 */
@DubboService(group = DubboGroups.GUILD)
public class GuildClientMessageService implements ClientMessageService {

    private final GuildDispatcher dispatcher;

    public GuildClientMessageService(GuildDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 调用方截止只能在这里（Dubbo 线程、入口处）读：派发器把工作投到别的线程之后就读不到了。 */
    @Override
    public CompletableFuture<ClientReply> handle(ClientCall call) {
        return dispatcher.dispatch(call, CallerDeadline.fromRpcContext());
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
