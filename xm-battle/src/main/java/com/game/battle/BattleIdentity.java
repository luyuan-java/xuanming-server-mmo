package com.game.battle;

import java.util.Objects;

/**
 * 本 battle 进程的身份（battle-node-spec §7.5、§7.10）：启动时占到节点号租约（{@code NodeTypes.BATTLE}，作用域 0）后构造一次，
 * 进程生命周期内不变；构造之后才能建直连面与房间服务。不可变，线程安全。
 *
 * <ul>
 *   <li>签票（{@code BattleTicketPayload.battle_node_id / battle_instance_id}）与验票（{@code BattleTickets.classify} 的 self 参数）用
 *       {@link #nodeId} / {@link #instanceId}；</li>
 *   <li>票据里给客户端的直连地址（{@code BattleAssignedS2C.host / port}）用 {@link #advertiseHost} / {@link #advertisePort}
 *       （{@code BattleProperties#effectiveClientAdvertiseHost}：配了 {@code xm.battle.client-advertise-host} 用它，否则 {@code xm.advertise-host}；
 *       与 {@code BattleProperties#effectiveAdvertisePort()}，Java 的通告地址总有值，§11 N6）。控制面的 {@code rpc_host} 不在这里
 *       （deploy-ci-spec Q9）。</li>
 * </ul>
 *
 * @param nodeId        节点号租约（≥ 1）
 * @param instanceId    本进程实例 UUID（每次启动 {@code UUID.randomUUID()}，非空）
 * @param advertiseHost 通告给客户端的直连主机（非空）
 * @param advertisePort 通告给客户端的直连端口（1..65535）
 */
public record BattleIdentity(int nodeId, String instanceId, String advertiseHost, int advertisePort) {

    public BattleIdentity {
        if (nodeId <= 0) {
            throw new IllegalArgumentException("battle 节点号必须为正: " + nodeId);
        }
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(advertiseHost, "advertiseHost");
        if (instanceId.isBlank()) {
            throw new IllegalArgumentException("battle 实例 id 不能为空");
        }
        if (advertiseHost.isBlank()) {
            throw new IllegalArgumentException("battle 客户端通告地址不能为空（xm.battle.client-advertise-host / xm.advertise-host）");
        }
        if (advertisePort < 1 || advertisePort > 65535) {
            throw new IllegalArgumentException("battle 通告端口超出范围: " + advertisePort);
        }
    }
}
