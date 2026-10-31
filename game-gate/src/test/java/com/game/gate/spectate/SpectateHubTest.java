package com.game.gate.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import com.game.discovery.spectate.SpectateFrameChannel;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.session.ClientSession;
import com.game.gate.session.SessionIdAllocator;
import com.game.gate.session.SessionRegistry;
import com.game.net.client.ClientFrames;
import com.game.proto.BattleStateS2C;
import com.game.proto.MessageContent;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SpectateStateS2C;
import com.game.proto.SpectateTicketPayload;
import com.game.proto.TurnResultS2C;
import com.game.proto.contracts.kafka.SpectateFrameEvent;
import com.game.proto.contracts.kafka.eSpectateFrameKind;
import com.game.proto.eBattleOutcome;
import com.game.proto.eSpectateEndReason;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * gate 观战订阅中心（mmorpg battle-spectate-tiered-fanout.md §15.5 的 Java 版）。同步执行器 + 假频道 + 显式时钟，不用真实墙钟、不依赖执行顺序；
 * 线上字节经真实的 {@link ClientFrames} 编解码，连接是 {@link EmbeddedChannel}。
 */
class SpectateHubTest {

    private static final long BATTLE = 0x9000_0000_0000_0007L;
    private static final long OTHER_BATTLE = 0x9000_0000_0000_0008L;
    private static final String INSTANCE = "battle-instance-1";
    private static final long WALL = 1_760_000_000_000L;
    private static final long DEADLINE = WALL + 300_000;

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final int STATE_ID = REGISTRY.requireId("BattleClientPlayer", "NotifySpectateState");
    private static final int TURN_ID = REGISTRY.requireId("BattleClientPlayer", "NotifySpectateTurnResult");
    private static final int END_ID = REGISTRY.requireId("BattleClientPlayer", "NotifySpectateEnd");
    private static final Map<String, Message> DOWNSTREAM = Map.of(MessageContent.getDescriptor().getFullName(), MessageContent.getDefaultInstance());

    /** 假频道：记下每场的回调与最新帧；退订计数；订阅与读键可以挂起或失败。 */
    private final class FakeSource implements SpectateHub.FrameSource {
        final Map<Long, Consumer<byte[]>> listeners = new HashMap<>();
        final Map<Long, byte[]> state = new HashMap<>();
        final List<Long> closed = new ArrayList<>();
        final List<Long> subscribed = new ArrayList<>();
        boolean failSubscribe;
        /** 订阅不当场完成：监听器已登记（真 Redisson 里帧可能先于 attach 的回调到达），调 {@link #heldAttach} 里的一项才完成。 */
        boolean holdSubscribe;
        final List<Runnable> heldAttach = new ArrayList<>();
        final List<CompletableFuture<SpectateFrameChannel.Subscription>> heldFutures = new ArrayList<>();
        /** 非 null 时读键一律回这个 future。 */
        CompletableFuture<Optional<byte[]>> heldRead;
        int stateReads;

        @Override
        public CompletionStage<SpectateFrameChannel.Subscription> subscribe(long battleId, Consumer<byte[]> frames) {
            subscribed.add(battleId);
            if (failSubscribe) {
                return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
            }
            listeners.put(battleId, frames);
            SpectateFrameChannel.Subscription handle = () -> {
                closed.add(battleId);
                listeners.remove(battleId, frames);
            };
            CompletableFuture<SpectateFrameChannel.Subscription> attached = new CompletableFuture<>();
            if (holdSubscribe) {
                heldAttach.add(() -> attached.complete(handle));
                heldFutures.add(attached);
            } else {
                attached.complete(handle);
            }
            return attached;
        }

        @Override
        public CompletionStage<Optional<byte[]>> readState(long battleId) {
            stateReads++;
            return heldRead != null ? heldRead : CompletableFuture.completedFuture(Optional.ofNullable(state.get(battleId)));
        }
    }

