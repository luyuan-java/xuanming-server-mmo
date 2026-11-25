package com.game.battle.engine;

import static com.game.proto.eBattleActionType.*;
import static com.game.proto.eBattleEventType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.*;
import com.game.table.ConfigTables;
import com.game.table.SkillTable;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfiguredBattleCatalogTest {
    private static final ConfigTables TABLES = ConfigTables.load(Path.of("../config-data/tables"));
    private static final TableBattleData DATA = new TableBattleData(TABLES);
    private static final long SELF = 101, ALLY = 102, ENEMY = 201;

    private static CreateBattleRequest.Builder request() {
        var request = TestBattles.request(3,20261117);
        for (long id : new long[]{SELF,ALLY,ENEMY,202}) {
            var player = TestBattles.addPlayer(request,id,id < ENEMY ? 0 : 1,5000,10000,0,0,0,id < ENEMY ? 200 : 50);
            player.setLevel(30).setMaxMana(10000).setMagicAttack(600).setPhysicalAttack(500).clearSkillTableIds();
            player.getBaseAttributesBuilder().setMana(10000);
            var pet = TestBattles.addPet(player,id+1000,5000,10000,0,100,500);
            pet.setMaxMana(10000).setMagicAttack(600).getBaseAttributesBuilder().setMana(10000);
        }
        return request;
    }

    private static void learn(CreateBattleRequest.Builder request, int skill, boolean pet) {
        var owner = request.getPlayersBuilder(0);
        if (pet) owner.getPetsBuilder(0).putSkillRanks(skill,1).addSkillTableIds(skill);
        else owner.putSkillRanks(skill,1).addSkillTableIds(skill);
    }

    private static long pet(TurnBattleEngine engine, long owner) {
        return TestBattles.petActor(engine.buildStateSnapshot(),owner+1000).getActorId();
    }

    private static TurnResultS2C cast(TurnBattleEngine engine, int id, boolean pet) {
        var skill = DATA.skill(id).orElseThrow();
        long actor = pet ? pet(engine,SELF) : SELF;
        long target = skill.getTargetTeam() == 3 ? actor : skill.getTargetTeam() == 1 ? pet(engine,ENEMY) : ALLY;
        for (var unit : engine.buildStateSnapshot().getActorsList()) {
            long owner = unit.getOwnerPlayerId() == 0 ? unit.getActorId() : unit.getOwnerPlayerId();
            engine.submitOwnedAction(owner,unit.getActorId(),TestBattles.action(BATTLE_ACTION_DEFEND));
        }
        var action = TestBattles.action(BATTLE_ACTION_SKILL,target,id);
        assertThat(engine.validateOwnedAction(SELF,actor,action)).as("技能 %s，主体 %s",id,actor).isEqualTo(ActionChecks.SUCCESS);
        engine.submitOwnedAction(SELF,actor,action);
        return engine.resolveCurrentRound();
    }

    @Test void 宝宝群攻按两目标上限保留主目标且相同种子可回放() {
        var request = request();
        learn(request,10002,true);
        var first = TestBattles.start(request,DATA);
        var replay = TestBattles.start(request,DATA);
        long source = pet(first,SELF), selected = pet(first,ENEMY);
        var result = cast(first,10002,true);
        assertThat(result).isEqualTo(cast(replay,10002,true));
        var hits = result.getEventsList().stream().filter(e -> e.getEventType() == BATTLE_EVENT_DAMAGE && e.getSourceId() == source
                && e.getSkillTableId() == 10002).map(BattleEventItem::getTargetId).toList();
        assertThat(hits).hasSize(2).doesNotHaveDuplicates().contains(selected);
    }

    @Test void 复苏印在全场伤害后复活同一单位并且只消耗死者的印() {
        var request = request();
        learn(request,10026,false);
        request.getPlayersBuilder(1).getBaseAttributesBuilder().setHealth(1);
        request.getPlayersBuilder(2).putSkillRanks(10002,1).addSkillTableIds(10002);
        var engine = TestBattles.start(request,DATA);
        for (var unit : engine.buildStateSnapshot().getActorsList()) {
            engine.submitOwnedAction(unit.getOwnerPlayerId() != 0 ? unit.getOwnerPlayerId() : unit.getActorId(),
                    unit.getActorId(),TestBattles.action(BATTLE_ACTION_DEFEND));
        }
        engine.submitOwnedAction(SELF,SELF,TestBattles.action(BATTLE_ACTION_SKILL,ALLY,10026));
        engine.submitOwnedAction(ENEMY,ENEMY,TestBattles.action(BATTLE_ACTION_SKILL,ALLY,10002));
        var result = engine.resolveCurrentRound();
        var events = result.getEventsList().stream().filter(e -> e.getTargetId() == ALLY).map(BattleEventItem::getEventType).toList();
        assertThat(events).containsSubsequence(BATTLE_EVENT_DEATH,BATTLE_EVENT_REVIVE);
        var ally = TestBattles.stateActor(result.getState(),ALLY);
        assertThat(ally.getIsDead()).isFalse();
        assertThat(ally.getAttributes().getHealth()).isPositive();
        assertThat(ally.getBuffsList()).noneMatch(b -> b.getBuffTableId() == 100260);
    }

    @Test void 主动牺牲先验证目标后支付且施法者死亡不会取消已接受的治疗() {
        var request = request();
        request.getPlayersBuilder(1).getBaseAttributesBuilder().setHealth(1000);
        learn(request,11016,true);
        var engine = TestBattles.start(request,DATA);
        long source = pet(engine,SELF);
        assertThat(engine.validateOwnedAction(SELF,source,TestBattles.action(BATTLE_ACTION_SKILL,source,11016))).isEqualTo(ActionChecks.SKILL_INVALID_TARGET);
        var result = cast(engine,11016,true);
        var pet = TestBattles.stateActor(result.getState(),source);
        assertThat(pet.getIsDead()).isTrue();
        assertThat(pet.getAbilityUseCountsOrDefault(11016,0)).isEqualTo(1);
        assertThat(TestBattles.stateActor(result.getState(),ALLY).getAttributes().getHealth()).isEqualTo(7000);
    }

    @Test void 入场原件的非退役未知正重使两种来源标为不完整且不能施放() {
        for (int id : new int[]{10007, 999999}) {
            var request = request();
            var owner = request.getPlayersBuilder(0);
            owner.getEquipmentEffectsBuilder().setSupportSourcesComplete(true);
            owner.putSkillRanks(id, 2).addSkillTableIds(id);
            var engine = TestBattles.start(request, DATA);
            var state = TestBattles.stateActor(engine.buildStateSnapshot(), SELF);
            assertThat(state.getSupportEffects().getHealing().getComplete()).isEqualTo(id == 10007);
            assertThat(state.getSupportEffects().getShield().getComplete()).isEqualTo(id == 10007);
            assertThat(state.getSkillTableIdsList()).doesNotContain(id);
            assertThat(state.getSkillRanksMap()).doesNotContainKey(id);
            assertThat(owner.getSkillRanksOrThrow(id)).isEqualTo(2);
        }
    }

    @Test void 宝宝治疗和毒跳各取自己的已存条目且不继承主人加成() {
        for (int skill : new int[]{10010, 10019}) {
            var request = request();
            learn(request,skill,true);
            request.getPlayersBuilder(0).getEquipmentEffectsBuilder().setOutgoingHealBps(3200);
            long[] amounts = new long[2];
            for (int run = 0; run < 2; run++) {
                var cultivation = request.getPlayersBuilder(0).getPetsBuilder(0).getPetCultivationBuilder();
                cultivation.setOutgoingHealBps(run * 1000).setPoisonDamageBps(run * 1000).setMagicDamageBps(10000);
                for (int seed = 1; seed <= 32 && amounts[run] == 0; seed++) {
                    var engine = TestBattles.start(request.setSeed(seed),DATA);
                    long pet = pet(engine,SELF);
                    var result = cast(engine,skill,true);
                    var event = result.getEventsList().stream().filter(e -> e.getSourceId() == pet && e.getSkillTableId() == skill &&
                            (skill == 10010 ? e.getEventType() == BATTLE_EVENT_HEAL
                                    : e.getEventType() == BATTLE_EVENT_DAMAGE && e.getDamageOrigin() == BattleEffect.DOT)).findFirst();
                    if (event.isPresent()) amounts[run] = event.get().getValue();
                }
            }
            // 等级30的 A10 = 20×30。A19 快照 = 2×30+0.25×600，目标先防御，伤害向上取整。
            assertThat(amounts).containsExactly(skill == 10010 ? 600L : 105L,skill == 10010 ? 660L : 116L);
        }
    }

    @Test void 普攻触发宝宝已学监听并在本次行动组挂载配置子效果() {
        var request = request();
        request.getPlayersBuilder(0).getPetsBuilder(0).putSkillRanks(13005,1);
        var engine = TestBattles.start(request,DATA);
        long pet = pet(engine,SELF), enemy = pet(engine,ENEMY);
        assertThat(TestBattles.stateActor(engine.buildStateSnapshot(),pet).getBuffsList())
                .anyMatch(b -> b.getSourceSkillId() == 13005 && b.getBuffTableId() == 130050);
        for (var unit : engine.buildStateSnapshot().getActorsList()) {
            engine.submitOwnedAction(unit.getOwnerPlayerId() == 0 ? unit.getActorId() : unit.getOwnerPlayerId(),
                    unit.getActorId(),TestBattles.action(BATTLE_ACTION_DEFEND));
        }
        engine.submitOwnedAction(SELF,pet,TestBattles.action(BATTLE_ACTION_ATTACK,enemy));
        var events = engine.resolveCurrentRound().getEventsList();
        var attack = events.stream().filter(e -> e.getEventType() == BATTLE_EVENT_ATTACK && e.getSourceId() == pet).findFirst().orElseThrow();
        assertThat(events).anyMatch(e -> e.getEventType() == BATTLE_EVENT_BUFF_ADD && e.getBuffTableId() == 130051
                && e.getSourceId() == pet && e.getTargetId() == enemy && e.getGroupId() == attack.getGroupId());
    }

    @Test void 每个正式主动技能都能被许可主体实际施放() {
        var catalog = TABLES.skill().all().stream().filter(s -> s.getBattleModel() == 1).toList();
        assertThat(catalog).hasSize(69);
        assertThat(TABLES.buff().all().stream().filter(b -> b.getBattleOp() != 0).count()).isEqualTo(72);
        int casts = 0;
        for (SkillTable skill : catalog) {
            if (skill.getAbilityKind() != 1) continue;
            for (int mask : new int[]{1,2}) {
                if ((skill.getCasterMask() & mask) == 0) continue;
                boolean pet = mask == 2;
                var request = request();
                if (!pet && skill.getSeasonId() != 0) {
                    request.getPlayersBuilder(0).setActiveSeasonId(skill.getSeasonId());
                }
                learn(request,skill.getId(),pet);
                var engine = TestBattles.start(request,DATA);
                var result = cast(engine,skill.getId(),pet);
                assertThat(result.getEventsList()).as("技能 %s",skill.getId()).anyMatch(e -> e.getEventType() == BATTLE_EVENT_SKILL && e.getSkillTableId() == skill.getId());
                casts++;
            }
        }
        assertThat(casts).isEqualTo(56);
    }

    @Test void 中立治疗和初始护盾预览等于同局实际收益且按独立加成扣蓝() {
        for (int skill : new int[]{10010,10040}) {
            var request = request();
            learn(request,skill,false);
            var eq = request.getPlayersBuilder(0).getEquipmentEffectsBuilder().setOutgoingHealBps(1200)
                    .setShieldCapacityBps(2400).setSupportSourcesComplete(true).putManaReductionBps(skill == 10010 ? 3 : 4,1000);
            for (int kind : new int[]{1,2}) {
                int bonus = kind == 1 ? 1200 : 2400;
                eq.addSupportSourcesBuilder().setKind(1).setEffectKind(kind).setItemId(500+kind).setAffixId(kind).setRawBonusBps(bonus).setEffectiveBonusBps(bonus);
            }
            var engine = TestBattles.start(request,DATA);
            var summary = TestBattles.stateActor(engine.buildStateSnapshot(),SELF).getSupportEffects();
            var preview = summary.getPreviews(0);
            assertThat(preview.getComplete()).isTrue();
            var result = cast(engine,skill,false);
            var mana = result.getEventsList().stream().filter(e -> e.getEventType() == BATTLE_EVENT_MANA && e.getSourceId() == SELF).findFirst().orElseThrow();
            assertThat(mana.getValue()).isEqualTo(preview.getManaCost());
            var event = result.getEventsList().stream().filter(e -> e.getSourceId() == SELF && e.getTargetId() == ALLY
                    && e.getEventType() == (skill == 10010 ? BATTLE_EVENT_HEAL : BATTLE_EVENT_BUFF_ADD)).findFirst().orElseThrow();
            assertThat(skill == 10010 ? event.getValue() : event.getShieldAfter()).isEqualTo(preview.getReferenceValue());
        }
    }

    @Test void 宝宝条目504增加次数盾容量且保持命中次数和持续时间() {
        long[] capacities = new long[2];
        int hits = -1, rounds = -1;
        for (int i = 0; i < 2; i++) {
            var request = request();
            learn(request,11005,true);
            request.getPlayersBuilder(0).getPetsBuilder(0).getPetCultivationBuilder().setShieldCapacityBps(i*3000);
            var engine = TestBattles.start(request,DATA);
            var result = cast(engine,11005,true);
            var shield = result.getEventsList().stream().filter(e -> e.getEventType() == BATTLE_EVENT_BUFF_ADD && e.getShieldAfter() > 0).findFirst().orElseThrow();
            capacities[i] = shield.getShieldAfter();
            var live = TestBattles.stateActor(result.getState(),shield.getTargetId()).getBuffsList().stream()
                    .filter(e -> e.getBuffId() == shield.getBuffInstanceId()).findFirst().orElseThrow();
            if (i == 0) { hits = live.getRemainingHits(); rounds = live.getRemainRounds(); }
            assertThat(live.getRemainingHits()).isPositive().isEqualTo(hits);
            assertThat(live.getRemainRounds()).isEqualTo(rounds);
        }
        assertThat(capacities[1]).isGreaterThan(capacities[0]);
    }
}
