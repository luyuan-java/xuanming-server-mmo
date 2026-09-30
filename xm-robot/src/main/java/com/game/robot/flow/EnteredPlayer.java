package com.game.robot.flow;

import com.game.proto.SceneInfoComp;
import com.game.robot.client.GameConnection;

/**
 * 已进游戏、收到 79 的一个角色。
 *
 * @param account    账号
 * @param playerId   角色 id（EnterGameResponse.player_id，已校验等于请求值且非 0）
 * @param created    本次是否新建了角色
 * @param sceneInfo  79 {@code EnterSceneS2C.scene_info}（已校验存在、配置号与场景号非 0）
 * @param connection 该角色所在的连接（调用方负责关闭）
 */
public record EnteredPlayer(String account, long playerId, boolean created, SceneInfoComp sceneInfo,
                            GameConnection connection) {
}
