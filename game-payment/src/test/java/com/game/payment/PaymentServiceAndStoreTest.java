package com.game.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.RunMode;
import com.game.common.compliance.AntiAddictionRules;
import com.game.contract.MessageIdRegistry;
import com.game.contract.generated.ContractMessageIds;
import com.game.payment.channel.ChannelTypes;
import com.game.payment.channel.ChannelTypes.HttpNotificationRequest;
import com.game.payment.channel.ChannelTypes.Notification;
import com.game.payment.channel.ChannelTypes.Payment;
import com.game.payment.channel.DevPayChannel;
import com.game.payment.channel.DouyinMinigameChannel;
import com.game.payment.core.PaymentService;
import com.game.payment.core.PaymentService.CreateRequest;
import com.game.payment.core.PaymentService.CreateResult;
import com.game.payment.core.PaymentService.FactConflictException;
import com.game.payment.core.PaymentService.GetResult;
import com.game.payment.core.PaymentService.Options;
import com.game.payment.core.RealNameReader;
import com.game.payment.mall.MallClient;
import com.game.payment.rpc.PaymentRpcService;
import com.game.payment.store.JdbcPaymentStore;
import com.game.payment.store.PaymentKeys;
import com.game.payment.store.PaymentRecords;
import com.game.payment.store.PaymentRecords.IntentRecord;
import com.game.payment.store.PaymentRecords.TxnRecord;
import com.game.proto.payment.CreatePayIntentRequest;
import com.game.proto.payment.CreatePayIntentResponse;
import com.game.proto.payment.GetPayIntentRequest;
import com.game.proto.payment.GetPayIntentResponse;
import com.game.proto.payment.ListPayChannelsRequest;
import com.game.proto.payment.ListPayChannelsResponse;
import com.game.proto.payment.PayChannel;
import com.game.proto.payment.PayClientPlatform;
import com.game.proto.payment.PayIntentState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 支付核心状态机、未成年人限额行锁事务、退款墓碑与编译期 RPC 派发回归测试（H2 MySQL 模式）。
 */
class PaymentServiceAndStoreTest {

    private static final String DEVPAY_SECRET = "0123456789abcdef0123456789abcdef";

