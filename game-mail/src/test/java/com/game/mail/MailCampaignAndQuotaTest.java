package com.game.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityIds;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.mail.ImportMailRecipientsRequest;
import com.game.proto.mail.ImportMailRecipientsResponse;
import com.game.proto.mail.SendMailRequest;
import com.game.proto.mail.SendMailResponse;
import com.game.table.MailErrorTip;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 帮会邮件按帮独立计数（缺口清单第 27 条后半）与活动名单一片一条多行 INSERT（第 27 条前半）。
 */
class MailCampaignAndQuotaTest {

    private static Deadline budget() {
        return Deadline.after(5_000);
    }

    @Test
    void 帮会邮件按帮独立上限且与全服系统邮件互不挤占() throws Exception {
        AuthorityJdbc sql = mock(AuthorityJdbc.class);
        AuthorityIds ids = mock(AuthorityIds.class);
        when(ids.next(any())).thenReturn(101L, 102L, 103L, 104L);
        when(sql.read(any(), any())).thenReturn(null);

        // 模拟按 SQL 参数区分 guild_id=10（已满 2 条）、guild_id=20（0 条）、系统邮件 guild_id=0（已满 3 条）
        List<Long> lockedGuards = new ArrayList<>();
        AuthorityJdbc.Session session = mock(AuthorityJdbc.Session.class);
        when(session.one(contains("FROM mail_box WHERE player_id=? FOR UPDATE"), any(), any())).thenAnswer(inv -> {
            Object raw = inv.getArgument(2);
            long guard = raw instanceof Number n ? n.longValue() : Long.parseUnsignedLong(raw.toString());
            lockedGuards.add(guard);
            return guard;
        });
        when(session.one(contains("FROM mail_message WHERE sender_kind=? AND request_id=?"), any(), any(), any()))
                .thenReturn(null);
        when(session.count(contains("guild_id=? AND kind IN (0,3)"), any(), any())).thenAnswer(inv -> {
            Object gid = inv.getArgument(1);
            long g = gid instanceof Number n ? n.longValue() : Long.parseUnsignedLong(gid.toString());
            return g == 10L ? 2L : 0L;
        });
        when(session.count(contains("guild_id=0 AND kind IN (0,2)"), any())).thenReturn(3L);
        when(session.update(anyString(), any())).thenReturn(1);
        when(sql.tx(any(), any())).thenAnswer(inv -> {
            AuthorityJdbc.Work<?> work = inv.getArgument(1);
            return work.apply(session);
        });

        MailStore store = new MailStore(sql, ids, id -> true, () -> 1_000_000L).withBroadcastLimits(3, 2);

        // 帮会 10 已满 2 条 -> 拒绝 FULL
        SendMailRequest guild10 = SendMailRequest.newBuilder()
                .setSenderKind(MailStore.sender("guild")).setRequestId(1L).setGuildId(10L)
                .setTitle("帮会公告").setBody("正文").build();
        assertThatThrownBy(() -> store.send("guild", 3, guild10, budget()))
                .isInstanceOf(MailStore.Reject.class)
                .extracting(e -> ((MailStore.Reject) e).tip)
                .isEqualTo(MailErrorTip.mail_error.kMailRecipientBoxFull_VALUE);

        // 帮会 20 独立配额 0/2 -> 成功，且锁行落在高位互斥空间 Long.MIN_VALUE | 20L
        SendMailRequest guild20 = SendMailRequest.newBuilder()
                .setSenderKind(MailStore.sender("guild")).setRequestId(2L).setGuildId(20L)
                .setTitle("帮会公告").setBody("正文").build();
        SendMailResponse okGuild = store.send("guild", 3, guild20, budget());
        assertThat(okGuild.getMailId()).isEqualTo(102L);
        assertThat(lockedGuards).contains(Long.MIN_VALUE | 10L, Long.MIN_VALUE | 20L);

        // 系统邮件独立走 guild_id=0 配额（3/3 已满）-> 拒绝 FULL，锁 0 号守卫行
        SendMailRequest sys = SendMailRequest.newBuilder()
                .setSenderKind(MailStore.sender("admin")).setRequestId(3L)
                .setTitle("全服维护").setBody("正文").build();
        assertThatThrownBy(() -> store.send("admin", 2, sys, budget()))
                .isInstanceOf(MailStore.Reject.class)
                .extracting(e -> ((MailStore.Reject) e).tip)
                .isEqualTo(MailErrorTip.mail_error.kMailRecipientBoxFull_VALUE);
        assertThat(lockedGuards).contains(0L);
    }

