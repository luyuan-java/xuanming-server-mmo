package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ClientRequest;
import com.game.proto.login.GateMigrationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 覆盖 Batch 4T-1 门面状态机：
 * <ul>
 *   <li>场景 lane 暂存、重投保序、超 2s 回 1003、在途 13s 不重投、单向冲刷、推 124 清队与旧场景消息闸标记（S13）；</li>
 *   <li>gate 排空在线连接迁移：默认开、限速 20/s、2×rate 在途、10s 退避、场景 lane 非空跳过、身份栅栏、NOT_DRAINING 停；</li>
 *   <li>客户端自发 RequestGateMigration 被服务端路由表按 SERVER_ONLY 拦截。</li>
 * </ul>
 */
class SceneLaneAndGateMigrationTest {

    private record Sent(long id, int msgId, boolean staleRoute) {}
    private record Failed(long id, int tip) {}

    private static ClientRequest req(long id, int msgId) {
        return ClientRequest.newBuilder().setId(id).setMessageId(msgId).build();
    }

    @Test
    void 场景lane_同路由不标旧_换节点请求与单向标旧_队头重投冲刷单向按各自收包路由标记() {
        SceneRequestLane lane = new SceneRequestLane();
        AtomicReference<SceneRequestLane.RouteKey> route =
                new AtomicReference<>(new SceneRequestLane.RouteKey(1, 10L, 100L));
        List<Sent> sent = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        SceneRequestLane.Sender sender = new SceneRequestLane.Sender() {
            @Override public boolean sendToScene(ClientRequest r, boolean stale) {
                sent.add(new Sent(r.getId(), r.getMessageId(), stale));
                return true;
            }
            @Override public void failRequest(ClientRequest r, int tipId) {
                failed.add(new Failed(r.getId(), tipId));
            }
            @Override public SceneRequestLane.RouteKey currentRoute() {
                return route.get();
            }
        };

        // MessagesSentOnTheSameRouteAreNotMarked
        lane.submit(req(1, 54), false, 1_000L, sender);
        assertThat(sent).containsExactly(new Sent(1, 54, false));

        // 排队第 2 条请求和 1 条单向（收包路由为节点 1）
        lane.submit(req(2, 54), false, 1_010L, sender);
        lane.submit(req(0, 132), true, 1_020L, sender);
        assertThat(sent).hasSize(1);

        // 节点切换至节点 2；第 1 条收 retry_later -> 放回队头等待 200ms，并立即冲刷排在其后的单向（带旧路由标记）
        route.set(new SceneRequestLane.RouteKey(2, 20L, 200L));
        lane.onRetryLater(1, 1_050L, sender);
        assertThat(lane.isHoldingForRetry()).isTrue();
        // OneWayFlushedAtHoldIsMarkedByItsOwnReceiptRoute + QueuedOneWayCrossingANodeChangeIsMarked
        assertThat(sent).containsExactly(new Sent(1, 54, false), new Sent(0, 132, true));

        // 未满 200ms 不重投，也不会被第 2 条越过
        lane.tick(1_200L, sender);
        assertThat(sent).hasSize(2);

        // 满 200ms 重投第 1 条（RequestsSentAfterASceneChangeAreMarked）
        lane.tick(1_250L, sender);
        assertThat(sent).containsExactly(new Sent(1, 54, false), new Sent(0, 132, true), new Sent(1, 54, true));

        // 第 1 条成功回包 -> 发出第 2 条（同样标旧路由）
        lane.onResponse(1, 1_260L, sender);
        assertThat(sent).hasSize(4);
        assertThat(sent.get(3)).isEqualTo(new Sent(2, 54, true));
        lane.onResponse(2, 1_270L, sender);
        assertThat(lane.hasPendingWork()).isFalse();
        assertThat(failed).isEmpty();
    }

