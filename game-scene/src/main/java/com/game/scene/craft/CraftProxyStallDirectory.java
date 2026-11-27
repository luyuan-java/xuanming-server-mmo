package com.game.scene.craft;

import com.game.player.store.state.PlayerCraftState;
import com.game.proto.CraftProxyStallInfo;
import com.game.scene.equip.EquipCraftQualityRules;
import com.game.scene.equip.EquipmentCraftService;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SwitchPhase;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * 跨节点/离线名匠代打共享摊位目录与口碑/24h 极品热榜原子账本（场景逻辑线程持有本地镜像，Redis Hash 异步读写）：
 * 宗师开启营业摊位或登录后即同步入共享目录；下线后摊位继续保留，买家可在任意场景节点 24 小时查看并下单，
 * 离线成交的口碑、极品/稀世统计、净银两与新解锁雅号通过原子 HINCRBY 伴生账本在宗师登录/刷新时安全回账。
 */
public final class CraftProxyStallDirectory {

    /** 异步底层存储接口（生产接 Redisson Hash，单测默认无外部 I/O）。 */
    public interface BackingStore {
        BackingStore NOOP = new BackingStore() {
            @Override
            public CompletionStage<Void> putAsync(long masterPlayerId, byte[] payload) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> removeAsync(long masterPlayerId) {
                return CompletableFuture.completedFuture(null);
            }
        };

        CompletionStage<Void> putAsync(long masterPlayerId, byte[] payload);

        CompletionStage<Void> removeAsync(long masterPlayerId);

        default CompletionStage<Void> incrReputationAsync(long masterPlayerId, String fieldSuffix, long delta) {
            return CompletableFuture.completedFuture(null);
        }

        default CompletionStage<Void> setLastBuyerAsync(long masterPlayerId, String buyerName) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class PendingReputation {
        int orders;
        int supreme;
        int rare;
        long netGold;
        String lastBuyer = "";

        boolean isEmpty() {
            return orders == 0 && supreme == 0 && rare == 0 && netGold == 0L;
        }
    }

    private static final class HotCounters {
        int orders24h;
        int supreme24h;
    }

    private final BackingStore store;
    private final Executor logic;
    private final Map<Long, CraftProxyStallInfo> stalls = new HashMap<>();
    private final Map<Long, Long> localMutationSeq = new HashMap<>();
    private final Map<Long, PendingReputation> pendingReputation = new HashMap<>();
    private final Map<Long, HotCounters> hotCounters = new HashMap<>();
    private final Map<Long, Long> localReputationMutationSeq = new HashMap<>();
    private long nextSeq;

    public CraftProxyStallDirectory() {
        this(BackingStore.NOOP, Runnable::run);
    }

    public CraftProxyStallDirectory(BackingStore store, Executor logic) {
        this.store = Objects.requireNonNull(store, "store");
        this.logic = Objects.requireNonNull(logic, "logic");
    }

    /** 依据角色当前手艺与摊位状态构造合法的共享目录快照；不具备营业资格时返回 null。 */
    public static CraftProxyStallInfo buildStallInfo(ScenePlayer master) {
        if (master == null || master.playerId() == 0L) {
            return null;
        }
        PlayerCraftState craft = master.craft().state();
        if (!craft.getProxyStallOpen()) {
            return null;
        }
        int forgeTier = EquipCraftQualityRules.masteryTierForExp(craft.getForgeMasteryExp());
        int scrollTier = EquipCraftQualityRules.masteryTierForExp(craft.getScrollMasteryExp());
        boolean forgeReady = forgeTier >= EquipCraftQualityRules.MASTERY_MASTER
                && EquipCraftQualityRules.validProxyFee(craft.getProxyForgeFeeGold());
        boolean scrollReady = scrollTier >= EquipCraftQualityRules.MASTERY_MASTER
                && EquipCraftQualityRules.validProxyFee(craft.getProxyScrollFeeGold());
        if (!forgeReady && !scrollReady) {
            return null;
        }
        return CraftProxyStallInfo.newBuilder()
                .setMasterPlayerId(master.playerId())
                .setMasterName(EquipmentCraftService.playerDisplayName(master))
                .setForgeMasteryTier(forgeTier)
                .setScrollMasteryTier(scrollTier)
                .setTopQualityRateBps(EquipCraftQualityRules.forgeTopQualityRateBps(forgeTier))
                .setScrollBonusRateBps(EquipCraftQualityRules.scrollBonusFragmentRateBps(scrollTier))
                .setProxyForgeFeeGold(forgeReady ? craft.getProxyForgeFeeGold() : 0L)
                .setProxyScrollFeeGold(scrollReady ? craft.getProxyScrollFeeGold() : 0L)
                .setProxyOrdersCompleted(craft.getProxyOrdersCompleted())
                .setSupremeForgedCount(craft.getSupremeForgedCount())
                .setRareBooksCraftedCount(craft.getRareBooksCraftedCount())
                .setMasterShowcaseTag(craft.getMasterShowcaseTag())
                .setEquippedCraftTitleId(craft.getEquippedCraftTitleId())
                .build();
    }

