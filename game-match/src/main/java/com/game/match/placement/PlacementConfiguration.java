package com.game.match.placement;

import com.game.discovery.RedisKeys;
import com.game.match.gather.BattleNodes;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 落点记录的装配：存取（Redis）与按记录直拨 battle。使用者：gather（写 / 删）、补签 179（读 + 直拨）、6.5 观战（读 + 直拨）。
 *
 * <p>直拨有<b>自己的</b>直连客户端缓存（{@link PlacementClients}），不与 gather 建房 / 销毁共用（原因见那个类的注释）；
 * 它的空闲条目由基础设施装配里的 {@code NodeClientSweeper} 定时清（按 {@code IdleSweep} 类型收集）。
 */
@Configuration(proxyBeanMethods = false)
public class PlacementConfiguration {

    public PlacementStore placementStore(RedissonClient redis, RedisKeys redisKeys) {
        return new RedissonPlacementStore(redis, redisKeys);
    }
    @Bean(name="placementStore")
    public PlacementStore configuredPlacementStore(RedissonClient redis,RedisKeys keys,
            org.springframework.beans.factory.ObjectProvider<com.game.match.global.GlobalMatchStore> global){
        var projection=placementStore(redis,keys);var store=global.getIfAvailable();
        return store==null?projection:new com.game.match.global.GlobalPlacementStore(store,projection);}

    /** 直拨专用的直连客户端缓存。销毁时关闭（Spring 按依赖逆序：先停用到它的补签入口）。 */
    @Bean(destroyMethod = "close")
    public PlacementClients placementClients() {
        return new PlacementClients();
    }

    @Bean
    public PlacementDialer placementDialer(PlacementClients placementClients, BattleNodes battleNodes) {
        return new DirectPlacementDialer(placementClients.calls(), battleNodes, new TcpConnectProbe());
    }
}
