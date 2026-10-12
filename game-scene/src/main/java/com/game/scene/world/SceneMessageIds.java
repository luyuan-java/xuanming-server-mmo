package com.game.scene.world;

import com.game.contract.MessageIdRegistry;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.Message;

/**
 * 场景用到的消息号，启动时按「服务裸名 + 方法名」从 {@code message_id.txt} 解析（号会被生成器复用空洞，不许写死）。
 * 缺任何一个即启动失败：说明同步产物与代码不一致。
 */
public record SceneMessageIds(
        int notifyEnterScene,
        int notifyActorCreate,
        int notifyActorListCreate,
        int notifyActorDestroy,
        int notifySceneInfo,
        int notifySkillUsed,
        int notifySkillInterrupted,
        int listSkills,
        int releaseSkill,
        int enterScene,
        int sceneInfoC2S,
        int notifyActorListDestroy,
        int syncBaseAttribute,
        int moveStart,
        int moveSync,
        int moveStop,
        int notifyMoveAck,
        int sendTipToClient,
        int travelToZone) {

    private static final String SCENE_SERVICE = "SceneSceneClientPlayer";
    private static final String SKILL_SERVICE = "SceneSkillClientPlayer";
    private static final String SYNC_SERVICE = "ScenePlayerSync";
    private static final String MOVEMENT_SERVICE = "SceneMovementClientPlayer";
    private static final String COMMON_SERVICE = "SceneClientPlayerCommon";

    public static SceneMessageIds resolve(MessageIdRegistry registry) {
        return new SceneMessageIds(
                registry.requireId(SCENE_SERVICE, "NotifyEnterScene"),
                registry.requireId(SCENE_SERVICE, "NotifyActorCreate"),
                registry.requireId(SCENE_SERVICE, "NotifyActorListCreate"),
                registry.requireId(SCENE_SERVICE, "NotifyActorDestroy"),
                registry.requireId(SCENE_SERVICE, "NotifySceneInfo"),
                registry.requireId(SKILL_SERVICE, "NotifySkillUsed"),
                registry.requireId(SKILL_SERVICE, "NotifySkillInterrupted"),
                registry.requireId(SKILL_SERVICE, "ListSkills"),
                registry.requireId(SKILL_SERVICE, "ReleaseSkill"),
                registry.requireId(SCENE_SERVICE, "EnterScene"),
                registry.requireId(SCENE_SERVICE, "SceneInfoC2S"),
                registry.requireId(SCENE_SERVICE, "NotifyActorListDestroy"),
                registry.requireId(SYNC_SERVICE, "SyncBaseAttribute"),
                registry.requireId(MOVEMENT_SERVICE, "MoveStart"),
                registry.requireId(MOVEMENT_SERVICE, "MoveSync"),
                registry.requireId(MOVEMENT_SERVICE, "MoveStop"),
                registry.requireId(MOVEMENT_SERVICE, "NotifyMoveAck"),
                registry.requireId(COMMON_SERVICE, "SendTipToClient"),
                registry.requireId(SCENE_SERVICE, "TravelToZone"));
    }

    /** 服务端主动推送：{@code MessageContent.id} 不设（0），与基线 GateSendMessageToPlayer 一致。 */
    public static MessageContent push(int messageId, Message body) {
        return MessageContent.newBuilder()
                .setMessageId(messageId)
                .setSerializedMessage(body.toByteString())
                .build();
    }

    /** 请求的应答：{@code message_id} 同请求，{@code id} 回显请求的 {@code ClientRequest.id}。 */
    public static MessageContent reply(int messageId, long requestId, Message body) {
        return MessageContent.newBuilder()
                .setMessageId(messageId)
                .setId(requestId)
                .setSerializedMessage(body.toByteString())
                .build();
    }

    /**
     * 应答里的 {@code error_message}。基线每个应答都带这个字段（成功时是 id=0 的空子消息，线上字节 {@code 0a 00}），
     * 所以成功时也要显式放进去。
     */
    public static TipInfoMessage tip(int tipId) {
        return TipInfoMessage.newBuilder().setId(tipId).build();
    }
}
