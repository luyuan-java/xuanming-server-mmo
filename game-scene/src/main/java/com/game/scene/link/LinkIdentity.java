package com.game.scene.link;

import com.game.api.proto.LinkHelloAck;
import com.game.api.proto.NodeLinkFrame;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 本 scene 节点在链路握手里的身份（写进 {@code LinkHelloAck}）。不可变。 */
public record LinkIdentity(int sceneNodeId, String sceneInstanceId, int zoneId, LongSupplier leaseEpoch) {

    public LinkIdentity { Objects.requireNonNull(leaseEpoch); }

    public LinkIdentity(int sceneNodeId, String sceneInstanceId, int zoneId) {
        this(sceneNodeId, sceneInstanceId, zoneId, () -> 0);
    }

    static int negotiatedPing(int requested) {
        return requested >= 1000 && requested <= 30_000 ? requested : 0;
    }

    NodeLinkFrame ack(boolean accepted, String reason) {
        return ack(accepted, reason, 0);
    }

    NodeLinkFrame ack(boolean accepted, String reason, int pingInterval) {
        return NodeLinkFrame.newBuilder()
                .setHelloAck(LinkHelloAck.newBuilder()
                        .setSceneNodeId(sceneNodeId)
                        .setSceneInstanceId(sceneInstanceId)
                        .setZoneId(zoneId)
                        .setSceneLeaseEpoch(leaseEpoch.getAsLong())
                        .setPingIntervalMs(accepted ? negotiatedPing(pingInterval) : 0)
                        .setAccepted(accepted)
                        .setReason(reason))
                .build();
    }
}
