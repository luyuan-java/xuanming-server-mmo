package com.game.match.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.SpectateRules;
import com.game.match.spectate.SpectateStore;
import com.game.match.spectate.SpectateStore.Acquire;
import com.game.match.spectate.SpectateStore.Eviction;
import com.game.match.spectate.SpectateStore.Pick;
import com.game.match.spectate.SpectateStore.Record;
import com.game.match.spectate.SpectateStoreContract;
import com.game.match.ticket.QueueRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 内存观战存储：先过 {@link SpectateStoreContract} 的全部用例（与 Redis 实现同一套断言——163 / 164 / 开局钩子的组件测试都建在这个替身上，
 * 它不许和真实现漂移），再加只有替身才有的东西：手拨时钟下精确的 TTL 与过期分界、与内存版票据 / 落点存储共享状态、故障注入的两个注入点、
 * 挂起、测试缝 {@code beforeAcquire}、调用序列与事件序列。
 */
class InMemorySpectateStoreTest extends SpectateStoreContract {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore(events);
    private final InMemorySpectateStore store = new InMemorySpectateStore(clock, tickets, placements, events);

    // ================================================================ 契约测试的钩子

    @Override
    protected SpectateStore store() {
        return store;
    }

    @Override
    protected long pid(int n) {
        return 1000 + n;
    }

    @Override
    protected long battle(int n) {
        return Long.MIN_VALUE + 7_000 + n; // ≥ 2^63：无符号十进制的写法也一并过一遍
    }

    @Override
    protected long nowMs() {
        return clock.peekMs();
    }

    @Override
    protected void givenTicket(long playerId) {
        tickets.enqueue(playerId, "ticket-" + playerId, new QueueRef(3, 0), 1, 150_000, 21_600_000, Deadline.after(1_000));
    }

    @Override
    protected void givenMark(long playerId, String value) {
        store.putMark(playerId, value);
    }

    @Override
    protected Optional<String> markOf(long playerId) {
        return store.markOf(playerId);
    }

    @Override
    protected long markTtlMs(long playerId) {
        return store.markTtlMs(playerId);
    }

    @Override
    protected void givenPlacement(BattlePlacement placement) {
        placements.put(placement);
    }

    @Override
    protected void givenCorruptPlacement(long battleId) {
        placements.corrupt(battleId);
    }

    @Override
    protected boolean placementExists(long battleId) {
        return placements.corrupted(battleId) || placements.stored(battleId).isPresent();
    }

    @Override
    protected Optional<BattlePlacement> placementOf(long battleId) {
        return placements.corrupted(battleId) ? Optional.empty() : placements.stored(battleId);
    }

    @Override
    protected void givenMember(String member, long score) {
        store.putWatchable(member, score);
    }

