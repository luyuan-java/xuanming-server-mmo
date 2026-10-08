package com.game.match.spectate;

import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.lifecycle.InflightWatches;
import com.game.match.metrics.MatchMetrics;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementStore;
import com.game.match.port.PlayerStatusReader;
import com.game.match.ticket.TicketReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 163 WatchBattle、观众 RPC 与 163 的在途执行器的装配（批次 6.5，工作包 W2；spectate-spec §4.4、§4.8、§4.9）。四个 bean：
 * <ul>
 *   <li>{@link #spectateExecutor}：163 自己的执行器（每个请求一条虚拟线程 + 在途上限 {@code xm.match.spectate.max-inflight}）。
 *       <b>它同时就是 {@link InflightWatches} bean</b>——{@code MatchLifecycle} 停机时经它有界等在途的 163；在途数在这里接到
 *       {@code xm_match_spectate_inflight}。</li>
 *   <li>{@link #observerDialer}：观众 RPC（{@link DefaultObserverDialer}，包着 placement 包带硬截止的直拨）。163 与开局钩子
 *       （{@code WatchableConfiguration} 的 {@code SpectateGatherHooks}）共用这一个。</li>
 *   <li>{@link #watchBattleService}：163 的判定流程。</li>
 *   <li>{@link #watchBattleHandler}：163 的入口处理器（非 inline；{@code executor()} 返回上面那个执行器；过载回 in-band 16004）。
 *       声明成 bean 即被派发器收集。</li>
 * </ul>
 * 依赖别的包都经接口注入：观战存储（{@link SpectateStore}，{@code SpectateStoreConfiguration}）、落点记录与直拨（placement 包）、
 * 票据的只读口（{@link TicketReader}，ticket 包）、战斗锁与在线目录（{@link PlayerStatusReader}，基础设施装配）。
 * 构造时都不碰 Redis、不发任何调用。不带条件装配（理由见 {@code MatchConfiguration} 的类注释）。
 *
 * <p>销毁顺序由依赖链钉住：处理器依赖执行器与判定流程，Spring 按依赖逆序销毁——先没有入口，之后执行器才 {@code close}（只是不再受理，
 * 不等也不打断在途的请求；有界等待在 {@code MatchLifecycle} 的停机序列里已经做过）。
 */
@Configuration(proxyBeanMethods = false)
public class WatchBattleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WatchBattleConfiguration.class);

    /**
     * 163 的执行器，也是停机时「等在途 163」的口（{@link InflightWatches}：整个进程恰好这一个）。在途数接到 {@code xm_match_spectate_inflight}。
     */
    @Bean(destroyMethod = "close")
    public SpectateExecutor spectateExecutor(MatchProperties props, MatchMetrics metrics) {
        SpectateExecutor executor = new SpectateExecutor(props.spectate().maxInflight());
        metrics.bindSpectateInflight(executor::inflight);
        log.info("163 观战在途上限={}（每个请求一条虚拟线程 {}*）", executor.maxInflight(), SpectateExecutor.THREAD_PREFIX);
        return executor;
    }

    /** 观众 RPC：按落点记录直拨 battle 的 addObserver / removeObserver，结局四分，计 {@code xm_match_observer_rpc_total}。 */
    @Bean
    public ObserverDialer observerDialer(PlacementDialer placementDialer, MatchMetrics metrics) {
        return new DefaultObserverDialer(placementDialer, metrics);
    }

    /** 163 的判定流程。 */
    @Bean
    public WatchBattleService watchBattleService(SpectateStore spectateStore, PlacementStore placements, PlayerStatusReader players,
                                                 TicketReader tickets, ObserverDialer observerDialer, MatchMetrics metrics) {
        return new WatchBattleService(spectateStore, placements, players, tickets, observerDialer, metrics);
    }

    /** 163 的处理器：跑在 {@link #spectateExecutor} 上，不占 {@code match-worker}。 */
    @Bean
    public MatchMethodHandler watchBattleHandler(WatchBattleService watchBattleService, SpectateExecutor spectateExecutor, MatchMetrics metrics) {
        return new WatchBattleHandler(watchBattleService, spectateExecutor, metrics);
    }
}
