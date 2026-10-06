package com.game.battle.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.outbox.ActivityRetryRules.Decision;
import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleRedis;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 活动结果重发的判定（纯函数；scene-battle-spec §13.1，逐条移植基线 {@code battle_result_activity_test.cpp:116-135}）：判定顺序
 * 已销账 &gt; 用尽 &gt; 重发；持久副本的键名与 TTL / 间隔 / 上限；只在重发时计次，所以 30 次重发之后第 31 轮才用尽（{@code room.cpp:2014}）。
 */
class ActivityRetryRulesTest {

    @Test
    void 判定顺序_已销账先于用尽先于重发() {
        assertThat(ActivityRetryRules.classify(false, 99)).isEqualTo(Decision.ACKED);
        assertThat(ActivityRetryRules.classify(false, 30)).as("最后一轮恰好已销账算成功收尾").isEqualTo(Decision.ACKED);
        assertThat(ActivityRetryRules.classify(true, 30)).isEqualTo(Decision.EXHAUSTED);
        assertThat(ActivityRetryRules.classify(true, 29)).isEqualTo(Decision.RESEND);
        assertThat(ActivityRetryRules.classify(true, 0)).isEqualTo(Decision.RESEND);
    }

    @Test
    void 已销账不看次数() {
        assertThat(ActivityRetryRules.classify(false, 0)).isEqualTo(Decision.ACKED);
        assertThat(ActivityRetryRules.classify(false, Integer.MAX_VALUE)).isEqualTo(Decision.ACKED);
    }

    @Test
    void 用尽之后一直是用尽() {
        assertThat(ActivityRetryRules.classify(true, 31)).isEqualTo(Decision.EXHAUSTED);
        assertThat(ActivityRetryRules.classify(true, Integer.MAX_VALUE)).isEqualTo(Decision.EXHAUSTED);
    }

    @Test
    void 键名_按battle_id的无符号十进制() {
        assertThat(RedisKeys.battleActivityResult(123)).isEqualTo("xm:battle:activity-result:123");
        assertThat(RedisKeys.battleActivityResult(-1L)).as("uint64 最大值").isEqualTo("xm:battle:activity-result:18446744073709551615");
    }

    @Test
    void 常量_七天_十秒_三十次() {
        assertThat(BattleRedis.ACTIVITY_RESULT_TTL_SEC).isEqualTo(604_800);
        assertThat(BattleRedis.ACTIVITY_RETRY_INTERVAL).isEqualTo(Duration.ofSeconds(10));
        assertThat(BattleRedis.ACTIVITY_RETRY_MAX).isEqualTo(30);
    }

    @Test
    void 只在重发时计次_30次重发后第31轮用尽() {
        int resent = 0;
        int rounds = 0;
        Decision decision;
        while ((decision = ActivityRetryRules.classify(true, resent)) == Decision.RESEND) {
            resent++;
            rounds++;
        }
        rounds++;

        assertThat(resent).as("重发次数").isEqualTo(30);
        assertThat(rounds).as("用尽发生在第几轮").isEqualTo(31);
        assertThat(decision).isEqualTo(Decision.EXHAUSTED);
    }
}
