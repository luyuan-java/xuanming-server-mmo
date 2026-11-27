package com.game.scene.player;

import com.game.player.store.state.SavedBuffEntryState;
import com.game.player.store.state.SavedBuffListState;
import com.game.table.BuffTable;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家实时场景 Buff 运行态与下线保留存档管理（只在场景逻辑线程上读写；规则见主仓 {@code realtime-cooldown-buff-persistence.md}）。
 *
 * <ul>
 *   <li>仅 {@code BuffTable.keep_on_logout != 0} 的非子 Buff 随玩家存档持久化，按 UTC 绝对到期毫秒保存（0 = 永久），离线期间照常计时；</li>
 *   <li>保留类 Buff 在线时按 {@code no_caster = 1} 叠层（忽略施法者），子 Buff 不单独存，父项恢复后重挂；</li>
 *   <li>再施加同表同有效施法者的有限时长 Buff 时，层数叠加到 {@code max_layer} 且剩余时长刷新回表里的整时长（永久/瞬时不动）；</li>
 *   <li>加载恢复按当刻配表裁剪：查不到表行且未到期的原样带回（含未知字段，不生效），表里为 0 或已到期的丢弃，剩余时长不超过表时长，
 *       且未超表时长时保留存档里的原始到期毫秒（重建任意次零漂移）；</li>
 *   <li>存盘导出不按当前时钟过滤，保证存档字节只由状态变化驱动。</li>
 * </ul>
 */
public final class PlayerRealtimeBuffs {

    private static final Logger log = LoggerFactory.getLogger(PlayerRealtimeBuffs.class);

    /** 一条实时场景 Buff 实例。 */
    public static final class ActiveBuff {
        private final long buffId;
        private final int buffTableId;
        private final long casterId;
        private int layer;
        private long expireAtMs;
        private final boolean keepOnLogout;
        private final boolean subBuff;
        private final long parentBuffId;
        private final UnknownFieldSet persistUnknown;

        ActiveBuff(long buffId, int buffTableId, long casterId, int layer, long expireAtMs,
                   boolean keepOnLogout, boolean subBuff, long parentBuffId, UnknownFieldSet persistUnknown) {
            this.buffId = buffId;
            this.buffTableId = buffTableId;
            this.casterId = casterId;
            this.layer = layer;
            this.expireAtMs = expireAtMs;
            this.keepOnLogout = keepOnLogout;
            this.subBuff = subBuff;
            this.parentBuffId = parentBuffId;
            this.persistUnknown = persistUnknown == null ? UnknownFieldSet.getDefaultInstance() : persistUnknown;
        }

        public long buffId() { return buffId; }
        public int buffTableId() { return buffTableId; }
        public long casterId() { return casterId; }
        public int layer() { return layer; }
        /** UTC 绝对到期毫秒；0 表示永久或无定时截止。 */
        public long expireAtMs() { return expireAtMs; }
        public boolean keepOnLogout() { return keepOnLogout; }
        public boolean subBuff() { return subBuff; }
        public long parentBuffId() { return parentBuffId; }
        UnknownFieldSet persistUnknown() { return persistUnknown; }
    }

    private long nextBuffId = 1L;
    private final List<ActiveBuff> active = new ArrayList<>();
    /** 本节点配表里查不到行的未到期保留条目（混版本保护：不生效，原样带回存档）。 */
    private final List<SavedBuffEntryState> unresolved = new ArrayList<>();
    /** 列表级未知字段（混版本保护）。 */
    private UnknownFieldSet listUnknownFields = UnknownFieldSet.getDefaultInstance();
    /** 构造时从存档收下、尚未执行 {@link #restorePending} 的原始列表。 */
    private SavedBuffListState pendingSaved;

    public static PlayerRealtimeBuffs empty() {
        return new PlayerRealtimeBuffs(null);
    }

    public static PlayerRealtimeBuffs fromSaved(SavedBuffListState saved) {
        return new PlayerRealtimeBuffs(saved);
    }

    private PlayerRealtimeBuffs(SavedBuffListState saved) {
        this.pendingSaved = saved;
    }

    /** 当前活跃的实时 Buff 列表（只读视图，不含未识别表行的暂存条目）。 */
    public List<ActiveBuff> activeBuffs() {
        return List.copyOf(active);
    }

    /** 暂存的未识别表行条目（供单测核验混版本保护）。 */
    public List<SavedBuffEntryState> unresolvedEntries() {
        return List.copyOf(unresolved);
    }