    private final FakeSource source = new FakeSource();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private long nanos = 1_000;
    private long wall = WALL;
    private final SpectateHub hub = new SpectateHub(Runnable::run, source, SpectateHub.MessageIds.resolve(REGISTRY), new GateMetrics(meters), () -> nanos,
            () -> wall);
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(3));
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @AfterEach
    void releaseAll() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    }

    private ClientSession session() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        return registry.open(channel, "127.0.0.1");
    }

    private EmbeddedChannel channelOf(int index) {
        return channels.get(index);
    }

    private static SpectateTicketPayload ticket(long battleId, String instance) {
        return SpectateTicketPayload.newBuilder().setBattleId(battleId).setPlayerId(1001).setBattleInstanceId(instance).setDeadlineMs(DEADLINE)
                .setExpireAtMs(WALL + 60_000).build();
    }

    private static SpectateTicketPayload ticket() {
        return ticket(BATTLE, INSTANCE);
    }

    private static BattleStateS2C state(int round) {
        return BattleStateS2C.newBuilder().setBattleId(BATTLE).setRoundIndex(round).build();
    }

    private static byte[] stateFrame(int round) {
        MessageContent content = MessageContent.newBuilder().setMessageId(STATE_ID)
                .setSerializedMessage(SpectateStateS2C.newBuilder().setState(state(round)).build().toByteString()).build();
        return event(eSpectateFrameKind.SPECTATE_FRAME_STATE, round, content, INSTANCE);
    }

    private static byte[] turnFrame(int round, String instance) {
        MessageContent content = MessageContent.newBuilder().setMessageId(TURN_ID)
                .setSerializedMessage(TurnResultS2C.newBuilder().setBattleId(BATTLE).setRoundIndex(round).setState(state(round + 1)).build().toByteString()).build();
        return event(eSpectateFrameKind.SPECTATE_FRAME_TURN, round, content, instance);
    }

    private static byte[] turnFrame(int round) {
        return turnFrame(round, INSTANCE);
    }

    private static byte[] endFrame(int round) {
        return endFrame(round, INSTANCE);
    }

    private static byte[] endFrame(int round, String instance) {
        MessageContent content = MessageContent.newBuilder().setMessageId(END_ID)
                .setSerializedMessage(SpectateEndS2C.newBuilder().setBattleId(BATTLE).setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                        .setReason(eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED).build().toByteString()).build();
        return event(eSpectateFrameKind.SPECTATE_FRAME_END, round, content, instance);
    }

    private static byte[] event(eSpectateFrameKind kind, int round, MessageContent content, String instance) {
        return SpectateFrameEvent.newBuilder().setBattleId(BATTLE).setBattleInstanceId(instance).setRoundIndex(round).setKind(kind)
                .setClientFrame(content.toByteString()).setDeadlineMs(DEADLINE).build().toByteArray();
    }

    private void publish(byte[] frame) {
        source.listeners.get(BATTLE).accept(frame);
    }

    /** 这条连接上写出的全部帧（按真实线上格式解码；读完清空、释放）。 */
    private static List<MessageContent> frames(EmbeddedChannel channel) throws Exception {
        List<MessageContent> out = new ArrayList<>();
        ByteBuf buf;
        while ((buf = channel.readOutbound()) != null) {
            int len = buf.readInt();
            out.add((MessageContent) ClientFrames.decodeBody(buf.readSlice(len), DOWNSTREAM));
            buf.release();
        }
        return out;
    }

    private static List<Integer> ids(List<MessageContent> frames) {
        return frames.stream().map(MessageContent::getMessageId).toList();
    }

    private double counter(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    // ================================================================ 订阅与首帧

    @Test
    void 首个订阅者_先订频道后读最新帧_有状态帧当场收到161() throws Exception {
        source.state.put(BATTLE, stateFrame(3));
        ClientSession a = session();

        hub.subscribe(a, ticket());

        assertThat(source.subscribed).containsExactly(BATTLE);
        List<MessageContent> frames = frames(channelOf(0));
        assertThat(ids(frames)).containsExactly(STATE_ID);
        assertThat(SpectateStateS2C.parseFrom(frames.get(0).getSerializedMessage()).getState().getRoundIndex()).isEqualTo(3);
    }

    @Test
    void 最新帧是158_取其state合成161_观众数0_不带请求id() throws Exception {
        source.state.put(BATTLE, turnFrame(5));

        hub.subscribe(session(), ticket());

        List<MessageContent> frames = frames(channelOf(0));
        assertThat(ids(frames)).containsExactly(STATE_ID);
        assertThat(frames.get(0).getId()).isZero();
        SpectateStateS2C first = SpectateStateS2C.parseFrom(frames.get(0).getSerializedMessage());
        assertThat(first.getState().getRoundIndex()).as("158 的 state 是下一回合的快照").isEqualTo(6);
        assertThat(first.getObserverCount()).isZero();
    }

    @Test
    void 没有最新帧_先等_下一帧到了先给161_不是158() throws Exception {
        ClientSession a = session();
        hub.subscribe(a, ticket());
        assertThat(frames(channelOf(0))).isEmpty();

        publish(turnFrame(1));

        assertThat(ids(frames(channelOf(0)))).as("首帧恒为 161（D69）").containsExactly(STATE_ID);
        publish(turnFrame(2));
        assertThat(ids(frames(channelOf(0)))).containsExactly(TURN_ID);
    }

    @Test
    void 第二个订阅者直接用缓存发首帧_不再读键_不重复订频道() throws Exception {
        source.state.put(BATTLE, stateFrame(3));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));
        source.state.remove(BATTLE);

        hub.subscribe(session(), ticket());

        assertThat(ids(frames(channelOf(1)))).containsExactly(STATE_ID);
        assertThat(source.subscribed).containsExactly(BATTLE);
    }

    @Test
    void 一个会话只订一场_新订阅替换旧订阅_旧场不再收帧() throws Exception {
        ClientSession a = session();
        hub.subscribe(a, ticket());

        hub.subscribe(a, ticket(OTHER_BATTLE, "other-instance"));
        publish(turnFrame(1));

        assertThat(frames(channelOf(0))).isEmpty();
        assertThat(source.subscribed).containsExactly(BATTLE, OTHER_BATTLE);
    }

    @Test
    void 退订_场次不符什么也不做_符合就摘除_linger到期才退频道() throws Exception {
        ClientSession a = session();
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(a, ticket());
        frames(channelOf(0));

        hub.unsubscribe(a, OTHER_BATTLE);
        publish(turnFrame(1));
        assertThat(ids(frames(channelOf(0)))).as("场次不符：照常收").containsExactly(TURN_ID);

        hub.unsubscribe(a, BATTLE);
        publish(turnFrame(2));
        assertThat(frames(channelOf(0))).isEmpty();
        assertThat(source.closed).as("linger 期内频道还在").isEmpty();

        nanos += SpectateHub.LINGER.toNanos() - 1;
        hub.sweep();
        assertThat(source.closed).isEmpty();
        nanos += 1;
        hub.sweep();
        assertThat(source.closed).containsExactly(BATTLE);
    }

    @Test
    void linger期内回来的订阅者用缓存首帧_不重新订频道() throws Exception {
        ClientSession a = session();
        hub.subscribe(a, ticket());
        hub.drop(a);
        publish(turnFrame(4));

        nanos += SpectateHub.LINGER.toNanos() - 1;
        hub.sweep();
        hub.subscribe(session(), ticket());

        assertThat(ids(frames(channelOf(1)))).containsExactly(STATE_ID);
        assertThat(source.subscribed).hasSize(1);
    }

    @Test
    void 凭证的实例与条目不同_以凭证为准_清缓存_旧实例的帧丢弃() throws Exception {
        source.state.put(BATTLE, stateFrame(3));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        hub.subscribe(session(), ticket(BATTLE, "new-instance"));

        assertThat(frames(channelOf(1))).as("缓存属于旧实例，不能发").isEmpty();
        publish(turnFrame(4, INSTANCE));
        assertThat(counter("game.gate.spectate.frames", "result", "stale_instance")).isEqualTo(1);
        assertThat(frames(channelOf(1))).isEmpty();
        publish(turnFrame(1, "new-instance"));
        assertThat(ids(frames(channelOf(1)))).containsExactly(STATE_ID);
    }

    // ================================================================ 收帧规则

    @Test
    void TURN只收更大的回合_重复与乱序丢弃() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        publish(turnFrame(1));
        publish(turnFrame(1));
        publish(turnFrame(3));
        publish(turnFrame(2));

        List<MessageContent> frames = frames(channelOf(0));
        assertThat(ids(frames)).containsExactly(TURN_ID, TURN_ID);
        assertThat(TurnResultS2C.parseFrom(frames.get(1).getSerializedMessage()).getRoundIndex()).isEqualTo(3);
        assertThat(counter("game.gate.spectate.frames", "result", "duplicate")).isEqualTo(2);
    }

    @Test
    void 紧跟STATE的同号TURN不算重复_快照的回合号是还没结算的那一回合() throws Exception {
        source.state.put(BATTLE, stateFrame(7));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        publish(turnFrame(7));

        assertThat(ids(frames(channelOf(0)))).as("打开中继之后的第一条 158 不能丢").containsExactly(TURN_ID);
    }

    @Test
    void TURN晚于更新的STATE到达_照常扇出_但不把缓存往回换() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        publish(stateFrame(5));
        publish(turnFrame(3));

        assertThat(ids(frames(channelOf(0)))).as("观众还没看过这回合").containsExactly(TURN_ID);
        hub.subscribe(session(), ticket());
        List<MessageContent> second = frames(channelOf(1));
        assertThat(ids(second)).containsExactly(STATE_ID);
        assertThat(SpectateStateS2C.parseFrom(second.get(0).getSerializedMessage()).getState().getRoundIndex()).as("缓存还是更新的快照").isEqualTo(5);
    }

    @Test
    void 第r回合结算之后才到的STATE_r_比缓存旧_丢弃() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        publish(turnFrame(4));
        publish(stateFrame(4));

        assertThat(counter("game.gate.spectate.frames", "result", "duplicate")).isEqualTo(1);
        hub.subscribe(session(), ticket());
        List<MessageContent> second = frames(channelOf(1));
        assertThat(SpectateStateS2C.parseFrom(second.get(0).getSerializedMessage()).getState().getRoundIndex()).as("158(4) 的 state 是下一回合的快照").isEqualTo(5);
    }

    @Test
    void STATE只更新缓存_不下发给已在看的会话_旧快照不回退() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        publish(stateFrame(5));
        assertThat(frames(channelOf(0))).as("已在看的不收").isEmpty();
        publish(stateFrame(2));

        hub.subscribe(session(), ticket());
        List<MessageContent> second = frames(channelOf(1));
        assertThat(ids(second)).containsExactly(STATE_ID);
        assertThat(SpectateStateS2C.parseFrom(second.get(0).getSerializedMessage()).getState().getRoundIndex()).as("旧快照没有盖掉新缓存").isEqualTo(5);
    }

    @Test
    void 解析不了的帧丢弃并计数_不影响之后的帧() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        publish(new byte[] {(byte) 0xFF, 0x01});
        publish(turnFrame(1));

        assertThat(counter("game.gate.spectate.frames", "result", "invalid")).isEqualTo(1);
        assertThat(ids(frames(channelOf(0)))).containsExactly(TURN_ID);
    }

    @Test
    void 种类与消息号对不上的帧丢弃并计数_不下发也不进缓存() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        publish(event(eSpectateFrameKind.SPECTATE_FRAME_TURN, 9, MessageContent.newBuilder().setMessageId(STATE_ID).build(), INSTANCE));

        assertThat(counter("game.gate.spectate.frames", "result", "invalid")).isEqualTo(1);
        assertThat(frames(channelOf(0))).isEmpty();
        publish(turnFrame(2));
        assertThat(ids(frames(channelOf(0)))).as("没有推进水位").containsExactly(TURN_ID);
    }

    @Test
    void 实例换了_留在条目里的订阅者从新实例的下一帧161重新开始() throws Exception {
        source.state.put(BATTLE, stateFrame(3));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        hub.subscribe(session(), ticket(BATTLE, "new-instance"));
        publish(turnFrame(1, "new-instance"));

        assertThat(ids(frames(channelOf(0)))).as("换了来源，老观众也要重新拿状态").containsExactly(STATE_ID);
        assertThat(ids(frames(channelOf(1)))).containsExactly(STATE_ID);
        publish(turnFrame(2, "new-instance"));
        assertThat(ids(frames(channelOf(0)))).containsExactly(TURN_ID);
    }

    @Test
    void END只扇一次_退频道_墓碑期内新订阅直接收166_不进订阅表_到期后删除() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        hub.subscribe(session(), ticket());
        frames(channelOf(0));
        frames(channelOf(1));

        publish(endFrame(3));

        assertThat(ids(frames(channelOf(0)))).containsExactly(END_ID);
        assertThat(ids(frames(channelOf(1)))).containsExactly(END_ID);
        assertThat(source.closed).containsExactly(BATTLE);

        // 墓碑
        nanos += SpectateHub.TOMBSTONE.toNanos() - 1;
        hub.sweep();
        hub.subscribe(session(), ticket());
        assertThat(ids(frames(channelOf(2)))).containsExactly(END_ID);
        assertThat(source.subscribed).as("没有重新订频道").containsExactly(BATTLE);

        // 墓碑到期：新订阅重新开始
        nanos += 1;
        hub.sweep();
        hub.subscribe(session(), ticket());
        assertThat(source.subscribed).containsExactly(BATTLE, BATTLE);
    }

    @Test
    void 最新帧键里已经是终局帧_第一个订阅者直接收166() throws Exception {
        source.state.put(BATTLE, endFrame(9));

        hub.subscribe(session(), ticket());

        assertThat(ids(frames(channelOf(0)))).containsExactly(END_ID);
        assertThat(source.closed).containsExactly(BATTLE);
    }

    // ================================================================ 收尾

    private void assertSourceLost(EmbeddedChannel channel) throws Exception {
        List<MessageContent> frames = frames(channel);
        assertThat(ids(frames)).containsExactly(END_ID);
        SpectateEndS2C end = SpectateEndS2C.parseFrom(frames.get(0).getSerializedMessage());
        assertThat(end.getBattleId()).isEqualTo(BATTLE);
        assertThat(end.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_SOURCE_LOST);
        assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
    }

    @Test
    void 空闲30秒合成166_源站失联_条目删除_退频道_计数() throws Exception {
        hub.subscribe(session(), ticket());
        nanos += SpectateHub.IDLE_TIMEOUT.toNanos() - 1;
        hub.sweep();
        assertThat(frames(channelOf(0))).isEmpty();

        nanos += 1;
        hub.sweep();

        assertSourceLost(channelOf(0));
        assertThat(source.closed).containsExactly(BATTLE);
        assertThat(counter("game.gate.spectate.source.lost")).isEqualTo(1);
        hub.sweep();
        assertThat(frames(channelOf(0))).as("只收尾一次").isEmpty();
    }

    @Test
    void 频道上有新帧刷新空闲计时_最新帧键里读到的不算() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        nanos += SpectateHub.IDLE_TIMEOUT.toNanos() - 1;
        publish(turnFrame(1));
        frames(channelOf(0));
        nanos += SpectateHub.IDLE_TIMEOUT.toNanos() - 1;
        hub.sweep();
        assertThat(frames(channelOf(0))).as("新帧之后不足 30 s").isEmpty();

        nanos += 1;
        hub.sweep();
        assertSourceLost(channelOf(0));
    }

    @Test
    void 过了战斗期限加60秒_即使还有帧也本地收尾() throws Exception {
        hub.subscribe(session(), ticket());
        wall = DEADLINE + SpectateHub.DEADLINE_GRACE.toMillis();
        hub.sweep();
        assertThat(frames(channelOf(0))).isEmpty();

        wall += 1;
        hub.sweep();

        assertSourceLost(channelOf(0));
    }

    @Test
    void 频道订阅失败_当场给观众发源站失联_不空等30秒_计数_条目删除_下一个观众重新订() throws Exception {
        source.failSubscribe = true;
        hub.subscribe(session(), ticket());

        assertSourceLost(channelOf(0));
        assertThat(counter("game.gate.spectate.channel.failures")).isEqualTo(1);
        assertThat(counter("game.gate.spectate.source.lost")).isEqualTo(1);
        assertThat(meters.get("game.gate.spectate.entries").gauge().value()).as("没有频道的条目不留着").isZero();

        source.failSubscribe = false;
        hub.subscribe(session(), ticket());
        assertThat(source.subscribed).containsExactly(BATTLE, BATTLE);
        publish(turnFrame(1));
        assertThat(ids(frames(channelOf(1)))).containsExactly(STATE_ID);
    }

    @Test
    void 频道订阅失败时观众已经走了_只删条目_不算源站失联() throws Exception {
        source.holdSubscribe = true;
        ClientSession a = session();
        hub.subscribe(a, ticket());
        hub.drop(a);

        source.heldFutures.get(0).completeExceptionally(new IllegalStateException("redis down"));

        assertThat(frames(channelOf(0))).isEmpty();
        assertThat(counter("game.gate.spectate.channel.failures")).isEqualTo(1);
        assertThat(counter("game.gate.spectate.source.lost")).isZero();
        assertThat(meters.get("game.gate.spectate.entries").gauge().value()).isZero();
    }

    @Test
    void END之后实例换了_两次订阅都在途_晚到的句柄当场退掉_只留一个订阅() throws Exception {
        source.holdSubscribe = true;
        hub.subscribe(session(), ticket());
        publish(endFrame(3));
        assertThat(ids(frames(channelOf(0)))).as("帧先于 attach 的回调到达").containsExactly(END_ID);

        hub.subscribe(session(), ticket(BATTLE, "new-instance"));
        assertThat(source.heldAttach).as("墓碑已退频道：重新订").hasSize(2);

        source.heldAttach.get(1).run();
        assertThat(source.closed).isEmpty();
        source.heldAttach.get(0).run();
        assertThat(source.closed).as("晚到的多余句柄要退，否则到进程退出才释放").containsExactly(BATTLE);

        publish(turnFrame(1, "new-instance"));
        assertThat(ids(frames(channelOf(1)))).as("留下的订阅照常收帧").containsExactly(STATE_ID);
    }

    @Test
    void 回合与终局依次到达_先158后166() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));

        publish(turnFrame(1));
        publish(endFrame(1));

        assertThat(ids(frames(channelOf(0)))).containsExactly(TURN_ID, END_ID);
    }

    // ================================================================ 收尾之前先看最新帧键（pub/sub 至多一次：丢了 END 而键里还在）

    @Test
    void 空闲收尾前先读最新帧键_读到同实例的END按真实终局收尾_不是源站失联_留墓碑() throws Exception {
        hub.subscribe(session(), ticket());
        source.state.put(BATTLE, endFrame(3));
        nanos += SpectateHub.IDLE_TIMEOUT.toNanos();

        hub.sweep();

        List<MessageContent> frames = frames(channelOf(0));
        assertThat(ids(frames)).containsExactly(END_ID);
        SpectateEndS2C end = SpectateEndS2C.parseFrom(frames.get(0).getSerializedMessage());
        assertThat(end.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED);
        assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(counter("game.gate.spectate.source.lost")).isZero();
        assertThat(source.closed).containsExactly(BATTLE);

        hub.subscribe(session(), ticket());
        assertThat(ids(frames(channelOf(1)))).as("墓碑期内的新订阅同样是真实终局").containsExactly(END_ID);
    }

    @Test
    void 过了战斗期限的收尾同样先读键() throws Exception {
        hub.subscribe(session(), ticket());
        source.state.put(BATTLE, endFrame(3));
        wall = DEADLINE + SpectateHub.DEADLINE_GRACE.toMillis() + 1;

        hub.sweep();

        assertThat(ids(frames(channelOf(0)))).containsExactly(END_ID);
        assertThat(counter("game.gate.spectate.source.lost")).isZero();
    }

    @Test
    void 键里是别的实例的END_不算数_按源站失联收尾() throws Exception {
        hub.subscribe(session(), ticket());
        source.state.put(BATTLE, endFrame(3, "old-instance"));
        nanos += SpectateHub.IDLE_TIMEOUT.toNanos();

        hub.sweep();

        assertSourceLost(channelOf(0));
        assertThat(counter("game.gate.spectate.frames", "result", "stale_instance")).isEqualTo(1);
    }

    @Test
    void 读键失败_按源站失联收尾() throws Exception {
        hub.subscribe(session(), ticket());
        source.heldRead = CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        nanos += SpectateHub.IDLE_TIMEOUT.toNanos();

        hub.sweep();

        assertSourceLost(channelOf(0));
    }

    @Test
    void 读键在途_巡检不重复读也不抢先收尾_其间来了新帧就不收尾() throws Exception {
        hub.subscribe(session(), ticket());
        source.heldRead = new CompletableFuture<>();
        nanos += SpectateHub.IDLE_TIMEOUT.toNanos();

        hub.sweep();
        int reads = source.stateReads;
        hub.sweep();

        assertThat(source.stateReads).as("在途不重复发").isEqualTo(reads);
        assertThat(frames(channelOf(0))).as("结论出来之前不收尾").isEmpty();

        publish(turnFrame(1));
        source.heldRead.complete(Optional.empty());

        assertThat(ids(frames(channelOf(0)))).as("源站还活着：照常收帧").containsExactly(STATE_ID);
        assertThat(counter("game.gate.spectate.source.lost")).isZero();
        assertThat(source.closed).isEmpty();
    }

    @Test
    void 读键在途时观众都走了_不算源站失联_按linger退频道() throws Exception {
        ClientSession a = session();
        hub.subscribe(a, ticket());
        source.heldRead = new CompletableFuture<>();
        nanos += SpectateHub.IDLE_TIMEOUT.toNanos();
        hub.sweep();

        hub.drop(a);
        source.heldRead.complete(Optional.empty());

        assertThat(frames(channelOf(0))).isEmpty();
        assertThat(counter("game.gate.spectate.source.lost")).isZero();
        nanos += SpectateHub.LINGER.toNanos();
        hub.sweep();
        assertThat(source.closed).containsExactly(BATTLE);
    }

    @Test
    void 断开的会话摘除后不再写_收尾不碰它() throws Exception {
        ClientSession a = session();
        hub.subscribe(a, ticket());
        hub.drop(a);

        publish(turnFrame(1));
        nanos += SpectateHub.IDLE_TIMEOUT.toNanos();
        hub.sweep();

        assertThat(frames(channelOf(0))).isEmpty();
        assertThat(counter("game.gate.spectate.source.lost")).as("没有订阅者不算源站失联，按 linger 退订").isZero();
    }

    // ================================================================ 扇出

    @Test
    void 扇出编码一次_各连接线上字节相同_原件没有泄漏() {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        hub.subscribe(session(), ticket());
        channelOf(0).releaseOutbound();
        channelOf(1).releaseOutbound();

        publish(turnFrame(1));

        ByteBuf first = channelOf(0).readOutbound();
        ByteBuf second = channelOf(1).readOutbound();
        assertThat(first.nioBuffer()).as("逐字节相同").isEqualTo(second.nioBuffer());
        first.release();
        second.release();
        assertThat(first.refCnt()).as("各连接的引用释放之后没有残留").isZero();
        assertThat(second.refCnt()).isZero();
    }

    @Test
    void 写缓冲过软水位_跳过这一帧并计数_不断开连接_回落水位后继续收() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        ClientSession slow = session();
        ClientSession fast = session();
        hub.subscribe(slow, ticket());
        hub.subscribe(fast, ticket());
        frames(channelOf(0));
        frames(channelOf(1));
        EmbeddedChannel slowChannel = channelOf(0);
        // 高水位同生产（2 MB）：攒着没 flush 的字节过了 256 KB 软水位，但远没到会断连的高水位
        slowChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1 << 20, 2 << 20));
        slowChannel.write(Unpooled.wrappedBuffer(new byte[(int) SpectateHub.SOFT_WATERMARK_BYTES + 1024]));

        publish(turnFrame(1));

        assertThat(slowChannel.isWritable()).isTrue();
        assertThat(slowChannel.isOpen()).isTrue();
        assertThat(ids(frames(channelOf(1)))).as("别人不受影响").containsExactly(TURN_ID);
        assertThat(counter("game.gate.spectate.fanout", "result", "skipped")).isEqualTo(1);

        slowChannel.flush();
        ((ByteBuf) slowChannel.readOutbound()).release();
        publish(turnFrame(2));
        assertThat(ids(frames(slowChannel))).as("排空之后接着收").containsExactly(TURN_ID);
    }

    @Test
    void 写缓冲过软水位_终局帧照样下发_跳过就再也没有下一帧() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        hub.subscribe(session(), ticket());
        frames(channelOf(0));
        EmbeddedChannel slowChannel = channelOf(0);
        slowChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1 << 20, 2 << 20));
        slowChannel.write(Unpooled.wrappedBuffer(new byte[(int) SpectateHub.SOFT_WATERMARK_BYTES + 1024]));

        publish(endFrame(3));

        ((ByteBuf) slowChannel.readOutbound()).release();
        assertThat(ids(frames(slowChannel))).containsExactly(END_ID);
    }

    @Test
    void 写缓冲过软水位_源站失联的合成166照样下发() throws Exception {
        hub.subscribe(session(), ticket());
        EmbeddedChannel slowChannel = channelOf(0);
        slowChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1 << 20, 2 << 20));
        slowChannel.write(Unpooled.wrappedBuffer(new byte[(int) SpectateHub.SOFT_WATERMARK_BYTES + 1024]));

        nanos += SpectateHub.IDLE_TIMEOUT.toNanos();
        hub.sweep();

        ((ByteBuf) slowChannel.readOutbound()).release();
        assertSourceLost(slowChannel);
    }

    @Test
    void 指标不带id_订阅数与条目数是快照() throws Exception {
        source.state.put(BATTLE, stateFrame(1));
        ClientSession a = session();
        hub.subscribe(a, ticket());
        assertThat(meters.get("game.gate.spectate.subscribers").gauge().value()).isEqualTo(1);
        assertThat(meters.get("game.gate.spectate.entries").gauge().value()).isEqualTo(1);

        hub.drop(a);
        nanos += Duration.ofSeconds(31).toNanos();
        hub.sweepLater();

        assertThat(meters.get("game.gate.spectate.subscribers").gauge().value()).isZero();
        assertThat(meters.get("game.gate.spectate.entries").gauge().value()).isZero();
        assertThat(meters.getMeters()).noneMatch(m -> m.getId().getName().startsWith("game.gate.spectate")
                && m.getId().getTags().stream().anyMatch(t -> t.getKey().contains("battle") || t.getKey().contains("player")));
    }
}
