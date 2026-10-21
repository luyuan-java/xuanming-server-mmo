package com.game.player.store;

import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 账号 / 玩家表的 SQL；运维入口仅复用同源最终存盘准入谓词，业务写入仍经 {@link PlayerStore}。 */
@Mapper
public interface PlayerMapper {

    // ASCII协议状态逐字节比较，避免部署collation放宽大小写或尾空格；不依赖数据库专有COLLATE。
    String EXACT_OPEN = "(OCTET_LENGTH(status)=4 AND ASCII(SUBSTRING(status,1,1))=79 AND ASCII(SUBSTRING(status,2,1))=80 AND ASCII(SUBSTRING(status,3,1))=69 AND ASCII(SUBSTRING(status,4,1))=78)";
    String EXACT_PENDING = "(OCTET_LENGTH(status)=7 AND ASCII(SUBSTRING(status,1,1))=80 AND ASCII(SUBSTRING(status,2,1))=69 AND ASCII(SUBSTRING(status,3,1))=78 AND ASCII(SUBSTRING(status,4,1))=68 AND ASCII(SUBSTRING(status,5,1))=73 AND ASCII(SUBSTRING(status,6,1))=78 AND ASCII(SUBSTRING(status,7,1))=71)";
    String EXACT_APPLIED = "(OCTET_LENGTH(status)=7 AND ASCII(SUBSTRING(status,1,1))=65 AND ASCII(SUBSTRING(status,2,1))=80 AND ASCII(SUBSTRING(status,3,1))=80 AND ASCII(SUBSTRING(status,4,1))=76 AND ASCII(SUBSTRING(status,5,1))=73 AND ASCII(SUBSTRING(status,6,1))=69 AND ASCII(SUBSTRING(status,7,1))=68)";
    // 全部写者/运维入口共用：未知、NULL、大小写或尾空格异常一律阻断，不读大快照。
    String BLOCKING_FINAL_STATUS = "(status IS NULL OR NOT (" + EXACT_OPEN + " OR " + EXACT_APPLIED + "))";


    // JDBC现有int API保留uint32位模式；SQL列始终存0..4294967295，回读先降为有符号int范围。
    String PLAYER_COLUMNS = "player_id,account,owner_revision,trade_listing_id,CASE WHEN zone_id>2147483647 THEN CAST(zone_id AS DECIMAL(20,0))-4294967296 ELSE zone_id END AS zone_id,name,name_key,class_id,gender,appearance_id,level,"
            + "CASE WHEN scene_config_id>2147483647 THEN CAST(scene_config_id AS DECIMAL(20,0))-4294967296 ELSE scene_config_id END AS scene_config_id,"
            + "pos_x,pos_y,pos_z,owner_epoch,owner_released,owner_lease_until,created_at,updated_at";

    @Insert("INSERT IGNORE INTO account (account, created_at) VALUES (#{account}, #{createdAt})")
    int insertAccountIfAbsent(@Param("account") String account, @Param("createdAt") long createdAt);

    /** 账号的口令记录（主键精确匹配，utf8mb4_bin）；账号不存在返回 null。 */
    @Select("SELECT account, password_hash FROM account WHERE account = #{account}")
    AccountPassword selectAccountPassword(@Param("account") String account);

    /**
     * 锁住账号行（主键上的记录锁，不带间隙锁），同一账号的建角在所有 login 实例之间串行。
     * 必须是事务里的<b>第一条</b>语句，见 {@link PlayerStore#createPlayerWithinCap}。账号不存在返回 null。
     */
    @Select("SELECT account FROM account WHERE account = #{account} FOR UPDATE")
    String lockAccount(@Param("account") String account);

    @Select("SELECT " + PLAYER_COLUMNS + " FROM player WHERE account = #{account} ORDER BY created_at, player_id")
    List<PlayerRow> selectByAccount(@Param("account") String account);

    @Select("SELECT COUNT(*) FROM player WHERE account = #{account}")
    int countByAccount(@Param("account") String account);

    @Select("SELECT " + PLAYER_COLUMNS + " FROM player WHERE player_id = #{playerId}")
    PlayerRow selectById(@Param("playerId") long playerId);

    /** 调用者已持player主键锁；只读交易门禁，不复制账号或资产事实源。 */
    @Select("SELECT trade_listing_id FROM player WHERE player_id=#{playerId}")
    Long selectTradeListingId(@Param("playerId") long playerId);

