package com.game.data.store;

import com.game.data.snapshot.PlayerSnapshotRow;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code player_snapshot} 的读写。读路径（data-ops-spec §3.3）一律走 {@code idx_snapshot_player (player_id, time_ms)}
 * 或主键；不分来源（scene 经 Kafka 的与 xm-data 直写的同表同格式）。
 */
@Mapper
public interface PlayerSnapshotMapper {

    /** 元数据列（不取玩法数据本体，只给字节数）。 */
    String META = "snapshot_id, player_id, time_ms, cause, zone_id, owner_epoch, level, scene_config_id, pos_x, pos_y, pos_z, "
            + "OCTET_LENGTH(player_state) AS state_bytes, ingested_at, operator, note";

    /**
     * 多行插入（Kafka 落库路径）；主键已存在的行不动（重放幂等，同 {@link TransactionLogMapper#insertAll}）。
     * operator / note 取列缺省值空串。
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

    /**
     * xm-data 直写一份快照（手工 / 维护前 / 安全快照）：号是刚从全服租约池发的，用普通 INSERT——撞主键说明号源出了问题，
     * 必须报错回滚，不能像 Kafka 落库那样按幂等吞掉。
     *
     * @return 受影响行数（恰好 1）
     */
    @Insert("""
            INSERT INTO player_snapshot (snapshot_id, player_id, time_ms, cause, zone_id, owner_epoch, level,
                scene_config_id, pos_x, pos_y, pos_z, player_state, ingested_at, operator, note)
            VALUES (#{r.snapshotId}, #{r.playerId}, #{r.timeMs}, #{r.cause}, #{r.zoneId}, #{r.ownerEpoch}, #{r.level},
                #{r.sceneConfigId}, #{r.posX}, #{r.posY}, #{r.posZ}, #{r.playerState}, #{ingestedAt}, #{operator}, #{note})""")
    int insertDirect(@Param("r") PlayerSnapshotRow row, @Param("ingestedAt") long ingestedAt,
                     @Param("operator") String operator, @Param("note") String note);

    /** 按号取一份快照（含玩法数据本体）；没有为 null。 */
    @Select("SELECT " + META + ", player_state FROM player_snapshot WHERE snapshot_id = #{id}")
    PlayerSnapshotEntry findById(@Param("id") long snapshotId);

    /**
     * 某玩家在 {@code atMs}（含）之前、原因在白名单里的最近一份快照的元数据；同毫秒并列取号大的（雪花时间序）。没有为 null。
     * 白名单由调用方给（按时刻选源的缺省白名单不含安全快照，见 SnapshotCauses.POINT_IN_TIME_SOURCES）。
     */
    @Select("""
            <script>
            SELECT""" + " " + META + " " + """
              FROM player_snapshot
             WHERE player_id = #{player} AND time_ms &lt;= #{atMs} AND cause IN
             <foreach collection="causes" item="c" open="(" separator="," close=")">#{c}</foreach>
             ORDER BY time_ms DESC, snapshot_id DESC LIMIT 1
            </script>""")
    PlayerSnapshotEntry findLatestAtOrBefore(@Param("player") long player, @Param("atMs") long atMs,
                                             @Param("causes") Collection<Integer> causes);

    /**
     * 某玩家的快照元数据（半开窗口 [since, until)），可按原因过滤（空 = 全部），正序或倒序（时间、快照号），至多 {@code limit} 条。
     */
    @Select("""
            <script>
            SELECT""" + " " + META + " " + """
              FROM player_snapshot
             WHERE player_id = #{player} AND time_ms &gt;= #{since} AND time_ms &lt; #{until}
             <if test="causes != null and !causes.isEmpty()">
               AND cause IN <foreach collection="causes" item="c" open="(" separator="," close=")">#{c}</foreach>
             </if>
             <choose>
               <when test="desc">ORDER BY time_ms DESC, snapshot_id DESC</when>
               <otherwise>ORDER BY time_ms, snapshot_id</otherwise>
             </choose>
             LIMIT #{limit}
            </script>""")
    List<PlayerSnapshotEntry> listByPlayer(@Param("player") long player, @Param("since") long since,
                                           @Param("until") long until, @Param("causes") Collection<Integer> causes,
                                           @Param("desc") boolean desc, @Param("limit") int limit);

    /**
     * 保留期清理：删一批「内容时刻与拍摄时刻都早于 {@code before}」、原因在 {@code causes} 里的快照。
     * 多判一个 {@code ingested_at}：xm-data 直写的快照内容时刻可能远早于拍摄时刻（玩家很久没上线），只看 time_ms 会把刚拍的删掉；
     * 而拍摄时刻总不早于内容时刻，所以 time_ms 上的范围条件照样能走 {@code idx_snapshot_time} 缩小扫描。
     */
    @Delete("""
            <script>
            DELETE FROM player_snapshot
             WHERE time_ms &lt; #{before} AND ingested_at &lt; #{before} AND cause IN
             <foreach collection="causes" item="c" open="(" separator="," close=")">#{c}</foreach>
             LIMIT #{limit}
            </script>""")
    int deleteOlderThan(@Param("before") long before, @Param("causes") Collection<Integer> causes,
                        @Param("limit") int limit);
}
