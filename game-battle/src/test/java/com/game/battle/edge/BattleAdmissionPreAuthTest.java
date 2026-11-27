package com.game.battle.edge;

import static com.game.battle.edge.EdgeTestKit.BATTLE;
import static com.game.battle.edge.EdgeTestKit.INSTANCE;
import static com.game.battle.edge.EdgeTestKit.NODE_ID;
import static com.game.battle.edge.EdgeTestKit.NOW_MS;
import static com.game.battle.edge.EdgeTestKit.PLAYER;
import static com.game.battle.edge.EdgeTestKit.buf;
import static com.game.battle.edge.EdgeTestKit.drain;
import static com.game.battle.edge.EdgeTestKit.labels;
import static com.game.battle.edge.EdgeTestKit.participant;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.BattleIdentity;
import com.game.battle.GlobalBattleProperties;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.protocol.BattleMessageIds;
import com.game.common.net.ConnectionAdmissionPolicy;
import com.game.common.net.LoopStallDetector;
import com.game.common.token.BattleTickets;
import com.game.common.token.NetworkProbes;
import com.game.net.limit.MessageLimits;
import com.game.proto.eBattleTicketRole;
import com.game.proto.match.NetworkProbeGrant;
import com.game.proto.match.NetworkProbeStart;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * battle 直连准入（P1 / P0-2 / P2-2）与 P2-4 票据先行生命周期单测：
 * <ul>
 *   <li>验签或探测授权通过之前不建 {@link DirectSession}；</li>
 *   <li>探测连接建会话后带「建连 + 10 秒」期限，探测期间发战斗票据握手包按协议违例关闭；</li>
 *   <li>承压期按建连先后淘汰最老未认证连接（事件循环卡顿时禁止淘汰），表头扫除超时连接且卡顿时推迟；</li>
 *   <li>Prometheus 同名导出 {@code battle_connections_current/max}、{@code battle_unauthenticated_connections}、
 *       {@code battle_accepted_total}、{@code battle_rejected_total}、{@code battle_handshake_duration_ms}、
 *       {@code battle_preauth_*} 等全部指标。</li>
 * </ul>
 */
class BattleAdmissionPreAuthTest {

    private final PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final BattleMetrics metrics = new BattleMetrics(meters);
    private final BattleMessageIds ids = BattleMessageIds.loadFromClasspath();
    private final BattleTickets tickets = BattleTickets.ofUtf8(EdgeTestKit.SECRET);
    private final FakeRooms rooms = new FakeRooms(ids);
    private final AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
    private final DefaultEventLoopGroup unusedLogic = new DefaultEventLoopGroup(1);

    @AfterEach
    void tearDown() {
        unusedLogic.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }

    @Test
    void 验签前不建DirectSession_验签通过记录握手耗时并导出同名Prometheus指标() {
        LoopStallDetector stall = new LoopStallDetector();
        stall.recordBeat(nanos.get());
        BattleEdgeServer edge = newEdge(ConnectionAdmissionPolicy.defaults(100, 10_000L), stall, GlobalBattleProperties.DISABLED);
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        EmbeddedChannel ch = open(edge);
        BattleEdgeHandler handler = (BattleEdgeHandler) ch.pipeline().get("battleEdge");
        assertThat(handler.session()).isNull();
        assertThat(handler.preAuthEntry()).isNotNull();
        assertThat(edge.connectionCount()).isEqualTo(1);
        assertThat(edge.unauthenticatedConnectionCount()).isEqualTo(1);

        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(35));
        ch.writeInbound(buf(participant(tickets, BATTLE, PLAYER)));
        assertThat(labels(drain(ch))).containsExactly("verify-ok:" + BATTLE);
        assertThat(handler.session()).isNotNull();
        assertThat(handler.preAuthEntry()).isNull();
        assertThat(edge.connectionCount()).isEqualTo(1);
        assertThat(edge.unauthenticatedConnectionCount()).isZero();

