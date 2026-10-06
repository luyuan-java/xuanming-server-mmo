package com.game.match.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.challenge.ChallengeStore.ConsumeResult;
import com.game.match.challenge.ChallengeStore.InviteResult;
import com.game.match.testing.ManualRedisClock;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 内存替身跑 {@link ChallengeStoreContract}（与 Redis 实现同一套），另钉替身自己的故障注入：切磋服务的组件测试靠它模拟
 * 「执行前失败」与「已生效但应答丢了」。
 */
class InMemoryChallengeStoreTest extends ChallengeStoreContract {

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryChallengeStore store = new InMemoryChallengeStore(clock);
    private final AtomicLong nextChallenge = new AtomicLong(7001);
    private final AtomicLong nextPlayer = new AtomicLong(1001);

    @Override
    ChallengeStore store() {
        return store;
    }

    @Override
    long newChallengeId() {
        return nextChallenge.getAndIncrement();
    }

    @Override
    long newPlayerId() {
        return nextPlayer.getAndIncrement();
    }

    @Override
    long storeNowMs() {
        return clock.peekMs();
    }

    @Override
    void elapse(long ms) {
        clock.advanceMs(ms);
    }

    @Test
    void 故障注入_执行前失败什么都不写_after是已生效但调用方看到异常() {
        Deadline d = Deadline.after(1000);
        store.faults.failNext("invite");
        assertThatThrownBy(() -> store.invite(7001, 1001, 1002, 0, 60_000, d)).isInstanceOf(Deadline.DependencyException.class);
        assertThat(store.pendingOn(1002)).as("执行前失败：没有占坑").isEmpty();

        store.faults.failNext("invite:after");
        assertThatThrownBy(() -> store.invite(7002, 1001, 1002, 0, 60_000, d)).isInstanceOf(Deadline.DependencyException.class);
        assertThat(store.pendingOn(1002)).as("结局不明：其实已经占了坑").hasValue(7002L);
        assertThat(store.recordOf(7002)).isPresent();

        store.faults.failNext("consume:after");
        assertThatThrownBy(() -> store.consume(7002, 1002, "n-1", d)).isInstanceOf(Deadline.DependencyException.class);
        assertThat(store.recordOf(7002)).as("结局不明：其实已经消费").isEmpty();
        assertThat(store.tombstoned(7002)).isTrue();
        assertThat(store.consume(7002, 1002, "n-1", d)).as("同一个 nonce 重发：拿回第一次的结果").isInstanceOf(ConsumeResult.Consumed.class);

        assertThat(store.calls).containsExactly("invite(7001,1001,1002)", "invite(7002,1001,1002)", "consume(7002,1002,n-1)",
                "consume(7002,1002,n-1)");
    }

    @Test
    void 墓碑六十秒后过期_之后同一个nonce也拿不回结果() {
        Deadline d = Deadline.after(1000);
        assertThat(store.invite(7001, 1001, 1002, 0, 60_000, d)).isInstanceOf(InviteResult.Created.class);
        assertThat(store.consume(7001, 1002, "n-1", d)).isInstanceOf(ConsumeResult.Consumed.class);

        clock.advanceMs(InMemoryChallengeStore.TOMBSTONE_TTL_MS);

        assertThat(store.tombstoned(7001)).isFalse();
        assertThat(store.consume(7001, 1002, "n-1", d)).isEqualTo(new ConsumeResult.Gone());
    }
}
