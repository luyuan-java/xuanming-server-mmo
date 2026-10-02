package com.game.gate.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.proto.PlayerPresence;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GatePresenceTest {

    private final PlayerPresenceDirectory directory = mock(PlayerPresenceDirectory.class);
    private final GatePresence presence = new GatePresence(directory, 1, 3, "gate-uuid", () -> 1_000L);

    @BeforeEach
    void setUp() {
        when(directory.putAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(directory.removeAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
        when(directory.refreshAsync(any())).thenReturn(CompletableFuture.completedFuture(0));
    }

    @Test
    void 上线写入本gate的条目() {
        presence.online(42, 7, 1);

        ArgumentCaptor<PlayerPresence> written = ArgumentCaptor.forClass(PlayerPresence.class);
        verify(directory).putAsync(written.capture());
        assertThat(written.getValue()).isEqualTo(PlayerPresence.newBuilder().setPlayerId(42).setZoneId(1).setGateNodeId(3)
                .setGateInstanceId("gate-uuid").setSessionId(7).setOnlineSinceMs(1_000).setOwnerEpoch(1).build());
        assertThat(presence.size()).isEqualTo(1);
    }

    @Test
    void 同一玩家换了会话_旧会话下线不撤销新的() {
        presence.online(42, 7, 1);
        presence.online(42, 8, 1);

        presence.offline(42, 7);
        verify(directory, never()).removeAsync(any());
        assertThat(presence.size()).isEqualTo(1);

        presence.offline(42, 8);
        verify(directory).removeAsync(any());
        assertThat(presence.size()).isZero();
    }

    @Test
    void 旧登录的确认迟到_不覆盖新登录_旧会话被踢不撤销新会话() {
        presence.online(42, 8, 6);      // 新登录（epoch 6）先确认
        presence.online(42, 7, 5);      // 旧登录（epoch 5）的确认迟到
        verify(directory, times(1)).putAsync(any());
        assertThat(presence.size()).isEqualTo(1);

        presence.offline(42, 7);        // 旧会话随后被踢
        verify(directory, never()).removeAsync(any());
        assertThat(presence.size()).as("新会话的条目还在").isEqualTo(1);

        presence.offline(42, 8);
        verify(directory).removeAsync(any());
    }

    @Test
    void 续期按本地表一次提交_空表不提交() {
        presence.refreshNow();
        verify(directory, never()).refreshAsync(any());

        presence.online(42, 7, 1);
        presence.online(43, 8, 1);
        presence.refreshNow();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<PlayerPresence>> batch = ArgumentCaptor.forClass(Collection.class);
        verify(directory).refreshAsync(batch.capture());
        assertThat(batch.getValue()).extracting(PlayerPresence::getPlayerId).containsExactlyInAnyOrder(42L, 43L);
    }

    @Test
    void 写失败不抛给会话线程() {
        when(directory.putAsync(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("redis down")));
        presence.online(42, 7, 1);
        assertThat(presence.size()).as("本地表照记，下一轮续期补回").isEqualTo(1);
    }

    @Test
    void 停止时撤销剩下的全部条目() {
        presence.online(42, 7, 1);
        presence.online(43, 8, 1);
        presence.stop();
        verify(directory, times(2)).removeAsync(any());
        assertThat(presence.size()).isZero();
        presence.stop();
        verify(directory, times(2)).removeAsync(any());
        assertThat(List.of()).isEmpty();
    }
}
