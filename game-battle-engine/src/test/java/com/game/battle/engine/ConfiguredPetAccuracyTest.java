package com.game.battle.engine;

import static com.game.battle.engine.BattleEffect.*;
import static com.game.battle.engine.TestBattles.*;
import static com.game.proto.eBattleActionType.*;
import static com.game.proto.eBattleEventType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.PetCultivationEffects;
import org.junit.jupiter.api.Test;

/** 确定性种子复用同一动作、目标和首个概率点，逐类检查命中及抵抗的正反方向和类型隔离。 */
class ConfiguredPetAccuracyTest {
    private enum Investment { NONE, HIT, RESIST, OTHER_HIT }

    @Test void 七类命中七类抵抗和劝退的命中抵抗仅消费目标类别() {
        for (int kind : new int[]{SEAL_ACTIVE, SEAL_MAGIC, SEAL_PHYSICAL, CONFUSION, SLEEP, FREEZE, POISON, 9}) {
            var tables = new MemoryBattleData();
            tables.addSkill(901).setBattleModel(1).setCasterMask(2).setAbilityKind(1).setTargetTeam(1)
                    .setUnlockLevel(1).setMaxRank(10).setPetMaxTargets(1).addEffect(902);
            tables.addBuff(902).setBattleOp(kind == 9 ? RETREAT : kind == POISON ? PERIODIC : CONTROL)
                    .setStateKind(kind).setApplicationChance(0.5).setDurationRounds(2).setTickCount(2);
            int[] successes = new int[Investment.values().length];
            for (int seed = 1; seed <= 32; seed++) {
                for (var investment : Investment.values()) {
                    if (success(tables,kind,investment,seed)) successes[investment.ordinal()]++;
                }
            }
            assertThat(successes[Investment.HIT.ordinal()]).as("类别 %s 命中",kind).isGreaterThan(successes[0]);
            assertThat(successes[Investment.RESIST.ordinal()]).as("类别 %s 抵抗",kind).isLessThan(successes[0]);
            assertThat(successes[Investment.OTHER_HIT.ordinal()]).as("类别 %s 不读异类",kind).isEqualTo(successes[0]);
        }
    }

    private static boolean success(MemoryBattleData tables, int kind, Investment investment, long seed) {
        var request = request(3,seed);
        var owner = addPlayer(request,101,0,10000,10000,0,0,0,1);
        var enemy = addPlayer(request,201,1,10000,10000,0,0,0,1);
        var source = addPet(owner,1001,1000,1000,0,200);
        var target = addPet(enemy,1002,1000,1000,0,100);
        source.putSkillRanks(901,1).addSkillTableIds(901);
        PetCultivationEffects.Builder attacking = source.getPetCultivationBuilder();
        PetCultivationEffects.Builder defending = target.getPetCultivationBuilder();
        if (investment == Investment.HIT) {
            if (kind == 9) attacking.setRetreatHitBps(3000);
            else attacking.putAilmentHitBps(kind,3000);
        } else if (investment == Investment.RESIST) {
            if (kind == 9) defending.setRetreatResistBps(3000);
            else defending.putAilmentResistBps(kind,3000);
        } else if (investment == Investment.OTHER_HIT) {
            attacking.putAilmentHitBps(kind % 7 + 1,3000);
        }
        var engine = start(request,tables);
        var state = engine.buildStateSnapshot();
        long sourceId = petActor(state,1001).getActorId(), targetId = petActor(state,1002).getActorId();
        for (var unit : state.getActorsList()) {
            engine.submitOwnedAction(unit.getOwnerPlayerId() == 0 ? unit.getActorId() : unit.getOwnerPlayerId(),
                    unit.getActorId(), action(BATTLE_ACTION_DEFEND));
        }
        engine.submitOwnedAction(101,sourceId,action(BATTLE_ACTION_SKILL,targetId,901));
        return engine.resolveCurrentRound().getEventsList().stream().anyMatch(event ->
                event.getSourceId() == sourceId && event.getTargetId() == targetId &&
                (kind == 9 ? event.getEventType() == BATTLE_EVENT_PET_RETREAT && event.getSuccess()
                        : event.getEventType() == BATTLE_EVENT_BUFF_ADD && event.getBuffTableId() == 902));
    }
}
