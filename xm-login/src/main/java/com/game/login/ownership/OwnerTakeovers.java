package com.game.login.ownership;

/**
 * 请玩家数据归属的当前持有者（某个 scene 节点上的实例）让出：写回并释放归属、把它的会话踢下线。
 *
 * <p>契约：尽力而为、不阻塞调用线程、不抛异常（投递失败只记日志——最坏情况是等归属租约过期）。
 * 调用方在等待窗口内每次重试夺权都会再发一次，接收方按 (player_id, owner_epoch) 幂等处理。
 */
@FunctionalInterface
public interface OwnerTakeovers {

    /**
     * @param playerId  玩家
     * @param heldEpoch 夺权时读到的当前持有 epoch：只有持有正是这个 epoch 的实例才让出
     */
    void request(long playerId, long heldEpoch);
}
