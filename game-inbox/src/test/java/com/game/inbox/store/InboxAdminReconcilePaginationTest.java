package com.game.inbox.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.inbox.InboxShards;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.registry.InboxRegistry;
import com.game.player.store.state.InboxDomainCursorState;
import com.game.player.store.state.InboxLedgerState;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxFinalizer;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.PlayerInboxReconcileDiffKind;
import com.game.proto.player_inbox.ReconcilePlayerInboxRequest;
import com.game.proto.player_inbox.ReconcilePlayerInboxResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** 单人对账按 (domain, seq) 键集翻页读完且只读判定列（不读 payload），预算耗尽抛错不回半份。 */
class InboxAdminReconcilePaginationTest {

    private static final long PLAYER = 42L;
    private static final int SHARD = InboxShards.rowShard(PLAYER);

    private static JdbcDataSource createDb() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:inbox_reconcile_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE player_inbox_stream("
                    + "shard INT,player_id BIGINT,domain INT,next_seq BIGINT,fence_epoch BIGINT,open_count INT,"
                    + "notify_due_ms BIGINT,created_ms BIGINT,updated_ms BIGINT,PRIMARY KEY(shard,player_id,domain))");
            st.execute("CREATE TABLE player_inbox_item("
                    + "shard INT,player_id BIGINT,domain INT,seq BIGINT,msg_type INT,producer_kind INT,request_id BIGINT,"
                    + "content_sha256 VARBINARY(32),payload BLOB,state INT,directive INT,void_reason INT,delivery_count INT,"
                    + "last_delivered_epoch BIGINT,last_delivered_ms BIGINT,outcome_reason INT,finalized_by INT,"
                    + "decided_epoch BIGINT,issued_at_ms BIGINT,created_ms BIGINT,updated_ms BIGINT,"
                    + "operator_name VARCHAR(64),note VARCHAR(256),owner_account VARCHAR(64),owner_revision BIGINT,"
                    + "PRIMARY KEY(shard,player_id,domain,seq))");
        }
        return ds;
    }

    private static void insertStream(JdbcDataSource ds, int domain, long nextSeq) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO player_inbox_stream VALUES(?,?,?,?,0,0,0,1000,1000)")) {
            ps.setInt(1, SHARD);
            ps.setLong(2, PLAYER);
            ps.setInt(3, domain);
            ps.setLong(4, nextSeq);
            ps.executeUpdate();
        }
    }

    private static void insertItem(JdbcDataSource ds, int domain, long seq, int state, int finalizedBy) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO player_inbox_item VALUES(?,?,?,?,1,1,?,?,?, ?,0,0,1,1,1000,0,?,1,1000,1000,1000,'','','acc',1)")) {
            ps.setInt(1, SHARD);
            ps.setLong(2, PLAYER);
            ps.setInt(3, domain);
            ps.setLong(4, seq);
            ps.setLong(5, domain * 1000L + seq);
            ps.setBytes(6, new byte[32]);
            ps.setBytes(7, new byte[128]);
            ps.setInt(8, state);
            ps.setInt(9, finalizedBy);
            ps.executeUpdate();
        }
    }

    @Test
    void 跨多域多页翻页读完全部行且不读载荷列_发现尾页差异() throws Exception {
        assertThat(InboxSql.RECONCILE_ITEM_COLUMNS).doesNotContain("payload");
        JdbcDataSource ds = createDb();
        InboxRegistry registry = InboxRegistry.load();
        assertThat(registry.domains()).hasSizeGreaterThanOrEqualTo(2);
        PlayerInboxDomain d1 = registry.domains().get(0);
        PlayerInboxDomain d2 = registry.domains().get(1);

        insertStream(ds, d1.getNumber(), 6);
        insertStream(ds, d2.getNumber(), 4);
        // d1: seq 1..4 正常 ADMIN 作废；seq 5 为 PENDING 但账本水位已过（差异落在第 3 页）
        for (long seq = 1; seq <= 4; seq++) {
            insertItem(ds, d1.getNumber(), seq,
                    PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED_VALUE,
                    PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_ADMIN_VALUE);
        }
        insertItem(ds, d1.getNumber(), 5,
                PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING_VALUE, 0);

        // d2: seq 1..2 正常 ADMIN 作废；seq 3 由消费方 APPLIED 但账本水位为 0（差异落在最后页）
        insertItem(ds, d2.getNumber(), 1,
                PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED_VALUE,
                PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_ADMIN_VALUE);
        insertItem(ds, d2.getNumber(), 2,
                PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED_VALUE,
                PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_ADMIN_VALUE);
        insertItem(ds, d2.getNumber(), 3,
                PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED_VALUE,
                PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_CONSUMER_VALUE);

        InboxLedgerState ledger = InboxLedgerState.newBuilder()
                .addCursors(InboxDomainCursorState.newBuilder().setDomain(d1.getNumber()).setDecidedThroughSeq(5))
                .build();
        InboxAdminStore admin = new InboxAdminStore(
                new AuthorityJdbc(ds), registry, (b, pid) -> new LedgerReader.Read(true, ledger),
                new InboxMetrics(new SimpleMeterRegistry()), () -> 2000L);

        // pageSize=2，跨 4 页扫描全部 8 行
        ReconcilePlayerInboxResponse allResp = admin.reconcilePlayer(
                Deadline.after(5000),
                ReconcilePlayerInboxRequest.newBuilder().setPlayerId(PLAYER).build(),
                2);
        assertThat(allResp.getLedgerFound()).isTrue();
        assertThat(allResp.getDiffsList()).hasSize(2);
        assertThat(allResp.getDiffs(0).getDomain()).isEqualTo(d1);
        assertThat(allResp.getDiffs(0).getSeq()).isEqualTo(5L);
        assertThat(allResp.getDiffs(0).getKind()).isEqualTo(PlayerInboxReconcileDiffKind.PLAYER_INBOX_RECONCILE_PENDING_BUT_DECIDED);
        assertThat(allResp.getDiffs(1).getDomain()).isEqualTo(d2);
        assertThat(allResp.getDiffs(1).getSeq()).isEqualTo(3L);
        assertThat(allResp.getDiffs(1).getKind()).isEqualTo(PlayerInboxReconcileDiffKind.PLAYER_INBOX_RECONCILE_FINAL_NOT_IN_LEDGER);

        // 单域翻页同样读完全部页
        ReconcilePlayerInboxResponse singleResp = admin.reconcilePlayer(
                Deadline.after(5000),
                ReconcilePlayerInboxRequest.newBuilder().setPlayerId(PLAYER).setDomain(d1).build(),
                2);
        assertThat(singleResp.getDiffsList()).hasSize(1);
        assertThat(singleResp.getDiffs(0).getSeq()).isEqualTo(5L);
    }

    @Test
    void 对账预算耗尽抛错_绝不返回半份结论() throws Exception {
        JdbcDataSource ds = createDb();
        InboxRegistry registry = InboxRegistry.load();
        InboxLedgerState ledger = InboxLedgerState.getDefaultInstance();
        InboxAdminStore admin = new InboxAdminStore(
                new AuthorityJdbc(ds), registry, (b, pid) -> new LedgerReader.Read(true, ledger),
                new InboxMetrics(new SimpleMeterRegistry()), () -> 2000L);

        assertThatThrownBy(() -> admin.reconcilePlayer(
                Deadline.after(0),
                ReconcilePlayerInboxRequest.newBuilder().setPlayerId(PLAYER).build(),
                2))
                .isInstanceOf(SQLException.class);
    }
}
