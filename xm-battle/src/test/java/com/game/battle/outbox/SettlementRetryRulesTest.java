package com.game.battle.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.outbox.SettlementRetryRules.Decision;
import com.game.discovery.battle.BattleRedis;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 结算重投的判定（纯函数；scene-battle-spec §13.1，逐条移植基线 {@code settlement_route_test.cpp:353-397} 对 {@code ClassifyRetry} 的用例，
 * 判定顺序见 {@code settlement_outbox.h:55-74}）：已销账 &gt; 用尽 &gt; 无目标 &gt; 重投；次数在判定<b>之后</b>才加，所以
 * attemptsSoFar = 11 仍重投、12 才用尽（共 12 次重投，第 13 轮用尽）。
 */
class SettlementRetryRulesTest {

    private static final int MAX = BattleRedis.SETTLEMENT_RETRY_MAX;

    @Test
    void 重投上限是12次_间隔10秒() {
        assertThat(BattleRedis.SETTLEMENT_RETRY_MAX).as("基线 kSettlementRetryMaxAttempts").isEqualTo(12);
        assertThat(BattleRedis.SETTLEMENT_RETRY_INTERVAL).as("基线 kSettlementRetryIntervalSec").isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 停止于已销账_不看次数也不看有没有目标() {
        assertThat(SettlementRetryRules.classify(false, 0, true)).isEqualTo(Decision.DONE);
        assertThat(SettlementRetryRules.classify(false, 0, false)).isEqualTo(Decision.DONE);
        assertThat(SettlementRetryRules.classify(false, 5, true)).isEqualTo(Decision.DONE);
    }

    @Test
    void 按重新解析的目标重投() {
        assertThat(SettlementRetryRules.classify(true, 0, true)).isEqualTo(Decision.RESEND);
        assertThat(SettlementRetryRules.classify(true, 3, true)).isEqualTo(Decision.RESEND);
    }

    @Test
    void 无位置时等待_本轮不投() {
        assertThat(SettlementRetryRules.classify(true, 0, false)).isEqualTo(Decision.SKIP_NO_TARGET);
        assertThat(SettlementRetryRules.classify(true, 3, false)).isEqualTo(Decision.SKIP_NO_TARGET);
        assertThat(SettlementRetryRules.classify(true, MAX - 1, false)).as("最后一轮可用次数上仍是等待").isEqualTo(Decision.SKIP_NO_TARGET);
    }

    @Test
    void 用尽响亮但不丢_有没有目标都是用尽() {
        assertThat(SettlementRetryRules.classify(true, MAX, true)).isEqualTo(Decision.EXHAUSTED);
        assertThat(SettlementRetryRules.classify(true, MAX, false)).isEqualTo(Decision.EXHAUSTED);
        assertThat(SettlementRetryRules.classify(true, 99, false)).isEqualTo(Decision.EXHAUSTED);
        assertThat(SettlementRetryRules.classify(true, Integer.MAX_VALUE, true)).isEqualTo(Decision.EXHAUSTED);
    }

    @Test
    void 最后一轮已销账算成功_已销账排在用尽之前() {
        assertThat(SettlementRetryRules.classify(false, MAX, false)).isEqualTo(Decision.DONE);
        assertThat(SettlementRetryRules.classify(false, MAX, true)).isEqualTo(Decision.DONE);
        assertThat(SettlementRetryRules.classify(false, 99, true)).isEqualTo(Decision.DONE);
    }

    @Test
    void 次数在判定之后才加_11仍重投_12才用尽() {
        assertThat(SettlementRetryRules.classify(true, 11, true)).isEqualTo(Decision.RESEND);
        assertThat(SettlementRetryRules.classify(true, 12, true)).isEqualTo(Decision.EXHAUSTED);
    }

    @Test
    void 按发件箱的用法走一遍_判定后加一_共12次重投_第13轮用尽() {
        List<Decision> rounds = new ArrayList<>();
        int attempts = 0;
        Decision decision;
        do {
            decision = SettlementRetryRules.classify(true, attempts, true);
            attempts++;
            rounds.add(decision);
        } while (decision == Decision.RESEND);

        assertThat(rounds).hasSize(13);
        assertThat(rounds.subList(0, 12)).containsOnly(Decision.RESEND);
        assertThat(rounds.get(12)).isEqualTo(Decision.EXHAUSTED);
    }

    @Test
    void 跳过的轮同样计次_全程无目标也是第13轮用尽() {
        int attempts = 0;
        int skipped = 0;
        while (SettlementRetryRules.classify(true, attempts, false) == Decision.SKIP_NO_TARGET) {
            attempts++;
            skipped++;
        }

        assertThat(skipped).isEqualTo(12);
        assertThat(SettlementRetryRules.classify(true, attempts, false)).isEqualTo(Decision.EXHAUSTED);
    }

    @Test
    void 负的次数按还没试过处理() {
        assertThat(SettlementRetryRules.classify(true, -1, true)).isEqualTo(Decision.RESEND);
    }
}
