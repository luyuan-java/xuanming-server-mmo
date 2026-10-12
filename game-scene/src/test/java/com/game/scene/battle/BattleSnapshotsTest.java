package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.engine.BattleRules;
import com.game.battle.engine.TableBattleData;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.player.BagTestAccess;
import com.game.scene.player.BagType;
import com.game.scene.player.ItemCatalog;
import com.game.scene.player.PlayerAttributes.Derived;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import com.game.table.ClassTable;
import com.game.table.SkillTable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 玩家战斗快照的组装（scene-battle-spec §1.3 的表、§7.11 快照表、§13.1；基线 {@code BuildBattleSnapshot}，{@code pb.cpp:885-1103}）。
 *
 * <p>两种写法：
 * <ul>
 *   <li><b>纯函数</b>：玩家实例与配表视图都是手造的（{@link WorldTestAccess#player(long, int, int, String, int, List, String, PlayerState)} +
 *       直接构造 {@link SceneBattleTables}），逐字段钉取值规则与边界——经世界进场的玩家技能恒为初始技能、等级被压回合法范围，造不出这些输入；</li>
 *   <li><b>正式配表 + 真进场</b>（{@link BattleFixture}）：钉住装配（名字经 {@code PlayerData} 带进来、路由、指纹与 battle 同源、
 *       {@link SceneBattleTables#from} 对正式技能表 / 物品表的判定）。</li>
 * </ul>
 */
class BattleSnapshotsTest {

    private static final long PLAYER = 1001;
    private static final int SESSION = 11;
    private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef";
    /** 大于 2^31 的物品配置号（有符号看是负数）：合并后的次序必须按无符号。 */
    private static final int HUGE_ITEM = 0x8000_0001;
    private static final int MAX_ITEM = 0xFFFF_FFFF;

    private static final BattleRouting ROUTING = BattleRouting.newBuilder().setSessionId(SESSION).setGateNodeId(7)
            .setGateInstanceId("gate-x").setSceneNodeId(3).setSceneInstanceId("scene-x").setZoneId(2).build();

    /** 什么物品都认、都能叠的物品表（背包规整只用它定格子，不影响快照）。 */
    private static final ItemCatalog ANY_ITEM = new ItemCatalog() {
        @Override
        public ItemSpec item(int configId) {
            return new ItemSpec(configId, 999, 0);
        }

        @Override
        public List<Integer> equipSlots(int equipKind) {
            return List.of();
        }
    };

    // 手造的技能行：位号 0 被动 / 2 持续施法 / 3 开关不能在回合制里施放，其余（含空）都能
    private static final int NORMAL = 21;
    private static final int PASSIVE = 22;
    private static final int TOGGLE = 23;
    private static final int CHANNEL = 24;
    private static final int EMPTY_TYPE = 25;
    private static final int NORMAL_AND_PASSIVE = 26;
    private static final int OTHER_AND_TOGGLE = 27;
    private static final int OTHER = 28;
    private static final int OTHER_AND_CHANNEL = 29;
    private static final int NO_ROW = 999;

    private static final List<SkillTable> SKILL_ROWS = List.of(
            skillRow(NORMAL, 1), skillRow(PASSIVE, 0), skillRow(TOGGLE, 3), skillRow(CHANNEL, 2), skillRow(EMPTY_TYPE),
            skillRow(NORMAL_AND_PASSIVE, 1, 0), skillRow(OTHER_AND_TOGGLE, 4, 3), skillRow(OTHER, 5), skillRow(OTHER_AND_CHANNEL, 6, 2));

    private static SkillTable skillRow(int id, int... typeBits) {
        SkillTable.Builder row = SkillTable.newBuilder().setId(id);
        for (int bit : typeBits) {
            row.addSkillType(bit);
        }
        return row.build();
    }

    /** 手造的配表视图：技能可施放集合照 {@link SceneBattleTables#from} 的做法用引擎的判据算；职业 3 与职业 4 的初值不同。 */
    private static SceneBattleTables tables(Set<Integer> usableItems) {
        Set<Integer> skills = new HashSet<>();
        Set<Integer> castable = new HashSet<>();
        for (SkillTable row : SKILL_ROWS) {
            skills.add(row.getId());
            if (BattleRules.isTurnBattleCastableSkill(row)) {
                castable.add(row.getId());
            }
        }
        Map<Integer, ClassTable> classes = new HashMap<>();
        classes.put(3, ClassTable.newBuilder().setId(3).setInitArmor(7).setInitStrength(8).setInitCritchance(9).setInitResistance(10)
                .setInitHealth(70_000).setInitMana(80_000).setInitSpeed(90_000).build());
        classes.put(4, ClassTable.newBuilder().setId(4).setInitArmor(41).setInitStrength(42).setInitCritchance(43).setInitResistance(44)
                .build());
        return new SceneBattleTables(FINGERPRINT, skills, castable, usableItems, classes);
    }

    private static SceneBattleTables tables() {
        return tables(Set.of());
    }

    private static ScenePlayer player(int classId, int level, List<Integer> skills, PlayerState state) {
        ScenePlayer player = WorldTestAccess.player(PLAYER, classId, 2, "look-7", level, skills, "阿青", state);
        player.bags().normalize(ANY_ITEM);
        player.attributes().setDerived(new Derived(5_000, 3_000, 111, 222, 150, 333));
        player.attributes().setHealth(4_321);
        player.attributes().setMana(2_100);
        return player;
    }

    private static ScenePlayer player() {
        return player(3, 37, List.of(NORMAL), PlayerState.getDefaultInstance());
    }

    private static BattlePlayerSnapshot build(ScenePlayer player) {
        return BattleSnapshots.build(player, tables(), List.of(), ROUTING);
    }

    private static BagItemState stack(long guid, int configId, int size, int pos, BagType bag) {
        return BagItemState.newBuilder().setItemUuid(guid).setConfigId(configId).setStackSize(size).setPos(pos)
                .setBagType(bag.code()).setAcquireSeq(guid).build();
    }

    private static PlayerState bagOf(BagItemState... stacks) {
        return PlayerState.newBuilder().setBag(BagState.newBuilder().addAllItems(List.of(stacks))).build();
    }

    private static BattleItemEntry itemEntry(int configId, long count) {
        return BattleItemEntry.newBuilder().setItemTableId(configId).setCount(count).build();
    }

    // ------------------------------------------------------------------ 各字段来源

    /** 整份快照逐字段：身份取玩家实例，气血法力取当前值，上限与攻防取派生属性，护甲 / 力量 / 暴击 / 抗性取本职业行的初值，stamina 0，buffs 恒空。 */
    @Test
    void 各字段来源_整份快照逐字段相等() {
        BattlePetSnapshot pet = BattlePetSnapshot.newBuilder().setPetId(9_001).setOwnerPlayerId(PLAYER).setPetName("灵狐").build();

        BattlePlayerSnapshot snapshot = BattleSnapshots.build(player(), tables(), List.of(pet), ROUTING);

        assertThat(snapshot).isEqualTo(BattlePlayerSnapshot.newBuilder()
                .setPlayerId(PLAYER)
                .setPlayerName("阿青")
                .setAppearanceId("look-7")
                .setGender(2)
                .setClassId(3)
                .setLevel(37)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(4_321).setMana(2_100).setSpeed(150)
                        .setArmor(7).setStrength(8).setCritchance(9).setResistance(10))
                .setMaxHealth(5_000)
                .setMaxMana(3_000)
                .setPhysicalAttack(111)
                .setMagicAttack(222)
                .setDefense(333)
                .addPets(pet)
                .addSkillTableIds(NORMAL)
                .setRouting(ROUTING)
                .setTableFingerprint(FINGERPRINT)
                .build());
        assertThat(snapshot.getBaseAttributes().getStamina()).as("stamina 恒 0").isZero();
        assertThat(snapshot.getBuffsList()).as("Java 玩家身上没有实时 buff").isEmpty();
        assertThat(snapshot.getTeamIndex()).as("阵营由 match 改写，scene 恒填 0").isZero();
        assertThat(snapshot.getItemsList()).isEmpty();
    }

    @Test
    void 成长属性取玩家自己职业那一行的初值_不是别的职业的() {
        BattlePlayerSnapshot snapshot = build(player(4, 37, List.of(), PlayerState.getDefaultInstance()));

        assertThat(snapshot.getClassId()).isEqualTo(4);
        assertThat(snapshot.getBaseAttributes()).isEqualTo(BaseAttributesComp.newBuilder().setHealth(4_321).setMana(2_100).setSpeed(150)
                .setArmor(41).setStrength(42).setCritchance(43).setResistance(44).build());
    }

    @Test
    void 职业行缺失_成长属性为0_其余照常() {
        BattlePlayerSnapshot snapshot = build(player(77, 37, List.of(), PlayerState.getDefaultInstance()));

        assertThat(snapshot.getClassId()).isEqualTo(77);
        assertThat(snapshot.getBaseAttributes())
                .isEqualTo(BaseAttributesComp.newBuilder().setHealth(4_321).setMana(2_100).setSpeed(150).build());
        assertThat(snapshot.getMaxHealth()).isEqualTo(5_000);
    }

    @Test
    void 宝宝列表原样带上_没有宝宝就是空表() {
        BattlePetSnapshot pet = BattlePetSnapshot.newBuilder().setPetId(9_001).setOwnerPlayerId(PLAYER).setPetTableId(1)
                .addSkillTableIds(5).build();

        assertThat(BattleSnapshots.build(player(), tables(), List.of(pet), ROUTING).getPetsList()).containsExactly(pet);
        assertThat(BattleSnapshots.build(player(), tables(), List.of(), ROUTING).getPetsList()).isEmpty();
    }

    @Test
    void 路由原样带上_阵营恒为0_指纹取配表视图的() {
        BattleRouting other = BattleRouting.newBuilder().setSessionId(99).setGateNodeId(8).setGateInstanceId("gate-y")
                .setSceneNodeId(5).setSceneInstanceId("scene-y").setZoneId(9).build();

        BattlePlayerSnapshot snapshot = BattleSnapshots.build(player(), tables(), List.of(), other);

        assertThat(snapshot.getRouting()).isEqualTo(other);
        assertThat(snapshot.getTeamIndex()).isZero();
        assertThat(snapshot.getTableFingerprint()).isEqualTo(FINGERPRINT);
    }

    // ------------------------------------------------------------------ 兜底：速度、等级、上限

    @Test
    void 派生速度为0_取兜底120_非0原样() {
        assertThat(BattleSnapshots.FALLBACK_SPEED).isEqualTo(120);
        ScenePlayer slow = player();
        slow.attributes().setDerived(new Derived(5_000, 3_000, 111, 222, 0, 333));

        assertThat(build(slow).getBaseAttributes().getSpeed()).isEqualTo(120);

        ScenePlayer one = player();
        one.attributes().setDerived(new Derived(5_000, 3_000, 111, 222, 1, 333));
        assertThat(build(one).getBaseAttributes().getSpeed()).as("只有 0 才兜底").isEqualTo(1);
    }

    @Test
    void 等级至少为1() {
        assertThat(build(player(3, 0, List.of(), PlayerState.getDefaultInstance())).getLevel()).isEqualTo(1);
        assertThat(build(player(3, 1, List.of(), PlayerState.getDefaultInstance())).getLevel()).isEqualTo(1);
        assertThat(build(player(3, 2, List.of(), PlayerState.getDefaultInstance())).getLevel()).isEqualTo(2);
    }

    /** 派生上限为 0（缺配）时保守取当前值，不给引擎超治疗空间；当前值也是 0 时取 1（上限不能是 0）。气血、法力各自兜底。 */
    @Test
    void 上限兜底_派生上限为0时取当前值与1的较大者_大于0就用派生值() {
        ScenePlayer noMax = player();
        noMax.attributes().setDerived(new Derived(0, 0, 111, 222, 150, 333));
        noMax.attributes().setHealth(77);
        noMax.attributes().setMana(55);
        BattlePlayerSnapshot fromCurrent = build(noMax);
        assertThat(fromCurrent.getMaxHealth()).isEqualTo(77);
        assertThat(fromCurrent.getMaxMana()).isEqualTo(55);
        assertThat(fromCurrent.getBaseAttributes().getHealth()).isEqualTo(77);
        assertThat(fromCurrent.getBaseAttributes().getMana()).isEqualTo(55);

        noMax.attributes().setHealth(0);
        noMax.attributes().setMana(0);
        BattlePlayerSnapshot floor = build(noMax);
        assertThat(floor.getMaxHealth()).isEqualTo(1);
        assertThat(floor.getMaxMana()).isEqualTo(1);
        assertThat(floor.getBaseAttributes().getHealth()).as("当前值不改").isZero();

        ScenePlayer onlyManaMissing = player();
        onlyManaMissing.attributes().setDerived(new Derived(5_000, 0, 111, 222, 150, 333));
        BattlePlayerSnapshot mixed = build(onlyManaMissing);
        assertThat(mixed.getMaxHealth()).as("气血上限有派生值就用它，不看当前值").isEqualTo(5_000);
        assertThat(mixed.getMaxMana()).as("法力上限缺配，取当前法力").isEqualTo(2_100);
    }

    // ------------------------------------------------------------------ 技能

    /**
     * 技能保持玩家技能表的原顺序；跳过 0 与表里查不到行的；skill_type 任一位号是被动 0 / 持续施法 2 / 开关 3 就剔除（位号不是掩码）；
     * 空 skill_type 保留。基线不去重，Java 也不去重。
     */
    @Test
    void 技能_剔除被动开关持续施法_空skill_type保留_0与无表行跳过_保持原顺序() {
        List<Integer> owned = List.of(OTHER, 0, PASSIVE, NORMAL, NO_ROW, TOGGLE, EMPTY_TYPE, CHANNEL, NORMAL_AND_PASSIVE,
                OTHER_AND_TOGGLE, OTHER_AND_CHANNEL, NORMAL);

        BattlePlayerSnapshot snapshot = build(player(3, 37, owned, PlayerState.getDefaultInstance()));

        assertThat(snapshot.getSkillTableIdsList()).containsExactly(OTHER, NORMAL, EMPTY_TYPE, NORMAL);
    }

    @Test
    void 没有技能_技能列表为空() {
        assertThat(build(player(3, 37, List.of(), PlayerState.getDefaultInstance())).getSkillTableIdsCount()).isZero();
    }

    /**
     * 正式技能表（13 行）：3（持续施法）、4（开关）、6 与 12（含开关位）不能在回合制里施放，其余 9 个能；
     * {@link SceneBattleTables#from} 对每一行的判定与引擎的 {@code BattleRules.isTurnBattleCastableSkill} 相同。
     */
    @Test
    void 正式技能表_可施放集合与引擎判据一致_快照只带能施放的() {
        SceneBattleTables real = SceneBattleTables.from(BattleFixture.CONFIG);
        List<Integer> all = BattleFixture.CONFIG.skill().all().stream().map(SkillTable::getId).sorted().toList();
        assertThat(all).as("正式技能表的行（变了就要重看下面的期望）").containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        for (SkillTable row : BattleFixture.CONFIG.skill().all()) {
            assertThat(real.skillExists(row.getId())).isTrue();
            assertThat(real.castable(row.getId())).as("技能 %s skill_type=%s", row.getId(), row.getSkillTypeList())
                    .isEqualTo(BattleRules.isTurnBattleCastableSkill(row));
        }
        assertThat(real.skillExists(0)).isFalse();
        assertThat(real.skillExists(14)).isFalse();
        assertThat(real.castable(14)).as("表里没有的按不能").isFalse();

        ScenePlayer everything = player(3, 37, all, PlayerState.getDefaultInstance());
        BattlePlayerSnapshot snapshot = BattleSnapshots.build(everything, real, List.of(), ROUTING);

        assertThat(snapshot.getSkillTableIdsList()).containsExactly(1, 2, 5, 7, 8, 9, 10, 11, 13);
    }

    // ------------------------------------------------------------------ 道具

    /**
     * 道具副本：只取主背包（仓库、临时格都不算——临时格会先进先出淘汰，计进去会让结算少扣）；只取 battle_usable 的；
     * 同配置多堆合并；按 config_id <b>无符号</b>升序写出（含大于 2^31 的配置号）。
     */
    @Test
    void 道具_只取主背包_只取战斗可用_同配置合并_按配置号无符号升序() {
        Set<Integer> usable = Set.of(10, 11, 12, 13, HUGE_ITEM, MAX_ITEM);
        PlayerState state = bagOf(
                stack(1, 11, 5, 0, BagType.INVENTORY),
                stack(2, MAX_ITEM, 1, 1, BagType.INVENTORY),
                stack(3, 10, 3, 2, BagType.INVENTORY),
                stack(4, 9, 2, 3, BagType.INVENTORY),
                stack(5, HUGE_ITEM, 4, 4, BagType.INVENTORY),
                stack(6, 11, 7, 5, BagType.INVENTORY),
                stack(7, 77, 6, 6, BagType.INVENTORY),
                stack(20, 10, 9, 0, BagType.WAREHOUSE),
                stack(21, 12, 8, 1, BagType.WAREHOUSE),
                stack(30, 10, 50, 0, BagType.TEMPORARY),
                stack(31, 13, 5, 1, BagType.TEMPORARY));
        ScenePlayer player = player(3, 37, List.of(), state);
        assertThat(player.bags().bag(BagType.TEMPORARY).total(10)).as("临时格里确实有 50 个").isEqualTo(50);
        assertThat(player.bags().bag(BagType.WAREHOUSE).total(12)).isEqualTo(8);

        BattlePlayerSnapshot snapshot = BattleSnapshots.build(player, tables(usable), List.of(), ROUTING);

        assertThat(snapshot.getItemsList()).as("9 不是战斗道具；77 表里没有；12 只在仓库、13 只在临时格")
                .containsExactly(itemEntry(10, 3), itemEntry(11, 12), itemEntry(HUGE_ITEM, 4), itemEntry(MAX_ITEM, 1));
    }

    /** 数量为 0 的僵尸堆不进副本：只有僵尸堆的配置整项不出现（不是出现一条 count = 0）。 */
    @Test
    void 道具_数量为0的堆跳过_只有0数量堆的配置不出现() {
        PlayerState state = bagOf(stack(1, 10, 3, 0, BagType.INVENTORY), stack(2, 11, 6, 1, BagType.INVENTORY),
                stack(3, 10, 4, 2, BagType.INVENTORY));
        ScenePlayer player = player(3, 37, List.of(), state);
        BagTestAccess.setStackSize(player.bags().bag(BagType.INVENTORY).item(2), 0);
        BagTestAccess.setStackSize(player.bags().bag(BagType.INVENTORY).item(3), 0);

        BattlePlayerSnapshot snapshot = BattleSnapshots.build(player, tables(Set.of(10, 11)), List.of(), ROUTING);

        assertThat(snapshot.getItemsList()).containsExactly(itemEntry(10, 3));
    }

    /** 单堆数量是 uint32，合并后的总数按 uint64 累加，不在 2^32 处回绕。 */
    @Test
    void 道具_数量按uint64累加_超过2的32次方不回绕() {
        PlayerState state = bagOf(stack(1, 10, 0xFFFF_FFFF, 0, BagType.INVENTORY), stack(2, 10, 0xFFFF_FFFF, 1, BagType.INVENTORY),
                stack(3, 10, 0xFFFF_FFFF, 2, BagType.INVENTORY));

        BattlePlayerSnapshot snapshot = BattleSnapshots.build(player(3, 37, List.of(), state), tables(Set.of(10)), List.of(), ROUTING);

        assertThat(snapshot.getItemsList()).containsExactly(itemEntry(10, 3 * 4_294_967_295L));
    }

    @Test
    void 道具_主背包为空或没有战斗可用的_列表为空() {
        assertThat(BattleSnapshots.build(player(), tables(Set.of(10)), List.of(), ROUTING).getItemsCount()).isZero();
        PlayerState state = bagOf(stack(1, 9, 2, 0, BagType.INVENTORY));
        assertThat(BattleSnapshots.build(player(3, 37, List.of(), state), tables(Set.of(10)), List.of(), ROUTING).getItemsCount())
                .isZero();
    }

    /** 正式物品表里 battle_usable ≠ 0 的只有 10、11（快照与结算消耗共用这一个判据）。 */
    @Test
    void 正式物品表_战斗可用的判定() {
        SceneBattleTables real = SceneBattleTables.from(BattleFixture.CONFIG);

        assertThat(real.battleUsableItems()).containsExactlyInAnyOrder(10, 11);
        assertThat(real.battleUsable(10)).isTrue();
        assertThat(real.battleUsable(9)).isFalse();
        assertThat(real.battleUsable(0)).isFalse();
        assertThat(real.battleUsable(99_999)).as("表里没有的按不能").isFalse();
    }

    // ------------------------------------------------------------------ 正式配表 + 真进场

    /** 指纹与 battle 节点同源：scene 出的快照、备战应答、battle 的 {@code TableBattleData.fingerprint()} 三处同值。 */
    @Test
    void 指纹等于battle侧TableBattleData的指纹_快照与应答两处同值() {
        BattleFixture f = new BattleFixture();
        f.enter(SESSION, PLAYER);
        String battleSide = new TableBattleData(BattleFixture.CONFIG).fingerprint();

        PrepareBattleResponse response = f.prepared(PLAYER, 7);

        assertThat(f.tables.fingerprint()).isEqualTo(battleSide).matches("[0-9a-f]{32}");
        assertThat(response.getTableFingerprint()).isEqualTo(battleSide);
        assertThat(response.getSnapshot().getTableFingerprint()).isEqualTo(battleSide);
    }

    /**
     * 经真进场拿到的快照：名字来自 {@code PlayerData.name}（存档列 player.name），外观 / 性别 / 职业来自存档行，技能是进场发的初始技能，
     * 路由是会话与本节点，气血法力与上限取进场规整算出的属性，成长属性取正式职业表（9 个职业初值相同：护甲 120 / 力量 20 / 暴击 10 / 抗性 5）。
     */
    @Test
    void 真进场的玩家_名字来自PlayerData_路由是会话与本节点_属性取进场规整后的值() {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(SESSION, PLAYER);
        Derived derived = player.attributes().derived();
        assertThat(derived.maxHealth()).isPositive();
        assertThat(derived.speed()).isPositive();

        BattlePlayerSnapshot snapshot = f.prepared(PLAYER, 7).getSnapshot();

        assertThat(snapshot.getPlayerId()).isEqualTo(PLAYER);
        assertThat(snapshot.getPlayerName()).isEqualTo("玩家1001");
        assertThat(snapshot.getAppearanceId()).isEqualTo("look-1001");
        assertThat(snapshot.getGender()).isEqualTo(1);
        assertThat(snapshot.getClassId()).isEqualTo(3);
        assertThat(snapshot.getLevel()).isEqualTo(1);
        assertThat(snapshot.getBaseAttributes()).isEqualTo(BaseAttributesComp.newBuilder()
                .setHealth(derived.maxHealth()).setMana(derived.maxMana()).setSpeed(derived.speed())
                .setArmor(120).setStrength(20).setCritchance(10).setResistance(5).build());
        assertThat(snapshot.getMaxHealth()).isEqualTo(derived.maxHealth());
        assertThat(snapshot.getMaxMana()).isEqualTo(derived.maxMana());
        assertThat(snapshot.getPhysicalAttack()).isEqualTo(derived.physicalAttack());
        assertThat(snapshot.getMagicAttack()).isEqualTo(derived.magicAttack());
        assertThat(snapshot.getDefense()).isEqualTo(derived.defense());
        assertThat(snapshot.getSkillTableIdsList()).as("初始技能 1、2、13 都能施放").containsExactly(1, 2, 13);
        assertThat(snapshot.getRouting()).isEqualTo(BattleRouting.newBuilder().setSessionId(SESSION)
                .setGateNodeId(BattleFixture.GATE_NODE).setGateInstanceId(BattleFixture.GATE_INSTANCE)
                .setSceneNodeId(BattleFixture.LOCAL_NODE).setSceneInstanceId(BattleFixture.SCENE_INSTANCE)
                .setZoneId(BattleFixture.ZONE).build());
        assertThat(snapshot.getTeamIndex()).isZero();
        assertThat(snapshot.getBuffsList()).isEmpty();
        assertThat(snapshot.getPetsList()).isEmpty();
        assertThat(snapshot.getItemsList()).isEmpty();
    }

    @Test
    void 没有角色名的玩家_名字为空串_不拦开战() {
        ScenePlayer nameless = WorldTestAccess.player(PLAYER, 3, 1, "", 1, List.of(), "", PlayerState.getDefaultInstance());
        nameless.attributes().setDerived(new Derived(100, 100, 1, 1, 100, 1));

        BattlePlayerSnapshot snapshot = build(nameless);

        assertThat(snapshot.getPlayerName()).isEmpty();
        assertThat(snapshot.getAppearanceId()).isEmpty();
        assertThat(snapshot.getPlayerId()).isEqualTo(PLAYER);
    }

    /** 装配：备战的快照带上背包里的战斗道具（正式物品表：10、11 可用，9 不可用）与出战宝宝（经 {@code PetService.buildBattleSnapshot}）。 */
    @Test
    void 真进场的玩家_快照带主背包的战斗道具与出战宝宝() {
        BattleFixture f = new BattleFixture();
        PlayerState state = PlayerState.newBuilder()
                .setBag(BagState.newBuilder()
                        .addItems(stack(501, 11, 2, 0, BagType.INVENTORY))
                        .addItems(stack(502, 10, 3, 1, BagType.INVENTORY))
                        .addItems(stack(503, 9, 2, 2, BagType.INVENTORY))
                        .addItems(stack(504, 10, 4, 3, BagType.INVENTORY))
                        .addItems(stack(505, 10, 50, 0, BagType.TEMPORARY)))
                .setPets(PetState.newBuilder().setActivePetId(9_001)
                        .addPets(PetEntry.newBuilder().setPetId(9_001).setPetTableId(1).setLevel(1).setHealth(100).setMana(50)))
                .build();
        f.enter(SESSION, PLAYER, state);

        BattlePlayerSnapshot snapshot = f.prepared(PLAYER, 7).getSnapshot();

        assertThat(snapshot.getItemsList()).containsExactly(itemEntry(10, 7), itemEntry(11, 2));
        assertThat(snapshot.getPetsList()).singleElement().satisfies(pet -> {
            assertThat(pet.getPetId()).isEqualTo(9_001);
            assertThat(pet.getOwnerPlayerId()).isEqualTo(PLAYER);
            assertThat(pet.getPetTableId()).isEqualTo(1);
            assertThat(pet.getPetName()).isEqualTo("灵狐");
        });
    }
}
