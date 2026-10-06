package com.game.match.matcher;

import com.game.match.MatchProperties;

/**
 * 凑单的评分容差曲线（纯函数；match-spec §2.8，基线 {@code rating.go:154-233}）。锚点等得越久，愿意接受的分差越大：
 * <pre>
 * 曲线   tol = min(max, base + ⌊已等秒数 / stepSeconds⌋ × stepDelta)        缺省 100 起、每 5 s +100、封顶 1000
 * 兜底   已等 ≥ maxWaitSeconds（缺省 90 s）→ 容差无穷大（纯等待序）；非评分模式恒为无穷大
 * </pre>
 * 缺省曲线逐档：0–4 s ±100、5–9 s ±200、10–14 s ±300、20–24 s ±500、30–34 s ±700、40–44 s ±900、≥ 45 s ±1000、≥ 90 s ∞。
 *
 * <p><b>单位</b>：配置与 {@link #curvePoints} 是评分点；票据、评分镜像里的评分是 centi（× 100），所以凑单比较用 {@link #anchorCenti}
 * 与 {@link #distance}，都是 centi 的整数运算（基线是两位小数的浮点，换算后逐值相同）。
 *
 * <p>五个参数 ≤ 0 一律取缺省值（同基线「0 不是合法容差、漏配按默认」）；终态兜底不能关闭——曲线有上限，没有兜底的话分差超过上限的两个人
 * 会一直饿到票据过期。不可变、线程安全。
 */
public final class Tolerance {

    /** 容差无穷大：任何分差都接受。 */
    public static final long UNBOUNDED = Long.MAX_VALUE;

    static final int DEFAULT_BASE = 100;
    static final int DEFAULT_STEP_SECONDS = 5;
    static final int DEFAULT_STEP_DELTA = 100;
    static final int DEFAULT_MAX = 1000;
    static final int DEFAULT_MAX_WAIT_SECONDS = 90;
    /** 评分点 → centi。 */
    private static final long CENTI = 100;

    private final long base;
    private final long stepSeconds;
    private final long stepDelta;
    private final long max;
    private final long maxWaitSeconds;

    /** 五个参数的单位见类注释；≤ 0 取缺省值。 */
    public Tolerance(int base, int stepSeconds, int stepDelta, int max, int maxWaitSeconds) {
        this.base = positiveOr(base, DEFAULT_BASE);
        this.stepSeconds = positiveOr(stepSeconds, DEFAULT_STEP_SECONDS);
        this.stepDelta = positiveOr(stepDelta, DEFAULT_STEP_DELTA);
        this.max = positiveOr(max, DEFAULT_MAX);
        this.maxWaitSeconds = positiveOr(maxWaitSeconds, DEFAULT_MAX_WAIT_SECONDS);
    }

    /** 按 {@code xm.match.rating.tolerance.*} 建（那边已把 0 / 漏配换成缺省值）。 */
    public static Tolerance of(MatchProperties.Tolerance configured) {
        return new Tolerance(configured.base(), configured.stepSeconds(), configured.stepDelta(), configured.max(), configured.maxWaitSeconds());
    }

    /** 缺省曲线（100 / 5 s / 100 / 1000 / 90 s）。 */
    public static Tolerance defaults() {
        return new Tolerance(0, 0, 0, 0, 0);
    }

    /**
     * 曲线上的容差（评分点）：{@code min(max, base + ⌊wait / stepSeconds⌋ × stepDelta)}。负的等待按 0；{@code max < base} 时恒为 {@code max}
     * （同基线：先算后封顶）。不含终态兜底。
     */
    public long curvePoints(long waitSeconds) {
        if (max <= base) {
            return max;
        }
        long steps = Math.max(0, waitSeconds) / stepSeconds;
        // steps × stepDelta ≥ max − base 时已到顶；先比档数再乘，等待秒数再大也不会溢出
        if (steps >= ceilDiv(max - base, stepDelta)) {
            return max;
        }
        return base + steps * stepDelta;
    }

    /**
     * 锚点此刻实际使用的容差（centi）：非评分模式、或已等 ≥ {@link #maxWaitSeconds()} → {@link #UNBOUNDED}；否则曲线值 × 100。
     *
     * @param rated       这条队列是不是评分模式（只有 1V1 / 5V5）
     * @param waitSeconds 锚点已等的整秒数（Redis 时间 − 票据的入队时刻，夹到 ≥ 0）
     */
    public long anchorCenti(boolean rated, long waitSeconds) {
        if (!rated || waitSeconds >= maxWaitSeconds) {
            return UNBOUNDED;
        }
        return curvePoints(waitSeconds) * CENTI;
    }

    /**
     * 曲线到顶需要等的秒数（缺省 (1000 − 100) / 100 × 5 = 45）：锚点等过这么久仍凑不到人，就是曲线本身救不了的饥饿（段位里没人 / 分差超过上限），
     * 只能等终态兜底——凑单据此打限频告警。{@code max ≤ base} 时为 0。
     */
    public long saturationSeconds() {
        return max <= base ? 0 : ceilDiv(max - base, stepDelta) * stepSeconds;
    }

    /** 终态兜底的秒数：锚点等到这么久之后容差无穷大。 */
    public long maxWaitSeconds() {
        return maxWaitSeconds;
    }

    /** 两个评分（同单位）的分差；溢出时取 {@link Long#MAX_VALUE}（评分镜像被人为改成离谱的值也不会算出负的分差）。 */
    public static long distance(long ratingA, long ratingB) {
        long hi = Math.max(ratingA, ratingB);
        long lo = Math.min(ratingA, ratingB);
        long diff = hi - lo;
        return diff < 0 ? Long.MAX_VALUE : diff;
    }

    /** 分差是否在容差之内（含边界；{@link #UNBOUNDED} 恒为真）。三个参数同单位。 */
    public static boolean within(long ratingA, long ratingB, long tolerance) {
        return tolerance == UNBOUNDED || distance(ratingA, ratingB) <= tolerance;
    }

    private static long positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    /** 正数的向上取整除法。 */
    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    @Override
    public String toString() {
        return "Tolerance{base=" + base + ", stepSeconds=" + stepSeconds + ", stepDelta=" + stepDelta + ", max=" + max
                + ", maxWaitSeconds=" + maxWaitSeconds + '}';
    }
}
