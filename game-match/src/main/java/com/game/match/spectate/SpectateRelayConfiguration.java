package com.game.match.spectate;

import com.game.common.RunMode;
import com.game.common.token.SpectateSecretPolicy;
import com.game.common.token.SpectateTickets;
import com.game.discovery.RedisKeys;
import com.game.match.MatchProperties;
import com.game.match.gather.BattleNodes;
import com.game.match.placement.PlacementDialer;
import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 观战大厅档的装配（mmorpg battle-spectate-tiered-fanout.md §15.6、§15.7）：档位存储、打开 battle 中继开关的直拨、准入判定、赛事局运营入口。
 * 163 的两条路径与 164 经 {@code ObjectProvider} 取它们（没装配时等于关闭：只走直连席）。构造这些 bean 不碰 Redis、不发调用。
 *
 * <p><b>启动门禁</b>（{@link #spectateRelay}）：中继缺省打开（{@code GAME_MATCH_SPECTATE_RELAY_ENABLED}），打开就要密钥——prod 下为空、太短或与 gate / battle 令牌密钥相同拒启；dev / test 告警，
 * 空密钥按关闭处理（只走直连席）。不要大厅档的部署显式关掉开关，就不要密钥。
 */
@Configuration(proxyBeanMethods = false)
public class SpectateRelayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SpectateRelayConfiguration.class);

    static final String SECRET_ENV = "GAME_SPECTATE_TOKEN_SECRET";
    private static final String GATE_SECRET_ENV = "GAME_GATE_TOKEN_SECRET";
    private static final String BATTLE_SECRET_ENV = "GAME_BATTLE_TOKEN_SECRET";

    @Bean
    public SpectateTierStore spectateTierStore(RedissonClient redis, RedisKeys redisKeys) {
        return new RedissonSpectateTierStore(redis, redisKeys);
    }

    @Bean
    public SpectateRelay spectateRelay(MatchProperties props, RunMode matchRunMode, Environment environment, SpectateTierStore tiers, PlacementDialer dialer, BattleNodes battleNodes) {
        return relayOf(props.spectate(), matchRunMode, environment.getProperty(GATE_SECRET_ENV), environment.getProperty(BATTLE_SECRET_ENV), tiers,
                new SpectateRelayDialer(dialer), new BattleInstanceCheck(battleNodes));
    }

    /** 门禁 + 构造（纯函数，测试直接调）。 */
    static SpectateRelay relayOf(MatchProperties.Spectate config, RunMode mode, String gateSecret, String battleSecret, SpectateTierStore tiers, SpectateRelayDialer dialer,
                                 BattleInstanceCheck instances) {
        if (!config.relayEnabled()) {
            log.info("观战中继关闭：直连席满了回 16021");
            return SpectateRelay.disabled();
        }
        SpectateSecretPolicy.Verdict verdict = SpectateSecretPolicy.check(config.ticketSecret(), mode, gateSecret, battleSecret);
        if (verdict.refused()) {
            throw new IllegalStateException("拒绝启动：game.match.spectate.relay-enabled=true 但 " + verdict.describe(SECRET_ENV));
        }
        if (verdict.problem() == SpectateSecretPolicy.Problem.MISSING) {
            log.warn("观战中继按关闭处理：{}；只因运行模式不是 prod 才放行", verdict.describe(SECRET_ENV));
            return SpectateRelay.disabled();
        }
        if (verdict.problem() != SpectateSecretPolicy.Problem.NONE) {
            log.warn("安全告警：{}；只因运行模式不是 prod 才放行", verdict.describe(SECRET_ENV));
        }
        log.info("观战中继打开：直连席满了转大厅档，凭证有效期 {} s", config.ticketTtlSeconds());
        return new SpectateRelay(tiers, dialer, SpectateTickets.ofUtf8(config.ticketSecret()), Duration.ofSeconds(config.ticketTtlSeconds()), instances);
    }

    @Bean
    public SpectateFeaturedService spectateFeaturedService(SpectateStore spectateStore, SpectateTierStore tiers, SpectateRelay spectateRelay) {
        return new SpectateFeaturedService(spectateStore, tiers, spectateRelay);
    }
}
