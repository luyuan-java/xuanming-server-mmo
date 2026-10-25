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

    /** 多种叠加物和完整唯一实例一起规划；失败不改变玩家，成功后由资产通道在同一逻辑线程提交。 */
    public final class PreparedTransfer {
        private final ScenePlayer player;
        private final com.game.scene.player.PlayerBags candidate;
        private final java.util.List<com.game.player.store.state.BagItemState> instances;
        private final java.util.List<Bag.Drawn> removed;
        private final java.util.List<Bag.Written> gained;
        private final int tip;
        private boolean committed;

        private PreparedTransfer(ScenePlayer player, com.game.scene.player.PlayerBags candidate,
                                 java.util.List<com.game.player.store.state.BagItemState> instances,
                                 java.util.List<Bag.Drawn> removed, java.util.List<Bag.Written> gained, int tip) {
            this.player = player; this.candidate = candidate; this.instances = java.util.List.copyOf(instances);
            this.removed = java.util.List.copyOf(removed); this.gained = java.util.List.copyOf(gained); this.tip = tip;
        }
        public int tip() { return tip; }
        public java.util.List<com.game.player.store.state.BagItemState> instances() { return instances; }
        public void commit(AssetAudit.Reason reason, long correlationId) {
            commit(reason,reason,correlationId);
        }
        public void commitBattle(long battleId) {
            commit(AssetAudit.Reason.ITEM_DESTROY,AssetAudit.Reason.ITEM_AWARD,battleId);
        }
        private void commit(AssetAudit.Reason removedReason,AssetAudit.Reason gainedReason,long correlationId) {
            if (tip != 0 || committed || !writable(player)) throw new IllegalStateException("资产背包候选不能提交");
            player.bags().commitPrepared(candidate);
            committed = true;
            for (var item : removed) {
                try { audit.itemDestroyed(player.playerId(), item.guid(), item.configId(), item.count(), removedReason, correlationId, ""); }
                catch (RuntimeException failure) { transferAuditFailure(correlationId, failure); }
            }
            for (var item : gained) {
                try {
                    audit.itemGained(player.playerId(), item.guids().getFirst(), item.configId(), item.count(), gainedReason, correlationId, "");
                    anomalies.itemGained(player, item.configId(), item.count());
                } catch (RuntimeException failure) { transferAuditFailure(correlationId, failure); }
            }
        }
    }

    public PreparedTransfer prepareDebit(ScenePlayer player, Map<Integer, Long> counts, java.util.List<Long> guidsToTake) {
        var snapshot = player.bags().toState();
        var candidate = com.game.scene.player.PlayerBags.restore(snapshot);
        candidate.normalize(tables);
        var bag = candidate.bag(BagType.INVENTORY);
        var removed = new java.util.ArrayList<Bag.Drawn>();
        var instances = new java.util.ArrayList<com.game.player.store.state.BagItemState>();
        java.util.Set<Long> unique = new java.util.HashSet<>();
        if (!writable(player)) return failedTransfer(player, REFUSED);
        for (var entry : counts.entrySet()) {
            var spec = tables.item(entry.getKey());
            if (spec == null || !spec.stackable() || entry.getValue() <= 0 || bag.total(entry.getKey()) < entry.getValue()) {
                return failedTransfer(player, com.game.table.AssetErrorTip.asset_error.kAssetInvalidBundle_VALUE);
            }
        }
        for (long guid : guidsToTake) {
            var item = bag.item(guid);
            var spec = item == null ? null : tables.item(item.configId());
            if (!unique.add(guid) || item == null || item.size() != 1 || spec == null || spec.maxStackSize() != 1) {
                return failedTransfer(player, com.game.table.AssetErrorTip.asset_error.kAssetInvalidBundle_VALUE);
            }
            var raw = snapshot.getItemsList().stream().filter(stored -> stored.getItemUuid() == guid).findFirst().orElseThrow();
            instances.add(raw.toBuilder().clearPos().clearBagType().clearAcquireSeq().build());
        }
        for (var entry : new TreeMap<>(counts).entrySet()) removed.addAll(bag.drainClamped(entry.getKey(), entry.getValue()));
        for (var instance : instances) {
            bag.takeInstance(instance.getItemUuid());
            removed.add(new Bag.Drawn(instance.getItemUuid(), instance.getConfigId(), 1));
        }
        return new PreparedTransfer(player, candidate, instances, removed, java.util.List.of(), 0);
    }

    public PreparedTransfer prepareCredit(ScenePlayer player, Map<Integer, Long> counts,
                                           java.util.List<com.game.player.store.state.BagItemState> instances) {
        if (!writable(player)) return failedTransfer(player, REFUSED);
        var candidate = com.game.scene.player.PlayerBags.restore(player.bags().toState());
        candidate.normalize(tables);
        var bag = candidate.bag(BagType.INVENTORY);
        var gained = new java.util.ArrayList<Bag.Written>();
        for (int config : counts.keySet()) if (globalBlocks.blocksItem(config)) return failedTransfer(player, REFUSED);
        if (!counts.isEmpty()) {
            var result = bag.add(counts, tables, guids);
            if (!result.ok()) return failedTransfer(player, result.tip());
            gained.addAll(result.written());
        }
        for (var instance : instances) {
            if (globalBlocks.blocksItem(instance.getConfigId())) return failedTransfer(player, REFUSED);
            if (candidate.toState().getItemsList().stream().anyMatch(existing -> existing.getItemUuid() == instance.getItemUuid())) return failedTransfer(player, Bag.INVALID_PARAM);
            int tip = bag.putInstance(instance, tables);
            if (tip != 0) return failedTransfer(player, tip);
            gained.add(new Bag.Written(instance.getConfigId(), 1, java.util.List.of(instance.getItemUuid())));
        }
        return new PreparedTransfer(player, candidate, java.util.List.of(), java.util.List.of(), gained, 0);
    }

    private PreparedTransfer failedTransfer(ScenePlayer player, int tip) {
        return new PreparedTransfer(player, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), tip);
    }

    /** 消耗、主包掉落和临时格溢出一起规划；包满/封禁/铸号失败时原玩家零改动。 */
    public PreparedTransfer prepareBattle(ScenePlayer player,Map<Integer,Long> consumed,Map<Integer,Long> gained) {
        if(!writable(player))return failedTransfer(player,REFUSED);
        var snapshot=player.bags().toState();
        if(!snapshot.getUnknownFields().asMap().isEmpty())return failedTransfer(player,Bag.INVALID_PARAM);
        for(var request:consumed.entrySet()){
            var spec=tables.item(request.getKey());
            if(request.getKey()==0||request.getValue()<=0||spec==null||spec.maxStack()==0||tables.hasUnknownItemSchema(request.getKey()))
                return failedTransfer(player,Bag.INVALID_PARAM);
        }
        // 不把未来版本实例当普通数量扣减；无关opaque实例仍由原候选逐字保留。
        for(var item:snapshot.getItemsList())if(consumed.containsKey(item.getConfigId())
                &&(!item.getUnknownFields().asMap().isEmpty()||item.getItemUuid()==0||item.getItemUuid()==-1L||item.getStackSize()==0))
            return failedTransfer(player,Bag.INVALID_PARAM);
        var candidate=com.game.scene.player.PlayerBags.restore(snapshot);candidate.normalize(tables);
        var inventory=candidate.bag(BagType.INVENTORY);
        var removed=new java.util.ArrayList<Bag.Drawn>();var written=new java.util.ArrayList<Bag.Written>();
        for(var entry:consumed.entrySet())removed.addAll(inventory.drainClamped(entry.getKey(),entry.getValue()));
        for(var entry:new TreeMap<>(gained).entrySet()) {
            if(globalBlocks.blocksItem(entry.getKey()))return failedTransfer(player,REFUSED);
            long before=inventory.total(entry.getKey());
            var first=inventory.add(Map.of(entry.getKey(),entry.getValue()),tables,guids);written.addAll(first.written());
            long credited=inventory.total(entry.getKey())-before;
            long remaining=entry.getValue()-credited;
            if(remaining<0)throw new IllegalStateException("战斗背包候选数量超过原件");
            if(remaining>0){
                var overflow=candidate.bag(BagType.TEMPORARY).add(Map.of(entry.getKey(),remaining),tables,guids);
                if(!overflow.ok())return failedTransfer(player,overflow.tip());written.addAll(overflow.written());
            }else if(!first.ok())return failedTransfer(player,first.tip());
        }
        return new PreparedTransfer(player,candidate,java.util.List.of(),removed,written,0);
    }

    private static void transferAuditFailure(long correlationId, RuntimeException failure) {
        // 玩家状态已原子提交，不能因旁路审计异常跳过同SQL存档的幂等账本、导致重投二次扣发。
        org.slf4j.LoggerFactory.getLogger(BagService.class).error("资产转移审计失败，需按操作号对账 correlation={} reason={}",
                Long.toUnsignedString(correlationId), failure.getClass().getSimpleName());
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

    /** 192 在回合制战斗中被挡（{@code game.scene.battle.gate.rejects{gate=bag_sort}}；闸在入口 BagFeature，这里只计数）。 */
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
