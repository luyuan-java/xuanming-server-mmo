package com.game.match.spectate;

import com.game.discovery.RedisKeys;
import com.game.discovery.RedisProperties;
import com.game.match.port.RedisClientWrites;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 观战存储的装配（批次 6.5，工作包 W1）：{@link SpectateStore} 的生产实现 {@link RedissonSpectateStore}——观战标记、可观战索引与落点记录的原子读，
 * 每个操作一段 Lua（{@link SpectateScripts}）。使用者：163（{@code WatchBattleConfiguration}）、164 / 开局钩子 / 清扫器（{@code WatchableConfiguration}）。
 *
 * <p>构造时不碰 Redis（用到才发命令）：进程启动、上下文测试都不依赖 Redis 此刻可达；Redis 不可用时每个同步方法抛依赖异常，由各调用方按自己的口径收场。
 * 不带条件装配（理由见 {@code MatchConfiguration} 的类注释）：测试里要换存储就把替身标 {@code @Primary}，或直接 new 被测对象
 * （内存替身 {@code testing.InMemorySpectateStore}）。
 */
@Configuration(proxyBeanMethods = false)
public class SpectateStoreConfiguration {

    /**
     * 观战存储。索引键恒为注入的 {@code redisKeys.matchWatchable()}；抢标记（163 的提交点）的 Redisson 超时以配置的
     * {@code game.redis.timeout-ms} 封顶（{@link RedisClientWrites}）。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="game.match.global.enabled",havingValue="false",matchIfMissing=true)
    public SpectateStore spectateStore(RedissonClient redis, RedisKeys redisKeys, RedisProperties redisProperties) {
        return new RedissonSpectateStore(redis, redisKeys, RedisClientWrites.commandTimeout(redisProperties));
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="game.match.global.enabled",havingValue="true")
    public com.game.match.global.GlobalWatchAuthority globalWatchAuthority(javax.sql.DataSource source,com.game.match.global.GlobalMatchStore schema){
        return new com.game.match.global.GlobalWatchAuthority(source,schema);
    }
    @Bean(initMethod="start",destroyMethod="close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="game.match.global.enabled",havingValue="true")
    public SqlSpectateStore sqlSpectateStore(com.game.match.global.GlobalWatchAuthority authority,com.game.match.placement.PlacementDialer dialer){return new SqlSpectateStore(authority,dialer);}
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="game.match.global.enabled",havingValue="true")
    public GlobalWatchService globalWatchService(SqlSpectateStore store,com.game.match.port.PlayerStatusReader players,ObserverDialer observers,javax.sql.DataSource source){
        com.game.common.player.PlayerProfiles.ConnectionSource connections=source instanceof com.alibaba.druid.pool.DruidDataSource pool?pool::getConnection:wait->source.getConnection();
        var profiles=new com.game.common.player.PlayerProfiles(connections,1);
        return new GlobalWatchService(store,players,observers,(player,deadline)->{var profile=profiles.load(java.util.List.of(player),deadline).get(player);return profile==null?"":profile.name();});
    }
}
