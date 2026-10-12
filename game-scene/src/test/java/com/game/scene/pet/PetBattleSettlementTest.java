package com.game.scene.pet;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattlePetSettlementData;
import com.game.scene.battle.BattleFixture;
import com.game.scene.battle.BattleFreeze;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import com.game.table.AttributeDimensionTable;
import com.game.table.AttributePoolTable;
import com.game.table.PetTable;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * 战斗结算回写宝宝 {@code PetService.applyBattleSettlement}（scene-battle-spec §5.3、§7.14；基线 {@code PetSystem::ApplyBattleSettlement}，
 * {@code player_pet.cpp:914-941}）的取值规则。用<b>手造的配表</b>（与 {@code PetBattleSnapshotTest} 同一套）以便摆出正式表里没有的边界——
 * 法力上限为 0 的种类、缺表行、缺宝宝池：
 * <ul>
 *   <li>维度 401：每级 2 点；每点气血上限 +3，法力上限 +0；</li>
 *   <li>种类 51「铁甲龟」初值气血 100 / 法力 50；种类 52「无蓝兽」气血 100 / 法力 0。</li>
 * </ul>
 * 5 级、401 加了 1 点：气血上限 100 + 11 × 3 = 133；法力上限 50（无蓝兽 0）。
 * 经整笔结算走到这里的路径（184、与 150 的次序、冻结闸）在 {@code SettlementApplyValuesTest}。
 */
class PetBattleSettlementTest {

    private static final long PLAYER = 1001;
    private static final long TURTLE_ID = 9_001;
    private static final long NO_MANA_ID = 9_002;
    private static final int TURTLE = 51;
    private static final int NO_MANA = 52;
    private static final int DIMENSION = 401;
    private static final long MAX_HEALTH = 133;
    private static final long MAX_MANA = 50;

    private static PetTables tables(boolean withPool) {
        PetTable turtle = PetTable.newBuilder().setId(TURTLE).setName("铁甲龟").setInitHealth(100).setInitMana(50).setInitSpeed(10).build();
        PetTable noMana = PetTable.newBuilder().setId(NO_MANA).setName("无蓝兽").setInitHealth(100).setInitMana(0).setInitSpeed(10).build();
        AttributePoolTable pool = AttributePoolTable.newBuilder().setId(4).setUnlockLevel(1).setPointsPerLevel(5).build();
        AttributeDimensionTable dimension = AttributeDimensionTable.newBuilder().setId(DIMENSION).setBasePerLevel(2)
                .setMaxHealth(3).setMaxMana(0).setPhysicalAttack(4).setMagicAttack(5).setSpeed(6).setDefense(7).build();
        return new PetTables(Map.of(TURTLE, turtle, NO_MANA, noMana), null, withPool ? pool : null,
                withPool ? List.of(dimension) : List.of(), null);
    }

    private static PetService service(PetTables tables) {
        return new PetService(tables, new CurrencyService(new RecordingAssetAudit()), count -> new long[count], new ManualClock(),
                new SplittableRandom(7));
    }

    private static Pet pet(ScenePlayer player, long petId, int petTableId, long health, long mana) {
        Pet pet = new Pet(petId, petTableId, 5, 1_700_000_000L);
        pet.setAllocated(DIMENSION, 1);
        pet.setHealth(health);
        pet.setMana(mana);
        player.pets().add(pet);
        return pet;
    }

    private static BattlePetSettlementData entry(long petId, long health, long mana, boolean dead) {
        return BattlePetSettlementData.newBuilder().setPetId(petId).setHealth(health).setMana(mana).setIsDead(dead).build();
    }

    @Test
    void 气血法力按现算上限夹_不超过上限的原样() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet turtle = pet(player, TURTLE_ID, TURTLE, 100, 10);
        PetService service = service(tables(true));

        service.applyBattleSettlement(player, List.of(entry(TURTLE_ID, 9_999, 777, false)));
        assertThat(turtle.health()).isEqualTo(MAX_HEALTH);
        assertThat(turtle.mana()).isEqualTo(MAX_MANA);

        service.applyBattleSettlement(player, List.of(entry(TURTLE_ID, MAX_HEALTH, MAX_MANA, false)));
        assertThat(turtle.health()).as("恰好等于上限").isEqualTo(MAX_HEALTH);
        assertThat(turtle.mana()).isEqualTo(MAX_MANA);

