package com.game.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-11 / 2026-11-12 邮件附件重投（按 player_id）与过期清理（按 mail_id）64 片分工及循环活性。 */
class MailShardingAndLivenessTest {

    @Test
    void 附件重投按playerId分片_过期清理按mailId分片_canary不领行_循环活性捕获异常并有意关闭摘除() throws Exception {
        MailStore store = mock(MailStore.class);
        ReliableAssetDelivery delivery = mock(ReliableAssetDelivery.class);
        PersistedAssetLedgerReader ledger = mock(PersistedAssetLedgerReader.class);
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);

        WorkShard.Membership stable = new WorkShard.Membership(30_000L, clock::get);
        stable.setSelf("mail-1");
        stable.updateMembers(List.of("mail-1", "mail-2"));

        when(store.due(any(), anyLong(), argThat(f -> !f.isAll() && !f.isNone() && f.sql().startsWith("MOD(player_id,64) IN("))))
                .thenReturn(List.of());
        when(store.cleanupExpired(any(), anyLong(), eq(100),
                argThat(f -> !f.isAll() && !f.isNone() && f.sql().startsWith("MOD(mail_id,64) IN("))))
                .thenReturn(3);

        LoopLiveness deliveryLive = new LoopLiveness("mail", "asset_delivery", 300L, clock::get);
        LoopLiveness cleanupLive = new LoopLiveness("mail", "expiry_cleanup", 300L, clock::get);
        try (MailDeliveryWorker worker = new MailDeliveryWorker(store, delivery, ledger)
                .withWorkShard(stable)
                .withLiveness(deliveryLive, cleanupLive)) {
            worker.pollDue();
            assertThat(worker.cleanupExpiredOnce()).isEqualTo(3);
        }

        verify(store).due(any(), anyLong(), argThat(f -> f.sql().startsWith("MOD(player_id,64) IN(")));
        verify(store).cleanupExpired(any(), anyLong(), eq(100), argThat(f -> f.sql().startsWith("MOD(mail_id,64) IN(")));
        assertThat(deliveryLive.isClosed()).isTrue();
        assertThat(cleanupLive.isClosed()).isTrue();

        // canary 不在成员表内时过期清理直接返回 0，不碰库
        WorkShard.Membership canary = new WorkShard.Membership(30_000L, clock::get);
        canary.setSelf("mail-canary");
        canary.updateMembers(List.of("mail-1", "mail-2"));
        MailStore canaryStore = mock(MailStore.class);
        try (MailDeliveryWorker canaryWorker = new MailDeliveryWorker(canaryStore, delivery, ledger).withWorkShard(canary)) {
            assertThat(canaryWorker.cleanupExpiredOnce()).isZero();
        }
        verify(canaryStore, never()).cleanupExpired(any(), anyLong(), anyInt(), any());
    }
}
