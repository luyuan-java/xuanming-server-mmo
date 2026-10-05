package com.game.scenemanager.world;

import com.game.discovery.world.WorldChannels;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 主世界频道编排配置（{@code xm.scene-manager.world.*}，批次 5.1，scene-channels-spec §5.2）。缺省值同基线
 * mmorpg {@code go/scene_manager/internal/config/config.go}（行号见各参数）。全部在启动时校验，不合法即拒启。
 *
 * @param channelCount         每图种子频道数（基线 WorldChannelCount，config.go:95-98；&lt;1 当 1）。只是<b>首次播种</b>的种子：
 *                             播过种之后以 Redis {@code xm:world:{z:<zone>}:desired} 为准（基线 world_autoscale.go:68-99，B2）
 * @param channelCountByConfig 按 scene_config_id 覆盖种子（基线 WorldChannelCountByConfId，config.go:100-107、:333-351）；值 ≤0 忽略、&gt;999 拒启
 * @param coverage             频道放置模式（D6）：{@code per-node}（5.1 缺省，每个活节点对每张 World 图至少 1 个 ACTIVE 频道）或 {@code hash}
 * @param tick                 领导者一拍的间隔（基线负载刷新周期 LoadReportInterval = 5 s，load_reporter.go:31）
 * @param deadNodeGrace        计划引用的节点在目录里缺席满这么久，删掉它名下全部记录、由补建重铺容量（D12；数值同基线再入屏障 20 s，
 *                             constants/reentry_barrier.go:69，但这里只防快速重启抖动，不是屏障）
 * @param leaderLockTtl        分 zone 领导锁 TTL（基线 LeaderLockTTLSeconds = 30，config.go:65-74）；续期 TTL/3、有效期 2/3 TTL
 * @param leaderEligible       是否参与竞选（基线 LeaderEligible，config.go:80-86）；false 时本副本只做数据面（分配）
 * @param cleanupOrphans       World 表里已没有的图：其 ACTIVE 频道转 DRAINING(ORPHAN)、期望数 / 冷却字段删除（基线 CleanupOrphanChannelsOnStartup，config.go:232-240）
 * @param reservationTtl       进场软预占 TTL（§4.11，D7）；0 = 关闭预占（分配只看目录人数）。非 0 时必须 ≥ {@code loginOwnerClaimWait}（Q8）
 * @param loginOwnerClaimWait  login 的归属夺取等待（{@code xm.login.owner-claim-wait}，缺省 3 s）的镜像，只用于上一条的启动校验
 * @param rebalance            择机迁移（只在 {@code coverage=hash} 下执行，§4.8）
 * @param autoscale            自动扩缩容（缺省关，§4.7）；其中 {@code drain-timeout} 不论开关都生效（排空推进与扩缩容开关无关，D19）
 */
