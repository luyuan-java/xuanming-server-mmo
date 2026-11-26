package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.CONFLICT;
import static com.game.trade.commerce.CommerceRules.ENDED;
import static com.game.trade.commerce.CommerceRules.INVALID;
import static com.game.trade.commerce.CommerceRules.MISSING;
import static com.game.trade.commerce.CommerceRules.REQUEST_CONFLICT;
import static com.game.trade.commerce.CommerceRules.SELF;
import static com.game.trade.commerce.CommerceRules.SETTLED;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.proto.trade.SellerCreditView;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.trade.store.pb.TradeCommerceRow;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * 卖家店铺信用评分服务：
 * <ul>
 *   <li>按 {@code (sellerPlayerId, ownerRevision)} 严格隔离每一任持有者的店铺评分，官方过户换人立即清空重算；</li>
 *   <li>同一持有周期内，单纯换 IP（出差/切 5G）或同 IP 换常用设备零影响；仅当同时命中「陌生新设备 + IP 变化」时直接将正面信用清 0，但保留历史差评与违约计数严禁洗白；</li>
 *   <li>评价强制绑定原成交 {@code ownerRevision}、禁止同账号/同 IP/同设备互刷，支持最低计分金额、7 日配对上限与异地换机后好评冷静期。</li>
 * </ul>
 */
public final class SellerCreditService {
    public static final int DEFAULT_INITIAL_SCORE = 60;
    public static final int DEFAULT_INITIAL_STAR_TENTHS = 30; // 3.0★
    public static final long DEFAULT_REVIEW_WINDOW_MS = 7L * 24 * 3600 * 1000;
    public static final long DEFAULT_PAIR_SCORE_WINDOW_MS = 7L * 24 * 3600 * 1000;
    public static final int DEFAULT_MAX_SCORED_PAIR_REVIEWS = 1;
    private static final int POSITIVE_DELTA = 2;
    private static final int NEGATIVE_PENALTY = 6;
    private static final int VIOLATION_PENALTY = 25;
    private static final int MAX_KNOWN_SELLER_DEVICES = 8;

    private static final long MAX_POLICY_WINDOW_MS = 90L * 24 * 3600 * 1000;
    private static final long MAX_POLICY_COOLDOWN_MS = 30L * 24 * 3600 * 1000;

    /** 买家对已完成托管订单给出的评价档位。 */
    public enum Rating {
        UNSPECIFIED(0),
        POSITIVE(1),
        NEUTRAL(2),
        NEGATIVE(3);

        private final int code;

        Rating(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Rating fromCode(int code) {
            return switch (code) {
                case 1 -> POSITIVE;
                case 2 -> NEUTRAL;
                case 3 -> NEGATIVE;
                default -> UNSPECIFIED;
            };
        }
    }

    /** 卖家信用派生的风控与额度档位。 */
    public enum RiskTier {
        RESTRICTED("restricted"),
        NEW_OR_TRANSFERRED("new_or_transferred"),
        STANDARD("standard"),
        TRUSTED("trusted");

        private final String wireName;

        RiskTier(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static RiskTier fromWire(String wire) {
            if (wire == null) {
                return NEW_OR_TRANSFERRED;
            }
            return switch (wire) {
                case "restricted" -> RESTRICTED;
                case "standard" -> STANDARD;
                case "trusted" -> TRUSTED;
                default -> NEW_OR_TRANSFERRED;
            };
        }
    }

    /** 权威角色归属快照。 */
    public record RoleOwnership(String account, long ownerRevision) {}

    /** 查询角色当前权威持有账号与单调递增过户修订号 {@code owner_revision}。 */
    @FunctionalInterface
    public interface RoleOwnershipLookup {
        RoleOwnership lookup(long playerId) throws SQLException;
    }

    /** 按 {@code (sellerPlayerId, ownerRevision)} 唯一隔离一任持有者的店铺信用。 */
    public record CreditKey(long sellerPlayerId, long ownerRevision) {}

    /**
     * 卖家在当前持有周期内的店铺信用档案。
     * {@code lastIp} / {@code lastDeviceId} / {@code knownDevices} 仅供服务端内部防刷分判定，不得下发给其他玩家。
     */
    public record Profile(
            long sellerPlayerId,
            String ownerAccount,
            long ownerRevision,
            boolean ownershipChanged,
            long ownershipChangedAtMs,
            long epochStartedAtMs,
            long completedOrders,
            long positiveReviews,
            long neutralReviews,
            long negativeReviews,
            long violationCount,
            boolean envCreditCleared,
            long envResetCount,
            long lastEnvChangedAtMs,
            String lastIp,
            String lastDeviceId,
            List<String> knownDevices,
            int creditScore,
            int starRatingTenths,
            int positiveRateBps,
            RiskTier riskTier) {

        public Profile {
            ownerAccount = ownerAccount == null ? "" : ownerAccount;
            lastIp = lastIp == null ? "" : lastIp;
            lastDeviceId = lastDeviceId == null ? "" : lastDeviceId;
            knownDevices = knownDevices == null ? List.of() : List.copyOf(knownDevices);
            riskTier = riskTier == null ? RiskTier.NEW_OR_TRANSFERRED : riskTier;
        }
    }

    /** 服务端可信会话采集的卖家环境观测事件。 */
    public record EnvironmentEvent(
            long sellerPlayerId,
            String ownerAccount,
            long ownerRevision,
            String ip,
            String deviceId,
            long observedAtMs) {}

    /** 环境观测结果，{@code cleared=true} 表示本次触发了异地陌生新设备正面信用清 0。 */
    public record EnvironmentUpdate(Profile profile, boolean cleared) {}

    /** 官方托管真实成交事实（含可选的可信 IP/设备指纹）。 */
    public record CompletedTradeFact(
            String orderId,
            long sellerPlayerId,
            String sellerAccount,
            long sellerOwnerRevision,
            String sellerIp,
            String sellerDeviceId,
            long buyerPlayerId,
            String buyerAccount,
            String buyerIp,
            String buyerDeviceId,
            long amountMinor,
            long settledAtMs) {

        public CompletedTradeFact(
                String orderId,
                long sellerPlayerId,
                String sellerAccount,
                long sellerOwnerRevision,
                long buyerPlayerId,
                String buyerAccount,
                long amountMinor,
                long settledAtMs) {
            this(orderId, sellerPlayerId, sellerAccount, sellerOwnerRevision, "", "",
                    buyerPlayerId, buyerAccount, "", "", amountMinor, settledAtMs);
        }
    }

    /** 买家提交评价请求。 */
    public record ReviewRequest(CompletedTradeFact trade, Rating rating, String comment) {
        public ReviewRequest(CompletedTradeFact trade, Rating rating) {
            this(trade, rating, "");
        }
    }

    /** 持久化保存的单笔评价凭证（按 {@code orderId} 幂等唯一）。 */
    public record ReviewRecord(
            String orderId,
            long sellerPlayerId,
            long sellerOwnerRevision,
            long buyerPlayerId,
            String buyerAccount,
            String buyerIp,
            String buyerDeviceId,
            Rating rating,
            boolean countedForScore,
            String comment,
            long createdAtMs) {

        public ReviewRecord {
            buyerAccount = buyerAccount == null ? "" : buyerAccount;
            buyerIp = buyerIp == null ? "" : buyerIp;
            buyerDeviceId = buyerDeviceId == null ? "" : buyerDeviceId;
            comment = comment == null ? "" : comment.trim();
        }

        public ReviewRecord(
                String orderId,
                long sellerPlayerId,
                long sellerOwnerRevision,
                long buyerPlayerId,
                String buyerAccount,
                String buyerIp,
                String buyerDeviceId,
                Rating rating,
                boolean countedForScore,
                long createdAtMs) {
            this(orderId, sellerPlayerId, sellerOwnerRevision, buyerPlayerId, buyerAccount, buyerIp, buyerDeviceId,
                    rating, countedForScore, "", createdAtMs);
        }
    }

