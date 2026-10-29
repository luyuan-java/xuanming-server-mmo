package com.game.gate.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.game.api.proto.ClientForward;
import com.game.api.proto.LinkHello;
import com.game.api.proto.LinkHelloAck;
import com.game.api.proto.LinkPong;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.ToClient;
import com.game.gate.metrics.GateMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 真链路状态机 + 手动目录/时钟：不连 Redis，不依赖墙钟睡眠。 */
class LinkLifecycleTest {
    private static final int NODE = 7;
    private final List<SceneLink> connected = new ArrayList<>();
    private final List<Long> lost = new ArrayList<>();
    private final List<Long> superseded = new ArrayList<>();
    private final List<Long> transfers = new ArrayList<>();
    private final List<Long> undeliverable = new ArrayList<>();
    private final List<PlayerEnter> dispatched = new ArrayList<>();
    private final AtomicLong time = new AtomicLong();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SceneLinkManager links = new SceneLinkManager(
            () -> LinkHello.newBuilder().setGateNodeId(3).setGateInstanceId("gate").setZoneId(1).setPingIntervalMs(1000).build(),
            connected::add, new LinkSettings(Duration.ZERO, 100, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(30)),
            () -> true, new GateMetrics(meters), time::get);

    LinkLifecycleTest() {
        links.bindListener(new SceneLinkListener() {
            @Override public void onToClient(int n, long g, ToClient m) { }
            @Override public void onPlayerEnterResult(int n, long g, PlayerEnterResult r) { }
            @Override public void onPlayerKicked(int n, long g, PlayerKicked k) { }
            @Override public void onPlayerTransfer(int n, long g, PlayerTransfer t) { transfers.add(g); }
            @Override public void onEnterUndeliverable(int n, long g, PlayerEnter e) { undeliverable.add(g); }
            @Override public void onEnterDispatched(int n, long g, PlayerEnter e) { dispatched.add(e); }
            @Override public void onLinkDown(int n, long g) { lost.add(g); }
            @Override public void onLinkDown(int n, long g, boolean replaced) {
                lost.add(g); if (replaced) superseded.add(g);
            }
        });
    }