    @Test
    void 活动名单导入按片求和且一片一条多行INSERT_唯一键冲突转CONFLICT() throws Exception {
        AuthorityJdbc sql = mock(AuthorityJdbc.class);
        AuthorityIds ids = mock(AuthorityIds.class);
        AuthorityJdbc.Session session = mock(AuthorityJdbc.Session.class);
        when(sql.tx(any(), any())).thenAnswer(inv -> {
            AuthorityJdbc.Work<?> work = inv.getArgument(1);
            return work.apply(session);
        });
        stubCampaign(session, 5L, 2);

        // 正常导入 2 人：按 mail_campaign_shard SUM(recipient_count) 统计，且用单条多行 INSERT
        when(session.one(contains("FROM mail_campaign_shard"), any(), any(), any(), any(), any())).thenReturn(null);
        when(session.count(contains("COALESCE(SUM(recipient_count),0) FROM mail_campaign_shard"), any(), any(), any()))
                .thenReturn(1L);
        when(session.update(anyString(), any())).thenReturn(2);

        MailStore store = new MailStore(sql, ids, id -> true, () -> 1_000_000L);
        ImportMailRecipientsRequest req = ImportMailRecipientsRequest.newBuilder()
                .setSenderKind(MailStore.sender("admin")).setCampaignId(77L).setManifestGeneration(1L)
                .setShardIndex(1).addPlayerIds(30L).addPlayerIds(10L).build();
        ImportMailRecipientsResponse res = store.importRecipients("admin", req, budget());
        assertThat(res.getImportedCount()).isEqualTo(2);
        assertThat(res.getDuplicated()).isFalse();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(session).update(sqlCaptor.capture(), eq(1), eq(77L), eq(1L), eq(10L), eq(1),
                eq(1), eq(77L), eq(1L), eq(30L), eq(1));
        assertThat(sqlCaptor.getValue())
                .startsWith("INSERT INTO mail_campaign_recipient ")
                .contains("VALUES (?,?,?,?,?),(?,?,?,?,?)")
                .doesNotContain("IGNORE")
                .doesNotContain("ON DUPLICATE");

        // 跨片重复玩家撞唯一键 -> 转 Reject(CONFLICT) 且不写 mail_campaign_shard
        AuthorityJdbc conflictSql = mock(AuthorityJdbc.class);
        AuthorityJdbc.Session conflictSession = mock(AuthorityJdbc.Session.class);
        when(conflictSql.tx(any(), any())).thenAnswer(inv -> {
            AuthorityJdbc.Work<?> work = inv.getArgument(1);
            return work.apply(conflictSession);
        });
        stubCampaign(conflictSession, 5L, 2);
        when(conflictSession.one(contains("FROM mail_campaign_shard"), any(), any(), any(), any(), any())).thenReturn(null);
        when(conflictSession.count(contains("COALESCE(SUM(recipient_count),0) FROM mail_campaign_shard"), any(), any(), any()))
                .thenReturn(2L);
        when(conflictSession.update(contains("INSERT INTO mail_campaign_recipient"), any(Object[].class)))
                .thenThrow(new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062));