    /** 旧准入E一旦封闭，即使撤单清锁也不能由迟到的PlayerEnter复活。 */
    @Select("SELECT COUNT(*) FROM player_trade_drain WHERE player_id=#{playerId} AND owner_epoch=#{epoch}")
    long countTradeSeals(@Param("playerId") long playerId, @Param("epoch") long epoch);

    /** {@code nameKey} 只由 {@link PlayerStore#nameKey} 计算，不从行对象取（行对象不携带唯一键，调用方无从填错）。 */
    @Insert("""
            INSERT INTO player (player_id, account, zone_id, name, name_key, class_id, gender, appearance_id, level,
                                scene_config_id, pos_x, pos_y, pos_z, owner_epoch, created_at, updated_at)
            VALUES (#{row.playerId}, #{row.account}, (CASE WHEN #{row.zoneId}<0 THEN #{row.zoneId}+4294967296 ELSE #{row.zoneId} END), #{row.name}, #{nameKey}, #{row.classId},
                    #{row.gender}, #{row.appearanceId}, #{row.level}, (CASE WHEN #{row.sceneConfigId}<0 THEN #{row.sceneConfigId}+4294967296 ELSE #{row.sceneConfigId} END),
                    #{row.posX}, #{row.posY}, #{row.posZ}, #{row.ownerEpoch}, #{row.createdAt}, #{row.updatedAt})
            """)
    int insertPlayer(@Param("row") PlayerRow row, @Param("nameKey") String nameKey);

    /**
     * 夺权：只有上一个写者已释放（{@code owner_released = 1}）或它的租约已过期时才把 epoch 加一、标记为被持有并给新租约；
     * 否则影响 0 行。与随后的 {@link #selectOwnerEpoch} 必须在同一事务里（行锁保证读到的是本次自增的值）。
     */
    @Update("""
            UPDATE player
               SET owner_epoch = owner_epoch + 1, owner_released = 0, owner_lease_until = #{leaseUntil}, updated_at = #{now}
             WHERE player_id = #{playerId} AND trade_listing_id=0 AND (owner_released = 1 OR owner_lease_until < #{now})
            """)
    int claimOwnerEpoch(@Param("playerId") long playerId, @Param("now") long now, @Param("leaseUntil") long leaseUntil);

    /**
     * 历史恢复专用：调用方已在同一事务 FOR UPDATE 锁住玩家，并由独立审核上界选定严格更大的新纪元。
     * 仍在提交语句里复核原纪元与释放/过期条件；只改归属元数据，不触碰玩家资产或其 saved_epoch。
     */
    @Update("""
            UPDATE player
               SET owner_epoch = #{nextEpoch}, owner_revision=#{nextRevision}, owner_released = 0, owner_lease_until = #{leaseUntil}, updated_at = #{now}
             WHERE player_id = #{playerId} AND owner_epoch = #{expectedEpoch} AND trade_listing_id=0
               AND owner_revision=#{expectedRevision}
               AND (owner_released = 1 OR owner_lease_until < #{now})
            """)
    int recoverOwnerEpoch(@Param("playerId") long playerId, @Param("expectedEpoch") long expectedEpoch,
                          @Param("expectedRevision") long expectedRevision,@Param("nextRevision") long nextRevision,
                          @Param("nextEpoch") long nextEpoch, @Param("now") long now,
                          @Param("leaseUntil") long leaseUntil);

    @Select("SELECT owner_epoch FROM player WHERE player_id = #{playerId}")
    Long selectOwnerEpoch(@Param("playerId") long playerId);

    /** 带围栏的最终写回并释放归属：epoch 不是当前值（已被新的进场夺权）时影响 0 行。 */
    @Update("""
            UPDATE player
               SET level = #{level}, scene_config_id = (CASE WHEN #{sceneConfigId}<0 THEN #{sceneConfigId}+4294967296 ELSE #{sceneConfigId} END),
                   pos_x = #{posX}, pos_y = #{posY}, pos_z = #{posZ}, owner_released = 1, updated_at = #{updatedAt}
             WHERE player_id = #{playerId} AND owner_epoch = #{ownerEpoch} AND owner_released = 0
            """)
    int updateStateAndRelease(PlayerRow row);

