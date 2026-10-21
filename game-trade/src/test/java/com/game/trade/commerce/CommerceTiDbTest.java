package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.proto.trade.*;
import com.game.trade.store.TradeTables;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

/** 只验交易SQL合同与恢复，不把人工注入资产终局的夹具冒充scene端到端。 */
class CommerceTiDbTest {
    private DruidDataSource pool;
    private Connection admin;
    private String database;
    private CommerceEngine e;
    @BeforeEach
    void start() throws Exception {
        String url = System.getProperty("game.it.mysql");
        Assumptions.assumeTrue(url != null, "未配置隔离TiDB");
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):4000"))
            throw new IllegalArgumentException("仅允许本机4000端口TiDB随机库");
        admin = DriverManager.getConnection(url + "/", "root", "");
        database = "trade_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var q = admin.createStatement()) {
            q.execute("CREATE DATABASE `" + database + "`");
        }
        pool = new DruidDataSource();
        pool.setUrl(url + "/" + database);
        pool.setUsername("root");
        pool.setPassword("");
        pool.setMaxActive(12);
        pool.setConnectionInitSqls(List.of(StrongTiDbConnectionPolicy.SESSION_SQL));
        pool.init();
        try (var c = pool.getConnection()) {
            StrongTiDbConnectionPolicy.verify(c, true);
        }
        TradeTables.sync(pool, java.time.Duration.ofSeconds(30));
        AtomicLong ids = new AtomicLong(1000);
        var policy = new CommerceRules.Policy(Set.of(10), Set.of(), Set.of(1), false,
            List.of(TradeMarketView.newBuilder()
                    .setMarketId(1)
                    .setItemConfigId(10)
                    .setCurrencyType(1)
                    .setTitle("玄铁")
                    .build()));
        e = new CommerceEngine(new CommerceStore(pool), ids::incrementAndGet, policy, System::currentTimeMillis);
    }
    @AfterEach
    void close() throws Exception {
        if (pool != null)
            pool.close();
        if (admin != null) {
            try (var q = admin.createStatement()) {
                if (database == null || !database.matches("trade_it_[0-9a-f]{32}"))
                    throw new IllegalStateException("非法测试库");
                q.execute("DROP DATABASE `" + database + "`");
            }
            admin.close();
        }
    }
    Deadline budget() {
        return Deadline.after(10000);
    }
    @Test
    void 同出价并发与最高价后截止后重放_返回原合同原扣款_换载荷拒绝() throws Exception {
        var lot = e.lot(11,
            CreateAuctionLotRequest.newBuilder()
                .setClientRequestId("bid-replay-lot-001")
                .setAssets(TradeAssetSelection.newBuilder().putItems(10, 1))
                .setCurrencyType(1)
                .setOpeningPrice(50)
                .setMinimumIncrement(5)
                .setDurationMs(60000)
                .build(),
            budget());
        e.advance(lot, budget());
        complete(2);
        e.advance(e.store.get(lot.getCommerceId()), budget());
        var input = PlaceAuctionBidRequest.newBuilder()
                        .setLotId(Long.toUnsignedString(lot.getCommerceId()))
                        .setClientRequestId("bid-replay-request-001")
                        .setAmount(50)
                        .build();
        var matching = new CommerceMatching(e);
        long originalId;
        try (var workers = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Future<Long>>();
            for (int i = 0; i < 8; i++)
                calls.add(workers.submit(
                    () -> matching.bid(21, lot.getCommerceId(), input, budget()).getCommerceId()));
            originalId = calls.getFirst().get(15, TimeUnit.SECONDS);
            for (var call : calls)
                assertThat(call.get(15, TimeUnit.SECONDS)).isEqualTo(originalId);
        }
        e.advance(e.store.get(originalId), budget());
        var debit = e.store.ops("player_id=21 AND stream=3").getFirst();
        assertThat(debit.getDeadlineMs()).isEqualTo(lot.getExpiresMs());
        complete(2);
        e.advance(e.store.get(originalId), budget());
        matching.acceptBid(e.store.get(originalId), budget());
        assertThat(CommerceEngine.workflow(e.store.get(originalId)).getStage()).isEqualTo("bid_held");
        // 下一笔出价必须加价，丢回执后的原请求仍只查询原合同，不再冻结资金。
        assertThat(matching.bid(21, lot.getCommerceId(), input, budget()).getCommerceId()).isEqualTo(originalId);
        e.store.transaction(budget(), c -> {
            CommerceStore.execute(c, "UPDATE trade_commerce SET expires_ms=1 WHERE commerce_id=?", lot.getCommerceId());
            return null;
        });
        assertThat(matching.bid(21, lot.getCommerceId(), input, budget()).getCommerceId()).isEqualTo(originalId);
        assertThatThrownBy(
            () -> matching.bid(21, lot.getCommerceId(), input.toBuilder().setAmount(51).build(), budget()))
            .isInstanceOfSatisfying(CommerceRules.Refusal.class,
                denied -> assertThat(denied.tip()).isEqualTo(CommerceRules.REQUEST_CONFLICT));
        assertThatThrownBy(
            () -> matching.bid(21, lot.getCommerceId(), input.toBuilder().setLotId("another-lot").build(), budget()))
            .isInstanceOfSatisfying(CommerceRules.Refusal.class,
                denied -> assertThat(denied.tip()).isEqualTo(CommerceRules.REQUEST_CONFLICT));
        assertThatThrownBy(()
                              -> matching.bid(21, lot.getCommerceId(),
                                  input.toBuilder().setClientRequestId("bid-replay-new-late-001").build(), budget()))
            .isInstanceOfSatisfying(CommerceRules.Refusal.class,
                denied -> assertThat(denied.tip())
                              .isEqualTo(com.game.table.TradeErrorTip.trade_error.kTradeAuctionEnded_VALUE));
        assertThat(e.store.list("kind=?", CommerceRules.BID_KIND)).hasSize(1);
        assertThat(e.store.ops("player_id=21 AND stream=3")).singleElement().satisfies(current -> {
            assertThat(current.getOpId()).isEqualTo(debit.getOpId());
            assertThat(current.getStreamEpoch()).isEqualTo(debit.getStreamEpoch());
            assertThat(current.getSeq()).isEqualTo(debit.getSeq());
        });
    }
    void complete(int status) throws Exception {
        e.store.transaction(budget(), c -> {
            CommerceStore.execute(c, "UPDATE trade_asset_op SET status=?,durable=1 WHERE status=1", status);
            return null;
        });
    }
    @Test
    void 推迟不改业务版本_旧观察不能覆盖新推进_隔离保留坏原件() throws Exception {
        var row = e.consignment(11,
            CreateConsignmentRequest.newBuilder().setClientRequestId("defer-cas-001")
                .setAssets(TradeAssetSelection.newBuilder().putItems(10, 1))
                .setCurrencyType(1).setPrice(50).setDurationMs(60000).build(), budget());
        long now = System.currentTimeMillis();
        assertThat(e.store.defer(row, now + 60000, budget())).isTrue();
        assertThat(e.store.get(row.getCommerceId()).getVersion()).isEqualTo(row.getVersion());
        assertThat(e.store.due(now, 100)).extracting(com.game.trade.store.pb.TradeCommerceRow::getCommerceId)
            .doesNotContain(row.getCommerceId());
        e.advance(e.store.get(row.getCommerceId()), budget());
        assertThat(e.store.defer(row, now + 60000, budget())).isFalse();
        assertThat(e.store.quarantine(row, budget())).isFalse();
        var current = e.store.get(row.getCommerceId());
        var badBytes = com.google.protobuf.ByteString.copyFrom(new byte[] {(byte) 255});
        e.store.transaction(budget(), c -> {
            e.store.save(c, current.toBuilder().setWorkflowProto(badBytes));
            return null;
        });
        assertThat(e.store.quarantine(e.store.get(row.getCommerceId()), budget())).isTrue();
        var quarantined = e.store.get(row.getCommerceId());
        assertThat(quarantined.getStatus()).isEqualTo(CommerceRules.MANUAL);
        assertThat(quarantined.getWorkflowProto()).isEqualTo(badBytes);
        assertThat(e.store.due(System.currentTimeMillis(), 100))
            .extracting(com.game.trade.store.pb.TradeCommerceRow::getCommerceId).doesNotContain(row.getCommerceId());
        assertThat(e.store.ops("ref_id=?", row.getCommerceId())).singleElement()
            .satisfies(op -> assertThat(op.getBusinessConsumed()).isFalse());
    }

    @Test
    void 放弃扣款不靠durable推进_人工已扣实例无原件立即隔离() throws Exception {
        var row = e.consignment(11,
            CreateConsignmentRequest.newBuilder().setClientRequestId("abandoned-001")
                .setAssets(TradeAssetSelection.newBuilder().putItems(10, 1))
                .setCurrencyType(1).setPrice(50).setDurationMs(60000).build(), budget());
        e.advance(row, budget());
        e.store.transaction(budget(), c -> {
            CommerceStore.execute(c, "UPDATE trade_asset_op SET status=6,durable=0 WHERE ref_id=?", row.getCommerceId());
            return null;
        });
        e.advance(e.store.get(row.getCommerceId()), budget());
        assertThat(e.store.get(row.getCommerceId()).getStatus()).isEqualTo(CommerceRules.CANCELLED);
        assertThat(e.store.ops("stream=4")).isEmpty();

        var w = TradeCommerceWorkflow.newBuilder().setStage("escrow")
                    .setSellerAssets(TradeAssetSelection.newBuilder().addItemUuids(77));
        var instanceRow = e.create(22, "manual-instance-001",
            CreateConsignmentRequest.newBuilder().setClientRequestId("manual-instance-001").build(),
            com.game.trade.store.pb.TradeCommerceRow.newBuilder().setSellerPlayerId(22)
                .setKind(3).setStatus(3).setExpiresMs(System.currentTimeMillis() + 60000), w, budget());
        e.advance(instanceRow, budget());
        e.store.transaction(budget(), c -> {
            CommerceStore.execute(c, "UPDATE trade_asset_op SET status=2,durable=0,resolved_by='test-operator' WHERE ref_id=?",
                instanceRow.getCommerceId());
            return null;
        });
        try (var runtime = new CommerceRuntime(e,
                 org.mockito.Mockito.mock(com.game.discovery.asset.ReliableAssetDelivery.class),
                 org.mockito.Mockito.mock(com.game.player.store.asset.PersistedAssetLedgerReader.class))) {
            runtime.tick(budget());
        }
        assertThat(e.store.get(instanceRow.getCommerceId()).getStatus()).isEqualTo(CommerceRules.MANUAL);
        assertThat(e.store.ops("stream=4")).isEmpty();
        assertThat(e.store.ops("ref_id=?", instanceRow.getCommerceId())).singleElement()
            .satisfies(op -> assertThat(op.getBusinessConsumed()).isFalse());
    }
    @Test
    void 寄售并发只有一个买家_新恢复者沿用原seq_双侧完成才结算() throws Exception {
        var row = e.consignment(11,
            CreateConsignmentRequest.newBuilder()
                .setClientRequestId("listing-001")
                .setAssets(TradeAssetSelection.newBuilder().putItems(10, 2))
                .setCurrencyType(1)
                .setPrice(50)
                .setDurationMs(60000)
                .build(),
            budget());
        e.advance(row, budget());
        assertThat(e.store.get(row.getCommerceId()).getStatus()).isEqualTo(3);
        complete(2);
        e.advance(e.store.get(row.getCommerceId()), budget());
        try (var workers = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Future<Boolean>>();
            for (long player = 21; player < 29; player++) {
                long actor = player;
                calls.add(workers.submit(() -> {
                    try {
                        e.mutate(actor, row.getCommerceId(), "buy", budget(), "buy-request-" + actor);
                        return true;
                    } catch (CommerceRules.Refusal denied) {
                        assertThat(denied.tip()).isEqualTo(CommerceRules.CONFLICT);
                        return false;
                    }
                }));
            }
            int wins = 0;
            for (var f : calls)
                if (f.get(15, TimeUnit.SECONDS))
                    wins++;
            assertThat(wins).isEqualTo(1);
        }
        var restarted = new CommerceEngine(
            new CommerceStore(pool), new AtomicLong(2000)::incrementAndGet, e.policy, System::currentTimeMillis);
        restarted.advance(restarted.store.get(row.getCommerceId()), budget());
        complete(2);
        restarted.advance(restarted.store.get(row.getCommerceId()), budget());
        assertThat(restarted.store.get(row.getCommerceId()).getStatus()).isEqualTo(4);
        complete(2);
        restarted.advance(restarted.store.get(row.getCommerceId()), budget());
        assertThat(restarted.store.get(row.getCommerceId()).getStatus()).isEqualTo(11);
        assertThat(restarted.store.ops("stream=4")).hasSize(2);
    }
    @Test
    void partial只进人工_不继续交付或全量补偿() throws Exception {
        var row = e.consignment(11,
            CreateConsignmentRequest.newBuilder()
                .setClientRequestId("partial-001")
                .setAssets(TradeAssetSelection.newBuilder().putItems(10, 2))
                .setCurrencyType(1)
                .setPrice(50)
                .setDurationMs(60000)
                .build(),
            budget());
        e.advance(row, budget());
        complete(5);
        e.advance(e.store.get(row.getCommerceId()), budget());
        assertThat(e.store.get(row.getCommerceId()).getStatus()).isEqualTo(10);
        assertThat(e.store.ops("stream=4")).isEmpty();
    }

    @Test
    void 扣款被拒后同购买请求不重开扣款_显式新请求才允许再次购买() throws Exception {
        var row = e.consignment(11,
            CreateConsignmentRequest.newBuilder()
                .setClientRequestId("retry-offer-001")
                .setAssets(TradeAssetSelection.newBuilder().putItems(10, 1))
                .setCurrencyType(1)
                .setPrice(50)
                .setDurationMs(60000)
                .build(),
            budget());
        e.advance(row, budget());
        complete(2);
        e.advance(e.store.get(row.getCommerceId()), budget());
        e.mutate(22, row.getCommerceId(), "buy", budget(), "buy-retry-001");
        e.advance(e.store.get(row.getCommerceId()), budget());
        complete(3);
        e.advance(e.store.get(row.getCommerceId()), budget());
        var before = CommerceEngine.workflow(e.store.get(row.getCommerceId())).build();
        assertThat(before.getStage()).isEqualTo("open");
        assertThatThrownBy(() -> e.mutate(22, row.getCommerceId(), "buy", budget(), "buy-retry-001"))
            .isInstanceOfSatisfying(
                CommerceRules.Refusal.class, denied -> assertThat(denied.tip()).isEqualTo(CommerceRules.CONFLICT));
        e.advance(e.store.get(row.getCommerceId()), budget());
        assertThat(CommerceEngine.workflow(e.store.get(row.getCommerceId())).build()).isEqualTo(before);
        assertThat(e.store.ops("player_id=22 AND stream=3")).hasSize(1);
        e.mutate(22, row.getCommerceId(), "buy", budget(), "buy-retry-002");
        e.advance(e.store.get(row.getCommerceId()), budget());
        assertThat(e.store.ops("player_id=22 AND stream=3")).hasSize(2);
    }

    long openMarket(long player, int side, long price, int quantity) throws Exception {
        var row = e.market(player,
            PlaceMarketOrderRequest.newBuilder()
                .setClientRequestId("market-open-" + player)
                .setMarketId(1)
                .setItemConfigId(10)
                .setSideValue(side)
                .setUnitPrice(price)
                .setQuantity(quantity)
                .setCurrencyType(1)
                .build(),
            budget());
        e.advance(row, budget());
        complete(2);
        e.advance(e.store.get(row.getCommerceId()), budget());
        return row.getCommerceId();
    }

    @Test
    void 撮合调度采用引擎时钟_数据库时钟不把新成交与父单推到未来() throws Exception {
        long scheduleNow = 1_000_000;
        e = new CommerceEngine(e.store, e.ids, e.policy, () -> scheduleNow);
        long sell = openMarket(11, 2, 10, 2), buy = openMarket(22, 1, 15, 2);
        new CommerceMatching(e).match(e.store.get(buy), budget());
        assertThat(e.store.get(sell).getUpdatedMs()).isEqualTo(scheduleNow);
        assertThat(e.store.get(buy).getUpdatedMs()).isEqualTo(scheduleNow);
        var child = e.store.list("parent_id=?", buy).getFirst();
        assertThat(child.getUpdatedMs()).isEqualTo(scheduleNow);
        assertThat(child.getCreatedMs()).isGreaterThan(scheduleNow);
        assertThat(e.store.due(scheduleNow, 100))
            .extracting(com.game.trade.store.pb.TradeCommerceRow::getCommerceId).contains(child.getCommerceId());
    }

    @Test
    void 订单簿价格时间优先_按maker价退差_部分撤单等全部子成交交付() throws Exception {
        long expensive = openMarket(11, 2, 12, 2), first = openMarket(12, 2, 10, 2), second = openMarket(13, 2, 10, 2);
        long buy = openMarket(22, 1, 15, 3);
        var matching = new CommerceMatching(e);
        matching.match(e.store.get(buy), budget());
        matching.match(e.store.get(buy), budget());
        var children = e.store.list("parent_id=? ORDER BY commerce_id", buy);
        assertThat(children).hasSize(2);
        assertThat(children.getFirst().getCounterpartyId()).isEqualTo(first);
        assertThat(children.getFirst().getQuantity()).isEqualTo(2);
        assertThat(children.getLast().getCounterpartyId()).isEqualTo(second);
        assertThat(children.getLast().getQuantity()).isEqualTo(1);
        assertThat(e.store.get(expensive).getFilledQuantity()).isZero();
        for (var row : children) {
            assertThat(row.getUnitPrice()).isEqualTo(10);
            e.advance(row, budget());
            var workflow = CommerceEngine.workflow(e.store.get(row.getCommerceId()));
            assertThat(CommerceRules.leg(workflow, "buyer_surplus").getBundle().getCurrencies(0).getAmount())
                .isEqualTo(row.getQuantity() * 5L);
        }
        e.mutate(13, second, "cancel", budget());
        e.advance(e.store.get(second), budget());
        assertThat(CommerceRules.leg(CommerceEngine.workflow(e.store.get(second)), "seller_return")
                       .getBundle()
                       .getItems(0)
                       .getCount())
            .isEqualTo(1);
        e.store.transaction(budget(), c -> {
            CommerceStore.execute(
                c, "UPDATE trade_asset_op SET status=2,durable=1 WHERE ref_id=? AND stream=4", second);
            return null;
        });
        e.advance(e.store.get(second), budget());
        assertThat(e.store.get(second).getStatus()).isEqualTo(6);
        assertThat(CommerceEngine.workflow(e.store.get(second)).getStage()).isEqualTo("market_wait");
        complete(2);
        for (var row : children) e.advance(e.store.get(row.getCommerceId()), budget());
        e.advance(e.store.get(second), budget());
        assertThat(e.store.get(second).getStatus()).isEqualTo(7);
    }
}
