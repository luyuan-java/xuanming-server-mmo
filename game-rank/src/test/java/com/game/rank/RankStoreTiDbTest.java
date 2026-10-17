package com.game.rank;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.MailAdminService;
import com.game.common.deadline.Deadline;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.discovery.sql.*;
import com.game.proto.mail.*;
import com.game.proto.rank.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class RankStoreTiDbTest {
    private String base, database;
    private DruidDataSource pool;
    private AuthorityJdbc jdbc;
    private RankStore store;
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
        database = "rank_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (var c = DriverManager.getConnection(base, "root", ""); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
        pool = new DruidDataSource();
        pool.setUrl(base + database + "?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true");
        pool.setUsername("root");
        pool.setPassword("");
        pool.setMaxActive(16);
        pool.setMaxWait(5000);
        pool.setConnectionInitSqls(List.of(StrongTiDbConnectionPolicy.SESSION_SQL));
        pool.init();
        try (var a = pool.getConnection(); var b = pool.getConnection()) {
            StrongTiDbConnectionPolicy.verify(a, true);
            StrongTiDbConnectionPolicy.verify(b, true);
        }
        RankTables.sync(pool);
        jdbc = new AuthorityJdbc(pool);
        store = new RankStore(jdbc, new AuthorityIds(jdbc, "rank_id_sequence"), id -> id == 1, System::currentTimeMillis);
    }
    @AfterEach
    void cleanup() throws Exception {
        if (pool != null)
            pool.close();
        if (database != null) {
            assertTrue(database.matches("rank_it_[0-9a-f]{16}"));
            try (var c = DriverManager.getConnection(base, "root", ""); var s = c.createStatement()) {
                s.execute("DROP DATABASE " + database);
            }
        }
    }
    private RankBoardKey key() {
        return RankBoardKey.newBuilder().setBoardType(1).setScopeValue(1).setPeriod("season").setGeneration(7).build();
    }
    private OpenRankGenerationRequest opening() {
        return OpenRankGenerationRequest.newBuilder().setBoard(key()).setProducer("match").setSourceEpoch(9).setRequestId(1).build();
    }
    private SubmitRankScoresRequest input(long board, long cursor) {
        return SubmitRankScoresRequest.newBuilder()
                .setBoardId(board)
                .setGeneration(7)
                .setSourceEpoch(9)
                .setSourceCursor(cursor)
                .setRequestId(cursor)
                .setModeValue(2)
                .addEntries(RankScoreInput.newBuilder().setEntityId(7).setScore(100).setAchievedMs(10))
                .addEntries(RankScoreInput.newBuilder().setEntityId(8).setScore(100).setAchievedMs(20))
                .build();
    }
    private FreezeRankGenerationRequest freeze(long board) {
        return FreezeRankGenerationRequest.newBuilder()
                .setBoardId(board)
                .setGeneration(7)
                .setSourceEpoch(9)
                .setExpectedSourceCursor(1)
                .setRequestId(99)
                .setTopN(2)
                .addRewards(RankRewardTier.newBuilder().setFirstRank(1).setLastRank(2).setAttachments(
                        MailAttachments.newBuilder().addCurrencies(MailCurrency.newBuilder().setAmount(10))))
                .build();
    }
    @Test
    void cursorReplaysGapsFinalCursorAndWritesAfterFreezeAreChecked() throws Exception {
        long id = store.open("match", opening(), budget()).getBoardId();
        assertEquals(id, store.open("match", opening(), budget()).getBoardId());
        var r = input(id, 1);
        store.submit("match", r, budget());
        assertTrue(store.submit("match", r, budget()).getDuplicated());
        assertThrows(RankStore.Reject.class, () -> store.submit("match", r.toBuilder().setModeValue(3).build(), budget()));
        assertThrows(RankStore.Reject.class, () -> store.submit("match", input(id, 3), budget()));
        assertThrows(
                RankStore.Reject.class, () -> store.freeze("match", freeze(id).toBuilder().setExpectedSourceCursor(2).build(), budget()));
        var settled = store.freeze("match", freeze(id), budget());
        assertEquals(2, settled.getWinnerCount());
        assertEquals(settled.getSettlementId(), store.freeze("match", freeze(id), budget()).getSettlementId());
        assertThrows(RankStore.Reject.class, () -> store.submit("match", input(id, 2), budget()));
        assertThrows(RankStore.Reject.class, () -> store.submit("battle", r, budget()));
        assertEquals(2L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM rank_snapshot")));
        assertEquals(2L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM rank_mail_reward")));
        var page = store.page(8, GetRankPageRequest.newBuilder().setBoard(key()).setPageSize(1).setPage(1).build(), budget());
        assertEquals(2, page.getTotalCount());
        assertEquals(7, page.getEntries(0).getEntityId());
        assertEquals(2, page.getSelf().getRank());
        assertEquals(2, store.around(8, GetRankAroundRequest.newBuilder().setBoard(key()).build(), budget()).getEntriesCount());
    }
    @Test
    void duplicateSourceCursorAcrossTwoStoreInstancesAppliesIncrementOnce() throws Exception {
        long id = store.open("match", opening(), budget()).getBoardId();
        var peer = new RankStore(jdbc, new AuthorityIds(jdbc, "rank_id_sequence"), value -> true, System::currentTimeMillis);
        var request = input(id, 1).toBuilder().setModeValue(3).build();
        var threads = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var a = threads.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return store.submit("match", request, budget());
            });
            var b = threads.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return peer.submit("match", request, budget());
            });
            assertEquals(1, a.get(20, TimeUnit.SECONDS).getCommittedCursor());
            assertEquals(1, b.get(20, TimeUnit.SECONDS).getCommittedCursor());
        } finally {
            threads.shutdownNow();
        }
        assertEquals(100, store.page(7, GetRankPageRequest.newBuilder().setBoard(key()).build(), budget()).getSelf().getScore());
        assertEquals(1L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM rank_input_receipt")));
    }
    @Test
    void rewardInsertFailureRollsBackBoardSnapshotAndAllIntents() throws Exception {
        long id = store.open("match", opening(), budget()).getBoardId();
        store.submit("match", input(id, 1), budget());
        long next = jdbc.read(budget(), s -> s.one("SELECT next_value FROM rank_id_sequence WHERE sequence_key=1", r -> r.getLong(1)));
        jdbc.tx(budget(), s -> {
            s.update("INSERT INTO rank_mail_reward "
                     + "(reward_id,settlement_id,player_id,`rank`,attachments,status,mail_id,next_attempt_ms,lease_until_ms,lease_token,"
                     + "attempts,created_ms,updated_ms) VALUES (?,800,7,1,?,2,1,0,0,0,0,0,0)",
                    next + 1, new byte[0]);
            return null;
        });
        assertThrows(SQLException.class, () -> store.freeze("match", freeze(id), budget()));
        assertEquals(0L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM rank_snapshot")));
        assertEquals(0L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM rank_settlement")));
        assertEquals(1L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM rank_board WHERE state=1 AND settlement_id=0")));
        jdbc.tx(budget(), s -> {
            s.update("DELETE FROM rank_mail_reward");
            return null;
        });
        assertEquals(2, store.freeze("match", freeze(id), budget()).getWinnerCount());
    }
    @Test
    void restartedRewardWorkerKeepsOriginalMailIdempotencyAndDeletionCannotLoseRewards() throws Exception {
        long id = store.open("match", opening(), budget()).getBoardId();
        store.submit("match", input(id, 1), budget());
        store.freeze("match", freeze(id), budget());
        long reward = store.due(budget(), System.currentTimeMillis()).getFirst();
        String secret = "rank-mail-local-test-only-01234567890123456789";
        var remote = mock(MailAdminService.class);
        var hashes = new ArrayList<String>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(remote.sendPersonalMail(any())).thenAnswer(invocation -> {
            SendMailRequest request = invocation.getArgument(0);
            hashes.add(ServiceCallSignatures.digest(request));
            assertEquals(3, request.getSenderKind());
            assertEquals(reward, request.getRequestId());
            return calls.getAndIncrement() == 0
                    ? CompletableFuture.failedFuture(new TimeoutException("仅测试，远端已提交回包未知"))
                    : CompletableFuture.completedFuture(SendMailResponse.newBuilder().setMailId(600).setDuplicated(true).build());
        });
        new RankRewardWorker(store, secret, remote).process(reward);
        assertEquals(1L,
                (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM rank_mail_reward WHERE reward_id=? AND status=1", reward)));
        var peer = new RankStore(jdbc, new AuthorityIds(jdbc, "rank_id_sequence"), value -> true, System::currentTimeMillis);
        new RankRewardWorker(peer, secret, remote).process(reward);
        assertEquals(hashes.get(0), hashes.get(1));
        assertEquals(1L,
                (long) jdbc.read(budget(),
                        s -> s.count("SELECT COUNT(*) FROM rank_mail_reward WHERE reward_id=? AND status=2 AND mail_id=600", reward)));
        peer.remove("match", RemoveRankEntryRequest.newBuilder().setBoardId(id).setGeneration(7).setRequestId(100).build(), true, budget());
        assertEquals(2L, (long) jdbc.read(budget(), s -> s.count("SELECT COUNT(*) FROM rank_mail_reward")));
        assertThrows(RankStore.Reject.class, () -> peer.page(7, GetRankPageRequest.newBuilder().setBoard(key()).build(), budget()));
    }
}
