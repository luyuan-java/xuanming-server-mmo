package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.MysteryMerchantState;
import com.game.proto.SceneChannelDirectory;
import com.game.proto.SceneChannelInfo;
import com.game.proto.SceneChannelState;
import com.game.scenemanager.WorldSceneConfigs;
import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 神秘商人调度器验收：正式配表加载、整点出现、同轮幂等、CLOSING 立即换线、连续缺席 3 拍换线、跨轮新掷。 */
class MysteryMerchantSchedulerTest {

    private static final class MemoryStore implements MysteryMerchantScheduler.Store {
        private final Map<Integer, Long> versions = new HashMap<>();
        private final Map<Integer, byte[]> states = new HashMap<>();
        int writes;

        @Override
        public byte[] readState(int zoneId) {
            return states.get(zoneId);
        }

        @Override
        public boolean writeIfNewer(int zoneId, long version, byte[] stateBytes, long ttlSeconds, boolean force) {
            long current = versions.getOrDefault(zoneId, 0L);
            if (!force && Long.compareUnsigned(current, version) >= 0) {
                return false;
            }
            versions.put(zoneId, version);
            states.put(zoneId, stateBytes);
            writes++;
            return true;
        }
    }

    @Test
    void 神秘商人_出现同轮幂等换线与跨轮新掷() throws Exception {
        Path tableDir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        ConfigTables config = ConfigTables.load(tableDir);
        WorldSceneConfigs worlds = WorldSceneConfigs.fromWorldTable(config.world().all(), null);
        MysteryMerchantScheduler.Tables tables = MysteryMerchantScheduler.loadTables(config, worlds.worldConfigIds());
        assertThat(tables).as("正式配表应合法").isNotNull();

        int mapId = tables.spotsByConfig().keySet().iterator().next();
        MemoryStore store = new MemoryStore();
        MysteryMerchantScheduler scheduler = new MysteryMerchantScheduler(tables, store, bound -> 0L);

        long intervalMs = tables.rule().intervalSeconds() * 1000L;
        long round1Ms = 1_700_000_000_000L - (1_700_000_000_000L % intervalMs) + 5_000L;

        // 1. 首次在场内时间推进：落到 1 号线，revision=1，货架非空且无重复商品
        Map<Integer, SceneChannelDirectory> dir1 = Map.of(mapId, dir(mapId,
                entry(101L, 1, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH),
                entry(102L, 2, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH)));
        scheduler.tick(1, dir1, round1Ms);
        assertThat(store.writes).isEqualTo(1);
        MysteryMerchantState first = MysteryMerchantState.parseFrom(store.readState(1));
        assertThat(first.getRevision()).isEqualTo(1);
        assertThat(first.getSceneId()).isEqualTo(101L);
        assertThat(first.getShelfList()).isNotEmpty();

        // 2. 线路健康时同轮重复 tick：不重写
        scheduler.tick(1, dir1, round1Ms + 5_000L);
        assertThat(store.writes).isEqualTo(1);

        // 3. 101 变 CLOSING：立即换线到 102，revision=2 且保留原货架
        Map<Integer, SceneChannelDirectory> dirClosing = Map.of(mapId, dir(mapId,
                entry(101L, 1, SceneChannelState.SCENE_CHANNEL_STATE_CLOSING),
                entry(102L, 2, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH)));
        scheduler.tick(1, dirClosing, round1Ms + 10_000L);
        assertThat(store.writes).isEqualTo(2);
        MysteryMerchantState relocated = MysteryMerchantState.parseFrom(store.readState(1));
        assertThat(relocated.getRevision()).isEqualTo(2);
        assertThat(relocated.getSceneId()).isEqualTo(102L);
        assertThat(relocated.getShelfList()).isEqualTo(first.getShelfList());

        // 4. 102 连续 2 拍缺失不换线，第 3 拍缺失才换到 103（revision=3）
        Map<Integer, SceneChannelDirectory> dirMissing = Map.of(mapId, dir(mapId,
                entry(103L, 3, SceneChannelState.SCENE_CHANNEL_STATE_FULL)));
        scheduler.tick(1, dirMissing, round1Ms + 15_000L);
        scheduler.tick(1, dirMissing, round1Ms + 20_000L);
        assertThat(store.writes).isEqualTo(2);
        scheduler.tick(1, dirMissing, round1Ms + 25_000L);
        assertThat(store.writes).isEqualTo(3);
        MysteryMerchantState relocated2 = MysteryMerchantState.parseFrom(store.readState(1));
        assertThat(relocated2.getRevision()).isEqualTo(3);
        assertThat(relocated2.getSceneId()).isEqualTo(103L);

        // 5. 跨入下一轮：revision 重置为 1，roundId 递增
        scheduler.tick(1, dir1, round1Ms + intervalMs);
        assertThat(store.writes).isEqualTo(4);
        MysteryMerchantState nextRound = MysteryMerchantState.parseFrom(store.readState(1));
        assertThat(nextRound.getRevision()).isEqualTo(1);
        assertThat(nextRound.getRoundId()).isGreaterThan(first.getRoundId());
    }

    private static SceneChannelDirectory dir(int configId, SceneChannelInfo... entries) {
        SceneChannelDirectory.Builder b = SceneChannelDirectory.newBuilder().setSceneConfigId(configId);
        for (SceneChannelInfo e : entries) {
            b.addChannels(e);
        }
        return b.build();
    }

    private static SceneChannelInfo entry(long sceneId, int channelNo, SceneChannelState state) {
        return SceneChannelInfo.newBuilder().setSceneId(sceneId).setChannelNo(channelNo).setState(state).build();
    }
}
