package com.game.battle.engine;

import static com.game.battle.engine.TestTables.*;
import static com.game.proto.eBattleEventType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.CombatAttributes;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EquipmentAilmentTest {
    private static final class Fixture {
        final MemoryBattleData data = standard();
        final BattleContext context;
        final BuffEngine buffs;
        final TurnResultS2C.Builder events = TurnResultS2C.newBuilder();
        final BattleUnit caster;
        final BattleUnit target;
        Fixture(long seed, CombatAttributes casterCombat, CombatAttributes targetCombat) {
            context = new BattleContext(data, 3, seed);
            caster = unit(PLAYER_A, 0, casterCombat);
            target = unit(PLAYER_B, 1, targetCombat);
            context.units().add(caster); context.units().add(target);
            context.events().startRound(events); context.events().beginGroup();
            buffs = new BuffEngine(context);
        }
        private static BattleUnit unit(long id, int team, CombatAttributes combat) {
            return new BattleUnit(BattleActorState.newBuilder().setActorId(id).setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER)
                    .setTeamIndex(team).setAttributes(BaseAttributesComp.newBuilder().setHealth(1000))
                    .setLevel(10).setMaxHealth(1000).setCombat(combat));
        }
        void add(int buff) { buffs.addBuffToActor(target, buff, caster.actorId(), 0); }
    }
    private static final CombatAttributes ZERO = CombatAttributes.getDefaultInstance();

    @ParameterizedTest
    @ValueSource(ints = {50, 52, 30, 31})
    void 各异常读取对应单项并发RESIST_满率不耗随机数(int type) {
        var defense = CombatAttributes.newBuilder();
        switch (type) {
            case 50 -> defense.setResistPoison(100);
            case 52 -> defense.setResistFreeze(100);
            case 30 -> defense.setResistSleep(100);
            case 31 -> defense.setResistForget(100);
            default -> throw new AssertionError();
        }
        var f = new Fixture(42, ZERO, defense.build());
        f.data.addBuff(800).setBuffType(type).setDuration(12);
        f.add(800);
        assertThat(f.target.state().getBuffsList()).isEmpty();
        assertThat(f.events.getEventsList()).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(BATTLE_EVENT_RESIST);
            assertThat(event.getSourceId()).isEqualTo(PLAYER_A);
            assertThat(event.getTargetId()).isEqualTo(PLAYER_B);
            assertThat(event.getBuffTableId()).isEqualTo(800);
        });
        assertThat(f.context.rng().draws()).isZero();
    }

    @Test
    void 单项与全抗相加后扣忽视_不能先把总抗夹为100() {
        var defense = CombatAttributes.newBuilder().setResistPoison(80).setResistAllAilment(70).build();
        for (long ignored : new long[] {50, 150, -1L}) {
            var f = new Fixture(7, CombatAttributes.newBuilder().setIgnoreAilmentResist(ignored).build(), defense);
            f.add(BUFF_POISON);
            assertThat(f.events.getEvents(0).getEventType()).isEqualTo(ignored == 50 ? BATTLE_EVENT_RESIST : BATTLE_EVENT_BUFF_ADD);
            assertThat(f.context.rng().draws()).isZero();
        }
    }

    @Test
    void 灼烧与未知类型不映射混乱_同队自己与无施加者不抵抗() {
        var all = CombatAttributes.newBuilder().setResistAllAilment(100).setResistConfusion(100).build();
        for (int type : new int[] {51, 99}) {
            var f = new Fixture(7, ZERO, all);
            f.data.addBuff(800).setBuffType(type).setDuration(12);
            f.add(800);
            assertThat(f.events.getEvents(0).getEventType()).isEqualTo(BATTLE_EVENT_BUFF_ADD);
            assertThat(f.context.rng().draws()).isZero();
        }
        for (long source : new long[] {PLAYER_A, PLAYER_B, 0}) {
            var f = new Fixture(7, ZERO, all);
            f.target.state().setTeamIndex(0);
            f.buffs.addBuffToActor(f.target, BUFF_POISON, source, 0);
            assertThat(f.target.state().getBuffsList()).hasSize(1);
            assertThat(f.events.getEvents(0).getEventType()).isEqualTo(BATTLE_EVENT_BUFF_ADD);
            assertThat(f.context.rng().draws()).isZero();
        }
    }

    @Test
    void 免疫先于抵抗_抵抗先于驱散叠层和子buff() {
        var immune = new Fixture(42, ZERO, CombatAttributes.newBuilder().setResistPoison(50).build());
        immune.target.state().addBuffs(BattleBuffEntry.newBuilder().setBuffId(10).setBuffTableId(BUFF_IMMUNE_POISON).setCasterId(PLAYER_B));
        immune.add(BUFF_POISON);
        assertThat(immune.events.getEventsList()).isEmpty();
        assertThat(immune.context.rng().draws()).isZero();

        var resisted = new Fixture(42, ZERO, CombatAttributes.newBuilder().setResistPoison(100).build());
        resisted.data.addBuff(800).setBuffType(50).setDuration(12).putDispelTag("kept", true).addSubBuff(801).addTargetSubBuff(802);
        resisted.data.addBuff(801).setDuration(12);
        resisted.data.addBuff(802).setDuration(12);
        resisted.data.addBuff(803).setInfiniteDuration(1).putTag("kept", true);
        var old = BattleBuffEntry.newBuilder().setBuffId(10).setBuffTableId(803).setCasterId(PLAYER_B).build();
        resisted.target.state().addBuffs(old);
        resisted.add(800);
        assertThat(resisted.target.state().getBuffsList()).containsExactly(old);
        assertThat(resisted.caster.state().getBuffsList()).isEmpty();
        assertThat(resisted.events.getEventsList()).singleElement().satisfies(e -> assertThat(e.getEventType()).isEqualTo(BATTLE_EVENT_RESIST));
        // 已有同种异常也先判抵抗，不让叠层刷新旁路进入。
        resisted.target.state().addBuffs(BattleBuffEntry.newBuilder().setBuffId(11).setBuffTableId(800).setCasterId(PLAYER_A).setLayer(1).setRemainRounds(1));
        resisted.add(800);
        assertThat(resisted.target.state().getBuffs(1).getLayer()).isEqualTo(1);
        assertThat(resisted.target.state().getBuffs(1).getRemainRounds()).isEqualTo(1);
    }

    @Test
    void 回挂施法者的子buff使用原目标的忽视抗性() {
        var f = new Fixture(42, CombatAttributes.newBuilder().setResistSleep(100).build(),
                CombatAttributes.newBuilder().setIgnoreAilmentResist(100).build());
        f.data.addBuff(800).setDuration(12).addTargetSubBuff(801);
        f.data.addBuff(801).setBuffType(30).setDuration(12);
        f.add(800);
        assertThat(f.caster.state().getBuffsList()).singleElement().satisfies(buff -> {
            assertThat(buff.getBuffTableId()).isEqualTo(801);
            assertThat(buff.getCasterId()).isEqualTo(PLAYER_B);
        });
        assertThat(f.events.getEventsList()).noneMatch(e -> e.getEventType() == BATTLE_EVENT_RESIST);
    }

    @Test
    void 部分抵抗逐次只掷一次且与同种子序列一致() {
        var f = new Fixture(42, ZERO, CombatAttributes.newBuilder().setResistPoison(35).build());
        var oracle = new BattleRandom(42);
        for (int i = 0; i < 40; i++) {
            boolean resisted = oracle.rand01() * 100 < 35;
            f.add(BUFF_POISON);
            assertThat(f.events.getEvents(i).getEventType()).isEqualTo(resisted ? BATTLE_EVENT_RESIST : BATTLE_EVENT_BUFF_ADD);
        }
        assertThat(f.context.rng().draws()).isEqualTo(40);
    }

    @Test
    void 开局已有合法异常不重新掷抵抗_快照清洗仍保留() {
        var request = TestBattles.request(3, 42);
        TestBattles.addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 0, 0, 100);
        var target = TestBattles.addPlayer(request, PLAYER_B, 1, 1000, 1000, 0, 0, 0, 90);
        target.getCombatBuilder().setResistPoison(100);
        var poison = BattleBuffEntry.newBuilder().setBuffId(10).setBuffTableId(BUFF_POISON)
                .setCasterId(PLAYER_A).setLayer(1).setRemainRounds(2).build();
        target.addBuffs(poison);
        var engine = TestBattles.start(request, standard());
        assertThat(TestBattles.stateActor(engine.buildStateSnapshot(), PLAYER_B).getBuffsList()).containsExactly(poison);
        assertThat(engine.rngDrawsForTest()).isZero();
    }
}
