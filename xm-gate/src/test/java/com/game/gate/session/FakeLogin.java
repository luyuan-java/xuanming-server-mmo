package com.game.gate.session;

import com.game.api.ClientMessageService;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** login 后端替身：记录调用，由测试决定何时、以何结果完成。 */
final class FakeLogin implements ClientMessageService {

    final List<ClientCall> calls = new ArrayList<>();
    final List<SessionClosed> closed = new ArrayList<>();
    final List<AbandonedEnter> abandoned = new ArrayList<>();
    private final Deque<CompletableFuture<ClientReply>> pending = new ArrayDeque<>();

    @Override
    public CompletableFuture<ClientReply> handle(ClientCall call) {
        calls.add(call);
        CompletableFuture<ClientReply> future = new CompletableFuture<>();
        pending.add(future);
        return future;
    }

    @Override
    public CompletableFuture<Ack> sessionClosed(SessionClosed event) {
        closed.add(event);
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
    }

    @Override
    public CompletableFuture<Ack> abandonEnter(AbandonedEnter event) {
        abandoned.add(event);
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
    }

    /** 按发起顺序完成最早的一个在途调用。 */
    void complete(ClientReply reply) {
        pending.remove().complete(reply);
    }

    void fail(Throwable error) {
        pending.remove().completeExceptionally(error);
    }
}
