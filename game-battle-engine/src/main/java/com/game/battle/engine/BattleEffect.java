package com.game.battle.engine;

/** Skill/Buff 配表枚举；所有机制按操作、目标、修正类型派发。 */
final class BattleEffect {
    private BattleEffect() { }

    static final int DAMAGE = 1, HEAL = 2, MANA = 3, DISPEL_DEBUFF = 4, DISPEL_BUFF = 5,
            MODIFIER = 6, PERIODIC = 7, REVIVE = 8, SHIELD = 9, MANA_SHIELD = 10, SHARE = 11,
            REFLECT = 12, CONTROL = 13, CONSUME = 14, EXTEND = 15, DRAIN = 16, RETREAT = 17,
            TEMP_HEALTH = 18, MIRROR = 19, PURSUIT = 20, CHILDREN = 21, CONDITIONAL_HEAL = 22,
            CONDITIONAL_MANA = 23, EVASION_CHARGE = 24, AURA = 25;
    static final int PHYSICAL_ATTACK = 1, MAGIC_ATTACK = 2, DEFENSE = 3, SPEED = 4,
            OUT_DAMAGE = 5, IN_HEAL = 6, EVASION = 7, OUT_HEAL = 8, MANA_COST = 9,
            MAGIC_REDUCTION = 10, RETREAT_RESIST = 11, CONTROL_RESIST = 12, LATER_REDUCTION = 13,
            NEXT_DAMAGE = 14, ADAPTIVE = 15, IN_DAMAGE = 16, OUT_SHIELD = 17,
            OUT_PHYSICAL_DAMAGE = 18, OUT_MAGIC_DAMAGE = 19, SELF_GROWING_DEFENSE = 20;
    static final int AFTER_BASIC = 1, AFTER_SKILL = 2, AFTER_TAKE = 3, AFTER_PERIODIC = 4, AFTER_DODGE = 5, ROUND_START = 6;
    static final int DIRECT = 0, DOT = 1, REFLECTED = 2, REDIRECT = 3, COST = 4, PURSUED = 5,
            MIRRORED = 6, CONFUSED = 7;
    static final int SEAL_ACTIVE = 1, SEAL_MAGIC = 2, SEAL_PHYSICAL = 3, CONFUSION = 4,
            SLEEP = 5, FREEZE = 6, POISON = 7, BURN = 8;

    static boolean instant(int op) {
        return switch (op) {
            case DAMAGE, HEAL, MANA, DISPEL_DEBUFF, DISPEL_BUFF, CONSUME, EXTEND, DRAIN, RETREAT -> true;
            default -> false;
        };
    }

    /** 一次目标的局部结算上下文；派生事件单独复制，不污染原动作的段序与归属。 */
    static final class Hit {
        int skill, rank = 1, origin, damageOrdinal, healOrdinal, shieldOrdinal, periodicState;
        long primary, paidHealth, paidMana, actualDamage, instigator;
        double scale = 1;
        boolean critical;

        Hit(int skill, int rank, int origin, long primary) {
            this.skill = skill;
            this.rank = rank;
            this.origin = origin;
            this.primary = primary;
        }

        Hit derived(int newOrigin) {
            Hit next = new Hit(skill, rank, newOrigin, primary);
            next.paidHealth = paidHealth;
            next.paidMana = paidMana;
            next.actualDamage = actualDamage;
            next.instigator = instigator;
            next.periodicState = periodicState;
            next.scale = scale;
            next.damageOrdinal = damageOrdinal;
            next.healOrdinal = healOrdinal;
            next.shieldOrdinal = shieldOrdinal;
            return next;
        }
    }
}
