package com.game.scene.pet;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattlePetSnapshot;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import com.game.table.AttributeDimensionTable;
import com.game.table.AttributePoolTable;
import com.game.table.ConfigTables;
import com.game.table.PetTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 出战宝宝的战斗快照 {@code PetService.buildBattleSnapshot}（scene-battle-spec §5.1、§7.14、§13.1；基线
 * {@code PetSystem::BuildBattleSnapshot}，{@code player_pet.cpp:867-912}，技能 {@code ResolveSkills} {@code :95-109}）。
 *
 * <p>大部分用例用<b>手造的配表</b>（系数都是整数、资质取基准，二级属性可以心算，不踩浮点取整的边）：
 * <ul>
 *   <li>宝宝池维度 401：每级自然成长 2 点；每点 气血上限 +3、物伤 +4、法伤 +5、速度 +6、防御 +7，法力上限 +0；</li>
 *   <li>种类 51「铁甲龟」：初值 气血 100 / 法力 50 / 速度 10，自带技能 [3, 0, 4, 22]；</li>
 *   <li>种类 52「无蓝兽」：初值 气血 100 / 法力 0 / 速度 10——法力上限算出来是 0。</li>
 * </ul>
 * 5 级、401 维度加了 1 点的宝宝：维度值 2 × 5 + 1 = 11 → 气血上限 133、物伤 44、法伤 55、速度 76、防御 77。
 * 正式配表（灵狐）另有一条，钉住与面板同一套现算口径。
 */
class PetBattleSnapshotTest {

    private static final long PLAYER = 1001;
    private static final long PET = 9_001;
    private static final int TURTLE = 51;
    private static final int NO_MANA = 52;
    private static final int DIMENSION = 401;

    private static PetTables real;

    private static final PetTables SYNTHETIC = synthetic(true);

