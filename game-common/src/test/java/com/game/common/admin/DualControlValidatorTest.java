package com.game.common.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.RunMode;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-26 双人复核共享校验与生产应急开关拦截。 */
class DualControlValidatorTest {

    @Test
    void 双人复核强验四项与两人不同_核对请求号与操作人_生产禁开应急放行() {
        assertThat(DualControlValidator.validate("10001", "ops-alice", "ops-bob", 1_700_000_000_000L,
                "ops-alice", "10001", true)).isEqualTo(DualControlValidator.Outcome.APPROVED);

        // 缺记录 / 自批 / 请求号不符 / 操作人不符一律拒绝
        assertThatThrownBy(() -> DualControlValidator.validate("", "", "", 0L, null, null, true))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> DualControlValidator.validate("10001", "ops-alice", "ops-alice", 1000L, null, null, true))
                .isInstanceOf(SecurityException.class).hasMessageContaining("自批");
        assertThatThrownBy(() -> DualControlValidator.validate("10001", "ops-alice", "ops-bob", 1000L, null, "99999", true))
                .isInstanceOf(SecurityException.class).hasMessageContaining("request_id");
        assertThatThrownBy(() -> DualControlValidator.validate("10001", "ops-alice", "ops-bob", 1000L, "ops-charlie", null, true))
                .isInstanceOf(SecurityException.class).hasMessageContaining("proposer");

        // 非生产应急放行返回 UNAPPROVED_BYPASSED（供服务打告警指标）
        assertThat(DualControlValidator.validate("", "", "", 0L, null, null, false))
                .isEqualTo(DualControlValidator.Outcome.UNAPPROVED_BYPASSED);

        // 生产模式禁开应急放行开关
        assertThatThrownBy(() -> DualControlValidator.assertProductionPolicy(RunMode.PROD, true))
                .isInstanceOf(IllegalStateException.class);
        DualControlValidator.assertProductionPolicy(RunMode.DEV, true);
        DualControlValidator.assertProductionPolicy(RunMode.PROD, false);
    }
}
