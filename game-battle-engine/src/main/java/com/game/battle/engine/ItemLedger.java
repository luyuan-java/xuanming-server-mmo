package com.game.battle.engine;

import com.game.common.math.Unsigned;
import com.game.proto.BattleAction;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleEventType;
import com.game.table.ItemTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 战斗道具：开局快照里的道具余量副本、PVP 用药次数、出手期用药与消耗账（基线 {@code ExecuteItem}，{@code engine.cpp:947-1012}；
 * {@code FindItemEntry}，{@code :1680-1711}；{@code SelfItems}，{@code :1287-1306}；规格 §6.1–§6.3）。
 *
 * <p>承载：基线把余量就地扣在引擎私有的开局请求副本上；Java 保留原请求不可变，另按快照里玩家与道具的原顺序建
 * {@code player_id → [BattleItemEntry.Builder]} 账本。{@code BattleActorState} 不承载道具（道具是玩家资产，不是单位状态）。
 * 用药次数只做查找，用 {@link HashMap} 不影响确定性。
 */
final class ItemLedger {

    private final BattleContext ctx;
    /** player_id → 本人快照里的道具条目（原顺序）。只看本人那份快照。 */
    private final Map<Long, List<BattleItemEntry.Builder>> items = new LinkedHashMap<>();
    /** player_id → 本场已<strong>实际执行</strong>的用药次数（uint32；PVP 限次用）。 */
    private final Map<Long, Integer> useCounts = new HashMap<>();