    /**
     * 带围栏的在线存盘（不释放）：只有仍由这个 epoch 持有、且尚未释放时才写。
     * {@code owner_released = 0} 这一条让迟到的在线存盘永远盖不过已经提交的最终写回（最终写回会置 1）。
     */
    @Update("""
            UPDATE player
               SET level = #{level}, scene_config_id = (CASE WHEN #{sceneConfigId}<0 THEN #{sceneConfigId}+4294967296 ELSE #{sceneConfigId} END),
                   pos_x = #{posX}, pos_y = #{posY}, pos_z = #{posZ}, updated_at = #{updatedAt}
             WHERE player_id = #{playerId} AND owner_epoch = #{ownerEpoch} AND owner_released = 0
            """)
    int updateStateHeld(PlayerRow row);

    /**
     * 带围栏的交出（跨节点换图，归属协议第 6 步）：与 {@link #updateStateHeld} 同形地写回冻结快照，同时把 epoch 加一、
     * 把释放标记置为 {@code released}、给新租约 {@code leaseUntil}。只有仍由 {@code row.ownerEpoch} 持有、尚未释放、且剩余租约不短于安全边际
     * （{@code owner_lease_until >= requireLeaseAtLeast}）时才改，否则影响 0 行、什么也不改——判定条件（WHERE）不随 {@code released} 变。
     * 与随后的 {@link #selectOwnerEpoch} / {@link #selectOwnerForUpdate} 必须在同一事务里，见 {@link PlayerStore#handOffOwnership}。
     *
     * @param released 交出后 {@code owner_released} 的取值，只能是 0 或 1（由 {@link PlayerStore.HandOffMode} 决定，调用方不自己拼）：
     *                 0 = 新 epoch 保持未释放，等接手的节点来续约（跨节点换图，批次 5.2）；
     *                 1 = 新 epoch 同时释放，没有持有者，下一次夺权立即成功（跨 zone 传送，批次 5.4）。
     *                 为 1 时 {@code leaseUntil} 对夺权没有语义（夺权先看释放标记），只当这次尝试的标识
     */
    @Update("""
            UPDATE player
               SET level = #{row.level}, scene_config_id = (CASE WHEN #{row.sceneConfigId}<0 THEN #{row.sceneConfigId}+4294967296 ELSE #{row.sceneConfigId} END),
                   pos_x = #{row.posX}, pos_y = #{row.posY}, pos_z = #{row.posZ},
                   owner_epoch = owner_epoch + 1, owner_released = #{released}, owner_lease_until = #{leaseUntil},
                   updated_at = #{row.updatedAt}
             WHERE player_id = #{row.playerId} AND owner_epoch = #{row.ownerEpoch} AND owner_released = 0 AND trade_listing_id=0
               AND owner_lease_until >= #{requireLeaseAtLeast}
            """)
    int updateStateAndHandOff(@Param("row") PlayerRow row, @Param("leaseUntil") long leaseUntil,
                              @Param("requireLeaseAtLeast") long requireLeaseAtLeast, @Param("released") int released);

    /**
     * 加锁读归属三列（主键记录锁）：会等任何仍持有这一行行锁的在途事务结束，所以读到的结论确定——没提交的不会再提交。
     * 时限由调用方的事务超时给出（{@link PlayerStore#probeOwnership}）。玩家不存在返回 null。
     */
    @Select("SELECT owner_epoch, owner_released, owner_lease_until FROM player WHERE player_id = #{playerId} FOR UPDATE")
    @ConstructorArgs({
            @Arg(column = "owner_epoch", javaType = long.class),
            @Arg(column = "owner_released", javaType = boolean.class),
            @Arg(column = "owner_lease_until", javaType = long.class)})
    OwnerState selectOwnerForUpdate(@Param("playerId") long playerId);

    @Select("SELECT data FROM player_state WHERE player_id = #{playerId}")
    PlayerStateRow selectState(@Param("playerId") long playerId);

    /** 写玩家玩法数据（存在即覆盖）。必须在已通过围栏的同一事务里调用，见 {@link PlayerStore#saveState}。 */
    @Insert("""
            INSERT INTO player_state (player_id, data, saved_epoch, updated_at)
            VALUES (#{playerId}, #{data}, #{savedEpoch}, #{updatedAt})
            ON DUPLICATE KEY UPDATE data = VALUES(data), saved_epoch = VALUES(saved_epoch), updated_at = VALUES(updated_at)
            """)
    int upsertState(@Param("playerId") long playerId, @Param("data") byte[] data, @Param("savedEpoch") long savedEpoch,
                    @Param("updatedAt") long updatedAt);

    /** 带围栏的释放（不写状态）：只释放仍由这个 epoch 持有的归属，否则影响 0 行。 */
    @Update("""
            UPDATE player SET owner_released = 1, updated_at = #{now}
             WHERE player_id = #{playerId} AND owner_epoch = #{ownerEpoch} AND owner_released = 0
            """)
    int releaseOwner(@Param("playerId") long playerId, @Param("ownerEpoch") long ownerEpoch, @Param("now") long now);


