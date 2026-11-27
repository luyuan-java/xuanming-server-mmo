package com.game.common.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 覆盖主仓缺口清单第 21 条（事务外纯读只过读闸门、冻结仍挡读）、第 20 条（事务内合并读与 LOCK_TOUCH 跳过回读）
 * 以及第 22 条残余（一代结束删 slot 行后写者闸门确定性拒绝）。
 */
class WriterGateReadAndMergeTest {

    private static final String SCHEMA = "auth_gate_test";

    private WriterAuthority createAuthority(String lockPrimitive, byte[] writerKey, byte[] instanceId,
                                            String generation, byte[] regDigest, byte[] principal) throws Exception {
        byte[] grantId = new byte[16];
        grantId[0] = 7;
        byte[] phys = new byte[32];
        phys[0] = 3;
        byte[] qual = new byte[32];
        qual[0] = 9;
        String json = "{"
                + "\"format\":\"SQL_WRITER_BOOT_V1\","
                + "\"writerKey\":\"" + Base64.getEncoder().encodeToString(writerKey) + "\","
                + "\"instanceId\":\"" + Base64.getEncoder().encodeToString(instanceId) + "\","
                + "\"generation\":\"" + generation + "\","
                + "\"authoritySchema\":\"" + SCHEMA + "\","
                + "\"protectedSchemas\":[\"" + SCHEMA + "\"],"
                + "\"sqlPrincipal\":\"" + Base64.getEncoder().encodeToString(principal) + "\","
                + "\"registrationDigest\":\"" + Base64.getEncoder().encodeToString(regDigest) + "\","
                + "\"deploymentId\":\"pod-test-0\","
                + "\"grantId\":\"" + Base64.getEncoder().encodeToString(grantId) + "\","
                + "\"grantedMs\":\"1700000000000\","
                + "\"permission\":\"NORMAL\","
                + "\"physicalAuthoritySha256\":\"" + Base64.getEncoder().encodeToString(phys) + "\","
                + "\"lockPrimitive\":\"" + lockPrimitive + "\","
                + "\"lockQualificationSha256\":\"" + Base64.getEncoder().encodeToString(qual) + "\""
                + "}";
        var ctor = WriterAuthority.class.getDeclaredConstructor(com.fasterxml.jackson.databind.JsonNode.class);
        ctor.setAccessible(true);
        return ctor.newInstance(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
    }

    private static ResultSet metaResult(String gen, long epoch, int writable, long catalog) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn(gen);
        when(rs.getObject(2)).thenReturn(epoch);
        when(rs.getInt(3)).thenReturn(writable);
        when(rs.getInt(4)).thenReturn(64);
        when(rs.getObject(5)).thenReturn(catalog);
        when(rs.getBytes(6)).thenReturn(new byte[0]);
        return rs;
    }

    private static ResultSet mergedMetaRegistryResult(String gen, long epoch, int writable, long catalog,
                                                      byte[] instance, byte[] principal, byte[] regDigest, int state) throws Exception {
        ResultSet rs = metaResult(gen, epoch, writable, catalog);
        when(rs.getString(7)).thenReturn(gen);
        when(rs.getBytes(8)).thenReturn(instance);
        when(rs.getBytes(9)).thenReturn(principal);
        when(rs.getBytes(10)).thenReturn(regDigest);
        when(rs.getInt(11)).thenReturn(state);
        return rs;
    }

