package com.game.guild.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetRpc;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import com.game.guild.asset.AssetOpAction;
import com.game.guild.asset.AssetOpMetrics.RpcOutcome;
import com.game.guild.asset.AssetOpMetrics.StoreOp;
import com.game.guild.asset.AssetOpStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 4.5 指标（guild-economy-spec §8.2）：全部标签组合启动即预建为 0（「从未发生」的序列也存在，{@code rate(...) > 0} 的告警才有意义）；流号只分三档、
 * 越界值归 other，不把原始数值放进 label；不以任何 id 作标签。
 */
class GuildMetricsEconomyTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GuildMetrics metrics = new GuildMetrics(meters);

    @Test
    void 经济与资产通道的标签组合启动即预建() {
        assertThat(meters.find("xm.guild.economy.requests").counters()).hasSize(5 * GuildMetrics.EconomyResult.values().length);
        assertThat(meters.find("xm.guild.asset.sync.skipped").counters()).hasSize(2);
        assertThat(meters.find("xm.guild.asset.orphans").counters()).hasSize(3);
        assertThat(meters.find("xm.guild.asset.cleanup.deleted").counters()).hasSize(2);
        assertThat(meters.find("xm.guild.internal.list.applied").counters()).hasSize(6);
        assertThat(meters.find("xm.guild.assetop.rpc").counters()).hasSize(3 * 3 * RpcOutcome.values().length);
        assertThat(meters.find("xm.guild.assetop.rpc.duration").timers()).hasSize(3);
        assertThat(meters.find("xm.guild.assetop.requery").counters()).hasSize(9);
        assertThat(meters.find("xm.guild.assetop.finalize").counters()).hasSize(3 * 4);
        assertThat(meters.find("xm.guild.assetop.reschedule").counters()).hasSize(3 * 3);
        assertThat(meters.find("xm.guild.assetop.claim").counters()).hasSize(3);
        assertThat(meters.find("xm.guild.assetop.ledger.read").counters()).hasSize(4);
        assertThat(meters.find("xm.guild.assetop.manual.resolve").counters()).hasSize(4);
        assertThat(meters.find("xm.guild.assetop.store.errors").counters()).hasSize(StoreOp.values().length);
        assertThat(meters.find("xm.guild.assetop.pending.oldest.age.seconds").gauges()).hasSize(2);
        assertThat(meters.find("xm.guild.scene.resolve").counters()).hasSize(ResolveResult.values().length);
        for (String name : List.of("xm.guild.assetop.unknown", "xm.guild.assetop.outcome.flip", "xm.guild.assetop.partial",
                "xm.guild.assetop.reschedule.lost")) {
            assertThat(meters.find(name).counters()).as(name).hasSize(3);
        }
        assertThat(meters.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags())
                .noneMatch(t -> t.getKey().equals("id") || t.getKey().endsWith("_id")));
    }

    @Test
    void 越界流号归other_计数与耗时() {
        metrics.rpc(99, AssetRpc.CREDIT, RpcOutcome.ERROR, 5_000_000);
        metrics.rpc(1, AssetRpc.DEBIT, RpcOutcome.APPLIED, 1_000_000);
        metrics.finalized(2, AssetOpStatus.APPLIED_PARTIAL);
        metrics.rescheduled(0, AssetOpAction.ALERT);
        metrics.resolved(ResolveResult.LEASE);
        assertThat(meters.get("xm.guild.assetop.rpc").tags("stream", "other", "rpc", "credit", "outcome", "error").counter()
                .count()).isEqualTo(1);
        assertThat(meters.get("xm.guild.assetop.rpc").tags("stream", "guild_debit", "rpc", "debit", "outcome", "applied").counter()
                .count()).isEqualTo(1);
        assertThat(meters.get("xm.guild.assetop.rpc.duration").tag("rpc", "debit").timer().count()).isEqualTo(1);
        assertThat(meters.get("xm.guild.assetop.finalize").tags("stream", "guild_credit", "status", "applied_partial").counter()
                .count()).isEqualTo(1);
        assertThat(meters.get("xm.guild.assetop.reschedule").tags("stream", "other", "reason", "alert").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("xm.guild.scene.resolve").tag("result", "lease").counter().count()).isEqualTo(1);
    }
}
