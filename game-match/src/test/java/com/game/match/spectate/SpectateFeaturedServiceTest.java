package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.RunMode;
import com.game.common.deadline.Deadline;
import com.game.common.token.SpectateTickets;
import com.game.match.MatchProperties;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemorySpectateTierStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.proto.eSpectateTier;
import com.game.proto.eSpectateVisibility;
import com.game.proto.match.SetSpectateFeaturedRequest;
import com.game.proto.match.SetSpectateFeaturedResponse;
import com.game.proto.match.SpectateFeaturedReject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 赛事局标记（mmorpg battle-spectate-tiered-fanout.md §15.6）与观战中继的启动门禁。 */
class SpectateFeaturedServiceTest {

    private static final long X = 7_000_000_077L;

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore();
    private final InMemorySpectateStore store = new InMemorySpectateStore(clock, new InMemoryTicketStore(clock), placements);
    private final InMemorySpectateTierStore tiers = new InMemorySpectateTierStore();
    private final SpectateRelayDialer dialer = mock(SpectateRelayDialer.class);
    private final BattleInstanceCheck instances = new BattleInstanceCheck(new com.game.match.testing.FakeBattleNodes());
    private final SpectateRelay relay = new SpectateRelay(tiers, dialer, SpectateTickets.ofUtf8("featured-test-secret-0123456789abcdefgh"), Duration.ofSeconds(60), instances);
    private final SpectateFeaturedService service = new SpectateFeaturedService(store, tiers, relay);

    private void open(eSpectateVisibility visibility) {
        long now = clock.peekMs();
        placements.put(BattlePlacement.newBuilder().setBattleId(X).setBattleNodeId(7).setBattleInstanceId("inst-a").setRpcHost("10.1.1.1").setRpcPort(21200)
                .setAttempt(1).setCreatedAtMs(now).setDeadlineMs(now + 300_000).setSpectateVisibility(visibility.getNumber()).build());
    }

    private SetSpectateFeaturedResponse set(boolean featured) {
        return service.set(SetSpectateFeaturedRequest.newBuilder().setBattleId(X).setFeatured(featured).build(), Deadline.after(1000));
    }

    @Test
    void 记录不在_NOT_FOUND_私密局_PRIVATE_都不碰标记与battle() {
        assertThat(set(true).getReject()).isEqualTo(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_NOT_FOUND);

        open(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE);
        assertThat(set(true).getReject()).isEqualTo(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_PRIVATE);

        assertThat(tiers.featured(X)).isFalse();
        verify(dialer, never()).promote(any(), any(), any());
    }

    @Test
    void 公开局标记_立刻打开中继_升档_幂等() {
        open(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
        when(dialer.promote(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Enabled("inst-a", 1L));

        SetSpectateFeaturedResponse first = set(true);
        SetSpectateFeaturedResponse second = set(true);

        assertThat(first.getReject()).isEqualTo(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_NONE);
        assertThat(first.getTier()).isEqualTo(eSpectateTier.SPECTATE_TIER_BROADCAST);
        assertThat(second).isEqualTo(first);
        assertThat(tiers.featured(X)).isTrue();
    }

    @Test
    void 打开中继调不通_标记照写_保持原档_不算失败() {
        open(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
        when(dialer.promote(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Unavailable("TIMEOUT"));

        SetSpectateFeaturedResponse response = set(true);

        assertThat(response.getReject()).isEqualTo(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_NONE);
        assertThat(response.getTier()).isEqualTo(eSpectateTier.SPECTATE_TIER_DIRECT);
        assertThat(tiers.featured(X)).isTrue();
    }

    @Test
    void 房间已不在_NOT_FOUND_不留标记() {
        open(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
        when(dialer.promote(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Rejected(com.game.match.support.MatchTips.BATTLE_ROOM_NOT_FOUND));

        assertThat(set(true).getReject()).isEqualTo(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_NOT_FOUND);
        assertThat(tiers.featured(X)).isFalse();
    }

    @Test
    void 取消只删标记_不降档_不调battle() {
        open(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
        tiers.raise(X, eSpectateTier.SPECTATE_TIER_BROADCAST, Deadline.after(1000));
        tiers.setFeatured(X, true, Deadline.after(1000));

        SetSpectateFeaturedResponse response = set(false);

        assertThat(response.getTier()).isEqualTo(eSpectateTier.SPECTATE_TIER_BROADCAST);
        assertThat(tiers.featured(X)).isFalse();
        verify(dialer, never()).promote(any(), any(), any());
    }

    @Test
    void Redis故障_INTERNAL_可重试() {
        open(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC);
        tiers.failing = true;
        when(dialer.promote(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Enabled("inst-a", 1L));

        assertThat(set(true).getReject()).isEqualTo(SpectateFeaturedReject.SPECTATE_FEATURED_REJECT_INTERNAL);
    }

    // ================================================================ 启动门禁（SpectateRelayConfiguration）

    private static MatchProperties.Spectate config(boolean enabled, String secret) {
        return new MatchProperties.Spectate(null, null, enabled, secret, null);
    }

    private SpectateRelay relayOf(MatchProperties.Spectate config, RunMode mode, String gateSecret) {
        return SpectateRelayConfiguration.relayOf(config, mode, gateSecret, null, tiers, dialer, instances);
    }

    @Test
    void 开关关闭_不要密钥_prod也照常起() {
        assertThat(relayOf(config(false, ""), RunMode.PROD, null).enabled()).isFalse();
    }

    @Test
    void 开关打开_prod下空的太短的与gate相同的密钥都拒启() {
        String good = "x".repeat(32);

        assertThat(relayOf(config(true, good), RunMode.PROD, "gate-secret").enabled()).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> relayOf(config(true, ""), RunMode.PROD, null));
        assertThatIllegalStateException().isThrownBy(() -> relayOf(config(true, "short"), RunMode.PROD, null));
        assertThatIllegalStateException().isThrownBy(() -> relayOf(config(true, good), RunMode.PROD, good));
    }

    @Test
    void 开关打开_dev下空密钥按关闭处理_太短只告警() {
        assertThat(relayOf(config(true, ""), RunMode.DEV, null).enabled()).isFalse();
        assertThat(relayOf(config(true, "short"), RunMode.TEST, null).enabled()).isTrue();
    }

    @Test
    void 配置的toString不打印密钥() {
        assertThat(config(true, "very-secret-value").toString()).doesNotContain("very-secret-value").contains("<set>");
        assertThat(new MatchProperties.Spectate(null, null).ticketTtlSeconds()).isEqualTo(60);
        assertThat(new MatchProperties.Spectate(null, null).relayEnabled()).as("中继缺省打开：Java 全栈共用一套 Redis，没有跨 Kafka 集群的顾虑").isTrue();
        assertThat(config(false, "").relayEnabled()).as("显式关闭仍然生效").isFalse();
    }
}
