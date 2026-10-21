package com.game.rank;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.MailAdminService;
import com.game.api.RankAdminService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.ClientCall;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.presence.PlayerPushes;
import com.game.discovery.serviceauth.DomainClientDispatch;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.discovery.sql.AuthorityIds;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.guild.store.GuildTables;
import com.game.mail.MailClientProvider;
import com.game.mail.MailNotifications;
import com.game.mail.MailProvider;
import com.game.mail.MailStore;
import com.game.mail.MailTables;
import com.game.mail.RankMailClientFixture;
import com.game.match.global.GlobalMatchProperties;
import com.game.match.global.GlobalMatchStore;
import com.game.match.global.GlobalRankProperties;
import com.game.match.global.RatingRankOutbox;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.rating.MatchRatingTables;
import com.game.match.rating.RatingStore;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import com.game.proto.mail.*;
import com.game.proto.rank.*;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 仅由 rank-mail-chain profile 显式运行：真实 TiDB、生产评分/outbox、真实本机 Triple、生产榜奖与邮件列表。
 * 故障包装只在 MailProvider 成功提交后扣住第一次回复；不伪造 SQL 终态或业务应答。
 */
class RankMailChainTiDbTest {
    private static final String RANK_SECRET = "rank-chain-local-fixture-only-match-key-0123456789";
    private static final String MAIL_SECRET = "rank-chain-local-fixture-only-mail-key-0123456789";
    private static final long WINNER = 7001, OTHER = 7002;
    private static String database;
    private static DruidDataSource pool;
    private static AuthorityJdbc sql;
    private static RankStore ranks;
    private static MailStore mails;
    private static RankProvider rankProvider;
    private static MailProvider mailProvider;
    private static DomainClientDispatch mailDispatch;
    private static IsolatedDubboModule server, caller;
    private static RankAdminService rankRemote;
    private static MailAdminService mailRemote;
    private static ClientMessageService mailbox;
    private static final List<ServiceConfig<?>> exports = new ArrayList<>();
    private static final List<ReferenceConfig<?>> references = new ArrayList<>();
    private static final List<RatingRankOutbox> outboxes = new ArrayList<>();
    private static final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private static final LostFirstMailReply lost = new LostFirstMailReply();
    private static int rankPort;

    private static Deadline budget() { return Deadline.after(20_000); }

