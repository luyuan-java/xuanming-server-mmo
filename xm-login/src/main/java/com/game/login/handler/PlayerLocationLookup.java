package com.game.login.handler;

import com.game.discovery.proto.PlayerLocation;
import java.util.Optional;

/**
 * 玩家位置记录的读口（{@code PlayerLocationDirectory::find}）：进游戏时按它把玩家送回原场景实例。
 * 阻塞，只在 login 工作线程上调用；Redis 出错抛出（调用方决定怎么退化）。
 */
@FunctionalInterface
public interface PlayerLocationLookup {

    Optional<PlayerLocation> find(long playerId);

    PlayerLocationLookup NONE = playerId -> Optional.empty();
}
