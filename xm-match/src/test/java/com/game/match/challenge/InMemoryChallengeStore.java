package com.game.match.challenge;

import com.game.common.deadline.Deadline;
import com.game.match.testing.Faults;
import com.game.match.testing.ManualRedisClock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * {@link ChallengeStore} 的内存实现：切磋服务的组件测试拿它当存储。语义逐条对着 {@link ChallengeScripts} 的三段脚本写；真实现与它跑同一套
 * 契约测试（{@link ChallengeStoreContract}），保证替身不漂移。时间与 TTL 用 {@link ManualRedisClock}。
 *
 * <pre>
 * ManualRedisClock clock = new ManualRedisClock();
 * InMemoryChallengeStore store = new InMemoryChallengeStore(clock);
 * store.faults.failNext("invite:after");        // 发起已生效但调用方看到异常（结局不明）
 * store.faults.failNext("consume");             // 消费在执行之前失败
 * assertThat(store.pendingOn(1002)).hasValue(7001L);   // 目标身上的占坑指向哪条邀请
 * assertThat(store.calls).containsExactly("read(7001)", "consume(7001,1002,n-1)");
 * </pre>
 * 故障注入的操作名 = 接口方法名；写方法另有 {@code ":after"}。线程安全（一把锁）。
 */
final class InMemoryChallengeStore implements ChallengeStore {

    static final long TOMBSTONE_TTL_MS = RedissonChallengeStore.TOMBSTONE_TTL_MS;

    /** 故障注入。 */
    final Faults faults = new Faults();
    /** 调用序列。 */
    final List<String> calls = new CopyOnWriteArrayList<>();

    private final ManualRedisClock clock;
    private final Object lock = new Object();
    private final Map<Long, Held<ChallengeRecord>> records = new HashMap<>();
    private final Map<Long, Held<Long>> slots = new HashMap<>();
    private final Map<Long, Held<Tombstone>> tombstones = new HashMap<>();

    private record Held<T>(T value, long expiresAtMs) {
    }

    private record Tombstone(String nonce, ChallengeRecord record, long nowMs) {
    }

    InMemoryChallengeStore(ManualRedisClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---------------------------------------------------------------- 测试侧：看状态、摆状态

    /** 此刻的记录（不计入 {@link #calls}，不触发故障注入）。 */
    Optional<ChallengeRecord> recordOf(long challengeId) {
        synchronized (lock) {
            return Optional.ofNullable(live(records, challengeId));
        }
    }

    /** 目标身上的占坑指向的 challenge_id；没有为空。 */
    Optional<Long> pendingOn(long targetId) {
        synchronized (lock) {
            return Optional.ofNullable(live(slots, targetId));
        }
    }

    /** 这条邀请有没有消费墓碑。 */
    boolean tombstoned(long challengeId) {
        synchronized (lock) {
            return live(tombstones, challengeId) != null;
        }
    }

    /** 直接摆一条记录（不占坑）：用来摆出字段损坏的记录。 */
    InMemoryChallengeStore putRecord(long challengeId, ChallengeRecord record, long ttlMs) {
        synchronized (lock) {
            records.put(challengeId, new Held<>(record, clock.peekMs() + ttlMs));
            return this;
        }
    }

    // ---------------------------------------------------------------- ChallengeStore

    @Override
    public InviteResult invite(long challengeId, long challengerId, long targetId, int configId, long ttlMs, Deadline d) {
        return run("invite", "invite(" + challengeId + "," + challengerId + "," + targetId + ")", true, () -> {
            Long held = live(slots, targetId);
            if (held != null) {
                if (held != challengeId) {
                    return new InviteResult.Pending();
                }
                ChallengeRecord existing = live(records, challengeId);
                if (existing != null) {
                    return new InviteResult.Created(existing.expiresAtMs());
                }
            }
            long expiresAtMs = clock.peekMs() + ttlMs;
            slots.put(targetId, new Held<>(challengeId, expiresAtMs));
            records.put(challengeId, new Held<>(new ChallengeRecord(challengerId, targetId, configId, expiresAtMs), expiresAtMs));
            return new InviteResult.Created(expiresAtMs);
        });
    }

    @Override
    public void delete(long challengeId, long targetId, Deadline d) {
        run("delete", "delete(" + challengeId + "," + targetId + ")", true, () -> {
            records.remove(challengeId);
            Long held = live(slots, targetId);
            if (held != null && held == challengeId) {
                slots.remove(targetId);
            }
            return null;
        });
    }

    @Override
    public Optional<ChallengeRecord> read(long challengeId, Deadline d) {
        return run("read", "read(" + challengeId + ")", false, () -> Optional.ofNullable(live(records, challengeId)));
    }

    @Override
    public ConsumeResult consume(long challengeId, long responderId, String nonce, Deadline d) {
        return run("consume", "consume(" + challengeId + "," + responderId + "," + nonce + ")", true, () -> {
            Tombstone done = live(tombstones, challengeId);
            if (done != null && done.nonce().equals(nonce)) {
                return new ConsumeResult.Consumed(done.record(), done.nowMs());
            }
            ChallengeRecord record = live(records, challengeId);
            if (record == null) {
                return new ConsumeResult.Gone();
            }
            if (record.targetId() != responderId) {
                return new ConsumeResult.NotTarget();
            }
            long now = clock.peekMs();
            records.remove(challengeId);
            Long held = live(slots, responderId);
            if (held != null && held == challengeId) {
                slots.remove(responderId);
            }
            tombstones.put(challengeId, new Held<>(new Tombstone(nonce, record, now), now + TOMBSTONE_TTL_MS));
            return new ConsumeResult.Consumed(record, now);
        });
    }

    // ---------------------------------------------------------------- 内部

    private <T> T run(String op, String call, boolean write, Supplier<T> body) {
        calls.add(call);
        faults.check(op);
        T result;
        synchronized (lock) {
            result = body.get();
        }
        if (write) {
            faults.check(op + ":after");
        }
        return result;
    }

    private <T> T live(Map<Long, Held<T>> map, long key) {
        Held<T> held = map.get(key);
        if (held == null) {
            return null;
        }
        if (held.expiresAtMs() <= clock.peekMs()) {
            map.remove(key);
            return null;
        }
        return held.value();
    }
}
