package com.game.scene.rewardedad;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.player.store.state.ClientRequestRecordState;
import com.game.player.store.state.RewardedAdLedgerState;
import com.game.proto.BeginRewardedAdSessionRequest;
import com.game.proto.BeginRewardedAdSessionResponse;
import com.game.proto.ClaimRewardedAdRequest;
import com.game.proto.ClaimRewardedAdResponse;
import com.game.proto.GetRewardedAdInfoRequest;
import com.game.proto.GetRewardedAdInfoResponse;
import com.game.proto.RewardedAdSlotView;
import com.game.scene.audit.AssetAudit;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerClientRequestLedger.Verdict;
import com.game.scene.player.PlayerLevels;
import com.game.scene.player.Wallet;
import com.game.scene.rewardedad.RewardedAdRules.SessionVerdict;
import com.game.scene.rewardedad.RewardedAdRules.SlotConfig;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.CommonErrorTip;
import com.game.table.RewardedAdErrorTip;
import com.google.protobuf.ByteString;
import java.security.SecureRandom;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 零拒审激励视频广告场景侧服务（{@code SceneRewardedAdClientPlayer}；
 * 对齐 C++ {@code PlayerRewardedAdSystem} 与 {@code docs/design/rewarded-ad-system.md}）。
 */
public final class RewardedAdService {

    private static final Logger log = LoggerFactory.getLogger(RewardedAdService.class);

    public static final int OK = 0;
    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    public static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    public static final int UNAVAILABLE = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdUnavailable_VALUE;
    public static final int DISABLED = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdDisabled_VALUE;
    public static final int PLATFORM_UNSUPPORTED = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdPlatformUnsupported_VALUE;
    public static final int LEVEL_NOT_ENOUGH = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdLevelNotEnough_VALUE;
    public static final int DAILY_LIMIT_REACHED = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdDailyLimitReached_VALUE;
    public static final int COOLDOWN = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdCooldown_VALUE;
    public static final int SESSION_INVALID = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdSessionInvalid_VALUE;
    public static final int SESSION_EXPIRED = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdSessionExpired_VALUE;
    public static final int WATCH_TOO_SHORT = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdWatchTooShort_VALUE;
    public static final int CURRENCY_BLOCKED = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdCurrencyBlocked_VALUE;
    public static final int IN_BATTLE = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdInBattle_VALUE;
    public static final int FROZEN = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdFrozen_VALUE;
    public static final int SSV_REQUIRED = RewardedAdErrorTip.rewarded_ad_error.kRewardedAdSsvRequired_VALUE;

    private final RewardedAdTables tables;
    private final CurrencyService currency;
    private final SceneClock clock;
    private final SceneMetrics metrics;
    private final SecureRandom random = new SecureRandom();

    public RewardedAdService(RewardedAdTables tables, CurrencyService currency, SceneClock clock, SceneMetrics metrics) {
        this.tables = Objects.requireNonNull(tables, "tables");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public long nowMillis() {
        return clock.epochMillis();
    }

    public GetRewardedAdInfoResponse getInfo(ScenePlayer player, GetRewardedAdInfoRequest req, long nowMs) {
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return GetRewardedAdInfoResponse.newBuilder().setErrorMessage(tip(UNAVAILABLE)).build();
        }
        long[] day = RewardedAdRules.resolveGameDay(nowMs);
        if (day == null) {
            return GetRewardedAdInfoResponse.newBuilder().setErrorMessage(tip(UNAVAILABLE)).build();
        }
        int playerLevel = PlayerLevels.effective(player.level());
        GetRewardedAdInfoResponse.Builder out = GetRewardedAdInfoResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setServerTimeMs(nowMs)
                .setBindGoldBalance(player.wallet().balance(Wallet.BOUND_GOLD))
                .setGoldBalance(player.wallet().balance(Wallet.GOLD));
        RewardedAdLedgerState adLedger = ledger.rewardedAd();
        for (SlotConfig cfg : tables.slots()) {
            out.addSlots(buildSlotView(cfg, adLedger, req.getClientPlatform(), playerLevel, day[0], day[1], nowMs));
        }
        return out.build();
    }

