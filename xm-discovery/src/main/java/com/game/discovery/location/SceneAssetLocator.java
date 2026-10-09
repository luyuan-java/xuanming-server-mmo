package com.game.discovery.location;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.proto.PlayerLocation;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产通道的定位（基线 {@code go/shared/scenenode/locator.go:144-215} 的 {@code Locator.Resolve}；guild-economy-spec §4.7 / E2 / E14）：
 * 把 player_id 解析成「持有该玩家数据归属的 scene 节点」的资产通道直连地址。供帮会（以后还有交易）的资产调用方用；Dubbo 客户端由调用方经
 * {@code com.game.api.asset.SceneAssetOpClients} 按地址取。
 *
 * <p>判定顺序（不可重排）：
 * <ol>
 *   <li>读 {@code xm:location:{pid}}（{@link PlayerLocationDirectory#findHolderAsync}）出错 → {@link Failure}（故障：调用方按 Retry 处理并告警，
 *       <b>不</b>算「没人持有」——把数据面事故藏成不在线，资产操作会静悄悄地永远不落地，{@code locator.go:145-146}）；</li>
 *   <li>键不存在 / {@code s=x} 登出墓碑 / {@code s=l} 重连租约 → {@link NoHolder}（NOT_ONLINE / LOGGED_OUT / LEASE）：调用方本地合成 NOT_HERE。
 *       Java 断线即最终写回并释放归属，租约期间没有任何节点持有该玩家（E14，基线租约内实体还在、可以应用）；</li>
 *   <li>值损坏 / 与键不符 / 状态值不认识 → {@link Failure}；位置记录没写 zone（不可能来自正常写者）→ {@link Failure}；
 *       没写节点号 → {@link NoHolder}(NODE_UNKNOWN)（基线「node_id 为空、无 epoch」同样归到不知道发给谁）；</li>
 *   <li>节点目录（zone = 记录里的 zone_id）读失败或条目损坏 → {@link Failure}；没有这个节点号 → NODE_UNKNOWN；
 *       条目的 {@code rpc_port = 0} 或 {@code rpc_host} 为空（不提供资产通道的旧版本节点）→ NO_RPC_PORT；</li>
 *   <li>找到 → {@link Found}（地址 + 实例 id）。</li>
 * </ol>
 * 基线的 AwaitingPlacement 在 Java 不是单独的状态：跨 zone 传送的待落点（批次 5.4）写成 {@code s=l} 加节点号 0
 * （{@link PlayerLocationDirectory#awaitPlacementAsync}），在第 2 步就按重连租约归到 {@link NoHolder}(LEASE)——判状态先于读节点号，
 * 走不到第 3 步的 NODE_UNKNOWN；两条腿之间确实没有任何节点持有该玩家，调用方照常合成 NOT_HERE、稍后重投。
 * 基线有、Java 没有的状态：同身份歧义（Redis 目录按节点号做键，天然唯一）、镜像未同步（每次现读目录）。
 * 节点号按区租约、实例退出即交还，位置记录可能指向「同号新实例」：新实例回 NOT_HERE，调用方重定位，正确性在 scene 账本与归属围栏。
 * <b>不读 {@code xm:presence}</b>：在线目录由 gate 写；资产通道要找的是数据持有者（持有归属的 scene 写的位置记录）。
 *
 * <p>全程异步（两次 Redis 往返），返回的 future 从不异常完成；线程安全。指标由调用方经 {@code observer} 计
 * （{@code xm_guild_scene_resolve_total{result}}，label 取 {@link ResolveResult#label()}，可在启动时按枚举预注册）。
 */
public final class SceneAssetLocator {

    private static final Logger log = LoggerFactory.getLogger(SceneAssetLocator.class);

    /** 一次定位的结局（指标 label 的唯一出处；基线 {@code scenenode_resolve_total{result}} 的 Java 对应）。 */
    public enum ResolveResult {
        FOUND("found"),
        /** 位置记录不存在。 */
        NOT_ONLINE("not_online"),
        /** 重连租约中（E14）。 */
        LEASE("lease"),
        /** 登出墓碑。 */
        LOGGED_OUT("logged_out"),
        /** 位置记录没写节点号，或目录里没有这个节点。 */
        NODE_UNKNOWN("node_unknown"),
        /** 目录里有这个节点，但它不提供资产通道（rpc_port = 0）。 */
        NO_RPC_PORT("no_rpc_port"),
        /** 故障（Redis 出错、记录 / 目录条目损坏）。 */
        ERROR("error");

        private final String label;

        ResolveResult(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 定位结果（三选一，调用方穷举）。 */
    public sealed interface Resolution permits Found, NoHolder, Failure {
        ResolveResult result();
    }

    /**
     * 找到了。
     *
     * @param location 位置记录（含 owner_epoch，可进日志）
     * @param endpoint 资产通道直连地址
     */
    public record Found(PlayerLocation location, SceneAssetEndpoint endpoint) implements Resolution {
        @Override
        public ResolveResult result() {
            return ResolveResult.FOUND;
        }
    }

    /** 此刻没有节点持有该玩家（调用方本地合成 NOT_HERE，不算错误）。result ∈ NOT_ONLINE / LEASE / LOGGED_OUT / NODE_UNKNOWN / NO_RPC_PORT。 */
    public record NoHolder(ResolveResult result) implements Resolution {
        public NoHolder {
            if (result == ResolveResult.FOUND || result == ResolveResult.ERROR) {
                throw new IllegalArgumentException("NoHolder 不能是 " + result);
            }
        }
    }

    /** 故障（调用方按 Retry 处理并告警）。 */
    public record Failure(String reason) implements Resolution {
        @Override
        public ResolveResult result() {
            return ResolveResult.ERROR;
        }
    }

    /** 按 (zone, 节点号) 读一条 scene 节点目录条目（{@link NodeDirectory#findAsync}）。 */
    @FunctionalInterface
    public interface SceneNodeLookup {
        CompletableFuture<Optional<SceneNodeInfo>> findAsync(int zoneId, int nodeId);
    }

    private final LongFunction<CompletableFuture<HolderRead>> holders;
    private final SceneNodeLookup nodes;
    private final Consumer<ResolveResult> observer;

    /** 生产装配：位置记录与 scene 节点目录都来自同一个 Redis。 */
    public SceneAssetLocator(PlayerLocationDirectory locations, NodeDirectory<SceneNodeInfo> scenes,
                             Consumer<ResolveResult> observer) {
        this(locations::findHolderAsync, scenes::findAsync, observer);
    }

    /**
     * @param holders  玩家 → 位置记录的严格读（future 不得异常完成；异常完成也按故障处理）
     * @param nodes    scene 节点目录的单条读
     * @param observer 每次定位恰好回调一次结局（指标），可为 null
     */
    public SceneAssetLocator(LongFunction<CompletableFuture<HolderRead>> holders, SceneNodeLookup nodes,
                             Consumer<ResolveResult> observer) {
        this.holders = Objects.requireNonNull(holders, "holders");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.observer = observer == null ? r -> { } : observer;
    }

    /** 定位玩家此刻的持有者节点（异步；future 从不异常完成）。 */
    public CompletableFuture<Resolution> resolveAsync(long playerId) {
        CompletableFuture<HolderRead> read;
        try {
            read = holders.apply(playerId);
        } catch (RuntimeException e) {
            read = CompletableFuture.failedFuture(e);
        }
        return read.handle((holder, error) -> error != null
                        ? CompletableFuture.<Resolution>completedFuture(new Failure("读位置记录失败: " + error))
                        : afterHolder(playerId, holder))
                .thenCompose(stage -> stage)
                .handle((resolution, error) -> {
                    Resolution done = error != null ? new Failure("定位出错: " + error) : resolution;
                    report(playerId, done);
                    return done;
                });
    }

    private CompletableFuture<Resolution> afterHolder(long playerId, HolderRead holder) {
        switch (holder.status()) {
            case MISSING -> {
                return done(new NoHolder(ResolveResult.NOT_ONLINE));
            }
            case RECONNECT_LEASE -> {
                return done(new NoHolder(ResolveResult.LEASE));
            }
            case LOGGED_OUT -> {
                return done(new NoHolder(ResolveResult.LOGGED_OUT));
            }
            case ERROR -> {
                return done(new Failure(holder.detail() == null ? "位置记录状态未知" : holder.detail()));
            }
            case ONLINE -> {
                // 往下查目录
            }
        }
        PlayerLocation location = holder.location();
        if (location == null) {
            return done(new Failure("在线位置记录没有位置值"));
        }
        if (location.getZoneId() == 0) {
            return done(new Failure("位置记录没写 zone"));
        }
        if (location.getSceneNodeId() == 0) {
            return done(new NoHolder(ResolveResult.NODE_UNKNOWN));
        }
        int zoneId = location.getZoneId();
        int nodeId = location.getSceneNodeId();
        CompletableFuture<Optional<SceneNodeInfo>> entry;
        try {
            entry = nodes.findAsync(zoneId, nodeId);
        } catch (RuntimeException e) {
            entry = CompletableFuture.failedFuture(e);
        }
        return entry.handle((found, error) -> {
            if (error != null) {
                return new Failure("读 scene 节点目录失败 zone=" + Integer.toUnsignedString(zoneId) + " node="
                        + Integer.toUnsignedString(nodeId) + ": " + error);
            }
            if (found == null || found.isEmpty()) {
                return new NoHolder(ResolveResult.NODE_UNKNOWN);
            }
            return fromEntry(location, found.get());
        });
    }

    private static Resolution fromEntry(PlayerLocation location, SceneNodeInfo info) {
        if (info.getZoneId() != location.getZoneId() || info.getNodeId() != location.getSceneNodeId()) {
            return new Failure("scene 节点目录条目与键不符 entry_zone=" + Integer.toUnsignedString(info.getZoneId())
                    + " entry_node=" + Integer.toUnsignedString(info.getNodeId()));
        }
        int port = info.getRpcPort();
        if (port == 0 || info.getRpcHost().isBlank()) {
            return new NoHolder(ResolveResult.NO_RPC_PORT);
        }
        if (Integer.compareUnsigned(port, 65535) > 0) {
            return new Failure("scene 节点目录条目的 rpc_port 越界: " + Integer.toUnsignedString(port));
        }
        return new Found(location, new SceneAssetEndpoint(info.getZoneId(), info.getNodeId(), info.getInstanceId(),
                info.getRpcHost(), port));
    }

    private void report(long playerId, Resolution resolution) {
        if (resolution instanceof Failure failure) {
            // 故障要告警；player_id 只进日志，不进指标
            log.warn("[AssetOp] 定位持有者失败（按故障重投） player={} 原因={}", Long.toUnsignedString(playerId), failure.reason());
        }
        try {
            observer.accept(resolution.result());
        } catch (RuntimeException e) {
            log.warn("定位结局的观察者出错（忽略）", e);
        }
    }

    private static CompletableFuture<Resolution> done(Resolution resolution) {
        return CompletableFuture.completedFuture(resolution);
    }
}
