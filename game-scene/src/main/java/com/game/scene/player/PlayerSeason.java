package com.game.scene.player;

import com.game.battle.engine.SeasonRules;
import com.game.player.store.state.PlayerSeasonSealRecordState;
import com.game.player.store.state.PlayerSeasonState;
import com.game.player.store.state.SavedBattleBuildBookmarkState;
import com.game.player.store.state.WuxingTowerState;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 玩家单槽赛季印存档状态（对齐 C++ {@code PlayerSeasonComp}，含 V4 永久徽章动态位图、往季追忆、五行阵法/幻境与配装书签）。
 * 按 {@code season_id} 索引保留全部历史赛季记录及未知字段；仅在场景逻辑线程读写。
 */
public final class PlayerSeason {

    private final Map<Integer, PlayerSeasonSealRecordState> records = new TreeMap<>(Integer::compareUnsigned);
    private final List<Long> unlockedHonorWords = new ArrayList<>();
    private int retroHonorTargetSeasonId;
    private final Map<Integer, Integer> retroHonorProgress = new TreeMap<>(Integer::compareUnsigned);
    private int equippedFormationId;
    private WuxingTowerState wuxingTower = WuxingTowerState.getDefaultInstance();
    private final List<SavedBattleBuildBookmarkState> savedBuilds = new ArrayList<>();
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerSeason() {
    }

    public static PlayerSeason empty() {
        return new PlayerSeason();
    }

    public static PlayerSeason restore(PlayerSeasonState state) {
        PlayerSeason out = new PlayerSeason();
        if (state == null) {
            return out;
        }
        for (PlayerSeasonSealRecordState record : state.getRecordsList()) {
            if (record.getSeasonId() != 0) {
                out.records.putIfAbsent(record.getSeasonId(), record);
            }
        }
        out.unlockedHonorWords.addAll(state.getUnlockedHonorWordsList());
        out.retroHonorTargetSeasonId = state.getRetroHonorTargetSeasonId();
        for (Map.Entry<Integer, Integer> entry : state.getRetroHonorProgressMap().entrySet()) {
            if (entry.getKey() != 0 && entry.getValue() != 0) {
                out.retroHonorProgress.put(entry.getKey(), entry.getValue());
            }
        }
        out.equippedFormationId = state.getEquippedFormationId();
        if (state.hasWuxingTower()) {
            out.wuxingTower = state.getWuxingTower();
        }
        out.savedBuilds.addAll(state.getSavedBuildsList());
        out.unknownFields = state.getUnknownFields();
        return out;
    }

    public PlayerSeasonSealRecordState record(int seasonId) {
        return seasonId == 0 ? null : records.get(seasonId);
    }

    public List<Integer> seasonIds() {
        return List.copyOf(records.keySet());
    }

    public void putRecord(PlayerSeasonSealRecordState record) {
        if (record == null || record.getSeasonId() == 0) {
            return;
        }
        records.put(record.getSeasonId(), record);
    }

    public boolean isHonorBitSet(int seasonId) {
        return SeasonRules.isHonorBitSet(unlockedHonorWords, seasonId);
    }

    public boolean setHonorBit(int seasonId) {
        return SeasonRules.setHonorBit(unlockedHonorWords, seasonId);
    }

    public int retroHonorTargetSeasonId() {
        return retroHonorTargetSeasonId;
    }

    public void setRetroHonorTargetSeasonId(int seasonId) {
        this.retroHonorTargetSeasonId = seasonId;
    }

    public int retroHonorProgress(int seasonId) {
        return seasonId == 0 ? 0 : retroHonorProgress.getOrDefault(seasonId, 0);
    }

    public void putRetroHonorProgress(int seasonId, int points) {
        if (seasonId == 0) {
            return;
        }
        if (points == 0) {
            retroHonorProgress.remove(seasonId);
        } else {
            retroHonorProgress.put(seasonId, points);
        }
    }

    public void removeRetroHonorProgress(int seasonId) {
        retroHonorProgress.remove(seasonId);
    }

    public int equippedFormationId() {
        return equippedFormationId;
    }

    public void setEquippedFormationId(int formationId) {
        this.equippedFormationId = formationId;
    }

    public WuxingTowerState wuxingTower() {
        return wuxingTower;
    }

    public void setWuxingTower(WuxingTowerState wuxingTower) {
        this.wuxingTower = wuxingTower == null ? WuxingTowerState.getDefaultInstance() : wuxingTower;
    }

    public List<SavedBattleBuildBookmarkState> savedBuilds() {
        return List.copyOf(savedBuilds);
    }

    public void setSavedBuilds(List<SavedBattleBuildBookmarkState> next) {
        savedBuilds.clear();
        if (next != null) {
            savedBuilds.addAll(next);
        }
    }

    public boolean isEmpty() {
        return records.isEmpty()
                && unlockedHonorWords.isEmpty()
                && retroHonorTargetSeasonId == 0
                && retroHonorProgress.isEmpty()
                && equippedFormationId == 0
                && wuxingTower.equals(WuxingTowerState.getDefaultInstance())
                && savedBuilds.isEmpty()
                && unknownFields.asMap().isEmpty();
    }

    public PlayerSeasonState toState() {
        PlayerSeasonState.Builder out = PlayerSeasonState.newBuilder();
        records.values().stream()
                .sorted(Comparator.comparing(PlayerSeasonSealRecordState::getSeasonId, Integer::compareUnsigned))
                .forEach(out::addRecords);
        out.addAllUnlockedHonorWords(unlockedHonorWords);
        out.setRetroHonorTargetSeasonId(retroHonorTargetSeasonId);
        out.putAllRetroHonorProgress(retroHonorProgress);
        if (equippedFormationId != 0) {
            out.setEquippedFormationId(equippedFormationId);
        }
        if (!wuxingTower.equals(WuxingTowerState.getDefaultInstance())) {
            out.setWuxingTower(wuxingTower);
        }
        if (!savedBuilds.isEmpty()) {
            out.addAllSavedBuilds(savedBuilds);
        }
        out.setUnknownFields(unknownFields);
        return out.build();
    }
}
