package com.game.inbox.store;

import com.game.discovery.sql.AuthorityJdbc;
import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerRouteAuthority;
import com.game.inbox.rules.InboxShards;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.player_inbox_item_row;
import com.game.proto.player_inbox.player_inbox_stream;
import com.google.protobuf.ByteString;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 收件箱三张表的 SQL 片段与行映射（表结构的唯一事实源是同步来的契约 {@code proto/player_inbox/player_inbox_table.proto}，
 * 由 {@link InboxTables} 经 game-pbmysql 建表；业务 SQL 手写，行直接读进契约里的表 message，不另写并行的 Java 行类）。
 * 列名一律加反引号（operator / action / state 等与 SQL 关键字同名）。uint64 列经 {@link AuthorityJdbc#u64} 绑定 / 读取，uint32 列按无符号绑定。
 */
final class InboxSql {

    // 全部写路径先持meta/完整zone围栏/P，再碰流或条目；预读玩家存在不能替代提交复核。
    record Owner(String account, long revision, long epoch, boolean released) {}

    static Owner lockPlayer(AuthorityJdbc.Session session, Deadline budget, long player) throws SQLException {
        int timeout = (int)Math.max(1, Math.min(3, (budget.remainingMillis() + 999) / 1000));
        PlayerRouteAuthority.lock(session.connection(), java.util.List.of(player), timeout);
        Owner owner = session.one("SELECT account,owner_revision,owner_epoch,owner_released,trade_listing_id FROM player WHERE player_id=? FOR UPDATE", row -> {
            if (!"0".equals(row.getString(5))) throw new SQLException("收件箱角色正在交易封存，拒绝写入");
            String account = row.getString(1);
            long revision, epoch;
            try { revision=Long.parseLong(row.getString(2));epoch=Long.parseLong(row.getString(3)); }
            catch (NumberFormatException invalid) { throw new SQLException("收件箱角色归属数字格式无效",invalid); }
            String released = row.getString(4);
            if (account == null || account.isEmpty() || revision <= 0 || epoch < 0 || (!"0".equals(released) && !"1".equals(released)))
                throw new SQLException("收件箱角色归属事实缺失或越界");
            return new Owner(account, revision, epoch, "1".equals(released));
        }, u64(player));
        if (owner == null) throw new SQLException("收件箱角色不存在，拒绝写入");
        return owner;
    }

    /** 未终局条目的原归属只能核验，不能从当前目录修补或转授给新主人。 */
    static void requireOriginalOwner(player_inbox_item_row row, Owner owner) throws SQLException {
        if (row != null && !com.game.inbox.rules.InboxRules.terminal(row.getState())
                && (row.getState() != PENDING || row.getOwnerRevision() <= 0
                || row.getOwnerRevision() != owner.revision() || !row.getOwnerAccount().equals(owner.account())))
            throw new SQLException("收件箱未终局原归属失效或状态未知，拒绝继续");
    }

    static final String STREAM_TABLE = "player_inbox_stream";
    static final String ITEM_TABLE = "player_inbox_item";
    static final String AUDIT_TABLE = "player_inbox_audit";

    static final String STREAM_COLUMNS = "`shard`,`player_id`,`domain`,`next_seq`,`fence_epoch`,`open_count`,`notify_due_ms`,`created_ms`,"
            + "`updated_ms`";
    static final String ITEM_COLUMNS = "`shard`,`player_id`,`domain`,`seq`,`msg_type`,`producer_kind`,`request_id`,`content_sha256`,`payload`,"
            + "`state`,`directive`,`void_reason`,`delivery_count`,`last_delivered_epoch`,`last_delivered_ms`,`outcome_reason`,"
            + "`finalized_by`,`decided_epoch`,`issued_at_ms`,`created_ms`,`updated_ms`,`operator_name`,`note`,`owner_account`,`owner_revision`";
    static final String AUDIT_COLUMNS = "`shard`,`player_id`,`domain`,`seq`,`action`,`operator`,`peer_addr`,`reason`,`created_ms`,"
            + "`msg_type`,`request_id`,`issued_at_ms`,`content_sha256`,`payload`,`void_reason`";

    static final String STREAM_PK = "`shard` = ? AND `player_id` = ? AND `domain` = ?";
    static final String ITEM_PK = "`shard` = ? AND `player_id` = ? AND `domain` = ? AND `seq` = ?";

    /** 终态集合的 SQL 字面量（APPLIED / REJECTED / VOIDED / SETTLED_UNKNOWN），由枚举值拼出而不是手写数字。 */
    static final String TERMINAL_STATES = "(" + PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED_VALUE + ","
            + PlayerInboxItemState.PLAYER_INBOX_ITEM_REJECTED_VALUE + "," + PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED_VALUE + ","
            + PlayerInboxItemState.PLAYER_INBOX_ITEM_SETTLED_UNKNOWN_VALUE + ")";

    static final int PENDING = PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING_VALUE;

    private InboxSql() {
    }

    /** uint64 → JDBC 参数。 */
    static Object u64(long bits) {
        return AuthorityJdbc.u64(bits);
    }

    /** uint32 → JDBC 参数（按无符号）。 */
    static long u32(int bits) {
        return Integer.toUnsignedLong(bits);
    }

    static long u64(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new SQLException("uint64 列 " + column + " 不可解码: " + value);
    }

    static int u32(ResultSet row, String column) throws SQLException {
        return (int) row.getLong(column);
    }

    static ByteString bytes(ResultSet row, String column) throws SQLException {
        byte[] value = row.getBytes(column);
        return value == null ? ByteString.EMPTY : ByteString.copyFrom(value);
    }

    static String text(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? "" : value;
    }

    static player_inbox_stream stream(ResultSet row) throws SQLException {
        return player_inbox_stream.newBuilder()
                .setShard(u32(row, "shard"))
                .setPlayerId(u64(row, "player_id"))
                .setDomain(u32(row, "domain"))
                .setNextSeq(u64(row, "next_seq"))
                .setFenceEpoch(u64(row, "fence_epoch"))
                .setOpenCount(u32(row, "open_count"))
                .setNotifyDueMs(u64(row, "notify_due_ms"))
                .setCreatedMs(u64(row, "created_ms"))
                .setUpdatedMs(u64(row, "updated_ms"))
                .build();
    }

    static player_inbox_item_row item(ResultSet row) throws SQLException {
        return player_inbox_item_row.newBuilder()
                .setShard(u32(row, "shard"))
                .setPlayerId(u64(row, "player_id"))
                .setDomain(u32(row, "domain"))
                .setSeq(u64(row, "seq"))
                .setMsgType(u32(row, "msg_type"))
                .setProducerKind(u32(row, "producer_kind"))
                .setRequestId(u64(row, "request_id"))
                .setContentSha256(bytes(row, "content_sha256"))
                .setPayload(bytes(row, "payload"))
                .setState(u32(row, "state"))
                .setDirective(u32(row, "directive"))
                .setVoidReason(u32(row, "void_reason"))
                .setDeliveryCount(u32(row, "delivery_count"))
                .setLastDeliveredEpoch(u64(row, "last_delivered_epoch"))
                .setLastDeliveredMs(u64(row, "last_delivered_ms"))
                .setOutcomeReason(u32(row, "outcome_reason"))
                .setFinalizedBy(u32(row, "finalized_by"))
                .setDecidedEpoch(u64(row, "decided_epoch"))
                .setIssuedAtMs(u64(row, "issued_at_ms"))
                .setCreatedMs(u64(row, "created_ms"))
                .setUpdatedMs(u64(row, "updated_ms"))
                .setOperatorName(text(row, "operator_name"))
                .setNote(text(row, "note"))
                .setOwnerAccount(text(row, "owner_account"))
                .setOwnerRevision(u64(row, "owner_revision"))
                .build();
    }

    /**
     * 读出时的断言（TiDB 不执行 CHECK，shard 一致性靠唯一计算函数 + 读出断言 + 整库核对）。只拦得住<b>按分片扫描</b>读出来的行：
     * 按玩家的读写一律带 WHERE shard = rowShard(player_id)，shard 写错的行根本查不出来（静默不可见，见 {@link InboxTables#checkRowShards}）。
     */
    static void checkRowShard(int shard, long playerId) throws SQLException {
        int want = InboxShards.rowShard(playerId);
        if (shard != want) {
            throw new SQLException("收件箱行的 shard 与 player_id 不一致 shard=" + shard + " player_id=" + Long.toUnsignedString(playerId)
                    + "（应为 " + want + "）");
        }
    }

    /** n 个占位符。 */
    static String placeholders(int n) {
        return n <= 0 ? "" : "?,".repeat(n - 1) + "?";
    }

    /** 主键 / 唯一键冲突（MySQL 1062，H2 SQLState 23505）：并发同键入箱。 */
    static boolean duplicateKey(SQLException e) {
        return e.getErrorCode() == 1062 || "23505".equals(e.getSQLState());
    }
}
