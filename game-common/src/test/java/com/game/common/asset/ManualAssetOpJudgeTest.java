package com.game.common.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.asset.ManualAssetOpJudge.Evidence;
import com.game.common.asset.ManualAssetOpJudge.LedgerStatus;
import com.game.common.asset.ManualAssetOpJudge.RequestedOutcome;
import org.junit.jupiter.api.Test;

class ManualAssetOpJudgeTest {

    @Test
    void enforcesLedgerEvidenceRulesMatchingSharedJudgeManual() {
        long now = 10_000_000L;
        long quietSend = now - ManualAssetOpJudge.MIN_QUIET_MS - 1_000L;

        // 1. APPLIED 需账本 APPLIED 且收据匹配
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.APPLIED,
                new Evidence(LedgerStatus.APPLIED, true, false, false, quietSend, 10_000L, now)).allowed()).isTrue();
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.APPLIED,
                new Evidence(LedgerStatus.APPLIED, false, false, false, quietSend, 10_000L, now)).allowed()).isFalse();

        // 2. PARTIAL 任何方向都禁止人工直接终结
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.APPLIED,
                new Evidence(LedgerStatus.PARTIAL, true, true, false, quietSend, 10_000L, now)).allowed()).isFalse();
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.ABORTED,
                new Evidence(LedgerStatus.PARTIAL, false, true, false, quietSend, 10_000L, now)).allowed()).isFalse();

        // 3. UNKNOWN 账本绝不许判 ABORTED
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.ABORTED,
                new Evidence(LedgerStatus.UNKNOWN, false, true, false, quietSend, 10_000L, now)).allowed()).isFalse();

        // 4. REJECTED 账本允许 ABORTED
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.ABORTED,
                new Evidence(LedgerStatus.REJECTED, false, false, true, now, 10_000L, now)).allowed()).isTrue();

        // 5. NOT_SEEN 须同时满足写权已释放 + 无活租约 + 安静满 max(30m, lease)
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.ABORTED,
                new Evidence(LedgerStatus.NOT_SEEN, false, true, false, quietSend, 10_000L, now)).allowed()).isTrue();
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.ABORTED,
                new Evidence(LedgerStatus.NOT_SEEN, false, false, false, quietSend, 10_000L, now)).allowed()).isFalse();
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.ABORTED,
                new Evidence(LedgerStatus.NOT_SEEN, false, true, true, quietSend, 10_000L, now)).allowed()).isFalse();
        assertThat(ManualAssetOpJudge.judgeManual(RequestedOutcome.ABORTED,
                new Evidence(LedgerStatus.NOT_SEEN, false, true, false, now - 60_000L, 10_000L, now)).allowed()).isFalse();
    }
}
