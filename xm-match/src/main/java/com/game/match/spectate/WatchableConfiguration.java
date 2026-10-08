package com.game.match.spectate;

import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.gather.GatherHooks;
import com.game.match.lifecycle.SweeperControl;
import com.game.match.metrics.MatchMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 164 ListWatchableBattles、开局钩子与观战清扫的装配（批次 6.5，spectate-spec §4.5–§4.7）。三样东西都只经 {@link SpectateStore}
 * （{@code SpectateStoreConfiguration}）碰 Redis，开局钩子另经 {@link ObserverDialer}（{@code WatchBattleConfiguration}）直拨 battle：
 * <ul>
 *   <li>{@link #listWatchableBattlesHandler}：164 的处理器 {@link ListWatchableHandler}（声明成 bean 即被派发器收集）。非 inline、
 *       不带自己的执行器 = 跑在共用的 {@code match-worker} 上；过载回信封 1003。流程在 {@link WatchableListService}。</li>
 *   <li>{@link #gatherHooks}：开局管线留给观战的两个接缝 {@link SpectateGatherHooks}——开局前清退正在观战的参战者、开局后把这一场登记进可观战索引。
 *       {@code GatherConfiguration} 的管线按 {@link GatherHooks} 注入它；一个进程恰好一个。</li>
 *   <li>{@link #sweeperControl}：观战清扫器 {@link SpectateSweeper}，间隔取 {@code xm.match.spectate.sweep-interval}。<b>不自己启停</b>：
 *       {@code MatchLifecycle} 在启动第 8 步经 {@link SweeperControl#start()} 起、停机时在「等在途 163」之后停。{@code destroyMethod}
 *       只是兜底（幂等；正常停机时 {@code MatchLifecycle} 早已停过它）。</li>
 * </ul>
 * 构造这些 bean 不碰 Redis、不起线程。不带条件装配（理由见 {@code MatchConfiguration} 的类注释）：测试里要换钩子或清扫口，
 * 就把替身标 {@code @Primary}，或自己提供同名 bean。
 */
@Configuration(proxyBeanMethods = false)
public class WatchableConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WatchableConfiguration.class);

    /** 164 的流程。 */
    @Bean
    public WatchableListService watchableListService(SpectateStore spectateStore, MatchMetrics metrics) {
        return new WatchableListService(spectateStore, metrics);
    }

    /** 164 的处理器。 */
    @Bean
    public MatchMethodHandler listWatchableBattlesHandler(WatchableListService watchableListService, MatchMetrics metrics) {
        return new ListWatchableHandler(watchableListService, metrics);
    }

    /** 开局钩子：开局前清退观众、开局后公开。 */
    @Bean
    public GatherHooks gatherHooks(SpectateStore spectateStore, ObserverDialer observerDialer, MatchMetrics metrics) {
        return new SpectateGatherHooks(spectateStore, observerDialer, metrics);
    }

    /** 观战清扫的启停口。只建不起：启停归 {@code MatchLifecycle}。 */
    @Bean(destroyMethod = "stop")
    public SweeperControl sweeperControl(SpectateStore spectateStore, MatchMetrics metrics, MatchProperties props) {
        log.info("观战清扫间隔={}（由 MatchLifecycle 在 Dubbo 导出之后启动）", props.spectate().sweepInterval());
        return new SpectateSweeper(spectateStore, metrics, props.spectate().sweepInterval());
    }
}
