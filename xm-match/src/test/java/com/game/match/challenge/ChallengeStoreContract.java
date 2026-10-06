package com.game.match.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.match.challenge.ChallengeStore.ChallengeRecord;
import com.game.match.challenge.ChallengeStore.ConsumeResult;
import com.game.match.challenge.ChallengeStore.InviteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link ChallengeStore} 的契约测试（match-spec §6.3 的三段脚本、§15.3「切磋的两个并发接受只有一个成功」）：内存替身与 Redis 实现跑同一套，
 * 保证组件测试里用的替身与真实现不漂移。子类提供存储、发新号的办法与「让存储的时间往前走」的办法。
 */
abstract class ChallengeStoreContract {

    static final long TTL_MS = 60_000;

    abstract ChallengeStore store();

    /** 一个没用过的 challenge_id（Redis 版用随机号，免得与别人的数据相撞）。 */
    abstract long newChallengeId();

    /** 一个没用过的玩家号。 */
    abstract long newPlayerId();

    /** 存储此刻的时间（Unix 毫秒）。 */
    abstract long storeNowMs();

    /** 让存储的时间走过 {@code ms}（内存版拨钟，Redis 版真的等）。 */
    abstract void elapse(long ms) throws InterruptedException;

    private static Deadline d() {
        return Deadline.after(5_000);
    }

    private long invite(long challengeId, long challenger, long target, int config, long ttlMs) {
        InviteResult result = store().invite(challengeId, challenger, target, config, ttlMs, d());
        assertThat(result).isInstanceOf(InviteResult.Created.class);
        return ((InviteResult.Created) result).expiresAtMs();
    }

    @Test
    void 发起_占坑并写下记录_过期时刻是存储时间加TTL() {
        long id = newChallengeId();
        long challenger = newPlayerId();
        long target = newPlayerId();

        long before = storeNowMs();
        long expiresAtMs = invite(id, challenger, target, 7, TTL_MS);
        long after = storeNowMs();

        assertThat(expiresAtMs).isBetween(before + TTL_MS, after + TTL_MS);
        assertThat(store().read(id, d())).hasValue(new ChallengeRecord(challenger, target, 7, expiresAtMs));
    }

    @Test
    void 目标已有别的待应答邀请_后来者pending_什么都不写() {
        long first = newChallengeId();
        long second = newChallengeId();
        long target = newPlayerId();
        long firstExpires = invite(first, newPlayerId(), target, 0, TTL_MS);

        InviteResult result = store().invite(second, newPlayerId(), target, 0, TTL_MS, d());

        assertThat(result).isEqualTo(new InviteResult.Pending());
        assertThat(store().read(second, d())).as("后来者没有留下记录").isEmpty();
        assertThat(store().read(first, d()).orElseThrow().expiresAtMs()).as("先到的邀请原样").isEqualTo(firstExpires);
    }

    @Test
    void 同一个号再发起一次_当作重放_返回第一次的过期时刻_不续期() throws Exception {
        long id = newChallengeId();
        long challenger = newPlayerId();
        long target = newPlayerId();
        long first = invite(id, challenger, target, 3, TTL_MS);
        elapse(40);

        long replayed = invite(id, challenger, target, 3, TTL_MS);

        assertThat(replayed).as("重发的脚本看到的是自己第一次写下的记录").isEqualTo(first);
        assertThat(store().read(id, d()).orElseThrow().expiresAtMs()).isEqualTo(first);
    }

    @Test
    void 清理_删记录并摘占坑_之后别人可以再向同一目标发起_清理幂等() {
        long id = newChallengeId();
        long target = newPlayerId();
        invite(id, newPlayerId(), target, 0, TTL_MS);

        store().delete(id, target, d());
        store().delete(id, target, d());

        assertThat(store().read(id, d())).isEmpty();
        long next = newChallengeId();
        assertThat(store().invite(next, newPlayerId(), target, 0, TTL_MS, d())).isInstanceOf(InviteResult.Created.class);
    }

    @Test
    void 清理_占坑已是别的邀请时不误摘() {
        long stale = newChallengeId();
        long current = newChallengeId();
        long target = newPlayerId();
        invite(current, newPlayerId(), target, 0, TTL_MS);

        store().delete(stale, target, d());

        assertThat(store().read(current, d())).as("别的邀请的记录不动").isPresent();
        assertThat(store().invite(newChallengeId(), newPlayerId(), target, 0, TTL_MS, d()))
                .as("占坑还在：目标仍有一条待应答的邀请").isEqualTo(new InviteResult.Pending());
    }

