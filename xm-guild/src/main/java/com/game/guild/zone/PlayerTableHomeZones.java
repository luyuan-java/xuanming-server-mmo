package com.game.guild.zone;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.guild.rules.GuildLimits;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * {@link HomeZones} 的生产实现：归属区 = {@code xm_java.player.zone_id}，即建角时 login 所在的 zone（D3；基线读 data_service 的
 * {@code GetPlayerHomeZone}，Java 没有 data_service，同 team D5）。
 *
 * <p>查询预算取 {@code min(1500 ms, 请求剩余预算)}（基线 home_zone.go:28 单次 1500 ms，请求 ctx 仍能截断它）。JDBC 查询超时以秒为单位，
 * 实际按这个预算向上取整到秒（{@code PlayerProfiles} 的口径）。查不到行或 zone_id = 0 → 缺项；SQL 出错或预算用完 →
 * {@code DependencyException}。
 */
public final class PlayerTableHomeZones implements HomeZones {

    private final BiFunction<List<Long>, Deadline, Map<Long, Profile>> loadStrict;
    private final long timeoutMillis;

    /**
     * @param loadStrict 严格读资料（生产 {@code PlayerProfiles::loadStrict}：读失败或预算用完抛 {@code DependencyException}）
     */
    public PlayerTableHomeZones(BiFunction<List<Long>, Deadline, Map<Long, Profile>> loadStrict) {
        this(loadStrict, GuildLimits.HOME_ZONE_LOOKUP_TIMEOUT_MS);
    }

    PlayerTableHomeZones(BiFunction<List<Long>, Deadline, Map<Long, Profile>> loadStrict, long timeoutMillis) {
        this.loadStrict = loadStrict;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public Map<Long, Integer> homeZones(List<Long> playerIds, Deadline deadline) {
        Deadline lookup = Deadline.after(Math.min(timeoutMillis, deadline.remainingMillis()));
        Map<Long, Integer> zones = new HashMap<>();
        for (Profile p : loadStrict.apply(playerIds, lookup).values()) {
            if (p.zoneId() != 0) {
                zones.put(p.playerId(), p.zoneId());
            }
        }
        return zones;
    }
}
