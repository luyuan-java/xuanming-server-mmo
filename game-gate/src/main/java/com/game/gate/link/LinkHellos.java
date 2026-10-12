package com.game.gate.link;

import com.game.api.proto.LinkHello;
import com.game.common.token.NodeLinkAuth;
import java.time.InstantSource;
import java.util.function.Supplier;

/**
 * 生成 gate → scene 链路握手帧：身份（gate 节点号、实例 uuid、zone、节点号租约的防护代次）+ 建链时刻的鉴权时间戳与 MAC
 * （算法见 {@link NodeLinkAuth}，租约代次也在 MAC 里）。每次建链调用一次 {@link #get()}，所以重连带的都是新时间戳，
 * 不会因为进程跑久了被 scene 判「时间戳过期」。不可变，线程安全，不阻塞。
 */
public final class LinkHellos implements Supplier<LinkHello> {

    private final int gateNodeId;
    private final String gateInstanceId;
    private final int zoneId;
    private final long leaseEpoch;
    private final NodeLinkAuth auth;
    private final InstantSource clock;

    /**
     * @param leaseEpoch 本进程占到 gate 节点号时领的防护代次（{@code NodeIdLease.leaseEpoch()}）
     */
    public LinkHellos(int gateNodeId, String gateInstanceId, int zoneId, long leaseEpoch, NodeLinkAuth auth,
                      InstantSource clock) {
        this.gateNodeId = gateNodeId;
        this.gateInstanceId = gateInstanceId;
        this.zoneId = zoneId;
        this.leaseEpoch = leaseEpoch;
        this.auth = auth;
        this.clock = clock;
    }

    @Override
    public LinkHello get() {
        long now = clock.instant().getEpochSecond();
        return LinkHello.newBuilder()
                .setGateNodeId(gateNodeId)
                .setGateInstanceId(gateInstanceId)
                .setZoneId(zoneId)
                .setAuthTimestamp(now)
                .setLeaseEpoch(leaseEpoch)
                .setAuthMac(auth.sign(gateNodeId, gateInstanceId, zoneId, leaseEpoch, now))
                .build();
    }
}
