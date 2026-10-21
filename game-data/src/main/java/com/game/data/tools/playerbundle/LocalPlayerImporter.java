package com.game.data.tools.playerbundle;

import static com.game.data.tools.playerbundle.BundleMysqlReader.bindU64;

import com.game.player.store.PlayerMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 把导入计划写进本地库：第一段「占位并写入」、第三段「发布」（第二段是 Redis 清理，在调用方）。
 *
 * <p><b>为什么分段、为什么中途失败是 fail-closed</b>：第一段在一个事务里把玩家行写成 (新 epoch E, 未释放, 租约 = {@link #IMPORT_HOLD_LEASE})
 * 并写好玩法数据。这个租约永不过期，所以从这一刻起 login 的夺权（「已释放或租约过期」才成功）一律拿不到归属、回 2005，玩家进不来；
 * 本地残留的旧写者（epoch &lt; E）的写回、续约、释放全被围栏挡掉。第三段确认占位还是自己的（epoch = E、未释放、租约 = 哨兵值）才置为已释放，
 * 玩家才能登录。任一段之后中断，玩家都停在「进不来」，不会以半成品被加载；带 {@code --replace} 重跑即可（重跑认得这个占位）。
 *
 * <p>新 epoch E = max(线上 epoch, 本地 epoch) + 1：大于本地任何旧写者，按归属协议它们的迟到写都落不了库。
 */
final class LocalPlayerImporter {

    /**
     * 导入占位的租约值：BIGINT 最大值，永不过期（{@code owner_lease_until < now} 恒假）。正常写路径写的租约是「现在 + 30 s」，
     * 不会撞上它，所以它同时是「这是导入中断留下的占位」的标记。
     */
    static final long IMPORT_HOLD_LEASE = Long.MAX_VALUE;

    /** 本地同号玩家此刻的归属状态；{@code exists = false} 时其余字段无意义。 */
    record LocalState(boolean exists, long ownerEpoch, int ownerReleased, long ownerLeaseUntil, String account) {

        static final LocalState ABSENT = new LocalState(false, 0, 1, 0, null);

        boolean importHold() {
            return exists && ownerReleased == 0 && ownerLeaseUntil == IMPORT_HOLD_LEASE;
        }
    }

    /** 拒绝导入（原因写给操作者）。 */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    private final Connection connection;

    LocalPlayerImporter(Connection connection) {
        this.connection = connection;
    }

    /** 读本地同号玩家的归属状态（不加锁；预览与提示用，提交点在 {@link #stage} 里加锁复核）。 */
    LocalState inspect(long playerId) throws SQLException {
        return readOwner(playerId, false);
    }

    /** 本地与导入名字冲突的另一名玩家（同 {@code name_key}、不同玩家号）；没有为 null。 */
    String nameConflict(ImportPlan plan) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_id FROM player WHERE name_key = ? AND player_id <> ?")) {
            ps.setString(1, plan.nameKey());
            bindU64(ps, 2, plan.playerId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * 准入：本地同号玩家在线（被未过期的租约持有）一律拒绝；已有同号存档要 {@code replace}。
     *
     * @throws Refused 不准导入
     */
    static void admit(LocalState local, boolean replace, long nowMs) throws Refused {
        if (!local.exists()) {
            return;
        }
        if (local.importHold()) {
            if (!replace) {
                throw new Refused("本地同号玩家是上一次导入中断留下的占位（epoch " + Long.toUnsignedString(local.ownerEpoch()) + "）：加 --replace 重跑");
            }
            return;
        }
        if (local.ownerReleased() == 0 && local.ownerLeaseUntil() >= nowMs) {
            throw new Refused("本地同号玩家在线：归属由 epoch " + Long.toUnsignedString(local.ownerEpoch()) + " 持有，租约还剩 "
                    + (local.ownerLeaseUntil() - nowMs) + " ms。先让它下线（或停掉本地 scene 等租约过期）再导入");
        }
        if (!replace) {
            throw new Refused("本地已有同号玩家（账号 " + local.account() + "）：确认可以覆盖后加 --replace");
        }
    }

    /** 本地要重铸的 epoch：max(线上, 本地) + 1（无符号）。 */
    static long nextEpoch(long baseEpoch, LocalState local) {
        long max = Long.compareUnsigned(baseEpoch, local.ownerEpoch()) >= 0 ? baseEpoch : local.ownerEpoch();
        if (max == -1L) {
            throw new IllegalStateException("epoch 已到 uint64 上限");
        }
        return max + 1;
    }

    /**
     * 第一段（一个事务）：加锁复核准入 → 补账号行 → 写玩家行（新 epoch、未释放、占位租约）→ 写或删玩法数据。失败回滚、什么也没写。
     *
     * @return 本次重铸的 epoch
     * @throws Refused 加锁复核时不准导入（已回滚）
     */
    long stage(ImportPlan plan, boolean replace, long nowMs) throws SQLException, Refused {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            com.game.common.player.PlayerRouteAuthority.lock(connection,java.util.List.of(plan.playerId()),java.util.List.of((int)plan.zoneId()),5);
            LocalState local = readOwner(plan.playerId(), true);
            if (local.exists() && !tradeAdmission(plan.playerId(), true)) {
                throw new Refused("本地角色存在交易锁、账号归属修订损坏或修订耗尽，禁止覆盖");
            }
            if (com.game.player.store.RolePendingFence.blocked(connection,plan.playerId())||com.game.player.store.RolePendingFence.economicBlocked(connection,plan.playerId()))
                throw new Refused("本地角色仍有历练ACTIVE、待发奖、未终局帮会/邮件资产或匹配在途，禁止覆盖");
            if (hasBlockingFinalSave(plan.playerId())) {
                throw new Refused("本地玩家存在未完成或无效的最终存盘，必须先恢复待办再导入");
            }
            admit(local, replace, nowMs);
            String conflict = nameConflict(plan);
            if (conflict != null) {
                throw new Refused("名字 " + plan.name() + " 与本地玩家 " + conflict + " 冲突（名字全服唯一、大小写与全半角不敏感）：用 --name 指定一个本地不重的名字");
            }
            long epoch = nextEpoch(plan.baseEpoch(), local);
            try (PreparedStatement ps = connection.prepareStatement("INSERT IGNORE INTO account (account, created_at) VALUES (?, ?)")) {
                ps.setString(1, plan.account());
                ps.setLong(2, nowMs);
                ps.executeUpdate();
            }
            writePlayer(plan, local.exists(), epoch, nowMs);
            writeState(plan, epoch, nowMs);
            connection.commit();
            return epoch;
        } catch (SQLException | Refused | RuntimeException e) {
            rollbackQuietly();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * 第三段（独立事务）：同 player → final intent 锁顺序复核占位与待办后发布。
     * 最终存盘不只可能由当前 epoch 登记；异代或损坏待办也不能被运维发布绕过。
     *
     * @return 是否发布成功；false = 占位已变或仍有最终存盘待办，玩家继续保持不可登录
     */
    boolean publish(long playerId, long epoch, long nowMs) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            com.game.common.player.PlayerRouteAuthority.lock(connection,java.util.List.of(playerId),5);
            LocalState local = readOwner(playerId, true);
            if (!local.importHold() || local.ownerEpoch() != epoch || !tradeAdmission(playerId, false)
                    || hasBlockingFinalSave(playerId)
                    || com.game.player.store.RolePendingFence.blocked(connection,playerId)||com.game.player.store.RolePendingFence.economicBlocked(connection,playerId)) {
                connection.rollback();
                return false;
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE player SET owner_released = 1, owner_lease_until = 0, updated_at = ?
                     WHERE player_id = ? AND owner_epoch = ? AND owner_released = 0 AND owner_lease_until = ?
                       AND trade_listing_id=0 AND owner_revision>0""")) {
                ps.setLong(1, nowMs);
                bindU64(ps, 2, playerId);
                bindU64(ps, 3, epoch);
                ps.setLong(4, IMPORT_HOLD_LEASE);
                if (ps.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                // 不能在回滚未明的事务上通过恢复autoCommit意外提交。
                try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw failure;
        } finally {
            if (!connection.isClosed()) {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /** 调用方已经持有 player 行锁；只投影小归属行，状态规则与所有 claim/load/release 入口同源。 */
    private boolean hasBlockingFinalSave(long playerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT owner_epoch FROM player_final_save WHERE player_id = ? AND "
                        + PlayerMapper.BLOCKING_FINAL_STATUS + " ORDER BY owner_epoch LIMIT 1 FOR UPDATE")) {
            bindU64(ps, 1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private LocalState readOwner(long playerId, boolean forUpdate) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT owner_epoch, owner_released, owner_lease_until, account FROM player WHERE player_id = ?"
                        + (forUpdate ? " FOR UPDATE" : ""))) {
            bindU64(ps, 1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return LocalState.ABSENT;
                }
                return new LocalState(true, Long.parseUnsignedLong(rs.getString("owner_epoch")), rs.getInt("owner_released"),
                        rs.getLong("owner_lease_until"), rs.getString("account"));
            }
        }
    }

    private boolean tradeAdmission(long playerId, boolean replacing) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT trade_listing_id,owner_revision FROM player WHERE player_id=? FOR UPDATE")) {
            bindU64(ps, 1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                long listing = rs.getLong(1); boolean missingListing = rs.wasNull();
                String rawRevision = rs.getString(2);
                long revision;
                try { revision = Long.parseUnsignedLong(rawRevision); }
                catch (NumberFormatException invalid) { return false; }
                return !missingListing && listing == 0 && revision > 0 && (!replacing || revision < Long.MAX_VALUE);
            }
        }
    }

    private void writePlayer(ImportPlan plan, boolean exists, long epoch, long nowMs) throws SQLException {
        String sql = exists ? """
                UPDATE player SET account = ?, zone_id = ?, name = ?, name_key = ?, class_id = ?, gender = ?, appearance_id = ?,
                       level = ?, scene_config_id = ?, pos_x = ?, pos_y = ?, pos_z = ?, owner_epoch = ?, owner_released = 0,
                       owner_lease_until = ?, created_at = ?, updated_at = ?, owner_revision=owner_revision+1
                 WHERE player_id = ? AND trade_listing_id=0 AND owner_revision>0 AND owner_revision<9223372036854775807""" : """
                INSERT INTO player (account, zone_id, name, name_key, class_id, gender, appearance_id, level, scene_config_id,
                                    pos_x, pos_y, pos_z, owner_epoch, owner_released, owner_lease_until, created_at, updated_at, player_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, plan.account());
            ps.setLong(2, plan.zoneId());
            ps.setString(3, plan.name());
            ps.setString(4, plan.nameKey());
            ps.setLong(5, plan.classId());
            ps.setLong(6, plan.gender());
            ps.setString(7, plan.appearanceId());
            ps.setLong(8, plan.level());
            ps.setLong(9, plan.sceneConfigId());
            ps.setDouble(10, plan.posX());
            ps.setDouble(11, plan.posY());
            ps.setDouble(12, plan.posZ());
            bindU64(ps, 13, epoch);
            ps.setLong(14, IMPORT_HOLD_LEASE);
            ps.setLong(15, plan.createdAt());
            ps.setLong(16, nowMs);
            bindU64(ps, 17, plan.playerId());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("写玩家行影响行数不是 1");
            }
        }
    }

    private void writeState(ImportPlan plan, long epoch, long nowMs) throws SQLException {
        if (plan.stateBytes() == null) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM player_state WHERE player_id = ?")) {
                bindU64(ps, 1, plan.playerId());
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO player_state (player_id, data, saved_epoch, updated_at) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE data = VALUES(data), saved_epoch = VALUES(saved_epoch), updated_at = VALUES(updated_at)""")) {
            bindU64(ps, 1, plan.playerId());
            ps.setBytes(2, plan.stateBytes());
            bindU64(ps, 3, epoch);
            ps.setLong(4, nowMs);
            ps.executeUpdate();
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 回滚失败时连接已坏，事务随连接关闭由库回滚；保留原始失败
        }
    }
}
