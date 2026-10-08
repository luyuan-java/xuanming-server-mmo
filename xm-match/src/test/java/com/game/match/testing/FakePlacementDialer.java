package com.game.match.testing;

import com.game.api.BattleNodeService;
import com.game.common.deadline.Deadline;
import com.game.match.placement.PlacementDialer;
import com.game.match.proto.BattlePlacement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * {@link PlacementDialer} 的测试替身：给「用直拨结果做判定」的调用方（补签 179 的六行、6.5 的观众 RPC）用，不涉及真的连接与异常分类——
 * 那部分由真实现配 {@link FakeNodeCalls} 与真 Triple 回环去测。
 *
 * <pre>
 * FakeBattleNode battle = new FakeBattleNode();
 * FakePlacementDialer dialer = new FakePlacementDialer(battle);    // 缺省：调通，应答来自这个假 battle 节点
 * dialer.roomGone();                                                // 之后一律：建连失败且同号已换实例
 * dialer.unavailable(PlacementDialer.Kind.TIMEOUT);                 // 之后一律：超时
 * dialer.reachable();                                               // 恢复成调通
 * assertThat(dialer.dials).singleElement().satisfies(d -> assertThat(d.timeout()).isEqualTo(Duration.ofSeconds(3)));
 * assertThat(dialer.dials.get(0).hardStop()).isNull();              // 179 用不带硬截止的重载；观众 RPC 的那一条非 null
 * </pre>
 * 带硬截止的重载：截止已过时不调假节点、回 {@code Unavailable(NOT_DELIVERED)}（同真实现）；其余与不带截止的相同，只是等假节点应答的时间不超过截止的剩余。
 */
public final class FakePlacementDialer implements PlacementDialer {

    /**
     * 一次直拨的入参。
     *
     * @param hardStop 带硬截止的重载传进来的截止；不带硬截止的重载为 null
     */
    public record Dialed(BattlePlacement placement, Duration timeout, Deadline hardStop) {
    }

    /** 每次直拨，按调用顺序。 */
    public final List<Dialed> dials = new CopyOnWriteArrayList<>();
    private final BattleNodeService node;
    private volatile Kind unavailable;
    private volatile boolean roomGone;

    /** @param node 调通时把调用交给它（通常是 {@link FakeBattleNode}） */
    public FakePlacementDialer(BattleNodeService node) {
        this.node = node;
    }

    public FakePlacementDialer reachable() {
        this.roomGone = false;
        this.unavailable = null;
        return this;
    }

    public FakePlacementDialer roomGone() {
        this.roomGone = true;
        this.unavailable = null;
        return this;
    }

    public FakePlacementDialer unavailable(Kind kind) {
        this.roomGone = false;
        this.unavailable = kind;
        return this;
    }

    @Override
    public <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Function<BattleNodeService, CompletableFuture<R>> call) {
        dials.add(new Dialed(placement, timeout, null));
        return outcome(Math.max(1, timeout.toMillis()), call);
    }

    @Override
    public <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Deadline hardStop, Function<BattleNodeService, CompletableFuture<R>> call) {
        dials.add(new Dialed(placement, timeout, hardStop));
        if (hardStop.expired()) {
            return new Dial.Unavailable<>(Kind.NOT_DELIVERED, "硬截止已到，没有发出调用");
        }
        return outcome(Math.max(1, Math.min(timeout.toMillis(), hardStop.remainingMillis())), call);
    }

    private <R> Dial<R> outcome(long waitMs, Function<BattleNodeService, CompletableFuture<R>> call) {
        if (roomGone) {
            return new Dial.RoomGone<>();
        }
        Kind kind = unavailable;
        if (kind != null) {
            return new Dial.Unavailable<>(kind, "注入的故障: " + kind);
        }
        try {
            R reply = call.apply(node).get(waitMs, TimeUnit.MILLISECONDS);
            return reply == null ? new Dial.Unavailable<>(Kind.OTHER, "应答为空") : new Dial.Replied<>(reply);
        } catch (java.util.concurrent.TimeoutException e) {
            return new Dial.Unavailable<>(Kind.TIMEOUT, "假节点没有在时限内应答");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Dial.Unavailable<>(Kind.OTHER, "被中断");
        } catch (Exception e) {
            return new Dial.Unavailable<>(Kind.OTHER, String.valueOf(e.getCause() == null ? e : e.getCause()));
        }
    }
}
