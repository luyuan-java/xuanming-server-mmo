package com.game.mall;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.game.common.RunMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 原OMS接口与独立随机库；回执是trade SQL终局夹具，不代表真实scene、外部支付或玩家过户验收。 */
class RmbConsignmentStoreTest {
    @Test void 登记重放冻结条款_同账号自购与请求换角色拒绝_原账号保留订单权() {
        try (var f = new Fixture()) {
            var original = f.register("1001", 101);
            assertEquals("PUBLIC_NOTICE", original.phase());
            assertEquals(original, f.rmb.register(f.escrow("1001", 101)));
            rejects(409, () -> f.rmb.register(f.escrow("1001", 102)));
            rejects(409, () -> f.orders.createConsignment(f.buy("1001", "seller", "22", "self-buy-request")));
            rejects(409, () -> f.orders.createConsignment(f.buy("1001", "buyer", "22", "notice-buy-request")));
            f.clock.add(1_000);
            var order = f.orders.createConsignment(f.buy("1001", "buyer", "22", "buy-original-request"));
            assertEquals(0, order.skuId()); assertEquals(1, order.quantity()); assertEquals(101, order.amountMinor());
            assertEquals("CNY", order.currency());
            assertEquals(order, f.orders.createConsignment(f.buy("1001", "buyer", "22", "buy-original-request")));
            rejects(409, () -> f.orders.createConsignment(f.buy("1001", "buyer", "23", "buy-original-request")));
            rejects(409, () -> f.orders.createConsignment(f.buy("7777", "buyer", "22", "buy-original-request")));
            rejects(404, () -> f.orders.get("22", order.orderId()));
            rejects(404, () -> f.orders.cancel("22", order.orderId()));
            assertTrue(f.orders.list("22", new DeliveryApi.OrderPage("", 20, "fixture-account", "1")).isEmpty());
            assertEquals(order, f.orders.getConsignment(new RmbConsignmentApi.OrderQuery("buyer", order.orderId())));
            rejects(404, () -> f.orders.getConsignment(new RmbConsignmentApi.OrderQuery("new-character-owner", order.orderId())));
            assertEquals(List.of(order), f.orders.listConsignments(new RmbConsignmentApi.OrderPage("buyer", "", 20)));
            assertEquals(1, f.db.count("oms_order")); assertEquals(0, f.db.count("pms_sku_stock"));
            assertEquals(0, f.db.count("commerce_order_grant"));
            var changedRules = new RmbConsignmentStore(f.db.jdbc, f.db.manager, f.clock,
                    new RmbConsignmentProperties(0L, 1L, 0L, 10_000, Set.of()), (RmbConsignmentStore.PayoutEvidenceVerifier) null);
            var replay = changedRules.register(f.escrow("1001", 101));
            assertEquals(500, replay.feeBps()); assertEquals(1_000, replay.coolingMs());
            assertEquals(original.noticeEndMs(), replay.noticeEndMs()); assertEquals(original.saleEndMs(), replay.saleEndMs());
        }
    }

    @Test void 取消释放原listing_迟到付款只退款且不夺新订单_永久交付失败无收入() {
        try (var f = new Fixture()) {
            f.register("1001", 101); f.clock.add(1_000);
            var first = f.orders.createConsignment(f.buy("1001", "buyer", "22", "late-old-request"));
            assertEquals("CANCELLED", f.orders.cancelConsignment(new RmbConsignmentApi.OrderQuery("buyer", first.orderId())).state());
            var second = f.orders.createConsignment(f.buy("1001", "other-buyer", "23", "late-new-request"));
            assertEquals("REFUND_PENDING", f.payments.pay(new MockPaymentApi.Pay(first.orderId(), "late-payment-request")).state());
            assertEquals(second.orderId(), f.db.jdbc.queryForObject("SELECT current_order_sn FROM commerce_consignment WHERE listing_id='1001'", String.class));
            assertEquals("LOCKED", f.db.jdbc.queryForObject("SELECT state FROM commerce_consignment WHERE listing_id='1001'", String.class));
            assertEquals(0, f.db.count("commerce_delivery_intent"));
            f.payments.pay(new MockPaymentApi.Pay(second.orderId(), "new-payment-request"));
            f.delivery.receipt(f.receipt(second.orderId(), "REFUND_PENDING"));
            assertEquals(0, f.db.count("commerce_seller_income"));
            assertEquals("RETURNING", f.db.jdbc.queryForObject("SELECT state FROM commerce_consignment WHERE listing_id='1001'", String.class));
            assertEquals(2, f.payments.reconcile().succeeded());
            assertEquals(2, f.db.count("commerce_refund_receipt"));
            assertEquals(0, f.db.count("commerce_seller_income"));
        }
    }

