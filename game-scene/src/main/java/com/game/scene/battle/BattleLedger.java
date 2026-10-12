package com.game.scene.battle;

import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 回合制战斗结算的幂等账本（基线 {@code battle_settlement_ledger.h}；scene-battle-spec §7.12）：只登记「已应用、销账未确认」的局，
 * 稳态 0~1 条，容量 {@value BattleRedis#LEDGER_CAPACITY} 是异常兜底。随 {@code player_state.battle_ledger} 与资产同一次带围栏的写落库。
 *
 * <p>两条判据分开（{@code ledger.h:17-20}）：「这一局应用过没有」看本类（活账本）；「可以销账」看最近一次确认落库的快照里有没有
 * （{@link #persistedHas}）。{@link #forget} 只能在销账脚本的结局回来之后调。
 *
 * <p><b>加载校验</b>（D24）：battle_id 为 0、重复、条数超容量视为损坏——原样带回、不改写，该玩家的结算一律延后、备战一律 1006，绝不当空账本用。
 *
 * <p><b>未知字段</b>：存档里本版本不认识的字段各层都原样带回——{@code BattleLedgerState} 这一层，以及每个 {@code BattleLedgerEntry} 自己的
 * （按 battle_id 旁挂，条目被 {@link #forget} / 淘汰时一起丢；审计 STL-10，做法同 {@code AssetOpLedger}）。滚动升级 / 回滚期间旧版本节点不会把新字段抹掉。
 *
 * <p>只在场景逻辑线程上读写。
 */
public final class BattleLedger {

    public static final int CAPACITY = BattleRedis.LEDGER_CAPACITY;

    /** battle_id（无符号序）→ applied_at_ms。 */
    private final TreeMap<Long, Long> applied = new TreeMap<>(Long::compareUnsigned);
    /** battle_id → 该条目里本版本不认识的字段（只存非空的；条目摘掉时一起摘）。 */
    private final TreeMap<Long, UnknownFieldSet> entryUnknownFields = new TreeMap<>(Long::compareUnsigned);
    private final UnknownFieldSet unknownFields;
    /** 损坏时原样带回的那份；正常为 null。 */
    private final BattleLedgerState corrupt;
    private final String invalidReason;

    private BattleLedger(UnknownFieldSet unknownFields, BattleLedgerState corrupt, String invalidReason) {
        this.unknownFields = unknownFields;
        this.corrupt = corrupt;
        this.invalidReason = invalidReason;
    }

    public static BattleLedger empty() {
        return new BattleLedger(UnknownFieldSet.getDefaultInstance(), null, null);
    }

    /** 从存档恢复；损坏时返回一个原样带回、拒绝改动的账本（{@link #invalidReason()} 非空）。 */
    public static BattleLedger restore(BattleLedgerState state) {
        String problem = validate(state);
        if (problem != null) {
            return new BattleLedger(UnknownFieldSet.getDefaultInstance(), state, problem);
        }
        BattleLedger ledger = new BattleLedger(state.getUnknownFields(), null, null);
        for (BattleLedgerEntry entry : state.getAppliedList()) {
            ledger.applied.put(entry.getBattleId(), entry.getAppliedAtMs());
            if (!entry.getUnknownFields().asMap().isEmpty()) {
                ledger.entryUnknownFields.put(entry.getBattleId(), entry.getUnknownFields());
            }
        }
        return ledger;
    }

    /** 损坏判定（0 号、重复、超容量）；合法为 null。 */
    static String validate(BattleLedgerState state) {
        if (state.getAppliedCount() > CAPACITY) {
            return "条数 " + state.getAppliedCount() + " 超过容量 " + CAPACITY;
        }
        Set<Long> seen = new HashSet<>();
        for (BattleLedgerEntry entry : state.getAppliedList()) {
            if (entry.getBattleId() == 0) {
                return "含 battle_id = 0 的条目";
            }
            if (!seen.add(entry.getBattleId())) {
                return "battle_id 重复 " + Long.toUnsignedString(entry.getBattleId());
            }
        }
        return null;
    }

    /** 损坏原因；正常为 null。 */
    public String invalidReason() {
        return invalidReason;
    }

    public boolean has(long battleId) {
        return battleId != 0 && applied.containsKey(battleId);
    }

    /**
     * 登记已应用（已在账本里只刷新时间戳，条目里不认识的字段留着）；满时淘汰 {@code applied_at_ms} 最小的（按时间戳，不按下标，同 {@code ledger.h:44-74}）。
     *
     * @return 被淘汰的 battle_id；没有淘汰为 0
     * @throws IllegalStateException 账本损坏（调用方先判 {@link #invalidReason()}）
     */
    public long record(long battleId, long nowMs) {
        requireValid();
        if (battleId == 0) {
            return 0;
        }
        if (applied.containsKey(battleId)) {
            applied.put(battleId, nowMs);
            return 0;
        }
        long evicted = 0;
        if (applied.size() >= CAPACITY) {
            Map.Entry<Long, Long> oldest = null;
            for (Map.Entry<Long, Long> e : applied.entrySet()) {
                if (oldest == null || Long.compareUnsigned(e.getValue(), oldest.getValue()) < 0) {
                    oldest = e;
                }
            }
            evicted = oldest.getKey();
            applied.remove(evicted);
            entryUnknownFields.remove(evicted);
        }
        applied.put(battleId, nowMs);
        return evicted;
    }

    /** 摘掉一条（只在销账脚本的结局回来之后调）；返回是否摘到。损坏的账本不改。 */
    public boolean forget(long battleId) {
        if (corrupt != null) {
            return false;
        }
        entryUnknownFields.remove(battleId);
        return applied.remove(battleId) != null;
    }

    /** 已登记的 battle_id（无符号升序，副本）。 */
    public List<Long> battleIds() {
        return new ArrayList<>(applied.keySet());
    }

    public int size() {
        return corrupt != null ? corrupt.getAppliedCount() : applied.size();
    }

    /** 没有条目、没有不认识的字段、没损坏：持久化时省略整段。 */
    public boolean isPristine() {
        return corrupt == null && applied.isEmpty() && unknownFields.asMap().isEmpty();
    }

    /** 写出：按 battle_id 无符号升序（周期存盘按值比对）；各层不认识的字段原样带回；损坏的原样写回。 */
    public BattleLedgerState toState() {
        if (corrupt != null) {
            return corrupt;
        }
        BattleLedgerState.Builder state = BattleLedgerState.newBuilder().setUnknownFields(unknownFields);
        applied.forEach((id, at) -> {
            BattleLedgerEntry.Builder entry = BattleLedgerEntry.newBuilder().setBattleId(id).setAppliedAtMs(at);
            UnknownFieldSet unknown = entryUnknownFields.get(id);
            if (unknown != null) {
                entry.setUnknownFields(unknown);
            }
            state.addApplied(entry);
        });
        return state.build();
    }

    /** 最近一次确认落库的快照里有没有这一局（「可以销账」的判据；快照为 null = 不确定，按没有）。 */
    public static boolean persistedHas(PlayerState persisted, long battleId) {
        if (persisted == null || !persisted.hasBattleLedger() || battleId == 0) {
            return false;
        }
        for (BattleLedgerEntry entry : persisted.getBattleLedger().getAppliedList()) {
            if (entry.getBattleId() == battleId) {
                return true;
            }
        }
        return false;
    }

    private void requireValid() {
        if (corrupt != null) {
            throw new IllegalStateException("战斗结算账本已损坏（" + invalidReason + "），不能改动");
        }
    }
}
