package com.game.discovery.serviceauth;

import com.game.common.admin.DualControlValidator;
import com.game.proto.common.base.AdminApproval;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资源服务侧 {@link AdminApproval} 复核与「不带批准记录被接受」告警计数（对齐主仓 {@code go/shared/dualcontrol}）。
 * 集群里 {@code game_admin_unapproved_accepted_total} 应恒为 0；非 0 即告警。
 */
public final class AdminApprovals {

    private static final Logger log = LoggerFactory.getLogger(AdminApprovals.class);

    public static final String UNAPPROVED_METRIC = "game.admin.unapproved.accepted";

    private AdminApprovals() {
    }

    public static Counter unapprovedCounter(MeterRegistry registry, String service, String action) {
        if (registry == null) {
            return null;
        }
        return Counter.builder(UNAPPROVED_METRIC)
                .tag("service", service)
                .tag("action", action)
                .description("资源服务在应急模式下不带 AdminApproval 接受的高风险管理调用次数（生产应恒为 0）")
                .register(registry);
    }

    /** 校验带幂等请求号的高风险管理调用（如邮件、商城）：强验 approval_id == requestId。 */
    public static void requireForRequestId(
            AdminApproval approval,
            String requestId,
            boolean required,
            Counter unapprovedCounter,
            String service,
            String action) {
        verify(approval, null, requestId, required, unapprovedCounter, service, action);
    }

    /** 校验无幂等请求号的高风险管理调用（如账号、榜删）：强验 proposer == expectedOperator（可空）。 */
    public static void requireForOperator(
            AdminApproval approval,
            String expectedOperator,
            boolean required,
            Counter unapprovedCounter,
            String service,
            String action) {
        verify(approval, expectedOperator, null, required, unapprovedCounter, service, action);
    }

    private static void verify(
            AdminApproval approval,
            String expectedOperator,
            String expectedRequestId,
            boolean required,
            Counter unapprovedCounter,
            String service,
            String action) {
        AdminApproval a = approval == null ? AdminApproval.getDefaultInstance() : approval;
        DualControlValidator.Outcome outcome = DualControlValidator.validate(
                a.getApprovalId(), a.getProposer(), a.getApprover(), a.getApprovedAtMs(),
                expectedOperator, expectedRequestId, required);
        if (outcome == DualControlValidator.Outcome.UNAPPROVED_BYPASSED) {
            if (unapprovedCounter != null) {
                unapprovedCounter.increment();
            }
            log.warn("[DualControl] accepted unapproved admin action under emergency bypass service={} action={}",
                    service, action);
        }
    }
}
