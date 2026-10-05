package com.game.battle.edge;

import static com.game.battle.edge.EdgeTestKit.BATTLE;
import static com.game.battle.edge.EdgeTestKit.INSTANCE;
import static com.game.battle.edge.EdgeTestKit.NODE_ID;
import static com.game.battle.edge.EdgeTestKit.NOW_MS;
import static com.game.battle.edge.EdgeTestKit.OBSERVER;
import static com.game.battle.edge.EdgeTestKit.PLAYER;
import static com.game.battle.edge.EdgeTestKit.buf;
import static com.game.battle.edge.EdgeTestKit.drain;
import static com.game.battle.edge.EdgeTestKit.frame;
import static com.game.battle.edge.EdgeTestKit.labels;
import static com.game.battle.edge.EdgeTestKit.observer;
import static com.game.battle.edge.EdgeTestKit.participant;
import static com.game.battle.edge.EdgeTestKit.request;
import static com.game.battle.edge.EdgeTestKit.ticket;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.BattleIdentity;
import com.game.battle.BattleProperties;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.protocol.BattleFrames;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.room.DirectLink;
import com.game.common.token.BattleTickets;
import com.game.net.limit.MessageLimits;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.MessageContent;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 直连面的线上行为（battle-node-spec §3、§7.4、§13.3）：用 {@code EmbeddedChannel} 装与生产相同的 pipeline，按 robot 写法拼上行帧，
 * 读回下行帧逐字节比对。房间用 {@link FakeRooms}。基线没有直连面的单测（{@code cpp/nodes/battle/tests/} 只有房间表、准入闸、推送判定、票据），
 * 这里按 {@code edge.cpp} 的行为与 §13.3 的清单写。
 *
 * <p>说明：{@code EmbeddedChannel} 不是全双工连接，优雅关闭的「输出排空后 FIN」退化为「输出排空后 close」；真 FIN 见 {@link BattleEdgeLoopbackTest}。
 */
class BattleEdgeHandlerTest {

    private static final int GET_STATE = 140;
    private static final int SUBMIT = 149;
    private static final int SET_AUTO = 162;
    private static final int STOP_WATCH = 165;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(meters);
    private final BattleMessageIds ids = BattleMessageIds.loadFromClasspath();
    private final BattleTickets tickets = BattleTickets.ofUtf8(EdgeTestKit.SECRET);
    private final FakeRooms rooms = new FakeRooms(ids);
    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private final DefaultEventLoopGroup unusedLogic = new DefaultEventLoopGroup(1);
    private long nowMs = NOW_MS;
    private BattleEdgeServer edge = edge(EdgeTestKit.props(4096, Duration.ofSeconds(10), 50));

    @AfterEach
    void tearDown() {
        unusedLogic.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- 握手成功

    @Test
    void 握手成功_应答是第一帧且类型名是全名加零结尾_参战者握手后不推任何帧() {
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        EmbeddedChannel ch = open();
        ch.writeInbound(Unpooled.wrappedBuffer(frame(participant(tickets, BATTLE, PLAYER), ' ')));

        ByteBuf out = ch.readOutbound();
        assertThat(EdgeTestKit.typeName(out)).isEqualTo("BattleTokenVerifyResponse\0");
        BattleTokenVerifyResponse response = (BattleTokenVerifyResponse) EdgeTestKit.decodeFrame(out);
        out.release();
        assertThat(response.toByteString()).isEqualTo(BattleFrames.verifyAccepted(BATTLE).toByteString());
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getError()).isEmpty();
        assertThat((Object) ch.readOutbound()).as("参战者握手后服务端什么也不推，由客户端自己发 140").isNull();
        assertThat(ch.isOpen()).isTrue();
        assertThat(rooms.events).containsExactly("attach:" + PLAYER, "verified:" + PLAYER + ":1");
        assertThat(session(ch).state()).isEqualTo(DirectSession.State.VERIFIED);
        assertThat(session(ch).gateSessionId()).isEqualTo(4_000_000_001L);
        assertThat(rooms.link(PLAYER)).isSameAs(session(ch));
        assertThat(rooms.link(PLAYER).isLive()).isTrue();
        assertThat(count("xm.battle.handshakes", "result", "ok")).isEqualTo(1);
        assertThat(edge.connectionCount()).isEqualTo(1);
    }

