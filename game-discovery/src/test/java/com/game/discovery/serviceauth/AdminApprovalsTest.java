package com.game.discovery.serviceauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.common.base.AdminApproval;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 验证资源服务 AdminApprovals 校验与「不带批准记录被接受」告警计数。 */
class AdminApprovalsTest {

    @Test
    void 正常批准放行_缺批准拒绝_应急跳过计入告警指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter counter = AdminApprovals.unapprovedCounter(registry, "mail", "send_personal");

        AdminApproval valid = AdminApproval.newBuilder()
                .setApprovalId("10088").setProposer("alice").setApprover("bob").setApprovedAtMs(1_700_000_000_000L).build();
        AdminApprovals.requireForRequestId(valid, "10088", true, counter, "mail", "send_personal");
        assertThat(counter.count()).isZero();

        assertThatThrownBy(() -> AdminApprovals.requireForRequestId(
                AdminApproval.getDefaultInstance(), "10088", true, counter, "mail", "send_personal"))
                .isInstanceOf(SecurityException.class);
        assertThat(counter.count()).isZero();

        AdminApprovals.requireForRequestId(
                AdminApproval.getDefaultInstance(), "10088", false, counter, "mail", "send_personal");
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