        String scrape = meters.scrape();
        assertThat(scrape).contains(
                "battle_connections_current 1.0",
                "battle_connections_max 100.0",
                "battle_unauthenticated_connections 0.0",
                "battle_accepted_total 1.0",
                "battle_rejected_total{reason=\"capacity\"} 0.0",
                "battle_rejected_total{reason=\"accept_rate\"} 0.0",
                "battle_rejected_total{reason=\"shutting_down\"} 0.0",
                "battle_accept_rate_limit_per_second 100.0",
                "battle_accept_burst 100.0",
                "battle_handshake_duration_ms_count 1",
                "battle_preauth_reaped_total 0.0",
                "battle_preauth_evicted_total 0.0",
                "battle_preauth_budget 1000.0",
                "battle_preauth_early_drop_start 500.0",
                "battle_preauth_evictable_age_ms 3000.0");
        ch.close();
        assertThat(edge.connectionCount()).isZero();
    }

    @Test
    void 探测授权通过才建会话且受建连加10秒期限约束_探测中再发票据按协议违例关闭() {
        GlobalBattleProperties global = new GlobalBattleProperties(true, 2, EdgeTestKit.SECRET);
        LoopStallDetector stall = new LoopStallDetector();
        stall.recordBeat(nanos.get());
        BattleEdgeServer edge = newEdge(ConnectionAdmissionPolicy.defaults(100, 10_000L), stall, global);

        NetworkProbeGrant grant = NetworkProbeGrant.newBuilder()
                .setPlanId("p1").setPlayerId(PLAYER).setSessionBinding("sb")
                .setPhysicalRegion(2).setTargetNodeId(NODE_ID).setTargetInstanceId(INSTANCE)
                .setExpiresAtMs(NOW_MS + 120_000).build();
        NetworkProbeStart start = NetworkProbeStart.newBuilder()
                .setGrant(NetworkProbes.sign(global.probeSecret(), NetworkProbes.GRANT, grant)).build();

        // 1. 探测连接 2 秒后发 ProbeStart，建会话并设剩余 8 秒探测期限（总期限 = 建连 + 10 秒）
        EmbeddedChannel ch1 = open(edge);
        BattleEdgeHandler h1 = (BattleEdgeHandler) ch1.pipeline().get("battleEdge");
        assertThat(h1.session()).isNull();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
        ch1.writeInbound(buf(start));
        assertThat(h1.session()).isNotNull();
        assertThat(edge.unauthenticatedConnectionCount()).isZero();
        assertThat(edge.connectionCount()).isEqualTo(1);

        ch1.advanceTimeBy(7_900, TimeUnit.MILLISECONDS);
        ch1.runPendingTasks();
        assertThat(ch1.isOpen()).isTrue();
        ch1.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        ch1.runPendingTasks();
        assertThat(ch1.isOpen()).as("建连 + 10 秒探测期限到期自动关闭").isFalse();

        // 2. 探测中的连接再发 BattleTokenVerifyRequest 按协议违例立即关闭
        EmbeddedChannel ch2 = open(edge);
        ch2.writeInbound(buf(start));
        assertThat(ch2.isOpen()).isTrue();
        ch2.writeInbound(buf(participant(tickets, BATTLE, PLAYER)));
        assertThat(ch2.isOpen()).isFalse();
        ch1.finishAndReleaseAll();
        ch2.finishAndReleaseAll();
    }

    @Test
    void 承压淘汰最老未认证连接_表头扫除超时并在卡顿时推迟_停机前关源拒连() {
        LoopStallDetector stall = new LoopStallDetector();
        stall.recordBeat(nanos.get());
        // max=10, budget=2, earlyStart=1, evictAge=3000ms, handshake=10000ms
        var policy = ConnectionAdmissionPolicy.of(10, 10_000L, 100, 100, 2, 1, 3_000L, null, null);
        BattleEdgeServer edge = newEdge(policy, stall, GlobalBattleProperties.DISABLED);

        EmbeddedChannel oldest = open(edge);
        assertThat(edge.unauthenticatedConnectionCount()).isEqualTo(1);

        // 健康打拍 4 秒后新连接到来 -> 淘汰 oldest 并正常关闭
        for (int i = 0; i < 4; i++) {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
            stall.recordBeat(nanos.get());
        }
        EmbeddedChannel newer = open(edge);
        oldest.runPendingTasks();
        assertThat(oldest.isOpen()).isFalse();
        assertThat(newer.isOpen()).isTrue();
        assertThat(edge.unauthenticatedConnectionCount()).isEqualTo(1);
        assertThat(meters.get("battle.preauth.evicted").functionCounter().count()).isEqualTo(1.0);

        // 跳过打拍直接推进 11 秒模拟卡顿 -> sweepPreAuthExpired 只推迟不杀 newer
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(11));
        assertThat(edge.sweepPreAuthExpired()).isZero();
        assertThat(newer.isOpen()).isTrue();

        // 恢复连续两拍健康打拍 -> 扫表头收割超时连接 newer
        stall.recordBeat(nanos.get());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        stall.recordBeat(nanos.get());
        assertThat(edge.sweepPreAuthExpired()).isEqualTo(1);
        assertThat(newer.isOpen()).isFalse();
        assertThat(edge.unauthenticatedConnectionCount()).isZero();
        assertThat(meters.get("battle.preauth.reaped").functionCounter().count()).isEqualTo(1.0);

        // 关源后新连接走 shutting_down 拒绝
        edge.stopAccepting();
        EmbeddedChannel rejected = open(edge);
        assertThat(rejected.isOpen()).isFalse();
        assertThat(meters.get("battle.rejected").tag("reason", "shutting_down").counter().count()).isEqualTo(1.0);
    }

    private BattleEdgeServer newEdge(ConnectionAdmissionPolicy policy, LoopStallDetector stall, GlobalBattleProperties global) {
        return new BattleEdgeServer(new EdgeDependencies(
                EdgeTestKit.props(policy.maxConnections(), Duration.ofMillis(policy.handshakeTimeoutMs()), 50),
                unusedLogic, rooms, tickets,
                new BattleIdentity(NODE_ID, INSTANCE, "127.0.0.1", 12000),
                () -> NOW_MS, MessageLimits.of(Map.of()), ids, metrics, global),
                nanos::get, policy, () -> 0.99, stall);
    }

    private static EmbeddedChannel open(BattleEdgeServer edge) {
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel c) {
                edge.initChannel(c);
            }
        });
        ch.freezeTime();
        return ch;
    }
}