    public static boolean isValidStall(CraftProxyStallInfo info) {
        if (info == null || info.getMasterPlayerId() == 0L) {
            return false;
        }
        boolean forgeReady = info.getForgeMasteryTier() >= EquipCraftQualityRules.MASTERY_MASTER
                && EquipCraftQualityRules.validProxyFee(info.getProxyForgeFeeGold());
        boolean scrollReady = info.getScrollMasteryTier() >= EquipCraftQualityRules.MASTERY_MASTER
                && EquipCraftQualityRules.validProxyFee(info.getProxyScrollFeeGold());
        return forgeReady || scrollReady;
    }

    /** 将玩家当前摊位营业状态同步到本地镜像与底层共享目录（开摊/改价/关摊/登录加载调用）。 */
    public void syncPlayer(ScenePlayer player) {
        if (player == null || player.playerId() == 0L) {
            return;
        }
        long masterId = player.playerId();
        CraftProxyStallInfo info = buildStallInfo(player);
        long seq = ++nextSeq;
        localMutationSeq.put(masterId, seq);
        if (info != null) {
            stalls.put(masterId, info);
            store.putAsync(masterId, info.toByteArray()).whenComplete((v, err) -> finishLocalMutation(masterId, seq));
        } else {
            stalls.remove(masterId);
            store.removeAsync(masterId).whenComplete((v, err) -> finishLocalMutation(masterId, seq));
        }
    }

    /**
     * 记录一笔名匠代打/代制成单的口碑、极品/稀世统计、净银两与近期热度：
     * 若宗师在本节点在线且可写，直接合入其 PlayerCraftState 并返回该宗师（由调用方触发关键进度存盘）；
     * 若宗师离线或在其他节点，写入伴生共享口碑账本（本地待回账 + Redis HINCRBY）并返回 null。
     */
    public ScenePlayer recordOrderReputation(long masterPlayerId, int supremeDelta, int rareDelta,
                                             long netGold, String buyerName, ScenePlayer onlineMaster) {
        if (masterPlayerId == 0L) {
            return null;
        }
        String safeBuyer = buyerName == null ? "" : buyerName;
        HotCounters hot = hotCounters.computeIfAbsent(masterPlayerId, k -> new HotCounters());
        hot.orders24h = satAddU32(hot.orders24h, 1);
        if (supremeDelta > 0) {
            hot.supreme24h = satAddU32(hot.supreme24h, supremeDelta);
        }
        long repSeq = ++nextSeq;
        localReputationMutationSeq.put(masterPlayerId, repSeq);
        List<CompletionStage<Void>> stages = new ArrayList<>(6);
        stages.add(store.incrReputationAsync(masterPlayerId, "24o", 1L));
        if (supremeDelta > 0) {
            stages.add(store.incrReputationAsync(masterPlayerId, "24s", supremeDelta));
        }

        if (onlineMaster != null
                && onlineMaster.playerId() == masterPlayerId
                && !onlineMaster.frozen()
                && onlineMaster.switchPhase() == SwitchPhase.NONE) {
            applyReputationToPlayer(onlineMaster, 1, supremeDelta, rareDelta, netGold, safeBuyer);
            if (onlineMaster.craft().state().getProxyStallOpen()) {
                syncPlayer(onlineMaster);
            }
            finishLocalReputationStages(masterPlayerId, repSeq, stages);
            return onlineMaster;
        }

        PendingReputation pending = pendingReputation.computeIfAbsent(masterPlayerId, k -> new PendingReputation());
        pending.orders = satAddU32(pending.orders, 1);
        pending.supreme = satAddU32(pending.supreme, supremeDelta);
        pending.rare = satAddU32(pending.rare, rareDelta);
        pending.netGold = satAddU64(pending.netGold, netGold);
        if (!safeBuyer.isEmpty()) {
            pending.lastBuyer = safeBuyer;
        }
        stages.add(store.incrReputationAsync(masterPlayerId, "o", 1L));
        if (supremeDelta > 0) {
            stages.add(store.incrReputationAsync(masterPlayerId, "s", supremeDelta));
        }
        if (rareDelta > 0) {
            stages.add(store.incrReputationAsync(masterPlayerId, "r", rareDelta));
        }
        if (netGold > 0L) {
            stages.add(store.incrReputationAsync(masterPlayerId, "g", netGold));
        }
        if (!safeBuyer.isEmpty()) {
            stages.add(store.setLastBuyerAsync(masterPlayerId, safeBuyer));
        }
        finishLocalReputationStages(masterPlayerId, repSeq, stages);
        return null;
    }

