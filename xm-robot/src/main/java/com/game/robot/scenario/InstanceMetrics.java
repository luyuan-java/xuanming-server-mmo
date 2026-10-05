package com.game.robot.scenario;

import com.game.robot.client.AdminClient;
import com.game.robot.client.RobotException;
import java.time.Duration;

/**
 * 批次 5.3 的指标名（dungeon-mirror-spec §8.2，Prometheus 名）与差值计算。标签只有 kind / state / event / result，没有场景号与玩家号。
 */
final class InstanceMetrics {

    /** scene：实例数 Gauge，{@code kind = mirror / dungeon}、{@code state = active / reclaiming / draining}。 */
    static final String INSTANCES = "xm_scene_instances";
    /** scene：实例生命周期 Counter，{@code kind}、{@code event = created / rejected / reclaim_started / revived / cascade_started / destroyed_*}。 */
    static final String LIFECYCLE = "xm_scene_instance_lifecycle_total";
    /** scene：63 镜像分支的同步结局 Counter，{@code result = accepted / bad_source / bad_mirror_config / …}。 */
    static final String MIRROR_REQUESTS = "xm_scene_mirror_requests_total";
    /** scene：建镜像的异步结局 Counter，{@code result = created / rejected / error / stale / …}。 */
    static final String MIRROR_RESOLVES = "xm_scene_mirror_resolves_total";
    /** scene-manager：{@code createInstance} 的 Timer 计数，{@code kind = mirror / dungeon}、{@code result = ok / bad_request / …}。 */
    static final String SM_INSTANCE = "xm_scene_manager_instance_seconds_count";

    static final String KIND_MIRROR = "kind=\"mirror\"";
    static final String KIND_DUNGEON = "kind=\"dungeon\"";
    static final String ACTIVE = "state=\"active\"";

    /** 轮询指标的间隔。 */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private InstanceMetrics() {
    }

    static String event(String name) {
        return "event=\"" + name + "\"";
    }

    static String result(String name) {
        return "result=\"" + name + "\"";
    }

    /** 两次抓取之间名为 {@code metric}、标签包含全部 {@code labels} 的序列之和的增量。 */
    static double delta(String before, String after, String metric, String... labels) {
        return AdminClient.sum(after, metric, labels) - AdminClient.sum(before, metric, labels);
    }

    /** 文本里有没有这个指标的任何一条序列（判断「指标缺失」与「本节点没增长」）。 */
    static boolean present(String text, String metric) {
        return text != null && (text.startsWith(metric + "{") || text.contains("\n" + metric + "{"));
    }

    /** 一次轮询的结果：最后一次抓到的文本、是否已达到期望增量、最后的增量。 */
    record Polled(String text, boolean reached, double delta) {
    }

    /**
     * 每 {@code interval} 抓一次，直到 {@code metric{labels}} 相对 {@code baseline} 的增量 ≥ {@code want}，或超过 {@code timeout}（至少抓一次）。
     * 抓取失败直接抛出。
     */
    static Polled awaitDelta(MetricsSource source, String baseline, double want, Duration timeout, Duration interval,
                             String metric, String... labels) throws RobotException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            String text = source.scrape();
            double grown = delta(baseline, text, metric, labels);
            if (grown >= want) {
                return new Polled(text, true, grown);
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return new Polled(text, false, grown);
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(left, interval.toNanos())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RobotException("等指标增长被中断", e);
            }
        }
    }
}
