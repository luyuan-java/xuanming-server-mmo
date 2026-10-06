package com.game.match.activity;

import com.game.match.gather.GatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.ticket.TicketStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 帮会活动开战的装配：{@link ActivityBattleService} 一个 bean，Dubbo 提供方（{@link MatchInternalServiceImpl}）与 dev 管理口
 * （{@code admin.DevActivityBattleController}）共用它。跨包只经冻结的接口：{@link MemberPrecheck}、{@link TicketStore}、{@link GatherLauncher}。
 */
@Configuration(proxyBeanMethods = false)
public class ActivityConfiguration {

    @Bean
    public ActivityBattleService activityBattleService(MemberPrecheck precheck, TicketStore tickets, GatherLauncher gather, MatchIds matchIds,
                                                       MatchMetrics metrics) {
        return new ActivityBattleService(precheck, tickets, gather, matchIds, metrics);
    }
}