    /** 将挂在共享伴生账本中的离线/跨线待回账口碑合入在线可写宗师存档，返回是否发生变更。 */
    public boolean drainPendingReputation(ScenePlayer player) {
        if (player == null || player.playerId() == 0L
                || player.frozen() || player.switchPhase() != SwitchPhase.NONE) {
            return false;
        }
        long masterId = player.playerId();
        PendingReputation pending = pendingReputation.get(masterId);
        if (pending == null || pending.isEmpty()) {
            return false;
        }
        int orders = pending.orders;
        int supreme = pending.supreme;
        int rare = pending.rare;
        long netGold = pending.netGold;
        String lastBuyer = pending.lastBuyer;

        applyReputationToPlayer(player, orders, supreme, rare, netGold, lastBuyer);
        pendingReputation.remove(masterId);

        long repSeq = ++nextSeq;
        localReputationMutationSeq.put(masterId, repSeq);
        List<CompletionStage<Void>> stages = new ArrayList<>(4);
        if (orders != 0) {
            stages.add(store.incrReputationAsync(masterId, "o", -Integer.toUnsignedLong(orders)));
        }
        if (supreme != 0) {
            stages.add(store.incrReputationAsync(masterId, "s", -Integer.toUnsignedLong(supreme)));
        }
        if (rare != 0) {
            stages.add(store.incrReputationAsync(masterId, "r", -Integer.toUnsignedLong(rare)));
        }
        if (netGold != 0L) {
            stages.add(store.incrReputationAsync(masterId, "g", -netGold));
        }
        finishLocalReputationStages(masterId, repSeq, stages);

        if (player.craft().state().getProxyStallOpen()) {
            syncPlayer(player);
        }
        return true;
    }

    /** 仅测试：从共享目录直接摘除指定摊位。 */
    public void removeForTest(long masterPlayerId) {
        stalls.remove(masterPlayerId);
        localMutationSeq.remove(masterPlayerId);
    }

    /** 逻辑线程合并远端 Redis Hash 全量摊位快照，保留尚在途的本节点本地写避免回退。 */
    public void applyRemoteSnapshot(Map<String, byte[]> rawEntries) {
        Set<Long> remoteIds = new HashSet<>();
        if (rawEntries != null) {
            for (byte[] payload : rawEntries.values()) {
                if (payload == null || payload.length == 0) {
                    continue;
                }
                try {
                    CraftProxyStallInfo info = CraftProxyStallInfo.parseFrom(payload);
                    if (!isValidStall(info)) {
                        continue;
                    }
                    long id = info.getMasterPlayerId();
                    remoteIds.add(id);
                    if (!localMutationSeq.containsKey(id)) {
                        stalls.put(id, info);
                    }
                } catch (InvalidProtocolBufferException ignored) {
                    // 忽略损坏单条，不阻断同区其他宗师摊位刷新
                }
            }
        }
        stalls.keySet().removeIf(id -> !remoteIds.contains(id) && !localMutationSeq.containsKey(id));
    }

