package com.game.payment.core;

import com.game.common.compliance.AntiAddictionRules;
import com.game.payment.PaymentMetrics;
import com.game.payment.PaymentTips;
import com.game.payment.channel.ChannelTypes;
import com.game.payment.channel.ChannelTypes.ChannelException;
import com.game.payment.channel.ChannelTypes.ClientParams;
import com.game.payment.channel.ChannelTypes.HistoryPollResult;
import com.game.payment.channel.ChannelTypes.Notification;
import com.game.payment.channel.ChannelTypes.Order;
import com.game.payment.channel.ChannelTypes.Payment;
import com.game.payment.channel.ChannelTypes.Prepared;
import com.game.payment.channel.ChannelTypes.QueryRequest;
import com.game.payment.channel.ChannelTypes.QueryResult;
import com.game.payment.channel.ChannelTypes.Receipt;
import com.game.payment.channel.ChannelTypes.Ref;
import com.game.payment.channel.ChannelTypes.RefundRequest;
import com.game.payment.channel.ChannelTypes.RefundResult;
import com.game.payment.channel.ChannelTypes.RefundState;
import com.game.payment.channel.ChannelTypes.Revocation;
import com.game.payment.channel.ChannelTypes.TradeState;
import com.game.payment.channel.PaymentChannel;
import com.game.payment.mall.MallClient;
import com.game.payment.mall.MallClient.MallClientException;
import com.game.payment.mall.MallClient.OrderView;
import com.game.payment.mall.MallClient.PaymentOutcome;
import com.game.payment.mall.MallClient.PaymentReport;
import com.game.payment.mall.MallClient.PendingRefund;
import com.game.payment.mall.MallClient.PendingRefundPage;
import com.game.payment.mall.MallClient.RefundClosure;
import com.game.payment.mall.MallClient.RefundReceipt;
import com.game.payment.mall.MallClient.RevisionReport;
import com.game.payment.store.PaymentKeys;
import com.game.payment.store.PaymentKeys.Key128;
import com.game.payment.store.PaymentRecords;
import com.game.payment.store.PaymentRecords.CursorClaim;
import com.game.payment.store.PaymentRecords.InsertOutcome;
import com.game.payment.store.PaymentRecords.IntentRecord;
import com.game.payment.store.PaymentRecords.MallSync;
import com.game.payment.store.PaymentRecords.RecordMinorPaymentOutcome;
import com.game.payment.store.PaymentRecords.RefundProgress;
import com.game.payment.store.PaymentRecords.ReserveOutcome;
import com.game.payment.store.PaymentRecords.SpendLimitWindow;
import com.game.payment.store.PaymentRecords.SpendRejection;
import com.game.payment.store.PaymentRecords.TxnRecord;
import com.game.payment.store.PaymentStore;
import com.game.proto.payment.PayChannel;
import com.game.proto.payment.PayIntentView;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 支付服务核心编排层（对齐 Go {@code payment/internal/core/{service,minor,workers}.go}）：
 * <ul>
 *   <li>客户端意图管理（列渠道 / 建意图含未成年人额度预占 / 查意图含限频主动查单 / 提交商店购买）；</li>
 *   <li>渠道回调落账、退款墓碑、修订翻转与防冲突核对（含 2026-11-20 {@code sameFact} 实付金额与币种校验）；</li>
 *   <li>5 个后台推进原语（查单关单、推进商城、执行退款、渠道收尾、历史游标补漏）。</li>
 * </ul>
 */
