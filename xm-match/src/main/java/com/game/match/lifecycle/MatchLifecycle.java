package com.game.match.lifecycle;

import com.game.match.gather.GatherLauncher;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
 * xm-match 后台件的启停次序（match-spec §9.8）。一个类把次序钉死，三个挂点都是 Spring / Dubbo 的标准时机：
 *
 * <table>
 *   <caption>启动（前 6 步是 bean 的创建次序，见 {@code MatchConfiguration}；任一步失败即拒绝启动）</caption>
 *   <tr><th>步</th><th>做什么</th><th>挂点</th></tr>
 *   <tr><td>7</td><td>导出 Dubbo</td><td>Dubbo 自己的 {@code ContextRefreshedEvent} 监听器（全部单例与 {@code SmartLifecycle} 就绪之后；同步等导出完成）</td></tr>
 *   <tr><td>8</td><td>起凑单</td><td rowspan="2">{@link ApplicationStartedEvent}：Spring Boot 在上下文刷新<b>全部</b>完成（含第 7 步）之后才发。
 *       不放在 {@link #start()} 里——那时 Dubbo 还没导出，而且导出失败的话进程马上就退，不该先弹出一组人</td></tr>
 *   <tr><td>9</td><td>起评分消费（Kafka 不可达只告警）</td></tr>
 * </table>
 *
 * <table>
 *   <caption>停机</caption>
 *   <tr><th>步</th><th>做什么</th><th>挂点</th></tr>
 *   <tr><td>1</td><td>停凑单，等当前一轮结束</td><td>{@link ContextClosedEvent}，次序 {@link #LISTENER_ORDER}：排在 Dubbo 的监听器（最低优先级）之前</td></tr>
 *   <tr><td>2</td><td>撤 Dubbo 导出（Dubbo 等在途调用至多 {@code dubbo.service.shutdown.wait}）</td><td>Dubbo 自己的 {@code ContextClosedEvent} 监听器</td></tr>
 *   <tr><td>3</td><td>排空 {@code match-worker}：已受理的请求做完，之后不会再有人交 gather</td><td rowspan="3">{@link #stop()}（{@code SmartLifecycle}，
 *       相位最高 = 最先停，早于管理 Tomcat）</td></tr>
 *   <tr><td>4</td><td>有界等在途 gather（{@link #GATHER_DRAIN_TIMEOUT}），超时直接放弃：票据按 matched TTL 自愈，scene 按备战期限解冻</td></tr>
 *   <tr><td>5</td><td>停评分消费</td></tr>
 *   <tr><td>6</td><td>交还发号租约、关直连客户端、关 Redis / 连接池</td><td>单例销毁（bean 依赖逆序，在全部 {@code SmartLifecycle} 停完之后）</td></tr>
 * </table>
 *
 * <p>为什么不是「一个 {@code stop()} 做完全部」：Dubbo 的导出 / 撤导出挂在上下文事件上，{@code SmartLifecycle} 的启停都落在它的同一侧
 * （启动在导出之前、停止在撤导出之后），夹不进去；所以启动的第 8 / 9 步与停机的第 1 步各借一个事件，其余在 {@link #stop()} 里。
 * 停机第 3 步是规格之外补的一步：排空之前被受理的 PVE_SOLO / 切磋应答还可能交出新的 gather，先排空，第 4 步等的才是全集。
 *
 * <p>停机时长挂的 {@code runTeamGather} 会被第 2 步切断：xm-team 按传输失败处理（推 MATCH_FAILED、计 {@code gather_unknown}，<b>不删票</b>），
 * gather 本身不受影响、在第 4 步的窗口里跑完并自己收尾票据。
 *
 * <p>各步都幂等、互不抛异常（停机路径上任何一步出错只记日志，继续后面的步骤）；启动的第 8 / 9 步抛异常 = 拒绝启动（Spring Boot 随即关闭上下文，
 * 走上面的停机序列）。回调都在启动线程 / 关停线程上，线程安全。
 */
public final class MatchLifecycle implements SmartLifecycle, SmartApplicationListener, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(MatchLifecycle.class);

    /** 停机时等在途 gather 的上限（规格的缺省 10 s；不开放配置：再长也长不过 matched TTL，过了就靠 TTL 自愈）。 */
    public static final Duration GATHER_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    /** 作为事件监听器的次序：只要求比 Dubbo 的 {@code DubboDeployApplicationListener}（{@code LOWEST_PRECEDENCE}）靠前。 */
    public static final int LISTENER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

    private final MatcherControl matcher;
    private final ResultConsumerControl consumer;
    private final GatherLauncher gathers;
    private final Runnable drainWorkers;
    private final Duration gatherDrainTimeout;

    private final AtomicBoolean backgroundStarted = new AtomicBoolean();
    private final AtomicBoolean matcherStopped = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile boolean matcherStartAttempted;
    private volatile boolean consumerStartAttempted;
    private volatile boolean running;
    private volatile ApplicationContext context;

    /**
     * @param matcher            凑单的启停口（未接入时传 {@link #matcherNotReady()}）
     * @param consumer           评分消费的启停口（未接入时传 {@link #consumerNotReady()}）
     * @param gathers            开局管线（停机时等它空闲）；null = 开局管线未接入，不等
     * @param drainWorkers       排空 {@code match-worker}（生产为 {@code MatchWorkerPool::close}；必须幂等）
     * @param gatherDrainTimeout 等在途 gather 的上限（生产为 {@link #GATHER_DRAIN_TIMEOUT}）
     */
    public MatchLifecycle(MatcherControl matcher, ResultConsumerControl consumer, GatherLauncher gathers, Runnable drainWorkers,
                          Duration gatherDrainTimeout) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.gathers = gathers;
        this.drainWorkers = Objects.requireNonNull(drainWorkers, "drainWorkers");
        this.gatherDrainTimeout = Objects.requireNonNull(gatherDrainTimeout, "gatherDrainTimeout");
    }

    /** 凑单尚未接入时的替身：启动时告警一次，其余什么都不做。 */
    public static MatcherControl matcherNotReady() {
        return new MatcherControl() {
            @Override
            public void start() {
                log.warn("凑单尚未接入（上下文里没有 MatcherControl）：排进队列的票不会被弹出成局");
            }

            @Override
            public void stop() {
            }
        };
    }

    /** 评分消费尚未接入时的替身：启动时告警一次，其余什么都不做。 */
    public static ResultConsumerControl consumerNotReady() {
        return new ResultConsumerControl() {
            @Override
            public void start() {
                log.warn("评分消费尚未接入（上下文里没有 ResultConsumerControl）：对局结果不会入账，评分停在已有值");
            }

            @Override
            public void stop() {
            }
        };
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
     * 启动第 8、9 步：起凑单，再起评分消费。只做一次；停机已经开始就不再启动。
     *
     * @throws RuntimeException 任何一步启动失败（原样抛出 = 拒绝启动）
     */
    public void startBackground() {
        if (matcherStopped.get() || stopped.get() || !backgroundStarted.compareAndSet(false, true)) {
            return;
        }
        matcherStartAttempted = true;
        matcher.start();
        consumerStartAttempted = true;
        consumer.start();
        log.info("match 已就绪：Dubbo 已导出，凑单与评分消费已启动");
    }

    /** 停机第 1 步：停凑单并等当前一轮结束。幂等；凑单没启动过就什么都不做。 */
    public void stopMatcher() {
        if (!matcherStopped.compareAndSet(false, true) || !matcherStartAttempted) {
            return;
        }
        long startedNanos = System.nanoTime();
        try {
            matcher.stop();
            log.info("停机 1/5：凑单已停（{} ms）", elapsedMillis(startedNanos));
        } catch (Throwable t) {
            log.error("停机 1/5：停凑单出错（继续停机；已弹出的组由各自的 gather 或 matched TTL 收尾）", t);
        }
    }

    // ================================================================ SmartLifecycle：停机第 3–5 步

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
            drainWorkersQuietly();
            awaitGathers();
            stopConsumer();
        } finally {
            running = false;
        }
    }

    private void drainWorkersQuietly() {
        long startedNanos = System.nanoTime();
        try {
            drainWorkers.run();
            log.info("停机 3/5：match-worker 已排空（{} ms）", elapsedMillis(startedNanos));
        } catch (Throwable t) {
            log.error("停机 3/5：排空 match-worker 出错（继续停机）", t);
        }
    }

    private void awaitGathers() {
        if (gathers == null) {
            return;
        }
        long startedNanos = System.nanoTime();
        try {
            if (gathers.awaitIdle(gatherDrainTimeout)) {
                log.info("停机 4/5：在途 gather 已全部结束（{} ms）", elapsedMillis(startedNanos));
            } else {
                log.warn("停机 4/5：等了 {} 仍有在途 gather，放弃等待（票据按 matched TTL 自愈，scene 按备战期限解冻）", gatherDrainTimeout);
            }
        } catch (Throwable t) {
            log.error("停机 4/5：等在途 gather 出错（继续停机）", t);
        }
    }

    private void stopConsumer() {
        if (!consumerStartAttempted) {
            return;
        }
        long startedNanos = System.nanoTime();
        try {
            consumer.stop();
            log.info("停机 5/5：评分消费已停（{} ms）", elapsedMillis(startedNanos));
        } catch (Throwable t) {
            log.error("停机 5/5：停评分消费出错（未提交的位点下次启动重放）", t);
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

    /** 凑单与评分消费是否已经启动过（启动第 8、9 步做过；测试与就绪日志用）。 */
    public boolean backgroundStarted() {
        return backgroundStarted.get();
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
