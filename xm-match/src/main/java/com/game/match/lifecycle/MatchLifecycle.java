package com.game.match.lifecycle;

import com.game.match.gather.GatherLauncher;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;

/**
 * xm-match 后台件的启停次序（match-spec §9.8；观战的两步见 spectate-spec §4.11）。一个类把次序钉死，三个挂点都是 Spring / Dubbo 的标准时机：
 *
 * <table>
 *   <caption>启动（前 6 步是 bean 的创建次序，见 {@code MatchConfiguration}；任一步失败即拒绝启动）</caption>
 *   <tr><th>步</th><th>做什么</th><th>挂点</th></tr>
 *   <tr><td>7</td><td>导出 Dubbo</td><td>Dubbo 自己的 {@code ContextRefreshedEvent} 监听器（全部单例与 {@code SmartLifecycle} 就绪之后；同步等导出完成）</td></tr>
 *   <tr><td>8</td><td>起凑单；随后起观战清扫（都只是排上定时任务，不阻塞）</td><td rowspan="2">{@link ApplicationStartedEvent}：Spring Boot 在上下文刷新
 *       <b>全部</b>完成（含第 7 步）之后才发。不放在 {@link #start()} 里——那时 Dubbo 还没导出，而且导出失败的话进程马上就退，不该先弹出一组人</td></tr>
 *   <tr><td>9</td><td>起评分消费：对局结果 topic 的首次核对在这一步同步做（至多等 {@code xm.match.kafka.init-timeout}）——与契约不符抛出 = 拒绝启动；
 *       Kafka 不可达只告警、后台每 30 s 重试</td></tr>
 * </table>
 *
 * <table>
 *   <caption>停机</caption>
 *   <tr><th>步</th><th>做什么</th><th>挂点</th></tr>
 *   <tr><td>1</td><td>停凑单，等当前一轮结束</td><td>{@link ContextClosedEvent}，次序 {@link #LISTENER_ORDER}：排在 Dubbo 的监听器（最低优先级）之前</td></tr>
 *   <tr><td>2</td><td>撤 Dubbo 导出（Dubbo 等在途调用至多 {@code dubbo.service.shutdown.wait}）</td><td>Dubbo 自己的 {@code ContextClosedEvent} 监听器</td></tr>
 *   <tr><td>3</td><td><b>并行</b>做两件事，两件都结束才往下走：排空 {@code match-worker}（已受理的请求做完，之后不会再有人交 gather；至多 10 s）；
 *       有界等在途的 163 观战（它不在工作池上；至多 {@code watchDrainTimeout}，生产 5 s）。并行，所以这一步的上限仍是排空工作池的 10 s</td>
 *       <td rowspan="4">{@link #stop()}（{@code SmartLifecycle}，相位最高 = 最先停，早于管理 Tomcat）</td></tr>
 *   <tr><td>4</td><td>停观战清扫（当场中断手上那一轮，不等它做完：这一步不给停机加时间）</td></tr>
 *   <tr><td>5</td><td>有界等在途 gather（{@link #GATHER_DRAIN_TIMEOUT}），超时直接放弃：票据按 matched TTL 自愈，scene 按备战期限解冻</td></tr>
 *   <tr><td>6</td><td>停评分消费</td></tr>
 *   <tr><td>7</td><td>交还发号租约、关直连客户端、关 Redis / 连接池</td><td>单例销毁（bean 依赖逆序，在全部 {@code SmartLifecycle} 停完之后）</td></tr>
 * </table>
 *
 * <p>为什么不是「一个 {@code stop()} 做完全部」：Dubbo 的导出 / 撤导出挂在上下文事件上，{@code SmartLifecycle} 的启停都落在它的同一侧
 * （启动在导出之前、停止在撤导出之后），夹不进去；所以启动的第 8 / 9 步与停机的第 1 步各借一个事件，其余在 {@link #stop()} 里。
 * 停机第 3 步的排空是规格之外补的一步：排空之前被受理的 PVE_SOLO / 切磋应答还可能交出新的 gather，先排空，第 5 步等的才是全集。
 *
 * <p><b>为什么等 163 与排空工作池并行</b>（lead 裁决 4：加了观战之后，{@link #stop()} 里第 3–5 步的最坏时长不得比 6.4 长）：两者都在撤 Dubbo
 * 导出之后才开始、互不依赖；6.4 是 10 + 10 = 20 s，串行会变成 10 + 5 + 10 = 25 s。163 的上界（5 s）小于排空的上界（10 s），并行之后这一步不比原来长。
 * 这 20 s 是<b>设计预算</b>，取值与 {@code spring.lifecycle.timeout-per-shutdown-phase} 相同，但 <b>Spring 不会拿它截断这个同步的 {@code stop()}</b>：
 * 本类没有覆盖 {@code stop(Runnable)}，缺省实现先同步跑完 {@code stop()} 再回调，而 {@code DefaultLifecycleProcessor} 的每阶段等待在全部成员的
 * stop 调用返回之后才开始（spring-context 6.2.19；同 scene-drain-spec 的 G16）。真正的硬期限在进程外、从 SIGTERM 起算、还包含第 1、2 步：
 * 本机切片是 {@code tools/local/stop-slice.sh} 的 20 s（match-spec §9.8 已登记可能超出而被强杀，后果自愈）；容器部署是按这个值推出来的宽限期
 * （ops-release-spec §0.6 #30：preStop 5 + 20 + 10 = 35 s）——第 3–5 步守住 20 s，那条公式才继续成立。
 * 等 163 在一条辅助线程（{@code match-stop-watches}）上做，停机线程自己排空工作池、再回头收辅助线程的结果；
 * 辅助线程到了「{@code watchDrainTimeout} + {@link #WATCH_DRAIN_GRACE}」还不返回（实现违反约定）就放弃它，停机不被拖住。
 * 清扫器排在 163 之后停：在途的 163 还可能读写索引，清扫本身是幂等的只删操作，早停晚停都无害，放在这里只为「不再有请求时才停后台件」。
 * 它是串在第 3 步与第 5 步之间的一步，所以<b>不等手上那一轮清扫自己做完</b>（{@link SweeperControl#stop} 的契约：当场中断、只等线程退出），
 * 第 3–5 步的最坏时长仍是 10 + 10 = 20 s，与加观战之前相同。
 *
 * <p>停机时长挂的 {@code runTeamGather} 会被第 2 步切断：xm-team 按传输失败处理（推 MATCH_FAILED、计 {@code gather_unknown}，<b>不删票</b>），
 * gather 本身不受影响、在第 5 步的窗口里跑完并自己收尾票据。
 *
 * <p>各步都幂等、互不抛异常（停机路径上任何一步出错只记日志，继续后面的步骤）；启动的第 8 / 9 步抛异常 = 拒绝启动（Spring Boot 随即关闭上下文，
 * 走上面的停机序列）。
 *
 * <p><b>线程</b>：启动的第 8 / 9 步在启动线程上（{@link ApplicationStartedEvent} 在上下文刷新返回之后才发），停机各步在关停线程上
 * （SIGTERM 时是 JVM 的关停钩子线程）——「刚启动完就收到 SIGTERM」时两条线程会<b>同时</b>在这个类里。约定：
 * <ul>
 *   <li>「判停机 → 起凑单」与「置停机 → 停凑单」经同一把锁（{@code gate}）串行：要么停机先到、凑单不再起；要么凑单先起完、停机接着把它停掉。
 *       不许出现「停机认为没起过而跳过，随后凑单照样起来」——那样凑单会在 Dubbo 撤导出、工作池排空期间继续弹组。观战清扫的「起不起」同理，
 *       在同一把锁里定。</li>
 *   <li>评分消费的 {@code start()} 会同步核对 topic（至多 {@code init-timeout}），<b>不放在锁里</b>（否则关停线程的第 1 步要等它，其间凑单还在跑、
 *       Dubbo 也撤不了导出）：锁里只定「起不起」，起完再看一眼停机是否已经走过第 6 步，走过了就自己补停。启停口自身保证 start / stop 互斥。</li>
 * </ul>
 */