    ItemLedger(BattleContext ctx, CreateBattleRequest request) {
        this.ctx = ctx;
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            // 基线 FindItemEntry 遇到第一份 player_id 相同的快照就停（重复参战会在开局时被拒，这里只是保持同一口径）
            if (items.containsKey(snapshot.getPlayerId())) {
                continue;
            }
            List<BattleItemEntry.Builder> entries = new ArrayList<>(snapshot.getItemsCount());
            for (BattleItemEntry item : snapshot.getItemsList()) {
                entries.add(item.toBuilder());
            }
            items.put(snapshot.getPlayerId(), entries);
        }
    }

    /**
     * 本人快照里第一个 {@code item_table_id} 相同且余量 &gt; 0 的条目；找不到返回 null（{@code FindItemEntry}，{@code engine.cpp:1680-1711}）。
     */
    BattleItemEntry.Builder findItemEntry(long playerId, int itemTableId) {
        List<BattleItemEntry.Builder> entries = items.get(playerId);
        if (entries == null) {
            return null;
        }
        for (BattleItemEntry.Builder item : entries) {
            if (item.getItemTableId() == itemTableId && item.getCount() != 0) {
                return item;
            }
        }
        return null;
    }

    /** 该玩家本场已用药次数是否已达 PVP 上限（{@code engine.cpp:463-468}；计数为 uint32）。 */
    boolean reachedPvpUseLimit(long playerId) {
        Integer used = useCounts.get(playerId);
        return used != null && Integer.compareUnsigned(used, BattleConstants.MAX_ITEM_USES_PER_BATTLE_PVP) >= 0;
    }

    /**
     * 剩余战斗道具（{@code SelfItems}，{@code engine.cpp:1287-1306}）：本人副本里余量非 0 的条目，按 item_table_id（uint32）升序。
     * 基线用不稳定的 {@code std::sort}；Java 用稳定排序，同 id 时保持快照顺序（有意差异 D6）。玩家不在本局时返回空列表。
     */
    List<BattleItemEntry> selfItems(long playerId) {
        List<BattleItemEntry.Builder> entries = items.get(playerId);
        if (entries == null) {
            return List.of();
        }
        List<BattleItemEntry> result = new ArrayList<>(entries.size());
        for (BattleItemEntry.Builder item : entries) {
            if (item.getCount() == 0) {
                continue; // 用光的条目不下发
            }
            result.add(item.build());
        }
        result.sort((lhs, rhs) -> Integer.compareUnsigned(lhs.getItemTableId(), rhs.getItemTableId()));
        return Collections.unmodifiableList(result);
    }

    /**
     * 出手期用药（{@code ExecuteItem}，{@code engine.cpp:947-1012}；规格 §6.2）。
     *
     * <ol>
     *   <li>重跑与提交期同一份 {@link ActionChecks#checkItemUse}。不通过时：原目标是 0 或自己 → 落空（不出事件，组号已占）；
     *       否则改成对自己用再验一次，仍不通过也落空。<strong>从不降级为普攻</strong>，也不耗随机数（{@code :953-965}）；</li>
     *   <li>表行 / 副本条目 / 目标任一缺失就返回（最后防线，{@code :966-972}）；</li>
     *   <li>先扣副本、计次数，再记消耗账（记在<strong>用药者</strong>名下，给队友用也一样），最后落效果：
     *       满血时也扣药并出 value = 0 的事件（{@code :974-991}）；</li>
     *   <li>回血走 {@link BattleUnit#applyHeal}；回蓝 {@code after = min(max_mana, before + heal_mp)}（uint64 加法可回绕），
     *       {@code restored = after - before}（当前法力超上限时回绕成巨大值，基线行为）（{@code :993-1004}）；</li>
     *   <li>ITEM 事件：value 取回血量，没回血时取回蓝量（{@code :1006-1011}）。</li>
     * </ol>
     */
    void executeItem(BattleUnit actor, BattleAction action, ActionChecks checks) {
        BattleAction effective = action;
        if (checks.checkItemUse(actor, effective) != ActionChecks.SUCCESS) {
            if (effective.getTargetId() == 0 || effective.getTargetId() == actor.actorId()) {
                return;
            }
            effective = effective.toBuilder().setTargetId(actor.actorId()).build();
            if (checks.checkItemUse(actor, effective) != ActionChecks.SUCCESS) {
                return;
            }
        }
        Optional<ItemTable> itemRow = ctx.data().item(effective.getItemTableId());
        BattleItemEntry.Builder itemEntry = findItemEntry(actor.actorId(), effective.getItemTableId());
        long targetId = effective.getTargetId() == 0 ? actor.actorId() : effective.getTargetId();
        BattleUnit target = ctx.findActor(targetId);
        if (itemRow.isEmpty() || itemEntry == null || target == null) {
            return;
        }
        ItemTable row = itemRow.get();

        itemEntry.setCount(itemEntry.getCount() - 1);
        useCounts.merge(actor.actorId(), 1, Integer::sum);

        // 基线 settlements[actor_id]：缺条目时就地建空条目（用药者恒为玩家，开局已建，实际不会走到）
        BattleSettlementData.Builder settlement =
                ctx.settlements().computeIfAbsent(actor.actorId(), id -> BattleSettlementData.newBuilder());
        BattleItemEntry.Builder consumed = null;
        for (BattleItemEntry.Builder entry : settlement.getItemsConsumedBuilderList()) {
            if (entry.getItemTableId() == effective.getItemTableId()) {
                consumed = entry;
                break;
            }
        }
        if (consumed == null) {
            consumed = settlement.addItemsConsumedBuilder().setItemTableId(effective.getItemTableId());
        }
        consumed.setCount(consumed.getCount() + 1);

        long healed = row.getBattleHealHp() != 0 ? target.applyHeal(Unsigned.toDouble(row.getBattleHealHp())) : 0;
        long restoredMana = 0;
        if (row.getBattleHealMp() != 0 && !target.isDead()) {
            long manaBefore = target.mana();
            long manaAfter = Unsigned.minUnsigned(target.maxMana(), manaBefore + row.getBattleHealMp());
            restoredMana = manaAfter - manaBefore;
            target.setMana(manaAfter);
        }

        ctx.events().append(eBattleEventType.BATTLE_EVENT_ITEM, actor.actorId(), target.actorId())
                .setItemTableId(effective.getItemTableId())
                .setValue(healed != 0 ? healed : restoredMana)
                .setTargetHealthAfter(target.health())
                .setTargetManaAfter(target.mana());
    }
}
