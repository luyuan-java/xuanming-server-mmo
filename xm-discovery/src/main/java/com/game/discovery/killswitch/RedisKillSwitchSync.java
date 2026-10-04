package com.game.discovery.killswitch;

import com.game.common.killswitch.KillSwitch;
import com.game.discovery.RedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 热关停规则源：Redis 哈希 {@code xm:killswitch}（字段 = 规则键，如 {@code ClientPlayerFriend/AddFriend}、{@code friendpb.ClientPlayerFriend/*}、
 * {@code *}；值 = 规则，见 {@link KillSwitch#parseRule}）。按固定间隔全量读一次换进 {@link KillSwitch}（基线是 etcd list-watch；
 * Java 版没有 etcd，规则少、全量读一个小哈希每秒一次足够便宜）。
 *
 * <p>读失败只记指标与限频日志、保留上一份快照；失联超过 {@link KillSwitch} 的作废时长后快照自动作废、整体放行（fail-open）。
 * 写坏的规则值忽略并记 ERROR（同一个值只记一次）。字段名去首尾空白与开头的 {@code /}，去完为空的忽略（基线 PatternFromKey）。
 */
public final class RedisKillSwitchSync implements AutoCloseable, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(RedisKillSwitchSync.class);

    static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final RedissonClient redis;
    private final KillSwitch killSwitch;
    private final Duration interval;
    private final AtomicLong syncFailures = new AtomicLong();
    private final AtomicLong lastFailureLog = new AtomicLong();
    private final AtomicReference<Map<String, String>> lastRaw = new AtomicReference<>(Map.of());
    private ScheduledExecutorService scheduler;

    public RedisKillSwitchSync(RedissonClient redis, KillSwitch killSwitch, Duration interval) {
        this.redis = redis;
        this.killSwitch = killSwitch;
        this.interval = interval;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("killswitch-sync").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::syncSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("[killswitch] 开始同步规则 key={} 间隔={}", RedisKeys.killSwitch(), interval);
    }

    private void syncSafely() {
        try {
            syncOnce();
        } catch (Throwable t) {
            syncFailures.incrementAndGet();
            long now = System.nanoTime();
            long last = lastFailureLog.get();
            if ((last == 0 || now - last >= FAILURE_LOG_INTERVAL_NANOS) && lastFailureLog.compareAndSet(last, now)) {
                log.error("[killswitch] 同步规则失败（不影响放行，失联超过作废时长后整体放行）: {}", t.toString());
            }
        }
    }

    /**
     * 全量读一次并换快照（测试直调）。几个原始字段规范化后是同一条规则（手写的 {@code /X} 与接口写的 {@code X}）时，
     * 已是规范形的那个字段胜出，其余按字段名字典序取第一个（确定性，不依赖 HGETALL 的返回顺序），并记 ERROR。
     */
    public void syncOnce() {
        Map<String, String> raw = redis.<String, String>getMap(RedisKeys.killSwitch(), StringCodec.INSTANCE).readAllMap();
        Map<String, String> previous = lastRaw.getAndSet(Map.copyOf(raw));
        boolean changed = !raw.equals(previous);
        Map<String, KillSwitch.Rule> rules = new HashMap<>();
        Map<String, String> winners = new HashMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(raw).entrySet()) {
            String pattern = pattern(e.getKey());
            if (pattern.isEmpty()) {
                continue;
            }
            Optional<KillSwitch.Rule> rule = KillSwitch.parseRule(e.getValue());
            if (rule.isEmpty()) {
                if (!e.getValue().equals(previous.get(e.getKey()))) {
                    log.error("[killswitch] 规则值无法解析，已忽略: key={} value={}", e.getKey(), e.getValue());
                }
                continue;
            }
            String winner = winners.get(pattern);
            if (winner != null) {
                boolean replace = e.getKey().equals(pattern);
                if (changed) {
                    log.error("[killswitch] 多个字段规范化后是同一条规则 {}：{} 与 {}，取 {}（请删掉多余的字段）", pattern, winner, e.getKey(),
                            replace ? e.getKey() : winner);
                }
                if (!replace) {
                    continue;
                }
            }
            winners.put(pattern, e.getKey());
            rules.put(pattern, rule.get());
            if (!e.getValue().equals(previous.get(e.getKey()))) {
                log.warn("[killswitch] 规则更新: {} deny={} reason={}", pattern, rule.get().deny(), rule.get().reason());
            }
        }
        for (String key : previous.keySet()) {
            if (!raw.containsKey(key)) {
                log.warn("[killswitch] 规则移除: {}", key);
            }
        }
        killSwitch.setRules(rules);
    }

    static String pattern(String field) {
        return KillSwitch.normalizePattern(field);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("xm.killswitch.rules", killSwitch, KillSwitch::ruleCount)
                .description("当前生效的热关停规则条数（正常应为 0）").register(registry);
        FunctionCounter.builder("xm.killswitch.sync.failures", syncFailures, AtomicLong::get)
                .description("热关停规则同步失败次数（不影响放行）").register(registry);
        killSwitch.onBlocked(method -> Counter.builder("xm.killswitch.blocked")
                .description("被热关停规则短路掉的调用次数（method 只取客户端白名单方法与本仓库 Dubbo 接口方法，基数有界）")
                .tag("method", method).register(registry).increment());
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
