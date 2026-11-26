package com.game.scene.craft;

import com.game.player.store.state.PlayerCraftState;
import com.game.proto.CraftProxyStallInfo;
import com.game.scene.equip.EquipCraftQualityRules;
import com.game.scene.equip.EquipmentCraftService;
import com.game.scene.world.ScenePlayer;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 跨节点/离线名匠代打共享摊位目录（场景逻辑线程持有本地只读镜像，Redis Hash 异步读写）：
 * 宗师开启营业摊位或登录后即同步入共享目录；下线后摊位继续保留，买家可在任意场景节点 24 小时查看并下单。
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
    }

    private final BackingStore store;
    private final Executor logic;
    private final Map<Long, CraftProxyStallInfo> stalls = new HashMap<>();
    private final Map<Long, Long> localMutationSeq = new HashMap<>();
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

    /** 仅测试：从共享目录直接摘除指定摊位。 */
    public void removeForTest(long masterPlayerId) {
        stalls.remove(masterPlayerId);
        localMutationSeq.remove(masterPlayerId);
    }

    /** 逻辑线程合并远端 Redis Hash 全量快照，保留尚在途的本节点本地写避免回退。 */
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

    public CraftProxyStallInfo find(long masterPlayerId) {
        if (masterPlayerId == 0L) {
            return null;
        }
        CraftProxyStallInfo info = stalls.get(masterPlayerId);
        return isValidStall(info) ? info : null;
    }

    public List<CraftProxyStallInfo> listStalls() {
        List<CraftProxyStallInfo> out = new ArrayList<>();
        for (CraftProxyStallInfo info : stalls.values()) {
            if (isValidStall(info)) {
                out.add(info);
            }
        }
        out.sort(Comparator.comparing(CraftProxyStallInfo::getMasterPlayerId, Long::compareUnsigned));
        return out;
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
}
