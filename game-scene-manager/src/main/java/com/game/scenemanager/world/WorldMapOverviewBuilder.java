package com.game.scenemanager.world;

import com.game.proto.SceneChannelDirectory;
import com.game.proto.SceneChannelInfo;
import com.game.proto.SceneChannelState;
import com.game.proto.WorldMapHeatLevel;
import com.game.proto.WorldMapOverview;
import com.game.proto.WorldMapOverviewEntry;
import com.game.proto.WorldTideSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 世界地图总览与双层潮汐的纯函数构建器（docs/design/world-map-play-v2.md §2.3–§2.4）。
 * 与 Go 版 {@code world_map_overview.go} 保持完全一致的确定性轮换公式，领导者切换或跨版无状态漂移。
 */
public final class WorldMapOverviewBuilder {

    /** 东八区 05:00 跨日边界 = UTC 21:00，对应 +3 小时偏移再按 86400 秒取整。 */
    private static final long DAILY_HOTSPOT_OFFSET_SECONDS = 3L * 3600L;
    private static final long SECONDS_PER_DAY = 86_400L;

    private WorldMapOverviewBuilder() {
    }

    /** 按墙钟确定性计算当前灵潮轮次。 */
    public static WorldTideSnapshot worldTideAt(long nowSeconds, List<Integer> candidates,
                                                long intervalSeconds, long durationSeconds) {
        if (candidates == null || candidates.isEmpty() || intervalSeconds <= 0) {
            return WorldTideSnapshot.getDefaultInstance();
        }
        long clampedNow = Math.max(0L, nowSeconds);
        long clampedDuration = Math.max(0L, Math.min(durationSeconds, intervalSeconds));
        long roundIndex = clampedNow / intervalSeconds;
        long startAt = roundIndex * intervalSeconds;
        long endAt = startAt + clampedDuration;
        long nextStartAt = startAt + intervalSeconds;
        int sceneConfId = candidates.get((int) Long.remainderUnsigned(roundIndex, candidates.size()));
        return WorldTideSnapshot.newBuilder()
                .setActive(clampedNow < endAt)
                .setRoundId(startAt)
                .setSceneConfigId(sceneConfId)
                .setStartsAtMs(startAt * 1000L)
                .setEndsAtMs(endAt * 1000L)
                .setNextStartsAtMs(nextStartAt * 1000L)
                .build();
    }

    /** 按东八区 05:00 跨日确定性计算今日旺地地图。 */
    public static int dailyHotspotAt(long nowSeconds, List<Integer> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        long clampedNow = Math.max(0L, nowSeconds);
        long dayIndex = (clampedNow + DAILY_HOTSPOT_OFFSET_SECONDS) / SECONDS_PER_DAY;
        return candidates.get((int) Long.remainderUnsigned(dayIndex, candidates.size()));
    }

    public static WorldMapHeatLevel classifyHeat(int totalPlayers, int crowdedThreshold) {
        long unsignedPlayers = Integer.toUnsignedLong(totalPlayers);
        if (unsignedPlayers == 0L) {
            return WorldMapHeatLevel.WORLD_MAP_HEAT_LEVEL_QUIET;
        }
        long threshold = Math.max(1L, Integer.toUnsignedLong(crowdedThreshold));
        return unsignedPlayers >= threshold
                ? WorldMapHeatLevel.WORLD_MAP_HEAT_LEVEL_CROWDED
                : WorldMapHeatLevel.WORLD_MAP_HEAT_LEVEL_ACTIVE;
    }

    public static WorldMapOverview build(int zoneId, WorldChannelProperties.WorldMapPlay play,
                                         Map<Integer, SceneChannelDirectory> directoriesByConfig, long nowMs) {
        return build(zoneId, play, directoriesByConfig, nowMs, 0);
    }

    public static WorldMapOverview build(int zoneId, WorldChannelProperties.WorldMapPlay play,
                                         Map<Integer, SceneChannelDirectory> directoriesByConfig, long nowMs,
                                         int mysteryMerchantSceneConfigId) {
        long nowSeconds = Math.floorDiv(nowMs, 1000L);
        List<Integer> tideCandidates = play.tideCandidateMapIds();
        WorldTideSnapshot.Builder tide = worldTideAt(nowSeconds, tideCandidates,
                play.tideInterval().toSeconds(), play.tideDuration().toSeconds()).toBuilder();
        int dailyHotspot = dailyHotspotAt(nowSeconds, tideCandidates);
        int crowdedThreshold = play.crowdedPlayerThreshold();

        List<WorldMapOverviewEntry> entries = new ArrayList<>();
        for (int configId : play.openWorldMapIds()) {
            SceneChannelDirectory dir = directoriesByConfig.get(configId);
            if (dir == null || dir.getChannelsCount() == 0) {
                continue;
            }
            long totalPlayers = 0L;
            int activeChannels = 0;
            long recSceneId = 0L;
            int recChannelNo = 0;
            long fallbackSceneId = 0L;
            int fallbackChannelNo = 0;
            for (SceneChannelInfo ch : dir.getChannelsList()) {
                totalPlayers += Integer.toUnsignedLong(ch.getPlayerCount());
                SceneChannelState state = ch.getState();
                if (state != SceneChannelState.SCENE_CHANNEL_STATE_CLOSING
                        && state != SceneChannelState.SCENE_CHANNEL_STATE_UNAVAILABLE) {
                    activeChannels++;
                }
                if (ch.getSceneId() == 0L) {
                    continue;
                }
                if (recSceneId == 0L && (state == SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH
                        || state == SceneChannelState.SCENE_CHANNEL_STATE_BUSY)) {
                    recSceneId = ch.getSceneId();
                    recChannelNo = ch.getChannelNo();
                } else if (fallbackSceneId == 0L && state == SceneChannelState.SCENE_CHANNEL_STATE_FULL) {
                    fallbackSceneId = ch.getSceneId();
                    fallbackChannelNo = ch.getChannelNo();
                }
            }
            if (recSceneId == 0L) {
                recSceneId = fallbackSceneId;
                recChannelNo = fallbackChannelNo;
            }
            int clampedPlayers = (int) Math.min(totalPlayers, 0xFFFF_FFFFL);
            boolean isTideMap = tide.getActive() && tide.getSceneConfigId() == configId;
            if (tide.getSceneConfigId() == configId) {
                tide.setRecommendedSceneId(recSceneId).setRecommendedChannelNo(recChannelNo);
            }
            entries.add(WorldMapOverviewEntry.newBuilder()
                    .setSceneConfigId(configId)
                    .setTotalPlayerCount(clampedPlayers)
                    .setChannelCount(activeChannels)
                    .setHeatLevel(classifyHeat(clampedPlayers, crowdedThreshold))
                    .setRecommendedSceneId(recSceneId)
                    .setRecommendedChannelNo(recChannelNo)
                    .setIsTideMap(isTideMap)
                    .setHasMysteryMerchant(mysteryMerchantSceneConfigId != 0 && mysteryMerchantSceneConfigId == configId)
                    .build());
        }
        entries.sort(Comparator.comparing(WorldMapOverviewEntry::getSceneConfigId, Integer::compareUnsigned));

        return WorldMapOverview.newBuilder()
                .setZoneId(zoneId)
                .setUpdatedAtMs(nowMs)
                .setServerTimeMs(nowMs)
                .addAllMaps(entries)
                .setTide(tide.build())
                .setDailyHotspotSceneConfigId(dailyHotspot)
                .build();
    }
}
