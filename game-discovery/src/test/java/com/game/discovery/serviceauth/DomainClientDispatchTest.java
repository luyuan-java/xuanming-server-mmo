package com.game.discovery.serviceauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.google.protobuf.Message;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DomainClientDispatchTest {

    @Test
    void dispatch_intersectsWithCallerDeadline() throws Exception {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        int messageId = registry.requireId("ClientPlayerMail", "ListMails");
        MessageMethod contract = registry.byId(messageId).orElseThrow();
        AtomicLong observedRemainingMs = new AtomicLong(-1);
        try (DomainClientDispatch dispatch = new DomainClientDispatch(
                registry, "ClientPlayerMail", Set.of("ListMails"),
                (method, player, request, budget) -> {
                    observedRemainingMs.set(budget.remainingMillis());
                    return contract.responsePrototype();
                },
                failure -> 1003)) {
            ClientCall call = ClientCall.newBuilder()
                    .setMessageId(messageId)
                    .setSession(SessionContext.newBuilder().setPlayerId(1001L))
                    .setBody(contract.requestPrototype().toByteString())
                    .build();
            ClientReply reply = dispatch.dispatch(call, Optional.of(Deadline.after(300))).get(3, TimeUnit.SECONDS);
            assertThat(reply.getTipId()).isZero();
            assertThat(observedRemainingMs.get()).isGreaterThan(0L).isLessThanOrEqualTo(300L);
        }
    }

    @Test
    void dispatch_completesFutureWhenHandlerThrowsError() throws Exception {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        int messageId = registry.requireId("ClientPlayerMail", "ListMails");
        MessageMethod contract = registry.byId(messageId).orElseThrow();
        try (DomainClientDispatch dispatch = new DomainClientDispatch(
                registry, "ClientPlayerMail", Set.of("ListMails"),
                (method, player, request, budget) -> {
                    throw new AssertionError("boom");
                },
                failure -> 1003)) {
            ClientCall call = ClientCall.newBuilder()
                    .setMessageId(messageId)
                    .setSession(SessionContext.newBuilder().setPlayerId(1001L))
                    .setBody(contract.requestPrototype().toByteString())
                    .build();
            ClientReply reply = dispatch.dispatch(call).get(3, TimeUnit.SECONDS);
            Message parsed = contract.responsePrototype().getParserForType().parseFrom(reply.getBody());
            var errorField = parsed.getDescriptorForType().findFieldByName("error_message");
            assertThat(errorField).isNotNull();
            Message tip = (Message) parsed.getField(errorField);
            assertThat(tip.getField(tip.getDescriptorForType().findFieldByName("id"))).isEqualTo(1003);
        }
    }
}