@ConfigurationProperties("xm.scene-manager.world")
public record WorldChannelProperties(
        Integer channelCount,
        Map<Integer, Integer> channelCountByConfig,
        Coverage coverage,
        Duration tick,
        Duration deadNodeGrace,
        Duration leaderLockTtl,
        Boolean leaderEligible,
        Boolean cleanupOrphans,
        Duration reservationTtl,
        Duration loginOwnerClaimWait,
        Rebalance rebalance,
        Autoscale autoscale) {

    private static final String PREFIX = "xm.scene-manager.world.";

    /** 频道放置模式（§4.6.3）。 */
    public enum Coverage {
        /** 每个活节点对每张 World 图至少 1 个 ACTIVE 频道；新频道放在该图 ACTIVE 数最少的活节点（并列取节点号小的）。缺省（Q1）；
         *  5.2 上线后仍保持缺省，双节点切片与 robot 验证跨节点换图后再切 hash（scene-handoff-spec Q4）。 */
        PER_NODE,
        /** 落点 = FNV-1a32(conf*1000+slot) 无符号取模活节点（按数值升序）；补建与再平衡同一个键（D5）。 */
        HASH
    }

    public WorldChannelProperties {
        channelCount = channelCount == null || channelCount < 1 ? 1 : channelCount;
        if (channelCount > WorldChannels.MAX_CHANNELS_PER_MAP) {
            throw new IllegalArgumentException(PREFIX + "channel-count 不能超过 " + WorldChannels.MAX_CHANNELS_PER_MAP
                    + "（slot 0..998）: " + channelCount);
        }
        channelCountByConfig = normalizeByConfig(channelCountByConfig);
        coverage = coverage == null ? Coverage.PER_NODE : coverage;
        tick = positiveOr(tick, Duration.ofSeconds(5), "tick");
        deadNodeGrace = nonNegativeOr(deadNodeGrace, Duration.ofSeconds(20), "dead-node-grace");
        leaderLockTtl = positiveOr(leaderLockTtl, WorldChannels.DEFAULT_LEADER_LOCK_TTL, "leader-lock-ttl");
        if (leaderLockTtl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(PREFIX + "leader-lock-ttl 不能小于 1s: " + leaderLockTtl);
        }
        leaderEligible = leaderEligible == null || leaderEligible;
        cleanupOrphans = cleanupOrphans == null || cleanupOrphans;
        reservationTtl = nonNegativeOr(reservationTtl, WorldChannels.DEFAULT_RESERVATION_TTL, "reservation-ttl");
        loginOwnerClaimWait = nonNegativeOr(loginOwnerClaimWait, Duration.ofSeconds(3), "login-owner-claim-wait");
        // Q8：预占在玩家真正进场、节点上报新人数之前就到期，并发分配就看不见这个人
        WorldChannels.requireReservationTtlCovers(reservationTtl, loginOwnerClaimWait);
        rebalance = rebalance == null ? new Rebalance(null, null) : rebalance;
        autoscale = autoscale == null ? new Autoscale(null, null, null, null, null, null, null, null) : autoscale;
    }

    /** 缺省配置（单测与不配任何 {@code xm.scene-manager.world.*} 时）。 */
    public static WorldChannelProperties defaults() {
        return new WorldChannelProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 某图的种子频道数（同基线 ChannelCountFor，config.go:333-351）：按 conf 覆盖优先，否则 {@link #channelCount()}；结果 ≥1。
     * <b>不钳到</b> {@code autoscale.max-channels}（同基线：只有改写期望数时才钳，world_autoscale.go:101-117）。
     */
    public int seedFor(int sceneConfigId) {
        Integer override = channelCountByConfig.get(sceneConfigId);
        return override != null ? override : channelCount;
    }

    /**
     * 择机迁移（§4.8）。
     *
     * @param maxMigrationsPerTick 每拍最多计划几条迁移（基线 MaxRebalanceMigrationsPerTick，config.go:190-197）：缺省 10，0 = 关闭，&lt;0 当 10
     *                             （world_rebalance.go:127-133）
     * @param interval             活节点集合没变时多久再跑一次（基线 RebalanceCheckIntervalSeconds = 300，config.go:199-205）；0 = 只在集合变化时
     */
    public record Rebalance(Integer maxMigrationsPerTick, Duration interval) {

        public Rebalance {
            maxMigrationsPerTick = maxMigrationsPerTick == null || maxMigrationsPerTick < 0 ? 10 : maxMigrationsPerTick;
            interval = nonNegativeOr(interval, Duration.ofSeconds(300), "rebalance.interval");
        }
    }

    /**
     * 自动扩缩容（基线 WorldAutoscaleConfig，config.go:297-331；缺省全同）。
     *
     * @param enabled         总开关（缺省 false）
     * @param checkInterval   决策间隔（≤0 当 30 s，world_autoscale.go:127-130）
     * @param scaleOutPlayers 该图<b>所有</b>（已建出、未排空的 ACTIVE）频道人数都 ≥ 它才扩容
     * @param scaleInPlayers  最空的频道 &lt; 它才考虑缩容；开启时必须 &lt; {@code scaleOutPlayers}（基线只在注释里要求宽带，config.go:309-314）
     * @param minChannels     每图最少频道数（硬下限 1，config.go:316-318）
     * @param maxChannels     每图最多频道数（0 = 不限，但仍受 slot 上限 999 约束；开启时必须 ≤999 且 ≥ min）
     * @param cooldown        一次伸缩后该图静默多久（≤0 当 120 s，world_autoscale.go:497-500）
     * @param drainTimeout    排空满这么久仍未收尾：缩容排空回滚 ACTIVE（D10、Q5），其它原因只告警（≤0 当 300 s）。<b>不论开关都生效</b>
     */
    public record Autoscale(Boolean enabled, Duration checkInterval, Integer scaleOutPlayers, Integer scaleInPlayers,
                            Integer minChannels, Integer maxChannels, Duration cooldown, Duration drainTimeout) {

        public Autoscale {
            enabled = enabled != null && enabled;
            checkInterval = positiveOrDefault(checkInterval, Duration.ofSeconds(30));
            scaleOutPlayers = scaleOutPlayers == null ? 2000 : scaleOutPlayers;
            scaleInPlayers = scaleInPlayers == null ? 100 : scaleInPlayers;
            minChannels = minChannels == null || minChannels < 1 ? 1 : minChannels;
            maxChannels = maxChannels == null ? 16 : Math.max(0, maxChannels);
            cooldown = positiveOrDefault(cooldown, Duration.ofSeconds(120));
            drainTimeout = positiveOrDefault(drainTimeout, Duration.ofSeconds(300));
            if (scaleOutPlayers < 0 || scaleInPlayers < 0) {
                throw new IllegalArgumentException(PREFIX + "autoscale.scale-out-players / scale-in-players 不能为负");
            }
            if (enabled) {
                if (scaleInPlayers >= scaleOutPlayers) {
                    throw new IllegalArgumentException(PREFIX + "autoscale.scale-in-players（" + scaleInPlayers
                            + "）必须小于 scale-out-players（" + scaleOutPlayers + "）：缩容把人并过去会立刻触发扩容，来回抖");
                }
                if (maxChannels > WorldChannels.MAX_CHANNELS_PER_MAP) {
                    throw new IllegalArgumentException(PREFIX + "autoscale.max-channels 不能超过 "
                            + WorldChannels.MAX_CHANNELS_PER_MAP + "（slot 0..998）: " + maxChannels);
                }
                if (maxChannels > 0 && minChannels > maxChannels) {
                    throw new IllegalArgumentException(PREFIX + "autoscale.min-channels（" + minChannels
                            + "）不能大于 max-channels（" + maxChannels + "）");
                }
            }
        }

        /**
         * 改写期望数时的钳制（同基线 setDesiredWorldChannelCount，world_autoscale.go:101-117）：
         * {@code min(max(n, max(1, min-channels)), max-channels > 0 ? max-channels : 999)}。
         */
        public int clamp(int n) {
            int upper = maxChannels > 0 ? Math.min(maxChannels, WorldChannels.MAX_CHANNELS_PER_MAP)
                    : WorldChannels.MAX_CHANNELS_PER_MAP;
            return Math.min(Math.max(n, Math.max(1, minChannels)), upper);
        }

        /** 扩容的期望数上限（{@code max-channels}，0 = 只受 slot 上限约束）。 */
        public int effectiveMaxChannels() {
            return maxChannels > 0 ? Math.min(maxChannels, WorldChannels.MAX_CHANNELS_PER_MAP)
                    : WorldChannels.MAX_CHANNELS_PER_MAP;
        }
    }

    private static Map<Integer, Integer> normalizeByConfig(Map<Integer, Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> out = new TreeMap<>(Integer::compareUnsigned);
        for (Map.Entry<Integer, Integer> e : raw.entrySet()) {
            Integer conf = e.getKey();
            Integer count = e.getValue();
            if (conf == null || conf == 0 || count == null || count <= 0) {
                continue; // 同基线：值 ≤0 的覆盖项跳过（config.go:339-341）
            }
            if (count > WorldChannels.MAX_CHANNELS_PER_MAP) {
                throw new IllegalArgumentException(PREFIX + "channel-count-by-config." + Integer.toUnsignedString(conf)
                        + " 不能超过 " + WorldChannels.MAX_CHANNELS_PER_MAP + "（slot 0..998）: " + count);
            }
            out.put(conf, count);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(PREFIX + name + " 必须为正: " + value);
        }
        return value;
    }

    private static Duration nonNegativeOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative()) {
            throw new IllegalArgumentException(PREFIX + name + " 不能为负: " + value);
        }
        return value;
    }

    /** 基线口径：≤0 当缺省（world_autoscale.go:127-130、:345-348、:497-500）。 */
    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
