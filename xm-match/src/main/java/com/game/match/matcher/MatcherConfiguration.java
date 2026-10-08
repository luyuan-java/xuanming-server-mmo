package com.game.match.matcher;

import com.game.match.MatchInstance;
import com.game.match.MatchProperties;
import com.game.match.gather.BattleNodes;
import com.game.match.gather.GatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.lifecycle.MatcherControl;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerStatusReader;
import com.game.match.ticket.TicketStore;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 凑单的装配：把 {@link QueueMatcher} 接到 {@code match-matcher} 调度线程上（{@link MatcherRunner}），并以进程的凑单启停口
 * {@link MatcherControl} 的身份交给 {@code MatchLifecycle}——<b>这个 bean 自己不启停</b>（没有 init / destroy 方法、不是 {@code Lifecycle}）：
 * 启动第 8 步（Dubbo 导出之后）与停机第 1 步（撤导出之前）都由 {@code MatchLifecycle} 调。
 *
 * <p>凑单用到的三个协作件由别的包提供：票据存储（ticket 包）、开局管线与 battle 节点目录（gather 包）。三者都是<b>硬依赖</b>：缺任何一个上下文
 * 起不来（拒绝启动）——凑单没接上线的进程会照收排队、永不成局，不如不起。
 */
@Configuration(proxyBeanMethods = false)
public class MatcherConfiguration {

    /** 停机时在凑单锁 TTL 之外多等的余量：一条队列的操作以锁 TTL 为界，再留出放锁与收尾的时间。 */
    static final Duration STOP_MARGIN = Duration.ofSeconds(5);

    @Bean
    public MatcherRunner matcherRunner(MatchProperties props, MatchInstance instance, MatchIds ids, MatchMetrics metrics, MetricLabels labels,
                                       PlayerStatusReader players, TicketStore ticketStore, GatherLauncher gatherLauncher,
                                       BattleNodes battleNodes) {
        QueueMatcher matcher = new QueueMatcher(ticketStore, players, gatherLauncher, battleNodes, ids, metrics, labels, props, instance);
        return new MatcherRunner(matcher::runRound, props.matcher().interval(), props.matcher().lockTtl().plus(STOP_MARGIN), metrics);
    }
}