    public BeginRewardedAdSessionResponse beginSession(SceneWorld world, ScenePlayer player,
                                                       BeginRewardedAdSessionRequest req, long nowMs) {
        if (player.inBattle()) {
            metrics.battleGateReject(SceneMetrics.BattleGate.REWARDED_AD);
            return rejectBegin(IN_BATTLE);
        }
        if (player.frozen()) {
            return rejectBegin(FROZEN);
        }
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return rejectBegin(UNAVAILABLE);
        }
        if (!tables.allRowsValid()) {
            return rejectBegin(UNAVAILABLE);
        }
        SlotConfig cfg = tables.slot(req.getSlotId());
        if (cfg == null) {
            log.warn("[RewardedAd] metric=rewarded_ad_session_rejected reason=invalid_slot player_id={} slot_id={}",
                    Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(req.getSlotId()));
            return rejectBegin(UNAVAILABLE);
        }
        if (!cfg.enabled()) {
            return rejectBegin(DISABLED);
        }
        if (!RewardedAdRules.isPlatformAllowed(req.getClientPlatform(), cfg.platformMask())) {
            return rejectBegin(PLATFORM_UNSUPPORTED);
        }
        int playerLevel = PlayerLevels.effective(player.level());
        if (Integer.compareUnsigned(playerLevel, cfg.minLevel()) < 0) {
            return rejectBegin(LEVEL_NOT_ENOUGH);
        }
        long[] day = RewardedAdRules.resolveGameDay(nowMs);
        if (day == null) {
            return rejectBegin(UNAVAILABLE);
        }
        RewardedAdLedgerState currentAd = ledger.rewardedAd();
        if (Integer.compareUnsigned(RewardedAdRules.effectiveClaimedToday(currentAd, day[0]), cfg.dailyLimit()) >= 0) {
            return rejectBegin(DAILY_LIMIT_REACHED);
        }
        long cooldownUntil = RewardedAdRules.cooldownUntilMs(currentAd, cfg.cooldownSec());
        if (cooldownUntil > 0L && Long.compareUnsigned(nowMs, cooldownUntil) < 0) {
            return rejectBegin(COOLDOWN);
        }
        int addCheck = currency.checkAdd(player, Wallet.BOUND_GOLD, cfg.rewardAmount(), nowMs / 1000L);
        if (addCheck == Wallet.BLOCKED) {
            return rejectBegin(CURRENCY_BLOCKED);
        }
        if (addCheck == Wallet.FROZEN) {
            return rejectBegin(FROZEN);
        }
        if (addCheck != 0) {
            return rejectBegin(UNAVAILABLE);
        }

        long sessionId = generateSessionId(currentAd.getActiveSessionId());
        RewardedAdLedgerState.Builder nextAd = currentAd.toBuilder();
        RewardedAdRules.advanceDayIfNeeded(nextAd, day[0]);
        nextAd.setActiveSessionId(sessionId)
                .setActiveSessionIssuedMs(nowMs)
                .setActiveSessionSlotId(cfg.slotId());
        ledger.setRewardedAd(nextAd.build());

        if (world != null) {
            world.requestKeyProgressSave(player);
        }

