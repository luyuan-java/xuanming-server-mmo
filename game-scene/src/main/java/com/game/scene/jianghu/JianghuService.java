package com.game.scene.jianghu;

import com.game.proto.BattleSettlementData;
import com.game.proto.CheckJianghuDuelEligibilityRequest;
import com.game.proto.CheckJianghuDuelEligibilityResponse;
import com.game.proto.ClearJianghuDefeatMarkRequest;
import com.game.proto.ClearJianghuDefeatMarkResponse;
import com.game.proto.GetJianghuProfileRequest;
import com.game.proto.GetJianghuProfileResponse;
import com.game.proto.JianghuClearMarkMode;
import com.game.proto.JianghuDuelBattleContext;
import com.game.proto.JianghuDuelPrepareFee;
import com.game.proto.JianghuPlayerEligibilityEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.game.proto.JianghuProfileView;
import com.game.proto.PlayerJianghuComp;
import com.game.proto.SetJianghuRefuseStrangersRequest;
import com.game.proto.SetJianghuRefuseStrangersResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.eBattleOutcome;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.BagType;
import com.game.scene.player.Wallet;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import com.game.table.JianghuErrorTip;
import com.game.table.JianghuRuleTable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 江湖恩怨与天墉生死状一期 scene 侧领域服务（Java 双版对账，镜像 C++ {@code player_jianghu.{h,cpp}}）。
 *
 * <p>核心红线：
 * <ul>
 *   <li>普通战书(500 银两)与普通洗尘(1000 银两)严格只扣可交易银两 {@link Wallet#GOLD}（绝不扣绑定银两防退费洗白）；</li>
 *   <li>天墉生死状结算零经验、零银两、零 660 装备材料、零赛季/试炼/游历外溢，仅按 {@code owner_epoch} 围栏维护 30 分钟战败社交印记；</li>
 *   <li>{@link #clearDefeatMark} 严格按 {@code client_request_id} 幂等去重；{@link #checkEligibility} 仅做只读预检不扣费。</li>
 * </ul>
 */
public final class JianghuService {

    private static final Logger log = LoggerFactory.getLogger(JianghuService.class);

    public static final int DEFAULT_GOLD_WRIT_ITEM_ID = 3415;
    public static final int DEFAULT_CLEAR_ITEM_ID = 3416;
    public static final long DEFAULT_SILVER_WRIT_COST = 500L;
    public static final long DEFAULT_SILVER_CLEAR_COST = 1000L;
    public static final long DEFAULT_DEFEAT_MARK_DURATION_MS = 1800_000L;
    public static final long DEFAULT_CLEAR_BADGE_DURATION_MS = 1800_000L;

    private final CurrencyService currency;
    private final BagService bags;
    private final Supplier<JianghuRuleTable> ruleSupplier;
    private final LongSupplier clockMs;
    private final Map<Long, PlayerJianghuComp> statesByPlayer = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> clearRequestIdsByPlayer = new ConcurrentHashMap<>();

    public JianghuService(CurrencyService currency, BagService bags,
                          Supplier<JianghuRuleTable> ruleSupplier, LongSupplier clockMs) {
        this.currency = Objects.requireNonNull(currency, "currency");
        this.bags = Objects.requireNonNull(bags, "bags");
        this.ruleSupplier = ruleSupplier != null ? ruleSupplier : () -> null;
        this.clockMs = clockMs != null ? clockMs : System::currentTimeMillis;
    }

    public PlayerJianghuComp stateOf(long playerId) {
        return statesByPlayer.getOrDefault(playerId, PlayerJianghuComp.getDefaultInstance());
    }

    private boolean isEnabled() {
        JianghuRuleTable row = ruleSupplier.get();
        return row == null || row.getEnabled() != 0;
    }

    private long silverWritCost() {
        JianghuRuleTable row = ruleSupplier.get();
        return row != null && row.getSilverWritCost() > 0 ? row.getSilverWritCost() : DEFAULT_SILVER_WRIT_COST;
    }

    private int goldWritItemId() {
        JianghuRuleTable row = ruleSupplier.get();
        return row != null && row.getGoldWritItemId() > 0 ? row.getGoldWritItemId() : DEFAULT_GOLD_WRIT_ITEM_ID;
    }

    private long silverClearCost() {
        JianghuRuleTable row = ruleSupplier.get();
        return row != null && row.getSilverClearCost() > 0 ? row.getSilverClearCost() : DEFAULT_SILVER_CLEAR_COST;
    }

    private int clearItemId() {
        JianghuRuleTable row = ruleSupplier.get();
        return row != null && row.getClearItemId() > 0 ? row.getClearItemId() : DEFAULT_CLEAR_ITEM_ID;
    }

    private long defeatMarkDurationMs() {
        JianghuRuleTable row = ruleSupplier.get();
        return row != null && row.getDefeatMarkDurationSec() > 0
                ? row.getDefeatMarkDurationSec() * 1000L : DEFAULT_DEFEAT_MARK_DURATION_MS;
    }

    private long clearBadgeDurationMs() {
        JianghuRuleTable row = ruleSupplier.get();
        return row != null && row.getClearBadgeDurationSec() > 0
                ? row.getClearBadgeDurationSec() * 1000L : DEFAULT_CLEAR_BADGE_DURATION_MS;
    }

    private JianghuProfileView buildProfileView(ScenePlayer player, PlayerJianghuComp comp, long nowMs) {
        boolean activeMark = comp.getDefeatMarkExpiresAtMs() > nowMs;
        boolean activeBadge = comp.getClearBadgeExpiresAtMs() > nowMs;
        return JianghuProfileView.newBuilder()
                .setServerTimeMs(nowMs)
                .setDuelRefuseStrangers(comp.getDuelRefuseStrangers())
                .setHasActiveDefeatMark(activeMark)
                .setDefeatMarkExpiresAtMs(activeMark ? comp.getDefeatMarkExpiresAtMs() : 0L)
                .setDefeatMarkWinnerId(activeMark ? comp.getDefeatMarkWinnerId() : 0L)
                .setDefeatMarkWinnerName(activeMark ? comp.getDefeatMarkWinnerName() : "")
                .setDefeatMarkStyleId(activeMark ? comp.getDefeatMarkStyleId() : 0)
                .setDefeatMarkIsGuildName(activeMark && comp.getDefeatMarkIsGuildName())
                .setDefeatMarkBattleId(activeMark ? comp.getDefeatMarkBattleId() : 0L)
                .setHasActiveClearBadge(activeBadge)
                .setClearBadgeExpiresAtMs(activeBadge ? comp.getClearBadgeExpiresAtMs() : 0L)
                .setClearBadgeStyleId(activeBadge ? comp.getClearBadgeStyleId() : 0)
                .setSilverBalance(player.wallet().balance(Wallet.GOLD))
                .setGoldWritItemCount(countItem(player, goldWritItemId()))
                .setClearCharmItemCount(countItem(player, clearItemId()))
                .setSilverWritCost(silverWritCost())
                .setSilverClearCost(silverClearCost())
                .build();
    }

    public GetJianghuProfileResponse getProfile(ScenePlayer player, GetJianghuProfileRequest ignored) {
        long nowMs = clockMs.getAsLong();
        PlayerJianghuComp comp = normalizeExpired(player, stateOf(player.playerId()), nowMs);
        statesByPlayer.put(player.playerId(), comp);
        return GetJianghuProfileResponse.newBuilder()
                .setProfile(buildProfileView(player, comp, nowMs))
                .build();
    }

    public SetJianghuRefuseStrangersResponse setRefuseStrangers(ScenePlayer player, SetJianghuRefuseStrangersRequest req) {
        if (!isEnabled()) {
            return SetJianghuRefuseStrangersResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuDuelDisabled_VALUE)).build();
        }
        long nowMs = clockMs.getAsLong();
        PlayerJianghuComp next = normalizeExpired(player, stateOf(player.playerId()), nowMs).toBuilder()
                .setDuelRefuseStrangers(req.getRefuseStrangers())
                .build();
        statesByPlayer.put(player.playerId(), next);
        return SetJianghuRefuseStrangersResponse.newBuilder()
                .setProfile(buildProfileView(player, next, nowMs))
                .build();
    }

    public ClearJianghuDefeatMarkResponse clearDefeatMark(ScenePlayer player, ClearJianghuDefeatMarkRequest req) {
        if (!isEnabled()) {
            return ClearJianghuDefeatMarkResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuDuelDisabled_VALUE)).build();
        }
        long reqId = req.getClientRequestId();
        if (reqId == 0L || (req.getMode() != JianghuClearMarkMode.JIANGHU_CLEAR_MARK_MODE_SILVER
                && req.getMode() != JianghuClearMarkMode.JIANGHU_CLEAR_MARK_MODE_ITEM_CHARM)) {
            return ClearJianghuDefeatMarkResponse.newBuilder()
                    .setErrorMessage(tip(CommonErrorTip.common_error.kInvalidParameter_VALUE)).build();
        }
        long nowMs = clockMs.getAsLong();
        Set<Long> ledger = clearRequestIdsByPlayer.computeIfAbsent(player.playerId(), k -> ConcurrentHashMap.newKeySet());
        if (ledger.contains(reqId)) {
            PlayerJianghuComp cur = normalizeExpired(player, stateOf(player.playerId()), nowMs);
            return ClearJianghuDefeatMarkResponse.newBuilder()
                    .setProfile(buildProfileView(player, cur, nowMs))
                    .setReplayed(true)
                    .build();
        }
        PlayerJianghuComp cur = normalizeExpired(player, stateOf(player.playerId()), nowMs);
        if (cur.getDefeatMarkExpiresAtMs() <= nowMs) {
            return ClearJianghuDefeatMarkResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuNoActiveDefeatMark_VALUE)).build();
        }

        PlayerJianghuComp.Builder updated = cur.toBuilder()
                .setDefeatMarkWinnerId(0L)
                .setDefeatMarkWinnerName("")
                .setDefeatMarkExpiresAtMs(0L)
                .setDefeatMarkStyleId(0)
                .setDefeatMarkIsGuildName(false)
                .setDefeatMarkBattleId(0L);

        if (req.getMode() == JianghuClearMarkMode.JIANGHU_CLEAR_MARK_MODE_ITEM_CHARM) {
            int itemId = clearItemId();
            if (countItem(player, itemId) < 1) {
                return ClearJianghuDefeatMarkResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE)).build();
            }
            var prep = bags.prepareSelfConsume(player, Map.of(itemId, 1L));
            if (prep.tip() != 0) {
                return ClearJianghuDefeatMarkResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE)).build();
            }
            prep.commit(AssetAudit.Reason.JIANGHU_CLEAR_MARK, reqId);
            updated.setClearBadgeExpiresAtMs(nowMs + clearBadgeDurationMs())
                    .setClearBadgeStyleId(1);
        } else {
            long cost = silverClearCost();
            if (player.wallet().balance(Wallet.GOLD) < cost) {
                return ClearJianghuDefeatMarkResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE)).build();
            }
            var deduct = currency.deduct(player, Wallet.GOLD, cost, AssetAudit.Reason.JIANGHU_CLEAR_MARK, reqId);
            if (!deduct.ok()) {
                return ClearJianghuDefeatMarkResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE)).build();
            }
        }
        PlayerJianghuComp saved = updated.build();
        statesByPlayer.put(player.playerId(), saved);
        ledger.add(reqId);
        return ClearJianghuDefeatMarkResponse.newBuilder()
                .setProfile(buildProfileView(player, saved, nowMs))
                .setReplayed(false)
                .build();
    }

    /** 只读资格与资费预检（供 match 在发邀请前核验闭关免战开关与发起方银两/金帖余额，绝不扣费）。 */
    public CheckJianghuDuelEligibilityResponse checkEligibility(
            CheckJianghuDuelEligibilityRequest req, Function<Long, ScenePlayer> playerLookup) {
        if (!isEnabled()) {
            return CheckJianghuDuelEligibilityResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuDuelDisabled_VALUE))
                    .setPayerHasSufficientFee(false)
                    .build();
        }
        if (req == null || req.getPlayerIdsCount() > 10) {
            return CheckJianghuDuelEligibilityResponse.newBuilder()
                    .setErrorMessage(tip(CommonErrorTip.common_error.kInvalidParameter_VALUE))
                    .setPayerHasSufficientFee(false)
                    .build();
        }
        CheckJianghuDuelEligibilityResponse.Builder resp = CheckJianghuDuelEligibilityResponse.newBuilder()
                .setPayerHasSufficientFee(true);
        for (long pid : req.getPlayerIdsList()) {
            ScenePlayer p = playerLookup.apply(pid);
            boolean online = p != null;
            boolean refuse = online && stateOf(pid).getDuelRefuseStrangers();
            resp.addPlayers(JianghuPlayerEligibilityEntry.newBuilder()
                    .setPlayerId(pid)
                    .setOnlineOnNode(online)
                    .setDuelRefuseStrangers(refuse)
                    .build());
        }
        long payerId = req.getPayerPlayerId();
        if (payerId != 0L) {
            ScenePlayer payer = playerLookup.apply(payerId);
            if (payer == null) {
                resp.setPayerHasSufficientFee(false);
            } else if (req.getWritTier() == 2) {
                int itemId = req.getItemConfigId() > 0 ? req.getItemConfigId() : goldWritItemId();
                resp.setPayerHasSufficientFee(countItem(payer, itemId) >= 1);
            } else if (req.getWritTier() == 1) {
                long cost = req.getSilverCost() > 0 ? req.getSilverCost() : silverWritCost();
                resp.setPayerHasSufficientFee(payer.wallet().balance(Wallet.GOLD) >= cost);
            } else {
                resp.setPayerHasSufficientFee(false);
            }
        }
        return resp.build();
    }

    /**
     * 备战资费只读预检（批 X / r2-jh#1）：
     * <ul>
     *   <li>若存在别的局未结清待退银两（{@code pending_silver_fee_battle_id != 0 && != battleId}），一律拒回 {@code kFeatureUnavailable} 且不动标记；</li>
     *   <li>普通战书核对可交易银两 {@link Wallet#GOLD}，金帖核对主背包道具 3415。</li>
     * </ul>
     */
    public int validatePrepareFee(ScenePlayer player, long battleId, JianghuDuelPrepareFee fee) {
        if (fee == null || fee.getWritTier() == 0) {
            return 0;
        }
        if (!isEnabled()) {
            return JianghuErrorTip.jianghu_error.kJianghuDuelDisabled_VALUE;
        }
        if (player == null || player.frozen() || battleId == 0L) {
            return CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
        }
        PlayerJianghuComp cur = stateOf(player.playerId());
        long pendingBattleId = cur.getPendingSilverFeeBattleId();
        if (pendingBattleId != 0L && pendingBattleId != battleId) {
            return CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
        }
        if (fee.getWritTier() == 1) {
            if (pendingBattleId == battleId) {
                return 0;
            }
            long cost = fee.getSilverCost() > 0 ? fee.getSilverCost() : silverWritCost();
            if (player.wallet().balance(Wallet.GOLD) < cost) {
                return JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE;
            }
            return 0;
        }
        if (fee.getWritTier() == 2) {
            int itemId = fee.getItemConfigId() > 0 ? fee.getItemConfigId() : goldWritItemId();
            if (countItem(player, itemId) < 1) {
                return JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE;
            }
            return 0;
        }
        return CommonErrorTip.common_error.kInvalidParameter_VALUE;
    }

    /**
     * 备战锁落定后原子预扣生死状资费并登记待退标记与 SQL 终局复核时刻（批 X / r2-jh#1）。
     * 别的局未结清时拒绝且绝不覆盖旧标记。
     */
    public int commitPrepareFee(ScenePlayer player, long battleId, JianghuDuelPrepareFee fee, long reviewAfterMs) {
        int precheck = validatePrepareFee(player, battleId, fee);
        if (precheck != 0 || fee == null || fee.getWritTier() == 0) {
            return precheck;
        }
        long nowMs = clockMs.getAsLong();
        PlayerJianghuComp cur = stateOf(player.playerId());
        if (fee.getWritTier() == 1) {
            if (cur.getPendingSilverFeeBattleId() == battleId) {
                return 0;
            }
            long cost = fee.getSilverCost() > 0 ? fee.getSilverCost() : silverWritCost();
            long refId = fee.getDuelInviteId() != 0L ? fee.getDuelInviteId() : battleId;
            var deduct = currency.deduct(player, Wallet.GOLD, cost, AssetAudit.Reason.JIANGHU_DUEL_FEE, refId);
            if (!deduct.ok()) {
                return JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE;
            }
            statesByPlayer.put(player.playerId(), cur.toBuilder()
                    .setPendingSilverFeeBattleId(battleId)
                    .setPendingSilverFeeAmount(cost)
                    .setPendingSilverFeeReviewAfterMs(reviewAfterMs)
                    .build());
            return 0;
        }
        if (fee.getWritTier() == 2) {
            int itemId = fee.getItemConfigId() > 0 ? fee.getItemConfigId() : goldWritItemId();
            long refId = fee.getDuelInviteId() != 0L ? fee.getDuelInviteId() : battleId;
            var prep = bags.prepareSelfConsume(player, Map.of(itemId, 1L));
            if (prep.tip() != 0) {
                return JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE;
            }
            prep.commit(AssetAudit.Reason.JIANGHU_DUEL_FEE, refId);
            return 0;
        }
        return CommonErrorTip.common_error.kInvalidParameter_VALUE;
    }

    /**
     * 单通道退还本局普通战书预扣银两（批 X / r2-jh#2）：
     * 玩家冻结或不可改时不动标记；严格「先入账成功、再清标记」；{@link AssetAudit.Reason#JIANGHU_DUEL_REFUND} 豁免获取封禁。
     */
    public boolean refundPendingSilverFee(ScenePlayer player, long battleId) {
        if (player == null || player.frozen() || battleId == 0L) {
            return false;
        }
        PlayerJianghuComp cur = stateOf(player.playerId());
        if (cur.getPendingSilverFeeBattleId() != battleId || cur.getPendingSilverFeeAmount() <= 0L) {
            return false;
        }
        long amount = cur.getPendingSilverFeeAmount();
        long nowMs = clockMs.getAsLong();
        var added = currency.add(player, Wallet.GOLD, amount, AssetAudit.Reason.JIANGHU_DUEL_REFUND,
                battleId, nowMs / 1000L);
        if (!added.ok()) {
            log.warn("jianghu silver fee refund credit failed; keeping marker player={} battle_id={} amount={} change={}",
                    Long.toUnsignedString(player.playerId()), Long.toUnsignedString(battleId), amount, added);
            return false;
        }
        statesByPlayer.put(player.playerId(), cur.toBuilder()
                .setPendingSilverFeeBattleId(0L)
                .setPendingSilverFeeAmount(0L)
                .setPendingSilverFeeReviewAfterMs(0L)
                .build());
        return true;
    }

    /** 缺陷兜底（批 X §7.3）：SQL 终局已 COMPLETE 且结算已在本实体应用但标记残留时清掉不退并记 ERROR，避免永久卡新局。 */
    public boolean consumePendingSilverFee(ScenePlayer player, long battleId) {
        if (player == null || player.frozen() || battleId == 0L) {
            return false;
        }
        PlayerJianghuComp cur = stateOf(player.playerId());
        if (cur.getPendingSilverFeeBattleId() != battleId) {
            return false;
        }
        log.error("jianghu pending silver fee marker remained after COMPLETE settlement; clearing without refund player={} battle_id={} amount={}",
                Long.toUnsignedString(player.playerId()), Long.toUnsignedString(battleId), cur.getPendingSilverFeeAmount());
        statesByPlayer.put(player.playerId(), cur.toBuilder()
                .setPendingSilverFeeBattleId(0L)
                .setPendingSilverFeeAmount(0L)
                .setPendingSilverFeeReviewAfterMs(0L)
                .build());
        return true;
    }

    /** 天墉生死状隔离战后社交印记结算（零经验/零银两/零 660 材料，仅维护 30 分钟战败印记与雪耻清除）。 */
    public void applyDuelSettlement(ScenePlayer player, BattleSettlementData settlement) {
        if (player == null || settlement == null || !settlement.hasJianghuDuel()) {
            return;
        }
        long nowMs = clockMs.getAsLong();
        PlayerJianghuComp cur = normalizeExpired(player, stateOf(player.playerId()), nowMs);
        if (settlement.getTotalRounds() == 0 || settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_VOID) {
            // 批 X：作废 / 0 回合严格先入账成功再清标记，入账失败保留标记交终局复核重试
            statesByPlayer.put(player.playerId(), cur);
            refundPendingSilverFee(player, settlement.getBattleId());
            return;
        }
        PlayerJianghuComp.Builder b = cur.toBuilder();
        if (cur.getPendingSilverFeeBattleId() == settlement.getBattleId()) {
            b.setPendingSilverFeeBattleId(0L)
                    .setPendingSilverFeeAmount(0L)
                    .setPendingSilverFeeReviewAfterMs(0L);
        }
        JianghuDuelBattleContext duel = settlement.getJianghuDuel();
        boolean playerWon = (settlement.getPlayerTeamIndex() == 0 && settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                || (settlement.getPlayerTeamIndex() == 1 && settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);
        boolean playerLost = (settlement.getPlayerTeamIndex() == 0 && settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                || (settlement.getPlayerTeamIndex() == 1 && settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);

        long opponentCaptainId = settlement.getPlayerTeamIndex() == 0
                ? duel.getTargetCaptainId() : duel.getChallengerCaptainId();
        String opponentCaptainName = settlement.getPlayerTeamIndex() == 0
                ? duel.getTargetCaptainName() : duel.getChallengerCaptainName();
        long opponentGuildId = settlement.getPlayerTeamIndex() == 0
                ? duel.getSideBGuildId() : duel.getSideAGuildId();
        boolean isPureGuild = duel.getSideAGuildId() != 0L && duel.getSideBGuildId() != 0L
                && duel.getSideAGuildId() != duel.getSideBGuildId();
        long displayWinnerId = isPureGuild ? opponentGuildId : opponentCaptainId;
        String displayWinnerName = isPureGuild
                ? (settlement.getPlayerTeamIndex() == 0 ? duel.getSideBGuildName() : duel.getSideAGuildName())
                : opponentCaptainName;

        if (playerLost && cur.getDefeatMarkBattleId() != settlement.getBattleId()) {
            b.setDefeatMarkWinnerId(displayWinnerId)
                    .setDefeatMarkWinnerName(displayWinnerName)
                    .setDefeatMarkExpiresAtMs(nowMs + defeatMarkDurationMs())
                    .setDefeatMarkStyleId(duel.getWritTier())
                    .setDefeatMarkIsGuildName(isPureGuild)
                    .setDefeatMarkBattleId(settlement.getBattleId())
                    .setOwnerRevision(player.ownerEpoch())
                    .setClearBadgeExpiresAtMs(0L)
                    .setClearBadgeStyleId(0);
        } else if (playerWon && cur.getDefeatMarkExpiresAtMs() > nowMs) {
            boolean revengeMatched = (!cur.getDefeatMarkIsGuildName() && opponentCaptainId != 0L
                    && opponentCaptainId == cur.getDefeatMarkWinnerId())
                    || (cur.getDefeatMarkIsGuildName() && opponentGuildId != 0L
                    && opponentGuildId == cur.getDefeatMarkWinnerId());
            if (revengeMatched) {
                b.setDefeatMarkWinnerId(0L)
                        .setDefeatMarkWinnerName("")
                        .setDefeatMarkExpiresAtMs(0L)
                        .setDefeatMarkStyleId(0)
                        .setDefeatMarkIsGuildName(false)
                        .setDefeatMarkBattleId(0L);
            }
        }
        statesByPlayer.put(player.playerId(), b.build());
    }

    private static PlayerJianghuComp normalizeExpired(ScenePlayer player, PlayerJianghuComp comp, long nowMs) {
        boolean ownerChanged = comp.getOwnerRevision() != 0
                && comp.getOwnerRevision() != player.ownerEpoch();
        boolean needClearMark = comp.getDefeatMarkExpiresAtMs() > 0
                && (comp.getDefeatMarkExpiresAtMs() <= nowMs || ownerChanged);
        boolean needClearBadge = comp.getClearBadgeExpiresAtMs() > 0
                && (comp.getClearBadgeExpiresAtMs() <= nowMs || ownerChanged);
        if (!needClearMark && !needClearBadge) {
            return comp;
        }
        PlayerJianghuComp.Builder b = comp.toBuilder();
        if (needClearMark) {
            b.setDefeatMarkWinnerId(0L)
                    .setDefeatMarkWinnerName("")
                    .setDefeatMarkExpiresAtMs(0L)
                    .setDefeatMarkStyleId(0)
                    .setDefeatMarkIsGuildName(false)
                    .setDefeatMarkBattleId(0L);
        }
        if (needClearBadge) {
            b.setClearBadgeExpiresAtMs(0L)
                    .setClearBadgeStyleId(0);
        }
        return b.build();
    }

    private static int countItem(ScenePlayer player, int configId) {
        if (player == null || configId <= 0) {
            return 0;
        }
        long total = player.bags().bag(BagType.INVENTORY).total(configId);
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private static TipInfoMessage tip(int tipId) {
        return TipInfoMessage.newBuilder().setId(tipId).build();
    }
}
