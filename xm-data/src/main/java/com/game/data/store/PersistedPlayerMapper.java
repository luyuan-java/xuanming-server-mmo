package com.game.data.store;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 玩家已落盘数据的<b>只读</b>访问（{@code player} 行 + {@code player_state} 原字节；两张表归 xm-player-store / xm-login，
 * xm-data 不建表，data-ops-spec §7.1）。一条语句读完两张表：InnoDB 一条语句一个读视图，行与玩法数据出自同一时刻的已提交状态。
 * xm-data 的写（回档覆盖写、夺权 / 续约 / 释放）一律经 xm-player-store 的 {@code PlayerStore}（带 epoch 围栏），这里不写。
 */
@Mapper
public interface PersistedPlayerMapper {

    /**
     * 区号是 uint32（{@code INT UNSIGNED}），Java 按位放在 int 里：≥ 2^31 的区号是负数，直接绑定会被当成负数发给数据库、
     * 一行也查不到，读回时也会越界。绑定与读取都经 {@link UnsignedIntTypeHandler}（同 {@link TransactionLogMapper}）。
     */
    String U32 = "typeHandler=com.game.data.store.UnsignedIntTypeHandler";

    @Results(id = "persistedPlayer", value = {
            @Result(column = "zone_id", property = "zoneId", typeHandler = UnsignedIntTypeHandler.class)})
    @Select("""
            SELECT p.player_id, p.zone_id, p.level, p.scene_config_id, p.pos_x, p.pos_y, p.pos_z, p.owner_epoch,
                   p.owner_released, p.owner_lease_until, p.created_at, p.updated_at,
                   s.data AS state_data, s.saved_epoch AS state_saved_epoch, s.updated_at AS state_updated_at
              FROM player p LEFT JOIN player_state s ON s.player_id = p.player_id
             WHERE p.player_id = #{player}""")
    PersistedPlayer find(@Param("player") long playerId);

    /** 一名玩家的归属区与建角时刻（不取玩法数据）；不存在为 null。 */
    @Results(id = "playerBrief", value = {
            @Result(column = "zone_id", property = "zoneId", typeHandler = UnsignedIntTypeHandler.class)})
    @Select("SELECT player_id, zone_id, created_at FROM player WHERE player_id = #{player}")
    PlayerBrief findBrief(@Param("player") long playerId);

    /**
     * 归属区为 {@code zone} 的玩家，按玩家号升序、从 {@code after}（不含）之后取 {@code limit} 个（整区回档 / 维护前快照的目标，
     * 走 {@code idx_player_zone}：二级索引自带主键，按玩家号续翻不用排序）。
     */
    @ResultMap("playerBrief")
    @Select("SELECT player_id, zone_id, created_at FROM player WHERE zone_id = #{zone," + U32 + "} AND player_id > #{after}"
            + " ORDER BY player_id LIMIT #{limit}")
    List<PlayerBrief> listInZone(@Param("zone") int zoneId, @Param("after") long afterPlayer, @Param("limit") int limit);

    /** 这些区的玩家总数（整区回档的规模上限在受理时就判，超出 422 plan_too_large）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM player WHERE zone_id IN
            <foreach collection="zones" item="z" open="(" separator="," close=")">#{z,typeHandler=com.game.data.store.UnsignedIntTypeHandler}</foreach>
            </script>""")
    long countInZones(@Param("zones") Collection<Integer> zones);
}