    @Test
    void 消费_一次性_返回记录与此刻的存储时间_记录与占坑都没了() {
        long id = newChallengeId();
        long challenger = newPlayerId();
        long target = newPlayerId();
        long expiresAtMs = invite(id, challenger, target, 9, TTL_MS);

        long before = storeNowMs();
        ConsumeResult first = store().consume(id, target, "nonce-a", d());
        long after = storeNowMs();
        ConsumeResult second = store().consume(id, target, "nonce-b", d());

        assertThat(first).isInstanceOf(ConsumeResult.Consumed.class);
        ConsumeResult.Consumed consumed = (ConsumeResult.Consumed) first;
        assertThat(consumed.record()).isEqualTo(new ChallengeRecord(challenger, target, 9, expiresAtMs));
        assertThat(consumed.redisNowMs()).isBetween(before, after);
        assertThat(second).as("另一个请求（nonce 不同）再消费：已经没有了").isEqualTo(new ConsumeResult.Gone());
        assertThat(store().read(id, d())).isEmpty();
        assertThat(store().invite(newChallengeId(), newPlayerId(), target, 0, TTL_MS, d()))
                .as("占坑已摘：目标可以再被邀请").isInstanceOf(InviteResult.Created.class);
    }

    @Test
    void 消费_同一个nonce再执行一次_从墓碑里原样返回第一次的结果() throws Exception {
        long id = newChallengeId();
        long target = newPlayerId();
        invite(id, newPlayerId(), target, 5, TTL_MS);

        ConsumeResult first = store().consume(id, target, "same-nonce", d());
        elapse(40);
        ConsumeResult replayed = store().consume(id, target, "same-nonce", d());

        assertThat(first).isInstanceOf(ConsumeResult.Consumed.class);
        assertThat(replayed).as("含第一次执行时的时间：过期判定不因重发而改变").isEqualTo(first);
    }

    @Test
    void 消费_应答者不是目标_记录与占坑原样保留() {
        long id = newChallengeId();
        long target = newPlayerId();
        long stranger = newPlayerId();
        invite(id, newPlayerId(), target, 0, TTL_MS);

        ConsumeResult result = store().consume(id, stranger, "nonce-x", d());

        assertThat(result).isEqualTo(new ConsumeResult.NotTarget());
        assertThat(store().read(id, d())).isPresent();
        assertThat(store().consume(id, target, "nonce-y", d())).as("真正的目标之后照常消费").isInstanceOf(ConsumeResult.Consumed.class);
    }

    @Test
    void 消费_从未有过的邀请_gone() {
        assertThat(store().consume(newChallengeId(), newPlayerId(), "nonce", d())).isEqualTo(new ConsumeResult.Gone());
        assertThat(store().read(newChallengeId(), d())).isEmpty();
    }

    @Test
    void TTL到期_记录读不到_占坑自动释放() throws Exception {
        long id = newChallengeId();
        long target = newPlayerId();
        invite(id, newPlayerId(), target, 0, 150);

        elapse(400);

        assertThat(store().read(id, d())).isEmpty();
        assertThat(store().consume(id, target, "late", d())).isEqualTo(new ConsumeResult.Gone());
        assertThat(store().invite(newChallengeId(), newPlayerId(), target, 0, TTL_MS, d())).isInstanceOf(InviteResult.Created.class);
    }

    @Test
    void 六十四位的号与uint32的配置号原样往返_不经浮点() {
        long id = newChallengeId() | Long.MIN_VALUE; // 最高位为 1：无符号十进制有 20 位，超出 double 的精确范围
        long challenger = newPlayerId() | Long.MIN_VALUE;
        long target = newPlayerId() | Long.MIN_VALUE;
        int config = 0xFFFF_FFF1;

        long expiresAtMs = invite(id, challenger, target, config, TTL_MS);
        ConsumeResult consumed = store().consume(id, target, "nonce", d());

        assertThat(consumed).isEqualTo(new ConsumeResult.Consumed(new ChallengeRecord(challenger, target, config, expiresAtMs),
                ((ConsumeResult.Consumed) consumed).redisNowMs()));
        assertThat(store().consume(id - 1, target, "nonce-2", d())).as("相邻的号不是同一条").isEqualTo(new ConsumeResult.Gone());
    }

    @Test
    void 并发的两条应答_只有一条拿到记录() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                long id = newChallengeId();
                long target = newPlayerId();
                invite(id, newPlayerId(), target, 0, TTL_MS);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<ConsumeResult>> futures = new ArrayList<>();
                for (String nonce : List.of("accept-1", "accept-2")) {
                    Callable<ConsumeResult> call = () -> {
                        start.await();
                        return store().consume(id, target, nonce, d());
                    };
                    futures.add(pool.submit(call));
                }
                start.countDown();

                List<ConsumeResult> results = new ArrayList<>();
                for (Future<ConsumeResult> future : futures) {
                    results.add(future.get(10, TimeUnit.SECONDS));
                }
                assertThat(results).as("第 %d 轮", round).filteredOn(r -> r instanceof ConsumeResult.Consumed).hasSize(1);
                assertThat(results).filteredOn(r -> r instanceof ConsumeResult.Gone).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
