package com.game.team;

import com.game.api.DubboGroups;
import com.game.api.MatchTeamService;
import com.game.team.match.MatchTeamBattle;
import com.game.team.match.TeamBattlePort;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * xm-team 调 xm-match 的 Dubbo 引用（整队开战，match-spec §7.5、§7.6）。与 {@link TeamConfiguration} 分开：只装配业务的测试
 * 换上 {@link TeamBattlePort} 的替身即可，不起 Dubbo（先例 xm-gateway 的 {@code GatewayDubboConfiguration}）。
 */
@Configuration(proxyBeanMethods = false)
public class TeamDubboConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TeamDubboConfiguration.class);

    /**
     * xm-match 的整队开战接口（Dubbo group = {@code match}）。默认直连 {@code xm.dubbo.match-url}，nacos profile 该值置空走注册中心。
     * <ul>
     *   <li><b>不重试</b>（{@code retries = 0}）：Dubbo 缺省的 failover 会在超时后重发建票与 gather（{@code MatchTeamService} 的契约）；</li>
     *   <li>{@code check = false}：xm-match 不在时不影响 xm-team 启动，只是 211 回 4030；</li>
     *   <li>引用上的 {@code timeout} 只是兜底：每次调用都带调用级超时——前三个方法取 min(3 s, 剩余请求预算)，
     *       {@code runTeamGather} 取开战锁时长（见 {@link MatchTeamBattle}）。</li>
     * </ul>
     */
    @Bean
    @DubboReference(group = DubboGroups.MATCH, check = false, url = "${xm.dubbo.match-url:}", retries = 0,
            timeout = (int) MatchTeamBattle.HOP_TIMEOUT_MS)
    public ReferenceBean<MatchTeamService> matchTeamService() {
        return new ReferenceBean<>();
    }

    /** 整队开战的票据域端口：把 {@link MatchTeamService} 的应答翻译成 team 自己的词汇（team 段 tip 只归 xm-team）。 */
    @Bean
    public TeamBattlePort teamBattlePort(MatchTeamService matchTeamService, @Value("${xm.dubbo.match-url:}") String matchUrl) {
        log.info("整队开战端口：xm-match {}", matchUrl.isBlank() ? "经注册中心发现" : "直连 " + matchUrl);
        return new MatchTeamBattle(matchTeamService);
    }
}