        MailStore conflictStore = new MailStore(conflictSql, ids, id -> true, () -> 1_000_000L);
        assertThatThrownBy(() -> conflictStore.importRecipients("admin", req, budget()))
                .isInstanceOf(MailStore.Reject.class)
                .extracting(e -> ((MailStore.Reject) e).tip)
                .isEqualTo(MailErrorTip.mail_error.kMailRequestConflict_VALUE);
        verify(conflictSession, never()).update(contains("INSERT INTO mail_campaign_shard"), any(Object[].class));
    }

    @Test
    void 同片重放同时核对摘要与人数_人数不符报CONFLICT() throws Exception {
        AuthorityJdbc sql = mock(AuthorityJdbc.class);
        AuthorityIds ids = mock(AuthorityIds.class);
        AuthorityJdbc.Session session = mock(AuthorityJdbc.Session.class);
        when(sql.tx(any(), any())).thenAnswer(inv -> {
            AuthorityJdbc.Work<?> work = inv.getArgument(1);
            return work.apply(session);
        });
        stubCampaign(session, 5L, 2);
        String expectedHash = MailStore.sha("10\n30\n");

        // 1) hash 相同且人数一致 -> 幂等重放 duplicated=true
        when(session.one(contains("SELECT shard_hash,recipient_count FROM mail_campaign_shard"), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    AuthorityJdbc.Row<?> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString(1)).thenReturn(expectedHash);
                    when(rs.getInt(2)).thenReturn(2);
                    return mapper.apply(rs);
                });

        MailStore store = new MailStore(sql, ids, id -> true, () -> 1_000_000L);
        ImportMailRecipientsRequest req = ImportMailRecipientsRequest.newBuilder()
                .setSenderKind(MailStore.sender("admin")).setCampaignId(77L).setManifestGeneration(1L)
                .setShardIndex(0).addPlayerIds(10L).addPlayerIds(30L).build();
        ImportMailRecipientsResponse dup = store.importRecipients("admin", req, budget());
        assertThat(dup.getDuplicated()).isTrue();
        assertThat(dup.getImportedCount()).isEqualTo(2);

        // 2) hash 相同但人数篡改/不符 -> CONFLICT
        when(session.one(contains("SELECT shard_hash,recipient_count FROM mail_campaign_shard"), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    AuthorityJdbc.Row<?> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString(1)).thenReturn(expectedHash);
                    when(rs.getInt(2)).thenReturn(3);
                    return mapper.apply(rs);
                });
        assertThatThrownBy(() -> store.importRecipients("admin", req, budget()))
                .isInstanceOf(MailStore.Reject.class)
                .extracting(e -> ((MailStore.Reject) e).tip)
                .isEqualTo(MailErrorTip.mail_error.kMailRequestConflict_VALUE);
    }

    @Test
    void 并发重发同senderKind与requestId撞唯一键时按哈希判幂等或CONFLICT() throws Exception {
        AuthorityJdbc sql = mock(AuthorityJdbc.class);
        AuthorityIds ids = mock(AuthorityIds.class);
        when(ids.next(any())).thenReturn(501L, 502L);

        SendMailRequest req = SendMailRequest.newBuilder()
                .setSenderKind(MailStore.sender("guild"))
                .setRequestId(8801L)
                .setGuildId(10L)
                .setTitle("帮会战报")
                .setBody("并发测试")
                .build();
        String expectedHash = com.game.discovery.serviceauth.ServiceCallSignatures.digest(req);

        AuthorityJdbc.Session readSession = mock(AuthorityJdbc.Session.class);
        ResultSet rsSame = mock(ResultSet.class);
        when(rsSame.getObject(1)).thenReturn(900L);
        when(rsSame.getObject(2)).thenReturn(0L);
        when(rsSame.getObject(3)).thenReturn(10L);
        when(rsSame.getInt(4)).thenReturn(3);
        when(rsSame.getInt(5)).thenReturn(MailStore.sender("guild"));
        when(rsSame.getObject(6)).thenReturn(8801L);
        when(rsSame.getString(7)).thenReturn(expectedHash);
        when(rsSame.getString(8)).thenReturn("帮会战报");
        when(rsSame.getString(9)).thenReturn("并发测试");
        when(rsSame.getBytes(10)).thenReturn(new byte[0]);
        when(rsSame.getObject(11)).thenReturn(1_000_000L);
        when(rsSame.getObject(12)).thenReturn(2_000_000L);
        when(rsSame.getObject(13)).thenReturn(0L);
        when(rsSame.getObject(14)).thenReturn(0L);

        // 首次 pre-check 未查到，进入事务 INSERT 撞 1062，补偿查询查到同 hash 行 -> 返回 duplicated=true
        java.util.concurrent.atomic.AtomicInteger readCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(sql.read(any(), any())).thenAnswer(inv -> {
            if (readCalls.getAndIncrement() == 0) {
                return null;
            }
            AuthorityJdbc.Work<?> work = inv.getArgument(1);
            return work.apply(readSession);
        });
        when(readSession.one(contains("FROM mail_message WHERE sender_kind=? AND request_id=?"), any(), any(), any()))
                .thenAnswer(inv -> {
                    AuthorityJdbc.Row<?> mapper = inv.getArgument(1);
                    return mapper.apply(rsSame);
                });
        when(sql.tx(any(), any())).thenAnswer(inv -> {
            AuthorityJdbc.Session txSession = mock(AuthorityJdbc.Session.class);
            when(txSession.one(contains("FROM mail_box WHERE player_id=? FOR UPDATE"), any(), any())).thenReturn(1L);
            when(txSession.one(contains("FROM mail_message WHERE sender_kind=? AND request_id=?"), any(), any(), any())).thenReturn(null);
            when(txSession.count(anyString(), any(), any())).thenReturn(0L);
            when(txSession.update(contains("INSERT INTO mail_message"), any(Object[].class)))
                    .thenThrow(new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062));
            AuthorityJdbc.Work<?> work = inv.getArgument(1);
            return work.apply(txSession);
        });

        MailStore store = new MailStore(sql, ids, id -> true, () -> 1_000_000L);
        SendMailResponse dup = store.send("guild", 3, req, budget());
        assertThat(dup.getMailId()).isEqualTo(900L);
        assertThat(dup.getDuplicated()).isTrue();

        // 再次并发但换了不同内容（不同 guild_id -> 不同 hash）撞 1062 -> 转 Reject(CONFLICT) 而非 503
        readCalls.set(0);
        SendMailRequest conflictReq = req.toBuilder().setGuildId(20L).build();
        assertThatThrownBy(() -> store.send("guild", 3, conflictReq, budget()))
                .isInstanceOf(MailStore.Reject.class)
                .extracting(e -> ((MailStore.Reject) e).tip)
                .isEqualTo(MailErrorTip.mail_error.kMailRequestConflict_VALUE);
    }

    @Test
    void 邮件过期清行后重发凭mail_request_receipt判重不重复发物且摘要不符回CONFLICT() throws Exception {
        assertThat(MailTables.registry().tables())
                .extracting(com.game.pbmysql.TableSchema::tableName)
                .contains("mail_request_receipt");

        AuthorityJdbc sql = mock(AuthorityJdbc.class);
        AuthorityIds ids = mock(AuthorityIds.class);
        SendMailRequest req = SendMailRequest.newBuilder()
                .setSenderKind(MailStore.sender("match"))
                .setRequestId(77001L)
                .setRecipientPlayerId(10001L)
                .setTitle("论剑赛季奖励")
                .setBody("请查收附件")
                .build();
        String expectedHash = com.game.discovery.serviceauth.ServiceCallSignatures.digest(req);

        AuthorityJdbc.Session readSession = mock(AuthorityJdbc.Session.class);
        ResultSet receiptRs = mock(ResultSet.class);
        when(receiptRs.getObject(1)).thenReturn(99001L);
        when(receiptRs.getString(2)).thenReturn(expectedHash);
        when(receiptRs.getInt(3)).thenReturn(1);
        when(readSession.one(contains("FROM mail_request_receipt WHERE sender_kind=? AND request_id=?"), any(), any(), any()))
                .thenAnswer(inv -> ((AuthorityJdbc.Row<?>) inv.getArgument(1)).apply(receiptRs));
        // mail_message 已被过期清理删除（回 null）
        when(readSession.one(contains("FROM mail_message WHERE sender_kind=? AND request_id=?"), any(), any(), any()))
                .thenReturn(null);
        when(sql.read(any(), any())).thenAnswer(inv -> ((AuthorityJdbc.Work<?>) inv.getArgument(1)).apply(readSession));

        MailStore store = new MailStore(sql, ids, id -> true, () -> 2_000_000L);
        SendMailResponse replayed = store.send("match", 1, req, budget());
        assertThat(replayed.getMailId()).isEqualTo(99001L);
        assertThat(replayed.getDuplicated()).isTrue();
        verify(sql, never()).tx(any(), any());

        // 同键不同请求内容（篡改正文/附件）必须回 CONFLICT，不放行也不重插
        SendMailRequest tampered = req.toBuilder().setBody("篡改内容").build();
        assertThatThrownBy(() -> store.send("match", 1, tampered, budget()))
                .isInstanceOf(MailStore.Reject.class)
                .extracting(e -> ((MailStore.Reject) e).tip)
                .isEqualTo(MailErrorTip.mail_error.kMailRequestConflict_VALUE);
        verify(sql, never()).tx(any(), any());
    }

    private static void stubCampaign(AuthorityJdbc.Session session, long expectedCount, int shardCount) throws Exception {
        when(session.one(contains("FROM mail_campaign WHERE"), any(), any(), any(), any())).thenAnswer(inv -> {
            AuthorityJdbc.Row<?> mapper = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1)).thenReturn(9001L);
            when(rs.getString(2)).thenReturn("admin");
            when(rs.getObject(3)).thenReturn(expectedCount);
            when(rs.getInt(4)).thenReturn(shardCount);
            when(rs.getString(5)).thenReturn("0".repeat(64));
            when(rs.getInt(6)).thenReturn(1);
            when(rs.getObject(7)).thenReturn(9_999_999L);
            when(rs.getInt(8)).thenReturn(2);
            return mapper.apply(rs);
        });
    }
}
