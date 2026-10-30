package com.game.common.player;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles.Profile;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * {@link HomeZones} 的生产实现：归属区 = {@code game.player.zone_id}，即建角时会话所在的 zone（team D5、guild D3、trade T3；
 * 基线读 data_service 的 {@code player:zone} 映射，合服重映射等批次 7.3）。原先 game-team 与 game-guild 各有一份同形的
 * {@code PlayerTableHomeZones}，聚宝斋是第三个用户，按 trade-spec Q6 上移到这里，行为不变。
 *
 * <p>查询预算取 {@code min(单次超时, 请求剩余预算)}：单次超时缺省 {@link #DEFAULT_TIMEOUT}（基线三处都是 1500 ms：
 * homezone.go:31-33、guild home_zone.go:28、trade home_zone.go:77-79 / constants.go:71），请求预算仍能截断它。
 * JDBC 的查询超时以秒为单位，实际按这个预算向上取整到秒（{@link PlayerProfiles} 的口径）。
 * 查不到行或 zone_id = 0 → 缺项；SQL 出错或预算用完 → {@code DependencyException}（{@link PlayerProfiles#loadStrict} 抛出，原样透传）。
 */
public final class PlayerHomeZones implements HomeZones {

    /** 单次查询的缺省上限（基线 HomeZoneLookupTimeout = 1500 ms）。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(1_500);

    private final BiFunction<List<Long>, Deadline, Map<Long, Profile>> loadStrict;
    private final long timeoutMillis;
    private final javax.sql.DataSource authoritySource;
    /** 生产SQL读侧：meta/fence/P同事务读取唯一player.zone_id。 */
    public PlayerHomeZones(javax.sql.DataSource source,Duration timeout){
        if(timeout.isNegative()||timeout.isZero())throw new IllegalArgumentException("归属区查询预算必须为正");
        authoritySource=java.util.Objects.requireNonNull(source);loadStrict=null;timeoutMillis=timeout.toMillis();
    }
    public PlayerHomeZones(javax.sql.DataSource source){this(source,DEFAULT_TIMEOUT);}

    /**
     * 单次超时取 {@link #DEFAULT_TIMEOUT}。
     *
     * @param loadStrict 严格读资料（生产 {@code PlayerProfiles::loadStrict}：读失败或预算用完抛 {@code DependencyException}）
     */
    public PlayerHomeZones(BiFunction<List<Long>, Deadline, Map<Long, Profile>> loadStrict) {
        this(loadStrict, DEFAULT_TIMEOUT);
    }

    /**
     * @param loadStrict 严格读资料（同上）
     * @param timeout    单次查询上限（组队可配 {@code game.team.home-zone-timeout}；帮会 / 聚宝斋是代码常量 1500 ms），必须为正
     */
    public PlayerHomeZones(BiFunction<List<Long>, Deadline, Map<Long, Profile>> loadStrict, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("归属区查询的单次超时必须为正: " + timeout);
        }
        this.loadStrict = loadStrict;
        this.timeoutMillis = timeout.toMillis();
        authoritySource=null;
    }

    @Override
    public Map<Long, Integer> homeZones(List<Long> playerIds, Deadline deadline) {
        Deadline lookup = Deadline.after(Math.min(timeoutMillis, deadline.remainingMillis()));
        if(authoritySource!=null)return authoritative(playerIds,lookup);
        Map<Long, Integer> zones = new HashMap<>();
        for (Profile p : loadStrict.apply(playerIds, lookup).values()) {
            if (p.zoneId() != 0) {
                zones.put(p.playerId(), p.zoneId());
            }
        }
        return zones;
    }
    private Map<Long,Integer> authoritative(List<Long> players,Deadline budget){
        if(players.isEmpty())return Map.of();
        try(var connection=authoritySource.getConnection()){
            int network=connection.getNetworkTimeout(),isolation=connection.getTransactionIsolation();boolean auto=connection.getAutoCommit();
            if(budget.expired())throw new java.sql.SQLException("home查询预算已经耗尽");
            connection.setNetworkTimeout(Runnable::run,(int)Math.max(1,Math.min(3000,budget.remainingMillis())));
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);connection.setAutoCommit(false);
            try{
                int timeout=(int)Math.max(1,Math.min(3,(budget.remainingMillis()+999)/1000));
                Map<Long,Integer> zones=PlayerRouteAuthority.readCurrentHomes(connection,players,timeout);
                if(budget.expired())throw new java.sql.SQLException("home查询预算已经耗尽");connection.commit();return Map.copyOf(zones);
            }catch(java.sql.SQLException|RuntimeException failure){try{connection.rollback();}catch(java.sql.SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
            finally{if(!connection.isClosed()){connection.setAutoCommit(auto);connection.setTransactionIsolation(isolation);connection.setNetworkTimeout(Runnable::run,network);}}
        }catch(java.sql.SQLException failure){throw new com.game.common.deadline.Deadline.DependencyException("SQL归属原件或维护围栏不可用",failure);}
    }
}
