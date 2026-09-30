package com.game.robot.client;

import com.game.contract.MessageIdRegistry;

/**
 * 探针用到的消息号，启动时按「服务裸名 + 方法名」从同步来的 {@code message_id.txt} 解析。
 * 号由 mmorpg 的生成器发（会复用空洞），这里一个数字都不写死；缺任何一个即启动失败。
 */
public record MessageIds(
        int login,
        int createPlayer,
        int enterGame,
        int sendTip,
        int notifyEnterScene,
        int notifyActorCreate,
        int notifyActorListCreate,
        int notifyActorDestroy,
        int notifyActorListDestroy,
        int listSkills,
        int syncBaseAttribute,
        int moveStart,
        int moveSync,
        int moveStop,
        int notifyMoveAck) {

    private static final String LOGIN_SERVICE = "ClientPlayerLogin";
    private static final String COMMON_SERVICE = "SceneClientPlayerCommon";
    private static final String SCENE_SERVICE = "SceneSceneClientPlayer";
    private static final String SKILL_SERVICE = "SceneSkillClientPlayer";
    private static final String SYNC_SERVICE = "ScenePlayerSync";
    private static final String MOVEMENT_SERVICE = "SceneMovementClientPlayer";

    public static MessageIds resolve(MessageIdRegistry registry) {
        return new MessageIds(
                registry.requireId(LOGIN_SERVICE, "Login"),
                registry.requireId(LOGIN_SERVICE, "CreatePlayer"),
                registry.requireId(LOGIN_SERVICE, "EnterGame"),
                registry.requireId(COMMON_SERVICE, "SendTipToClient"),
                registry.requireId(SCENE_SERVICE, "NotifyEnterScene"),
                registry.requireId(SCENE_SERVICE, "NotifyActorCreate"),
                registry.requireId(SCENE_SERVICE, "NotifyActorListCreate"),
                registry.requireId(SCENE_SERVICE, "NotifyActorDestroy"),
                registry.requireId(SCENE_SERVICE, "NotifyActorListDestroy"),
                registry.requireId(SKILL_SERVICE, "ListSkills"),
                registry.requireId(SYNC_SERVICE, "SyncBaseAttribute"),
                registry.requireId(MOVEMENT_SERVICE, "MoveStart"),
                registry.requireId(MOVEMENT_SERVICE, "MoveSync"),
                registry.requireId(MOVEMENT_SERVICE, "MoveStop"),
                registry.requireId(MOVEMENT_SERVICE, "NotifyMoveAck"));
    }

    /** 三条移动上行之一（应答类型都是 Empty，契约规定永不回包）。 */
    public boolean isMoveInput(int messageId) {
        return messageId == moveStart || messageId == moveSync || messageId == moveStop;
    }
}
