package com.game.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityIds;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.rank.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 验证主仓缺口清单第 17 条：排行榜同榜写入按来源片并行（只锁本片不锁榜行）、新实体撞唯一键整笔重试、封榜/移除先锁榜行再按片升序锁全片。 */
class RankParallelShardTest {

    @Test
    void submitShardSkipsBoardLockAdvancesShardsIndependentlyRetriesDupKeyAndFreezeLocksShardsInOrder() throws Exception {
        List<String> executedSqls = new ArrayList<>();
        Map<Integer, long[]> shards = new TreeMap<>(); // shard -> [cursor, entries]
        Map<String, String> receipts = new LinkedHashMap<>();
        Map<Long, long[]> scores = new TreeMap<>(); // entityId -> [score, achievedMs]
        AtomicBoolean injectDupOnce = new AtomicBoolean(true);

        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(inv -> fakeConn(executedSqls, shards, receipts, scores, injectDupOnce));

        AuthorityJdbc jdbc = new AuthorityJdbc(ds);
        AtomicLong idGen = new AtomicLong(100);
        AuthorityIds ids = mock(AuthorityIds.class);
        when(ids.next(any())).thenAnswer(i -> idGen.incrementAndGet());
        when(ids.reserve(any(), anyInt())).thenAnswer(i -> idGen.addAndGet((Integer) i.getArgument(1)));

        RankStore store = new RankStore(jdbc, ids, item -> true, () -> 1_700_000_000_000L);
        Deadline d = Deadline.after(10_000L);

        // 1. 片 0 提交 cursor=1（首次模拟新实体撞唯一键 1062，整笔重试后成功），绝不锁 rank_board
        var req0 = SubmitRankScoresRequest.newBuilder()
                .setBoardId(1L).setGeneration(1L).setSourceEpoch(1L).setSourceCursor(1L).setRequestId(11L)
                .setModeValue(2)
                .addEntries(RankScoreInput.newBuilder().setEntityId(1001L).setScore(1800L).setAchievedMs(1000L))
                .addEntries(RankScoreInput.newBuilder().setEntityId(1002L).setScore(1600L).setAchievedMs(1001L))
                .build();
        var res0 = store.submitShard("match", req0, 0, d);
        assertThat(res0.getCommittedCursor()).isEqualTo(1L);
        assertThat(injectDupOnce.get()).isFalse();
        assertThat(executedSqls.stream().noneMatch(s -> s.contains("FROM rank_board ") && s.contains("FOR UPDATE")))
                .as("submitShard 不得锁 rank_board 行").isTrue();
        assertThat(executedSqls.stream().anyMatch(s -> s.contains("FROM rank_board_shard ") && s.contains("FOR UPDATE")))
                .isTrue();
        assertThat(executedSqls.stream().anyMatch(s -> s.contains("FROM rank_score ") && s.contains("FOR UPDATE")))
                .isTrue();

        // 2. 片 1 独立提交自己的 cursor=1（与片 0 互不阻塞、各自计数）
        var req1 = SubmitRankScoresRequest.newBuilder()
                .setBoardId(1L).setGeneration(1L).setSourceEpoch(1L).setSourceCursor(1L).setRequestId(12L)
                .setModeValue(2)
                .addEntries(RankScoreInput.newBuilder().setEntityId(2001L).setScore(1950L).setAchievedMs(1002L))
                .build();
        var res1 = store.submitShard("match", req1, 1, d);
        assertThat(res1.getCommittedCursor()).isEqualTo(1L);
        assertThat(shards.get(0)).containsExactly(1L, 2L);
        assertThat(shards.get(1)).containsExactly(1L, 1L);

        // 3. 片 1 重放 cursor=1 幂等返回 duplicated=true
        var dup1 = store.submitShard("match", req1, 1, d);
        assertThat(dup1.getDuplicated()).isTrue();

        // 4. 封榜先锁 rank_board FOR UPDATE，再按片号升序锁 rank_board_shard ORDER BY source_shard FOR UPDATE
        executedSqls.clear();
        var frozen = store.freeze("match", FreezeRankGenerationRequest.newBuilder()
                .setBoardId(1L).setGeneration(1L).setSourceEpoch(1L).setExpectedSourceCursor(1L)
                .setRequestId(200L).setTopN(10).build(), d);
        assertThat(frozen.getWinnerCount()).isEqualTo(3L);
        int boardLockIdx = indexOfMatch(executedSqls, s -> s.contains("FROM rank_board ") && s.contains("FOR UPDATE"));
        int shardLockIdx = indexOfMatch(executedSqls, s -> s.contains("FROM rank_board_shard ") && s.contains("ORDER BY source_shard FOR UPDATE"));
        assertThat(boardLockIdx).isGreaterThanOrEqualTo(0);
        assertThat(shardLockIdx).isGreaterThan(boardLockIdx);
    }

    private static int indexOfMatch(List<String> list, java.util.function.Predicate<String> pred) {
        for (int i = 0; i < list.size(); i++) {
            if (pred.test(list.get(i))) return i;
        }
        return -1;
    }

