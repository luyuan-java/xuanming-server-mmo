package com.game.scene.battle;

import static com.game.scene.world.SceneMessageIds.push;

import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattleSettlementData;
import com.game.proto.PetListChangedS2C;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneBattleMetrics;
import com.game.scene.metrics.SceneBattleMetrics.ItemKind;
import com.game.scene.metrics.SceneBattleMetrics.SettlementResult;
import com.game.scene.mission.MissionService;
import com.game.scene.pet.PetService;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerAttributes;
import com.game.scene.player.PlayerRevive;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一份结算在一名玩家身上的应用（基线 {@code ApplySettlementToEntity}，{@code pb.cpp:1613-1809}；scene-battle-spec §7.11）。逻辑线程上、一个任务内
 * 同步完成。<b>顺序就是语义</b>：
 * <ol>
 *   <li>a 归属：{@code settlement.player_id == 玩家} 且 battle_id ≠ 0，否则 DISCARDED（不应用、不销账、不毒化合法重投）；</li>
 *   <li>b 账本命中 → ALREADY_APPLIED（不推 150）；</li>
 *   <li>c 交出冻结中或账本损坏 → DEFERRED，零副作用；</li>
 *   <li>d <b>金币先行</b>：唯一会正常失败的入账，失败（封禁 / 溢出 / 冻结）→ DEFERRED，此前没有任何副作用；</li>
 *   <li>e–j 气血法力（夹到上限、阵亡回满，不推属性消息）→ 宝宝（有条目推一次 184）→ 经验（只打日志）→ 消耗（按持有夹紧、格子号升序）→
 *       掉落（主包放不下的改投临时格，再放不下丢失并 ERROR）→ 击杀事实；每步单独 try / catch，出错只记 ERROR、继续；</li>
 *   <li>k 在 e–j 的 {@code finally} 里登记账本（金币一旦入账就一定登记）；</li>
 *   <li>l 请求一次在线存盘。</li>
 * </ol>
 * <b>应用时战斗冻结还没摘</b>（调用方在应用之后才解冻），所以 d–j 走到的代码都不得看 {@code inBattle()}：宝宝回写与气血回写不经过带 26008 / 25011 的
 * {@code checkWritable}；货币 / 背包 / 任务服务只在交出冻结时拒绝（c 步已排除），战斗闸不下沉到它们（D48）。
 */
public final class BattleSettlementService {

    private static final Logger log = LoggerFactory.getLogger(BattleSettlementService.class);

    /** 道具数量的上限（uint64 → uint32 夹紧，§7.11 h / i 步）。 */
    static final long UINT32_MAX = 0xFFFF_FFFFL;

    /** 一次应用的结论。 */
    public enum Outcome {
        APPLIED, ALREADY_APPLIED, DEFERRED, DISCARDED
    }

    /**
     * @param outcome 结论
     * @param reason  指标口径（DEFERRED / DISCARDED 时说明原因）
     */
    public record Result(Outcome outcome, SettlementResult reason) {

        static final Result APPLIED = new Result(Outcome.APPLIED, SettlementResult.APPLIED);
        static final Result ALREADY = new Result(Outcome.ALREADY_APPLIED, SettlementResult.ALREADY_APPLIED);
    }

    private final CurrencyService currency;
    private final BagService bags;
    private final PetService pets;
    private final MissionService missions;
    private final SceneBattleTables tables;
    private final SceneBattleMetrics metrics;
    private final SceneClock clock;
    private final int notifyPetListChanged;

    /** @param notifyPetListChanged 184 的消息号（{@code ScenePetClientPlayer.NotifyPetListChanged}） */
    public BattleSettlementService(CurrencyService currency, BagService bags, PetService pets, MissionService missions,
                                   SceneBattleTables tables, SceneBattleMetrics metrics, SceneClock clock, int notifyPetListChanged) {
        this.currency = Objects.requireNonNull(currency, "currency");
        this.bags = Objects.requireNonNull(bags, "bags");
        this.pets = Objects.requireNonNull(pets, "pets");
        this.missions = Objects.requireNonNull(missions, "missions");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.notifyPetListChanged = notifyPetListChanged;
    }

    /**
     * 应用一份结算（逻辑线程）。
     *
     * @throws IllegalStateException 重入（同一玩家上一笔应用还没结束，取代基线应用缓存的 InFlight，D18）
     */
    public Result apply(SceneWorld world, ScenePlayer player, BattleSettlementData settlement) {
        PlayerBattle battle = player.battle();
        if (battle.applying()) {
            throw new IllegalStateException("战斗结算重入 player=" + Long.toUnsignedString(player.playerId()));
        }
        battle.setApplying(true);
        try {
            return applyOnce(world, player, settlement);
        } finally {
            battle.setApplying(false);
        }
    }