    /** 先锁 player 才能调用；所有写方遵守 player → final-save 的唯一锁序。 */
    @Select("SELECT owner_epoch FROM player_final_save WHERE player_id=#{playerId} AND " + BLOCKING_FINAL_STATUS + " ORDER BY owner_epoch LIMIT 1 FOR UPDATE")
    Long selectBlockingFinalEpoch(@Param("playerId") long playerId);

    @Select("SELECT owner_epoch FROM player_final_save WHERE player_id=#{playerId} AND owner_epoch<>#{epoch} AND " + BLOCKING_FINAL_STATUS + " ORDER BY owner_epoch LIMIT 1 FOR UPDATE")
    Long selectOtherBlockingFinalEpoch(@Param("playerId") long playerId,@Param("epoch") long epoch);

    @Select("""
            SELECT player_id, owner_epoch, save_seq, protocol_version, source_instance, status,
                   level, scene_config_id, pos_x, pos_y, pos_z, data, payload_digest
              FROM player_final_save WHERE player_id = #{playerId} AND owner_epoch = #{epoch} FOR UPDATE
            """)
    @ConstructorArgs({
            @Arg(column="player_id", javaType=long.class), @Arg(column="owner_epoch", javaType=long.class),
            @Arg(column="save_seq", javaType=long.class), @Arg(column="protocol_version", javaType=int.class),
            @Arg(column="source_instance", javaType=String.class), @Arg(column="status", javaType=String.class),
            @Arg(column="level", javaType=Long.class), @Arg(column="scene_config_id", javaType=Long.class),
            @Arg(column="pos_x", javaType=Double.class), @Arg(column="pos_y", javaType=Double.class),
            @Arg(column="pos_z", javaType=Double.class), @Arg(column="data", javaType=byte[].class),
            @Arg(column="payload_digest", javaType=byte[].class)})
    PlayerStore.FinalSaveRow selectFinalSave(@Param("playerId") long playerId, @Param("epoch") long epoch);

    @Insert("""
            INSERT INTO player_final_save (player_id,owner_epoch,save_seq,protocol_version,source_instance,status,
                    created_at,next_attempt_at) VALUES (#{playerId},#{epoch},1,1,#{source},'OPEN',#{now},#{now})
            """)
    int insertFinalAdmission(@Param("playerId") long playerId,@Param("epoch") long epoch,
                            @Param("source") String source,@Param("now") long now);

    /** 只在已锁P且核原调用身份的新load中登记；旧空准入不允许UPDATE补权。 */
    @Insert("""
            INSERT INTO player_final_save (player_id,owner_epoch,save_seq,protocol_version,source_instance,status,
                    admission_proto,created_at,next_attempt_at)
            VALUES (#{playerId},#{epoch},1,1,#{source},'OPEN',#{admission},#{now},#{now})
            """)
    int insertFinalAdmissionOriginal(@Param("playerId") long playerId,@Param("epoch") long epoch,
                                     @Param("source") String source,@Param("admission") byte[] admission,@Param("now") long now);

    @Select("SELECT admission_proto FROM player_final_save WHERE player_id=#{playerId} AND owner_epoch=#{epoch} FOR UPDATE")
    byte[] selectFinalAdmissionProto(@Param("playerId") long playerId,@Param("epoch") long epoch);

    @Insert("""
            INSERT INTO player_final_save (player_id,owner_epoch,save_seq,protocol_version,source_instance,status,
                    level,scene_config_id,pos_x,pos_y,pos_z,data,payload_digest,created_at,next_attempt_at)
            VALUES (#{row.playerId},#{row.ownerEpoch},1,1,#{source},'PENDING',#{row.level},(CASE WHEN #{row.sceneConfigId}<0 THEN #{row.sceneConfigId}+4294967296 ELSE #{row.sceneConfigId} END),
                    #{row.posX},#{row.posY},#{row.posZ},#{data},#{digest},#{now},#{now})
            """)
    int insertFinalSave(@Param("row") PlayerRow row,@Param("data") byte[] data,@Param("digest") byte[] digest,
                        @Param("source") String source,@Param("now") long now);

