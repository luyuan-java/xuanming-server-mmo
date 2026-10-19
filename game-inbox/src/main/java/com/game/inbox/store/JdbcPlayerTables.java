package com.game.inbox.store;

import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.player.store.state.InboxLedgerState;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 玩家表与玩家状态表的只读 JDBC（{@link PlayerDirectory} 与 {@link LedgerReader} 的生产实现）。两张表由 game-player-store 的建表脚本建
 * （login / scene 启动时初始化），本服务只读、不建表；与收件箱三张表同一个部署命名空间库。读走 {@link AuthorityJdbc}（预算内的网络与语句超时；
 * 生产数据源带强读会话策略），不重试——下一次读本身就是重试。线程安全（无可变状态）。
 */
public final class JdbcPlayerTables implements PlayerDirectory, LedgerReader {

    static final String OWNER_EPOCH_SQL = "SELECT owner_epoch FROM player WHERE player_id = ?";
    static final String STATE_SQL = "SELECT data FROM player_state WHERE player_id = ?";

    private final AuthorityJdbc jdbc;

    public JdbcPlayerTables(AuthorityJdbc jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public OptionalLong ownerEpoch(Deadline budget, long playerId) throws SQLException {
        Long epoch = jdbc.read(budget, s -> s.one(OWNER_EPOCH_SQL, row -> AuthorityJdbc.u64(row, 1), AuthorityJdbc.u64(playerId)));
        return epoch == null ? OptionalLong.empty() : OptionalLong.of(epoch);
    }

    @Override
    public Read read(Deadline budget, long playerId) throws SQLException {
        byte[][] data = jdbc.read(budget, s -> {
            byte[][] found = s.one(STATE_SQL, row -> new byte[][] {row.getBytes(1)}, AuthorityJdbc.u64(playerId));
            return found;
        });
        if (data == null) {
            return Read.absent();
        }
        if (data[0] == null) {
            // data 列 NOT NULL；读到 NULL 只可能是库被改坏，按读失败处理，不当空账本
            throw new SQLException("player_state.data 为 NULL player_id=" + Long.toUnsignedString(playerId));
        }
        try {
            InboxLedgerState ledger = PlayerState.parseFrom(data[0]).getInboxLedger();
            return new Read(true, ledger);
        } catch (InvalidProtocolBufferException e) {
            throw new SQLException("player_state.data 解析失败 player_id=" + Long.toUnsignedString(playerId), e);
        }
    }
}