    /** 评价提交结果。 */
    public record ReviewSubmission(ReviewRecord review, Profile profile, boolean replayed) {}

    /** 经核实的违约/欺诈/恶意拒付扣分记录（按 {@code caseId} 幂等唯一）。 */
    public record ViolationRecord(
            String caseId,
            String orderId,
            long sellerPlayerId,
            String sellerAccount,
            long sellerOwnerRevision,
            String reason,
            long recordedAtMs) {}

    /** 卖家信用防刷分窗口与冷静期策略。 */
    public record Policy(
            long reviewWindowMs,
            long pairScoreWindowMs,
            int maxScoredReviewsPerPairInWindow,
            long minScoreAmountMinor,
            long postEnvChangeScoreCooldownMs) {

        public static Policy defaults() {
            return new Policy(
                    DEFAULT_REVIEW_WINDOW_MS,
                    DEFAULT_PAIR_SCORE_WINDOW_MS,
                    DEFAULT_MAX_SCORED_PAIR_REVIEWS,
                    0L,
                    0L);
        }

        public Policy normalized() {
            return new Policy(
                    reviewWindowMs == 0 ? DEFAULT_REVIEW_WINDOW_MS : reviewWindowMs,
                    pairScoreWindowMs == 0 ? DEFAULT_PAIR_SCORE_WINDOW_MS : pairScoreWindowMs,
                    maxScoredReviewsPerPairInWindow <= 0
                            ? DEFAULT_MAX_SCORED_PAIR_REVIEWS
                            : maxScoredReviewsPerPairInWindow,
                    minScoreAmountMinor,
                    postEnvChangeScoreCooldownMs);
        }

        /** 配置启动期范围校验，与 Go {@code SellerCreditConf.validate} 同口径。 */
        public void validate() {
            if (maxScoredReviewsPerPairInWindow < 0 || maxScoredReviewsPerPairInWindow > 10) {
                throw new IllegalArgumentException(
                        "game.trade.commerce.seller-credit.max-scored-reviews-per-pair-in-window("
                                + maxScoredReviewsPerPairInWindow + ") 必须在 [0, 10] 内");
            }
            if (Long.compareUnsigned(reviewWindowMs, MAX_POLICY_WINDOW_MS) > 0) {
                throw new IllegalArgumentException("game.trade.commerce.seller-credit.review-window-ms 不能超过 90 天");
            }
            if (Long.compareUnsigned(pairScoreWindowMs, MAX_POLICY_WINDOW_MS) > 0) {
                throw new IllegalArgumentException("game.trade.commerce.seller-credit.pair-score-window-ms 不能超过 90 天");
            }
            if (Long.compareUnsigned(postEnvChangeScoreCooldownMs, MAX_POLICY_COOLDOWN_MS) > 0) {
                throw new IllegalArgumentException(
                        "game.trade.commerce.seller-credit.post-env-change-score-cooldown-ms 不能超过 30 天");
            }
        }
    }

    /** 卖家信用、成交计数、评价与违约记录的持久层抽象。 */
    public interface Store {
        @FunctionalInterface
        interface StoreWork<T> {
            T run(Store txStore) throws SQLException;
        }

        /** 在单事务内按卖家 (player_id, owner_revision) 串行化读写，防止并发评价/违约相互覆盖或绕过频控。 */
        default <T> T inTransaction(CreditKey lockKey, StoreWork<T> work) throws SQLException {
            return work.run(this);
        }

        default void insertProfileIfAbsent(Profile profile) throws SQLException {
            if (getProfile(new CreditKey(profile.sellerPlayerId(), profile.ownerRevision())).isEmpty()) {
                saveProfile(profile);
            }
        }

        Optional<Profile> getProfile(CreditKey key) throws SQLException;

        boolean hasPriorEpoch(long sellerPlayerId, long currentRevision) throws SQLException;

        void saveProfile(Profile profile) throws SQLException;

        boolean markOrderCompleted(String orderId, CreditKey key) throws SQLException;

        Optional<ReviewRecord> getReviewByOrder(String orderId) throws SQLException;

        int countPairScoredReviews(
                CreditKey key,
                String buyerKey,
                String buyerIp,
                String buyerDeviceId,
                long sinceMs,
                long nowMs) throws SQLException;

        void saveReview(ReviewRecord record) throws SQLException;

        boolean hasViolation(String caseId) throws SQLException;

        void saveViolation(ViolationRecord record) throws SQLException;
    }

    private final Store store;
    private final RoleOwnershipLookup ownership;
    private final Policy policy;

    public SellerCreditService(Store store, RoleOwnershipLookup ownership, Policy policy) {
        this.store = Objects.requireNonNull(store, "卖家信用存储未装配");
        this.ownership = ownership;
        this.policy = policy == null ? Policy.defaults() : policy;
    }

    /** 角色完成官方过户换账号时，立即创建全新清空的信用档案并标记换人重算。 */
    public Profile onCharacterTransferred(long playerId, String newAccount, long newOwnerRevision, long nowMs)
            throws SQLException {
        if (playerId == 0 || newAccount == null || newAccount.isEmpty() || newOwnerRevision == 0) {
            throw new CommerceRules.Refusal(INVALID);
        }
        CreditKey key = new CreditKey(playerId, newOwnerRevision);
        return store.inTransaction(key, tx -> {
            Profile fresh = newInitialProfile(playerId, newAccount, newOwnerRevision, true, nowMs);
            tx.saveProfile(fresh);
            return fresh;
        });
    }

