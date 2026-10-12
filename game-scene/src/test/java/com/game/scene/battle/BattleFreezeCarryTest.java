package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.battle.BattleFreeze.Phase;
import org.junit.jupiter.api.Test;

/**
 * 同 epoch 沿用旧实例的冻结时那份复制（scene-battle-spec §7.8 第 0 步；第二轮修正 R2-b）：纯函数 {@link BattleFreeze#carriedCopy}，
 * 不碰世界、不碰 Redis。走真进场流程的用例在 {@code BattleRecoveryTest}（第 0 步）与 {@code ConfirmBattleTest}（144 谁推、推几次）。
 */
class BattleFreezeCarryTest {

    private static final long BATTLE = 0x8000_0000_0000_0007L;
    private static final long DEADLINE = 1_800_000_300_000L;
    private static final long PREPARE_DEADLINE = 1_800_000_060_000L;

    private static BattleFreeze freeze(Phase phase, boolean preparedHere) {
        return new BattleFreeze(BATTLE, 21, phase, DEADLINE, PREPARE_DEADLINE, preparedHere);
    }

    /** 「144 不必再推」跟着会话走：会话没变照抄旧值（真的还是真、假的还是假），会话变了一律清成假。 */
    @Test
    void preparedHere_会话没变照抄旧值_会话变了一律为假() {
        for (Phase phase : Phase.values()) {
            assertThat(freeze(phase, true).carriedCopy(true).preparedHere()).as("%s 同会话、旧值真", phase).isTrue();
            assertThat(freeze(phase, false).carriedCopy(true).preparedHere()).as("%s 同会话、旧值假", phase).isFalse();
            assertThat(freeze(phase, true).carriedCopy(false).preparedHere()).as("%s 换会话、旧值真", phase).isFalse();
            assertThat(freeze(phase, false).carriedCopy(false).preparedHere()).as("%s 换会话、旧值假", phase).isFalse();
        }
    }

    /** 不管会话变没变：是新对象，身份字段与期限照抄，运行态标记复位，续期结论照抄。 */
    @Test
    void 复制出新对象_身份与期限照抄_运行态标记复位_续期结论照抄() {
        for (boolean sameSession : new boolean[] {true, false}) {
            BattleFreeze original = freeze(Phase.FIGHTING, true);
            original.setLockExtended(true);
            original.setRescuing(true);
            original.requestCancel();
            original.setLockPending(true);

            BattleFreeze copy = original.carriedCopy(sameSession);

            assertThat(copy).isNotSameAs(original);
            assertThat(copy.battleId()).isEqualTo(BATTLE);
            assertThat(copy.battleNodeId()).isEqualTo(21);
            assertThat(copy.phase()).isEqualTo(Phase.FIGHTING);
            assertThat(copy.deadlineMs()).isEqualTo(DEADLINE);
            assertThat(copy.prepareDeadlineMs()).isEqualTo(PREPARE_DEADLINE);
            assertThat(copy.lockExtended()).as("续期结论照抄").isTrue();
            assertThat(copy.rescuing()).as("运行态标记复位").isFalse();
            assertThat(copy.cancelRequested()).isFalse();
            assertThat(copy.lockPending()).isFalse();
            assertThat(freeze(Phase.FIGHTING, true).carriedCopy(sameSession).lockExtended()).as("没续过的照抄为假").isFalse();
        }
    }
}
