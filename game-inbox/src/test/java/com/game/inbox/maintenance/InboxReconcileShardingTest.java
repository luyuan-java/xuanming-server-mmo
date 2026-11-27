package com.game.inbox.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.workshard.WorkShard;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.store.InboxMaintenanceStore;
import com.game.inbox.store.LedgerReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-06 收件箱对账 64 片 HRW 分工与短键分布、最小迁移、未就绪/canary 跳过及卸片清 gauge。 */
class InboxReconcileShardingTest {

    @Test
    void 生产形状短键在多副本间分散且顺序与重复成员无关_增删成员最小迁移() {
        List<String> three = List.of("node-a", "node-b", "node-c");
        List<String> permuted = List.of("node-c", "node-a", "node-b", "node-a");

        for (String prefix : List.of("", "team-launch:", "rank-source:3:")) {
            Map<String, Integer> counts = new HashMap<>();
            for (int s = 0; s < WorkShard.SHARDS; s++) {
                String key = prefix + s;
                String o1 = WorkShard.owner(key, three);
                String o2 = WorkShard.owner(key, permuted);
                assertThat(o1).isEqualTo(o2);
                counts.merge(o1, 1, Integer::sum);
            }
            // 三副本必须均有分担，绝不全部塌到单个副本（修 FNV-1a 短数字后缀塌陷）
            assertThat(counts.keySet()).containsExactlyInAnyOrderElementsOf(three);
            counts.values().forEach(c -> assertThat(c).isGreaterThanOrEqualTo(10));
        }

        // 加第 4 个成员：仅迁移到新成员
        List<String> four = List.of("node-a", "node-b", "node-c", "node-d");
        for (int s = 0; s < WorkShard.SHARDS; s++) {
            String key = Integer.toString(s);
            String before = WorkShard.owner(key, three);
            String after = WorkShard.owner(key, four);
            if (!before.equals(after)) {
                assertThat(after).isEqualTo("node-d");
            }
        }
        // 删去 node-b：仅原属 node-b 的片迁移
        List<String> two = List.of("node-a", "node-c");
        for (int s = 0; s < WorkShard.SHARDS; s++) {
            String key = Integer.toString(s);
            String before = WorkShard.owner(key, three);
            String after = WorkShard.owner(key, two);
            if (!before.equals(after)) {
                assertThat(before).isEqualTo("node-b");
            }
        }
    }

    @Test
    void 双stable副本互补覆盖全部64片_canary与未就绪跳过_卸片清理旧分片读数() throws Exception {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        WorkShard.Membership m1 = new WorkShard.Membership(30_000L, clock::get);
        m1.setSelf("inbox-1");
        WorkShard.Membership m2 = new WorkShard.Membership(30_000L, clock::get);
        m2.setSelf("inbox-2");
        WorkShard.Membership canary = new WorkShard.Membership(30_000L, clock::get);
        canary.setSelf("inbox-canary");

        Set<Integer> scanned1 = new CopyOnWriteArraySet<>();
        Set<Integer> scanned2 = new CopyOnWriteArraySet<>();
        Set<Integer> scannedCanary = new CopyOnWriteArraySet<>();

        InboxMaintenanceStore store1 = mockStoreRecording(scanned1);
        InboxMaintenanceStore store2 = mockStoreRecording(scanned2);
        InboxMaintenanceStore storeCanary = mockStoreRecording(scannedCanary);
        LedgerReader ledgers = mock(LedgerReader.class);
        LeaderLease lease = mock(LeaderLease.class);
        when(lease.ttl()).thenReturn(Duration.ofSeconds(15));
        InboxMaintenance.Settings settings = new InboxMaintenance.Settings(
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofHours(1));

        InboxMetrics metrics1 = new InboxMetrics(new SimpleMeterRegistry());
        InboxMaintenance maint1 = new InboxMaintenance(lease, store1, ledgers, metrics1, settings, clock::get, System::nanoTime, b -> 0)
                .withWorkShard(m1);
        InboxMaintenance maint2 = new InboxMaintenance(lease, store2, ledgers, new InboxMetrics(new SimpleMeterRegistry()), settings, clock::get, System::nanoTime, b -> 0)
                .withWorkShard(m2);
        InboxMaintenance maintCanary = new InboxMaintenance(lease, storeCanary, ledgers, new InboxMetrics(new SimpleMeterRegistry()), settings, clock::get, System::nanoTime, b -> 0)
                .withWorkShard(canary);

        // 1. 成员表未就绪时跳过对账
        maint1.reconcileRoundSharded(maint1.new Round(Duration.ofMinutes(5), false));
        assertThat(scanned1).isEmpty();

        // 2. 成员表就绪（不含 canary）：双 stable 互补扫描全部 64 片，canary 0 片
        m1.updateMembers(List.of("inbox-1", "inbox-2"));
        m2.updateMembers(List.of("inbox-1", "inbox-2"));
        canary.updateMembers(List.of("inbox-1", "inbox-2"));
        maint1.start();
        maint2.start();
        maintCanary.start();
        try {
            maint1.reconcileRoundSharded(maint1.new Round(Duration.ofMinutes(5), false));
            maint2.reconcileRoundSharded(maint2.new Round(Duration.ofMinutes(5), false));
            maintCanary.reconcileRoundSharded(maintCanary.new Round(Duration.ofMinutes(5), false));
        } finally {
            maint1.stop();
            maint2.stop();
            maintCanary.stop();
        }
        assertThat(scannedCanary).isEmpty();
        Set<Integer> union = new HashSet<>(scanned1);
        union.addAll(scanned2);
        assertThat(union).hasSize(WorkShard.SHARDS);
        assertThat(scanned1).doesNotContainAnyElementsOf(scanned2);

        // 3. Tally.retainShards 卸片时清除不再归本副本的分片读数，保留仍在名下的不一致数
        ReconcileRound.Tally tally = new ReconcileRound.Tally();
        assertThat(tally.apply(List.of(
                new ReconcileRound.ShardTally(1, 3, true),
                new ReconcileRound.ShardTally(2, 5, true)))).isEqualTo(8);
        assertThat(tally.retainShards(s -> s == 2)).isEqualTo(5);
    }

    private static InboxMaintenanceStore mockStoreRecording(Set<Integer> scanned) throws Exception {
        InboxMaintenanceStore store = mock(InboxMaintenanceStore.class);
        when(store.recentlyFinalized(anyInt(), anyLong(), org.mockito.ArgumentMatchers.any(), anyInt())).thenAnswer(inv -> {
            scanned.add(inv.getArgument(0, Integer.class));
            return List.of();
        });
        return store;
    }
}