    /**
     * 记录卖家可信登录/交易环境：
     * 仅同时命中「从未见过的陌生新设备 + IP 发生变化」时清 0 正面信用与分数（保留差评与违约防洗白）；
     * 单纯换 IP 或同 IP 换设备或切回常用设备零影响。
     */
    public EnvironmentUpdate recordSellerEnvironment(EnvironmentEvent ev) throws SQLException {
        if (ev == null) {
            throw new CommerceRules.Refusal(INVALID);
        }
        String ip = normalizeEnvToken(ev.ip());
        String device = normalizeEnvToken(ev.deviceId());
        if (ev.sellerPlayerId() == 0
                || ev.ownerRevision() == 0
                || ev.ownerAccount() == null
                || ev.ownerAccount().isEmpty()
                || (ip.isEmpty() && device.isEmpty())) {
            throw new CommerceRules.Refusal(INVALID);
        }
        CreditKey key = new CreditKey(ev.sellerPlayerId(), ev.ownerRevision());
        return store.inTransaction(key, tx -> {
            Profile profile = ensureEpochProfile(
                    tx, ev.sellerPlayerId(), ev.ownerAccount(), ev.ownerRevision(), ev.observedAtMs());

            boolean ipChanged = !ip.isEmpty() && !profile.lastIp().isEmpty() && !ip.equals(profile.lastIp());
            boolean hasDeviceBaseline = !profile.knownDevices().isEmpty() || !profile.lastDeviceId().isEmpty();
            boolean isUnknownNewDevice = !device.isEmpty()
                    && hasDeviceBaseline
                    && !profile.knownDevices().contains(device)
                    && !device.equals(profile.lastDeviceId());

            boolean updated = false;
            String nextIp = profile.lastIp();
            if (!ip.isEmpty() && !ip.equals(nextIp)) {
                nextIp = ip;
                updated = true;
            }

            boolean cleared = false;
            Profile next;
            if (isUnknownNewDevice && ipChanged) {
                // 严禁清空 negativeReviews 与 violationCount，防止借异地换机洗白黑历史。
                next = recompute(new Profile(
                        profile.sellerPlayerId(),
                        profile.ownerAccount(),
                        profile.ownerRevision(),
                        profile.ownershipChanged(),
                        profile.ownershipChangedAtMs(),
                        profile.epochStartedAtMs(),
                        0L,
                        0L,
                        0L,
                        profile.negativeReviews(),
                        profile.violationCount(),
                        true,
                        profile.envResetCount() + 1L,
                        ev.observedAtMs(),
                        nextIp,
                        device,
                        List.of(device),
                        0,
                        0,
                        0,
                        RiskTier.RESTRICTED));
                cleared = true;
                updated = true;
            } else {
                String nextDevice = profile.lastDeviceId();
                List<String> nextKnown = profile.knownDevices();
                if (!device.isEmpty()) {
                    if (!device.equals(nextDevice)) {
                        nextDevice = device;
                        updated = true;
                    }
                    if (!nextKnown.contains(device)) {
                        nextKnown = rememberKnownDevice(nextKnown, device);
                        updated = true;
                    }
                }
                next = updated
                        ? new Profile(
                                profile.sellerPlayerId(),
                                profile.ownerAccount(),
                                profile.ownerRevision(),
                                profile.ownershipChanged(),
                                profile.ownershipChangedAtMs(),
                                profile.epochStartedAtMs(),
                                profile.completedOrders(),
                                profile.positiveReviews(),
                                profile.neutralReviews(),
                                profile.negativeReviews(),
                                profile.violationCount(),
                                profile.envCreditCleared(),
                                profile.envResetCount(),
                                profile.lastEnvChangedAtMs(),
                                nextIp,
                                nextDevice,
                                nextKnown,
                                profile.creditScore(),
                                profile.starRatingTenths(),
                                profile.positiveRateBps(),
                                profile.riskTier())
                        : profile;
            }
            if (updated) {
                tx.saveProfile(next);
            }
            return new EnvironmentUpdate(next, cleared);
        });
    }

    /** 查询卖家在当前权威持有周期下的信用档案；角色一旦过户自动切换至全新清空档案。 */
    public Profile getProfile(long sellerPlayerId, long nowMs) throws SQLException {
        if (ownership == null) {
            throw new IllegalStateException("卖家信用归属查询未装配");
        }
        if (sellerPlayerId == 0) {
            throw new CommerceRules.Refusal(INVALID);
        }
        RoleOwnership owned = ownership.lookup(sellerPlayerId);
        if (owned == null || owned.ownerRevision() == 0 || owned.account() == null || owned.account().isEmpty()) {
            throw new CommerceRules.Refusal(CONFLICT);
        }
        return ensureEpochProfile(store, sellerPlayerId, owned.account(), owned.ownerRevision(), nowMs);
    }

    /** 官方托管真实成交结案后按 {@code orderId} 幂等累加卖家成交笔数，拒绝自买自卖。 */
    public Profile recordCompletedTrade(CompletedTradeFact fact) throws SQLException {
        if (fact == null
                || fact.orderId() == null
                || fact.orderId().isEmpty()
                || fact.sellerPlayerId() == 0
                || fact.buyerPlayerId() == 0
                || fact.sellerOwnerRevision() == 0
                || fact.sellerAccount() == null
                || fact.sellerAccount().isEmpty()) {
            throw new CommerceRules.Refusal(INVALID);
        }
        if (fact.sellerPlayerId() == fact.buyerPlayerId()
                || (fact.buyerAccount() != null && !fact.buyerAccount().isEmpty()
                        && fact.sellerAccount().equals(fact.buyerAccount()))) {
            throw new CommerceRules.Refusal(SELF);
        }
        CreditKey key = new CreditKey(fact.sellerPlayerId(), fact.sellerOwnerRevision());
        return store.inTransaction(key, tx -> recordCompletedTradeInTx(tx, fact));
    }

    private Profile recordCompletedTradeInTx(Store tx, CompletedTradeFact fact) throws SQLException {
        CreditKey key = new CreditKey(fact.sellerPlayerId(), fact.sellerOwnerRevision());
        Profile profile = ensureEpochProfile(
                tx, fact.sellerPlayerId(), fact.sellerAccount(), fact.sellerOwnerRevision(), fact.settledAtMs());
        boolean inserted = tx.markOrderCompleted(fact.orderId(), key);
        if (inserted) {
            profile = recompute(withCounters(
                    profile,
                    profile.completedOrders() + 1L,
                    profile.positiveReviews(),
                    profile.neutralReviews(),
                    profile.negativeReviews(),
                    profile.violationCount()));
            tx.saveProfile(profile);
        }
        return profile;
    }

