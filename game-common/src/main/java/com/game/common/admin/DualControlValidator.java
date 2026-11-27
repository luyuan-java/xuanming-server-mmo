package com.game.common.admin;

import com.game.common.RunMode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 共享的高风险管理动作双人复核校验器（对齐主仓 {@code docs/design/admin-identity-and-dual-control.md} §5.4、§11）：
 * <ul>
 *   <li>四项必填：{@code approvalId}（十进制正整数）、{@code proposer}、{@code approver}、{@code approvedAtMs > 0}；</li>
 *   <li>严格不同人：{@code !proposer.equals(approver)}；</li>
 *   <li>带请求号的下游（邮件、商城、榜单等）强验 {@code approvalId.equals(expectedRequestId)}，用下游幂等键防重放；</li>
 *   <li>无请求号的下游强验 {@code proposer.equals(expectedOperator)}；</li>
 *   <li>应急跳过开关（{@code allowUnapproved=true}）在 {@link RunMode#PROD} 下启动即拒绝（fail-closed）。</li>
 * </ul>
 */
public final class DualControlValidator {

    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,19}");
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    /** 生产模式下拒绝把应急放行开关置为 true（部署与启动双门禁）。 */
    public static void assertProductionPolicy(RunMode runMode, boolean allowUnapproved) {
        if (runMode == RunMode.PROD && allowUnapproved) {
            throw new IllegalStateException("生产模式严禁开启双人复核应急放行开关（allow-unapproved=true）");
        }
    }

    public enum Outcome {
        APPROVED,
        UNAPPROVED_BYPASSED
    }

    private DualControlValidator() {
    }

    /**
     * 校验双人复核批准记录；不合格一律抛出 {@link SecurityException}。
     *
     * @param approvalId        后台流水号（十进制正整数文本）
     * @param proposer          发起人主体
     * @param approver          批准人主体（必须与 proposer 不同）
     * @param approvedAtMs      首次批准时间戳（> 0）
     * @param expectedOperator  若非空，要求 {@code proposer.equals(expectedOperator)}
     * @param expectedRequestId 若非空，要求 {@code approvalId.equals(expectedRequestId)}
     * @param required          true = 强验；false = 应急模式允许空批准并记告警计数（但一旦传了批准记录仍须合法）
     */
    public static Outcome validate(
            String approvalId,
            String proposer,
            String approver,
            long approvedAtMs,
            String expectedOperator,
            String expectedRequestId,
            boolean required) {
        boolean empty = (approvalId == null || approvalId.isBlank())
                && (proposer == null || proposer.isBlank())
                && (approver == null || approver.isBlank())
                && approvedAtMs == 0L;
        if (empty) {
            if (!required) {
                return Outcome.UNAPPROVED_BYPASSED;
            }
            throw new SecurityException("缺少双人复核批准记录（AdminApproval）");
        }
        String aid = approvalId == null ? "" : approvalId.strip();
        String prop = proposer == null ? "" : proposer.strip();
        String appr = approver == null ? "" : approver.strip();
        if (!POSITIVE_DECIMAL.matcher(aid).matches()) {
            throw new SecurityException("双人复核 approval_id 必须为十进制正整数");
        }
        if (!isValidOperator(prop) || !isValidOperator(appr)) {
            throw new SecurityException("双人复核 proposer/approver 操作人标识非法");
        }
        if (prop.equals(appr)) {
            throw new SecurityException("双人复核拒绝自批：proposer 与 approver 必须为不同管理员");
        }
        if (approvedAtMs <= 0L) {
            throw new SecurityException("双人复核 approved_at_ms 必须为正整数");
        }
        if (expectedOperator != null && !expectedOperator.isBlank() && !prop.equals(expectedOperator.strip())) {
            throw new SecurityException("双人复核发起人 proposer 与请求操作人不一致");
        }
        if (expectedRequestId != null && !expectedRequestId.isBlank() && !aid.equals(expectedRequestId.strip())) {
            throw new SecurityException("双人复核 approval_id 必须与请求幂等号 request_id 一致");
        }
        return Outcome.APPROVED;
    }

    public static boolean isValidOperator(String name) {
        return name != null && !name.isBlank()
                && name.getBytes(StandardCharsets.UTF_8).length <= 64
                && OPERATOR_PATTERN.matcher(name).matches();
    }
}