    private MutableClock clock;
    private JdbcPaymentStore store;
    private StubMallClient mall;
    private DevPayChannel devPay;
    private DouyinMinigameChannel douyin;
    private PaymentMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(LocalDateTime.of(2026, 11, 23, 12, 0, 0).toInstant(AntiAddictionRules.BEIJING));
        String dbName = "pay_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        String ddl = new String(getClass().getResourceAsStream("/db/payment-authority.sql").readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$", "");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String stmt : ddl.split(";")) {
                String s = stmt.strip();
                if (!s.isEmpty()) {
                    st.execute(s);
                }
            }
        }
        store = new JdbcPaymentStore(ds);
        store.verifySchema();
        mall = new StubMallClient();
        devPay = new DevPayChannel(DEVPAY_SECRET, clock);
        douyin = new DouyinMinigameChannel(new DouyinMinigameChannel.Config("dy_app", "dy_tok", "1", "元宝"));
        metrics = new PaymentMetrics(new SimpleMeterRegistry());
    }

    @Test
    void 自然键编解码与确定性派生() {
        String orderId = "01234567-89ab-cdef-fedc-ba9876543210";
        PaymentKeys.Key128 key = PaymentKeys.orderKey(orderId);
        assertThat(key).isNotNull();
        assertThat(PaymentKeys.orderId(key.hi(), key.lo())).isEqualTo(orderId);
        String outNo = PaymentKeys.outTradeNo(orderId);
        assertThat(outNo).hasSize(32).doesNotContain("-");
        assertThat(PaymentKeys.orderIdFromOutTradeNo(outNo)).isEqualTo(orderId);
        assertThat(PaymentKeys.orderKey("00000000-0000-0000-0000-000000000000")).isNull();
        assertThat(PaymentKeys.outRefundNo("wechat_pay", "wx_txn_1"))
                .hasSize(32).startsWith("rf")
                .isEqualTo(PaymentKeys.outRefundNo("wechat_pay", "wx_txn_1"));
    }

    @Test
    void 建意图回调落账推商城退款与修订全链路() throws Exception {
        PaymentService svc = newService(null, false);
        String orderId = UUID.randomUUID().toString();
        mall.orders.put(orderId, new MallClient.OrderView(
                orderId, "1001", 10L, 1, 600L, "CNY", MallClient.ORDER_STATE_AWAITING_PAYMENT,
                clock.millis() + 600_000L));

        // 1) 通过编译期生成的 ClientPlayerPaymentContractDispatcher 调 RPC
        PaymentRpcService rpc = new PaymentRpcService(svc, MessageIdRegistry.loadFromClasspath(), Runnable::run);
        ClientReply listReply = rpc.handle(call(ContractMessageIds.CLIENT_PLAYER_PAYMENT_LIST_PAY_CHANNELS, 1001L,
                ListPayChannelsRequest.newBuilder().setPlatform(PayClientPlatform.PAY_CLIENT_PLATFORM_DESKTOP).build())).join();
        ListPayChannelsResponse listResp = ListPayChannelsResponse.parseFrom(listReply.getBody());
        assertThat(listResp.getChannelsList()).containsExactly(PayChannel.PAY_CHANNEL_DEVPAY);

        ClientReply createReply = rpc.handle(call(ContractMessageIds.CLIENT_PLAYER_PAYMENT_CREATE_PAY_INTENT, 1001L,
                CreatePayIntentRequest.newBuilder().setOrderId(orderId)
                        .setChannel(PayChannel.PAY_CHANNEL_DEVPAY)
                        .setPlatform(PayClientPlatform.PAY_CLIENT_PLATFORM_DESKTOP).build())).join();
        CreatePayIntentResponse createResp = CreatePayIntentResponse.parseFrom(createReply.getBody());
        assertThat(createResp.hasErrorMessage()).isFalse();
        assertThat(createResp.getIntent().getState()).isEqualTo(PayIntentState.PAY_INTENT_STATE_CREATED);
        String outTradeNo = createResp.getDevpay().getOutTradeNo();

        // 2) DevPay 回调落账 + 推商城
        byte[] payBody = ("{\"out_trade_no\":\"" + outTradeNo + "\",\"amount_minor\":600,\"currency\":\"CNY\"}")
                .getBytes(StandardCharsets.UTF_8);
        String sig = DevPayChannel.signHex(DEVPAY_SECRET.getBytes(StandardCharsets.UTF_8), payBody);
        Notification note = devPay.parseNotification(new HttpNotificationRequest(
                "POST", Map.of(DevPayChannel.SIGNATURE_HEADER, sig), Map.of(), payBody));
        svc.handleNotification(devPay, note);

        ClientReply getReply = rpc.handle(call(ContractMessageIds.CLIENT_PLAYER_PAYMENT_GET_PAY_INTENT, 1001L,
                GetPayIntentRequest.newBuilder().setOrderId(orderId).build())).join();
        GetPayIntentResponse getResp = GetPayIntentResponse.parseFrom(getReply.getBody());
        assertThat(getResp.getIntent().getState()).isEqualTo(PayIntentState.PAY_INTENT_STATE_PAID);

        svc.pushMall();
        assertThat(mall.recordedPayments).hasSize(1);
        PaymentKeys.Key128 txnKey = PaymentKeys.txnKey("devpay-" + outTradeNo);
        TxnRecord txn = store.findTxn(PayChannel.PAY_CHANNEL_DEVPAY_VALUE, txnKey.hi(), txnKey.lo());
        assertThat(txn.mallState()).isEqualTo(PaymentRecords.MALL_BOUND);

        // 3) 2026-11-20 审计修复：同交易号篡改实付金额或币种必须报冲突
        Payment tampered = new Payment("devpay-" + outTradeNo, outTradeNo, "", 999L, "CNY",
                clock.instant(), "", false, 0L, "", "", "", false, false, "", "");
        assertThatThrownBy(() -> svc.recordPayment(devPay, tampered)).isInstanceOf(FactConflictException.class);

        // 4) 渠道侧撤销通知：翻转 fact_revision 并同步修订给商城
        clock.advance(Duration.ofSeconds(5));
        byte[] revBody = ("{\"out_trade_no\":\"" + outTradeNo + "\",\"revoke\":true}").getBytes(StandardCharsets.UTF_8);
        String revSig = DevPayChannel.signHex(DEVPAY_SECRET.getBytes(StandardCharsets.UTF_8), revBody);
        svc.handleNotification(devPay, devPay.parseNotification(new HttpNotificationRequest(
                "POST", Map.of(DevPayChannel.SIGNATURE_HEADER, revSig), Map.of(), revBody)));
        svc.pushMall();
        assertThat(mall.pushedRevisions).hasSize(1);
        assertThat(mall.pushedRevisions.get(0).fact()).isEqualTo(MallClient.FACT_REVOKED);
        assertThat(store.findIntent(PaymentKeys.orderKey(orderId).hi(), PaymentKeys.orderKey(orderId).lo()).state())
                .isEqualTo(PaymentRecords.INTENT_CLOSED);
    }

    @Test
    void 退款先于付款到达写墓碑拦截迟到付款() throws Exception {
        PaymentService svc = newService(null, false);
        String orderId = UUID.randomUUID().toString();
        String outTradeNo = PaymentKeys.outTradeNo(orderId);
        mall.orders.put(orderId, new MallClient.OrderView(
                orderId, "1001", 10L, 1, 600L, "CNY", MallClient.ORDER_STATE_AWAITING_PAYMENT,
                clock.millis() + 600_000L));
        svc.createIntent(new CreateRequest(1001L, orderId, PayChannel.PAY_CHANNEL_DEVPAY, "desktop", "", ""));

        byte[] revBody = ("{\"out_trade_no\":\"" + outTradeNo + "\",\"revoke\":true}").getBytes(StandardCharsets.UTF_8);
        svc.handleNotification(devPay, devPay.parseNotification(new HttpNotificationRequest(
                "POST", Map.of(DevPayChannel.SIGNATURE_HEADER, DevPayChannel.signHex(DEVPAY_SECRET.getBytes(StandardCharsets.UTF_8), revBody)),
                Map.of(), revBody)));

        byte[] payBody = ("{\"out_trade_no\":\"" + outTradeNo + "\",\"amount_minor\":600,\"currency\":\"CNY\"}").getBytes(StandardCharsets.UTF_8);
        svc.handleNotification(devPay, devPay.parseNotification(new HttpNotificationRequest(
                "POST", Map.of(DevPayChannel.SIGNATURE_HEADER, DevPayChannel.signHex(DEVPAY_SECRET.getBytes(StandardCharsets.UTF_8), payBody)),
                Map.of(), payBody)));

        svc.pushMall();
        assertThat(mall.recordedPayments).isEmpty();
        PaymentKeys.Key128 txnKey = PaymentKeys.txnKey("devpay-" + outTradeNo);
        TxnRecord txn = store.findTxn(PayChannel.PAY_CHANNEL_DEVPAY_VALUE, txnKey.hi(), txnKey.lo());
        assertThat(txn.mallState()).isEqualTo(PaymentRecords.MALL_ORPHAN);
        assertThat(txn.mallLastError()).isEqualTo(PaymentService.REVOCATION_TOMBSTONE_REASON);
    }

    @Test
    void 未成年人合规闸按年龄段限额跨账号合并与迟到超限拦截() throws Exception {
        String piChild7 = AntiAddictionRules.piBirthPrefix(2019, 12, 1) + "a".repeat(32); // 6岁
        String piTeen12 = AntiAddictionRules.piBirthPrefix(2014, 1, 1) + "b".repeat(32);  // 12岁（单次/日50元，月200元）
        Map<Long, RealNameReader.RealNameStatus> statuses = Map.of(
                2001L, new RealNameReader.RealNameStatus(false, ""),
                2002L, new RealNameReader.RealNameStatus(true, piChild7),
                2003L, new RealNameReader.RealNameStatus(true, piTeen12),
                2004L, new RealNameReader.RealNameStatus(true, piTeen12)); // 同一实名人的第二个账号
        PaymentService svc = newService(statuses::get, true);

        // 未实名：desktop(devpay) 拒 31008；抖音小游戏由平台管放行
        String oUnverified = putOrder("2001", 600L);
        assertThat(svc.createIntent(new CreateRequest(2001L, oUnverified, PayChannel.PAY_CHANNEL_DEVPAY, "desktop", "", "")).tip())
                .isEqualTo(PaymentTips.REAL_NAME_REQUIRED);
        assertThat(svc.createIntent(new CreateRequest(2001L, oUnverified, PayChannel.PAY_CHANNEL_DOUYIN_MINIGAME, "douyin_minigame", "", "")).tip())
                .isEqualTo(PaymentTips.OK);

        // 未满 8 周岁：禁止充值 31009
        String oChild = putOrder("2002", 100L);
        assertThat(svc.createIntent(new CreateRequest(2002L, oChild, PayChannel.PAY_CHANNEL_DEVPAY, "desktop", "", "")).tip())
                .isEqualTo(PaymentTips.MINOR_NOT_ALLOWED);

        // 12 周岁：单笔 51 元拒 31010
        String oOverSingle = putOrder("2003", 5100L);
        assertThat(svc.createIntent(new CreateRequest(2003L, oOverSingle, PayChannel.PAY_CHANNEL_DEVPAY, "desktop", "", "")).tip())
                .isEqualTo(PaymentTips.MINOR_SINGLE_LIMIT);

        // 12 周岁：账号 2003 建 30 元在途，同人另一账号 2004 再建 25 元撞单日上限 50 元 -> 31011
        String o1 = putOrder("2003", 3000L);
        assertThat(svc.createIntent(new CreateRequest(2003L, o1, PayChannel.PAY_CHANNEL_DEVPAY, "desktop", "", "")).tip())
                .isEqualTo(PaymentTips.OK);
        String o2 = putOrder("2004", 2500L);
        assertThat(svc.createIntent(new CreateRequest(2004L, o2, PayChannel.PAY_CHANNEL_DEVPAY, "desktop", "", "")).tip())
                .isEqualTo(PaymentTips.MINOR_DAILY_LIMIT);

        // 关闭 o1 后释放额度，2004 建 50 元并付清，再给已关闭的 o1 来一笔迟到付款 -> 事务内复核超限标 ORPHAN 不复活意图
        PaymentKeys.Key128 k1 = PaymentKeys.orderKey(o1);
        store.closeIntent(k1.hi(), k1.lo(), clock.millis());
        String o3 = putOrder("2004", 5000L);
        CreateResult r3 = svc.createIntent(new CreateRequest(2004L, o3, PayChannel.PAY_CHANNEL_DEVPAY, "desktop", "", ""));
        assertThat(r3.tip()).isEqualTo(PaymentTips.OK);
        svc.recordPayment(devPay, new Payment("devpay-" + PaymentKeys.outTradeNo(o3), PaymentKeys.outTradeNo(o3), "",
                5000L, "CNY", clock.instant(), "", false, 0L, "", "", "", false, false, "", ""));

        TxnRecord late = svc.recordPayment(devPay, new Payment("devpay-" + PaymentKeys.outTradeNo(o1), PaymentKeys.outTradeNo(o1), "",
                3000L, "CNY", clock.instant(), "", false, 0L, "", "", "", false, false, "", ""));
        assertThat(late.mallState()).isEqualTo(PaymentRecords.MALL_ORPHAN);
        assertThat(late.mallLastError()).isEqualTo(PaymentService.MINOR_LATE_OVER_LIMIT_REASON);
        IntentRecord reloadedO1 = store.findIntent(k1.hi(), k1.lo());
        assertThat(reloadedO1.state()).isEqualTo(PaymentRecords.INTENT_CLOSED);
    }

    @Test
    void 生产模式配置门禁拦截不安全组合() {
        PaymentProperties props = new PaymentProperties();
        props.getDevpay().setEnabled(true);
        assertThatThrownBy(() -> props.validate(RunMode.PROD)).hasMessageContaining("devpay");

        PaymentProperties prodNoGate = new PaymentProperties();
        prodNoGate.setNotifyBaseUrl("https://pay.example.com");
        prodNoGate.getAlipay().setEnabled(true);
        prodNoGate.setMinorSpendingGate(PaymentProperties.GATE_NONE);
        assertThatThrownBy(() -> prodNoGate.validate(RunMode.PROD)).hasMessageContaining("minor-spending-gate=none");

        prodNoGate.setMinorSpendingGate(PaymentProperties.GATE_REALNAME);
        prodNoGate.validate(RunMode.PROD);

        PaymentProperties badPlatform = new PaymentProperties();
        badPlatform.setNotifyBaseUrl("https://pay.example.com");
        badPlatform.setMinorSpendingGate(PaymentProperties.GATE_OVERSEAS);
        badPlatform.getAlipay().setEnabled(true);
        PaymentProperties.PlatformProps ios = new PaymentProperties.PlatformProps();
        ios.setPlatform("ios_app");
        ios.setChannels(List.of("alipay"));
        badPlatform.setPlatforms(List.of(ios));
        assertThatThrownBy(() -> badPlatform.validate(RunMode.PROD)).hasMessageContaining("cannot be offered on platform ios_app");
    }

    private PaymentService newService(RealNameReader realName, boolean realNameGate) {
        Options opts = Options.defaults(
                Map.of(
                        "desktop", List.of(PayChannel.PAY_CHANNEL_DEVPAY),
                        "douyin_minigame", List.of(PayChannel.PAY_CHANNEL_DOUYIN_MINIGAME)),
                Map.of(),
                Map.of(),
                realNameGate);
        return new PaymentService(store, mall, realName, List.of(devPay, douyin), opts, metrics, clock);
    }

    private String putOrder(String buyerId, long amountMinor) {
        String id = UUID.randomUUID().toString();
        mall.orders.put(id, new MallClient.OrderView(
                id, buyerId, 10L, 1, amountMinor, "CNY", MallClient.ORDER_STATE_AWAITING_PAYMENT,
                clock.millis() + 600_000L));
        return id;
    }

    private static ClientCall call(int msgId, long playerId, com.google.protobuf.Message req) {
        return ClientCall.newBuilder()
                .setMessageId(msgId)
                .setSession(SessionContext.newBuilder().setPlayerId(playerId).setGateNodeId(1).setSessionId(1).build())
                .setBody(req.toByteString())
                .build();
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class StubMallClient implements MallClient {
        final Map<String, OrderView> orders = new HashMap<>();
        final List<PaymentReport> recordedPayments = new ArrayList<>();
        final List<RevisionReport> pushedRevisions = new ArrayList<>();
        final List<PendingRefund> refunds = new ArrayList<>();
        final List<RefundReceipt> reportedRefunds = new ArrayList<>();
        final List<RefundClosure> closedRefunds = new ArrayList<>();

        @Override
        public OrderView orderView(String orderId) throws MallClientException {
            OrderView v = orders.get(orderId);
            if (v == null) throw new MallClientException(MallClientException.Kind.NOT_FOUND, 404, "not found", null);
            return v;
        }

        @Override
        public PaymentOutcome recordPayment(PaymentReport payment) {
            recordedPayments.add(payment);
            return new PaymentOutcome("DELIVERY_PENDING", "");
        }

        @Override
        public PendingRefundPage pendingRefunds(String cursor) {
            return new PendingRefundPage(List.copyOf(refunds), "");
        }

        @Override
        public void reportRefund(RefundReceipt refund) {
            reportedRefunds.add(refund);
        }

        @Override
        public void closeRefund(RefundClosure closure) {
            closedRefunds.add(closure);
        }

        @Override
        public int pushRevision(RevisionReport revision) {
            pushedRevisions.add(revision);
            return revision.revision();
        }
    }
}
