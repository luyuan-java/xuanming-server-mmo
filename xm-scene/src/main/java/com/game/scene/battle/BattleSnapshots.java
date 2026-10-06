package com.game.scene.battle;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerAttributes;
import com.game.scene.world.ScenePlayer;
import com.game.table.ClassTable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家战斗快照的纯组装（基线 {@code BuildBattleSnapshot}，{@code pb.cpp:885-1103}；scene-battle-spec §1.3、§7.11 快照表）。逻辑线程上调用，不改状态。
 */
public final class BattleSnapshots {

    private static final Logger log = LoggerFactory.getLogger(BattleSnapshots.class);

    /** 派生速度为 0 时的兜底（基线 {@code pb.cpp:99-102}，2026-09-14 单位 ×12）。 */
    public static final long FALLBACK_SPEED = 120;

    private BattleSnapshots() {
    }

    /**
     * 组快照。
     *
     * @param pets    出战宝宝的快照（{@code PetService.buildBattleSnapshot}，至多一只）
     * @param routing 路由（会话、gate 节点与实例、scene 节点与实例、zone）
     */
    public static BattlePlayerSnapshot build(ScenePlayer player, SceneBattleTables tables, List<BattlePetSnapshot> pets,
                                             BattleRouting routing) {
        PlayerAttributes attributes = player.attributes();
        PlayerAttributes.Derived derived = attributes.derived();
        long health = attributes.health();
        long mana = attributes.mana();
        long speed = derived.speed();
        if (speed == 0) {
            log.warn("派生速度为 0，快照取兜底 {} player={}", FALLBACK_SPEED, Long.toUnsignedString(player.playerId()));
            speed = FALLBACK_SPEED;
        }
        BaseAttributesComp.Builder base = BaseAttributesComp.newBuilder().setHealth(health).setMana(mana).setSpeed(speed);
        ClassTable classRow = tables.classRow(player.classId());
        if (classRow != null) {
            // D8：成长属性不存档，取真实职业行的初值（现表 9 个职业初值相同，没有数值差异）；stamina 恒 0
            base.setArmor(classRow.getInitArmor())
                    .setStrength(classRow.getInitStrength())
                    .setCritchance(classRow.getInitCritchance())
                    .setResistance(classRow.getInitResistance());
        }
        BattlePlayerSnapshot.Builder snapshot = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(player.playerId())
                .setPlayerName(player.name())
                .setAppearanceId(player.appearanceId())
                .setGender(player.gender())
                .setClassId(player.classId())
                .setLevel(Math.max(player.level(), 1))
                .setBaseAttributes(base)
                .setMaxHealth(derived.maxHealth() > 0 ? derived.maxHealth() : Math.max(health, 1))
                .setMaxMana(derived.maxMana() > 0 ? derived.maxMana() : Math.max(mana, 1))
                .setPhysicalAttack(derived.physicalAttack())
                .setMagicAttack(derived.magicAttack())
                .setDefense(derived.defense())
                .addAllPets(pets)
                .setRouting(routing)
                .setTeamIndex(0)
                .setTableFingerprint(tables.fingerprint());
        for (int skill : player.skills()) {
            // 原顺序；跳过 0 与查不到行的；剔除被动 / 持续施法 / 开关（引擎再过滤一次）
            if (skill != 0 && tables.skillExists(skill) && tables.castable(skill)) {
                snapshot.addSkillTableIds(skill);
            }
        }
        // 只取主背包；跳过数量 0 的堆；只取 battle_usable；按 config_id 无符号升序合并，数量 uint64 累加
        Map<Integer, Long> items = new TreeMap<>(Integer::compareUnsigned);
        for (BagItem item : player.bags().bag(BagType.INVENTORY).items()) {
            if (item.size() == 0 || !tables.battleUsable(item.configId())) {
                continue;
            }
            items.merge(item.configId(), item.size(), Long::sum);
        }
        items.forEach((config, count) -> snapshot.addItems(BattleItemEntry.newBuilder().setItemTableId(config).setCount(count)));
        return snapshot.build();
    }
}
