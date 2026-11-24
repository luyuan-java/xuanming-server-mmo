package com.game.mall;

import com.game.common.RunMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 渠道付款修订存储（payment 角色，{@code POST /internal/payments/revision}）。
 *
 * <p>对齐权威仓 {@code PaymentRevisionStore}：奇数修订号表示渠道已撤销（{@code REVOKED}），
 * 正偶数表示撤销被撤回（{@code PAID}）；只接受递增修订号，重放或过时修订原样返回当前值。
 */
@Repository
@DependsOn("mallSchemaReady")
public class PaymentRevisionStore {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentRevisionStore.class);
    static final String PAID = "PAID";
    static final String REVOKED = "REVOKED";
    private static final Set<Integer> BOUND_OFFICIAL_STATES = Set.of(1, 2, 7, 8, 9);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MallProperties props;
    private final RunMode runMode;

    @Autowired
    public PaymentRevisionStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                                MallProperties props, @Value("${game.run-mode:prod}") String runMode) {
        this(jdbc, manager, clock, props, RunMode.parse(runMode));
    }

    PaymentRevisionStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                         MallProperties props, RunMode runMode) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.props = props;
        this.runMode = runMode;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(5);
    }

    public MallApi.RevisionOutcome record(MallApi.Revision in) {
        if (in == null || in.orderId() == null || !in.orderId().matches(PaymentPolicy.ORDER)
                || in.channel() == null || !in.channel().matches("[a-z][a-z0-9_-]{1,31}")
                || in.transactionId() == null || !in.transactionId().matches("[A-Za-z0-9_-]{1,128}")
                || in.revision() < 1 || !(PAID.equals(in.fact()) || REVOKED.equals(in.fact()))
                || (in.revision() % 2 == 1) != REVOKED.equals(in.fact())) {
            throw new MallException(400, "付款修订参数非法");
        }
        props.requirePaymentChannel(in.channel(), runMode);
        return Objects.requireNonNull(tx.execute(status -> {
            var orders = jdbc.queryForList("SELECT status,sku_id FROM oms_order WHERE order_sn=? FOR UPDATE", in.orderId());
            if (orders.size() != 1) {
                throw new MallException(404, "订单不存在");
            }
            int orderStatus = ((Number) orders.getFirst().get("status")).intValue();
            long skuId = ((Number) orders.getFirst().get("sku_id")).longValue();
            var receipts = jdbc.queryForList("SELECT order_sn,revision,fact FROM commerce_payment_receipt "
                    + "WHERE channel=? AND transaction_id=? FOR UPDATE", ascii(in.channel()), ascii(in.transactionId()));
            if (receipts.size() != 1) {
                throw new MallException(404, "付款回执不存在");
            }
            var receipt = receipts.getFirst();
            String boundOrder = (String) receipt.get("order_sn");
            String owner = boundOrder;
            if (owner == null) {
                var abnormal = jdbc.queryForList("SELECT order_sn FROM commerce_abnormal_payment WHERE channel=? AND transaction_id=?",
                        String.class, ascii(in.channel()), ascii(in.transactionId()));
                if (abnormal.size() != 1) {
                    throw new MallException(503, "不绑定订单的付款缺异常付款记录");
                }
                owner = abnormal.getFirst();
            }
            if (!in.orderId().equals(owner)) {
                throw new MallException(409, "这笔付款不属于这张订单");
            }
            int stored = ((Number) receipt.get("revision")).intValue();
            String storedFact = (String) receipt.get("fact");
            if (in.revision() <= stored) {
                return new MallApi.RevisionOutcome(stored, storedFact, MallOrderStore.stateName(orderStatus));
            }
            if (jdbc.update("UPDATE commerce_payment_receipt SET revision=?,fact=? WHERE channel=? AND transaction_id=? AND revision=?",
                    in.revision(), in.fact(), ascii(in.channel()), ascii(in.transactionId()), stored) != 1) {
                throw new MallException(503, "付款回执修订冲突");
            }
            boolean revoked = REVOKED.equals(in.fact());
            var refunds = jdbc.queryForList("SELECT source,status FROM commerce_refund_intent WHERE channel=? AND transaction_id=? FOR UPDATE",
                    ascii(in.channel()), ascii(in.transactionId()));
            int next;
            if (!refunds.isEmpty()) {
                next = refundEffect(in, refunds.getFirst(), revoked, orderStatus);
            } else if (boundOrder == null) {
                throw new MallException(503, "异常付款缺退款待办");
            } else if (skuId == 0) {
                next = consignmentEffect(in, revoked, orderStatus);
            } else {
                next = reversalEffect(in, revoked, orderStatus);
            }
            return new MallApi.RevisionOutcome(in.revision(), in.fact(), MallOrderStore.stateName(next));
        }));
    }

    private int refundEffect(MallApi.Revision in, Map<String, Object> refund, boolean revoked, int orderStatus) {
        boolean ordersOwnPayment = MallApi.RefundReason.stored((String) refund.get("source")).refundsOrdersOwnPayment();
        String current = (String) refund.get("status");
        String nextStatus;
        String reason;
        int expectedOrder;
        int nextOrder;
        if (revoked && ("PENDING".equals(current) || "MANUAL".equals(current))) {
            nextStatus = "REVOKED";
            reason = "CHANNEL_REVOKED";
            expectedOrder = "PENDING".equals(current) ? 5 : 7;
            nextOrder = 6;
        } else if (!revoked && "REVOKED".equals(current)) {
            nextStatus = "MANUAL";
            reason = "REVOCATION_REVERSED";
            expectedOrder = 6;
            nextOrder = 7;
        } else {
            if ("REFUNDED".equals(current)) {
                afterCommit(() -> LOG.warn("payment changed by the channel after the mall refunded it: order={} channel={} revision={} fact={}",
                        in.orderId(), in.channel(), in.revision(), in.fact()));
            }
            return orderStatus;
        }
        if (jdbc.update("UPDATE commerce_refund_intent SET status=?,close_reason=? WHERE channel=? AND transaction_id=? AND status=?",
                nextStatus, reason, ascii(in.channel()), ascii(in.transactionId()), current) != 1) {
            throw new MallException(503, "退款待办状态冲突");
        }
        if (!ordersOwnPayment) {
            return orderStatus;
        }
        if (jdbc.update("UPDATE oms_order SET status=? WHERE order_sn=? AND status=?", nextOrder, in.orderId(), expectedOrder) != 1) {
            throw new MallException(503, "订单本身的退款待办与订单状态不一致");
        }
        return nextOrder;
    }

    private int consignmentEffect(MallApi.Revision in, boolean revoked, int orderStatus) {
        if (revoked && orderStatus == 1) {
            var intents = jdbc.queryForList("SELECT status FROM commerce_delivery_intent WHERE order_sn=? FOR UPDATE", in.orderId());
            if (!intents.isEmpty() && "PENDING".equals(intents.getFirst().get("status"))) {
                jdbc.update("UPDATE commerce_delivery_intent SET status='REVOKED' WHERE order_sn=? AND status='PENDING'", in.orderId());
            }
            jdbc.update("UPDATE commerce_consignment SET state='MANUAL' WHERE current_order_sn=?", in.orderId());
            if (jdbc.update("UPDATE oms_order SET status=7 WHERE order_sn=? AND status=1", in.orderId()) != 1) {
                throw new MallException(503, "寄售订单状态冲突");
            }
            afterCommit(() -> LOG.error("consignment payment revoked before delivery, delivery halted for manual handling: order={} channel={} revision={}",
                    in.orderId(), in.channel(), in.revision()));
            return 7;
        }
        if (revoked && orderStatus == 2) {
            var incomes = jdbc.query("SELECT account FROM commerce_seller_income WHERE order_sn=?",
                    (row, n) -> row.getBytes(1), in.orderId());
            if (!incomes.isEmpty()) {
                byte[] sellerAccount = incomes.getFirst();
                jdbc.update("INSERT INTO commerce_seller_guard(account) VALUES(?) ON DUPLICATE KEY UPDATE account=account", sellerAccount);
                jdbc.queryForObject("SELECT account FROM commerce_seller_guard WHERE account=? FOR UPDATE", byte[].class, sellerAccount);
                jdbc.update("UPDATE commerce_seller_income SET available_ms=? WHERE order_sn=? AND paid_fen+reserved_fen+forfeited_fen<net_fen",
                        Long.MAX_VALUE, in.orderId());
            }
            jdbc.update("UPDATE commerce_consignment SET state='MANUAL' WHERE current_order_sn=?", in.orderId());
            if (jdbc.update("UPDATE oms_order SET status=7 WHERE order_sn=? AND status=2", in.orderId()) != 1) {
                throw new MallException(503, "寄售订单状态冲突");
            }
            afterCommit(() -> LOG.error("consignment payment revoked after delivery, unpaid seller income frozen for manual handling: order={} channel={} revision={}",
                    in.orderId(), in.channel(), in.revision()));
            return 7;
        }
        afterCommit(() -> LOG.error("consignment payment changed by the channel, manual handling needed: order={} channel={} revision={} fact={}",
                in.orderId(), in.channel(), in.revision(), in.fact()));
        return orderStatus;
    }

    private int reversalEffect(MallApi.Revision in, boolean revoked, int orderStatus) {
        if (!BOUND_OFFICIAL_STATES.contains(orderStatus)) {
            throw new MallException(503, "绑定付款的官方订单状态无法处理");
        }
        if (jdbc.queryForList("SELECT status FROM commerce_delivery_intent WHERE order_sn=?", in.orderId()).size() != 1) {
            throw new MallException(503, "官方订单的绑定付款缺交付待办");
        }
        var existing = jdbc.queryForList("SELECT revision FROM commerce_reversal_intent WHERE order_sn=? FOR UPDATE", in.orderId());
        if (existing.isEmpty() && !revoked) {
            return orderStatus;
        }
        long now = clock.millis();
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO commerce_reversal_intent(order_sn,revision,status,updated_ms) VALUES(?,?,'PENDING',?)",
                    in.orderId(), in.revision(), now);
        } else if (jdbc.update("UPDATE commerce_reversal_intent SET revision=?,status='PENDING',updated_ms=? WHERE order_sn=? AND revision<?",
                in.revision(), now, in.orderId(), in.revision()) != 1) {
            throw new MallException(503, "扣回待办的修订号超过付款回执");
        }
        if (orderStatus != 8 && jdbc.update("UPDATE oms_order SET status=8 WHERE order_sn=? AND status=?", in.orderId(), orderStatus) != 1) {
            throw new MallException(503, "订单状态冲突");
        }
        afterCommit(() -> LOG.warn("payment changed by the channel, game grant reconciliation queued: order={} channel={} revision={} fact={}",
                in.orderId(), in.channel(), in.revision(), in.fact()));
        return 8;
    }

    private static void afterCommit(Runnable log) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { log.run(); }
            });
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
