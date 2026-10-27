package com.game.mail;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.discovery.sql.*;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import com.game.player.store.state.*;
import com.game.proto.mail.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 真TiDB隔离库；缺DSN时仅开发默认跳过，MAIL_RANK_REQUIRE_TESTS=1强制失败。 */
class MailStoreTiDbTest {
    private String base;
    private AuthoritySqlFixture fixture;
    private DruidDataSource pool;
    private AuthorityJdbc jdbc;
    private MailStore store;
    private static Deadline budget() {
        return Deadline.after(20000);
    }
    @BeforeEach
    void setup() throws Exception {
        base = System.getenv("MAIL_RANK_TEST_JDBC");
        if (base == null) {
            if ("1".equals(System.getenv("MAIL_RANK_REQUIRE_TESTS")))
                fail("MAIL_RANK_TEST_JDBC必须配置");
            Assumptions.assumeTrue(false, "未配置隔离TiDB");
        }
        Class.forName("com.mysql.cj.jdbc.Driver");
        fixture = AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment(base,
                System.getProperty("game.it.mysql.user", "root"), System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""),
                "mail", false, List.of(1)), (connection, principal) -> {
            MailTables.registry().initializeFresh(connection, principal);
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE guild_member (player_id BIGINT UNSIGNED PRIMARY KEY,guild_id BIGINT UNSIGNED NOT NULL)");
                sql.execute("CREATE TABLE player_state (player_id BIGINT UNSIGNED PRIMARY KEY,data MEDIUMBLOB NOT NULL)");
                sql.execute("CREATE TABLE player(player_id BIGINT UNSIGNED PRIMARY KEY,account VARBINARY(191) NOT NULL,owner_revision BIGINT UNSIGNED NOT NULL,trade_listing_id BIGINT UNSIGNED NOT NULL,zone_id INT UNSIGNED NOT NULL DEFAULT 1)");
            }
        });
        pool = new DruidDataSource();
        pool.setUrl(fixture.jdbcUrl("?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true"));
        pool.setUsername(fixture.normalUser());
        pool.setPassword(fixture.normalPassword());
        pool.setMaxActive(16);
        pool.setMaxWait(5000);
        AuthorityDataSourcePolicy.protectDruid(pool, fixture.writerBoot(), true);
        try (var first = pool.getConnection(); var second = pool.getConnection()) {
            StrongTiDbConnectionPolicy.verify(first, true);
            StrongTiDbConnectionPolicy.verify(second, true);
        }
        MailTables.sync(pool);
        jdbc = new AuthorityJdbc(pool);
        store = new MailStore(jdbc, new AuthorityIds(jdbc, "mail_id_sequence"), id -> id == 1, System::currentTimeMillis);
    }
    @AfterEach
    void cleanup() throws Exception {
        if (pool != null)
            pool.close();
        if (fixture != null) fixture.close();
    }
    private SendMailRequest personal(long request, long player) {
        return SendMailRequest.newBuilder()
                .setSenderKind(1)
                .setRequestId(request)
                .setRecipientPlayerId(player)
                .setTitle("奖励")
                .setBody("SQL一致附件")
                .setAttachments(MailAttachments.newBuilder().addCurrencies(MailCurrency.newBuilder().setCurrencyType(0).setAmount(500)))
                .build();
    }
    private void roleOwner(String account, long revision, long listing) throws Exception {
        try (var connection = fixture.schemaConnection(); var sql = connection.prepareStatement(
                "INSERT INTO player(player_id,account,owner_revision,trade_listing_id) VALUES (7,?,?,?) ON DUPLICATE KEY UPDATE account=?,owner_revision=?,trade_listing_id=?")) {
            sql.setString(1, account); sql.setLong(2, revision); sql.setLong(3, listing);
            sql.setString(4, account); sql.setLong(5, revision); sql.setLong(6, listing); sql.executeUpdate();
        }
    }
    private MailStore actor(String account, long revision) {
        return store.forActor(SessionContext.newBuilder().setPlayerId(7).setAccount(account).setOwnerRevision(revision).build());
    }
    @Test
    void transferredOrAbaMailSessionCannotReadClaimOrDeleteNewOwnersMail() throws Exception {
        long mail = store.send("admin", 1, personal(701, 7), budget()).getMailId();
        roleOwner("原卖家", 1, 0);
        var original = actor("原卖家", 1);
        assertEquals(1, original.list(7, ListMailsRequest.getDefaultInstance(), budget()).getMailsCount());
        roleOwner("新买家", 2, 0);
        assertThrows(SecurityException.class, () -> original.list(7, ListMailsRequest.getDefaultInstance(), budget()));
        assertThrows(SecurityException.class, () -> original.read(7, ReadMailRequest.newBuilder().setMailId(mail).build(), budget()));
        assertThrows(SecurityException.class, () -> original.claim(7, mail, budget()));
        assertThrows(SecurityException.class, () -> original.delete(7, List.of(mail), false, budget()));
        assertEquals(0L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM mail_asset_op")));
        assertEquals(0L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM mail_state WHERE read_ms<>0 OR deleted_ms<>0")));
        roleOwner("原卖家", 3, 0);
        assertThrows(SecurityException.class, () -> original.claim(7, mail, budget()));
        roleOwner("原卖家", 3, 99);
        assertThrows(SecurityException.class, () -> actor("原卖家", 3).claim(7, mail, budget()));
        roleOwner("原卖家", 3, 0);
        assertEquals(1, actor("原卖家", 3).list(7, ListMailsRequest.getDefaultInstance(), budget()).getMailsCount());
    }
    @Test
    void mailSessionCheckPrecedesPendingClaimReplay() throws Exception {
        long mail = store.send("admin", 1, personal(702, 7), budget()).getMailId();
        roleOwner("原卖家", 1, 0);
        var original = actor("原卖家", 1);
        long op = original.claim(7, mail, budget()).getAssetOpId();
        assertNotEquals(0, op);
        roleOwner("新买家", 2, 0);
        assertThrows(SecurityException.class, () -> original.claim(7, mail, budget()));
        assertEquals(op, actor("新买家", 2).claim(7, mail, budget()).getAssetOpId());
        assertEquals(1L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM mail_asset_op")));
    }
    @Test
    void concurrentClaimHasOneOperationAndNoSqlProofCannotFinish() throws Exception {
        long id = store.send("admin", 1, personal(11, 7), budget()).getMailId();
        var threads = Executors.newFixedThreadPool(8);
        var barrier = new CyclicBarrier(8);
        List<Future<MailActionResult>> answers = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++)
                answers.add(threads.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return store.claim(7, id, budget());
                }));
            long op = 0;
            for (var answer : answers) {
                var response = answer.get(20, TimeUnit.SECONDS);
                assertEquals(2, response.getClaimStateValue());
                if (op == 0)
                    op = response.getAssetOpId();
                assertEquals(op, response.getAssetOpId());
            }
        } finally {
            threads.shutdownNow();
        }
        assertEquals(1L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM mail_asset_op")));
        var operation = store.lease(budget(), store.due(budget(), System.currentTimeMillis()).getFirst(), System.currentTimeMillis(), 91);
        store.settle(budget(), operation, null);
        assertEquals(2, store.read(7, ReadMailRequest.newBuilder().setMailId(id).build(), budget()).getMail().getClaimStateValue());
        assertEquals(1L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM mail_asset_op WHERE status=1 AND durable=0")));
    }
    @Test
    void sealedAudienceDoesNotExpandAndEveryReplayChecksPayload() throws Exception {
        String shard = MailStore.sha("10\n20\n"), manifest = MailStore.sha("v1\n0:" + shard + ":2\n");
        var request = personal(22, 0)
                              .toBuilder()
                              .setCampaignId(51)
                              .setManifestGeneration(1)
                              .setExpectedRecipientCount(2)
                              .setExpectedShardCount(1)
                              .setManifestHash(manifest)
                              .build();
        long id = store.send("admin", 2, request, budget()).getMailId();
        assertEquals(0, store.list(10, ListMailsRequest.getDefaultInstance(), budget()).getMailsCount());
        var importRequest = ImportMailRecipientsRequest.newBuilder()
                                    .setSenderKind(1)
                                    .setCampaignId(51)
                                    .setManifestGeneration(1)
                                    .addPlayerIds(20)
                                    .addPlayerIds(10)
                                    .addPlayerIds(10)
                                    .build();
        assertEquals(2, store.importRecipients("admin", importRequest, budget()).getImportedCount());
        assertTrue(store.importRecipients("admin", importRequest, budget()).getDuplicated());
        var seal = SealMailCampaignRequest.newBuilder().setSenderKind(1).setCampaignId(51).setManifestGeneration(1).build();
        assertEquals(id, store.seal("admin", seal, budget()).getMailId());
        assertTrue(store.seal("admin", seal, budget()).getDuplicated());
        assertEquals(1, store.list(10, ListMailsRequest.getDefaultInstance(), budget()).getMailsCount());
        assertEquals(0, store.list(30, ListMailsRequest.getDefaultInstance(), budget()).getMailsCount());
        assertThrows(MailStore.Reject.class,
                () -> store.importRecipients("admin", importRequest.toBuilder().clearPlayerIds().addPlayerIds(30).build(), budget()));
        assertThrows(MailStore.Reject.class, () -> store.claim(30, id, budget()));
        assertTrue(store.send("admin", 2, request, budget()).getDuplicated());
        assertThrows(MailStore.Reject.class, () -> store.send("admin", 2, request.toBuilder().setBody("被改了").build(), budget()));
    }
    @Test
    void workerRestartNeedsIndependentCommittedPlayerLedgerAndNeverDuplicates() throws Exception {
        long id = store.send("admin", 1, personal(33, 7), budget()).getMailId();
        var claim = store.claim(7, id, budget());
        long op = claim.getAssetOpId();
        var delivery = mock(ReliableAssetDelivery.class);
        when(delivery.deliver(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ReliableAssetDelivery.Delivery.answered(
                        AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).setDurable(true).build())));
        new MailDeliveryWorker(store, delivery, new PersistedAssetLedgerReader(pool)).process(op);
        assertEquals(2, store.read(7, ReadMailRequest.newBuilder().setMailId(id).build(), budget()).getMail().getClaimStateValue());
        var operation = store.lease(budget(), op, System.currentTimeMillis() + 60_000, 92);
        var stream = AssetOpStreamLedgerState.newBuilder().setStream(5).setStreamEpoch(operation.epoch()).setMaxSeq(operation.seq());
        for (int i = 0; i < 16; i++) {
            long bit = i == (operation.seq() - 1) / 64 ? 1L << ((operation.seq() - 1) % 64) : 0;
            stream.addSeenBits(bit).addAppliedBits(bit);
        }
        // 此用例只验证 SQL 证明判据；真实 scene 到账另见 AssetArrivalTiDbTest。
        stream.addReceipts(AssetOpReceiptState.newBuilder().setSeq(operation.seq()).setCorrelationId(op)
                .setRequestDigest(com.game.api.asset.AssetOpSignatures.requestDigest(operation.request())));
        var state = PlayerState.newBuilder().setAssetLedger(AssetOpLedgerState.newBuilder().addStreams(stream)).build();
        new AuthorityJdbc(fixture.schemaDataSource()).tx(budget(), s -> {
            s.update("INSERT INTO player_state (player_id,data) VALUES (?,?)", 7, state.toByteArray());
            return null;
        });
        var restarted = new MailStore(jdbc, new AuthorityIds(jdbc, "mail_id_sequence"), value -> true, System::currentTimeMillis);
        // 释放测试领取的令牌，仍用真实 settle 路径安排下一轮，不写业务成功终态。
        store.settle(budget(), operation, null);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            new MailDeliveryWorker(restarted, delivery, new PersistedAssetLedgerReader(pool)).process(op);
            assertEquals(3, restarted.read(7, ReadMailRequest.newBuilder().setMailId(id).build(), budget()).getMail().getClaimStateValue());
        });
        assertEquals(3, restarted.read(7, ReadMailRequest.newBuilder().setMailId(id).build(), budget()).getMail().getClaimStateValue());
        assertEquals(op, restarted.claim(7, id, budget()).getAssetOpId());
        assertEquals(1L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM mail_asset_op")));
        verify(delivery, times(2)).deliver(any(), any(), any());
    }
    @Test
    void guildTextEligibilityIsTheSameSqlSnapshot() throws Exception {
        new AuthorityJdbc(fixture.schemaDataSource()).tx(budget(), s -> {
            s.update("INSERT INTO guild_member VALUES (7,90)");
            return null;
        });
        var r = SendMailRequest.newBuilder().setSenderKind(2).setRequestId(44).setGuildId(90).setTitle("帮会公告").build();
        store.send("guild", 3, r, budget());
        assertEquals(1, store.list(7, ListMailsRequest.getDefaultInstance(), budget()).getMailsCount());
        new AuthorityJdbc(fixture.schemaDataSource()).tx(budget(), s -> {
            s.update("DELETE FROM guild_member WHERE player_id=7");
            return null;
        });
        assertEquals(0, store.list(7, ListMailsRequest.getDefaultInstance(), budget()).getMailsCount());
    }
    @Test
    void committedPartialDeliveryRemainsManualAndProtectedAfterExpiry() throws Exception {
        long id = store.send("admin", 1, personal(55, 7), budget()).getMailId();
        var action = store.claim(7, id, budget());
        var op = store.lease(budget(), action.getAssetOpId(), System.currentTimeMillis(), 71);
        var stream =
                AssetOpStreamLedgerState.newBuilder().setStream(5).setStreamEpoch(op.epoch()).setMaxSeq(op.seq()).addPartialSeqs(op.seq());
        for (int i = 0; i < 16; i++) {
            long bit = i == (op.seq() - 1) / 64 ? 1L << ((op.seq() - 1) % 64) : 0;
            stream.addSeenBits(bit).addAppliedBits(bit);
        }
        stream.addReceipts(AssetOpReceiptState.newBuilder().setSeq(op.seq()).setCorrelationId(op.id())
                .setRequestDigest(com.game.api.asset.AssetOpSignatures.requestDigest(op.request())));
        var data = PlayerState.newBuilder().setAssetLedger(AssetOpLedgerState.newBuilder().addStreams(stream)).build();
        new AuthorityJdbc(fixture.schemaDataSource()).tx(budget(), s -> {
            s.update("INSERT INTO player_state (player_id,data) VALUES (?,?)", 7, data.toByteArray());
            return null;
        });
        var read = new PersistedAssetLedgerReader(pool).read(7);
        assertInstanceOf(PersistedAssetLedgerReader.Read.Loaded.class, read);
        store.settle(budget(), op, ((PersistedAssetLedgerReader.Read.Loaded) read).ledger());
        new AuthorityJdbc(fixture.schemaDataSource()).tx(budget(), s -> {
            s.update("UPDATE mail_message SET expire_ms=1 WHERE mail_id=?", id);
            return null;
        });
        assertEquals(List.of(id), store.delete(7, List.of(id), false, budget()).getProtectedMailIdsList());
        assertEquals(4, store.read(7, ReadMailRequest.newBuilder().setMailId(id).build(), budget()).getMail().getClaimStateValue());
        assertEquals(action.getAssetOpId(), store.claim(7, id, budget()).getAssetOpId());
    }
    @Test
    void producerSignaturesCannotCrossMethodsCallersOrPayloads() throws Exception {
        String secret = "mail-admin-fixture-only-01234567890123456789";
        var signatures = new com.game.discovery.serviceauth.ServiceCallSignatures(Map.of("admin", secret), System::currentTimeMillis);
        var signed = com.game.discovery.serviceauth.ServiceCallSignatures.sign(
                com.game.api.MailAdminService.PERSONAL_METHOD, personal(66, 7), "admin", secret, System.currentTimeMillis());
        assertEquals("admin", signatures.verify(com.game.api.MailAdminService.PERSONAL_METHOD, signed));
        assertThrows(SecurityException.class, () -> signatures.verify(com.game.api.MailAdminService.SYSTEM_METHOD, signed));
        assertThrows(SecurityException.class,
                () -> signatures.verify(com.game.api.MailAdminService.PERSONAL_METHOD, signed.toBuilder().setRecipientPlayerId(8).build()));
        assertThrows(SecurityException.class, () -> signatures.verify(com.game.api.MailAdminService.PERSONAL_METHOD, personal(66, 7)));
    }
    @Test
    void unknownFutureClaimStateIsVisibleProtectedAndNeverCredited() throws Exception {
        long id = store.send("admin", 1, personal(77, 7), budget()).getMailId();
        new AuthorityJdbc(fixture.schemaDataSource()).tx(budget(), s -> {
            s.update("UPDATE mail_state SET claim_state=99 WHERE player_id=7 AND mail_id=?", id);
            s.update("UPDATE mail_message SET expire_ms=1 WHERE mail_id=?", id);
            return null;
        });
        assertEquals(List.of(id), store.delete(7, List.of(id), false, budget()).getProtectedMailIdsList());
        assertEquals(99, store.list(7, ListMailsRequest.getDefaultInstance(), budget()).getMails(0).getClaimStateValue());
        assertEquals(0, store.claim(7, id, budget()).getAssetOpId());
        assertEquals(0L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM mail_asset_op")));
    }
    @Test
    void grantItemBudgetMatchesSceneBeforeMailOrRewardIntent() throws Exception {
        var excessive = MailAttachments.newBuilder().addItems(MailItem.newBuilder().setConfigId(1).setCount(4097)).build();
        var request = personal(88, 7).toBuilder().setAttachments(excessive).build();
        assertThrows(MailStore.Reject.class, () -> store.send("admin", 1, request, budget()));
        assertEquals(0L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM mail_message")));
        assertDoesNotThrow(()
                                   -> com.game.api.asset.MailGrants.bundle(
                                           excessive.toBuilder().setItems(0, MailItem.newBuilder().setConfigId(1).setCount(4096)).build(),
                                           id -> true));
    }

    @Test
    void realRedisNoticeAfterSqlCommitIsOnceAndOfflineMailRemainsReadable() throws Exception {
        String address = System.getenv("MAIL_RANK_TEST_REDIS");
        if (address == null) {
            if ("1".equals(System.getenv("MAIL_RANK_REQUIRE_TESTS")))
                fail("MAIL_RANK_TEST_REDIS必须配置");
            Assumptions.assumeTrue(false, "未配置隔离Redis");
        }
        var config = new org.redisson.config.Config();
        config.useSingleServer().setAddress(address).setDatabase(5);
        var redis = org.redisson.Redisson.create(config);
        var keys = new com.game.discovery.RedisKeys(
                com.game.common.GameNamespace.of("mailnt" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)));
        var directory = new com.game.discovery.presence.PlayerPresenceDirectory(redis, keys);
        var pushes = new com.game.discovery.presence.PlayerPushes(redis, keys, directory);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        int messageId = com.game.contract.MessageIdRegistry.loadFromClasspath().requireId("ClientPlayerMail", "NotifyMailChanged");
        var notices = new MailNotifications(pushes, messageId, registry);
        var received = new CompletableFuture<com.game.discovery.proto.GatePush>();
        var topic = redis.getTopic(keys.gatePushTopic(1, 5), org.redisson.client.codec.ByteArrayCodec.INSTANCE);
        int subscription = topic.addListener(byte[].class, (channel, bytes) -> {
            try {
                received.complete(com.game.discovery.proto.GatePush.parseFrom(bytes));
            } catch (Exception error) {
                received.completeExceptionally(error);
            }
        });
        var presence = com.game.discovery.proto.PlayerPresence.newBuilder()
                               .setPlayerId(7)
                               .setZoneId(1)
                               .setGateNodeId(5)
                               .setSessionId(99)
                               .setGateInstanceId("mail-push-gate")
                               .build();
        String secret = "mail-notice-unit-test-only-01234567890123456789";
        try (var provider = new MailProvider(store,
                     new com.game.discovery.serviceauth.ServiceCallSignatures(Map.of("admin", secret), System::currentTimeMillis),
                     notices)) {
            directory.putAsync(presence).toCompletableFuture().get(2, TimeUnit.SECONDS);
            var signed = com.game.discovery.serviceauth.ServiceCallSignatures.sign(
                    com.game.api.MailAdminService.PERSONAL_METHOD, personal(99, 7), "admin", secret, System.currentTimeMillis());
            var calls = new ArrayList<CompletableFuture<SendMailResponse>>();
            for (int i = 0; i < 8; i++) calls.add(provider.sendPersonalMail(signed));
            CompletableFuture.allOf(calls.toArray(CompletableFuture[] ::new)).get(20, TimeUnit.SECONDS);
            long mail = calls.getFirst().get().getMailId();
            for (var result : calls) {
                assertEquals(0, result.get().getErrorMessage().getId());
                assertEquals(mail, result.get().getMailId());
            }
            var event = received.get(2, TimeUnit.SECONDS);
            assertEquals("mail-push-gate", event.getGateInstanceId());
            assertEquals(7, event.getTargets(0).getPlayerId());
            assertEquals(99, event.getTargets(0).getSessionId());
            var content = com.game.proto.MessageContent.parseFrom(event.getMessageContent());
            assertEquals(messageId, content.getMessageId());
            assertEquals(mail, MailChangedEvent.parseFrom(content.getSerializedMessage()).getMailId());
            assertEquals(1, store.list(7, ListMailsRequest.getDefaultInstance(), budget()).getMailsCount());
            assertEquals(0L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM mail_asset_op")));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (registry.counter("game_mail_notifications_total", "outcome", "sent").count() == 0 && System.nanoTime() < until)
                Thread.sleep(5);
            assertEquals(1.0, registry.counter("game_mail_notifications_total", "outcome", "sent").count());
            directory.removeAsync(presence).toCompletableFuture().get(2, TimeUnit.SECONDS);
            var offline = com.game.discovery.serviceauth.ServiceCallSignatures.sign(
                    com.game.api.MailAdminService.PERSONAL_METHOD, personal(100, 7), "admin", secret, System.currentTimeMillis());
            assertEquals(0, provider.sendPersonalMail(offline).get(5, TimeUnit.SECONDS).getErrorMessage().getId());
            assertEquals(2, store.list(7, ListMailsRequest.getDefaultInstance(), budget()).getMailsCount());
        } finally {
            directory.removeAsync(presence).toCompletableFuture().get(2, TimeUnit.SECONDS);
            topic.removeListener(subscription);
            registry.close();
            redis.shutdown();
        }
    }
}
