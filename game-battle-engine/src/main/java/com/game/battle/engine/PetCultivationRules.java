package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.eBattleActorType;

/** 宝宝独立条目快照的基础直伤/治疗渠道，不读取主人装备。 */
final class PetCultivationRules {
    private PetCultivationRules() { }
    static double rate(int bps) { return Math.min(Integer.toUnsignedLong(bps), 10000L) / 10000.0; }
    static double damageBonus(BattleUnit actor, int damageType) {
        if (actor.actorType() != eBattleActorType.BATTLE_ACTOR_TYPE_PET) return 0;
        var effects = actor.state().getPetCultivation();
        return damageType == CombatDamageRules.PHYSICAL_DAMAGE ? rate(effects.getPhysicalDamageBps()) :
                damageType == CombatDamageRules.MAGIC_DAMAGE ? rate(effects.getMagicDamageBps()) : 0;
    }
    static double healBonus(BattleUnit actor) {
        return actor.actorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET ? rate(actor.state().getPetCultivation().getOutgoingHealBps()) : 0;
    }
}
