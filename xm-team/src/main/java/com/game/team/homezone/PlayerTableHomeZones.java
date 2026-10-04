package com.game.team.homezone;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles.Profile;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * {@link HomeZones} 的生产实现：home zone = {@code xm_java.player.zone_id}，即建角时 login 所在的 zone（D5；基线读 data_service 的
 * {@code player:zone} 映射，Java 没有 data_service，合服重映射等批次 7.3）。
 *
 * <p>查询预算取 {@code min(timeout, 请求剩余预算)}（基线 homezone.go:31-33、:60-61：1500 ms，请求 ctx 仍能截断它）。
 * JDBC 的查询超时以秒为单位，实际按这个预算向上取整到秒（{@code PlayerProfiles} 的口径）。
 * 查不到行或 zone_id=0 → 缺项（调用方 4019）；SQL 出错或预算用完 → {@code DependencyException}（调用方 4030）。
 */
public final class PlayerTableHomeZones implements HomeZones {

    private final BiFunction<List<Long>, Deadline, Map<Long, Profile>> loadStrict;
    private final long timeoutMillis;

    /**
     * @param loadStrict 严格读资料（生产 {@code PlayerProfiles::loadStrict}：读失败或预算用完抛 {@code DependencyException}）
     * @param timeout    单次查询预算（{@code xm.team.home-zone-timeout}，缺省 1500 ms）
     */
    public PlayerTableHomeZones(BiFunction<List<Long>, Deadline, Map<Long, Profile>> loadStrict, Duration timeout) {
        this.loadStrict = loadStrict;
        this.timeoutMillis = timeout.toMillis();
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
