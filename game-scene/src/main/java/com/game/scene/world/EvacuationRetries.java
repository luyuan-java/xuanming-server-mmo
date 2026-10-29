package com.game.scene.world;

import java.util.IdentityHashMap;
import java.util.Map;

/** 场景逻辑线程独占。玩家实例离开即移除，重登不能继承旧实例的在途状态。 */
final class EvacuationRetries {
    private record Retry(int failures, long readyAtNanos) {}
    private final Map<ScenePlayer, Retry> retries = new IdentityHashMap<>();
    private long storageBlockedUntil;
    private boolean storageBlocked;

    boolean ready(ScenePlayer player, long now) {
        Retry retry = retries.get(player);
        return retry == null || now - retry.readyAtNanos() >= 0;
    }

    void failed(ScenePlayer player, long now, EvacuationPolicy policy) {
        Retry previous = retries.get(player);
        int count = previous == null ? 1 : Math.min(32, previous.failures() + 1);
        retries.put(player, new Retry(count, now + policy.retryNanos(count)));
    }

    void storageUncertain(long now, EvacuationPolicy policy) {
        storageBlockedUntil = now + policy.retryMaximum().toNanos();
        storageBlocked = true;
    }

    boolean storageReady(long now) {
        if (storageBlocked && now - storageBlockedUntil >= 0) storageBlocked = false;
        return !storageBlocked;
    }

    void forget(ScenePlayer player) { retries.remove(player); }
    void clearPlayers() { retries.clear(); }
    void clear() { retries.clear(); storageBlocked = false; }
}
