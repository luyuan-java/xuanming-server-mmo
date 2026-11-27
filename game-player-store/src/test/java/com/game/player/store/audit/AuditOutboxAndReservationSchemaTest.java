package com.game.player.store.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import com.game.player.store.SaveRowGuard;
import com.game.player.store.state.BattleReservationBookState;
import com.game.player.store.state.MailOutboxEntryState;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 覆盖审计发件箱分片转发（acks=all 后删行、坏行跳过、暂停开关）与战斗预留组件缺席拒存、出箱条目未知字段往返。 */
class AuditOutboxAndReservationSchemaTest {

    @Test
    void 发件箱转发_确认后删行_失败留行_坏行跳过_支持暂停开关() {
        List<AuditOutboxEntry> rows = new ArrayList<>(List.of(
                new AuditOutboxEntry(1L, 1, 1001L, AuditOutboxEntry.KIND_TRANSACTION, new byte[] { 1, 2 }, 1_000L),
                new AuditOutboxEntry(2L, 1, 1001L, AuditOutboxEntry.KIND_TRANSACTION, new byte[] { 9 }, 2_000L),
                new AuditOutboxEntry(3L, 1, 1001L, AuditOutboxEntry.KIND_SNAPSHOT, new byte[] { 3, 4 }, 3_000L)));
        List<Long> published = new ArrayList<>();
        AtomicBoolean failPublish = new AtomicBoolean(false);
        AtomicLong now = new AtomicLong(10_000L);

        WorkShard.Membership membership = new WorkShard.Membership(30_000L, now::get);
        membership.setSelf("node-a");
        membership.updateMembers(List.of("node-a"));
        LoopLiveness liveness = new LoopLiveness("player-store", "audit-outbox", 300L, now::get);

        AuditOutboxForwarder.Backend backend = new AuditOutboxForwarder.Backend() {
            @Override
            public List<AuditOutboxEntry> pollDue(WorkShard.SqlFilter filter, int limit) {
                return List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            }

            @Override
            public void deleteByIds(List<Long> outboxIds) {
                rows.removeIf(r -> outboxIds.contains(r.outboxId()));
            }

            @Override
            public long oldestCreatedMs(WorkShard.SqlFilter filter) {
                return rows.isEmpty() ? 0L : rows.getFirst().createdMs();
            }
        };

        AuditOutboxForwarder forwarder = new AuditOutboxForwarder(
                backend,
                entry -> {
                    if (failPublish.get()) {
                        throw new RuntimeException("broker down");
                    }
                    published.add(entry.outboxId());
                },
                entry -> !(entry.payload().length == 1 && entry.payload()[0] == 9),
                membership, 10, now::get, liveness);

        // 暂停开关：仅采样积压年龄，不发不删
        forwarder.setPaused(true);
        assertThat(forwarder.runOnce()).isZero();
        assertThat(forwarder.oldestBacklogAgeSeconds()).isEqualTo(9L);
        assertThat(rows).hasSize(3);

        // 投递失败：留行不删
        forwarder.setPaused(false);
        failPublish.set(true);
        assertThat(forwarder.runOnce()).isZero();
        assertThat(forwarder.publishFailures()).isEqualTo(1L);
        assertThat(rows).hasSize(3);

        // 恢复正常：第 1 条与第 3 条确认后删除，第 2 条坏行计数并清理防队头阻塞
        failPublish.set(false);
        assertThat(forwarder.runOnce()).isEqualTo(2);
        assertThat(published).containsExactly(1L, 3L);
        assertThat(forwarder.badRowsSkipped()).isEqualTo(1L);
        assertThat(forwarder.forwardedCount()).isEqualTo(2L);
        assertThat(rows).isEmpty();
    }

    @Test
    void 存盘缺战斗预留组件整份拒存_且MailOutboxEntryState保留未知字段() throws Exception {
        PlayerState withoutRes = PlayerState.getDefaultInstance();
        assertThatThrownBy(() -> SaveRowGuard.requireBattleReservationsPresent(withoutRes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("battle_reservations");

        PlayerState withRes = PlayerState.newBuilder()
                .setBattleReservations(BattleReservationBookState.getDefaultInstance())
                .build();
        SaveRowGuard.requireBattleReservationsPresent(withRes);

        UnknownFieldSet future = UnknownFieldSet.newBuilder()
                .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(77L).build())
                .build();
        MailOutboxEntryState original = MailOutboxEntryState.newBuilder()
                .setRequestId(42L)
                .setUnknownFields(future)
                .build();
        MailOutboxEntryState roundTripped = MailOutboxEntryState.parseFrom(original.toByteArray()).toBuilder().build();
        assertThat(roundTripped.getUnknownFields().hasField(999)).isTrue();
    }
}
