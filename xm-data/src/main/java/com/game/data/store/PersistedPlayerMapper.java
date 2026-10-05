package com.game.data.store;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 玩家已落盘数据的<b>只读</b>访问（{@code player} 行 + {@code player_state} 原字节；两张表归 xm-player-store / xm-login，
 * xm-data 不建不写，data-ops-spec §7.1）。一条语句读完两张表：InnoDB 一条语句一个读视图，行与玩法数据出自同一时刻的已提交状态。
 */
@Mapper
public interface PersistedPlayerMapper {

    @Select("""
            SELECT p.player_id, p.zone_id, p.level, p.scene_config_id, p.pos_x, p.pos_y, p.pos_z, p.owner_epoch,
                   p.owner_released, p.owner_lease_until, p.created_at, p.updated_at,
                   s.data AS state_data, s.saved_epoch AS state_saved_epoch, s.updated_at AS state_updated_at
              FROM player p LEFT JOIN player_state s ON s.player_id = p.player_id
             WHERE p.player_id = #{player}""")
    PersistedPlayer find(@Param("player") long playerId);
}
