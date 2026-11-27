package com.game.scenemanager;

import com.game.api.proto.SceneNodeInfo;
import com.game.common.deadline.Deadline;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * 进场 / 跨 zone 传送按需补铺大世界频道（对齐 2026-11-12「进场不怕 zone 刚起」）：
 * <ul>
 *   <li>当一张大世界地图当前一个频道都没有时，按 {@code (zoneId, sceneConfigId)} 在本进程内单飞合并补铺；</li>
 *   <li>候选活节点严格剔除计划排空中的节点（{@code evacuating = true}）及同节点号出现多条不同实例注册（重号冲突）的节点；</li>
 *   <li>若编排领导者尚未同步完，则在本次请求 {@link Deadline} 内按有界退避等待，绝不越界阻塞。</li>
 * </ul>
 */
public final class OnDemandWorldBootstrap {

    public static final long INITIAL_WAIT_MS = 50L;
    public static final long MAX_WAIT_MS = 400L;

    private record Key(int zoneId, int sceneConfigId) {
    }

    /** 触发一次面向 (zoneId, sceneConfigId) 的按需铺设，返回是否已就绪（或由本调用铺出）。 */
    @FunctionalInterface
    public interface Provisioner {
        boolean provision(int zoneId, int sceneConfigId, List<SceneNodeInfo> eligibleNodes);
    }

    private final SceneNodeSource source;
    private final BiPredicate<Integer, SceneNodeInfo> leaderRecognizesAlive;
    private final Provisioner provisioner;
    private final ConcurrentHashMap<Key, CompletableFuture<Boolean>> inFlight = new ConcurrentHashMap<>();

    public OnDemandWorldBootstrap(SceneNodeSource source,
                                  BiPredicate<Integer, SceneNodeInfo> leaderRecognizesAlive,
                                  Provisioner provisioner) {
        this.source = Objects.requireNonNull(source, "source");
        this.leaderRecognizesAlive = Objects.requireNonNull(leaderRecognizesAlive, "leaderRecognizesAlive");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
    }

    /**
     * 过滤本 zone 中可用于按需落地大世界频道的节点：
     * <ul>
     *   <li>属于本 zone、节点号非 0、地址完整、未处于 {@code evacuating} 排空中；</li>
     *   <li>剔除同一 {@code node_id} 下出现多个不同 {@code instance_id} 的重号节点；</li>
     *   <li>须经编排领导者认可为存活节点。</li>
     * </ul>
     */
    public List<SceneNodeInfo> eligibleNodes(int zoneId, List<SceneNodeInfo> snapshot) {
        Map<Integer, Set<String>> instancesByNode = new HashMap<>();
        for (SceneNodeInfo n : snapshot) {
            if (n.getZoneId() != zoneId || n.getNodeId() == 0) {
                continue;
            }
            instancesByNode.computeIfAbsent(n.getNodeId(), ignored -> new HashSet<>())
                    .add(n.getInstanceId() == null ? "" : n.getInstanceId());
        }
        List<SceneNodeInfo> out = new ArrayList<>();
        for (SceneNodeInfo n : snapshot) {
            if (n.getZoneId() != zoneId || n.getNodeId() == 0) {
                continue;
            }
            if (n.getEvacuating()) {
                continue;
            }
            if (n.getLinkHost().isBlank() || n.getLinkPort() == 0) {
                continue;
            }
            Set<String> seen = instancesByNode.getOrDefault(n.getNodeId(), Set.of());
            if (seen.size() > 1) {
                continue;
            }
            if (!leaderRecognizesAlive.test(zoneId, n)) {
                continue;
            }
            out.add(n);
        }
        return out;
    }

    /**
     * 按 {@code (zoneId, sceneConfigId)} 单飞合并补铺：同一进程并发进场同一张空图时只执行一次补铺循环，
     * 在 {@code deadline} 内按 50ms..400ms 退避等待领导者同步与合格节点就绪。
     */
    public boolean ensureBootstrapped(int zoneId, int sceneConfigId, Deadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        Key key = new Key(zoneId, sceneConfigId);
        CompletableFuture<Boolean> created = new CompletableFuture<>();
        CompletableFuture<Boolean> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            try {
                long rem = Math.max(1L, deadline.remainingMillis());
                return Boolean.TRUE.equals(existing.get(rem, java.util.concurrent.TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }
        boolean ok = false;
        try {
            long waitMs = INITIAL_WAIT_MS;
            while (!deadline.expired()) {
                List<SceneNodeInfo> eligible = eligibleNodes(zoneId, source.list(zoneId));
                if (!eligible.isEmpty() && provisioner.provision(zoneId, sceneConfigId, eligible)) {
                    ok = true;
                    break;
                }
                long remaining = deadline.remainingMillis();
                if (remaining <= 0) {
                    break;
                }
                long sleepMs = Math.min(waitMs, remaining);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                waitMs = Math.min(MAX_WAIT_MS, waitMs * 2L);
            }
            created.complete(ok);
            return ok;
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, created);
        }
    }
}