public final class MatchLifecycle implements SmartLifecycle, SmartApplicationListener, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(MatchLifecycle.class);

    /** 停机时等在途 gather 的上限（规格的缺省 10 s；不开放配置：再长也长不过 matched TTL，过了就靠 TTL 自愈）。 */
    public static final Duration GATHER_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    /**
     * 等在途 163 的辅助线程超过它自己的上限之后，停机线程再多等它多久才放弃。正常实现到点就返回，用不到这段余量；它只防
     * {@link InflightWatches#awaitIdle} 的实现不守约把停机拖住。
     */
    public static final Duration WATCH_DRAIN_GRACE = Duration.ofSeconds(1);
    /** 作为事件监听器的次序：只要求比 Dubbo 的 {@code DubboDeployApplicationListener}（{@code LOWEST_PRECEDENCE}）靠前。 */
    public static final int LISTENER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

    private final MatcherControl matcher;
    private final ResultConsumerControl consumer;
    private final GatherLauncher gathers;
    private final Runnable drainWorkers;
    private final Duration gatherDrainTimeout;
    private final InflightWatches watches;
    private final Duration watchDrainTimeout;
    private final SweeperControl sweeper;

    /**
     * 串行化启动线程与关停线程对「起不起 / 停不停」的判定（见类注释「线程」）。锁里只有凑单的启停（start 不阻塞；stop 有界地等当前一轮结束）、
     * 观战清扫的启动（不阻塞）与三个「起过没有」标志的读写；评分消费的启停、清扫的停止都在锁外。
     */
    private final Object gate = new Object();
    private final AtomicBoolean backgroundStarted = new AtomicBoolean();
    private final AtomicBoolean matcherStopped = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    /** 只在持有 {@link #gate} 时读写。 */
    private boolean matcherStartAttempted;
    /** 只在持有 {@link #gate} 时读写。 */
    private boolean sweeperStartAttempted;
    /** 只在持有 {@link #gate} 时读写。 */
    private boolean consumerStartAttempted;
    private volatile boolean running;
    private volatile ApplicationContext context;

    /**
     * @param matcher            凑单的启停口
     * @param consumer           评分消费的启停口
     * @param gathers            开局管线（停机时等它空闲）
     * @param drainWorkers       排空 {@code match-worker}（生产为 {@code MatchWorkerPool::close}；必须幂等）
     * @param gatherDrainTimeout 等在途 gather 的上限（生产为 {@link #GATHER_DRAIN_TIMEOUT}）
     * @param watches            在途的 163 观战（停机时与排空工作池并行地等它们结束）
     * @param watchDrainTimeout  等在途 163 的上限（正数；生产为 {@code MatchBudgets.SPECTATE_DRAIN_TIMEOUT_MS} = 5 s）
     * @param sweeper            观战清扫的启停口
     */
    public MatchLifecycle(MatcherControl matcher, ResultConsumerControl consumer, GatherLauncher gathers, Runnable drainWorkers,
                          Duration gatherDrainTimeout, InflightWatches watches, Duration watchDrainTimeout, SweeperControl sweeper) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.gathers = Objects.requireNonNull(gathers, "gathers");
        this.drainWorkers = Objects.requireNonNull(drainWorkers, "drainWorkers");
        this.gatherDrainTimeout = Objects.requireNonNull(gatherDrainTimeout, "gatherDrainTimeout");
        this.watches = Objects.requireNonNull(watches, "watches");
        this.watchDrainTimeout = Objects.requireNonNull(watchDrainTimeout, "watchDrainTimeout");
        if (watchDrainTimeout.isNegative() || watchDrainTimeout.isZero()) {
            throw new IllegalArgumentException("等在途 163 的上限必须为正: " + watchDrainTimeout);
        }
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper");
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.context = applicationContext;
    }

    // ================================================================ 事件：启动第 8 / 9 步、停机第 1 步

    @Override
    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return ApplicationStartedEvent.class.isAssignableFrom(eventType) || ContextClosedEvent.class.isAssignableFrom(eventType);
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationStartedEvent started && own(started.getApplicationContext())) {
            startBackground();
        } else if (event instanceof ContextClosedEvent closed && own(closed.getApplicationContext())) {
            stopMatcher();
        }
    }

    @Override
    public int getOrder() {
        return LISTENER_ORDER;
    }

    /** 子上下文的事件也会传到父上下文的监听器：只认自己所在的那个。没有上下文（单元测试直接驱动）时都认。 */
    private boolean own(ApplicationContext source) {
        ApplicationContext mine = context;
        return mine == null || mine == source;
    }

    /**
     * 启动第 8、9 步：起凑单与观战清扫，再起评分消费。只做一次；停机已经开始就不再启动。
     *
     * @throws RuntimeException 任何一步启动失败（原样抛出 = 拒绝启动）
     */
    public void startBackground() {
        synchronized (gate) {
            // 判定与启动在同一把锁里：停机的第 1 步要么已经做过（这里不再起），要么排在 matcher.start() 返回之后（它会把凑单停掉）
            if (matcherStopped.get() || stopped.get() || !backgroundStarted.compareAndSet(false, true)) {
                return;
            }
            matcherStartAttempted = true;
            matcher.start(); // 不阻塞（只是排上定时任务）
        }
        synchronized (gate) {
            if (matcherStopped.get() || stopped.get()) {
                // 凑单刚起来停机就开始了：清扫与评分消费不必再起
                return;
            }
            sweeperStartAttempted = true;
            sweeper.start(); // 不阻塞（只是排上定时任务）
            consumerStartAttempted = true;
        }
        // 有界阻塞（同步核对 topic），不能占着锁：关停线程的第 1 步不该等它
        consumer.start();
        if (stopped.get()) {
            // 停机的第 6 步可能在 consumer.start() 之前就走过了（那时还没起，停了个空）：自己补停。停两次无妨（幂等）
            stopConsumerQuietly("启动途中进入停机，补停评分消费");
            return;
        }
        log.info("match 已就绪：Dubbo 已导出，凑单、观战清扫与评分消费已启动");
    }

    /** 停机第 1 步：停凑单并等当前一轮结束。幂等；凑单没启动过就什么都不做。 */
    public void stopMatcher() {
        synchronized (gate) {
            // 置位与停止在同一把锁里：启动线程若正卡在 matcher.start() 里，这里等它起完再停；若还没进来，它之后看到置位就不再起
            if (!matcherStopped.compareAndSet(false, true) || !matcherStartAttempted) {
                return;
            }
            long startedNanos = System.nanoTime();
            try {
                matcher.stop();
                log.info("停机 1/6：凑单已停（{} ms）", elapsedMillis(startedNanos));
            } catch (Throwable t) {
                log.error("停机 1/6：停凑单出错（继续停机；已弹出的组由各自的 gather 或 matched TTL 收尾）", t);
            }
        }
    }

    // ================================================================ SmartLifecycle：停机第 3–6 步

    /** 只登记「在运行」，让容器在关闭时调 {@link #stop()}；后台件等 {@link ApplicationStartedEvent} 才启动。 */
    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            stopMatcher(); // 兜底：正常停机时上下文关闭事件已经做过
            drainWorkersAndAwaitWatches();
            stopSweeper();
            awaitGathers();
            stopConsumer();
        } finally {
            running = false;
        }
    }

    /** 停机第 3 步：停机线程排空工作池，同时一条辅助线程等在途的 163；两件都有了结果才返回。 */
    private void drainWorkersAndAwaitWatches() {
        long startedNanos = System.nanoTime();
        CompletableFuture<Boolean> watchesIdle = new CompletableFuture<>();
        Thread waiter = null;
        try {
            waiter = Thread.ofPlatform().name("match-stop-watches").daemon(true).start(() -> {
                try {
                    watchesIdle.complete(watches.awaitIdle(watchDrainTimeout));
                } catch (Throwable t) {
                    watchesIdle.completeExceptionally(t);
                }
            });
        } catch (Throwable t) { // 起不了线程（资源耗尽）：不等 163 了，照常往下停
            watchesIdle.completeExceptionally(t);
        }
        drainWorkersQuietly();
        awaitWatches(watchesIdle, waiter, startedNanos);
    }

    private void drainWorkersQuietly() {
        long startedNanos = System.nanoTime();
        try {
            drainWorkers.run();
            log.info("停机 3/6：match-worker 已排空（{} ms）", elapsedMillis(startedNanos));
        } catch (Throwable t) {
            log.error("停机 3/6：排空 match-worker 出错（继续停机）", t);
        }
    }

    /** 收辅助线程的结果。它自己有界（{@link InflightWatches} 的契约）；这里另设一道硬上限，到点就放弃它。 */
    private void awaitWatches(CompletableFuture<Boolean> watchesIdle, Thread waiter, long startedNanos) {
        long hardStopNanos = watchDrainTimeout.toNanos() + WATCH_DRAIN_GRACE.toNanos();
        long waitNanos = Math.max(1, hardStopNanos - (System.nanoTime() - startedNanos));
        try {
            if (watchesIdle.get(waitNanos, TimeUnit.NANOSECONDS)) {
                log.info("停机 3/6：在途的 163 观战已全部结束（{} ms）", elapsedMillis(startedNanos));
            } else {
                log.warn("停机 3/6：等了 {} 仍有在途的 163 观战，放弃等待（已抢到的观战标记留到 TTL；客户端按 gate 的超时收到信封 1003）", watchDrainTimeout);
            }
        } catch (TimeoutException e) {
            log.error("停机 3/6：等在途 163 的调用过了它自己的上限 {} 仍没有返回（实现违反约定），放弃等待，继续停机", watchDrainTimeout);
            if (waiter != null) {
                waiter.interrupt();
            }
        } catch (ExecutionException e) {
            log.error("停机 3/6：等在途的 163 观战出错（继续停机）", e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("停机 3/6：等在途的 163 观战时被中断，放弃等待，继续停机");
            if (waiter != null) {
                waiter.interrupt();
            }
        }
    }

    private void stopSweeper() {
        synchronized (gate) {
            if (!sweeperStartAttempted) {
                return;
            }
        }
        long startedNanos = System.nanoTime();
        try {
            sweeper.stop();
            log.info("停机 4/6：观战清扫已停（{} ms）", elapsedMillis(startedNanos));
        } catch (Throwable t) {
            log.error("停机 4/6：停观战清扫出错（继续停机；清扫是幂等的只删操作，没停干净也无害）", t);
        }
    }

    private void awaitGathers() {
        long startedNanos = System.nanoTime();
        try {
            if (gathers.awaitIdle(gatherDrainTimeout)) {
                log.info("停机 5/6：在途 gather 已全部结束（{} ms）", elapsedMillis(startedNanos));
            } else {
                log.warn("停机 5/6：等了 {} 仍有在途 gather，放弃等待（票据按 matched TTL 自愈，scene 按备战期限解冻）", gatherDrainTimeout);
            }
        } catch (Throwable t) {
            log.error("停机 5/6：等在途 gather 出错（继续停机）", t);
        }
    }

    private void stopConsumer() {
        synchronized (gate) {
            if (!consumerStartAttempted) {
                return;
            }
        }
        // 启动线程可能还没走到 consumer.start()（或正卡在它里面）：这里停个空、或等它返回后停掉；停了个空的那种由启动线程自己补停
        stopConsumerQuietly("停机 6/6");
    }

    private void stopConsumerQuietly(String step) {
        long startedNanos = System.nanoTime();
        try {
            consumer.stop();
            log.info("{}：评分消费已停（{} ms）", step, elapsedMillis(startedNanos));
        } catch (Throwable t) {
            log.error("{}：停评分消费出错（未提交的位点下次启动重放）", step, t);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 相位最高：最后启动、最先停止（早于管理 Tomcat 与一切单例的销毁）。 */
    @Override
    public int getPhase() {
        return DEFAULT_PHASE;
    }

    /** 凑单、观战清扫与评分消费是否已经启动过（启动第 8、9 步做过；测试与就绪日志用）。 */
    public boolean backgroundStarted() {
        return backgroundStarted.get();
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
