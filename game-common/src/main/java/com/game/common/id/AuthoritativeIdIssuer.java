package com.game.common.id;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 三份独立预留记录 + 全局号源登记处的权威永久业务 ID 发号器（对齐主仓 {@code docs/design/player-data-tidb-scaling-plan.md} §6.3）：
 * <ul>
 *   <li>64 位结构：{@code [6 位地域码 0..63][58 位可逆打散单调序号]}；最高 6 位由发号器按地域码固定，客户端与调用方不可覆盖；</li>
 *   <li>每次按块预留 {@code [lo, hi)}：先校验地域码在 {@link GlobalRegionRegistry} 中唯一绑定到本部署环境与主副两套独立存储，
 *       再读取主、副、登记处三份水位，与进程已确认水位取最大值作为安全下界；</li>
 *   <li>必须三份预留记录全部单调抬升至 {@code hi} 成功后才允许发放块内号码；任一份不可用、写入失败、绑定冲突或退役一律 fail-closed 抛异常，
 *       绝不回退本地 Snowflake；</li>
 *   <li>任一份或两份本地记录丢失/回档时自动由剩余记录与登记处兜底并跳至其上；三份同时回档时存活进程仍记住已确认水位并把三份抬回；</li>
 *   <li>58 位可逆双射打散（奇数乘法 + XOR 折叠）避免 TiDB 顺序主键热点，并保持严格无碰撞与 1-to-1 可逆。</li>
 * </ul>
 */
public final class AuthoritativeIdIssuer {

    public static final int REGION_SHIFT = 58;
    public static final long COUNTER_MASK = (1L << REGION_SHIFT) - 1L;
    public static final long DEFAULT_BLOCK_SIZE = 10_000L;
    public static final long SMALL_BLOCK_SIZE = 1_000L;
    /** 58 位模下的奇数乘子与其模逆元（用于双射打散，不破坏唯一性）。 */
    private static final long SCRAMBLE_MUL = 0x2545F4914F6CDD1DL & COUNTER_MASK;
    private static final long SCRAMBLE_INV = modInverse58(SCRAMBLE_MUL);
    private static final long SCRAMBLE_XOR = 0x1A2B3C4D5E6F777L & COUNTER_MASK;

    private final int regionCode;
    private final String deploymentId;
    private final IdReservationStore primary;
    private final IdReservationStore secondary;
    private final GlobalRegionRegistry registry;
    private final long blockSize;
    private final long smallBlockSize;
    private final LongSupplier clockMs;

    private long nextCounter = 0L;
    private long reservedUpper = 0L;
    private long confirmedWatermark = 0L;
    private final AtomicLong recordsBehindRegistryCount = new AtomicLong();

    public AuthoritativeIdIssuer(int regionCode,
                                 boolean prodMode,
                                 String deploymentId,
                                 IdReservationStore primary,
                                 IdReservationStore secondary,
                                 GlobalRegionRegistry registry,
                                 long blockSize,
                                 LongSupplier clockMs) {
        this.regionCode = GlobalRegionRegistry.validateRegionCode(regionCode, prodMode);
        if (deploymentId == null || deploymentId.isBlank()) {
            throw new IllegalArgumentException("deploymentId 不能为空");
        }
        this.deploymentId = deploymentId;
        this.primary = Objects.requireNonNull(primary, "primary");
        this.secondary = Objects.requireNonNull(secondary, "secondary");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (primary.storeId().equals(secondary.storeId())
                || primary.storeId().equals(registry.storeId())
                || secondary.storeId().equals(registry.storeId())) {
            throw new IllegalArgumentException("主记录、副记录与全局登记处必须是三套互相独立的存储");
        }
        if (blockSize < 1 || blockSize > (1L << 30)) {
            throw new IllegalArgumentException("块大小非法: " + blockSize);
        }
        this.blockSize = blockSize;
        this.smallBlockSize = Math.max(1L, Math.min(SMALL_BLOCK_SIZE, blockSize));
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
    }

    public AuthoritativeIdIssuer(int regionCode,
                                 boolean prodMode,
                                 String deploymentId,
                                 IdReservationStore primary,
                                 IdReservationStore secondary,
                                 GlobalRegionRegistry registry) {
        this(regionCode, prodMode, deploymentId, primary, secondary, registry, DEFAULT_BLOCK_SIZE, System::currentTimeMillis);
    }

    /** 发放一个全局唯一永久业务 ID；无可用可信上界或任一预留记录失败时直接抛 {@link IllegalStateException}。 */
    public synchronized long nextId() {
        if (nextCounter >= reservedUpper) {
            reserveNextBlock();
        }
        long counter = nextCounter++;
        return encode(regionCode, counter);
    }

    /** 检查是否曾观测到本地记录落后于全局登记处（例如双本地回档被登记处成功兜底）。 */
    public long recordsBehindRegistryCount() {
        return recordsBehindRegistryCount.get();
    }

    /** 本进程已确认的最高预留水位。 */
    public synchronized long confirmedWatermark() {
        return confirmedWatermark;
    }

