package com.game.scene.player;

import com.game.player.store.state.PlayerSkillBookState;
import com.game.player.store.state.PlayerSkillEntry;
import com.google.protobuf.UnknownFieldSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 持久技能聚合。只有场景逻辑线程可写；未知字段、未知技能、超版本重数均随对象保存。 */
public final class PlayerSkills {
    private Map<Integer, PlayerSkillEntry> entries;
    private final UnknownFieldSet unknownFields;

    private PlayerSkills(PlayerSkillBookState state) {
        unknownFields = state.getUnknownFields();
        entries = new LinkedHashMap<>();
        for (PlayerSkillEntry entry : state.getEntriesList()) {
            // 与版本无关的坏记录才规整：零号丢弃、重复号保留首条。
            if (entry.getSkillId() != 0) entries.putIfAbsent(entry.getSkillId(), entry);
        }
    }

    public static PlayerSkills restore(PlayerSkillBookState state) { return new PlayerSkills(state); }
    public static PlayerSkills empty() { return restore(PlayerSkillBookState.getDefaultInstance()); }
    public List<PlayerSkillEntry> entries() { return List.copyOf(entries.values()); }
    public boolean contains(int id) { return entries.containsKey(id); }
    public int rank(int id) { return entries.containsKey(id) ? entries.get(id).getRank() : 0; }

    /** 更新原条目保留未知字段；初始补发与已预检的修习调用。 */
    public void setRank(int id, int rank) {
        if (id == 0) throw new IllegalArgumentException("skill id must not be zero");
        PlayerSkillEntry old = entries.get(id);
        entries.put(id, (old == null ? PlayerSkillEntry.newBuilder().setSkillId(id) : old.toBuilder())
                .setRank(rank).build());
    }

    public PlayerSkillBookState toState() {
        return PlayerSkillBookState.newBuilder().setUnknownFields(unknownFields).addAllEntries(entries.values()).build();
    }

    public boolean isPristine() { return entries.isEmpty() && unknownFields.asMap().isEmpty(); }

    /** 候选在扣款前已完整构造，提交只交换引用，不调用外部代码。 */
    public void commitPrepared(PlayerSkills candidate) {
        entries = new LinkedHashMap<>(candidate.entries);
    }
}
