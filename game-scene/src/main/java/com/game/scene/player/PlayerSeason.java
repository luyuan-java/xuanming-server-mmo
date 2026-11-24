package com.game.scene.player;

import com.game.player.store.state.PlayerSeasonSealRecordState;
import com.game.player.store.state.PlayerSeasonState;
import com.google.protobuf.UnknownFieldSet;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * 玩家单槽赛季印存档状态（对齐 C++ {@code PlayerSeasonComp}）。
 * 按 {@code season_id} 索引保留全部历史赛季记录及未知字段；仅在场景逻辑线程读写。
 */
public final class PlayerSeason {

    private final Map<Integer, PlayerSeasonSealRecordState> records = new TreeMap<>(Integer::compareUnsigned);
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
        out.unknownFields = state.getUnknownFields();
        return out;
    }

    public PlayerSeasonSealRecordState record(int seasonId) {
        return seasonId == 0 ? null : records.get(seasonId);
    }

    public void putRecord(PlayerSeasonSealRecordState record) {
        if (record == null || record.getSeasonId() == 0) {
            return;
        }
        records.put(record.getSeasonId(), record);
    }

    public boolean isEmpty() {
        return records.isEmpty() && unknownFields.asMap().isEmpty();
    }

    public PlayerSeasonState toState() {
        PlayerSeasonState.Builder out = PlayerSeasonState.newBuilder();
        records.values().stream()
                .sorted(Comparator.comparing(PlayerSeasonSealRecordState::getSeasonId, Integer::compareUnsigned))
                .forEach(out::addRecords);
        out.setUnknownFields(unknownFields);
        return out.build();
    }
}
