package com.game.scene.world;

import com.game.api.proto.PlayerEnter;
import com.game.common.player.SceneAdmissionAuthority;
import com.game.proto.common.base.RoleAdmissionIdentity;

/** 固定进程身份与原Gate进场参数组成唯一准入原件；不读取当前玩家目录。 */
public record SceneAdmissionSource(String nodeId, String instanceId, int zoneId) {
    public SceneAdmissionSource {
        if (nodeId == null || nodeId.isBlank() || nodeId.length() > 64
                || instanceId == null || !instanceId.matches("[A-Za-z0-9_-]{1,64}") || zoneId == 0)
            throw new IllegalArgumentException("场景准入来源未配置");
    }

    public RoleAdmissionIdentity original(PlayerEnter enter) {
        if (enter == null || enter.getAccount().isBlank() || enter.getAccount().length() > 1024
                || enter.getLoginGeneration() <= 0 || enter.getLoginGeneration() > enter.getOwnerEpoch()
                || !enter.getUnknownFields().asMap().isEmpty())
            throw new SecurityException("原进场身份不完整");
        // Java的E是SQL持有代次：同进程重新登录、交接都严格递增，与C++进程局部generation实现不同。
        RoleAdmissionIdentity original = RoleAdmissionIdentity.newBuilder().setPlayerId(enter.getPlayerId())
                .setSceneInstanceId(instanceId).setGeneration(enter.getOwnerEpoch())
                .setOwnerRevision(enter.getOwnerRevision()).setOwnerEpoch(enter.getOwnerEpoch())
                .setSceneNodeId(nodeId).setSceneId(enter.getSceneId()).setZoneId(zoneId)
                .setSessionId(enter.getSessionId()).setLoginGeneration(enter.getLoginGeneration()).build();
        SceneAdmissionAuthority.original(original);
        return original;
    }
}
