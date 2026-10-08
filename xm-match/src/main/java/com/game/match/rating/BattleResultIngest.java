package com.game.match.rating;

import com.game.audit.AuditTopicContractException;
import com.game.audit.AuditTopicInitializer;
import com.game.audit.BattleResultTopics;
import com.game.audit.TopicAdmin;
import com.game.match.metrics.MatchMetrics;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Properties;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 评分回流的运行时（match-spec §5.3、§5.4、§9.8 启动第 9 步；基线 {@code msvc.go:101-134}）：核对对局结果 topic，然后起一条
 * {@value #THREAD_NAME} 线程跑 {@link BattleResultConsumer}。写法同 xm-data 的 {@code DataNode}。
 *
 * <ul>
 *   <li><b>topic 的主人是 xm-match</b>：{@link AuditTopicInitializer.Mode#OWN}——缺就建（3 分区）、核对分区数、把保留期校正到 7 天并读回。
 *       xm-battle（生产方）只核对分区数。</li>
 *   <li><b>第一次核对在启动线程上同步做</b>：分区数与契约不符（{@link AuditTopicContractException}）直接抛出、进程拒绝启动——要升代次
 *       （{@code XM_BATTLE_RESULT_TOPIC_GENERATION}，xm-battle 与 xm-match 一起改）。</li>
 *   <li><b>Kafka 不可达不拒启</b>（评分是软数据，不能拖死匹配）：最多等 {@code xm.match.kafka.init-timeout} 后照常启动，后台每 30 s 重试核对，
 *       通过后再起消费者；期间排队 / 凑单照常、评分停在当前值，恢复后从已提交位点（新消费组从最早）补消费，不丢结果。</li>
 *   <li>消费循环意外退出（不是停止）：5 s 后换一个新的 KafkaConsumer 重来，没提交的记录会被重新消费。</li>
 *   <li>{@code xm.match.rating.enabled = false}：什么都不做（不核对 topic、不消费），评分停在已有值。</li>
 * </ul>
 * 停止发生在数据源销毁之前（{@link SmartLifecycle}）：唤醒轮询、等消费线程退出（至多 10 s），没提交的记录留给下次启动重放。
 * 它与 matcher / gather / Dubbo 没有先后依赖（只碰 Kafka 与 MySQL），所以用缺省相位即可。
 */
public final class BattleResultIngest implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BattleResultIngest.class);

    static final String THREAD_NAME = "match-rating-consumer";
    static final String INIT_THREAD_NAME = "match-rating-init";
    /** Kafka 不可达时后台重试核对的间隔（同基线 30 s）。 */
    static final Duration INIT_RETRY = Duration.ofSeconds(30);
    /** 消费循环意外退出后重启的间隔。 */
    static final Duration RESTART_DELAY = Duration.ofSeconds(5);
    static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    /** 可恢复故障的退避：1 s 起、每次翻倍、封顶 30 s。 */
    static final Duration RETRY_INITIAL = Duration.ofSeconds(1);
    static final Duration RETRY_MAX = Duration.ofSeconds(30);
    /** 一次拉取最多多少条（逐条入账、逐条提交；量很小，不需要大批）。 */
    static final int MAX_POLL_RECORDS = 32;
    private static final long JOIN_TIMEOUT_MS = 10_000;

    /**
     * 连接与开关（装配时从 {@code MatchProperties} 取）。
     *
     * @param enabled           {@code xm.match.rating.enabled}
     * @param topicGeneration   topic 代次（≥ 1）
     * @param replicationFactor 新建 topic 的副本数
     * @param initTimeout       核对 / 创建 topic 一次的上限
     */
    public record Settings(boolean enabled, int topicGeneration, short replicationFactor, Duration initTimeout) {
    }

    private final Settings settings;
    private final Supplier<TopicAdmin> adminFactory;
    private final Supplier<Consumer<String, byte[]>> consumerFactory;
    private final BattleResultConsumer.Handler handler;
    private final MatchMetrics metrics;
    private final Duration initRetry;
    private final Duration restartDelay;
    private final Duration pollTimeout;
    private final String topic;

    private volatile boolean running;
    /** 本次运行的停止信号：后台线程的等待都挂在它上面，stop() 一放就醒（不靠中断，免得打断在途的事务与消费者的关闭）。 */
    private volatile CountDownLatch stopSignal = new CountDownLatch(1);
    private volatile BattleResultConsumer loop;
    private Thread initThread;
    private volatile Thread consumerThread;

    /**
     * @param adminFactory    建 topic 管理客户端（每次核对新建一个、用完关闭）
     * @param consumerFactory 建 KafkaConsumer（关自动提交、{@code earliest}；每次（重）启动消费循环新建一个，由循环关闭）
     * @param handler         入账（{@code ratingStore::apply}）
     */
    public BattleResultIngest(Settings settings, Supplier<TopicAdmin> adminFactory, Supplier<Consumer<String, byte[]>> consumerFactory,
                              BattleResultConsumer.Handler handler, MatchMetrics metrics) {
        this(settings, adminFactory, consumerFactory, handler, metrics, INIT_RETRY, RESTART_DELAY, POLL_TIMEOUT);
    }

    BattleResultIngest(Settings settings, Supplier<TopicAdmin> adminFactory, Supplier<Consumer<String, byte[]>> consumerFactory,
                       BattleResultConsumer.Handler handler, MatchMetrics metrics, Duration initRetry, Duration restartDelay,
                       Duration pollTimeout) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.adminFactory = Objects.requireNonNull(adminFactory, "adminFactory");
        this.consumerFactory = Objects.requireNonNull(consumerFactory, "consumerFactory");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.initRetry = initRetry;
        this.restartDelay = restartDelay;
        this.pollTimeout = pollTimeout;
        this.topic = BattleResultTopics.name(settings.topicGeneration());
    }

    /** 消费的 topic 名（{@code xm-battle-result-g<代次>}）。 */
    public String topic() {
        return topic;
    }

    /**
     * 生产用的 KafkaConsumer 工厂：关自动提交（入账成功才提交）、新消费组从最早位点开始（match 晚于 battle 启动时先发的结果不能丢）、
     * 不许自动建 topic（自动建出来的是 broker 默认分区数）。同组的多个 match 实例分摊分区，每条结果只被一个实例消费。
     *
     * @param group    消费组（{@code xm.match.rating.consumer-group}，缺省 {@code xm-match-rating}）
     * @param clientId Kafka 客户端标识（只进 broker 日志与客户端指标）
     */
    public static Supplier<Consumer<String, byte[]>> kafkaConsumers(String bootstrapServers, String group, String clientId) {
        return () -> {
            Properties p = new Properties();
            p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            p.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Integer.toString(MAX_POLL_RECORDS));
            p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "500");
            return new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer());
        };
    }

    /**
     * @throws AuditTopicContractException topic 的分区数与契约不符（进程拒绝启动）
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!settings.enabled()) {
            log.warn("[rating] xm.match.rating.enabled=false：不消费对局结果，评分停在已有值（新号 1500），凑单退化为纯等待序");
            return;
        }
        running = true;
        CountDownLatch signal = new CountDownLatch(1);
        stopSignal = signal;
        boolean verified;
        try {
            // 第一次核对在启动线程上同步做：分区契约不符直接抛出、进程拒绝启动
            verified = verify();
        } catch (RuntimeException e) {
            running = false;
            throw e;
        }
        initThread = new Thread(() -> {
            boolean ok = verified;
            while (running && !ok) {
                try {
                    if (signal.await(initRetry.toMillis(), TimeUnit.MILLISECONDS)) {
                        return;
                    }
                    ok = verify();
                } catch (InterruptedException e) {
                    return;
                } catch (AuditTopicContractException e) {
                    log.error("[rating] 对局结果 topic 与契约不符，消费者不启动（评分停更），需人工处理：{}", e.getMessage());
                    return;
                }
            }
            if (running) {
                startConsumer(signal);
            }
        }, INIT_THREAD_NAME);
        initThread.setDaemon(true);
        initThread.start();
    }

    /** @return true = 核对通过；false = Kafka 暂时不可达（稍后重试）。分区数不符抛 {@link AuditTopicContractException}。 */
    private boolean verify() {
        try (TopicAdmin admin = adminFactory.get()) {
            BattleResultTopics.ensure(admin, settings.topicGeneration(), AuditTopicInitializer.Mode.OWN, settings.replicationFactor(),
                    settings.initTimeout());
            return true;
        } catch (AuditTopicContractException e) {
            throw e;
        } catch (RuntimeException e) {
            // 连不上、地址解析不了（容器 / k8s 服务名尚未注册）等：都可恢复，稍后重试；只有契约不符才拒绝启动
            log.warn("[rating] 对局结果 topic {} 暂时核对不了（Kafka 不可达），评分暂停更新，{} 后重试：{}", topic, initRetry, e.toString());
            return false;
        }
    }

    private synchronized void startConsumer(CountDownLatch signal) {
        if (!running) {
            return;
        }
        Thread thread = new Thread(() -> {
            // 监督：消费循环意外退出（非停止）时换一个新的 KafkaConsumer 重来；未提交的记录会被重新消费
            while (running) {
                BattleResultConsumer current;
                try {
                    current = new BattleResultConsumer(consumerFactory.get(), topic, handler, metrics, pollTimeout, RETRY_INITIAL, RETRY_MAX);
                } catch (RuntimeException e) {
                    log.error("[rating] 创建对局结果的 KafkaConsumer 失败，{} 后重试", restartDelay, e);
                    if (awaitStop(signal, restartDelay)) {
                        return;
                    }
                    continue;
                }
                loop = current;
                if (!running) {
                    // stop() 可能在发布这个循环之前读的 loop：自己收尾（run 会订阅后立即退出并关闭消费者）
                    current.stop();
                }
                try {
                    current.run();
                } catch (RuntimeException e) {
                    log.error("[rating] 对局结果消费循环异常，{} 后重启", restartDelay, e);
                    if (awaitStop(signal, restartDelay)) {
                        return;
                    }
                }
            }
        }, THREAD_NAME);
        thread.setDaemon(true);
        consumerThread = thread;
        thread.start();
        log.info("[rating] 对局结果消费者已启动 topic={}", topic);
    }

    @Override
    public void stop() {
        Thread init;
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            init = initThread;
        }
        stopSignal.countDown();
        if (init != null) {
            // 可能正卡在一次核对里（至多 init-timeout）：打断它
            init.interrupt();
            join(init);
        }
        BattleResultConsumer current = loop;
        if (current != null) {
            current.stop();
        }
        Thread thread = consumerThread;
        if (thread != null) {
            join(thread);
        }
        log.info("[rating] 对局结果消费者已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 消费线程是否已经起来（核对通过之后才为真；测试与排障用）。 */
    boolean consuming() {
        Thread thread = consumerThread;
        return thread != null && thread.isAlive();
    }

    private static void join(Thread thread) {
        try {
            thread.join(JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 等一段时间或等到停止。@return true = 已停止（或被中断），调用方退出 */
    private static boolean awaitStop(CountDownLatch signal, Duration delay) {
        try {
            return signal.await(delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
