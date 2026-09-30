package com.game.scene.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerKicked;
import com.game.proto.MessageContent;
import com.game.scene.metrics.SceneMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

class GateLinksTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GateLinks links = new GateLinks(new SceneMetrics(meters));

    @Test
    void 同一gate节点号_代次更低的新链路被拒_相同或更高的顶替() {
        EmbeddedChannel a = new EmbeddedChannel();
        EmbeddedChannel b = new EmbeddedChannel();
        EmbeddedChannel c = new EmbeddedChannel();
        assertThat(links.register(1, 5, "gate-new", 8, a).accepted()).isTrue();

        GateLinks.Registration stale = links.register(2, 5, "gate-old", 7, b);
        assertThat(stale.accepted()).isFalse();
        assertThat(stale.current().linkId()).isEqualTo(1);
        assertThat(links.isRegistered(2)).isFalse();

        GateLinks.Registration same = links.register(3, 5, "gate-new", 8, c);
        assertThat(same.accepted()).isTrue();
        assertThat(same.replaced().linkId()).isEqualTo(1);
        assertThat(links.isRegistered(1)).isFalse();
        assertThat(links.isRegistered(3)).isTrue();
    }

    @Test
    void 踢出通知写成PlayerKicked帧() {
        EmbeddedChannel ch = new EmbeddedChannel();
        links.register(1, 5, "gate", 1, ch);

        links.playerKicked(1, 11, 1001, 4, 2017);

        assertThat(((NodeLinkFrame) ch.readOutbound()).getPlayerKicked()).isEqualTo(PlayerKicked.newBuilder()
                .setSessionId(11).setPlayerId(1001).setOwnerEpoch(4).setTipId(2017).build());
        assertThat(framesOut("player_kicked")).isEqualTo(1);
    }

    @Test
    void 出站帧按类型计数_链路已注销或已断开的帧计为丢弃() {
        EmbeddedChannel ch = new EmbeddedChannel();
        links.register(1, 5, "gate", 1, ch);

        links.send(1, List.of(11, 12), MessageContent.newBuilder().setMessageId(79).build());
        links.enterResult(1, 11, 1001, 3, 0);
        links.send(2, List.of(21), MessageContent.newBuilder().setMessageId(79).build());
        ch.close();
        links.send(1, List.of(11), MessageContent.newBuilder().setMessageId(51).build());

        assertThat(framesOut("to_client")).as("一帧可带多个会话，按帧计").isEqualTo(1);
        assertThat(framesOut("player_enter_result")).isEqualTo(1);
        assertThat(dropped("link_gone")).as("未登记的链路 + 已断开的链路").isEqualTo(2);
    }

    @Test
    void 出站缓冲越过高水位_断开链路而不是继续堆积() {
        EmbeddedChannel ch = new EmbeddedChannel();
        links.register(1, 5, "gate", 1, ch);
        // gate 读不动：未冲刷的帧堆过高水位。
        ch.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 8));
        ch.write(NodeLinkFrame.getDefaultInstance());
        ch.write(NodeLinkFrame.getDefaultInstance());
        assertThat(ch.isWritable()).isFalse();

        links.send(1, List.of(11), MessageContent.newBuilder().setMessageId(79).build());

        assertThat(ch.isOpen()).isFalse();
        assertThat(dropped("write_buffer_full")).isEqualTo(1);
        assertThat(framesOut("to_client")).isZero();
    }

    private double framesOut(String type) {
        return meters.get("xm.scene.link.frames").tag("direction", "out").tag("type", type).counter().count();
    }

    private double dropped(String reason) {
        return meters.get("xm.scene.link.dropped").tag("reason", reason).counter().count();
    }
}
