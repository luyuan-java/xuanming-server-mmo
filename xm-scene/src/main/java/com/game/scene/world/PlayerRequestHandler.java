package com.game.scene.world;

import com.google.protobuf.Message;

/**
 * 一个玩家服务方法的处理器（场景逻辑线程上调用）。应答类型不是 {@code Empty} 的方法必须经 {@link PlayerCall#reply} 回且只回一次；
 * 业务拒绝写进应答体自己的 {@code error_message}（客户端按它判拒绝）。
 */
@FunctionalInterface
public interface PlayerRequestHandler<Q extends Message> {

    void handle(PlayerCall call, Q request);
}
