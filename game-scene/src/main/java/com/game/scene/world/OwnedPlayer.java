package com.game.scene.world;

/** 本节点内存里持有的一份玩家数据归属：玩家 {@code playerId} 的实例持有 {@code ownerEpoch}。不可变，可跨线程传递。 */
public record OwnedPlayer(long playerId, long ownerEpoch) {
}
