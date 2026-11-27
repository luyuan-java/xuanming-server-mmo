package com.game.api.rpc;

import com.game.api.proto.ClientCall;
import com.game.common.release.ReleaseTrack;
import com.game.common.routing.DeterministicApertureSubset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.RouterSnapshotNode;
import org.apache.dubbo.rpc.cluster.router.state.AbstractStateRouter;
import org.apache.dubbo.rpc.cluster.router.state.BitList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 发布轨道 + 确定性 aperture 连接子集 Dubbo {@code StateRouter}（对齐主仓 {@code release-track-gray-canary.md} §2.4
 * 与 {@code connection-fanout-subsetting-20261106.md} §3.3、§8）：
 * <ol>
 *   <li>先按发布轨道过滤候选提供者：调用方是 {@code canary} 时优先选 {@code canary}、无可用退回 {@code stable}；
 *       {@code stable} 与内部服务间调用永不进 {@code canary}；轨道无法识别的提供者跳过并记 ERROR；</li>
 *   <li>再在选定轨道内按本消费端在同轨道消费端集合里的确定性 aperture 弧收窄提供者子集，保证每台提供者份额一致、绝不遗漏；
 *       当目标数多于 {@code 消费端数 × k} 时自动扩大子集。配合消费端懒连接（{@code lazy=true}），不在子集里的提供者不建连。</li>
 * </ol>
 */
public final class ReleaseTrackAndSubsetRouter<T> extends AbstractStateRouter<T> {

    private static final Logger log = LoggerFactory.getLogger(ReleaseTrackAndSubsetRouter.class);

    private final Supplier<String> selfIdSupplier;
    private final Supplier<Collection<String>> sameTrackPeersSupplier;
    private final int subsetSize;

    public ReleaseTrackAndSubsetRouter(URL url) {
        this(url,
                () -> url.getParameter("subset.self", url.getAddress()),
                () -> List.of(url.getParameter("subset.self", url.getAddress())),
                DeterministicApertureSubset.validateSubsetSize(url.getParameter("subset.size", 0)));
    }

    public ReleaseTrackAndSubsetRouter(URL url,
                                       Supplier<String> selfIdSupplier,
                                       Supplier<Collection<String>> sameTrackPeersSupplier,
                                       int subsetSize) {
        super(url);
        this.selfIdSupplier = Objects.requireNonNull(selfIdSupplier, "selfIdSupplier");
        this.sameTrackPeersSupplier = Objects.requireNonNull(sameTrackPeersSupplier, "sameTrackPeersSupplier");
        this.subsetSize = DeterministicApertureSubset.validateSubsetSize(subsetSize);
    }

    @Override
    protected BitList<Invoker<T>> doRoute(BitList<Invoker<T>> invokers,
                                          URL url,
                                          Invocation invocation,
                                          boolean needToPrintMessage,
                                          org.apache.dubbo.common.utils.Holder<RouterSnapshotNode<T>> routerSnapshotNodeHolder,
                                          org.apache.dubbo.common.utils.Holder<String> messageHolder) throws RpcException {
        if (invokers == null || invokers.isEmpty()) {
            return invokers;
        }
        ReleaseTrack callerTrack = resolveCallerTrack(invocation);
        List<Invoker<T>> trackFiltered = ReleaseTrack.filterCandidates(
                callerTrack,
                invokers,
                ReleaseTrackAndSubsetRouter::providerTrackOf,
                bad -> log.error("跳过无法识别发布轨道的提供者 address={} track={}",
                        bad.getUrl() == null ? "unknown" : bad.getUrl().getAddress(),
                        providerTrackOf(bad)));
        if (trackFiltered.isEmpty()) {
            return new BitList<>(List.of());
        }
        List<Invoker<T>> finalTargets = subsetSize <= 0
                ? trackFiltered
                : DeterministicApertureSubset.selectSubset(
                        selfIdSupplier.get(),
                        sameTrackPeersSupplier.get(),
                        trackFiltered,
                        ReleaseTrackAndSubsetRouter::providerIdentityOf,
                        subsetSize);
        if (finalTargets.size() == invokers.size()) {
            return invokers;
        }
        Set<Invoker<T>> allowed = new HashSet<>(finalTargets);
        List<Invoker<T>> ordered = new ArrayList<>(finalTargets.size());
        for (Invoker<T> invoker : invokers) {
            if (allowed.contains(invoker)) {
                ordered.add(invoker);
            }
        }
        return new BitList<>(ordered);
    }

    /** 解析本次调用所属发布轨道：优先取 ClientCall.session.release_track，次取调用附件；缺省为 STABLE（内部调用绝不进 canary）。 */
    static ReleaseTrack resolveCallerTrack(Invocation invocation) {
        if (invocation != null) {
            Object[] args = invocation.getArguments();
            if (args != null && args.length > 0 && args[0] instanceof ClientCall call && call.hasSession()) {
                return ReleaseTrack.parseDiscoveryTag(call.getSession().getReleaseTrack()).orElse(ReleaseTrack.STABLE);
            }
            String attached = invocation.getAttachment(ReleaseTrack.ATTACHMENT_KEY);
            if (attached != null && !attached.isBlank()) {
                return ReleaseTrack.parseDiscoveryTag(attached).orElse(ReleaseTrack.STABLE);
            }
        }
        String ctx = RpcContext.getClientAttachment().getAttachment(ReleaseTrack.ATTACHMENT_KEY);
        return ReleaseTrack.parseDiscoveryTag(ctx).orElse(ReleaseTrack.STABLE);
    }

    private static String providerTrackOf(Invoker<?> invoker) {
        URL u = invoker.getUrl();
        return u == null ? "" : u.getParameter(ReleaseTrack.URL_PARAM_KEY, "");
    }

    private static String providerIdentityOf(Invoker<?> invoker) {
        URL u = invoker.getUrl();
        if (u == null) {
            return "";
        }
        String id = u.getParameter("instance.id", "");
        return id.isBlank() ? u.getAddress() : id;
    }

    @Override
    public boolean isForce() {
        return true;
    }
}
