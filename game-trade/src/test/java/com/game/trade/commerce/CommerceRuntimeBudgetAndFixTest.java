package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.AssetOutcome;
import com.game.common.deadline.Deadline;
import com.game.trade.store.pb.TradeAssetOpRow;
import com.game.trade.store.pb.TradeCommerceRow;
import java.sql.SQLException;
import java.util.ConcurrentModificationException;
import org.junit.jupiter.api.Test;

/** 交易单行预算预留、五类失败分类（L947）与卡死资产指令人工终结（L963）单测。 */
class CommerceRuntimeBudgetAndFixTest {

    @Test
    void 单行推进预算按预留ACK500ms与推迟300ms且剩余折半封顶5s计算() {
        assertThat(CommerceRuntime.stepBudgetMs(10_000L)).isEqualTo(4_600L);
        assertThat(CommerceRuntime.stepBudgetMs(20_000L)).isEqualTo(5_000L);
        assertThat(CommerceRuntime.stepBudgetMs(1_200L)).isEqualTo(200L);
        assertThat(CommerceRuntime.stepBudgetMs(999L)).isZero();
        assertThat(CommerceRuntime.stepBudgetMs(800L)).isZero();
    }

    @Test
    void 五类失败分类精准区分conflict_roleConflict_ownerChanged_corrupt_backpressure与retry() {
        TradeCommerceRow normal = TradeCommerceRow.newBuilder().setCommerceId(1L).setKind(1).build();
        TradeCommerceRow rmb = TradeCommerceRow.newBuilder().setCommerceId(2L).setKind(6).build();

        assertThat(CommerceRuntime.classifyContractFailure(normal, new ConcurrentModificationException("stale")))
                .isEqualTo("conflict");
        assertThat(CommerceRuntime.classifyContractFailure(normal, new SQLException("serialization", "40001")))
                .isEqualTo("conflict");
        assertThat(CommerceRuntime.classifyContractFailure(normal, new CommerceRules.Refusal(CommerceRules.CONFLICT)))
                .isEqualTo("role_conflict");
        assertThat(CommerceRuntime.classifyContractFailure(normal, new CommerceEngine.OwnerChangedContract("moved")))
                .isEqualTo("owner_changed");
        assertThat(CommerceRuntime.classifyContractFailure(rmb, new CommerceEngine.OwnerChangedContract("moved")))
                .isEqualTo("retry");
        assertThat(CommerceRuntime.classifyContractFailure(normal, new CommerceEngine.CorruptContract("bad")))
                .isEqualTo("corrupt");
        assertThat(CommerceRuntime.classifyContractFailure(normal, new CommerceStore.PendingBackpressure()))
                .isEqualTo("too_many_pending");
        assertThat(CommerceRuntime.classifyContractFailure(normal, new SQLException("io", "08001")))
                .isEqualTo("retry");
    }

    @Test
    void 交易卡死资产指令人工终结前置门禁与参数校验() {
        assertThatThrownBy(() -> new TradeAssetOpFixService(null, () -> 0L))
                .isInstanceOf(NullPointerException.class);
        assertThat(TradeAssetOpFixService.MIN_ATTEMPTS_FOR_MANUAL_RESOLVE).isEqualTo(10);
        assertThat(TradeAssetOpFixService.STATUS_PENDING).isEqualTo(1);
        assertThat(TradeAssetOpFixService.STATUS_APPLIED).isEqualTo(2);
        assertThat(TradeAssetOpFixService.STATUS_REJECTED).isEqualTo(3);
        assertThat(TradeAssetOpFixService.STATUS_ABORTED).isEqualTo(4);
        assertThat(AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE).isZero();
        assertThat(TradeAssetOpRow.getDefaultInstance().getResolvedBy()).isEmpty();
        assertThat(Deadline.after(1000).remainingMillis()).isPositive();
    }
}
