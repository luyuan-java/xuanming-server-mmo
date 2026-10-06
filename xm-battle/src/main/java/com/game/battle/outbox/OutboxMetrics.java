package com.game.battle.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * battle 侧两个发件箱与结算投递的指标（scene-battle-spec §9 battle 段）。全部标签是本类的有界枚举；构造时预建全部组合。线程安全。
 */
public final class OutboxMetrics {

    static final String SETTLEMENT_OUTBOX = "xm.battle.settlement.outbox";
    static final String SETTLEMENT_OUTBOX_ENTRIES = "xm.battle.settlement.outbox.entries";
    static final String SETTLEMENT_DELIVERY = "xm.battle.settlement.delivery";
    static final String ACTIVITY_OUTBOX = "xm.battle.activity.result.outbox";

    /**
     * 结算发件箱的事件（{@code xm_battle_settlement_outbox_total{event}}）。口径：
     * <ul>
     *   <li>{@link #DELIVERED} 是<b>发出次数</b>（首投 + 每次重投各计一次），不是首投数；一次重投同时计 {@link #RESEND} 与 {@code DELIVERED}，
     *       首投数 = {@code delivered − resend}。投递的应答另见 {@code xm_battle_settlement_delivery_total}。</li>
     *   <li>{@link #STORED} 只在落库脚本真的写入时计；落库时这一局已经销账（脚本回 -1、没有写）计 {@link #ALREADY_SETTLED}，两者互斥。</li>
     *   <li>{@link #FIELDS_OVERFLOW} 在 {@code STORED} 之外另计：落库后该玩家的待结算字段数超过 {@code SETTLEMENT_FIELDS_WARN}（记录在堆积）。</li>
     * </ul>
     */
    public enum SettlementEvent {
        STORED, NOT_DURABLE, DELIVERED, RESEND, SKIP_NO_TARGET, SUPERSEDED, ACKED, EXHAUSTED, EXHAUSTED_OFFLINE, EXPIRED, PROBE_ERROR,
        LOCATE_ERROR, SERIALIZE_FAILED,
        /** 落库时这一局已销账（已销账墓碑还在，脚本没有写入）：不登记、不投递。 */
        ALREADY_SETTLED,
        /** 落库后玩家的待结算字段数超过告警阈值（正常为 1；同时打 ERROR）。 */
        FIELDS_OVERFLOW
    }

    /** 一次结算投递的应答（只计数，不改发件箱状态，D16）。 */
    public enum Delivery {
        APPLIED, ALREADY_APPLIED, DISCARDED, DEFERRED, NOT_HERE, OVERLOADED, TRANSPORT_ERROR
    }

    /** 活动结果发件箱的事件。 */
    public enum ActivityEvent {
        STORED, NOT_DURABLE, RESEND, ACKED, EXHAUSTED, EXPIRED, PROBE_ERROR, SERIALIZE_FAILED
    }

    private final Map<SettlementEvent, Counter> settlementEvents = new EnumMap<>(SettlementEvent.class);
    private final Map<Delivery, Counter> deliveries = new EnumMap<>(Delivery.class);
    private final Map<ActivityEvent, Counter> activityEvents = new EnumMap<>(ActivityEvent.class);
    private final AtomicInteger entries = new AtomicInteger();

    public OutboxMetrics(MeterRegistry registry) {
        for (SettlementEvent event : SettlementEvent.values()) {
            settlementEvents.put(event, Counter.builder(SETTLEMENT_OUTBOX).description("结算发件箱（先落库、后投递、未销账就有界重投，§7.15）")
                    .tag("event", lower(event)).register(registry));
        }
        for (Delivery result : Delivery.values()) {
            deliveries.put(result, Counter.builder(SETTLEMENT_DELIVERY).description("结算投递的应答（只计数，D16）")
                    .tag("result", lower(result)).register(registry));
        }
        for (ActivityEvent event : ActivityEvent.values()) {
            activityEvents.put(event, Counter.builder(ACTIVITY_OUTBOX).description("活动结果持久通道（§7.17）")
                    .tag("event", lower(event)).register(registry));
        }
        Gauge.builder(SETTLEMENT_OUTBOX_ENTRIES, entries, AtomicInteger::get).description("结算发件箱里登记的条目数").register(registry);
    }

    /** 不导出（测试用）。 */
    public static OutboxMetrics noop() {
        return new OutboxMetrics(new CompositeMeterRegistry());
    }

    public void settlement(SettlementEvent event) {
        settlementEvents.get(event).increment();
    }

    public void delivery(Delivery result) {
        deliveries.get(result).increment();
    }

    public void activity(ActivityEvent event) {
        activityEvents.get(event).increment();
    }

    /** 结算发件箱的条目数（发件箱线程推绝对值）。 */
    public void entries(int count) {
        entries.set(count);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
