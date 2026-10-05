package com.game.data;

import com.game.player.store.PlayerStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * xm-data 进程入口：消费审计 topic（资产流水、玩家快照）落 MySQL，管理端口上提供 actuator 与带令牌的运维接口（查询、运维快照、
 * 快照差异、回收 dry-run……）。无状态，可多实例（同一消费组分摊分区，落库按主键幂等）。
 *
 * <p>引入 xm-player-store 只为读玩家数据（PlayerState 与已落盘账本规则）：批次 7.2a 不写玩家数据，所以排除它的自动装配
 * （不带进 PlayerStore 与它的 MapperScan）；回档 / 离线编辑（7.2b / 7.2c）接入归属协议时再由 xm-data 自己定义 PlayerStore（data-ops-spec §7.5）。
 */
@SpringBootApplication(exclude = PlayerStoreAutoConfiguration.class)
public class DataApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataApplication.class, args);
    }
}
