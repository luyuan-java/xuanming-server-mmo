package com.game.gate.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.LinkHello;
import com.game.common.token.NodeLinkAuth;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LinkHellosTest {

    private static final long NOW = 1_800_000_000L;

    @Test
    void 握手帧带身份_租约代次与建链时刻的时间戳_scene用同一密钥验签通过() {
        NodeLinkAuth auth = NodeLinkAuth.ofUtf8("link-secret");
        LinkHello hello = new LinkHellos(3, "gate-uuid", 1, 9, auth, InstantSource.fixed(Instant.ofEpochSecond(NOW))).get();

        assertThat(hello.getGateNodeId()).isEqualTo(3);
        assertThat(hello.getGateInstanceId()).isEqualTo("gate-uuid");
        assertThat(hello.getZoneId()).isEqualTo(1);
        assertThat(hello.getLeaseEpoch()).isEqualTo(9);
        assertThat(hello.getAuthTimestamp()).isEqualTo(NOW);
        assertThat(auth.verify(hello.getGateNodeId(), hello.getGateInstanceId(), hello.getZoneId(), hello.getLeaseEpoch(),
                hello.getAuthTimestamp(), hello.getAuthMac(), NOW)).isEqualTo(NodeLinkAuth.Verdict.OK);
        assertThat(NodeLinkAuth.ofUtf8("other").verify(3, "gate-uuid", 1, 9, NOW, hello.getAuthMac(), NOW))
                .isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
        assertThat(auth.verify(3, "gate-uuid", 1, 10, NOW, hello.getAuthMac(), NOW)).as("代次被改大")
                .isEqualTo(NodeLinkAuth.Verdict.BAD_MAC);
    }

    @Test
    void 每次调用取当前时间() {
        AtomicLong seconds = new AtomicLong(NOW);
        LinkHellos hellos = new LinkHellos(3, "gate-uuid", 1, 1, NodeLinkAuth.ofUtf8("s"),
                () -> Instant.ofEpochSecond(seconds.get()));
        assertThat(hellos.get().getAuthTimestamp()).isEqualTo(NOW);
        seconds.addAndGet(3600);
        assertThat(hellos.get().getAuthTimestamp()).isEqualTo(NOW + 3600);
    }
}
