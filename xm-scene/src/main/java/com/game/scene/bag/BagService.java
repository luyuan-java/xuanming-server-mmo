package com.game.scene.bag;

import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.gainblock.GlobalGainBlocks;
import com.game.scene.gainblock.RedisGainBlockSource;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.player.ItemGuids;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import java.util.Map;
import java.util.TreeMap;

/**
 * 背包读写的唯一入口（场景逻辑线程上调用；基线 BagService）：先过闸（冻结、全服禁发），再调容器（{@link Bag}），
 * 成功后连带记资产流水、做获取异常检测。各玩法不直接改背包。
 *
 * <p>闸只在这一层和更外面的入口：战斗中禁止扣减之类的闸放在各玩法的入口（基线 D48：战斗结算是在战斗标记还挂着时应用的，
 * 闸下沉到这里会把结算自己拦住）。跨节点换图冻结中（交出事务在途，{@link ScenePlayer#frozen()}）一切写都回 1005（基线 BagService
 * 同码）：客户端入口已按冻结策略收拢（scene-handoff-spec §5.9），这里是纵深防御，挡住经内部路径（任务领奖、资产通道）漏进来的写。
 */
public final class BagService {

    /** 物品被封禁 / 玩家冻结（基线物品口径回 1005，与货币的 27005 / 27003 不同——都是客户端契约）。入包的容器本身不回 1005，所以 1005 一定是闸拒的。 */
    public static final int REFUSED = CommonErrorTip.common_error.kInvalidParameter_VALUE;

    private final BagTables tables;
    private final ItemGuids guids;
    private final AssetAudit audit;
    private final GainAnomalyDetector anomalies;
    private final SceneMetrics metrics;
    /** 当前生效的全服产出封禁名单（只在逻辑线程上读写）。 */
    private GlobalGainBlocks globalBlocks = GlobalGainBlocks.NONE;

    public BagService(BagTables tables, ItemGuids guids, AssetAudit audit, GainAnomalyDetector anomalies,
                      SceneMetrics metrics) {
        this.tables = tables;
        this.guids = guids;
        this.audit = audit;
        this.anomalies = anomalies;
        this.metrics = metrics;
    }

    public BagTables tables() {
        return tables;
    }

    /** 换上新的全服产出封禁名单（逻辑线程上调用）。 */
    public void applyGlobalBlocks(GlobalGainBlocks blocks) {
        globalBlocks = blocks;
    }

    /**
     * 进场景前规整存档（{@code PlayerInitializer}）：结构性损坏抛异常，按进场失败处理（不会把坏档当成空包存回去）。
     */
    public void initializeOnLoad(ScenePlayer player) {
        player.bags().normalize(tables);
    }

    /**
     * 批量入包（基线 BagService::AddItems(map)）：闸 → 容器整批原子写入 → 每个配置一条入包流水 + 异常检测，每个被淘汰的实例一条销毁流水。
     *
     * @param counts        配置号 → 数量
     * @param correlationId 流水关联号（0 = 无）
     * @param extra         流水附加信息 JSON（空 = 无）
     * @return 0 成功；1005 被封禁 / 冻结；其余见 {@link Bag#add}（批量满包是 6006）
     */
    public Bag.AddResult addItems(ScenePlayer player, BagType type, Map<Integer, Long> counts, AssetAudit.Reason reason,
                                  long correlationId, String extra) {
        if (!writable(player)) {
            return new Bag.AddResult(REFUSED, java.util.List.of(), java.util.List.of());
        }
        TreeMap<Integer, Long> ordered = new TreeMap<>(Integer::compareUnsigned);
        ordered.putAll(counts);
        for (int configId : ordered.keySet()) {
            if (globalBlocks.blocksItem(configId)) {
                metrics.gainBlocked(RedisGainBlockSource.ITEM);
                return new Bag.AddResult(REFUSED, java.util.List.of(), java.util.List.of());
            }
        }
        Bag.AddResult result = player.bags().bag(type).add(ordered, tables, guids);
        for (Bag.Removed evicted : result.evicted()) {
            audit.itemDestroyed(player.playerId(), evicted.guid(), evicted.configId(), evicted.size(),
                    AssetAudit.Reason.ITEM_DESTROY, 0, "");
        }
        for (Bag.Written written : result.written()) {
            audit.itemGained(player.playerId(), written.guids().get(0), written.configId(), written.count(), reason,
                    correlationId, extra);
            anomalies.itemGained(player, written.configId(), written.count());
        }
        return result;
    }

