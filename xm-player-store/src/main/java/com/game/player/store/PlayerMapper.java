package com.game.player.store;

import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 账号 / 玩家表的 SQL。只被 {@link PlayerStore} 调用，业务代码不直接用。 */
@Mapper
public interface PlayerMapper {

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

    @Select("SELECT * FROM player WHERE account = #{account} ORDER BY created_at, player_id")
    List<PlayerRow> selectByAccount(@Param("account") String account);

    @Select("SELECT COUNT(*) FROM player WHERE account = #{account}")
    int countByAccount(@Param("account") String account);

    @Select("SELECT * FROM player WHERE player_id = #{playerId}")
    PlayerRow selectById(@Param("playerId") long playerId);

    /** {@code nameKey} 只由 {@link PlayerStore#nameKey} 计算，不从行对象取（行对象不携带唯一键，调用方无从填错）。 */
    @Insert("""
            INSERT INTO player (player_id, account, zone_id, name, name_key, class_id, gender, appearance_id, level,
                                scene_config_id, pos_x, pos_y, pos_z, owner_epoch, created_at, updated_at)
            VALUES (#{row.playerId}, #{row.account}, #{row.zoneId}, #{row.name}, #{nameKey}, #{row.classId},
                    #{row.gender}, #{row.appearanceId}, #{row.level}, #{row.sceneConfigId},
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
             WHERE player_id = #{playerId} AND (owner_released = 1 OR owner_lease_until < #{now})
            """)
    int claimOwnerEpoch(@Param("playerId") long playerId, @Param("now") long now, @Param("leaseUntil") long leaseUntil);

    @Select("SELECT owner_epoch FROM player WHERE player_id = #{playerId}")
    Long selectOwnerEpoch(@Param("playerId") long playerId);

    /** 带围栏的最终写回并释放归属：epoch 不是当前值（已被新的进场夺权）时影响 0 行。 */
    @Update("""
            UPDATE player
               SET level = #{level}, scene_config_id = #{sceneConfigId},
                   pos_x = #{posX}, pos_y = #{posY}, pos_z = #{posZ}, owner_released = 1, updated_at = #{updatedAt}
             WHERE player_id = #{playerId} AND owner_epoch = #{ownerEpoch}
            """)
    int updateStateAndRelease(PlayerRow row);

    /**
     * 带围栏的在线存盘（不释放）：只有仍由这个 epoch 持有、且尚未释放时才写。
     * {@code owner_released = 0} 这一条让迟到的在线存盘永远盖不过已经提交的最终写回（最终写回会置 1）。
     */
    @Update("""
            UPDATE player
               SET level = #{level}, scene_config_id = #{sceneConfigId},
                   pos_x = #{posX}, pos_y = #{posY}, pos_z = #{posZ}, updated_at = #{updatedAt}
             WHERE player_id = #{playerId} AND owner_epoch = #{ownerEpoch} AND owner_released = 0
            """)
    int updateStateHeld(PlayerRow row);

    /**
     * 带围栏的交出（跨节点换图，归属协议第 6 步）：与 {@link #updateStateHeld} 同形地写回冻结快照，同时把 epoch 加一、保持未释放、
     * 给新租约 {@code leaseUntil}。只有仍由 {@code row.ownerEpoch} 持有、尚未释放、且剩余租约不短于安全边际
     * （{@code owner_lease_until >= requireLeaseAtLeast}）时才改，否则影响 0 行、什么也不改。
     * 与随后的 {@link #selectOwnerEpoch} / {@link #selectOwnerForUpdate} 必须在同一事务里，见 {@link PlayerStore#handOffOwnership}。
     */
    @Update("""
            UPDATE player
               SET level = #{row.level}, scene_config_id = #{row.sceneConfigId},
                   pos_x = #{row.posX}, pos_y = #{row.posY}, pos_z = #{row.posZ},
                   owner_epoch = owner_epoch + 1, owner_released = 0, owner_lease_until = #{leaseUntil},
                   updated_at = #{row.updatedAt}
             WHERE player_id = #{row.playerId} AND owner_epoch = #{row.ownerEpoch} AND owner_released = 0
               AND owner_lease_until >= #{requireLeaseAtLeast}
            """)
    int updateStateAndHandOff(@Param("row") PlayerRow row, @Param("leaseUntil") long leaseUntil,
                              @Param("requireLeaseAtLeast") long requireLeaseAtLeast);

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
