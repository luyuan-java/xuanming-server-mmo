package com.game.scene.player;

import com.game.player.store.state.BattleResultCurrencyState;
import com.game.player.store.state.BattleResultEntryState;
import com.game.player.store.state.BattleResultHistoryState;
import com.game.player.store.state.BattleResultItemState;
import com.game.proto.PlayerBattleResult;
import com.game.proto.PlayerBattleResultCurrency;
import com.game.proto.PlayerBattleResultItem;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 玩家近期战斗结果列表（至多 10 条，按 {@code result_seq} 单调递增；对齐 C++ {@code battle_result_history.h}）。
 * 仅在场景逻辑线程上读写；随同一次存档围栏提交，保留未知字段以便滚动升级往返。
 */
public final class PlayerBattleResults {

    public static final int MAX_ENTRIES = 10;
    public static final int MAX_ITEM_LINES = 32;

    private long lastResultSeq;
    private final List<BattleResultEntryState> entries = new ArrayList<>();
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerBattleResults() {
    }

    public static PlayerBattleResults empty() {
        return new PlayerBattleResults();
    }

    public static PlayerBattleResults restore(BattleResultHistoryState state) {
        PlayerBattleResults out = new PlayerBattleResults();
        if (state == null) {
            return out;
        }
        out.lastResultSeq = state.getLastResultSeq();
        out.entries.addAll(state.getEntriesList());
        out.unknownFields = state.getUnknownFields();
        return out;
    }

    /** 追加一局战果：去重按 {@code battle_id}，超 {@value #MAX_ENTRIES} 条淘汰 {@code result_seq} 最小者。 */
    public boolean record(BattleResultEntryState.Builder draft) {
        long battleId = draft.getBattleId();
        if (battleId == 0) {
            return false;
        }
        for (BattleResultEntryState existing : entries) {
            if (existing.getBattleId() == battleId) {
                return false;
            }
        }
        long maxSeq = lastResultSeq;
        for (BattleResultEntryState existing : entries) {
            if (Long.compareUnsigned(existing.getResultSeq(), maxSeq) > 0) {
                maxSeq = existing.getResultSeq();
            }
        }
        long nextSeq = maxSeq + 1L;
        lastResultSeq = nextSeq;
        entries.add(draft.setResultSeq(nextSeq).setSeen(false).build());
        while (entries.size() > MAX_ENTRIES) {
            int oldest = 0;
            for (int i = 1; i < entries.size(); i++) {
                if (Long.compareUnsigned(entries.get(i).getResultSeq(), entries.get(oldest).getResultSeq()) < 0) {
                    oldest = i;
                }
            }
            entries.remove(oldest);
        }
        return true;
    }

    /** 按 battle_id 查一条内存中的近期战果（只读；找不到返回 null）。 */
    public BattleResultEntryState findByBattleId(long battleId) {
        if (battleId == 0L) {
            return null;
        }
        for (BattleResultEntryState entry : entries) {
            if (entry.getBattleId() == battleId) {
                return entry;
            }
        }
        return null;
    }

