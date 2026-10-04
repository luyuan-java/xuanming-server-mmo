package com.game.friend.store;

/**
 * 存储层的业务上限（{@code xm.friend.*}）。启动校验要求全部 &gt; 0；存储层仍把 0 当「不限」作为防御（同基线）。
 *
 * @param maxFriends          每人好友数上限（权威判定在守卫事务里）
 * @param maxPendingRequests  每人出站待处理申请上限
 * @param maxIncomingRequests 每人入站待处理申请上限
 * @param maxBlocks           每人黑名单上限
 */
public record FriendLimits(int maxFriends, int maxPendingRequests, int maxIncomingRequests, int maxBlocks) {
}
