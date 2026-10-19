package com.game.player.store.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.InboxDecisionState;
import com.game.player.store.state.InboxDomainCursorState;
import com.game.player.store.state.InboxLedgerState;
import org.junit.jupiter.api.Test;

class InboxLedgerRulesTest {

    private static InboxDecisionState decision(long seq, int outcome, long requestId) {
        return InboxDecisionState.newBuilder().setSeq(seq).setOutcome(outcome).setRequestId(requestId).setMsgType(1).build();
    }

    private static InboxDomainCursorState.Builder cursor(int domain, long through) {
        return InboxDomainCursorState.newBuilder().setDomain(domain).setDecidedThroughSeq(through);
    }

    @Test
    void 合法账本通过_空账本也通过() {
        assertThat(InboxLedgerRules.validate(InboxLedgerState.getDefaultInstance(), true)).isNull();
        InboxLedgerState ledger = InboxLedgerState.newBuilder()
                .addCursors(cursor(1, 30).addRecent(decision(10, 1, 7)).addRecent(decision(30, 3, 8)))
                .build();
        assertThat(InboxLedgerRules.validate(ledger, true)).isNull();
        assertThat(InboxLedgerRules.findCursor(ledger, 1).getDecidedThroughSeq()).isEqualTo(30);
        assertThat(InboxLedgerRules.findCursor(ledger, 2)).isNull();
        assertThat(InboxLedgerRules.decidedThrough(ledger, 2)).isZero();
        assertThat(InboxLedgerRules.findRecent(ledger.getCursors(0), 30).getRequestId()).isEqualTo(8);
        assertThat(InboxLedgerRules.findRecent(ledger.getCursors(0), 20)).isNull();
    }

    @Test
    void 逐条拒绝_域为0_域重复_环超长_seq为0_不升序_越过水位_结局不可记() {
        assertThat(InboxLedgerRules.validate(InboxLedgerState.newBuilder().addCursors(cursor(0, 0)).build(), false))
                .contains("domain is 0");
        assertThat(InboxLedgerRules.validate(InboxLedgerState.newBuilder().addCursors(cursor(1, 0)).addCursors(cursor(1, 0)).build(),
                false)).contains("duplicate domain");
        InboxDomainCursorState.Builder longRing = cursor(1, 1000);
        for (int i = 1; i <= InboxLedgerRules.RECENT_CAPACITY + 1; i++) {
            longRing.addRecent(decision(i, 1, i));
        }
        assertThat(InboxLedgerRules.validate(InboxLedgerState.newBuilder().addCursors(longRing).build(), false))
                .contains("recent has 33 entries");
        assertThat(InboxLedgerRules.validate(ledgerOf(cursor(1, 5).addRecent(decision(0, 1, 1))), false)).contains("seq is 0");
        assertThat(InboxLedgerRules.validate(ledgerOf(cursor(1, 5).addRecent(decision(3, 1, 1)).addRecent(decision(3, 1, 2))), false))
                .contains("not strictly ascending");
        assertThat(InboxLedgerRules.validate(ledgerOf(cursor(1, 5).addRecent(decision(6, 1, 1))), false)).contains("beyond");
        assertThat(InboxLedgerRules.validate(ledgerOf(cursor(1, 5).addRecent(decision(5, InboxLedgerRules.OUTCOME_UNKNOWN, 1))), false))
                .contains("not recordable");
    }

    @Test
    void request_id为0只在证据口径下判坏() {
        InboxLedgerState ledger = ledgerOf(cursor(1, 5).addRecent(decision(5, 1, 0)));
        assertThat(InboxLedgerRules.validate(ledger, false)).isNull();
        assertThat(InboxLedgerRules.validate(ledger, true)).contains("request_id is 0");
    }

    @Test
    void seq按无符号比较_高位置位的seq大于小seq() {
        long huge = 0x8000_0000_0000_0010L;
        InboxLedgerState ledger = ledgerOf(cursor(1, huge).addRecent(decision(7, 1, 1)).addRecent(decision(huge, 2, 2)));
        assertThat(InboxLedgerRules.validate(ledger, true)).isNull();
    }

    private static InboxLedgerState ledgerOf(InboxDomainCursorState.Builder cursor) {
        return InboxLedgerState.newBuilder().addCursors(cursor).build();
    }
}
