package com.game.player.store.routing;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 地域存档库路由与池级读并发配额（主仓 A17a / A17b / A17c 与 {@code player-save-direct-commit.md} §5.7）：
 * <ul>
 *   <li>存档库与区号（{@code home_zone}）彻底脱钩：每个地域上线前固定 K 个存档库（开发 1、生产初值 64），
 *       落点编号 {@code storeId = 1_000_000 + regionCode * 1_000 + shardIndex}，库名 {@code player_store_<storeId>_db}；</li>
 *   <li>建号按无符号 {@code player_id % K} 选库并写进目录，此后读写只认目录；</li>
 *   <li>旧按区库（{@code storeId < 1_000_000} 或 {@code zone_<n>_db}）在库名派生、落点登记、打开存档库与清单校验四处一律拒绝；</li>
 *   <li>启动时不打开任何存档库，每个库在首次被路由指向时按单飞懒打开并核表；</li>
 *   <li>每库连接池预算按 K 摊开（每库至少 2 条），且账本/展示读的并发配额按单个连接池各发一份（池容量的 1/2，至少 1），
 *       不发全进程全局一份；建角与判死直接绕过该配额。</li>
 * </ul>
 */
public final class RegionalSaveDbRouter<T> {

    /** 地域存档库落点编号下界：凡小于该值（旧区库号）一律拒绝。 */
    public static final int MIN_REGIONAL_STORE_ID = 1_000_000;
    public static final int REGION_STRIDE = 1_000;
    public static final int MIN_POOL_PER_DB = 2;
    private static final Pattern LEGACY_ZONE_DB = Pattern.compile("^zone_\\d+_db$");

    /** 单个存档库连接池句柄：自带本池独立的读并发配额（与其余存档库完全隔离）。 */
    public record PoolHandle<T>(int storeId, String dbName, int poolSize, T dataSource, ReadConcurrencyQuota readQuota) {
    }

    /**
     * 单池读并发配额：每库连接池各一份（池大小的一半，至少 1），避免摊薄池容量后多库读请求串行互卡；
     * 建角与判死操作不申请该配额（调用方直接执行）。
     */
    public static final class ReadConcurrencyQuota {
        private final int permits;
        private final Semaphore semaphore;

        public ReadConcurrencyQuota(int poolSize) {
            this.permits = Math.max(1, poolSize / 2);
            this.semaphore = new Semaphore(this.permits);
        }

        public int maxPermits() {
            return permits;
        }

        public int availablePermits() {
            return semaphore.availablePermits();
        }

        public boolean tryAcquire() {
            return semaphore.tryAcquire();
        }

        public void release() {
            semaphore.release();
        }
    }

    private final int regionCode;
    private final int shardCountK;
    private final int poolPerDb;
    private final Function<String, T> openerAndVerifier;
    private final ConcurrentHashMap<Integer, PoolHandle<T>> opened = new ConcurrentHashMap<>();

    public RegionalSaveDbRouter(int regionCode, int shardCountK, int totalPoolBudget,
                                Collection<String> configuredDbManifest, Function<String, T> openerAndVerifier) {
        if (regionCode < 0 || regionCode > 999) {
            throw new IllegalArgumentException("regionCode 必须在 0..999 之间: " + regionCode);
        }
        if (shardCountK < 1 || shardCountK > REGION_STRIDE) {
            throw new IllegalArgumentException("地域存档库数量 K 必须在 1..1000 之间: " + shardCountK);
        }
        validateManifest(regionCode, shardCountK, configuredDbManifest);
        this.regionCode = regionCode;
        this.shardCountK = shardCountK;
        this.poolPerDb = poolSizePerDb(totalPoolBudget, shardCountK);
        this.openerAndVerifier = Objects.requireNonNull(openerAndVerifier, "openerAndVerifier");
        // 启动时不连任何存档库：opened 保持为空，首次被路由指向时再单飞打开并核表
    }

    /** 计算地域与片号对应的唯一存档库落点编号（>= 1_000_000）。 */
    public static int storeIdFor(int regionCode, int shardIndex) {
        if (regionCode < 0 || regionCode > 999) {
            throw new IllegalArgumentException("regionCode 非法: " + regionCode);
        }
        if (shardIndex < 0 || shardIndex >= REGION_STRIDE) {
            throw new IllegalArgumentException("shardIndex 非法: " + shardIndex);
        }
        return MIN_REGIONAL_STORE_ID + regionCode * REGION_STRIDE + shardIndex;
    }

