package com.game.scene.world;

/**
 * 会话在本 scene 节点上的标识：哪条 gate 链路上的哪个 session。
 * session_id 由 gate 在自己的节点号下分配，带上链路号可以挡住已被顶替的旧链路上的迟到帧。
 */
public record SessionKey(long linkId, int sessionId) {
}
