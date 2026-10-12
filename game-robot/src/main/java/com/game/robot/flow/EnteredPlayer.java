package com.game.robot.flow;

import com.game.proto.SceneInfoComp;
import com.game.robot.client.GameConnection;
import com.game.robot.client.GateEndpoint;
import java.util.List;

/**
 * 已进游戏、收到 79 的一个角色。
 *
 * @param account    账号
 * @param playerId   角色 id（EnterGameResponse.player_id，已校验等于请求值且非 0）
 * @param created    本次是否新建了角色
 * @param sceneInfo  79 {@code EnterSceneS2C.scene_info}（已校验存在、配置号与场景号非 0）
 * @param connection 该角色所在的连接（调用方负责关闭）
 * @param gate       这条连接实际连的 gate 端点（批次 5.4：assign-gate 给的，或跟随 124 时 124 给的）
 * @param roles      登录（48）给的角色列表；本次新建了角色时是建角（14）应答里的列表。一定含 {@code playerId} 那一项
 */
public record EnteredPlayer(String account, long playerId, boolean created, SceneInfoComp sceneInfo,
                            GameConnection connection, GateEndpoint gate, List<RoleZone> roles) {

    public EnteredPlayer {
        roles = List.copyOf(roles);
    }

    /**
     * 这个角色的建角归属区（角色列表里本人那一项的 {@code zone_id}）。跨 zone 传送不改它：从别的区的入口登录进来，它仍是建角时的区。
     * 0 = 服务端没填（或列表里找不到本人——登录流程保证找得到）。
     */
    public int homeZoneId() {
        for (RoleZone role : roles) {
            if (role.playerId() == playerId) {
                return role.zoneId();
            }
        }
        return 0;
    }
}
