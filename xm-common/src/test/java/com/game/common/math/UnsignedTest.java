package com.game.common.math;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * {@link Unsigned} 与 C++ {@code static_cast} / {@code std::min<uint64_t>} 的逐位对照（回合制战斗规格 §13.3、§10.7）。
 * 参照值用 {@link BigInteger} / {@link BigDecimal} 独立算出：{@code BigInteger.doubleValue()} 就近舍入（平局取偶），
 * 与 IEEE 754 / C++ 的整数转浮点同口径。
 */
class UnsignedTest {

    private static final long UINT64_MAX = -1L;
    private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(64);

    private static BigInteger unsigned(long value) {
        return new BigInteger(Long.toUnsignedString(value));
    }

    @Test
    void 无符号换双精度_全一是二的六十四次方() {
        assertThat(Unsigned.toDouble(UINT64_MAX)).isEqualTo(1.8446744073709552E19).isEqualTo(0x1p64);
        assertThat(Unsigned.toDouble(Long.MIN_VALUE)).isEqualTo(0x1p63);
        assertThat(Unsigned.toDouble(0)).isEqualTo(0.0);
        assertThat(Unsigned.toDouble(Long.MAX_VALUE)).isEqualTo(0x1p63);
        assertThat(Unsigned.toDouble(12345)).isEqualTo(12345.0);
    }

    @Test
    void 无符号换双精度_就近舍入平局取偶() {
        // 2^63 附近 double 的间距是 2048：+1024 是平局，取偶（尾数偶）→ 2^63；+1025 过半 → 2^63 + 2048
        assertThat(Unsigned.toDouble(Long.MIN_VALUE + 1024)).isEqualTo(0x1p63);
        assertThat(Unsigned.toDouble(Long.MIN_VALUE + 1025)).isEqualTo(0x1p63 + 2048);
        // +3072 是 2048 与 4096 之间的平局，取尾数为偶的 4096
        assertThat(Unsigned.toDouble(Long.MIN_VALUE + 3072)).isEqualTo(0x1p63 + 4096);
        // 最低位被右移掉时要并回粘滞位：+1025 的最低位就是决定性的那一位
        assertThat(Unsigned.toDouble(Long.MIN_VALUE + 1025)).isNotEqualTo(Unsigned.toDouble(Long.MIN_VALUE + 1024));
    }

    @Test
    void 无符号换双精度_与大整数参照逐位相同() {
        SplittableRandom random = new SplittableRandom(20261005L);
        for (int i = 0; i < 200_000; i++) {
            long value = random.nextLong();
            assertThat(Unsigned.toDouble(value)).as("0x%016x", value).isEqualTo(unsigned(value).doubleValue());
        }
    }

    @Test
    void 双精度换无符号_截断小数() {
        assertThat(Unsigned.fromDouble(0.0)).isZero();
        assertThat(Unsigned.fromDouble(12.9)).isEqualTo(12);
        assertThat(Unsigned.fromDouble(0.999)).isZero();
        assertThat(Unsigned.fromDouble(-0.5)).as("(-1, 0) 截断为 0 在 C++ 里有定义").isZero();
    }

    @Test
    void 双精度换无符号_二的六十三次方附近() {
        assertThat(Unsigned.fromDouble(Math.nextDown(0x1p63))).isEqualTo(Long.MAX_VALUE - 1023);
        assertThat(Unsigned.fromDouble(0x1p63)).isEqualTo(Long.MIN_VALUE);
        assertThat(Unsigned.fromDouble(Math.nextUp(0x1p63))).isEqualTo(Long.MIN_VALUE + 2048);
        assertThat(Unsigned.fromDouble(Math.nextDown(0x1p64))).as("2^64 - 2048").isEqualTo(-2048L);
        assertThat(Long.toUnsignedString(Unsigned.fromDouble(1.5e19))).isEqualTo("15000000000000000000");
    }

    @Test
    void 双精度换无符号_与大数截断参照逐位相同() {
        SplittableRandom random = new SplittableRandom(7L);
        for (int i = 0; i < 200_000; i++) {
            double value = random.nextDouble() * 0x1p64;
            BigInteger expected = new BigDecimal(value).setScale(0, RoundingMode.DOWN).toBigInteger();
            assertThat(unsigned(Unsigned.fromDouble(value))).as("%s", value).isEqualTo(expected);
        }
    }

    @Test
    void 双精度换无符号_换回去不变() {
        SplittableRandom random = new SplittableRandom(42L);
        for (int i = 0; i < 100_000; i++) {
            double value = Unsigned.toDouble(random.nextLong());
            if (value < 0x1p64) {
                assertThat(Unsigned.toDouble(Unsigned.fromDouble(value))).isEqualTo(value);
            }
        }
    }

    @Test
    void 饱和换算_非有限与越界有确定口径() {
        assertThat(Unsigned.fromDoubleSaturating(Double.NaN)).isZero();
        assertThat(Unsigned.fromDoubleSaturating(Double.NEGATIVE_INFINITY)).isZero();
        assertThat(Unsigned.fromDoubleSaturating(-1.0)).isZero();
        assertThat(Unsigned.fromDoubleSaturating(-0.5)).isZero();
        assertThat(Unsigned.fromDoubleSaturating(-0.0)).isZero();
        assertThat(Unsigned.fromDoubleSaturating(Double.POSITIVE_INFINITY)).isEqualTo(UINT64_MAX);
        assertThat(Unsigned.fromDoubleSaturating(0x1p64)).isEqualTo(UINT64_MAX);
        assertThat(Unsigned.fromDoubleSaturating(Double.MAX_VALUE)).isEqualTo(UINT64_MAX);
        assertThat(Unsigned.fromDoubleSaturating(Math.nextDown(0x1p64))).isEqualTo(-2048L);
        assertThat(Unsigned.fromDoubleSaturating(0x1p63)).isEqualTo(Long.MIN_VALUE);
        assertThat(Unsigned.fromDoubleSaturating(3.7)).isEqualTo(3);
        assertThat(unsigned(Unsigned.fromDoubleSaturating(0x1p64)).add(BigInteger.ONE)).isEqualTo(TWO_POW_64);
    }

    @Test
    void 无符号取小() {
        assertThat(Unsigned.minUnsigned(UINT64_MAX, 1)).isEqualTo(1);
        assertThat(Unsigned.minUnsigned(1, UINT64_MAX)).isEqualTo(1);
        assertThat(Unsigned.minUnsigned(Long.MIN_VALUE, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(Unsigned.minUnsigned(0, Long.MIN_VALUE)).isZero();
        assertThat(Unsigned.minUnsigned(5, 5)).isEqualTo(5);
        assertThat(Unsigned.minUnsigned(-3L, -2L)).as("两个都 ≥ 2^63").isEqualTo(-3L);
    }
}
