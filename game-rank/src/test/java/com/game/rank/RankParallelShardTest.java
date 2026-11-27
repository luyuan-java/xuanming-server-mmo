package com.game.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.common.deadline.Deadline;
import com.game.common.sql.AuthorityDataSource;
import com.game.common.sql.WriterAuthority;
import com.game.discovery.sql.AuthorityIds;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.rank.*;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 验证主仓缺口清单第 17 条：排行榜同榜写入按来源片并行（只锁本片不锁榜行）、封榜/移除按片升序锁全片及计数汇总准确。 */
class RankParallelShardTest {

    private static String h2Ddl(String ddl) {
        return ddl.replace(" bigint unsigned ", " NUMERIC(20) ").replace(" int unsigned ", " BIGINT ")
                .replaceAll(" CHARACTER SET [^ ]+ COLLATE [^ ]+", "")
                .replaceAll("\\) ENGINE=InnoDB[^\\n]*;\\s*$", ");");
    }

    private static DataSource createH2Db() throws Exception {
        String url = "jdbc:h2:mem:rank_shard_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        JdbcDataSource raw = new JdbcDataSource();
        raw.setURL(url);
        raw.setUser("sa");
        try (Connection c = raw.getConnection(); Statement s = c.createStatement()) {
            for (var table : RankTables.registry().tables()) {
                s.execute(h2Ddl(table.createTableSql()));
            }
        }
        byte[] inst = new byte[16];
        inst[0] = 5;
        WriterAuthority boot = Mockito.mock(WriterAuthority.class, inv -> {
            String name = inv.getMethod().getName();
            if ("begin".equals(name)) {
                ((Connection) inv.getArgument(0)).setAutoCommit(false);
                return null;
            }
            if ("verifyPhysical".equals(name) || "verifyReadGate".equals(name) || "seal".equals(name)) return null;
            if ("isSealed".equals(name)) return false;
            if ("instanceId".equals(name)) return inst.clone();
            return Mockito.RETURNS_DEFAULTS.answer(inv);
        });
        Properties props = new Properties();
        props.setProperty("user", "sa");
        props.setProperty("password", "");
        return new AuthorityDataSource(url, props, boot, false, List.of());
    }

    @Test
    void parallelSourceShardsAdvanceIndependentlyAggregateTotalAndFreezeCleanly() throws Exception {
        DataSource ds = createH2Db();
        AuthorityJdbc jdbc = new AuthorityJdbc(ds, false, false, false);
        AtomicLong idGen = new AtomicLong(100);
        AuthorityIds ids = mock(AuthorityIds.class);
        Mockito.when(ids.next(Mockito.any())).thenAnswer(i -> idGen.incrementAndGet());
        Mockito.when(ids.reserve(Mockito.any(), Mockito.anyInt())).thenAnswer(i -> idGen.addAndGet((Integer) i.getArgument(1)));

        RankStore store = new RankStore(jdbc, ids, item -> true, () -> 1_700_000_000_000L);
        Deadline d = Deadline.afterMillis(10_000);
        RankBoardKey key = RankBoardKey.newBuilder()
                .setBoardType(1).setScope(RankScope.RANK_SCOPE_GLOBAL).setScopeId(0)
                .setPeriod("2026-S1").setGeneration(1).build();

        long boardId = store.open("match", OpenRankGenerationRequest.newBuilder()
                .setBoard(key).setProducer("match").setSourceEpoch(1).setRequestId(1).setAscending(false).build(), d).getBoardId();

        // 片 0 与片 1 各自推进独立 source_cursor=1（不互相阻塞 rank_board 行）
        var s0 = store.submitShard("match", SubmitRankScoresRequest.newBuilder()
                .setBoardId(boardId).setGeneration(1).setSourceEpoch(1).setSourceCursor(1).setRequestId(11)
                .setMode(RankScoreWriteMode.RANK_SCORE_WRITE_MODE_REPLACE)
                .addEntries(RankScoreInput.newBuilder().setEntityId(1001).setScore(1800).setAchievedMs(1000))
                .addEntries(RankScoreInput.newBuilder().setEntityId(1002).setScore(1600).setAchievedMs(1001))
                .build(), 0, d);
        assertThat(s0.getCommittedCursor()).isEqualTo(1L);

        var s1 = store.submitShard("match", SubmitRankScoresRequest.newBuilder()
                .setBoardId(boardId).setGeneration(1).setSourceEpoch(1).setSourceCursor(1).setRequestId(12)
                .setMode(RankScoreWriteMode.RANK_SCORE_WRITE_MODE_REPLACE)
                .addEntries(RankScoreInput.newBuilder().setEntityId(2001).setScore(1950).setAchievedMs(1002))
                .build(), 1, d);
        assertThat(s1.getCommittedCursor()).isEqualTo(1L);

        // 片 1 重放 cursor=1 幂等
        var s1Dup = store.submitShard("match", SubmitRankScoresRequest.newBuilder()
                .setBoardId(boardId).setGeneration(1).setSourceEpoch(1).setSourceCursor(1).setRequestId(12)
                .setMode(RankScoreWriteMode.RANK_SCORE_WRITE_MODE_REPLACE)
                .addEntries(RankScoreInput.newBuilder().setEntityId(2001).setScore(1950).setAchievedMs(1002))
                .build(), 1, d);
        assertThat(s1Dup.getDuplicated()).isTrue();

        // 分页查询汇总两片条目数 = 3，顺序 2001(1950) > 1001(1800) > 1002(1600)
        var page = store.page(1001L, GetRankPageRequest.newBuilder().setBoard(key).setPage(1).setPageSize(10).build(), d);
        assertThat(page.getTotalCount()).isEqualTo(3L);
        assertThat(page.getEntriesList()).extracting(RankEntry::getEntityId).containsExactly(2001L, 1001L, 1002L);

        // 移除实体 1002 后总条目数降为 2
        store.remove("match", RemoveRankEntryRequest.newBuilder()
                .setBoardId(boardId).setGeneration(1).setEntityId(1002L).setRequestId(99).build(), false, d);
        var pageAfterRemove = store.page(1001L, GetRankPageRequest.newBuilder().setBoard(key).setPage(1).setPageSize(10).build(), d);
        assertThat(pageAfterRemove.getTotalCount()).isEqualTo(2L);

        // 封榜按片号升序锁全片并落盘最终赢家快照
        var frozen = store.freeze("match", FreezeRankGenerationRequest.newBuilder()
                .setBoardId(boardId).setGeneration(1).setSourceEpoch(1).setExpectedSourceCursor(1)
                .setRequestId(200).setTopN(10).build(), d);
        assertThat(frozen.getWinnerCount()).isEqualTo(2L);
    }
}
