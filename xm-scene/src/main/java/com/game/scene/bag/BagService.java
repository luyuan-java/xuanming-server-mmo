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
 * 闸下沉到这里会把结算自己拦住）。跨节点换图冻结随 5.2 接入（基线回 1005），目前恒放行。
 */
public final class BagService {

    /** 物品被封禁 / 玩家冻结（基线物品口径回 1005，与货币的 27005 / 27003 不同——都是客户端契约）。 */
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;

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
            return new Bag.AddResult(INVALID_PARAMETER, java.util.List.of(), java.util.List.of());
        }
        TreeMap<Integer, Long> ordered = new TreeMap<>(Integer::compareUnsigned);
        ordered.putAll(counts);
        for (int configId : ordered.keySet()) {
            if (globalBlocks.blocksItem(configId)) {
                metrics.gainBlocked(RedisGainBlockSource.ITEM);
                return new Bag.AddResult(INVALID_PARAMETER, java.util.List.of(), java.util.List.of());
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

    /** 冻结闸：跨节点换图 / 跨 zone 交接在途时拒绝写（基线回 1005）。Java 还没有交接冻结（5.2），恒放行。 */
    private static boolean writable(ScenePlayer player) {
        return true;
    }
}
