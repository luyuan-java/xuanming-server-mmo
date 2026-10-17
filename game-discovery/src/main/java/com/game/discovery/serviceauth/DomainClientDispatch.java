package com.game.discovery.serviceauth;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.google.protobuf.Message;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.ToIntFunction;

/** 无场景状态的领域入口共用接缝：契约解析、玩家绑定、有界排队、预算及in-band错误。 */
public final class DomainClientDispatch implements AutoCloseable {
    @FunctionalInterface
    public interface Handler {
        Message apply(String method, long player, Message request, Deadline budget) throws Exception;
    }
    private final MessageIdRegistry registry;
    private final String service;
    private final Set<String> methods;
    private final Handler handler;
    private final ToIntFunction<Throwable> error;
    private final ThreadPoolExecutor workers;
    public DomainClientDispatch(
            MessageIdRegistry registry, String service, Set<String> methods, Handler handler, ToIntFunction<Throwable> error) {
        this.registry = registry;
        this.service = service;
        this.methods = Set.copyOf(methods);
        this.handler = handler;
        this.error = error;
        this.workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(512),
                Thread.ofPlatform().name(service + "-worker-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
        methods.forEach(method -> registry.requireId(service, method));
    }
    public CompletableFuture<ClientReply> dispatch(ClientCall call) {
        var contract = registry.byId(call.getMessageId()).orElse(null);
        if (contract == null || !contract.serviceName().equals(service) || !methods.contains(contract.methodName())
                || call.getSession().getPlayerId() == 0)
            return CompletableFuture.completedFuture(
                    ClientReply.newBuilder().setTipId(error.applyAsInt(new IllegalArgumentException())).build());
        Deadline budget = Deadline.after(3500);
        var result = new CompletableFuture<ClientReply>();
        Runnable run = () -> {
            Message response;
            try {
                if (budget.expired())
                    throw new TimeoutException();
                var request = contract.requestPrototype().getParserForType().parseFrom(call.getBody());
                response = handler.apply(contract.methodName(), call.getSession().getPlayerId(), request, budget);
            } catch (Exception failure) {
                var builder = contract.responsePrototype().toBuilder();
                var field = builder.getDescriptorForType().findFieldByName("error_message");
                if (field == null) {
                    result.complete(ClientReply.newBuilder().setTipId(error.applyAsInt(failure)).build());
                    return;
                }
                var tip = builder.newBuilderForField(field);
                tip.setField(tip.getDescriptorForType().findFieldByName("id"), error.applyAsInt(failure));
                builder.setField(field, tip.build());
                response = builder.build();
            }
            result.complete(ClientReply.newBuilder().setBody(response.toByteString()).build());
        };
        try {
            workers.execute(run);
        } catch (RejectedExecutionException full) {
            result.complete(ClientReply.newBuilder().setTipId(error.applyAsInt(full)).build());
        }
        return result;
    }
    @Override
    public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS))
                workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
