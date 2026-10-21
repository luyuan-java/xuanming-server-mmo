package com.game.inbox.store;

import com.game.inbox.rules.InboxShards;
import com.game.pbmysql.PbMysql;
import com.game.proto.player_inbox.player_inbox_audit;
import com.game.proto.player_inbox.player_inbox_item_row;
import com.game.proto.player_inbox.player_inbox_stream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

/**
 * 收件箱三张表的唯一登记：表结构直接取同步来的契约 {@code proto/player_inbox/player_inbox_table.proto}（mmorpg 的唯一事实源，设计 §4.1；
 * PARITY 第 ③ 条的审计列 10–15 随它而来），经 game-pbmysql 建表、只扩不缩地同步（选项按扩展字段号读，mmorpg 的 {@code OptionTableName} 等同样生效）。
 * 部署命名空间库（{@code <ns>}）里与玩家表同库。
 */
public final class InboxTables {

    /** shard 列由 rowShard(player_id) 决定的全部表。 */
    static final List<String> ROW_SHARD_TABLES = List.of(InboxSql.STREAM_TABLE, InboxSql.ITEM_TABLE, InboxSql.AUDIT_TABLE);

    private InboxTables() {
    }

    public static PbMysql registry() {
        PbMysql db = new PbMysql();
        db.register(player_inbox_stream.getDefaultInstance());
        db.register(player_inbox_item_row.getDefaultInstance());
        db.register(player_inbox_audit.getDefaultInstance());
        return db;
    }

    /** 建表 / 只扩不缩地同步结构，再整库核对 shard（{@link #checkRowShards}）。失败抛出：启动致命。 */
    public static PbMysql sync(DataSource dataSource) throws SQLException {
        PbMysql db = registry();
        try (Connection connection = dataSource.getConnection()) {
            int timeout = connection.getNetworkTimeout();
            try {
                connection.setNetworkTimeout(Runnable::run, 120_000);
                connection.setAutoCommit(true);
                db.syncAll(connection);
                com.game.common.player.PlayerRouteAuthority.verifySchema(connection);
                try (var required=connection.prepareStatement("SELECT admission_proto,source_instance,protocol_version,save_seq,status FROM player_final_save WHERE 1=0");
                     var ignored=required.executeQuery()) { }
                checkRowShards(connection);
                checkOwnerOriginals(connection);
            } finally {
                connection.setNetworkTimeout(Runnable::run, timeout);
            }
        }
        return db;
    }

    /**
     * 逐表核对 (shard, player_id)：只要有一行 shard ≠ rowShard(player_id) 就抛 {@link IllegalStateException}（拒启）。
     * 为什么要整库核对：shard 写错的行（例如按别的公式写出的开发库存量）对按玩家的读写<b>静默不可见</b>——锁内判重读不到它，同一 request_id
     * 重试会再入箱一次（IB-2 失效）；旧的 PENDING 行再也拉不到；按分片扫描的补发每轮都在读出断言上整片报错。只读、不加锁；三张表是 GM 域的小表。
     */
    static void checkRowShards(Connection connection) throws SQLException {
        for (String table : ROW_SHARD_TABLES) {
            long bad = 0;
            long firstPlayer = 0;
            int firstShard = 0;
            try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT `shard`, `player_id` FROM " + table);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int shard = InboxSql.u32(rows, "shard");
                    long playerId = InboxSql.u64(rows, "player_id");
                    if (shard == InboxShards.rowShard(playerId)) {
                        continue;
                    }
                    if (bad == 0) {
                        firstShard = shard;
                        firstPlayer = playerId;
                    }
                    bad++;
                }
            }
            if (bad > 0) {
                throw new IllegalStateException(table + " 有 " + bad + " 组 (shard, player_id) 的 shard 不等于 mix64(player_id) % 64（第一组 shard="
                        + firstShard + " player_id=" + Long.toUnsignedString(firstPlayer) + "，应为 " + InboxShards.rowShard(firstPlayer)
                        + "）：这些行对按玩家的读写全部不可见、会破坏幂等。一次性的开发 / 测试库清空收件箱三张表后重来；别处停下上报，不要删行（mmorpg 设计 §4.2，评审 L5）");
            }
        }
    }

    /** 旧开发行必须经审核迁移或终结；启动不从当前player回填尚未完成的历史命令。 */
    static void checkOwnerOriginals(Connection connection) throws SQLException {
        try (var statement=connection.prepareStatement("SELECT 1 FROM "+InboxSql.ITEM_TABLE
                +" WHERE state IS NULL OR state NOT IN(1,2,3,4,5) OR (state=1 AND (owner_revision IS NULL OR owner_revision=0 OR owner_account IS NULL OR OCTET_LENGTH(owner_account)=0)) LIMIT 1")) {
            statement.setQueryTimeout(3);
            try(var rows=statement.executeQuery()) {
                if(rows.next()) throw new SQLException("收件箱存在无原owner的待办或未知状态；拒绝启动，禁止自动补当前归属");
            }
        }
    }
}
