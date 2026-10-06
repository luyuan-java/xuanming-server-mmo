package com.game.battle.port.scene;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * battle → scene 传输的配置（{@code xm.battle.*} 的一部分；scene-battle-spec §8）。与 {@code BattleProperties} 共用前缀、分开绑定
 * （那边是 6.2 的节点配置，按位置构造的地方多，不动它）。
 *
 * @param sceneRpcTimeout    确认 / 结算调用 {@code SceneBattleService} 的超时（缺省 5 s）：必须大于 scene 侧一条 Redis 脚本的最坏耗时
 *                           （{@code RedisProperties.worstCaseCommandMillis()}，缺省 4.2 s），启动时校验、不满足拒启（§7.3、§10.4）
 * @param outboxDrainTimeout 停机时等在途的落库与首投回来的上限（缺省 3 s，D25）
 */
@ConfigurationProperties("xm.battle")
public record SceneTransportProperties(Duration sceneRpcTimeout, Duration outboxDrainTimeout) {

    public static final Duration DEFAULT_SCENE_RPC_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_OUTBOX_DRAIN_TIMEOUT = Duration.ofSeconds(3);

    public SceneTransportProperties {
        if (sceneRpcTimeout == null) {
            sceneRpcTimeout = DEFAULT_SCENE_RPC_TIMEOUT;
        } else if (sceneRpcTimeout.isNegative() || sceneRpcTimeout.isZero() || sceneRpcTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("xm.battle.scene-rpc-timeout 必须为正: " + sceneRpcTimeout);
        }
        if (outboxDrainTimeout == null) {
            outboxDrainTimeout = DEFAULT_OUTBOX_DRAIN_TIMEOUT;
        } else if (outboxDrainTimeout.isNegative()) {
            throw new IllegalArgumentException("xm.battle.outbox-drain-timeout 不能为负: " + outboxDrainTimeout);
        }
    }

    /** 全部取缺省值（测试用）。 */
    public static SceneTransportProperties defaults() {
        return new SceneTransportProperties(null, null);
    }

    /**
     * 调用方超时必须大于 scene 侧一条脚本的最坏耗时（否则 scene 已给出确定结论、调用方先按「结局未知」处理）。
     *
     * @throws IllegalStateException 不满足
     */
    public void requireAbove(long redisWorstCaseMillis) {
        if (sceneRpcTimeout.toMillis() <= redisWorstCaseMillis) {
            throw new IllegalStateException("xm.battle.scene-rpc-timeout = " + sceneRpcTimeout + " 不大于 Redis 单条命令最坏耗时 "
                    + redisWorstCaseMillis + " ms（scene-battle-spec §7.3、§10.4）");
        }
    }
}
