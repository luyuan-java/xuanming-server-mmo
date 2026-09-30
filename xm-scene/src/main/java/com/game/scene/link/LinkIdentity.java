package com.game.scene.link;

import com.game.api.proto.LinkHelloAck;
import com.game.api.proto.NodeLinkFrame;

/** 本 scene 节点在链路握手里的身份（写进 {@code LinkHelloAck}）。不可变。 */
public record LinkIdentity(int sceneNodeId, String sceneInstanceId, int zoneId) {

    NodeLinkFrame ack(boolean accepted, String reason) {
        return NodeLinkFrame.newBuilder()
                .setHelloAck(LinkHelloAck.newBuilder()
                        .setSceneNodeId(sceneNodeId)
                        .setSceneInstanceId(sceneInstanceId)
                        .setZoneId(zoneId)
                        .setAccepted(accepted)
                        .setReason(reason))
                .build();
    }
}