    /**
     * 施加或刷新一条实时 Buff。
     *
     * <p>{@code keep_on_logout != 0} 或 {@code no_caster != 0} 时按无施法者（{@code casterId = 0}）叠层；
     * 命中已有同表同有效施法者的未到期条目时，层数 +1（上限 {@code max(1, max_layer)}）并把有限时长 Buff 的到期时刻刷新为 {@code nowMs + 表时长}。
     */
    public ActiveBuff apply(BuffTable row, long casterId, long nowMs, Function<Integer, BuffTable> tableLookup) {
        if (row == null || row.getId() == 0) {
            return null;
        }
        boolean keepOnLogout = row.getKeepOnLogout() != 0;
        long effectiveCaster = (row.getNoCaster() != 0 || keepOnLogout) ? 0L : casterId;
        boolean permanent = row.getInfiniteDuration() != 0;
        long durationMs = finiteDurationMs(row);
        int maxLayer = Math.max(1, row.getMaxLayer());

        ActiveBuff existing = findActiveAndPruneExpired(row.getId(), effectiveCaster, nowMs);
        if (existing != null) {
            existing.layer = Math.min(existing.layer + 1, maxLayer);
            if (!permanent && durationMs > 0L) {
                existing.expireAtMs = nowMs + durationMs;
            }
            return existing;
        }

        long expireAtMs = permanent ? 0L : (durationMs > 0L ? nowMs + durationMs : 0L);
        ActiveBuff created = new ActiveBuff(nextBuffId++, row.getId(), effectiveCaster, 1, expireAtMs,
                keepOnLogout, false, 0L, UnknownFieldSet.getDefaultInstance());
        active.add(created);
        attachSubBuffs(row, created.buffId(), effectiveCaster, nowMs, tableLookup);
        return created;
    }

    /** 显式清理已过期的活跃 Buff 及其子 Buff（不用于存盘导出，仅用于运行时按需清理）。 */
    public void purgeExpired(long nowMs) {
        Set<Long> expiredParents = new HashSet<>();
        Iterator<ActiveBuff> it = active.iterator();
        while (it.hasNext()) {
            ActiveBuff b = it.next();
            if (isExpired(b.expireAtMs, nowMs)) {
                expiredParents.add(b.buffId);
                it.remove();
            }
        }
        if (!expiredParents.isEmpty()) {
            active.removeIf(b -> b.subBuff && expiredParents.contains(b.parentBuffId));
        }
    }

    /**
     * 进场加载规整：按加载当刻的配表恢复存档中的保留 Buff。
     * 不重跑免疫、驱散、叠层准入判断；按无施法者（{@code casterId = 0}）恢复，并重挂父项的子 Buff。
     */
    public void restorePending(Function<Integer, BuffTable> tableLookup, long nowMs) {
        if (pendingSaved == null) {
            return;
        }
        SavedBuffListState raw = pendingSaved;
        pendingSaved = null;
        listUnknownFields = raw.getUnknownFields();
        unresolved.clear();

        Set<Integer> restoredTables = new HashSet<>();
        for (SavedBuffEntryState entry : raw.getEntriesList()) {
            int tableId = entry.getBuffTableId();
            if (tableId == 0) {
                continue;
            }
            long savedExpire = entry.getExpireAtMs();
            BuffTable row = tableLookup == null ? null : tableLookup.apply(tableId);
            if (row == null) {
                // 本节点配表里查不到行（更新版本新增或未热更到）：未到期原样带回，不生效
                if (!isExpired(savedExpire, nowMs)) {
                    unresolved.add(entry);
                }
                continue;
            }
            if (row.getKeepOnLogout() == 0) {
                log.info("drop saved realtime buff whose keep_on_logout=0 buff_table_id={}",
                        Integer.toUnsignedString(tableId));
                continue;
            }
            if (!restoredTables.add(tableId)) {
                continue;
            }

            boolean permanent = row.getInfiniteDuration() != 0;
            long expireAtMs;
            if (permanent) {
                expireAtMs = 0L;
            } else {
                long tableDurationMs = finiteDurationMs(row);
                if (tableDurationMs <= 0L) {
                    continue;
                }
                if (savedExpire == 0L) {
                    // 存档时为永久，表改为有时限：从加载时刻起算一个整时长
                    expireAtMs = nowMs + tableDurationMs;
                } else if (isExpired(savedExpire, nowMs)) {
                    continue;
                } else {
                    long maxAllowed = nowMs + tableDurationMs;
                    // 未超过表时长时原样保留存档值，避免多次重建漂移
                    expireAtMs = Long.compareUnsigned(savedExpire, maxAllowed) > 0 ? maxAllowed : savedExpire;
                }
            }

            int maxLayer = Math.max(1, row.getMaxLayer());
            int rawLayer = entry.getLayer();
            int layer = rawLayer == 0 ? 1 : (Integer.compareUnsigned(rawLayer, maxLayer) > 0 ? maxLayer : rawLayer);
            ActiveBuff restored = new ActiveBuff(nextBuffId++, tableId, 0L, layer, expireAtMs,
                    true, false, 0L, entry.getUnknownFields());
            active.add(restored);
            attachSubBuffs(row, restored.buffId(), 0L, nowMs, tableLookup);
        }
    }

