package com.game.scene.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.player.store.OwnerLease;
import com.game.player.store.PlayerStore;
import com.game.scene.world.OwnedPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class OwnerLeaseRenewerTest {

    private final PlayerStore store = mock(PlayerStore.class);
    private final List<List<OwnedPlayer>> lostReports = new ArrayList<>();

    private OwnerLeaseRenewer renewer(List<OwnedPlayer> owned) {
        return new OwnerLeaseRenewer(() -> owned, store, Runnable::run, lostReports::add);
    }

    @Test
    void 续约周期是租约的三分之一() {
        assertThat(OwnerLeaseRenewer.PERIOD).isEqualTo(PlayerStore.OWNER_LEASE.dividedBy(3));
    }

    @Test
    void 把内存里持有的归属交给存储续约_续不上的交回场景逻辑() {
        List<OwnedPlayer> owned = List.of(new OwnedPlayer(1, 3), new OwnedPlayer(2, 4));
        when(store.renewOwnerLeases(List.of(new OwnerLease(1, 3), new OwnerLease(2, 4))))
                .thenReturn(List.of(new OwnerLease(2, 4)));

        renewer(owned).renewOnce();

        assertThat(lostReports).containsExactly(List.of(new OwnedPlayer(2, 4)));
    }

    @Test
    void 全部续上不回报_没有持有不查库() {
        when(store.renewOwnerLeases(any())).thenReturn(List.of());
        renewer(List.of(new OwnedPlayer(1, 3))).renewOnce();
        assertThat(lostReports).isEmpty();

        renewer(List.of()).renewOnce();
        verify(store).renewOwnerLeases(any());
    }

    @Test
    void 续约失败或快照失败或存储池满_本轮跳过不回报() {
        when(store.renewOwnerLeases(any())).thenThrow(new IllegalStateException("db down"));
        renewer(List.of(new OwnedPlayer(1, 3))).renewOnce();

        new OwnerLeaseRenewer(() -> {
            throw new IllegalStateException("逻辑线程超时");
        }, store, Runnable::run, lostReports::add).renewOnce();

        PlayerStore untouched = mock(PlayerStore.class);
        new OwnerLeaseRenewer(() -> List.of(new OwnedPlayer(1, 3)), untouched, task -> {
            throw new RejectedExecutionException("满");
        }, lostReports::add).renewOnce();

        assertThat(lostReports).isEmpty();
        verify(untouched, never()).renewOwnerLeases(any());
    }
}
