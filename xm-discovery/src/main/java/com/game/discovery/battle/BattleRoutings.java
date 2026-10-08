package com.game.discovery.battle;

import com.game.discovery.proto.PlayerPresence;
import com.game.proto.BattleRouting;
import java.util.Objects;

/**
 * 在线目录条目 → 契约 {@link BattleRouting} 的换算（spectate-spec §4.1、W11；基线 {@code watchbattlelogic.go:133-139}）。
 * 两个使用者：xm-match 的 163 观战（观众路由）与 xm-battle 的 dev 管理口（补全快照路由）——放在 xm-discovery，两边不各写一份。
 *
 * <p>纯函数，不读 Redis、不校验「条目是否可用」：条目缺 gate 实例（数据损坏，正常写者不会产生）由调用方自己判
 * （163 回 16004，dev 管理口回 422）。线程安全。
 */
public final class BattleRoutings {

    private BattleRoutings() {
    }

    /**
     * 路由的 <b>gate 部分</b>：{@code session_id}、{@code gate_node_id}、{@code gate_instance_id}、{@code zone_id} 四个字段都取自在线目录条目；
     * {@code scene_node_id} 恒为 0、{@code scene_instance_id} 恒为空。观众的路由只有这一部分（观众不冻结、没有 scene 侧的状态；
     * 同基线 {@code room.h:197-202}）；参战者的路由在它之上再由调用方补 scene 两个字段。
     *
     * <p>{@code zone_id} 取在线目录的 zone（玩家此刻挂在哪个 zone 的 gate 上），不取位置记录的：battle 按 (zone, gate 节点号, gate 实例)
     * 把 177 等大厅公告推回去，两个 zone 各有 1 号 gate 时只凭节点号会推错（spectate-spec §2.8）。
     *
     * @param presence 在线目录条目（非 null）
     */
    public static BattleRouting gatePart(PlayerPresence presence) {
        Objects.requireNonNull(presence, "presence");
        return BattleRouting.newBuilder()
                .setSessionId(presence.getSessionId())
                .setGateNodeId(presence.getGateNodeId())
                .setGateInstanceId(presence.getGateInstanceId())
                .setZoneId(presence.getZoneId())
                .build();
    }
}
