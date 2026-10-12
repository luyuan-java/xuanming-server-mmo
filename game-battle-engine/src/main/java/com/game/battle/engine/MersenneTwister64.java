package com.game.battle.engine;

/**
 * {@code std::mt19937_64} 的逐位移植（C++ 标准 [rand.predef]；参数 w=64、n=312、m=156、r=31、
 * a=0xB5026F5AA96619E9、u=29、d=0x5555555555555555、s=17、b=0x71D67FFFEDA60000、t=37、c=0xFFF7EEE000000000、l=43、
 * f=6364136223846793005）。回合制战斗引擎的唯一随机源：同种子同序列，与 C++ 基线逐个输出相同（规格 §8）。
 *
 * <p>为什么自写：JDK 21 的 {@code RandomGenerator} 家族里没有 MT19937-64，commons-rng 达不到选型的 star 门槛；
 * 算法约 40 行，用标准规定的校验值（默认种子第 10000 个输出 = 9981545732273789042）钉住，不算新增依赖。
 *
 * <p>种子与输出都是 uint64 的位模式（{@code long} 承载，按无符号解释）。非线程安全。
 */
public final class MersenneTwister64 {

    /** {@code std::mt19937_64::default_seed}。 */
    public static final long DEFAULT_SEED = 5489L;

    private static final int NN = 312;
    private static final int MM = 156;
    private static final long MATRIX_A = 0xB5026F5AA96619E9L;
    /** 高 33 位（w − r = 33）。 */
    private static final long UPPER_MASK = 0xFFFFFFFF80000000L;
    /** 低 31 位（r = 31）。 */
    private static final long LOWER_MASK = 0x7FFFFFFFL;

    private final long[] mt = new long[NN];
    private int mti;

    /** 同 {@code std::mt19937_64(seed)} / {@code seed(seed)}；seed 按 uint64 位模式解释。 */
    public MersenneTwister64(long seed) {
        mt[0] = seed;
        for (int i = 1; i < NN; i++) {
            mt[i] = 6364136223846793005L * (mt[i - 1] ^ (mt[i - 1] >>> 62)) + i;
        }
        mti = NN;
    }

    /** 下一个输出（uint64 位模式），同 {@code operator()}。 */
    public long next() {
        if (mti >= NN) {
            twist();
        }
        long x = mt[mti++];
        x ^= (x >>> 29) & 0x5555555555555555L;
        x ^= (x << 17) & 0x71D67FFFEDA60000L;
        x ^= (x << 37) & 0xFFF7EEE000000000L;
        x ^= (x >>> 43);
        return x;
    }

    private void twist() {
        int i;
        for (i = 0; i < NN - MM; i++) {
            long x = (mt[i] & UPPER_MASK) | (mt[i + 1] & LOWER_MASK);
            mt[i] = mt[i + MM] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
        }
        for (; i < NN - 1; i++) {
            long x = (mt[i] & UPPER_MASK) | (mt[i + 1] & LOWER_MASK);
            mt[i] = mt[i + (MM - NN)] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
        }
        long x = (mt[NN - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
        mt[NN - 1] = mt[MM - 1] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
        mti = 0;
    }
}
