package com.game.scene.afkpass;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.proto.AfkPassInfo;
import com.game.proto.BuyAfkPassRequest;
import com.game.proto.BuyAfkPassResponse;
import com.game.proto.GetAfkPassResponse;
import com.game.proto.GmSetAfkPassRequest;
import com.game.proto.GmSetAfkPassResponse;
import com.game.scene.audit.AssetAudit;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.AfkPassErrorTip;
import com.game.table.CommonErrorTip;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 挂机月卡服务（{@code SceneAfkPassClientPlayer} 430–432；对齐 C++ {@code AfkPassSystem}）。
 *
 * <p>特性与 2026-11-22 收口约束：
 * <ul>
 *   <li>{@code BuyAfkPass} 与 {@code GmSetAfkPass} 共用全局可写门禁（冻结/交接/退场一律拒返 {@code kAfkPassFrozen(32003)}）；</li>
 *   <li>{@code BuyAfkPass} 按 {@code client_request_id} 去重（内存 64 条环缓冲 + 存档列 {@code afk_pass_last_req_id}）、
 *       报价校验（32000）、上限校验（32002）、经 {@link CurrencyService} 扣币（32001）并立即触发关键进度存盘；</li>
 *   <li>Redis 投影（{@code <ns>:player:afk_pass:<playerId>}）仅在确认写成功后才推进 {@code confirmedSeq}，
 *       失败的 SET 与 DEL 在加载、存盘提交及 60s 托管心跳中自动自愈，不留脏键。</li>
 * </ul>
 */
public final class AfkPassService {

    private static final Logger log = LoggerFactory.getLogger(AfkPassService.class);

    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    public static final int PRICE_CHANGED = AfkPassErrorTip.afk_pass_error.kAfkPassPriceChanged_VALUE;
    public static final int CURRENCY_NOT_ENOUGH = AfkPassErrorTip.afk_pass_error.kAfkPassCurrencyNotEnough_VALUE;
    public static final int STACK_LIMIT = AfkPassErrorTip.afk_pass_error.kAfkPassStackLimit_VALUE;
    public static final int FROZEN = AfkPassErrorTip.afk_pass_error.kAfkPassFrozen_VALUE;
    public static final int UNAVAILABLE = AfkPassErrorTip.afk_pass_error.kAfkPassUnavailable_VALUE;

    /** Redis 投影异步写接口（成功完成返回 true，失败或异常视为未确认，后续自愈重发）。 */
    public interface ProjectionSink {
        CompletionStage<Boolean> set(long playerId, long expiresAtSec, Duration ttl);
        CompletionStage<Boolean> delete(long playerId);

        ProjectionSink NOOP = new ProjectionSink() {
            @Override public CompletionStage<Boolean> set(long playerId, long expiresAtSec, Duration ttl) {
                return CompletableFuture.completedFuture(true);
            }
            @Override public CompletionStage<Boolean> delete(long playerId) {
                return CompletableFuture.completedFuture(true);
            }
        };
    }

    private final AfkPassTables tables;
    private final CurrencyService currency;
    private final SceneClock clock;
    private final ProjectionSink projection;
    private final Executor logicExecutor;
    private volatile java.util.function.LongFunction<ScenePlayer> playerLookup;

    public AfkPassService bindPlayerLookup(java.util.function.LongFunction<ScenePlayer> lookup) {
        this.playerLookup = Objects.requireNonNull(lookup, "lookup");
        return this;
    }

    public AfkPassService(AfkPassTables tables, CurrencyService currency, SceneClock clock) {
        this(tables, currency, clock, ProjectionSink.NOOP, Runnable::run);
    }

    public AfkPassService(AfkPassTables tables, CurrencyService currency, SceneClock clock,
                          ProjectionSink projection, Executor logicExecutor) {
        this.tables = Objects.requireNonNull(tables, "tables");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.projection = projection == null ? ProjectionSink.NOOP : projection;
        this.logicExecutor = logicExecutor == null ? Runnable::run : logicExecutor;
    }

    private long nowSeconds() {
        return Math.floorDiv(clock.epochMillis(), 1000L);
    }

    /** 430 GetAfkPass：只读查询月卡状态与当前报价。 */
    public GetAfkPassResponse getInfo(ScenePlayer player) {
        return GetAfkPassResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(buildInfo(player, nowSeconds()))
                .build();
    }

