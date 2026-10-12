package com.game.team;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.team.dispatch.TeamDispatcher;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * {@link ClientMessageService} 的 Dubbo 提供方（group {@code team}，Triple，默认端口 20885；team-spec §6.1）。只做协议适配：客户端消息交给
 * {@link TeamDispatcher}（在 team 工作线程池上执行）。team 进程无状态：会话结束、进场未送达都与它无关，直接确认。
 */
@DubboService(group = DubboGroups.TEAM)
public class TeamClientMessageService implements ClientMessageService {

    private final TeamDispatcher dispatcher;

    public TeamClientMessageService(TeamDispatcher dispatcher) {
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
