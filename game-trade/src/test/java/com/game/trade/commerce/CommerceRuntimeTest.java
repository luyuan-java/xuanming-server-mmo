package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.api.proto.AssetBundle;
import com.game.common.deadline.Deadline;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.trade.store.pb.TradeAssetOpRow;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.ByteString;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 经真实 tick 接口核单行失败、调度和翻页；SQL 原子性另由 CommerceTiDbTest 覆盖。 */
class CommerceRuntimeTest {
    private final CommerceStore store = mock(CommerceStore.class);
    private final ReliableAssetDelivery delivery = mock(ReliableAssetDelivery.class);
    private final PersistedAssetLedgerReader ledger = mock(PersistedAssetLedgerReader.class);
    private final long now = 1_000_000;
    private final CommerceEngine engine = new CommerceEngine(store, new AtomicLong(1000)::incrementAndGet,
        new CommerceRules.Policy(Set.of(10), Set.of(), Set.of(1), false, List.of()), () -> now);

    private TradeCommerceRow row(long id, String stage) {
        return TradeCommerceRow.newBuilder()
            .setCommerceId(id)
            .setVersion(1)
            .setSellerPlayerId(id)
            .setStatus(3)
            .setWorkflowProto(TradeCommerceWorkflow.newBuilder().setStage(stage).build().toByteString())
            .build();
    }

    @Test
    void 队头背压推迟但不挡后续合同_不把失败猜成资产终局() throws Exception {
        var blocked = row(11, "official_deliver");
        var healthy = row(12, "official_deliver");
        when(store.due(now, 100)).thenReturn(List.of(blocked, healthy));
        doThrow(new CommerceStore.PendingBackpressure())
            .when(store).ensureSeqs(any(), eq(11L), eq(0L), eq(0L));
        try (var runtime = new CommerceRuntime(engine, delivery, ledger)) {
            runtime.tick(Deadline.after(10000));
        }
        verify(store).ensureSeqs(any(), eq(12L), eq(0L), eq(0L));
        var until = ArgumentCaptor.forClass(Long.class);
        verify(store).defer(eq(blocked), until.capture(), any());
        assertThat(until.getValue()).isBetween(now + 48000, now + 72000);
        verify(store, never()).quarantine(any(), any());
        verifyNoInteractions(delivery, ledger);
    }

    @Test
    void 坏合同隔离且不挡后续合同() throws Exception {
        var corrupt = row(11, "unused").toBuilder().setWorkflowProto(ByteString.copyFrom(new byte[] {(byte) 255})).build();
        when(store.due(now, 100)).thenReturn(List.of(corrupt, row(12, "official_deliver")));
        when(store.quarantine(eq(corrupt), any())).thenReturn(true);
        try (var runtime = new CommerceRuntime(engine, delivery, ledger)) {
            runtime.tick(Deadline.after(10000));
        }
        verify(store).quarantine(eq(corrupt), any());
        verify(store).ensureSeqs(any(), eq(12L), eq(0L), eq(0L));
        verifyNoInteractions(delivery, ledger);
    }

    @Test
    void 等待中的持有竞价按拍品截止推迟_不再永久占住队头() throws Exception {
        var held = row(11, "bid_held").toBuilder().setParentId(99).build();
        var parent = row(99, "lot_open").toBuilder().setExpiresMs(now + 90000).build();
        when(store.due(now, 100)).thenReturn(List.of(held, row(12, "official_deliver")));
        when(store.get(99)).thenReturn(parent);
        try (var runtime = new CommerceRuntime(engine, delivery, ledger)) {
            runtime.tick(Deadline.after(10000));
        }
        verify(store).defer(eq(held), eq(now + 90000), any());
        verify(store).ensureSeqs(any(), eq(12L), eq(0L), eq(0L));
        verify(store, never()).ensureSeqs(any(), eq(11L), eq(0L), eq(0L));
    }

