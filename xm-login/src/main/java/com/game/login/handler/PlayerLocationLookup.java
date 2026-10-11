package com.game.login.handler;

import com.game.discovery.proto.PlayerLocation;
import java.util.Optional;

/**
 * 玩家位置记录的读口（{@code PlayerLocationDirectory::find}）：进游戏时按它定去向（{@link EnterRoutes}）——送回原场景实例、
 * 按跨 zone 传送的待落点（节点号 0）落图，或在别的 zone 时把连接重定向过去。只回在线 / 重连租约 / 待落点记录，登出墓碑与过期读成空。
 * 阻塞，只在 login 工作线程上调用；Redis 出错抛出（调用方决定怎么退化）。
 */
@FunctionalInterface
public interface PlayerLocationLookup {

    Optional<PlayerLocation> find(long playerId);

    PlayerLocationLookup NONE = playerId -> Optional.empty();
}
