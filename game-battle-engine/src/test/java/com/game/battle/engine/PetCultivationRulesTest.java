package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.*;
import static com.game.battle.engine.TestTables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DAMAGE;
import com.game.proto.BattleActorState;
import com.game.proto.PetCultivationEffects;
import com.game.proto.eBattleActorType;
import org.junit.jupiter.api.Test;

class PetCultivationRulesTest {
    @Test
    void onlyPetReceivesIndependentDirectChannels() {
        var builder=BattleActorState.newBuilder().setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PET)
                .setPetCultivation(PetCultivationEffects.newBuilder().setPhysicalDamageBps(500).setMagicDamageBps(700));
        var unit=new BattleUnit(builder);
        assertThat(PetCultivationRules.damageBonus(unit,1)).isEqualTo(.05);
        assertThat(PetCultivationRules.damageBonus(unit,0)).isEqualTo(.07);
        builder.setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER);
        assertThat(PetCultivationRules.damageBonus(unit,1)).isZero();
    }
    @Test
    void savedSnapshotChangesPetBasicAndLegacyMagicOnce() {
        for(boolean magic:new boolean[]{false,true}) {
            long[] damages=new long[2];
            for(int enhanced=0;enhanced<2;enhanced++) {
                var request=request(89001,3,19);
                var owner=addPlayer(request,PLAYER_A,0,10000,10000,0,10,0,10);
                var pet=addPet(owner,PET_A,1000,1000,0,200,100);
                pet.setMagicAttack(100);pet.setMaxMana(100);pet.getBaseAttributesBuilder().setMana(100);
                if(magic)pet.addSkillTableIds(SKILL_DAMAGE);
                if(enhanced==1)pet.setPetCultivation(PetCultivationEffects.newBuilder().setPhysicalDamageBps(1000).setMagicDamageBps(1000));
                addPlayer(request,PLAYER_B,1,10000,10000,0,10,0,10);
                var engine=TestBattles.start(request,standard());engine.submitAction(PLAYER_A,action(BATTLE_ACTION_DEFEND));engine.submitAction(PLAYER_B,action(BATTLE_ACTION_DEFEND));
                var result=engine.resolveCurrentRound();
                damages[enhanced]=result.getEventsList().stream().filter(e->e.getSourceId()==BattleConstants.PET_ACTOR_ID_BASE && e.getEventType()==BATTLE_EVENT_DAMAGE).mapToLong(e->e.getValue()).sum();
            }
            assertThat(damages[0]).isPositive();assertThat(damages[1]).isGreaterThan(damages[0]);
            assertThat(Math.abs(damages[1]-damages[0]*1.1)).isLessThanOrEqualTo(2.0);
        }
    }
}
