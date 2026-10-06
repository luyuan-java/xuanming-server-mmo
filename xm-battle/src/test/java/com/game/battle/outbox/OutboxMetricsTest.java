package com.game.battle.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.outbox.OutboxMetrics.ActivityEvent;
import com.game.battle.outbox.OutboxMetrics.Delivery;
import com.game.battle.outbox.OutboxMetrics.SettlementEvent;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * battle 侧发件箱指标的名字、标签与预建（scene-battle-spec §9 battle 段；审计 OPS-17、OBX-7 / OBX-10 新增的两个取值）：按 Prometheus 导出格式核对。
 * 不预建的话「从没发生」与「指标不存在」分不开，{@code not_durable > 0} 这类告警既不报警也不报错。
 */
class OutboxMetricsTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final OutboxMetrics metrics = new OutboxMetrics(registry);

    private static Set<String> lower(Enum<?>[] values) {
        return Arrays.stream(values).map(v -> v.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Test
    void 结算发件箱事件_规格的13个取值加already_settled与fields_overflow_全部以0预建() {
        assertThat(lower(SettlementEvent.values())).containsExactlyInAnyOrder("stored", "not_durable", "delivered", "resend",
                "skip_no_target", "superseded", "acked", "exhausted", "exhausted_offline", "expired", "probe_error", "locate_error",
                "serialize_failed", "already_settled", "fields_overflow");

        String scrape = registry.scrape();
        for (String event : lower(SettlementEvent.values())) {
            assertThat(scrape).contains("xm_battle_settlement_outbox_total{event=\"" + event + "\"} 0.0");
        }
    }

    @Test
    void 结算投递应答_7个取值以0预建() {
        assertThat(lower(Delivery.values())).containsExactlyInAnyOrder("applied", "already_applied", "discarded", "deferred", "not_here",
                "overloaded", "transport_error");

        String scrape = registry.scrape();
        for (String result : lower(Delivery.values())) {
            assertThat(scrape).contains("xm_battle_settlement_delivery_total{result=\"" + result + "\"} 0.0");
        }
    }

    @Test
    void 活动结果通道事件_8个取值以0预建() {
        assertThat(lower(ActivityEvent.values())).containsExactlyInAnyOrder("stored", "not_durable", "resend", "acked", "exhausted",
                "expired", "probe_error", "serialize_failed");

        String scrape = registry.scrape();
        for (String event : lower(ActivityEvent.values())) {
            assertThat(scrape).contains("xm_battle_activity_result_outbox_total{event=\"" + event + "\"} 0.0");
        }
    }

    @Test
    void 条目数是gauge_推绝对值() {
        assertThat(registry.scrape()).contains("xm_battle_settlement_outbox_entries 0.0");

        metrics.entries(3);
        assertThat(registry.scrape()).contains("xm_battle_settlement_outbox_entries 3.0");
        metrics.entries(0);
        assertThat(registry.scrape()).contains("xm_battle_settlement_outbox_entries 0.0");
    }

    @Test
    void 计数落在对应的取值上() {
        metrics.settlement(SettlementEvent.ALREADY_SETTLED);
        metrics.settlement(SettlementEvent.FIELDS_OVERFLOW);
        metrics.settlement(SettlementEvent.FIELDS_OVERFLOW);
        metrics.delivery(Delivery.NOT_HERE);
        metrics.activity(ActivityEvent.EXPIRED);

        String scrape = registry.scrape();
        assertThat(scrape).contains("xm_battle_settlement_outbox_total{event=\"already_settled\"} 1.0",
                "xm_battle_settlement_outbox_total{event=\"fields_overflow\"} 2.0",
                "xm_battle_settlement_outbox_total{event=\"stored\"} 0.0",
                "xm_battle_settlement_delivery_total{result=\"not_here\"} 1.0",
                "xm_battle_activity_result_outbox_total{event=\"expired\"} 1.0");
    }

    @Test
    void 标签只有有界枚举_没有玩家号战斗号节点号() {
        Set<String> tagKeys = registry.getMeters().stream().map(Meter::getId).flatMap(id -> id.getTags().stream()).map(Tag::getKey)
                .collect(Collectors.toSet());

        assertThat(tagKeys).containsExactlyInAnyOrder("event", "result");
        assertThat(registry.getMeters()).as("15 + 7 + 8 个计数器 + 1 个 gauge").hasSize(31);
    }
}