    /** 闭区间随机恒取下限：灵狐（种类 1）的资质 = 8000 / 10000 / 7000 / 11000。 */
    private static final RandomGenerator LOWEST = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long origin, long bound) {
            return origin;
        }
    };

    private final AtomicLong guidSeq = new AtomicLong(1L << 60);

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        real = PetTables.from(ConfigTables.load(dir));
    }

    private static PetTables synthetic(boolean withPool) {
        PetTable turtle = PetTable.newBuilder().setId(TURTLE).setName("铁甲龟").setInitHealth(100).setInitMana(50).setInitSpeed(10)
                .addSkill(3).addSkill(0).addSkill(4).addSkill(22).build();
        PetTable noMana = PetTable.newBuilder().setId(NO_MANA).setName("无蓝兽").setInitHealth(100).setInitMana(0).setInitSpeed(10)
                .build();
        AttributePoolTable pool = AttributePoolTable.newBuilder().setId(4).setUnlockLevel(1).setPointsPerLevel(5).build();
        AttributeDimensionTable dimension = AttributeDimensionTable.newBuilder().setId(DIMENSION).setBasePerLevel(2)
                .setMaxHealth(3).setMaxMana(0).setPhysicalAttack(4).setMagicAttack(5).setSpeed(6).setDefense(7).build();
        return new PetTables(Map.of(TURTLE, turtle, NO_MANA, noMana), null, withPool ? pool : null,
                withPool ? List.of(dimension) : List.of(), null);
    }

    private PetService service(PetTables tables) {
        return new PetService(tables, new CurrencyService(new RecordingAssetAudit()), count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = guidSeq.incrementAndGet();
            }
            return out;
        }, new ManualClock(), LOWEST);
    }

    /** 一只 5 级、401 维度加了 1 点、资质取基准的宝宝，已出战。 */
    private static Pet activePet(ScenePlayer player, int petTableId, long health, long mana) {
        Pet pet = new Pet(PET, petTableId, 5, 1_700_000_000L);
        pet.setAllocated(DIMENSION, 1);
        pet.setHealth(health);
        pet.setMana(mana);
        player.pets().add(pet);
        player.pets().setActivePetId(PET);
        return pet;
    }

    private static BaseAttributesComp vitals(long health, long mana, long speed) {
        return BaseAttributesComp.newBuilder().setHealth(health).setMana(mana).setSpeed(speed).build();
    }

    // ------------------------------------------------------------------ 不带宝宝的情形

    @Test
    void 没有宝宝_或有宝宝但没出战_不带() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).isEmpty();

        activePet(player, TURTLE, 100, 10);
        player.pets().setActivePetId(0);

        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).isEmpty();
    }

    /**
     * 「没有出战」只看出战号是不是 0，不拿 0 去找宝宝：存档恢复不校验宝宝号，坏档里真有一只 {@code pet_id = 0} 的宝宝时也不能带——
     * 引擎对 {@code pet_id == 0} 的宝宝整局拒绝（§5.2），带进去这名玩家每一局都建不了房。
     */
    @Test
    void 出战号为0_哪怕坏档里真有一只号为0的宝宝_也不带() {
        PlayerState state = PlayerState.newBuilder().setPets(PetState.newBuilder().setActivePetId(0)
                .addPets(PetEntry.newBuilder().setPetId(0).setPetTableId(TURTLE).setLevel(5).setHealth(100).setMana(10))).build();
        ScenePlayer player = WorldTestAccess.player(PLAYER, 1, state);
        assertThat(player.pets().activePetId()).isZero();
        assertThat(player.pets().find(0)).as("坏档里号为 0 的宝宝确实读进来了").isNotNull();
        assertThat(player.pets().find(0).health()).isEqualTo(100);

        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).isEmpty();
    }

    @Test
    void 出战号指向不存在的宝宝_不带() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        activePet(player, TURTLE, 100, 10);
        player.pets().setActivePetId(PET + 1);

        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).isEmpty();
    }

    @Test
    void 缺种类行_不带() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        activePet(player, 999, 100, 10);

        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).isEmpty();
    }

    @Test
    void 缺宝宝池_不带() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        activePet(player, TURTLE, 100, 10);

        assertThat(service(synthetic(false)).buildBattleSnapshot(player)).isEmpty();
        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).as("同一只宝宝，有池就带").hasSize(1);
    }

    /** 死宝宝不参战（带进去就是开局即倒的空单位；结算会把它回满）。 */
    @Test
    void 气血为0_不带_气血为1就带() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet pet = activePet(player, TURTLE, 0, 10);

        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).isEmpty();

        pet.setHealth(1);
        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).singleElement()
                .satisfies(snapshot -> assertThat(snapshot.getBaseAttributes().getHealth()).isEqualTo(1));
    }

    // ------------------------------------------------------------------ 字段

    /**
     * 整份快照逐字段：号、主人、名字、种类、等级取实例；上限与攻防取现算的二级属性；{@code base_attributes} 只填气血 / 法力 / 速度三项，
     * 其余（力量、护甲……）为 0；技能 = 种类自带（跳过 0）。
     */
    @Test
    void 各字段来源_整份快照逐字段相等() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet pet = activePet(player, TURTLE, 120, 30);
        pet.rename("小乌龟");

        List<BattlePetSnapshot> snapshots = service(SYNTHETIC).buildBattleSnapshot(player);

        assertThat(snapshots).containsExactly(BattlePetSnapshot.newBuilder()
                .setPetId(PET)
                .setOwnerPlayerId(PLAYER)
                .setPetName("小乌龟")
                .setPetTableId(TURTLE)
                .setLevel(5)
                .setBaseAttributes(vitals(120, 30, 76))
                .setMaxHealth(133)
                .setMaxMana(50)
                .setPhysicalAttack(44)
                .setMagicAttack(55)
                .setDefense(77)
                .addAllSkillTableIds(List.of(3, 4, 22))
                .build());
    }

    @Test
    void 名字为空时用表里的名字() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        activePet(player, TURTLE, 120, 30);

        assertThat(service(SYNTHETIC).buildBattleSnapshot(player).get(0).getPetName()).isEqualTo("铁甲龟");
    }

    /** 快照里的二级属性是按这只宝宝此刻的等级、已分配点、资质现算的（不是存下来的）：资质翻倍，维度贡献翻倍。 */
    @Test
    void 速度与攻防取现算的派生值_等级已分配点资质都算进去() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet pet = activePet(player, TURTLE, 120, 30);
        pet.setAptitude(DIMENSION, 20_000);

        BattlePetSnapshot doubled = service(SYNTHETIC).buildBattleSnapshot(player).get(0);

        assertThat(doubled.getMaxHealth()).as("100 + 11 × 3 × 2").isEqualTo(166);
        assertThat(doubled.getPhysicalAttack()).isEqualTo(88);
        assertThat(doubled.getMagicAttack()).isEqualTo(110);
        assertThat(doubled.getDefense()).isEqualTo(154);
        assertThat(doubled.getBaseAttributes().getSpeed()).as("速度取派生速度：10 + 11 × 6 × 2").isEqualTo(142);
        assertThat(doubled.getMaxMana()).as("这个维度不加法力").isEqualTo(50);

        pet.setAptitude(DIMENSION, 10_000);
        pet.setAllocated(DIMENSION, 0);
        pet.setLevel(3);
        BattlePetSnapshot plain = service(SYNTHETIC).buildBattleSnapshot(player).get(0);

        assertThat(plain.getLevel()).isEqualTo(3);
        assertThat(plain.getMaxHealth()).as("100 + (2 × 3) × 3").isEqualTo(118);
        assertThat(plain.getBaseAttributes().getSpeed()).as("10 + 6 × 6").isEqualTo(46);
        assertThat(plain.getPhysicalAttack()).isEqualTo(24);
    }

    // ------------------------------------------------------------------ 气血、法力的夹取

    @Test
    void 气血按上限夹_不超过上限的原样_快照不改宝宝自身() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet pet = activePet(player, TURTLE, 9_999, 30);

        BattlePetSnapshot clamped = service(SYNTHETIC).buildBattleSnapshot(player).get(0);

        assertThat(clamped.getBaseAttributes().getHealth()).isEqualTo(133);
        assertThat(clamped.getMaxHealth()).isEqualTo(133);
        assertThat(pet.health()).as("组快照是只读的").isEqualTo(9_999);

        pet.setHealth(133);
        assertThat(service(SYNTHETIC).buildBattleSnapshot(player).get(0).getBaseAttributes().getHealth()).isEqualTo(133);
        pet.setHealth(132);
        assertThat(service(SYNTHETIC).buildBattleSnapshot(player).get(0).getBaseAttributes().getHealth()).isEqualTo(132);
    }

    @Test
    void 法力上限大于0时按上限夹() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet pet = activePet(player, TURTLE, 120, 777);

        BattlePetSnapshot clamped = service(SYNTHETIC).buildBattleSnapshot(player).get(0);

        assertThat(clamped.getMaxMana()).isEqualTo(50);
        assertThat(clamped.getBaseAttributes().getMana()).isEqualTo(50);
        assertThat(pet.mana()).isEqualTo(777);

        pet.setMana(49);
        assertThat(service(SYNTHETIC).buildBattleSnapshot(player).get(0).getBaseAttributes().getMana()).isEqualTo(49);
    }

    /** 法力上限算出来是 0（缺配）时不夹：夹了就把法力抹成 0。 */
    @Test
    void 法力上限为0时取原值不夹() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        activePet(player, NO_MANA, 120, 777);

        BattlePetSnapshot snapshot = service(SYNTHETIC).buildBattleSnapshot(player).get(0);

        assertThat(snapshot.getMaxMana()).isZero();
        assertThat(snapshot.getBaseAttributes()).isEqualTo(vitals(120, 777, 76));
        assertThat(snapshot.getSkillTableIdsList()).as("这个种类没有自带技能").isEmpty();
        assertThat(snapshot.getPetName()).isEqualTo("无蓝兽");
    }

    // ------------------------------------------------------------------ 技能

    /**
     * 技能 = 种类自带 ∪ 实例已学：先种类（表序，跳过 0）、后已学（实例里的次序，跳过 0、跳过种类已有的、已学里重复的只留第一个）。
     * <b>不做可施放过滤</b>（B4）：正式技能表里 3 是持续施法、4 是开关，玩家快照会剔除它们，宝宝快照照带（引擎只让宝宝普攻）；
     * 表里查不到的技能号（22、9_999）也照带。
     */
    @Test
    void 技能先种类后已学_去重_跳过0_不做可施放过滤() {
        PlayerState state = PlayerState.newBuilder().setPets(PetState.newBuilder().setActivePetId(PET)
                .addPets(PetEntry.newBuilder().setPetId(PET).setPetTableId(TURTLE).setLevel(5).setHealth(100).setMana(10)
                        .addAllSkillTableIds(List.of(4, 9, 0, 9_999, 3, 9, 1)))).build();
        ScenePlayer player = WorldTestAccess.player(PLAYER, 1, state);

        BattlePetSnapshot snapshot = service(SYNTHETIC).buildBattleSnapshot(player).get(0);

        assertThat(snapshot.getSkillTableIdsList()).containsExactly(3, 4, 22, 9, 9_999, 1);
    }

    // ------------------------------------------------------------------ 多只宝宝、正式配表

    @Test
    void 多只宝宝_只带出战的那一只() {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        Pet first = new Pet(PET + 1, NO_MANA, 5, 1);
        first.setHealth(50);
        player.pets().add(first);
        activePet(player, TURTLE, 120, 30);
        Pet last = new Pet(PET + 2, NO_MANA, 5, 2);
        last.setHealth(60);
        player.pets().add(last);

        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).singleElement()
                .satisfies(snapshot -> assertThat(snapshot.getPetId()).isEqualTo(PET));

        player.pets().setActivePetId(PET + 2);
        assertThat(service(SYNTHETIC).buildBattleSnapshot(player)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.getPetId()).isEqualTo(PET + 2);
            assertThat(snapshot.getPetTableId()).isEqualTo(NO_MANA);
            assertThat(snapshot.getBaseAttributes().getHealth()).isEqualTo(60);
        });
    }

    /**
     * 正式配表：GM 发放的灵狐（1 级，资质取区间下限 8000 / 10000 / 7000 / 11000）出战后的快照，数值与面板（{@code PetServiceTest}）同一套现算口径：
     * 气血上限 400 + 42 × 0.8 = 433、法力上限 800 + 30 = 830、物伤 40 × 0.7 = 28、法伤 33、速度 144 + 30 × 1.1 = 177、防御 45 × 0.8 = 36。
     */
    @Test
    void 正式配表_发放并出战的灵狐_满血满蓝_数值同面板口径() {
        PetService service = service(real);
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        service.initializeOnLoad(player);
        PetService.Grant grant = service.grant(player, 1);
        assertThat(grant.tip()).isZero();
        assertThat(service.buildBattleSnapshot(player)).as("发放不等于出战").isEmpty();
        assertThat(service.summon(player, grant.petId())).isZero();

        List<BattlePetSnapshot> snapshots = service.buildBattleSnapshot(player);

        assertThat(snapshots).containsExactly(BattlePetSnapshot.newBuilder()
                .setPetId(grant.petId())
                .setOwnerPlayerId(PLAYER)
                .setPetName("灵狐")
                .setPetTableId(1)
                .setLevel(1)
                .setBaseAttributes(vitals(433, 830, 177))
                .setMaxHealth(433)
                .setMaxMana(830)
                .setPhysicalAttack(28)
                .setMagicAttack(33)
                .setDefense(36)
                .build());

        assertThat(service.recall(player)).isZero();
        assertThat(service.buildBattleSnapshot(player)).as("收回后不带").isEmpty();
    }
}
