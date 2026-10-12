package com.game.match.reissue;

import com.game.match.metrics.MatchMetrics;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 补签 179 的装配：判定逻辑 {@link BattleTicketReissue} 与它的入口处理器（声明成 bean 即被派发器收集）。
 * 落点记录与直拨由 placement 包提供（{@link PlacementStore} / {@link PlacementDialer}）。
 */
@Configuration(proxyBeanMethods = false)
public class ReissueConfiguration {

    @Bean
    public BattleTicketReissue battleTicketReissue(PlacementStore placements, PlacementDialer dialer, MatchMetrics metrics) {
        return new BattleTicketReissue(placements, dialer, metrics);
    }

    @Bean
    public ReissueHandler reissueHandler(BattleTicketReissue battleTicketReissue) {
        return new ReissueHandler(battleTicketReissue);
    }
}
