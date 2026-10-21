package com.game.guild.activity;

import com.game.api.MatchInternalService;
import com.game.api.match.MatchRpcAttachments;
import com.game.audit.*;
import com.game.common.KafkaNames;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.guild.store.GuildActivityStore;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.match.StartActivityBattleResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.*;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 有界后台：原开战意图重投、奖励入队、战报提交后确认及Redis补拉。每进程一消费线程。 */
public final class GuildActivityRuntime implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(GuildActivityRuntime.class);
    @DubboReference(
            group = "match", url = "${game.guild.activity.match-url:tri://127.0.0.1:20888}", check = false, retries = 0, timeout = 3000)
    private MatchInternalService match;
    private final GuildActivityStore store;
    private final RedissonClient redis;
    private final RedisKeys keys;
    private final KafkaNames names;
    private final String brokers;
    private final int generation;
    private final boolean enabled;
    private final boolean globalMatch;
    private final MeterRegistry metrics;
    private final GuildActivityService.Effects effects;
    private final GuildTrialCancellation cancellation;
    private volatile boolean running;
    private volatile Consumer<String, byte[]> consumer;
    private volatile GuildActivityResults resultLoop;
    private ScheduledExecutorService timer;
    private Thread resultThread;
    public GuildActivityRuntime(GuildActivityStore store, RedissonClient redis, RedisKeys keys, KafkaNames names, String brokers,
            int generation, boolean enabled, boolean globalMatch, MeterRegistry metrics, GuildActivityService.Effects effects) {
        this(store,redis,keys,names,brokers,generation,enabled,globalMatch,metrics,effects,
                enabled ? GuildTrialCancellation.fromEnvironment() : null);
    }
    public GuildActivityRuntime(GuildActivityStore store, RedissonClient redis, RedisKeys keys, KafkaNames names, String brokers,
            int generation, boolean enabled, boolean globalMatch, MeterRegistry metrics, GuildActivityService.Effects effects,
            GuildTrialCancellation cancellation) {
        this.store = store;
        this.redis = redis;
        this.keys = keys;
        this.names = names;
        this.brokers = brokers;
        this.generation = generation;
        this.enabled = enabled;
        this.globalMatch = globalMatch;
        this.metrics = metrics;
        this.effects = effects;
        if (enabled && cancellation == null) throw new IllegalArgumentException("历练取消签名依赖未装配");
        this.cancellation = cancellation;
        for (String stage : List.of("launch", "reward", "result", "recover"))
            for (String outcome : List.of("ok", "error"))
                metrics.counter("game_guild_activity_background_total", "stage", stage, "outcome", outcome);
    }
    @Override
    public synchronized void start() {
        if (running || !enabled)
            return;
        running = true;
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("guild-activity-worker").daemon(true).factory());
        timer.scheduleWithFixedDelay(this::tick, 100, 500, TimeUnit.MILLISECONDS);
        resultThread = Thread.ofPlatform().name("guild-activity-results").daemon(true).start(this::consumeForever);
    }
    @Override
    public synchronized void stop() {
        running = false;
        if (resultLoop != null)
            resultLoop.stop();
        else if (consumer != null)
            consumer.wakeup();
        if (timer != null) {
            timer.shutdownNow();
            try {
                timer.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (resultThread != null)
            try {
                resultThread.join(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
    }
    @Override
    public boolean isRunning() {
        return running;
    }
    @Override
    public int getPhase() {
        return 100;
    }
    private void count(String stage, boolean ok) {
        metrics.counter("game_guild_activity_background_total", "stage", stage, "outcome", ok ? "ok" : "error").increment();
    }
    public void tick() {
        if (!running)
            return;
        try {
            store.enqueueRewards(System.currentTimeMillis(), Deadline.after(2500));
            count("reward", true);
        } catch (Exception e) {
            failed("reward", e);
        }
        try {
            for (long id : store.launches(Deadline.after(1000), System.currentTimeMillis())) {
                if (!running)
                    return;
                try { launch(id); }
                catch (Exception failure) {
                    store.deferLaunch(id,System.currentTimeMillis(),Deadline.after(1000));
                    failed("launch",failure);
                }
            }
            count("launch", true);
        } catch (Exception e) {
            failed("launch", e);
        }
        try {
            for (var battle : store.unsettled(Deadline.after(1500), System.currentTimeMillis())) {
                if (!running)
                    return;
                var bucket = redis.<byte[]>getBucket(keys.battleActivityResult(battle.id()), ByteArrayCodec.INSTANCE);
                byte[] raw = bucket.getAsync().toCompletableFuture().get(800, TimeUnit.MILLISECONDS);
                if (raw != null) {
                    try {
                        settle(BattleResultEvent.parseFrom(raw));
                    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
                        store.poison("redis-raw-activity", 0, battle.id(), raw, System.currentTimeMillis(), Deadline.after(1500));
                        bucket.compareAndSetAsync(raw, null).toCompletableFuture().get(800, TimeUnit.MILLISECONDS);
                    }
                } else {
                    store.expireBattle(battle.id(), System.currentTimeMillis(), Deadline.after(1000));
                    cancel(store.battleLaunch(battle.id(),Deadline.after(1000)));
                }
            }
            count("recover", true);
        } catch (Exception e) {
            failed("recover", e);
        }
    }
    private void launch(long id) throws Exception {
        long now = System.currentTimeMillis();
        var launch = store.leaseLaunch(id, now, Deadline.after(1000));
        if (launch == null)
            return;
        StartActivityBattleResponse response = null;
        try {
            response = MatchRpcAttachments.callWithBudget(3000, 3000, () -> match.startActivityBattle(launch.request()))
                               .get(3200, TimeUnit.MILLISECONDS);
        } catch (Exception unknown) {
            LOG.warn("历练开战结局未知，保留原意图重投 failure={}", unknown.getClass().getSimpleName());
        }
        store.completeLaunch(launch, response, System.currentTimeMillis(), Deadline.after(1500));
        if (response == null || response.getRejectValue() != 0 || response.getBattleId() == 0) {
            cancel(launch);
        }
        if (response != null && response.getRejectValue() >= 0 && response.getRejectValue() <= 4)
            try {
                effects.committed(launch.guild(), 0, launch.request().getMemberPlayerIdsList());
            } catch (Exception reminder) {
                LOG.warn("开战意图已更新，提醒待页面恢复 failure={}", reminder.getClass().getSimpleName());
            }
    }
    private void cancel(GuildActivityStore.Launch launch) {
        var request = cancellation.request(launch.request());
        try {
            var reply = MatchRpcAttachments.callWithBudget(3000,3000,() -> match.cancelActivityBattle(request))
                    .get(3200,TimeUnit.MILLISECONDS);
            store.completeCancellation(launch,cancellation.verify(request,reply),System.currentTimeMillis(),Deadline.after(1500));
        } catch (Exception unknown) {
            LOG.warn("历练取消结局未证明，保留原ACTIVE等待轮转 failure={}",unknown.getClass().getSimpleName());
        }
    }
    public void settle(BattleResultEvent event) throws Exception {
        if (event.getActivityContext().getKindValue() != 1)
            return;
        boolean poisoned = false;
        try {
            store.settle(event, System.currentTimeMillis(), Deadline.after(3500));
        } catch (GuildActivityStore.InvalidResult invalid) {
            store.poisonBattle(event.getBattleId(),"redis-activity", 0, event.getBattleId(), event.toByteArray(), System.currentTimeMillis(), Deadline.after(1500));
            poisoned = true;
            LOG.error("历练战报已持久隔离，未自动发奖 reason={}", invalid.getMessage());
        }
        // SQL结果/奖励意图已提交后才销账。原记录可能已被另一消费者确认，按字节CAS删除。
        byte[] raw = event.toByteArray();
        var bucket = redis.<byte[]>getBucket(keys.battleActivityResult(event.getBattleId()), ByteArrayCodec.INSTANCE);
        bucket.compareAndSetAsync(raw, null).toCompletableFuture().get(800, TimeUnit.MILLISECONDS);
        try {
            if (!poisoned)
                effects.committed(event.getActivityContext().getGuildId(), 0,
                        store.members(event.getActivityContext().getGuildId(), Deadline.after(1000)));
        } catch (Exception reminder) {
            LOG.warn("历练已结算，提醒待页面恢复 failure={}", reminder.getClass().getSimpleName());
        }
        count("result", !poisoned);
    }
    private void failed(String stage, Exception failure) {
        count(stage, false);
        LOG.warn("帮会活动后台保留原意图等待恢复 stage={} failure={}", stage, failure.getClass().getSimpleName());
    }
    private void consumeForever() {
        String client = names.guildTrialClientId(UUID.randomUUID().toString());
        var topics = new BattleResultTopics(names);
        while (running) {
            try (var admin = new KafkaTopicAdmin(brokers, client + "-admin")) {
                // 结果 topic 的主人是 Match；帮会不能抢先创建低副本 topic，未满足持久契约前不消费。
                if (globalMatch)
                    topics.ensureDurable(admin, generation, AuditTopicInitializer.Mode.VERIFY_ONLY, (short) 3, Duration.ofSeconds(3));
                else
                    topics.ensure(admin, generation, AuditTopicInitializer.Mode.VERIFY_ONLY, (short) 1, Duration.ofSeconds(3));
                var config = new Properties();
                config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
                config.put(ConsumerConfig.GROUP_ID_CONFIG, names.guildTrialGroup());
                config.put(ConsumerConfig.CLIENT_ID_CONFIG, client);
                config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
                config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "32");
                config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
                config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
                try (var current = new KafkaConsumer<String, byte[]>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
                    consumer = current;
                    var loop = new GuildActivityResults(
                            current, topics.name(generation), this::processResult, failure -> failed("result", failure));
                    resultLoop = loop;
                    if (!running)
                        loop.stop();
                    loop.run();
                }
            } catch (WakeupException stop) {
                if (running)
                    failed("result", stop);
            } catch (Exception failure) {
                if (running)
                    failed("result", failure);
            } finally {
                resultLoop = null;
                consumer = null;
            }
            if (running)
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    return;
                }
        }
    }
    private void processResult(ConsumerRecord<String, byte[]> record) throws Exception {
        if (record.value() == null) {
            store.poison(
                    record.topic(), record.partition(), record.offset(), new byte[0], System.currentTimeMillis(), Deadline.after(1500));
            return;
        }
        BattleResultEvent event;
        try {
            event = BattleResultEvent.parseFrom(record.value());
        } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
            store.poison(
                    record.topic(), record.partition(), record.offset(), record.value(), System.currentTimeMillis(), Deadline.after(1500));
            return;
        }
        settle(event);
    }
}
