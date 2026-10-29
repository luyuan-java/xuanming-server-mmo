package com.game.data.consistency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MatchExpiredShapeTest {
    private static final long DEADLINE = 100_000L;
    private static final long EXPIRED = DEADLINE + 60_000L;

    @Test
    void missingCompletionIsBrokenOnlyAfterEntireGrace() {
        var row = row(2, DEADLINE, 1, true, null, "op", "instance", 1, 1, "instance", 0, false, null);
        assertDecision(row, EXPIRED - 1, MatchExpiredShape.Status.SAFE, "not_expired");
        assertDecision(row, EXPIRED, MatchExpiredShape.Status.BROKEN, "completion_missing");
    }

    @Test
    void noCreateAndUncreatedTerminalAreLegitimateExits() {
        for (int state : new int[] {1, 2, 3, 5}) {
            var row = row(state, DEADLINE, 0, true, null, null, null, null, null, null, null, null, null);
            assertThat(MatchExpiredShape.inspect(row, EXPIRED).status()).isEqualTo(MatchExpiredShape.Status.SAFE);
        }
    }

    @Test
    void createdStateOrExistingPreflightCannotClaimUncreatedExit() {
        assertDecision(row(4, DEADLINE, 0, true, null, null, null, null, null, null, null, null, null),
                EXPIRED, MatchExpiredShape.Status.UNKNOWN, "uncreated_exit_unproved");
        assertDecision(row(3, DEADLINE, 0, false, 0, "op", "instance", 1, 1, "instance", 0, false, null),
                EXPIRED, MatchExpiredShape.Status.UNKNOWN, "uncreated_exit_unproved");
    }

    @Test
    void completeAndAbortNoLongerNeedRecoveryEvenIfOtherShapeFieldsAreMissing() {
        for (int decision : new int[] {1, 2}) {
            var row = row(4, DEADLINE, 1, false, decision, null, null, null, null, null, null, null, null);
            assertDecision(row, EXPIRED, MatchExpiredShape.Status.SAFE, "terminal_already_decided");
        }
    }

    @Test
    void unseenAdoptedZeroAndObservedOneRemainUnknownUntilFullCreateValidation() {
        assertDecision(valid(false, 0), EXPIRED, MatchExpiredShape.Status.UNKNOWN, "deep_validation_unavailable");
        assertDecision(valid(true, 1), EXPIRED, MatchExpiredShape.Status.UNKNOWN, "deep_validation_unavailable");
        assertDecision(valid(true, 0), EXPIRED, MatchExpiredShape.Status.BROKEN, "adopted_protocol_incompatible");
    }

    @Test
    void nullObservationMustNotBecomeFalse() {
        assertDecision(valid(null, 0), EXPIRED, MatchExpiredShape.Status.BROKEN, "adopted_observation_null");
        assertDecision(valid(true, null), EXPIRED, MatchExpiredShape.Status.BROKEN, "adopted_protocol_incompatible");
    }

    @Test
    void eachImmutableBindingIsCheckedSeparately() {
        assertDecision(row(2, DEADLINE, 1, false, 0, "other-op", "instance", 1, 1, "instance", 0, false, null),
                EXPIRED, MatchExpiredShape.Status.BROKEN, "operation_mismatch");
        assertDecision(row(2, DEADLINE, 1, false, 0, "op", "other-instance", 1, 1, "instance", 0, false, null),
                EXPIRED, MatchExpiredShape.Status.BROKEN, "instance_mismatch");
        assertDecision(row(2, DEADLINE, 1, false, 0, "op", "instance", 1, 1, "other-instance", 0, false, null),
                EXPIRED, MatchExpiredShape.Status.BROKEN, "preflight_instance_mismatch");
    }

    @Test
    void missingAndIncompatibleProtocolProofCannotBecomeVersionZero() {
        for (Integer version : new Integer[] {null, 0, 2}) {
            assertDecision(row(3, DEADLINE, 1, false, 0, "op", "instance", version, 1, "instance", 0, false, null),
                    EXPIRED, MatchExpiredShape.Status.BROKEN, "outcome_protocol_invalid");
            assertDecision(row(3, DEADLINE, 1, false, 0, "op", "instance", 1, version, "instance", 0, false, null),
                    EXPIRED, MatchExpiredShape.Status.BROKEN, "preflight_unsupported");
        }
    }

    @Test
    void illegalTerminalDecisionCannotBeReadAsPending() {
        assertDecision(row(2, DEADLINE, 1, false, null, "op", "instance", 1, 1, "instance", 0, false, null),
                EXPIRED, MatchExpiredShape.Status.BROKEN, "terminal_decision_null");
        assertDecision(row(2, DEADLINE, 1, false, 3, "op", "instance", 1, 1, "instance", 0, false, null),
                EXPIRED, MatchExpiredShape.Status.BROKEN, "terminal_decision_invalid");
    }

    @Test
    void invalidSqlTimeAndUnknownLengthAreNotSuccessfulCoverage() {
        assertDecision(valid(false, 0), 0, MatchExpiredShape.Status.UNKNOWN, "sql_time_invalid");
        assertDecision(row(2, DEADLINE, -1, false, 0, "op", "instance", 1, 1, "instance", 0, false, null),
                EXPIRED, MatchExpiredShape.Status.UNKNOWN, "create_length_unknown");
    }

    @Test
    void futureDeadlineNearLongMaximumDoesNotOverflowIntoExpired() {
        var row = row(2, Long.MAX_VALUE - 1, 1, true, null, "op", "instance", 1, 1, "instance", 0, false, null);
        assertDecision(row, Long.MAX_VALUE, MatchExpiredShape.Status.SAFE, "not_expired");
    }

    @Test
    void incompleteBlobReadIsUnknownAndNeverTreatedAsEmptyCreate() {
        var row = row(2, DEADLINE, 2, false, 0, "op", "instance", 1, 1, "instance", 0, false, new byte[] {1});
        assertDecision(row, EXPIRED, MatchExpiredShape.Status.UNKNOWN, "incomplete_create_read");
    }

    @Test
    void highUnsignedBattleIdIsNotRejectedOrExposedAndBytesAreDefensive() {
        byte[] bytes = {1};
        var row = row(2, DEADLINE, 1, false, 0, "op", "instance", 1, 1, "instance", 0, false, bytes);
        bytes[0] = 9;
        assertThat(row.create()).containsExactly((byte) 1);
        byte[] exposed = row.create();
        exposed[0] = 8;
        assertThat(row.create()).containsExactly((byte) 1);
        assertThat(row.battleId()).isEqualTo(Long.MIN_VALUE);
        assertThat(row.toString()).isEqualTo("MatchExpiredShape.Row[redacted]");
        assertDecision(row, EXPIRED, MatchExpiredShape.Status.UNKNOWN, "deep_validation_unavailable");
    }

    private static MatchExpiredShape.Row valid(Boolean observed, Integer adoptedVersion) {
        return row(2, DEADLINE, 1, false, 0, "op", "instance", 1, 1, "instance", adoptedVersion, observed, null);
    }

    private static MatchExpiredShape.Row row(int state, long deadline, long length, boolean missing,
            Integer decision, String completionOperation, String completionInstance,
            Integer protocol, Integer supported, String preflightInstance, Integer adopted,
            Boolean observed, byte[] create) {
        return new MatchExpiredShape.Row(Long.MIN_VALUE, "op", "instance", state, deadline, length,
                missing, decision, completionOperation, completionInstance, protocol, supported,
                preflightInstance, adopted, observed, create);
    }

    private static void assertDecision(MatchExpiredShape.Row row, long now,
            MatchExpiredShape.Status status, String reason) {
        assertThat(MatchExpiredShape.inspect(row, now)).isEqualTo(new MatchExpiredShape.Decision(status, reason));
    }
}