public final class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public static final String REVOCATION_TOMBSTONE_REASON =
            "revoked at the channel before payment notification arrived; not delivered";
    public static final String MINOR_LATE_OVER_LIMIT_REASON =
            "minor spending limit reached by a payment that arrived after its intent closed";

    private static final Duration HISTORY_OVERLAP = Duration.ofMinutes(5);
    private static final int REFUND_POLL_MAX_PAGES = 20;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 商品 SKU → 各按商品定价渠道的商品号与标题。 */
    public record ProductSpec(String title, Map<String, String> channelProductIds) {
        public ProductSpec {
            title = title == null ? "" : title;
            channelProductIds = channelProductIds == null ? Map.of() : Map.copyOf(channelProductIds);
        }
    }

    /** 测试交易放行策略（沙箱 / 许可测试账号）。 */
    public record TestPolicy(boolean accept, Set<Long> players) {
        public TestPolicy {
            players = players == null ? Set.of() : Set.copyOf(players);
        }
    }

    /** 服务运行参数（均有合理默认值）。 */
    public record Options(
            Map<String, List<PayChannel>> platforms,
            Map<Long, ProductSpec> products,
            Map<String, TestPolicy> testPolicies,
            Duration minPayWindow,
            Duration receiptWindow,
            Duration firstCheckDelay,
            Duration checkInterval,
            Duration closeGrace,
            Duration giveUpAfter,
            Duration activeQueryInterval,
            Duration lease,
            Duration mallBackoffMax,
            Duration refundBackoffMax,
            Duration historyLookback,
            int batch,
            String refundReason,
            boolean realNameGateEnabled) {

        public static Options defaults(Map<String, List<PayChannel>> platforms, Map<Long, ProductSpec> products,
                                       Map<String, TestPolicy> testPolicies, boolean realNameGateEnabled) {
            return new Options(
                    platforms == null ? Map.of() : Map.copyOf(platforms),
                    products == null ? Map.of() : Map.copyOf(products),
                    testPolicies == null ? Map.of() : Map.copyOf(testPolicies),
                    Duration.ofSeconds(30),
                    Duration.ofHours(72),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(2),
                    Duration.ofHours(24),
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(10),
                    Duration.ofHours(24),
                    64,
                    "订单已取消或异常付款",
                    realNameGateEnabled);
        }
    }

    /** 客户端建意图请求。 */
    public record CreateRequest(long playerId, String orderId, PayChannel channel, String platform, String loginCode, String deviceOs) {}

    /** 客户端建意图结果（tip == 0 表示成功）。 */
    public record CreateResult(int tip, PayIntentView intent, ClientParams params) {
        public static CreateResult err(int tip, PayIntentView intent) { return new CreateResult(tip, intent, ClientParams.EMPTY); }
        public static CreateResult ok(PayIntentView intent, ClientParams params) { return new CreateResult(PaymentTips.OK, intent, params); }
    }

    /** 客户端查意图结果。 */
    public record GetResult(int tip, PayIntentView intent) {}

    /** 客户端提交商店购买请求。 */
    public record SubmitRequest(long playerId, PayChannel channel, String orderId, String transactionId, String purchaseToken, String productId) {}

    /** 客户端提交商店购买结果。 */
    public record SubmitResult(int tip, PayIntentView intent, boolean finish) {}

    /** 事实冲突异常（同交易号重复上报但订单/金额/币种不同）。 */
    public static final class FactConflictException extends Exception {
        public FactConflictException(String message) { super(message); }
    }

    private final PaymentStore store;
    private final MallClient mall;
    private final RealNameReader realNameReader;
    private final Map<PayChannel, PaymentChannel> channels = new EnumMap<>(PayChannel.class);
    private final Map<String, PaymentChannel> channelsByName = new HashMap<>();
    private final Options opts;
    private final PaymentMetrics metrics;
    private final Clock clock;
    private volatile Runnable kickCallback = () -> {};

    public PaymentService(PaymentStore store, MallClient mall, RealNameReader realNameReader,
                          List<PaymentChannel> channelList, Options opts, PaymentMetrics metrics, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.mall = Objects.requireNonNull(mall, "mall");
        this.realNameReader = realNameReader;
        this.opts = Objects.requireNonNull(opts, "opts");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        for (PaymentChannel ch : channelList) {
            this.channels.put(ch.id(), ch);
            this.channelsByName.put(ch.name(), ch);
        }
    }

    public void setKickCallback(Runnable kick) {
        this.kickCallback = kick == null ? () -> {} : kick;
    }

    public Clock clock() {
        return clock;
    }

    public PaymentChannel channelByName(String name) {
        return channelsByName.get(name);
    }

    // ---------------------------------------------------------------------------
    // 客户端入口（396–399）
    // ---------------------------------------------------------------------------

    public List<PayChannel> listChannels(String platform) {
        if (platform == null || platform.isEmpty() || !opts.platforms().containsKey(platform)) {
            return null;
        }
        List<PayChannel> out = new ArrayList<>();
        for (PayChannel id : opts.platforms().get(platform)) {
            if (channels.containsKey(id)) {
                out.add(id);
            }
        }
        return out;
    }

    public CreateResult createIntent(CreateRequest in) {
        Key128 key = PaymentKeys.orderKey(in.orderId());
        if (in.playerId() == 0L || key == null || in.channel() == null
                || in.channel() == PayChannel.PAY_CHANNEL_UNSPECIFIED) {
            return CreateResult.err(PaymentTips.INVALID_PARAMETER, null);
        }
        if (in.platform() == null || in.platform().isEmpty() || !opts.platforms().containsKey(in.platform())) {
            return CreateResult.err(PaymentTips.INVALID_PARAMETER, null);
        }
        if (!opts.platforms().get(in.platform()).contains(in.channel())) {
            return CreateResult.err(PaymentTips.CHANNEL_UNAVAILABLE, null);
        }
        PaymentChannel ch = channels.get(in.channel());
        if (ch == null) {
            return CreateResult.err(PaymentTips.CHANNEL_UNAVAILABLE, null);
        }
        Instant now = clock.instant();
        IntentRecord intent = store.findIntent(key.hi(), key.lo());
        if (intent == null) {
            OpenIntentResult opened = openIntent(in.playerId(), in.orderId(), key, ch, now);
            if (opened.tip() != PaymentTips.OK) {
                return CreateResult.err(opened.tip(), null);
            }
            intent = opened.intent();
        }
        if (intent.playerId() != in.playerId()) {
            return CreateResult.err(PaymentTips.ORDER_NOT_FOUND, null);
        }
        PayIntentView view = intent.toView();
        if (intent.channel() != in.channel().getNumber()) {
            return CreateResult.err(PaymentTips.CHANNEL_MISMATCH, view);
        }
        if (intent.state() == PaymentRecords.INTENT_PAID) {
            return CreateResult.ok(view, ClientParams.EMPTY);
        }
        if (intent.state() == PaymentRecords.INTENT_CLOSED) {
            return CreateResult.err(PaymentTips.ORDER_EXPIRED, view);
        }
        if (!isReceiptChannel(ch) && now.plus(opts.minPayWindow()).toEpochMilli() >= intent.expireMs()) {
            return CreateResult.err(PaymentTips.ORDER_EXPIRED, view);
        }
        String product = productFor(intent.skuId(), ch.name());
        if (ch.needsProduct() && product.isEmpty()) {
            return CreateResult.err(PaymentTips.CHANNEL_UNAVAILABLE, view);
        }
        try {
            Prepared prepared = prepareWithChannel(ch, intent, product, in, now);
            metrics.countIntent(ch.name(), "prepared");
            return CreateResult.ok(view, prepared.params());
        } catch (ChannelException e) {
            if (e.kind() == ChannelException.Kind.ORDER_PAID) {
                IntentRecord refreshed = activeQuery(intent, true);
                return CreateResult.ok(refreshed.toView(), ClientParams.EMPTY);
            }
            if (e.kind() == ChannelException.Kind.LOGIN_REQUIRED) {
                return CreateResult.err(PaymentTips.LOGIN_REQUIRED, view);
            }
            log.error("[payment] prepare channel={} order={} failed: {}", ch.name(), in.orderId(), e.getMessage());
            return CreateResult.err(PaymentTips.BUSY, view);
        }
    }

    private record OpenIntentResult(int tip, IntentRecord intent) {}

    private OpenIntentResult openIntent(long playerId, String orderId, Key128 key, PaymentChannel ch, Instant now) {
        OrderView order;
        try {
            order = mall.orderView(orderId);
        } catch (MallClientException e) {
            if (e.kind() == MallClientException.Kind.NOT_FOUND || e.kind() == MallClientException.Kind.INVALID) {
                return new OpenIntentResult(PaymentTips.ORDER_NOT_FOUND, null);
            }
            log.error("[payment] reading mall order view failed: {}", e.getMessage());
            return new OpenIntentResult(PaymentTips.BUSY, null);
        }
        boolean receipt = isReceiptChannel(ch);
        if (!Long.toUnsignedString(playerId).equals(order.buyerId())) {
            return new OpenIntentResult(PaymentTips.ORDER_NOT_FOUND, null);
        }
        if (!MallClient.ORDER_STATE_AWAITING_PAYMENT.equals(order.state()) || order.amountMinor() <= 0) {
            return new OpenIntentResult(PaymentTips.ORDER_NOT_PAYABLE, null);
        }
        if (!ch.supports(order.currency())) {
            return new OpenIntentResult(PaymentTips.CURRENCY_UNSUPPORTED, null);
        }
        if (ch.needsProduct() && productFor(order.skuId(), ch.name()).isEmpty()) {
            return new OpenIntentResult(PaymentTips.CHANNEL_UNAVAILABLE, null);
        }
        if (!receipt && order.paymentDeadlineMs() <= now.plus(opts.minPayWindow()).toEpochMilli()) {
            return new OpenIntentResult(PaymentTips.ORDER_EXPIRED, null);
        }
        long expire = receipt ? order.paymentDeadlineMs() + opts.receiptWindow().toMillis() : order.paymentDeadlineMs();
        long nowMs = now.toEpochMilli();
        IntentRecord rec = new IntentRecord(
                key.hi(), key.lo(), orderId, playerId, ch.id().getNumber(),
                order.amountMinor(), order.currency(), PaymentRecords.INTENT_CREATED,
                expire, now.plus(opts.firstCheckDelay()).toEpochMilli(), 0, 0L,
                nowMs, nowMs, "", 0L, order.skuId(), "", 0L, 0L, 0L, 0);

        // 未成年人实名限额合规闸（对齐 Go minor.go）
        GateResult gated = gateBuyer(rec, ch.id(), now);
        if (gated.tip() != PaymentTips.OK) {
            return new OpenIntentResult(gated.tip(), null);
        }
        rec = gated.intent();

        IntentRecord stored;
        boolean inserted;
        if (rec.isMinor()) {
            SpendLimitWindow window = spendWindow(rec.minorAge(), now);
            ReserveOutcome res = store.reserveIntent(rec, window);
            if (res.rejection() == SpendRejection.MONTHLY_LIMIT) {
                metrics.countMinorGate("monthly_limit");
                return new OpenIntentResult(PaymentTips.MINOR_MONTHLY_LIMIT, null);
            }
            if (res.rejection() == SpendRejection.DAILY_LIMIT) {
                metrics.countMinorGate("daily_limit");
                return new OpenIntentResult(PaymentTips.MINOR_DAILY_LIMIT, null);
            }
            stored = res.record();
            inserted = res.inserted();
        } else {
            InsertOutcome<IntentRecord> res = store.insertIntent(rec);
            stored = res.record();
            inserted = res.inserted();
        }
        if (inserted) {
            metrics.countIntent(ch.name(), "created");
        }
        return new OpenIntentResult(PaymentTips.OK, stored);
    }

    private record GateResult(int tip, IntentRecord intent) {}

    private GateResult gateBuyer(IntentRecord rec, PayChannel channel, Instant now) {
        if (!opts.realNameGateEnabled()) {
            return new GateResult(PaymentTips.OK, rec);
        }
        boolean platformManaged = isPlatformManagedMiniGame(channel);
        if (realNameReader == null) {
            if (platformManaged) {
                return new GateResult(PaymentTips.OK, rec);
            }
            metrics.countMinorGate("realname_unavailable");
            return new GateResult(PaymentTips.BUSY, rec);
        }
        RealNameReader.RealNameStatus status;
        try {
            status = realNameReader.lookupByPlayer(rec.playerId());
        } catch (RuntimeException e) {
            metrics.countMinorGate("realname_unavailable");
            log.error("[payment] realname lookup failed player={}: {}", rec.playerId(), e.getMessage());
            return new GateResult(PaymentTips.BUSY, rec);
        }
        if (status == null || !status.verified() || status.pi() == null || status.pi().isEmpty()) {
            if (platformManaged) {
                metrics.countMinorGate("platform_managed");
                return new GateResult(PaymentTips.OK, rec);
            }
            metrics.countMinorGate("unverified");
            return new GateResult(PaymentTips.REAL_NAME_REQUIRED, rec);
        }
        LocalDate birth = AntiAddictionRules.birthDate(status.pi());
        if (birth == null) {
            metrics.countMinorGate("bad_pi");
            return new GateResult(PaymentTips.REAL_NAME_REQUIRED, rec);
        }
        int age = AntiAddictionRules.ageAt(birth, now);
        AntiAddictionRules.SpendLimit limit = AntiAddictionRules.spendLimitAt(age);
        if (limit.unlimited()) {
            metrics.countMinorGate("adult");
            return new GateResult(PaymentTips.OK, rec);
        }
        if (limit.singleFen() == 0L) {
            metrics.countMinorGate("under_8");
            return new GateResult(PaymentTips.MINOR_NOT_ALLOWED, rec);
        }
        if (!"CNY".equals(rec.currency())) {
            metrics.countMinorGate("non_cny_minor");
            return new GateResult(PaymentTips.CURRENCY_UNSUPPORTED, rec);
        }
        if (rec.amountMinor() > limit.singleFen()) {
            metrics.countMinorGate("single_limit");
            return new GateResult(PaymentTips.MINOR_SINGLE_LIMIT, rec);
        }
        Key128 spender = PaymentKeys.spenderKey(status.pi());
        return new GateResult(PaymentTips.OK, rec.withSpender(spender.hi(), spender.lo(), age));
    }

    private static boolean isPlatformManagedMiniGame(PayChannel ch) {
        return ch == PayChannel.PAY_CHANNEL_WECHAT_MINIGAME || ch == PayChannel.PAY_CHANNEL_DOUYIN_MINIGAME;
    }

    private static SpendLimitWindow spendWindow(int minorAge, Instant now) {
        AntiAddictionRules.SpendLimit lim = AntiAddictionRules.spendLimitAt(minorAge);
        return new SpendLimitWindow(lim.monthlyFen(), lim.dailyFen(),
                AntiAddictionRules.monthStartMs(now), AntiAddictionRules.dayStartMs(now));
    }

    private Prepared prepareWithChannel(PaymentChannel ch, IntentRecord intent, String product, CreateRequest in, Instant now)
            throws ChannelException {
        String outTradeNo = PaymentKeys.outTradeNo(intent.orderId());
        Ref cached = intent.channelRef().isEmpty() ? Ref.EMPTY
                : new Ref(intent.channelRef(), intent.channelRefMs() > 0 ? Instant.ofEpochMilli(intent.channelRefMs()) : null);
        Order order = new Order(intent.orderId(), outTradeNo, intent.playerId(), intent.amountMinor(),
                intent.currency(), Instant.ofEpochMilli(intent.expireMs()), product,
                titleFor(intent.skuId()), in.loginCode(), in.deviceOs());
        Prepared prepared;
        try {
            prepared = ch.prepare(order, cached);
            metrics.observeChannelCall(ch.name(), "prepare", null);
        } catch (ChannelException e) {
            metrics.observeChannelCall(ch.name(), "prepare", e);
            throw e;
        }
        if (prepared.ref().isPresent() || (prepared.payerId() != null && !prepared.payerId().isEmpty())) {
            String ref = prepared.ref().isPresent() ? prepared.ref().value() : intent.channelRef();
            long refMs = prepared.ref().at() != null ? prepared.ref().at().toEpochMilli() : intent.channelRefMs();
            String payer = (prepared.payerId() != null && !prepared.payerId().isEmpty()) ? prepared.payerId() : intent.payerId();
            store.setChannelRef(intent.orderHi(), intent.orderLo(), ref, refMs, payer, now.toEpochMilli());
        }
        return prepared;
    }

    public GetResult getIntent(long playerId, String orderId) {
        Key128 key = PaymentKeys.orderKey(orderId);
        if (playerId == 0L || key == null) {
            return new GetResult(PaymentTips.INVALID_PARAMETER, null);
        }
        IntentRecord intent = store.findIntent(key.hi(), key.lo());
        if (intent == null || intent.playerId() != playerId) {
            return new GetResult(PaymentTips.ORDER_NOT_FOUND, null);
        }
        if (intent.state() == PaymentRecords.INTENT_CREATED) {
            intent = activeQuery(intent, false);
        }
        return new GetResult(PaymentTips.OK, intent.toView());
    }

    private IntentRecord activeQuery(IntentRecord intent, boolean force) {
        PayChannel chId = PayChannel.forNumber(intent.channel());
        PaymentChannel ch = chId == null ? null : channels.get(chId);
        if (ch == null || isReceiptChannel(ch)) {
            return intent;
        }
        long nowMs = clock.millis();
        if (!force) {
            if (nowMs < intent.checkedMs() + opts.activeQueryInterval().toMillis()) {
                return intent;
            }
            if (!store.claimActiveQuery(intent, nowMs)) {
                return intent;
            }
        }
        QueryResult result;
        try {
            result = queryChannel(ch, intent);
        } catch (ChannelException e) {
            return intent;
        }
        if (result.payment() == null
                || (result.state() != TradeState.PAID && result.state() != TradeState.REFUNDED_OUTSIDE)) {
            return intent;
        }
        try {
            recordPaymentInternal(ch, result.payment(), result.state() == TradeState.REFUNDED_OUTSIDE);
        } catch (FactConflictException e) {
            return intent;
        }
        IntentRecord refreshed = store.findIntent(intent.orderHi(), intent.orderLo());
        return refreshed != null ? refreshed : intent;
    }

    public SubmitResult submitPurchase(SubmitRequest in) {
        if (in.playerId() == 0L
                || (in.transactionId() != null && in.transactionId().length() > 64)
                || (in.purchaseToken() != null && in.purchaseToken().length() > 512)
                || (in.productId() != null && in.productId().length() > 128)) {
            return new SubmitResult(PaymentTips.INVALID_PARAMETER, null, false);
        }
        PaymentChannel ch = in.channel() == null ? null : channels.get(in.channel());
        if (ch == null) {
            return new SubmitResult(PaymentTips.CHANNEL_UNAVAILABLE, null, false);
        }
        if (!(ch instanceof PaymentChannel.ReceiptChannel rc)
                || (isBlank(in.transactionId()) && isBlank(in.purchaseToken()))) {
            return new SubmitResult(PaymentTips.INVALID_PARAMETER, null, false);
        }
        QueryResult res;
        try {
            res = rc.verify(new Receipt(nullToEmpty(in.transactionId()), nullToEmpty(in.purchaseToken()), nullToEmpty(in.productId())));
            metrics.observeChannelCall(ch.name(), "verify", null);
        } catch (ChannelException e) {
            metrics.observeChannelCall(ch.name(), "verify", e);
            log.error("[payment] verify {} purchase failed: {}", ch.name(), e.getMessage());
            return new SubmitResult(PaymentTips.BUSY, ownedView(in.playerId(), in.orderId()), false);
        }
        String orderId = in.orderId();
        boolean finish = true;
        switch (res.state()) {
            case PAID, REFUNDED_OUTSIDE -> {
                Payment p = res.payment();
                if (!p.orderId().isEmpty() && !p.orderId().equals(orderId)) {
                    log.info("[payment] submitted {} purchase bound to order={} instead of client order={}",
                            ch.name(), p.orderId(), orderId);
                }
                try {
                    TxnRecord stored = recordPaymentInternal(ch, p, res.state() == TradeState.REFUNDED_OUTSIDE);
                    if (ch.id() == PayChannel.PAY_CHANNEL_GOOGLE) {
                        finish = stored != null && (stored.mallState() == PaymentRecords.MALL_PENDING
                                || stored.mallState() == PaymentRecords.MALL_BOUND
                                || stored.mallState() == PaymentRecords.MALL_REFUND_QUEUED);
                    }
                    if (res.state() == TradeState.PAID && !p.orderId().isEmpty()) {
                        orderId = p.orderId();
                    }
                } catch (FactConflictException e) {
                    finish = ch.id() != PayChannel.PAY_CHANNEL_GOOGLE;
                }
            }
            case NOT_PAID -> finish = false;
            case CLOSED -> {
                if (res.payment() != null) {
                    try {
                        revoke(ch, new Revocation(res.payment().transactionId(), "", "", true, clock.instant(), "verify", null));
                    } catch (FactConflictException ignored) {
                        // ignore conflict on verify closed
                    }
                }
            }
        }
        return new SubmitResult(PaymentTips.OK, ownedView(in.playerId(), orderId), finish);
    }

    private PayIntentView ownedView(long playerId, String orderId) {
        Key128 key = PaymentKeys.orderKey(orderId);
        if (key == null) return null;
        IntentRecord intent = store.findIntent(key.hi(), key.lo());
        return (intent != null && intent.playerId() == playerId) ? intent.toView() : null;
    }

    // ---------------------------------------------------------------------------
    // 渠道回调与付款事实记账 / 撤销翻转
    // ---------------------------------------------------------------------------

    public void handleNotification(PaymentChannel ch, Notification note) throws ChannelException {
        if (note.payment() != null) {
            try {
                recordPaymentInternal(ch, note.payment(), false);
            } catch (FactConflictException ignored) {
                // 冲突不让渠道无限重投，已记账告警
            }
        }
        for (Revocation rev : note.revocations()) {
            try {
                revoke(ch, rev);
            } catch (FactConflictException ignored) {
                // 冲突不重试
            }
        }
        if (ch instanceof PaymentChannel.ReceiptChannel rc) {
            for (Receipt receipt : note.receipts()) {
                QueryResult res;
                try {
                    res = rc.verify(receipt);
                    metrics.observeChannelCall(ch.name(), "verify", null);
                } catch (ChannelException e) {
                    metrics.observeChannelCall(ch.name(), "verify", e);
                    throw e;
                }
                if (res.state() == TradeState.PAID || res.state() == TradeState.REFUNDED_OUTSIDE) {
                    try {
                        recordPaymentInternal(ch, res.payment(), res.state() == TradeState.REFUNDED_OUTSIDE);
                    } catch (FactConflictException ignored) {
                        // already logged
                    }
                } else if (res.state() == TradeState.CLOSED && res.payment() != null) {
                    try {
                        revoke(ch, new Revocation(res.payment().transactionId(), "", "", true, clock.instant(), "recheck", null));
                    } catch (FactConflictException ignored) {
                        // already logged
                    }
                }
            }
        }
    }

    public TxnRecord recordPayment(PaymentChannel ch, Payment payment) throws FactConflictException {
        return recordPaymentInternal(ch, payment, false);
    }

    TxnRecord recordPaymentInternal(PaymentChannel ch, Payment payment, boolean refundedOutside) throws FactConflictException {
        Instant now = clock.instant();
        long nowMs = now.toEpochMilli();
        Instant paidAt = payment.paidAt() == null ? now : payment.paidAt();
        long paidAtMs = paidAt.toEpochMilli();
        Key128 txnKey = PaymentKeys.txnKey(payment.transactionId());

        String orderId = !isBlank(payment.orderId()) ? payment.orderId()
                : PaymentKeys.orderIdFromOutTradeNo(payment.outTradeNo());
        String outTradeNo = !isBlank(payment.outTradeNo()) ? payment.outTradeNo()
                : (orderId != null ? PaymentKeys.outTradeNo(orderId) : "");
        if (outTradeNo == null) outTradeNo = "";

        long orderHi = 0L;
        long orderLo = 0L;
        long playerId = 0L;
        IntentRecord intent = null;
        int mallState = PaymentRecords.MALL_PENDING;
        int mallDue = 1;
        String mallLastError = "";

        Key128 orderKey = PaymentKeys.orderKey(orderId);
        if (!PaymentKeys.validTransactionId(payment.transactionId())) {
            mallState = PaymentRecords.MALL_ORPHAN;
            mallDue = 0;
            mallLastError = "transaction id cannot be reported to the mall";
        } else if (orderKey == null) {
            mallState = PaymentRecords.MALL_ORPHAN;
            mallDue = 0;
            mallLastError = "the payment is not bound to an order issued by this service";
        } else {
            orderHi = orderKey.hi();
            orderLo = orderKey.lo();
            intent = store.findIntent(orderHi, orderLo);
            if (intent != null) {
                playerId = intent.playerId();
            }
        }

        long paidAmount = payment.amountMinor();
        String paidCurrency = nullToEmpty(payment.currency());
        if (mallState == PaymentRecords.MALL_PENDING) {
            ScreenResult screened = screen(ch, payment, intent);
            paidAmount = screened.paidAmount();
            paidCurrency = screened.paidCurrency();
            if (!screened.orphanReason().isEmpty()) {
                mallState = PaymentRecords.MALL_ORPHAN;
                mallDue = 0;
                mallLastError = screened.orphanReason();
            }
        }
        int factRevision = 0;
        if (refundedOutside) {
            factRevision = 1;
            mallState = PaymentRecords.MALL_ORPHAN;
            mallDue = 0;
            if (mallLastError.isEmpty()) {
                mallLastError = "refunded at the channel before this service recorded it; not delivered";
            }
        }
        int finalizeState = (payment.needsFinalize() && mallState == PaymentRecords.MALL_PENDING)
                ? PaymentRecords.FINALIZE_PENDING : PaymentRecords.FINALIZE_NONE;
        long finalizeNextMs = finalizeState == PaymentRecords.FINALIZE_PENDING ? nowMs : 0L;

        TxnRecord rec = new TxnRecord(
                ch.id().getNumber(), txnKey.hi(), txnKey.lo(), nullToEmpty(payment.transactionId()),
                orderHi, orderLo, outTradeNo, playerId, paidAmount, paidCurrency, paidAtMs,
                mallState, "", 0, nowMs, mallLastError,
                PaymentRecords.REFUND_NONE, "", "", 0L, 0, 0L, "",
                nowMs, nowMs, factRevision, paidAtMs, 0, mallDue,
                nullToEmpty(payment.channelToken()), nullToEmpty(payment.channelOrderId()),
                payment.storeAmountMinor(), nullToEmpty(payment.storeCurrency()),
                finalizeState, finalizeNextMs, nullToEmpty(payment.productId()));

        TxnRecord stored;
        boolean inserted;
        boolean intentSettled = false;

        if (intent != null && intent.isMinor() && countsForIntent(rec, intent) && !refundedOutside) {
            long effectivePaidMs = Math.max(nowMs, intent.createdMs());
            SpendLimitWindow limit = spendWindow(intent.minorAge(), Instant.ofEpochMilli(effectivePaidMs));
            RecordMinorPaymentOutcome out = store.recordMinorPaymentTxn(
                    rec, intent, limit, effectivePaidMs, nowMs, MINOR_LATE_OVER_LIMIT_REASON);
            stored = out.stored();
            inserted = out.inserted();
            if (inserted) {
                intentSettled = true;
                if (out.wasClosed()) {
                    metrics.countMinorGate(out.overLimit() ? "late_payment_over_limit" : "late_payment_admitted");
                }
            }
        } else {
            InsertOutcome<TxnRecord> out = store.insertTxn(rec);
            stored = out.record();
            inserted = out.inserted();
        }

        // 退款墓碑拦截迟到付款
        if (!inserted && stored.factRevision() > 0 && PaymentRecords.isRevoked(stored.factRevision())
                && REVOCATION_TOMBSTONE_REASON.equals(stored.mallLastError())
                && stored.transactionId().equals(rec.transactionId())) {
            metrics.countFact(ch.name(), "duplicate");
            return stored;
        }
        if (!inserted && !sameFact(stored, rec)) {
            metrics.countFact(ch.name(), "conflict");
            log.error("[payment] channel={} reported txn=...{} again with conflicting amount/currency/binding",
                    ch.name(), tail(payment.transactionId()));
            throw new FactConflictException("payment fact conflict");
        }
        if (!intentSettled && (stored.orderHi() != 0L || stored.orderLo() != 0L)
                && (inserted || !PaymentRecords.isRevoked(stored.factRevision()))) {
            settleIntent(ch, stored, intent, inserted, refundedOutside, nowMs);
        }
        if (!inserted) {
            metrics.countFact(ch.name(), "duplicate");
        } else if (stored.mallState() == PaymentRecords.MALL_ORPHAN) {
            metrics.countFact(ch.name(), "orphan");
            log.error("[payment] manual handling needed channel={} txn=...{} order={} reason={}",
                    ch.name(), tail(payment.transactionId()), orderId, stored.mallLastError());
        } else {
            metrics.countFact(ch.name(), "recorded");
        }
        kickCallback.run();
        return stored;
    }

    /** 2026-11-20 审计加固：比对同一交易号两次报告，非商店渠道带实付金额/币种时必须一致，防篡改。 */
    public static boolean sameFact(TxnRecord a, TxnRecord b) {
        if (!Objects.equals(a.transactionId(), b.transactionId())
                || a.orderHi() != b.orderHi() || a.orderLo() != b.orderLo()) {
            return false;
        }
        if (a.storeAmountMinor() != 0L && b.storeAmountMinor() != 0L && a.storeAmountMinor() != b.storeAmountMinor()) {
            return false;
        }
        if (a.paidAmountMinor() != 0L && b.paidAmountMinor() != 0L && a.paidAmountMinor() != b.paidAmountMinor()) {
            return false;
        }
        if (!isBlank(a.paidCurrency()) && !isBlank(b.paidCurrency())
                && !a.paidCurrency().equalsIgnoreCase(b.paidCurrency())) {
            return false;
        }
        return true;
    }

    private record ScreenResult(long paidAmount, String paidCurrency, String orphanReason) {}

    private ScreenResult screen(PaymentChannel ch, Payment payment, IntentRecord intent) {
        long paidAmount = payment.amountMinor();
        String paidCurrency = nullToEmpty(payment.currency());
        if (!isBlank(payment.anomaly())) {
            return new ScreenResult(paidAmount, paidCurrency, payment.anomaly());
        }
        if (payment.storePriced()) {
            if (intent == null) {
                return new ScreenResult(paidAmount, paidCurrency,
                        "store-priced payment has no payment intent to take the order amount from");
            }
            paidAmount = intent.amountMinor();
            paidCurrency = intent.currency();
            if (payment.storeAmountMinor() > 0 && intent.currency().equals(payment.storeCurrency())
                    && payment.storeAmountMinor() != intent.amountMinor()) {
                return new ScreenResult(paidAmount, paidCurrency, "store price differs from the order price");
            }
        }
        if (intent != null && !isBlank(payment.productId()) && ch.needsProduct()
                && !productFor(intent.skuId(), ch.name()).equals(payment.productId())) {
            return new ScreenResult(paidAmount, paidCurrency, "product does not match the order sku");
        }
        if (ch instanceof PaymentChannel.ReceiptChannel rc && intent != null && !isBlank(payment.accountRef())
                && !rc.accountRef(intent.playerId()).equals(payment.accountRef())) {
            return new ScreenResult(paidAmount, paidCurrency, "account binding does not match the order buyer");
        }
        if (payment.test() && !acceptsTest(ch.name(), intent)) {
            return new ScreenResult(paidAmount, paidCurrency, "test transaction is not accepted in this environment");
        }
        return new ScreenResult(paidAmount, paidCurrency, "");
    }

    private boolean acceptsTest(String channelName, IntentRecord intent) {
        TestPolicy policy = opts.testPolicies().get(channelName);
        if (policy == null || !policy.accept()) {
            return false;
        }
        if (policy.players().isEmpty()) {
            return true;
        }
        return intent != null && policy.players().contains(intent.playerId());
    }

    private void settleIntent(PaymentChannel ch, TxnRecord fact, IntentRecord intent, boolean inserted,
                              boolean refundedOutside, long nowMs) {
        boolean counts = countsForIntent(fact, intent);
        if (refundedOutside || (!counts && !isReceiptChannel(ch))) {
            store.closeIntent(fact.orderHi(), fact.orderLo(), nowMs);
        } else if (!counts) {
            // 商店渠道不算数的付款不关意图
        } else if (!inserted && intent != null && intent.state() == PaymentRecords.INTENT_CLOSED && intent.isMinor()) {
            long paidMs = Math.max(nowMs, intent.createdMs());
            boolean within = store.reviveMinorIntent(intent, spendWindow(intent.minorAge(), Instant.ofEpochMilli(paidMs)),
                    paidMs, nowMs, true);
            if (!within) {
                metrics.countMinorGate("recount_over_limit");
            }
        } else {
            store.markIntentPaid(fact.orderHi(), fact.orderLo(), nowMs);
        }
    }

    private static boolean countsForIntent(TxnRecord fact, IntentRecord intent) {
        if (fact.mallState() == PaymentRecords.MALL_ORPHAN
                || fact.refundState() == PaymentRecords.REFUND_SUCCEEDED
                || fact.refundState() == PaymentRecords.REFUND_REPORTED) {
            return false;
        }
        return intent == null || (fact.paidAmountMinor() == intent.amountMinor()
                && Objects.equals(fact.paidCurrency(), intent.currency()));
    }

    private static boolean drivesIntent(TxnRecord txn) {
        return txn.mallState() == PaymentRecords.MALL_BOUND
                || (txn.mallState() == PaymentRecords.MALL_REFUND_QUEUED
                && MallClient.refundsOrdersOwnPayment(txn.mallRefundReason()));
    }

    private void revoke(PaymentChannel ch, Revocation rev) throws FactConflictException {
        Key128 key = PaymentKeys.txnKey(rev.transactionId());
        long observedMs = rev.observedAt() == null ? clock.millis() : rev.observedAt().toEpochMilli();
        for (int attempt = 0; attempt < 3; attempt++) {
            TxnRecord txn = store.findTxn(ch.id().getNumber(), key.hi(), key.lo());
            if (txn == null) {
                if (rev.revoked() && rev.payment() != null) {
                    recordPaymentInternal(ch, rev.payment(), true);
                    return;
                }
                if (rev.revoked()) {
                    if (!isBlank(rev.outRefundNo())
                            && rev.outRefundNo().equals(PaymentKeys.outRefundNo(ch.name(), rev.transactionId()))) {
                        return;
                    }
                    long orderHi = 0L;
                    long orderLo = 0L;
                    String oid = PaymentKeys.orderIdFromOutTradeNo(rev.outTradeNo());
                    Key128 ok = PaymentKeys.orderKey(oid);
                    if (ok != null) {
                        orderHi = ok.hi();
                        orderLo = ok.lo();
                    }
                    long nowMs = clock.millis();
                    TxnRecord tomb = new TxnRecord(ch.id().getNumber(), key.hi(), key.lo(), rev.transactionId(),
                            orderHi, orderLo, nullToEmpty(rev.outTradeNo()), 0L, 0L, "", 0L,
                            PaymentRecords.MALL_ORPHAN, "", 0, 0L, REVOCATION_TOMBSTONE_REASON,
                            PaymentRecords.REFUND_NONE, "", "", 0L, 0, 0L, "",
                            nowMs, nowMs, 1, observedMs, 0, 0, "", "", 0L, "",
                            PaymentRecords.FINALIZE_NONE, 0L, "");
                    if (!store.insertTxn(tomb).inserted()) {
                        continue;
                    }
                    metrics.countFact(ch.name(), "revoked");
                    log.info("[payment] recorded revocation tombstone channel={} txn=...{}", ch.name(), tail(rev.transactionId()));
                    return;
                }
                log.info("[payment] revocation ignored for unrecorded txn channel={} txn=...{}", ch.name(), tail(rev.transactionId()));
                return;
            }
            if (!Objects.equals(txn.transactionId(), rev.transactionId())) {
                throw new FactConflictException("txn hash collision");
            }
            if (txn.refundState() != PaymentRecords.REFUND_NONE
                    || (!isBlank(rev.outRefundNo()) && rev.outRefundNo().equals(PaymentKeys.outRefundNo(ch.name(), txn.transactionId())))) {
                return;
            }
            if ((txn.factRevision() > 0 && observedMs <= txn.factObservedMs())
                    || (txn.factRevision() == 0 && observedMs < txn.factObservedMs())) {
                return;
            }
            if (PaymentRecords.isRevoked(txn.factRevision()) == rev.revoked()) {
                if (observedMs > txn.factObservedMs()) {
                    store.touchFactObserved(txn, observedMs, clock.millis());
                }
                return;
            }
            if (!rev.revoked() && ch.id() != PayChannel.PAY_CHANNEL_APPLE) {
                return;
            }
            long nowMs = clock.millis();
            if (!store.flipFact(txn, observedMs, nowMs)) {
                continue;
            }
            int nextState = rev.revoked() ? PaymentRecords.INTENT_CLOSED : PaymentRecords.INTENT_PAID;
            if ((txn.orderHi() != 0L || txn.orderLo() != 0L) && drivesIntent(txn)) {
                applyFlip(txn.orderHi(), txn.orderLo(), nextState, nowMs);
            }
            metrics.countFact(ch.name(), rev.revoked() ? "revoked" : "reinstated");
            kickCallback.run();
            return;
        }
        throw new IllegalStateException("payment: txn fact kept changing while applying revocation");
    }

    private void applyFlip(long orderHi, long orderLo, int targetState, long nowMs) {
        if (targetState == PaymentRecords.INTENT_PAID) {
            IntentRecord intent = store.findIntent(orderHi, orderLo);
            if (intent != null && intent.isMinor()) {
                long paidMs = Math.max(nowMs, intent.createdMs());
                boolean within = store.reviveMinorIntent(intent,
                        spendWindow(intent.minorAge(), Instant.ofEpochMilli(paidMs)), paidMs, nowMs, true);
                if (!within) {
                    metrics.countMinorGate("reinstated_over_limit");
                }
                return;
            }
        }
        store.setIntentState(orderHi, orderLo, targetState, nowMs);
    }

    // ---------------------------------------------------------------------------
    // 5 个后台推进器（单轮可执行，方便单测直接驱动）
    // ---------------------------------------------------------------------------

    public void sweepIntents() {
        List<IntentRecord> due = store.dueIntents(clock.millis(), opts.batch());
        for (IntentRecord intent : due) {
            try {
                checkIntent(intent);
            } catch (RuntimeException e) {
                log.error("[payment] checkIntent order={} failed: {}", intent.orderId(), e.getMessage());
            }
        }
    }

    private void checkIntent(IntentRecord intent) {
        long nowMs = clock.millis();
        long lease = nowMs + opts.lease().toMillis();
        if (!store.claimCheck(intent, lease, nowMs)) {
            return;
        }
        long deadline = intent.expireMs() + opts.closeGrace().toMillis();
        long giveUp = intent.expireMs() + opts.giveUpAfter().toMillis();
        PayChannel chId = PayChannel.forNumber(intent.channel());
        PaymentChannel ch = chId == null ? null : channels.get(chId);
        if (ch == null) {
            if (nowMs >= giveUp) {
                store.closeIntent(intent.orderHi(), intent.orderLo(), nowMs);
                return;
            }
            store.scheduleCheck(intent, lease, nowMs + Duration.ofHours(1).toMillis(), nowMs);
            return;
        }
        QueryResult result = null;
        ChannelException queryErr = null;
        boolean unsupported = false;
        try {
            result = queryChannel(ch, intent);
        } catch (ChannelException e) {
            queryErr = e;
            unsupported = e.kind() == ChannelException.Kind.QUERY_UNSUPPORTED;
        }
        if (queryErr == null) {
            try {
                switch (result.state()) {
                    case PAID -> { recordPaymentInternal(ch, result.payment(), false); return; }
                    case REFUNDED_OUTSIDE -> { recordPaymentInternal(ch, result.payment(), true); return; }
                    case CLOSED -> { store.closeIntent(intent.orderHi(), intent.orderLo(), nowMs); return; }
                    case NOT_PAID -> {}
                }
            } catch (FactConflictException e) {
                return;
            }
        }
        if (nowMs >= deadline && (queryErr == null || unsupported)) {
            if (!unsupported) {
                String outTradeNo = PaymentKeys.outTradeNo(intent.orderId());
                try {
                    ch.close(outTradeNo);
                    metrics.observeChannelCall(ch.name(), "close", null);
                } catch (ChannelException e) {
                    metrics.observeChannelCall(ch.name(), "close", e);
                    store.scheduleCheck(intent, lease, nowMs + backoffMs(intent.checkCount(), opts.checkInterval()), nowMs);
                    return;
                }
                try {
                    QueryResult fin = queryChannel(ch, intent);
                    if (fin.state() == TradeState.PAID) {
                        recordPaymentInternal(ch, fin.payment(), false);
                        return;
                    }
                    if (fin.state() == TradeState.REFUNDED_OUTSIDE) {
                        recordPaymentInternal(ch, fin.payment(), true);
                        return;
                    }
                } catch (ChannelException e) {
                    store.scheduleCheck(intent, lease, nowMs + backoffMs(intent.checkCount(), opts.checkInterval()), nowMs);
                    return;
                } catch (FactConflictException e) {
                    return;
                }
            }
            if (store.closeIntent(intent.orderHi(), intent.orderLo(), nowMs)) {
                metrics.countIntent(ch.name(), "closed");
            }
            return;
        }
        if (queryErr != null && !unsupported && nowMs >= giveUp) {
            store.closeIntent(intent.orderHi(), intent.orderLo(), nowMs);
            return;
        }
        long next = (queryErr != null && !unsupported)
                ? nowMs + backoffMs(intent.checkCount(), opts.checkInterval())
                : nowMs + opts.checkInterval().toMillis();
        if (unsupported || (nowMs < deadline && next > deadline)) {
            next = Math.max(deadline, nowMs + 1);
        }
        store.scheduleCheck(intent, lease, next, nowMs);
    }

    public void pushMall() {
        long nowMs = clock.millis();
        long oldest = store.oldestMallPendingMs();
        metrics.setMallOldestPendingSeconds((oldest > 0L && nowMs > oldest) ? (nowMs - oldest) / 1000.0 : 0.0);
        List<TxnRecord> due = store.dueMallTxns(nowMs, opts.batch());
        for (TxnRecord txn : due) {
            try {
                pushOneToMall(txn);
            } catch (RuntimeException e) {
                log.error("[payment] pushMall txn=...{} failed: {}", tail(txn.transactionId()), e.getMessage());
            }
        }
    }

    private void pushOneToMall(TxnRecord txn) {
        long nowMs = clock.millis();
        long lease = nowMs + opts.lease().toMillis();
        if (!store.claimMall(txn, lease, nowMs)) {
            return;
        }
        String chName = ChannelTypes.nameOf(PayChannel.forNumber(txn.channel()));
        String orderId = PaymentKeys.orderId(txn.orderHi(), txn.orderLo());
        if (txn.mallState() == PaymentRecords.MALL_PENDING) {
            try {
                PaymentOutcome out = mall.recordPayment(new PaymentReport(
                        orderId, chName, txn.transactionId(), txn.paidAmountMinor(), txn.paidCurrency()));
                if (!out.hasRefundReason()) {
                    metrics.countMallPush("bound");
                    store.settleMall(txn, lease, new MallSync(PaymentRecords.MALL_BOUND, "", 0, true, ""), nowMs);
                    if (PaymentRecords.isRevoked(txn.factRevision()) && (txn.orderHi() != 0L || txn.orderLo() != 0L)) {
                        applyFlip(txn.orderHi(), txn.orderLo(), PaymentRecords.INTENT_CLOSED, nowMs);
                    }
                } else {
                    metrics.countMallPush("refund_queued");
                    store.settleMall(txn, lease, new MallSync(PaymentRecords.MALL_REFUND_QUEUED, out.refundReason(), 0, true, ""), nowMs);
                    if (PaymentRecords.isRevoked(txn.factRevision())
                            && MallClient.refundsOrdersOwnPayment(out.refundReason())
                            && (txn.orderHi() != 0L || txn.orderLo() != 0L)) {
                        applyFlip(txn.orderHi(), txn.orderLo(), PaymentRecords.INTENT_CLOSED, nowMs);
                    }
                }
            } catch (MallClientException e) {
                if (e.kind() == MallClientException.Kind.CONFLICT || e.kind() == MallClientException.Kind.INVALID) {
                    metrics.countMallPush("rejected");
                    store.settleMall(txn, lease, new MallSync(PaymentRecords.MALL_REJECTED, "", txn.mallRevision(), false, errorClass(e)), nowMs);
                } else {
                    metrics.countMallPush("retry");
                    store.deferMall(txn, lease, nowMs + backoffMs(txn.mallAttempts() + 1, opts.mallBackoffMax()), errorClass(e), nowMs);
                }
            }
            return;
        }
        String fact = PaymentRecords.isRevoked(txn.factRevision()) ? MallClient.FACT_REVOKED : MallClient.FACT_PAID;
        try {
            int acked = mall.pushRevision(new RevisionReport(orderId, chName, txn.transactionId(), txn.factRevision(), fact));
            if (acked >= txn.factRevision()) {
                metrics.countMallPush("revision");
                store.settleMall(txn, lease, new MallSync(txn.mallState(), txn.mallRefundReason(), txn.factRevision(), true, ""), nowMs);
            } else {
                metrics.countMallPush("retry");
                store.deferMall(txn, lease, nowMs + backoffMs(txn.mallAttempts() + 1, opts.mallBackoffMax()), "mall_revision_behind", nowMs);
            }
        } catch (MallClientException e) {
            if (e.kind() == MallClientException.Kind.INVALID) {
                metrics.countMallPush("rejected");
                store.settleMall(txn, lease, new MallSync(txn.mallState(), txn.mallRefundReason(), txn.mallRevision(), false, "mall_invalid_revision"), nowMs);
            } else {
                metrics.countMallPush("retry");
                store.deferMall(txn, lease, nowMs + backoffMs(txn.mallAttempts() + 1, opts.mallBackoffMax()), errorClass(e), nowMs);
            }
        }
    }

    public void processRefunds() {
        String cursor = "";
        for (int pageNum = 0; pageNum < REFUND_POLL_MAX_PAGES; pageNum++) {
            PendingRefundPage page;
            try {
                page = mall.pendingRefunds(cursor);
            } catch (MallClientException e) {
                log.error("[payment] polling mall refunds failed: {}", e.getMessage());
                return;
            }
            for (PendingRefund item : page.entries()) {
                PaymentChannel ch = channelsByName.get(item.channel());
                if (ch == null) continue;
                try {
                    refundOne(ch, item);
                } catch (RuntimeException e) {
                    log.error("[payment] refund txn=...{} failed: {}", tail(item.transactionId()), e.getMessage());
                }
            }
            if (isBlank(page.nextCursor()) || page.entries().isEmpty()) {
                return;
            }
            cursor = page.nextCursor();
        }
    }

    private void refundOne(PaymentChannel ch, PendingRefund item) {
        Key128 key = PaymentKeys.txnKey(item.transactionId());
        TxnRecord txn = store.findTxn(ch.id().getNumber(), key.hi(), key.lo());
        if (txn == null) {
            closeRefundQuietly(ch, item, "TXN_NOT_FOUND");
            return;
        }
        if (!Objects.equals(txn.transactionId(), item.transactionId())
                || txn.paidAmountMinor() != item.amountMinor()
                || !Objects.equals(txn.paidCurrency(), item.currency())) {
            closeRefundQuietly(ch, item, "TXN_FACT_MISMATCH");
            return;
        }
        if (PaymentRecords.isRevoked(txn.factRevision()) && txn.refundState() == PaymentRecords.REFUND_NONE) {
            return;
        }
        long nowMs = clock.millis();
        if (txn.refundNextMs() > nowMs) {
            return;
        }
        String outRefundNo = !isBlank(txn.outRefundNo()) ? txn.outRefundNo()
                : PaymentKeys.outRefundNo(ch.name(), txn.transactionId());
        long lease = nowMs + opts.lease().toMillis();
        if (!store.claimRefund(txn, outRefundNo, lease, nowMs)) {
            return;
        }
        long retryAt = nowMs + backoffMs(txn.refundAttempts() + 1, opts.refundBackoffMax());
        int state = txn.refundState() == PaymentRecords.REFUND_NONE ? PaymentRecords.REFUND_REQUESTED : txn.refundState();
        String refundId = txn.refundId();
        long refundMs = txn.refundMs();
        String lastErr = txn.refundLastError();

        if (txn.refundState() == PaymentRecords.REFUND_REPORTED) {
            try {
                mall.reportRefund(new RefundReceipt(item.orderId(), ch.name(), txn.transactionId(),
                        refundId, txn.paidAmountMinor(), txn.paidCurrency()));
                store.saveRefund(txn, lease, new RefundProgress(state, outRefundNo, refundId, refundMs, nowMs + 60_000L, lastErr), nowMs);
            } catch (MallClientException e) {
                if (e.kind() == MallClientException.Kind.CONFLICT) {
                    store.saveRefund(txn, lease, new RefundProgress(state, outRefundNo, refundId, refundMs, nowMs + 60_000L, lastErr), nowMs);
                } else {
                    store.saveRefund(txn, lease, new RefundProgress(state, outRefundNo, refundId, refundMs, retryAt, errorClass(e)), nowMs);
                }
            }
            return;
        }
        if (txn.refundState() == PaymentRecords.REFUND_FAILED) {
            try {
                mall.closeRefund(new RefundClosure(item.orderId(), ch.name(), item.transactionId(), closeReason(lastErr)));
                store.saveRefund(txn, lease, new RefundProgress(state, outRefundNo, refundId, refundMs, nowMs + 60_000L, lastErr), nowMs);
            } catch (MallClientException e) {
                long next = e.kind() == MallClientException.Kind.CONFLICT ? nowMs + 60_000L : retryAt;
                store.saveRefund(txn, lease, new RefundProgress(state, outRefundNo, refundId, refundMs, next, lastErr), nowMs);
            }
            return;
        }
        if (state != PaymentRecords.REFUND_SUCCEEDED) {
            RefundResult res;
            try {
                res = ch.refund(new RefundRequest(txn.outTradeNo(), txn.transactionId(), outRefundNo,
                        txn.paidAmountMinor(), txn.paidCurrency(), opts.refundReason(), txn.channelToken(), txn.channelOrderId()));
                metrics.observeChannelCall(ch.name(), "refund", null);
            } catch (ChannelException e) {
                metrics.observeChannelCall(ch.name(), "refund", e);
                store.saveRefund(txn, lease, new RefundProgress(state, outRefundNo, refundId, refundMs, retryAt, errorClass(e)), nowMs);
                return;
            }
            if (res.state() == RefundState.PROCESSING) {
                metrics.countRefund(ch.name(), "processing");
                store.saveRefund(txn, lease, new RefundProgress(PaymentRecords.REFUND_REQUESTED, outRefundNo, refundId, refundMs, retryAt, res.detail()), nowMs);
                return;
            }
            if (res.state() == RefundState.FAILED) {
                metrics.countRefund(ch.name(), "failed");
                long next = nowMs + 60_000L;
                try {
                    mall.closeRefund(new RefundClosure(item.orderId(), ch.name(), item.transactionId(), closeReason(res.detail())));
                } catch (MallClientException e) {
                    if (e.kind() != MallClientException.Kind.CONFLICT) {
                        next = retryAt;
                    }
                }
                store.saveRefund(txn, lease, new RefundProgress(PaymentRecords.REFUND_FAILED, outRefundNo, refundId, refundMs, next, res.detail()), nowMs);
                return;
            }
            Instant at = res.at() == null ? clock.instant() : res.at();
            state = PaymentRecords.REFUND_SUCCEEDED;
            refundId = res.refundId();
            refundMs = at.toEpochMilli();
            lastErr = "";
            metrics.countRefund(ch.name(), "succeeded");
            store.saveRefund(txn, lease, new RefundProgress(state, outRefundNo, refundId, refundMs, lease, ""), nowMs);
        }
        if ((txn.orderHi() != 0L || txn.orderLo() != 0L) && MallClient.refundsOrdersOwnPayment(item.reason())) {
            store.setIntentState(txn.orderHi(), txn.orderLo(), PaymentRecords.INTENT_CLOSED, nowMs);
        }
        try {
            mall.reportRefund(new RefundReceipt(item.orderId(), ch.name(), txn.transactionId(),
                    refundId, txn.paidAmountMinor(), txn.paidCurrency()));
            store.saveRefund(txn, lease, new RefundProgress(PaymentRecords.REFUND_REPORTED, outRefundNo, refundId, refundMs, 0L, ""), nowMs);
        } catch (MallClientException e) {
            if (e.kind() == MallClientException.Kind.CONFLICT) {
                store.saveRefund(txn, lease, new RefundProgress(PaymentRecords.REFUND_REPORTED, outRefundNo, refundId, refundMs, nowMs + 3_600_000L, "mall_conflict"), nowMs);
            } else {
                store.saveRefund(txn, lease, new RefundProgress(state, outRefundNo, refundId, refundMs, retryAt, errorClass(e)), nowMs);
            }
        }
    }

    private void closeRefundQuietly(PaymentChannel ch, PendingRefund item, String reason) {
        try {
            mall.closeRefund(new RefundClosure(item.orderId(), ch.name(), item.transactionId(), closeReason(reason)));
        } catch (MallClientException e) {
            log.error("[payment] closeRefund txn=...{} failed: {}", tail(item.transactionId()), e.getMessage());
        }
    }

    public void finalizePending() {
        long nowMs = clock.millis();
        long oldest = store.oldestFinalizePendingMs();
        metrics.setFinalizeOldestPendingSeconds((oldest > 0L && nowMs > oldest) ? (nowMs - oldest) / 1000.0 : 0.0);
        List<TxnRecord> due = store.dueFinalize(nowMs, opts.batch());
        for (TxnRecord txn : due) {
            try {
                finalizeOne(txn);
            } catch (RuntimeException e) {
                log.error("[payment] finalize txn=...{} failed: {}", tail(txn.transactionId()), e.getMessage());
            }
        }
    }

    private void finalizeOne(TxnRecord txn) {
        long nowMs = clock.millis();
        long lease = nowMs + opts.lease().toMillis();
        if (!store.claimFinalize(txn, lease, nowMs)) {
            return;
        }
        if (PaymentRecords.isRevoked(txn.factRevision())) {
            store.saveFinalize(txn, lease, PaymentRecords.FINALIZE_FAILED, 0L, nowMs);
            return;
        }
        PayChannel chId = PayChannel.forNumber(txn.channel());
        PaymentChannel ch = chId == null ? null : channels.get(chId);
        if (!(ch instanceof PaymentChannel.Finalizer fin)) {
            store.saveFinalize(txn, lease, PaymentRecords.FINALIZE_PENDING, nowMs + 3_600_000L, nowMs);
            return;
        }
        try {
            fin.finalizePurchase(txn.productId(), txn.channelToken());
            metrics.observeChannelCall(ch.name(), "finalize", null);
            store.saveFinalize(txn, lease, PaymentRecords.FINALIZE_DONE, 0L, nowMs);
        } catch (ChannelException e) {
            metrics.observeChannelCall(ch.name(), "finalize", e);
            if (e.kind() == ChannelException.Kind.CALL_ERROR
                    && (e.status() == 400 || e.status() == 404 || e.status() == 410)) {
                store.saveFinalize(txn, lease, PaymentRecords.FINALIZE_FAILED, 0L, nowMs);
                return;
            }
            int attempts = nowMs > txn.createdMs() ? (int) Math.min(10L, (nowMs - txn.createdMs()) / 60_000L) : 0;
            store.saveFinalize(txn, lease, PaymentRecords.FINALIZE_PENDING,
                    nowMs + backoffMs(attempts, Duration.ofMinutes(10)), nowMs);
        }
    }

    public void pollHistory() {
        for (PaymentChannel ch : channels.values()) {
            if (!(ch instanceof PaymentChannel.HistoryPoller poller)) {
                continue;
            }
            long owner = SECURE_RANDOM.nextLong() | 1L;
            Instant now = clock.instant();
            long nowMs = now.toEpochMilli();
            CursorClaim claim = store.claimCursor(ch.id().getNumber(), owner,
                    now.minus(opts.historyLookback()).toEpochMilli(), nowMs + 90_000L, nowMs);
            if (!claim.claimed()) {
                continue;
            }
            long nextCursorMs = claim.cursorMs();
            try {
                Instant from = Instant.ofEpochMilli(claim.cursorMs()).minus(HISTORY_OVERLAP);
                HistoryPollResult res = poller.pollHistory(from);
                metrics.observeChannelCall(ch.name(), "history", null);
                for (Notification note : res.notifications()) {
                    handleNotification(ch, note);
                }
                nextCursorMs = res.through().toEpochMilli();
            } catch (ChannelException | RuntimeException e) {
                metrics.observeChannelCall(ch.name(), "history", e);
                log.error("[payment] pollHistory channel={} failed: {}", ch.name(), e.getMessage());
            } finally {
                store.advanceCursor(ch.id().getNumber(), owner, nextCursorMs, clock.millis());
            }
        }
    }

    // ---------------------------------------------------------------------------
    // 辅助函数
    // ---------------------------------------------------------------------------

    private QueryResult queryChannel(PaymentChannel ch, IntentRecord intent) throws ChannelException {
        String outTradeNo = PaymentKeys.outTradeNo(intent.orderId());
        Ref ref = intent.channelRef().isEmpty() ? Ref.EMPTY
                : new Ref(intent.channelRef(), intent.channelRefMs() > 0 ? Instant.ofEpochMilli(intent.channelRefMs()) : null);
        QueryResult res;
        try {
            res = ch.query(new QueryRequest(outTradeNo, intent.payerId(), ref, intent.amountMinor(), intent.currency()));
            metrics.observeChannelCall(ch.name(), "query", null);
        } catch (ChannelException e) {
            if (e.kind() != ChannelException.Kind.QUERY_UNSUPPORTED) {
                metrics.observeChannelCall(ch.name(), "query", e);
            }
            throw e;
        }
        if ((res.state() == TradeState.PAID || res.state() == TradeState.REFUNDED_OUTSIDE) && res.payment() == null) {
            throw new ChannelException(ChannelException.Kind.OTHER, "channel reported paid without payment details");
        }
        return res;
    }

    private String productFor(long skuId, String channelName) {
        ProductSpec spec = opts.products().get(skuId);
        return spec == null ? "" : spec.channelProductIds().getOrDefault(channelName, "");
    }

    private String titleFor(long skuId) {
        ProductSpec spec = opts.products().get(skuId);
        return spec == null ? "" : spec.title();
    }

    private static boolean isReceiptChannel(PaymentChannel ch) {
        return ch instanceof PaymentChannel.ReceiptChannel;
    }

    private static long backoffMs(int attempt, Duration max) {
        int clamped = Math.min(Math.max(attempt, 0), 20);
        long maxMs = Math.max(1000L, max.toMillis());
        long wait = Math.min(1000L << clamped, maxMs);
        long jitterBound = Math.max(1L, wait / 5);
        long jitter = ThreadLocalRandom.current().nextLong(jitterBound + 1);
        return ThreadLocalRandom.current().nextBoolean() ? Math.max(500L, wait - jitter) : wait + jitter;
    }

    private static String errorClass(Throwable err) {
        if (err instanceof MallClientException me) {
            return switch (me.kind()) {
                case CONFLICT -> "mall_conflict";
                case INVALID -> "mall_invalid";
                case NOT_FOUND -> "mall_not_found";
                case HTTP_STATUS -> "mall_http_" + me.status();
                case TRANSPORT -> "unreachable";
            };
        }
        if (err instanceof ChannelException ce && ce.kind() == ChannelException.Kind.CALL_ERROR) {
            return ce.channelName() + "_" + ce.op() + "_" + ce.status() + "_" + ce.code();
        }
        return "unreachable";
    }

    public static String closeReason(String detail) {
        if (detail == null || detail.isEmpty()) {
            return "CHANNEL_REFUND_FAILED";
        }
        StringBuilder sb = new StringBuilder(Math.min(detail.length(), 64));
        for (int i = 0; i < detail.length() && sb.length() < 64; i++) {
            char c = detail.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == ':' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.isEmpty() ? "CHANNEL_REFUND_FAILED" : sb.toString();
    }

    private static String tail(String id) {
        if (id == null) return "";
        return id.length() <= 6 ? id : id.substring(id.length() - 6);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
