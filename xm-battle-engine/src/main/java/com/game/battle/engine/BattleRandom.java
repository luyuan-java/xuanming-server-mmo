package com.game.battle.engine;

/**
 * 引擎内的两个随机原语（基线 {@code turn_battle_engine.cpp:1808-1816}），外加抽数计数器供测试核对规格 §8.4 的消耗账。
 *
 * <ul>
 *   <li>{@link #randIndex(long)} = {@code rng() % count}：count 按 uint64、调用方保证大于 0；count 为 1 也消耗一次。
 *       必须用无符号取模（种子 7 的第 1 抽 ≥ 2^63：有符号 {@code %} 得 -1，{@code Math.floorMod} 得 2，正确值是 0）。</li>
 *   <li>{@link #rand01()} = {@code (double)(rng() >> 11) × 2^-53}：结果是 k / 2^53，精确，值域 [0, 1)。
 *       必须用逻辑右移 {@code >>>}。{@code 0x1.0p-53} 与 C++ 的 {@code 1.0 / 9007199254740992.0} 是同一个 double。</li>
 * </ul>
 * 不用任何 {@code uniform_*_distribution} 等价物（基线也不用，其实现跨平台不定）。非线程安全，归属一个引擎实例。
 */
final class BattleRandom {

    private final MersenneTwister64 mt;
    private long draws;

    /** 同 {@code rng.seed(request.seed())}：seed 是完整的 uint64。 */
    BattleRandom(long seed) {
        this.mt = new MersenneTwister64(seed);
    }

    /** {@code rng() % count}（无符号）；count 为 0 时抛 {@link ArithmeticException}（C++ 是未定义行为，调用方保证不会发生）。 */
    long randIndex(long count) {
        draws++;
        return Long.remainderUnsigned(mt.next(), count);
    }

    /** [0, 1) 内的 k / 2^53。 */
    double rand01() {
        draws++;
        return (double) (mt.next() >>> 11) * 0x1.0p-53;
    }

    /** 至今的总抽数（randIndex 与 rand01 各计一次）。 */
    long draws() {
        return draws;
    }
}
