package com.game.data.consistency;

import java.util.Objects;

/**
 * 到期成局恢复的只读形状判定。规则依据 GlobalMatchStore.resolveTerminalAfterDeadline，
 * 不调用其事务、锁或任何恢复方法；SAFE 只表示本检查没有需要处理的到期候选。
 * 合法的 pending 形状仍需完整 Create 绑定校验，本类明确返回 UNKNOWN。
 */
public final class MatchExpiredShape {
    /** 与生产终局恢复相同：原 deadline 之后给终局提交保留 60 秒。 */
    public static final long TERMINAL_OUTCOME_GRACE_MILLIS = 60_000L;

    public enum Status { SAFE, BROKEN, UNKNOWN }

    public record Decision(Status status, String reason) {
        public Decision {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * completion 字段使用可空类型，禁止 JDBC getInt/getBoolean 的默认零吞掉 SQL NULL。
     * createLength 为 OCTET_LENGTH(create_request)，负值表示长度未知；create 可为 null，
     * 表示本次仅取了长度。正文只在内存中使用，不能加入报告或日志。
     */
    public record Row(long battleId, String operationId, String instanceId, int state,
            long deadlineMillis, long createLength, boolean completionMissing,
            Integer terminalDecision, String placementOperationId, String targetInstanceId,
            Integer outcomeProtocolVersion, Integer preflightSupportedVersion,
            String preflightInstanceId, Integer adoptedOutcomeProtocolVersion,
            Boolean adoptedOutcomeObserved, byte[] create) {
        public Row {
            create = create == null ? null : create.clone();
        }

        @Override
        public byte[] create() {
            return create == null ? null : create.clone();
        }

        /** record 默认 toString 会暴露身份原件，诊断也只能给固定字段名。 */
        @Override
        public String toString() {
            return "MatchExpiredShape.Row[redacted]";
        }
    }

    private MatchExpiredShape() {}

    /** dbNowMillis 必须来自本页同一 SQL 会话，不接受进程墙钟代替。 */
    public static Decision inspect(Row row, long dbNowMillis) {
        Objects.requireNonNull(row, "row");
        if (dbNowMillis <= 0) return unknown("sql_time_invalid");
        if (row.state() < 1 || row.state() > 5) return unknown("unsupported_formation_state");
        if (row.state() == 1 || row.state() == 5) return safe("outside_expired_recovery_scope");

        if (!row.completionMissing()
                && (Integer.valueOf(1).equals(row.terminalDecision())
                    || Integer.valueOf(2).equals(row.terminalDecision()))) {
            return safe("terminal_already_decided");
        }
        // 未建房且无终局原件可走 finishUncreated。state=4 已建房不能据此声称有安全出口，
        // 空 Create 却有 completion 也超出该出口的保证；不把未证明的形状归为通过。
        if (row.createLength() == 0) {
            return row.state() < 4 && row.completionMissing()
                    ? safe("uncreated_exit_available") : unknown("uncreated_exit_unproved");
        }
        if (row.createLength() < 0) return unknown("create_length_unknown");
        if (row.deadlineMillis() <= 0) return unknown("deadline_invalid");
        // 先比较再相减，不能用 deadline + grace，后者可溢出后误判到期。
        if (dbNowMillis < row.deadlineMillis()
                || dbNowMillis - row.deadlineMillis() < TERMINAL_OUTCOME_GRACE_MILLIS) {
            return safe("not_expired");
        }

        if (row.completionMissing()) return broken("completion_missing");
        Integer decision = row.terminalDecision();
        if (decision == null) return broken("terminal_decision_null");
        if (decision != 0) return broken("terminal_decision_invalid");

        if (row.operationId() == null || row.operationId().isEmpty()
                || row.instanceId() == null || row.instanceId().isEmpty()) {
            return broken("formation_identity_missing");
        }
        if (!row.operationId().equals(row.placementOperationId())) return broken("operation_mismatch");
        if (!row.instanceId().equals(row.targetInstanceId())) return broken("instance_mismatch");
        if (!Integer.valueOf(1).equals(row.outcomeProtocolVersion())) return broken("outcome_protocol_invalid");
        if (!Integer.valueOf(1).equals(row.preflightSupportedVersion())) return broken("preflight_unsupported");
        if (!row.instanceId().equals(row.preflightInstanceId())) return broken("preflight_instance_mismatch");
        if (row.adoptedOutcomeObserved() == null) return broken("adopted_observation_null");
        if (row.adoptedOutcomeObserved()
                && !Integer.valueOf(1).equals(row.adoptedOutcomeProtocolVersion())) {
            return broken("adopted_protocol_incompatible");
        }
        // 只取得一部分正文或两次读取发生变化，不能把未验证的正文称为坏原件或完整覆盖。
        if (row.create != null && row.create.length != row.createLength()) {
            return unknown("incomplete_create_read");
        }
        // formation.members 是本模块不可见的内部 PB holder。只有 SQL 形状，不能证明
        // Create 的成员顺序、模式队伍、activity context 等与完整 formation 一致。
        return unknown("deep_validation_unavailable");
    }

    private static Decision safe(String reason) { return new Decision(Status.SAFE, reason); }
    private static Decision broken(String reason) { return new Decision(Status.BROKEN, reason); }
    private static Decision unknown(String reason) { return new Decision(Status.UNKNOWN, reason); }
}
