package com.game.data;

import com.game.player.store.PlayerStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * xm-data 进程入口：消费审计 topic（资产流水、玩家快照）落 MySQL，管理端口上提供 actuator 与带令牌的运维接口（查询、运维快照、
 * 快照差异、回收 dry-run……）。无状态，可多实例（同一消费组分摊分区，落库按主键幂等）。
 *
 * <p>xm-player-store 的自动装配被排除：xm-data 自己定义 {@code PlayerStore}（严格递增时钟，data-ops-spec §7.5）并在
 * {@code DataConfiguration} 里显式扫描 {@code PlayerMapper}（批次 7.2b 起回档经归属协议写玩家数据）。
 */
@SpringBootApplication(exclude = PlayerStoreAutoConfiguration.class)
public class DataApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataApplication.class, args);
    }
}
