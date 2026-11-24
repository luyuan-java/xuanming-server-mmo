package com.game.payment;

import com.game.payment.channel.ChannelTypes.ChannelException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 支付服务低基数监控指标（绝不以玩家号、订单号、交易号作 label）。
 */
public final class PaymentMetrics {

    private final MeterRegistry registry;
    private final AtomicReference<Double> mallOldestPendingSec = new AtomicReference<>(0.0);
    private final AtomicReference<Double> finalizeOldestPendingSec = new AtomicReference<>(0.0);

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("payment.mall.oldest.pending.seconds", mallOldestPendingSec, AtomicReference::get)
                .description("最老待同步商城付款已等待秒数")
                .register(registry);
        Gauge.builder("payment.finalize.oldest.pending.seconds", finalizeOldestPendingSec, AtomicReference::get)
                .description("最老待渠道收尾（Google consume）付款已等待秒数")
                .register(registry);
    }

    public void observeChannelCall(String channel, String op, Throwable err) {
        String outcome;
        if (err == null) {
            outcome = "ok";
        } else if (err instanceof ChannelException ce && ce.kind() == ChannelException.Kind.CALL_ERROR) {
            outcome = "rejected";
        } else {
            outcome = "error";
        }
        Counter.builder("payment.channel.calls")
                .tag("channel", channel)
                .tag("op", op)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void countNotification(String channel, String outcome) {
        Counter.builder("payment.notifications")
                .tag("channel", channel)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void countIntent(String channel, String outcome) {
        Counter.builder("payment.intents")
                .tag("channel", channel)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void countFact(String channel, String outcome) {
        Counter.builder("payment.facts")
                .tag("channel", channel)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void countMallPush(String outcome) {
        Counter.builder("payment.mall.pushes")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void countRefund(String channel, String outcome) {
        Counter.builder("payment.refunds")
                .tag("channel", channel)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void countMinorGate(String outcome) {
        Counter.builder("payment.minor.gate")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void setMallOldestPendingSeconds(double seconds) {
        mallOldestPendingSec.set(Math.max(0.0, seconds));
    }

    public void setFinalizeOldestPendingSeconds(double seconds) {
        finalizeOldestPendingSec.set(Math.max(0.0, seconds));
    }
}
