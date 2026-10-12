package com.game.player.store;

/** 一份玩家数据归属：玩家 {@code playerId} 由持有 {@code ownerEpoch} 的写者持有。不可变，可跨线程传递。 */
public record OwnerLease(long playerId, long ownerEpoch) {
}
