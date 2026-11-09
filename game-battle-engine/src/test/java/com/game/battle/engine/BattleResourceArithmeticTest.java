package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.firstEvent;
import static com.game.battle.engine.TestTables.BUFF_REGEN;
import static com.game.battle.engine.TestTables.ITEM_MANA_POTION;
import static com.game.battle.engine.TestTables.ITEM_POTION;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ITEM;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_BUFF_TICK;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_ITEM;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleEventItem;
import com.game.proto.BattleItemEntry;
import org.junit.jupiter.api.Test;

/** uint64 资源落地与事件的数值边界；通过真实药品/周期路径验证共用原语。 */
class BattleResourceArithmeticTest {

    @Test
    void 预测不改状态_实际治疗不丢高位气血的低位() {
        long before = (1L << 53) + 1;
        BattleUnit unit = unit(before, before + 10, 0, 100);
        assertThat(unit.landedHeal(1.99)).isEqualTo(1);
        assertThat(unit.health()).isEqualTo(before);
        assertThat(unit.applyHeal(1.99)).isEqualTo(1);
        assertThat(unit.health()).isEqualTo(before + 1);
        assertThat(unit.applyHeal(.99)).isZero();
        assertThat(unit.health()).isEqualTo(before + 1);
    }

    @Test
    void 高位治疗先截断缺口_不会溢出或超过上限() {
        BattleUnit unit = unit(-4L, -1L, 0, 100);
        assertThat(unit.applyHeal(Double.MAX_VALUE)).isEqualTo(3);
        assertThat(unit.health()).isEqualTo(-1L);
        assertThat(unit.applyHeal(1)).isZero();
        assertThat(unit.health()).isEqualTo(-1L);

        unit.setHealth(0);
        assertThat(unit.applyHeal(Double.MAX_VALUE)).isEqualTo(-1L);
        assertThat(unit.health()).isEqualTo(-1L);
    }

