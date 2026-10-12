package com.game.robot.client;

import com.game.contract.MessageIdRegistry;

/**
 * battle 场景用到的消息号（battle-node-spec §5.1），按「服务裸名 + 方法名」从同步来的 {@code message_id.txt} 解析，一个数字都不写死；
 * 缺任何一个即启动失败。
 *
 * @param getBattleState     140：直连上补拉状态
 * @param submitAction       149：提交行动
 * @param setAutoBattle      162：挂机开关
 * @param stopWatch          165：退出观战
 * @param turnResult         139：参战者每回合（战斗帧，只走直连）
 * @param battleStart        143：开局（大厅公告）
 * @param battleEnd          150：终局（战斗帧；6.3 起 scene 另经大厅推一份）
 * @param spectateTurnResult 158：观众每回合
 * @param spectateState      161：观众握手后首帧
 * @param spectateEnd        166：观战结束
 * @param battleAssigned     177：落点分配（大厅公告，带票）
 * @param sendTip            23：gate 推的 tip（大厅上发战斗上行回 {@code {1003}}）
 * @param notWhitelisted     157（MatchService.JoinQueue）：合法的契约号但不在直连面的上行白名单里（battle-edge 用它测信封 1005）
 */
public record BattleIds(
        int getBattleState,
        int submitAction,
        int setAutoBattle,
        int stopWatch,
        int turnResult,
        int battleStart,
        int battleEnd,
        int spectateTurnResult,
        int spectateState,
        int spectateEnd,
        int battleAssigned,
        int sendTip,
        int notWhitelisted) {

    static final String BATTLE_SERVICE = "BattleClientPlayer";

    public static BattleIds resolve(MessageIdRegistry registry) {
        return new BattleIds(
                registry.requireId(BATTLE_SERVICE, "GetBattleState"),
                registry.requireId(BATTLE_SERVICE, "SubmitBattleAction"),
                registry.requireId(BATTLE_SERVICE, "SetAutoBattle"),
                registry.requireId(BATTLE_SERVICE, "StopWatchBattle"),
                registry.requireId(BATTLE_SERVICE, "NotifyTurnResult"),
                registry.requireId(BATTLE_SERVICE, "NotifyBattleStart"),
                registry.requireId(BATTLE_SERVICE, "NotifyBattleEnd"),
                registry.requireId(BATTLE_SERVICE, "NotifySpectateTurnResult"),
                registry.requireId(BATTLE_SERVICE, "NotifySpectateState"),
                registry.requireId(BATTLE_SERVICE, "NotifySpectateEnd"),
                registry.requireId(BATTLE_SERVICE, "NotifyBattleAssigned"),
                registry.requireId("SceneClientPlayerCommon", "SendTipToClient"),
                registry.requireId("MatchService", "JoinQueue"));
    }
}
