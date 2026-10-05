package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleStateS2C;
import com.game.proto.TurnResultS2C;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 视角裁剪（基线 {@code room.cpp:1250-1281}；battle-node-spec §5.6、§13.1）。 */
class BattleViewsTest {

    private static final long A = 5001;
    private static final long B = 5002;
    private static final long PET_OF_A = Long.MIN_VALUE | (2L << 32);
    private static final long PET_OF_B = PET_OF_A + 1;
    private static final long MONSTER = Long.MIN_VALUE | (1L << 32);

    private static BattleActorState actor(long actorId, long owner, int cooldownSkill) {
        return BattleActorState.newBuilder()
                .setActorId(actorId)
                .setOwnerPlayerId(owner)
                .putSkillCooldownRounds(cooldownSkill, 2)
                .addBuffs(BattleBuffEntry.newBuilder().setBuffId(actorId).setBuffTableId(201).setRemainRounds(2))
                .build();
    }

    /** 全知快照：A、A 的宝宝、B、B 的宝宝、怪物，人人有冷却与 buff；还混进了一条不该出现的 self_items。 */
    private static BattleStateS2C full() {
        return BattleStateS2C.newBuilder()
                .setBattleId(9)
                .setRoundIndex(3)
                .setActionDeadlineMs(123)
                .addActors(actor(A, 0, 101))
                .addActors(actor(PET_OF_A, A, 102))
                .addActors(actor(B, 0, 103))
                .addActors(actor(PET_OF_B, B, 104))
                .addActors(actor(MONSTER, 0, 105))
                .addSelfItems(BattleItemEntry.newBuilder().setItemTableId(999).setCount(1))
                .build();
    }

    private static BattleActorState find(BattleStateS2C state, long actorId) {
        return state.getActorsList().stream().filter(a -> a.getActorId() == actorId).findFirst().orElseThrow();
    }

    @Test
    void 参战者只保留本人与本人宝宝的冷却_buff原样保留() {
        BattleStateS2C original = full();
        List<BattleItemEntry> items = List.of(BattleItemEntry.newBuilder().setItemTableId(301).setCount(2).build());

        BattleStateS2C view = BattleViews.forParticipant(original, A, items).state();

        assertThat(find(view, A).getSkillCooldownRoundsMap()).containsEntry(101, 2);
        assertThat(find(view, PET_OF_A).getSkillCooldownRoundsMap()).containsEntry(102, 2);
        assertThat(find(view, B).getSkillCooldownRoundsMap()).isEmpty();
        assertThat(find(view, PET_OF_B).getSkillCooldownRoundsMap()).isEmpty();
        assertThat(find(view, MONSTER).getSkillCooldownRoundsMap()).isEmpty();
        for (BattleActorState actor : view.getActorsList()) {
            assertThat(actor.getBuffsCount()).as("buff 不裁剪").isEqualTo(1);
        }
        assertThat(view.getSelfItemsList()).as("先清空再只填本人的").containsExactlyElementsOf(items);
        assertThat(view.getBattleId()).isEqualTo(9);
        assertThat(view.getActionDeadlineMs()).isEqualTo(123);
    }

    @Test
    void 观众全员冷却清空且不带道具() {
        BattleStateS2C view = BattleViews.forObserver(full()).state();

        for (BattleActorState actor : view.getActorsList()) {
            assertThat(actor.getSkillCooldownRoundsMap()).isEmpty();
            assertThat(actor.getBuffsCount()).isEqualTo(1);
        }
        assertThat(view.getSelfItemsList()).isEmpty();
    }

    @Test
    void viewer为0时按观众处理() {
        BattleStateS2C view = BattleViews.forParticipant(full(), 0,
                List.of(BattleItemEntry.newBuilder().setItemTableId(301).setCount(2).build())).state();

        assertThat(view.getActorsList()).allSatisfy(actor -> assertThat(actor.getSkillCooldownRoundsMap()).isEmpty());
        assertThat(view.getSelfItemsList()).isEmpty();
    }

    @Test
    void 输入快照不被改动() {
        BattleStateS2C original = full();
        BattleStateS2C copy = original.toBuilder().build();

        BattleViews.forParticipant(original, B, List.of());
        BattleViews.forObserver(original);

        assertThat(original).isEqualTo(copy);
    }

    @Test
    void 回合结果只换state_事件与出手序原样() {
        TurnResultS2C result = TurnResultS2C.newBuilder()
                .setBattleId(9)
                .setRoundIndex(2)
                .setState(full())
                .addActionOrder(A)
                .addActionOrder(B)
                .build();

        TurnResultS2C personal = BattleViews.forParticipant(result.getState(), B, List.of()).toTurnResult(result);

        assertThat(personal.getActionOrderList()).containsExactly(A, B);
        assertThat(personal.getRoundIndex()).isEqualTo(2);
        assertThat(find(personal.getState(), A).getSkillCooldownRoundsMap()).isEmpty();
        assertThat(find(personal.getState(), B).getSkillCooldownRoundsMap()).containsEntry(103, 2);
    }
}
