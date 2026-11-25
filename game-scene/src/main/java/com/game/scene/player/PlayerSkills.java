package com.game.scene.player;

import com.game.player.store.state.PlayerSkillBookState;
import com.game.player.store.state.PlayerSkillEntry;
import com.game.player.store.state.SkillLoadoutSchemeState;
import com.google.protobuf.UnknownFieldSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 持久技能聚合。只有场景逻辑线程可写；未知字段、未知技能、超版本重数与战法构筑方案均随对象保存。 */
public final class PlayerSkills {
    private Map<Integer, PlayerSkillEntry> entries;
    private int activeLoadoutSchemeId;
    private Map<Integer, SkillLoadoutSchemeState> loadoutSchemes;
    private long loadoutStateVersion;
    private String loadoutLastRequestId;
    private long loadoutLastBaseVersion;
    private int loadoutLastSchemeId;
    private final UnknownFieldSet unknownFields;

    private PlayerSkills(PlayerSkillBookState state) {
        unknownFields = state.getUnknownFields();
        entries = new LinkedHashMap<>();
        for (PlayerSkillEntry entry : state.getEntriesList()) {
            // 与版本无关的坏记录才规整：零号丢弃、重复号保留首条。
            if (entry.getSkillId() != 0) entries.putIfAbsent(entry.getSkillId(), entry);
        }
        activeLoadoutSchemeId = state.getActiveLoadoutSchemeId();
        loadoutSchemes = new LinkedHashMap<>();
        for (SkillLoadoutSchemeState scheme : state.getLoadoutSchemesList()) {
            if (scheme.getSchemeId() != 0) loadoutSchemes.putIfAbsent(scheme.getSchemeId(), scheme);
        }
        loadoutStateVersion = state.getLoadoutStateVersion();
        loadoutLastRequestId = state.getLoadoutLastRequestId();
        loadoutLastBaseVersion = state.getLoadoutLastBaseVersion();
        loadoutLastSchemeId = state.getLoadoutLastSchemeId();
    }

    public static PlayerSkills restore(PlayerSkillBookState state) { return new PlayerSkills(state); }
    public static PlayerSkills empty() { return restore(PlayerSkillBookState.getDefaultInstance()); }
    public List<PlayerSkillEntry> entries() { return List.copyOf(entries.values()); }
    public boolean contains(int id) { return entries.containsKey(id); }
    public int rank(int id) { return entries.containsKey(id) ? entries.get(id).getRank() : 0; }

    public int activeLoadoutSchemeId() { return activeLoadoutSchemeId; }
    public SkillLoadoutSchemeState loadoutScheme(int schemeId) { return loadoutSchemes.get(schemeId); }
    public Map<Integer, SkillLoadoutSchemeState> loadoutSchemes() { return Collections.unmodifiableMap(loadoutSchemes); }
    public long loadoutStateVersion() { return loadoutStateVersion; }
    public String loadoutLastRequestId() { return loadoutLastRequestId; }
    public long loadoutLastBaseVersion() { return loadoutLastBaseVersion; }
    public int loadoutLastSchemeId() { return loadoutLastSchemeId; }

    /** 更新原条目保留未知字段；初始补发与已预检的修习调用。 */
    public void setRank(int id, int rank) {
        if (id == 0) throw new IllegalArgumentException("skill id must not be zero");
        PlayerSkillEntry old = entries.get(id);
        entries.put(id, (old == null ? PlayerSkillEntry.newBuilder().setSkillId(id) : old.toBuilder())
                .setRank(rank).build());
    }

    /** 更新或新增指定战法构筑方案（保留未认识的方案字段）。 */
    public void putLoadoutScheme(SkillLoadoutSchemeState scheme) {
        if (scheme.getSchemeId() == 0) throw new IllegalArgumentException("scheme id must not be zero");
        loadoutSchemes.put(scheme.getSchemeId(), scheme);
    }

    /** 原子落盘战法构筑方案切换或保存的回执元数据。 */
    public void commitLoadout(int activeSchemeId, long newVersion, String requestId, long baseVersion, int schemeId) {
        this.activeLoadoutSchemeId = activeSchemeId;
        this.loadoutStateVersion = newVersion;
        this.loadoutLastRequestId = requestId;
        this.loadoutLastBaseVersion = baseVersion;
        this.loadoutLastSchemeId = schemeId;
    }

    public PlayerSkillBookState toState() {
        return PlayerSkillBookState.newBuilder()
                .setUnknownFields(unknownFields)
                .addAllEntries(entries.values())
                .setActiveLoadoutSchemeId(activeLoadoutSchemeId)
                .addAllLoadoutSchemes(loadoutSchemes.values())
                .setLoadoutStateVersion(loadoutStateVersion)
                .setLoadoutLastRequestId(loadoutLastRequestId)
                .setLoadoutLastBaseVersion(loadoutLastBaseVersion)
                .setLoadoutLastSchemeId(loadoutLastSchemeId)
                .build();
    }

    public boolean isPristine() {
        return entries.isEmpty()
                && activeLoadoutSchemeId == 0
                && loadoutSchemes.isEmpty()
                && loadoutStateVersion == 0
                && loadoutLastRequestId.isEmpty()
                && loadoutLastBaseVersion == 0
                && loadoutLastSchemeId == 0
                && unknownFields.asMap().isEmpty();
    }

    /** 候选在扣款前已完整构造，提交只交换引用，不调用外部代码。 */
    public void commitPrepared(PlayerSkills candidate) {
        entries = new LinkedHashMap<>(candidate.entries);
        activeLoadoutSchemeId = candidate.activeLoadoutSchemeId;
        loadoutSchemes = new LinkedHashMap<>(candidate.loadoutSchemes);
        loadoutStateVersion = candidate.loadoutStateVersion;
        loadoutLastRequestId = candidate.loadoutLastRequestId;
        loadoutLastBaseVersion = candidate.loadoutLastBaseVersion;
        loadoutLastSchemeId = candidate.loadoutLastSchemeId;
    }
}
