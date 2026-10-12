package com.game.match.precheck;

import com.game.match.port.PlayerStatusReader;
import com.game.match.ticket.TicketHealing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 成员预检的装配：整队开战（{@code checkTeamMatch}）与帮会活动开战共用同一个 {@link MemberPrecheck}。 */
@Configuration(proxyBeanMethods = false)
public class PrecheckConfiguration {

    @Bean
    public MemberPrecheck memberPrecheck(PlayerStatusReader players, TicketHealing healing) {
        return new DefaultMemberPrecheck(players, healing);
    }
}
