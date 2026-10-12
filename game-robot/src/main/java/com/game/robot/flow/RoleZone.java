package com.game.robot.flow;

import com.game.proto.login.AccountSimplePlayerWrapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色列表里的一项：角色与它的<b>建角归属区</b>（{@code AccountSimplePlayer.zone_id}，批次 5.4）。归属区在建角时定、之后不变——
 * 跨 zone 传送不改它，所以它与「角色此刻在哪个区」是两回事。
 *
 * @param playerId 角色 id
 * @param zoneId   归属区；0 = 服务端没填（旧数据）
 */
public record RoleZone(long playerId, int zoneId) {

    /** 把 48 / 14 应答里的角色列表换成 (player_id, zone_id)，保持原次序。 */
    public static List<RoleZone> of(List<AccountSimplePlayerWrapper> players) {
        List<RoleZone> roles = new ArrayList<>(players.size());
        for (AccountSimplePlayerWrapper wrapper : players) {
            roles.add(new RoleZone(wrapper.getPlayer().getPlayerId(), wrapper.getPlayer().getZoneId()));
        }
        return List.copyOf(roles);
    }

    /** 报告里用：{@code player_id@zone}，角色号按无符号打印。 */
    @Override
    public String toString() {
        return Long.toUnsignedString(playerId) + "@" + zoneId;
    }
}