    /** 是否无任何可持久化条目且无列表级未知字段（新号或老档省略写该段）。 */
    public boolean isPristine() {
        if (pendingSaved != null) {
            return pendingSaved.getEntriesCount() == 0 && pendingSaved.getUnknownFields().asMap().isEmpty();
        }
        if (!unresolved.isEmpty() || !listUnknownFields.asMap().isEmpty()) {
            return false;
        }
        for (ActiveBuff b : active) {
            if (b.keepOnLogout && !b.subBuff) {
                return false;
            }
        }
        return true;
    }

    /**
     * 导出持久化载荷：按 {@code buff_table_id} 无符号升序写出所有 {@code keep_on_logout && !subBuff} 活跃条目与未识别表行暂存条目，
     * 保留条目级与列表级未知字段，不按当前时钟筛选。
     */
    public SavedBuffListState toState() {
        if (pendingSaved != null) {
            return pendingSaved;
        }
        List<SavedBuffEntryState> entries = new ArrayList<>();
        for (ActiveBuff b : active) {
            if (!b.keepOnLogout || b.subBuff) {
                continue;
            }
            SavedBuffEntryState.Builder item = SavedBuffEntryState.newBuilder()
                    .setBuffTableId(b.buffTableId)
                    .setLayer(b.layer)
                    .setExpireAtMs(b.expireAtMs);
            if (!b.persistUnknown.asMap().isEmpty()) {
                item.setUnknownFields(b.persistUnknown);
            }
            entries.add(item.build());
        }
        entries.addAll(unresolved);
        entries.sort((a, b) -> Integer.compareUnsigned(a.getBuffTableId(), b.getBuffTableId()));
        SavedBuffListState.Builder out = SavedBuffListState.newBuilder().setUnknownFields(listUnknownFields);
        out.addAllEntries(entries);
        return out.build();
    }

    /** 计算有限时长 Buff 的毫秒时长；永久或非正有限数返回 0。 */
    public static long finiteDurationMs(BuffTable row) {
        if (row == null || row.getInfiniteDuration() != 0) {
            return 0L;
        }
        double sec = row.getDuration();
        if (!Double.isFinite(sec) || sec <= 0.0) {
            return 0L;
        }
        return Math.max(1L, Math.round(sec * 1000.0));
    }

    /** 到期时刻非 0 且不晚于当前时刻即为已到期。 */
    public static boolean isExpired(long expireAtMs, long nowMs) {
        return expireAtMs != 0L && Long.compareUnsigned(expireAtMs, nowMs) <= 0;
    }

    private ActiveBuff findActiveAndPruneExpired(int tableId, long effectiveCaster, long nowMs) {
        Set<Long> expiredParents = null;
        ActiveBuff matched = null;
        Iterator<ActiveBuff> it = active.iterator();
        while (it.hasNext()) {
            ActiveBuff b = it.next();
            if (b.subBuff || b.buffTableId != tableId || b.casterId != effectiveCaster) {
                continue;
            }
            if (isExpired(b.expireAtMs, nowMs)) {
                if (expiredParents == null) {
                    expiredParents = new HashSet<>();
                }
                expiredParents.add(b.buffId);
                it.remove();
            } else if (matched == null) {
                matched = b;
            }
        }
        if (expiredParents != null) {
            Set<Long> toDrop = expiredParents;
            active.removeIf(b -> b.subBuff && toDrop.contains(b.parentBuffId));
        }
        return matched;
    }

    private void attachSubBuffs(BuffTable parentRow, long parentBuffId, long casterId, long nowMs,
                                Function<Integer, BuffTable> tableLookup) {
        if (tableLookup == null || parentRow.getSubBuffCount() == 0) {
            return;
        }
        Set<Integer> attached = new HashSet<>();
        attached.add(parentRow.getId());
        for (int subId : parentRow.getSubBuffList()) {
            if (subId == 0 || !attached.add(subId)) {
                continue;
            }
            BuffTable subRow = tableLookup.apply(subId);
            if (subRow == null) {
                continue;
            }
            boolean subPerm = subRow.getInfiniteDuration() != 0;
            long subDurationMs = finiteDurationMs(subRow);
            long subExpire = subPerm ? 0L : (subDurationMs > 0L ? nowMs + subDurationMs : 0L);
            active.add(new ActiveBuff(nextBuffId++, subId, casterId, 1, subExpire,
                    false, true, parentBuffId, UnknownFieldSet.getDefaultInstance()));
        }
    }
}