        long ttlMs = Integer.toUnsignedLong(cfg.sessionTtlSec()) * 1000L;
        long expiresAt = nowMs > Long.MAX_VALUE - ttlMs ? Long.MAX_VALUE : (nowMs + ttlMs);
        log.info("[RewardedAd] metric=rewarded_ad_session_issued player_id={} slot_id={} platform={} ad_session_id={}",
                Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(cfg.slotId()),
                Integer.toUnsignedString(req.getClientPlatform()), Long.toUnsignedString(sessionId));
        return BeginRewardedAdSessionResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setAdSessionId(sessionId)
                .setExpiresAtMs(expiresAt)
                .build();
    }

    public ClaimRewardedAdResponse claim(SceneWorld world, ScenePlayer player,
                                         ClaimRewardedAdRequest req, long nowMs) {
        long clientRequestId = req.getClientRequestId();
        if (clientRequestId == 0L || !req.getUnknownFields().asMap().isEmpty()) {
            return rejectClaim(INVALID_PARAMETER);
        }

        // 1. 幂等查重必须排在一切状态校验之前
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return rejectClaim(SERVICE_UNAVAILABLE);
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(req.toBuilder().clearClientRequestId().build());
        var check = ledger.check(clientRequestId, PlayerClientRequestLedger.KIND_CLAIM_REWARDED_AD, fingerprint);
        if (check.verdict() == Verdict.MISMATCH) {
            return rejectClaim(INVALID_PARAMETER);
        }
        if (check.verdict() == Verdict.REPLAY) {
            ClientRequestRecordState rec = check.record();
            ClaimRewardedAdResponse.Builder out = ClaimRewardedAdResponse.newBuilder()
                    .setErrorMessage(tip(OK))
                    .setCurrencyType(rec.getRewardedAdCurrencyType())
                    .setRewardAmount(rec.getRewardedAdAmount())
                    .setCurrencyBalance(player.wallet().balance(Wallet.BOUND_GOLD))
                    .setGoldBalance(player.wallet().balance(Wallet.GOLD))
                    .setReplayed(true);
            SlotConfig cfg = tables.slot(req.getSlotId());
            long[] day = RewardedAdRules.resolveGameDay(nowMs);
            if (cfg != null && day != null) {
                out.setSlot(buildSlotView(cfg, ledger.rewardedAd(), 0,
                        PlayerLevels.effective(player.level()), day[0], day[1], nowMs));
            }
            return out.build();
        }

        // 2. 运行态校验
        if (player.inBattle()) {
            metrics.battleGateReject(SceneMetrics.BattleGate.REWARDED_AD);
            return rejectClaim(IN_BATTLE);
        }
        if (player.frozen()) {
            return rejectClaim(FROZEN);
        }

        // 3. 配表、等级、日限、冷却校验
        if (!tables.allRowsValid()) {
            return rejectClaim(UNAVAILABLE);
        }
        SlotConfig cfg = tables.slot(req.getSlotId());
        if (cfg == null) {
            return rejectClaim(UNAVAILABLE);
        }
        if (!cfg.enabled()) {
            return rejectClaim(DISABLED);
        }
        int playerLevel = PlayerLevels.effective(player.level());
        if (Integer.compareUnsigned(playerLevel, cfg.minLevel()) < 0) {
            return rejectClaim(LEVEL_NOT_ENOUGH);
        }
        long[] day = RewardedAdRules.resolveGameDay(nowMs);
        if (day == null) {
            return rejectClaim(UNAVAILABLE);
        }
        RewardedAdLedgerState currentAd = ledger.rewardedAd();
        if (Integer.compareUnsigned(RewardedAdRules.effectiveClaimedToday(currentAd, day[0]), cfg.dailyLimit()) >= 0) {
            return rejectClaim(DAILY_LIMIT_REACHED);
        }
        long cooldownUntil = RewardedAdRules.cooldownUntilMs(currentAd, cfg.cooldownSec());
        if (cooldownUntil > 0L && Long.compareUnsigned(nowMs, cooldownUntil) < 0) {
            return rejectClaim(COOLDOWN);
        }

        // 4. 一次性会话、最短观看时长、TTL 与可选 SSV 校验
        SessionVerdict verdict = RewardedAdRules.verifyActiveSession(
                currentAd, cfg.slotId(), req.getAdSessionId(), nowMs, cfg.minWatchSec(), cfg.sessionTtlSec());
        if (verdict == SessionVerdict.INVALID) {
            log.warn("[RewardedAd] metric=rewarded_ad_claim_rejected reason=session_invalid player_id={} slot_id={} ad_session_id={}",
                    Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(cfg.slotId()),
                    Long.toUnsignedString(req.getAdSessionId()));
            return rejectClaim(SESSION_INVALID);
        }
        if (verdict == SessionVerdict.EXPIRED) {
            log.warn("[RewardedAd] metric=rewarded_ad_claim_rejected reason=session_expired player_id={} slot_id={} ad_session_id={}",
                    Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(cfg.slotId()),
                    Long.toUnsignedString(req.getAdSessionId()));
            return rejectClaim(SESSION_EXPIRED);
        }
        if (verdict == SessionVerdict.TOO_SHORT) {
            log.warn("[RewardedAd] metric=rewarded_ad_claim_rejected reason=too_fast player_id={} slot_id={} ad_session_id={}",
                    Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(cfg.slotId()),
                    Long.toUnsignedString(req.getAdSessionId()));
            return rejectClaim(WATCH_TOO_SHORT);
        }
        if (cfg.requireSsv() && req.getSsvProof().isEmpty()) {
            return rejectClaim(SSV_REQUIRED);
        }

        // 5. 产出封禁与溢出预检
        long nowSec = nowMs / 1000L;
        int addCheck = currency.checkAdd(player, Wallet.BOUND_GOLD, cfg.rewardAmount(), nowSec);
        if (addCheck == Wallet.BLOCKED) {
            return rejectClaim(CURRENCY_BLOCKED);
        }
        if (addCheck == Wallet.FROZEN) {
            return rejectClaim(FROZEN);
        }
        if (addCheck != 0) {
            return rejectClaim(UNAVAILABLE);
        }

        // 6. 原子入账 + 推进日账本 + 写入幂等记录 + 关键进度存盘
        Wallet.Change added = currency.add(player, Wallet.BOUND_GOLD, cfg.rewardAmount(),
                AssetAudit.Reason.SYSTEM_GRANT, req.getAdSessionId(), nowSec);
        if (!added.ok()) {
            if (added.tipId() == Wallet.BLOCKED) {
                return rejectClaim(CURRENCY_BLOCKED);
            }
            if (added.tipId() == Wallet.FROZEN) {
                return rejectClaim(FROZEN);
            }
            log.error("[RewardedAd] currency add failed after precheck player_id={} slot_id={} amount={} tip={}",
                    Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(cfg.slotId()),
                    Long.toUnsignedString(cfg.rewardAmount()), added.tipId());
            return rejectClaim(UNAVAILABLE);
        }

        RewardedAdLedgerState.Builder nextAd = currentAd.toBuilder();
        RewardedAdRules.advanceDayIfNeeded(nextAd, day[0]);
        nextAd.setClaimedToday(nextAd.getClaimedToday() + 1)
                .setLastClaimAtMs(nowMs)
                .setActiveSessionId(0L)
                .setActiveSessionIssuedMs(0L)
                .setActiveSessionSlotId(0);
        if (nextAd.getTotalClaimedLifetime() != Long.MAX_VALUE) {
            nextAd.setTotalClaimedLifetime(nextAd.getTotalClaimedLifetime() + 1L);
        }
        RewardedAdLedgerState committedAd = nextAd.build();
        ledger.setRewardedAd(committedAd);
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(clientRequestId)
                .setKind(PlayerClientRequestLedger.KIND_CLAIM_REWARDED_AD)
                .setFingerprint(fingerprint)
                .setRewardedAdCurrencyType(Wallet.BOUND_GOLD)
                .setRewardedAdAmount(cfg.rewardAmount()));

        if (world != null) {
            world.requestKeyProgressSave(player);
        }

        log.info("[RewardedAd] metric=rewarded_ad_claimed player_id={} slot_id={} currency_type={} reward_amount={} claimed_today={} client_request_id={}",
                Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(cfg.slotId()),
                Wallet.BOUND_GOLD, Long.toUnsignedString(cfg.rewardAmount()),
                committedAd.getClaimedToday(), Long.toUnsignedString(clientRequestId));
        return ClaimRewardedAdResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setCurrencyType(Wallet.BOUND_GOLD)
                .setRewardAmount(cfg.rewardAmount())
                .setCurrencyBalance(player.wallet().balance(Wallet.BOUND_GOLD))
                .setGoldBalance(player.wallet().balance(Wallet.GOLD))
                .setSlot(buildSlotView(cfg, committedAd, 0, playerLevel, day[0], day[1], nowMs))
                .setReplayed(false)
                .build();
    }

    private long generateSessionId(long avoidId) {
        for (int attempt = 0; attempt < 8; attempt++) {
            long candidate = random.nextLong() & Long.MAX_VALUE;
            if (candidate != 0L && candidate != avoidId) {
                return candidate;
            }
        }
        return avoidId == 1L ? 2L : 1L;
    }

    private static RewardedAdSlotView buildSlotView(SlotConfig cfg, RewardedAdLedgerState adLedger,
                                                    int clientPlatform, int playerLevel,
                                                    long dayStartMs, long nextDayMs, long nowMs) {
        boolean platformOk = clientPlatform == 0
                || RewardedAdRules.isPlatformAllowed(clientPlatform, cfg.platformMask());
        long cooldownUntil = RewardedAdRules.cooldownUntilMs(adLedger, cfg.cooldownSec());
        long activeCooldown = Long.compareUnsigned(cooldownUntil, nowMs) > 0 ? cooldownUntil : 0L;
        return RewardedAdSlotView.newBuilder()
                .setSlotId(cfg.slotId())
                .setEnabled(cfg.enabled() && platformOk && Integer.compareUnsigned(playerLevel, cfg.minLevel()) >= 0)
                .setMinLevel(cfg.minLevel())
                .setDailyLimit(cfg.dailyLimit())
                .setClaimedToday(RewardedAdRules.effectiveClaimedToday(adLedger, dayStartMs))
                .setNextResetMs(nextDayMs)
                .setCooldownUntilMs(activeCooldown)
                .setCurrencyType(cfg.currencyType())
                .setRewardAmount(cfg.rewardAmount())
                .build();
    }

    private static BeginRewardedAdSessionResponse rejectBegin(int tipId) {
        return BeginRewardedAdSessionResponse.newBuilder().setErrorMessage(tip(tipId)).build();
    }

    private static ClaimRewardedAdResponse rejectClaim(int tipId) {
        return ClaimRewardedAdResponse.newBuilder().setErrorMessage(tip(tipId)).build();
    }
}