    @Test
    void 非正非有限及死亡目标不治疗_超上限也不拉低() {
        BattleUnit unit = unit(50, 100, 0, 100);
        for (double raw : new double[]{0, -0.0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThat(unit.applyHeal(raw)).as("raw=%s", raw).isZero();
            assertThat(unit.health()).isEqualTo(50);
        }
        unit.state().setIsDead(true);
        assertThat(unit.applyHeal(10)).isZero();
        assertThat(unit.health()).isEqualTo(50);
        unit.state().setIsDead(false);
        unit.setHealth(101);
        assertThat(unit.applyHeal(10)).isZero();
        assertThat(unit.health()).isEqualTo(101);
    }

    @Test
    void 回蓝只补uint64缺口_超上限或死亡保持原值() {
        BattleUnit unit = unit(50, 100, -4L, -1L);
        assertThat(unit.restoreMana(100)).isEqualTo(3);
        assertThat(unit.mana()).isEqualTo(-1L);
        assertThat(unit.restoreMana(-1L)).isZero();
        assertThat(unit.mana()).isEqualTo(-1L);

        unit.state().setMaxMana(100);
        assertThat(unit.restoreMana(-1L)).isZero();
        assertThat(unit.mana()).isEqualTo(-1L);
        unit.setMana(3);
        unit.state().setIsDead(true);
        assertThat(unit.restoreMana(100)).isZero();
        assertThat(unit.mana()).isEqualTo(3);
    }

    @Test
    void 纯回蓝药高位加法不回绕_事件只报实际缺口() {
        BattleEventItem event = usePotion(0, 100, -4L, -1L);
        assertThat(event.getValue()).isEqualTo(3);
        assertThat(event.hasManaDelta()).isTrue();
        assertThat(event.getManaDelta()).isEqualTo(3);
        assertThat(event.getTargetManaAfter()).isEqualTo(-1L);
    }

    @Test
    void 纯回蓝药满蓝和超上限也显式给零增量() {
        for (long before : new long[]{100, 101, -1L}) {
            BattleEventItem event = usePotion(0, 100, before, 100);
            assertThat(event.getValue()).isZero();
            assertThat(event.hasManaDelta()).isTrue();
            assertThat(event.getManaDelta()).isZero();
            assertThat(event.getTargetManaAfter()).isEqualTo(before);
        }
    }

    @Test
    void 巨额回蓝的value精确而显示保持正数饱和() {
        BattleEventItem event = usePotion(0, -1L, 0, -1L);
        assertThat(event.getValue()).isEqualTo(-1L);
        assertThat(event.hasManaDelta()).isTrue();
        assertThat(event.getManaDelta()).isEqualTo(Long.MAX_VALUE);
        assertThat(event.getTargetManaAfter()).isEqualTo(-1L);
    }

    @Test
    void 双效药保留回血value且回蓝独立给增量_纯回血药不伪造presence() {
        BattleEventItem mixed = usePotion(10, 100, 95, 100);
        assertThat(mixed.getValue()).isEqualTo(10);
        assertThat(mixed.getTargetHealthAfter()).isEqualTo(60);
        assertThat(mixed.hasManaDelta()).isTrue();
        assertThat(mixed.getManaDelta()).isEqualTo(5);
        assertThat(mixed.getTargetManaAfter()).isEqualTo(100);

        BattleEventItem healthOnly = usePotion(10, 0, 95, 100);
        assertThat(healthOnly.getValue()).isEqualTo(10);
        assertThat(healthOnly.hasManaDelta()).isFalse();
        assertThat(healthOnly.getTargetManaAfter()).isEqualTo(95);
    }

    @Test
    void 药品治疗沿用相同uint64缺口且保留高位气血() {
        long before = (1L << 53) + 1;
        var data = standard();
        data.addItem(ITEM_POTION).setBattleHealHp(1);
        var request = TestBattles.request(BattleConstants.MATCH_MODE_PVE_SOLO, 1);
        var player = addPlayer(request, PLAYER_A, 0, before, before + 5, 0, 100, 0, 120);
        player.addItems(BattleItemEntry.newBuilder().setItemTableId(ITEM_POTION).setCount(1));
        var engine = TestBattles.start(request, data);
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_POTION))).isTrue();
        BattleEventItem event = firstEvent(engine.resolveCurrentRound(), BATTLE_EVENT_ITEM);
        assertThat(event).isNotNull();
        assertThat(event.getValue()).isEqualTo(1);
        assertThat(event.getTargetHealthAfter()).isEqualTo(before + 1);
    }

    @Test
    void 周期治疗按本次量截断_高位气血低位不因double相加丢失() {
        long before = (1L << 53) + 1;
        var data = standard();
        data.setBuffRegen(BUFF_REGEN, 1.99);
        var request = TestBattles.request(BattleConstants.MATCH_MODE_PVE_SOLO, 1);
        var player = addPlayer(request, PLAYER_A, 0, before, before + 5, 0, 100, 0, 120);
        player.addBuffs(BattleBuffEntry.newBuilder().setBuffId(1).setBuffTableId(BUFF_REGEN)
                .setLayer(1).setRemainRounds(2));
        var engine = TestBattles.start(request, data);
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND))).isTrue();
        BattleEventItem tick = firstEvent(engine.resolveCurrentRound(), BATTLE_EVENT_BUFF_TICK);
        assertThat(tick).isNotNull();
        assertThat(tick.getValue()).isEqualTo(1);
        // 敌方兜底怪在防御下实扣 8 点，再精确回复 1 点。
        assertThat(tick.getTargetHealthAfter()).isEqualTo(before - 7);
    }

    private static BattleUnit unit(long health, long maxHealth, long mana, long maxMana) {
        return new BattleUnit(BattleActorState.newBuilder().setMaxHealth(maxHealth).setMaxMana(maxMana)
                .setAttributes(BaseAttributesComp.newBuilder().setHealth(health).setMana(mana)));
    }

    private static BattleEventItem usePotion(long heal, long restore, long mana, long maximum) {
        var data = standard();
        data.addItem(ITEM_MANA_POTION).setBattleUsable(1).setBattleHealHp(heal).setBattleHealMp(restore);
        var request = TestBattles.request(BattleConstants.MATCH_MODE_PVE_SOLO, 1);
        var player = addPlayer(request, PLAYER_A, 0, 50, 100, 0, 100, 0, 120);
        player.setMaxMana(maximum);
        player.getBaseAttributesBuilder().setMana(mana);
        player.addItems(BattleItemEntry.newBuilder().setItemTableId(ITEM_MANA_POTION).setCount(1));
        var engine = TestBattles.start(request, data);
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_MANA_POTION))).isTrue();
        var result = engine.resolveCurrentRound();
        BattleEventItem event = firstEvent(result, BATTLE_EVENT_ITEM);
        assertThat(event).isNotNull();
        assertThat(engine.buildSettlement(PLAYER_A).getItemsConsumedList())
                .containsExactly(BattleItemEntry.newBuilder().setItemTableId(ITEM_MANA_POTION).setCount(1).build());
        assertThat(engine.selfItems(PLAYER_A)).isEmpty();
        return event;
    }
}
