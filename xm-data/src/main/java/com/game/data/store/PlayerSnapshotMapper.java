package com.game.data.store;

import com.game.data.snapshot.PlayerSnapshotRow;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@code player_snapshot} 的读写。 */
@Mapper
public interface PlayerSnapshotMapper {

    /**
     * 多行插入；主键已存在的行不动（重放幂等，同 {@link TransactionLogMapper#insertAll}）。
     *
     * @return 受影响行数（连接串带 useAffectedRows=true 时 = 新插入的行数）
     */
    @Insert("""
            <script>
            INSERT INTO player_snapshot (snapshot_id, player_id, time_ms, cause, zone_id, owner_epoch, level,
                scene_config_id, pos_x, pos_y, pos_z, player_state, ingested_at)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.snapshotId}, #{r.playerId}, #{r.timeMs}, #{r.cause}, #{r.zoneId}, #{r.ownerEpoch}, #{r.level},
                 #{r.sceneConfigId}, #{r.posX}, #{r.posY}, #{r.posZ}, #{r.playerState}, #{ingestedAt})
            </foreach>
            ON DUPLICATE KEY UPDATE snapshot_id = snapshot_id
            </script>""")
    int insertAll(@Param("rows") List<PlayerSnapshotRow> rows, @Param("ingestedAt") long ingestedAt);

    /** 某玩家的快照元数据（不取玩法数据本体，只带字节数；走 idx_snapshot_player），按时间、快照号升序。 */
    @Select("""
            SELECT snapshot_id, player_id, time_ms, cause, zone_id, owner_epoch, level, scene_config_id,
                   pos_x, pos_y, pos_z, OCTET_LENGTH(player_state) AS state_bytes, ingested_at
              FROM player_snapshot
             WHERE player_id = #{player} AND time_ms >= #{since} AND time_ms < #{until}
             ORDER BY time_ms, snapshot_id LIMIT #{limit}""")
    List<PlayerSnapshotEntry> findByPlayer(@Param("player") long player, @Param("since") long since,
                                           @Param("until") long until, @Param("limit") int limit);

    /** 删一批早于 {@code before} 的快照（保留期清理）。 */
    @Delete("DELETE FROM player_snapshot WHERE time_ms < #{before} LIMIT #{limit}")
    int deleteOlderThan(@Param("before") long before, @Param("limit") int limit);
}