    /**
     * 玩家整理（192 SortBag；基线 SortByPlayerRequest = MergeAndCompact(kMergeAndReorder)）。合并掉的空实例各记一条数量 0 的销毁流水。
     * 调用方先判背包是否可整理（只允许人物背包与仓库）。
     *
     * @return null = 被闸拒绝（1005）；否则整理结果
     */
    public Bag.SortResult sortByPlayer(ScenePlayer player, BagType type) {
        if (!writable(player)) {
            return null;
        }
        Bag.SortResult result = player.bags().bag(type).mergeAndCompact(true, tables);
        for (Bag.Removed retired : result.retired()) {
            audit.itemDestroyed(player.playerId(), retired.guid(), retired.configId(), 0, AssetAudit.Reason.ITEM_DESTROY,
                    0, "");
        }
        return result;
    }

    /** 192 在回合制战斗中被挡（{@code xm.scene.battle.gate.rejects{gate=bag_sort}}；闸在入口 BagFeature，这里只计数）。 */
    public void battleGateRejected() {
        metrics.battleGateReject(SceneMetrics.BattleGate.BAG_SORT);
    }

    /**
     * 按持有夹紧的批量扣除（回合制战斗结算的消耗，scene-battle-spec §7.11 h 步；基线 {@code RemoveItemsClamped}）：每个配置（无符号升序）
     * 按<b>格子号升序</b>抽取（D22），扣不满不报错；每个被抽到的实例记一条销毁流水（{@code reason}、关联号、附加信息由调用方给）。
     * 闸：冻结中什么都不扣（返回空表；调用方已排除冻结）。<b>不判战斗在途</b>（D48）。
     *
     * @param counts 配置号 → 请求扣除的数量（每项 1 .. 2^32−1）
     * @return 配置号 → 实扣数量（扣到 0 的配置不出现）
     */
    public Map<Integer, Long> removeClamped(ScenePlayer player, BagType type, Map<Integer, Long> counts,
                                            AssetAudit.Reason reason, long correlationId, String extra) {
        if (!writable(player)) {
            return Map.of();
        }
        TreeMap<Integer, Long> ordered = new TreeMap<>(Integer::compareUnsigned);
        ordered.putAll(counts);
        Map<Integer, Long> removed = new TreeMap<>(Integer::compareUnsigned);
        Bag bag = player.bags().bag(type);
        for (Map.Entry<Integer, Long> e : ordered.entrySet()) {
            long total = 0;
            for (Bag.Drawn drawn : bag.drainClamped(e.getKey(), e.getValue())) {
                audit.itemDestroyed(player.playerId(), drawn.guid(), drawn.configId(), drawn.count(), reason, correlationId,
                        extra);
                total += drawn.count();
            }
            if (total > 0) {
                removed.put(e.getKey(), total);
            }
        }
        return removed;
    }

    /**
     * 只合并、不重排（基线 {@code MergeAndCompact(kMergeOnly)}，结算扣药之后用）。合并掉的空实例各记一条数量 0 的销毁流水。
     */
    public void mergeOnly(ScenePlayer player, BagType type) {
        if (!writable(player)) {
            return;
        }
        Bag.SortResult result = player.bags().bag(type).mergeAndCompact(false, tables);
        for (Bag.Removed retired : result.retired()) {
            audit.itemDestroyed(player.playerId(), retired.guid(), retired.configId(), 0, AssetAudit.Reason.ITEM_DESTROY, 0, "");
        }
    }

    /**
     * 冻结闸：跨节点换图的交出事务在途（{@link ScenePlayer#frozen()}）时拒绝写（基线回 1005）——冻结快照已在写库，之后的改动会随实例移除而丢。
     * 选目标中（RESOLVING）不冻结，照常写。跨 zone 交接（5.4）共用同一个冻结状态。
     */
    private static boolean writable(ScenePlayer player) {
        return !player.frozen();
    }
}
