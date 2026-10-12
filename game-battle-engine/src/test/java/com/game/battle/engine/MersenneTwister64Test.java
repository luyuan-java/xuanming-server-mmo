package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link MersenneTwister64} 与 {@code std::mt19937_64} 的逐位对照（规格 §13.1）。
 *
 * <p>分级：默认种子的两个值是【标准】（C++ 标准 [rand.predef] 规定的校验值，与任何实现无关）；
 * 各种子前几抽是【复核】（两个独立写成的 Java 实现结果一致，且都通过了【标准】值），未在 C++ 里实跑。
 */
class MersenneTwister64Test {

    private static long u64(String unsignedDecimal) {
        return Long.parseUnsignedLong(unsignedDecimal);
    }

    @Test
    void 默认种子第一抽与第一万抽是标准校验值() {
        // 【标准】C++ 标准 [rand.predef]：默认构造的 mt19937_64 第 10000 次调用的结果是 9981545732273789042
        MersenneTwister64 mt = new MersenneTwister64(MersenneTwister64.DEFAULT_SEED);
        assertThat(Long.toUnsignedString(mt.next())).isEqualTo("14514284786278117030");
        long last = 0;
        for (int i = 2; i <= 10_000; i++) {
            last = mt.next();
        }
        assertThat(Long.toUnsignedString(last)).isEqualTo("9981545732273789042");
    }

    @ParameterizedTest(name = "seed {0}")
    @CsvSource({
            // seed,                 #1,                    #2,                    #3
            "0,                    2947667278772165694,  18301848765998365067, 729919693006235833",
            "1,                    2469588189546311528,  2516265689700432462,  8323445853463659930",
            "7,                    13915952638675311015, 17511516338625233250, 2165911192842364878",
            "21,                   5263790498402004422,  11409222558240126990, 8042491938685489068",
            "33,                   5141232079998836263,  4479202188031934533,  12050777948115226479",
            "42,                   13930160852258120406, 11788048577503494824, 13874630024467741450",
            "1234,                 17473339210090333472, 963351229459618018,   17972999874122035550",
            "20260815,             5760554920487847109,  17868157564151766807, 8699233025562771549",
            "20260831,             7339334672052659764,  18022099805066839321, 15070275510894644899",
            "18446744073709551615, 478026398904862820,   13243134898385798468, 709236020254955927",
    })
    void 各种子前三抽(String seed, String first, String second, String third) {
        // 【复核】
        MersenneTwister64 mt = new MersenneTwister64(u64(seed));
        assertThat(Long.toUnsignedString(mt.next())).isEqualTo(first);
        assertThat(Long.toUnsignedString(mt.next())).isEqualTo(second);
        assertThat(Long.toUnsignedString(mt.next())).isEqualTo(third);
    }

    @ParameterizedTest(name = "seed {0}")
    @CsvSource({
            "1,        387828560950575246",
            "7,        16452894106784333046",
            "42,       2513787319205155662",
            "20260815, 8455420907625438291",
    })
    void 各种子第四抽(String seed, String fourth) {
        // 【复核】
        MersenneTwister64 mt = new MersenneTwister64(u64(seed));
        mt.next();
        mt.next();
        mt.next();
        assertThat(Long.toUnsignedString(mt.next())).isEqualTo(fourth);
    }

    @Test
    void 种子按无符号六十四位解释() {
        // 2^64 − 1 与 -1L 是同一个位模式，没有截断成 32 位或按有符号处理
        assertThat(new MersenneTwister64(-1L).next()).isEqualTo(u64("478026398904862820"));
        assertThat(new MersenneTwister64(Long.MIN_VALUE).next()).isNotEqualTo(new MersenneTwister64(0).next());
    }
}
