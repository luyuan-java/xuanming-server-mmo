package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.robot.client.RobotException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 批次 5.3 指标（dungeon-mirror-spec §8.2）的 Prometheus 名、按标签求和的增量、「指标在不在」的判定与轮询。
 * Prometheus 文本按 Micrometer 的导出形状写（标签按名字排序、计数器带 {@code _total}、值带小数点）。
 */
class InstanceMetricsTest {

    private static final String BEFORE = """
            # HELP xm_scene_instance_lifecycle_total 镜像 / 副本实例的生命周期事件
            # TYPE xm_scene_instance_lifecycle_total counter
            xm_scene_instance_lifecycle_total{event="created",kind="mirror"} 2.0
            xm_scene_instance_lifecycle_total{event="created",kind="dungeon"} 1.0
            xm_scene_instance_lifecycle_total{event="destroyed_idle",kind="mirror"} 0.0
            xm_scene_instances{kind="mirror",state="active"} 1.0
            xm_scene_instances{kind="mirror",state="reclaiming"} 0.0
            xm_scene_mirror_requests_total{result="accepted"} 3.0
            xm_scene_mirror_requests_total{result="bad_mirror_config"} 0.0
            xm_scene_manager_instance_seconds_count{kind="mirror",result="ok"} 2.0
            xm_scene_manager_instance_seconds_count{kind="mirror",result="ok_but_not_this"} 9.0
            """;

    private static final String AFTER = BEFORE
            .replace("{event=\"created\",kind=\"mirror\"} 2.0", "{event=\"created\",kind=\"mirror\"} 4.0")
            .replace("{event=\"destroyed_idle\",kind=\"mirror\"} 0.0", "{event=\"destroyed_idle\",kind=\"mirror\"} 2.0")
            .replace("{result=\"bad_mirror_config\"} 0.0", "{result=\"bad_mirror_config\"} 1.0")
            .replace("{kind=\"mirror\",result=\"ok\"} 2.0", "{kind=\"mirror\",result=\"ok\"} 4.0");

    @Test
    void 指标名就是scene与scene_manager导出的Prometheus名() {
        assertThat(InstanceMetrics.INSTANCES).isEqualTo("xm_scene_instances");
        assertThat(InstanceMetrics.LIFECYCLE).isEqualTo("xm_scene_instance_lifecycle_total");
        assertThat(InstanceMetrics.MIRROR_REQUESTS).isEqualTo("xm_scene_mirror_requests_total");
        assertThat(InstanceMetrics.MIRROR_RESOLVES).isEqualTo("xm_scene_mirror_resolves_total");
        assertThat(InstanceMetrics.SM_INSTANCE).isEqualTo("xm_scene_manager_instance_seconds_count");
        assertThat(InstanceMetrics.event("created")).isEqualTo("event=\"created\"");
        assertThat(InstanceMetrics.result("ok")).isEqualTo("result=\"ok\"");
    }

    @Test
    void 增量按全部标签过滤_标签不在首位也认_取值不做前缀匹配() {
        assertThat(InstanceMetrics.delta(BEFORE, AFTER, InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_MIRROR,
                InstanceMetrics.event("created"))).isEqualTo(2.0);
        assertThat(InstanceMetrics.delta(BEFORE, AFTER, InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_DUNGEON,
                InstanceMetrics.event("created"))).isZero();
        assertThat(InstanceMetrics.delta(BEFORE, AFTER, InstanceMetrics.LIFECYCLE, InstanceMetrics.event("created")))
                .as("只按 event 过滤：两种合计").isEqualTo(2.0);
        assertThat(InstanceMetrics.delta(BEFORE, AFTER, InstanceMetrics.MIRROR_REQUESTS, InstanceMetrics.result("bad_mirror_config")))
                .isEqualTo(1.0);
        assertThat(InstanceMetrics.delta(BEFORE, AFTER, InstanceMetrics.SM_INSTANCE, InstanceMetrics.KIND_MIRROR,
                InstanceMetrics.result("ok"))).as("ok_but_not_this 不算 ok").isEqualTo(2.0);
        assertThat(InstanceMetrics.delta(BEFORE, AFTER, InstanceMetrics.INSTANCES, InstanceMetrics.KIND_MIRROR,
                InstanceMetrics.ACTIVE)).isZero();
    }

    @Test
    void 指标在不在_按完整指标名判定() {
        assertThat(InstanceMetrics.present(BEFORE, InstanceMetrics.LIFECYCLE)).isTrue();
        assertThat(InstanceMetrics.present(BEFORE, InstanceMetrics.MIRROR_RESOLVES)).isFalse();
        assertThat(InstanceMetrics.present("xm_scene_instances{kind=\"mirror\",state=\"active\"} 0.0\n",
                InstanceMetrics.INSTANCES)).as("在第一行").isTrue();
        assertThat(InstanceMetrics.present(BEFORE, "xm_scene_instance")).as("只是前缀").isFalse();
        assertThat(InstanceMetrics.present(null, InstanceMetrics.LIFECYCLE)).isFalse();
    }

    @Test
    void 轮询_增量够了立即返回_不够就到点返回最后一次() throws Exception {
        Deque<String> texts = new ArrayDeque<>(List.of(BEFORE, BEFORE, AFTER));
        MetricsSource source = () -> texts.size() > 1 ? texts.poll() : texts.peek();

        InstanceMetrics.Polled reached = InstanceMetrics.awaitDelta(source, BEFORE, 2, Duration.ofSeconds(5), Duration.ofMillis(1),
                InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_MIRROR, InstanceMetrics.event("destroyed_idle"));
        assertThat(reached.reached()).isTrue();
        assertThat(reached.delta()).isEqualTo(2.0);
        assertThat(reached.text()).isEqualTo(AFTER);

        InstanceMetrics.Polled timedOut = InstanceMetrics.awaitDelta(() -> BEFORE, BEFORE, 1, Duration.ofMillis(20),
                Duration.ofMillis(5), InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_MIRROR, InstanceMetrics.event("destroyed_idle"));
        assertThat(timedOut.reached()).isFalse();
        assertThat(timedOut.delta()).isZero();
        assertThat(timedOut.text()).isEqualTo(BEFORE);

        InstanceMetrics.Polled once = InstanceMetrics.awaitDelta(() -> AFTER, BEFORE, 1, Duration.ZERO, Duration.ofMillis(5),
                InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_MIRROR, InstanceMetrics.event("destroyed_idle"));
        assertThat(once.reached()).as("上限为 0 也至少抓一次").isTrue();
    }

    @Test
    void 轮询_抓取失败直接抛出() {
        MetricsSource broken = () -> {
            throw new RobotException("抓不到");
        };

        assertThatThrownBy(() -> InstanceMetrics.awaitDelta(broken, BEFORE, 1, Duration.ofSeconds(1), Duration.ofMillis(1),
                InstanceMetrics.LIFECYCLE)).isInstanceOf(RobotException.class).hasMessageContaining("抓不到");
    }
}
