package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 房间表：每次插表 / 移除恰好触发一次回调（逐条移植基线 {@code battle_room_table_test.cpp}；battle-node-spec §4.2、§13.1）。 */
class RoomTableTest {

    /** 测试用房间。 */
    record FakeRoom(int marker) {
    }

    /** 记录每个 id 收到的回调次数。 */
    static final class Recorder implements RoomHooks {
        final Map<Long, Integer> created = new HashMap<>();
        final Map<Long, Integer> removed = new HashMap<>();

        @Override
        public void onCreated(long battleId) {
            created.merge(battleId, 1, Integer::sum);
        }

        @Override
        public void onRemoved(long battleId) {
            removed.merge(battleId, 1, Integer::sum);
        }

        int totalCreated() {
            return created.values().stream().mapToInt(Integer::intValue).sum();
        }

        int totalRemoved() {
            return removed.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final Recorder recorder = new Recorder();
    private final RoomTable<FakeRoom> table = new RoomTable<>(recorder);

    @Test
    void 插入恰好触发一次onCreated() {
        FakeRoom room = table.emplace(7, new FakeRoom(70));

        assertThat(room).isNotNull();
        assertThat(room.marker()).isEqualTo(70);
        assertThat(table.find(7)).isSameAs(room);
        assertThat(table.size()).isEqualTo(1);
        assertThat(recorder.created).containsEntry(7L, 1);
        assertThat(recorder.totalRemoved()).isZero();
    }

    @Test
    void 重复插入被拒_不回调_保留原房间() {
        FakeRoom original = table.emplace(7, new FakeRoom(70));
        FakeRoom duplicate = table.emplace(7, new FakeRoom(71));

        assertThat(duplicate).isNull();
        assertThat(table.find(7)).isSameAs(original);
        assertThat(table.find(7).marker()).isEqualTo(70);
        assertThat(table.size()).isEqualTo(1);
        assertThat(recorder.created).containsEntry(7L, 1);
    }

    @Test
    void null房间被拒且不回调() {
        assertThat(table.emplace(7, null)).isNull();
        assertThat(table.isEmpty()).isTrue();
        assertThat(recorder.totalCreated()).isZero();
    }

    @Test
    void 移除恰好触发一次onRemoved_二次移除是空操作() {
        table.emplace(7, new FakeRoom(70));

        assertThat(table.erase(7)).isTrue();
        assertThat(table.find(7)).isNull();
        assertThat(recorder.removed).containsEntry(7L, 1);

        assertThat(table.erase(7)).isFalse();
        assertThat(recorder.removed).containsEntry(7L, 1);
    }

    @Test
    void 移除未知id不回调() {
        table.emplace(7, new FakeRoom(70));

        assertThat(table.erase(8)).isFalse();
        assertThat(table.size()).isEqualTo(1);
        assertThat(recorder.totalRemoved()).isZero();
    }

    @Test
    void 按快照批量移除每间恰好一次() {
        for (long id : new long[] {3, 1, 2}) {
            table.emplace(id, new FakeRoom((int) id));
        }

        List<Long> ids = table.ids();
        assertThat(ids).containsExactly(1L, 2L, 3L);
        for (long id : ids) {
            assertThat(table.erase(id)).isTrue();
        }

        assertThat(table.isEmpty()).isTrue();
        assertThat(recorder.totalRemoved()).isEqualTo(3);
        ids.forEach(id -> assertThat(recorder.removed).containsEntry(id, 1));
    }

    @Test
    void 回调里看到的表已经更新() {
        boolean[] seen = new boolean[2];
        RoomTable<FakeRoom>[] holder = new RoomTable[1];
        holder[0] = new RoomTable<>(new RoomHooks() {
            @Override
            public void onCreated(long battleId) {
                seen[0] = holder[0].find(battleId) != null;
            }

            @Override
            public void onRemoved(long battleId) {
                seen[1] = holder[0].find(battleId) == null;
            }
        });

        holder[0].emplace(7, new FakeRoom(70));
        holder[0].erase(7);

        assertThat(seen[0]).as("onCreated 时房间已在表里").isTrue();
        assertThat(seen[1]).as("onRemoved 时房间已不在表里").isTrue();
    }

    @Test
    void 没设回调就跳过() {
        RoomTable<FakeRoom> bare = new RoomTable<>();

        assertThat(bare.emplace(7, new FakeRoom(70))).isNotNull();
        assertThat(bare.erase(7)).isTrue();
        assertThat(bare.isEmpty()).isTrue();
    }

    @Test
    void 无符号id排序_回调里重入增删直接抛异常() {
        table.emplace(-1L, new FakeRoom(1));
        table.emplace(5L, new FakeRoom(2));
        assertThat(table.ids()).as("uint64 的最大值排在最后").containsExactly(5L, -1L);

        RoomTable<FakeRoom>[] holder = new RoomTable[1];
        holder[0] = new RoomTable<>(new RoomHooks() {
            @Override
            public void onCreated(long battleId) {
                holder[0].erase(battleId);
            }

            @Override
            public void onRemoved(long battleId) {
            }
        });
        assertThatThrownBy(() -> holder[0].emplace(9, new FakeRoom(9))).isInstanceOf(IllegalStateException.class);
    }
}
