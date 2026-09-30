package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.SessionContext;
import com.game.login.dispatch.ClientMessageDispatcher;
import com.game.player.store.PlayerStore;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoginClientMessageServiceTest {

    private static final SessionContext SESSION = SessionContext.newBuilder().setGateNodeId(3).setSessionId(9).build();

    private final PlayerStore store = mock(PlayerStore.class);
    private final LoginClientMessageService service =
            new LoginClientMessageService(mock(ClientMessageDispatcher.class), store, Runnable::run);

    private static AbandonedEnter abandoned(long playerId, long epoch) {
        return AbandonedEnter.newBuilder().setSession(SESSION).setPlayerId(playerId).setOwnerEpoch(epoch).build();
    }

    @Test
    void 未送达的进场_按epoch围栏释放归属() throws Exception {
        when(store.releaseOwnership(42, 7)).thenReturn(true);

        Ack ack = service.abandonEnter(abandoned(42, 7)).get(5, TimeUnit.SECONDS);

        assertThat(ack).isEqualTo(Ack.getDefaultInstance());
        verify(store).releaseOwnership(42, 7);
    }

    @Test
    void 玩家号或epoch为0_忽略() throws Exception {
        service.abandonEnter(abandoned(0, 7)).get(5, TimeUnit.SECONDS);
        service.abandonEnter(abandoned(42, 0)).get(5, TimeUnit.SECONDS);
        verify(store, never()).releaseOwnership(anyLong(), anyLong());
    }

    @Test
    void 释放失败或工作队列满_仍正常应答() throws Exception {
        when(store.releaseOwnership(42, 7)).thenThrow(new IllegalStateException("db down"));
        assertThat(service.abandonEnter(abandoned(42, 7)).get(5, TimeUnit.SECONDS)).isEqualTo(Ack.getDefaultInstance());

        LoginClientMessageService full = new LoginClientMessageService(mock(ClientMessageDispatcher.class), store, task -> {
            throw new RejectedExecutionException("满");
        });
        assertThat(full.abandonEnter(abandoned(42, 7)).get(5, TimeUnit.SECONDS)).isEqualTo(Ack.getDefaultInstance());
    }
}