    @Override
    protected Map<String, Long> index() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String member : store.watchable()) {
            out.put(member, store.scoreOf(member).orElseThrow());
        }
        return out;
    }

    // ================================================================ 只有替身才有的：手拨时钟

    @Test
    void 标记的TTL恰好360秒_到点读不到_重放不刷新TTL() {
        String value = mark(77);
        store.acquire(1001, value, d());
        assertThat(store.markTtlMs(1001)).isEqualTo(360_000);

        clock.advanceSeconds(100);
        assertThat(store.acquire(1001, value, d())).isEqualTo(Acquire.OK);
        assertThat(store.markTtlMs(1001)).as("同值重放不是续期：还剩 260 s").isEqualTo(260_000);

        clock.advanceMs(259_999);
        assertThat(store.markOf(1001)).contains(value);
        clock.advanceMs(1);
        assertThat(store.markOf(1001)).as("360 s 整：过期").isEmpty();
        assertThat(store.entry(1001, d()).mark()).isEmpty();
        assertThat(store.acquire(1001, mark(78), d())).as("过期之后别的请求可以抢").isEqualTo(Acquire.OK);
        assertThat(store.markCount()).isEqualTo(1);
    }

    @Test
    void 过期分界恰好是存储时间减360秒_分界上的成员还算活着_过了1毫秒就挑不到也会被清扫() {
        long now = clock.peekMs();
        store.putWatchable(77, now - 360_000);
        store.putWatchable(78, now - 360_001);

        assertThat(((Pick.Member) store.pickRandom(0.0, d())).member()).as("78 已过期；77 恰在分界上").isEqualTo("77");
        assertThat(((Pick.Member) store.pickRandom(0.999, d())).member()).isEqualTo("77");
        assertThat(store.sweep(d())).isEqualTo(1);
        assertThat(store.watchable()).containsExactly("77");

        clock.advanceMs(1);
        assertThat(store.pickRandom(0.0, d())).isInstanceOf(Pick.None.class);
        assertThat(store.sweep(d())).isEqualTo(1);
        assertThat(store.watchableCount(d())).isZero();
    }

    @Test
    void 读到的时间就是手拨时钟_与票据存储同一个钟() {
        clock.set(1_900_000_000_123L);
        placements.put(placement(77, 1, 1_900_000_000_000L));

        assertThat(store.read(77, d()).redisNowMs()).isEqualTo(1_900_000_000_123L);
        assertThat(store.list(5, d()).redisNowMs()).isEqualTo(1_900_000_000_123L);
        assertThat(((Pick.None) store.pickRandom(0.3, d())).redisNowMs()).isEqualTo(1_900_000_000_123L);
        assertThat(store.clock()).isSameAs(clock);
        assertThat(store.tickets()).isSameAs(tickets);
        assertThat(store.placements()).isSameAs(placements);
    }

    // ================================================================ 与票据 / 落点存储共享状态

    @Test
    void 有没有票看的是内存票据存储里未过期的票_票过期之后就能抢标记() {
        tickets.createMatched(1001, "t-1001", 5, 1, 1, 150_000, 42_000, d());

        assertThat(store.entry(1001, d()).hasTicket()).isTrue();
        assertThat(store.acquire(1001, mark(77), d())).isEqualTo(Acquire.QUEUED);
        clock.advanceSeconds(43);
        assertThat(store.entry(1001, d()).hasTicket()).as("matched 票 42 s 到期").isFalse();
        assertThat(store.acquire(1001, mark(77), d())).isEqualTo(Acquire.OK);
    }

    @Test
    void 剔除删掉的落点记在落点存储的事件里_不算gather的delete() {
        placements.put(placement(77, 1, clock.peekMs()));
        store.putWatchable(77, clock.peekMs());

        assertThat(store.evict(new Eviction.Dead(77, 1), d())).isTrue();

        assertThat(placements.stored(77)).isEmpty();
        assertThat(placements.deletes).as("deletes 只记 gather 调的 PlacementStore.delete").isEmpty();
        assertThat(events).containsSubsequence("placement.evict:77", "spectate.evict:77");
    }

    @Test
    void 损坏的落点_Dead剔除无从比对attempt_不动_Missing也不动_公开也不登记() {
        placements.put(placement(77, 1, clock.peekMs())).corrupt(77);
        store.putWatchable(77, clock.peekMs());

        assertThat(store.read(77, d()).record()).isInstanceOf(Record.Corrupt.class);
        assertThat(store.evict(new Eviction.Dead(77, 1), d())).isFalse();
        assertThat(store.evict(new Eviction.Missing(77), d())).as("键在：不是缺记录").isFalse();
        assertThat(store.watchable()).containsExactly("77");
        store.evict(new Eviction.Invalid("77"), d());
        assertThat(store.publish(placement(77, 1, clock.peekMs()), d())).isFalse();
        assertThat(store.watchable()).isEmpty();
    }

    // ================================================================ 测试缝、故障注入、挂起

    @Test
    void beforeAcquire在原子判定之前调一次_模拟入口检查之后才建出票据与标记被并发请求抢走() {
        List<Long> seen = new CopyOnWriteArrayList<>();
        store.beforeAcquire = pid -> {
            seen.add(pid);
            tickets.createMatched(pid, "late-" + pid, 5, 1, 1, 150_000, 42_000, Deadline.after(1_000));
        };
        assertThat(store.entry(1001, d()).hasTicket()).as("入口检查时还没有票").isFalse();
        assertThat(store.acquire(1001, mark(77), d())).as("检查之后、抢标记之前建出了票（W2）").isEqualTo(Acquire.QUEUED);
        assertThat(store.markOf(1001)).isEmpty();

        String stolen = mark(78);
        store.beforeAcquire = pid -> store.putMark(pid, stolen);
        assertThat(store.acquire(1002, mark(77), d())).as("标记被并发请求抢走").isEqualTo(Acquire.BUSY);
        assertThat(store.markOf(1002)).contains(stolen);

        store.beforeAcquire = null;
        assertThat(store.acquire(1003, mark(77), d())).isEqualTo(Acquire.OK);
        assertThat(seen).as("每次 acquire 恰好一次").containsExactly(1001L);
    }

    @Test
    void 故障注入_执行之前失败什么都没写_after是已生效但调用方看到异常() {
        String value = mark(77);
        store.faults.failNext("acquire");
        assertThatThrownBy(() -> store.acquire(1001, value, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(store.markOf(1001)).as("之前失败：没写").isEmpty();

        store.faults.failNext("acquire:after");
        assertThatThrownBy(() -> store.acquire(1001, value, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(store.markOf(1001)).as("结局不明：其实写下了——调用方要按本次的值尽力释放").contains(value);
        assertThat(store.acquire(1001, value, d())).as("重发命中自己写的值").isEqualTo(Acquire.OK);

        store.faults.failNext("release:after");
        assertThatThrownBy(() -> store.release(1001, value, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(store.markOf(1001)).as("已经删了").isEmpty();

        store.putWatchable(77, clock.peekMs() - 400_000);
        store.faults.failNext("sweep:after").failNext("read").failNext("list").failNext("marksOf").failNext("readPlacements").failNext("entry")
                .failNext("pickRandom").failNext("watchableCount").failNext("evict").failNext("publish");
        assertThatThrownBy(() -> store.sweep(d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(store.watchable()).as("sweep:after：已经摘了").isEmpty();
        assertThatThrownBy(() -> store.read(77, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.list(5, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.marksOf(List.of(1001L), d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.readPlacements(List.of(77L), d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.entry(1001, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.pickRandom(0.5, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.watchableCount(d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.evict(new Eviction.Invalid("x"), d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.publish(placement(77, 1, 1), d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(store.list(5, d()).members()).as("一次性的故障用完即止").isEmpty();
    }

    @Test
    void 异步操作当场生效_注入故障时静默丢弃并记下_永不抛() {
        String value = mark(77);
        store.putMark(1001, value);
        store.putWatchable("junk", 1);

        store.faults.failNext("releaseAsync").failNext("evictAsync");
        store.releaseAsync(1001, value);
        store.evictAsync(List.of(new Eviction.Invalid("junk")));
        assertThat(store.markOf(1001)).as("尽力而为：这一次没删成").contains(value);
        assertThat(store.watchable()).containsExactly("junk");
        assertThat(store.droppedAsync).hasSize(2).first().asString().startsWith("releaseAsync(1001,");

        store.releaseAsync(1001, value);
        store.evictAsync(List.of(new Eviction.Invalid("junk")));
        assertThat(store.markOf(1001)).as("没有故障：在调用线程上当场生效").isEmpty();
        assertThat(store.watchable()).isEmpty();
    }

    @Test
    void 挂起_同步调用等到截止抛异常且没有生效_放行之后照常() throws Exception {
        store.putWatchable("junk", 1);
        store.hang("evict");

        long started = System.nanoTime();
        assertThatThrownBy(() -> store.evict(new Eviction.Invalid("junk"), Deadline.after(120))).isInstanceOf(Deadline.DependencyException.class)
                .hasMessageContaining("超过请求预算");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(100L, 5_000L);
        assertThat(store.watchable()).as("挂起到点：没有生效").containsExactly("junk");

        CompletableFuture<Boolean> blocked = CompletableFuture.supplyAsync(() -> store.evict(new Eviction.Invalid("junk"), Deadline.after(10_000)));
        store.resume("evict");
        assertThat(blocked.get(5, TimeUnit.SECONDS)).as("放行之后照常执行").isTrue();
        assertThat(store.watchable()).isEmpty();
        assertThat(store.evict(new Eviction.Invalid("junk"), d())).as("没挂起的时候不等").isFalse();
    }

    @Test
    void 挂起_异步调用立刻返回_效果攒到放行时按发出的次序生效() {
        String value = mark(77);
        store.putMark(1001, value);
        store.putWatchable("junk", 1);
        store.putWatchable("junk2", 2);
        store.hang("evictAsync").hang("releaseAsync");

        store.evictAsync(List.of(new Eviction.Invalid("junk")));
        store.evictAsync(List.of(new Eviction.Invalid("junk2")));
        store.releaseAsync(1001, value);

        assertThat(store.watchable()).as("调用已经返回，剔除还没发生：164 的应答不等它").containsExactly("junk2", "junk");
        assertThat(store.markOf(1001)).contains(value);
        assertThat(store.calls).filteredOn(call -> call.startsWith("evictAsync")).hasSize(2);

        store.resume("evictAsync");
        assertThat(store.watchable()).isEmpty();
        assertThat(store.markOf(1001)).as("另一个操作还挂着").contains(value);
        store.resume("releaseAsync").resume("从没挂起过的名字");
        assertThat(store.markOf(1001)).isEmpty();
    }

    // ================================================================ 调用序列与事件序列

    @Test
    void 调用序列每次调用一条_事件序列只记生效了的操作() {
        String value = mark(77);
        placements.put(placement(77, 1, clock.peekMs()));

        store.entry(1001, d());
        store.read(77, d());
        store.acquire(1001, value, d());
        store.acquire(1001, mark(78), d());
        store.publish(placement(77, 1, clock.peekMs()), d());
        store.publish(placement(77, 2, clock.peekMs()), d());
        store.release(1001, mark(77), d());
        store.release(1001, value, d());
        store.evict(new Eviction.Missing(77), d());
        store.list(20, d());

        assertThat(store.calls).containsExactly("entry(1001)", "read(77)", "acquire(1001," + value + ")",
                store.calls.get(3), "publish(77#1)", "publish(77#2)", store.calls.get(6), "release(1001," + value + ")",
                "evict(Missing[battleId=77])", "list(20)");
        assertThat(store.calls.get(3)).startsWith("acquire(1001,78:");
        assertThat(events).as("BUSY 的抢占、条件不成立的公开、值不等的释放、没摘到的剔除都不产生事件")
                .containsExactly("spectate.entry:1001", "spectate.read:77", "spectate.acquire:1001", "spectate.publish:77", "spectate.release:1001");
    }

    @Test
    void 入参不合法是调用方的错_r越界_空标记值_attempt为0() {
        assertThatThrownBy(() -> store.pickRandom(1.0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.pickRandom(-0.1, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.pickRandom(Double.NaN, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.acquire(1001, "", d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.release(1001, null, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Eviction.Dead(77, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Eviction.Stale(Long.MIN_VALUE + 5, 1).member()).as("成员一律是无符号十进制").isEqualTo("9223372036854775813");
        assertThat(new Eviction.Invalid("abc").member()).isEqualTo("abc");
        assertThat(SpectateRules.member(-1L)).isEqualTo("18446744073709551615");
    }
}