    /**
     * 处理买家评价：绑定原成交 {@code sellerOwnerRevision}、按 {@code orderId} 幂等（改评拒回 {@code REQUEST_CONFLICT}）、
     * 严格校验评价窗口、禁止自买自评与同 IP/同设备/超频/低金额/换环境冷静期内涨分。
     */
    public ReviewSubmission submitReview(ReviewRequest in, long nowMs) throws SQLException {
        if (in == null || in.trade() == null) {
            throw new CommerceRules.Refusal(INVALID);
        }
        CompletedTradeFact t = in.trade();
        if (t.orderId() == null
                || t.orderId().isEmpty()
                || t.sellerPlayerId() == 0
                || t.buyerPlayerId() == 0
                || t.sellerOwnerRevision() == 0
                || t.sellerAccount() == null
                || t.sellerAccount().isEmpty()
                || (in.rating() != Rating.POSITIVE && in.rating() != Rating.NEUTRAL && in.rating() != Rating.NEGATIVE)) {
            throw new CommerceRules.Refusal(INVALID);
        }
        if (t.sellerPlayerId() == t.buyerPlayerId()
                || (t.buyerAccount() != null && !t.buyerAccount().isEmpty()
                        && t.sellerAccount().equals(t.buyerAccount()))) {
            throw new CommerceRules.Refusal(SELF);
        }
        Policy p = policy.normalized();
        if (Long.compareUnsigned(nowMs, t.settledAtMs()) < 0
                || Long.compareUnsigned(nowMs - t.settledAtMs(), p.reviewWindowMs()) > 0) {
            throw new CommerceRules.Refusal(ENDED);
        }

        CreditKey key = new CreditKey(t.sellerPlayerId(), t.sellerOwnerRevision());
        return store.inTransaction(key, tx -> {
            // 先锁卖家信用档案行，再查重/计数/插评价/回写分数，杜绝并发超频刷分与覆盖违约记录。
            Profile lockedBase = ensureEpochProfile(
                    tx, t.sellerPlayerId(), t.sellerAccount(), t.sellerOwnerRevision(), nowMs);
            Optional<ReviewRecord> existing = tx.getReviewByOrder(t.orderId());
            if (existing.isPresent()) {
                ReviewRecord prior = existing.get();
                if (prior.rating() != in.rating()
                        || prior.buyerPlayerId() != t.buyerPlayerId()
                        || prior.sellerPlayerId() != t.sellerPlayerId()) {
                    throw new CommerceRules.Refusal(REQUEST_CONFLICT);
                }
                return new ReviewSubmission(prior, lockedBase, true);
            }

            Profile profile = recordCompletedTradeInTx(tx, t);

            String buyerKey = (t.buyerAccount() == null || t.buyerAccount().isEmpty())
                    ? "player:" + Long.toUnsignedString(t.buyerPlayerId())
                    : t.buyerAccount();
            String sellerIp = normalizeEnvToken(t.sellerIp());
            if (sellerIp.isEmpty()) {
                sellerIp = profile.lastIp();
            }
            String sellerDev = normalizeEnvToken(t.sellerDeviceId());
            if (sellerDev.isEmpty()) {
                sellerDev = profile.lastDeviceId();
            }
            String buyerIp = normalizeEnvToken(t.buyerIp());
            String buyerDev = normalizeEnvToken(t.buyerDeviceId());

            long windowStart = Long.compareUnsigned(nowMs, p.pairScoreWindowMs()) > 0
                    ? nowMs - p.pairScoreWindowMs()
                    : 0L;
            int pairCount = tx.countPairScoredReviews(key, buyerKey, buyerIp, buyerDev, windowStart, nowMs);

            boolean counted = true;
            if ((!sellerIp.isEmpty() && !buyerIp.isEmpty() && sellerIp.equals(buyerIp))
                    || (!sellerDev.isEmpty() && !buyerDev.isEmpty() && sellerDev.equals(buyerDev))) {
                counted = false;
            }
            if (in.rating() == Rating.POSITIVE
                    && Long.compareUnsigned(t.amountMinor(), p.minScoreAmountMinor()) < 0) {
                counted = false;
            }
            if (pairCount >= p.maxScoredReviewsPerPairInWindow()) {
                counted = false;
            }
            if (in.rating() == Rating.POSITIVE
                    && p.postEnvChangeScoreCooldownMs() > 0
                    && profile.lastEnvChangedAtMs() != 0
                    && Long.compareUnsigned(nowMs, profile.lastEnvChangedAtMs()) >= 0
                    && Long.compareUnsigned(nowMs - profile.lastEnvChangedAtMs(), p.postEnvChangeScoreCooldownMs()) < 0) {
                counted = false;
            }

            ReviewRecord rec = new ReviewRecord(
                    t.orderId(),
                    t.sellerPlayerId(),
                    t.sellerOwnerRevision(),
                    t.buyerPlayerId(),
                    buyerKey,
                    buyerIp,
                    buyerDev,
                    in.rating(),
                    counted,
                    in.comment() == null ? "" : in.comment().trim(),
                    nowMs);
            tx.saveReview(rec);
            if (counted) {
                long pos = profile.positiveReviews() + (in.rating() == Rating.POSITIVE ? 1L : 0L);
                long neu = profile.neutralReviews() + (in.rating() == Rating.NEUTRAL ? 1L : 0L);
                long neg = profile.negativeReviews() + (in.rating() == Rating.NEGATIVE ? 1L : 0L);
                profile = recompute(withCounters(profile, profile.completedOrders(), pos, neu, neg, profile.violationCount()));
                tx.saveProfile(profile);
            }
            return new ReviewSubmission(rec, profile, false);
        });
    }

    /** 记入经核实的违约/欺诈/恶意拒付，按 {@code caseId} 幂等扣减当任信用并立即降级为 {@code RESTRICTED}。 */
    public Profile recordViolation(ViolationRecord rec) throws SQLException {
        if (rec == null
                || rec.caseId() == null
                || rec.caseId().isEmpty()
                || rec.sellerPlayerId() == 0
                || rec.sellerOwnerRevision() == 0
                || rec.sellerAccount() == null
                || rec.sellerAccount().isEmpty()) {
            throw new CommerceRules.Refusal(INVALID);
        }
        CreditKey key = new CreditKey(rec.sellerPlayerId(), rec.sellerOwnerRevision());
        return store.inTransaction(key, tx -> {
            Profile profile = ensureEpochProfile(
                    tx, rec.sellerPlayerId(), rec.sellerAccount(), rec.sellerOwnerRevision(), rec.recordedAtMs());
            if (tx.hasViolation(rec.caseId())) {
                return profile;
            }
            tx.saveViolation(rec);
            profile = recompute(withCounters(
                    profile,
                    profile.completedOrders(),
                    profile.positiveReviews(),
                    profile.neutralReviews(),
                    profile.negativeReviews(),
                    profile.violationCount() + 1L));
            tx.saveProfile(profile);
            return profile;
        });
    }

    /**
     * 从已结案（{@code SETTLED}）的官方托管合同提取卖家信用成交事实；
     * 仅统计固定价寄售（kind=3）、集市撮合子单（kind=4/side=0）、拍卖（kind=5）与非角色人民币寄售（kind=6）；
     * 刻意排除当面交易（kind=2，防熟人互刷）与角色过户（kind=6 且 character_player_id!=0，走过户清空链路）。
     */
    public static Optional<CompletedTradeFact> settledTradeFact(
            TradeCommerceRow row, TradeCommerceWorkflow workflow, long settledAtMs) {
        if (row == null || workflow == null || row.getStatus() != SETTLED || row.getSellerPlayerId() == 0) {
            return Optional.empty();
        }
        String orderId = "commerce:" + Long.toUnsignedString(row.getCommerceId());
        long buyerId;
        long amount;
        switch (row.getKind()) {
            case 3 -> {
                buyerId = row.getBuyerPlayerId();
                amount = row.getUnitPrice();
            }
            case 4 -> {
                if (row.getSide() != 0) {
                    return Optional.empty();
                }
                buyerId = row.getBuyerPlayerId();
                long qty = Integer.toUnsignedLong(row.getQuantity());
                if (qty > 0 && Long.compareUnsigned(row.getUnitPrice(), Long.divideUnsigned(-1L, qty)) > 0) {
                    amount = -1L;
                } else {
                    amount = row.getUnitPrice() * qty;
                }
            }
            case 5 -> {
                buyerId = workflow.getHighestBidder();
                amount = workflow.getHighestBid();
            }
            case 6 -> {
                if (!workflow.hasRmb() || workflow.getRmb().getCharacterPlayerId() != 0) {
                    return Optional.empty();
                }
                buyerId = row.getBuyerPlayerId();
                amount = workflow.getRmb().getPriceFen();
                if (!workflow.getRmb().getMallOrderId().isEmpty()) {
                    orderId = workflow.getRmb().getMallOrderId();
                }
            }
            default -> {
                return Optional.empty();
            }
        }
        if (buyerId == 0 || buyerId == row.getSellerPlayerId()) {
            return Optional.empty();
        }
        String sellerAcct = workflow.getSellerIdentity().getAccount();
        long sellerRev = workflow.getSellerIdentity().getOwnerRevision();
        String buyerAcct = workflow.getBuyerIdentity().getAccount();
        if (row.getKind() == 6 && workflow.hasRmb()) {
            if (sellerAcct.isEmpty()) {
                sellerAcct = workflow.getRmb().getSellerAccount();
            }
            if (sellerRev == 0) {
                sellerRev = workflow.getRmb().getSellerOwnerRevision();
            }
            if (buyerAcct.isEmpty()) {
                buyerAcct = workflow.getRmb().getBuyerAccount();
            }
        }
        if (sellerAcct.isEmpty() || sellerRev == 0 || (!buyerAcct.isEmpty() && sellerAcct.equals(buyerAcct))) {
            return Optional.empty();
        }
        return Optional.of(new CompletedTradeFact(
                orderId,
                row.getSellerPlayerId(),
                sellerAcct,
                sellerRev,
                buyerId,
                buyerAcct,
                amount,
                settledAtMs));
    }

