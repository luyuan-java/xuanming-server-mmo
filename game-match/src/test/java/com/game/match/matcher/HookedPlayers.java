package com.game.match.matcher;

import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.port.PlayerStatusReader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 凑单测试用的玩家状态包装：读原样转给被包的替身，只在「读某人的位置之前」留一个一次性的口子——用来摆出「凑单校验到一半，这个人取消并重新排了一次」
 * 这种交错（校验时读到的旧票号与他此刻的新票号不同）。
 */
final class HookedPlayers implements PlayerStatusReader {

    private final PlayerStatusReader delegate;
    private final Map<Long, Runnable> beforeLocation = new ConcurrentHashMap<>();

    HookedPlayers(PlayerStatusReader delegate) {
        this.delegate = delegate;
    }

    /** 下一次读 {@code playerId} 的位置之前先执行 {@code action}（只执行一次）。 */
    HookedPlayers beforeLocation(long playerId, Runnable action) {
        beforeLocation.put(playerId, action);
        return this;
    }

    @Override
    public boolean inBattle(long playerId, Deadline d) {
        return delegate.inBattle(playerId, d);
    }

    @Override
    public Optional<PlayerPresence> presence(long playerId, Deadline d) {
        return delegate.presence(playerId, d);
    }

    @Override
    public HolderRead location(long playerId, Deadline d) {
        Runnable action = beforeLocation.remove(playerId);
        if (action != null) {
            action.run();
        }
        return delegate.location(playerId, d);
    }
}
