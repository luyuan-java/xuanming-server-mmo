package com.game.gateway.drain;

import java.time.Duration;

/**
 * gate 排空判定（{@code xm.gateway.gate-drain.*}，同 mmorpg login GateDrain 配置）。
 *
 * @param drainedBelowPlayers 在线人数 ≤ 它就判「已排空」（缺省 0：一个人都不剩）
 * @param deadline            从打标记起等这么久后无论还剩多少人都判「可下线」（缺省 25 min；0 = 永不因超时放行，只认人走干净）
 * @param interval            判定周期（缺省 5 s）
 */
public record GateDrainSettings(Long drainedBelowPlayers, Duration deadline, Duration interval) {

    public GateDrainSettings {
        drainedBelowPlayers = drainedBelowPlayers == null ? 0L : drainedBelowPlayers;
        deadline = deadline == null ? Duration.ofMinutes(25) : deadline;
        interval = interval == null ? Duration.ofSeconds(5) : interval;
        if (drainedBelowPlayers < 0 || deadline.isNegative() || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("xm.gateway.gate-drain 的阈值不能为负、周期必须为正");
        }
    }

    public static GateDrainSettings defaults() {
        return new GateDrainSettings(null, null, null);
    }
}
