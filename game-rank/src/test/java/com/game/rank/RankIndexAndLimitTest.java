package com.game.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.game.api.MailAdminService;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityIds;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.mail.MailAttachments;
import com.game.proto.mail.MailCurrency;
import com.game.proto.mail.SendMailResponse;
import com.game.proto.rank.*;
import com.game.table.MailErrorTip;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 校验名次索引 order_key、entries 计数、前 N 名精确/N+ 边界与 17011 收件角色已删终态放弃（无外部依赖）。 */
class RankIndexAndLimitTest {

    private static final String SECRET = "rank-test-secret-0123456789abcdef0123456789";

    @Test
    void 排序键覆盖全部int64无溢出_分页窗口与名次上限纯函数准确() {
        assertThat(RankStore.orderKey(true, Long.MIN_VALUE)).isLessThan(RankStore.orderKey(true, 0L));
        assertThat(RankStore.orderKey(true, 0L)).isLessThan(RankStore.orderKey(true, Long.MAX_VALUE));
        // 降序：分数越大 orderKey 越小，Long.MIN_VALUE 不溢出
        assertThat(RankStore.orderKey(false, Long.MAX_VALUE)).isLessThan(RankStore.orderKey(false, 1L));
        assertThat(RankStore.orderKey(false, 1L)).isLessThan(RankStore.orderKey(false, 0L));
        assertThat(RankStore.orderKey(false, 0L)).isLessThan(RankStore.orderKey(false, -1L));
        assertThat(RankStore.orderKey(false, -1L)).isLessThan(RankStore.orderKey(false, Long.MIN_VALUE));

        // 空榜 1 页 0 行
        RankStore.PageWindow empty = RankStore.pageWindowFor(0, 1, 20, 10_000);
        assertThat(empty).isEqualTo(new RankStore.PageWindow(1, 1, 0, 0));
        // total=25, limit=5, pageSize=3 -> 只翻前 5 名共 2 页，第 2 页只取 2 行（第 4、5 名），超页码钳到第 2 页
        RankStore.PageWindow p1 = RankStore.pageWindowFor(25, 1, 3, 5);
        assertThat(p1).isEqualTo(new RankStore.PageWindow(1, 2, 0, 3));
        RankStore.PageWindow p2 = RankStore.pageWindowFor(25, 99, 3, 5);
        assertThat(p2).isEqualTo(new RankStore.PageWindow(2, 2, 3, 2));

        // rankOrBeyond 边界
        RankEntry exact = RankStore.rankOrBeyond(RankEntry.newBuilder().setEntityId(1), 10_000, 10_000);
        assertThat(exact.getRank()).isEqualTo(10_000);
        assertThat(exact.getBeyondRankLimit()).isFalse();
        RankEntry over = RankStore.rankOrBeyond(RankEntry.newBuilder().setEntityId(2), 10_001, 10_000);
        assertThat(over.getRank()).isZero();
        assertThat(over.getBeyondRankLimit()).isTrue();
        RankEntry unknown = RankStore.rankOrBeyond(RankEntry.newBuilder().setEntityId(3), 0, 10_000);
        assertThat(unknown.getRank()).isZero();
        assertThat(unknown.getBeyondRankLimit()).isTrue();
    }

    @Test
    void 分页与附近名次走索引且不跑全表ROW_NUMBER_超上限回rank0与beyond_rank_limit() throws Exception {
        // 8 条降序数据，rankLimit=5：1..5 名精确，6..8 名显示 5+
        record Row(long entityId, long score, long orderKey, long achievedMs) {}
        List<Row> board = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            long score = 1000L - i * 10L;
            board.add(new Row(100L + i, score, RankStore.orderKey(false, score), 1000L + i));
        }
        Comparator<Row> rankCmp = Comparator.comparingLong(Row::orderKey)
                .thenComparingLong(Row::achievedMs)
                .thenComparing(Row::entityId, Long::compareUnsigned);
        board.sort(rankCmp);

        List<String> executedSql = new ArrayList<>();
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(inv -> fakeSnapshotConnection(board, rankCmp, executedSql));

        RankStore store = new RankStore(new AuthorityJdbc(ds), mock(AuthorityIds.class), id -> true, () -> 5000L, 5L);
        RankBoardKey key = RankBoardKey.newBuilder().setBoardType(3).setScopeValue(1).setPeriod("all").setGeneration(1).build();

