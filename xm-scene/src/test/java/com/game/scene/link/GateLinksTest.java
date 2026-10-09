package com.game.scene.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.ZoneRedirect;
import com.game.proto.MessageContent;
import com.game.scene.metrics.SceneMetrics;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GateLinksTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GateLinks links = new GateLinks(new SceneMetrics(meters), Runnable::run);

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

    @Test
    void 交出指令写成PlayerTransfer帧_链路已断时返回false且不回调() {
        EmbeddedChannel ch = new EmbeddedChannel();
        links.register(1, 5, "gate", 1, ch);
        AtomicInteger failed = new AtomicInteger();

        assertThat(links.playerTransfer(1, 11, 1001, 4, 5, 7, 900_001, failed::incrementAndGet)).isTrue();
        assertThat(((NodeLinkFrame) ch.readOutbound()).getPlayerTransfer()).isEqualTo(PlayerTransfer.newBuilder()
                .setSessionId(11).setPlayerId(1001).setFromEpoch(4).setToEpoch(5).setTargetSceneNodeId(7)
                .setTargetSceneId(900_001).build());
        assertThat(framesOut("player_transfer")).isEqualTo(1);

        assertThat(links.playerTransfer(2, 11, 1001, 4, 5, 7, 900_001, failed::incrementAndGet))
                .as("未登记的链路").isFalse();
        ch.close();
        assertThat(links.playerTransfer(1, 11, 1001, 4, 5, 7, 900_001, failed::incrementAndGet))
                .as("已断开的链路").isFalse();
        assertThat(failed.get()).as("同步就知道没写出的，不再回调").isZero();
    }

    @Test
    void 交出指令交给链路后异步写失败_回调经逻辑线程执行_计write_failed() {
        List<Runnable> logicTasks = new ArrayList<>();
        GateLinks queued = new GateLinks(new SceneMetrics(meters), logicTasks::add);
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                promise.setFailure(new ClosedChannelException());
            }
        });
        queued.register(1, 5, "gate", 1, ch);
        AtomicInteger failed = new AtomicInteger();

        assertThat(queued.playerTransfer(1, 11, 1001, 4, 5, 7, 900_001, failed::incrementAndGet)).isTrue();

        assertThat(failed.get()).as("不在链路 I/O 线程上直接跑").isZero();
        assertThat(logicTasks).hasSize(1);
        logicTasks.get(0).run();
        assertThat(failed.get()).isEqualTo(1);
        assertThat(dropped("write_failed")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 跨 zone 传送的重定向帧（批次 5.4）

    /** 票据金样（与 xm-common 的 {@code GateTokenIssuerTest} 同一组：7 号 gate、zone 2、持票者 0x0102030405060708、目标 zone 3、过期 1_800_000_300）。 */
    private static final String TICKET_PAYLOAD_HEX = "0807100218aca6a7da0628888e98a8c0e08081013003";
    private static final String TICKET_SIGNATURE = "d79e88f6e0720ed0012ef75ab6b3ed9714561cbe2746e3f8510acbc4493c4902";
    private static final long HOLDER = 0x0102030405060708L;

    /**
     * 重定向帧的字节金样：{@code PlayerTransfer{session 11, player 0x0102030405060708, epoch 4 → 5, redirect{…}}} 序列化后的 131 个字节。
     *
     * <p>期望值<b>不是</b>从被测代码打印出来的：按 proto3 线格式手工排出（签名那 64 个 ASCII 字节用 {@code od} 转成十六进制）：
     * <pre>
     *   08 0b                            session_id   = 11
     *   10 88 8e 98 a8 c0 e0 80 81 01    player_id    = 0x0102030405060708
     *   18 04                            from_epoch   = 4
     *   20 05                            to_epoch     = 5
     *                                    target_scene_node_id / target_scene_id 不出现（都是 0）
     *   3a 71                            redirect，长 113
     *     08 03                            target_zone_id  = 3
     *     10 07                            gate_node_id    = 7
     *     1a 08 31 30 2e 30 2e 30 2e 37    gate_host       = "10.0.0.7"
     *     20 82 56                         gate_port       = 11010
     *     2a 16 （22 字节）                token_payload   = 票据金样的原字节
     *     32 40 （64 字节）                token_signature = 票据金样签名的 ASCII
     *     38 ac a6 a7 da 06                token_deadline  = 1_800_000_300
     * </pre>
     * 用途：scene 写出的帧与 gate 解析的帧是同一串字节。xm-gate 的重定向测试持有<b>同一串常量的另一份拷贝</b>、从它解析出帧再处理——
     * 任何一边改了帧的形状（字段号、类型、多填少填）而没有同步另一边，其中一边就会红。改这里的常量时必须同时改那一份。
     */
    static final String REDIRECT_FRAME_GOLDEN_HEX =
            "080b10888e98a8c0e0808101180420053a71080310071a0831302e302e302e372082562a16"
                    + "0807100218aca6a7da0628888e98a8c0e08081013003"
                    + "3240"
                    + "6437396538386636653037323065643030313265663735616236623365643937"
                    + "3134353631636265323734366533663835313061636263343439336334393032"
                    + "38aca6a7da06";

    private static ZoneRedirect goldenRedirect() {
        return ZoneRedirect.newBuilder()
                .setTargetZoneId(3)
                .setGateNodeId(7)
                .setGateHost("10.0.0.7")
                .setGatePort(11010)
                .setTokenPayload(ByteString.copyFrom(HexFormat.of().parseHex(TICKET_PAYLOAD_HEX)))
                .setTokenSignature(ByteString.copyFrom(TICKET_SIGNATURE, StandardCharsets.US_ASCII))
                .setTokenDeadline(1_800_000_300L)
                .build();
    }

    @Test
    void 重定向帧的字节金样_写出的PlayerTransfer逐字节固定_从金样解析回来字段逐项相等() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        links.register(1, 5, "gate", 1, ch);

        assertThat(links.playerRedirect(1, 11, HOLDER, 4, 5, goldenRedirect(), () -> { })).isTrue();

        NodeLinkFrame frame = ch.readOutbound();
        assertThat(frame.getBodyCase()).isEqualTo(NodeLinkFrame.BodyCase.PLAYER_TRANSFER);
        assertThat(HexFormat.of().formatHex(frame.getPlayerTransfer().toByteArray())).isEqualTo(REDIRECT_FRAME_GOLDEN_HEX);
        assertThat(REDIRECT_FRAME_GOLDEN_HEX.length() / 2).as("131 个字节").isEqualTo(131);

        // 反方向（gate 的视角）：从金样字节解析，五项与票据逐字段取得到
        PlayerTransfer parsed = PlayerTransfer.parseFrom(HexFormat.of().parseHex(REDIRECT_FRAME_GOLDEN_HEX));
        assertThat(parsed.getSessionId()).isEqualTo(11);
        assertThat(parsed.getPlayerId()).isEqualTo(HOLDER);
        assertThat(parsed.getFromEpoch()).isEqualTo(4);
        assertThat(parsed.getToEpoch()).isEqualTo(5);
        assertThat(parsed.getTargetSceneNodeId()).as("重定向帧不带目标节点").isZero();
        assertThat(parsed.getTargetSceneId()).as("重定向帧不带目标场景").isZero();
        assertThat(parsed.hasRedirect()).isTrue();
        ZoneRedirect redirect = parsed.getRedirect();
        assertThat(redirect.getTargetZoneId()).isEqualTo(3);
        assertThat(redirect.getGateNodeId()).isEqualTo(7);
        assertThat(redirect.getGateHost()).isEqualTo("10.0.0.7");
        assertThat(redirect.getGatePort()).isEqualTo(11010);
        assertThat(HexFormat.of().formatHex(redirect.getTokenPayload().toByteArray())).isEqualTo(TICKET_PAYLOAD_HEX);
        assertThat(redirect.getTokenSignature().toString(StandardCharsets.US_ASCII)).isEqualTo(TICKET_SIGNATURE);
        assertThat(redirect.getTokenDeadline()).isEqualTo(1_800_000_300L);
        assertThat(parsed.getUnknownFields().asMap()).as("没有不认识的字段").isEmpty();
    }

    /**
     * 帧字段逐项，以及「票据字节原样」：故意给一份<b>不是规范序列化</b>的 payload（字段倒序、带一个不认识的字段）——任何一环把它解析成
     * {@code GateTokenPayload} 再重新序列化，字节都会变（字段回到升序、未知字段可能被丢），签名随之对不上。
     */
    @Test
    void 重定向帧_字段逐项等于入参_目标节点与场景为0_票据字节原样不重排() {
        EmbeddedChannel ch = new EmbeddedChannel();
        links.register(1, 5, "gate", 1, ch);
        // 30 03 = target_zone_id 3；f8 07 01 = 127 号未知字段；08 07 = gate_node_id 7（倒序）
        ByteString oddPayload = ByteString.copyFrom(HexFormat.of().parseHex("3003f807010807"));
        ByteString signature = ByteString.copyFromUtf8("ab".repeat(32));
        ZoneRedirect redirect = ZoneRedirect.newBuilder().setTargetZoneId(2).setGateNodeId(9).setGateHost("gate-z2.example")
                .setGatePort(65535).setTokenPayload(oddPayload).setTokenSignature(signature).setTokenDeadline(1_900_000_000L)
                .build();

        assertThat(links.playerRedirect(1, 21, 1001, 40, 41, redirect, () -> { })).isTrue();

        PlayerTransfer transfer = ((NodeLinkFrame) ch.readOutbound()).getPlayerTransfer();
        assertThat(transfer).isEqualTo(PlayerTransfer.newBuilder().setSessionId(21).setPlayerId(1001).setFromEpoch(40)
                .setToEpoch(41).setRedirect(redirect).build());
        assertThat(transfer.getTargetSceneNodeId()).isZero();
        assertThat(transfer.getTargetSceneId()).isZero();
        assertThat(transfer.getRedirect().getTokenPayload()).as("原字节，逐字节相等").isEqualTo(oddPayload);
        assertThat(HexFormat.of().formatHex(transfer.getRedirect().getTokenPayload().toByteArray())).isEqualTo("3003f807010807");
        assertThat(transfer.getRedirect().getTokenSignature()).isEqualTo(signature);
        assertThat(framesOut("player_transfer")).as("与改绑指令同一种帧，计在同一个类型下").isEqualTo(1);
        assertThat(ch.<Object>readOutbound()).as("只写了一帧").isNull();
    }

    @Test
    void 重定向帧_未登记或已断开的链路返回false_不回调_计link_gone() {
        EmbeddedChannel ch = new EmbeddedChannel();
        links.register(1, 5, "gate", 1, ch);
        AtomicInteger failed = new AtomicInteger();

        assertThat(links.playerRedirect(2, 11, HOLDER, 4, 5, goldenRedirect(), failed::incrementAndGet))
                .as("未登记的链路").isFalse();
        ch.close();
        assertThat(links.playerRedirect(1, 11, HOLDER, 4, 5, goldenRedirect(), failed::incrementAndGet))
                .as("已断开的链路").isFalse();

        assertThat(failed.get()).as("同步就知道没写出的，不再回调").isZero();
        assertThat(dropped("link_gone")).isEqualTo(2);
        assertThat(framesOut("player_transfer")).isZero();
    }

    @Test
    void 重定向帧_出站缓冲越过高水位返回false_断开链路_不回调() {
        EmbeddedChannel ch = new EmbeddedChannel();
        links.register(1, 5, "gate", 1, ch);
        ch.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 8));
        ch.write(NodeLinkFrame.getDefaultInstance());
        ch.write(NodeLinkFrame.getDefaultInstance());
        assertThat(ch.isWritable()).isFalse();
        AtomicInteger failed = new AtomicInteger();

        assertThat(links.playerRedirect(1, 11, HOLDER, 4, 5, goldenRedirect(), failed::incrementAndGet)).isFalse();

        assertThat(ch.isOpen()).isFalse();
        assertThat(failed.get()).isZero();
        assertThat(dropped("write_buffer_full")).isEqualTo(1);
    }

    /**
     * 异步写失败这条口：帧已交给链路（返回 true），随后写失败——回调不在触发它的线程上直接跑，而是投递给逻辑执行器，恰好一次。
     * 先用闩锁（带上限）确认「已投递」这件事发生了，再去读记录，不靠睡眠。
     */
    @Test
    void 重定向帧交给链路后异步写失败_回调经逻辑线程执行恰好一次_计write_failed() throws Exception {
        List<Runnable> logicTasks = new ArrayList<>();
        CountDownLatch posted = new CountDownLatch(1);
        GateLinks queued = new GateLinks(new SceneMetrics(meters), task -> {
            logicTasks.add(task);
            posted.countDown();
        });
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                promise.setFailure(new ClosedChannelException());
            }
        });
        queued.register(1, 5, "gate", 1, ch);
        AtomicInteger failed = new AtomicInteger();

        assertThat(queued.playerRedirect(1, 11, HOLDER, 4, 5, goldenRedirect(), failed::incrementAndGet))
                .as("帧已交给链路：同步结果是「写出了」").isTrue();

        assertThat(posted.await(5, TimeUnit.SECONDS)).as("失败回调已投递给逻辑执行器").isTrue();
        assertThat(failed.get()).as("不在链路 I/O 线程上直接跑").isZero();
        assertThat(logicTasks).hasSize(1);
        logicTasks.get(0).run();
        assertThat(failed.get()).isEqualTo(1);
        assertThat(dropped("write_failed")).isEqualTo(1);
    }

    /** 逻辑线程已停（执行器拒绝）时异步写失败的善后被丢弃：回调不跑，丢帧照计。 */
    @Test
    void 重定向帧异步写失败时逻辑线程已停_回调不跑_仍计write_failed() {
        GateLinks stopped = new GateLinks(new SceneMetrics(meters), task -> {
            throw new RejectedExecutionException("逻辑线程已停止（测试）");
        });
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                promise.setFailure(new ClosedChannelException());
            }
        });
        stopped.register(1, 5, "gate", 1, ch);
        AtomicInteger failed = new AtomicInteger();

        assertThat(stopped.playerRedirect(1, 11, HOLDER, 4, 5, goldenRedirect(), failed::incrementAndGet)).isTrue();

        assertThat(failed.get()).isZero();
        assertThat(dropped("write_failed")).isEqualTo(1);
    }

    @Test
    void 重定向帧不带redirect是编程错误_直接抛_不写帧() {
        EmbeddedChannel ch = new EmbeddedChannel();
        links.register(1, 5, "gate", 1, ch);

        assertThatThrownBy(() -> links.playerRedirect(1, 11, HOLDER, 4, 5, null, () -> { }))
                .isInstanceOf(NullPointerException.class);

        assertThat(ch.<Object>readOutbound()).isNull();
    }

    private double framesOut(String type) {
        return meters.get("xm.scene.link.frames").tag("direction", "out").tag("type", type).counter().count();
    }

    private double dropped(String reason) {
        return meters.get("xm.scene.link.dropped").tag("reason", reason).counter().count();
    }
}
