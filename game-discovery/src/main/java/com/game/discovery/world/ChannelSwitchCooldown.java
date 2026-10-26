package com.game.discovery.world;

import java.time.Duration;

/** 全服玩家主动切线冷却。预检只读，场景确认成功后写；调用方负责离开逻辑/IO线程。 */
public interface ChannelSwitchCooldown {
    boolean active(long playerId);
    void start(long playerId, Duration ttl);
}
