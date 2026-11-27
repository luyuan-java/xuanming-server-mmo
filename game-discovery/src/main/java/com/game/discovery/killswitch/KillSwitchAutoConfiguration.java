package com.game.discovery.killswitch;

import com.game.common.killswitch.KillSwitch;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 热关停（按方法止血阀）：只在 {@code game.killswitch.enabled=true} 的进程里装配（gate / login / scene-manager / friend / chat……
 * 这些接客的进程）。装上后本进程的 {@link KillSwitch} 也登记成全局实例，Dubbo 提供方过滤器经它判定。
 *
 * <p>{@code game.killswitch.sync-interval}（缺省 1 s）：多久全量读一次规则；{@code game.killswitch.stale-after}（缺省 60 s）：
 * 规则源失联多久后本地快照作废、整体放行。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "game.killswitch.enabled", havingValue = "true")
public class KillSwitchAutoConfiguration {

    @Bean
    public KillSwitch killSwitch(
            @Value("${game.killswitch.stale-after:60s}") Duration staleAfter,
            @Value("${game.killswitch.node-type:}") String nodeType,
            @Value("${game.killswitch.instance-id:}") String instanceId,
            @Value("${spring.application.name:}") String applicationName) {
        KillSwitch killSwitch = new KillSwitch(staleAfter.toNanos(), System::nanoTime);
        killSwitch.protectMethods(KillSwitch.DEFAULT_PROTECTED_METHODS);
        String resolvedType = (nodeType != null && !nodeType.isBlank()) ? nodeType.strip() : inferNodeType(applicationName);
        if (!resolvedType.isEmpty()) {
            killSwitch.addNodeType(resolvedType);
        }
        if (instanceId != null && !instanceId.isBlank()) {
            killSwitch.addInstance(instanceId.strip());
        }
        KillSwitch.installGlobal(killSwitch);
        return killSwitch;
    }

    /** 按 Spring 应用名推导标准 eNodeType 名（对齐主仓 proto/common/base/node.proto），便于 type/<名>/ 规则开箱即用。 */
    static String inferNodeType(String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            return "";
        }
        return switch (applicationName.strip()) {
            case "game-gate" -> "GateNodeService";
            case "game-scene" -> "SceneNodeService";
            case "game-login" -> "LoginNodeService";
            case "game-battle" -> "BattleNodeService";
            case "game-match" -> "MatchNodeService";
            case "game-trade" -> "TradeNodeService";
            case "game-guild" -> "GuildNodeService";
            case "game-friend" -> "FriendNodeService";
            case "game-chat" -> "ChatNodeService";
            case "game-team" -> "TeamNodeService";
            case "game-mail" -> "MailNodeService";
            case "game-rank" -> "RankNodeService";
            case "game-payment" -> "PaymentNodeService";
            case "game-data" -> "DataNodeService";
            case "game-scene-manager" -> "SceneManagerNodeService";
            case "game-gateway" -> "GatewayNodeService";
            default -> "";
        };
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public RedisKillSwitchSync redisKillSwitchSync(RedissonClient redis, RedisKeys keys, KillSwitch killSwitch,
                                                   @Value("${game.killswitch.sync-interval:1s}") Duration interval) {
        return new RedisKillSwitchSync(redis, keys, killSwitch, interval);
    }
}