    private Profile ensureEpochProfile(Store s, long playerId, String account, long revision, long nowMs)
            throws SQLException {
        CreditKey key = new CreditKey(playerId, revision);
        Optional<Profile> existing = s.getProfile(key);
        if (existing.isEmpty()) {
            boolean hasPrior = s.hasPriorEpoch(playerId, revision);
            boolean changed = hasPrior || Long.compareUnsigned(revision, 1L) > 0;
            Profile fresh = newInitialProfile(playerId, account, revision, changed, nowMs);
            // INSERT IGNORE 确保首建行存在，再以 FOR UPDATE 重读锁定，消除并发首建丢失更新。
            s.insertProfileIfAbsent(fresh);
            existing = s.getProfile(key);
            if (existing.isEmpty()) {
                s.saveProfile(fresh);
                return fresh;
            }
        }
        Profile p = existing.get();
        if (!p.ownerAccount().isEmpty() && !account.isEmpty() && !p.ownerAccount().equals(account)) {
            Profile reset = newInitialProfile(playerId, account, revision, true, nowMs);
            s.saveProfile(reset);
            return reset;
        }
        return p;
    }

    private static Profile newInitialProfile(
            long playerId, String account, long revision, boolean changed, long nowMs) {
        return new Profile(
                playerId,
                account,
                revision,
                changed,
                changed ? nowMs : 0L,
                nowMs,
                0L,
                0L,
                0L,
                0L,
                0L,
                false,
                0L,
                0L,
                "",
                "",
                List.of(),
                DEFAULT_INITIAL_SCORE,
                DEFAULT_INITIAL_STAR_TENTHS,
                0,
                RiskTier.NEW_OR_TRANSFERRED);
    }

    private static Profile withCounters(
            Profile p, long completed, long positive, long neutral, long negative, long violations) {
        return new Profile(
                p.sellerPlayerId(),
                p.ownerAccount(),
                p.ownerRevision(),
                p.ownershipChanged(),
                p.ownershipChangedAtMs(),
                p.epochStartedAtMs(),
                completed,
                positive,
                neutral,
                negative,
                violations,
                p.envCreditCleared(),
                p.envResetCount(),
                p.lastEnvChangedAtMs(),
                p.lastIp(),
                p.lastDeviceId(),
                p.knownDevices(),
                p.creditScore(),
                p.starRatingTenths(),
                p.positiveRateBps(),
                p.riskTier());
    }

    private static Profile recompute(Profile p) {
        long baseScore = p.envCreditCleared() ? 0L : DEFAULT_INITIAL_SCORE;
        long score = baseScore
                + p.positiveReviews() * POSITIVE_DELTA
                - p.negativeReviews() * NEGATIVE_PENALTY
                - p.violationCount() * VIOLATION_PENALTY;
        if (score < 0) {
            score = 0;
        }
        if (score > 100) {
            score = 100;
        }
        int clampedScore = (int) score;

        long totalRated = p.positiveReviews() + p.neutralReviews() + p.negativeReviews();
        int positiveRateBps = totalRated > 0 ? (int) ((p.positiveReviews() * 10_000L) / totalRated) : 0;

        int starTenths = (!p.envCreditCleared() && totalRated == 0 && p.violationCount() == 0)
                ? DEFAULT_INITIAL_STAR_TENTHS
                : 10 + (clampedScore * 40 + 50) / 100;

        RiskTier tier;
        if (p.violationCount() > 0 || clampedScore < 40) {
            tier = RiskTier.RESTRICTED;
        } else if (p.completedOrders() < 5
                || (p.ownershipChanged() && p.completedOrders() < 10)
                || (p.envCreditCleared() && p.completedOrders() < 10)) {
            tier = RiskTier.NEW_OR_TRANSFERRED;
        } else if (p.completedOrders() >= 20 && clampedScore >= 85 && positiveRateBps >= 9500) {
            tier = RiskTier.TRUSTED;
        } else {
            tier = RiskTier.STANDARD;
        }

        return new Profile(
                p.sellerPlayerId(),
                p.ownerAccount(),
                p.ownerRevision(),
                p.ownershipChanged(),
                p.ownershipChangedAtMs(),
                p.epochStartedAtMs(),
                p.completedOrders(),
                p.positiveReviews(),
                p.neutralReviews(),
                p.negativeReviews(),
                p.violationCount(),
                p.envCreditCleared(),
                p.envResetCount(),
                p.lastEnvChangedAtMs(),
                p.lastIp(),
                p.lastDeviceId(),
                p.knownDevices(),
                clampedScore,
                starTenths,
                positiveRateBps,
                tier);
    }

