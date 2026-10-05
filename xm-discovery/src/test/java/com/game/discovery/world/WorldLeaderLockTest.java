package com.game.discovery.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@link WorldLeaderLock} 的有效期与降级（不连 Redis；门面用替身，单调时钟手动拨动）：2/3 TTL 判有效、续期回 0 立即降级、
 * 续期出错到 2/3 TTL 自动失效、出错超过 TTL 视为丢失、写入回 −1 降级、停服放锁（scene-channels-spec §9.3 WorldLeaderLockTest）。
 */
class WorldLeaderLockTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final long SEC = 1_000_000_000L;

    private final long[] now = {5_000 * SEC};
    private final FakeStore store = new FakeStore();
    private final WorldLeaderLock lock = new WorldLeaderLock(store, 7, "inst:tok", TTL, () -> now[0]);

    @Test
    void 竞选成功后_2_3_TTL_内有效_之后无效_续期后恢复() {
        store.acquire.add(() -> true);
        assertThat(lock.isValid()).isFalse();
        assertThat(lock.tryAcquire()).isTrue();
        assertThat(lock.isHeld()).isTrue();
        assertThat(lock.isValid()).isTrue();
        now[0] += 19 * SEC;
        assertThat(lock.isValid()).isTrue();
        now[0] += SEC;                       // 恰好 20 s = 2/3 TTL
        assertThat(lock.isValid()).isFalse();
        assertThat(lock.isHeld()).isTrue();
        store.renew.add(() -> true);
        assertThat(lock.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.RENEWED);
        assertThat(lock.isValid()).isTrue();
        assertThat(lock.renewInterval()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 有效期从命令发出前起算() {
        // 竞选往返耗时 3 s：有效期起点是发出前的时刻，不是返回时刻
        store.acquire.add(() -> {
            now[0] += 3 * SEC;
            return true;
        });
        lock.tryAcquire();
        now[0] += 17 * SEC - 1;              // 距发出 20 s 差 1 ns
        assertThat(lock.isValid()).isTrue();
        now[0] += 1;
        assertThat(lock.isValid()).isFalse();
    }

    @Test
    void 竞选失败不持有_持有中竞选失败即降级() {
        store.acquire.add(() -> false);
        assertThat(lock.tryAcquire()).isFalse();
        assertThat(lock.isHeld()).isFalse();
        assertThat(lock.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.NOT_HELD);

        store.acquire.add(() -> true);
        store.acquire.add(() -> false);
        lock.tryAcquire();
        assertThat(lock.tryAcquire()).isFalse();
        assertThat(lock.isHeld()).isFalse();
        assertThat(lock.isValid()).isFalse();
    }

    @Test
    void 续期返回_0_立即降级() {
        store.acquire.add(() -> true);
        lock.tryAcquire();
        store.renew.add(() -> false);
        assertThat(lock.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.LOST);
        assertThat(lock.isHeld()).isFalse();
        assertThat(lock.isValid()).isFalse();
    }

    @Test
    void 续期出错保持到_2_3_TTL_出错超过_TTL_视为丢失() {
        store.acquire.add(() -> true);
        lock.tryAcquire();
        now[0] += 10 * SEC;
        store.renew.add(() -> {
            throw new IllegalStateException("Redis 断开");
        });
        assertThat(lock.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.FAILED);
        assertThat(lock.isValid()).isTrue();
        now[0] += 10 * SEC;
        store.renew.add(() -> {
            throw new IllegalStateException("Redis 断开");
        });
        assertThat(lock.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.FAILED);
        assertThat(lock.isValid()).isFalse();   // 20 s 未续上：失效，但仍认为持有（Redis 恢复后续期可救回）
        assertThat(lock.isHeld()).isTrue();
        now[0] += 10 * SEC + 1;
        store.renew.add(() -> {
            throw new IllegalStateException("Redis 断开");
        });
        assertThat(lock.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.LOST);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void 续期出错后恢复_有效期重新起算() {
        store.acquire.add(() -> true);
        lock.tryAcquire();
        now[0] += 25 * SEC;
        store.renew.add(() -> {
            throw new IllegalStateException("Redis 断开");
        });
        lock.renew();
        assertThat(lock.isValid()).isFalse();
        store.renew.add(() -> true);
        assertThat(lock.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.RENEWED);
        assertThat(lock.isValid()).isTrue();
    }

    @Test
    void 写入被围栏后降级_在途续期不复活() {
        store.acquire.add(() -> true);
        lock.tryAcquire();
        store.renew.add(() -> {
            lock.markLost("写入回 -1");       // 续期往返途中被降级
            return true;
        });
        assertThat(lock.renew()).isEqualTo(WorldLeaderLock.RenewOutcome.NOT_HELD);
        assertThat(lock.isHeld()).isFalse();
        assertThat(lock.isValid()).isFalse();
    }

    @Test
    void 放锁先降级再删属于本令牌的键_出错只告警() {
        store.acquire.add(() -> true);
        lock.tryAcquire();
        store.release.add(() -> true);
        lock.release();
        assertThat(lock.isHeld()).isFalse();
        assertThat(store.released).containsExactly("7/inst:tok");
        store.release.add(() -> {
            throw new IllegalStateException("Redis 断开");
        });
        lock.release();                      // 不抛
    }

    @Test
    void 竞选出错原样抛出_持有状态不变() {
        store.acquire.add(() -> {
            throw new IllegalStateException("Redis 断开");
        });
        assertThatThrownBy(lock::tryAcquire).isInstanceOf(IllegalStateException.class);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void 令牌带实例号且每次不同_参数校验() {
        String a = WorldLeaderLock.newToken("sm-1");
        String b = WorldLeaderLock.newToken("sm-1");
        assertThat(a).startsWith("sm-1:").isNotEqualTo(b);
        assertThatThrownBy(() -> new WorldLeaderLock(store, 1, "", TTL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldLeaderLock(store, 1, "t", Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 只实现领导锁三个方法的替身：每次调用按队列给结果。 */
    private static final class FakeStore implements WorldChannelStore {
        final Deque<Supplier<Boolean>> acquire = new ArrayDeque<>();
        final Deque<Supplier<Boolean>> renew = new ArrayDeque<>();
        final Deque<Supplier<Boolean>> release = new ArrayDeque<>();
        final List<String> released = new ArrayList<>();

        @Override
        public boolean tryAcquireLeader(int zoneId, String token, Duration ttl) {
            return acquire.remove().get();
        }

        @Override
        public boolean renewLeader(int zoneId, String token, Duration ttl) {
            return renew.remove().get();
        }

        @Override
        public boolean releaseLeader(int zoneId, String token) {
            boolean r = release.remove().get();
            released.add(zoneId + "/" + token);
            return r;
        }

        @Override
        public long planVersion(int zoneId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorldPlan readPlan(int zoneId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorldPlanSnapshot snapshot(int zoneId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorldPlanWriteResult write(int zoneId, String leaderToken, WorldPlanBatch batch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReservationPick reserve(int zoneId, List<ReservationCandidate> candidates, long playerId, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reserveScene(int zoneId, long sceneId, long playerId, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean releaseReservation(int zoneId, long sceneId, long playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Long> countReservations(int zoneId, List<Long> sceneIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerZone(int zoneId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Integer> zones() {
            throw new UnsupportedOperationException();
        }
    }
}