    /** 过滤未确认落盘的 {@code hidden} 局，按 {@code result_seq} 降序返回至多 {@value #MAX_ENTRIES} 条客户端视图。 */
    public List<PlayerBattleResult> visibleNewestFirst(Set<Long> hidden) {
        List<BattleResultEntryState> visible = new ArrayList<>(entries.size());
        for (BattleResultEntryState entry : entries) {
            if (!hidden.contains(entry.getBattleId())) {
                visible.add(entry);
            }
        }
        visible.sort((a, b) -> Long.compareUnsigned(b.getResultSeq(), a.getResultSeq()));
        int limit = Math.min(visible.size(), MAX_ENTRIES);
        List<PlayerBattleResult> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            out.add(toClient(visible.get(i)));
        }
        return out;
    }

    /** 将 {@code battleIds} 中已落盘且未读的条目标记为已读，返回本次新标已读条数；未知/隐藏/重复均幂等忽略。 */
    public int markSeen(List<Long> battleIds, Set<Long> hidden) {
        if (battleIds.isEmpty()) {
            return 0;
        }
        Set<Long> requested = new HashSet<>(battleIds);
        int changed = 0;
        for (int i = 0; i < entries.size(); i++) {
            BattleResultEntryState entry = entries.get(i);
            if (!entry.getSeen() && !hidden.contains(entry.getBattleId()) && requested.contains(entry.getBattleId())) {
                entries.set(i, entry.toBuilder().setSeen(true).build());
                changed++;
            }
        }
        return changed;
    }

    /** 将道具汇总表按配置号升序填入列表，最多 {@value #MAX_ITEM_LINES} 行；返回是否发生截断。 */
    public static boolean fillItemLines(Map<Integer, Long> items, List<BattleResultItemState> out) {
        TreeMap<Integer, Long> sorted = new TreeMap<>(Integer::compareUnsigned);
        for (Map.Entry<Integer, Long> e : items.entrySet()) {
            if (e.getKey() != 0 && e.getValue() > 0) {
                sorted.put(e.getKey(), e.getValue());
            }
        }
        boolean truncated = false;
        for (Map.Entry<Integer, Long> e : sorted.entrySet()) {
            if (out.size() >= MAX_ITEM_LINES) {
                truncated = true;
                break;
            }
            out.add(BattleResultItemState.newBuilder()
                    .setItemTableId(e.getKey())
                    .setCount(e.getValue())
                    .build());
        }
        return truncated;
    }

    public List<BattleResultEntryState> entries() {
        return List.copyOf(entries);
    }

    public boolean isPristine() {
        return lastResultSeq == 0 && entries.isEmpty() && unknownFields.asMap().isEmpty();
    }

    public BattleResultHistoryState toState() {
        List<BattleResultEntryState> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(BattleResultEntryState::getResultSeq, Long::compareUnsigned));
        return BattleResultHistoryState.newBuilder()
                .setUnknownFields(unknownFields)
                .setLastResultSeq(lastResultSeq)
                .addAllEntries(ordered)
                .build();
    }

    private static PlayerBattleResult toClient(BattleResultEntryState e) {
        PlayerBattleResult.Builder b = PlayerBattleResult.newBuilder()
                .setResultSeq(e.getResultSeq())
                .setBattleId(e.getBattleId())
                .setOutcome(e.getOutcome())
                .setTeamIndex(e.getTeamIndex())
                .setIsDead(e.getIsDead())
                .setFled(e.getFled())
                .setSurrendered(e.getSurrendered())
                .setTotalRounds(e.getTotalRounds())
                .setFinishedAtMs(e.getFinishedAtMs())
                .setSettledAtMs(e.getSettledAtMs())
                .setLinesTruncated(e.getLinesTruncated())
                .setSeen(e.getSeen())
                .setMatchMode(e.getMatchMode())
                .setBattleConfigId(e.getBattleConfigId())
                .setItemsMailed(e.getItemsMailed())
                .setExpGain(e.getExpGain())
                .setMaterialCreditUnitsAdded(e.getMaterialCreditUnitsAdded())
                .setMaterialTradableUnitsAdded(e.getMaterialTradableUnitsAdded())
                .setMaterialPvpLossIneligible(e.getMaterialPvpLossIneligible())
                .setSeasonId(e.getSeasonId())
                .setTrialJournalEntryId(e.getTrialJournalEntryId())
                .addAllTrialNewlyLitSealIds(e.getTrialNewlyLitSealIdsList())
                .setTrialNewSoloBest(e.getTrialNewSoloBest())
                .setTrialNewCoopBest(e.getTrialNewCoopBest())
                .setFestivalActivityInstanceId(e.getFestivalActivityInstanceId())
                .setFestivalRewardIdentity(e.getFestivalRewardIdentity())
                .setFestivalWinsBefore(e.getFestivalWinsBefore())
                .setFestivalWinsAfter(e.getFestivalWinsAfter())
                .setFestivalRequiredWins(e.getFestivalRequiredWins())
                .setFestivalNewlyEligible(e.getFestivalNewlyEligible())
                .setMaterialAvailableUnitsAfter(e.getMaterialAvailableUnitsAfter())
                .setMaterialEarnableRemainingAfter(e.getMaterialEarnableRemainingAfter())
                .setSeasonSealPointsAdded(e.getSeasonSealPointsAdded())
                .setSeasonSealProgressAfter(e.getSeasonSealProgressAfter())
                .setSeasonSealMaxPoints(e.getSeasonSealMaxPoints())
                .setSeasonHonorNewlyUnlocked(e.getSeasonHonorNewlyUnlocked())
                .setLevelBefore(e.getLevelBefore())
                .setLevelAfter(e.getLevelAfter())
                .setTotalExpAfter(e.getTotalExpAfter())
                .setNextLevelTotalExp(e.getNextLevelTotalExp())
                .setBattleSilverWeeklyUsed(e.getBattleSilverWeeklyUsed())
                .setBattleSilverWeeklySoftCap(e.getBattleSilverWeeklySoftCap())
                .setBattleSilverDiminished(e.getBattleSilverDiminished());
        for (BattleResultCurrencyState c : e.getCurrenciesList()) {
            b.addCurrencies(PlayerBattleResultCurrency.newBuilder()
                    .setCurrencyType(c.getCurrencyType())
                    .setAmount(c.getAmount()));
        }
        for (BattleResultItemState it : e.getItemsGainedList()) {
            b.addItemsGained(PlayerBattleResultItem.newBuilder()
                    .setItemTableId(it.getItemTableId())
                    .setCount(it.getCount()));
        }
        for (BattleResultItemState it : e.getItemsConsumedList()) {
            b.addItemsConsumed(PlayerBattleResultItem.newBuilder()
                    .setItemTableId(it.getItemTableId())
                    .setCount(it.getCount()));
        }
        return b.build();
    }
}
