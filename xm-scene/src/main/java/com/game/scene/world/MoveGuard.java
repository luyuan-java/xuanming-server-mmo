package com.game.scene.world;

import java.util.Optional;

/**
 * 一个玩家的位移校验（防瞬移 / 加速）：令牌桶限制「相邻两次被接受的上报位置」之间的水平位移。只在场景逻辑线程上使用。
 *
 * <p><b>规则</b>：额度按服务器单调时钟以 {@link #RATE} 米/秒累积，封顶 {@link #CAPACITY} 米。一次上报相对上次接受位置
 * （锚点）的水平位移不超过当前额度就原样接受并扣掉这段位移；超过就把<b>水平分量</b>沿上报方向只走到额度用完的位置，
 * 额度清零——调用方按水平偏差决定是否回 137 纠偏。
 *
 * <p><b>高度不校验、原样取上报值</b>（fail-open）：Java 版没有导航网格，高度无从校验（与基线无导航场景一致）。
 * 额度内的上报本来就原样接受任意高度，截断时同样取上报高度，本类对 z 没有任何规则。
 * 高度若也按比例插值，{@code z + (to.z - z) × f} 的减法会从两个有限的极端输入溢出成 ±Inf（f = 0 时再得 NaN）；
 * 坐标范围由调用方先按 {@link MovementRules#WORLD_LIMIT} 挡掉，本类仍自证输出有限（见 {@link #admit}）。
 *
 * <p><b>为什么是令牌桶</b>：按「本次与上次的到达间隔 × 速度」逐条判会误伤网络抖动——几条 MoveSync 被延迟后挤在一起到达，
 * 后面几条的到达间隔接近 0，位移却是正常的 250ms 步长。桶把「晚到的时间」存成额度，挤在一起的包依次消耗；
 * 封顶则防止「站着攒够额度再一次瞬移」，最多透支 {@link #CAPACITY}。
 *
 * <p><b>取值</b>：{@link #RATE} = 信任上限 10 m/s × 1.2（客户端基础移速 9 m/s，基线 kMaxTrustedClientSpeed 已含约 10% 余量，
 * 再给时钟速率与帧抖动留 20%）；{@link #CAPACITY} = 2 秒的额度（24 m），吸收 2 秒以内的延迟堆积。
 *
 * <p>基线没有任何位移校验（无导航网格时上报坐标原样接受），这是 Java 版有意更严的地方，见 PARITY「移动」行。
 */
final class MoveGuard {

    /** 额度累积速率（米/秒）。 */
    static final double RATE = MovementRules.MAX_TRUSTED_SPEED * 1.2;
    /** 额度上限（米）：2 秒的累积量。 */
    static final double CAPACITY = RATE * 2.0;

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private Vec3 anchor;
    private double allowance;
    private long lastNanos;

    /** 以 {@code anchor} 为起点、额度满格。 */
    MoveGuard(Vec3 anchor, long nowNanos) {
        reset(anchor, nowNanos);
    }

    /**
     * 服务器把玩家放到某处（进场落位、换场景）：锚点改到那里、额度回满。之后的上报都相对新位置判。
     */
    void reset(Vec3 anchor, long nowNanos) {
        this.anchor = anchor;
        this.allowance = CAPACITY;
        this.lastNanos = nowNanos;
    }

    /**
     * 裁决一次上报位置，返回被接受的位置（未超额度时就是 {@code reported} 本身），并把锚点移到那里。
     *
     * <p>先算出候选位置、确认三个分量都有限，才提交锚点与额度；候选不有限（上报本身非有限，或坐标大到差值溢出）就返回空，
     * 锚点与额度都不动（额度照常按时间累积），调用方整条丢弃这次输入。所以本方法<b>永远不会</b>给出或记住非有限的位置。
     * 水平距离溢出成 Infinity 时截断比例为 0，停在锚点的水平位置。
     */
    Optional<Vec3> admit(Vec3 reported, long nowNanos) {
        refill(nowNanos);
        double wanted = anchor.horizontalDistance(reported);
        Vec3 candidate;
        double spent;
        if (wanted <= allowance) {
            candidate = reported;
            spent = wanted;
        } else {
            double fraction = allowance / wanted;
            candidate = new Vec3(anchor.x() + (reported.x() - anchor.x()) * fraction,
                    anchor.y() + (reported.y() - anchor.y()) * fraction,
                    reported.z());
            spent = allowance;
        }
        if (!candidate.isFinite()) {
            return Optional.empty();
        }
        allowance -= spent;
        anchor = candidate;
        return Optional.of(candidate);
    }

    Vec3 anchor() {
        return anchor;
    }

    double allowance() {
        return allowance;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastNanos;
        if (elapsed > 0) {
            allowance = Math.min(CAPACITY, allowance + RATE * (elapsed / NANOS_PER_SECOND));
            lastNanos = nowNanos;
        }
    }
}