    /** 校验落点编号必须为地域存档库（>= 1_000_000），拒绝任何旧区号落点。 */
    public static int requireRegionalStoreId(int storeId) {
        if (storeId < MIN_REGIONAL_STORE_ID) {
            throw new IllegalArgumentException("拒绝旧按区落点编号(< 1000000): " + storeId);
        }
        return storeId;
    }

    /** 派生地域存档库库名；落点编号 < 1_000_000 一律拒绝，不再派生任何 zone_<n>_db。 */
    public static String dbNameFor(int storeId) {
        requireRegionalStoreId(storeId);
        return "player_store_" + storeId + "_db";
    }

    /** 建号按无符号 {@code player_id % K} 选择本地域存档库落点编号（写进目录，此后读写只认目录）。 */
    public static int assignStoreForNewPlayer(long playerId, int regionCode, int shardCountK) {
        if (playerId == 0L) {
            throw new IllegalArgumentException("player_id 不能为 0");
        }
        if (shardCountK < 1 || shardCountK > REGION_STRIDE) {
            throw new IllegalArgumentException("shardCountK 非法: " + shardCountK);
        }
        int shard = (int) Long.remainderUnsigned(playerId, shardCountK);
        return storeIdFor(regionCode, shard);
    }

    public int assignStoreForNewPlayer(long playerId) {
        return assignStoreForNewPlayer(playerId, regionCode, shardCountK);
    }

    /** 计算每个存档库分摊到的连接池大小（总预算按 K 摊开，每库至少 2 条）。 */
    public static int poolSizePerDb(int totalPoolBudget, int shardCountK) {
        if (shardCountK < 1) {
            throw new IllegalArgumentException("shardCountK 必须为正");
        }
        return Math.max(MIN_POOL_PER_DB, totalPoolBudget / shardCountK);
    }

    /**
     * 部署清单硬校验：必须恰好等于本地域的 K 个 {@code player_store_<id>_db}，
     * 出现任何旧 {@code zone_<n>_db}、缺片、多片或重复库名均在写入之前拒绝。
     */
    public static void validateManifest(int regionCode, int shardCountK, Collection<String> configuredDbNames) {
        if (configuredDbNames == null) {
            throw new IllegalArgumentException("地域存档库清单不能为空");
        }
        Set<String> expected = new HashSet<>(shardCountK * 2);
        for (int shard = 0; shard < shardCountK; shard++) {
            expected.add(dbNameFor(storeIdFor(regionCode, shard)));
        }
        Set<String> actual = new HashSet<>(configuredDbNames.size() * 2);
        for (String name : configuredDbNames) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("地域存档库清单含空库名");
            }
            if (LEGACY_ZONE_DB.matcher(name).matches()) {
                throw new IllegalArgumentException("拒绝已退役的旧按区存档库名: " + name);
            }
            if (!actual.add(name)) {
                throw new IllegalArgumentException("地域存档库清单存在重复库名: " + name);
            }
        }
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("地域存档库清单与本地域 K=" + shardCountK + " 期望集合不符: expected="
                    + expected.size() + " actual=" + actual.size());
        }
    }

    /** 懒打开指定落点编号的存档库并核表；拒绝旧按区落点及越界片号，保证单飞只打开一次。 */
    public PoolHandle<T> openLazy(int storeId) {
        requireRegionalStoreId(storeId);
        int expectedRegion = (storeId - MIN_REGIONAL_STORE_ID) / REGION_STRIDE;
        int shard = (storeId - MIN_REGIONAL_STORE_ID) % REGION_STRIDE;
        if (expectedRegion != regionCode || shard < 0 || shard >= shardCountK) {
            throw new IllegalArgumentException("落点编号不属于本地域 K=" + shardCountK + " 存档库范围: " + storeId);
        }
        return opened.computeIfAbsent(storeId, id -> {
            String dbName = dbNameFor(id);
            T ds = Objects.requireNonNull(openerAndVerifier.apply(dbName), "opener returned null for " + dbName);
            return new PoolHandle<>(id, dbName, poolPerDb, ds, new ReadConcurrencyQuota(poolPerDb));
        });
    }

    public int openedCount() {
        return opened.size();
    }

    public int poolSizePerDb() {
        return poolPerDb;
    }

    public int shardCountK() {
        return shardCountK;
    }

    public int regionCode() {
        return regionCode;
    }
}