    @Test
    void pureAutoReadUsesReadGateWithoutTransactionAndStillBlocksWhenFrozen() throws Exception {
        String gen = UUID.randomUUID().toString();
        byte[] wk = new byte[16]; wk[0] = 1;
        byte[] iid = new byte[16]; iid[0] = 2;
        byte[] dig = new byte[32]; dig[0] = 3;
        byte[] principal = "sa".getBytes(StandardCharsets.UTF_8);
        WriterAuthority auth = createAuthority("LOCK_TOUCH", wk, iid, gen, dig, principal);

        List<String> sqls = new ArrayList<>();
        Connection raw = mock(Connection.class);
        when(raw.getAutoCommit()).thenReturn(true);
        PreparedStatement psMetaOpen = mock(PreparedStatement.class);
        ResultSet openRs = metaResult(gen, 5L, 1, 10L);
        when(psMetaOpen.executeQuery()).thenReturn(openRs);
        when(raw.prepareStatement(anyString())).thenAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            return psMetaOpen;
        });

        auth.verifyReadGate(raw);
        assertEquals(1, sqls.size());
        assertTrue(sqls.getFirst().contains("route_meta"));
        verify(raw, never()).setAutoCommit(false);

        // 冻结(writable=0)必须挡回纯读
        PreparedStatement psMetaFrozen = mock(PreparedStatement.class);
        ResultSet frozenRs = metaResult(gen, 5L, 0, 10L);
        when(psMetaFrozen.executeQuery()).thenReturn(frozenRs);
        when(raw.prepareStatement(anyString())).thenReturn(psMetaFrozen);
        assertThrows(SQLException.class, () -> auth.verifyReadGate(raw));
    }

    @Test
    void writeGateSkipsSlotRereadOnLockTouchMergesMetaRegistryAndRejectsDeletedSlots() throws Exception {
        String gen = UUID.randomUUID().toString();
        byte[] wk = new byte[16]; wk[0] = 11;
        byte[] iid = new byte[16]; iid[0] = 12;
        byte[] dig = new byte[32]; dig[0] = 13;
        byte[] principal = "sa".getBytes(StandardCharsets.UTF_8);
        WriterAuthority lockTouchAuth = createAuthority("LOCK_TOUCH", wk, iid, gen, dig, principal);
        WriterAuthority selectOnlyAuth = createAuthority("SELECT_ONLY", wk, iid, gen, dig, principal);

        // LOCK_TOUCH: pre-BEGIN route_meta + UPDATE writer_slot (无回读 SELECT) + 合并 LEFT JOIN route_meta/writer_registry = 共 3 条 SQL
        List<String> sqls = new ArrayList<>();
        Connection raw = mock(Connection.class);
        when(raw.getAutoCommit()).thenReturn(true);
        PreparedStatement psPre = mock(PreparedStatement.class);
        ResultSet preRs = metaResult(gen, 5L, 1, 10L);
        when(psPre.executeQuery()).thenReturn(preRs);
        PreparedStatement psUpdateSlot = mock(PreparedStatement.class);
        when(psUpdateSlot.executeUpdate()).thenReturn(1);
        PreparedStatement psMerged = mock(PreparedStatement.class);
        ResultSet mergedRs = mergedMetaRegistryResult(gen, 5L, 1, 10L, iid, principal, dig, 1);
        when(psMerged.executeQuery()).thenReturn(mergedRs);
        when(raw.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            sqls.add(sql);
            if (sql.startsWith("UPDATE ")) return psUpdateSlot;
            if (sql.contains("LEFT JOIN ")) return psMerged;
            return psPre;
        });

        lockTouchAuth.begin(raw);
        assertEquals(3, sqls.size(), "LOCK_TOUCH 应仅 3 条 SQL: " + sqls);
        assertTrue(sqls.get(1).startsWith("UPDATE ") && sqls.get(1).contains("writer_slot"));
        assertTrue(sqls.get(2).contains("LEFT JOIN ") && sqls.get(2).contains("writer_registry"));

        // slot 已被删除时（UPDATE 影响 0 行 或 SELECT FOR UPDATE 空集），两路径均确定性拒绝 (40001)
        PreparedStatement psPre2 = mock(PreparedStatement.class);
        ResultSet pre2Rs = metaResult(gen, 5L, 1, 10L);
        when(psPre2.executeQuery()).thenReturn(pre2Rs);
        PreparedStatement psUpdateZero = mock(PreparedStatement.class);
        when(psUpdateZero.executeUpdate()).thenReturn(0);
        when(raw.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.startsWith("UPDATE ") ? psUpdateZero : psPre2;
        });
        assertEquals("40001", assertThrows(SQLException.class, () -> lockTouchAuth.begin(raw)).getSQLState());

        PreparedStatement psPre3 = mock(PreparedStatement.class);
        ResultSet pre3Rs = metaResult(gen, 5L, 1, 10L);
        when(psPre3.executeQuery()).thenReturn(pre3Rs);
        PreparedStatement psEmptySlot = mock(PreparedStatement.class);
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        when(psEmptySlot.executeQuery()).thenReturn(emptyRs);
        when(raw.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.contains("writer_slot") ? psEmptySlot : psPre3;
        });
        assertEquals("40001", assertThrows(SQLException.class, () -> selectOnlyAuth.begin(raw)).getSQLState());
    }

    @Test
    void lockingSelectOnAutoCommitStillEntersFullWriteGate() throws Exception {
        Connection raw = mock(Connection.class);
        when(raw.getAutoCommit()).thenReturn(true);
        WriterAuthority auth = mock(WriterAuthority.class);
        Connection managed = AuthorityConnection.wrap(raw, auth);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(raw.createStatement()).thenReturn(st);
        when(st.executeQuery("SELECT v FROM business FOR UPDATE")).thenReturn(rs);

        managed.createStatement().executeQuery("SELECT v FROM business FOR UPDATE").close();
        verify(auth, times(1)).begin(raw);
        verify(auth, never()).verifyReadGate(raw);
        verify(raw, times(1)).commit();
    }
}
