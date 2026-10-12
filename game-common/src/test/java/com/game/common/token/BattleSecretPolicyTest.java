package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.RunMode;
import com.game.common.token.BattleSecretPolicy.Action;
import com.game.common.token.BattleSecretPolicy.Problem;
import com.game.common.token.BattleSecretPolicy.Verdict;
import org.junit.jupiter.api.Test;

/**
 * 移植基线 {@code battle_ticket_test.cpp} 的 {@code BattleTokenSecretStrength*} 与空密钥处置（battle-node-spec §13.1）；
 * 空密钥改为任何模式都拒启（§11 N5）。
 */
class BattleSecretPolicyTest {

    private static final String SECRET = "unit-test-battle-token-secret-0123456789abcdef";
    private static final String GATE = "unit-test-gate-token-secret-0123456789abcdef";

    @Test
    void 缺失或纯空白_任何模式都拒启() {
        for (RunMode mode : RunMode.values()) {
            assertThat(BattleSecretPolicy.check(null, null, mode)).isEqualTo(new Verdict(Problem.MISSING, Action.REFUSE));
            assertThat(BattleSecretPolicy.check("", GATE, mode)).isEqualTo(new Verdict(Problem.MISSING, Action.REFUSE));
            assertThat(BattleSecretPolicy.check("   \t\r\n", null, mode).refused()).isTrue();
            assertThat(BattleSecretPolicy.check("\u000b\f ", null, mode).problem()).isEqualTo(Problem.MISSING);
        }
    }

    @Test
    void 强度_短密钥含两侧空白凑数都算太短() {
        assertThat(BattleSecretPolicy.classifyStrength("x", "")).isEqualTo(Problem.TOO_SHORT);
        // 31 字节差一个字节也不行
        assertThat(BattleSecretPolicy.classifyStrength("a".repeat(31), "")).isEqualTo(Problem.TOO_SHORT);
        // 空白不算长度：凑出来的 32 字节里有 8 个空格 → 实际 24
        assertThat(BattleSecretPolicy.classifyStrength("    " + "a".repeat(24) + "    ", "")).isEqualTo(Problem.TOO_SHORT);
        assertThat(BattleSecretPolicy.classifyStrength("a".repeat(32), "")).isEqualTo(Problem.NONE);
        assertThat(BattleSecretPolicy.classifyStrength(SECRET, "")).isEqualTo(Problem.NONE);
    }

    @Test
    void 强度_长度按UTF8字节计() {
        // 11 个汉字 = 33 字节
        assertThat(BattleSecretPolicy.classifyStrength("玄冥战斗票据密钥测试用", null)).isEqualTo(Problem.NONE);
        // 10 个汉字 = 30 字节
        assertThat(BattleSecretPolicy.classifyStrength("玄冥战斗票据密钥测试", null)).isEqualTo(Problem.TOO_SHORT);
    }

    @Test
    void 强度_与gate相同含两侧空白差异_gate没配不比() {
        assertThat(BattleSecretPolicy.classifyStrength(GATE, GATE)).isEqualTo(Problem.SAME_AS_GATE);
        // 首尾空白差异不构成「不同密钥」
        assertThat(BattleSecretPolicy.classifyStrength("  " + GATE + "  ", GATE)).isEqualTo(Problem.SAME_AS_GATE);
        assertThat(BattleSecretPolicy.classifyStrength(GATE, "\t" + GATE + "\n")).isEqualTo(Problem.SAME_AS_GATE);
        assertThat(BattleSecretPolicy.classifyStrength(SECRET, GATE)).isEqualTo(Problem.NONE);
        // gate 侧没配密钥（或进程看不到）不判相同：这一项只守「分域」
        assertThat(BattleSecretPolicy.classifyStrength(SECRET, "")).isEqualTo(Problem.NONE);
        assertThat(BattleSecretPolicy.classifyStrength(SECRET, null)).isEqualTo(Problem.NONE);
    }

    @Test
    void 太短或与gate相同_prod拒启_dev与test只WARN() {
        String shortSecret = "a".repeat(31);
        assertThat(BattleSecretPolicy.check(shortSecret, null, RunMode.PROD)).isEqualTo(new Verdict(Problem.TOO_SHORT, Action.REFUSE));
        assertThat(BattleSecretPolicy.check(shortSecret, null, RunMode.DEV)).isEqualTo(new Verdict(Problem.TOO_SHORT, Action.WARN));
        assertThat(BattleSecretPolicy.check(shortSecret, null, RunMode.TEST)).isEqualTo(new Verdict(Problem.TOO_SHORT, Action.WARN));

        assertThat(BattleSecretPolicy.check(GATE, GATE, RunMode.PROD)).isEqualTo(new Verdict(Problem.SAME_AS_GATE, Action.REFUSE));
        assertThat(BattleSecretPolicy.check(" " + GATE, GATE, RunMode.DEV)).isEqualTo(new Verdict(Problem.SAME_AS_GATE, Action.WARN));
    }

    @Test
    void 合格密钥任何模式都通过() {
        for (RunMode mode : RunMode.values()) {
            Verdict verdict = BattleSecretPolicy.check(SECRET, GATE, mode);
            assertThat(verdict).isEqualTo(new Verdict(Problem.NONE, Action.ACCEPT));
            assertThat(verdict.refused()).isFalse();
        }
    }

    @Test
    void 说明不含密钥内容() {
        for (Problem problem : Problem.values()) {
            String text = new Verdict(problem, Action.WARN).describe();
            assertThat(text).isNotBlank().doesNotContain(SECRET);
        }
        assertThat(Problem.SAME_AS_GATE.wireName()).isEqualTo("same_as_gate");
    }
}