        // 1. 翻末页（请求第 99 页，pageSize=3）：共 8 人但 limit=5 -> page_count=2，第 2 页只含第 4、5 名；第 7 名查自己回 rank=0 + beyond_rank_limit=true
        GetRankPageResponse pageResp = store.page(107L,
                GetRankPageRequest.newBuilder().setBoard(key).setPage(99).setPageSize(3).build(), Deadline.after(5000));
        assertThat(pageResp.getTotalCount()).isEqualTo(8L);
        assertThat(pageResp.getRankLimit()).isEqualTo(5L);
        assertThat(pageResp.getPageCount()).isEqualTo(2L);
        assertThat(pageResp.getPage()).isEqualTo(2);
        assertThat(pageResp.getEntriesList()).extracting(RankEntry::getEntityId, RankEntry::getRank, RankEntry::getBeyondRankLimit)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(104L, 4L, false),
                        org.assertj.core.groups.Tuple.tuple(105L, 5L, false));
        assertThat(pageResp.hasSelf()).isTrue();
        assertThat(pageResp.getSelf().getEntityId()).isEqualTo(107L);
        assertThat(pageResp.getSelf().getRank()).isZero();
        assertThat(pageResp.getSelf().getBeyondRankLimit()).isTrue();

        // 2. 跨边界附近名次：第 6 名 radius=2 -> 前两位（第 4、5 名）精确，本人第 6 名与后面（第 7、8 名）全标 beyond_rank_limit=true
        GetRankAroundResponse aroundBorder = store.around(106L,
                GetRankAroundRequest.newBuilder().setBoard(key).setRadius(2).build(), Deadline.after(5000));
        assertThat(aroundBorder.getRankLimit()).isEqualTo(5L);
        assertThat(aroundBorder.getSelf().getRank()).isZero();
        assertThat(aroundBorder.getSelf().getBeyondRankLimit()).isTrue();
        assertThat(aroundBorder.getEntriesList()).extracting(RankEntry::getEntityId, RankEntry::getRank, RankEntry::getBeyondRankLimit)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(104L, 4L, false),
                        org.assertj.core.groups.Tuple.tuple(105L, 5L, false),
                        org.assertj.core.groups.Tuple.tuple(106L, 0L, true),
                        org.assertj.core.groups.Tuple.tuple(107L, 0L, true),
                        org.assertj.core.groups.Tuple.tuple(108L, 0L, true));

        // 3. 远超上限（第 8 名 radius=1，cap = 5 + 1 = 6，前面 7 人触发 capped）：本人与全部邻居都标 beyond_rank_limit=true
        GetRankAroundResponse aroundFar = store.around(108L,
                GetRankAroundRequest.newBuilder().setBoard(key).setRadius(1).build(), Deadline.after(5000));
        assertThat(aroundFar.getEntriesList()).extracting(RankEntry::getEntityId, RankEntry::getRank, RankEntry::getBeyondRankLimit)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(107L, 0L, true),
                        org.assertj.core.groups.Tuple.tuple(108L, 0L, true));

        // 确认全过程零 ROW_NUMBER()、零全表 COUNT(*) FROM rank_score
        assertThat(executedSql).noneMatch(s -> s.toUpperCase().contains("ROW_NUMBER"));
        assertThat(executedSql).noneMatch(s -> s.equals("SELECT COUNT(*) FROM rank_score WHERE board_id=? AND generation=?"));
        assertThat(executedSql).anyMatch(s -> s.contains("ORDER BY order_key,achieved_ms,entity_id LIMIT ? OFFSET ?"));
        assertThat(executedSql).anyMatch(s -> s.contains("LIMIT ?) AS ahead"));
    }

    @Test
    void 榜奖遇17011收件角色已删立即终态放弃不重投() throws Exception {
        RankStore store = mock(RankStore.class);
        MailAdminService mail = mock(MailAdminService.class);
        byte[] att = MailAttachments.newBuilder()
                .addCurrencies(MailCurrency.newBuilder().setCurrencyType(0).setAmount(100)).build().toByteArray();
        var reward = new RankStore.Reward(701L, 888L, 50L, 1L, att, 999L, 1_000_000L);
        when(store.lease(any(), eq(701L), anyLong(), anyLong())).thenReturn(reward);
        var deletedResp = SendMailResponse.newBuilder();
        deletedResp.getErrorMessageBuilder().setId(MailErrorTip.mail_error.kMailRecipientDeleted_VALUE);
        when(mail.sendPersonalMail(any())).thenReturn(CompletableFuture.completedFuture(deletedResp.build()));

        RankRewardWorker worker = new RankRewardWorker(store, SECRET, mail);
        worker.process(701L);

        verify(store).abandon(any(), eq(reward));
        verify(store, never()).settle(any(), any(), anyLong());
    }

    /** 最小模拟只读快照连接，按名次索引语义响应 RankStore.page / around 的四类 SQL。 */
    private static <T> Connection fakeSnapshotConnection(List<T> rawBoard, Comparator<T> cmp, List<String> sqlLog) throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.isClosed()).thenReturn(false);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            sqlLog.add(sql);
            PreparedStatement ps = mock(PreparedStatement.class);
            Map<Integer, Object> params = new LinkedHashMap<>();
            doAnswer(a -> {
                params.put(a.getArgument(0), a.getArgument(1));
                return null;
            }).when(ps).setObject(anyInt(), any());
            when(ps.executeQuery()).thenAnswer(q -> executeFakeQuery(sql, params, rawBoard));
            return ps;
        });
        return conn;
    }

    private static ResultSet executeFakeQuery(String sql, Map<Integer, Object> params, List<?> board) throws Exception {
        var entityMethod = board.getFirst().getClass().getDeclaredMethod("entityId");
        var scoreMethod = board.getFirst().getClass().getDeclaredMethod("score");
        var keyMethod = board.getFirst().getClass().getDeclaredMethod("orderKey");
        var achievedMethod = board.getFirst().getClass().getDeclaredMethod("achievedMs");
        entityMethod.setAccessible(true);
        scoreMethod.setAccessible(true);
        keyMethod.setAccessible(true);
        achievedMethod.setAccessible(true);

        List<Object[]> rows = new ArrayList<>();
        if (sql.contains("FROM rank_board")) {
            rows.add(new Object[] {1L, 3, 1, 0L, "all", 1L, "match", 1L, 1L, false, 1, 0L, "h", 0L, (long) board.size()});
        } else if (sql.contains("ORDER BY order_key,achieved_ms,entity_id LIMIT ? OFFSET ?")) {
            int limit = ((Number) params.get(3)).intValue();
            int offset = ((Number) params.get(4)).intValue();
            for (int i = offset; i < Math.min(board.size(), offset + limit); i++) {
                Object r = board.get(i);
                rows.add(new Object[] {entityMethod.invoke(r), scoreMethod.invoke(r), achievedMethod.invoke(r)});
            }
        } else if (sql.startsWith("SELECT score,order_key,achieved_ms FROM rank_score")) {
            long pid = ((Number) params.get(3)).longValue();
            for (Object r : board) {
                if (((Number) entityMethod.invoke(r)).longValue() == pid) {
                    rows.add(new Object[] {scoreMethod.invoke(r), keyMethod.invoke(r), achievedMethod.invoke(r)});
                    break;
                }
            }
        } else if (sql.contains("AS ahead")) {
            long key = ((Number) params.get(3)).longValue();
            long ach = ((Number) params.get(5)).longValue();
            long pid = ((Number) params.get(8)).longValue();
            long cap = ((Number) params.get(9)).longValue();
            long count = 0;
            for (Object r : board) {
                if (isAhead(((Number) keyMethod.invoke(r)).longValue(), ((Number) achievedMethod.invoke(r)).longValue(),
                        ((Number) entityMethod.invoke(r)).longValue(), key, ach, pid)) {
                    count++;
                    if (count >= cap) break;
                }
            }
            rows.add(new Object[] {count});
        } else if (sql.contains("ORDER BY order_key DESC,achieved_ms DESC,entity_id DESC LIMIT ?")) {
            long key = ((Number) params.get(3)).longValue();
            long ach = ((Number) params.get(5)).longValue();
            long pid = ((Number) params.get(8)).longValue();
            int limit = ((Number) params.get(9)).intValue();
            List<Object[]> ahead = new ArrayList<>();
            for (Object r : board) {
                if (isAhead(((Number) keyMethod.invoke(r)).longValue(), ((Number) achievedMethod.invoke(r)).longValue(),
                        ((Number) entityMethod.invoke(r)).longValue(), key, ach, pid)) {
                    ahead.add(new Object[] {entityMethod.invoke(r), scoreMethod.invoke(r), achievedMethod.invoke(r)});
                }
            }
            for (int i = ahead.size() - 1; i >= 0 && rows.size() < limit; i--) rows.add(ahead.get(i));
        } else if (sql.contains("ORDER BY order_key,achieved_ms,entity_id LIMIT ?")) {
            long key = ((Number) params.get(3)).longValue();
            long ach = ((Number) params.get(5)).longValue();
            long pid = ((Number) params.get(8)).longValue();
            int limit = ((Number) params.get(9)).intValue();
            for (Object r : board) {
                if (isBehind(((Number) keyMethod.invoke(r)).longValue(), ((Number) achievedMethod.invoke(r)).longValue(),
                        ((Number) entityMethod.invoke(r)).longValue(), key, ach, pid)) {
                    rows.add(new Object[] {entityMethod.invoke(r), scoreMethod.invoke(r), achievedMethod.invoke(r)});
                    if (rows.size() >= limit) break;
                }
            }
        }
        return fakeResultSet(rows);
    }

    private static boolean isAhead(long k, long a, long e, long tk, long ta, long te) {
        return k < tk || (k == tk && a < ta) || (k == tk && a == ta && Long.compareUnsigned(e, te) < 0);
    }

    private static boolean isBehind(long k, long a, long e, long tk, long ta, long te) {
        return k > tk || (k == tk && a > ta) || (k == tk && a == ta && Long.compareUnsigned(e, te) > 0);
    }

    private static ResultSet fakeResultSet(List<Object[]> rows) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        int[] idx = {-1};
        when(rs.next()).thenAnswer(inv -> ++idx[0] < rows.size());
        when(rs.getObject(anyInt())).thenAnswer(inv -> rows.get(idx[0])[(int) inv.getArgument(0) - 1]);
        when(rs.getLong(anyInt())).thenAnswer(inv -> ((Number) rows.get(idx[0])[(int) inv.getArgument(0) - 1]).longValue());
        when(rs.getInt(anyInt())).thenAnswer(inv -> ((Number) rows.get(idx[0])[(int) inv.getArgument(0) - 1]).intValue());
        when(rs.getString(anyInt())).thenAnswer(inv -> (String) rows.get(idx[0])[(int) inv.getArgument(0) - 1]);
        when(rs.getBoolean(anyInt())).thenAnswer(inv -> (Boolean) rows.get(idx[0])[(int) inv.getArgument(0) - 1]);
        return rs;
    }

    @Test
    void 后台admin可按单条摘除任意生产者榜上作弊条目_整代隐藏与非开榜方仍拒收() throws Exception {
        AuthorityJdbc sql = mock(AuthorityJdbc.class);
        AuthorityJdbc.Session session = mock(AuthorityJdbc.Session.class);
        ResultSet boardRs = mock(ResultSet.class);
        when(boardRs.getObject(1)).thenReturn(801L);
        when(boardRs.getInt(2)).thenReturn(1);
        when(boardRs.getInt(3)).thenReturn(1);
        when(boardRs.getObject(4)).thenReturn(0L);
        when(boardRs.getString(5)).thenReturn("s1");
        when(boardRs.getObject(6)).thenReturn(1L);
        when(boardRs.getString(7)).thenReturn("match");
        when(boardRs.getObject(8)).thenReturn(1L);
        when(boardRs.getObject(9)).thenReturn(10L);
        when(boardRs.getBoolean(10)).thenReturn(false);
        when(boardRs.getInt(11)).thenReturn(1);
        when(boardRs.getObject(12)).thenReturn(0L);
        when(boardRs.getString(13)).thenReturn("open-hash");
        when(boardRs.getObject(14)).thenReturn(0L);
        when(boardRs.getObject(15)).thenReturn(5L);

        when(session.one(contains("FROM rank_board WHERE board_id=? FOR UPDATE"), any(), any()))
                .thenAnswer(inv -> ((AuthorityJdbc.Row<?>) inv.getArgument(1)).apply(boardRs));
        when(session.one(contains("FROM rank_mutation WHERE"), any(), any(), any(), any())).thenReturn(null);
        when(session.update(anyString(), any())).thenReturn(1);
        when(sql.tx(any(), any())).thenAnswer(inv -> ((AuthorityJdbc.Work<?>) inv.getArgument(1)).apply(session));

        RankStore store = new RankStore(sql, mock(AuthorityIds.class), id -> true, () -> 10_000L);
        RemoveRankEntryRequest removeOne = RemoveRankEntryRequest.newBuilder()
                .setBoardId(801L).setGeneration(1L).setEntityId(999L).setRequestId(1001L).build();
        // admin 摘除 match 榜单个作弊条目 -> 写墓碑并删分
        store.remove("admin", removeOne, false, Deadline.after(5_000));
        verify(session).update(contains("INSERT INTO rank_removed_entry"), any(), any(), any(), any());
        verify(session).update(contains("DELETE FROM rank_score"), any(), any(), any());
        assertThat(RankTables.registry().tables())
                .extracting(com.game.pbmysql.TableSchema::tableName)
                .contains("rank_removed_entry");

        // admin 隐藏 match 榜整代 -> 拒绝 CONFLICT（整代隐藏严格仅开榜方）
        RemoveRankEntryRequest hideAll = RemoveRankEntryRequest.newBuilder()
                .setBoardId(801L).setGeneration(1L).setRequestId(1002L).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.remove("admin", hideAll, true, Deadline.after(5_000)))
                .isInstanceOf(RankStore.Reject.class)
                .extracting(e -> ((RankStore.Reject) e).tip)
                .isEqualTo(RankStore.CONFLICT);

        // 非开榜方且非 admin -> 单条摘除也拒绝 CONFLICT
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.remove("guild", removeOne, false, Deadline.after(5_000)))
                .isInstanceOf(RankStore.Reject.class)
                .extracting(e -> ((RankStore.Reject) e).tip)
                .isEqualTo(RankStore.CONFLICT);
    }

    @Test
    void 榜奖永久拒收或剩余领取不足24h直接放弃_暂态失败按attempts指数退避() throws Exception {
        long now = System.currentTimeMillis();
        RankStore store = mock(RankStore.class);
        MailAdminService mail = mock(MailAdminService.class);
        RankRewardWorker worker = new RankRewardWorker(store, SECRET, mail);

        // 1) 距离到期不足 24h -> 不发信直接 abandon
        RankStore.Reward expiring = new RankStore.Reward(
                701L, 1001L, 1L, 1L, MailAttachments.getDefaultInstance().toByteArray(), 11L, now - 29L * 86400_000L - 3600_000L);
        when(store.lease(any(), eq(701L), anyLong(), anyLong())).thenReturn(expiring);
        worker.process(701L);
        verify(store).abandon(any(), eq(expiring));
        verify(mail, never()).sendPersonalMail(any());

        // 2) 附件字节损坏 -> 直接 abandon，不陷入 2s 死循环
        RankStore.Reward corrupt = new RankStore.Reward(702L, 1002L, 1L, 2L, new byte[] {(byte) 0xFF, (byte) 0xFF}, 12L, now);
        when(store.lease(any(), eq(702L), anyLong(), anyLong())).thenReturn(corrupt);
        worker.process(702L);
        verify(store).abandon(any(), eq(corrupt));

        // 3) 邮件服务回永久错误 kMailExpired -> 直接 abandon
        RankStore.Reward expiredMail = new RankStore.Reward(
                703L, 1003L, 1L, 3L, MailAttachments.getDefaultInstance().toByteArray(), 13L, now);
        when(store.lease(any(), eq(703L), anyLong(), anyLong())).thenReturn(expiredMail);
        when(mail.sendPersonalMail(any())).thenReturn(CompletableFuture.completedFuture(
                SendMailResponse.newBuilder()
                        .setErrorMessage(com.game.proto.TipInfoMessage.newBuilder()
                                .setId(MailErrorTip.mail_error.kMailExpired_VALUE))
                        .build()));
        worker.process(703L);
        verify(store).abandon(any(), eq(expiredMail));

        // 4) 暂态失败 settle 按 attempts=3 计算指数退避（2000 << 3 = 16000ms）
        AuthorityJdbc sql = mock(AuthorityJdbc.class);
        AuthorityJdbc.Session session = mock(AuthorityJdbc.Session.class);
        when(session.one(contains("SELECT status,lease_token,attempts FROM rank_mail_reward"), any(), any()))
                .thenReturn(new long[] {1L, 99L, 3L});
        when(sql.tx(any(), any())).thenAnswer(inv -> ((AuthorityJdbc.Work<?>) inv.getArgument(1)).apply(session));
        long fixedNow = 100_000L;
        RankStore realStore = new RankStore(sql, mock(AuthorityIds.class), id -> true, () -> fixedNow);
        realStore.settle(Deadline.after(1_000), new RankStore.Reward(704L, 1004L, 1L, 4L, new byte[0], 99L, fixedNow), 0L);
        verify(session).update(contains("UPDATE rank_mail_reward SET status=?"),
                eq(1), any(), eq(fixedNow + 16_000L), eq(fixedNow), any());
    }
}
