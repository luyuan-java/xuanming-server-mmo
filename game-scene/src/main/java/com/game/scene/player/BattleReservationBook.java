package com.game.scene.player;

import com.game.player.store.state.BattleReservationBookState;
import com.game.player.store.state.BattleReservationEntryState;
import com.game.proto.BagHeldItemInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 战斗道具预留账本（PARITY 2026-11-10 + 2026-11-12）：
 * 挂在 {@code ScenePlayer} 上并与背包同在 {@code PlayerState.battle_reservations = 29} 单事务围栏提交，
 * 防止续冻达到上限解冻后玩家把本局带进战斗的消耗品通过交易/捐献/邮件/强化/穿戴转走，导致晚到结算按持有夹紧（药白喝）。
 */
public final class BattleReservationBook {

    public record Entry(long battleId, long freezeDeadlineMs, Map<Integer, Long> items) {
        public Entry {
            items = items == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(items));
        }
    }

    private final Map<Long, Entry> byBattle = new TreeMap<>(Long::compareUnsigned);
    private com.google.protobuf.UnknownFieldSet unknownFields = com.google.protobuf.UnknownFieldSet.getDefaultInstance();

    private BattleReservationBook() {
    }

    public static BattleReservationBook empty() {
        return new BattleReservationBook();
    }

    public static BattleReservationBook restore(BattleReservationBookState state) {
        BattleReservationBook book = new BattleReservationBook();
        if (state == null) {
            return book;
        }
        book.unknownFields = state.getUnknownFields();
        for (BattleReservationEntryState e : state.getEntriesList()) {
            if (e.getBattleId() == 0L) {
                continue;
            }
            Map<Integer, Long> items = new TreeMap<>(Integer::compareUnsigned);
            for (Map.Entry<Integer, Long> item : e.getItemsMap().entrySet()) {
                if (item.getKey() != 0 && item.getValue() != null && item.getValue() > 0L) {
                    items.put(item.getKey(), item.getValue());
                }
            }
            if (!items.isEmpty()) {
                book.byBattle.put(e.getBattleId(), new Entry(e.getBattleId(), e.getFreezeDeadlineMs(), items));
            }
        }
        return book;
    }

    public boolean isEmpty() {
        return byBattle.isEmpty();
    }

    public boolean has(long battleId) {
        return byBattle.containsKey(battleId);
    }

    public List<Entry> entries() {
        return List.copyOf(byBattle.values());
    }

    /**
     * 为指定战斗建立预留（扣除其他战斗已预留数量后的可用消耗品快照）。
     * 任一配置号超过当前可用净量时拒绝并返回 false（不改动账本）。
     */
    public boolean reserve(long battleId, long freezeDeadlineMs, Map<Integer, Long> wantedItems, Bag inventory) {
        Objects.requireNonNull(inventory, "inventory");
        if (battleId == 0L) {
            return false;
        }
        if (wantedItems == null || wantedItems.isEmpty()) {
            return true;
        }
        Map<Integer, Long> clean = new TreeMap<>(Integer::compareUnsigned);
        for (Map.Entry<Integer, Long> e : wantedItems.entrySet()) {
            int cfg = e.getKey();
            long cnt = e.getValue() == null ? 0L : e.getValue();
            if (cfg == 0 || cnt <= 0L) {
                continue;
            }
            long avail = Math.max(0L, inventory.total(cfg) - reservedByOtherBattles(battleId, cfg));
            if (cnt > avail) {
                return false;
            }
            clean.put(cfg, cnt);
        }
        if (!clean.isEmpty()) {
            byBattle.put(battleId, new Entry(battleId, freezeDeadlineMs, clean));
        }
        return true;
    }

    /** 直接登记一份已算好净量的预留（单测与 PrepareBattle 已扣净后使用）。 */
    public void putVerified(long battleId, long freezeDeadlineMs, Map<Integer, Long> items) {
        if (battleId == 0L || items == null || items.isEmpty()) {
            return;
        }
        Map<Integer, Long> clean = new TreeMap<>(Integer::compareUnsigned);
        for (Map.Entry<Integer, Long> e : items.entrySet()) {
            if (e.getKey() != 0 && e.getValue() != null && e.getValue() > 0L) {
                clean.put(e.getKey(), e.getValue());
            }
        }
        if (!clean.isEmpty()) {
            byBattle.put(battleId, new Entry(battleId, freezeDeadlineMs, clean));
        }
    }

    /** 释放指定战斗的预留（结算应用、取消备战或终局核实 ABORT/无原件时调用）。 */
    public boolean release(long battleId) {
        return byBattle.remove(battleId) != null;
    }

    /** 全部战斗合计占住的该物品配置号数量。 */
    public long totalReserved(int configId) {
        long sum = 0L;
        for (Entry e : byBattle.values()) {
            long c = e.items().getOrDefault(configId, 0L);
            if (c > 0L) {
                sum = sum > Long.MAX_VALUE - c ? Long.MAX_VALUE : sum + c;
            }
        }
        return sum;
    }

    /** 除 {@code selfBattleId} 外其余战斗合计占住的该物品配置号数量（备战快照与结算扣药用）。 */
    public long reservedByOtherBattles(long selfBattleId, int configId) {
        long sum = 0L;
        for (Entry e : byBattle.values()) {
            if (e.battleId() == selfBattleId) {
                continue;
            }
            long c = e.items().getOrDefault(configId, 0L);
            if (c > 0L) {
                sum = sum > Long.MAX_VALUE - c ? Long.MAX_VALUE : sum + c;
            }
        }
        return sum;
    }

    /**
     * 派生只读 {@code BagInfo.held_items}（PARITY 2026-11-12）：
     * 仅人物背包，按 {@code config_id} 无符号升序，数量夹紧至背包内该配置号持有总量；0 不列出。
     */
    public List<BagHeldItemInfo> heldItemsClamped(Bag inventory) {
        if (byBattle.isEmpty() || inventory == null) {
            return List.of();
        }
        Map<Integer, Long> totals = new TreeMap<>(Integer::compareUnsigned);
        for (Entry e : byBattle.values()) {
            for (Map.Entry<Integer, Long> item : e.items().entrySet()) {
                totals.merge(item.getKey(), item.getValue(), (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
            }
        }
        List<BagHeldItemInfo> out = new ArrayList<>(totals.size());
        for (Map.Entry<Integer, Long> e : totals.entrySet()) {
            long held = Math.min(e.getValue(), inventory.total(e.getKey()));
            if (held > 0L) {
                out.add(BagHeldItemInfo.newBuilder()
                        .setConfigId(e.getKey())
                        .setCount(held)
                        .build());
            }
        }
        out.sort(Comparator.comparingInt(BagHeldItemInfo::getConfigId));
        return out;
    }

    /** 确定性序列化（按 battle_id 无符号升序写出，始终产出非 null 对象以满足存盘组件存在性门禁）。 */
    public BattleReservationBookState toState() {
        BattleReservationBookState.Builder b = BattleReservationBookState.newBuilder()
                .setUnknownFields(unknownFields);
        for (Entry e : byBattle.values()) {
            BattleReservationEntryState.Builder eb = BattleReservationEntryState.newBuilder()
                    .setBattleId(e.battleId())
                    .setFreezeDeadlineMs(e.freezeDeadlineMs());
            e.items().forEach(eb::putItems);
            b.addEntries(eb);
        }
        return b.build();
    }
}
