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

    // ---- 发往 Redis 的写没有执行次序的保证：同一玩家的写入 / 撤销逐条发；续期在途时下线的再撤销一次 ----

    @Test
    void 进场后立刻断线_写入还没有结局时不发撤销_写入落地之后才发() {
        CompletableFuture<Void> put = new CompletableFuture<>();
        when(directory.putAsync(any())).thenReturn(put);

        presence.online(42, 7, 1);
        presence.offline(42, 7);

        assertThat(presence.size()).as("本地表立刻撤销").isZero();
        verify(directory, never()).removeAsync(any());   // 撤销先执行就是空操作，随后落地的写入会留到 TTL

        put.complete(null);

        ArgumentCaptor<PlayerPresence> removed = ArgumentCaptor.forClass(PlayerPresence.class);
        verify(directory).removeAsync(removed.capture());
        assertThat(removed.getValue().getSessionId()).isEqualTo(7);
    }

    @Test
    void 写入失败之后下线_撤销照发() {
        CompletableFuture<Void> put = new CompletableFuture<>();
        when(directory.putAsync(any())).thenReturn(put);
        presence.online(42, 7, 1);
        presence.offline(42, 7);

        put.completeExceptionally(new IllegalStateException("redis timeout"));   // 超时不等于没执行：照样撤销

        verify(directory).removeAsync(any());
    }

    @Test
    void 同一玩家先后两个会话_后一次写入等前一次有了结局才发_不同玩家互不等待() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        when(directory.putAsync(any())).thenReturn(first).thenReturn(CompletableFuture.completedFuture(null));

        presence.online(42, 7, 5);
        presence.online(42, 8, 6);      // 同一玩家的新会话：两次写入乱序的话旧条目会盖住新条目
        verify(directory, times(1)).putAsync(any());

        presence.online(43, 9, 1);      // 别的玩家不排在 42 后面
        verify(directory, times(2)).putAsync(any());

        first.complete(null);
        ArgumentCaptor<PlayerPresence> written = ArgumentCaptor.forClass(PlayerPresence.class);
        verify(directory, times(3)).putAsync(written.capture());
        assertThat(written.getAllValues()).extracting(PlayerPresence::getSessionId).containsExactly(7, 9, 8);
    }

    @Test
    void 写入同步抛出_不抛给会话线程_后面的撤销照发() {
        when(directory.putAsync(any())).thenThrow(new IllegalStateException("客户端已关闭"));

        presence.online(42, 7, 1);
        presence.offline(42, 7);

        verify(directory).removeAsync(any());
    }

    @Test
    void 续期在途时下线_续期回来后再撤销一次_仍在线的不动() {
        CompletableFuture<Integer> refresh = new CompletableFuture<>();
        when(directory.refreshAsync(any())).thenReturn(refresh);
        presence.online(42, 7, 1);
        presence.online(43, 8, 1);

        presence.refreshNow();          // 快照里有 42 与 43
        presence.offline(42, 7);        // 续期在途时 42 下线
        verify(directory, times(1)).removeAsync(any());

        refresh.complete(1);            // 续期脚本若排在撤销之后执行，会把 42 补回来

        ArgumentCaptor<PlayerPresence> removed = ArgumentCaptor.forClass(PlayerPresence.class);
        verify(directory, times(2)).removeAsync(removed.capture());
        assertThat(removed.getAllValues()).extracting(PlayerPresence::getPlayerId).as("两次都只撤 42，43 仍在线不动")
                .containsExactly(42L, 42L);
        assertThat(presence.size()).isEqualTo(1);
    }

    @Test
    void 续期在途时换了会话_对旧条目再撤销一次_续期失败也照做() {
        CompletableFuture<Integer> refresh = new CompletableFuture<>();
        when(directory.refreshAsync(any())).thenReturn(refresh);
        presence.online(42, 7, 5);

        presence.refreshNow();
        presence.online(42, 8, 6);      // 续期在途时同一玩家换到新会话（新条目盖住旧条目）

        refresh.completeExceptionally(new IllegalStateException("redis timeout"));

        ArgumentCaptor<PlayerPresence> removed = ArgumentCaptor.forClass(PlayerPresence.class);
        verify(directory).removeAsync(removed.capture());
        assertThat(removed.getValue().getSessionId()).as("按旧条目的值撤：键上已是新会话的值时是空操作").isEqualTo(7);
        assertThat(presence.size()).isEqualTo(1);
    }

    @Test
    void 续期回来时都还在线_不发撤销() {
        presence.online(42, 7, 1);
        presence.refreshNow();
        verify(directory, never()).removeAsync(any());
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
