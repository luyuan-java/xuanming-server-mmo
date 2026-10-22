package com.game.match.challenge;

import com.game.contract.MessageIdRegistry;
import com.game.discovery.RedisKeys;
import com.game.discovery.RedisProperties;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.gather.GatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.port.PlayerPusher;
import com.game.match.port.PlayerStatusReader;
import com.game.match.port.RedisClientWrites;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 切磋的装配（match-spec §6）：存储、{@code match-push} 执行器、服务，以及 152 / 151 两个处理器 bean（派发器按 {@link MatchMethodHandler} 收集）。
 * 跨包只经冻结的接口：{@link GatherLauncher}（gather 包）、{@link PlayerStatusReader} / {@link PlayerPusher}（基础设施装配）。
 */
@Configuration(proxyBeanMethods = false)
public class ChallengeConfiguration {

    /** 切磋推送回调的执行器；销毁在 {@link ChallengeService} 之后（Spring 按依赖逆序）。 */
    @Bean(destroyMethod = "close")
    public MatchPushExecutor matchPushExecutor() {
        return new MatchPushExecutor();
    }

    /** 发起 / 消费（152 / 151 的提交点）的 Redisson 超时以配置的 {@code game.redis.timeout-ms} 封顶（{@link RedisClientWrites}）。 */
    @Bean
    public ChallengeStore challengeStore(RedissonClient redis, RedisKeys redisKeys, RedisProperties redisProperties) {
        return new RedissonChallengeStore(redis, redisKeys, RedisClientWrites.commandTimeout(redisProperties));
    }

    public ChallengeService challengeService(PlayerStatusReader players, ChallengeStore challengeStore, MatchIds matchIds, PlayerPusher pusher,
                                             GatherLauncher gather, MatchMetrics metrics, MatchPushExecutor matchPushExecutor,
                                             MatchProperties props, MessageIdRegistry registry) {
        return new ChallengeService(players, challengeStore, matchIds, pusher, gather, metrics, matchPushExecutor,
                props.challengeTtl().toMillis(),
                registry.requireId(MatchMethods.SERVICE, MatchMethods.NOTIFY_CHALLENGE_INVITE),
                registry.requireId(MatchMethods.SERVICE, MatchMethods.NOTIFY_CHALLENGE_RESULT));
    }
    @Bean(name="challengeService")
    public ChallengeService configuredChallengeService(PlayerStatusReader players,ChallengeStore store,MatchIds ids,PlayerPusher pusher,GatherLauncher gather,
            MatchMetrics metrics,MatchPushExecutor executor,MatchProperties props,MessageIdRegistry registry,
            org.springframework.beans.factory.ObjectProvider<com.game.match.global.GlobalMatchService> global,javax.sql.DataSource dataSource){
        com.game.common.player.PlayerProfiles.ConnectionSource connections=dataSource instanceof com.alibaba.druid.pool.DruidDataSource pool?pool::getConnection:wait->dataSource.getConnection();
        var profiles=new com.game.common.player.PlayerProfiles(connections,1);
        var service=challengeService(players,store,ids,pusher,gather,metrics,executor,props,registry);
        // 显式开发/测试装配可以没有全球服务；生产仍由GlobalMatchConfiguration的SQL/TiDB门禁拒绝缺失。
        global.ifAvailable(service::withGlobal);
        return service.withPublicNames((player,deadline)->{
            var profile=profiles.load(java.util.List.of(player),deadline).get(player);return profile==null?"":profile.name();
        });
    }

    /** 152 ChallengePlayer。 */
    @Bean
    public MatchMethodHandler challengePlayerHandler(ChallengeService challengeService, MatchMetrics metrics) {
        return ChallengeHandlers.challengePlayer(challengeService, metrics);
    }

    /** 151 RespondChallenge。 */
    @Bean
    public MatchMethodHandler respondChallengeHandler(ChallengeService challengeService, MatchMetrics metrics) {
        return ChallengeHandlers.respondChallenge(challengeService, metrics);
    }
}
