package com.game.mail;
import com.game.api.*;
import com.game.api.proto.*;
import com.game.discovery.serviceauth.DomainClientDispatch;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.config.annotation.DubboService;
@DubboService(group = DubboGroups.MAIL)
public final class MailClientProvider implements ClientMessageService {
    private final DomainClientDispatch dispatcher;
    public MailClientProvider(DomainClientDispatch dispatcher) {
        this.dispatcher = dispatcher;
    }
    public CompletableFuture<ClientReply> handle(ClientCall call) {
        return dispatcher.dispatch(call);
    }
    public CompletableFuture<Ack> sessionClosed(SessionClosed event) {
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
    }
    public CompletableFuture<Ack> abandonEnter(AbandonedEnter event) {
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
    }
}