    /** 431 BuyAfkPass：幂等开通/续费（查账先于冻结/报价/上限/余额，同键换报价参数回 1005，账本损坏 fail-closed 回 32004）。 */
    public BuyAfkPassResponse buy(SceneWorld world, ScenePlayer player, BuyAfkPassRequest req) {
        long nowSec = nowSeconds();
        long reqId = req.getClientRequestId();
        if (reqId == 0) {
            return buyError(INVALID_PARAMETER);
        }
        var ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return buyError(UNAVAILABLE);
        }
        var fingerprint = com.game.scene.player.PlayerClientRequestLedger.fingerprint(
                req.toBuilder().clearClientRequestId().build());
        var check = ledger.check(reqId, com.game.scene.player.PlayerClientRequestLedger.KIND_BUY_AFK_PASS, fingerprint);
        if (check.verdict() == com.game.scene.player.PlayerClientRequestLedger.Verdict.REPLAY) {
            return BuyAfkPassResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setInfo(buildInfo(player, nowSec))
                    .setReplayed(true)
                    .build();
        }
        if (check.verdict() == com.game.scene.player.PlayerClientRequestLedger.Verdict.MISMATCH) {
            return buyError(INVALID_PARAMETER);
        }
        // 兼容未写入 client_request_ledger 的旧存档单键列
        if (player.hasAfkPassRequest(reqId)) {
            return BuyAfkPassResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setInfo(buildInfo(player, nowSec))
                    .setReplayed(true)
                    .build();
        }
        if (!isPlayerMutable(world, player)) {
            return buyError(FROZEN);
        }
        AfkPassTables.Rule rule = tables.rule();
        if (rule == null) {
            return buyError(UNAVAILABLE);
        }
        if (req.getExpectedCurrencyType() != rule.priceCurrencyType() || req.getExpectedPrice() != rule.price()) {
            return BuyAfkPassResponse.newBuilder()
                    .setErrorMessage(tip(PRICE_CHANGED))
                    .setInfo(buildInfo(player, nowSec))
                    .build();
        }
        long before = player.afkPassExpiresAt();
        OptionalLong afterOpt = AfkPassRules.nextExpiry(before, nowSec, rule.periodDays(), rule.maxRemainingDays());
        if (afterOpt.isEmpty()) {
            return buyError(STACK_LIMIT);
        }
        Wallet.Change deducted = currency.deduct(player, rule.priceCurrencyType(), rule.price(),
                AssetAudit.Reason.AFK_PASS_BUY, reqId);
        if (!deducted.ok()) {
            int tipId = deducted.tipId() == Wallet.FROZEN ? FROZEN : CURRENCY_NOT_ENOUGH;
            return buyError(tipId);
        }
        long after = afterOpt.getAsLong();
        player.setAfkPassExpiresAt(after);
        player.recordAfkPassRequest(reqId);
        ledger.remember(com.game.player.store.state.ClientRequestRecordState.newBuilder()
                .setKey(reqId)
                .setKind(com.game.scene.player.PlayerClientRequestLedger.KIND_BUY_AFK_PASS)
                .setFingerprint(fingerprint));
        world.requestKeyProgressSave(player);
        log.info("[AfkPass] metric=afk_pass_bought player_id={} expires_before={} expires_after={} price={} currency_type={} client_request_id={}",
                Long.toUnsignedString(player.playerId()), before, after, rule.price(), rule.priceCurrencyType(),
                Long.toUnsignedString(reqId));
        return BuyAfkPassResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(buildInfo(player, nowSec))
                .setReplayed(false)
                .build();
    }

    /** 432 GmSetAfkPass：dev/test 专用设定剩余时长（0 = 清除）。 */
    public GmSetAfkPassResponse gmSet(SceneWorld world, ScenePlayer player, GmSetAfkPassRequest req) {
        long remaining = Integer.toUnsignedLong(req.getRemainingSeconds());
        if (remaining > AfkPassRules.GM_MAX_REMAINING_SECONDS) {
            return GmSetAfkPassResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        if (!isPlayerMutable(world, player)) {
            return GmSetAfkPassResponse.newBuilder().setErrorMessage(tip(FROZEN)).build();
        }
        long nowSec = nowSeconds();
        long before = player.afkPassExpiresAt();
        long after = AfkPassRules.gmExpiry(nowSec, remaining);
        player.setAfkPassExpiresAt(after);
        world.requestKeyProgressSave(player);
        log.info("[AfkPass] metric=afk_pass_gm_set player_id={} expires_before={} expires_after={} remaining_seconds={}",
                Long.toUnsignedString(player.playerId()), before, after, remaining);
        return GmSetAfkPassResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(buildInfo(player, nowSec))
                .build();
    }

    /** 进场加载完成后按已提交存档值重建 Redis 投影（纠正残留或回档脏键）。 */
    public void onPlayerLoaded(ScenePlayer player) {
        long expiresAt = player.afkPassExpiresAt();
        long seq = player.initAfkPassProjection(expiresAt);
        sendProjection(player, expiresAt, seq, nowSeconds(), "loaded");
    }

    /** 在线存盘提交确认后同步 Redis 投影；若上次未确认，即使到期值未变也自动补发自愈。 */
    public void onSaveCommitted(ScenePlayer player, long committedExpiresAtSec) {
        if (!player.needsAfkPassProjectionSync(committedExpiresAtSec)) {
            return;
        }
        long seq = player.nextAfkPassProjectionSeq(committedExpiresAtSec);
        sendProjection(player, committedExpiresAtSec, seq, nowSeconds(), "committed");
    }

    /** 离线托管 60s 心跳周期刷新 Redis 投影（有效月卡补写防丢键；未确认的 DEL 补发清脏键）。 */
    public void refreshProjection(ScenePlayer player) {
        long nowSec = nowSeconds();
        long committed = player.committedAfkPassExpiresAt();
        boolean active = AfkPassRules.projectionIsSet(committed, nowSec);
        if (!active && !player.hasUnconfirmedAfkPassProjection()) {
            return;
        }
        long seq = player.nextAfkPassProjectionSeq(committed);
        sendProjection(player, committed, seq, nowSec, "hosted_refresh");
    }

    public AfkPassInfo buildInfo(ScenePlayer player, long nowSec) {
        long expiresAt = player.afkPassExpiresAt();
        boolean active = AfkPassRules.isActive(expiresAt, nowSec);
        AfkPassInfo.Builder b = AfkPassInfo.newBuilder()
                .setExpiresAt(expiresAt)
                .setServerNow(nowSec)
                .setUnlockedLoadoutSlots(AfkPassRules.unlockedLoadoutSlots(active))
                .setStage2TradableEnabled(active);
        AfkPassTables.Rule rule = tables.rule();
        if (rule != null) {
            b.setPriceCurrencyType(rule.priceCurrencyType())
                    .setPrice(rule.price())
                    .setPeriodDays(rule.periodDays())
                    .setMaxRemainingDays(rule.maxRemainingDays())
                    .setRemindBeforeHours(rule.remindBeforeHours())
                    .setBalance(player.wallet().balance(rule.priceCurrencyType()));
        }
        return b.build();
    }

    private void sendProjection(ScenePlayer player, long expiresAtSec, long seq, long nowSec, String reason) {
        long playerId = player.playerId();
        long ownerEpoch = player.ownerEpoch();
        long instanceId = player.instanceId();
        java.util.function.LongFunction<ScenePlayer> lookup = this.playerLookup;
        // 仅在独立单测未装配 SceneWorld 时保留当前实例兜底；生产路径只捕获不可变身份值，禁止跨线程传播可变 ScenePlayer。
        ScenePlayer unboundFallback = lookup == null ? player : null;
        boolean set = AfkPassRules.projectionIsSet(expiresAtSec, nowSec);
        CompletionStage<Boolean> future;
        try {
            if (set) {
                long ttlSec = AfkPassRules.projectionTtlSeconds(expiresAtSec, nowSec);
                future = projection.set(playerId, expiresAtSec, Duration.ofSeconds(Math.max(1L, ttlSec)));
            } else {
                future = projection.delete(playerId);
            }
        } catch (RuntimeException e) {
            log.warn("[AfkPass] projection sync threw player_id={} op={} reason={}",
                    Long.toUnsignedString(playerId), set ? "set" : "del", reason, e);
            return;
        }
        future.whenComplete((ok, err) -> {
            if (err != null || !Boolean.TRUE.equals(ok)) {
                log.warn("[AfkPass] projection sync failed player_id={} op={} seq={} reason={}",
                        Long.toUnsignedString(playerId), set ? "set" : "del", seq, reason, err);
                return;
            }
            try {
                logicExecutor.execute(() -> {
                    ScenePlayer current = lookup != null ? lookup.apply(playerId) : unboundFallback;
                    if (current == null || current.instanceId() != instanceId || current.ownerEpoch() != ownerEpoch) {
                        log.debug("[AfkPass] stale projection confirmation dropped player_id={} epoch={} seq={}",
                                Long.toUnsignedString(playerId), ownerEpoch, seq);
                        return;
                    }
                    current.confirmAfkPassProjection(seq);
                });
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                log.debug("[AfkPass] projection confirmation dropped during shutdown player_id={} seq={}",
                        Long.toUnsignedString(playerId), seq);
            }
        });
    }

    private static boolean isPlayerMutable(SceneWorld world, ScenePlayer player) {
        return player != null && !player.frozen() && !world.switchInFlight(player) && player.ownerEpoch() > 0;
    }

    private static BuyAfkPassResponse buyError(int tipId) {
        return BuyAfkPassResponse.newBuilder().setErrorMessage(tip(tipId)).build();
    }
}