    @Update("UPDATE player_final_save SET status='PENDING',level=#{row.level},scene_config_id=(CASE WHEN #{row.sceneConfigId}<0 THEN #{row.sceneConfigId}+4294967296 ELSE #{row.sceneConfigId} END),pos_x=#{row.posX},pos_y=#{row.posY},pos_z=#{row.posZ},data=#{data},payload_digest=#{digest},created_at=#{now},next_attempt_at=#{now} WHERE player_id=#{row.playerId} AND owner_epoch=#{row.ownerEpoch} AND " + EXACT_OPEN + " AND save_seq=1 AND protocol_version=1 AND source_instance=#{source}")
    int registerOpenFinalSave(@Param("row") PlayerRow row,@Param("data") byte[] data,@Param("digest") byte[] digest,
                             @Param("source") String source,@Param("now") long now);

    @Update("UPDATE player_final_save SET status='APPLIED',applied_at=#{now},last_error_code='' WHERE player_id=#{playerId} AND owner_epoch=#{epoch} AND " + EXACT_PENDING + " AND payload_digest=#{digest}")
    int markFinalApplied(@Param("playerId") long playerId,@Param("epoch") long epoch,
                         @Param("digest") byte[] digest,@Param("now") long now);

    @Select("SELECT player_id,owner_epoch FROM player_final_save WHERE " + EXACT_PENDING + " AND next_attempt_at <= #{now} ORDER BY next_attempt_at,player_id,owner_epoch LIMIT #{limit}")
    @ConstructorArgs({@Arg(column="player_id",javaType=long.class),@Arg(column="owner_epoch",javaType=long.class)})
    List<PlayerStore.FinalSaveKey> selectDueFinalSaves(@Param("now") long now,@Param("limit") int limit);

    @Update("UPDATE player_final_save SET next_attempt_at=#{next},attempt_count=LEAST(attempt_count+1,9223372036854775806),last_error_code=#{code} WHERE player_id=#{playerId} AND owner_epoch=#{epoch} AND " + EXACT_PENDING)
    int postponeFinalSave(@Param("playerId") long playerId,@Param("epoch") long epoch,
                          @Param("next") long next,@Param("code") String code);

    @Select("SELECT f.player_id,f.owner_epoch FROM player_final_save f JOIN player p ON p.player_id=f.player_id AND p.owner_epoch>f.owner_epoch WHERE f.status=#{status} AND (" + EXACT_OPEN + " OR " + EXACT_APPLIED + ") AND f.created_at < #{cutoff} AND (" + EXACT_OPEN + " OR f.applied_at < #{cutoff}) ORDER BY f.created_at,f.player_id,f.owner_epoch LIMIT #{limit}")
    @ConstructorArgs({@Arg(column="player_id",javaType=long.class),@Arg(column="owner_epoch",javaType=long.class)})
    List<PlayerStore.FinalSaveKey> selectRetiredFinalKeys(@Param("status") String status,
            @Param("cutoff") long cutoff,@Param("limit") int limit);

    /** 调用方已先锁player并证明owner_epoch更大；PENDING永不进入此删除入口。 */
    @Delete("DELETE FROM player_final_save WHERE player_id=#{playerId} AND owner_epoch=#{epoch} AND created_at < #{cutoff} AND (" + EXACT_OPEN + " OR (" + EXACT_APPLIED + " AND applied_at < #{cutoff}))")
    int deleteRetiredFinal(@Param("playerId") long playerId,@Param("epoch") long epoch,@Param("cutoff") long cutoff);

    /** 批量续约：只续仍由对应 epoch 持有的归属。返回匹配的行数（驱动默认 useAffectedRows=false，即 found rows）。 */
    @Update({"<script>",
            "UPDATE player SET owner_lease_until = #{leaseUntil}",
            " WHERE owner_released = 0 AND (player_id, owner_epoch) IN",
            "<foreach collection='leases' item='l' open='(' separator=',' close=')'>(#{l.playerId}, #{l.ownerEpoch})</foreach>",
            "</script>"})
    int renewOwnerLeases(@Param("leases") List<OwnerLease> leases, @Param("leaseUntil") long leaseUntil);

    /** 这些 (player_id, epoch) 里仍由该 epoch 持有的 player_id。 */
    @Select({"<script>",
            "SELECT player_id FROM player",
            " WHERE owner_released = 0 AND (player_id, owner_epoch) IN",
            "<foreach collection='leases' item='l' open='(' separator=',' close=')'>(#{l.playerId}, #{l.ownerEpoch})</foreach>",
            "</script>"})
    List<Long> selectStillHeld(@Param("leases") List<OwnerLease> leases);
}
