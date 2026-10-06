package com.game.match.challenge;

import com.game.contract.MessageIdRegistry;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.gather.GatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.port.PlayerPusher;
import com.game.match.port.PlayerStatusReader;
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

    @Bean
    public ChallengeStore challengeStore(RedissonClient redis) {
        return new RedissonChallengeStore(redis);
    }

    @Bean
    public ChallengeService challengeService(PlayerStatusReader players, ChallengeStore challengeStore, MatchIds matchIds, PlayerPusher pusher,
                                             GatherLauncher gather, MatchMetrics metrics, MatchPushExecutor matchPushExecutor,
                                             MatchProperties props, MessageIdRegistry registry) {
        return new ChallengeService(players, challengeStore, matchIds, pusher, gather, metrics, matchPushExecutor,
                props.challengeTtl().toMillis(),
                registry.requireId(MatchMethods.SERVICE, MatchMethods.NOTIFY_CHALLENGE_INVITE),
                registry.requireId(MatchMethods.SERVICE, MatchMethods.NOTIFY_CHALLENGE_RESULT));
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
