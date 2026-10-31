package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.match.MatchBudgets;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.token.SpectateTickets;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.match.testing.FakeBattleNodes;
import com.game.match.testing.FakeObserverDialer;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemorySpectateTierStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.proto.eSpectateTier;
import com.game.proto.eSpectateVisibility;
import com.game.proto.match.WatchBattleResponse;
import com.game.proto.match.eSpectateDelivery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 本地 163 的大厅档（mmorpg battle-spectate-tiered-fanout.md §15.6）：直连席满了转 LOBBY、已是中继档的不再撞直连席、开关关闭回 16021。battle 的开关调用是替身，
 * 凭证用真实 HMAC（gate 那一侧同一个实现验签）。
 */
class WatchBattleLobbyTest {

    private static final long PLAYER = 1001;
    private static final long X = 7_000_000_077L;
    private static final String SECRET = "watch-lobby-test-secret-0123456789abcdef";
    private static final long BUDGET_MS = MatchBudgets.DEFAULT_REQUEST_BUDGET_MS;
    private static final Duration TTL = Duration.ofSeconds(60);

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore(events);
    private final InMemorySpectateStore spectate = new InMemorySpectateStore(clock, tickets, placements, events);
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final FakeObserverDialer observers = new FakeObserverDialer(events);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final InMemorySpectateTierStore tiers = new InMemorySpectateTierStore();
    private final SpectateRelayDialer relayDialer = mock(SpectateRelayDialer.class);
    private final SpectateTickets verifier = SpectateTickets.ofUtf8(SECRET);
    private final FakeBattleNodes directory = new FakeBattleNodes();
    private final BattleInstanceCheck instances = new BattleInstanceCheck(directory);
    private final SpectateRelay relay = new SpectateRelay(tiers, relayDialer, verifier, TTL, instances);
    private final WatchBattleService service = new WatchBattleService(spectate, placements, players, tickets, observers, metrics, () -> 0.0,
            () -> new java.util.UUID(0, 1).toString(), Runnable::run).withRelay(relay);
    private BattlePlacement placement;

