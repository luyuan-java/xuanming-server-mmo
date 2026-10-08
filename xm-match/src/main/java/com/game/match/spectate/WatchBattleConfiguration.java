package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.lifecycle.InflightWatches;
import com.game.match.metrics.MatchMetrics;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementStore;
import com.game.match.port.PlayerStatusReader;
import com.game.match.ticket.TicketReader;
import java.time.Duration;
import java.util.Optional;
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
 *   <li>{@link #watchBattleService}：163 的判定流程。装配时顺带核对 {@code xm.match.request-budget} 够不够它的两道预算门槛，
 *       不够打一条 WARN（{@link #budgetWarning}；不拒启）。</li>
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

    /** 核对预算时给入口那几次 Redis 读留的余量（毫秒）：门槛判的是「走到那一步时的剩余」，不是整个预算。 */
    static final long BUDGET_SLACK_MS = 100;
    /**
     * 163 的预算（{@code xm.match.request-budget}）低于它时，<b>带着旧标记的换场 / 随机观战回 16004</b>（不计余量、即 ≤ 2200 ms 时恒回）：
     * 换场要先后发两跳，发 Remove 之前剩余预算不足「换场预留 + AddObserver 最低预算」就什么都不做（{@code WatchBattleService} 的 J 行；
     * 规格 §5.3 的不等式「1.2 + 1.0 ≤ 预算」只对缺省的 4500 ms 核对过）。
     */
    static final long REWATCH_BUDGET_FLOOR_MS = MatchBudgets.WATCH_REWATCH_RESERVE_MS + MatchBudgets.WATCH_ADD_MIN_BUDGET_MS + BUDGET_SLACK_MS;
    /**
     * 163 的预算低于它时，<b>凡是走到登记的 163 都回 16004</b>（≤ 1000 ms 时恒回：发 AddObserver 之前剩余预算不足最低预算，
     * 每次还白写白删一个标记）。
     */
    static final long ADD_BUDGET_FLOOR_MS = MatchBudgets.WATCH_ADD_MIN_BUDGET_MS + BUDGET_SLACK_MS;

    /**
     * 核对运行期配置的整请求预算够不够 163 用。{@code xm.match.request-budget} 的合法区间是 [500 ms, 4500 ms]（{@link MatchProperties}），
     * 而 163 的两道门槛是代码常量——区间的下半段对别的号够用，对 163 不够。不拒启（那会让原本合法的配置起不来），只在启动时告警。
     *
     * @return 要打的告警；预算够用为空
     */
    static Optional<String> budgetWarning(Duration requestBudget) {
        long budgetMs = requestBudget.toMillis();
        if (budgetMs >= REWATCH_BUDGET_FLOOR_MS) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder("xm.match.request-budget=").append(budgetMs).append("ms 不够 163 观战用：低于 ")
                .append(REWATCH_BUDGET_FLOOR_MS).append(" ms（换场预留 ").append(MatchBudgets.WATCH_REWATCH_RESERVE_MS)
                .append(" + AddObserver 最低预算 ").append(MatchBudgets.WATCH_ADD_MIN_BUDGET_MS).append(" + 余量 ").append(BUDGET_SLACK_MS)
                .append("），带着旧观战标记的换场 / 随机观战会因预算不足回 16004「服务器繁忙」（不留余量的话恒回）、旧标记原样保留")
                .append("（要等旧场落点或标记过期，最长 ").append(MatchBudgets.WATCHING_TTL_SECONDS).append(" s）");
        if (budgetMs < ADD_BUDGET_FLOOR_MS) {
            text.append("；并且低于 ").append(ADD_BUDGET_FLOOR_MS).append(" ms：凡是走到登记的 163 都会因预算不足回 16004，观战整体不可用");
        }
        return Optional.of(text.append("。缺省 ").append(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS).append(" ms 没有这个问题").toString());
    }

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

    /** 163 的判定流程。装配时核对一次整请求预算够不够它的两道门槛（不够只告警，见 {@link #budgetWarning}）。 */
    @Bean
    public WatchBattleService watchBattleService(MatchProperties props, SpectateStore spectateStore, PlacementStore placements,
                                                 PlayerStatusReader players, TicketReader tickets, ObserverDialer observerDialer,
                                                 MatchMetrics metrics) {
        budgetWarning(props.requestBudget()).ifPresent(warning -> log.warn("[spectate] {}", warning));
        return new WatchBattleService(spectateStore, placements, players, tickets, observerDialer, metrics);
    }

    /** 163 的处理器：跑在 {@link #spectateExecutor} 上，不占 {@code match-worker}。 */
    @Bean
    public MatchMethodHandler watchBattleHandler(WatchBattleService watchBattleService, SpectateExecutor spectateExecutor, MatchMetrics metrics) {
        return new WatchBattleHandler(watchBattleService, spectateExecutor, metrics);
    }
}
