package com.game.chat.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * xm-chat 的低基数指标。对应基线 {@code chat_send_total{channel,outcome}} / {@code chat_pull_total{channel,outcome}}
 * （全部组合启动即注册），另加每个客户端请求的耗时（与 gate / login / friend 同一套 SLO 桶）。
 * 不以 player_id 作标签；频道只取枚举（未知数值记 unknown）。线程安全。
 */
public final class ChatMetrics {

    static final String SENDS = "xm.chat.sends";
    static final String PULLS = "xm.chat.pulls";
    static final String REQUESTS = "xm.chat.requests";

    public static final String UNROUTED = "unrouted";

    static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    public enum Channel { WORLD, PRIVATE, TEAM, SYSTEM, UNSPECIFIED, UNKNOWN }

    /** 一次发言 / 拉取的结局（同基线 svc.Outcome*）。 */
    public enum Outcome {
        /** 写入 / 拉取成功。 */
        OK,
        /** 同 request_id 重发且首发已写入：按成功回、不重复写。 */
        DUPLICATE,
        /** 同 request_id 重发时首发还没写完：回 1008 让客户端稍后重试。 */
        IN_FLIGHT,
        NO_SESSION,
        BAD_REQUEST,
        CHANNEL_UNAVAILABLE,
        TOO_LONG,
        RATE_LIMITED,
        STORAGE_ERROR
    }

    /** 一个客户端请求的结果（{@code xm.chat.requests{result}}）。 */
    public enum RequestResult { OK, BUSINESS_ERROR, INTERNAL_ERROR, BAD_REQUEST, UNSUPPORTED }

    private final MeterRegistry registry;
    private final Map<Channel, Map<Outcome, Counter>> sends = new EnumMap<>(Channel.class);
    private final Map<Channel, Map<Outcome, Counter>> pulls = new EnumMap<>(Channel.class);
    private final ConcurrentHashMap<String, Timer> requests = new ConcurrentHashMap<>();

    public ChatMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Channel channel : Channel.values()) {
            Map<Outcome, Counter> send = new EnumMap<>(Outcome.class);
            Map<Outcome, Counter> pull = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                send.put(outcome, Counter.builder(SENDS).description("发言的结局")
                        .tag("channel", tag(channel)).tag("outcome", tag(outcome)).register(registry));
                pull.put(outcome, Counter.builder(PULLS).description("拉取历史的结局")
                        .tag("channel", tag(channel)).tag("outcome", tag(outcome)).register(registry));
            }
            sends.put(channel, send);
            pulls.put(channel, pull);
        }
    }

    public void send(Channel channel, Outcome outcome) {
        sends.get(channel).get(outcome).increment();
    }

    public void pull(Channel channel, Outcome outcome) {
        pulls.get(channel).get(outcome).increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void requestCompleted(Timer.Sample sample, String method, RequestResult result) {
        String key = method + '\0' + result.name();
        Timer timer = requests.get(key);
        if (timer == null) {
            timer = requests.computeIfAbsent(key, k -> Timer.builder(REQUESTS)
                    .description("chat 处理客户端请求的耗时与结果")
                    .tag("method", method).tag("result", tag(result))
                    .serviceLevelObjectives(LATENCY_BUCKETS).register(registry));
        }
        sample.stop(timer);
    }

    static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
