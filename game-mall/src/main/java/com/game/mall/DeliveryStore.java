package com.game.mall;

import com.game.common.RunMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Mall 侧可靠交付意图与退款/转人工待办；相同 order 只能绑定一个 trade 合同，未知结果保留原意图。 */
@Component
public final class DeliveryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PaymentPolicy paymentPolicy;
    private final RmbConsignmentStore consignments;

    public DeliveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, null, RunMode.PROD);
    }

    public DeliveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager, MallProperties properties) {
        this(jdbc, manager, properties, RunMode.PROD);
    }

    public DeliveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager, MallProperties properties,
                         @Value("${game.run-mode:prod}") String runMode) {
        this(jdbc, manager, properties, RunMode.parse(runMode));
    }

    DeliveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager, MallProperties properties, RunMode runMode) {
        this(jdbc, manager, properties, runMode, new RmbConsignmentStore(jdbc, manager, Clock.systemUTC(),
                new RmbConsignmentProperties(), (RmbConsignmentStore.PayoutEvidenceVerifier) null));
    }

    @Autowired
    public DeliveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager, MallProperties properties,
                         @Value("${game.run-mode:prod}") String runMode, RmbConsignmentStore consignments) {
        this(jdbc, manager, properties, RunMode.parse(runMode), consignments);
    }

    DeliveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager, MallProperties properties, RunMode runMode,
                  RmbConsignmentStore consignments) {
        this.jdbc = jdbc;
        this.consignments = consignments;
        this.paymentPolicy = new PaymentPolicy(jdbc, properties, runMode);
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(5);
    }

    public DeliveryApi.Skus skus() {
        return skus(new DeliveryApi.SkuPage(0, 100));
    }

    public DeliveryApi.Skus skus(DeliveryApi.SkuPage page) {
        if (page == null || page.afterSkuId() < 0 || page.limit() < 1 || page.limit() > 100) {
            throw new MallException(400, "商品分页非法");
        }
        return new DeliveryApi.Skus(jdbc.query("SELECT s.id,g.title,s.price_minor,s.currency,s.stock-s.lock_stock FROM "
                        + "pms_sku_stock s JOIN commerce_sku_grant g ON g.sku_id=s.id WHERE "
                        + "s.published=1 AND s.stock>s.lock_stock AND s.id>? ORDER BY s.id LIMIT ?",
                (r, n) -> new DeliveryApi.Sku(r.getLong(1), r.getString(2), r.getLong(3), r.getString(4), r.getLong(5)),
                page.afterSkuId(), page.limit()));
    }

    public List<DeliveryApi.Intent> poll(int limit) {
        return poll(new DeliveryApi.Poll(limit)).entries().stream().map(DeliveryApi.Entry::intent).toList();
    }

    private record PendingRow(DeliveryApi.Intent intent, long createdMs) {}

    public DeliveryApi.PollPage poll(DeliveryApi.Poll input) {
        if (input == null || input.limit() < 1 || input.limit() > 100) {
            throw new MallException(400, "交付批量非法");
        }
        PendingScanCursor cursor = PendingScanCursor.parse(input.cursor());
        if (cursor == null) {
            var upper = jdbc.query("SELECT created_ms,order_sn FROM commerce_delivery_intent WHERE status='PENDING' "
                            + "ORDER BY created_ms DESC,order_sn DESC LIMIT 1",
                    (r, n) -> new PendingScanCursor(0, "", r.getLong(1), r.getString(2)));
            if (upper.isEmpty()) {
                return new DeliveryApi.PollPage(List.of());
            }
            cursor = upper.getFirst();
            if (cursor.upperMs() < 0 || !cursor.upperId().matches(PaymentPolicy.ORDER)) {
                throw new MallException(503, "交付意图排序键损坏");
            }
        }
        var rows = jdbc.query("SELECT "
                        + "d.order_sn,d.buyer_id,d.sku_id,d.quantity,g.item_config_id,g.count_per_unit,g.title,o.pay_"
                        + "amount,o.currency,d.created_ms,p.buyer_account,p.buyer_owner_revision,o.buyer_id,o.sku_id,o.quantity "
                        + "FROM commerce_delivery_intent d JOIN commerce_order_grant g ON g.order_sn=d.order_sn JOIN oms_order "
                        + "o ON o.order_sn=d.order_sn LEFT JOIN commerce_official_owner p ON p.order_sn=d.order_sn "
                        + "WHERE d.status='PENDING' AND d.sku_id<>0 AND o.status=1 "
                        + "AND NOT EXISTS (SELECT 1 FROM commerce_reversal_intent r WHERE r.order_sn=d.order_sn) "
                        + "AND (d.created_ms>? OR (d.created_ms=? AND d.order_sn>?)) "
                        + "AND (d.created_ms<? OR (d.created_ms=? AND d.order_sn<=?)) "
                        + "ORDER BY d.created_ms,d.order_sn LIMIT ?",
                (r, n) -> {
                    long count = Math.multiplyExact(r.getInt(4), (long) r.getInt(6));
                    if (count < 1 || count > 4096) {
                        throw new MallException(503, "已冻结的SKU游戏数量越界");
                    }
                    if (r.getLong(10) < 0 || !r.getString(1).matches(PaymentPolicy.ORDER)) {
                        throw new MallException(503, "交付意图排序键损坏");
                    }
                    if (!r.getString(2).equals(r.getString(13)) || r.getLong(3) != r.getLong(14) || r.getInt(4) != r.getInt(15)) {
                        throw new MallException(503, "交付意图与原OMS角色或商品不符");
                    }
                    return new PendingRow(new DeliveryApi.Intent(r.getString(1), r.getString(2),
                            CommerceIdentity.storedAccount(r.getBytes(11)), CommerceIdentity.storedRevision(r.getBytes(12)),
                            r.getLong(3), r.getInt(4), r.getInt(5),
                            (int) count, r.getString(7), r.getLong(8), r.getString(9)), r.getLong(10));
                },
                cursor.afterMs(), cursor.afterMs(), cursor.afterId(), cursor.upperMs(), cursor.upperMs(), cursor.upperId(), input.limit() + 1);
        boolean hasMore = rows.size() > input.limit();
        int count = Math.min(rows.size(), input.limit());
        List<DeliveryApi.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var row = rows.get(i);
            entries.add(new DeliveryApi.Entry(row.intent(), !hasMore && i == count - 1 ? ""
                    : cursor.after(row.createdMs(), row.intent().orderId())));
        }
        return new DeliveryApi.PollPage(List.copyOf(entries));
    }

    public void receipt(DeliveryApi.Receipt in) {
        if (in == null || in.orderId() == null || !in.orderId().matches("[0-9a-f-]{36}") || in.tradeOrderId() == null
                || !in.tradeOrderId().matches("[1-9][0-9]{0,19}") || in.proof() == null
                || !in.proof().matches("[0-9a-f]{64}")
                || !Set.of("DELIVERED", "REFUND_PENDING", "MANUAL").contains(in.state())) {
            throw new MallException(400, "交付持久回执非法");
        }
        tx.executeWithoutResult(status -> {
            var orders = jdbc.queryForList("SELECT status,sku_id FROM oms_order WHERE order_sn=? FOR UPDATE", in.orderId());
            var rows = jdbc.queryForList("SELECT status FROM commerce_delivery_intent WHERE order_sn=? FOR UPDATE", in.orderId());
            if (rows.size() != 1) {
                throw new MallException(404, "交付意图不存在");
            }
            var existing = jdbc.queryForList("SELECT trade_order_id,state,proof FROM commerce_delivery_receipt WHERE order_sn=?", in.orderId());
            if (!existing.isEmpty()) {
                var old = existing.getFirst();
                if (!in.tradeOrderId().equals(old.get("trade_order_id")) || !in.state().equals(old.get("state"))
                        || !in.proof().equals(old.get("proof"))) {
                    throw new MallException(409, "同一交付意图的终局发生冲突");
                }
                return;
            }
            if (!"PENDING".equals(rows.getFirst().get("status"))) {
                throw new MallException(409, "交付意图已终结但缺原始回执");
            }
            if (orders.size() != 1) {
                throw new MallException(409, "交付订单不在已支付待交付状态");
            }
            boolean reversalOwned = !jdbc.queryForList(
                    "SELECT revision FROM commerce_reversal_intent WHERE order_sn=? FOR UPDATE", in.orderId()).isEmpty();
            if (!reversalOwned && ((Number) orders.getFirst().get("status")).intValue() != 1) {
                throw new MallException(409, "交付订单不在已支付待交付状态");
            }
            if (((Number) orders.getFirst().get("sku_id")).longValue() == 0) {
                if (consignments.mapping(in.orderId()) == null) {
                    throw new MallException(503, "寄售交付原身份缺失");
                }
            } else {
                var original = jdbc.query("SELECT buyer_account,buyer_owner_revision FROM commerce_official_owner WHERE order_sn=?",
                        (row, index) -> List.of(CommerceIdentity.storedAccount(row.getBytes(1)), CommerceIdentity.storedRevision(row.getBytes(2))),
                        in.orderId());
                if (original.size() != 1) {
                    throw new MallException(503, "官方交付原身份缺失");
                }
            }
            consignments.delivered(in.orderId(), in.state());
            jdbc.update("INSERT INTO commerce_delivery_receipt(order_sn,trade_order_id,state,proof) VALUES(?,?,?,?)",
                    in.orderId(), in.tradeOrderId(), in.state(), in.proof());
            jdbc.update("UPDATE commerce_delivery_intent SET status=? WHERE order_sn=? AND status='PENDING'",
                    in.state(), in.orderId());
            if (reversalOwned) {
                return;
            }
            if (in.state().equals("DELIVERED")) {
                jdbc.update("UPDATE oms_order SET status=2 WHERE order_sn=? AND status=1", in.orderId());
            }
            if (in.state().equals("MANUAL")) {
                jdbc.update("UPDATE oms_order SET status=7 WHERE order_sn=? AND status=1", in.orderId());
            }
            if (in.state().equals("REFUND_PENDING")) {
                int intents = jdbc.update("INSERT INTO commerce_refund_intent(channel,transaction_id,order_sn,source,status) "
                        + "SELECT channel,transaction_id,order_sn,'" + MallApi.RefundReason.DELIVERY_REJECTED.name() + "','PENDING' "
                        + "FROM commerce_payment_receipt WHERE order_sn=?", in.orderId());
                if (intents != 1) {
                    throw new MallException(503, "交付退款缺订单付款回执");
                }
                jdbc.update("UPDATE oms_order SET status=5 WHERE order_sn=? AND status=1", in.orderId());
            }
        });
    }

    public List<MallApi.PendingRefund> refunds() {
        return refundsPage(new DeliveryApi.RefundPoll(100, "")).entries();
    }

    /** 按确定性游标分页列出待退款的付款（/internal/refunds/poll，payment 角色）。 */
    public DeliveryApi.RefundPage refundsPage(DeliveryApi.RefundPoll input) {
        int limit = input == null || input.limit() == 0 ? 100 : input.limit();
        if (limit < 1 || limit > 100) {
            throw new MallException(400, "退款分页批量非法");
        }
        String rawCursor = input == null || input.cursor() == null ? "" : input.cursor();
        List<MallApi.PendingRefund> rows;
        if (rawCursor.isEmpty()) {
            rows = jdbc.query("SELECT r.order_sn,p.channel,p.transaction_id,p.amount_minor,p.currency,r.source FROM "
                            + "commerce_refund_intent r JOIN commerce_payment_receipt p ON p.channel=r.channel AND p.transaction_id=r.transaction_id "
                            + "WHERE r.status='PENDING' ORDER BY r.order_sn,r.channel,r.transaction_id LIMIT ?",
                    (r, n) -> new MallApi.PendingRefund(r.getString(1), r.getString(2), r.getString(3),
                            r.getLong(4), r.getString(5), MallApi.RefundReason.stored(r.getString(6))),
                    limit + 1);
        } else {
            String[] parts = rawCursor.split(":", -1);
            if (parts.length != 4 || !"r1".equals(parts[0])
                    || !parts[1].matches(PaymentPolicy.ORDER)
                    || !parts[2].matches("[a-z][a-z0-9_-]{1,31}")
                    || !parts[3].matches("[A-Za-z0-9_-]{1,128}")) {
                throw new MallException(400, "退款分页游标非法");
            }
            byte[] channelBytes = ascii(parts[2]);
            byte[] txnBytes = ascii(parts[3]);
            rows = jdbc.query("SELECT r.order_sn,p.channel,p.transaction_id,p.amount_minor,p.currency,r.source FROM "
                            + "commerce_refund_intent r JOIN commerce_payment_receipt p ON p.channel=r.channel AND p.transaction_id=r.transaction_id "
                            + "WHERE r.status='PENDING' AND (r.order_sn>? OR (r.order_sn=? AND (r.channel>? OR (r.channel=? AND r.transaction_id>?)))) "
                            + "ORDER BY r.order_sn,r.channel,r.transaction_id LIMIT ?",
                    (r, n) -> new MallApi.PendingRefund(r.getString(1), r.getString(2), r.getString(3),
                            r.getLong(4), r.getString(5), MallApi.RefundReason.stored(r.getString(6))),
                    parts[1], parts[1], channelBytes, channelBytes, txnBytes, limit + 1);
        }
        boolean hasMore = rows.size() > limit;
        List<MallApi.PendingRefund> entries = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String nextCursor = "";
        if (hasMore && !entries.isEmpty()) {
            var last = entries.getLast();
            nextCursor = "r1:" + last.orderId() + ":" + last.channel() + ":" + last.transactionId();
        }
        return new DeliveryApi.RefundPage(entries, nextCursor);
    }

    /** 渠道报告某一笔付款已退款（按 channel + paymentTransactionId 幂等）。 */
    public void refunded(MallApi.Refund in) {
        if (in == null || in.orderId() == null || !in.orderId().matches(PaymentPolicy.ORDER)
                || in.channel() == null || !in.channel().matches("[a-z][a-z0-9_-]{1,31}")
                || in.paymentTransactionId() == null || !in.paymentTransactionId().matches("[A-Za-z0-9_-]{1,128}")
                || in.transactionId() == null || !in.transactionId().matches("[A-Za-z0-9_-]{1,128}")) {
            throw new MallException(400, "退款回执非法");
        }
        try {
            settleRefund(in);
        } catch (DuplicateKeyException reused) {
            throw new MallException(409, "退款流水已用于其他付款");
        }
    }

    private void settleRefund(MallApi.Refund in) {
        tx.executeWithoutResult(status -> {
            var lockedOrder = jdbc.queryForList("SELECT status FROM oms_order WHERE order_sn=? FOR UPDATE", in.orderId());
            if (lockedOrder.size() != 1) {
                throw new MallException(404, "退款订单不存在");
            }
            var intents = jdbc.queryForList("SELECT order_sn,source,status FROM commerce_refund_intent "
                    + "WHERE channel=? AND transaction_id=? FOR UPDATE", ascii(in.channel()), ascii(in.paymentTransactionId()));
            if (intents.size() != 1 || !in.orderId().equals(intents.getFirst().get("order_sn"))) {
                throw new MallException(404, "退款待办不存在");
            }
            var intent = intents.getFirst();
            var original = jdbc.queryForList("SELECT order_sn,amount_minor,currency FROM commerce_payment_receipt "
                    + "WHERE channel=? AND transaction_id=?", ascii(in.channel()), ascii(in.paymentTransactionId()));
            if (original.size() != 1) {
                throw new MallException(503, "退款待办缺原付款回执");
            }
            var row = original.getFirst();
            if (((Number) row.get("amount_minor")).longValue() != in.amountMinor()
                    || !row.get("currency").equals(in.currency())) {
                throw new MallException(409, "退款金额币种不符");
            }
            boolean ordersOwnPayment = MallApi.RefundReason.stored((String) intent.get("source")).refundsOrdersOwnPayment();
            if (ordersOwnPayment != in.orderId().equals(row.get("order_sn"))) {
                throw new MallException(503, "退款待办来源与付款绑定不一致");
            }
            paymentPolicy.refund(in);
            var prior = jdbc.queryForList("SELECT transaction_id FROM commerce_refund_receipt "
                    + "WHERE channel=? AND payment_transaction_id=?", String.class, ascii(in.channel()), ascii(in.paymentTransactionId()));
            if (!prior.isEmpty()) {
                if (!in.transactionId().equals(prior.getFirst())) {
                    throw new MallException(409, "退款流水冲突");
                }
                return;
            }
            String intentStatus = (String) intent.get("status");
            if ("MANUAL".equals(intentStatus) || "REVOKED".equals(intentStatus)) {
                throw new MallException(409, "退款待办已终结");
            }
            if (!"PENDING".equals(intentStatus)) {
                throw new MallException(503, "退款待办已终结但缺退款回执");
            }
            if (ordersOwnPayment && ((Number) lockedOrder.getFirst().get("status")).intValue() != 5) {
                throw new MallException(409, "订单不在退款中");
            }
            jdbc.update("INSERT INTO commerce_refund_receipt(channel,payment_transaction_id,transaction_id,order_sn,amount_minor,currency) "
                            + "VALUES(?,?,?,?,?,?)",
                    ascii(in.channel()), ascii(in.paymentTransactionId()), ascii(in.transactionId()), in.orderId(),
                    in.amountMinor(), in.currency());
            if (jdbc.update("UPDATE commerce_refund_intent SET status='REFUNDED' WHERE channel=? AND transaction_id=? AND status='PENDING'",
                    ascii(in.channel()), ascii(in.paymentTransactionId())) != 1) {
                throw new MallException(409, "退款待办状态冲突");
            }
            if (ordersOwnPayment && jdbc.update("UPDATE oms_order SET status=6 WHERE order_sn=? AND status=5", in.orderId()) != 1) {
                throw new MallException(409, "订单状态冲突");
            }
        });
    }

    /** 退款转人工（/internal/refunds/close，payment 角色）。 */
    public void closeRefund(MallApi.RefundClosure in) {
        if (in == null || in.orderId() == null || !in.orderId().matches(PaymentPolicy.ORDER)
                || in.channel() == null || !in.channel().matches("[a-z][a-z0-9_-]{1,31}")
                || in.paymentTransactionId() == null || !in.paymentTransactionId().matches("[A-Za-z0-9_-]{1,128}")
                || in.reason() == null || !in.reason().matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new MallException(400, "退款转人工参数非法");
        }
        paymentPolicy.requireChannel(in.channel());
        tx.executeWithoutResult(status -> {
            var lockedOrder = jdbc.queryForList("SELECT status FROM oms_order WHERE order_sn=? FOR UPDATE", in.orderId());
            if (lockedOrder.size() != 1) {
                throw new MallException(404, "退款订单不存在");
            }
            var intents = jdbc.queryForList("SELECT order_sn,source,status FROM commerce_refund_intent "
                    + "WHERE channel=? AND transaction_id=? FOR UPDATE", ascii(in.channel()), ascii(in.paymentTransactionId()));
            if (intents.size() != 1 || !in.orderId().equals(intents.getFirst().get("order_sn"))) {
                throw new MallException(404, "退款待办不存在");
            }
            String current = (String) intents.getFirst().get("status");
            if ("MANUAL".equals(current)) {
                return;
            }
            if (!"PENDING".equals(current)) {
                throw new MallException(409, "退款待办已终结");
            }
            jdbc.update("UPDATE commerce_refund_intent SET status='MANUAL',close_reason=? WHERE channel=? AND transaction_id=? AND status='PENDING'",
                    in.reason(), ascii(in.channel()), ascii(in.paymentTransactionId()));
            boolean ordersOwnPayment = MallApi.RefundReason.stored((String) intents.getFirst().get("source")).refundsOrdersOwnPayment();
            if (ordersOwnPayment && jdbc.update("UPDATE oms_order SET status=7 WHERE order_sn=? AND status=5", in.orderId()) != 1) {
                throw new MallException(503, "订单本身的退款待办与订单状态不一致");
            }
        });
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
