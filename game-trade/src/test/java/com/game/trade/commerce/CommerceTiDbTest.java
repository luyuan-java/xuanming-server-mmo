package com.game.trade.commerce;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;

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
    private AuthoritySqlFixture fixture;
    private CommerceEngine e;
    private CommerceStore fixtureStore;
    /** 只配置本机TiDB测试端口，非法数值必须在连接前失败。 */
    static int tiDbTestPort() {
        int port = Integer.parseInt(System.getProperty("game.it.tidb.port", "4000"));
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("game.it.tidb.port必须在1..65535");
        return port;
    }
    @BeforeEach
    void start() throws Exception {
        String url = System.getProperty("game.it.mysql");
        Assumptions.assumeTrue(url != null, "未配置隔离TiDB");
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + tiDbTestPort()))
            throw new IllegalArgumentException("仅允许本机game.it.tidb.port指定端口TiDB随机库");
        fixture=AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment(url,
                System.getProperty("game.it.mysql.user","root"),System.getenv().getOrDefault("GAME_MYSQL_PASSWORD",""),
                "trade_commerce",false,List.of(1)),(c,principal)->{
            TradeTables.registry().initializeFresh(c,principal);
            try(var statement=c.createStatement()){
            statement.execute("CREATE TABLE player(player_id BIGINT UNSIGNED PRIMARY KEY,owner_revision BIGINT UNSIGNED NOT NULL,trade_listing_id BIGINT UNSIGNED NOT NULL,account VARCHAR(64) NOT NULL,zone_id INT UNSIGNED NOT NULL DEFAULT 1)");
            for (int player = 1; player <= 100; player++)
                statement.execute("INSERT INTO player(player_id,owner_revision,trade_listing_id,account) VALUES(" + player + ",1,0,'fixture-account-" + player + "')");
            }
        });
        pool=new DruidDataSource();pool.setUrl(fixture.jdbcUrl("?connectTimeout=3000&socketTimeout=30000"));
        pool.setUsername(fixture.normalUser());pool.setPassword(fixture.normalPassword());pool.setMaxActive(12);pool.setMaxWait(3000);
        AuthorityDataSourcePolicy.protectDruid(pool,fixture.writerBoot(),true);
        TradeTables.sync(pool,java.time.Duration.ofSeconds(30));
        fixtureStore=new CommerceStore(fixture.schemaDataSource());
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
        if(fixture!=null)fixture.close();
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
        fixtureStore.transaction(budget(), c -> {
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
        fixtureStore.transaction(budget(), c -> {
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
        var originalOps = e.store.ops("ref_id=? ORDER BY op_id", row.getCommerceId());
        e.advance(row, budget());
        assertThat(e.store.get(row.getCommerceId())).isEqualTo(current);
        assertThat(e.store.ops("ref_id=? ORDER BY op_id", row.getCommerceId())).isEqualTo(originalOps);
        var badBytes = com.google.protobuf.ByteString.copyFrom(new byte[] {(byte) 255});
        fixtureStore.transaction(budget(), c -> {
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
        fixtureStore.transaction(budget(), c -> {
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
        fixtureStore.transaction(budget(), c -> {
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
        assertThat(e.store.get(row.getCommerceId()).getBuyerPlayerId()).isZero();
        assertThat(before.getBuyerIdentity().getAccount()).isEqualTo("fixture-account-22");
        assertThat(before.getBuyerIdentity().getOwnerRevision()).isEqualTo(1);
        var refusedDebit = e.store.ops("player_id=22 AND stream=3").getFirst();
        assertThatThrownBy(() -> e.mutate(22, row.getCommerceId(), "buy", budget(), "buy-retry-001"))
            .isInstanceOfSatisfying(
                CommerceRules.Refusal.class, denied -> assertThat(denied.tip()).isEqualTo(CommerceRules.CONFLICT));
        e.advance(e.store.get(row.getCommerceId()), budget());
        assertThat(CommerceEngine.workflow(e.store.get(row.getCommerceId())).build()).isEqualTo(before);
        assertThat(e.store.ops("player_id=22 AND stream=3")).hasSize(1);
        e.mutate(22, row.getCommerceId(), "buy", budget(), "buy-retry-002");
        e.advance(e.store.get(row.getCommerceId()), budget());
        assertThat(e.store.ops("player_id=22 AND stream=3")).hasSize(2);
        assertThat(e.store.ops("op_id=?", refusedDebit.getOpId())).containsExactly(refusedDebit);
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
    void 普通交易原账号修订在同SQL锁提交复核_同账号ABA不冒用旧会话或旧幂等键() throws Exception {
        var session = com.game.api.proto.SessionContext.newBuilder().setPlayerId(11).setAccount("fixture-account-11").setOwnerRevision(1).build();
        var input = CreateConsignmentRequest.newBuilder().setClientRequestId("actor-contract-request")
            .setAssets(TradeAssetSelection.newBuilder().putItems(10,1)).setPrice(50).setCurrencyType(1).setDurationMs(60000).build();
        var original = e.forActor(session).consignment(11,input,budget());
        assertThat(CommerceEngine.workflow(original).getSellerIdentity().getOwnerRevision()).isEqualTo(1);
        // 这里只注入已完成原合同和后续归属变化，验证提交接缝；不将其声称真实过户/资产结算验收。
        fixtureStore.transaction(budget(), c -> {
            CommerceStore.execute(c,"UPDATE trade_commerce SET status=11 WHERE commerce_id=?",original.getCommerceId());
            CommerceStore.execute(c,"UPDATE player SET account='new-owner',owner_revision=2 WHERE player_id=11"); return null;
        });
        assertThatThrownBy(() -> e.forActor(session).consignment(11,input.toBuilder().setClientRequestId("late-request-0002").build(),budget()))
            .isInstanceOf(SecurityException.class);
        fixtureStore.transaction(budget(), c -> { CommerceStore.execute(c,"UPDATE player SET account='fixture-account-11',owner_revision=3 WHERE player_id=11"); return null; });
        assertThatThrownBy(() -> e.forActor(session).consignment(11,input.toBuilder().setClientRequestId("late-request-0003").build(),budget()))
            .isInstanceOf(SecurityException.class);
        var current = e.forActor(session.toBuilder().setOwnerRevision(3).build()).consignment(11,input,budget());
        assertThat(current.getCommerceId()).isNotEqualTo(original.getCommerceId());
        assertThat(CommerceEngine.workflow(current).getSellerIdentity().getOwnerRevision()).isEqualTo(3);
        assertThat(e.store.list("1=1")).hasSize(2); assertThat(e.store.ops("1=1")).isEmpty();
    }

    @Test
    void 官方已付款意图绑定原账号修订_换主和同账号ABA只原单退款不建资产腿() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (boolean aba : List.of(false, true)) {
            long buyer = aba ? 23 : 22;
            String originalAccount = "fixture-account-" + buyer;
            var intent = json.createObjectNode().put("orderId", UUID.randomUUID().toString())
                .put("buyerId", Long.toUnsignedString(buyer)).put("buyerAccount", originalAccount).put("buyerOwnerRevision", "1")
                .put("skuId", 1).put("quantity", 1).put("itemConfigId", 10).put("itemCount", 1)
                .put("amountMinor", 100).put("currency", "CNY");
            // 独立注入归属变化来验证交付提交接缝；此用例不冒充真实角色过户验收。
            fixtureStore.transaction(budget(), c -> { CommerceStore.execute(c,
                "UPDATE player SET account=?,owner_revision=? WHERE player_id=?", aba ? originalAccount : "new-owner", aba ? 3L : 2L,buyer); return null; });
            List<com.fasterxml.jackson.databind.JsonNode> receipts = new ArrayList<>();
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/internal/deliveries/poll", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    var page=json.createObjectNode();var entry=page.putArray("entries").addObject();entry.set("intent",intent);entry.put("cursor","");
                    byte[] bytes=json.writeValueAsBytes(page);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }
            });
            server.createContext("/internal/deliveries/receipt", exchange -> {
                try(exchange) { receipts.add(json.readTree(exchange.getRequestBody()));exchange.sendResponseHeaders(200,-1); }
            });
            server.start();
            try {
                var properties=new OfficialMallProperties(true,"http://127.0.0.1:"+server.getAddress().getPort(),
                    "gateway-test-only-00000000000000001","delivery-test-only-0000000000000002");
                new OfficialMallClient(properties,e,json).tick(budget());
                var original=e.store.byRequest(CommerceEngine.key("official",buyer,intent.path("orderId").asText()));
                assertThat(original.getStatus()).isEqualTo(CommerceRules.CANCELLED);
                var workflow=CommerceEngine.workflow(original);assertThat(workflow.getStage()).isEqualTo("official_refund");
                assertThat(workflow.getBuyerIdentity().getAccount()).isEqualTo(originalAccount);
                assertThat(workflow.getBuyerIdentity().getOwnerRevision()).isEqualTo(1);
                assertThat(workflow.getLegsCount()).isZero();assertThat(e.store.ops("ref_id=?",original.getCommerceId())).isEmpty();
                // 另一副本只重放原合同与退款回执，不用当前账号重建新意图。
                new OfficialMallClient(properties,e,json).tick(budget());
                assertThat(receipts).hasSize(2).allMatch(value -> value.path("state").asText().equals("REFUND_PENDING")
                    && value.path("tradeOrderId").asText().equals(Long.toUnsignedString(original.getCommerceId())));
            } finally { server.stop(0); }
        }
    }

    @Test
    void 人民币意图换主先退款_原卖家退回提交再次核对持久原身份() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();long listingId=7001, originalDebitId=9001;
        var original=TradeRmbConsignmentWorkflow.newBuilder().setSellerAccount("fixture-account-11").setSellerOwnerRevision(1)
            .setAssetKind("ITEMS").setPriceFen(100).setEscrowReference("7001").setEscrowProof("a".repeat(64))
            .setRegisteredListing(RmbConsignmentView.newBuilder().setListingId(listingId).setSellerPlayerId(11));
        var workflow=TradeCommerceWorkflow.newBuilder().setStage("rmb_open").setRmb(original)
            .addLegs(TradeCommerceLeg.newBuilder().setRole("seller_debit").setPlayerId(11).setStream(3).setOpId(originalDebitId).setOutcome(2)
                .setBundle(com.game.proto.AssetBundle.newBuilder().addItems(com.game.proto.ItemGrant.newBuilder().setConfigId(10).setCount(1))));
        var row=com.game.trade.store.pb.TradeCommerceRow.newBuilder().setCommerceId(listingId).setRequestKey("rmb-original-owner-fixture")
            .setRequestDigest(com.google.protobuf.ByteString.copyFromUtf8("fixture-digest")).setKind(6).setSellerPlayerId(11)
            .setStatus(2).setVersion(1).setWorkflowProto(workflow.build().toByteString()).build();
        // 只注入已完成的SQL原扣款，真实seq/epoch由原enqueue产生；不冒充Scene扣款验收。
        e.store.ensureSeqs(budget(), 11);
        fixtureStore.transaction(budget(), c -> {
            long now=CommerceStore.sqlNow(c);
            e.store.enqueue(c,com.game.trade.store.pb.TradeAssetOpRow.newBuilder().setOpId(originalDebitId)
                .setPlayerId(11).setStream(3).setKind(1).setStatus(2).setDurable(true).setTxType(3)
                .setRefKind(2).setRefId(listingId).setPayload(workflow.getLegs(0).getBundle().toByteString())
                .setCreatedMs(now).setUpdatedMs(now).setLastSendMs(now));
            CommerceStore.insert(c,"trade_commerce",row);
            CommerceStore.execute(c,"UPDATE player SET account='new-buyer-owner',owner_revision=2 WHERE player_id=22");return null;
        });
        var originalDebit=e.store.ops("op_id=?",originalDebitId).getFirst();
        assertThat(originalDebit.getStatus()).isEqualTo(2);assertThat(originalDebit.getDurable()).isTrue();
        assertThat(originalDebit.getSeq()).isNotZero();assertThat(originalDebit.getStreamEpoch()).isNotZero();
        var intent=json.createObjectNode().put("orderId",UUID.randomUUID().toString()).put("buyerPlayerId","22")
            .put("buyerAccount","fixture-account-22").put("buyerOwnerRevision","1").put("listingId","7001")
            .put("escrowReference","7001").put("escrowProof","a".repeat(64)).put("amountFen",100);
        List<com.fasterxml.jackson.databind.JsonNode> receipts=new ArrayList<>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/consignments/", exchange -> {
            try(exchange) {
                exchange.getRequestBody().readAllBytes();var page=json.createObjectNode();var entries=page.putArray("entries");
                if(exchange.getRequestURI().getPath().equals("/internal/consignments/deliveries/poll")) {
                    var entry=entries.addObject();entry.set("intent",intent);entry.put("cursor","");
                }
                byte[] bytes=json.writeValueAsBytes(page);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }
        });
        server.createContext("/internal/deliveries/receipt", exchange -> {
            try(exchange) {receipts.add(json.readTree(exchange.getRequestBody()));exchange.sendResponseHeaders(200,-1);}
        });server.start();
        try {
            var http=new OfficialMallClient(new OfficialMallProperties(true,"http://127.0.0.1:"+server.getAddress().getPort(),
                "gateway-test-only-00000000000000001","delivery-test-only-0000000000000002"),e,json);
            var service=new RmbConsignmentService(e,new RmbConsignmentClient(http),org.mockito.Mockito.mock(PlayerTradeClient.class),Set.of(0));
            service.poll(budget());var bound=e.store.get(listingId);
            assertThat(CommerceEngine.workflow(bound).getStage()).isEqualTo("rmb_refund");
            assertThat(CommerceEngine.workflow(bound).getRmb().getBuyerOwnerRevision()).isEqualTo(1);
            assertThat(e.store.ops("ref_id=? AND op_id<>?",listingId,originalDebitId)).isEmpty();
            assertThat(e.store.ops("op_id=?",originalDebitId)).containsExactly(originalDebit);
            service.advance(bound,budget());assertThat(receipts).hasSize(1);
            assertThat(receipts.getFirst().path("state").asText()).isEqualTo("REFUND_PENDING");
            assertThat(e.store.ops("ref_id=? AND op_id<>?",listingId,originalDebitId)).isEmpty();
            assertThat(e.store.ops("op_id=?",originalDebitId)).containsExactly(originalDebit);
            // 维护注入错误归属，原返还不能发给新主；保留rmb_return等待人工/恢复。
            fixtureStore.transaction(budget(), c -> {CommerceStore.execute(c,"UPDATE player SET account='new-seller-owner',owner_revision=2 WHERE player_id=11");return null;});
            assertThatThrownBy(() -> service.advance(e.store.get(listingId),budget())).isInstanceOf(SecurityException.class);
            assertThat(e.store.ops("ref_id=? AND op_id<>?",listingId,originalDebitId)).isEmpty();
            assertThat(e.store.ops("op_id=?",originalDebitId)).containsExactly(originalDebit);
            assertThat(CommerceEngine.workflow(e.store.get(listingId)).getStage()).isEqualTo("rmb_return");
            // 坏终局没有原操作ID时必须隔离原件，不能悄悄生成另一笔扣款。
            var malformedWorkflow=workflow.build().toBuilder().setStage("rmb_refund");
            malformedWorkflow.getLegsBuilder(0).clearOpId();
            var malformed=row.toBuilder().setCommerceId(7002).setRequestKey("rmb-missing-original-op-fixture")
                .setWorkflowProto(malformedWorkflow.build().toByteString()).build();
            fixtureStore.transaction(budget(),c->{CommerceStore.insert(c,"trade_commerce",malformed);return null;});
            var beforeMalformedOps=e.store.ops("1=1 ORDER BY op_id");
            assertThatThrownBy(()->e.advance(malformed,budget())).isInstanceOf(CommerceEngine.CorruptContract.class)
                .hasMessageContaining("原操作ID");
            assertThat(e.store.get(malformed.getCommerceId())).isEqualTo(malformed);
            assertThat(e.store.ops("1=1 ORDER BY op_id")).isEqualTo(beforeMalformedOps);
        } finally {server.stop(0);}
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
        fixtureStore.transaction(budget(), c -> {
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
