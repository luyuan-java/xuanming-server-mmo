package com.game.common.player;

import com.game.proto.common.base.RoleAdmissionIdentity;
import com.game.common.deadline.Deadline;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * 消费既有player_final_save同代OPEN准入，不建立第二份角色归属或入场表。
 * 调用require前必须已持完整路由/P前缀，锁保持到业务提交；原件只能在首次loadForScene同事务登记。
 */
public final class SceneAdmissionAuthority {
    private SceneAdmissionAuthority() {}
    public static final int MAX_ORIGINAL_BYTES = 4096;

    /** 原调用方必须固定所有字段；缺失/越界/未知字段不能用当前目录补齐。 */
    public static byte[] original(RoleAdmissionIdentity identity) {
        if (identity == null || identity.getPlayerId() == 0 || identity.getOwnerRevision() <= 0
                || identity.getOwnerEpoch() <= 0 || identity.getGeneration() <= 0 || identity.getLoginGeneration() <= 0
                || identity.getSceneId() == 0 || identity.getZoneId() == 0 || identity.getSessionId() == 0
                || !identity.getSceneInstanceId().matches("[A-Za-z0-9_-]{1,64}")
                || identity.getSceneNodeId().isEmpty() || identity.getSceneNodeId().length() > 64
                || !identity.getUnknownFields().asMap().isEmpty() || identity.getSerializedSize() > MAX_ORIGINAL_BYTES)
            throw new SecurityException("场景原准入身份缺失或越界");
        byte[] bytes = new byte[identity.getSerializedSize()];
        var output = CodedOutputStream.newInstance(bytes);
        output.useDeterministicSerialization();
        try { identity.writeTo(output); output.checkNoSpaceLeft(); }
        catch (IOException impossible) { throw new IllegalStateException("场景原准入序列化失败", impossible); }
        return bytes;
    }

    public static void require(Connection connection, RoleAdmissionIdentity identity, int timeout) throws SQLException {
        if (timeout <= 0) throw new SQLException("场景准入核验预算无效");
        require(connection,identity,Deadline.after(java.util.concurrent.TimeUnit.SECONDS.toMillis(timeout)));
    }

    public static void require(Connection connection, RoleAdmissionIdentity identity, Deadline budget) throws SQLException {
        byte[] expected = original(identity);
        if (connection.getAutoCommit() || budget == null) throw new SQLException("场景准入核验必须持同P事务");
        try (var statement = connection.prepareStatement("SELECT owner_revision,owner_epoch,owner_released,trade_listing_id FROM player WHERE player_id=? FOR UPDATE")) {
            statement.setQueryTimeout(remainingSeconds(budget));
            statement.setObject(1, PlayerRouteAuthority.u64(identity.getPlayerId()));
            try (var row = statement.executeQuery()) {
                if (!row.next() || !Long.toString(identity.getOwnerRevision()).equals(row.getString(1))
                        || !Long.toString(identity.getOwnerEpoch()).equals(row.getString(2))
                        || !"0".equals(row.getString(3)) || !"0".equals(row.getString(4)))
                    throw new SecurityException("场景原准入归属或持有代已失效");
            }
        }
        try (var statement = connection.prepareStatement("SELECT source_instance,save_seq,protocol_version,status,admission_proto FROM player_final_save WHERE player_id=? AND owner_epoch=? FOR UPDATE")) {
            statement.setQueryTimeout(remainingSeconds(budget));
            statement.setObject(1, PlayerRouteAuthority.u64(identity.getPlayerId()));
            statement.setLong(2, identity.getOwnerEpoch());
            try (var row = statement.executeQuery()) {
                if (!row.next() || !identity.getSceneInstanceId().equals(row.getString(1)) || row.getLong(2) != 1
                        || row.getInt(3) != 1 || !"OPEN".equals(row.getString(4))
                        || !Arrays.equals(expected, row.getBytes(5)))
                    throw new SecurityException("场景完整原准入未登记或已终结");
            }
        }
    }

    private static int remainingSeconds(Deadline budget) throws SQLException {
        if (Thread.currentThread().isInterrupted() || budget.expired()) throw new java.sql.SQLTimeoutException("场景准入核验已超过原请求预算");
        return (int)Math.max(1,Math.min(3,(budget.remainingMillis()+999)/1000));
    }
}
