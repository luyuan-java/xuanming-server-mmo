package com.game.common.math;

/**
 * 无符号 64 位整数（C++ {@code uint64_t}）在 Java {@code long} 里的换算工具。纯函数，没有状态。
 *
 * <p>proto 的 uint64 字段在 Java 里用 {@code long} 承载、按位模式解释。比较、除法、取模用 JDK 的
 * {@code Long.compareUnsigned} / {@code divideUnsigned} / {@code remainderUnsigned}；本类补 JDK 没有的
 * 「uint64 ↔ double」换算与无符号取小，逐位对齐 C++ 的 {@code static_cast} 与 {@code std::min<uint64_t>}。
 * 伤害公式（{@code com.game.common.combat.CombatDamageRules}）与回合制战斗引擎共用。
 */
public final class Unsigned {

    /** 2^64（double 精确可表示）。 */
    private static final double TWO_POW_64 = 0x1p64;
    /** 2^63（double 精确可表示）。 */
    private static final double TWO_POW_63 = 0x1p63;

    private Unsigned() {
    }

    /**
     * uint64 → double，同 C++ 的 {@code static_cast<double>(uint64_t)}：就近舍入（平局取偶）。
     *
     * <p>最高位为 1 时先右移一位、把移出的最低位并回粘滞位再换算，最后乘 2（精确），舍入结果与直接换算 65 位数相同。
     */
    public static double toDouble(long value) {
        if (value >= 0) {
            return value;
        }
        return ((value >>> 1) | (value & 1)) * 2.0;
    }

    /**
     * 非负有限 double → uint64，同 C++ 的 {@code static_cast<uint64_t>(double)}：向零截断小数。
     *
     * <p>前置条件：{@code 0 <= value < 2^64}。越界（含负数、NaN、无穷）在 C++ 里是未定义行为，这里不做保证；
     * 需要有定义的口径时用 {@link #fromDoubleSaturating(double)}。
     */
    public static long fromDouble(double value) {
        if (value < TWO_POW_63) {
            return (long) value;
        }
        // ≥ 2^63 的 double 都是 2 的倍数：先除 2 再左移，精确
        return ((long) (value / 2)) << 1;
    }

    /**
     * double → uint64 的饱和换算（C++ 的越界转换是未定义行为，Java 版给出确定口径）：
     * NaN、负数（含 -∞）→ 0；≥ 2^64（含 +∞）→ UINT64_MAX（位模式 {@code -1L}）；其余同 {@link #fromDouble(double)}。
     *
     * <p>注意 {@code (-1, 0)} 区间的负小数在 C++ 里截断为 0 是有定义的，与这里结果相同。
     */
    public static long fromDoubleSaturating(double value) {
        if (!(value > 0.0)) {
            return 0L;
        }
        if (value >= TWO_POW_64) {
            return -1L;
        }
        return fromDouble(value);
    }

    /** 按无符号取小，同 C++ 的 {@code std::min<uint64_t>(a, b)}（相等时返回 a）。 */
    public static long minUnsigned(long a, long b) {
        return Long.compareUnsigned(b, a) < 0 ? b : a;
    }
}