    /**
     * 预留下一段 {@code [lo, hi)}：必须先核验登记处独占绑定，再确保三份独立记录全部单调抬升到 {@code hi} 才生效。
     */
    private void reserveNextBlock() {
        GlobalRegionRegistry.RegionBinding binding = registry.verifyBinding(
                regionCode, deploymentId, primary.storeId(), secondary.storeId());

        OptionalLong pMark = primary.readWatermark(regionCode);
        OptionalLong sMark = secondary.readWatermark(regionCode);
        long rMark = binding.watermark();

        if (pMark.isEmpty() && sMark.isEmpty() && rMark <= 0 && confirmedWatermark <= 0) {
            // 仅允许经显式 initialize 的合法 0 起号源（已由 verifyBinding 证实）；其他无记录但有历史的场景由 max 保护
        }
        long pVal = pMark.orElse(0L);
        long sVal = sMark.orElse(0L);
        if ((pMark.isPresent() && pVal < rMark) || (sMark.isPresent() && sVal < rMark)
                || (pMark.isEmpty() && rMark > 0) || (sMark.isEmpty() && rMark > 0)) {
            recordsBehindRegistryCount.incrementAndGet();
        }

        long lo = Math.max(Math.max(confirmedWatermark, rMark), Math.max(pVal, sVal));
        long span = chooseBlockSize(lo);
        long hi = lo + span;
        if (hi <= lo || hi > COUNTER_MASK) {
            throw new IllegalStateException("地域号源序号耗尽 region=" + regionCode + " lo=" + lo);
        }

        // 三份独立存储全部成功抬升后才允许开放该段；任何一份抛异常均不推进内存游标
        long rConfirm = registry.raiseWatermark(regionCode, hi);
        long pConfirm = primary.raiseWatermark(regionCode, hi);
        long sConfirm = secondary.raiseWatermark(regionCode, hi);
        if (rConfirm < hi || pConfirm < hi || sConfirm < hi) {
            throw new IllegalStateException("预留记录未达到期望上界 hi=" + hi
                    + " r=" + rConfirm + " p=" + pConfirm + " s=" + sConfirm);
        }

        this.nextCounter = lo;
        this.reservedUpper = hi;
        this.confirmedWatermark = Math.max(this.confirmedWatermark, hi);
    }

    /** 计数器相对墙钟超前超过 1 天估算额度时自动切小块预留，追回后恢复整块（§6.3 2026-11-19 收尾）。 */
    private long chooseBlockSize(long lo) {
        long nowMs = Math.max(0L, clockMs.getAsLong());
        long approxDailyCeiling = nowMs + 86_400_000L;
        return lo > approxDailyCeiling ? smallBlockSize : blockSize;
    }

    /** 将 (地域码, 58 位序号) 编码为 64 位永久 ID（保证不为 0）。 */
    public static long encode(int regionCode, long counter) {
        if (regionCode < 0 || regionCode > GlobalRegionRegistry.MAX_REGION_CODE) {
            throw new IllegalArgumentException("regionCode 越界: " + regionCode);
        }
        if (counter < 0 || counter > COUNTER_MASK) {
            throw new IllegalArgumentException("counter 越界: " + counter);
        }
        long scrambled = scramble58(counter + 1L);
        return (((long) regionCode) << REGION_SHIFT) | scrambled;
    }

    public static int regionOf(long id) {
        return (int) ((id >>> REGION_SHIFT) & GlobalRegionRegistry.MAX_REGION_CODE);
    }

    public static long counterOf(long id) {
        return unscramble58(id & COUNTER_MASK) - 1L;
    }

    /** 58 位双射打散：XOR 掩码 + 模 2^58 奇数乘法，保证无碰撞且可逆。 */
    public static long scramble58(long v) {
        long x = (v & COUNTER_MASK) ^ SCRAMBLE_XOR;
        return (x * SCRAMBLE_MUL) & COUNTER_MASK;
    }

    public static long unscramble58(long v) {
        long x = ((v & COUNTER_MASK) * SCRAMBLE_INV) & COUNTER_MASK;
        return (x ^ SCRAMBLE_XOR) & COUNTER_MASK;
    }

    private static long modInverse58(long odd) {
        long inv = 1L;
        for (int i = 0; i < 6; i++) {
            inv = (inv * (2L - odd * inv)) & COUNTER_MASK;
        }
        return inv;
    }

    /** 内存独立预留存储实现（用于单测模拟主/副本地 etcd 丢失与回档）。 */
    public static final class InMemoryReservationStore implements IdReservationStore {
        private final String id;
        private final java.util.Map<Integer, Long> watermarks = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile boolean failWrites;
        private volatile boolean failReads;

        public InMemoryReservationStore(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public void setFailWrites(boolean fail) {
            this.failWrites = fail;
        }

        public void setFailReads(boolean fail) {
            this.failReads = fail;
        }

        public void simulateRollback(int regionCode, long watermark) {
            watermarks.put(regionCode, watermark);
        }

        public void simulateWipe(int regionCode) {
            watermarks.remove(regionCode);
        }

        @Override
        public String storeId() {
            return id;
        }

        @Override
        public OptionalLong readWatermark(int regionCode) {
            if (failReads) {
                throw new IllegalStateException("本地预留存储不可达 store=" + id);
            }
            Long v = watermarks.get(regionCode);
            return v == null ? OptionalLong.empty() : OptionalLong.of(v);
        }

        @Override
        public long raiseWatermark(int regionCode, long newUpperBound) {
            if (failWrites) {
                throw new IllegalStateException("本地预留存储写入失败 store=" + id);
            }
            return watermarks.merge(regionCode, newUpperBound, Math::max);
        }
    }
}
