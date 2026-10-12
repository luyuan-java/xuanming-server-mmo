package com.game.battle.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.token.BattleTickets;
import org.junit.jupiter.api.Test;

/** 拒绝日志采样与原因名（基线 {@code edge.cpp:32-49}；battle-node-spec §7.4）。 */
class EdgeRejectLogTest {

    @Test
    void 每种原因首次必打_之后每1024次一行_原因之间互不影响() {
        EdgeRejectLog log = new EdgeRejectLog();
        assertThat(log.record(EdgeRejectReason.HANDSHAKE_TIMEOUT, "p")).isTrue();
        for (int i = 2; i <= 1024; i++) {
            assertThat(log.record(EdgeRejectReason.HANDSHAKE_TIMEOUT, "p")).as("第 %d 次", i).isFalse();
        }
        assertThat(log.record(EdgeRejectReason.HANDSHAKE_TIMEOUT, "p")).as("第 1025 次").isTrue();
        assertThat(log.record(EdgeRejectReason.TICKET_HMAC_MISMATCH, "p")).as("签名伪造不会被超时噪声淹没").isTrue();
        assertThat(log.count(EdgeRejectReason.HANDSHAKE_TIMEOUT)).isEqualTo(1025);
    }

    @Test
    void 原因名与票据判定名一致() {
        for (BattleTickets.Verdict verdict : BattleTickets.Verdict.values()) {
            if (verdict == BattleTickets.Verdict.OK) {
                assertThatThrownBy(() -> EdgeRejectReason.of(verdict)).isInstanceOf(IllegalArgumentException.class);
            } else {
                assertThat(EdgeRejectReason.of(verdict).wireName()).isEqualTo(verdict.wireName());
            }
        }
        assertThat(EdgeRejectReason.TICKET_NOT_IN_ROSTER.wireName()).isEqualTo("ticket_not_in_roster");
        assertThat(EdgeRejectReason.MESSAGE_ID_NOT_ALLOWED.wireName()).isEqualTo("message_id_not_allowed");
    }
}
