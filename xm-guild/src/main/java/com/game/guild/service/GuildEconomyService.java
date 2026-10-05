package com.game.guild.service;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.api.proto.AssetStream;
import com.game.common.deadline.Deadline;
import com.game.common.time.GameDay;
import com.game.guild.asset.AssetOp;
import com.game.guild.asset.AssetOpDecisions;
import com.game.guild.asset.AssetOpProcessor;
import com.game.guild.asset.DeliveryOrigin;
import com.game.guild.cache.GuildCache;
import com.game.guild.cache.pb.GuildSnapshot;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.metrics.GuildMetrics.AssetKind;
import com.game.guild.metrics.GuildMetrics.EconomyResult;
import com.game.guild.metrics.GuildMetrics.EconomyRpc;
import com.game.guild.push.GuildPushes;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildTableRules;
import com.game.guild.rules.GuildTip;
import com.game.guild.service.EconomyViews.OrderView;
import com.game.guild.service.GuildAccess.Membership;
import com.game.guild.store.EconomyStore;
import com.game.guild.store.EconomyStore.Contribution;
import com.game.guild.store.EconomyStore.OpState;
import com.game.guild.store.EconomyStore.Reserved;
import com.game.guild.store.EconomyStore.ShopReserved;
import com.game.guild.store.EconomyStore.ShopUsageKey;
import com.game.guild.store.EconomyStore.Upgraded;
import com.game.guild.store.GuildData;
import com.game.guild.store.TxOutcome;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.proto.TransactionType;
import com.game.proto.guild.BuyGuildShopGoodsRequest;
import com.game.proto.guild.BuyGuildShopGoodsResponse;
import com.game.proto.guild.DonateToGuildRequest;
import com.game.proto.guild.DonateToGuildResponse;
import com.game.proto.guild.GetGuildDonateOptionsRequest;
import com.game.proto.guild.GetGuildDonateOptionsResponse;
import com.game.proto.guild.GetGuildShopRequest;
import com.game.proto.guild.GetGuildShopResponse;
import com.game.proto.guild.GuildAssetOrderStatus;
import com.game.proto.guild.GuildDonationView;
import com.game.proto.guild.GuildInfo;
import com.game.proto.guild.GuildShopOrderView;
import com.game.proto.guild.UpgradeGuildRequest;
import com.game.proto.guild.UpgradeGuildResponse;
import com.game.table.GuildDonateTable;
import com.game.table.GuildShopTable;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会经济：捐献页 / 捐献 / 升级 / 商店页 / 兑换（基线 economy_logic.go；guild-economy-spec §3.0–§3.5）。
 *
 * <p>四条贯穿全文件的纪律（economy_logic.go:3-16）：
 * <ol>
 *   <li><b>身份只认会话，帮会只认复核过的映射</b>（{@link #economyCaller}）：快照里没有本人时以 MySQL 复核；事务回「不是成员 / 帮会不存在」时
 *       经 {@link GuildAccess#replyFor} 自愈映射。经济 RPC <b>不查</b>归属区（入帮事务已保证「是成员 ⇒ 归属区一致」，合服窗口由事务内闸门兜底）；</li>
 *   <li><b>合服闸门在事务里</b>：zone 一律取事务内读到的 guild.zone_id（Java 4.4 恒放行，检查点保留）；</li>
 *   <li><b>PENDING 不是错误</b>：scene 暂时不可达时指令留在 outbox 由重投循环接手，玩家看到的是视图里的「结算中」，不是 error_message
 *       （客户端遇到任何非 0 tip 都会中断后续刷新）；</li>
 *   <li><b>业务拒绝回 tip，故障抛异常</b>（派发器回信封 1003）。</li>
 * </ol>
 *
 * <p><b>同步投递不占 guild-worker</b>（Q5，§7.4）：捐献 / 兑换在 guild-worker 上做完前置与预留事务后返回 future——资产 RPC 异步发出、重查等待由定时器排、
 * 落库在有界的 {@code guild-asset-settle} 执行器上跑（700 ms 自有预算、不随请求取消），最后<b>回到 guild-worker</b> 做 O6 回读与装配。
 * 预算 {@code min(2500, 请求剩余 − 1000)}，低于 300 ms 跳过（计 {@code xm_guild_asset_sync_skipped_total}，行在插行租约到期后由循环领走）。
 * 同步投递里终结的不推送（{@link DeliveryOrigin#SYNC}，E9）；结局一律以回读为准，回读失败按 PENDING 展示（玩家会以为没扣钱而再点）。
 * 回 guild-worker 时工作队列满了：不回读，直接按 PENDING 回包（同「回读失败」，行已提交、最终会被循环终结）。
 *
 * <p>资产通道关闭（{@code xm.guild.asset-op.enabled=false}，{@code channel == null}）时捐献 / 兑换在发号与建行之前回 14026，升级与两个读页照常。
 * 一个请求只取一次 now：周期键、截止、租约、created_ms 都从它算。除返回 future 的两个方法的异步尾段外，全部方法阻塞，只在 guild-worker 上调用。线程安全。
 */
public final class GuildEconomyService {

    private static final Logger log = LoggerFactory.getLogger(GuildEconomyService.class);

    /** 经济前置的结果：{@code tip} 非 null = 业务拒绝；否则 {@code guild} 非 null 且快照里有本人。 */
    record Prelude(GuildData guild, GuildTip tip) {
    }

    /** 一次 RPC 的应答 + 指标结果。 */
    private record Answer<T>(T response, EconomyResult result) {
    }

    private final EconomyStore store;
    private final GuildCache cache;
    private final GuildAccess access;
    private final GuildViews views;
    private final EconomyTables.Lookup tables;
    private final GuildPushes pushes;
    private final GuildMetrics metrics;
    private final LongSupplier opIds;
    private final RandomGenerator tokens;
    private final AssetOpProcessor channel;
    private final long insertLeaseMs;
    private final Executor workers;
    private final LongSupplier clockMs;

    /**
     * @param opIds       发 op_id（生产 {@code GuildIds::nextId}，与 guild_id 同一个雪花，E6）；抛异常或返回 0 → 14008，绝不自造 id
     * @param tokens      插行租约令牌的随机源（生产 {@link java.security.SecureRandom}）
     * @param channel     同步投递（生产 {@code AssetOpLoop}）；null = 资产通道关闭
     * @param insertLease 插行租约（= {@code xm.guild.asset-op.lease}）：同步投递期间重投循环看不见这一行
     * @param workers     guild-worker 池：同步投递之后回到这里做回读与装配
     * @param clockMs     本服务唯一的「现在」（Unix 毫秒）
     */
    public GuildEconomyService(EconomyStore store, GuildCache cache, GuildAccess access, GuildViews views,
                               EconomyTables.Lookup tables, GuildPushes pushes, GuildMetrics metrics, LongSupplier opIds,
                               RandomGenerator tokens, AssetOpProcessor channel, Duration insertLease, Executor workers,
                               LongSupplier clockMs) {
        this.store = Objects.requireNonNull(store, "store");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.access = Objects.requireNonNull(access, "access");
        this.views = Objects.requireNonNull(views, "views");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.pushes = Objects.requireNonNull(pushes, "pushes");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.opIds = Objects.requireNonNull(opIds, "opIds");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.channel = channel;
        this.insertLeaseMs = insertLease.toMillis();
        this.workers = Objects.requireNonNull(workers, "workers");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        if (insertLeaseMs <= 0) {
            throw new IllegalArgumentException("插行租约必须为正: " + insertLease);
        }
    }

    /** 资产通道是否开启（启动日志用）。 */
    public boolean assetChannelEnabled() {
        return channel != null;
    }

    // ================================================================ 公共前置

    /**
     * 五个经济 RPC 的公共前置（economyCaller，economy_logic.go:189-238；<b>不查归属区</b>）。快路径：operatorGuild（缓存读到 0 时用 MySQL 复核）→
     * 帮会快照 → 快照里有本人。坏路径（映射指着一个已不存在的帮，或快照里没有本人）交 {@link GuildCache#resolve} 以 MySQL 复核（失效可疑快照、
     * 纠正映射、绕过缓存直读权威快照）；它报错时先经 {@link GuildAccess#leftGuildWhileResolving} 判「此刻还在不在帮」，不在 → 14002，否则原错误照抛（故障）。
     */
    Prelude economyCaller(long me, Deadline deadline) {
        Membership membership = access.operatorGuild(me, deadline);
        if (membership.rejected()) {
            return new Prelude(null, membership.tip());
        }
        long guildId = membership.guildId();
        Optional<GuildSnapshot> snapshot = cache.guild(guildId, deadline);
        if (snapshot.isPresent() && GuildCache.hasMember(snapshot.get(), me)) {
            return new Prelude(GuildSnapshots.toData(snapshot.get()), null);
        }
        Optional<GuildSnapshot> fresh;
        try {
            fresh = cache.resolve(me, deadline);
        } catch (RuntimeException e) {
            GuildTip tip = access.leftGuildWhileResolving(me, guildId, e, deadline);
            if (tip != null) {
                return new Prelude(null, tip);
            }
            throw e;
        }
        if (fresh.isEmpty()) {
            return new Prelude(null, GuildTip.NOT_IN_ANY_GUILD);
        }
        if (fresh.get().getGuildId() != guildId) {
            log.info("[GuildEconomy] player {} guild mapping healed from stale {} to {}", Long.toUnsignedString(me),
                    Long.toUnsignedString(guildId), Long.toUnsignedString(fresh.get().getGuildId()));
        }
        return new Prelude(GuildSnapshots.toData(fresh.get()), null);
    }

    /**
     * 经济事务的拒绝 → 答复（economyTip，economy_logic.go:313-344）：tip 与映射自愈一律经 {@link GuildAccess#replyFor}（{@code GuildTips.forReject}
     * 已含经济哨兵）；{@code LEVEL_CONFIG_MISSING} 等抛 {@link GuildFaultException}（信封 1003）。
     */
    private Answer<GuildTip> rejectReply(long me, long guildId, GuildReject reject, Deadline deadline) {
        if (reject == GuildReject.TOO_MANY_PENDING) {
            // 守卫拒绝不是故障：未决行数 / 跨度超限时再发新指令会破坏 scene 账本窗口的正确性证明
            log.info("[GuildEconomy] player {} has too many pending asset ops", Long.toUnsignedString(me));
        }
        GuildTip tip = access.replyFor(me, guildId, guildId, reject, deadline);
        return new Answer<>(tip, resultOf(reject));
    }

    /** 拒绝的指标结果（economyTip 的经济哨兵 + resultOfMapped，economy_logic.go:318-362）。 */
    static EconomyResult resultOf(GuildReject reject) {
        return switch (reject) {
            case ZONE_MERGING -> EconomyResult.FENCE;
            case LEVEL_TOO_LOW, MAX_LEVEL -> EconomyResult.LEVEL;
            case DONATE_LIMIT, SHOP_LIMIT -> EconomyResult.LIMIT;
            case CONTRIBUTION_INSUFFICIENT, FUNDS_INSUFFICIENT -> EconomyResult.INSUFFICIENT;
            case TOO_MANY_PENDING -> EconomyResult.PENDING_GUARD;
            case WRITE_CONFLICT -> EconomyResult.BUSY_RETRY;
            case GUILD_GONE, ZONE_MISMATCH, NOT_MEMBER -> EconomyResult.NOT_MEMBER;
            case RANK_TOO_LOW -> EconomyResult.RANK;
            case LEADER_MISMATCH, LEVEL_CONFIG_MISSING -> EconomyResult.ERROR;
            default -> EconomyResult.OTHER_REJECT;
        };
    }

    // ================================================================ GetGuildDonateOptions（120）

    /** 捐献页（getGuildDonateOptions，economy_logic.go:1052-1122；不查闸门）。 */
    public GetGuildDonateOptionsResponse getGuildDonateOptions(long me, GetGuildDonateOptionsRequest request, Deadline deadline) {
        return counted(EconomyRpc.GET_DONATE_OPTIONS, () -> donateOptions(me, deadline));
    }

    private Answer<GetGuildDonateOptionsResponse> donateOptions(long me, Deadline deadline) {
        long now = clockMs.getAsLong();
        Prelude p = economyCaller(me, deadline);
        if (p.tip() != null) {
            return new Answer<>(GetGuildDonateOptionsResponse.newBuilder().setErrorMessage(p.tip().proto()).build(),
                    EconomyResult.NOT_MEMBER);
        }
        GuildData g = p.guild();
        // 帮贡直读 MySQL、不走缓存：结算刚把帮贡加上时，缓存失效失败会让玩家看到旧数；成员行不在就说明快照过期，后面几次读都不必做
        Optional<Contribution> contribution = store.memberContribution(g.guildId(), me, deadline);
        if (contribution.isEmpty()) {
            access.verifyMapping(me, g.guildId(), deadline);
            return new Answer<>(GetGuildDonateOptionsResponse.newBuilder().setErrorMessage(GuildTip.NOT_A_MEMBER.proto())
                    .build(), EconomyResult.NOT_MEMBER);
        }
        Map<Integer, Integer> usage = store.donateUsage(me, GameDay.dayKey(now), deadline);
        List<GuildAssetOpRow> pending = store.pendingOps(me, AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE,
                GuildLimits.ASSET_OP_MAX_PENDING, deadline);
        List<GuildAssetOpRow> recent = store.recentOps(me, AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE,
                GuildLimits.RECENT_RESULT_SCAN, deadline);

        // 只展示本帮的捐献：离帮前在别的帮发起的捐献与当前帮会的页面无关
        Predicate<GuildAssetOpRow> ownDonation = row -> row.getKind() == GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE
                && row.getGuildId() == g.guildId();
        GetGuildDonateOptionsResponse.Builder resp = GetGuildDonateOptionsResponse.newBuilder()
                .setContributionTotal(contribution.get().total()).setContributionBalance(contribution.get().balance())
                .setNextDailyResetMs(GameDay.nextDailyResetMillis(now));
        for (GuildDonateTable row : tables.sortedDonateRows()) {
            Integer used = usage.get(row.getId());
            resp.addOptions(EconomyViews.donateOptionView(row, used == null ? 0 : used, g.level()));
        }
        for (GuildAssetOpRow row : pending) {
            if (ownDonation.test(row)) {
                resp.addPendingDonations(EconomyViews.donationViewOf(row));
            }
        }
        for (GuildAssetOpRow row : EconomyViews.recentResults(recent, now, ownDonation)) {
            resp.addRecentResults(EconomyViews.donationViewOf(row));
        }
        return new Answer<>(resp.build(), EconomyResult.OK);
    }

    // ================================================================ DonateToGuild（53）

    /**
     * 捐献（donateToGuild，economy_logic.go:703-810）。判定顺序（命中即返回；没写行的拒绝都不带视图）：前置 → 通道关闭 14026 → 选项不存在 14027 →
     * 缓存预判等级 14029 → GuildRule 缺行（故障）→ 令牌 → 发号（14008）→ 预留事务 → 同步投递 → 回读。
     */
    public CompletableFuture<DonateToGuildResponse> donateToGuild(long me, DonateToGuildRequest request, Deadline deadline) {
        Answer<DonateToGuildResponse> early;
        try {
            long start = clockMs.getAsLong();
            Prelude p = economyCaller(me, deadline);
            if (p.tip() != null) {
                early = new Answer<>(donateTip(p.tip()), EconomyResult.NOT_MEMBER);
            } else if (channel == null) {
                early = new Answer<>(donateTip(GuildTip.ASSET_CHANNEL_DISABLED), EconomyResult.DISABLED);
            } else {
                return donate(me, p.guild(), request.getDonateId(), start, deadline);
            }
        } catch (RuntimeException e) {
            metrics.economyRequest(EconomyRpc.DONATE, EconomyResult.ERROR);
            throw e;
        }
        metrics.economyRequest(EconomyRpc.DONATE, early.result());
        return CompletableFuture.completedFuture(early.response());
    }

    private CompletableFuture<DonateToGuildResponse> donate(long me, GuildData g, int donateId, long start, Deadline deadline) {
        GuildDonateTable row = tables.donateOption(donateId);
        if (row == null) {
            // 客户端的选项来自捐献页，查不到只可能是热更删了行或请求被篡改：按拒绝答复
            return done(EconomyRpc.DONATE, donateTip(GuildTip.DONATE_OPTION_NOT_FOUND), EconomyResult.NOT_FOUND);
        }
        // 缓存预判只为省一次发号与事务；事务内按 MySQL 的 level 复核
        if (Integer.compareUnsigned(g.level(), row.getMinGuildLevel()) < 0) {
            return done(EconomyRpc.DONATE, donateTip(GuildTip.GUILD_LEVEL_TOO_LOW), EconomyResult.LEVEL);
        }
        OptionalLong deadlineAfter = tables.assetOpDeadlineMs();
        if (deadlineAfter.isEmpty()) {
            log.error("[GuildEconomy] GuildRule row {} missing, cannot decide the donation deadline", GuildTableRules.RULE_ROW_ID);
            throw new GuildFaultException("GuildRule row " + GuildTableRules.RULE_ROW_ID + " missing");
        }
        AssetBundle bundle = AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder()
                .setCurrencyType(row.getCurrencyType()).setAmount(row.getCostAmount())).build();
        ByteString payload = bundle.toByteString();
        // 令牌先于发号（economy_logic.go:743-747）
        long token = AssetOpDecisions.leaseToken(tokens);
        long opId = mintOpId(me);
        if (opId == 0) {
            return done(EconomyRpc.DONATE, donateTip(GuildTip.ASSET_OP_ID_UNAVAILABLE), EconomyResult.ID_UNAVAILABLE);
        }
        long deadlineMs = start + deadlineAfter.getAsLong();
        TxOutcome<Reserved> outcome = store.reserveDonation(new EconomyStore.DonationReserve(opId, me, g.guildId(), row.getId(),
                row.getMinGuildLevel(), row.getContributionGain(), row.getFundsGain(), row.getDailyLimit(), GameDay.dayKey(start),
                deadlineMs, start + insertLeaseMs, token, start, payload, access.txFence()), deadline);
        if (!(outcome instanceof TxOutcome.Ok<Reserved> ok)) {
            Answer<GuildTip> reply = rejectReply(me, g.guildId(), outcome.rejection(), deadline);
            return done(EconomyRpc.DONATE, donateTip(reply.response()), reply.result());
        }
        Reserved reserved = ok.value();
        // 同步投递用的是内存里的截止（离帮提前截止改的是库里的值且不抢租约，已在进行的同步投递照常发 Debit，§2.3）
        AssetOp op = new AssetOp(opId, me, AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE, reserved.seq(), reserved.streamEpoch(), opId,
                TransactionType.TX_GUILD_DONATE_VALUE, bundle, 0, deadlineMs, token, 0);
        Supplier<DonateToGuildResponse> fallback = () -> donateResponse(opId, row, start,
                new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, 0), null);
        return onWorker(EconomyRpc.DONATE, deliverNow(op, AssetKind.DONATE, deadline), () -> {
            OrderView view = settledView(opId, deadline);
            // APPLIED 时资金与帮贡已入账、缓存已失效，重读才看得到新数字
            DonateToGuildResponse resp = donateResponse(opId, row, start, view, freshGuildInfo(g.guildId(), me, deadline));
            return new Answer<>(resp, EconomyViews.resultOfOrder(view.status()));
        }, () -> new Answer<>(fallback.get(), EconomyResult.PENDING), deadline);
    }

    /** 捐献回包：数值取<b>配表行</b>（不是行上的值），created_ms = 请求开头的 now（economy_logic.go:793-809）。 */
    private static DonateToGuildResponse donateResponse(long opId, GuildDonateTable row, long start, OrderView view,
                                                        GuildInfo guild) {
        DonateToGuildResponse.Builder b = DonateToGuildResponse.newBuilder()
                .setDonation(GuildDonationView.newBuilder()
                        .setOpId(opId).setDonateId(row.getId()).setStatus(view.status())
                        .setCurrencyType(row.getCurrencyType()).setCostAmount(row.getCostAmount())
                        .setContributionGain(row.getContributionGain()).setFundsGain(row.getFundsGain())
                        .setReasonTipId(view.reason()).setCreatedMs(start));
        GuildTip reject = EconomyViews.donationRejectTip(view.status(), view.reason());
        if (reject != null) {
            b.setErrorMessage(reject.proto());
        }
        if (guild != null) {
            b.setGuild(guild);
        }
        return b.build();
    }

    private static DonateToGuildResponse donateTip(GuildTip tip) {
        return DonateToGuildResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ================================================================ UpgradeGuild（76）

    /**
     * 升级（upgradeGuild，economy_logic.go:821-858；不经资产通道，通道关闭时照常）：职位、闸门、expected_level、花费全部在事务里按锁住的帮会行判定。
     * 业务失败也带最新 GuildInfo（资金不足时客户端顺带刷新资金显示），只有「不在帮 / 帮会不存在」不带；成功：真的升了才推 LEVEL_UP（全帮除操作者），
     * expected_level 过期（已被别人升过）回成功 + 最新 GuildInfo、不扣钱、不推。
     */
    public UpgradeGuildResponse upgradeGuild(long me, UpgradeGuildRequest request, Deadline deadline) {
        return counted(EconomyRpc.UPGRADE, () -> upgrade(me, request.getExpectedLevel(), deadline));
    }

    private Answer<UpgradeGuildResponse> upgrade(long me, int expectedLevel, Deadline deadline) {
        Prelude p = economyCaller(me, deadline);
        if (p.tip() != null) {
            return new Answer<>(UpgradeGuildResponse.newBuilder().setErrorMessage(p.tip().proto()).build(),
                    EconomyResult.NOT_MEMBER);
        }
        long guildId = p.guild().guildId();
        TxOutcome<Upgraded> outcome = store.upgradeGuild(guildId, me, expectedLevel, tables::upgradeLevel, access.txFence(),
                deadline);
        if (!(outcome instanceof TxOutcome.Ok<Upgraded> ok)) {
            Answer<GuildTip> reply = rejectReply(me, guildId, outcome.rejection(), deadline);
            UpgradeGuildResponse.Builder resp = UpgradeGuildResponse.newBuilder().setErrorMessage(reply.response().proto());
            if (reply.result() != EconomyResult.NOT_MEMBER) {
                GuildInfo info = freshGuildInfo(guildId, me, deadline);
                if (info != null) {
                    resp.setGuild(info);
                }
            }
            return new Answer<>(resp.build(), reply.result());
        }
        Upgraded upgraded = ok.value();
        // changed 或 staleView（上次升级已提交但回执丢失）都失效 guild(G)；推送在失效之后
        access.invalidate(upgraded.invalidation(), deadline);
        EconomyResult result = EconomyResult.UNCHANGED;
        if (upgraded.changed()) {
            result = EconomyResult.OK;
            pushes.levelUp(guildId, me, upgraded.pushRecipients());
        }
        UpgradeGuildResponse.Builder resp = UpgradeGuildResponse.newBuilder();
        GuildInfo info = freshGuildInfo(guildId, me, deadline);
        if (info != null) {
            resp.setGuild(info);
        }
        return new Answer<>(resp.build(), result);
    }

    // ================================================================ GetGuildShop（228）

    /** 商店页（getGuildShop，economy_logic.go:1131-1207；不查闸门）。 */
    public GetGuildShopResponse getGuildShop(long me, GetGuildShopRequest request, Deadline deadline) {
        return counted(EconomyRpc.GET_SHOP, () -> shop(me, deadline));
    }

    private Answer<GetGuildShopResponse> shop(long me, Deadline deadline) {
        long now = clockMs.getAsLong();
        Prelude p = economyCaller(me, deadline);
        if (p.tip() != null) {
            return new Answer<>(GetGuildShopResponse.newBuilder().setErrorMessage(p.tip().proto()).build(),
                    EconomyResult.NOT_MEMBER);
        }
        GuildData g = p.guild();
        Optional<Contribution> contribution = store.memberContribution(g.guildId(), me, deadline);
        if (contribution.isEmpty()) {
            access.verifyMapping(me, g.guildId(), deadline);
            return new Answer<>(GetGuildShopResponse.newBuilder().setErrorMessage(GuildTip.NOT_A_MEMBER.proto()).build(),
                    EconomyResult.NOT_MEMBER);
        }
        Map<ShopUsageKey, Integer> usage = store.shopUsage(me, GameDay.dayKey(now), GameDay.weekKey(now), deadline);
        List<GuildAssetOpRow> pending = store.pendingOps(me, AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE,
                GuildLimits.ASSET_OP_MAX_PENDING, deadline);
        List<GuildAssetOpRow> recent = store.recentOps(me, AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE,
                GuildLimits.RECENT_RESULT_SCAN, deadline);

        // GUILD_CREDIT 流上还有活动奖励（4.6），这里只要兑换；兑换不按帮会过滤（物品属于玩家，离帮前买的照常到账）
        Predicate<GuildAssetOpRow> isShopOrder = row -> row.getKind() == GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP;
        GetGuildShopResponse.Builder resp = GetGuildShopResponse.newBuilder()
                .setContributionBalance(contribution.get().balance())
                .setNextDailyResetMs(GameDay.nextDailyResetMillis(now))
                .setNextWeeklyResetMs(GameDay.nextWeeklyResetMillis(now));
        for (GuildShopTable row : tables.sortedShopRows()) {
            OptionalInt maxStack = tables.itemMaxStack(row.getItemId());
            if (maxStack.isEmpty()) {
                // 读路径不因一行坏配表整页失败：这件商品显示为不可兑换（单次上限 0），同时留 ERROR
                log.error("[GuildEconomy] Item row {} of GuildShop[{}] missing, shown as not purchasable",
                        Integer.toUnsignedString(row.getItemId()), Integer.toUnsignedString(row.getId()));
            }
            int maxBuy = maxStack.isEmpty() ? 0 : EconomyTables.maxBuyCount(row, maxStack.getAsInt());
            resp.addGoods(EconomyViews.shopGoodsView(row, EconomyViews.shopUsedCount(row, usage, now), maxBuy, g.level()));
        }
        for (GuildAssetOpRow row : pending) {
            if (isShopOrder.test(row)) {
                resp.addPendingOrders(EconomyViews.shopOrderViewOf(row));
            }
        }
        for (GuildAssetOpRow row : EconomyViews.recentResults(recent, now, isShopOrder)) {
            resp.addRecentOrders(EconomyViews.shopOrderViewOf(row));
        }
        return new Answer<>(resp.build(), EconomyResult.OK);
    }

    // ================================================================ BuyGuildShopGoods（233）

    /**
     * 兑换（buyGuildShopGoods，economy_logic.go:870-997）。流程与捐献同形，区别：GUILD_CREDIT 流、永不中止（deadline 0，物品属于玩家，背包满就等）、
     * 事务内先扣帮贡再投递；scene 永久拒绝时由终结退帮贡与限购，回包余额改为直读值。份数超过 MaxBuyCount 在<b>发号之前</b>判；
     * {@code count > limit_count} 在事务前纯判断（发号之后，照搬 §9.1 第 4 条）。回包没有 GuildInfo。
     */
    public CompletableFuture<BuyGuildShopGoodsResponse> buyGuildShopGoods(long me, BuyGuildShopGoodsRequest request,
                                                                         Deadline deadline) {
        Answer<BuyGuildShopGoodsResponse> early;
        try {
            long start = clockMs.getAsLong();
            Prelude p = economyCaller(me, deadline);
            if (p.tip() != null) {
                early = new Answer<>(buyTip(p.tip()), EconomyResult.NOT_MEMBER);
            } else if (channel == null) {
                early = new Answer<>(buyTip(GuildTip.ASSET_CHANNEL_DISABLED), EconomyResult.DISABLED);
            } else {
                return buy(me, p.guild(), request.getGoodsId(), request.getCount(), start, deadline);
            }
        } catch (RuntimeException e) {
            metrics.economyRequest(EconomyRpc.BUY_SHOP_GOODS, EconomyResult.ERROR);
            throw e;
        }
        metrics.economyRequest(EconomyRpc.BUY_SHOP_GOODS, early.result());
        return CompletableFuture.completedFuture(early.response());
    }

    private CompletableFuture<BuyGuildShopGoodsResponse> buy(long me, GuildData g, int goodsId, int requestedCount, long start,
                                                            Deadline deadline) {
        GuildShopTable row = tables.shopGoods(goodsId);
        if (row == null) {
            return done(EconomyRpc.BUY_SHOP_GOODS, buyTip(GuildTip.SHOP_GOODS_NOT_FOUND), EconomyResult.NOT_FOUND);
        }
        // count = 0 是 proto 缺省值（老客户端不填份数），按 1 份处理
        int count = requestedCount == 0 ? 1 : requestedCount;
        OptionalInt maxStack = tables.itemMaxStack(row.getItemId());
        if (maxStack.isEmpty()) {
            // 启动校验保证商品引用的物品存在，运行期查不到 = 配表被错误替换，fail-closed
            throw new GuildFaultException("Item row " + Integer.toUnsignedString(row.getItemId()) + " of GuildShop["
                    + Integer.toUnsignedString(row.getId()) + "] missing");
        }
        if (Integer.compareUnsigned(count, EconomyTables.maxBuyCount(row, maxStack.getAsInt())) > 0) {
            return done(EconomyRpc.BUY_SHOP_GOODS, buyTip(GuildTip.COUNT_EXCEEDS_MAX_BUY), EconomyResult.LIMIT);
        }
        // 以下两条是预判，只为省一次发号与事务；事务内按 MySQL 锁行复核
        if (Integer.compareUnsigned(g.level(), row.getRequiredGuildLevel()) < 0) {
            return done(EconomyRpc.BUY_SHOP_GOODS, buyTip(GuildTip.GUILD_LEVEL_TOO_LOW), EconomyResult.LEVEL);
        }
        // cost_contribution ≤ 1e9 且 count ≤ 20（启动校验），乘积不会溢出
        long cost = row.getCostContribution() * Integer.toUnsignedLong(count);
        Answer<GuildTip> precheck = contributionPrecheck(g, me, cost, deadline);
        if (precheck != null) {
            return done(EconomyRpc.BUY_SHOP_GOODS, buyTip(precheck.response()), precheck.result());
        }
        OptionalInt periodKey = GameDay.periodKey(row.getLimitPeriod(), start);
        if (periodKey.isEmpty()) {
            throw new GuildFaultException("GuildShop[" + Integer.toUnsignedString(row.getId()) + "].limit_period="
                    + Integer.toUnsignedString(row.getLimitPeriod()) + " invalid");
        }
        // item_count × count ≤ max_stack_size（MaxBuyCount 的定义），装得进 uint32
        AssetBundle bundle = AssetBundle.newBuilder().addItems(AssetItem.newBuilder()
                .setConfigId(row.getItemId()).setCount(row.getItemCount() * count)).build();
        long token = AssetOpDecisions.leaseToken(tokens);
        long opId = mintOpId(me);
        if (opId == 0) {
            return done(EconomyRpc.BUY_SHOP_GOODS, buyTip(GuildTip.ASSET_OP_ID_UNAVAILABLE), EconomyResult.ID_UNAVAILABLE);
        }
        TxOutcome<ShopReserved> outcome = store.reserveShopOrder(new EconomyStore.ShopReserve(opId, me, g.guildId(), row.getId(),
                count, row.getRequiredGuildLevel(), cost, row.getLimitCount(), periodKey.getAsInt(), start + insertLeaseMs, token,
                start, bundle.toByteString(), access.txFence()), deadline);
        if (!(outcome instanceof TxOutcome.Ok<ShopReserved> ok)) {
            Answer<GuildTip> reply = rejectReply(me, g.guildId(), outcome.rejection(), deadline);
            return done(EconomyRpc.BUY_SHOP_GOODS, buyTip(reply.response()), reply.result());
        }
        ShopReserved reserved = ok.value();
        // 帮贡在成员快照里：提交后失效 guild(G)（economy_repo.go:751-752）
        access.invalidate(reserved.invalidation(), deadline);
        // 兑换永不中止（R7）：物品属于玩家，背包满就等腾出空间后自动到账
        AssetOp op = new AssetOp(opId, me, AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE, reserved.seq(), reserved.streamEpoch(),
                opId, TransactionType.TX_GUILD_SHOP_VALUE, bundle, 0, 0, token, 0);
        int finalCount = count;
        return onWorker(EconomyRpc.BUY_SHOP_GOODS, deliverNow(op, AssetKind.SHOP, deadline), () -> {
            OrderView view = settledView(opId, deadline);
            BuyGuildShopGoodsResponse.Builder resp = buyResponse(opId, row.getId(), finalCount, cost, start, view,
                    reserved.balanceAfter());
            switch (view.status()) {
                case GUILD_ASSET_ORDER_STATUS_REJECTED -> {
                    // 兑换被拒一律「已撤销」：scene 的拒绝原因对兑换没有可操作的区分
                    resp.setErrorMessage(GuildTip.ASSET_REJECTED.proto());
                    resp.setContributionBalance(refundedBalance(g.guildId(), me, reserved.balanceAfter(), deadline));
                }
                // 兑换不设截止，同步路径上理论上到不了 ABORTED；真出现也同样已退帮贡，余额照样直读
                case GUILD_ASSET_ORDER_STATUS_ABORTED ->
                        resp.setContributionBalance(refundedBalance(g.guildId(), me, reserved.balanceAfter(), deadline));
                default -> {
                }
            }
            return new Answer<>(resp.build(), EconomyViews.resultOfOrder(view.status()));
        }, () -> new Answer<>(buyResponse(opId, row.getId(), finalCount, cost, start,
                new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, 0), reserved.balanceAfter()).build(),
                EconomyResult.PENDING), deadline);
    }

    private static BuyGuildShopGoodsResponse.Builder buyResponse(long opId, int goodsId, int count, long cost, long start,
                                                                 OrderView view, long balance) {
        return BuyGuildShopGoodsResponse.newBuilder()
                .setOrder(GuildShopOrderView.newBuilder()
                        .setOpId(opId).setGoodsId(goodsId).setCount(count).setStatus(view.status())
                        .setCostContribution(cost).setReasonTipId(view.reason()).setCreatedMs(start))
                .setContributionBalance(balance);
    }

    private static BuyGuildShopGoodsResponse buyTip(GuildTip tip) {
        return BuyGuildShopGoodsResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    /**
     * 兑换前的帮贡预判（contributionPrecheck，economy_logic.go:999-1026）：快照说够就放行；不够时先直读 MySQL 确认再拒绝（快照偏低一个 TTL 时
     * 只按快照拒绝会让「页面显示够、点下去说不够」持续 30 分钟）。直读失败既不拒绝也不报故障：交事务按锁内余额裁决。
     *
     * @return null = 放行
     */
    private Answer<GuildTip> contributionPrecheck(GuildData g, long me, long cost, Deadline deadline) {
        GuildData.Member member = g.member(me);
        if (member != null && Long.compareUnsigned(member.contributionBalance(), cost) >= 0) {
            return null;
        }
        Optional<Contribution> contribution;
        try {
            contribution = store.memberContribution(g.guildId(), me, deadline);
        } catch (RuntimeException e) {
            log.error("[GuildEconomy] confirm contribution before buying failed, leaving it to the transaction (guild {}, player {}): {}",
                    Long.toUnsignedString(g.guildId()), Long.toUnsignedString(me), e.toString());
            return null;
        }
        if (contribution.isEmpty()) {
            // 快照说他在帮、MySQL 说不在：以 MySQL 为准，顺手纠正映射
            access.verifyMapping(me, g.guildId(), deadline);
            return new Answer<>(GuildTip.NOT_A_MEMBER, EconomyResult.NOT_MEMBER);
        }
        if (Long.compareUnsigned(contribution.get().balance(), cost) < 0) {
            return new Answer<>(GuildTip.CONTRIBUTION_INSUFFICIENT, EconomyResult.INSUFFICIENT);
        }
        return null;
    }

    /**
     * 兑换被拒 / 中止后终结已退回帮贡，余额改为直读（refundedBalance，economy_logic.go:1028-1041）：读失败回预留时的余额（偏低，纯展示）；
     * 成员行已不在（兑换期间离帮）回 0。
     */
    private long refundedBalance(long guildId, long me, long fallback, Deadline deadline) {
        try {
            return store.memberContribution(guildId, me, deadline).map(Contribution::balance).orElse(0L);
        } catch (RuntimeException e) {
            log.error("[GuildEconomy] read contribution after refund (guild {}, player {}): {}", Long.toUnsignedString(guildId),
                    Long.toUnsignedString(me), e.toString());
            return fallback;
        }
    }

    // ================================================================ 同步投递与回读

    /**
     * 预留事务提交之后同步投一次（deliverNow，economy_logic.go:438-456）：预算 = min(2500, 请求剩余 − 1000)，低于 300 ms 跳过（行在插行租约到期后由
     * 循环领走）。错误只打日志：结局以随后的回读为准。返回的 future 不异常完成。
     */
    private CompletableFuture<Void> deliverNow(AssetOp op, AssetKind kind, Deadline deadline) {
        long budget = Math.min(GuildLimits.ASSET_SYNC_BUDGET_MS,
                deadline.remainingMillis() - GuildLimits.ASSET_SYNC_TAIL_RESERVE_MS);
        if (budget < GuildLimits.ASSET_SYNC_MIN_BUDGET_MS) {
            metrics.syncSkipped(kind);
            log.info("[GuildEconomy] sync delivery skipped, budget {}ms too small, left to reconcile loop op_id={} kind={}",
                    budget, Long.toUnsignedString(op.opId()), kind.label());
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<AssetOpProcessor.Processed> processed;
        try {
            processed = channel.processOne(op, DeliveryOrigin.SYNC, budget);
        } catch (RuntimeException e) {
            processed = CompletableFuture.failedFuture(e);
        }
        return processed.handle((p, error) -> {
            if (error != null) {
                log.error("[GuildEconomy] sync delivery failed, outcome decided by readback op_id={} kind={}: {}",
                        Long.toUnsignedString(op.opId()), kind.label(), error.toString());
            }
            return null;
        });
    }

    /**
     * 同步投递之后回读指令状态给回包定视图（settledView，economy_logic.go:525-545）：读失败或行不存在都<b>按 PENDING 展示</b>并记 ERROR——
     * 行已提交、带着租约，最坏也会被循环接手；把一次回读失败报成 RPC 失败，玩家会以为没扣钱而再点一次。
     */
    private OrderView settledView(long opId, Deadline deadline) {
        Optional<OpState> state;
        try {
            state = store.opState(opId, deadline);
        } catch (RuntimeException e) {
            log.error("[GuildEconomy] read op state failed, showing pending op_id={}: {}", Long.toUnsignedString(opId), e.toString());
            return new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, 0);
        }
        if (state.isEmpty()) {
            // 刚提交的行不该消失（清理只删 30 天前的终态行）：出现就是 bug 或人工删库
            log.error("[GuildEconomy] op row vanished right after commit, showing pending op_id={}", Long.toUnsignedString(opId));
            return new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, 0);
        }
        OrderView view = EconomyViews.orderViewOf(state.get().status(), state.get().lastReason(), state.get().reasonTipId());
        if (view.status() == GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_UNSPECIFIED) {
            log.error("[GuildEconomy] op row has unknown status={} op_id={}", state.get().status(), Long.toUnsignedString(opId));
        }
        return view;
    }

    /**
     * 写提交之后经缓存重读装配 GuildInfo（freshGuildInfo，economy_logic.go:634-648）：读失败或本人已不在帮 → null，只记日志——写已经提交，
     * 回包里少一份 GuildInfo 只是让客户端自己再拉一次，不能把一次成功的写报成失败。
     */
    private GuildInfo freshGuildInfo(long guildId, long me, Deadline deadline) {
        try {
            Optional<GuildSnapshot> g = cache.guild(guildId, deadline);
            if (g.isEmpty() || !GuildCache.hasMember(g.get(), me)) {
                return null;
            }
            return views.guildInfo(GuildSnapshots.toData(g.get()), me, deadline);
        } catch (RuntimeException e) {
            log.error("[GuildEconomy] reload guild {} for response (player {}): {}", Long.toUnsignedString(guildId),
                    Long.toUnsignedString(me), e.toString());
            return null;
        }
    }

    /**
     * 同步投递结束后回到 guild-worker 做回读与装配；工作队列满了、或轮到时请求已过截止 → 不回读，按 {@code fallback}（PENDING）回包。
     * 回包最晚在请求截止时发出（到点仍没装配好就按 PENDING 回）：写已提交，不能让回包拖过 gate 的 5 s 被当成失败（同基线回读失败按
     * PENDING 展示）。返回的 future 不异常完成；只有第一次完成计指标。
     */
    private <T> CompletableFuture<T> onWorker(EconomyRpc rpc, CompletableFuture<Void> after, Supplier<Answer<T>> task,
                                              Supplier<Answer<T>> fallback, Deadline deadline) {
        CompletableFuture<T> out = new CompletableFuture<>();
        Answer<T> pending = fallback.get();
        CompletableFuture.delayedExecutor(Math.max(0, deadline.remainingMillis()), TimeUnit.MILLISECONDS).execute(() -> {
            if (out.complete(pending.response())) {
                log.warn("[GuildEconomy] {} not settled by the request deadline, answering pending", rpc.label());
                metrics.economyRequest(rpc, pending.result());
            }
        });
        after.whenComplete((ignored, error) -> {
            if (out.isDone()) {
                return;
            }
            try {
                workers.execute(() -> {
                    if (out.isDone()) {
                        return;
                    }
                    Answer<T> answer;
                    if (deadline.expired()) {
                        answer = pending;
                    } else {
                        try {
                            answer = task.get();
                        } catch (RuntimeException e) {
                            // 写已提交：回读 / 装配出错也不能报失败（同「回读失败按 PENDING」）
                            log.error("[GuildEconomy] {} readback after commit failed, answering pending: {}", rpc.label(),
                                    e.toString());
                            answer = pending;
                        }
                    }
                    if (out.complete(answer.response())) {
                        metrics.economyRequest(rpc, answer.result());
                    }
                });
            } catch (RejectedExecutionException e) {
                log.warn("[GuildEconomy] {} worker queue full after sync delivery, answering pending without readback", rpc.label());
                if (out.complete(pending.response())) {
                    metrics.economyRequest(rpc, pending.result());
                }
            }
        });
        return out;
    }

    /** 发 op_id（mintAssetOpID，economy_logic.go:382-401）：拿不到就整体失败，绝不用 0 或自造 id。返回 0 = 不可用（14008）。 */
    private long mintOpId(long me) {
        try {
            long id = opIds.getAsLong();
            if (id == 0) {
                log.error("[GuildEconomy] asset op id generator returned 0 (player={})", Long.toUnsignedString(me));
            }
            return id;
        } catch (RuntimeException e) {
            log.error("[GuildEconomy] asset op id generator refused to mint (player={}): {}", Long.toUnsignedString(me),
                    e.toString());
            return 0;
        }
    }

    // ================================================================ 计数

    private <T> T counted(EconomyRpc rpc, Supplier<Answer<T>> body) {
        Answer<T> answer;
        try {
            answer = body.get();
        } catch (RuntimeException e) {
            metrics.economyRequest(rpc, EconomyResult.ERROR);
            throw e;
        }
        metrics.economyRequest(rpc, answer.result());
        return answer.response();
    }

    private <T> CompletableFuture<T> done(EconomyRpc rpc, T response, EconomyResult result) {
        metrics.economyRequest(rpc, result);
        return CompletableFuture.completedFuture(response);
    }
}