        service.applyBattleSettlement(player, List.of(entry(TURTLE_ID, 1, 0, false)));
        assertThat(turtle.health()).as("残血原样").isEqualTo(1);
        assertThat(turtle.mana()).as("法力打空就是 0：不因此回满").isZero();
    }

    /** uint64 高位为 1 的值有符号看是负数：按无符号的大值夹到上限，不能当成负数写进去，也不能当成 0 把活着的宝宝「复活」。 */
    @Test
    void 超出int64的气血法力按最大值夹到上限() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet turtle = pet(player, TURTLE_ID, TURTLE, 100, 10);

        service(tables(true)).applyBattleSettlement(player, List.of(entry(TURTLE_ID, -1L, Long.MIN_VALUE, false)));

        assertThat(turtle.health()).isEqualTo(MAX_HEALTH);
        assertThat(turtle.mana()).isEqualTo(MAX_MANA);
    }

    /** 法力上限算出来是 0（缺配）时不夹，取结算值：夹了就把法力抹成 0。 */
    @Test
    void 法力上限为0时不夹_取结算值() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet noMana = pet(player, NO_MANA_ID, NO_MANA, 100, 10);

        service(tables(true)).applyBattleSettlement(player, List.of(entry(NO_MANA_ID, 60, 777, false)));

        assertThat(noMana.health()).isEqualTo(60);
        assertThat(noMana.mana()).isEqualTo(777);
    }

    /** {@code is_dead}（哪怕带着残血）或夹后气血为 0 → 回满：气血 = 上限、法力 = 法力上限。不回满的话死宝宝会被战斗快照永远挡在门外。 */
    @Test
    void 阵亡回满_标了阵亡的或气血为0的都回到上限() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet flagged = pet(player, TURTLE_ID, TURTLE, 100, 10);
        Pet zeroed = pet(player, TURTLE_ID + 10, TURTLE, 100, 10);
        Pet noMana = pet(player, NO_MANA_ID, NO_MANA, 100, 10);

        service(tables(true)).applyBattleSettlement(player, List.of(
                entry(TURTLE_ID, 40, 5, true), entry(TURTLE_ID + 10, 0, 5, false), entry(NO_MANA_ID, 0, 777, true)));

        assertThat(flagged.health()).isEqualTo(MAX_HEALTH);
        assertThat(flagged.mana()).isEqualTo(MAX_MANA);
        assertThat(zeroed.health()).as("没标阵亡但气血 0").isEqualTo(MAX_HEALTH);
        assertThat(zeroed.mana()).isEqualTo(MAX_MANA);
        assertThat(noMana.health()).isEqualTo(MAX_HEALTH);
        assertThat(noMana.mana()).as("回满 = 回到法力上限，上限是 0 就是 0").isZero();
    }

    @Test
    void 找不到的宝宝忽略_其余条目照写_同一只宝宝的多条以后一条为准() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet turtle = pet(player, TURTLE_ID, TURTLE, 100, 10);
        Pet other = pet(player, TURTLE_ID + 10, TURTLE, 100, 10);

        service(tables(true)).applyBattleSettlement(player, List.of(
                entry(7_777, 1, 1, false), entry(TURTLE_ID, 80, 20, false), entry(0, 1, 1, true), entry(TURTLE_ID, 70, 30, false)));

        assertThat(player.pets().pets()).as("没有凭空多出宝宝").hasSize(2);
        assertThat(turtle.health()).isEqualTo(70);
        assertThat(turtle.mana()).isEqualTo(30);
        assertThat(other.health()).as("没有条目的宝宝不动").isEqualTo(100);
        assertThat(other.mana()).isEqualTo(10);
    }

    /** 缺种类行或缺宝宝池：算不出上限，静默跳过——不写、更不能按「上限 0」把宝宝写成 0 血。 */
    @Test
    void 缺种类行或缺宝宝池_跳过不写() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet unknownKind = pet(player, TURTLE_ID, 999, 100, 10);
        Pet turtle = pet(player, TURTLE_ID + 10, TURTLE, 100, 10);

        service(tables(true)).applyBattleSettlement(player, List.of(entry(TURTLE_ID, 5, 5, true), entry(TURTLE_ID + 10, 5, 5, false)));
        assertThat(unknownKind.health()).as("缺种类行：不动").isEqualTo(100);
        assertThat(unknownKind.mana()).isEqualTo(10);
        assertThat(turtle.health()).as("后面的条目照写").isEqualTo(5);

        service(tables(false)).applyBattleSettlement(player, List.of(entry(TURTLE_ID + 10, 0, 0, true)));
        assertThat(turtle.health()).as("缺宝宝池：不动").isEqualTo(5);
        assertThat(turtle.mana()).isEqualTo(5);
    }

    /** 结算应用时战斗冻结还没摘：回写不经过带 26008 的写入闸（同一时刻玩家自己的宝宝写操作是被拒的）。 */
    @Test
    void 战斗冻结还挂着时照常回写_不经过26008的写入闸() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet turtle = pet(player, TURTLE_ID, TURTLE, 100, 10);
        player.pets().setActivePetId(TURTLE_ID);
        BattleFixture.setFreeze(player, new BattleFreeze(7, 21, BattleFreeze.Phase.FIGHTING, 1, 0, true));
        PetService service = service(tables(true));
        assertThat(player.inBattle()).isTrue();
        assertThat(service.recall(player)).as("玩家自己的宝宝写操作此刻被战斗闸挡着").isEqualTo(26008);

        service.applyBattleSettlement(player, List.of(entry(TURTLE_ID, 80, 20, false)));

        assertThat(turtle.health()).isEqualTo(80);
        assertThat(turtle.mana()).isEqualTo(20);
        assertThat(player.pets().activePetId()).as("出战状态不动").isEqualTo(TURTLE_ID);
    }

    @Test
    void 空条目表_什么都不做() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet turtle = pet(player, TURTLE_ID, TURTLE, 100, 10);

        service(tables(true)).applyBattleSettlement(player, List.of());

        assertThat(turtle.health()).isEqualTo(100);
        assertThat(turtle.mana()).isEqualTo(10);
    }
}
