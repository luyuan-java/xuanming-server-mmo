package com.game.match.placement;

import com.game.api.BattleNodeService;
import com.game.match.gather.BattleNodes;
import com.game.match.port.NodeCalls;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 落点记录的装配：存取（Redis）与按记录直拨 battle。使用者：gather（写 / 删）、补签 179（读 + 直拨）、6.5 观战（读 + 直拨）。
 */
@Configuration(proxyBeanMethods = false)
public class PlacementConfiguration {

    @Bean
    public PlacementStore placementStore(RedissonClient redis) {
        return new RedissonPlacementStore(redis);
    }

    @Bean
    public PlacementDialer placementDialer(NodeCalls<BattleNodeService> battleNodeCalls, BattleNodes battleNodes) {
        return new DirectPlacementDialer(battleNodeCalls, battleNodes);
    }
}
