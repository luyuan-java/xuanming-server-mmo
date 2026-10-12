package com.game.scene.testing;

import com.game.scene.world.InstanceIds;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** 实例取号（scene-manager {@code createInstance}）的假实现：请求挂起，测试决定何时、以什么结果完成（模拟结果投递回逻辑线程）。 */
public final class FakeInstanceIds implements InstanceIds {

    /** 一次挂起的取号请求。 */
    public record PendingCreate(Request request, Consumer<Result> callback) {

        public void complete(Result result) {
            callback.accept(result);
        }

        /** 发了号，放在 {@code nodeId}。 */
        public void issued(int nodeId, long sceneId) {
            complete(new Result.Issued(nodeId, sceneId));
        }
    }

    private final Deque<PendingCreate> pending = new ArrayDeque<>();

    @Override
    public void create(Request request, Consumer<Result> onDone) {
        pending.add(new PendingCreate(request, onDone));
    }

    /** 取出最早一个挂起的请求。 */
    public PendingCreate take() {
        PendingCreate p = pending.poll();
        if (p == null) {
            throw new IllegalStateException("没有挂起的取号请求");
        }
        return p;
    }

    public int pendingCount() {
        return pending.size();
    }
}
