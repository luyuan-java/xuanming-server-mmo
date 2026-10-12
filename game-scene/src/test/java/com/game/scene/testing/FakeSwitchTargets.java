package com.game.scene.testing;

import com.game.scene.world.RemoteSwitchTargets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** 选目标的假实现：请求挂起，测试决定何时、以什么结果完成（模拟结果投递回逻辑线程）。 */
public final class FakeSwitchTargets implements RemoteSwitchTargets {

    /** 一次挂起的选目标请求。 */
    public record PendingSelect(long playerId, long fromSceneId, long wantSceneId, int wantSceneConfigId,
                                Consumer<Selection> callback) {

        public void complete(Selection selection) {
            callback.accept(selection);
        }

        public void chosen(int nodeId, long sceneId, int configId) {
            complete(new Selection.Chosen(nodeId, sceneId, configId));
        }
    }

    private final Deque<PendingSelect> pending = new ArrayDeque<>();

    @Override
    public void select(long playerId, long fromSceneId, long wantSceneId, int wantSceneConfigId,
                       Consumer<Selection> onDone) {
        pending.add(new PendingSelect(playerId, fromSceneId, wantSceneId, wantSceneConfigId, onDone));
    }

    /** 取出最早一个挂起的请求。 */
    public PendingSelect take() {
        PendingSelect p = pending.poll();
        if (p == null) {
            throw new IllegalStateException("没有挂起的选目标请求");
        }
        return p;
    }

    public int pendingCount() {
        return pending.size();
    }
}