    private static SceneNodeInfo node(String instance, long epoch) {
        return SceneNodeInfo.newBuilder().setNodeId(NODE).setInstanceId(instance).setLeaseEpoch(epoch).build();
    }
    private static NodeLinkFrame enter() { return NodeLinkFrame.newBuilder().setPlayerEnter(PlayerEnter.newBuilder().setSessionId(11)).build(); }
    private static NodeLinkFrame forward() { return NodeLinkFrame.newBuilder().setClientForward(ClientForward.newBuilder().setSessionId(11)).build(); }
    private static NodeLinkFrame leave() { return NodeLinkFrame.newBuilder().setPlayerLeave(PlayerLeave.newBuilder().setSessionId(11)).build(); }
    private static NodeLinkFrame ack(String instance, long epoch, int ping) {
        return NodeLinkFrame.newBuilder().setHelloAck(LinkHelloAck.newBuilder().setAccepted(true).setSceneNodeId(NODE)
                .setSceneInstanceId(instance).setZoneId(1).setSceneLeaseEpoch(epoch).setPingIntervalMs(ping)).build();
    }
    private EmbeddedChannel connect(String instance, long directoryEpoch, long ackEpoch, int ping) {
        links.send(NODE, enter());
        SceneLink link = connected.getLast();
        assertThat(link.resolved(node(instance, directoryEpoch))).isTrue();
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(link));
        ch.freezeTime();
        assertThat(((NodeLinkFrame) ch.readOutbound()).hasHello()).isTrue();
        ch.writeInbound(ack(instance, ackEpoch, ping));
        if (ch.isOpen()) assertThat(((NodeLinkFrame) ch.readOutbound()).hasPlayerEnter()).isTrue();
        return ch;
    }

    @Test void 握手必须命中解析实例_低代次拒绝_无符号代次不倒退() {
        links.send(NODE, enter());
        SceneLink first = connected.getLast();
        assertThat(first.resolved(node("expected", 5))).isTrue();
        EmbeddedChannel wrong = new EmbeddedChannel(new SceneLinkHandler(first));
        wrong.writeInbound(ack("other", 99, 0));
        assertThat(wrong.isOpen()).isFalse();
        assertThat(undeliverable).containsExactly(first.generation());
        EmbeddedChannel stale = connect("expected", 5, 4, 0);
        assertThat(stale.isOpen()).isFalse();
        EmbeddedChannel unsigned = connect("expected", Long.MIN_VALUE, -1L, 0);
        assertThat(unsigned.isOpen()).isTrue();
        links.close();
    }

    @Test void 任一握手代次为零兼容旧版_但实例仍严格一致() {
        EmbeddedChannel old = connect("old", 0, 0, 0);
        assertThat(old.isOpen()).isTrue();
        old.close();
        EmbeddedChannel newDirectoryOldScene = connect("old", 3, 0, 0);
        assertThat(newDirectoryOldScene.isOpen()).isTrue();
        links.close();
    }

    @Test void 心跳回显必须与声明相同_异常回显按握手失败回报原进场() {
        EmbeddedChannel channel = connect("a", 1, 1, 5000);
        assertThat(channel.isOpen()).isFalse();
        assertThat(undeliverable).containsExactly(connected.getLast().generation());
        assertThat(lost).isEmpty();
    }

    @Test void 非零代次的新实例必须严格更高_零代次旧实例看到新实例需连续两次() {
        EmbeddedChannel current = connect("a", 3, 3, 0);
        long generation = connected.getLast().generation();
        links.inspectDirectory(List.of(node("b", 3)));
        links.inspectDirectory(List.of(node("b", 3)));
        assertThat(links.send(NODE, enter())).isEqualTo(generation);
        assertThat(current.isOpen()).isTrue();
        links.close();

        // 另一 gate 对该节点尚无非零最大代次：旧 scene 与新目录可按两次证据升级。
        List<SceneLink> pending = new ArrayList<>();
        SceneLinkManager legacy = new SceneLinkManager(() -> LinkHello.newBuilder().setZoneId(1).build(), pending::add,
                new LinkSettings(Duration.ZERO, 10));
        legacy.bindListener(links.listener());
        long oldGen = legacy.send(NODE, enter());
        assertThat(pending.getFirst().resolved(node("old", 0))).isTrue();
        EmbeddedChannel old = new EmbeddedChannel(new SceneLinkHandler(pending.getFirst()));
        old.writeInbound(ack("old", 0, 0));
        legacy.inspectDirectory(List.of(node("new", 7)));
        assertThat(legacy.send(NODE, enter())).isEqualTo(oldGen);
        legacy.inspectDirectory(List.of(node("new", 7)));
        assertThat(legacy.send(NODE, enter())).isNotEqualTo(oldGen);
        legacy.close();
    }

    @Test void 固定心跳不被业务写推迟_读空闲关闭一次_旧版本不启用() {
        EmbeddedChannel ch = connect("a", 1, 1, 1000);
        long generation = connected.getLast().generation();
        assertThat(ch.pipeline().get(IdleStateHandler.class).getReaderIdleTimeInMillis()).isEqualTo(3000);
        links.send(NODE, generation, forward());
        assertThat(((NodeLinkFrame) ch.readOutbound()).hasClientForward()).isTrue();
        ch.advanceTimeBy(1000, TimeUnit.MILLISECONDS); ch.runScheduledPendingTasks();
        assertThat(((NodeLinkFrame) ch.readOutbound()).getPing().getSeq()).isEqualTo(1);
        ch.writeInbound(NodeLinkFrame.newBuilder().setPong(LinkPong.newBuilder().setSeq(1)).build());
        ch.writeInbound(NodeLinkFrame.newBuilder().setToClient(ToClient.getDefaultInstance()).build());
        assertThat(ch.isOpen()).isTrue();
        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        ch.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
        assertThat(ch.isOpen()).isFalse();
        assertThat(lost).containsExactly(generation);
        assertThat(meters.get("game.gate.link.idle.timeouts").counter().count()).isEqualTo(1);
        EmbeddedChannel legacy = connect("b", 2, 2, 0);
        assertThat(legacy.pipeline().get(IdleStateHandler.class)).isNull();
        legacy.advanceTimeBy(60, TimeUnit.SECONDS); legacy.runScheduledPendingTasks();
        assertThat((Object) legacy.readOutbound()).isNull();
        legacy.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
        assertThat(legacy.isOpen()).isTrue();
        links.close();
    }

    @Test void 不同实例更高代次取代_新旧绑定分流_旧链路仍处理改绑_宽限只关旧代次() {
        EmbeddedChannel old = connect("a", 4, 4, 0);
        long oldGen = connected.getLast().generation();
        links.inspectDirectory(List.of(node("b", 5)));
        assertThat(old.isOpen()).isTrue();
        assertThat(links.send(NODE, oldGen, forward())).isEqualTo(oldGen);
        assertThat(((NodeLinkFrame) old.readOutbound()).hasClientForward()).isTrue();
        EmbeddedChannel current = connect("b", 5, 5, 0);
        long newGen = connected.getLast().generation();
        assertThat(newGen).isGreaterThan(oldGen);
        assertThat(links.linkCount()).isEqualTo(2);
        assertThat(links.send(NODE, oldGen, leave())).isEqualTo(oldGen);
        assertThat(((NodeLinkFrame) old.readOutbound()).hasPlayerLeave()).isTrue();
        assertThat((Object) current.readOutbound()).isNull();
        old.writeInbound(NodeLinkFrame.newBuilder().setPlayerTransfer(PlayerTransfer.newBuilder().setSessionId(11)).build());
        assertThat(transfers).containsExactly(oldGen);
        time.set(Duration.ofSeconds(30).toNanos()); links.expireSuperseded();
        assertThat(old.isOpen()).isFalse();
        assertThat(current.isOpen()).isTrue();
        assertThat(lost).containsExactly(oldGen);
        assertThat(superseded).containsExactly(oldGen);
        assertThat(links.send(NODE, oldGen, forward())).isZero();
        assertThat(links.send(NODE, newGen, forward())).isEqualTo(newGen);
        assertThat(links.linkCount()).isEqualTo(1);
        assertThat(meters.get("game.gate.scene.links.superseded").counter().count()).isEqualTo(1);
        links.close();
    }

    @Test void 过期旧绑定发送立即拒绝_无需等下一次目录扫描() {
        EmbeddedChannel old = connect("a", 1, 1, 0);
        long oldGen = connected.getLast().generation();
        links.inspectDirectory(List.of(node("b", 2)));
        time.set(Duration.ofSeconds(30).toNanos());
        assertThat(links.send(NODE, oldGen, leave())).isZero();
        assertThat(old.isOpen()).isFalse();
        assertThat(connected).hasSize(1);
    }

    @Test void 同实例提升代次不换链_迟到旧目录既不取代也不能供新连接使用() {
        EmbeddedChannel ch = connect("a", 4, 4, 0);
        SceneLink link = connected.getLast();
        links.inspectDirectory(List.of(node("a", 8)));
        assertThat(link.peer().leaseEpoch()).isEqualTo(8);
        links.inspectDirectory(List.of(node("stale", 7)));
        links.inspectDirectory(List.of(node("stale", 7)));
        assertThat(links.send(NODE, enter())).isEqualTo(link.generation());
        assertThat(ch.isOpen()).isTrue();
        ch.close();
        links.send(NODE, enter());
        assertThat(connected.getLast().resolved(node("stale", 7))).isFalse();
        assertThat(undeliverable).containsExactly(connected.getLast().generation());
    }

    @Test void 旧版零代次必须连续两次相同新实例_缺席或失败清除候选_不因此断链() {
        EmbeddedChannel old = connect("a", 0, 0, 0);
        long gen = connected.getLast().generation();
        links.inspectDirectory(List.of(node("b", 0)));
        links.inspectDirectory(List.of());
        links.inspectDirectory(List.of(node("b", 0)));
        links.directoryUnavailable();
        links.inspectDirectory(List.of(node("b", 0)));
        links.inspectDirectory(List.of(node("c", 0)));
        assertThat(links.send(NODE, enter())).isEqualTo(gen);
        links.inspectDirectory(List.of(node("c", 0)));
        assertThat(links.send(NODE, enter())).isNotEqualTo(gen);
        assertThat(old.isOpen()).isTrue();
        links.close();
        assertThat(old.isOpen()).isFalse();
        assertThat(links.linkCount()).isZero();
    }

    @Test void 目录读取失败不动活链路_停扫后迟到结果不执行() {
        EmbeddedChannel old = connect("a", 1, 1, 0);
        SupersessionSweep failing = new SupersessionSweep(() -> { throw new IllegalStateException("Redis offline"); }, links);
        failing.run();
        assertThat(old.isOpen()).isTrue();
        assertThat(lost).isEmpty();
        SupersessionSweep closed = new SupersessionSweep(() -> List.of(node("b", 2)), links);
        closed.close(); closed.run();
        assertThat(links.send(NODE, enter())).isEqualTo(connected.getFirst().generation());
        links.close();
    }

    @Test void 扫描至多一笔排队_IO未返回时仍清理宽限_关闭撤销周期任务() {
        EmbeddedChannel old = connect("a", 1, 1, 0);
        links.inspectDirectory(List.of(node("b", 2)));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> tick = new AtomicReference<>();
        doAnswer(call -> { tick.set(call.getArgument(0)); return future; }).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        List<Runnable> ioQueue = new ArrayList<>();
        SupersessionSweep sweep = new SupersessionSweep(() -> List.of(node("b", 2)), links);
        sweep.start(scheduler, ioQueue::add, Duration.ofSeconds(5));
        tick.get().run(); tick.get().run();
        assertThat(ioQueue).hasSize(1);
        time.set(Duration.ofSeconds(30).toNanos()); tick.get().run();
        assertThat(old.isOpen()).isFalse();
        assertThat(ioQueue).hasSize(1);
        sweep.close(); verify(future).cancel(false);
        ioQueue.removeFirst().run(); tick.get().run();
        assertThat(ioQueue).isEmpty();
    }

    @Test void 取消先取得锁_原进场及其未发业务帧不再flush_不误动其它会话() {
        long gen = links.send(NODE, enter());
        links.send(NODE, gen, forward());
        links.send(NODE, gen, leave());
        PlayerEnter other = PlayerEnter.newBuilder().setSessionId(12).build();
        links.send(NODE, NodeLinkFrame.newBuilder().setPlayerEnter(other).build());
        assertThat(links.cancelQueuedEnter(NODE, gen, enter().getPlayerEnter())).isTrue();
        assertThat(links.cancelQueuedEnter(NODE, gen, enter().getPlayerEnter())).isFalse();
        EmbeddedChannel ch = new EmbeddedChannel(new SceneLinkHandler(connected.getLast()));
        ch.readOutbound(); ch.writeInbound(ack("a", 1, 0));
        assertThat(((NodeLinkFrame) ch.readOutbound()).getPlayerEnter()).isEqualTo(other);
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(dispatched).containsExactly(other);
        links.close();
    }

    @Test void 首次write开始前已移除可取消资格_失败future也绝不回报未发送() {
        long gen = links.send(NODE, enter());
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                if (message instanceof NodeLinkFrame frame && frame.hasPlayerEnter()) {
                    assertThat(links.cancelQueuedEnter(NODE, gen, frame.getPlayerEnter())).isFalse();
                    promise.setFailure(new IllegalStateException("socket write outcome unknown"));
                } else ctx.write(message, promise);
            }
        }, new SceneLinkHandler(connected.getLast()));
        ch.readOutbound(); ch.writeInbound(ack("a", 1, 0));
        ch.close();
        assertThat(dispatched).containsExactly(enter().getPlayerEnter());
        assertThat(undeliverable).isEmpty();
        assertThat(lost).containsExactly(gen);
        assertThat(links.cancelQueuedEnter(NODE, gen, enter().getPlayerEnter())).isFalse();
    }
}