    @Test
    void 场景lane_超2秒回1003_在途13秒不重投_推124清排队保留在途() {
        SceneRequestLane lane = new SceneRequestLane();
        List<Sent> sent = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        SceneRequestLane.Sender sender = new SceneRequestLane.Sender() {
            @Override public boolean sendToScene(ClientRequest r, boolean stale) {
                sent.add(new Sent(r.getId(), r.getMessageId(), stale));
                return true;
            }
            @Override public void failRequest(ClientRequest r, int tipId) {
                failed.add(new Failed(r.getId(), tipId));
            }
            @Override public SceneRequestLane.RouteKey currentRoute() {
                return new SceneRequestLane.RouteKey(1, 1L, 1L);
            }
        };

        // 超 2s 等待上限回 1003
        lane.submit(req(10, 54), false, 1_000L, sender);
        lane.onRetryLater(10, 3_050L, sender);
        assertThat(failed).containsExactly(new Failed(10, ClientDispatcher.TIP_SERVICE_UNAVAILABLE));

        // 在途超过 13s 主动判丢回 1003，绝不重投
        sent.clear();
        failed.clear();
        lane.submit(req(11, 54), false, 5_000L, sender);
        lane.tick(18_000L, sender);
        assertThat(sent).hasSize(1);
        assertThat(failed).containsExactly(new Failed(11, ClientDispatcher.TIP_SERVICE_UNAVAILABLE));

        // 推 124：排队中的 13 立刻回 1003，在途的 12 保留；若 12 回 retry_later 当场回 1003
        sent.clear();
        failed.clear();
        lane.submit(req(12, 54), false, 20_000L, sender);
        lane.submit(req(13, 54), false, 20_010L, sender);
        lane.onRedirect(sender);
        assertThat(failed).containsExactly(new Failed(13, ClientDispatcher.TIP_SERVICE_UNAVAILABLE));
        lane.onRetryLater(12, 20_050L, sender);
        assertThat(failed).containsExactly(
                new Failed(13, ClientDispatcher.TIP_SERVICE_UNAVAILABLE),
                new Failed(12, ClientDispatcher.TIP_SERVICE_UNAVAILABLE));
    }

    @Test
    void gate排空迁移_默认开启_限速与场景lane跳过_10秒退避_身份栅栏与NOT_DRAINING停() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GateDrainMigrator migrator = new GateDrainMigrator(meters);
        assertThat(migrator.enabledByConfig()).isTrue();

        migrator.applyPermit(1, 15_000L, 10_000L);
        assertThat(migrator.isPermitActive(10_000L)).isTrue();

        var busyLane = new GateDrainMigrator.SessionCandidate(1, true, true, false, false, 100L, 2, false, true, 0L);
        var recentLobby = new GateDrainMigrator.SessionCandidate(2, true, true, false, false, 0L, 0, false, false, 9_000L);
        var readyInGame = new GateDrainMigrator.SessionCandidate(3, true, true, false, false, 300L, 2, false, false, 0L);
        var readyLobby = new GateDrainMigrator.SessionCandidate(4, true, true, false, false, 0L, 0, false, false, 0L);

        assertThat(GateDrainMigrator.isEligible(busyLane, 10_000L)).isFalse();
        assertThat(GateDrainMigrator.isEligible(recentLobby, 10_000L)).isFalse();
        assertThat(GateDrainMigrator.isEligible(readyInGame, 10_000L)).isTrue();
        assertThat(GateDrainMigrator.isEligible(readyLobby, 10_000L)).isTrue();

        List<Integer> requested = new ArrayList<>();
        int count = migrator.tickOneSecond(List.of(busyLane, recentLobby, readyInGame, readyLobby), 10_000L,
                c -> requested.add(c.sessionId()));
        assertThat(count).isEqualTo(1);
        assertThat(requested).containsExactly(3);

        // 身份栅栏：票据 playerId=300 与当前换角 301 不符 -> 丢弃并退避 10 秒
        var switched = new GateDrainMigrator.SessionCandidate(3, true, true, false, false, 301L, 2, false, false, 0L);
        assertThat(migrator.onTicketReply(3, GateMigrationResult.GATE_MIGRATION_RESULT_OK, switched, 300L, 10_100L))
                .isFalse();
        requested.clear();
        assertThat(migrator.tickOneSecond(List.of(readyInGame), 15_000L, c -> requested.add(c.sessionId())))
                .as("10 秒退避期内不再挑同一会话").isZero();

        // 退避期满后再次发起并收到 NOT_DRAINING -> 立刻停本轮迁移
        assertThat(migrator.tickOneSecond(List.of(readyInGame), 20_200L, c -> requested.add(c.sessionId()))).isEqualTo(1);
        assertThat(migrator.onTicketReply(3, GateMigrationResult.GATE_MIGRATION_RESULT_NOT_DRAINING, readyInGame, 300L, 20_300L))
                .isFalse();
        assertThat(migrator.isPermitActive(20_300L)).isFalse();
    }

    @Test
    void 客户端自发RequestGateMigration标为serverOnly由网关按非法包拒绝() {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        int msgId = registry.requireId("ClientPlayerLogin", "RequestGateMigration");
        MessageRoutes routes = MessageRoutes.of(registry);
        MessageRoute route = routes.clientRoute(msgId);
        assertThat(route).isNotNull();
        assertThat(route.serverOnly()).as("SERVER_ONLY 方法不得开放给客户端直接调用").isTrue();
    }
}
