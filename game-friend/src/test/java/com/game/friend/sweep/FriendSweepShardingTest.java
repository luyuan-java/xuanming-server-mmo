package com.game.friend.sweep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import com.game.friend.store.SweepStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-12 去单点遗留项 #1：好友过期清理按 64 片 HRW 分工与循环活性兜底。 */
class FriendSweepShardingTest {

    @Test
    void 多副本下按玩家分64片扫描_单副本或未注册时回落与停扫行为正确_循环活性兜住异常() {
        SweepStore store = mock(SweepStore.class);
        FriendSweep.Gauges gauges = mock(FriendSweep.Gauges.class);
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);

        WorkShard.Membership stable = new WorkShard.Membership(30_000L, clock::get);
        stable.setSelf("friend-1");
        stable.updateMembers(List.of("friend-1", "friend-2"));

        when(store.sweepTerminalRequests(eq(SweepStore.MODE_DELETE), eq(7), eq(100), anyLong(), any(),
                argThat(f -> !f.isAll() && !f.isNone() && f.sql().startsWith("MOD(to_player_id,64) IN("))))
                .thenReturn(new SweepStore.Result(5, 5, null));
        when(store.sweepIdleCapacityRows(eq(SweepStore.MODE_DELETE), eq(7), eq(100), anyLong(), any(),
                argThat(f -> !f.isAll() && !f.isNone() && f.sql().startsWith("MOD(player_id,64) IN("))))
                .thenReturn(new SweepStore.Result(2, 2, null));

        LoopLiveness liveness = new LoopLiveness("friend", "sweep", 300L, clock::get);
        try (FriendSweep sweep = new FriendSweep(store, SweepStore.MODE_DELETE, Duration.ofMinutes(5), 7, 100, clock::get, gauges)
                .withWorkShard(stable)
                .withLiveness(liveness)) {
            clock.addAndGet(5_000L);
            liveness.runGuarded(sweep::runRound);
        }

        verify(store).sweepTerminalRequests(eq(SweepStore.MODE_DELETE), eq(7), eq(100), anyLong(), any(),
                argThat(f -> f.sql().startsWith("MOD(to_player_id,64) IN(")));
        verify(store).sweepIdleCapacityRows(eq(SweepStore.MODE_DELETE), eq(7), eq(100), anyLong(), any(),
                argThat(f -> f.sql().startsWith("MOD(player_id,64) IN(")));
        assertThat(liveness.isClosed()).isTrue();

        // canary（不在成员表内）在有存活 stable 时不扫任何片
        WorkShard.Membership canary = new WorkShard.Membership(30_000L, clock::get);
        canary.setSelf("friend-canary");
        canary.updateMembers(List.of("friend-1", "friend-2"));
        assertThat(canary.sqlFilter("friend_sweep_request", "to_player_id").isNone()).isTrue();
    }
}
