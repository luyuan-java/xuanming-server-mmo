package com.game.inbox.notify;

import com.game.api.proto.SceneInboxNotify;
import com.game.api.proto.SceneInboxNotifyReply;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.proto.PlayerLocation;
import com.game.inbox.metrics.InboxMetrics;
import com.game.proto.player_inbox.PlayerInboxDomain;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生产用的提醒器（mmorpg {@code KafkaNotifier} 的 Java 对应）：读玩家位置记录 → 节点目录查持有者节点 → 按节点直连调它的
 * {@code SceneInboxService.notifyInbox}（带 target_instance_id）。判定顺序：
 * <ol>
 *   <li>位置记录：读失败 / 损坏 → ERROR（不折成离线：数据面事故不能藏成「不在线」）；没有记录 / 登出墓碑 → OFFLINE；重连租约（含跨 zone 待落点）
 *       → AWAITING_PLACEMENT；在线但没写 zone → ERROR；没写节点号 → NO_NODE；</li>
 *   <li>节点目录（zone = 记录里的 zone）：读失败 / 条目与键不符 → ERROR；查不到 → NODE_MISSING（不能断定节点已下线）；没有 RPC 端口（旧版本节点）
 *       → NO_NODE；没有实例 id → EMPTY_UUID（防僵尸契约，不发）；</li>
 *   <li>Dubbo 直连：ACCEPTED → SENT；NOT_HERE（节点号已被新进程接手）→ NODE_MISSING；OVERLOADED / UNSPECIFIED / 调用失败 / 超时 → ERROR。</li>
 * </ol>
 * 每次提醒恰好计一次 {@code game_inbox_notify_total{result}}。线程安全；{@link #notify} 阻塞调用线程至多约一个 timeout。
 */
public final class SceneNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(SceneNotifier.class);

    /** 按 (zone, 节点号) 读一条 scene 节点目录条目（{@code NodeDirectory#findAsync}）。 */
    @FunctionalInterface
    public interface SceneNodes {
        CompletableFuture<Optional<SceneNodeInfo>> findAsync(int zoneId, int nodeId);
    }

    /** 按节点直连发一次提醒（生产 = {@link NodeRpcClients}{@code <SceneInboxService>}）。 */
    @FunctionalInterface
    public interface SceneCall {
        CompletableFuture<SceneInboxNotifyReply> notify(NodeRpcClients.Target target, SceneInboxNotify request, Duration timeout);
    }

    private final LongFunction<CompletableFuture<HolderRead>> holders;
    private final SceneNodes nodes;
    private final SceneCall call;
    private final InboxMetrics metrics;

    public SceneNotifier(LongFunction<CompletableFuture<HolderRead>> holders, SceneNodes nodes, SceneCall call, InboxMetrics metrics) {
        this.holders = Objects.requireNonNull(holders, "holders");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.call = Objects.requireNonNull(call, "call");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public NotifyResult notify(long playerId, PlayerInboxDomain domain, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        NotifyResult result;
        String detail = null;
        try {
            Outcome outcome = resolveAndSend(playerId, domain, deadline);
            result = outcome.result();
            detail = outcome.detail();
        } catch (TimeoutException e) {
            result = NotifyResult.ERROR;
            detail = "超时 " + timeout.toMillis() + " ms";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = NotifyResult.ERROR;
            detail = "被中断";
        } catch (ExecutionException | RuntimeException e) {
            result = NotifyResult.ERROR;
            detail = String.valueOf(e.getCause() != null ? e.getCause() : e);
        }
        metrics.notifyResult(result);
        if (detail != null) {
            // player_id 只进日志，不进指标
            log.warn("[PlayerInbox] 在线提醒没送到 player={} domain={} result={} settled={}：{}", Long.toUnsignedString(playerId),
                    domain.getNumber(), result.label(), result.settled(), detail);
        }
        return result;
    }

    private record Outcome(NotifyResult result, String detail) {
        static Outcome of(NotifyResult result) {
            return new Outcome(result, null);
        }
    }

    private Outcome resolveAndSend(long playerId, PlayerInboxDomain domain, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        HolderRead holder = holders.apply(playerId).get(remaining(deadline), TimeUnit.NANOSECONDS);
        switch (holder.status()) {
            case MISSING, LOGGED_OUT -> {
                return Outcome.of(NotifyResult.OFFLINE);
            }
            case RECONNECT_LEASE -> {
                return Outcome.of(NotifyResult.AWAITING_PLACEMENT);
            }
            case ERROR -> {
                return new Outcome(NotifyResult.ERROR, holder.detail() == null ? "位置记录状态未知" : holder.detail());
            }
            case ONLINE -> {
                // 往下查目录
            }
        }
        PlayerLocation location = holder.location();
        if (location == null || location.getZoneId() == 0) {
            return new Outcome(NotifyResult.ERROR, "在线位置记录缺值或没写 zone");
        }
        if (location.getSceneNodeId() == 0) {
            return new Outcome(NotifyResult.NO_NODE, "位置记录没写节点号；交给落地拉取");
        }
        Optional<SceneNodeInfo> entry = nodes.findAsync(location.getZoneId(), location.getSceneNodeId())
                .get(remaining(deadline), TimeUnit.NANOSECONDS);
        if (entry == null || entry.isEmpty()) {
            return Outcome.of(NotifyResult.NODE_MISSING);
        }
        SceneNodeInfo info = entry.get();
        if (info.getZoneId() != location.getZoneId() || info.getNodeId() != location.getSceneNodeId()) {
            return new Outcome(NotifyResult.ERROR, "scene 节点目录条目与键不符");
        }
        if (info.getRpcPort() == 0 || info.getRpcHost().isBlank() || Integer.compareUnsigned(info.getRpcPort(), 65535) > 0) {
            return new Outcome(NotifyResult.NO_NODE, "节点目录条目不提供 RPC 端口（旧版本节点）；交给落地拉取");
        }
        if (info.getInstanceId().isBlank()) {
            return new Outcome(NotifyResult.EMPTY_UUID, "节点目录条目没有实例 id，不发（防僵尸契约）");
        }
        SceneInboxNotify request = SceneInboxNotify.newBuilder()
                .setTargetInstanceId(info.getInstanceId())
                .setPlayerId(playerId)
                .setDomain(domain.getNumber())
                .build();
        Duration left = Duration.ofNanos(Math.max(1_000_000L, remaining(deadline)));
        SceneInboxNotifyReply reply = call.notify(new NodeRpcClients.Target(info.getRpcHost(), info.getRpcPort(), info.getInstanceId()),
                request, left).get(remaining(deadline), TimeUnit.NANOSECONDS);
        return switch (reply == null ? com.game.api.proto.SceneInboxNotifyStatus.SCENE_INBOX_NOTIFY_UNSPECIFIED : reply.getStatus()) {
            case SCENE_INBOX_NOTIFY_ACCEPTED -> Outcome.of(NotifyResult.SENT);
            case SCENE_INBOX_NOTIFY_NOT_HERE -> new Outcome(NotifyResult.NODE_MISSING, "目标节点回 NOT_HERE（节点号已换实例）");
            case SCENE_INBOX_NOTIFY_OVERLOADED -> new Outcome(NotifyResult.ERROR, "目标节点在途已满");
            default -> new Outcome(NotifyResult.ERROR, "目标节点回了不认识的状态");
        };
    }

    private static long remaining(long deadline) throws TimeoutException {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new TimeoutException();
        }
        return left;
    }
}
