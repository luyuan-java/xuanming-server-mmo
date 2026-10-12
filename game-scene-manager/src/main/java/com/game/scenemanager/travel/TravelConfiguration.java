package com.game.scenemanager.travel;

import com.game.common.token.GateTokenIssuer;
import com.game.common.token.GateTokens;
import com.game.discovery.gate.GateSource;
import com.game.discovery.gate.RedisGateSource;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.WorldSceneConfigs;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 跨 zone 选路的装配（批次 5.4，zone-travel-spec §5.4）：gate 目录（叠加排空标记）+ 频道计划 + scene 节点目录 + 合服围栏 + 票据签发 →
 * {@link TravelRouter}。
 *
 * <p><b>gate 令牌密钥必填</b>：scene-manager 给跨 zone 传送（226）与登录期重定向（GO-5）签重定向票据，用的就是 gateway 签登录令牌、
 * gate 验票的那一把（{@value #TOKEN_SECRET_ENV}）。取法与 game-gate / game-gateway 逐字节相同：经 {@link Environment} 读同名键，
 * 缺失或全是空白即拒绝启动；有值时<b>原值</b>按 UTF-8 作密钥（{@link GateTokens#ofUtf8}），<b>不去首尾空白</b>——三个进程任何一边多做一次
 * trim，带空白的密钥就会让 scene-manager 签的票据在目标 gate 上验不过，而验票失败发生在源端已经交出并释放归属之后。
 * 密钥只从环境变量注入，不进仓库（AGENTS.md §3）。
 */
@Configuration(proxyBeanMethods = false)
public class TravelConfiguration {

    /** 环境变量名：与 game-gateway（签登录令牌）、game-gate（验票）同一个键、同一个值。 */
    static final String TOKEN_SECRET_ENV = "GAME_GATE_TOKEN_SECRET";

    /** gate 目录的读取面（game-gate 发布的 {@code game:nodes:gate:{zone}}，叠加运维排空标记 {@code game:gate-draining:*}）。 */
    @Bean
    public GateSource gateSource(RedissonClient redis) {
        return new RedisGateSource(redis);
    }

    /** 合服围栏的读侧（检查点 F4）：批次 7.3 之前没有合服，恒放行；7.3 换成读 Redis 的实现，不动 {@link TravelRouter}。 */
    @Bean
    public ZoneMergeFence zoneMergeFence() {
        return ZoneMergeFence.OPEN;
    }

    /** 重定向票据的签发器：缺 gate 令牌密钥即拒绝启动（见类注释）。 */
    @Bean
    public GateTokenIssuer gateTokenIssuer(Environment environment) {
        String secret = environment.getProperty(TOKEN_SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + TOKEN_SECRET_ENV
                    + "（gate 令牌密钥，scene-manager 用它签跨 zone 重定向票据，须与 game-gateway、game-gate 一致）");
        }
        return new GateTokenIssuer(GateTokens.ofUtf8(secret), Clock.systemUTC(), new SecureRandom());
    }

    @Bean
    public TravelRouting travelRouting(WorldChannelStore worldChannelStore, SceneNodeSource sceneNodeSource, GateSource gateSource,
                                       WorldSceneConfigs worldSceneConfigs, ZoneMergeFence zoneMergeFence,
                                       GateTokenIssuer gateTokenIssuer, MeterRegistry meterRegistry) {
        return new TravelRouter(worldChannelStore, sceneNodeSource, gateSource, worldSceneConfigs, zoneMergeFence,
                gateTokenIssuer, meterRegistry);
    }
}
