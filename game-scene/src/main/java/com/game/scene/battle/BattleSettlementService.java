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
    private com.game.scene.season.SeasonService seasonService;

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

    public BattleSettlementService withSeasonService(com.game.scene.season.SeasonService seasonService) {
        this.seasonService = seasonService;
        return this;
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

    /** 新Inbox路径不使用会裁剪的BattleLedger；效果与永久Inbox水位由同一逻辑任务/SQL存盘提交。 */
    public Result applyInbox(SceneWorld world,ScenePlayer player,BattleSettlementData settlement) {
        // 旧在线快照必须先完成：本段到Inbox记水位/requestPersist均在同一逻辑调用栈内，
        // Pet/Mission/Bag/流水只同步改内存或提交不可变流水，不会启动玩家存盘。
        // 因此异常隔离时没有可能迟到覆盖同E新实体的旧PlayerSave。
        if(settlement.getPlayerId()!=player.playerId()||settlement.getBattleId()==0||world==null||!world.canApplyBattleInbox(player,player.ownerEpoch())
                ||player.inBattle()&&player.battle().freeze().battleId()!=settlement.getBattleId())
            return new Result(Outcome.DEFERRED,SettlementResult.DEFERRED_FROZEN);
        if(player.battle().applying())throw new IllegalStateException("战斗Inbox结算重入");
        player.battle().setApplying(true);
        try{
            if (settlement.getCompetitiveRanked() || settlement.getUseFreePreset()) {
                // 正式人宠会武(competitive_ranked)与免费预设娱乐场(use_free_preset)零资产侵入：
                // 不动气血/法力/宠物/背包/金币/经验/任务，仅原子累计材料自选额度、赛季进度、试炼志与近期战果。
                markNormalBossTrialIfCleared(player, settlement);
                recordBattleResult(player, settlement, 0L, Map.of(), Map.of());
                return Result.APPLIED;
            }
            long now=currency.nowSeconds(),gold=settlement.getGoldGain();
            int goldTip=gold>0?currency.checkAdd(player,Wallet.GOLD,gold,now):0;
            // 产出封禁是明确跳过该奖励的业务决定；冻结、溢出等仍须整笔延后，不能丢掉应补发的金币。
            if(gold<0||goldTip!=0&&goldTip!=Wallet.BLOCKED)
                return new Result(Outcome.DEFERRED,SettlementResult.DEFERRED_CURRENCY);
            // 未知/损坏的持久组件仍整笔拒绝；合法空组件不等于损坏。
            var petState=player.pets().toState();
            java.util.Set<Long> owned=new java.util.HashSet<>();
            if(!petState.getUnknownFields().asMap().isEmpty())return new Result(Outcome.DEFERRED,SettlementResult.DEFERRED_LEDGER);
            for(var pet:petState.getPetsList())if(pet.getPetId()==0||pet.getPetTableId()==0||pet.getLevel()==0
                    ||!owned.add(pet.getPetId())||!pet.getUnknownFields().asMap().isEmpty())
                return new Result(Outcome.DEFERRED,SettlementResult.DEFERRED_LEDGER);
            if(petState.getActivePetId()!=0&&!owned.contains(petState.getActivePetId()))
                return new Result(Outcome.DEFERRED,SettlementResult.DEFERRED_LEDGER);
            var known=pets.buildList(player).getPetsList();
            var applicablePets=new java.util.ArrayList<com.game.proto.BattlePetSettlementData>();
            java.util.Set<Long> seen=new java.util.HashSet<>();
            for(var entry:settlement.getPetsList()){
                if(entry.getPetId()==0||!seen.add(entry.getPetId())||!entry.getUnknownFields().asMap().isEmpty())
                    return new Result(Outcome.DEFERRED,SettlementResult.DEFERRED_LEDGER);
                // PetService正式面板仅在pool/table均存在时给正maxHealth；永久缺失只跳此子效果。
                if(!owned.contains(entry.getPetId())||known.stream().noneMatch(p->p.getPetId()==entry.getPetId()&&p.getDerived().getMaxHealth()>0)){
                    log.warn("战斗宠物子效果不再适用 reason=retired_or_missing_config battle={} pet={}",
                            Long.toUnsignedString(settlement.getBattleId()),Long.toUnsignedString(entry.getPetId()));
                    continue;
                }
                applicablePets.add(entry);
            }
            Map<Integer,Long> consumed=new TreeMap<>(Integer::compareUnsigned),gained=new TreeMap<>(Integer::compareUnsigned);
            for(var item:settlement.getItemsConsumedList()){
                if(item.getItemTableId()==0||item.getCount()==0)continue;
                // 原终局已经固定开局准许的config/count；热更后的battleUsable不能抹掉已发生的消耗。
                consumed.merge(item.getItemTableId(),clampCount(item.getCount()),(a,b)->Math.min(a+b,UINT32_MAX));
            }
            for(var item:settlement.getItemsGainedList())if(item.getItemTableId()!=0&&item.getCount()!=0)
                gained.merge(item.getItemTableId(),clampCount(item.getCount()),(a,b)->Math.min(a+b,UINT32_MAX));
            // 配置行已被新版本删掉的掉落 / 消耗：只剔除这一项（记 ERROR 与计数），其余照常应用——同宠物缺配置只跳子效果。否则整包候选恒失败、
            // 永久 DEFER，这一局的金币和其余奖励永远到不了账。全服产出封禁不在此列：被封禁的物品配置行还在，照旧走整包候选失败（口径待定，不改）。
            dropRetiredItems(settlement.getBattleId(),consumed,ItemKind.CONSUME_CONFIG_MISSING);
            dropRetiredItems(settlement.getBattleId(),gained,ItemKind.DROP_CONFIG_MISSING);
            Map<Integer,Long> shortfalls=new TreeMap<>(Integer::compareUnsigned);
            consumed.forEach((config,wanted)->{long held=player.bags().bag(BagType.INVENTORY).total(config);if(held<wanted)shortfalls.put(config,wanted-held);});
            var bag=bags.prepareBattle(player,consumed,gained);
            if(bag.tip()!=0)return new Result(Outcome.DEFERRED,SettlementResult.DEFERRED_LEDGER);
            var captured = pets.prepareCapturedPets(player, settlement.getCapturedPetTableIdsList());
            if (captured.tip() != 0) return new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_LEDGER);
            // 正常业务拒绝已全部预检。普通异常交给组合根不存盘隔离，绝不逐步catch后登记APPLIED。
            if(gold>0){
                var change=currency.add(player,Wallet.GOLD,gold,AssetAudit.Reason.BATTLE_REWARD,settlement.getBattleId(),now);
                if(change.tipId()!=goldTip)throw new IllegalStateException("战斗金币预检与同任务提交不一致");
                if(goldTip==Wallet.BLOCKED)log.warn("战斗金币奖励按产出封禁跳过，其它结算仍应用 battle={} player={} gold={}",
                        Long.toUnsignedString(settlement.getBattleId()),Long.toUnsignedString(player.playerId()),gold);
            }
            bag.commitBattle(settlement.getBattleId());applyVitals(player,settlement);
            pets.commitCapturedPets(player, captured);
            if (!captured.pets().isEmpty()) {
                world.sendTo(player, push(notifyPetListChanged,
                        PetListChangedS2C.newBuilder().setPets(pets.buildList(player)).build()));
            }
            pets.applyBattleSettlement(player,applicablePets);
            // 应用时战斗冻结已不在（判废期限已过才到达）：本局消耗的物品可能已在解冻之后经交易 / 帮会托管转走。夹紧照旧（不能扣成负数），
            // 另记专用计数与 WARN 供对账——它说明「冻结覆盖 Match 终局宽限 + 收件箱投递余量」这条不变量被打破了。
            boolean released=!shortfalls.isEmpty()&&player.battle().freeze()==null;
            shortfalls.forEach((config,missing)->{
                metrics.item(ItemKind.CONSUME_CLAMPED);
                log.error("战斗消耗按原持有夹紧 reason=held_shortfall battle={} item={} missing={}",
                        Long.toUnsignedString(settlement.getBattleId()),Integer.toUnsignedString(config),missing);
                if(released){
                    metrics.item(ItemKind.CONSUME_CLAMPED_UNFROZEN);
                    log.warn("战斗冻结已释放后结算才到达，消耗按当时持有夹紧 reason=clamped_after_freeze_release battle={} player={} item={} missing={}",
                            Long.toUnsignedString(settlement.getBattleId()),Long.toUnsignedString(player.playerId()),
                            Integer.toUnsignedString(config),missing);
                }
            });
            for(var defeat:settlement.getDefeatedMonstersList())missions.onMonsterKilled(player,defeat.getMonsterConfigId(),defeat.getCount());
            // 自动领奖失败的claimable仍在同份玩家状态，不删资格；经验产品边界仍不冒已到账。
            if(settlement.getExpGain()!=0)metrics.expIgnored();
            Map<Integer, Long> actualConsumed = new TreeMap<>(Integer::compareUnsigned);
            consumed.forEach((cfg, wanted) -> {
                long got = wanted - shortfalls.getOrDefault(cfg, 0L);
                if (got > 0) actualConsumed.put(cfg, got);
            });
            markNormalBossTrialIfCleared(player, settlement);
            recordBattleResult(player, settlement, gold > 0 && goldTip == 0 ? gold : 0L, gained, actualConsumed);
            return Result.APPLIED;
        }finally{player.battle().setApplying(false);}
    }

    /**
     * 剔除物品配置行已不存在的条目（新版本删了这一行）：剩下的照常进整包候选。只认「表里没有这一行」；行还在但语义未知（未知字段、堆叠上限 0）
     * 仍由整包候选拒绝、整笔延后，不在这里放过。每剔除一项记一次 {@code kind}（低基数）与一行 ERROR。
     */
    private void dropRetiredItems(long battleId, Map<Integer, Long> counts, ItemKind kind) {
        for (var it = counts.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (bags.tables().item(entry.getKey()) != null) {
                continue;
            }
            it.remove();
            metrics.item(kind);
            log.error("战斗结算的物品配置行已不存在，跳过这一项、其余照常应用 reason=item_config_missing kind={} battle={} item={} count={}",
                    kind.name().toLowerCase(java.util.Locale.ROOT), Long.toUnsignedString(battleId), Integer.toUnsignedString(entry.getKey()),
                    entry.getValue());
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
        if (settlement.getCompetitiveRanked() || settlement.getUseFreePreset()) {
            // 正式人宠会武(competitive_ranked)与免费预设娱乐场(use_free_preset)零资产侵入：
            // 跳过宠物/金币/气血法力/消耗/掉落/击杀，只记材料自选额度、赛季进度、试炼志与近期战果，返回前请求落盘。
            try {
                step("试炼志首领首通", player, battleId, () -> markNormalBossTrialIfCleared(player, settlement));
                step("近期战果", player, battleId, () -> recordBattleResult(player, settlement, 0L, Map.of(), Map.of()));
            } finally {
                long evicted = ledger.record(battleId, clock.epochMillis());
                if (evicted != 0) {
                    metrics.ledgerEvicted();
                    log.error("战斗结算账本满 {}，淘汰最旧项 player={} evicted={} battle_id={}",
                            BattleLedger.CAPACITY, Long.toUnsignedString(playerId), Long.toUnsignedString(evicted),
                            Long.toUnsignedString(battleId));
                }
            }
            world.requestSave(player);
            return Result.APPLIED;
        }
        var captured = pets.prepareCapturedPets(player, settlement.getCapturedPetTableIdsList());
        if (captured.tip() != 0) return new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_LEDGER);
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
            pets.commitCapturedPets(player, captured);
            step("气血法力", player, battleId, () -> applyVitals(player, settlement));
            step("宝宝", player, battleId, () -> applyPets(world, player, settlement));
            step("经验", player, battleId, () -> {
                if (settlement.getExpGain() != 0) {
                    metrics.expIgnored();
                    log.info("战斗结算经验（没有经验系统，只记日志） player={} battle_id={} exp={}", Long.toUnsignedString(playerId),
                            Long.toUnsignedString(battleId), Long.toUnsignedString(settlement.getExpGain()));
                }
            });
            Map<Integer, Long> actualConsumed = new TreeMap<>(Integer::compareUnsigned);
            Map<Integer, Long> actualGained = new TreeMap<>(Integer::compareUnsigned);
            step("消耗", player, battleId, () -> actualConsumed.putAll(applyConsumed(player, settlement)));
            step("掉落", player, battleId, () -> actualGained.putAll(applyGained(player, settlement)));
            step("击杀", player, battleId, () -> {
                for (BattleMonsterDefeat defeat : settlement.getDefeatedMonstersList()) {
                    missions.onMonsterKilled(player, defeat.getMonsterConfigId(), defeat.getCount());
                }
            });
            step("试炼志首领首通", player, battleId, () -> markNormalBossTrialIfCleared(player, settlement));
            step("近期战果", player, battleId, () -> recordBattleResult(player, settlement, gold, actualGained, actualConsumed));
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
        if (settlement.getPetsCount() == 0 && settlement.getCapturedPetTableIdsCount() == 0) {
            return;
        }
        pets.applyBattleSettlement(player, settlement.getPetsList());
        world.sendTo(player, push(notifyPetListChanged, PetListChangedS2C.newBuilder().setPets(pets.buildList(player)).build()));
    }

    /** h：消耗（跳过 0；非 battle_usable 拒扣；数量夹到 uint32；按持有夹紧、格子号升序；之后只合并不重排）。 */
    private Map<Integer, Long> applyConsumed(ScenePlayer player, BattleSettlementData settlement) {
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
            return Map.of();
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
        return removed;
    }

    /** 结算应用同帧写入本人近期战斗结果（确认落盘后才对 371/372 可见），并按游戏日上限累计装备材料自选额度。 */
    private void recordBattleResult(ScenePlayer player, BattleSettlementData settlement, long goldCredited,
                                    Map<Integer, Long> itemsGained, Map<Integer, Long> itemsConsumed) {
        long nowMs = clock.epochMillis();
        int materialCreditAdded = com.game.scene.equip.EquipmentCraftService.creditBattleMaterialReward(player, settlement, nowMs);
        var seasonCredit = seasonService == null
                ? com.game.scene.season.SeasonService.BattleCreditOutcome.NONE
                : seasonService.creditBattlePoints(player, settlement, nowMs);
        int recordedSeasonId = settlement.getSeasonId() != 0 ? settlement.getSeasonId() : seasonCredit.seasonId();
        com.game.player.store.state.BattleResultEntryState.Builder entry = com.game.player.store.state.BattleResultEntryState.newBuilder()
                .setBattleId(settlement.getBattleId())
                .setOutcome(settlement.getOutcomeValue())
                .setTeamIndex(settlement.getPlayerTeamIndex())
                .setIsDead(settlement.getIsDead())
                .setFled(settlement.getFled())
                .setTotalRounds(settlement.getTotalRounds())
                .setFinishedAtMs(settlement.getFinishedAtMs())
                .setSettledAtMs(nowMs)
                .setMatchMode(settlement.getMatchMode())
                .setBattleConfigId(settlement.getBattleConfigId())
                .setMaterialCreditUnitsAdded(materialCreditAdded)
                .setSeasonId(recordedSeasonId);
        if (goldCredited > 0) {
            entry.addCurrencies(com.game.player.store.state.BattleResultCurrencyState.newBuilder()
                    .setCurrencyType(Wallet.GOLD)
                    .setAmount(goldCredited));
        }
        java.util.List<com.game.player.store.state.BattleResultItemState> gainedLines = new java.util.ArrayList<>();
        boolean truncGained = com.game.scene.player.PlayerBattleResults.fillItemLines(itemsGained, gainedLines);
        java.util.List<com.game.player.store.state.BattleResultItemState> consumedLines = new java.util.ArrayList<>();
        boolean truncConsumed = com.game.scene.player.PlayerBattleResults.fillItemLines(itemsConsumed, consumedLines);
        entry.addAllItemsGained(gainedLines)
                .addAllItemsConsumed(consumedLines)
                .setLinesTruncated(truncGained || truncConsumed);
        player.battleResults().record(entry);
    }

    /** 任意普通难度首领试炼（boss_trial_kind 1..4 且 difficulty == 1）获胜未逃时持久标记解锁困难首领试炼。 */
    private void markNormalBossTrialIfCleared(ScenePlayer player, BattleSettlementData settlement) {
        if (settlement.getFled() || settlement.getBattleConfigId() == 0) {
            return;
        }
        var dungeon = tables.dungeon(settlement.getBattleConfigId());
        if (dungeon == null) {
            return;
        }
        int kind = dungeon.getBossTrialKind();
        if (kind < 1 || kind > 4 || dungeon.getDifficulty() != 1) {
            return;
        }
        boolean won = (settlement.getPlayerTeamIndex() == 0
                && settlement.getOutcome() == com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                || (settlement.getPlayerTeamIndex() == 1
                && settlement.getOutcome() == com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);
        if (won) {
            player.trialJournal().markNormalBossTrialCleared();
        }
    }

    /** i：掉落（数量夹到 uint32 后按配置累加；主包失败时没进去的部分改投临时格；两处都放不下丢失并 ERROR，不让整笔失败）。 */
    private Map<Integer, Long> applyGained(ScenePlayer player, BattleSettlementData settlement) {
        long battleId = settlement.getBattleId();
        Map<Integer, Long> counts = new TreeMap<>(Integer::compareUnsigned);
        for (BattleItemEntry item : settlement.getItemsGainedList()) {
            if (item.getItemTableId() == 0 || item.getCount() == 0) {
                continue;
            }
            counts.merge(item.getItemTableId(), clampCount(item.getCount()), (a, b) -> Math.min(a + b, UINT32_MAX));
        }
        if (counts.isEmpty()) {
            return Map.of();
        }
        Bag inventory = player.bags().bag(BagType.INVENTORY);
        Map<Integer, Long> before = new TreeMap<>(Integer::compareUnsigned);
        counts.keySet().forEach(config -> before.put(config, inventory.total(config)));
        String extra = extra(battleId);
        Bag.AddResult added = bags.addItems(player, BagType.INVENTORY, counts, AssetAudit.Reason.ITEM_AWARD, battleId, extra);
        if (added.ok()) {
            return counts;
        }
        Map<Integer, Long> credited = new TreeMap<>(Integer::compareUnsigned);
        Map<Integer, Long> missing = new TreeMap<>(Integer::compareUnsigned);
        counts.forEach((config, wanted) -> {
            long got = Math.max(0L, inventory.total(config) - before.get(config));
            if (got > 0) {
                credited.put(config, got);
            }
            if (got < wanted) {
                missing.put(config, wanted - got);
            }
        });
        if (missing.isEmpty()) {
            return credited;
        }
        Bag.AddResult overflow = bags.addItems(player, BagType.TEMPORARY, missing, AssetAudit.Reason.ITEM_AWARD, battleId, extra);
        if (overflow.ok()) {
            metrics.item(ItemKind.DROP_OVERFLOW);
            log.info("战斗掉落主包放不下，改投临时格 player={} battle_id={} items={} 主包 tip={}", Long.toUnsignedString(player.playerId()),
                    Long.toUnsignedString(battleId), missing, added.tip());
            missing.forEach((config, cnt) -> credited.merge(config, cnt, Long::sum));
            return credited;
        }
        metrics.item(ItemKind.DROP_LOST);
        log.error("战斗掉落主包与临时格都放不下（或被获取封禁），道具丢失 player={} battle_id={} items={} 主包 tip={} 临时格 tip={}",
                Long.toUnsignedString(player.playerId()), Long.toUnsignedString(battleId), missing, added.tip(), overflow.tip());
        return credited;
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
