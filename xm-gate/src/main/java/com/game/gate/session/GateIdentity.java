package com.game.gate.session;

/**
 * 本 gate 进程的身份，随每个客户端调用带给后端、随链路握手带给 scene。
 *
 * @param nodeId     节点号（Redis 租约，[1, 32767]），也是 session_id 的高 15 位
 * @param instanceId 进程实例 uuid：节点号会被复用，靠它区分新旧进程
 * @param zoneId     所在 zone
 */
public record GateIdentity(int nodeId, String instanceId, int zoneId) {
}