    @Test
    void 五十条离线收据和坏载荷不挡后页ACK_未知保持原待办() throws Exception {
        List<TradeAssetOpRow> firstPage = new ArrayList<>();
        for (long id = 1; id <= 50; id++)
            firstPage.add(TradeAssetOpRow.newBuilder()
                              .setOpId(id).setPlayerId(id).setStream(3).setStreamEpoch(1).setSeq(1)
                              .setStatus(2).setDurable(true)
                              .setPayload(id == 1 ? ByteString.copyFrom(new byte[] {(byte) 255})
                                                  : AssetBundle.getDefaultInstance().toByteString())
                              .build());
        var tail = firstPage.getLast().toBuilder().setOpId(51).setPlayerId(51).build();
        when(store.ackDue(0, 50)).thenReturn(firstPage);
        when(store.ackDue(50, 50)).thenReturn(List.of(tail));
        when(ledger.read(anyLong())).thenReturn(new PersistedAssetLedgerReader.Read.Absent());
        when(delivery.deliver(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(
            ReliableAssetDelivery.Delivery.failed(new IllegalStateException("离线"))));
        try (var runtime = new CommerceRuntime(engine, delivery, ledger)) {
            runtime.tick(Deadline.after(10000));
            runtime.tick(Deadline.after(10000));
            runtime.tick(Deadline.after(10000));
        }
        verify(ledger).read(51);
        verify(store, times(2)).ackDue(0, 50);
        verify(store).ackDue(50, 50);
        verify(store, never()).transaction(any(), any());
    }

    @Test
    void 商城重复依赖错误不挡后续已提交交付回执_页末仍报告原失败() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var valid = json.createObjectNode().put("orderId", "00112233-4455-6677-8899-aabbccddeeff")
                        .put("buyerId", "22").put("buyerAccount", "fixture-account-22").put("buyerOwnerRevision", "1")
                        .put("itemConfigId", 10).put("itemCount", 1);
        var stored = row(100, "done").toBuilder().setStatus(CommerceRules.SETTLED)
                         .setRequestDigest(CommerceEngine.sha(json.writeValueAsBytes(valid))).build();
        var dependencyFailure = new SQLException("测试商城合同库不可用");
        when(store.byRequest(anyString())).thenThrow(dependencyFailure).thenThrow(dependencyFailure).thenReturn(stored);
        var receipts = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/deliveries/poll", exchange -> {
            var intents = List.of(valid.deepCopy().put("orderId", "00112233-4455-6677-8899-aabbccddee01"),
                valid.deepCopy().put("orderId", "00112233-4455-6677-8899-aabbccddee02"), valid);
            byte[] body = json.writeValueAsBytes(deliveryPage(json, intents, 0, 3));
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/internal/deliveries/receipt", exchange -> {
            receipts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var mall = new OfficialMallClient(new OfficialMallProperties(true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "gateway-test-only-00000000000000001", "delivery-test-only-0000000000000002"), engine, json);
            assertThatThrownBy(() -> mall.tick(Deadline.after(10000))).isSameAs(dependencyFailure);
            assertThat(receipts.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 商城前二十项未交付不挡后五项_多worker和重启保持原摘要() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var intents = deliveryIntents(json, 25);
        Map<String, TradeCommerceRow> originals = new HashMap<>();
        for (int i = 0; i < intents.size(); i++) {
            var in = intents.get(i);
            long buyer = Long.parseUnsignedLong(in.path("buyerId").asText());
            originals.put(CommerceEngine.key("official", buyer, in.path("orderId").asText()),
                row(100 + i, "official_deliver").toBuilder().setKind(1).setBuyerPlayerId(buyer)
                    .setStatus(i < 20 ? CommerceRules.DELIVERING : CommerceRules.SETTLED)
                    .setRequestDigest(CommerceEngine.sha(json.writeValueAsBytes(in))).build());
        }
        when(store.byRequest(anyString())).thenAnswer(call -> originals.get(call.getArgument(0, String.class)));
        String afterTwenty = deliveryCursor(intents, 19);
        try (var server = new MallHttp(json, request -> request.path("cursor").asText().isEmpty()
                     ? deliveryPage(json, intents, 0, 20)
                     : deliveryPage(json, intents, 20, 20))) {
            var worker = server.mall(engine);
            worker.tick(Deadline.after(10000));
            assertThat(server.receipts).isEmpty();
            worker.tick(Deadline.after(10000));
            assertThat(server.receipts).extracting(receipt -> receipt.path("orderId").asText())
                .containsExactlyElementsOf(intents.subList(20, 25).stream().map(in -> in.path("orderId").asText()).toList());
            assertThat(server.cursors).containsExactly("", afterTwenty);
            // 新 worker 和重启都从首页重放；只查原合同，不把包装/游标混进原 Intent 摘要。
            server.mall(engine).tick(Deadline.after(10000));
            server.mall(engine).tick(Deadline.after(10000));
            assertThat(server.cursors).containsExactly("", afterTwenty, "", "");
            verify(store, never()).transaction(any(), any());
        }
    }

    @Test
    void 商城请求失败和畸形包装不前移_整页校验先于业务尝试() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var intents = deliveryIntents(json, 25);
        when(store.byRequest(anyString())).thenAnswer(call -> {
            for (var in : intents)
                if (CommerceEngine.key("official", Long.parseUnsignedLong(in.path("buyerId").asText()),
                        in.path("orderId").asText()).equals(call.getArgument(0)))
                    return row(100, "official_deliver").toBuilder()
                        .setRequestDigest(CommerceEngine.sha(json.writeValueAsBytes(in))).build();
            throw new AssertionError("意料外的交付订单");
        });
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        try (var server = new MallHttp(json, request -> {
            int count = polls.incrementAndGet();
            if (count == 1) return deliveryPage(json, intents, 0, 20);
            if (count == 2) return null; // HTTP 失败，原游标不变。
            if (count == 3) {
                var invalid = deliveryPage(json, intents, 20, 20);
                ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("entries").get(1)).putNull("cursor");
                return invalid;
            }
            return deliveryPage(json, intents, 20, 20);
        })) {
            var worker = server.mall(engine);
            worker.tick(Deadline.after(10000));
            assertThatThrownBy(() -> worker.tick(Deadline.after(10000))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> worker.tick(Deadline.after(10000))).isInstanceOf(IllegalStateException.class);
            verify(store, times(20)).byRequest(anyString());
            worker.tick(Deadline.after(10000));
            verify(store, times(25)).byRequest(anyString());
            String cursor = deliveryCursor(intents, 19);
            assertThat(server.cursors).containsExactly("", cursor, cursor, cursor);
        }
    }

    @Test
    void 商城预算不足不越未开始项_中断只推进已经尝试项() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var intents = deliveryIntents(json, 3);
        when(store.byRequest(anyString())).thenAnswer(call -> {
            var in = intents.stream().filter(value -> CommerceEngine.key("official",
                    Long.parseUnsignedLong(value.path("buyerId").asText()), value.path("orderId").asText())
                    .equals(call.getArgument(0))).findFirst().orElseThrow();
            if (in == intents.getFirst()) throw new InterruptedException("测试项内中断");
            return row(100, "official_deliver").toBuilder()
                .setRequestDigest(CommerceEngine.sha(json.writeValueAsBytes(in))).build();
        });
        try (var server = new MallHttp(json, request -> request.path("cursor").asText().isEmpty()
                     ? deliveryPage(json, intents, 0, 20) : deliveryPage(json, intents, 1, 20))) {
            var worker = server.mall(engine);
            // HTTP请求仍有正常预算，回页后确定剩250ms，不靠本机恰好在250ms内完成网络。
            var insufficient = mock(Deadline.class);
            when(insufficient.expired()).thenReturn(false);
            when(insufficient.remainingMillis()).thenReturn(10000L, 250L);
            worker.tick(insufficient);
            verify(store, never()).byRequest(anyString());
            try {
                assertThatThrownBy(() -> worker.tick(Deadline.after(10000))).isInstanceOf(InterruptedException.class);
            } finally {
                Thread.interrupted();
            }
            worker.tick(Deadline.after(10000));
            assertThat(server.cursors).containsExactly("", "", deliveryCursor(intents, 0));
            verify(store, times(3)).byRequest(anyString());
        }
    }

    @Test
    void 商城尾页在读取前全部终结_空页后从头绕回() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var intents = deliveryIntents(json, 25);
        when(store.byRequest(anyString())).thenAnswer(call -> {
            var in = intents.stream().filter(value -> CommerceEngine.key("official",
                    Long.parseUnsignedLong(value.path("buyerId").asText()), value.path("orderId").asText())
                    .equals(call.getArgument(0))).findFirst().orElseThrow();
            return row(100, "official_deliver").toBuilder()
                .setRequestDigest(CommerceEngine.sha(json.writeValueAsBytes(in))).build();
        });
        try (var server = new MallHttp(json, request -> request.path("cursor").asText().isEmpty()
                     ? deliveryPage(json, intents, 0, 20) : json.createObjectNode().set("entries", json.createArrayNode()))) {
            var worker = server.mall(engine);
            worker.tick(Deadline.after(10000));
            worker.tick(Deadline.after(10000));
            verify(store, times(20)).byRequest(anyString());
            worker.tick(Deadline.after(10000));
            verify(store, times(40)).byRequest(anyString());
            assertThat(server.cursors).containsExactly("", deliveryCursor(intents, 19), "");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 3, unit = java.util.concurrent.TimeUnit.MINUTES,
        threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void 真TiDB和HTTP后页创建合同不被离线首页挡住_多worker重启保持原seq() throws Exception {
        String url = System.getProperty("game.it.mysql");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null, "未配置隔离TiDB，未运行真实分页合同回归");
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + CommerceTiDbTest.tiDbTestPort()))
            throw new IllegalArgumentException("仅允许本机game.it.tidb.port指定端口TiDB随机库");
        String database = "trade_mall_page_it_" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var intents = deliveryIntents(json, 25);
        try (var admin = java.sql.DriverManager.getConnection(url + "/", "root", "");
             var pool = new com.alibaba.druid.pool.DruidDataSource()) {
            try {
                try (var sql = admin.createStatement()) {
                    sql.execute("CREATE DATABASE `" + database + "`");
                    created = true;
                }
                pool.setUrl(url + "/" + database);
                pool.setUsername("root");
                pool.setPassword("");
                pool.setMaxActive(12);
                pool.setMaxWait(5000);
                pool.setConnectionInitSqls(List.of(com.game.discovery.sql.StrongTiDbConnectionPolicy.SESSION_SQL));
                try (var connection = pool.getConnection()) {
                    com.game.discovery.sql.StrongTiDbConnectionPolicy.verify(connection, true);
                }
                com.game.trade.store.TradeTables.sync(pool, java.time.Duration.ofSeconds(30));
                try (var connection = pool.getConnection(); var sql = connection.createStatement()) {
                    sql.execute("CREATE TABLE player(player_id BIGINT UNSIGNED PRIMARY KEY,owner_revision BIGINT UNSIGNED NOT NULL,trade_listing_id BIGINT UNSIGNED NOT NULL,account VARCHAR(64) NOT NULL,zone_id INT UNSIGNED NOT NULL DEFAULT 1)");
                    for (var intent : intents) sql.execute("INSERT INTO player(player_id,owner_revision,trade_listing_id,account) VALUES(" + intent.path("buyerId").asText() + ",1,0,'" + intent.path("buyerAccount").asText() + "')");
                }
                new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/game-route-authority-schema.sql")).execute(pool);
                try(var connection=pool.getConnection()){com.game.common.player.RouteAuthorityInitializer.initialize(connection,UUID.randomUUID().toString(),List.of(1));}
                var real = new CommerceEngine(new CommerceStore(pool), new AtomicLong(10000)::incrementAndGet,
                    engine.policy, System::currentTimeMillis);
                try (var server = new MallHttp(json, request -> request.path("cursor").asText().isEmpty()
                             ? deliveryPage(json, intents, 0, 20) : deliveryPage(json, intents, 20, 20))) {
                    var worker = server.mall(real);
                    worker.tick(Deadline.after(60000));
                    assertThat(real.store.list("kind=1")).hasSize(20);
                    for (var row : real.store.list("kind=1")) real.advance(row, Deadline.after(10000));
                    // 只替代 scene 离线边界；合同、可靠待办与 seq 均真实写在此随机 TiDB 库。
                    var offline = mock(ReliableAssetDelivery.class);
                    var absent = mock(PersistedAssetLedgerReader.class);
                    when(offline.deliver(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(
                        ReliableAssetDelivery.Delivery.answered(com.game.api.proto.AssetOpResponse.newBuilder()
                            .setOutcome(com.game.api.proto.AssetOutcome.ASSET_OUTCOME_NOT_HERE).build())));
                    when(absent.read(anyLong())).thenReturn(new PersistedAssetLedgerReader.Read.Absent());
                    try (var runtime = new CommerceRuntime(real, offline, absent)) {
                        runtime.tick(Deadline.after(60000));
                    }
                    assertThat(real.store.ops("stream=4 AND status=1")).hasSize(20);
                    worker.tick(Deadline.after(60000));
                    assertThat(real.store.list("kind=1")).hasSize(25);
                    for (var row : real.store.list("kind=1")) real.advance(row, Deadline.after(10000));
                    var originalOps = real.store.ops("stream=4 ORDER BY op_id");
                    assertThat(originalOps).hasSize(25).allMatch(op -> op.getSeq() == 1 && op.getStatus() == 1);
                    assertThat(originalOps).extracting(TradeAssetOpRow::getOpId).doesNotHaveDuplicates();
                    // 仅给尾五项注入已提交 SQL 终局；这是交易状态机夹具，不是真实 scene 到账证明。
                    real.store.transaction(Deadline.after(10000), connection -> {
                        for (int i = 20; i < 25; i++)
                            assertThat(CommerceStore.execute(connection,
                                "UPDATE trade_asset_op SET status=2,durable=1 WHERE player_id=? AND stream=4 AND status=1",
                                Long.parseUnsignedLong(intents.get(i).path("buyerId").asText()))).isEqualTo(1);
                        return null;
                    });
                    for (int i = 20; i < 25; i++) {
                        var intent = intents.get(i);
                        real.advance(real.store.byRequest(CommerceEngine.key("official",
                            Long.parseUnsignedLong(intent.path("buyerId").asText()), intent.path("orderId").asText())),
                            Deadline.after(10000));
                    }
                    var finalOps = real.store.ops("stream=4 ORDER BY op_id");
                    assertThat(finalOps).filteredOn(op -> op.getStatus() == 1).hasSize(20);
                    assertThat(finalOps).filteredOn(op -> op.getStatus() == 2).hasSize(5);
                    assertThat(finalOps).extracting(TradeAssetOpRow::getOpId, TradeAssetOpRow::getStreamEpoch, TradeAssetOpRow::getSeq)
                        .isEqualTo(originalOps.stream().map(op -> tuple(op.getOpId(), op.getStreamEpoch(), op.getSeq())).toList());
                    worker.tick(Deadline.after(60000));
                    worker.tick(Deadline.after(60000));
                    var tailOrders = intents.subList(20, 25).stream().map(intent -> intent.path("orderId").asText()).toList();
                    assertThat(server.receipts).hasSize(5).extracting(receipt -> receipt.path("orderId").asText())
                        .containsExactlyElementsOf(tailOrders);
                    var restarted = server.mall(real);
                    restarted.tick(Deadline.after(60000));
                    restarted.tick(Deadline.after(60000));
                    var otherWorker = server.mall(real);
                    otherWorker.tick(Deadline.after(60000));
                    otherWorker.tick(Deadline.after(60000));
                    assertThat(real.store.list("kind=1")).hasSize(25);
                    assertThat(real.store.ops("stream=4 ORDER BY op_id")).isEqualTo(finalOps);
                    assertThat(server.receipts).hasSize(15).extracting(receipt -> receipt.path("orderId").asText())
                        .containsOnlyElementsOf(tailOrders);
                    for (var intent : intents) {
                        var stored = real.store.byRequest(CommerceEngine.key("official",
                            Long.parseUnsignedLong(intent.path("buyerId").asText()), intent.path("orderId").asText()));
                        assertThat(stored.getRequestDigest()).isEqualTo(CommerceEngine.sha(json.writeValueAsBytes(intent)));
                    }
                }
            } finally {
                pool.close();
                if (created) {
                    if (!database.matches("trade_mall_page_it_[0-9a-f]{32}"))
                        throw new IllegalStateException("拒绝清理非本用例随机库");
                    try (var sql = admin.createStatement()) { sql.execute("DROP DATABASE `" + database + "`"); }
                }
            }
        }
    }

    private static List<com.fasterxml.jackson.databind.node.ObjectNode> deliveryIntents(
        com.fasterxml.jackson.databind.ObjectMapper json, int count) {
        List<com.fasterxml.jackson.databind.node.ObjectNode> result = new ArrayList<>();
        for (int i = 1; i <= count; i++)
            result.add(json.createObjectNode().put("orderId", "10000000-0000-0000-0000-" + String.format("%012x", i))
                .put("buyerId", Integer.toString(5000 + i)).put("buyerAccount", "fixture-account-" + (5000 + i))
                .put("buyerOwnerRevision", "1").put("skuId", 17).put("quantity", 1)
                .put("itemConfigId", 10).put("itemCount", 1).put("title", "玄铁").put("amountMinor", 1250).put("currency", "CNY"));
        return result;
    }

    private static String deliveryCursor(List<com.fasterxml.jackson.databind.node.ObjectNode> intents, int index) {
        return "v1:1000:" + intents.get(index).path("orderId").asText() + ":1000:"
            + intents.getLast().path("orderId").asText();
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode deliveryPage(
        com.fasterxml.jackson.databind.ObjectMapper json, List<com.fasterxml.jackson.databind.node.ObjectNode> intents,
        int offset, int limit) {
        var entries = json.createArrayNode();
        for (int i = offset; i < Math.min(offset + limit, intents.size()); i++) {
            var entry = entries.addObject();
            entry.set("intent", intents.get(i));
            entry.put("cursor", i == intents.size() - 1 ? "" : deliveryCursor(intents, i));
        }
        return json.createObjectNode().set("entries", entries);
    }

    /** 真实本机 HTTP 边界；SQL 行为由独立真 TiDB 用例覆盖，测试不伪造网络或付款成功。 */
    private static final class MallHttp implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final com.fasterxml.jackson.databind.ObjectMapper json;
        final List<String> cursors = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<com.fasterxml.jackson.databind.JsonNode> receipts = new java.util.concurrent.CopyOnWriteArrayList<>();
        MallHttp(com.fasterxml.jackson.databind.ObjectMapper json,
            java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.JsonNode> poll)
            throws java.io.IOException {
            this.json = json;
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/deliveries/poll", exchange -> {
                try (exchange) {
                    var request = json.readTree(exchange.getRequestBody());
                    cursors.add(request.path("cursor").asText());
                    var page = poll.apply(request);
                    if (page == null) {
                        exchange.sendResponseHeaders(503, -1);
                    } else {
                        byte[] body = json.writeValueAsBytes(page);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                }
            });
            server.createContext("/internal/deliveries/receipt", exchange -> {
                try (exchange) {
                    receipts.add(json.readTree(exchange.getRequestBody()));
                    exchange.sendResponseHeaders(200, -1);
                }
            });
            server.start();
        }
        OfficialMallClient mall(CommerceEngine engine) {
            return new OfficialMallClient(new OfficialMallProperties(true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "gateway-test-only-00000000000000001", "delivery-test-only-0000000000000002"), engine, json);
        }
        @Override
        public void close() { server.stop(0); }
    }
}