    private static String normalizeEnvToken(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> rememberKnownDevice(List<String> existing, String device) {
        if (device.isEmpty() || existing.contains(device)) {
            return existing;
        }
        var out = new ArrayList<String>(existing.size() + 1);
        out.addAll(existing);
        out.add(device);
        if (out.size() > MAX_KNOWN_SELLER_DEVICES) {
            return List.copyOf(out.subList(out.size() - MAX_KNOWN_SELLER_DEVICES, out.size()));
        }
        return List.copyOf(out);
    }

    /** 线程安全的内存存储实现，供单元测试与隔离原型演练使用。 */
    public static final class MemoryStore implements Store {
        private final Map<CreditKey, Profile> profiles = new HashMap<>();
        private final Map<String, CreditKey> completedOrders = new HashMap<>();
        private final Map<String, ReviewRecord> reviewsByOrder = new HashMap<>();
        private final List<ReviewRecord> reviews = new ArrayList<>();
        private final Map<String, ViolationRecord> violations = new HashMap<>();

        @Override
        public synchronized <T> T inTransaction(CreditKey lockKey, StoreWork<T> work) throws SQLException {
            return work.run(this);
        }

        @Override
        public synchronized Optional<Profile> getProfile(CreditKey key) {
            return Optional.ofNullable(profiles.get(key));
        }

        @Override
        public synchronized boolean hasPriorEpoch(long sellerPlayerId, long currentRevision) {
            for (var k : profiles.keySet()) {
                if (k.sellerPlayerId() == sellerPlayerId
                        && Long.compareUnsigned(k.ownerRevision(), currentRevision) < 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public synchronized void saveProfile(Profile profile) {
            profiles.put(new CreditKey(profile.sellerPlayerId(), profile.ownerRevision()), profile);
        }

        @Override
        public synchronized boolean markOrderCompleted(String orderId, CreditKey key) {
            if (completedOrders.containsKey(orderId)) {
                return false;
            }
            completedOrders.put(orderId, key);
            return true;
        }

        @Override
        public synchronized Optional<ReviewRecord> getReviewByOrder(String orderId) {
            return Optional.ofNullable(reviewsByOrder.get(orderId));
        }

        @Override
        public synchronized int countPairScoredReviews(
                CreditKey key,
                String buyerKey,
                String buyerIp,
                String buyerDeviceId,
                long sinceMs,
                long nowMs) {
            int count = 0;
            for (var r : reviews) {
                if (r.sellerPlayerId() != key.sellerPlayerId()
                        || r.sellerOwnerRevision() != key.ownerRevision()
                        || !r.countedForScore()
                        || Long.compareUnsigned(r.createdAtMs(), sinceMs) < 0
                        || Long.compareUnsigned(r.createdAtMs(), nowMs) > 0) {
                    continue;
                }
                boolean sameBuyer = r.buyerAccount().equals(buyerKey)
                        || (!buyerIp.isEmpty() && !r.buyerIp().isEmpty() && r.buyerIp().equals(buyerIp))
                        || (!buyerDeviceId.isEmpty() && !r.buyerDeviceId().isEmpty()
                                && r.buyerDeviceId().equals(buyerDeviceId));
                if (sameBuyer) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public synchronized void saveReview(ReviewRecord record) {
            reviewsByOrder.put(record.orderId(), record);
            reviews.add(record);
        }

        @Override
        public synchronized boolean hasViolation(String caseId) {
            return violations.containsKey(caseId);
        }

        @Override
        public synchronized void saveViolation(ViolationRecord record) {
            violations.put(record.caseId(), record);
        }
    }

    /** 构造对外脱敏视图：严禁包含卖家原始账号、IP 或设备指纹。 */
    public static SellerCreditView toView(Profile p) {
        return SellerCreditView.newBuilder()
                .setSellerPlayerId(p.sellerPlayerId())
                .setOwnerRevision(p.ownerRevision())
                .setOwnershipChanged(p.ownershipChanged())
                .setOwnershipChangedAtMs(p.ownershipChangedAtMs())
                .setEpochStartedAtMs(p.epochStartedAtMs())
                .setCompletedOrders(p.completedOrders())
                .setPositiveReviews(p.positiveReviews())
                .setNeutralReviews(p.neutralReviews())
                .setNegativeReviews(p.negativeReviews())
                .setViolationCount(p.violationCount())
                .setEnvCreditCleared(p.envCreditCleared())
                .setEnvResetCount(p.envResetCount())
                .setLastEnvChangedAtMs(p.lastEnvChangedAtMs())
                .setCreditScore(p.creditScore())
                .setStarRatingTenths(p.starRatingTenths())
                .setPositiveRateBps(p.positiveRateBps())
                .setRiskTier(p.riskTier().wireName())
                .build();
    }

    /** 直读权威 {@code player} 表的角色持有者账号与 {@code owner_revision}。 */
    public static final class SqlRoleOwnershipLookup implements RoleOwnershipLookup {
        private static final String QUERY = "SELECT account, owner_revision FROM player WHERE player_id=?";
        private final DataSource source;

        public SqlRoleOwnershipLookup(DataSource source) {
            this.source = Objects.requireNonNull(source, "角色归属数据源未装配");
        }

        @Override
        public RoleOwnership lookup(long playerId) throws SQLException {
            if (playerId == 0) {
                throw new CommerceRules.Refusal(INVALID);
            }
            try (Connection c = source.getConnection();
                    PreparedStatement stmt = c.prepareStatement(QUERY)) {
                stmt.setQueryTimeout(2);
                bindUnsignedLong(stmt, 1, playerId);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new CommerceRules.Refusal(MISSING);
                    }
                    String account = rs.getString(1);
                    String rawRev = rs.getString(2);
                    long revision = rawRev == null ? 0L : Long.parseUnsignedLong(rawRev);
                    return new RoleOwnership(account == null ? "" : account, revision);
                }
            }
        }
    }

    /**
     * 基于参数化 SQL 的卖家信用持久层（对齐 Go {@code seller_credit_sql.go}）；
     * 表结构由 {@code TradeTables} 经 game-pbmysql 同步，本类不含任何 DDL。
     */
    public static final class SqlStore implements Store {
        private static final String PROFILE_COLUMNS =
                "seller_player_id,owner_revision,owner_account,ownership_changed,ownership_changed_at_ms,"
                        + "epoch_started_at_ms,completed_orders,positive_reviews,neutral_reviews,negative_reviews,violation_count,"
                        + "env_credit_cleared,env_reset_count,last_env_changed_at_ms,last_ip,last_device_id,known_devices_json,"
                        + "credit_score,star_rating_tenths,positive_rate_bps,risk_tier,updated_ms";
        private static final String SELECT_PROFILE =
                "SELECT " + PROFILE_COLUMNS + " FROM trade_seller_credit WHERE seller_player_id=? AND owner_revision=?";
        private static final String SELECT_PROFILE_FOR_UPDATE = SELECT_PROFILE + " FOR UPDATE";
        private static final String SELECT_PRIOR_EPOCH =
                "SELECT 1 FROM trade_seller_credit WHERE seller_player_id=? AND owner_revision<? LIMIT 1";
        private static final String INSERT_PROFILE_IGNORE =
                "INSERT IGNORE INTO trade_seller_credit (" + PROFILE_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        private static final String UPSERT_PROFILE =
                "INSERT INTO trade_seller_credit (" + PROFILE_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE owner_account=VALUES(owner_account),ownership_changed=VALUES(ownership_changed),"
                        + "ownership_changed_at_ms=VALUES(ownership_changed_at_ms),epoch_started_at_ms=VALUES(epoch_started_at_ms),"
                        + "completed_orders=VALUES(completed_orders),positive_reviews=VALUES(positive_reviews),neutral_reviews=VALUES(neutral_reviews),"
                        + "negative_reviews=VALUES(negative_reviews),violation_count=VALUES(violation_count),env_credit_cleared=VALUES(env_credit_cleared),"
                        + "env_reset_count=VALUES(env_reset_count),last_env_changed_at_ms=VALUES(last_env_changed_at_ms),last_ip=VALUES(last_ip),"
                        + "last_device_id=VALUES(last_device_id),known_devices_json=VALUES(known_devices_json),credit_score=VALUES(credit_score),"
                        + "star_rating_tenths=VALUES(star_rating_tenths),positive_rate_bps=VALUES(positive_rate_bps),risk_tier=VALUES(risk_tier),updated_ms=VALUES(updated_ms)";
        private static final String INSERT_COMPLETED_ORDER =
                "INSERT IGNORE INTO trade_seller_completed_order (record_id,order_id,seller_player_id,owner_revision,created_ms) VALUES (?,?,?,?,?)";
        private static final String SELECT_REVIEW_BY_ORDER =
                "SELECT order_id,seller_player_id,seller_owner_revision,buyer_player_id,buyer_account,buyer_ip,buyer_device_id,"
                        + "rating,counted_for_score,comment,created_ms FROM trade_seller_review WHERE order_id=?";
        private static final String COUNT_PAIR_REVIEWS =
                "SELECT COUNT(*) FROM trade_seller_review "
                        + "WHERE seller_player_id=? AND seller_owner_revision=? AND counted_for_score=1 AND created_ms>=? AND created_ms<=? "
                        + "AND (buyer_account=? OR (?<>'' AND buyer_ip=?) OR (?<>'' AND buyer_device_id=?))";
        private static final String INSERT_REVIEW =
                "INSERT INTO trade_seller_review (review_id,order_id,seller_player_id,seller_owner_revision,buyer_player_id,"
                        + "buyer_account,buyer_ip,buyer_device_id,rating,counted_for_score,comment,created_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        private static final String SELECT_VIOLATION =
                "SELECT 1 FROM trade_seller_violation WHERE case_id=? LIMIT 1";
        private static final String INSERT_VIOLATION =
                "INSERT INTO trade_seller_violation (violation_id,case_id,order_id,seller_player_id,seller_owner_revision,"
                        + "seller_account,reason,recorded_ms) VALUES (?,?,?,?,?,?,?,?)";
        private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

        private final DataSource source;
        private final LongSupplier ids;
        private final LongSupplier clock;
        private final ObjectMapper json;

        public SqlStore(DataSource source, LongSupplier ids, LongSupplier clock, ObjectMapper json) {
            this.source = Objects.requireNonNull(source, "卖家信用 SQL 数据源未装配");
            this.ids = Objects.requireNonNull(ids, "卖家信用发号器未装配");
            this.clock = clock == null ? System::currentTimeMillis : clock;
            this.json = json == null ? new ObjectMapper() : json;
        }

        @Override
        public <T> T inTransaction(CreditKey lockKey, StoreWork<T> work) throws SQLException {
            try (Connection c = source.getConnection()) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                c.setAutoCommit(false);
                try {
                    T result = work.run(new BoundTxStore(c));
                    c.commit();
                    return result;
                } catch (SQLException | RuntimeException | Error failure) {
                    try {
                        c.rollback();
                    } catch (SQLException rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
            }
        }

        @Override
        public Optional<Profile> getProfile(CreditKey key) throws SQLException {
            try (Connection c = source.getConnection()) {
                return readProfile(c, SELECT_PROFILE, key);
            }
        }

        @Override
        public boolean hasPriorEpoch(long sellerPlayerId, long currentRevision) throws SQLException {
            try (Connection c = source.getConnection()) {
                return hasPriorEpoch(c, sellerPlayerId, currentRevision);
            }
        }

        @Override
        public void insertProfileIfAbsent(Profile p) throws SQLException {
            try (Connection c = source.getConnection()) {
                writeProfile(c, INSERT_PROFILE_IGNORE, p);
            }
        }

        @Override
        public void saveProfile(Profile p) throws SQLException {
            try (Connection c = source.getConnection()) {
                writeProfile(c, UPSERT_PROFILE, p);
            }
        }

        @Override
        public boolean markOrderCompleted(String orderId, CreditKey key) throws SQLException {
            try (Connection c = source.getConnection()) {
                return markOrderCompleted(c, orderId, key);
            }
        }

        @Override
        public Optional<ReviewRecord> getReviewByOrder(String orderId) throws SQLException {
            try (Connection c = source.getConnection()) {
                return getReviewByOrder(c, orderId);
            }
        }

        @Override
        public int countPairScoredReviews(
                CreditKey key,
                String buyerKey,
                String buyerIp,
                String buyerDeviceId,
                long sinceMs,
                long nowMs) throws SQLException {
            try (Connection c = source.getConnection()) {
                return countPairScoredReviews(c, key, buyerKey, buyerIp, buyerDeviceId, sinceMs, nowMs);
            }
        }

        @Override
        public void saveReview(ReviewRecord rec) throws SQLException {
            try (Connection c = source.getConnection()) {
                saveReview(c, rec);
            }
        }

        @Override
        public boolean hasViolation(String caseId) throws SQLException {
            try (Connection c = source.getConnection()) {
                return hasViolation(c, caseId);
            }
        }

        @Override
        public void saveViolation(ViolationRecord rec) throws SQLException {
            try (Connection c = source.getConnection()) {
                saveViolation(c, rec);
            }
        }

        private Optional<Profile> readProfile(Connection c, String sql, CreditKey key) throws SQLException {
            try (PreparedStatement stmt = c.prepareStatement(sql)) {
                stmt.setQueryTimeout(2);
                bindUnsignedLong(stmt, 1, key.sellerPlayerId());
                bindUnsignedLong(stmt, 2, key.ownerRevision());
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    List<String> known = List.of();
                    String knownJson = rs.getString(17);
                    if (knownJson != null && !knownJson.isEmpty()) {
                        try {
                            List<String> parsed = json.readValue(knownJson, STRING_LIST_TYPE);
                            if (parsed != null) {
                                known = parsed;
                            }
                        } catch (JsonProcessingException ignored) {
                            known = List.of();
                        }
                    }
                    return Optional.of(new Profile(
                            readUnsignedLong(rs, 1),
                            rs.getString(3),
                            readUnsignedLong(rs, 2),
                            rs.getBoolean(4),
                            readUnsignedLong(rs, 5),
                            readUnsignedLong(rs, 6),
                            readUnsignedLong(rs, 7),
                            readUnsignedLong(rs, 8),
                            readUnsignedLong(rs, 9),
                            readUnsignedLong(rs, 10),
                            readUnsignedLong(rs, 11),
                            rs.getBoolean(12),
                            readUnsignedLong(rs, 13),
                            readUnsignedLong(rs, 14),
                            rs.getString(15),
                            rs.getString(16),
                            known,
                            rs.getInt(18),
                            rs.getInt(19),
                            rs.getInt(20),
                            RiskTier.fromWire(rs.getString(21))));
                }
            }
        }

        private boolean hasPriorEpoch(Connection c, long sellerPlayerId, long currentRevision) throws SQLException {
            try (PreparedStatement stmt = c.prepareStatement(SELECT_PRIOR_EPOCH)) {
                stmt.setQueryTimeout(2);
                bindUnsignedLong(stmt, 1, sellerPlayerId);
                bindUnsignedLong(stmt, 2, currentRevision);
                try (var rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        }

        private void writeProfile(Connection c, String sql, Profile p) throws SQLException {
            String knownJson = "[]";
            if (!p.knownDevices().isEmpty()) {
                try {
                    knownJson = json.writeValueAsString(p.knownDevices());
                } catch (JsonProcessingException err) {
                    throw new SQLException("序列化常用设备指纹列表失败", err);
                }
            }
            try (PreparedStatement stmt = c.prepareStatement(sql)) {
                stmt.setQueryTimeout(2);
                bindUnsignedLong(stmt, 1, p.sellerPlayerId());
                bindUnsignedLong(stmt, 2, p.ownerRevision());
                stmt.setString(3, p.ownerAccount());
                stmt.setBoolean(4, p.ownershipChanged());
                bindUnsignedLong(stmt, 5, p.ownershipChangedAtMs());
                bindUnsignedLong(stmt, 6, p.epochStartedAtMs());
                bindUnsignedLong(stmt, 7, p.completedOrders());
                bindUnsignedLong(stmt, 8, p.positiveReviews());
                bindUnsignedLong(stmt, 9, p.neutralReviews());
                bindUnsignedLong(stmt, 10, p.negativeReviews());
                bindUnsignedLong(stmt, 11, p.violationCount());
                stmt.setBoolean(12, p.envCreditCleared());
                bindUnsignedLong(stmt, 13, p.envResetCount());
                bindUnsignedLong(stmt, 14, p.lastEnvChangedAtMs());
                stmt.setString(15, p.lastIp());
                stmt.setString(16, p.lastDeviceId());
                stmt.setString(17, knownJson);
                stmt.setInt(18, p.creditScore());
                stmt.setInt(19, p.starRatingTenths());
                stmt.setInt(20, p.positiveRateBps());
                stmt.setString(21, p.riskTier().wireName());
                bindUnsignedLong(stmt, 22, clock.getAsLong());
                stmt.executeUpdate();
            }
        }

        private boolean markOrderCompleted(Connection c, String orderId, CreditKey key) throws SQLException {
            long recordId = ids.getAsLong();
            try (PreparedStatement stmt = c.prepareStatement(INSERT_COMPLETED_ORDER)) {
                stmt.setQueryTimeout(2);
                bindUnsignedLong(stmt, 1, recordId);
                stmt.setString(2, orderId);
                bindUnsignedLong(stmt, 3, key.sellerPlayerId());
                bindUnsignedLong(stmt, 4, key.ownerRevision());
                bindUnsignedLong(stmt, 5, clock.getAsLong());
                return stmt.executeUpdate() == 1;
            }
        }

        private Optional<ReviewRecord> getReviewByOrder(Connection c, String orderId) throws SQLException {
            try (PreparedStatement stmt = c.prepareStatement(SELECT_REVIEW_BY_ORDER)) {
                stmt.setQueryTimeout(2);
                stmt.setString(1, orderId);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ReviewRecord(
                            rs.getString(1),
                            readUnsignedLong(rs, 2),
                            readUnsignedLong(rs, 3),
                            readUnsignedLong(rs, 4),
                            rs.getString(5),
                            rs.getString(6),
                            rs.getString(7),
                            Rating.fromCode(rs.getInt(8)),
                            rs.getBoolean(9),
                            rs.getString(10),
                            readUnsignedLong(rs, 11)));
                }
            }
        }

        private int countPairScoredReviews(
                Connection c,
                CreditKey key,
                String buyerKey,
                String buyerIp,
                String buyerDeviceId,
                long sinceMs,
                long nowMs) throws SQLException {
            String safeIp = buyerIp == null ? "" : buyerIp;
            String safeDev = buyerDeviceId == null ? "" : buyerDeviceId;
            try (PreparedStatement stmt = c.prepareStatement(COUNT_PAIR_REVIEWS)) {
                stmt.setQueryTimeout(2);
                bindUnsignedLong(stmt, 1, key.sellerPlayerId());
                bindUnsignedLong(stmt, 2, key.ownerRevision());
                bindUnsignedLong(stmt, 3, sinceMs);
                bindUnsignedLong(stmt, 4, nowMs);
                stmt.setString(5, buyerKey == null ? "" : buyerKey);
                stmt.setString(6, safeIp);
                stmt.setString(7, safeIp);
                stmt.setString(8, safeDev);
                stmt.setString(9, safeDev);
                try (var rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }

        private void saveReview(Connection c, ReviewRecord rec) throws SQLException {
            long reviewId = ids.getAsLong();
            try (PreparedStatement stmt = c.prepareStatement(INSERT_REVIEW)) {
                stmt.setQueryTimeout(2);
                bindUnsignedLong(stmt, 1, reviewId);
                stmt.setString(2, rec.orderId());
                bindUnsignedLong(stmt, 3, rec.sellerPlayerId());
                bindUnsignedLong(stmt, 4, rec.sellerOwnerRevision());
                bindUnsignedLong(stmt, 5, rec.buyerPlayerId());
                stmt.setString(6, rec.buyerAccount());
                stmt.setString(7, rec.buyerIp());
                stmt.setString(8, rec.buyerDeviceId());
                stmt.setInt(9, rec.rating().code());
                stmt.setBoolean(10, rec.countedForScore());
                stmt.setString(11, rec.comment());
                bindUnsignedLong(stmt, 12, rec.createdAtMs());
                stmt.executeUpdate();
            }
        }

        private boolean hasViolation(Connection c, String caseId) throws SQLException {
            try (PreparedStatement stmt = c.prepareStatement(SELECT_VIOLATION)) {
                stmt.setQueryTimeout(2);
                stmt.setString(1, caseId);
                try (var rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        }

        private void saveViolation(Connection c, ViolationRecord rec) throws SQLException {
            long violationId = ids.getAsLong();
            try (PreparedStatement stmt = c.prepareStatement(INSERT_VIOLATION)) {
                stmt.setQueryTimeout(2);
                bindUnsignedLong(stmt, 1, violationId);
                stmt.setString(2, rec.caseId());
                stmt.setString(3, rec.orderId() == null ? "" : rec.orderId());
                bindUnsignedLong(stmt, 4, rec.sellerPlayerId());
                bindUnsignedLong(stmt, 5, rec.sellerOwnerRevision());
                stmt.setString(6, rec.sellerAccount());
                stmt.setString(7, rec.reason() == null ? "" : rec.reason());
                bindUnsignedLong(stmt, 8, rec.recordedAtMs());
                stmt.executeUpdate();
            }
        }

        private final class BoundTxStore implements Store {
            private final Connection c;

            private BoundTxStore(Connection c) {
                this.c = c;
            }

            @Override
            public <T> T inTransaction(CreditKey lockKey, StoreWork<T> work) throws SQLException {
                return work.run(this);
            }

            @Override
            public Optional<Profile> getProfile(CreditKey key) throws SQLException {
                return readProfile(c, SELECT_PROFILE_FOR_UPDATE, key);
            }

            @Override
            public boolean hasPriorEpoch(long sellerPlayerId, long currentRevision) throws SQLException {
                return SqlStore.this.hasPriorEpoch(c, sellerPlayerId, currentRevision);
            }

            @Override
            public void insertProfileIfAbsent(Profile profile) throws SQLException {
                writeProfile(c, INSERT_PROFILE_IGNORE, profile);
            }

            @Override
            public void saveProfile(Profile profile) throws SQLException {
                writeProfile(c, UPSERT_PROFILE, profile);
            }

            @Override
            public boolean markOrderCompleted(String orderId, CreditKey key) throws SQLException {
                return SqlStore.this.markOrderCompleted(c, orderId, key);
            }

            @Override
            public Optional<ReviewRecord> getReviewByOrder(String orderId) throws SQLException {
                return SqlStore.this.getReviewByOrder(c, orderId);
            }

            @Override
            public int countPairScoredReviews(
                    CreditKey key,
                    String buyerKey,
                    String buyerIp,
                    String buyerDeviceId,
                    long sinceMs,
                    long nowMs) throws SQLException {
                return SqlStore.this.countPairScoredReviews(c, key, buyerKey, buyerIp, buyerDeviceId, sinceMs, nowMs);
            }

            @Override
            public void saveReview(ReviewRecord record) throws SQLException {
                SqlStore.this.saveReview(c, record);
            }

            @Override
            public boolean hasViolation(String caseId) throws SQLException {
                return SqlStore.this.hasViolation(c, caseId);
            }

            @Override
            public void saveViolation(ViolationRecord record) throws SQLException {
                SqlStore.this.saveViolation(c, record);
            }
        }
    }

    private static void bindUnsignedLong(PreparedStatement stmt, int idx, long value) throws SQLException {
        stmt.setObject(idx, value < 0 ? new BigInteger(Long.toUnsignedString(value)) : value);
    }

    private static long readUnsignedLong(java.sql.ResultSet rs, int idx) throws SQLException {
        String raw = rs.getString(idx);
        return raw == null ? 0L : Long.parseUnsignedLong(raw);
    }
}