    private static Connection fakeConn(List<String> sqls, Map<Integer, long[]> shards,
                                       Map<String, String> receipts, Map<Long, long[]> scores,
                                       AtomicBoolean injectDupOnce) throws Exception {
        Connection c = mock(Connection.class);
        when(c.isWrapperFor(any())).thenReturn(false);
        when(c.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            sqls.add(sql);
            PreparedStatement ps = mock(PreparedStatement.class);
            Map<Integer, Object> params = new LinkedHashMap<>();
            doAnswer(a -> { params.put(a.getArgument(0), a.getArgument(1)); return null; }).when(ps).setObject(anyInt(), any());
            doAnswer(a -> { params.put(a.getArgument(0), a.getArgument(1)); return null; }).when(ps).setInt(anyInt(), anyInt());
            doAnswer(a -> { params.put(a.getArgument(0), a.getArgument(1)); return null; }).when(ps).setLong(anyInt(), any(Long.class));
            doAnswer(a -> { params.put(a.getArgument(0), a.getArgument(1)); return null; }).when(ps).setString(anyInt(), anyString());
            doAnswer(a -> { params.put(a.getArgument(0), a.getArgument(1)); return null; }).when(ps).setBytes(anyInt(), any());

            when(ps.executeQuery()).thenAnswer(q -> {
                List<Object[]> rows = new ArrayList<>();
                if (sql.contains("FROM route_meta")) {
                    rows.add(new Object[] {"00000000-0000-0000-0000-000000000001", "1", 64});
                } else if (sql.contains("FROM rank_board ")) {
                    rows.add(new Object[] {1L, 1, 1, 0L, "2026-S1", 1L, "match", 1L, 0L, false, 1, 0L, "h", 0L, 0L});
                } else if (sql.contains("SELECT committed_cursor FROM rank_board_shard")) {
                    int sh = ((Number) params.get(3)).intValue();
                    long[] st = shards.getOrDefault(sh, new long[] {0L, 0L});
                    rows.add(new Object[] {st[0]});
                } else if (sql.contains("SELECT source_shard,committed_cursor,entries FROM rank_board_shard")) {
                    shards.forEach((sh, st) -> rows.add(new Object[] {sh, st[0], st[1]}));
                } else if (sql.contains("FROM rank_input_receipt")) {
                    String k = params.get(3) + ":" + params.get(4);
                    if (receipts.containsKey(k)) rows.add(new Object[] {receipts.get(k)});
                } else if (sql.contains("FROM rank_score") && sql.contains("ORDER BY order_key")) {
                    scores.entrySet().stream()
                            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                            .forEach(e -> rows.add(new Object[] {e.getKey(), e.getValue()[0], e.getValue()[1]}));
                }
                return fakeRs(rows);
            });

            when(ps.executeUpdate()).thenAnswer(u -> {
                if (sql.startsWith("INSERT INTO rank_board_shard")) {
                    int sh = ((Number) params.get(3)).intValue();
                    shards.putIfAbsent(sh, new long[] {0L, 0L});
                } else if (sql.startsWith("INSERT INTO rank_score")) {
                    if (injectDupOnce.compareAndSet(true, false)) {
                        throw new SQLException("Duplicate entry for key PRIMARY", "23000", 1062);
                    }
                    int count = params.size() / 7;
                    for (int i = 0; i < count; i++) {
                        long eid = ((Number) params.get(i * 7 + 3)).longValue();
                        long score = ((Number) params.get(i * 7 + 4)).longValue();
                        long ach = ((Number) params.get(i * 7 + 6)).longValue();
                        scores.put(eid, new long[] {score, ach});
                    }
                } else if (sql.startsWith("INSERT INTO rank_input_receipt")) {
                    String k = params.get(3) + ":" + params.get(4);
                    receipts.put(k, (String) params.get(6));
                } else if (sql.startsWith("UPDATE rank_board_shard")) {
                    long cursor = ((Number) params.get(1)).longValue();
                    long added = ((Number) params.get(2)).longValue();
                    int sh = ((Number) params.get(5)).intValue();
                    long[] st = shards.computeIfAbsent(sh, k -> new long[] {0L, 0L});
                    st[0] = cursor;
                    st[1] += added;
                }
                return 1;
            });
            return ps;
        });
        return c;
    }

    private static ResultSet fakeRs(List<Object[]> rows) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        int[] idx = {-1};
        when(rs.next()).thenAnswer(a -> ++idx[0] < rows.size());
        when(rs.getObject(anyInt())).thenAnswer(a -> rows.get(idx[0])[(Integer) a.getArgument(0) - 1]);
        when(rs.getString(anyInt())).thenAnswer(a -> String.valueOf(rows.get(idx[0])[(Integer) a.getArgument(0) - 1]));
        when(rs.getInt(anyInt())).thenAnswer(a -> ((Number) rows.get(idx[0])[(Integer) a.getArgument(0) - 1]).intValue());
        when(rs.getLong(anyInt())).thenAnswer(a -> ((Number) rows.get(idx[0])[(Integer) a.getArgument(0) - 1]).longValue());
        when(rs.getBoolean(anyInt())).thenAnswer(a -> (Boolean) rows.get(idx[0])[(Integer) a.getArgument(0) - 1]);
        return rs;
    }
}