    @BeforeAll static void start() throws Exception {
        assertEquals("jdbc:mysql://127.0.0.1:4000/", System.getenv("MAIL_RANK_TEST_JDBC"), "仅允许显式本机专用 TiDB");
        assertNotNull(System.getenv("GAME_DUBBO_SECRET"), "真实 Dubbo HMAC 必须由测试进程环境注入");
        database = "rankchain_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:4000/", "root", ""); var s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + database + "`");
        }
        pool = new DruidDataSource();
        pool.setUrl("jdbc:mysql://127.0.0.1:4000/" + database + "?useSSL=false&useAffectedRows=true&connectTimeout=3000&socketTimeout=5000");
        pool.setUsername("root"); pool.setPassword(""); pool.setMaxActive(8); pool.setMaxWait(3_000);
        pool.setConnectionInitSqls(List.of(StrongTiDbConnectionPolicy.SESSION_SQL));
        pool.init();
        try (var first = pool.getConnection(); var second = pool.getConnection()) {
            StrongTiDbConnectionPolicy.verify(first, true);
            StrongTiDbConnectionPolicy.verify(second, true);
        }
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/game-route-authority-schema.sql")).execute(pool);
        try(var connection=pool.getConnection();var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE player(player_id BIGINT UNSIGNED PRIMARY KEY,account VARCHAR(191) NOT NULL,owner_revision BIGINT UNSIGNED NOT NULL,trade_listing_id BIGINT UNSIGNED NOT NULL,zone_id INT UNSIGNED NOT NULL)");
            try(var insert=connection.prepareStatement("INSERT INTO player VALUES(?,'local-fixture',1,0,1)")) {
                for(long player:List.of(WINNER,OTHER)){insert.setLong(1,player);insert.executeUpdate();}
            }
            com.game.common.player.RouteAuthorityInitializer.initialize(connection,java.util.UUID.randomUUID().toString(),List.of(1));
        }
        // 所有领域表均走原来的登记/迁移入口；guild_member 是邮箱可见性查询的真实依赖。
        new GlobalMatchStore(pool, new GlobalMatchProperties(true, true, 30, 180, 120, RANK_SECRET));
        MatchRatingTables.sync(pool, Duration.ofSeconds(60));
        RankTables.sync(pool); MailTables.sync(pool); GuildTables.sync(pool, Duration.ofSeconds(60));
        sql = new AuthorityJdbc(pool);
        ranks = new RankStore(sql, new AuthorityIds(sql, "rank_id_sequence"), id -> id == 1, System::currentTimeMillis);
        mails = new MailStore(sql, new AuthorityIds(sql, "mail_id_sequence"), id -> id == 1, System::currentTimeMillis);
        rankProvider = new RankProvider(ranks, new ServiceCallSignatures(Map.of("match", RANK_SECRET), System::currentTimeMillis));
        // 在线提醒不参与耐久性证明；显式离线夹具，使本用例仅依赖 TiDB 与本机 RPC。
        var pushes = mock(PlayerPushes.class);
        when(pushes.pushToPlayer(anyLong(), any())).thenReturn(CompletableFuture.completedFuture(PlayerPushes.Outcome.OFFLINE));
        var notifications = new MailNotifications(pushes,
                MessageIdRegistry.loadFromClasspath().requireId("ClientPlayerMail", "NotifyMailChanged"), metrics);
        mailProvider = new MailProvider(mails, new ServiceCallSignatures(Map.of("rank", MAIL_SECRET), System::currentTimeMillis), notifications);
        lost.delegate = mailProvider;
        mailDispatch = RankMailClientFixture.dispatcher(mails);
        server = IsolatedDubboModule.create("rank-mail-chain-provider");
        caller = IsolatedDubboModule.create("rank-mail-chain-caller");
        rankPort = freePort();
        int mailPort = freePort();
        export(RankAdminService.class, rankProvider, DubboGroups.RANK, rankPort);
        export(MailAdminService.class, lost, DubboGroups.MAIL, mailPort);
        export(ClientMessageService.class, new MailClientProvider(mailDispatch), DubboGroups.MAIL, mailPort);
        rankRemote = reference(RankAdminService.class, DubboGroups.RANK, rankPort, true);
        mailRemote = reference(MailAdminService.class, DubboGroups.MAIL, mailPort, true);
        mailbox = reference(ClientMessageService.class, DubboGroups.MAIL, mailPort, true);
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }

    private static <T> void export(Class<T> type, T provider, String group, int port) {
        var protocol = new ProtocolConfig("tri", port);
        protocol.setHost("127.0.0.1");
        var service = new ServiceConfig<T>(server.module());
        service.setInterface(type); service.setRef(provider); service.setGroup(group); service.setScope("remote");
        service.setRegister(false); service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE)); service.setProtocol(protocol);
        service.export(); exports.add(service);
    }

    private static <T> T reference(Class<T> type, String group, int port, boolean authenticated) {
        var reference = new ReferenceConfig<T>(caller.module());
        reference.setInterface(type); reference.setGroup(group); reference.setUrl("tri://127.0.0.1:" + port);
        reference.setInjvm(false); reference.setCheck(false); reference.setRetries(0); reference.setTimeout(1_500);
        if (!authenticated) reference.setFilter("-gameAuthConsumer");
        references.add(reference);
        return reference.get();
    }

    private static <T extends Message> T signed(String method, T request) {
        return ServiceCallSignatures.sign(method, request, "match", RANK_SECRET, System.currentTimeMillis());
    }

    private static long count(String query, Object... args) throws Exception { return sql.read(budget(), s -> s.count(query, args)); }

    private static RatingRankOutbox outbox() {
        var outbox = new RatingRankOutbox(pool, new GlobalRankProperties("127.0.0.1", rankPort, RANK_SECRET));
        outboxes.add(outbox); return outbox;
    }

    private static BattleResultEvent result(long battle) {
        return BattleResultEvent.newBuilder().setBattleId(battle).setMatchMode(3).setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(WINNER))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(OTHER)).build();
    }

    private static ListMailsResponse mailbox(long player) throws Exception {
        var request = ListMailsRequest.newBuilder().setLimit(10).build();
        var response = mailbox.handle(ClientCall.newBuilder()
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("local-fixture-gate")
                        .setSessionId(1).setZoneId(1).setPlayerId(player).setAccount("local-fixture").setOwnerRevision(1))
                .setMessageId(MessageIdRegistry.loadFromClasspath().requireId("ClientPlayerMail", "ListMails"))
                .setRequestId(1).setBody(request.toByteString()).build()).get(8, TimeUnit.SECONDS);
        assertEquals(0, response.getTipId());
        var list = ListMailsResponse.parseFrom(response.getBody());
        assertEquals(0, list.getErrorMessage().getId());
        return list;
    }

    @Test @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void SQL评分连续游标停代封榜到真实发信未知回复后重启仅一封且权威列表可见() throws Exception {
        // 同时核对传输 HMAC 与领域 HMAC，拒绝均不能进入领域写入。
        var opening = OpenRankGenerationRequest.newBuilder().setProducer("match").setSourceEpoch(1).setRequestId(1)
                .setBoard(RankBoardKey.newBuilder().setBoardType(1).setScope(RankScope.RANK_SCOPE_GLOBAL).setPeriod("all").setGeneration(1)).build();
        assertThrows(Exception.class, () -> rankRemote.openRankGeneration(opening).get(8, TimeUnit.SECONDS));
        var anonymous = reference(RankAdminService.class, DubboGroups.RANK, rankPort, false);
        assertThrows(Exception.class, () -> anonymous.openRankGeneration(signed(RankAdminService.OPEN_METHOD, opening)).get(8, TimeUnit.SECONDS));
        assertEquals(0, count("SELECT COUNT(*) FROM rank_board"));
        assertThrows(Exception.class, () -> mailRemote.sendPersonalMail(SendMailRequest.getDefaultInstance()).get(8, TimeUnit.SECONDS));
        assertEquals(0, count("SELECT COUNT(*) FROM mail_message"));

        var firstOutbox = outbox();
        var ratings = new RatingStore(RatingStore.connections(pool), id -> 30,
                new MatchMetrics(metrics, new MetricLabels(id -> false)), System::currentTimeMillis).withCommitHook(firstOutbox);
        var first = result(91001);
        assertEquals(RatingStore.Outcome.APPLIED, ratings.apply(first).outcome());
        assertEquals(RatingStore.Outcome.DUPLICATE, ratings.apply(first).outcome());
        assertEquals(RatingStore.Outcome.APPLIED, ratings.apply(result(91002)).outcome());
        assertEquals(2, count("SELECT COUNT(*) FROM match_rating_applied"));
        assertEquals(2, count("SELECT COUNT(*) FROM match_global_rank_outbox"));
        var attachments = MailAttachments.newBuilder().addCurrencies(MailCurrency.newBuilder().setCurrencyType(0).setAmount(10))
                .addItems(MailItem.newBuilder().setConfigId(1).setCount(3)).build();
        var sealing = FreezeRankGenerationRequest.newBuilder().setRequestId(99).setTopN(2)
                .addRewards(RankRewardTier.newBuilder().setFirstRank(1).setLastRank(1).setAttachments(attachments)).build();
        assertThrows(IllegalStateException.class, () -> firstOutbox.freeze(sealing));
        assertEquals(2, firstOutbox.stopSource());
        assertEquals(2, firstOutbox.stopSource());
        var before = ratings.findOrFresh(WINNER);
        assertThrows(RatingStore.StoreException.class, () -> ratings.apply(result(91003)));
        assertEquals(before, ratings.findOrFresh(WINNER));
        assertEquals(2, count("SELECT COUNT(*) FROM match_rating_applied"));
        assertThrows(IllegalStateException.class, () -> firstOutbox.freeze(sealing));

        // 用新对象从 SQL 恢复来源；不依赖前一个 outbox 的进程内状态。
        var recoveredOutbox = outbox();
        recoveredOutbox.publish(); recoveredOutbox.publish();
        assertEquals(2, count("SELECT committed_cursor FROM match_global_rank_source WHERE source_epoch=1"));
        long board = count("SELECT board_id FROM match_global_rank_source WHERE source_epoch=1");
        assertEquals(2, count("SELECT COUNT(*) FROM rank_input_receipt"));
        var page = ranks.page(WINNER, GetRankPageRequest.newBuilder().setBoard(opening.getBoard()).build(), budget());
        assertEquals(2, page.getTotalCount());
        assertEquals(WINNER, page.getEntries(0).getEntityId());
        assertEquals(before.ratingCenti(), page.getEntries(0).getScore());
        assertEquals(2, count("SELECT committed_cursor FROM rank_board WHERE board_id=?", board));
        byte[] originalBytes = sql.read(budget(), s -> s.one(
                "SELECT entries FROM match_global_rank_outbox WHERE source_epoch=1 AND source_cursor=1", row -> row.getBytes(1)));
        var original = SubmitRankScoresRequest.parseFrom(originalBytes);
        var replay = original.toBuilder().setBoardId(board).build();
        var replayed = rankRemote.submitRankScores(signed(RankAdminService.SUBMIT_METHOD, replay)).get(8, TimeUnit.SECONDS);
        assertEquals(0, replayed.getErrorMessage().getId()); assertTrue(replayed.getDuplicated()); assertEquals(2, replayed.getCommittedCursor());
        var gap = replay.toBuilder().setSourceCursor(4).setRequestId(91004).build();
        assertNotEquals(0, rankRemote.submitRankScores(signed(RankAdminService.SUBMIT_METHOD, gap)).get(8, TimeUnit.SECONDS).getErrorMessage().getId());
        assertEquals(2, count("SELECT committed_cursor FROM rank_board WHERE board_id=?", board));

        var frozen = recoveredOutbox.freeze(sealing);
        assertEquals(0, frozen.getErrorMessage().getId()); assertEquals(2, frozen.getWinnerCount());
        var duplicated = recoveredOutbox.freeze(sealing);
        assertEquals(frozen.getSettlementId(), duplicated.getSettlementId()); assertTrue(duplicated.getDuplicated());
        assertNotEquals(0, recoveredOutbox.freeze(sealing.toBuilder().setTopN(1).build()).getErrorMessage().getId());
        assertEquals(1, count("SELECT COUNT(*) FROM rank_settlement"));
        assertEquals(2, count("SELECT COUNT(*) FROM rank_snapshot"));
        assertEquals(1, count("SELECT COUNT(*) FROM rank_mail_reward"));

        long reward = ranks.due(budget(), System.currentTimeMillis()).getFirst();
        new RankRewardWorker(ranks, MAIL_SECRET, mailRemote).process(reward);
        var committed = lost.committed.get(5, TimeUnit.SECONDS);
        assertEquals(0, committed.getErrorMessage().getId()); assertNotEquals(0, committed.getMailId());
        assertEquals(1, count("SELECT COUNT(*) FROM mail_message"));
        assertEquals(1, count("SELECT COUNT(*) FROM rank_mail_reward WHERE reward_id=? AND status=1 AND mail_id=0 AND attempts=1", reward));
        var visibleWhileUnknown = mailbox(WINNER);
        assertEquals(1, visibleWhileUnknown.getMailsCount());
        assertEquals(committed.getMailId(), visibleWhileUnknown.getMails(0).getMailId());
        assertEquals(attachments, visibleWhileUnknown.getMails(0).getAttachments());
        assertEquals(0, mailbox(OTHER).getMailsCount());

        var restarted = new RankStore(sql, new AuthorityIds(sql, "rank_id_sequence"), id -> id == 1, System::currentTimeMillis);
        try (var worker = new RankRewardWorker(restarted, MAIL_SECRET, mailRemote)) {
            worker.process(reward); worker.process(reward);
        }
        assertEquals(2, lost.requests.size());
        assertEquals(ServiceCallSignatures.digest(lost.requests.get(0)), ServiceCallSignatures.digest(lost.requests.get(1)));
        assertEquals(reward, lost.requests.get(1).getRequestId());
        assertEquals(1, count("SELECT COUNT(*) FROM rank_mail_reward WHERE reward_id=? AND status=2 AND mail_id=? AND attempts=2", reward, committed.getMailId()));
        assertEquals(1, count("SELECT COUNT(*) FROM mail_message"));
        assertEquals(visibleWhileUnknown.getMailsList(), mailbox(WINNER).getMailsList());
        assertEquals(1, mailbox(WINNER).getClaimableCount());
        assertEquals(0, count("SELECT COUNT(*) FROM mail_asset_op"), "发信不提前应用资产，附件留待邮件领取链");
        assertEquals(1, count("SELECT COUNT(*) FROM rank_mail_reward"));
    }

    /** 真实服务提交后只丢第一份回包，后续全部交还真实服务处理重复请求。 */
    private static final class LostFirstMailReply implements MailAdminService {
        MailAdminService delegate;
        final AtomicBoolean drop = new AtomicBoolean(true);
        final CompletableFuture<SendMailResponse> committed = new CompletableFuture<>();
        final List<SendMailRequest> requests = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<SendMailResponse>> held = new CopyOnWriteArrayList<>();
        public CompletableFuture<SendMailResponse> sendPersonalMail(SendMailRequest request) {
            return delegate.sendPersonalMail(request).thenCompose(response -> {
                if (response.getErrorMessage().getId() == 0)
                    requests.add(request);
                if (response.getErrorMessage().getId() == 0 && drop.compareAndSet(true, false)) {
                    committed.complete(response);
                    var lost = new CompletableFuture<SendMailResponse>(); held.add(lost); return lost;
                }
                return CompletableFuture.completedFuture(response);
            });
        }
        public CompletableFuture<SendMailResponse> sendSystemMail(SendMailRequest r) { return delegate.sendSystemMail(r); }
        public CompletableFuture<SendMailResponse> sendGuildMail(SendMailRequest r) { return delegate.sendGuildMail(r); }
        public CompletableFuture<ImportMailRecipientsResponse> importMailRecipients(ImportMailRecipientsRequest r) { return delegate.importMailRecipients(r); }
        public CompletableFuture<SendMailResponse> sealMailCampaign(SealMailCampaignRequest r) { return delegate.sealMailCampaign(r); }
    }

    @AfterAll static void close() throws Exception {
        lost.held.forEach(pending -> pending.completeExceptionally(new IllegalStateException("测试故障夹具关闭")));
        // 所有操作结束后才销毁模型，避免 Dubbo 3.3.6 进程级 Triple 端口表影响仍在用的其它模型。
        references.forEach(ReferenceConfig::destroy);
        outboxes.forEach(RatingRankOutbox::close);
        exports.forEach(ServiceConfig::unexport);
        if (caller != null) caller.close(); if (server != null) server.close();
        if (mailDispatch != null) mailDispatch.close(); if (mailProvider != null) mailProvider.close(); if (rankProvider != null) rankProvider.close();
        metrics.close(); if (pool != null) pool.close();
        if (database != null) {
            assertTrue(database.matches("rankchain_[0-9a-f]{32}"));
            try (var c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:4000/", "root", ""); var s = c.createStatement()) {
                s.execute("DROP DATABASE `" + database + "`");
            }
        }
    }
}
