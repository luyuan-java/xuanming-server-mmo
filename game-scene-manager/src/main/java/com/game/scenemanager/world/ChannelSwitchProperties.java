package com.game.scenemanager.world;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 玩家主动切线政策；零值沿用 C++/Go 默认，负冷却/发布间隔表示关闭。 */
@ConfigurationProperties("game.scene-manager.channel-switch")
public record ChannelSwitchProperties(boolean disabled, long cooldownSeconds, long maxPlayersPerChannel,
                                      int busyPercent, long directoryRefreshSeconds) {
    public static ChannelSwitchProperties defaults() { return new ChannelSwitchProperties(false, 0, 0, 0, 0); }

    public int effectiveCooldownSeconds() {
        return (int) Math.min(86_400, cooldownSeconds == 0 ? 10 : Math.max(0, cooldownSeconds));
    }

    public long effectiveMaxPlayers(WorldChannelProperties world) {
        long configured = maxPlayersPerChannel > 0 ? maxPlayersPerChannel : world.autoscale().scaleOutPlayers();
        return Math.min(0xffff_ffffL, configured > 0 ? configured : 2000);
    }

    public int effectiveBusyPercent() { return busyPercent == 0 ? 60 : Math.clamp(busyPercent, 1, 100); }

    public Duration refreshInterval() {
        return Duration.ofSeconds(Math.min(86_400, directoryRefreshSeconds == 0 ? 2 : Math.max(0, directoryRefreshSeconds)));
    }

    /** 目录有独立节拍，TTL 覆盖三个发布周期及一次领导者竞选间隔。 */
    public Duration directoryTtl(Duration campaignInterval) { return refreshInterval().multipliedBy(3).plus(campaignInterval); }
}