    @Test
    void 观众握手_先应答再推161首帧() {
        rooms.add(BATTLE, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        EmbeddedChannel ch = open();
        EdgeTestKit.WireRecorder wire = record(ch);

        ch.writeInbound(buf(observer(tickets, BATTLE, OBSERVER)));

        assertThat(wire.events).containsExactly("verify-ok:" + BATTLE, "push:" + ids.id(Notify.SPECTATE_STATE));
        assertThat(rooms.events).containsExactly("attach:" + OBSERVER, "verified:" + OBSERVER + ":2");
    }

    @Test
    void 挂接期间房间写不出帧_握手应答一定是第一帧() {
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        rooms.duringAttach = link -> link.send(BattleFrames.push(ids.id(Notify.TURN_RESULT), TurnResultS2C.getDefaultInstance()));
        EmbeddedChannel ch = open();
        EdgeTestKit.WireRecorder wire = record(ch);

        ch.writeInbound(buf(participant(tickets, BATTLE, PLAYER)));

        assertThat(wire.events).containsExactly("verify-ok:" + BATTLE);
    }

    @Test
    void 握手成功时非法包计数清零() {
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        EmbeddedChannel ch = open();
        DirectSession s = session(ch);
        s.registerIllegal();
        s.registerIllegal();
        assertThat(s.illegalPackets()).isEqualTo(2);

        ch.writeInbound(buf(participant(tickets, BATTLE, PLAYER)));

        assertThat(s.state()).isEqualTo(DirectSession.State.VERIFIED);
        assertThat(s.illegalPackets()).isZero();
    }

    @Test
    void 已验证连接再握手_不看新票回旧battle_id且不重新绑定() {
        EmbeddedChannel ch = verifiedParticipant();
        BattleTokenVerifyRequest garbage = BattleTokenVerifyRequest.newBuilder()
                .setPayload(ByteString.copyFromUtf8("garbage")).setSignature(ByteString.copyFromUtf8("nope")).build();

        ch.writeInbound(buf(garbage));

        assertThat(labels(drain(ch))).containsExactly("verify-ok:" + BATTLE);
        assertThat(ch.isOpen()).isTrue();
        assertThat(rooms.events).containsExactly("attach:" + PLAYER, "verified:" + PLAYER + ":1");
        assertThat(count("xm.battle.handshakes", "result", "repeat")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 握手失败（§3.4，拒绝串逐字照抄）

    @Test
    void 签名不符_回invalid_ticket_signature后关闭() {
        BattleTokenVerifyRequest good = participant(tickets, BATTLE, PLAYER);
        byte[] sig = good.getSignature().toByteArray();
        sig[0] = (byte) (sig[0] == 'a' ? 'b' : 'a');
        assertRejected(good.toBuilder().setSignature(ByteString.copyFrom(sig)).build(),
                "invalid ticket signature", "ticket_hmac_mismatch");
        // 用别的密钥（例如 gate 密钥）签的票同样过不了
        assertRejected(participant(BattleTickets.ofUtf8("some-other-secret-0123456789abcdef"), BATTLE, PLAYER),
                "invalid ticket signature", "ticket_hmac_mismatch");
        // 大写 hex：大小写敏感
        assertRejected(good.toBuilder().setSignature(ByteString.copyFromUtf8(good.getSignature().toStringUtf8().toUpperCase())).build(),
                "invalid ticket signature", "ticket_hmac_mismatch");
    }

    @Test
    void payload解析失败_回malformed_ticket_payload() {
        ByteString broken = ByteString.copyFrom(new byte[] {0x08});
        assertRejected(EdgeTestKit.signed(tickets, broken), "malformed ticket payload", "ticket_payload_parse_failed");
    }

    @Test
    void 字段判定失败_五种拒绝串逐字比对() {
        long exp = NOW_MS + 60_000;
        assertRejected(ticket(tickets, 0, PLAYER, NODE_ID, INSTANCE, exp, 1), "ticket rejected: empty_identity", "empty_identity");
        assertRejected(ticket(tickets, BATTLE, 0, NODE_ID, INSTANCE, exp, 1), "ticket rejected: empty_identity", "empty_identity");
        assertRejected(ticket(tickets, BATTLE, PLAYER, NODE_ID + 1, INSTANCE, exp, 1), "ticket rejected: node_mismatch", "node_mismatch");
        assertRejected(ticket(tickets, BATTLE, PLAYER, NODE_ID, "other-instance", exp, 1),
                "ticket rejected: instance_mismatch", "instance_mismatch");
        assertRejected(ticket(tickets, BATTLE, PLAYER, NODE_ID, "", exp, 1), "ticket rejected: instance_mismatch", "instance_mismatch");
        assertRejected(ticket(tickets, BATTLE, PLAYER, NODE_ID, INSTANCE, NOW_MS, 1), "ticket rejected: expired", "expired");
        assertRejected(ticket(tickets, BATTLE, PLAYER, NODE_ID, INSTANCE, NOW_MS - 1, 1), "ticket rejected: expired", "expired");
        assertRejected(ticket(tickets, BATTLE, PLAYER, NODE_ID, INSTANCE, exp, 0), "ticket rejected: role_invalid", "role_invalid");
        assertRejected(ticket(tickets, BATTLE, PLAYER, NODE_ID, INSTANCE, exp, 3), "ticket rejected: role_invalid", "role_invalid");
        // 多个字段都坏时最便宜的那个赢：节点号不符 + 已过期 → node_mismatch
        assertRejected(ticket(tickets, BATTLE, PLAYER, NODE_ID + 1, INSTANCE, NOW_MS - 5, 9), "ticket rejected: node_mismatch",
                "node_mismatch");
        assertThat(rooms.events).as("字段判定失败时不碰房间").isEmpty();
    }

    @Test
    void 票据期限等于现在算过期_早一毫秒放行() {
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        nowMs = NOW_MS + 300_000 - 1;
        EmbeddedChannel ok = open();
        ok.writeInbound(buf(participant(tickets, BATTLE, PLAYER)));
        assertThat(labels(drain(ok))).containsExactly("verify-ok:" + BATTLE);

        nowMs = NOW_MS + 300_000;
        assertRejected(participant(tickets, BATTLE, PLAYER), "ticket rejected: expired", "expired");
    }

    @Test
    void 房间不在或角色与名单不符_回not_in_roster() {
        assertRejected(participant(tickets, BATTLE, PLAYER), "battle not found or player not in this battle", "ticket_not_in_roster");
        // 参战票只认参战名单、观众票只认观众名单
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        rooms.add(BATTLE, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        assertRejected(observer(tickets, BATTLE, PLAYER), "battle not found or player not in this battle", "ticket_not_in_roster");
        assertRejected(participant(tickets, BATTLE, OBSERVER), "battle not found or player not in this battle", "ticket_not_in_roster");
        assertThat(rooms.slots).isEmpty();
    }

    @Test
    void 握手被拒_应答之后FIN_对端不读时零点一秒强关() {
        EmbeddedChannel ch = open();
        EdgeTestKit.WireRecorder wire = record(ch);
        wire.stallFinMarker = true;

        ch.writeInbound(buf(participant(tickets, BATTLE, PLAYER)));

        assertThat(wire.events).containsExactly("verify-fail:battle not found or player not in this battle", "fin-marker");
        assertThat(ch.isOpen()).isTrue();
        ch.advanceTimeBy(99, TimeUnit.MILLISECONDS);
        ch.runPendingTasks();
        assertThat(ch.isOpen()).isTrue();
        ch.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        ch.runPendingTasks();
        assertThat(ch.isOpen()).isFalse();
        assertThat(wire.events).endsWith("close");
        assertThat(count("xm.battle.disconnects", "reason", "handshake_rejected")).isEqualTo(1);
    }

    @Test
    void 关闭中到达的帧一律丢弃_坏帧也不再计非法帧() {
        EmbeddedChannel ch = open();
        EdgeTestKit.WireRecorder wire = record(ch);
        wire.stallFinMarker = true;
        // 同一批里：被拒的握手 + 一条请求；随后又来一个坏帧
        ch.writeInbound(buf(participant(tickets, BATTLE, PLAYER), request(1, GET_STATE, GetBattleStateRequest.getDefaultInstance())));
        byte[] bad = frame(request(2, GET_STATE, GetBattleStateRequest.getDefaultInstance()), ' ');
        bad[bad.length - 1] ^= 0x5A;
        ch.writeInbound(Unpooled.wrappedBuffer(bad));

        assertThat(wire.events).containsExactly("verify-fail:battle not found or player not in this battle", "fin-marker");
        assertThat(ch.isOpen()).as("关闭中不被坏帧当场强关，等兜底计时器").isTrue();
        assertThat(meters.get("xm.battle.invalid.frames").counters()).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(count("xm.battle.disconnects", "reason", "request_before_verify")).isZero();
    }

    @Test
    void 同一次读里被拒的握手后跟坏帧_拒绝应答与FIN照常_坏帧不解析不计非法帧不当场强关() {
        EmbeddedChannel ch = open();
        EdgeTestKit.WireRecorder wire = record(ch);
        wire.stallFinMarker = true;
        byte[] bad = frame(getState(2), ' ');
        bad[bad.length - 1] ^= 0x5A;

        ch.writeInbound(Unpooled.wrappedBuffer(frame(participant(tickets, BATTLE, PLAYER), ' '), bad));

        assertThat(wire.events).as("基线握手被拒同步 shutdown，codec 不再解析同一批的后续字节（codec.cpp:180-185）")
                .containsExactly("verify-fail:battle not found or player not in this battle", "fin-marker");
        assertThat(ch.isOpen()).isTrue();
        assertThat(meters.get("xm.battle.invalid.frames").counters()).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(edge.rejectLog().count(EdgeRejectReason.INVALID_FRAME)).isZero();
        assertThat(count("xm.battle.disconnects", "reason", "handshake_rejected")).isEqualTo(1);
        assertThat(count("xm.battle.disconnects", "reason", "invalid_frame")).isZero();
    }

    // ---------------------------------------------------------------- 逐帧分发：同一次读里先到的合法帧先处理（基线 codec.cpp:164-191）

    @Test
    void 同一次读里握手后跟合法但不收的类型_先挂接应答再推161_然后才断开() {
        rooms.add(BATTLE, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        EmbeddedChannel ch = open();
        EdgeTestKit.WireRecorder wire = record(ch);

        // 客户端把大厅那条连接的 ClientTokenVerifyRequest 发错了连接，和握手挤在同一个 TCP 段里
        ch.writeInbound(buf(observer(tickets, BATTLE, OBSERVER),
                ClientTokenVerifyRequest.newBuilder().setPayload(ByteString.copyFromUtf8("x")).build()));

        assertThat(wire.events).containsExactly("verify-ok:" + BATTLE, "push:" + ids.id(Notify.SPECTATE_STATE), "close");
        assertThat(rooms.events).containsExactly("attach:" + OBSERVER, "verified:" + OBSERVER + ":2", "detach:" + OBSERVER + ":true");
        assertThat(ch.isOpen()).isFalse();
        assertThat(count("xm.battle.handshakes", "result", "ok")).isEqualTo(1);
        assertThat(count("xm.battle.invalid.frames", "reason", "unknown_type")).isEqualTo(1);
        assertThat(count("xm.battle.disconnects", "reason", "invalid_frame")).isEqualTo(1);
    }

    @Test
    void 同一次读里合法的149后跟坏帧_先提交并应答_然后才断开() {
        EmbeddedChannel ch = verifiedParticipant();
        EdgeTestKit.WireRecorder wire = record(ch);
        rooms.onSubmit = (pid, req) -> {
            rooms.link(pid).send(BattleFrames.push(ids.id(Notify.TURN_RESULT), TurnResultS2C.newBuilder().setBattleId(BATTLE).build()));
            return SubmitBattleActionResponse.getDefaultInstance();
        };
        byte[] submit = frame(request(9, SUBMIT, SubmitBattleActionRequest.newBuilder().setBattleId(BATTLE).build()), ' ');
        byte[] badChecksum = frame(getState(10), ' ');
        badChecksum[badChecksum.length - 1] ^= 0x01;

        ch.writeInbound(Unpooled.wrappedBuffer(submit, badChecksum));

        assertThat(wire.events).containsExactly("push:139", "reply:149", "close");
        assertThat(rooms.events).contains("submit:" + PLAYER + ":" + BATTLE).noneMatch(e -> e.startsWith("getState"));
        assertThat(ch.isOpen()).isFalse();
        assertThat(count("xm.battle.invalid.frames", "reason", "checksum")).isEqualTo(1);
        assertThat(count2("xm.battle.client.requests", "SubmitBattleAction", "ok")).isEqualTo(1);
    }

    @Test
    void 同一次读里合法请求后跟长度非法的帧_先应答再断开() {
        EmbeddedChannel ch = verifiedParticipant();
        EdgeTestKit.WireRecorder wire = record(ch);
        // 全 0：len = 0 < 10；补到 16 字节（基线要攒够 14 字节才看长度）
        byte[] badLength = new byte[16];

        ch.writeInbound(Unpooled.wrappedBuffer(frame(getState(3), ' '), badLength));

        assertThat(wire.events).containsExactly("reply:140", "close");
        assertThat(count("xm.battle.invalid.frames", "reason", "invalid_length")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 握手之前的闸

    @Test
    void 握手前发ClientRequest_立即关不回包不计非法包() {
        EmbeddedChannel ch = open();
        ch.writeInbound(buf(request(1, GET_STATE, GetBattleStateRequest.newBuilder().setBattleId(BATTLE).build())));

        assertThat(drain(ch)).isEmpty();
        assertThat(ch.isOpen()).isFalse();
        assertThat(count("xm.battle.disconnects", "reason", "request_before_verify")).isEqualTo(1);
        assertThat(meters.get("xm.battle.client.requests").counters()).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(rooms.events).isEmpty();
        assertThat(edge.rejectLog().count(EdgeRejectReason.REQUEST_BEFORE_VERIFY)).isEqualTo(1);
    }

    @Test
    void 首帧是大厅的ClientTokenVerifyRequest_解码器立即关不回包() {
        EmbeddedChannel ch = open();
        ch.writeInbound(buf(ClientTokenVerifyRequest.newBuilder().setPayload(ByteString.copyFromUtf8("x")).build()));

        assertThat(drain(ch)).isEmpty();
        assertThat(ch.isOpen()).isFalse();
        assertThat(count("xm.battle.invalid.frames", "reason", "unknown_type")).isEqualTo(1);
        assertThat(count("xm.battle.disconnects", "reason", "invalid_frame")).isEqualTo(1);
        assertThat(edge.rejectLog().count(EdgeRejectReason.UNKNOWN_FRAME)).isEqualTo(1);
        assertThat(edge.connectionCount()).isZero();
    }

    @Test
    void 校验和不对_立即关不回包() {
        EmbeddedChannel ch = open();
        byte[] bad = frame(participant(tickets, BATTLE, PLAYER), ' ');
        bad[bad.length - 1] ^= 0x01;
        ch.writeInbound(Unpooled.wrappedBuffer(bad));

        assertThat(drain(ch)).isEmpty();
        assertThat(ch.isOpen()).isFalse();
        assertThat(count("xm.battle.invalid.frames", "reason", "checksum")).isEqualTo(1);
        assertThat(edge.rejectLog().count(EdgeRejectReason.INVALID_FRAME)).isEqualTo(1);
    }

    @Test
    void 握手超时_到点关闭_握手成功后定时器取消() {
        edge = edge(EdgeTestKit.props(4096, Duration.ofSeconds(1), 50));
        EmbeddedChannel idle = open();
        idle.advanceTimeBy(900, TimeUnit.MILLISECONDS);
        idle.runPendingTasks();
        assertThat(idle.isOpen()).isTrue();
        idle.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        idle.runPendingTasks();
        assertThat(idle.isOpen()).isFalse();
        assertThat(drain(idle)).isEmpty();
        assertThat(count("xm.battle.disconnects", "reason", "handshake_timeout")).isEqualTo(1);

        EmbeddedChannel verified = verifiedParticipant();
        verified.advanceTimeBy(2_500, TimeUnit.MILLISECONDS);
        verified.runPendingTasks();
        assertThat(verified.isOpen()).isTrue();
        assertThat(count("xm.battle.disconnects", "reason", "handshake_timeout")).isEqualTo(1);
    }

    @Test
    void 并发上限_第N加1条连接立即关不分配会话_断开后名额归还() {
        edge = edge(EdgeTestKit.props(2, Duration.ofSeconds(10), 50));
        EmbeddedChannel a = open();
        EmbeddedChannel b = open();
        EmbeddedChannel c = open();

        assertThat(a.isOpen()).isTrue();
        assertThat(b.isOpen()).isTrue();
        assertThat(c.isOpen()).isFalse();
        assertThat(drain(c)).isEmpty();
        assertThat(edge.connectionCount()).as("被拒的连接从未分配会话、不占名额").isEqualTo(2);
        assertThat(edge.rejectLog().count(EdgeRejectReason.AT_CAPACITY)).isEqualTo(1);
        assertThat(count("xm.battle.disconnects", "reason", "at_capacity")).isEqualTo(1);

        a.close();
        assertThat(edge.connectionCount()).isEqualTo(1);
        EmbeddedChannel d = open();
        assertThat(d.isOpen()).isTrue();
        assertThat(edge.connectionCount()).isEqualTo(2);
    }

    @Test
    void 上限配0时取硬上限65535() {
        assertThat(EdgeTestKit.props(0, Duration.ofSeconds(10), 50).effectiveMaxConnections()).isEqualTo(65535);
        edge = edge(EdgeTestKit.props(0, Duration.ofSeconds(10), 50));
        assertThat(open().isOpen()).isTrue();
    }

    // ---------------------------------------------------------------- 已验证连接上的闸（§3.5）

    @Test
    void 整条超过1024字节回信封1010并计非法包_恰好1024字节放行() {
        EmbeddedChannel ch = verifiedParticipant();
        GetBattleStateRequest body = GetBattleStateRequest.newBuilder().setBattleId(BATTLE).build();

        ch.writeInbound(buf(EdgeTestKit.paddedRequest(11, GET_STATE, body, 1024)));
        ch.writeInbound(buf(EdgeTestKit.paddedRequest(12, GET_STATE, body, 1025)));

        List<Message> out = drain(ch);
        assertThat(labels(out)).containsExactly("reply:140", "error:140:1010");
        assertThat(((MessageContent) out.get(1)).toByteString())
                .isEqualTo(MessageContent.newBuilder().setId(12).setMessageId(GET_STATE)
                        .setErrorMessage(TipInfoMessage.newBuilder().setId(1010)).build().toByteString());
        assertThat(session(ch).illegalPackets()).isEqualTo(1);
        assertThat(rooms.events).containsOnlyOnce("getState:" + PLAYER + ":" + BATTLE);
        assertThat(count2("xm.battle.client.requests", "GetBattleState", "oversized")).isEqualTo(1);
        assertThat(count2("xm.battle.client.requests", "GetBattleState", "ok")).isEqualTo(1);
    }

    @Test
    void 同号每秒第4条回信封1008_被拒的不占额度_限频器每条连接一份() {
        EmbeddedChannel ch = verifiedParticipant();
        for (int i = 0; i < 4; i++) {
            ch.writeInbound(buf(getState(20 + i)));
        }
        assertThat(labels(drain(ch))).containsExactly("reply:140", "reply:140", "reply:140", "error:140:1008");

        nanos.addAndGet(500_000_000L);
        ch.writeInbound(buf(getState(30)));
        assertThat(labels(drain(ch))).containsExactly("error:140:1008");

        nanos.addAndGet(500_000_000L);
        for (int i = 0; i < 3; i++) {
            ch.writeInbound(buf(getState(40 + i)));
        }
        assertThat(labels(drain(ch))).as("窗口里只有受理过的 3 条，被拒的不占额度").containsExactly("reply:140", "reply:140", "reply:140");

        rooms.add(BATTLE, PLAYER + 1, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        EmbeddedChannel other = open();
        other.writeInbound(buf(participant(tickets, BATTLE, PLAYER + 1)));
        drain(other);
        for (int i = 0; i < 3; i++) {
            other.writeInbound(buf(getState(50 + i)));
        }
        assertThat(labels(drain(other))).containsExactly("reply:140", "reply:140", "reply:140");
        assertThat(count2("xm.battle.client.requests", "GetBattleState", "rate_limited")).isEqualTo(2);
        assertThat(session(ch).illegalPackets()).isEqualTo(2);
    }

    @Test
    void 白名单外的号回信封1005_也先过限频() {
        EmbeddedChannel ch = verifiedParticipant();
        for (int i = 0; i < 4; i++) {
            ch.writeInbound(buf(request(60 + i, 157, GetBattleStateRequest.getDefaultInstance())));
        }
        ch.writeInbound(buf(request(70, ids.id(Notify.TURN_RESULT), GetBattleStateRequest.getDefaultInstance())));

        List<Message> out = drain(ch);
        assertThat(labels(out)).containsExactly("error:157:1005", "error:157:1005", "error:157:1005", "error:157:1008", "error:139:1005");
        assertThat(((MessageContent) out.get(0)).getId()).isEqualTo(60);
        assertThat(rooms.events).containsExactly("attach:" + PLAYER, "verified:" + PLAYER + ":1");
        assertThat(count2("xm.battle.client.requests", "other", "not_allowed")).isEqualTo(4);
        assertThat(count2("xm.battle.client.requests", "other", "rate_limited")).isEqualTo(1);
        assertThat(session(ch).illegalPackets()).isEqualTo(5);
    }

    @Test
    void 请求体解析失败回信封1005_不交给房间() {
        EmbeddedChannel ch = verifiedParticipant();
        ch.writeInbound(buf(EdgeTestKit.rawRequest(80, SUBMIT, new byte[] {0x08})));

        assertThat(labels(drain(ch))).containsExactly("error:149:1005");
        assertThat(rooms.events).noneMatch(e -> e.startsWith("submit"));
        assertThat(count2("xm.battle.client.requests", "SubmitBattleAction", "bad_body")).isEqualTo(1);
        assertThat(session(ch).illegalPackets()).isEqualTo(1);
    }

    @Test
    void 非法包到50断开() {
        EmbeddedChannel ch = verifiedParticipant();
        for (int i = 0; i < 49; i++) {
            ch.writeInbound(buf(request(i + 1, 157, GetBattleStateRequest.getDefaultInstance())));
        }
        assertThat(ch.isOpen()).isTrue();
        ch.writeInbound(buf(request(50, 157, GetBattleStateRequest.getDefaultInstance())));
        assertThat(ch.isOpen()).isFalse();
        assertThat(count("xm.battle.disconnects", "reason", "illegal_packets")).isEqualTo(1);
        assertThat(rooms.events).contains("detach:" + PLAYER + ":true");
    }

    @Test
    void 非法包阈值配0只计不断() {
        edge = edge(EdgeTestKit.props(4096, Duration.ofSeconds(10), 0));
        EmbeddedChannel ch = verifiedParticipant();
        for (int i = 0; i < 60; i++) {
            ch.writeInbound(buf(request(i + 1, 157, GetBattleStateRequest.getDefaultInstance())));
        }
        assertThat(ch.isOpen()).isTrue();
        assertThat(session(ch).illegalPackets()).isEqualTo(60);
    }

    // ---------------------------------------------------------------- 应答形状与身份

    @Test
    void 应答形状_id与号回显_成功体0字节且没有error_message_业务错误在应答体里() {
        EmbeddedChannel ch = verifiedParticipant();
        long bigId = 0xFFFF_FFFF_FFFFL;
        ch.writeInbound(buf(request(bigId, SUBMIT, SubmitBattleActionRequest.newBuilder().setBattleId(BATTLE).build())));
        rooms.onSubmit = (pid, req) -> SubmitBattleActionResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(7006)).build();
        ch.writeInbound(buf(request(5, SUBMIT, SubmitBattleActionRequest.newBuilder().setBattleId(BATTLE).build())));

        List<Message> out = drain(ch);
        assertThat(((MessageContent) out.get(0)).toByteString())
                .isEqualTo(MessageContent.newBuilder().setId(bigId).setMessageId(SUBMIT).build().toByteString());
        MessageContent business = (MessageContent) out.get(1);
        assertThat(business.hasErrorMessage()).as("业务错误不走信封").isFalse();
        assertThat(business.getId()).isEqualTo(5);
        assertThat(business.getSerializedMessage()).isEqualTo(SubmitBattleActionResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(7006)).build().toByteString());
        assertThat(count2("xm.battle.client.requests", "SubmitBattleAction", "ok")).isEqualTo(1);
        assertThat(count2("xm.battle.client.requests", "SubmitBattleAction", "business_error")).isEqualTo(1);
    }

    @Test
    void 身份只来自票据_player_id取会话里绑定的_battle_id按请求体原样交给房间() {
        EmbeddedChannel ch = verifiedParticipant();
        ch.writeInbound(buf(request(1, SUBMIT, SubmitBattleActionRequest.newBuilder().setBattleId(999).build())));
        ch.writeInbound(buf(request(2, SET_AUTO, SetAutoBattleRequest.newBuilder().setBattleId(BATTLE).setEnabled(true).build())));
        rooms.onGetState = (pid, req) -> BattleStateS2C.getDefaultInstance();
        ch.writeInbound(buf(getState(3)));

        assertThat(rooms.events).containsSubsequence("submit:" + PLAYER + ":999", "setAuto:" + PLAYER + ":true",
                "getState:" + PLAYER + ":" + BATTLE);
        List<Message> out = drain(ch);
        assertThat(((MessageContent) out.get(2)).toByteString()).as("非成员拿到的默认状态是 0 字节体")
                .isEqualTo(MessageContent.newBuilder().setId(3).setMessageId(GET_STATE).build().toByteString());
    }

    // ---------------------------------------------------------------- 线上顺序（§5.8 O2 / O3 / O8；R2 / R3）

    @Test
    void 提交使全员就绪_线上依次是139_150_应答_同一次读里后面的请求照常应答_然后FIN() {
        EmbeddedChannel ch = verifiedParticipant();
        EdgeTestKit.WireRecorder wire = record(ch);
        DirectSession s = session(ch);
        rooms.onSubmit = (pid, req) -> {
            DirectLink link = rooms.link(pid);
            link.send(BattleFrames.push(ids.id(Notify.TURN_RESULT), TurnResultS2C.newBuilder().setBattleId(BATTLE).build()));
            link.send(BattleFrames.push(ids.id(Notify.BATTLE_END), BattleEndS2C.newBuilder().setBattleId(BATTLE).build()));
            rooms.closeSlot(pid, Disconnect.BATTLE_CLOSED);
            assertThat(link.isLive()).as("房间发起关闭后当场脱离房间").isFalse();
            assertThat(s.acceptsFrames()).as("但本次读里后面的帧照常分发（基线 queueInLoop 推迟 shutdown）").isTrue();
            return SubmitBattleActionResponse.getDefaultInstance();
        };
        rooms.onGetState = (pid, req) -> BattleStateS2C.getDefaultInstance();

        ch.writeInbound(buf(request(9, SUBMIT, SubmitBattleActionRequest.newBuilder().setBattleId(BATTLE).build()), getState(10)));

        assertThat(wire.events).containsExactly("push:139", "push:150", "reply:149", "reply:140", "fin-marker", "close");
        assertThat(rooms.events).containsSubsequence("submit:" + PLAYER + ":" + BATTLE, "getState:" + PLAYER + ":" + BATTLE,
                "detach:" + PLAYER + ":false");
        assertThat(ch.isOpen()).isFalse();
        assertThat(count("xm.battle.disconnects", "reason", "battle_closed")).isEqualTo(1);
    }

    @Test
    void 观众同一次写里165加140_两条都有应答_140是空状态_然后FIN() {
        rooms.add(BATTLE, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        EmbeddedChannel ch = open();
        ch.writeInbound(buf(observer(tickets, BATTLE, OBSERVER)));
        assertThat(labels(drain(ch))).containsExactly("verify-ok:" + BATTLE, "push:" + ids.id(Notify.SPECTATE_STATE));
        EdgeTestKit.WireRecorder wire = record(ch);
        rooms.onStopWatch = (pid, req) -> {
            rooms.closeSlot(pid, Disconnect.BATTLE_CLOSED);
            return StopWatchBattleResponse.getDefaultInstance();
        };
        // 已退出观战：房间按「不是观众」回空状态（基线 HandleGetBattleState）
        rooms.onGetState = (pid, req) -> rooms.link(pid) == null ? BattleStateS2C.getDefaultInstance()
                : BattleStateS2C.newBuilder().setBattleId(BATTLE).setRoundIndex(1).build();

        ch.writeInbound(buf(request(6, STOP_WATCH, StopWatchBattleRequest.newBuilder().setBattleId(BATTLE).build()), getState(7)));

        assertThat(wire.events).containsExactly("reply:165", "reply:140", "fin-marker", "close");
        assertThat(rooms.events).containsSubsequence("stopWatch:" + OBSERVER, "getState:" + OBSERVER + ":" + BATTLE);
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void 房间在读里发起的优雅关闭_这次读处理完即生效_下一次读到的帧作废() {
        EmbeddedChannel ch = verifiedParticipant();
        DirectSession s = session(ch);
        EdgeTestKit.WireRecorder wire = record(ch);
        rooms.onSubmit = (pid, req) -> {
            rooms.closeSlot(pid, Disconnect.BATTLE_CLOSED);
            return SubmitBattleActionResponse.getDefaultInstance();
        };
        // Netty 一轮里可能对同一连接连读几次，两次读之间不跑任务队列（DirectClose 那一步还没跑）：用一个 handler 把一条入站消息拆成两次 channelRead
        List<DirectSession.State> betweenReads = new ArrayList<>();
        ch.pipeline().addFirst("twoReads", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ByteBuf[] reads = (ByteBuf[]) msg;
                ctx.fireChannelRead(reads[0]);
                betweenReads.add(s.state());
                ctx.fireChannelRead(reads[1]);
            }
        });

        ch.writeInbound((Object) new ByteBuf[] {
                buf(request(9, SUBMIT, SubmitBattleActionRequest.newBuilder().setBattleId(BATTLE).build())), buf(getState(10))});

        assertThat(betweenReads).as("第一次读处理完，房间发起的关闭即生效").containsExactly(DirectSession.State.CLOSING);
        assertThat(wire.events).containsExactly("reply:149", "fin-marker", "close");
        assertThat(rooms.events).noneMatch(e -> e.startsWith("getState"));
    }

    @Test
    void 房间在读之外发起的优雅关闭_当场脱离房间_排在当前任务之后才进关闭中() {
        EmbeddedChannel ch = verifiedParticipant();
        DirectSession s = session(ch);
        EdgeTestKit.WireRecorder wire = record(ch);
        wire.stallFinMarker = true;

        // 例如回合计时器到点结算到终局：房间先摘槽再优雅关闭
        rooms.closeSlot(PLAYER, Disconnect.BATTLE_CLOSED);

        assertThat(s.isLive()).isFalse();
        s.send(BattleFrames.push(ids.id(Notify.TURN_RESULT), TurnResultS2C.getDefaultInstance()));
        assertThat(wire.events).as("脱离房间后房间写不出帧").isEmpty();
        assertThat(s.state()).isEqualTo(DirectSession.State.VERIFIED);
        assertThat(s.isClosing()).as("停机排空要等它").isTrue();
        assertThat(count("xm.battle.disconnects", "reason", "battle_closed")).isEqualTo(1);

        ch.runPendingTasks();
        assertThat(s.state()).isEqualTo(DirectSession.State.CLOSING);
        assertThat(s.isClosing()).isTrue();
        assertThat(wire.events).containsExactly("fin-marker");
        ch.writeInbound(buf(getState(11)));
        assertThat(rooms.events).noneMatch(e -> e.startsWith("getState"));
    }

    @Test
    void 开自动翻转_139先于162应答_连接保持() {
        EmbeddedChannel ch = verifiedParticipant();
        EdgeTestKit.WireRecorder wire = record(ch);
        rooms.onSetAuto = (pid, req) -> {
            rooms.link(pid).send(BattleFrames.push(ids.id(Notify.TURN_RESULT), TurnResultS2C.newBuilder().setBattleId(BATTLE).build()));
            return SetAutoBattleResponse.getDefaultInstance();
        };

        ch.writeInbound(buf(request(4, SET_AUTO, SetAutoBattleRequest.newBuilder().setBattleId(BATTLE).setEnabled(true).build())));

        assertThat(wire.events).containsExactly("push:139", "reply:162");
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 退出观战_应答之后FIN_不推166() {
        rooms.add(BATTLE, OBSERVER, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        EmbeddedChannel ch = open();
        ch.writeInbound(buf(observer(tickets, BATTLE, OBSERVER)));
        assertThat(labels(drain(ch))).containsExactly("verify-ok:" + BATTLE, "push:" + ids.id(Notify.SPECTATE_STATE));
        EdgeTestKit.WireRecorder wire = record(ch);
        rooms.onStopWatch = (pid, req) -> {
            rooms.closeSlot(pid, Disconnect.BATTLE_CLOSED);
            return StopWatchBattleResponse.getDefaultInstance();
        };

        ch.writeInbound(buf(request(6, STOP_WATCH, StopWatchBattleRequest.newBuilder().setBattleId(BATTLE).build())));

        assertThat(wire.events).containsExactly("reply:165", "fin-marker", "close");
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void 终局优雅关闭_对端不读时一秒强关() {
        EmbeddedChannel ch = verifiedParticipant();
        EdgeTestKit.WireRecorder wire = record(ch);
        wire.stallFinMarker = true;
        rooms.onSubmit = (pid, req) -> {
            rooms.link(pid).send(BattleFrames.push(ids.id(Notify.BATTLE_END), BattleEndS2C.newBuilder().setBattleId(BATTLE).build()));
            rooms.closeSlot(pid, Disconnect.BATTLE_CLOSED);
            return SubmitBattleActionResponse.getDefaultInstance();
        };

        ch.writeInbound(buf(request(9, SUBMIT, SubmitBattleActionRequest.newBuilder().setBattleId(BATTLE).build())));
        assertThat(wire.events).containsExactly("push:150", "reply:149", "fin-marker");
        ch.advanceTimeBy(DirectLink.GRACEFUL_FORCE_CLOSE_MS - 1, TimeUnit.MILLISECONDS);
        ch.runPendingTasks();
        assertThat(ch.isOpen()).isTrue();
        ch.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        ch.runPendingTasks();
        assertThat(ch.isOpen()).isFalse();
    }

    // ---------------------------------------------------------------- 会话生命周期（R7、断开、写缓冲）

    @Test
    void 重连顶替_旧连接立即关且不发帧_旧连接迟到的断开不摘新连接() {
        EmbeddedChannel old = verifiedParticipant();
        DirectSession oldSession = session(old);

        EmbeddedChannel fresh = open();
        fresh.writeInbound(buf(participant(tickets, BATTLE, PLAYER)));
        old.runPendingTasks();

        assertThat(labels(drain(fresh))).containsExactly("verify-ok:" + BATTLE);
        assertThat(old.isOpen()).isFalse();
        assertThat(drain(old)).as("旧连接不收任何帧").isEmpty();
        assertThat(rooms.link(PLAYER)).isSameAs(session(fresh));
        assertThat(rooms.events).contains("detach:" + PLAYER + ":false");
        assertThat(oldSession.isLive()).isFalse();
        assertThat(oldSession.channelOrNull()).as("关闭后清掉 Channel 引用").isNull();
        assertThat(count("xm.battle.disconnects", "reason", "replaced")).isEqualTo(1);
        assertThat(edge.connectionCount()).isEqualTo(1);
    }

    @Test
    void 客户端断开_房间按同一个对象摘槽_之后link的方法都安全地什么也不做() {
        EmbeddedChannel ch = verifiedParticipant();
        DirectSession s = session(ch);

        ch.close();

        assertThat(rooms.detached).containsExactly(s);
        assertThat(rooms.events).contains("detach:" + PLAYER + ":true");
        assertThat(s.state()).isEqualTo(DirectSession.State.CLOSED);
        assertThat(s.channelOrNull()).isNull();
        assertThat(s.isLive()).isFalse();
        s.send(BattleFrames.push(139, TurnResultS2C.getDefaultInstance()));
        s.closeGracefully(Disconnect.BATTLE_CLOSED);
        s.closeNow(Disconnect.REPLACED);
        assertThat(edge.connectionCount()).isZero();
        assertThat(meters.get("xm.battle.disconnects").counters()).as("客户端主动断开不计").allSatisfy(c -> assertThat(c.count()).isZero());
    }

    @Test
    void 未验证连接断开不摘房间() {
        EmbeddedChannel ch = open();
        ch.close();
        assertThat(rooms.events).isEmpty();
        assertThat(edge.connectionCount()).isZero();
    }

    @Test
    void 写缓冲越过高水位_强关() {
        EmbeddedChannel ch = verifiedParticipant();
        ch.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        ch.runPendingTasks();

        assertThat(ch.isOpen()).isFalse();
        assertThat(count("xm.battle.disconnects", "reason", "write_buffer_full")).isEqualTo(1);
    }

    @Test
    void 断开原因每条连接至多计一次() {
        EmbeddedChannel ch = verifiedParticipant();
        DirectSession s = session(ch);
        EdgeTestKit.WireRecorder wire = record(ch);
        wire.stallFinMarker = true;
        s.closeGracefully(Disconnect.BATTLE_CLOSED);
        ch.runPendingTasks();
        s.closeGracefully(Disconnect.SHUTDOWN);
        s.closeNow(Disconnect.SHUTDOWN);

        assertThat(ch.isOpen()).isFalse();
        assertThat(s.disconnectReason()).isEqualTo(Disconnect.BATTLE_CLOSED);
        assertThat(count("xm.battle.disconnects", "reason", "battle_closed")).isEqualTo(1);
        assertThat(count("xm.battle.disconnects", "reason", "shutdown")).isZero();
    }

    // ---------------------------------------------------------------- 工具

    private BattleEdgeServer edge(BattleProperties properties) {
        return new BattleEdgeServer(new EdgeDependencies(properties, unusedLogic, rooms, tickets,
                new BattleIdentity(NODE_ID, INSTANCE, "127.0.0.1", 12000), () -> nowMs, MessageLimits.of(Map.of()), ids, metrics),
                nanos::get);
    }

    private EmbeddedChannel open() {
        BattleEdgeServer e = edge;
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel c) {
                e.initChannel(c);
            }
        });
        ch.freezeTime();
        return ch;
    }

    private EmbeddedChannel verifiedParticipant() {
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        EmbeddedChannel ch = open();
        ch.writeInbound(buf(participant(tickets, BATTLE, PLAYER)));
        assertThat(labels(drain(ch))).containsExactly("verify-ok:" + BATTLE);
        return ch;
    }

    private void assertRejected(BattleTokenVerifyRequest request, String error, String result) {
        double before = count("xm.battle.handshakes", "result", result);
        double closedBefore = count("xm.battle.disconnects", "reason", "handshake_rejected");
        EmbeddedChannel ch = open();
        ch.writeInbound(buf(request));

        List<Message> out = drain(ch);
        assertThat(out).hasSize(1);
        BattleTokenVerifyResponse response = (BattleTokenVerifyResponse) out.get(0);
        assertThat(response.toByteString()).as("success = false、error 逐字、battle_id 不在线上")
                .isEqualTo(BattleTokenVerifyResponse.newBuilder().setError(error).build().toByteString());
        assertThat(ch.isOpen()).as("应答之后关闭").isFalse();
        assertThat(count("xm.battle.handshakes", "result", result)).isEqualTo(before + 1);
        assertThat(count("xm.battle.disconnects", "reason", "handshake_rejected")).isEqualTo(closedBefore + 1);
    }

    private static ClientRequest getState(long id) {
        return request(id, GET_STATE, GetBattleStateRequest.newBuilder().setBattleId(BATTLE).build());
    }

    private static EdgeTestKit.WireRecorder record(EmbeddedChannel ch) {
        EdgeTestKit.WireRecorder recorder = new EdgeTestKit.WireRecorder();
        ch.pipeline().addFirst("wireRecorder", recorder);
        return recorder;
    }

    private static BattleEdgeHandler handler(EmbeddedChannel ch) {
        return (BattleEdgeHandler) ch.pipeline().get("battleEdge");
    }

    private static DirectSession session(EmbeddedChannel ch) {
        return handler(ch).session();
    }

    private double count(String name, String tag, String value) {
        return meters.get(name).tag(tag, value).counter().count();
    }

    private double count2(String name, String method, String result) {
        return meters.get(name).tag("method", method).tag("result", result).counter().count();
    }
}
