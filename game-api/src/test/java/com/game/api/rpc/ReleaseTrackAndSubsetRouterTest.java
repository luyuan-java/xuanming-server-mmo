package com.game.api.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.ClientMessageService;
import com.game.api.proto.ClientCall;
import com.game.api.proto.SessionContext;
import com.game.common.release.ReleaseTrack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.router.state.BitList;
import org.junit.jupiter.api.Test;

class ReleaseTrackAndSubsetRouterTest {

    @Test
    void enforcesCanaryFallbackStableIsolationUnknownSkipAndApertureUniformCoverage() {
        List<Invoker<ClientMessageService>> providers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            providers.add(invoker("10.0.0." + (i + 1), 20880, "stable-p" + i, "stable"));
        }
        providers.add(invoker("10.0.1.1", 20880, "canary-p0", "canary"));
        providers.add(invoker("10.0.1.2", 20880, "canary-p1", "canary"));
        providers.add(invoker("10.0.2.1", 20880, "bad-p0", "unknown-track"));
        BitList<Invoker<ClientMessageService>> all = new BitList<>(providers);

        List<String> stableGates = List.of("gate-1", "gate-2", "gate-3", "gate-4");
        URL consumerUrl = URL.valueOf("consumer://127.0.0.1/com.game.api.ClientMessageService");

        // 1. stable 会话：永不进入 canary，跳过未知轨道，且 4 台 gate 的子集并集覆盖全部 8 台 stable 提供者
        Set<String> coveredStable = new HashSet<>();
        for (String gateId : stableGates) {
            var router = new ReleaseTrackAndSubsetRouter<ClientMessageService>(consumerUrl, () -> gateId, () -> stableGates, 4);
            BitList<Invoker<ClientMessageService>> routed = router.doRoute(all, consumerUrl, callWithTrack("stable"), false, null, null);
            assertThat(routed).hasSize(4);
            for (Invoker<ClientMessageService> inv : routed) {
                assertThat(inv.getUrl().getParameter(ReleaseTrack.URL_PARAM_KEY)).isEqualTo("stable");
                coveredStable.add(inv.getUrl().getParameter("instance.id"));
            }
        }
        assertThat(coveredStable).hasSize(8);

        // 2. canary 会话：存在 canary 提供者时仅选 canary
        var canaryRouter = new ReleaseTrackAndSubsetRouter<ClientMessageService>(consumerUrl, () -> "gate-c1", () -> List.of("gate-c1"), 4);
        BitList<Invoker<ClientMessageService>> canaryRouted = canaryRouter.doRoute(all, consumerUrl, callWithTrack("canary"), false, null, null);
        assertThat(canaryRouted).extracting(i -> i.getUrl().getParameter("instance.id"))
                .containsExactlyInAnyOrder("canary-p0", "canary-p1");

        // 3. canary 会话：无 canary 提供者时退回 stable
        BitList<Invoker<ClientMessageService>> stableOnly = new BitList<>(providers.subList(0, 8));
        BitList<Invoker<ClientMessageService>> fallback = canaryRouter.doRoute(stableOnly, consumerUrl, callWithTrack("canary"), false, null, null);
        assertThat(fallback).hasSize(8);

        // 4. 内部服务调用（无 ClientCall 与轨道附件）：恒走 stable，绝不进 canary
        RpcInvocation internalCall = new RpcInvocation("ping", "com.game.api.Internal", "", new Class<?>[0], new Object[0]);
        BitList<Invoker<ClientMessageService>> internalRouted = canaryRouter.doRoute(all, consumerUrl, internalCall, false, null, null);
        assertThat(internalRouted).allMatch(i -> "stable".equals(i.getUrl().getParameter(ReleaseTrack.URL_PARAM_KEY)));
    }

    @Test
    void nodeRpcClientsEvictsIdleConnectionsAndRejectsThresholdNotLongerThanTimeout() {
        AtomicLong clockNanos = new AtomicLong(1_000_000_000L);
        try (var clients = new NodeRpcClients<>("test-idle", ClientMessageService.class, "test",
                Duration.ofSeconds(3), "idle-test", clockNanos::get)) {
            assertThatThrownBy(() -> clients.evictIdle(Duration.ofSeconds(3)))
                    .isInstanceOf(IllegalArgumentException.class);
            var t1 = new NodeRpcClients.Target("127.0.0.1", 65001, "inst-1");
            var t2 = new NodeRpcClients.Target("127.0.0.1", 65002, "inst-2");
            clients.clientAsync(t1);
            clockNanos.addAndGet(Duration.ofMinutes(6).toNanos());
            clients.clientAsync(t2);
            clockNanos.addAndGet(Duration.ofMinutes(5).toNanos()); // t1 空闲 11m，t2 空闲 5m
            assertThat(clients.evictIdle(NodeRpcClients.DEFAULT_IDLE_TIMEOUT)).isEqualTo(1);
            assertThat(clients.size()).isEqualTo(1);
        }
    }

    private static RpcInvocation callWithTrack(String track) {
        ClientCall call = ClientCall.newBuilder()
                .setSession(SessionContext.newBuilder().setReleaseTrack(track).build())
                .build();
        return new RpcInvocation("handle", ClientMessageService.class.getName(), "",
                new Class<?>[]{ClientCall.class}, new Object[]{call});
    }

    private static Invoker<ClientMessageService> invoker(String host, int port, String instanceId, String track) {
        URL url = URL.valueOf("tri://" + host + ":" + port + "/" + ClientMessageService.class.getName()
                + "?instance.id=" + instanceId + "&" + ReleaseTrack.URL_PARAM_KEY + "=" + track);
        return new Invoker<>() {
            @Override public Class<ClientMessageService> getInterface() { return ClientMessageService.class; }
            @Override public Result invoke(Invocation invocation) { return new AppResponse("ok"); }
            @Override public URL getUrl() { return url; }
            @Override public boolean isAvailable() { return true; }
            @Override public void destroy() {}
        };
    }
}