    @Test void 原交付回执与收入同事务_费用向上取整且冷静期到点才可预留() {
        try (var f = new Fixture()) {
            var order = f.paid("1001", 101);
            var pending = f.rmb.deliveries(new DeliveryApi.Poll(20, ""));
            assertEquals(1, pending.entries().size());
            assertEquals(order.orderId(), pending.entries().getFirst().intent().orderId());
            assertEquals("", pending.entries().getFirst().cursor());
            assertTrue(f.delivery.poll(new DeliveryApi.Poll(20, "")).entries().isEmpty(), "唯一托管资产不能变成官方SKU发物");
            var reachedIncome = new AtomicBoolean();
            var receiptFailure = new JdbcTemplate(f.db.source) {
                @Override public int update(String sql, Object... args) throws DataAccessException {
                    if (sql.startsWith("INSERT INTO commerce_delivery_receipt")) {
                        assertEquals(1, f.db.count("commerce_seller_income"), "同一事务内已实际写入收入，随后才注入回执存储故障");
                        reachedIncome.set(true);
                        throw new DataAccessResourceFailureException("本例原回执insert边界失败");
                    }
                    return super.update(sql, args);
                }
            };
            receiptFailure.setQueryTimeout(5);
            var failedDelivery = new DeliveryStore(receiptFailure, f.db.manager, f.db.props, RunMode.TEST, f.rmb);
            assertThrows(DataAccessException.class, () -> failedDelivery.receipt(f.receipt(order.orderId(), "DELIVERED")));
            assertTrue(reachedIncome.get());
            assertEquals(0, f.db.count("commerce_seller_income"));
            assertEquals("DELIVERY_PENDING", f.orders.getConsignment(new RmbConsignmentApi.OrderQuery("buyer", order.orderId())).state());
            f.delivery.receipt(f.receipt(order.orderId(), "DELIVERED"));
            f.delivery.receipt(f.receipt(order.orderId(), "DELIVERED"));
            var income = f.rmb.incomes(new RmbConsignmentApi.IncomePage("seller", "", 20)).incomes().getFirst();
            assertEquals(101, income.grossFen()); assertEquals(6, income.feeFen()); assertEquals(95, income.netFen());
            assertEquals(f.clock.millis() + 1_000, income.availableMs());
            rejects(409, () -> f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "cooling-request", 1)));
            f.clock.add(999);
            rejects(409, () -> f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "cooling-request", 1)));
            f.clock.add(1);
            var withdrawal = f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "cooling-request", 95));
            assertEquals("REQUESTED", withdrawal.state());
            assertEquals(95, f.db.value("commerce_seller_income", "reserved_fen"));
            assertEquals(1, f.db.count("commerce_seller_income"));
            rejects(404, () -> f.rmb.getWithdrawal(new RmbConsignmentApi.WithdrawalQuery("other-account", withdrawal.withdrawalId())));
        }
    }

    @Test void 并发提现不重复分配_同请求重放_本地出款原件与原金额唯一绑定() throws Exception {
        try (var f = new Fixture()) {
            f.earned("1001", 101); f.clock.add(1_000);
            var outcomes = parallel(() -> outcome(() -> f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "withdraw-race-001", 70))),
                    () -> outcome(() -> f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "withdraw-race-002", 70))));
            assertEquals(List.of(200, 409), outcomes.stream().map(Outcome::status).sorted().toList());
            var winner = outcomes.stream().filter(value -> value.status() == 200).findFirst().orElseThrow().withdrawal();
            assertEquals(70, f.db.value("commerce_seller_income", "reserved_fen"));
            assertEquals(1, f.db.count("commerce_withdrawal"));
            var command = new MockPayoutStore.Pay(winner.withdrawalId(), "provider-request-001");
            var paid = f.payouts.pay(command);
            assertEquals("PAID", paid.state()); assertEquals(70, paid.amountFen());
            assertEquals(paid, f.payouts.pay(command));
            rejects(409, () -> f.payouts.pay(new MockPayoutStore.Pay(winner.withdrawalId(), "provider-request-002")));
            var last = f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "withdraw-last-001", 25));
            assertEquals(last, f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "withdraw-last-001", 25)));
            rejects(409, () -> f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "withdraw-last-001", 24)));
            rejects(409, () -> f.payouts.pay(new MockPayoutStore.Pay(last.withdrawalId(), command.requestId())));
            assertEquals("PAID", f.payouts.pay(new MockPayoutStore.Pay(last.withdrawalId(), "provider-request-003")).state());
            assertEquals(0, f.db.value("commerce_seller_income", "reserved_fen"));
            assertEquals(95, f.db.value("commerce_seller_income", "paid_fen"));
            assertEquals(2, f.db.count("commerce_mock_payout")); assertEquals(2, f.db.count("commerce_withdrawal_receipt"));
        }
    }

    @Test void 本地提供方先提交与结算先提交两个窗口均按原流水恢复() {
        for (boolean afterSettlement : List.of(false, true)) {
            try (var f = new Fixture()) {
                f.earned("1001", 101); f.clock.add(1_000);
                var original = f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "crash-withdrawal-001", 95));
                var boundary = mock(RmbConsignmentStore.class);
                var nanos = new AtomicLong();
                when(boundary.completeWithdrawal(any())).thenAnswer(call -> {
                    if (afterSettlement) {
                        var result = f.rmb.completeWithdrawal(call.getArgument(0));
                        // 返回后外层结算REQUIRES_NEW真实commit，再在applied开始前确定耗尽单调预算。
                        nanos.set(15_000_000_000L);
                        return result;
                    }
                    throw new MallException(503, "本例模拟公开结算边界未知结果");
                });
                f.provider.addBean("settlements", boundary);
                var crashWorker = new MockPayoutStore(f.db.jdbc, f.db.manager, f.db.props, f.provider.getBeanProvider(RmbConsignmentStore.class), f.clock, RunMode.TEST, nanos::get);
                rejects(503, () -> crashWorker.pay(new MockPayoutStore.Pay(original.withdrawalId(), "crash-provider-request")));
                assertEquals(1, f.db.count("commerce_mock_payout"));
                String tx = f.db.jdbc.queryForObject("SELECT transaction_id FROM commerce_mock_payout WHERE withdrawal_id=?", String.class, original.withdrawalId());
                assertEquals(0, f.db.value("commerce_mock_payout", "applied"));
                assertEquals(afterSettlement ? 1 : 0, f.db.count("commerce_withdrawal_receipt"));
                f.provider.addBean("settlements", f.rmb);
                var restarted = new MockPayoutStore(f.db.jdbc, f.db.manager, f.db.props, f.provider.getBeanProvider(RmbConsignmentStore.class), f.clock, RunMode.TEST, () -> 0L);
                assertEquals(1, restarted.reconcile().succeeded());
                assertEquals(tx, f.db.jdbc.queryForObject("SELECT transaction_id FROM commerce_mock_payout WHERE withdrawal_id=?", String.class, original.withdrawalId()));
                assertEquals(1, f.db.count("commerce_withdrawal_receipt"));
                assertEquals(95, f.db.value("commerce_seller_income", "paid_fen"));
                assertEquals(0, f.db.value("commerce_seller_income", "reserved_fen"));
            }
        }
    }

    @Test void 失败原件只解冻原分配_没有验证器或没有原件不能自报成功() {
        try (var f = new Fixture()) {
            f.earned("1001", 101); f.clock.add(1_000);
            var original = f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "failed-withdrawal-001", 95));
            var forged = new RmbConsignmentApi.WithdrawalReceipt(original.withdrawalId(), "mock", "mock-payout-" + java.util.UUID.randomUUID(), 95, "CNY", true);
            rejects(409, () -> f.rmb.completeWithdrawal(forged));
            var noVerifier = new RmbConsignmentStore(f.db.jdbc, f.db.manager, f.clock, f.rules, (RmbConsignmentStore.PayoutEvidenceVerifier) null);
            rejects(503, () -> noVerifier.completeWithdrawal(forged));
            // 只注入本地提供方的失败事实，不代表真实外部渠道失败；恢复仍走公开对账接口。
            f.db.jdbc.update("INSERT INTO commerce_mock_payout(withdrawal_id,request_id,transaction_id,amount_fen,successful,created_ms) VALUES(?,?,?,?,0,?)",
                    original.withdrawalId(), ascii("failed-provider-request"), ascii(forged.transactionId()), 95, f.clock.millis());
            assertEquals(1, f.payouts.reconcile().succeeded());
            assertEquals("FAILED", f.rmb.getWithdrawal(new RmbConsignmentApi.WithdrawalQuery("seller", original.withdrawalId())).state());
            assertEquals(0, f.db.value("commerce_seller_income", "reserved_fen"));
            assertEquals(0, f.db.value("commerce_seller_income", "paid_fen"));
            assertEquals(95, f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "retry-new-withdrawal", 95)).amountFen());
        }
    }

    @Test void 出款前二十坏项不挡尾五_预算未开始项不前移_重启不重复结算() {
        try (var f = new Fixture()) {
            f.earned("1001", 101); f.clock.add(1_000);
            var broken = mock(RmbConsignmentStore.class);
            when(broken.completeWithdrawal(any())).thenThrow(new MallException(503, "保留原件"));
            f.provider.addBean("settlements", broken);
            for (int i = 0; i < 25; i++) {
                var withdrawal = f.rmb.withdraw(new RmbConsignmentApi.Withdraw("seller", "fair-withdrawal-" + i, 1));
                rejects(503, () -> f.payouts.pay(new MockPayoutStore.Pay(withdrawal.withdrawalId(), "fair-provider-request-" + withdrawal.withdrawalId())));
            }
            var ids = f.db.jdbc.queryForList("SELECT withdrawal_id FROM commerce_mock_payout ORDER BY withdrawal_id", String.class);
            for (String id : ids.subList(0, 20)) { assertEquals(1, f.db.jdbc.update("UPDATE commerce_mock_payout SET amount_fen=2 WHERE withdrawal_id=?", id)); }
            f.provider.addBean("settlements", f.rmb);
            var calls = new AtomicInteger();
            var worker = new MockPayoutStore(f.db.jdbc, f.db.manager, f.db.props, f.provider.getBeanProvider(RmbConsignmentStore.class), f.clock,
                    RunMode.TEST, () -> calls.incrementAndGet() >= 7 ? 3_000_000_000L : 0L);
            var beforeStart = worker.reconcile();
            assertEquals(0, beforeStart.attempted()); assertEquals(0, beforeStart.scanFailures()); assertTrue(beforeStart.budgetExhausted());
            assertEquals(0, f.db.count("commerce_withdrawal_receipt"));
            var result = worker.reconcile();
            assertEquals(25, result.attempted()); assertEquals(5, result.succeeded()); assertEquals(20, result.failed());
            assertEquals(ids.subList(20, 25), f.db.jdbc.queryForList("SELECT withdrawal_id FROM commerce_withdrawal_receipt ORDER BY withdrawal_id", String.class));
            assertEquals(5, f.db.value("commerce_seller_income", "paid_fen"));
            assertEquals(20, f.db.value("commerce_seller_income", "reserved_fen"));
            var restarted = new MockPayoutStore(f.db.jdbc, f.db.manager, f.db.props, f.provider.getBeanProvider(RmbConsignmentStore.class), f.clock, RunMode.TEST, () -> 0L);
            assertEquals(20, restarted.reconcile().failed());
            assertEquals(5, f.db.count("commerce_withdrawal_receipt"));
        }
    }

    @Test void 退回固定上界与空尾页_原托管引用和proof必须一致() {
        try (var f = new Fixture()) {
            for (int i = 1001; i <= 1025; i++) { f.register(Integer.toString(i), 101); f.rmb.cancel(new RmbConsignmentApi.Cancel(Integer.toString(i), "seller")); }
            var page = f.rmb.returns(new DeliveryApi.Poll(20, ""));
            assertEquals(20, page.entries().size());
            String cursor = page.entries().getLast().cursor(); assertFalse(cursor.isEmpty());
            f.register("9999", 101); f.rmb.cancel(new RmbConsignmentApi.Cancel("9999", "seller"));
            var tail = f.rmb.returns(new DeliveryApi.Poll(20, cursor));
            assertEquals(5, tail.entries().size()); assertEquals("", tail.entries().getLast().cursor());
            assertTrue(tail.entries().stream().noneMatch(row -> row.intent().listingId().equals("9999")));
            var first = tail.entries().getFirst().intent();
            rejects(409, () -> f.rmb.returned(new RmbConsignmentApi.ReturnReceipt(first.listingId(), first.escrowReference(), "b".repeat(64), "c".repeat(64))));
            for (var entry : tail.entries()) {
                var row = entry.intent();
                var receipt = new RmbConsignmentApi.ReturnReceipt(row.listingId(), row.escrowReference(), row.escrowProof(), "c".repeat(64));
                f.rmb.returned(receipt); f.rmb.returned(receipt);
            }
            assertTrue(f.rmb.returns(new DeliveryApi.Poll(20, cursor)).entries().isEmpty());
            assertEquals(5, f.db.count("commerce_consignment_return"));
            assertEquals(21, f.db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_consignment WHERE state='RETURNING'", Integer.class));
            assertEquals(20, f.rmb.returns(new DeliveryApi.Poll(20, "")).entries().size());
        }
    }

    @Test void V4升级显式补V5_保留官方订单冻结与预占_规则和金额边界拒绝() throws Exception {
        try (var f = new Fixture()) {
            f.db.sku(1, 5, 125);
            var original = f.orders.create("1", new MallApi.CreateOrder("migration-official-order", 1, 2, "fixture-account", "1"));
            for (String table : List.of("commerce_official_owner", "commerce_mock_payout", "commerce_consignment_return", "commerce_withdrawal_receipt", "commerce_withdrawal_allocation", "commerce_withdrawal", "commerce_seller_income", "commerce_seller_guard", "commerce_consignment_order", "commerce_consignment")) {
                f.db.jdbc.execute("DROP TABLE " + table);
            }
            assertThrows(Exception.class, () -> new MallSchemaVerifier(f.db.source, f.db.props).verify());
            f.db.migrate("db/migration/V5__rmb_consignment.sql");
            new MallSchemaVerifier(f.db.source, f.db.props).verify();
            assertEquals(original, f.orders.paymentOrderView(original.orderId()));
            rejects(503, () -> f.orders.get("1",new MallApi.OwnedOrder(original.orderId(),"fixture-account","1")));
            assertEquals(0,f.db.count("commerce_official_owner"),"不能为V4历史订单猜测回填原账号和修订");
            assertEquals(5, f.db.value("pms_sku_stock", "stock")); assertEquals(2, f.db.value("pms_sku_stock", "lock_stock"));
            assertEquals(0, f.db.count("commerce_seller_income"));
        }
        assertThrows(IllegalArgumentException.class, () -> new RmbConsignmentProperties(0L, 1L, 0L, 500, Set.of(2)));
        assertEquals(MallOrderStore.MAX_AMOUNT_MINOR, RmbConsignmentStore.fee(MallOrderStore.MAX_AMOUNT_MINOR, 10_000));
        assertEquals(1, RmbConsignmentStore.fee(1, 1));
    }

    @Test void 过期未售资产进入同一退回集合_绑定货币不能登记() {
        try (var f = new Fixture()) {
            rejects(400, () -> f.rmb.register(new RmbConsignmentApi.Escrow("1002", "11", "seller", 1,
                    "CURRENCY", 2, "91002", "a".repeat(64), 101, "绑定币")));
            var original = f.register("1001", 101);
            f.clock.add(11_000);
            assertTrue(f.rmb.list(new RmbConsignmentApi.Page("", 20)).listings().isEmpty());
            rejects(409, () -> f.orders.createConsignment(f.buy("1001", "buyer", "22", "expired-buy-request")));
            var row = f.rmb.returns(new DeliveryApi.Poll(20, "")).entries().getFirst().intent();
            assertEquals(original.listingId(), row.listingId());
            f.rmb.returned(new RmbConsignmentApi.ReturnReceipt(row.listingId(), row.escrowReference(), row.escrowProof(), "c".repeat(64)));
            assertTrue(f.rmb.returns(new DeliveryApi.Poll(20, "")).entries().isEmpty());
            assertEquals(0, f.db.count("oms_order")); assertEquals(0, f.db.count("commerce_seller_income"));
        }
        var defaults = new RmbConsignmentProperties();
        assertEquals(172_800_000, defaults.noticeMs().longValue());
        assertEquals(259_200_000, defaults.coolingMs().longValue());
    }

    private record Outcome(int status, RmbConsignmentApi.Withdrawal withdrawal) {}
    private static Outcome outcome(Callable<RmbConsignmentApi.Withdrawal> action) throws Exception {
        try { return new Outcome(200, action.call()); } catch (MallException rejected) { return new Outcome(rejected.status(), null); }
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static void rejects(int status, Executable action) { assertEquals(status, assertThrows(MallException.class, action).status()); }
    private static <T> List<T> parallel(Callable<T> first, Callable<T> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(); List<T> results = new ArrayList<>(); List<Executable> errors = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            for (var action : List.of(first, second)) { futures.add(pool.submit(() -> { ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) { throw new AssertionError("并发起跑超时"); } return action.call(); })); }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            for (var future : futures) {
                try { results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)); }
                catch (ExecutionException error) { errors.add(() -> { throw error.getCause(); }); }
                catch (java.util.concurrent.TimeoutException error) { future.cancel(true); errors.add(() -> { throw error; }); }
            }
            assertAll("所有Future由主线程收集", errors); return results;
        } finally { start.countDown(); futures.forEach(future -> { if (!future.isDone()) { future.cancel(true); } }); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong now = new AtomicLong(1_760_000_000_000L);
        void add(long millis) { now.addAndGet(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!ZoneOffset.UTC.equals(zone)) { throw new IllegalArgumentException("仅供本例UTC时钟"); } return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }
    private static final class Fixture implements AutoCloseable {
        final MockPaymentTestDatabase db = new MockPaymentTestDatabase();
        final MutableClock clock = new MutableClock();
        final RmbConsignmentProperties rules = new RmbConsignmentProperties(1_000L, 10_000L, 1_000L, 500, Set.of(0));
        final StaticListableBeanFactory provider = new StaticListableBeanFactory();
        final MockPayoutStore payouts = new MockPayoutStore(db.jdbc, db.manager, db.props, provider.getBeanProvider(RmbConsignmentStore.class), clock, RunMode.TEST, () -> 0L);
        final RmbConsignmentStore rmb = new RmbConsignmentStore(db.jdbc, db.manager, clock, rules, payouts);
        final MallOrderStore orders = new MallOrderStore(db.jdbc, db.manager, clock, db.props, RunMode.TEST, rmb);
        final DeliveryStore delivery = new DeliveryStore(db.jdbc, db.manager, db.props, RunMode.TEST, rmb);
        final MockPaymentStore payments = new MockPaymentStore(db.jdbc, db.manager, clock, db.props, RunMode.TEST, orders, delivery, () -> 0L);
        Fixture() { provider.addBean("settlements", rmb); }
        RmbConsignmentApi.Escrow escrow(String id, long price) { return new RmbConsignmentApi.Escrow(id, "11", "seller", 1, "ITEMS", null, "9" + id, "a".repeat(64), price, "完整托管原件"); }
        RmbConsignmentApi.Listing register(String id, long price) { return rmb.register(escrow(id, price)); }
        RmbConsignmentApi.Buy buy(String id, String account, String player, String request) { return new RmbConsignmentApi.Buy(id, player, account, request, "1"); }
        MallApi.Order paid(String id, long price) { register(id, price); clock.add(1_000); var order = orders.createConsignment(buy(id, "buyer", "22", "buy-request-" + id)); return payments.pay(new MockPaymentApi.Pay(order.orderId(), "payment-request-" + id)); }
        MallApi.Order earned(String id, long price) { var order = paid(id, price); delivery.receipt(receipt(order.orderId(), "DELIVERED")); return order; }
        DeliveryApi.Receipt receipt(String order, String state) { return new DeliveryApi.Receipt(order, "9001", state, "d".repeat(64)); }
        @Override public void close() { db.close(); }
    }
}
