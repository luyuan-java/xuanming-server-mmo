package com.game.match.dispatch;

import com.game.contract.MessageIdRegistry;
import com.game.match.MatchProperties;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 客户端入口派发的装配：{@code match-worker} 工作池，以及收集上下文里全部 {@link MatchMethodHandler} bean 建成的派发表
 * （一个处理器都没有也能启动——那时每个号都回信封 1003）。
 */
@Configuration(proxyBeanMethods = false)
public class MatchDispatchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MatchDispatchConfiguration.class);

    /** 停机时等工作池排空的上限（之后中断剩余任务）。 */
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

    /**
     * {@code match-worker}：客户端请求、整队开战端口的前三个方法、活动开战都投到它上面（各包注入 {@link MatchWorkers}，不依赖这个具体类）。
     * 以 {@link MatchIds} 为参数只为钉住销毁顺序：Spring 按依赖逆序销毁，工作池先排空（排空中的请求还可能发号、读写 Redis），之后才还租约。
     * 正常停机时它在 {@code MatchLifecycle} 里就已经排空过一次（Dubbo 撤导出之后、等在途 gather 之前），这里的 {@code close} 是幂等的兜底。
     */
    @Bean(destroyMethod = "close")
    public MatchWorkerPool matchWorkerPool(MatchProperties props, MatchIds matchIds) {
        return new MatchWorkerPool(props.worker().threads(), props.worker().queue(), DRAIN_TIMEOUT);
    }

    /** 派发器。租约丢失的信号取 {@link MatchIds#leaseLost()}：为真时 157 / 152 当场拒收（lead 裁决 2）。 */
    @Bean
    public MatchDispatcher matchDispatcher(MessageIdRegistry registry, ObjectProvider<MatchMethodHandler> handlers, MatchWorkers workers,
                                           MatchMetrics metrics, MatchProperties props, MatchIds matchIds) {
        List<MatchMethodHandler> all = handlers.orderedStream().toList();
        MatchDispatcher dispatcher = new MatchDispatcher(registry, all, workers, metrics, props.requestBudget().toMillis(), matchIds::leaseLost);
        log.info("match 已登记处理器的消息号={}（契约 {} 共 {} 个方法） 工作线程={} 队列={} 请求预算={}", dispatcher.handledMessageIds(),
                MatchMethods.SERVICE, MatchMethods.ALL.size(), props.worker().threads(), props.worker().queue(), props.requestBudget());
        Set<String> unhandled = dispatcher.unhandledMethods();
        if (!unhandled.isEmpty()) {
            log.warn("match 还有方法没有处理器，这些号一律回信封 1003: {}", unhandled);
        }
        return dispatcher;
    }
}
