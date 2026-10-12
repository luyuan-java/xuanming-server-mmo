package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.common.math.Unsigned;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleActorState;
import com.game.proto.eBattleActorType;

/**
 * 一个战斗单位（玩家 / 宝宝 / 怪物）：包着一个 {@link BattleActorState.Builder}，它就是该单位的<strong>权威状态</strong>
 * （规格 §1.1、§10.4；基线把每个单位的状态直接放在 {@code BattleActorState} proto 里，{@code engine.h:218-221}）。
 *
 * <p>不另写平行结构：快照里有些字段规则从不读（name、appearance_id、attributes.stamina、值为 0 的冷却项等），
 * 但必须原样带出，包 builder 才不会漏。快照时 {@link #snapshot()} 调 {@code build()} 交出不可变副本，可变 builder 不外泄。
 *
 * <p>数值口径（规格 §10.5）：uint64 用 {@code long}、uint32 用 {@code int} 承载，按无符号解释；本类的访问器只是取位模式，
 * 比较、换 double 由调用方按无符号做。
 *
 * <p>{@code attributes} 子 builder 在构造时取出并缓存：开局时已经整体设好（玩家 / 宝宝拷快照、怪物填表值，都带 presence，
 * 同基线 {@code mutable_attributes()}），之后只改字段、不再整体替换，所以缓存的引用一直有效。
 */
final class BattleUnit {

    private final BattleActorState.Builder state;
    private final BaseAttributesComp.Builder attributes;

    BattleUnit(BattleActorState.Builder state) {
        this.state = state;
        this.attributes = state.getAttributesBuilder();
    }

    /** 权威状态本体（包内的 buff / 冷却 / 技能列表操作直接用它）。 */
    BattleActorState.Builder state() {
        return state;
    }

    /** 不可变副本（快照用，规格 §7.1）。 */
    BattleActorState snapshot() {
        return state.build();
    }

    // ---- 身份 ----

    long actorId() {
        return state.getActorId();
    }

    eBattleActorType actorType() {
        return state.getActorType();
    }

    boolean isPlayer() {
        return state.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER;
    }

    boolean isPet() {
        return state.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET;
    }

    boolean isMonster() {
        return state.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER;
    }

    /** uint32；只会是 0（A 方）或 1（B 方）。 */
    int teamIndex() {
        return state.getTeamIndex();
    }

    /** uint32。 */
    int level() {
        return state.getLevel();
    }

    long ownerPlayerId() {
        return state.getOwnerPlayerId();
    }

    long petId() {
        return state.getPetId();
    }

    /** uint32；兜底怪为 0。 */
    int monsterTableId() {
        return state.getMonsterTableId();
    }

    // ---- 属性（uint64） ----

    long health() {
        return attributes.getHealth();
    }

    void setHealth(long health) {
        attributes.setHealth(health);
    }

    long mana() {
        return attributes.getMana();
    }

    void setMana(long mana) {
        attributes.setMana(mana);
    }

    long maxHealth() {
        return state.getMaxHealth();
    }

    long maxMana() {
        return state.getMaxMana();
    }

    long speed() {
        return attributes.getSpeed();
    }

    long strength() {
        return attributes.getStrength();
    }

    long armor() {
        return attributes.getArmor();
    }

    long resistance() {
        return attributes.getResistance();
    }

    long critChance() {
        return attributes.getCritchance();
    }

    long physicalAttack() {
        return state.getPhysicalAttack();
    }

    long magicAttack() {
        return state.getMagicAttack();
    }

    long defense() {
        return state.getDefense();
    }

    // ---- 状态位 ----

    boolean isDead() {
        return state.getIsDead();
    }

    boolean fled() {
        return state.getFled();
    }

    /** 存活且未逃跑（{@code IsActorActive}，{@code engine.cpp:1722-1724}）。 */
    boolean isActive() {
        return !state.getIsDead() && !state.getFled();
    }

    boolean isDefending() {
        return state.getIsDefending();
    }

    void setDefending(boolean defending) {
        state.setIsDefending(defending);
    }

    boolean isAuto() {
        return state.getIsAuto();
    }

    void setAuto(boolean auto) {
        state.setIsAuto(auto);
    }

    void markFled() {
        state.setFled(true);
    }

    // ---- buff 列表 ----

    /** 按实例号找下标，找不到返回 -1（{@code FindBuffIndex}，{@code engine.cpp:1713-1720}）。条目会增删，调用方每次都要重查。 */
    int findBuffIndex(long buffId) {
        int count = state.getBuffsCount();
        for (int index = 0; index < count; index++) {
            if (state.getBuffsOrBuilder(index).getBuffId() == buffId) {
                return index;
            }
        }
        return -1;
    }

    // ---- 伤害 / 治疗原语 ----

    /**
     * 落伤害（{@code ApplyDamage}，{@code engine.cpp:1345-1359}）：raw ≤ 0 不扣；防御中减半；之后与实时侧共用落血规则
     * （向上取整、封顶到当前气血、非有限数不扣）。返回实扣（uint64）。
     *
     * <p>NaN 不进 {@code raw <= 0} 分支，由 {@link CombatDamageRules#damageToHealth} 对非有限值返回 0，与基线相同。
     */
    long applyDamage(double rawDamage) {
        if (rawDamage <= 0) {
            return 0;
        }
        if (state.getIsDefending()) {
            rawDamage *= 0.5;
        }
        long healthBefore = attributes.getHealth();
        long damage = CombatDamageRules.damageToHealth(rawDamage, healthBefore);
        attributes.setHealth(healthBefore - damage);
        return damage;
    }

    /**
     * 回血（{@code ApplyHeal}，{@code engine.cpp:1361-1371}）：{@code after = min(max_health, (uint64)(当前 + raw))}，
     * 返回 {@code after - before}（uint64 减法）。当前气血超过上限时差值回绕成巨大值，是基线行为，照搬（规格 §11.1 第 8 条）。
     *
     * <p>有意差异 D5：C++ 对 NaN、无穷与 ≥ 2^64 的和做 {@code static_cast<uint64_t>} 是未定义行为；这里非有限的 raw 按 0 处理
     * （不回血、返回 0），和 ≥ 2^64 时饱和到 UINT64_MAX 再与上限取小。
     */
    long applyHeal(double rawHeal) {
        if (rawHeal <= 0 || state.getIsDead()) {
            return 0;
        }
        if (!Double.isFinite(rawHeal)) {
            return 0; // D5
        }
        long healthBefore = attributes.getHealth();
        long healthAfter = Unsigned.minUnsigned(state.getMaxHealth(),
                Unsigned.fromDoubleSaturating(Unsigned.toDouble(healthBefore) + rawHeal));
        attributes.setHealth(healthAfter);
        return healthAfter - healthBefore;
    }
}
