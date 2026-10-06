package com.game.match.matcher;

import com.game.match.MatchInstance;
import com.game.match.MatchProperties;
import com.game.match.gather.BattleNodes;
import com.game.match.gather.GatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerStatusReader;
import com.game.match.ticket.TicketStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 凑单的装配：把 {@link QueueMatcher} 接到 {@code match-matcher} 调度线程上（{@link MatcherRunner}，随上下文启停）。
 *
 * <p>凑单用到的三个协作件由别的包提供：票据存储（ticket 包）、开局管线与 battle 节点目录（gather 包）。这里经 {@link ObjectProvider} 取——
 * 在创建本 bean 时才解析，那时全部 bean 定义都已登记，不受配置类扫描先后的影响。三者齐全就正常接线；<b>缺任何一个</b>都给出没接上线的占位实例
 * （启动时打 ERROR、不起线程），只为让并行开发阶段「还没有票据存储 / 开局管线」的进程骨架照样起得来。整模块装配完成后三者必然都在；
 * 那之后应把这三个参数改成直接注入（缺了就拒启），或在启动检查里断言 {@link MatcherRunner#wired()}。
 */
@Configuration(proxyBeanMethods = false)
public class MatcherConfiguration {

    /** 停机时在凑单锁 TTL 之外多等的余量：一条队列的操作以锁 TTL 为界，再留出放锁与收尾的时间。 */
    static final Duration STOP_MARGIN = Duration.ofSeconds(5);

    @Bean
    public MatcherRunner matcherRunner(MatchProperties props, MatchInstance instance, MatchIds ids, MatchMetrics metrics, MetricLabels labels,
                                       PlayerStatusReader players, ObjectProvider<TicketStore> ticketStore,
                                       ObjectProvider<GatherLauncher> gatherLauncher, ObjectProvider<BattleNodes> battleNodes) {
        TicketStore store = ticketStore.getIfAvailable();
        GatherLauncher gather = gatherLauncher.getIfAvailable();
        BattleNodes nodes = battleNodes.getIfAvailable();
        List<String> missing = new ArrayList<>();
        if (store == null) {
            missing.add(TicketStore.class.getSimpleName());
        }
        if (gather == null) {
            missing.add(GatherLauncher.class.getSimpleName());
        }
        if (nodes == null) {
            missing.add(BattleNodes.class.getSimpleName());
        }
        if (!missing.isEmpty()) {
            return MatcherRunner.notWired(missing);
        }
        QueueMatcher matcher = new QueueMatcher(store, players, gather, nodes, ids, metrics, labels, props, instance);
        return new MatcherRunner(matcher::runRound, props.matcher().interval(), props.matcher().lockTtl().plus(STOP_MARGIN), metrics);
    }
}