    private Result applyOnce(SceneWorld world, ScenePlayer player, BattleSettlementData settlement) {
        long battleId = settlement.getBattleId();
        long playerId = player.playerId();
        // a. 归属
        if (settlement.getPlayerId() != playerId || battleId == 0) {
            log.error("战斗结算归属不符，不应用（不销账） player={} settlement.player_id={} battle_id={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(settlement.getPlayerId()), Long.toUnsignedString(battleId));
            return new Result(Outcome.DISCARDED, SettlementResult.DISCARDED_INVALID);
        }
        BattleLedger ledger = player.battleLedger();
        // b. 持久账本命中
        if (ledger.invalidReason() == null && ledger.has(battleId)) {
            return Result.ALREADY;
        }
        // c. 整笔可应用：交出冻结中、账本损坏 → 延后，零副作用
        if (player.frozen()) {
            return new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_FROZEN);
        }
        if (ledger.invalidReason() != null) {
            log.error("战斗结算账本损坏，结算延后（等 GM 修复） player={} battle_id={} 原因={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), ledger.invalidReason());
            return new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_LEDGER);
        }
        // d. 金币先行（唯一会正常失败的入账，之前不得有任何副作用）
        long gold = settlement.getGoldGain();
        if (gold < 0) {
            log.error("战斗结算金币超出 int64（坏数据），延后 player={} battle_id={} gold={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), Long.toUnsignedString(gold));
            return new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_CURRENCY);
        }
        if (gold > 0) {
            Wallet.Change change = currency.add(player, Wallet.GOLD, gold, AssetAudit.Reason.BATTLE_REWARD, battleId);
            if (!change.ok()) {
                log.warn("战斗结算金币入账失败（封禁 / 溢出 / 冻结），整笔延后 player={} battle_id={} gold={} tip={}",
                        Long.toUnsignedString(playerId), Long.toUnsignedString(battleId), gold, change.tipId());
                return new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_CURRENCY);
            }
        }
        // e–j：金币已入账，之后任何一步出错都不得让整笔失败；k 在 finally 里登记账本
        try {
            step("气血法力", player, battleId, () -> applyVitals(player, settlement));
            step("宝宝", player, battleId, () -> applyPets(world, player, settlement));
            step("经验", player, battleId, () -> {
                if (settlement.getExpGain() != 0) {
                    metrics.expIgnored();
                    log.info("战斗结算经验（没有经验系统，只记日志） player={} battle_id={} exp={}", Long.toUnsignedString(playerId),
                            Long.toUnsignedString(battleId), Long.toUnsignedString(settlement.getExpGain()));
                }
            });
            step("消耗", player, battleId, () -> applyConsumed(player, settlement));
            step("掉落", player, battleId, () -> applyGained(player, settlement));
            step("击杀", player, battleId, () -> {
                for (BattleMonsterDefeat defeat : settlement.getDefeatedMonstersList()) {
                    missions.onMonsterKilled(player, defeat.getMonsterConfigId(), defeat.getCount());
                }
            });
        } finally {
            long evicted = ledger.record(battleId, clock.epochMillis());
            if (evicted != 0) {
                metrics.ledgerEvicted();
                log.error("战斗结算账本满 {}，淘汰最旧项（被淘汰的局再被投递会重复发奖） player={} evicted={} battle_id={}",
                        BattleLedger.CAPACITY, Long.toUnsignedString(playerId), Long.toUnsignedString(evicted),
                        Long.toUnsignedString(battleId));
            }
        }
        // l. 尽快落盘（销账要等 durable）
        world.requestSave(player);
        log.info("战斗结算已应用 player={} battle_id={} outcome={} gold={} hp={} consumed={} gained={} pets={} kills={}",
                Long.toUnsignedString(playerId), Long.toUnsignedString(battleId), settlement.getOutcome(), gold,
                settlement.getHealth(), settlement.getItemsConsumedCount(), settlement.getItemsGainedCount(),
                settlement.getPetsCount(), settlement.getDefeatedMonstersCount());
        return Result.APPLIED;
    }

    private static void step(String name, ScenePlayer player, long battleId, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            log.error("战斗结算第「{}」步出错（金币已入账，其余照做、账本照记） player={} battle_id={}", name,
                    Long.toUnsignedString(player.playerId()), Long.toUnsignedString(battleId), e);
        }
    }

    /** e：气血按派生上限夹（上限 &gt; 0 才夹），法力上限 &gt; 0 才夹；夹后气血为 0 回满（is_dead 但气血 &gt; 0 不复活，同基线）。不推任何属性消息。 */
    private static void applyVitals(ScenePlayer player, BattleSettlementData settlement) {
        PlayerAttributes attributes = player.attributes();
        PlayerAttributes.Derived derived = attributes.derived();
        long health = saturate(settlement.getHealth());
        long mana = saturate(settlement.getMana());
        if (derived.maxHealth() > 0) {
            health = Math.min(health, derived.maxHealth());
        }
        if (derived.maxMana() > 0) {
            mana = Math.min(mana, derived.maxMana());
        }
        PlayerRevive.Result revived = PlayerRevive.reviveIfDead(health, mana, derived.maxHealth(), derived.maxMana());
        attributes.setHealth(revived.health());
        attributes.setMana(revived.mana());
    }

    /** f：宝宝逐个回写（不经 checkWritable），有条目时推一次 184。 */
    private void applyPets(SceneWorld world, ScenePlayer player, BattleSettlementData settlement) {
        if (settlement.getPetsCount() == 0) {
            return;
        }
        pets.applyBattleSettlement(player, settlement.getPetsList());
        world.sendTo(player, push(notifyPetListChanged, PetListChangedS2C.newBuilder().setPets(pets.buildList(player)).build()));
    }

    /** h：消耗（跳过 0；非 battle_usable 拒扣；数量夹到 uint32；按持有夹紧、格子号升序；之后只合并不重排）。 */
    private void applyConsumed(ScenePlayer player, BattleSettlementData settlement) {
        long battleId = settlement.getBattleId();
        Map<Integer, Long> counts = new TreeMap<>(Integer::compareUnsigned);
        for (BattleItemEntry item : settlement.getItemsConsumedList()) {
            if (item.getItemTableId() == 0 || item.getCount() == 0) {
                continue;
            }
            if (!tables.battleUsable(item.getItemTableId())) {
                metrics.item(ItemKind.CONSUME_REJECTED);
                log.error("战斗结算消耗了非战斗道具（防伪造，拒扣） player={} battle_id={} item={} count={}",
                        Long.toUnsignedString(player.playerId()), Long.toUnsignedString(battleId), item.getItemTableId(),
                        Long.toUnsignedString(item.getCount()));
                continue;
            }
            counts.merge(item.getItemTableId(), clampCount(item.getCount()), (a, b) -> Math.min(a + b, UINT32_MAX));
        }
        if (counts.isEmpty()) {
            return;
        }
        Map<Integer, Long> removed = bags.removeClamped(player, BagType.INVENTORY, counts, AssetAudit.Reason.ITEM_DESTROY, battleId,
                extra(battleId));
        counts.forEach((config, wanted) -> {
            long got = removed.getOrDefault(config, 0L);
            if (got < wanted) {
                metrics.item(ItemKind.CONSUME_CLAMPED);
                log.warn("战斗结算消耗按持有夹紧 player={} battle_id={} item={} 请求={} 实扣={}", Long.toUnsignedString(player.playerId()),
                        Long.toUnsignedString(battleId), config, wanted, got);
            }
        });
        bags.mergeOnly(player, BagType.INVENTORY);
    }

    /** i：掉落（数量夹到 uint32 后按配置累加；主包失败时没进去的部分改投临时格；两处都放不下丢失并 ERROR，不让整笔失败）。 */
    private void applyGained(ScenePlayer player, BattleSettlementData settlement) {
        long battleId = settlement.getBattleId();
        Map<Integer, Long> counts = new TreeMap<>(Integer::compareUnsigned);
        for (BattleItemEntry item : settlement.getItemsGainedList()) {
            if (item.getItemTableId() == 0 || item.getCount() == 0) {
                continue;
            }
            counts.merge(item.getItemTableId(), clampCount(item.getCount()), (a, b) -> Math.min(a + b, UINT32_MAX));
        }
        if (counts.isEmpty()) {
            return;
        }
        Bag inventory = player.bags().bag(BagType.INVENTORY);
        Map<Integer, Long> before = new TreeMap<>(Integer::compareUnsigned);
        counts.keySet().forEach(config -> before.put(config, inventory.total(config)));
        String extra = extra(battleId);
        Bag.AddResult added = bags.addItems(player, BagType.INVENTORY, counts, AssetAudit.Reason.ITEM_AWARD, battleId, extra);
        if (added.ok()) {
            return;
        }
        Map<Integer, Long> missing = new TreeMap<>(Integer::compareUnsigned);
        counts.forEach((config, wanted) -> {
            long got = inventory.total(config) - before.get(config);
            if (got < wanted) {
                missing.put(config, wanted - Math.max(got, 0));
            }
        });
        if (missing.isEmpty()) {
            return;
        }
        Bag.AddResult overflow = bags.addItems(player, BagType.TEMPORARY, missing, AssetAudit.Reason.ITEM_AWARD, battleId, extra);
        if (overflow.ok()) {
            metrics.item(ItemKind.DROP_OVERFLOW);
            log.info("战斗掉落主包放不下，改投临时格 player={} battle_id={} items={} 主包 tip={}", Long.toUnsignedString(player.playerId()),
                    Long.toUnsignedString(battleId), missing, added.tip());
            return;
        }
        metrics.item(ItemKind.DROP_LOST);
        log.error("战斗掉落主包与临时格都放不下（或被获取封禁），道具丢失 player={} battle_id={} items={} 主包 tip={} 临时格 tip={}",
                Long.toUnsignedString(player.playerId()), Long.toUnsignedString(battleId), missing, added.tip(), overflow.tip());
    }

    private static long clampCount(long count) {
        return Long.compareUnsigned(count, UINT32_MAX) > 0 ? UINT32_MAX : count;
    }

    private static long saturate(long unsigned) {
        return unsigned < 0 ? Long.MAX_VALUE : unsigned;
    }

    /** 流水附加信息（同基线 {@code {"source":"battle","battle_id":N}}）。 */
    static String extra(long battleId) {
        return "{\"source\":\"battle\",\"battle_id\":" + Long.toUnsignedString(battleId) + "}";
    }
}
