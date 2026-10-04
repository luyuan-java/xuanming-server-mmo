package com.game.guild.service;

import com.game.common.deadline.Deadline;
import com.game.guild.cache.GuildCache;
import com.game.guild.cache.GuildCacheInvalidator;
import com.game.guild.cache.InvalidationOp;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildTip;
import com.game.guild.rules.GuildTips;
import com.game.guild.rules.RejectReply;
import com.game.guild.store.GuildStore;
import com.game.guild.store.Invalidation;
import com.game.guild.zone.HomeZones;
import com.game.guild.zone.MergeFence;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 所有 RPC 共用的前置与收尾（基线 guild_logic.go:124-171 callerOf / clientZone / visibleIn、:293-320 mergeFenceTip、
 * guild_manage_logic.go:198-329 clientWrite / operatorGuild / verifyMapping / mapWriteErr、economy_logic.go:240-273
 * leftGuildWhileResolving；guild-spec §3.1、§2.7）。4.5 / 4.6 的前置（economyCaller 不查归属区、activityPrelude）复用这些公开方法（§6.4）。
 *
 * <p>全局规则（§0.6）：
 * <ul>
 *   <li>身份只认会话（调用方传入 {@code playerId}，已保证非 0）；请求体里的 player_id / zone_id 一律忽略；</li>
 *   <li>写 RPC 的前置顺序固定：会话 → 归属区 → 合服闸门 → 业务前置 → 事务（{@link #clientWrite}）：归属区未知时<b>不发</b>闸门查询，
 *       闸门查的是<b>归属区</b>；</li>
 *   <li>业务拒绝回 in-band tip（返回 {@link GuildTip}）；故障抛异常（依赖故障原样抛，配表 / 双存储矛盾抛 {@link GuildFaultException}），
 *       派发器回信封 1003；</li>
 *   <li>授权只认锁内 MySQL：缓存只用来定位操作者属于哪个帮、以及展示。</li>
 * </ul>
 *
 * <p>阻塞（MySQL / 等 Redis），只在工作线程上调用。线程安全。
 */
public final class GuildAccess {

    private static final Logger log = LoggerFactory.getLogger(GuildAccess.class);

    /**
     * 归属区检查的结果：{@code tip} 非 null = 业务拒绝（14012 / 14013）；否则 {@code zoneId} 有效（&gt; 0）。
     */
    public record ZoneCheck(int zoneId, GuildTip tip) {

        static ZoneCheck ok(int zoneId) {
            return new ZoneCheck(zoneId, null);
        }

        static ZoneCheck rejected(GuildTip tip) {
            return new ZoneCheck(0, tip);
        }

        public boolean rejected() {
            return tip != null;
        }
    }

    /** 操作者所在帮会：{@code tip} 非 null = 不在任何帮（14002）；否则 {@code guildId} 非 0。 */
    public record Membership(long guildId, GuildTip tip) {

        public boolean rejected() {
            return tip != null;
        }
    }

    private final GuildCache cache;
    private final HomeZones homeZones;
    private final MergeFence fence;
    private final GuildCacheInvalidator invalidator;

    public GuildAccess(GuildCache cache, HomeZones homeZones, MergeFence fence, GuildCacheInvalidator invalidator) {
        this.cache = cache;
        this.homeZones = homeZones;
        this.fence = fence;
        this.invalidator = invalidator;
    }

    // ================================================================ 归属区与闸门

    /**
     * 客户端请求所属的 zone（基线 clientZone，guild_logic.go:150-166）：查询出错 → 原样抛（故障）；查不到或为 0 → 14012（打 Info 提示运维补映射）。
     */
    public ZoneCheck clientZone(long playerId, Deadline deadline) {
        return zoneOf(homeZones.homeZones(List.of(playerId), deadline), playerId);
    }

    /** 一次批量查多名玩家的归属区（审批通过时审批人与申请人合成一条 IN 查询，§7.10）。查询出错原样抛。 */
    public Map<Long, Integer> homeZonesOf(List<Long> playerIds, Deadline deadline) {
        return homeZones.homeZones(playerIds, deadline);
    }

    /** 从批量结果里取一名玩家的归属区：缺项或 0 → 14012。 */
    public ZoneCheck zoneOf(Map<Long, Integer> zones, long playerId) {
        Integer zone = zones.get(playerId);
        if (zone == null || zone == 0) {
            log.info("[guild] player {} has no home zone, guild request refused", Long.toUnsignedString(playerId));
            return ZoneCheck.rejected(GuildTip.HOME_ZONE_UNKNOWN);
        }
        return ZoneCheck.ok(zone);
    }

    /**
     * 事务外合服闸门（基线 mergeFenceTip，guild_logic.go:306-320）：zone 为 0 → 放行；读失败 → 14013「merge fence unreadable」
     * （ERROR，fail-closed）；键存在 → 14013「zone merging」（Info）。返回 null = 放行。
     */
    public GuildTip mergeFenceTip(int zoneId) {
        if (zoneId == 0 || fence == MergeFence.NONE) {
            return null;
        }
        boolean merging;
        try {
            merging = fence.mergeInProgress(zoneId);
        } catch (Exception e) {
            log.error("[guild] merge fence unreadable for zone {}, refusing (fail closed): {}", Integer.toUnsignedString(zoneId),
                    e.toString());
            return GuildTip.MERGE_FENCE_UNREADABLE;
        }
        if (merging) {
            log.info("[guild] refused: zone {} is merging", Integer.toUnsignedString(zoneId));
            return GuildTip.ZONE_MERGING;
        }
        return null;
    }

    /**
     * 写 RPC 的统一前置（基线 clientWrite，guild_manage_logic.go:222-232）：归属区（14012 / 故障）→ 闸门（14013）。
     * 顺序钉死：归属区未知时不发闸门查询（guild_manage_logic_test.go:231-244）。
     */
    public ZoneCheck clientWrite(long playerId, Deadline deadline) {
        ZoneCheck zone = clientZone(playerId, deadline);
        if (zone.rejected()) {
            return zone;
        }
        GuildTip fenced = mergeFenceTip(zone.zoneId());
        return fenced == null ? zone : ZoneCheck.rejected(fenced);
    }

    /** 事务内闸门（解散：锁住 guild 行后按行里的 zone_id 再判一次）。 */
    public GuildStore.ZoneFence txFence() {
        return fence.inTransaction();
    }

    // ================================================================ 成员关系

    /**
     * 操作者所在帮会（基线 operatorGuild，guild_manage_logic.go:239-253）：缓存映射（Redis 错误 → 故障）；读到 0 时<b>用 MySQL 复核</b>
     * （映射连 0 也缓存 30 分钟，审批通过后失效失败会留下陈旧的 0），仍为 0 → 14002「not in any guild」。
     */
    public Membership operatorGuild(long playerId, Deadline deadline) {
        long id = cache.guildIdOf(playerId, deadline);
        if (id == 0) {
            id = cache.verifyGuildIdOf(playerId, 0, deadline);
            if (id == 0) {
                return new Membership(0, GuildTip.NOT_IN_ANY_GUILD);
            }
        }
        return new Membership(id, null);
    }

    /**
     * 已在帮预检（建帮 / 申请 / 本人申请列表；guild_logic.go:225-239、guild_manage_logic.go:473-487、:547-561）：缓存说在帮时用 MySQL 复核，
     * 复核仍在帮才算；缓存说 0 时不复核（真正的判定在事务里）。缓存或复核出错 → 原样抛（故障）。
     */
    public boolean alreadyInGuild(long playerId, Deadline deadline) {
        long cached = cache.guildIdOf(playerId, deadline);
        return cached != 0 && cache.verifyGuildIdOf(playerId, cached, deadline) != 0;
    }

    /**
     * 事务已经按 MySQL 判定之后，顺手把可能陈旧的 Redis 映射纠正过来（基线 verifyMapping，guild_manage_logic.go:255-267）。
     * 失败只记日志：本次的回答已经由 MySQL 决定了。
     */
    public void verifyMapping(long playerId, long cachedGuildId, Deadline deadline) {
        if (playerId == 0) {
            return;
        }
        try {
            cache.verifyGuildIdOf(playerId, cachedGuildId, deadline);
        } catch (RuntimeException e) {
            log.error("[guild] verify guild mapping of player {} (cached {}): {}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(cachedGuildId), e.toString());
        }
    }

    /**
     * 解析途中刚被踢 / 退帮的识别（基线 leftGuildWhileResolving，economy_logic.go:240-273）：{@code resolve} 报错后以 M5 再复核一次——
     * 此刻不在任何帮 → 14002「not in any guild」；仍在某帮，或复核本身失败 → null（调用方原错误照回、按故障）。
     */
    public GuildTip leftGuildWhileResolving(long playerId, long cachedGuildId, RuntimeException cause, Deadline deadline) {
        long actual;
        try {
            actual = cache.verifyGuildIdOf(playerId, cachedGuildId, deadline);
        } catch (RuntimeException e) {
            log.info("[guild] membership recheck of player {} failed after resolve error: {} (resolve: {})",
                    Long.toUnsignedString(playerId), e.toString(), cause.toString());
            return null;
        }
        if (actual != 0) {
            return null;
        }
        log.info("[guild] player {} is in no guild on recheck (cached guild {}), answering not-in-guild (resolve: {})",
                Long.toUnsignedString(playerId), Long.toUnsignedString(cachedGuildId), cause.toString());
        return GuildTip.NOT_IN_ANY_GUILD;
    }

    // ================================================================ 事务结局 → 答复

    /**
     * 存储拒绝 → 客户端答复（基线 mapWriteErr，guild_manage_logic.go:281-329；整表在 {@link GuildTips#forReject}）。
     *
     * @param logGuildId    这次操作打的是哪个帮（只进日志）
     * @param cachedGuildId 以为操作者属于哪个帮（只喂映射自愈；申请 / 撤回传 0）
     * @return 写进应答 error_message 的 tip
     * @throws GuildFaultException {@code LEADER_MISMATCH} / {@code LEVEL_CONFIG_MISSING}（信封 1003，记 ERROR）
     */
    public GuildTip replyFor(long actor, long logGuildId, long cachedGuildId, GuildReject reject, Deadline deadline) {
        return reply(GuildTips.forReject(reject), actor, logGuildId, cachedGuildId, reject, deadline);
    }

    /** 解散专用：{@code RANK_TOO_LOW} 回 14005「not guild leader」（guild_logic.go:494-498），其余同 {@link #replyFor}。 */
    public GuildTip disbandReplyFor(long actor, long guildId, GuildReject reject, Deadline deadline) {
        return reply(GuildTips.forDisbandReject(reject), actor, guildId, guildId, reject, deadline);
    }

    private GuildTip reply(RejectReply reply, long actor, long logGuildId, long cachedGuildId, GuildReject reject,
                           Deadline deadline) {
        return switch (reply) {
            case RejectReply.Tip t -> {
                switch (t.repair()) {
                    case VERIFY_AGAINST_CACHED -> verifyMapping(actor, cachedGuildId, deadline);
                    case VERIFY_AGAINST_ZERO -> verifyMapping(actor, 0, deadline);
                    case NONE -> {
                    }
                }
                if (reject == GuildReject.WRITE_CONFLICT) {
                    // 记 Info 不记 Error：两个人同时改同一个帮是正常玩法，原地重试一次就能成功
                    log.info("[guild] guild {} write conflict (player {}), asking the client to retry",
                            Long.toUnsignedString(logGuildId), Long.toUnsignedString(actor));
                }
                yield t.tip();
            }
            case RejectReply.Fault f -> {
                log.error("[guild] guild {} data or config inconsistent (player {}): {}", Long.toUnsignedString(logGuildId),
                        Long.toUnsignedString(actor), reject.sentinel());
                throw new GuildFaultException(f.reason());
            }
        };
    }

    // ================================================================ 提交之后

    /**
     * 提交之后失效缓存（先用请求预算同步失效一次，失败的键交后台有界重试；永不抛）。必须在推送<b>之前</b>调用：收件人收到推送立即拉取时要读到新值。
     */
    public void invalidate(Invalidation invalidation, Deadline deadline) {
        if (invalidation.isEmpty()) {
            return;
        }
        InvalidationOp op;
        try {
            op = InvalidationOp.ofLabel(invalidation.op().label());
        } catch (IllegalArgumentException e) {
            // 两个 op 枚举是同一个固定集合（GuildTxOp ↔ InvalidationOp），走到这里是代码漂移；写已提交，绝不能因此报失败
            log.error("[guild] 未登记的失效 op {}，改记为 verify_mapping", invalidation.op().label(), e);
            op = InvalidationOp.VERIFY_MAPPING;
        }
        invalidator.afterCommit(op, invalidation.guildId(), invalidation.playerIds(), deadline);
    }
}