    @BeforeEach
    void open() {
        long now = clock.peekMs();
        placement = BattlePlacement.newBuilder().setBattleId(X).setBattleNodeId(7).setBattleInstanceId("inst-a").setRpcHost("10.1.1.1").setRpcPort(21200)
                .setAttempt(1).setMode(4).setBattleConfigId(1).addPlayerNames("甲").setCreatedAtMs(now).setDeadlineMs(now + 300_000)
                .setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC_VALUE).build();
        placements.put(placement);
        spectate.putWatchable(X, now);
        players.online(PLAYER, 1, 7);
    }

    private static SessionContext session() {
        return SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst-1").setSessionId(7).setZoneId(1).setPlayerId(PLAYER)
                .setAccount("acc-" + PLAYER).build();
    }

    private WatchBattleResponse watch() {
        return service.watch(session(), X, Deadline.after(BUDGET_MS));
    }

    private double outcome(String outcome) {
        return meters.get("game.match.watch.battle").tag("outcome", outcome).counter().count();
    }

    private void fullSeats() {
        observers.onAdd(X, new Outcome.Replied(MatchTips.SPECTATE_FULL));
    }

    private void relayGranted() {
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Enabled("inst-reply", 123_456L));
    }

    private void assertLobbyTicket(WatchBattleResponse response, String instance, long deadlineMs) {
        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getBattleId()).isEqualTo(X);
        assertThat(response.getDelivery()).isEqualTo(eSpectateDelivery.SPECTATE_DELIVERY_LOBBY);
        SpectateTickets.Verdict verdict = verifier.verify(response.getSpectateTicket(), PLAYER, X, clock.peekMs());
        assertThat(verdict.ok()).as("gate 用同一把密钥验得过").isTrue();
        assertThat(verdict.payload().getBattleInstanceId()).isEqualTo(instance);
        assertThat(verdict.payload().getDeadlineMs()).isEqualTo(deadlineMs);
        assertThat(verdict.payload().getExpireAtMs()).as("签发时刻 + TTL").isEqualTo(clock.peekMs() + TTL.toMillis());
    }

    private void assertNoMark() {
        assertThat(spectate.entry(PLAYER, Deadline.after(1000)).mark()).as("大厅档不留观战标记").isEmpty();
    }

    @Test
    void 没满员_走直连席_delivery是DIRECT_不碰中继() {
        WatchBattleResponse response = watch();

        assertThat(response.getDelivery()).isEqualTo(eSpectateDelivery.SPECTATE_DELIVERY_DIRECT);
        assertThat(response.getSpectateTicket().isEmpty()).isTrue();
        assertThat(tiers.peek(X)).isEqualTo(eSpectateTier.SPECTATE_TIER_DIRECT);
        verify(relayDialer, never()).attach(any(), any(), any());
        assertThat(outcome("ok")).isEqualTo(1);
    }

    @Test
    void 直连席满了_打开battle中继_记档位_回LOBBY与可验签的凭证_标记已回滚_实例与期限取battle回包() {
        fullSeats();
        relayGranted();

        WatchBattleResponse response = watch();

        assertLobbyTicket(response, "inst-reply", 123_456L);
        assertThat(tiers.peek(X)).isEqualTo(eSpectateTier.SPECTATE_TIER_RELAY);
        assertNoMark();
        assertThat(outcome("lobby")).isEqualTo(1);
        assertThat(outcome("full")).as("这次不算满员").isZero();
    }

    @Test
    void 已是中继档_不撞直连席_不调battle_不写标记_按落点记录签凭证() {
        tiers.raise(X, eSpectateTier.SPECTATE_TIER_RELAY, Deadline.after(1000));

        WatchBattleResponse response = watch();

        assertLobbyTicket(response, "inst-a", placement.getDeadlineMs());
        assertThat(observers.adds()).as("不进逐人名单").isEmpty();
        verify(relayDialer, never()).attach(any(), any(), any());
        assertNoMark();
    }

    @Test
    void 关闭开关_满员回16021_不碰档位() {
        WatchBattleService disabled = new WatchBattleService(spectate, placements, players, tickets, observers, metrics, () -> 0.0,
                () -> new java.util.UUID(0, 2).toString(), Runnable::run);
        fullSeats();

        WatchBattleResponse response = disabled.watch(session(), X, Deadline.after(BUDGET_MS));

        assertThat(response).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_FULL));
        assertThat(tiers.peek(X)).isEqualTo(eSpectateTier.SPECTATE_TIER_DIRECT);
        assertThat(outcome("full")).isEqualTo(1);
    }

    @Test
    void 打开中继时房间已不在_回不存在或已结束_不记档位() {
        fullSeats();
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Rejected(MatchTips.BATTLE_ROOM_NOT_FOUND));

        WatchBattleResponse response = watch();

        assertThat(response).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        assertThat(tiers.peek(X)).isEqualTo(eSpectateTier.SPECTATE_TIER_DIRECT);
        assertThat(outcome("not_found")).isEqualTo(1);
    }

    @Test
    void 打开中继调不通_不是结论_回16004_不记档位_不签凭证() {
        fullSeats();
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Unavailable("TIMEOUT"));

        WatchBattleResponse response = watch();

        assertThat(response).isEqualTo(MatchTips.watchRejected(MatchTip.BUSY));
        assertThat(response.getSpectateTicket().isEmpty()).isTrue();
        assertThat(tiers.peek(X)).isEqualTo(eSpectateTier.SPECTATE_TIER_DIRECT);
        assertThat(outcome("internal")).isEqualTo(1);
    }

    @Test
    void 打开中继被battle明确拒绝_回当前无法观战() {
        fullSeats();
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Rejected(MatchTips.INVALID_PARAMETER));

        assertThat(watch()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_WATCHABLE));
        assertThat(outcome("rejected")).isEqualTo(1);
    }

    @Test
    void 已是中继档而同节点号已是别的实例_按房间不存在处理_不签凭证_不撞直连席_懒剔除() {
        tiers.raise(X, eSpectateTier.SPECTATE_TIER_RELAY, Deadline.after(1000));
        directory.add(FakeBattleNodes.node(7, "inst-new", 21200));

        WatchBattleResponse response = watch();

        assertThat(response).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        assertThat(response.getSpectateTicket().isEmpty()).isTrue();
        assertThat(outcome("not_found")).isEqualTo(1);
        assertThat(observers.adds()).isEmpty();
        assertThat(spectate.read(X, Deadline.after(1000)).record()).as("房间随原进程没了：落点与索引一起摘").isInstanceOf(SpectateStore.Record.Absent.class);
    }

    @Test
    void 随机观战挑到实例已换的中继档_换下一场_没有可看的回没有可观战的战斗() {
        tiers.raise(X, eSpectateTier.SPECTATE_TIER_RELAY, Deadline.after(1000));
        directory.add(FakeBattleNodes.node(7, "inst-new", 21200));

        WatchBattleResponse response = service.watch(session(), 0, Deadline.after(BUDGET_MS));

        assertThat(response).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NO_BATTLE));
        assertThat(outcome("no_battle")).isEqualTo(1);
    }

    @Test
    void 已是中继档而目录里查不到这个节点号_不判_照常签凭证() {
        tiers.raise(X, eSpectateTier.SPECTATE_TIER_RELAY, Deadline.after(1000));

        assertLobbyTicket(watch(), "inst-a", placement.getDeadlineMs());
    }

    @Test
    void 读档位失败_fail_closed回16004_不抢标记不调battle() {
        tiers.failing = true;

        WatchBattleResponse response = watch();

        assertThat(response).isEqualTo(MatchTips.watchRejected(MatchTip.BUSY));
        assertThat(observers.adds()).isEmpty();
        assertNoMark();
        assertThat(outcome("internal")).isEqualTo(1);
    }

    @Test
    void 开关已开而记档位失败_这一次照常回LOBBY_下一个观众多撞一次直连席而已() {
        fullSeats();
        relayGranted();
        SpectateTierStore writeFails = new SpectateTierStore() {
            @Override
            public eSpectateTier tier(long battleId, Deadline d) {
                return eSpectateTier.SPECTATE_TIER_DIRECT;
            }

            @Override
            public eSpectateTier raise(long battleId, eSpectateTier target, Deadline d) {
                throw new Deadline.DependencyException("注入的 Redis 故障");
            }

            @Override
            public void setFeatured(long battleId, boolean featured, Deadline d) {
            }

            @Override
            public List<Long> activeFeatured(int limit, Deadline d) {
                return List.of();
            }
        };
        service.withRelay(new SpectateRelay(writeFails, relayDialer, verifier, TTL, instances));

        assertLobbyTicket(watch(), "inst-reply", 123_456L);
    }

    @Test
    void 私密局先过可见性闸_就算档位脏了也走不到大厅档() {
        placements.put(placement.toBuilder().setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE_VALUE).build());
        tiers.raise(X, eSpectateTier.SPECTATE_TIER_RELAY, Deadline.after(1000));

        WatchBattleResponse response = watch();

        assertThat(response).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        assertThat(response.getSpectateTicket().isEmpty()).isTrue();
    }

    @Test
    void 落点缺实例_签不出凭证_回当前无法观战() {
        placements.put(placement.toBuilder().setBattleInstanceId("").build());
        tiers.raise(X, eSpectateTier.SPECTATE_TIER_RELAY, Deadline.after(1000));

        assertThat(watch()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_WATCHABLE));
    }

    @Test
    void 没配密钥不能打开中继() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpectateRelay(tiers, relayDialer, SpectateTickets.disabled(), TTL, instances));
    }
}
