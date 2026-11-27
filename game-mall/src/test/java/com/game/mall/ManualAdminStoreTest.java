package com.game.mall;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 商城人工单查询与处置单测（对齐主仓 {@code ManualAdminStore} 与 jubaozhai-market.md §16.1）：
 * 覆盖官方订单人工处置、退款待办人工处置、寄售付款仍 PAID 禁退回托管改走 RETRY_DELIVERY、付款已 REVOKED 才放行 RETURN_TO_SELLER、
 * 卖家收入解冻/没收以及 commerce_admin_command 幂等重放与条款冲突保护。
 */
class ManualAdminStoreTest {
    private static final String ACTOR = "ops-admin";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_760_000_000_000L), ZoneOffset.UTC);

    private static ManualAdminStore manual(MallTestDatabase db) {
        return new ManualAdminStore(db.jdbc, new DataSourceTransactionManager(db.source), new ObjectMapper(), CLOCK);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void rejects(int expectedStatus, Executable action) {
        assertEquals(expectedStatus, assertThrows(MallException.class, action).status());
    }

    private static String insertOfficialManualOrder(MallTestDatabase db, String deliveryStatus,
                                                    Integer revRevision, String revStatus, String paymentFact) {
        String orderId = UUID.randomUUID().toString();
        db.jdbc.update(
                "INSERT INTO oms_order(order_sn,buyer_id,request_id,sku_id,quantity,pay_amount,currency,status,create_time_ms,payment_deadline_ms)"
                        + " VALUES(?,?,?,?,1,1250,'CNY',7,1000,901000)",
                orderId, "10001", ascii("req-" + orderId), 1L);
        db.jdbc.update(
                "INSERT INTO commerce_payment_receipt(channel,transaction_id,order_sn,amount_minor,currency,revision,fact)"
                        + " VALUES(?,?,?,?,?,?,?)",
                ascii("test"), ascii("txn-" + orderId), orderId, 1250L, "CNY",
                revRevision == null ? 0 : revRevision, paymentFact);
        if (deliveryStatus != null) {
            db.jdbc.update(
                    "INSERT INTO commerce_delivery_intent(order_sn,buyer_id,sku_id,quantity,status,created_ms)"
                            + " VALUES(?,?,?,?,?,1000)",
                    orderId, "10001", 1L, 1, deliveryStatus);
        }
        if (revRevision != null && revStatus != null) {
            db.jdbc.update(
                    "INSERT INTO commerce_reversal_intent(order_sn,revision,status,updated_ms) VALUES(?,?,?,1000)",
                    orderId, revRevision, revStatus);
            if ("MANUAL".equals(revStatus)) {
                db.jdbc.update(
                        "INSERT INTO commerce_reversal_receipt(order_sn,revision,trade_order_id,state,proof) VALUES(?,?,?,?,?)",
                        orderId, revRevision, "9001", "MANUAL", "proof-1");
            }
        }
        return orderId;
    }

    private static String insertConsignmentManual(MallTestDatabase db, String listingId, String paymentFact,
                                                  String deliveryIntentStatus, boolean withManualReceipt,
                                                  boolean withIncome, long reservedFen) {
        String orderId = UUID.randomUUID().toString();
        db.jdbc.update(
                "INSERT INTO oms_order(order_sn,buyer_id,request_id,sku_id,quantity,pay_amount,currency,status,create_time_ms,payment_deadline_ms)"
                        + " VALUES(?,?,?,?,1,10000,'CNY',7,1000,901000)",
                orderId, "20002", ascii("creq-" + listingId), 0L);
        db.jdbc.update(
                "INSERT INTO commerce_payment_receipt(channel,transaction_id,order_sn,amount_minor,currency,revision,fact)"
                        + " VALUES(?,?,?,?,?,?,?)",
                ascii("test"), ascii("ctxn-" + listingId), orderId, 10000L, "CNY",
                PaymentRevisionStore.REVOKED.equals(paymentFact) ? 1 : 0, paymentFact);
        db.jdbc.update(
                "INSERT INTO commerce_consignment(listing_id,seller_player_id,seller_account,market_zone,asset_kind,"
                        + "escrow_reference,escrow_proof,request_digest,title,price_fen,fee_bps,cooling_ms,notice_end_ms,"
                        + "sale_end_ms,state,current_order_sn,created_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listingId, "30003", ascii("seller-acc"), 1L, "ITEM", "trade:" + listingId, "proof-" + listingId,
                "digest-" + listingId, "神兵", 10000L, 500, 3600_000L, 2000L, 9999_000L, "MANUAL", orderId, 1000L);
        db.jdbc.update(
                "INSERT INTO commerce_consignment_order(order_sn,listing_id,buyer_account,buyer_owner_revision,"
                        + "request_id,seller_account,escrow_reference,escrow_proof,fee_bps,cooling_ms)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                orderId, listingId, ascii("buyer-acc"), ascii("1"), ascii("buy-" + listingId),
                ascii("seller-acc"), "trade:" + listingId, "proof-" + listingId, 500, 3600_000L);
        if (deliveryIntentStatus != null) {
            db.jdbc.update(
                    "INSERT INTO commerce_delivery_intent(order_sn,buyer_id,sku_id,quantity,status,created_ms)"
                            + " VALUES(?,?,?,?,?,1000)",
                    orderId, "20002", 0L, 1, deliveryIntentStatus);
        }
        if (withManualReceipt) {
            db.jdbc.update(
                    "INSERT INTO commerce_delivery_receipt(order_sn,trade_order_id,state,proof) VALUES(?,?,?,?)",
                    orderId, "88001", "MANUAL", "dproof");
        }
        if (withIncome) {
            db.jdbc.update(
                    "INSERT INTO commerce_seller_income(order_sn,account,gross_fen,fee_fen,net_fen,reserved_fen,paid_fen,forfeited_fen,available_ms)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)",
                    orderId, ascii("seller-acc"), 10000L, 500L, 9500L, reservedFen, 0L, 0L, Long.MAX_VALUE);
        }
        return orderId;
    }

    @Test
    void 寄售付款仍PAID禁退回托管改走RETRY_DELIVERY_付款已REVOKED放行RETURN_TO_SELLER() {
        try (var db = new MallTestDatabase()) {
            ManualAdminStore admin = manual(db);

            // 1. 付款仍 PAID 且交付待办 MANUAL：RETURN_TO_SELLER 必须 409 拒绝；RETRY_DELIVERY 清 MANUAL 回执并置 PENDING/SOLD/PAID(1)
            String paidOrder = insertConsignmentManual(db, "7001", PaymentRevisionStore.PAID, "MANUAL", true, false, 0L);
            rejects(409, () -> admin.resolveConsignment(ACTOR,
                    new ManualAdminApi.ResolveConsignment("req-ret-paid-1", "7001", "RETURN_TO_SELLER", "误操作退回")));

            var retry = admin.resolveConsignment(ACTOR,
                    new ManualAdminApi.ResolveConsignment("req-retry-del-1", "7001", "RETRY_DELIVERY", "交由trade重回执"));
            assertEquals("SOLD", retry.resultingState());
            assertEquals("PENDING", db.jdbc.queryForObject("SELECT status FROM commerce_delivery_intent WHERE order_sn=?", String.class, paidOrder));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_receipt WHERE order_sn=?", Integer.class, paidOrder));
            assertEquals(1, db.jdbc.queryForObject("SELECT status FROM oms_order WHERE order_sn=?", Integer.class, paidOrder));
            assertEquals("SOLD", db.jdbc.queryForObject("SELECT state FROM commerce_consignment WHERE listing_id='7001'", String.class));
            // 幂等重放返回完全相同快照
            assertEquals(retry, admin.resolveConsignment(ACTOR,
                    new ManualAdminApi.ResolveConsignment("req-retry-del-1", "7001", "RETRY_DELIVERY", "交由trade重回执")));
            // 同 requestId 改条款 409
            rejects(409, () -> admin.resolveConsignment(ACTOR,
                    new ManualAdminApi.ResolveConsignment("req-retry-del-1", "7001", "RETURN_TO_SELLER", "篡改")));

            // 2. 付款已 REVOKED 且无卖家收入：RETRY_DELIVERY 被拒，RETURN_TO_SELLER 成功置 RETURNING 且订单 9、交付待办 REVOKED
            String revokedOrder = insertConsignmentManual(db, "7002", PaymentRevisionStore.REVOKED, "MANUAL", false, false, 0L);
            rejects(409, () -> admin.resolveConsignment(ACTOR,
                    new ManualAdminApi.ResolveConsignment("req-retry-rev-1", "7002", "RETRY_DELIVERY", "已撤款")));
            var returned = admin.resolveConsignment(ACTOR,
                    new ManualAdminApi.ResolveConsignment("req-ret-rev-1", "7002", "RETURN_TO_SELLER", "渠道已撤款退回托管"));
            assertEquals("RETURNING", returned.resultingState());
            assertEquals("REVOKED", db.jdbc.queryForObject("SELECT status FROM commerce_delivery_intent WHERE order_sn=?", String.class, revokedOrder));
            assertEquals(9, db.jdbc.queryForObject("SELECT status FROM oms_order WHERE order_sn=?", Integer.class, revokedOrder));
            assertEquals("RETURNING", db.jdbc.queryForObject("SELECT state FROM commerce_consignment WHERE listing_id='7002'", String.class));
        }
    }

    @Test
    void 卖家收入解冻与没收_在途提现预留挡住没收() {
        try (var db = new MallTestDatabase()) {
            ManualAdminStore admin = manual(db);

            // 解冻：available_ms 从 MAX_VALUE 改为 now，寄售 SOLD、订单 DELIVERED(2)
            String orderUnfreeze = insertConsignmentManual(db, "7101", PaymentRevisionStore.PAID, "DELIVERED", false, true, 0L);
            var unfrozen = admin.resolveConsignment(ACTOR,
                    new ManualAdminApi.ResolveConsignment("req-unfreeze-1", "7101", "UNFREEZE_INCOME", "核查放行"));
            assertEquals("SOLD", unfrozen.resultingState());
            assertEquals(CLOCK.millis(), db.jdbc.queryForObject(
                    "SELECT available_ms FROM commerce_seller_income WHERE order_sn=?", Long.class, orderUnfreeze));
            assertEquals(2, db.jdbc.queryForObject("SELECT status FROM oms_order WHERE order_sn=?", Integer.class, orderUnfreeze));

            // 没收：有 reserved_fen > 0 时必须 409 拒绝；清零后成功没收至 forfeited_fen，寄售 FORFEITED、订单 9
            String orderForfeit = insertConsignmentManual(db, "7102", PaymentRevisionStore.REVOKED, "DELIVERED", false, true, 500L);
            rejects(409, () -> admin.resolveConsignment(ACTOR,
                    new ManualAdminApi.ResolveConsignment("req-forfeit-blk", "7102", "FORFEIT_INCOME", "有在途提现")));
            db.jdbc.update("UPDATE commerce_seller_income SET reserved_fen=0 WHERE order_sn=?", orderForfeit);
            var forfeited = admin.resolveConsignment(ACTOR,
                    new ManualAdminApi.ResolveConsignment("req-forfeit-ok1", "7102", "FORFEIT_INCOME", "确证违约没收"));
            assertEquals("FORFEITED", forfeited.resultingState());
            assertEquals(9500L, db.jdbc.queryForObject(
                    "SELECT forfeited_fen FROM commerce_seller_income WHERE order_sn=?", Long.class, orderForfeit));
            assertEquals(9, db.jdbc.queryForObject("SELECT status FROM oms_order WHERE order_sn=?", Integer.class, orderForfeit));
        }
    }

    @Test
    void 官方订单与退款人工待办列表分页与处置() {
        try (var db = new MallTestDatabase()) {
            ManualAdminStore admin = manual(db);

            // 官方订单：RETRY_REVERSAL 清 MANUAL 回执置 REVERSING(8)；奇数修订号 MARK_REVERSED 置 REVERSED(9)；交付 MANUAL 走 CREATE_REFUND 置 REFUND_PENDING(5)
            String oRetry = insertOfficialManualOrder(db, "DELIVERED", 1, "MANUAL", PaymentRevisionStore.REVOKED);
            String oRev = insertOfficialManualOrder(db, "DELIVERED", 3, "MANUAL", PaymentRevisionStore.REVOKED);
            String oRefund = insertOfficialManualOrder(db, "MANUAL", null, null, PaymentRevisionStore.PAID);

            var summary = admin.list(new ManualAdminApi.ListQuery("", "", "", 50));
            assertEquals(3, summary.orders().size());

            var r1 = admin.resolveOrder(ACTOR, new ManualAdminApi.ResolveOrder("req-ord-retry1", oRetry, "RETRY_REVERSAL", "重试扣回"));
            assertEquals("REVERSAL_PENDING", r1.resultingState());
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_reversal_receipt WHERE order_sn=?", Integer.class, oRetry));

            var r2 = admin.resolveOrder(ACTOR, new ManualAdminApi.ResolveOrder("req-ord-rev001", oRev, "MARK_REVERSED", "线下已扣回"));
            assertEquals("REVERSED", r2.resultingState());

            var r3 = admin.resolveOrder(ACTOR, new ManualAdminApi.ResolveOrder("req-ord-ref001", oRefund, "CREATE_REFUND", "无法发奖转退款"));
            assertEquals("REFUND_PENDING", r3.resultingState());

            // 退款待办转 MANUAL 后可通过 list 查到，并支持 MARK_REFUNDED 落回执将订单收敛为 REFUNDED(6)
            db.jdbc.update("UPDATE commerce_refund_intent SET status='MANUAL',close_reason='CHANNEL_UNSUPPORTED' WHERE order_sn=?", oRefund);
            db.jdbc.update("UPDATE oms_order SET status=7 WHERE order_sn=?", oRefund);
            var refundList = admin.list(new ManualAdminApi.ListQuery("", "", "", 50));
            assertEquals(1, refundList.refunds().size());
            var rf = refundList.refunds().getFirst();
            var resolvedRefund = admin.resolveRefund(ACTOR, new ManualAdminApi.ResolveRefund(
                    "req-rf-mark001", rf.orderId(), rf.channel(), rf.transactionId(),
                    "MARK_REFUNDED", "ext-refund-9981", "渠道人工退回"));
            assertEquals("REFUNDED", resolvedRefund.resultingState());
            assertEquals(6, db.jdbc.queryForObject("SELECT status FROM oms_order WHERE order_sn=?", Integer.class, oRefund));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_refund_receipt WHERE order_sn=?", Integer.class, oRefund));
        }
    }
}
