package com.game.match.queue;

import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.gather.GatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.port.PlayerStatusReader;
import com.game.match.rating.RatingReader;
import com.game.match.ticket.DefaultTicketHealing;
import com.game.match.ticket.RedissonTicketStore;
import com.game.match.ticket.TicketHealing;
import com.game.match.ticket.TicketStore;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 票据存储与排队入口的装配。
 *
 * <ul>
 *   <li>{@link TicketStore}（也就是只读口 {@code TicketReader}）与 {@link TicketHealing}：排队、凑单、gather、成员预检、整队、活动共用这一份。
 *       存储没有本地状态、构造时不碰 Redis。</li>
 *   <li>{@link QueueService} 与 157 / 148 / 153 三个处理器：处理器声明成 {@link MatchMethodHandler} bean，派发器启动时收集。</li>
 * </ul>
 * 本包向别的包要的：评分读取 {@link RatingReader}（rating 包）、开局入口 {@link GatherLauncher}（gather 包）；其余来自基础设施装配。
 */
@Configuration(proxyBeanMethods = false)
public class QueueConfiguration {

    @Bean
    public TicketStore ticketStore(RedissonClient redis) {
        return new RedissonTicketStore(redis);
    }

    @Bean
    public TicketHealing ticketHealing(TicketStore ticketStore) {
        return new DefaultTicketHealing(ticketStore);
    }

    @Bean
    public QueueService queueService(MatchProperties props, PlayerStatusReader players, TicketStore ticketStore, TicketHealing ticketHealing,
                                     RatingReader ratings, GatherLauncher gather, MatchIds matchIds, MatchMetrics metrics) {
        return new QueueService(props, players, ticketStore, ticketHealing, ratings, gather, matchIds, metrics);
    }

    @Bean
    public MatchMethodHandler joinQueueHandler(QueueService queueService, MatchMetrics metrics) {
        return new QueueHandlers.Join(queueService, metrics);
    }

    @Bean
    public MatchMethodHandler cancelQueueHandler(QueueService queueService) {
        return new QueueHandlers.Cancel(queueService);
    }

    @Bean
    public MatchMethodHandler queueStatusHandler(QueueService queueService) {
        return new QueueHandlers.Status(queueService);
    }
}
