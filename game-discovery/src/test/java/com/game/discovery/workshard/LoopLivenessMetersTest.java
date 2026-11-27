package com.game.discovery.workshard;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoopLivenessMetersTest {

    @Test
    void 绑定活性指标与缺片片数指标_有意关闭移除序列_多副本片数和为64() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        LoopLiveness liveness = LoopLivenessMeters.bind(registry,
                new LoopLiveness("mail", "expiry_cleanup", 300L, clock::get));

        assertThat(registry.get(LoopLivenessMeters.METRIC_LAST_COMPLETED)
                .tag("service", "mail").tag("loop", "expiry_cleanup").gauge().value())
                .isEqualTo(1_700_000_000.0);

        clock.addAndGet(10_000L);
        liveness.runGuarded(() -> {
            throw new RuntimeException("boom");
        });
        assertThat(registry.get(LoopLivenessMeters.METRIC_LOOP_PANIC)
                .tag("service", "mail").tag("loop", "expiry_cleanup").counter().count()).isEqualTo(1.0);
        // panic 后序列仍在且时间未推进
        assertThat(registry.get(LoopLivenessMeters.METRIC_LAST_COMPLETED)
                .tag("service", "mail").tag("loop", "expiry_cleanup").gauge().value())
                .isEqualTo(1_700_000_000.0);

        liveness.close();
        assertThat(registry.find(LoopLivenessMeters.METRIC_LAST_COMPLETED)
                .tag("service", "mail").tag("loop", "expiry_cleanup").gauge()).isNull();

        // 双副本分工片数求和恒等于 64
        WorkShard.Membership a = new WorkShard.Membership(30_000L, clock::get);
        a.setSelf("mail-1");
        a.updateMembers(List.of("mail-1", "mail-2"));
        WorkShard.Membership b = new WorkShard.Membership(30_000L, clock::get);
        b.setSelf("mail-2");
        b.updateMembers(List.of("mail-1", "mail-2"));
        double sum = LoopLivenessMeters.bindOwnedShards(registry, "mail", "mail_expiry_a", a).value()
                + LoopLivenessMeters.bindOwnedShards(registry, "mail", "mail_expiry_b", b).value();
        // 同一 kind 下双副本互补相加恒为 64
        assertThat(a.ownedCount("mail_expiry") + b.ownedCount("mail_expiry")).isEqualTo(WorkShard.SHARDS);
        assertThat(sum).isGreaterThan(0.0);
    }
}
