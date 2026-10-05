package com.game.battle.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.battle.BattleIdentity;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.TicketPath;
import com.game.common.token.BattleTickets;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.eBattleTicketRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 签发落点分配包（基线 {@code BuildAssignment}，{@code room.cpp:1432-1498}；battle-node-spec §2.1–§2.5、§7.5）。 */
class BattleTicketIssuerTest {

    private static final String SECRET = "issuer-test-ticket-secret-0123456789abcdef";
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final BattleIdentity identity = new BattleIdentity(7, "instance-uuid", "battle.test", 12345);
    private final BattleTickets tickets = BattleTickets.ofUtf8(SECRET);

    private double tickets(String path, String result) {
        return registry.get("xm.battle.tickets").tag("path", path).tag("result", result).counter().count();
    }

    @Test
    void 字段与通告地址正确_签的就是下发的那份字节() throws Exception {
        BattleTicketIssuer issuer = new BattleTicketIssuer(identity, tickets, metrics);

        BattleAssignedS2C assigned = issuer.issue(99, 5001, 1_760_000_300_000L, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER,
                TicketPath.OBSERVER).orElseThrow();

        assertThat(assigned.getBattleId()).isEqualTo(99);
        assertThat(assigned.getHost()).isEqualTo("battle.test");
        assertThat(assigned.getPort()).isEqualTo(12345);
        assertThat(assigned.getExpireAtMs()).isEqualTo(1_760_000_300_000L);
        assertThat(assigned.getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        BattleTicketPayload payload = BattleTicketPayload.parseFrom(assigned.getTokenPayload());
        assertThat(payload.getBattleId()).isEqualTo(99);
        assertThat(payload.getPlayerId()).isEqualTo(5001);
        assertThat(payload.getBattleNodeId()).isEqualTo(7);
        assertThat(payload.getBattleInstanceId()).isEqualTo("instance-uuid");
        assertThat(payload.getExpireAtMs()).isEqualTo(1_760_000_300_000L);
        assertThat(payload.getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        assertThat(assigned.getTokenSignature().toString(StandardCharsets.US_ASCII)).matches("^[0-9a-f]{64}$");
        assertThat(tickets.signatureMatches(assigned.getTokenPayload(), assigned.getTokenSignature())).isTrue();
        assertThat(BattleTickets.classify(payload, 7, "instance-uuid", 1_760_000_299_999L)).isEqualTo(BattleTickets.Verdict.OK);
        assertThat(tickets("observer", "ok")).isEqualTo(1);
    }

    @Test
    void 同一房间同一玩家同一角色补签出的票逐字节相同() {
        BattleTicketIssuer issuer = new BattleTicketIssuer(identity, tickets, metrics);

        BattleAssignedS2C first = issuer.issue(99, 5001, 1000, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT, TicketPath.CREATE)
                .orElseThrow();
        BattleAssignedS2C again = issuer.issue(99, 5001, 1000, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT, TicketPath.REISSUE)
                .orElseThrow();

        assertThat(again.toByteString()).isEqualTo(first.toByteString());
    }

    @Test
    void 签名器抛异常时fail_closed_返回空并计failed() {
        BattleTickets broken = mock(BattleTickets.class);
        when(broken.sign(any())).thenThrow(new IllegalStateException("测试：JCA 故障"));
        BattleTicketIssuer issuer = new BattleTicketIssuer(identity, broken, metrics);

        Optional<BattleAssignedS2C> assigned = issuer.issue(99, 5001, 1000, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT,
                TicketPath.REISSUE);

        assertThat(assigned).isEmpty();
        assertThat(tickets("reissue", "failed")).isEqualTo(1);
        assertThat(tickets("reissue", "ok")).isZero();
    }
}
