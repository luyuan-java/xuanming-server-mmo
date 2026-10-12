package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 引擎随机原语 {@link BattleRandom}（基线 {@code turn_battle_engine.cpp:1808-1816}）的向量与抽数账（规格 §8.2、§13.1）。
 * 向量均为【复核】级：由两个独立写成的 Java 实现算出且一致，其 mt19937_64 已用【标准】值校验，未在 C++ 里实跑。
 */
class BattleRandomTest {

    @ParameterizedTest(name = "seed {0}")
    @CsvSource({
            // seed,                 Rand01#1,             RandIndex(2)#1, RandIndex(3)#1
            "0,                    0.1597933633704608,   0, 0",
            "1,                    0.13387664401253263,  0, 2",
            "7,                    0.754385304152858,    1, 0",
            "21,                   0.2853506546938004,   0, 2",
            "33,                   0.27870674951934526,  1, 1",
            "42,                   0.755155532954539,    0, 0",
            "1234,                 0.9472316166078043,   0, 2",
            "20260815,             0.31228030797574924,  1, 1",
            "20260831,             0.39786612980188396,  0, 1",
            "18446744073709551615, 0.025913863009903726, 0, 2",
    })
    void 各种子第一抽的两个原语(String seed, double rand01, long randIndex2, long randIndex3) {
        // 【复核】三个值都基于第 1 抽，所以每个原语用一个新实例
        long s = Long.parseUnsignedLong(seed);
        assertThat(new BattleRandom(s).rand01()).isEqualTo(rand01);
        assertThat(new BattleRandom(s).randIndex(2)).isEqualTo(randIndex2);
        assertThat(new BattleRandom(s).randIndex(3)).isEqualTo(randIndex3);
    }

    @ParameterizedTest(name = "seed {0}")
    @CsvSource({
            "1,        0.13640703636619722, 0.4512149038445381,  0.02102422841672702",
            "7,        0.9493012028926442,  0.11741428103451801, 0.8919131767124763",
            "42,       0.6390313938546974,  0.7521452007480266,  0.13627268363243705",
            "20260815, 0.9686347624683321,  0.4715863672636402,  0.45836928586634273",
    })
    void 各种子第二到第四抽的零一随机数(long seed, double second, double third, double fourth) {
        // 【复核】
        BattleRandom random = new BattleRandom(seed);
        random.rand01();
        assertThat(random.rand01()).isEqualTo(second);
        assertThat(random.rand01()).isEqualTo(third);
        assertThat(random.rand01()).isEqualTo(fourth);
    }

    @Test
    void 取模必须按无符号() {
        // 种子 7 的第 1 抽 = 13915952638675311015 ≥ 2^63：有符号 % 得 -1，Math.floorMod 得 2，正确值是 0（13915952638675311015 = 3 × 4638650879558437005）
        long first = new MersenneTwister64(7).next();
        assertThat(first).isNegative();
        assertThat(first % 3).isEqualTo(-1);
        assertThat(Math.floorMod(first, 3L)).isEqualTo(2);
        assertThat(new BattleRandom(7).randIndex(3)).isZero();
        // 种子 42、1234 的第 1 抽也 ≥ 2^63
        assertThat(new MersenneTwister64(42).next()).isNegative();
        assertThat(new MersenneTwister64(1234).next()).isNegative();
    }

    @Test
    void 零一随机数是二的五十三次方分之k() {
        BattleRandom random = new BattleRandom(20260815);
        for (int i = 0; i < 10_000; i++) {
            double value = random.rand01();
            assertThat(value).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
            double scaled = value * 0x1p53;
            assertThat(scaled).isEqualTo(Math.floor(scaled));
        }
        assertThat(0x1.0p-53).isEqualTo(1.0 / 9007199254740992.0);
    }

    @Test
    void 抽数计数_候选只有一个也消耗一次() {
        BattleRandom random = new BattleRandom(42);
        assertThat(random.draws()).isZero();
        assertThat(random.randIndex(1)).isZero();
        assertThat(random.draws()).isEqualTo(1);
        // 第 1 抽已被 randIndex(1) 吃掉：接下来的 rand01 是第 2 抽
        assertThat(random.rand01()).isEqualTo(0.6390313938546974);
        assertThat(random.draws()).isEqualTo(2);
    }

    @Test
    void 抽数计数_两个原语共用一条序列() {
        BattleRandom random = new BattleRandom(1);
        assertThat(random.rand01()).isEqualTo(0.13387664401253263);
        random.randIndex(5);
        assertThat(random.rand01()).isEqualTo(0.4512149038445381);
        random.randIndex(Long.MIN_VALUE); // count 也按 uint64 解释
        assertThat(random.draws()).isEqualTo(4);
    }

    @Test
    void 候选数为零是调用方错误() {
        // C++ 是未定义行为（x86 上 SIGFPE）；引擎保证不会以 0 调用，万一调到了 Java 抛异常而不是给出错误的下标
        assertThatThrownBy(() -> new BattleRandom(1).randIndex(0)).isInstanceOf(ArithmeticException.class);
    }
}