    /** 逻辑线程合并远端口碑与 24h 热榜 Redis Hash（字段格式 {@code <mid>:o/:s/:r/:g/:24o/:24s/:b}）。 */
    public void applyRemoteReputationSnapshot(Map<String, String> rawEntries) {
        Map<Long, PendingReputation> parsedPending = new HashMap<>();
        Map<Long, HotCounters> parsedHot = new HashMap<>();
        if (rawEntries != null) {
            for (Map.Entry<String, String> entry : rawEntries.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                if (key == null || val == null) {
                    continue;
                }
                int colon = key.indexOf(':');
                if (colon <= 0 || colon + 1 >= key.length()) {
                    continue;
                }
                long masterId;
                try {
                    masterId = Long.parseUnsignedLong(key.substring(0, colon));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (masterId == 0L || localReputationMutationSeq.containsKey(masterId)) {
                    continue;
                }
                String suffix = key.substring(colon + 1);
                if ("b".equals(suffix)) {
                    parsedPending.computeIfAbsent(masterId, k -> new PendingReputation()).lastBuyer = val;
                    continue;
                }
                long signedVal;
                try {
                    signedVal = Long.parseLong(val);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (signedVal <= 0L) {
                    continue;
                }
                switch (suffix) {
                    case "o" -> parsedPending.computeIfAbsent(masterId, k -> new PendingReputation()).orders = clampU32(signedVal);
                    case "s" -> parsedPending.computeIfAbsent(masterId, k -> new PendingReputation()).supreme = clampU32(signedVal);
                    case "r" -> parsedPending.computeIfAbsent(masterId, k -> new PendingReputation()).rare = clampU32(signedVal);
                    case "g" -> parsedPending.computeIfAbsent(masterId, k -> new PendingReputation()).netGold = signedVal;
                    case "24o" -> parsedHot.computeIfAbsent(masterId, k -> new HotCounters()).orders24h = clampU32(signedVal);
                    case "24s" -> parsedHot.computeIfAbsent(masterId, k -> new HotCounters()).supreme24h = clampU32(signedVal);
                    default -> {
                    }
                }
            }
        }
        pendingReputation.keySet().removeIf(id -> !localReputationMutationSeq.containsKey(id));
        for (Map.Entry<Long, PendingReputation> e : parsedPending.entrySet()) {
            if (!e.getValue().isEmpty()) {
                pendingReputation.put(e.getKey(), e.getValue());
            }
        }
        hotCounters.keySet().removeIf(id -> !localReputationMutationSeq.containsKey(id));
        hotCounters.putAll(parsedHot);
    }

    public CraftProxyStallInfo find(long masterPlayerId) {
        if (masterPlayerId == 0L) {
            return null;
        }
        CraftProxyStallInfo info = stalls.get(masterPlayerId);
        return isValidStall(info) ? info : null;
    }

    public List<CraftProxyStallInfo> listStalls() {
        List<CraftProxyStallInfo.Builder> builders = new ArrayList<>();
        for (CraftProxyStallInfo info : stalls.values()) {
            if (!isValidStall(info)) {
                continue;
            }
            CraftProxyStallInfo.Builder b = info.toBuilder();
            long masterId = info.getMasterPlayerId();
            PendingReputation pending = pendingReputation.get(masterId);
            if (pending != null && !pending.isEmpty()) {
                b.setProxyOrdersCompleted(satAddU32(b.getProxyOrdersCompleted(), pending.orders));
                b.setSupremeForgedCount(satAddU32(b.getSupremeForgedCount(), pending.supreme));
                b.setRareBooksCraftedCount(satAddU32(b.getRareBooksCraftedCount(), pending.rare));
            }
            HotCounters hot = hotCounters.get(masterId);
            int orders24h = hot != null ? hot.orders24h : 0;
            int supreme24h = hot != null ? hot.supreme24h : 0;
            b.setRecent24HOrders(orders24h);
            b.setRecent24HSupreme(supreme24h);
            b.setHotRank(0);
            builders.add(b);
        }
        builders.sort(Comparator.comparing(CraftProxyStallInfo.Builder::getMasterPlayerId, Long::compareUnsigned));

        // 计算全区 24h 极品热榜 Top 3（与 C++ equip_craft_quality_rules.h / player_craft.cpp 排序规则完全一致）。
        List<Integer> rankedIndices = new ArrayList<>();
        for (int i = 0; i < builders.size(); i++) {
            CraftProxyStallInfo.Builder b = builders.get(i);
            if (b.getRecent24HOrders() != 0 || b.getProxyOrdersCompleted() != 0 || b.getSupremeForgedCount() != 0) {
                rankedIndices.add(i);
            }
        }
        rankedIndices.sort((ia, ib) -> {
            CraftProxyStallInfo.Builder a = builders.get(ia);
            CraftProxyStallInfo.Builder b = builders.get(ib);
            long scoreA = EquipCraftQualityRules.hotRankScore(a.getRecent24HOrders(), a.getRecent24HSupreme());
            long scoreB = EquipCraftQualityRules.hotRankScore(b.getRecent24HOrders(), b.getRecent24HSupreme());
            int cmp = Long.compareUnsigned(scoreB, scoreA);
            if (cmp != 0) return cmp;
            cmp = Integer.compareUnsigned(b.getRecent24HSupreme(), a.getRecent24HSupreme());
            if (cmp != 0) return cmp;
            cmp = Integer.compareUnsigned(b.getSupremeForgedCount(), a.getSupremeForgedCount());
            if (cmp != 0) return cmp;
            cmp = Integer.compareUnsigned(b.getProxyOrdersCompleted(), a.getProxyOrdersCompleted());
            if (cmp != 0) return cmp;
            return Long.compareUnsigned(a.getMasterPlayerId(), b.getMasterPlayerId());
        });
        for (int r = 0; r < rankedIndices.size() && r < EquipCraftQualityRules.HOT_RANK_TOP_LIMIT; r++) {
            builders.get(rankedIndices.get(r)).setHotRank(r + 1);
        }

        List<CraftProxyStallInfo> out = new ArrayList<>(builders.size());
        for (CraftProxyStallInfo.Builder b : builders) {
            out.add(b.build());
        }
        return out;
    }

    private static void applyReputationToPlayer(ScenePlayer player, int orders, int supreme, int rare,
                                                long netGold, String lastBuyer) {
        PlayerCraftState before = player.craft().state();
        int beforeMask = EquipCraftQualityRules.unlockedCraftTitleMask(
                before.getForgeMasteryExp(), before.getScrollMasteryExp(),
                before.getSupremeForgedCount(), before.getRareBooksCraftedCount(),
                before.getProxyOrdersCompleted());
        int nextOrders = satAddU32(before.getProxyOrdersCompleted(), orders);
        int nextSupreme = satAddU32(before.getSupremeForgedCount(), supreme);
        int nextRare = satAddU32(before.getRareBooksCraftedCount(), rare);
        int afterMask = EquipCraftQualityRules.unlockedCraftTitleMask(
                before.getForgeMasteryExp(), before.getScrollMasteryExp(),
                nextSupreme, nextRare, nextOrders);
        int newlyUnlocked = EquipCraftQualityRules.newlyUnlockedCraftTitleMask(beforeMask, afterMask);

        PlayerCraftState.Builder next = before.toBuilder()
                .setProxyOrdersCompleted(nextOrders)
                .setSupremeForgedCount(nextSupreme)
                .setRareBooksCraftedCount(nextRare)
                .setUnreadProxyDigestOrders(satAddU32(before.getUnreadProxyDigestOrders(), orders))
                .setUnreadProxyDigestSupreme(satAddU32(before.getUnreadProxyDigestSupreme(), supreme))
                .setUnreadProxyDigestRare(satAddU32(before.getUnreadProxyDigestRare(), rare))
                .setUnreadProxyDigestNetGold(satAddU64(before.getUnreadProxyDigestNetGold(), netGold))
                .setUnreadProxyDigestNewTitlesMask(before.getUnreadProxyDigestNewTitlesMask() | newlyUnlocked);
        if (lastBuyer != null && !lastBuyer.isEmpty()) {
            next.setUnreadProxyDigestLastBuyer(lastBuyer);
        }
        player.craft().replace(next.build());
    }

    private static int satAddU32(int current, int delta) {
        long sum = Integer.toUnsignedLong(current) + Integer.toUnsignedLong(delta);
        return sum >= 0xFFFF_FFFFL ? -1 : (int) sum;
    }

    private static long satAddU64(long current, long delta) {
        if (delta == 0L) {
            return current;
        }
        if (Long.compareUnsigned(current, -1L - delta) > 0) {
            return -1L;
        }
        return current + delta;
    }

    private static int clampU32(long v) {
        return v >= 0xFFFF_FFFFL ? -1 : (int) v;
    }

    private void finishLocalMutation(long masterId, long seq) {
        try {
            logic.execute(() -> {
                Long current = localMutationSeq.get(masterId);
                if (current != null && current == seq) {
                    localMutationSeq.remove(masterId);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 节点停服后丢弃迟到回调
        }
    }

    private void finishLocalReputationStages(long masterId, long seq, List<CompletionStage<Void>> stages) {
        CompletableFuture<?>[] futures = new CompletableFuture<?>[stages.size()];
        for (int i = 0; i < stages.size(); i++) {
            futures[i] = stages.get(i).toCompletableFuture();
        }
        CompletableFuture.allOf(futures).whenComplete((v, err) -> {
            try {
                logic.execute(() -> {
                    Long current = localReputationMutationSeq.get(masterId);
                    if (current != null && current == seq) {
                        localReputationMutationSeq.remove(masterId);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // 节点停服后丢弃迟到回调
            }
        });
    }
}
