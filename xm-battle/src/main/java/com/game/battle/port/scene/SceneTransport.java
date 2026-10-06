package com.game.battle.port.scene;

import com.game.api.DubboGroups;
import com.game.api.SceneBattleService;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.SceneEventKind;
import com.game.battle.metrics.BattleMetrics.SceneEventResult;
import com.game.battle.port.SettlementSink;
import com.game.battle.outbox.ActivityResultOutbox;
import com.game.battle.outbox.OutboxMetrics;
import com.game.battle.outbox.SettlementOutbox;
import com.game.battle.port.BattleResultSink;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.battle.BattleRedis;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.SceneAssetLocator;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * battle → scene 的全部真实传输（scene-battle-spec §7.15–§7.17，替换 6.2 的日志端口）：一条 {@code battle-outbox} 线程（两个发件箱共用，D25）、
 * 一个按节点直连的 {@link NodeRpcClients}{@code <SceneBattleService>}（确认与结算共用，{@code retries = 0}，超时 {@code xm.battle.scene-rpc-timeout}）、
 * 定位器（位置记录只认 {@code o} + scene 目录，D14）与 scene 目录（确认按快照路由直查，§7.16）。
 *
 * <p>停机（{@link #close}，Spring 销毁 bean 时调用——在 {@code BattleNode} 的「关闸 → 作废全部房间 → 停逻辑线程」之后）：结算发件箱有界等待在途的落库与首投
 * （{@code xm.battle.outbox-drain-timeout}），再停发件箱线程、销毁直连客户端。
 */
public final class SceneTransport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SceneTransport.class);

    /** Dubbo 应用名（进 URL，便于在 scene 日志里认出 battle 的调用）。 */
    static final String APPLICATION = "xm-battle-scene";

    private final DefaultEventLoop outboxLoop;
    private final NodeRpcClients<SceneBattleService> clients;
    private final SettlementOutbox settlements;
    private final ActivityResultOutbox activityResults;
    private final DubboSceneBattleEvents sceneEvents;
    private final Duration drainTimeout;
    private final BattleMetrics metrics;

    public SceneTransport(RedissonClient redis, BattleMetrics metrics, OutboxMetrics outboxMetrics, SceneTransportProperties props,
                          BattleResultSink results) {
        this.drainTimeout = props.outboxDrainTimeout();
        this.metrics = metrics;
        Duration timeout = props.sceneRpcTimeout();
        this.clients = new NodeRpcClients<>(APPLICATION, SceneBattleService.class, DubboGroups.SCENE_BATTLE, timeout,
                "battle-scene-connect");
        NodeDirectory<SceneNodeInfo> scenes = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
        SceneAssetLocator locator = new SceneAssetLocator(new PlayerLocationDirectory(redis), scenes, null);
        BattleRedis scripts = new BattleRedis(redis);
        this.outboxLoop = new DefaultEventLoop(new DefaultThreadFactory("battle-outbox", true));
        EventLoopBattleScheduler outbox = new EventLoopBattleScheduler(outboxLoop);
        this.settlements = new SettlementOutbox(outbox, SettlementOutbox.Store.redis(scripts), locator::resolveAsync,
                (endpoint, call) -> clients.call(target(endpoint), timeout, service -> service.applySettlement(call)), outboxMetrics,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        this.activityResults = new ActivityResultOutbox(outbox, ActivityResultOutbox.Store.redis(scripts), results, outboxMetrics,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        this.sceneEvents = new DubboSceneBattleEvents(scenes::findAsync, locator::resolveAsync,
                (endpoint, call) -> clients.call(target(endpoint), timeout, service -> service.confirmBattle(call)), metrics);
    }

    public SettlementOutbox settlements() {
        return settlements;
    }

    /**
     * 交给房间的结算端口：计 {@code xm_battle_scene_events_total{kind=settlement, result=sent}}（交给了发件箱），再交给结算发件箱。
     * 落库 / 投递 / 重投的结局另见 {@code xm_battle_settlement_outbox_total}。
     */
    public SettlementSink settlementSink() {
        return (routing, playerId, settlement) -> {
            metrics.sceneEvent(SceneEventKind.SETTLEMENT, SceneEventResult.SENT);
            settlements.dispatch(routing, playerId, settlement);
        };
    }

    public ActivityResultOutbox activityResults() {
        return activityResults;
    }

    public DubboSceneBattleEvents sceneEvents() {
        return sceneEvents;
    }

    static NodeRpcClients.Target target(SceneAssetEndpoint endpoint) {
        return new NodeRpcClients.Target(endpoint.host(), endpoint.port(), endpoint.instanceId());
    }

    /** 有界排空结算发件箱 → 停发件箱线程 → 销毁直连客户端。幂等。 */
    @Override
    public void close() {
        settlements.drainAndClose(drainTimeout);
        outboxLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS);
        clients.close();
        log.info("battle → scene 传输已关闭");
    }
}
