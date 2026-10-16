package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class MallOrderStoreTest {
    private static final String BUYER = "18446744073709551615";

    @Test
    void 全uint64买家_价格快照_终态重放_不会重新预占() {
        try (var db = new MallTestDatabase()) {
            db.sku(1, 10);
            MallApi.CreateOrder request = new MallApi.CreateOrder("request-001", 1, 2);
            MallApi.Order first = db.store.create(BUYER, request);
            assertThat(first.buyerId()).isEqualTo(BUYER);
            assertThat(first.amountMinor()).isEqualTo(2500);
            assertThat(first.paymentDeadlineMs()).isEqualTo(1_900_000);
            db.jdbc.update("UPDATE pms_sku_stock SET price_minor=999 WHERE id=1");
            assertThat(db.store.create(BUYER, request)).isEqualTo(first);
            assertThat(db.count("oms_order")).isEqualTo(1);
            assertThat(db.stock(1, "lock_stock")).isEqualTo(2);
            assertThatThrownBy(() -> db.store.get("2", first.orderId())).isInstanceOfSatisfying(MallException.class, e -> assertThat(e.status()).isEqualTo(404));
            assertThat(db.store.cancel(BUYER, first.orderId()).state()).isEqualTo("CANCELLED");
            assertThat(db.store.cancel(BUYER, first.orderId()).state()).isEqualTo("CANCELLED");
            assertThat(db.store.create(BUYER, request).state()).isEqualTo("CANCELLED");
            assertThat(db.stock(1, "lock_stock")).isZero();
            assertThatThrownBy(() -> db.store.create("018", request)).isInstanceOf(MallException.class);
        }
    }

    @Test
    void 未付上限内原子计数_取消释放额度_同请求不重复占用() {
        try (var db = new MallTestDatabase()) {
            db.sku(1, 20);
            List<MallApi.Order> orders = new ArrayList<>();
            for (int i = 0; i < 5; i++) orders.add(db.store.create(BUYER, new MallApi.CreateOrder("pending-00" + i, 1, 1)));
            assertThatThrownBy(() -> db.store.create(BUYER, new MallApi.CreateOrder("pending-006", 1, 1)))
                    .isInstanceOfSatisfying(MallException.class, e -> assertThat(e.status()).isEqualTo(429));
            assertThat(db.store.create(BUYER, new MallApi.CreateOrder("pending-000", 1, 1))).isEqualTo(orders.getFirst());
            db.store.cancel(BUYER, orders.getFirst().orderId());
            db.store.create(BUYER, new MallApi.CreateOrder("pending-006", 1, 1));
            assertThat(db.stock(1, "lock_stock")).isEqualTo(5);
        }
    }

    @Test
    void 大小写不同的不透明请求号与渠道流水保持独立() {
        try (var db = new MallTestDatabase()) {
            db.sku(1, 10);
            MallApi.Order first = db.store.create(BUYER, new MallApi.CreateOrder("OpaqueAA", 1, 1));
            MallApi.Order second = db.store.create(BUYER, new MallApi.CreateOrder("opaqueAA", 1, 1));
            assertThat(first.orderId()).isNotEqualTo(second.orderId());
            db.store.recordTrustedPayment(payment(first, "TxA"));
            db.store.recordTrustedPayment(payment(second, "txa"));
            assertThat(db.count("commerce_payment_receipt")).isEqualTo(2);
            assertThat(db.count("commerce_delivery_intent")).isEqualTo(2);
            assertThat(db.stock(1, "stock")).isEqualTo(8);
        }
    }

    @Test
    void 多副本同业务请求并发只预占一次() throws Exception {
        try (var db = new MallTestDatabase(); var workers = Executors.newFixedThreadPool(8)) {
            db.sku(1, 20);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MallApi.Order>> replies = new ArrayList<>();
            for (int i = 0; i < 16; i++) replies.add(workers.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return db.store.create(BUYER, new MallApi.CreateOrder("same-request-001", 1, 1));
            }));
            start.countDown();
            String first = replies.getFirst().get(10, TimeUnit.SECONDS).orderId();
            for (Future<MallApi.Order> reply : replies) assertThat(reply.get(10, TimeUnit.SECONDS).orderId()).isEqualTo(first);
            assertThat(db.count("oms_order")).isEqualTo(1);
            assertThat(db.stock(1, "lock_stock")).isEqualTo(1);
        }
    }

    @Test
    void 多买家争最后库存只成功一笔() throws Exception {
        try (var db = new MallTestDatabase(); var workers = Executors.newFixedThreadPool(8)) {
            db.sku(1, 1);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> replies = new ArrayList<>();
            for (int i = 1; i <= 16; i++) {
                String buyer = Integer.toString(i);
                replies.add(workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try { db.store.create(buyer, new MallApi.CreateOrder("last-stock-001", 1, 1)); return true; }
                    catch (MallException denied) { assertThat(denied.status()).isEqualTo(409); return false; }
                }));
            }
            start.countDown();
            int successful = 0;
            for (Future<Boolean> reply : replies) if (reply.get(10, TimeUnit.SECONDS)) successful++;
            assertThat(successful).isEqualTo(1);
            assertThat(db.count("oms_order")).isEqualTo(1);
            assertThat(db.stock(1, "stock")).isEqualTo(1);
            assertThat(db.stock(1, "lock_stock")).isEqualTo(1);
        }
    }

    @Test
    void 付款取消竞态只有一个终态_收据与交付意图不分离() throws Exception {
        try (var db = new MallTestDatabase(); var workers = Executors.newFixedThreadPool(2)) {
            db.sku(1, 20);
            for (int i = 0; i < 10; i++) {
                MallApi.Order order = db.store.create(BUYER, new MallApi.CreateOrder("racing-order-00" + i, 1, 1));
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> cancel = workers.submit(() -> compete(start, () -> db.store.cancel(BUYER, order.orderId())));
                Future<Boolean> pay = workers.submit(() -> compete(start, () -> db.store.recordTrustedPayment(payment(order, order.orderId()))));
                start.countDown();
                boolean cancelled = cancel.get(10, TimeUnit.SECONDS);
                boolean paid = pay.get(10, TimeUnit.SECONDS);
                assertThat(cancelled ^ paid).isTrue();
                assertThat(db.store.get(BUYER, order.orderId()).state()).isEqualTo(cancelled ? "CANCELLED" : "DELIVERY_PENDING");
                assertThat(db.stock(1, "lock_stock")).isZero();
                assertThat(db.count("commerce_payment_receipt")).isEqualTo(db.count("commerce_delivery_intent"));
                assertThat(db.stock(1, "stock") + db.count("commerce_payment_receipt")).isEqualTo(20);
            }
        }
    }

    @Test
    void 重放支付无重复扣减_跨订单盗用流水与错金额失败() {
        try (var db = new MallTestDatabase()) {
            db.sku(1, 10);
            MallApi.Order first = db.store.create(BUYER, new MallApi.CreateOrder("payment-first", 1, 1));
            MallApi.Order second = db.store.create(BUYER, new MallApi.CreateOrder("payment-second", 1, 1));
            assertThatThrownBy(() -> db.store.recordTrustedPayment(new MallApi.Payment(first.orderId(), "test-channel", "Wrong", 1, "CNY"))).isInstanceOf(MallException.class);
            assertThat(db.count("commerce_payment_receipt")).isZero();
            assertThat(db.store.recordTrustedPayment(payment(first, "TxSame")).state()).isEqualTo("DELIVERY_PENDING");
            assertThat(db.store.recordTrustedPayment(payment(first, "TxSame")).state()).isEqualTo("DELIVERY_PENDING");
            assertThatThrownBy(() -> db.store.recordTrustedPayment(payment(second, "TxSame"))).isInstanceOf(MallException.class);
            assertThat(db.stock(1, "stock")).isEqualTo(9);
            assertThat(db.stock(1, "lock_stock")).isEqualTo(1);
            assertThat(db.count("commerce_payment_receipt")).isEqualTo(1);
            assertThat(db.count("commerce_delivery_intent")).isEqualTo(1);
        }
    }

    @Test
    void 交付意图写入失败时付款收据库存状态全部回滚() {
        try (var db = new MallTestDatabase()) {
            db.sku(1, 10);
            MallApi.Order order = db.store.create(BUYER, new MallApi.CreateOrder("rollback-order", 1, 1));
            db.jdbc.execute("DROP TABLE commerce_delivery_intent");
            assertThatThrownBy(() -> db.store.recordTrustedPayment(payment(order, "RollbackTx"))).isInstanceOf(DataAccessException.class);
            assertThat(db.store.get(BUYER, order.orderId()).state()).isEqualTo("AWAITING_PAYMENT");
            assertThat(db.count("commerce_payment_receipt")).isZero();
            assertThat(db.stock(1, "stock")).isEqualTo(10);
            assertThat(db.stock(1, "lock_stock")).isEqualTo(1);
        }
    }

    private static MallApi.Payment payment(MallApi.Order order, String transaction) {
        return new MallApi.Payment(order.orderId(), "test-channel", transaction, order.amountMinor(), order.currency());
    }

    private static boolean compete(CountDownLatch start, Runnable operation) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try { operation.run(); return true; }
        catch (MallException denied) { assertThat(denied.status()).isEqualTo(409); return false; }
    }
}
