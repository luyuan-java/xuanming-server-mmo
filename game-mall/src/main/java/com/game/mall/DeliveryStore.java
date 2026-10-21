package com.game.mall;

import com.game.common.RunMode;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Mall侧可靠交付意图；相同order只能绑定一个trade合同，未知结果保留原意图。 */
@Component
public final class DeliveryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PaymentPolicy paymentPolicy;
    private final RmbConsignmentStore consignments;
    // 旧构造器仍可查询/接收游戏交付；没有渠道配置时退款默认拒绝，不能暗中允许测试渠道。
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
        paymentPolicy = new PaymentPolicy(jdbc, properties, runMode);
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }
    public DeliveryApi.Skus skus() {
        return skus(new DeliveryApi.SkuPage(0, 100));
    }
    public DeliveryApi.Skus skus(DeliveryApi.SkuPage page) {
        if (page == null || page.afterSkuId() < 0 || page.limit() < 1 || page.limit() > 100)
            throw new MallException(400, "商品分页非法");
        return new DeliveryApi.Skus(jdbc.query("SELECT s.id,g.title,s.price_minor,s.currency,s.stock-s.lock_stock FROM "
                + "pms_sku_stock s JOIN commerce_sku_grant g ON g.sku_id=s.id WHERE "
                + "s.published=1 AND s.stock>s.lock_stock AND s.id>? ORDER BY s.id LIMIT ?",
            (r, n)
                -> new DeliveryApi.Sku(r.getLong(1), r.getString(2), r.getLong(3), r.getString(4), r.getLong(5)),
            page.afterSkuId(), page.limit()));
    }
    public List<DeliveryApi.Intent> poll(int limit) {
        return poll(new DeliveryApi.Poll(limit)).entries().stream().map(DeliveryApi.Entry::intent).toList();
    }
    private record DeliveryKey(long createdMs, String orderId) {}
    private record PendingIntent(DeliveryApi.Intent intent, DeliveryKey key) {}
    private static final String ORDER_ID = PaymentPolicy.ORDER;
    /** 每轮固定上界；只读、无破坏性领取，多 worker 或重启仍按订单幂等处理。 */
    public DeliveryApi.PollPage poll(DeliveryApi.Poll input) {
        if (input == null || input.limit() < 1 || input.limit() > 100)
            throw new MallException(400, "交付批量非法");
        PendingScanCursor cursor = PendingScanCursor.parse(input.cursor());
        if (cursor == null) {
            var highest = jdbc.query("SELECT created_ms,order_sn FROM commerce_delivery_intent WHERE status='PENDING'"
                    + " ORDER BY created_ms DESC,order_sn DESC LIMIT 1",
                (r, n) -> new DeliveryKey(r.getLong(1), r.getString(2)));
            if (highest.isEmpty())
                return new DeliveryApi.PollPage(List.of());
            DeliveryKey upper = highest.getFirst();
            if (upper.createdMs() < 0 || !upper.orderId().matches(ORDER_ID))
                throw new MallException(503, "交付意图排序键损坏");
            cursor = new PendingScanCursor(0, "", upper.createdMs(), upper.orderId());
        }
        var rows = jdbc.query("SELECT "
                          + "d.order_sn,d.buyer_id,d.sku_id,d.quantity,g.item_config_id,g.count_per_unit,g.title,o.pay_"
                          + "amount,o.currency,d.created_ms "
                + "FROM commerce_delivery_intent d JOIN commerce_order_grant g ON g.order_sn=d.order_sn JOIN oms_order "
                  + "o ON o.order_sn=d.order_sn "
                + "WHERE d.status='PENDING' AND (d.created_ms>? OR (d.created_ms=? AND d.order_sn>?))"
                + " AND (d.created_ms<? OR (d.created_ms=? AND d.order_sn<=?))"
                + " ORDER BY d.created_ms,d.order_sn LIMIT ?",
            (r, n)
                -> {
                long count = Math.multiplyExact(r.getInt(4), (long) r.getInt(6));
                if (count < 1 || count > 4096)
                    throw new MallException(503, "已冻结的SKU游戏数量越界");
                String orderId = r.getString(1);
                long createdMs = r.getLong(10);
                if (createdMs < 0 || !orderId.matches(ORDER_ID))
                    throw new MallException(503, "交付意图排序键损坏");
                var intent = new DeliveryApi.Intent(orderId, r.getString(2), r.getLong(3), r.getInt(4), r.getInt(5),
                    (int) count, r.getString(7), r.getLong(8), r.getString(9));
                return new PendingIntent(intent, new DeliveryKey(createdMs, orderId));
            },
            cursor.afterMs(), cursor.afterMs(), cursor.afterId(),
            cursor.upperMs(), cursor.upperMs(), cursor.upperId(), input.limit() + 1);
        int size = Math.min(rows.size(), input.limit());
        List<DeliveryApi.Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            PendingIntent row = rows.get(i);
            entries.add(new DeliveryApi.Entry(row.intent(), i == size - 1 && rows.size() <= input.limit()
                    ? "" : cursor.after(row.key().createdMs(), row.key().orderId())));
        }
        return new DeliveryApi.PollPage(entries);
    }
    public void receipt(DeliveryApi.Receipt in) {
        if (in == null || in.orderId() == null || !in.orderId().matches("[0-9a-f-]{36}") || in.tradeOrderId() == null
            || !in.tradeOrderId().matches("[1-9][0-9]{0,19}") || in.proof() == null
            || !in.proof().matches("[0-9a-f]{64}")
            || !Set.of("DELIVERED", "REFUND_PENDING", "MANUAL").contains(in.state()))
            throw new MallException(400, "交付持久回执非法");
        tx.executeWithoutResult(status -> {
            // 与付款/取消保持OMS先锁；收入、原交付回执和终态同事务提交。
            var orders = jdbc.queryForList("SELECT status FROM oms_order WHERE order_sn=? FOR UPDATE", in.orderId());
            var rows = jdbc.queryForList(
                "SELECT status FROM commerce_delivery_intent WHERE order_sn=? FOR UPDATE", in.orderId());
            if (rows.size() != 1)
                throw new MallException(404, "交付意图不存在");
            var existing = jdbc.queryForList(
                "SELECT trade_order_id,state,proof FROM commerce_delivery_receipt WHERE order_sn=?", in.orderId());
            if (!existing.isEmpty()) {
                var old = existing.getFirst();
                if (!in.tradeOrderId().equals(old.get("trade_order_id")) || !in.state().equals(old.get("state"))
                    || !in.proof().equals(old.get("proof")))
                    throw new MallException(409, "同一交付意图的终局发生冲突");
                return;
            }
            if (!"PENDING".equals(rows.getFirst().get("status"))) { throw new MallException(409, "交付意图已终结但缺原始回执"); }
            if (orders.size() != 1 || ((Number) orders.getFirst().get("status")).intValue() != 1) {
                throw new MallException(409, "交付订单不在已支付待交付状态");
            }
            consignments.delivered(in.orderId(), in.state());
            jdbc.update("INSERT INTO commerce_delivery_receipt(order_sn,trade_order_id,state,proof) VALUES(?,?,?,?)",
                in.orderId(), in.tradeOrderId(), in.state(), in.proof());
            jdbc.update("UPDATE commerce_delivery_intent SET status=? WHERE order_sn=? AND status='PENDING'",
                in.state(), in.orderId());
            if (in.state().equals("DELIVERED"))
                jdbc.update("UPDATE oms_order SET status=2 WHERE order_sn=? AND status=1", in.orderId());
            if (in.state().equals("MANUAL"))
                jdbc.update("UPDATE oms_order SET status=7 WHERE order_sn=? AND status=1", in.orderId());
            if (in.state().equals("REFUND_PENDING")) {
                jdbc.update("INSERT INTO commerce_refund_intent(order_sn,status) VALUES(?,'PENDING')", in.orderId());
                jdbc.update("UPDATE oms_order SET status=5 WHERE order_sn=? AND status=1", in.orderId());
            }
        });
    }
    public List<MallApi.Payment> refunds() {
        return jdbc.query("SELECT r.order_sn,p.channel,p.transaction_id,p.amount_minor,p.currency FROM "
                + "commerce_refund_intent r JOIN commerce_payment_receipt p ON p.order_sn=r.order_sn WHERE "
                + "r.status='PENDING' ORDER BY r.order_sn LIMIT 100",
            (r, n)
                -> new MallApi.Payment(r.getString(1), r.getString(2), r.getString(3), r.getLong(4), r.getString(5)));
    }
    public void refunded(MallApi.Payment in) {
        if (in == null || in.orderId() == null || in.channel() == null || in.transactionId() == null
            || !in.channel().matches("[a-z][a-z0-9_-]{1,31}") || !in.transactionId().matches("[A-Za-z0-9_-]{1,128}"))
            throw new MallException(400, "退款回执非法");
        tx.executeWithoutResult(status -> {
            var original = jdbc.queryForList("SELECT o.status,p.amount_minor,p.currency FROM oms_order o JOIN "
                    + "commerce_payment_receipt p ON p.order_sn=o.order_sn WHERE o.order_sn=? FOR UPDATE",
                in.orderId());
            if (original.size() != 1)
                throw new MallException(404, "退款订单不存在");
            var row = original.getFirst();
            if (((Number) row.get("amount_minor")).longValue() != in.amountMinor()
                || !row.get("currency").equals(in.currency()))
                throw new MallException(409, "退款金额币种不符");
            paymentPolicy.refund(in);
            var prior = jdbc.queryForList(
                "SELECT channel,transaction_id FROM commerce_refund_receipt WHERE order_sn=?", in.orderId());
            if (!prior.isEmpty()) {
                if (!in.channel().equals(prior.getFirst().get("channel"))
                    || !in.transactionId().equals(prior.getFirst().get("transaction_id")))
                    throw new MallException(409, "退款流水冲突");
                return;
            }
            if (((Number) row.get("status")).intValue() != 5)
                throw new MallException(409, "订单不在退款中");
            jdbc.update("INSERT INTO commerce_refund_receipt(order_sn,channel,transaction_id,amount_minor,currency) "
                    + "VALUES(?,?,?,?,?)",
                in.orderId(), in.channel(), in.transactionId(), in.amountMinor(), in.currency());
            jdbc.update("UPDATE commerce_refund_intent SET status='REFUNDED' WHERE order_sn=? AND status='PENDING'",
                in.orderId());
            jdbc.update("UPDATE oms_order SET status=6 WHERE order_sn=? AND status=5", in.orderId());
        });
    }
}
