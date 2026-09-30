package com.game.login.dispatch;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「同一个键同一时刻只允许一个在途操作」的进程内闸门（对应 mmorpg 的 {@code account_lock:*} / {@code player_locker:*}，
 * 只试一次不排队，拿不到由调用方回 2005 kLoginInProgress）。
 *
 * <p>范围只在本进程：多个 xm-login 实例之间不互斥（见交付说明的剩余风险）。线程安全。
 *
 * @param <K> 键类型（账号、player_id）
 */
public final class InFlightKeys<K> {

    private final Set<K> keys = ConcurrentHashMap.newKeySet();

    /** 占用成功返回 true；调用方必须在操作结束（含异步链结束）后 {@link #release}。 */
    public boolean tryAcquire(K key) {
        return keys.add(key);
    }

    public void release(K key) {
        keys.remove(key);
    }

    public boolean isHeld(K key) {
        return keys.contains(key);
    }
}
