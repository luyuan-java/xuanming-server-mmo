package com.game.gate.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.LinkHello;
import com.game.api.proto.LinkHelloAck;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.ToClient;
import com.game.gate.metrics.GateMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 链路状态机：用 EmbeddedChannel 代替真实连接（LinkConnector 是网络隔离接缝）。 */
class SceneLinkTest {

    private static final int NODE = 7;
    private static final LinkHello HELLO = LinkHello.newBuilder().setGateNodeId(3).setGateInstanceId("gate-uuid").setZoneId(1).build();

    private final List<SceneLink> connects = new ArrayList<>();
    private final RecordingListener listener = new RecordingListener();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GateMetrics metrics = new GateMetrics(meters);

    private SceneLinkManager manager(int maxQueued, LinkConnector connector) {
        SceneLinkManager manager = new SceneLinkManager(() -> HELLO, connector, new LinkSettings(Duration.ZERO, maxQueued),
                () -> true, metrics);
        manager.bindListener(listener);
        return manager;
    }

    private SceneLinkManager manager() {
        return manager(100, connects::add);
    }

    @Test
    void 首帧触发建链_握手前排队_ack后按原顺序补发_之后直接发() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        assertThat(manager.send(NODE, forward(1))).isEqualTo(gen);
        assertThat(connects).hasSize(1);

        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));
        NodeLinkFrame first = ch.readOutbound();
        assertThat(first.getHello()).isEqualTo(HELLO);
        assertThat((Object) ch.readOutbound()).as("握手前不发业务帧").isNull();

        ch.writeInbound(ack(NODE, 1, true));
        assertThat(((NodeLinkFrame) ch.readOutbound()).hasPlayerEnter()).isTrue();
        assertThat(((NodeLinkFrame) ch.readOutbound()).hasClientForward()).isTrue();

        assertThat(manager.send(NODE, leave(1))).isEqualTo(gen);
        assertThat(((NodeLinkFrame) ch.readOutbound()).hasPlayerLeave()).isTrue();
        assertThat(connects).as("复用同一条链路").hasSize(1);
    }

    @Test
    void 握手被拒_排队的进场帧回报失败_链路摘除后下次发送建新代次() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        manager.send(NODE, forward(1));
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));

        ch.writeInbound(ack(NODE, 1, false));

        assertThat(ch.isOpen()).isFalse();
        assertThat(listener.undeliverable).containsExactly(new Event(NODE, gen, 1));
        assertThat(listener.linkDown).as("从未就绪的链路不报断链").isEmpty();

        long next = manager.send(NODE, enter(2));
        assertThat(next).isGreaterThan(gen);
        assertThat(connects).hasSize(2);
    }

    @Test
    void 对端zone或节点号不符都判建链失败() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));
        ch.writeInbound(ack(NODE, 2, true));
        assertThat(ch.isOpen()).isFalse();
        assertThat(listener.undeliverable).containsExactly(new Event(NODE, gen, 1));

        long gen2 = manager.send(NODE, enter(2));
        EmbeddedChannel ch2 = new EmbeddedChannel(new SceneLinkHandler(connects.get(1)));
        ch2.writeInbound(ack(NODE + 1, 1, true));
        assertThat(ch2.isOpen()).isFalse();
        assertThat(listener.undeliverable).contains(new Event(NODE, gen2, 2));
    }

    @Test
    void 连接失败时排队的进场帧回报失败_其余帧丢弃() {
        SceneLinkManager manager = manager(100, link -> link.fail("连不上"));
        long gen = manager.send(NODE, enter(5));
        assertThat(gen).isPositive();
        assertThat(listener.undeliverable).containsExactly(new Event(NODE, gen, 5));

        manager.send(NODE, forward(5));
        assertThat(listener.undeliverable).hasSize(1);
    }

    @Test
    void 就绪后断开回报断链并摘除() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));
        ch.writeInbound(ack(NODE, 1, true));

        ch.close();

        assertThat(listener.linkDown).containsExactly(new Event(NODE, gen, 0));
        assertThat(listener.undeliverable).isEmpty();
        assertThat(manager.send(NODE, enter(2))).isGreaterThan(gen);
    }

    @Test
    void 就绪后的下行带上代次转给监听器_就绪前的下行忽略() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));

        ch.writeInbound(NodeLinkFrame.newBuilder().setToClient(ToClient.newBuilder().addSessionIds(1)).build());
        assertThat(listener.toClient).isEmpty();

        ch.writeInbound(ack(NODE, 1, true));
        ch.writeInbound(NodeLinkFrame.newBuilder().setToClient(ToClient.newBuilder().addSessionIds(1)).build());
        ch.writeInbound(NodeLinkFrame.newBuilder()
                .setPlayerEnterResult(PlayerEnterResult.newBuilder().setSessionId(1).setPlayerId(1).setTipId(3007)).build());

        assertThat(listener.toClient).containsExactly(new Event(NODE, gen, 1));
        assertThat(listener.enterResults).containsExactly(new Event(NODE, gen, 1));
    }

    @Test
    void 排队溢出的进场帧按建链失败回报() {
        SceneLinkManager manager = manager(1, connects::add);
        manager.send(NODE, forward(1));
        long gen = manager.send(NODE, enter(9));
        assertThat(listener.undeliverable).containsExactly(new Event(NODE, gen, 9));
    }

    @Test
    void 关闭后不再受理_排队中的进场帧回报失败() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        manager.close();
        assertThat(listener.undeliverable).containsExactly(new Event(NODE, gen, 1));
        assertThat(manager.send(NODE, enter(2))).isZero();
    }

    @Test
    void 每次建链都现生成握手帧_重连带新的鉴权时间戳() {
        AtomicInteger made = new AtomicInteger();
        SceneLinkManager manager = new SceneLinkManager(
                () -> HELLO.toBuilder().setAuthTimestamp(made.incrementAndGet()).build(),
                connects::add, new LinkSettings(Duration.ZERO, 100));
        manager.bindListener(listener);

        manager.send(NODE, enter(1));
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));
        assertThat(((NodeLinkFrame) ch.readOutbound()).getHello().getAuthTimestamp()).isEqualTo(1);
        ch.close();

        manager.send(NODE, enter(2));
        EmbeddedChannel ch2 = new EmbeddedChannel(new SceneLinkHandler(connects.get(1)));
        assertThat(((NodeLinkFrame) ch2.readOutbound()).getHello().getAuthTimestamp()).isEqualTo(2);
        ch2.writeInbound(ack(NODE, 1, true));
        assertThat(((NodeLinkFrame) ch2.readOutbound()).hasPlayerEnter()).as("ack 的 zone 与本次发出的握手帧比对").isTrue();
    }

    @Test
    void 就绪后的踢出帧带上代次转给监听器() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));
        ch.writeInbound(ack(NODE, 1, true));

        ch.writeInbound(NodeLinkFrame.newBuilder()
                .setPlayerKicked(PlayerKicked.newBuilder().setSessionId(1).setPlayerId(1).setOwnerEpoch(3).setTipId(2017))
                .build());

        assertThat(listener.kicked).containsExactly(new Event(NODE, gen, 1));
    }

    @Test
    void 就绪链路出站缓冲越过高水位_判死断链_下一帧走新代次() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));
        ch.writeInbound(ack(NODE, 1, true));
        // scene 读不动：未冲刷的帧堆过高水位。
        ch.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 8));
        ch.write(forward(1));
        ch.write(forward(1));
        assertThat(ch.isWritable()).isFalse();

        long next = manager.send(NODE, forward(2));

        assertThat(listener.linkDown).containsExactly(new Event(NODE, gen, 0));
        assertThat(ch.isOpen()).isFalse();
        assertThat(next).as("换新代次重试").isGreaterThan(gen);
        assertThat(connects).hasSize(2);
    }

    // ---------------------------------------------------------------- 跨节点换图改绑指令（批次 5.2）

    @Test
    void 改绑指令_握手前忽略_就绪后带上代次转给监听器_判死之后到达的照样转给监听器() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        SceneLink link = connects.get(0);
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(link));

        ch.writeInbound(transfer(1));
        assertThat(listener.transfers).as("握手前对端身份未核对，改绑指令不可信").isEmpty();

        ch.writeInbound(ack(NODE, 1, true));
        ch.writeInbound(transfer(1));
        assertThat(listener.transfers).containsExactly(new Event(NODE, gen, 1));

        ch.close();
        // 判死与读帧在不同线程上交错（出站高水位判死来自会话线程）：握手成功过的链路上已收到的改绑指令不能丢，
        // 丢了源节点交出的新 epoch 就没人放弃
        link.onFrame(transfer(2));
        assertThat(listener.transfers).containsExactly(new Event(NODE, gen, 1), new Event(NODE, gen, 2));
        assertThat(frames("in", "player_transfer")).isEqualTo(3);
    }

    @Test
    void 从未握手成功就判死的链路_之后到达的改绑指令与其它下行都忽略() {
        SceneLinkManager manager = manager();
        manager.send(NODE, enter(1));
        SceneLink link = connects.get(0);
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(link));
        ch.writeInbound(ack(NODE, 1, false));
        assertThat(ch.isOpen()).isFalse();

        link.onFrame(toClient(1));
        link.onFrame(enterResult(1));
        link.onFrame(kicked(1));
        link.onFrame(transfer(1));
        assertThat(listener.order).isEmpty();
    }

    @Test
    void 就绪后被别的线程判死_已读到的下行与改绑指令同一规则_按到达顺序全部转给监听器() {
        SceneLinkManager manager = manager();
        long gen = manager.send(NODE, enter(1));
        SceneLink link = connects.get(0);
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(link));
        ch.writeInbound(ack(NODE, 1, true));

        // 会话线程发现出站高水位判死链路（关连接只是排进 I/O 线程）；I/O 线程上这一批已读到的帧仍逐个处理：
        // 源节点在 PlayerTransfer 之前发的应答 / 推送不能丢——会话随改绑去了目标节点，之后的断链事件不会再关它
        link.fail("出站缓冲超过高水位（scene 读不动）");
        link.onFrame(toClient(1));
        link.onFrame(enterResult(2));
        link.onFrame(kicked(3));
        link.onFrame(transfer(1));

        assertThat(listener.order).containsExactly("link_down", "to_client:1", "enter_result:2", "kicked:3", "transfer:1");
        assertThat(listener.toClient).containsExactly(new Event(NODE, gen, 1));
        assertThat(listener.enterResults).containsExactly(new Event(NODE, gen, 2));
        assertThat(listener.kicked).containsExactly(new Event(NODE, gen, 3));
        assertThat(listener.transfers).containsExactly(new Event(NODE, gen, 1));
    }

    // ---------------------------------------------------------------- 节点号租约无效：不新建链路

    @Test
    void 租约无效时不新建链路_发往没有活链路的节点返回0_已就绪的链路照常发() {
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        SceneLinkManager manager = new SceneLinkManager(() -> HELLO, connects::add, new LinkSettings(Duration.ZERO, 100),
                leaseValid::get, metrics);
        manager.bindListener(listener);
        long gen = manager.send(NODE, enter(1));
        EmbeddedChannel ready = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));
        ready.writeInbound(ack(NODE, 1, true));
        ready.readOutbound();
        ready.readOutbound();

        leaseValid.set(false);

        assertThat(manager.send(NODE + 1, enter(2))).as("没有活链路的节点：不建链").isZero();
        assertThat(connects).hasSize(1);
        assertThat(manager.send(NODE, forward(1))).as("已就绪的链路照常发").isEqualTo(gen);
        assertThat(((NodeLinkFrame) ready.readOutbound()).hasClientForward()).isTrue();
        assertThat(dropped("lease_invalid")).isEqualTo(1);
    }

    @Test
    void 建链途中租约失效_连上后不握手_排队的进场帧回报失败() {
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        SceneLinkManager manager = new SceneLinkManager(() -> HELLO, connects::add, new LinkSettings(Duration.ZERO, 100),
                leaseValid::get, metrics);
        manager.bindListener(listener);
        long gen = manager.send(NODE, enter(7));

        leaseValid.set(false);
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));

        assertThat((Object) ch.readOutbound()).as("不发握手").isNull();
        assertThat(ch.isOpen()).isFalse();
        assertThat(listener.undeliverable).containsExactly(new Event(NODE, gen, 7));
    }

    // ---------------------------------------------------------------- 指标

    @Test
    void 指标_建链就绪断链与帧收发按类型计数() {
        SceneLinkManager manager = manager();
        manager.send(NODE, enter(1));
        manager.send(NODE, forward(1));
        assertThat(event("connecting")).isEqualTo(1);
        assertThat(frames("out", "player_enter")).as("排队中不算发出").isZero();
        assertThat(manager.linkCount()).isEqualTo(1);

        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connects.get(0)));
        ch.writeInbound(ack(NODE, 1, true));
        manager.send(NODE, leave(1));
        ch.writeInbound(NodeLinkFrame.newBuilder().setToClient(ToClient.newBuilder().addSessionIds(1)).build());

        assertThat(event("ready")).isEqualTo(1);
        assertThat(frames("out", "hello")).isEqualTo(1);
        assertThat(frames("out", "player_enter")).as("就绪时补发").isEqualTo(1);
        assertThat(frames("out", "client_forward")).isEqualTo(1);
        assertThat(frames("out", "player_leave")).isEqualTo(1);
        assertThat(frames("in", "hello_ack")).isEqualTo(1);
        assertThat(frames("in", "to_client")).isEqualTo(1);

        ch.close();
        assertThat(event("down")).isEqualTo(1);
        assertThat(event("connect_failed")).isZero();
        assertThat(manager.linkCount()).isZero();
    }

    @Test
    void 指标_建链失败丢掉的排队帧与排队溢出() {
        SceneLinkManager overflowing = manager(1, connects::add);
        overflowing.send(NODE, forward(1));
        overflowing.send(NODE, enter(9));
        assertThat(dropped("queue_full")).isEqualTo(1);

        SceneLinkManager failing = manager(100, link -> link.fail("连不上"));
        failing.send(NODE + 1, enter(5));
        assertThat(event("connect_failed")).isEqualTo(1);
        assertThat(dropped("link_failed")).as("排队中的进场帧随建链失败丢弃").isEqualTo(1);

        failing.close();
        assertThat(failing.send(NODE + 1, enter(6))).isZero();
        assertThat(dropped("unavailable")).isEqualTo(1);
    }

    private double frames(String direction, String type) {
        return meters.get("xm.gate.link.frames").tag("direction", direction).tag("type", type).counter().count();
    }

    private double event(String event) {
        return meters.get("xm.gate.link.events").tag("event", event).counter().count();
    }

    private double dropped(String reason) {
        return meters.get("xm.gate.link.dropped").tag("reason", reason).counter().count();
    }

    // ---------------------------------------------------------------- 工具

    private static NodeLinkFrame enter(long playerId) {
        return NodeLinkFrame.newBuilder()
                .setPlayerEnter(PlayerEnter.newBuilder().setSessionId((int) playerId).setPlayerId(playerId).setSceneId(900))
                .build();
    }

    private static NodeLinkFrame forward(long playerId) {
        return NodeLinkFrame.newBuilder()
                .setClientForward(ClientForward.newBuilder().setSessionId((int) playerId).setPlayerId(playerId).setMessageId(77))
                .build();
    }

    private static NodeLinkFrame transfer(long playerId) {
        return NodeLinkFrame.newBuilder()
                .setPlayerTransfer(PlayerTransfer.newBuilder().setSessionId((int) playerId).setPlayerId(playerId)
                        .setFromEpoch(5).setToEpoch(6).setTargetSceneNodeId(NODE + 1).setTargetSceneId(901))
                .build();
    }

    private static NodeLinkFrame toClient(int sessionId) {
        return NodeLinkFrame.newBuilder().setToClient(ToClient.newBuilder().addSessionIds(sessionId)).build();
    }

    private static NodeLinkFrame enterResult(int sessionId) {
        return NodeLinkFrame.newBuilder()
                .setPlayerEnterResult(PlayerEnterResult.newBuilder().setSessionId(sessionId).setPlayerId(sessionId))
                .build();
    }

    private static NodeLinkFrame kicked(int sessionId) {
        return NodeLinkFrame.newBuilder()
                .setPlayerKicked(PlayerKicked.newBuilder().setSessionId(sessionId).setPlayerId(sessionId).setTipId(2017))
                .build();
    }

    private static NodeLinkFrame leave(long playerId) {
        return NodeLinkFrame.newBuilder()
                .setPlayerLeave(PlayerLeave.newBuilder().setSessionId((int) playerId).setPlayerId(playerId))
                .build();
    }

    private static NodeLinkFrame ack(int nodeId, int zoneId, boolean accepted) {
        return NodeLinkFrame.newBuilder()
                .setHelloAck(LinkHelloAck.newBuilder()
                        .setSceneNodeId(nodeId).setSceneInstanceId("scene-uuid").setZoneId(zoneId)
                        .setAccepted(accepted).setReason(accepted ? "" : "zone 不符"))
                .build();
    }

    /** (节点号, 代次, 附加值：玩家号 / 会话号 / 0)。 */
    record Event(int nodeId, long gen, long value) {
    }

    private static final class RecordingListener implements SceneLinkListener {

        final List<Event> toClient = new ArrayList<>();
        final List<Event> enterResults = new ArrayList<>();
        final List<Event> kicked = new ArrayList<>();
        final List<Event> transfers = new ArrayList<>();
        final List<Event> undeliverable = new ArrayList<>();
        final List<Event> linkDown = new ArrayList<>();
        /** 业务下行与断链事件的到达顺序（类型:会话号 / 玩家号）。 */
        final List<String> order = new ArrayList<>();

        @Override
        public void onToClient(int sceneNodeId, long linkGen, ToClient message) {
            toClient.add(new Event(sceneNodeId, linkGen, message.getSessionIds(0)));
            order.add("to_client:" + message.getSessionIds(0));
        }

        @Override
        public void onPlayerEnterResult(int sceneNodeId, long linkGen, PlayerEnterResult result) {
            enterResults.add(new Event(sceneNodeId, linkGen, result.getSessionId()));
            order.add("enter_result:" + result.getSessionId());
        }

        @Override
        public void onPlayerKicked(int sceneNodeId, long linkGen, PlayerKicked message) {
            kicked.add(new Event(sceneNodeId, linkGen, message.getSessionId()));
            order.add("kicked:" + message.getSessionId());
        }

        @Override
        public void onPlayerTransfer(int sceneNodeId, long linkGen, PlayerTransfer transfer) {
            transfers.add(new Event(sceneNodeId, linkGen, transfer.getPlayerId()));
            order.add("transfer:" + transfer.getPlayerId());
        }

        @Override
        public void onEnterUndeliverable(int sceneNodeId, long linkGen, PlayerEnter enter) {
            undeliverable.add(new Event(sceneNodeId, linkGen, enter.getPlayerId()));
        }

        @Override
        public void onLinkDown(int sceneNodeId, long linkGen) {
            linkDown.add(new Event(sceneNodeId, linkGen, 0));
            order.add("link_down");
        }
    }
}
