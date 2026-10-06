package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattleSettlementData;
import com.game.proto.MessageContent;
import com.game.proto.PetInfo;
import com.game.proto.PetListChangedS2C;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.BattleSettlementService.Outcome;
import com.game.scene.battle.BattleSettlementService.Result;
import com.game.scene.gainblock.GlobalGainBlocks;
import com.game.scene.metrics.SceneBattleMetrics.SettlementResult;
import com.game.scene.player.Bag;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerAttributes.Derived;
import com.game.scene.player.PlayerMissions;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.player.Wallet;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SwitchPhase;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 结算应用各步的<b>取值</b>（scene-battle-spec §7.11 的 a–l 步、§5.3 宝宝回写；§13.2 {@code SettlementApplyValuesTest} 一行逐条；
 * 基线 {@code ApplySettlementToEntity}，{@code pb.cpp:1613-1809}）：气血法力的夹取与复活、宝宝回写与 184、击杀事实、道具数量夹紧、
 * 金币越界、掉落改投临时格 / 丢失、抽取次序、三类流水、账本淘汰、某一步出错时其余照做、a–d 返回不登记账本、可应用闸是各内部闸的超集、
 * 战斗冻结还挂着时照常应用。「恰好一次」（重复、重登、落盘销账）在 {@code SettlementApplyTest}。
 *
 * <p>全部用正式配表：新号（职业 3、1 级）气血上限 550 / 法力上限 840；灵狐（种类 1，1 级、资质取基准）442 / 830，石灵（种类 2）742 / 350；
 * 物品 10 / 11 可叠加 999 且是战斗道具，3 不可叠加；人物背包 100 格、临时格 200 格；任务 7 = 击杀 1 号怪 8 次、完成后自动接任务 8（击杀 1 号怪 9 次）。
 * 配表同步后这些值变了，下面的断言会指出要重看。
 */
class SettlementApplyValuesTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 701;
    private static final long FULL_HEALTH = 550;
    private static final long FULL_MANA = 840;
    private static final long UINT32_MAX = 0xFFFF_FFFFL;
    private static final int POTION = 10;
    private static final int DROP = 11;
    private static final int UNSTACKABLE = 3;
    private static final long FOX = 9_001;
    private static final long GOLEM = 9_002;
    private static final String EXTRA = "{\"source\":\"battle\",\"battle_id\":701}";

    private final BattleFixture f = new BattleFixture();
    private final ch.qos.logback.classic.Logger applyLog =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BattleSettlementService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        applyLog.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        applyLog.detachAppender(logs);
    }

    // ------------------------------------------------------------------ 造数据

    /** 只带气血法力的结算（金币 0）；其余在返回的 builder 上加。 */
    private static BattleSettlementData.Builder vitals(long battleId, long health, long mana) {
        return BattleSettlementData.newBuilder().setPlayerId(PLAYER).setBattleId(battleId).setHealth(health).setMana(mana);
    }

    /** 满血满蓝（给大值，夹到上限）、不带金币的结算。 */
    private static BattleSettlementData.Builder plain(long battleId) {
        return vitals(battleId, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * 除金币外每一步都会改状态的结算（配 {@link #withPets()}、背包里 5 瓶 {@link #POTION}、接了任务 7 的玩家）：气血 80 / 法力 20、灵狐回写成 300 / 200、
     * 消耗 2 瓶药、掉落 3 个、击杀 2 只 1 号怪。金币由用例自己加。
     */
    private static BattleSettlementData.Builder loaded(long battleId) {
        return vitals(battleId, 80, 20).addPets(pet(FOX, 300, 200, false))
                .addItemsConsumed(item(POTION, 2)).addItemsGained(item(DROP, 3)).addDefeatedMonsters(kill(1, 2));
    }

    private static BattleItemEntry item(int configId, long count) {
        return BattleItemEntry.newBuilder().setItemTableId(configId).setCount(count).build();
    }

    private static BattleMonsterDefeat kill(int monster, int count) {
        return BattleMonsterDefeat.newBuilder().setMonsterConfigId(monster).setCount(count).build();
    }

    private static BattlePetSettlementData pet(long petId, long health, long mana, boolean dead) {
        return BattlePetSettlementData.newBuilder().setPetId(petId).setHealth(health).setMana(mana).setIsDead(dead).build();
    }

    private static BagItemState stack(long guid, int configId, int size, int pos, BagType bag) {
        return BagItemState.newBuilder().setItemUuid(guid).setConfigId(configId).setStackSize(size).setPos(pos)
                .setBagType(bag.code()).setAcquireSeq(guid).build();
    }

    private static PlayerState bagOf(BagItemState... stacks) {
        return PlayerState.newBuilder().setBag(BagState.newBuilder().addAllItems(List.of(stacks))).build();
    }

    /** 人物背包 100 格全满：0–98 格各一件不可叠加的 3 号物品，99 格是 990 个 11 号（还差 9 个到堆叠上限）。 */
    private static BagState.Builder fullInventory() {
        BagState.Builder bag = BagState.newBuilder();
        for (int slot = 0; slot < 99; slot++) {
            bag.addItems(stack(1_000 + slot, UNSTACKABLE, 1, slot, BagType.INVENTORY));
        }
        return bag.addItems(stack(1_099, DROP, 990, 99, BagType.INVENTORY));
    }

    /** 两只宝宝：灵狐（出战，100 / 50）、石灵（200 / 60）。 */
    private static PlayerState.Builder withPets() {
        return PlayerState.newBuilder().setPets(PetState.newBuilder().setActivePetId(FOX)
                .addPets(PetEntry.newBuilder().setPetId(FOX).setPetTableId(1).setLevel(1).setHealth(100).setMana(50))
                .addPets(PetEntry.newBuilder().setPetId(GOLEM).setPetTableId(2).setLevel(1).setHealth(200).setMana(60)));
    }

    private Result apply(ScenePlayer player, BattleSettlementData settlement) {
        return f.settlements.apply(f.world, player, settlement);
    }

    private SceneBattleReply delivered(BattleSettlementData settlement) {
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        f.drain();
        assertThat(reply).isCompleted();
        return BattleFixture.done(reply);
    }

    private static Bag inventory(ScenePlayer player) {
        return player.bags().bag(BagType.INVENTORY);
    }

    private static Bag temporary(ScenePlayer player) {
        return player.bags().bag(BagType.TEMPORARY);
    }

    private static long progress(ScenePlayer player, int missionId) {
        PlayerMissions.Active active = player.missions().active(missionId);
        assertThat(active).as("任务 %s 应在进行中", missionId).isNotNull();
        return active.progress(0);
    }

    private List<String> logged(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static RecordingAssetAudit.Item destroyed(long guid, int configId, long quantity) {
        return new RecordingAssetAudit.Item(false, PLAYER, guid, configId, quantity, Reason.ITEM_DESTROY, X, EXTRA);
    }

    // ------------------------------------------------------------------ e 步：气血法力

    @Test
    void 气血法力夹到派生上限_不超过上限的原样_超出int64的按最大值夹() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(player.attributes().derived().maxHealth()).isEqualTo(FULL_HEALTH);
        assertThat(player.attributes().derived().maxMana()).isEqualTo(FULL_MANA);

        assertThat(apply(player, vitals(1, 300, 100).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).isEqualTo(300);
        assertThat(player.attributes().mana()).isEqualTo(100);

        assertThat(apply(player, vitals(2, 10_000, 20_000).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).as("夹到上限").isEqualTo(FULL_HEALTH);
        assertThat(player.attributes().mana()).isEqualTo(FULL_MANA);

        assertThat(apply(player, vitals(3, FULL_HEALTH, FULL_MANA).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).as("恰好等于上限").isEqualTo(FULL_HEALTH);

        // uint64 高位为 1（有符号看是负数）：按无符号的大值处理，夹到上限，而不是当成负数 / 0
        assertThat(apply(player, vitals(4, -1L, Long.MIN_VALUE).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);
        assertThat(player.attributes().mana()).isEqualTo(FULL_MANA);
        assertThat(f.messageIds(player)).as("不推任何属性消息（170 / 66）").isEmpty();
    }

    /** 法力上限为 0（缺配）时不夹：夹了就把法力抹成 0。阵亡回满时这一项同样不动。 */
    @Test
    void 法力上限为0时不夹_取结算值_阵亡回满时法力也不动() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        player.attributes().setDerived(new Derived(FULL_HEALTH, 0, 50, 40, 276, 60));

        assertThat(apply(player, vitals(1, 300, 777_777).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).isEqualTo(300);
        assertThat(player.attributes().mana()).as("上限 0：不夹").isEqualTo(777_777);

        assertThat(apply(player, vitals(2, 0, 123).setIsDead(true).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).as("阵亡回满气血").isEqualTo(FULL_HEALTH);
        assertThat(player.attributes().mana()).as("法力上限为 0 的那一项不动").isEqualTo(123);
    }

    /**
     * 气血上限为 0（缺配）时同样不夹：同基线 {@code pb.cpp:1693-1697}（{@code max_health > 0} 才夹）。夹了就是 {@code min(残血, 0) = 0}——
     * 带着残血回来的玩家被抹成 0 血、接着按阵亡处理。法力上限正常，照常夹。
     */
    @Test
    void 气血上限为0时不夹_残血取结算值_不被抹成0血当成阵亡() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        player.attributes().setDerived(new Derived(0, FULL_MANA, 50, 40, 276, 60));

        assertThat(apply(player, vitals(1, 300, 100).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).as("上限 0：不夹").isEqualTo(300);
        assertThat(player.attributes().mana()).isEqualTo(100);

        assertThat(apply(player, vitals(2, 12_345, 20_000).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).isEqualTo(12_345);
        assertThat(player.attributes().mana()).as("法力上限正常：照常夹").isEqualTo(FULL_MANA);
    }

    @Test
    void 阵亡且气血为0_气血法力都回满_气血为0即使没标阵亡也回满() {
        ScenePlayer player = f.enter(SESSION, PLAYER);

        assertThat(apply(player, vitals(1, 0, 5).setIsDead(true).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);
        assertThat(player.attributes().mana()).isEqualTo(FULL_MANA);

        assertThat(apply(player, vitals(2, 30, 5).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).isEqualTo(30);
        assertThat(player.attributes().mana()).isEqualTo(5);

        assertThat(apply(player, vitals(3, 0, 5).build())).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).as("气血 0 = 阵亡，不看 is_dead").isEqualTo(FULL_HEALTH);
        assertThat(player.attributes().mana()).isEqualTo(FULL_MANA);
    }

    /** 同基线：{@code is_dead} 只是触发「看看要不要复活」，复活规则自己只在气血为 0 时回满——标了阵亡但带着残血回来的不复活。 */
    @Test
    void 标了阵亡但气血大于0_不复活_残血残蓝原样() {
        ScenePlayer player = f.enter(SESSION, PLAYER);

        assertThat(apply(player, vitals(X, 7, 9).setIsDead(true).build())).isEqualTo(Result.APPLIED);

        assertThat(player.attributes().health()).isEqualTo(7);
        assertThat(player.attributes().mana()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ f 步：宝宝

    @Test
    void 宝宝气血法力按现算上限夹_不超过的原样_没出战的宝宝照样回写() {
        ScenePlayer player = f.enter(SESSION, PLAYER, withPets().build());
        Pet fox = player.pets().find(FOX);
        Pet golem = player.pets().find(GOLEM);

        Result result = apply(player, plain(X).addPets(pet(FOX, 9_999, 9_999, false)).addPets(pet(GOLEM, 5, 3, false)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(fox.health()).as("灵狐气血上限 442").isEqualTo(442);
        assertThat(fox.mana()).as("灵狐法力上限 830").isEqualTo(830);
        assertThat(golem.health()).isEqualTo(5);
        assertThat(golem.mana()).isEqualTo(3);
    }

    /** 阵亡（标了 is_dead，或夹后气血为 0）的宝宝回满——不回满的话死宝宝会被战斗快照永远挡在门外。 */
    @Test
    void 宝宝阵亡回满_标了阵亡的带着残血也回满_气血为0没标阵亡也回满() {
        ScenePlayer player = f.enter(SESSION, PLAYER, withPets().build());
        Pet fox = player.pets().find(FOX);
        Pet golem = player.pets().find(GOLEM);

        Result result = apply(player, plain(X).addPets(pet(FOX, 50, 10, true)).addPets(pet(GOLEM, 0, 7, false)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(fox.health()).as("is_dead：气血回到上限").isEqualTo(442);
        assertThat(fox.mana()).as("法力也回到上限").isEqualTo(830);
        assertThat(golem.health()).as("气血 0：同样回满").isEqualTo(742);
        assertThat(golem.mana()).isEqualTo(350);
        assertThat(f.pets.buildBattleSnapshot(player)).as("回满后下一场能带进战斗").hasSize(1);
    }

    @Test
    void 结算里不认识的宝宝_忽略_其余宝宝照写_整笔照常() {
        ScenePlayer player = f.enter(SESSION, PLAYER, withPets().build());

        Result result = apply(player, plain(X).setGoldGain(12)
                .addPets(pet(7_777, 1, 1, false)).addPets(pet(FOX, 300, 200, false)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(player.pets().find(7_777)).isNull();
        assertThat(player.pets().pets()).as("没有凭空多出宝宝").hasSize(2);
        assertThat(player.pets().find(FOX).health()).isEqualTo(300);
        assertThat(player.pets().find(FOX).mana()).isEqualTo(200);
        assertThat(player.pets().find(GOLEM).health()).as("没有条目的宝宝不动").isEqualTo(200);
        assertThat(f.gold(player)).isEqualTo(12);
        assertThat(f.pushes(player, f.petListChangedId)).as("有宝宝条目就推一次 184").hasSize(1);
    }

    /** 客户端可见的线上顺序：184（宝宝列表，一次，内容是回写之后的值）→ 150；之前不推别的。 */
    @Test
    void 有宝宝条目时184只推一次_内容是回写后的值_先于150() throws InvalidProtocolBufferException {
        ScenePlayer player = f.enter(SESSION, PLAYER, withPets().build());
        f.fighting(PLAYER, X);
        f.sink.clear();
        BattleSettlementData settlement = plain(X).setGoldGain(12)
                .addPets(pet(FOX, 300, 200, false)).addPets(pet(GOLEM, 0, 0, true)).addPets(pet(7_777, 1, 1, false)).build();

        SceneBattleReply reply = delivered(settlement);

        assertThat(reply.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.messageIds(player)).as("三个宝宝条目只推一条 184，且在 150 之前").containsExactly(f.petListChangedId, f.battleEndId);
        MessageContent changed = f.pushes(player, f.petListChangedId).get(0);
        List<PetInfo> pets = PetListChangedS2C.parseFrom(changed.getSerializedMessage()).getPets().getPetsList();
        assertThat(pets).extracting(PetInfo::getPetId).containsExactly(FOX, GOLEM);
        assertThat(pets.get(0).getDerived().getHealth()).isEqualTo(300);
        assertThat(pets.get(0).getDerived().getMana()).isEqualTo(200);
        assertThat(pets.get(1).getDerived().getHealth()).as("阵亡的石灵已回满").isEqualTo(742);
        assertThat(pets.get(1).getDerived().getMana()).isEqualTo(350);
        BattleEndS2C end = f.battleEnds(player).get(0);
        assertThat(end.getBattleId()).isEqualTo(X);
        assertThat(end.getOutcome()).isEqualTo(settlement.getOutcome());
        assertThat(end.getSettlement()).isEqualTo(settlement);
    }

    @Test
    void 没有宝宝条目_不推184_只有150() {
        ScenePlayer player = f.enter(SESSION, PLAYER, withPets().build());
        f.fighting(PLAYER, X);
        f.sink.clear();

        delivered(plain(X).setGoldGain(12).build());

        assertThat(f.messageIds(player)).containsExactly(f.battleEndId);
        assertThat(player.pets().find(FOX).health()).isEqualTo(100);
    }

    // ------------------------------------------------------------------ g 步：经验

    @Test
    void 经验只记日志与计数_不改任何状态() {
        ScenePlayer player = f.enter(SESSION, PLAYER);

        assertThat(apply(player, plain(1).setExpGain(500).build())).isEqualTo(Result.APPLIED);
        assertThat(apply(player, plain(2).build())).isEqualTo(Result.APPLIED);

        assertThat(f.count("xm.scene.battle.exp.ignored")).as("只有带经验的那一份计数").isEqualTo(1);
        assertThat(player.level()).isEqualTo(1);
        assertThat(logged(Level.INFO)).anyMatch(m -> m.contains("没有经验系统") && m.contains("exp=500"));
    }

    // ------------------------------------------------------------------ j 步：击杀

    /**
     * 每只怪一条事实（amount = 1）。任务 7 要 8 只 1 号怪、完成后自动接任务 8：一份带 10 只的结算，逐条事实时第 8 条完成任务 7、
     * 第 9、10 条推进任务 8 到 2；若合成一条 amount = 10 的事实，任务 8 的进度会是 0。
     */
    @Test
    void N个击杀N条事实_逐条推进_完成后多出的事实推进后续任务() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(f.missions.accept(player, 0, 7)).isZero();

        assertThat(apply(player, plain(1).addDefeatedMonsters(kill(1, 3)).build())).isEqualTo(Result.APPLIED);
        assertThat(progress(player, 7)).isEqualTo(3);

        assertThat(apply(player, plain(2).addDefeatedMonsters(kill(1, 10)).build())).isEqualTo(Result.APPLIED);

        assertThat(player.missions().isComplete(7)).isTrue();
        assertThat(progress(player, 8)).as("10 条事实：5 条补满任务 7，剩 5 条推进任务 8").isEqualTo(5);
    }

    @Test
    void 多条击杀条目各自计数_0号怪跳过且不打断后面的_别的怪不串() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(f.missions.accept(player, 0, 7)).isZero();

        Result result = apply(player, plain(X).addDefeatedMonsters(kill(0, 5)).addDefeatedMonsters(kill(1, 2))
                .addDefeatedMonsters(kill(2, 4)).addDefeatedMonsters(kill(1, 1)).addDefeatedMonsters(kill(1, 0)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(progress(player, 7)).as("1 号怪 2 + 1 只；0 号、2 号、数量 0 的都不算").isEqualTo(3);
    }

    // ------------------------------------------------------------------ d 步：金币

    @Test
    void 金币超出int64_延后_零副作用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.locks.clearCalls();
        f.sink.clear();

        for (long gold : new long[] {-1L, Long.MIN_VALUE}) {
            Result result = apply(player, vitals(X, 80, 20).setGoldGain(gold).addItemsGained(item(DROP, 1)).build());

            assertThat(result).as("gold=%s", Long.toUnsignedString(gold))
                    .isEqualTo(new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_CURRENCY));
        }
        SceneBattleReply reply = delivered(vitals(X, 80, 20).setGoldGain(-1L).build());

        assertThat(reply.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_DEFERRED);
        assertThat(f.gold(player)).isZero();
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);
        assertThat(inventory(player).total(DROP)).isZero();
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(player.inBattle()).as("延后：冻结保留").isTrue();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(f.repo.pendingProgress()).isZero();
        assertThat(logged(Level.ERROR)).anyMatch(m -> m.contains("金币超出 int64") && m.contains("gold=18446744073709551615"));
    }

    @Test
    void 金币入账会溢出钱包_延后_余额不变_刚好不溢出的照常入账() {
        PlayerState rich = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(Long.MAX_VALUE - 12)).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, rich);

        assertThat(apply(player, vitals(1, 80, 20).setGoldGain(13).build()))
                .isEqualTo(new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_CURRENCY));
        assertThat(f.gold(player)).isEqualTo(Long.MAX_VALUE - 12);
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);
        assertThat(player.battleLedger().has(1)).isFalse();

        assertThat(apply(player, vitals(1, 80, 20).setGoldGain(12).build())).isEqualTo(Result.APPLIED);
        assertThat(f.gold(player)).isEqualTo(Long.MAX_VALUE);
        assertThat(player.attributes().health()).isEqualTo(80);
    }

    @Test
    void 金币被全服封禁_延后_解封后照常() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.currency.applyGlobalBlocks(new GlobalGainBlocks(Set.of(Wallet.GOLD), Set.of()));

        assertThat(apply(player, vitals(X, 80, 20).setGoldGain(12).build()))
                .isEqualTo(new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_CURRENCY));
        assertThat(f.gold(player)).isZero();
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);

        f.currency.applyGlobalBlocks(GlobalGainBlocks.NONE);
        assertThat(apply(player, vitals(X, 80, 20).setGoldGain(12).build())).isEqualTo(Result.APPLIED);
        assertThat(f.gold(player)).isEqualTo(12);
    }

    /** 金币为 0 的结算不碰钱包、不记金币流水，但 d 步算走过了：其余照做、账本照记。 */
    @Test
    void 金币为0_不记金币流水_其余照做_账本照记() {
        ScenePlayer player = f.enter(SESSION, PLAYER);

        assertThat(apply(player, vitals(X, 80, 20).build())).isEqualTo(Result.APPLIED);

        assertThat(f.audit.currencies).isEmpty();
        assertThat(player.attributes().health()).isEqualTo(80);
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(f.repo.pendingProgress()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ h 步：消耗

    /** 抽取按格子号升序（D22；基线是 entt 视图序、没有规定），每个被抽到的实例一条销毁流水；之后只合并零头、不重排。 */
    @Test
    void 消耗按格子号升序抽取_每个被抽实例一条销毁流水_之后只合并不重排() {
        // 入包先后是 501 → 502 → 503，格子号却是 5 / 2 / 9：先抽 2 号格的 502
        ScenePlayer player = f.enter(SESSION, PLAYER, bagOf(stack(501, POTION, 3, 5, BagType.INVENTORY),
                stack(502, POTION, 4, 2, BagType.INVENTORY), stack(503, POTION, 6, 9, BagType.INVENTORY)));

        assertThat(apply(player, plain(X).addItemsConsumed(item(POTION, 5)).build())).isEqualTo(Result.APPLIED);

        assertThat(f.audit.items).containsExactly(
                destroyed(502, POTION, 4),
                destroyed(501, POTION, 1),
                // 合并零头：503 的 6 个并进 501（入包更早），503 变空被回收——数量 0、没有关联号（没有资产损失）
                new RecordingAssetAudit.Item(false, PLAYER, 503, POTION, 0, Reason.ITEM_DESTROY, 0, ""));
        assertThat(inventory(player).total(POTION)).isEqualTo(8);
        assertThat(inventory(player).items()).singleElement().satisfies(left -> {
            assertThat(left.guid()).isEqualTo(501);
            assertThat(left.size()).isEqualTo(8);
            assertThat(left.slot()).as("只合并、不重排：留在原来的 5 号格").isEqualTo(5);
        });
        assertThat(f.count("xm.scene.battle.items", "kind", "consume_clamped")).isZero();
    }

    @Test
    void 消耗同一配置的多条按配置累加_id或数量为0的跳过() {
        ScenePlayer player = f.enter(SESSION, PLAYER, bagOf(stack(501, POTION, 9, 0, BagType.INVENTORY)));

        Result result = apply(player, plain(X).addItemsConsumed(item(POTION, 2)).addItemsConsumed(item(0, 5))
                .addItemsConsumed(item(POTION, 0)).addItemsConsumed(item(POTION, 1)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(inventory(player).total(POTION)).isEqualTo(6);
        assertThat(f.audit.items).containsExactly(destroyed(501, POTION, 3));
        assertThat(f.count("xm.scene.battle.items", "kind", "consume_rejected")).as("0 号不是「非战斗道具」，直接跳过").isZero();
    }

    /**
     * 单条数量夹到 {@code 0xFFFFFFFF}。uint64 的最大值有符号看是 −1：不夹的话抽取函数把它当成「数量 ≤ 0」一件都不扣；
     * 夹了之后按持有扣光，并记一次夹紧（请求 4294967295、实扣 5）。
     */
    @Test
    void 消耗数量夹到uint32上限_超大值不被当成负数_按持有扣光() {
        ScenePlayer player = f.enter(SESSION, PLAYER, bagOf(stack(501, POTION, 5, 0, BagType.INVENTORY)));

        assertThat(apply(player, plain(X).addItemsConsumed(item(POTION, -1L)).build())).isEqualTo(Result.APPLIED);

        assertThat(inventory(player).total(POTION)).isZero();
        assertThat(f.audit.items).containsExactly(destroyed(501, POTION, 5));
        assertThat(f.count("xm.scene.battle.items", "kind", "consume_clamped")).isEqualTo(1);
        assertThat(logged(Level.WARN)).anyMatch(m -> m.contains("按持有夹紧") && m.contains("请求=" + UINT32_MAX) && m.contains("实扣=5"));
    }

    @Test
    void 同一配置累加后也不超过uint32上限() {
        ScenePlayer player = f.enter(SESSION, PLAYER, bagOf(stack(501, POTION, 5, 0, BagType.INVENTORY)));

        Result result = apply(player,
                plain(X).addItemsConsumed(item(POTION, UINT32_MAX)).addItemsConsumed(item(POTION, UINT32_MAX)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(inventory(player).total(POTION)).isZero();
        assertThat(logged(Level.WARN)).anyMatch(m -> m.contains("请求=" + UINT32_MAX) && m.contains("实扣=5"));
    }

    // ------------------------------------------------------------------ i 步：掉落

    @Test
    void 掉落进人物背包_同一配置累加_id或数量为0的跳过_流水带关联号与来源() {
        ScenePlayer player = f.enter(SESSION, PLAYER);

        Result result = apply(player, plain(X).addItemsGained(item(DROP, 2)).addItemsGained(item(0, 9))
                .addItemsGained(item(POTION, 0)).addItemsGained(item(DROP, 3)).addItemsGained(item(POTION, 1)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(inventory(player).total(DROP)).isEqualTo(5);
        assertThat(inventory(player).total(POTION)).isEqualTo(1);
        assertThat(temporary(player).itemCount()).as("主包放得下，不进临时格").isZero();
        BagItem potion = inventory(player).items().stream().filter(i -> i.configId() == POTION).findFirst().orElseThrow();
        BagItem drop = inventory(player).items().stream().filter(i -> i.configId() == DROP).findFirst().orElseThrow();
        assertThat(f.audit.items).as("每个配置一条入包流水，按配置号升序").containsExactly(
                new RecordingAssetAudit.Item(true, PLAYER, potion.guid(), POTION, 1, Reason.ITEM_AWARD, X, EXTRA),
                new RecordingAssetAudit.Item(true, PLAYER, drop.guid(), DROP, 5, Reason.ITEM_AWARD, X, EXTRA));
        assertThat(f.count("xm.scene.battle.items", "kind", "drop_overflow")).isZero();
    }

    /**
     * 主包放不下 → 改投临时格，只补「没进去的部分」（现有 − 入包前）。Java 的批量入包整批原子（同基线先 Reserve 再逐个 Add），
     * 所以主包失败时一件都没进：临时格收到的恰好是全部掉落，主包原封不动，没有哪一件两边都有。
     */
    @Test
    void 主包满_掉落改投临时格_只补没进主包的部分_主包不动() {
        ScenePlayer player = f.enter(SESSION, PLAYER, PlayerState.newBuilder().setBag(fullInventory()).build());
        assertThat(inventory(player).freeCells()).isZero();

        // 11 号在主包还能再叠 9 个，但 20 个要多开一格、5 个 10 号也要一格：整批放不下
        Result result = apply(player, plain(X).addItemsGained(item(DROP, 20)).addItemsGained(item(POTION, 5)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(inventory(player).total(DROP)).as("主包一件都没进").isEqualTo(990);
        assertThat(inventory(player).total(POTION)).isZero();
        assertThat(inventory(player).itemCount()).isEqualTo(100);
        assertThat(temporary(player).total(DROP)).as("没进去的部分 = 全部").isEqualTo(20);
        assertThat(temporary(player).total(POTION)).isEqualTo(5);
        assertThat(f.count("xm.scene.battle.items", "kind", "drop_overflow")).isEqualTo(1);
        assertThat(f.count("xm.scene.battle.items", "kind", "drop_lost")).isZero();
        assertThat(f.audit.items).as("临时格的入包流水同样带关联号与来源").allSatisfy(entry -> {
            assertThat(entry.gained()).isTrue();
            assertThat(entry.reason()).isEqualTo(Reason.ITEM_AWARD);
            assertThat(entry.correlationId()).isEqualTo(X);
            assertThat(entry.extra()).isEqualTo(EXTRA);
        }).extracting(RecordingAssetAudit.Item::configId).containsExactly(POTION, DROP);
    }

    /** 主包虽满，掉落能全部叠进已有的未满堆时不算放不下。 */
    @Test
    void 主包格子满但能叠进已有的堆_不进临时格() {
        ScenePlayer player = f.enter(SESSION, PLAYER, PlayerState.newBuilder().setBag(fullInventory()).build());

        assertThat(apply(player, plain(X).addItemsGained(item(DROP, 9)).build())).isEqualTo(Result.APPLIED);

        assertThat(inventory(player).total(DROP)).isEqualTo(999);
        assertThat(temporary(player).itemCount()).isZero();
        assertThat(f.count("xm.scene.battle.items", "kind", "drop_overflow")).isZero();
    }

    /** 临时格自己满了会淘汰最早入包的实例腾位（每个被淘汰的实例一条销毁流水）；仍算改投成功。 */
    @Test
    void 主包满且临时格也满_临时格淘汰最早的实例腾位() {
        BagState.Builder bag = fullInventory();
        for (int slot = 0; slot < 200; slot++) {
            bag.addItems(stack(5_000 + slot, UNSTACKABLE, 1, slot, BagType.TEMPORARY));
        }
        ScenePlayer player = f.enter(SESSION, PLAYER, PlayerState.newBuilder().setBag(bag).build());
        assertThat(temporary(player).freeCells()).isZero();

        assertThat(apply(player, plain(X).addItemsGained(item(POTION, 5)).build())).isEqualTo(Result.APPLIED);

        assertThat(temporary(player).total(POTION)).isEqualTo(5);
        assertThat(temporary(player).item(5_000)).as("最早入包的那一件被淘汰").isNull();
        assertThat(temporary(player).item(5_001)).isNotNull();
        assertThat(temporary(player).itemCount()).isEqualTo(200);
        assertThat(f.audit.items).extracting(RecordingAssetAudit.Item::gained).containsExactly(false, true);
        assertThat(f.audit.items.get(0).itemUuid()).isEqualTo(5_000);
        assertThat(f.count("xm.scene.battle.items", "kind", "drop_overflow")).isEqualTo(1);
    }

    /**
     * 两处都放不下（主包满；掉落要 201 格、临时格全淘汰也只有 200 格）→ 道具丢失、记 ERROR 与 {@code drop_lost}，但<b>不让整笔失败</b>
     * （金币已入账）：击杀照推、账本照记、150 照推、照常销账。
     */
    @Test
    void 主包与临时格都放不下_掉落丢失_整笔不失败_账本照记_150照推() {
        ScenePlayer player = f.enter(SESSION, PLAYER, PlayerState.newBuilder().setBag(fullInventory()).build());
        assertThat(f.missions.accept(player, 0, 7)).isZero();
        f.fighting(PLAYER, X);
        f.sink.clear();
        BattleSettlementData settlement = vitals(X, 80, 20).setGoldGain(12).addItemsGained(item(UNSTACKABLE, 201))
                .addDefeatedMonsters(kill(1, 2)).build();
        f.store(settlement);

        SceneBattleReply reply = delivered(settlement);

        assertThat(reply.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(reply.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.count("xm.scene.battle.items", "kind", "drop_lost")).isEqualTo(1);
        assertThat(f.count("xm.scene.battle.items", "kind", "drop_overflow")).isZero();
        assertThat(inventory(player).total(UNSTACKABLE)).isEqualTo(99);
        assertThat(temporary(player).itemCount()).as("临时格也没进（整批原子）").isZero();
        assertThat(logged(Level.ERROR)).anyMatch(m -> m.contains("道具丢失") && m.contains("{3=201}"));
        assertThat(f.gold(player)).isEqualTo(12);
        assertThat(player.attributes().health()).isEqualTo(80);
        assertThat(progress(player, 7)).as("掉落之后的击杀照做").isEqualTo(2);
        assertThat(player.battleLedger().has(X)).as("账本照记").isTrue();
        assertThat(f.battleEnds(player)).as("150 照推").hasSize(1);
        assertThat(player.inBattle()).isFalse();

        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("照常销账：丢失不重投").isFalse();
    }

    /** 全服封禁获取的物品走同一条失败路径：主包、临时格都被闸拒 → 丢失，整笔照常。 */
    @Test
    void 掉落的物品被全服封禁_两处都拒_按丢失处理_其余照常() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.bags.applyGlobalBlocks(new GlobalGainBlocks(Set.of(), Set.of(DROP)));

        Result result = apply(player, vitals(X, 80, 20).setGoldGain(12).addItemsGained(item(DROP, 3)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(inventory(player).total(DROP)).isZero();
        assertThat(temporary(player).total(DROP)).isZero();
        assertThat(f.count("xm.scene.battle.items", "kind", "drop_lost")).isEqualTo(1);
        assertThat(f.gold(player)).isEqualTo(12);
        assertThat(player.battleLedger().has(X)).isTrue();
    }

    /**
     * 掉落数量同样夹到 {@code 0xFFFFFFFF}（评审修订第 9 条）。uint64 最大值夹成 4294967295 个：当然放不下（两处都拒），
     * 丢失日志里的数量就是夹过的值——不夹的话传进背包的是 −1。
     */
    @Test
    void 掉落数量夹到uint32上限() {
        ScenePlayer player = f.enter(SESSION, PLAYER);

        Result result = apply(player, plain(X).addItemsGained(item(DROP, -1L)).addItemsGained(item(POTION, 2)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(f.count("xm.scene.battle.items", "kind", "drop_lost")).isEqualTo(1);
        assertThat(logged(Level.ERROR)).anyMatch(m -> m.contains("道具丢失") && m.contains("11=" + UINT32_MAX));
        assertThat(inventory(player).total(DROP)).isZero();
        assertThat(inventory(player).total(POTION)).as("同一批里的别的掉落跟着整批落空（整批原子）").isZero();
        assertThat(player.battleLedger().has(X)).isTrue();
    }

    // ------------------------------------------------------------------ 流水

    /** 三类流水：金币 {@code BATTLE_REWARD}（关联号 = battle_id）、掉落 {@code ITEM_AWARD}（带来源）、每个被抽实例一条 {@code ITEM_DESTROY}。 */
    @Test
    void 流水_金币BATTLE_REWARD带关联号_掉落ITEM_AWARD带来源_每个被抽实例一条ITEM_DESTROY() {
        PlayerState state = bagOf(stack(501, POTION, 2, 0, BagType.INVENTORY), stack(502, POTION, 999, 1, BagType.INVENTORY))
                .toBuilder().setCurrency(CurrencyState.newBuilder().addBalances(100)).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, state);

        Result result = apply(player, plain(X).setGoldGain(12).addItemsConsumed(item(POTION, 3)).addItemsGained(item(DROP, 4)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(f.audit.currencies).containsExactly(
                new RecordingAssetAudit.Currency(PLAYER, Wallet.GOLD, 12, 100, 112, Reason.BATTLE_REWARD, X, ""));
        long dropGuid = inventory(player).items().stream().filter(i -> i.configId() == DROP).findFirst().orElseThrow().guid();
        assertThat(f.audit.items).containsExactly(
                destroyed(501, POTION, 2),
                destroyed(502, POTION, 1),
                new RecordingAssetAudit.Item(true, PLAYER, dropGuid, DROP, 4, Reason.ITEM_AWARD, X, EXTRA));
        assertThat(BattleSettlementService.extra(Long.MIN_VALUE)).as("来源里的 battle_id 按无符号写")
                .isEqualTo("{\"source\":\"battle\",\"battle_id\":9223372036854775808}");
    }

    // ------------------------------------------------------------------ k 步：账本

    /**
     * 账本满 64 条时的第 65 条：淘汰 {@code applied_at_ms} 最小的那条（按时间戳，不按下标也不按 battle_id），记 ERROR 与 {@code ledger_evictions}。
     * 这里让销账一直失败，把 64 条都留在账本里。
     */
    @Test
    void 第65条登记时淘汰时间戳最小的一条_计ledger_evictions() {
        BattleLedgerState.Builder ledger = BattleLedgerState.newBuilder();
        for (int i = 0; i < BattleLedger.CAPACITY; i++) {
            // 1040 号的时间戳最小：它既不是第一条，也不是 battle_id 最小的
            ledger.addApplied(BattleLedgerEntry.newBuilder().setBattleId(1_000 + i).setAppliedAtMs(i == 40 ? 7 : 5_000 + i));
        }
        f.locks.failAlways(Op.ACK, new RuntimeException("销账脚本失败"));
        ScenePlayer player = f.enter(SESSION, PLAYER, PlayerState.newBuilder().setBattleLedger(ledger).build());
        assertThat(player.battleLedger().size()).isEqualTo(64);

        assertThat(apply(player, plain(2_000).setGoldGain(12).build())).isEqualTo(Result.APPLIED);

        assertThat(f.count("xm.scene.battle.ledger.evictions")).isEqualTo(1);
        assertThat(player.battleLedger().size()).isEqualTo(64);
        assertThat(player.battleLedger().has(2_000)).isTrue();
        assertThat(player.battleLedger().has(1_040)).as("被淘汰的是时间戳最小的").isFalse();
        assertThat(player.battleLedger().has(1_000)).as("不是第一条").isTrue();
        assertThat(logged(Level.ERROR)).anyMatch(m -> m.contains("账本满") && m.contains("evicted=1040"));

        assertThat(apply(player, plain(2_000).setGoldGain(12).build())).as("已在账本里的再来：命中，不淘汰").isEqualTo(Result.ALREADY);
        assertThat(f.count("xm.scene.battle.ledger.evictions")).isEqualTo(1);
    }

    /**
     * e–j 某一步抛意外异常：金币已入账，不得让整笔失败——出错的那一步记 ERROR，之前和之后的步骤照做，账本照记（否则下一次重投再发一遍金币），
     * 150 照推。这里让掉落入包铸号时抛出（i 步中途）。
     */
    @Test
    void 某一步抛异常_其余各步照做_账本照记_150照推() {
        PlayerState state = withPets().setBag(BagState.newBuilder().addItems(stack(501, POTION, 5, 0, BagType.INVENTORY))).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, state);
        assertThat(f.missions.accept(player, 0, 7)).isZero();
        f.fighting(PLAYER, X);
        f.sink.clear();
        f.beforeItemMint = () -> {
            throw new IllegalStateException("铸号炸了");
        };
        BattleSettlementData settlement = vitals(X, 80, 20).setGoldGain(12).addPets(pet(FOX, 300, 200, false))
                .addItemsConsumed(item(POTION, 2)).addItemsGained(item(DROP, 3)).addDefeatedMonsters(kill(1, 2)).build();

        SceneBattleReply reply = delivered(settlement);

        assertThat(reply.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(logged(Level.ERROR)).anyMatch(m -> m.contains("第「掉落」步出错"));
        assertThat(inventory(player).total(DROP)).as("出错的那一步没有生效").isZero();
        assertThat(f.gold(player)).isEqualTo(12);
        assertThat(player.attributes().health()).as("e 步").isEqualTo(80);
        assertThat(player.pets().find(FOX).health()).as("f 步").isEqualTo(300);
        assertThat(inventory(player).total(POTION)).as("h 步").isEqualTo(3);
        assertThat(progress(player, 7)).as("出错之后的 j 步照做").isEqualTo(2);
        assertThat(player.battleLedger().has(X)).as("k 步在 finally 里").isTrue();
        assertThat(f.repo.pendingProgress()).as("l 步").isEqualTo(1);
        assertThat(f.messageIds(player)).containsExactly(f.petListChangedId, f.battleEndId);

        f.beforeItemMint = null;
        assertThat(delivered(settlement).getSettlement()).as("重投命中账本，不再发金币").isEqualTo(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(f.gold(player)).isEqualTo(12);
    }

    /**
     * a–d 任一步返回（归属不符 / 账本命中 / 交出冻结 / 账本损坏 / 金币被拒）都不登记账本、不压存盘、不记流水、不推消息。这里是 a 步与 d 步
     * （b、c 步各有专门的用例）。每份结算都带着<b>会改状态</b>的内容——气血法力不是满值、有消耗、有掉落、有宝宝回写、有击杀——
     * 所以哪一步提前生效了，紧跟着的整份状态比对就能看出来（气血给满值的话，回写泄漏夹到上限后与原值相同，比不出来）。
     */
    @Test
    void a到d步返回时_账本不登记_不压存盘_零副作用() {
        PlayerState saved = withPets().setBag(BagState.newBuilder().addItems(stack(501, POTION, 5, 0, BagType.INVENTORY))).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, saved);
        assertThat(f.missions.accept(player, 0, 7)).isZero();
        f.sink.clear();
        long health = player.attributes().health();
        assertThat(health).as("前提：气血此刻不是结算里的 80，回写泄漏看得出来").isNotEqualTo(80);
        PlayerState before = BattleFixture.persistentState(player);
        assertThat(before.hasBattleLedger()).isFalse();

        // a：归属不符 / battle_id 为 0
        assertThat(apply(player, loaded(X).setPlayerId(PLAYER + 1).setGoldGain(12).build()))
                .isEqualTo(new Result(Outcome.DISCARDED, SettlementResult.DISCARDED_INVALID));
        assertThat(BattleFixture.persistentState(player)).as("a 步（归属不符）：一个字段都没变").isEqualTo(before);
        assertThat(apply(player, loaded(0).setGoldGain(12).build()))
                .isEqualTo(new Result(Outcome.DISCARDED, SettlementResult.DISCARDED_INVALID));
        assertThat(BattleFixture.persistentState(player)).as("a 步（battle_id 为 0）：一个字段都没变").isEqualTo(before);
        // d：金币超界 / 被封
        assertThat(apply(player, loaded(X).setGoldGain(-1L).build()))
                .isEqualTo(new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_CURRENCY));
        assertThat(BattleFixture.persistentState(player)).as("d 步（金币超出 int64）：一个字段都没变").isEqualTo(before);
        assertThat(f.currency.block(player, Wallet.GOLD)).isZero();
        PlayerState blocked = BattleFixture.persistentState(player);
        assertThat(blocked).as("前提：GM 封禁本身进了持久状态，所以下面要与封禁之后的这一份比").isNotEqualTo(before);
        assertThat(apply(player, loaded(X).setGoldGain(12).build()))
                .isEqualTo(new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_CURRENCY));

        PlayerState after = BattleFixture.persistentState(player);
        assertThat(after).as("d 步（金币被封）：除了那次 GM 封禁，状态没变").isEqualTo(blocked);
        assertThat(after.hasBattleLedger()).as("四次都没有登记账本").isFalse();
        assertThat(player.attributes().health()).as("气血没被回写").isEqualTo(health);
        assertThat(player.pets().find(FOX).health()).as("宝宝没被回写").isEqualTo(100);
        assertThat(inventory(player).total(POTION)).as("消耗没被扣").isEqualTo(5);
        assertThat(inventory(player).total(DROP)).as("掉落没入包").isZero();
        assertThat(progress(player, 7)).as("击杀没计进任务").isZero();
        assertThat(f.gold(player)).isZero();
        assertThat(f.pushes(player, f.petListChangedId)).as("没有推 184").isEmpty();
        assertThat(player.battleLedger().size()).isZero();
        assertThat(f.repo.pendingProgress()).isZero();
        assertThat(f.audit.currencies).isEmpty();
        assertThat(f.audit.items).isEmpty();
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(f.locks.calls()).isEmpty();
    }

    @Test
    void b步账本命中_不刷新时间戳_不再压存盘() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long appliedAt = f.clock.epochMillis();
        assertThat(apply(player, plain(X).setGoldGain(12).build())).isEqualTo(Result.APPLIED);
        f.completeSaves(ProgressResult.FAILED);
        f.advance(5_000);

        assertThat(apply(player, plain(X).setGoldGain(12).build())).isEqualTo(Result.ALREADY);

        assertThat(BattleFixture.persistentState(player).getBattleLedger().getAppliedList())
                .containsExactly(BattleLedgerEntry.newBuilder().setBattleId(X).setAppliedAtMs(appliedAt).build());
        assertThat(f.repo.pendingProgress()).as("命中是只读的：应用服务自己不再压存盘（销账入口另算）").isZero();
        assertThat(f.gold(player)).isEqualTo(12);
    }

    /**
     * c 步：账本损坏（D24）时结算一律延后、零副作用，损坏的账本原样带回——绝不当空账本用（那样会把已发过的奖再发一遍）。
     * 进场恢复遇到它同样延后，恢复停在 RETRY。
     */
    @Test
    void c步账本损坏_一律延后_零副作用_账本原样带回() {
        BattleLedgerState corrupt = BattleLedgerState.newBuilder()
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(9).setAppliedAtMs(1))
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(9).setAppliedAtMs(2)).build();
        BattleSettlementData settlement = plain(X).setGoldGain(12).build();
        f.store(settlement);

        ScenePlayer player = f.enter(SESSION, PLAYER, PlayerState.newBuilder().setBattleLedger(corrupt).build());

        assertThat(player.battleLedger().invalidReason()).contains("重复");
        assertThat(f.settlementsCounted("login", "deferred_ledger")).as("进场恢复读到的记录被延后").isEqualTo(1);
        assertThat(player.battle().recovery()).isEqualTo(PlayerBattle.Recovery.RETRY);
        assertThat(apply(player, settlement)).isEqualTo(new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_LEDGER));
        assertThat(apply(player, plain(9).setGoldGain(12).build())).as("损坏的账本连「命中」也不认")
                .isEqualTo(new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_LEDGER));
        assertThat(f.gold(player)).isZero();
        assertThat(BattleFixture.persistentState(player).getBattleLedger()).as("原样带回、不改写").isEqualTo(corrupt);
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("记录留着").isTrue();
        assertThat(f.locks.count(Op.ACK)).as("损坏的账本不拿去销账").isZero();
        assertThat(f.repo.pendingProgress()).isZero();
    }

    // ------------------------------------------------------------------ 闸

    /**
     * 整笔可应用的判据（c 步）是各内部闸的超集：货币 / 背包 / 任务服务只在交出冻结时拒绝，c 步已排除它，所以放行之后 d–j 没有哪一步被自己的闸半路拒掉
     * （否则就是「闸半拒、账本照记」）。选目标中（RESOLVING）不是冻结：整笔应用、每一项都生效。
     */
    @Test
    void 选目标中不算冻结_c步放行_金币宝宝消耗掉落击杀每一项都生效() {
        PlayerState state = withPets().setBag(BagState.newBuilder().addItems(stack(501, POTION, 5, 0, BagType.INVENTORY))).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, state);
        assertThat(f.missions.accept(player, 0, 7)).isZero();
        f.resolveRemote(player);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(player.frozen()).isFalse();

        Result result = apply(player, vitals(X, 80, 20).setGoldGain(12).addPets(pet(FOX, 300, 200, false))
                .addItemsConsumed(item(POTION, 2)).addItemsGained(item(DROP, 3)).addDefeatedMonsters(kill(1, 2)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(f.gold(player)).isEqualTo(12);
        assertThat(player.attributes().health()).isEqualTo(80);
        assertThat(player.attributes().mana()).isEqualTo(20);
        assertThat(player.pets().find(FOX).health()).isEqualTo(300);
        assertThat(inventory(player).total(POTION)).isEqualTo(3);
        assertThat(inventory(player).total(DROP)).isEqualTo(3);
        assertThat(progress(player, 7)).isEqualTo(2);
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(f.repo.pendingProgress()).as("RESOLVING 不挡在线存盘：之后拍的冻结快照会包含这次应用").isEqualTo(1);
    }

    /**
     * 评审修订第 9 条：应用时战斗冻结<b>还没摘</b>（收尾在应用之后才解冻），{@code inBattle()} 为真；而属性 / 宝宝的写入口此刻回 25011 / 26008。
     * 结算的气血回写与宝宝回写绝不能经过那两道闸，货币 / 背包 / 任务也不看战斗闸（D48）——d–j 每一项都生效，各战斗闸一次都没被触发。
     */
    @Test
    void 在FIGHTING冻结的玩家身上应用_气血与宝宝回写都生效_不被战斗闸拒() {
        PlayerState state = withPets().setBag(BagState.newBuilder().addItems(stack(501, POTION, 5, 0, BagType.INVENTORY))).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, state);
        assertThat(f.missions.accept(player, 0, 7)).isZero();
        f.fighting(PLAYER, X);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        // 冻结确实挡着属性与宝宝的写入口
        assertThat(f.attributes.createScheme(player, "战斗中").tipId()).isEqualTo(25011);
        assertThat(f.pets.recall(player)).isEqualTo(26008);
        double attributeRejects = f.count("xm.scene.battle.gate.rejects", "gate", "attribute");
        double petRejects = f.count("xm.scene.battle.gate.rejects", "gate", "pet");
        BattleFreeze freeze = player.battle().freeze();

        // 直接调应用：冻结此刻还挂着
        Result result = apply(player, vitals(X, 80, 20).setGoldGain(12)
                .addPets(pet(FOX, 300, 200, false)).addPets(pet(GOLEM, 0, 0, true))
                .addItemsConsumed(item(POTION, 2)).addItemsGained(item(DROP, 3)).addDefeatedMonsters(kill(1, 2)).build());

        assertThat(player.battle().freeze()).as("应用本身不解冻").isSameAs(freeze);
        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(player.attributes().health()).as("气血回写").isEqualTo(80);
        assertThat(player.attributes().mana()).isEqualTo(20);
        assertThat(player.pets().find(FOX).health()).as("宝宝回写").isEqualTo(300);
        assertThat(player.pets().find(FOX).mana()).isEqualTo(200);
        assertThat(player.pets().find(GOLEM).health()).isEqualTo(742);
        assertThat(f.gold(player)).isEqualTo(12);
        assertThat(inventory(player).total(POTION)).isEqualTo(3);
        assertThat(inventory(player).total(DROP)).isEqualTo(3);
        assertThat(progress(player, 7)).isEqualTo(2);
        assertThat(f.pushes(player, f.petListChangedId)).hasSize(1);
        assertThat(f.count("xm.scene.battle.gate.rejects", "gate", "attribute")).as("没有经过带 25011 的闸").isEqualTo(attributeRejects);
        assertThat(f.count("xm.scene.battle.gate.rejects", "gate", "pet")).as("没有经过带 26008 的闸").isEqualTo(petRejects);
    }
}
