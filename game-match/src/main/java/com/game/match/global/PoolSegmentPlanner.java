package com.game.match.global;

import com.game.common.workshard.WorkShard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全服匹配热池拆分（对齐主仓 2026-11-12「全服匹配热池拆分」：{@code poolowner/segments} 与 {@code poolgroup/passes}）：
 * <ul>
 *   <li>按模板配置子池数 {@code 1..64}（默认 1 不拆）与子池合并时长（须严格小于跨机房等待阈值），越界启动期即拒绝；不进全局策略指纹。</li>
 *   <li>每个热池模板拆成三类职责段：{@link Role#LOCAL_PREFERRED}（按首选机房 × 子池）、{@link Role#CROSS_REGION}（按跨机房）、
 *       {@link Role#FALLBACK}（未拆/全池兜底），由 {@link WorkShard.Membership} 统一做 HRW 归属分配。</li>
 *   <li>提交点票行已被其他副本认领归类为正常竞争 {@link ClaimStaleException}，不计为结局未知。</li>
 * </ul>
 */
public final class PoolSegmentPlanner {

    public static final int MIN_SUBPOOLS = 1;
    public static final int MAX_SUBPOOLS = 64;

    /** 撮合段角色（用于低基数指标 role 标签，绝不放模板号或机房号）。 */
    public enum Role {
        LOCAL_PREFERRED,
        CROSS_REGION,
        FALLBACK;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 撮合轮三阶段选择：本区子池 -> 超合并阈值跨子池本区 -> 超跨区阈值全区。 */
    public enum PassPhase {
        LOCAL_SUBPOOL,
        LOCAL_MERGED,
        CROSS_REGION
    }

    /** 提交点票行复核失败哨兵：归类为多副本正常竞争（对齐主仓 {@code ErrClaimStale}），不当成结局未知。 */
    public static final class ClaimStaleException extends RuntimeException {
        public ClaimStaleException(String message) {
            super(message);
        }
    }

    /** 单个热池模板的拆分配置（越界拒启，不进全局策略指纹）。 */
    public record TemplateSplitConfig(int mode, int configId, int teamSize, int subpoolCount, int mergeWaitSeconds) {
        public TemplateSplitConfig {
            if (subpoolCount < MIN_SUBPOOLS || subpoolCount > MAX_SUBPOOLS) {
                throw new IllegalArgumentException("热池子池数越界(须 1..64): " + subpoolCount);
            }
            if (mergeWaitSeconds < 0) {
                throw new IllegalArgumentException("子池合并时长不能为负: " + mergeWaitSeconds);
            }
        }

        /** 启动期校验：合并时长必须严格小于跨机房等待阈值（crossRegionWaitSeconds）。 */
        public void validateAgainstCrossRegionWait(int crossRegionWaitSeconds) {
            if (subpoolCount > 1 && mergeWaitSeconds >= crossRegionWaitSeconds) {
                throw new IllegalArgumentException("热池合并时长(" + mergeWaitSeconds
                        + "s)必须严格小于跨机房等待阈值(" + crossRegionWaitSeconds + "s)");
            }
        }
    }

    /** 一个可被 HRW 分配给某个副本负责的撮合段。 */
    public record Segment(int mode, int configId, int teamSize, int region,
                          int subpoolCount, int subpoolIndex, Role role) {
        /** 稳定 HRW 归属键。 */
        public String ownershipKey() {
            return "poolseg:" + role.label() + ":" + mode + ":" + configId + ":" + teamSize
                    + ":r" + region + ":s" + subpoolIndex + "/" + subpoolCount;
        }

        /** 返回读页区间 SQL 片段（置于 LIMIT 之前）；未拆（subpoolCount <= 1 或非子池段）返回空串。 */
        public String sqlSubpoolPredicate(String entityIdColumn) {
            if (subpoolCount <= 1 || role != Role.LOCAL_PREFERRED) {
                return "";
            }
            return "MOD(" + entityIdColumn + "," + subpoolCount + ")=" + subpoolIndex;
        }
    }

    /** 为业务实体（玩家或整队）计算确定性子池号（0..subpoolCount-1）。 */
    public static int subpoolOf(long entityId, int subpoolCount) {
        if (subpoolCount < MIN_SUBPOOLS || subpoolCount > MAX_SUBPOOLS) {
            throw new IllegalArgumentException("子池数越界: " + subpoolCount);
        }
        return subpoolCount == 1 ? 0 : (int) Long.remainderUnsigned(entityId, subpoolCount);
    }

    /** 枚举某模板在给定活跃机房列表上的全部撮合段（本区子池段 + 跨机房段 + 兜底段）。 */
    public static List<Segment> enumerateSegments(TemplateSplitConfig cfg, List<Integer> activeRegions) {
        Objects.requireNonNull(cfg, "cfg");
        List<Segment> out = new ArrayList<>();
        if (activeRegions != null) {
            for (int region : activeRegions) {
                if (cfg.subpoolCount() > 1) {
                    for (int s = 0; s < cfg.subpoolCount(); s++) {
                        out.add(new Segment(cfg.mode(), cfg.configId(), cfg.teamSize(), region,
                                cfg.subpoolCount(), s, Role.LOCAL_PREFERRED));
                    }
                } else {
                    out.add(new Segment(cfg.mode(), cfg.configId(), cfg.teamSize(), region,
                            1, 0, Role.LOCAL_PREFERRED));
                }
            }
        }
        out.add(new Segment(cfg.mode(), cfg.configId(), cfg.teamSize(), 0, cfg.subpoolCount(), 0, Role.CROSS_REGION));
        if (cfg.subpoolCount() > 1) {
            out.add(new Segment(cfg.mode(), cfg.configId(), cfg.teamSize(), 0, 1, 0, Role.FALLBACK));
        }
        return List.copyOf(out);
    }

    /** 仅保留归属于本副本负责的段（空成员表保活全做，非成员如 canary 返回空）。 */
    public static List<Segment> ownedSegments(List<Segment> all, WorkShard.Membership membership) {
        if (membership == null) {
            return all;
        }
        List<Segment> owned = new ArrayList<>();
        for (Segment seg : all) {
            if (membership.owns(seg.ownershipKey())) {
                owned.add(seg);
            }
        }
        return List.copyOf(owned);
    }

    /** 根据等待时长与配置决定当前锚点可参与的撮合阶段（三阶段选择：本区子池 -> 本区合并 -> 跨机房）。 */
    public static PassPhase selectPass(long anchorWaitMs, TemplateSplitConfig cfg, int crossRegionWaitSeconds) {
        long crossMs = Math.max(1L, crossRegionWaitSeconds) * 1000L;
        if (anchorWaitMs >= crossMs) {
            return PassPhase.CROSS_REGION;
        }
        if (cfg.subpoolCount() > 1 && anchorWaitMs >= cfg.mergeWaitSeconds() * 1000L) {
            return PassPhase.LOCAL_MERGED;
        }
        return cfg.subpoolCount() > 1 ? PassPhase.LOCAL_SUBPOOL : PassPhase.LOCAL_MERGED;
    }

    /** 低基数指标（仅按段角色 role 与提交结果 result 打标签，绝不放模板/机房/玩家号）。 */
    public static final class Metrics {
        private final Map<Role, AtomicInteger> ownedCounts = new LinkedHashMap<>();
        private final Map<String, Counter> commits = new ConcurrentHashMap<>();
        private final MeterRegistry registry;

        public Metrics(MeterRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
            for (Role role : Role.values()) {
                AtomicInteger holder = new AtomicInteger();
                ownedCounts.put(role, holder);
                Gauge.builder("game.match.pool.segments.owned", holder, AtomicInteger::get)
                        .description("当前副本按段角色负责的热池撮合段数量")
                        .tag("role", role.label())
                        .register(registry);
            }
        }

        public void recordOwnedSegments(List<Segment> owned) {
            Map<Role, Integer> counts = new LinkedHashMap<>();
            for (Role r : Role.values()) counts.put(r, 0);
            for (Segment seg : owned) {
                counts.merge(seg.role(), 1, Integer::sum);
            }
            counts.forEach((role, c) -> ownedCounts.get(role).set(c));
        }

        public void recordCommit(Role role, String result) {
            String key = role.label() + ":" + result;
            commits.computeIfAbsent(key, k -> Counter.builder("game.match.pool.segment.commits")
                    .description("热池撮合段提交结果（committed / claim_stale / error）")
                    .tag("role", role.label())
                    .tag("result", result)
                    .register(registry)).increment();
        }
    }
}
