package com.game.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.chat.service.ChatService;
import com.game.chat.store.JdbcChatStore;
import com.game.common.deadline.Deadline;
import com.game.proto.MessageContent;
import com.game.proto.chat.ChatChannelType;
import com.game.proto.chat.ChatMessage;
import com.game.proto.chat.ChatRetractedNotify;
import com.game.proto.chat.SendChatRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RFuture;
import org.redisson.api.RTopic;

/** 世界频道每副本 500ms 近窗快照（含本副本写入/撤回即时作废、私聊仍直读）与 Redis pub/sub 发布器单测。 */
class WorldChatCacheAndPublisherTest {

    private static ChatProperties cachedProps(int cacheMs) {
        return new ChatProperties(512, 20, 200, Duration.ofDays(7), 20, 50,
                Duration.ofSeconds(60), Duration.ofMillis(3500), List.of(),
                0, 0, 0, 0, 0, 0, 0, false, cacheMs);
    }

    private static SessionContext actor(long playerId, String account) {
        return SessionContext.newBuilder().setPlayerId(playerId).setAccount(account).setOwnerRevision(7).build();
    }

    private static SendChatRequest worldReq(long sender, String id, String text) {
        return SendChatRequest.newBuilder().setRequestId(id).setMessage(ChatMessage.newBuilder()
                .setSenderPlayerId(sender).setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).setContent(text)).build();
    }

    private static SendChatRequest privateReq(long sender, long peer, String id, String text) {
        return SendChatRequest.newBuilder().setRequestId(id).setMessage(ChatMessage.newBuilder()
                .setSenderPlayerId(sender).setTargetPlayerId(peer)
                .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE).setContent(text)).build();
    }

    @Test
    void 世界频道近窗500ms缓存命中与过期重读_本副本发送与撤回即时作废_私聊始终直读() throws Exception {
        ChatProperties props = cachedProps(500);
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        try (var f = new ChatSqlFixture(props)) {
            f.player(7, "A", 7);
            f.player(8, "B", 7);
            JdbcChatStore cachedStore = new JdbcChatStore(f.source, f.authority, props, null, nanos::get);
            SessionContext a = actor(7, "A");

            // 1. 写入第一条世界消息并首次拉取建立缓存
            var first = cachedStore.sendDetailed(a, worldReq(7, "w1", "hello-1"), "hello-1", 0, Deadline.after(3500))
                    .get(5, TimeUnit.SECONDS);
            assertThat(first.sent().tip()).isZero();
            List<ChatMessage> snap1 = cachedStore.pull(a, 0, 20, Deadline.after(3500)).get(5, TimeUnit.SECONDS);
            assertThat(snap1).extracting(ChatMessage::getContent).containsExactly("hello-1");

            // 2. 模拟另一副本直接写库：500ms 内仍命中本副本快照，满 500ms 后重读见新消息
            f.clock.addAndGet(100);
            SendChatRequest otherReplicaReq = worldReq(8, "w-other", "from-other-replica");
            byte[] reqBytes = otherReplicaReq.toByteArray();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(reqBytes);
            byte[] msgBytes = otherReplicaReq.getMessage().toBuilder().setSendTimeMs(f.clock.get()).build().toByteArray();
            f.sql.update("INSERT INTO chat_send_authority VALUES(?,?,?,?,?,?,?,?,1,?,0)",
                    8L, "w-other".getBytes(StandardCharsets.UTF_8), "B".getBytes(StandardCharsets.UTF_8), 7L,
                    digest, reqBytes, msgBytes, "world:v2".getBytes(StandardCharsets.UTF_8), f.clock.get());

            nanos.addAndGet(200_000_000L);
            List<ChatMessage> cached = cachedStore.pull(a, 0, 20, Deadline.after(3500)).get(5, TimeUnit.SECONDS);
            assertThat(cached).as("500ms 内命中本副本快照").extracting(ChatMessage::getContent)
                    .containsExactly("hello-1");

            nanos.addAndGet(300_000_000L);
            List<ChatMessage> refreshed = cachedStore.pull(a, 0, 20, Deadline.after(3500)).get(5, TimeUnit.SECONDS);
            assertThat(refreshed).as("满 500ms 过期重读").extracting(ChatMessage::getContent)
                    .containsExactly("from-other-replica", "hello-1");

            // 3. 本副本发送世界消息：即使未满 500ms 也立刻作废快照
            f.clock.addAndGet(100);
            var second = cachedStore.sendDetailed(a, worldReq(7, "w2", "local-new"), "local-new", 0, Deadline.after(3500))
                    .get(5, TimeUnit.SECONDS);
            assertThat(second.sent().tip()).isZero();
            List<ChatMessage> afterLocalSend = cachedStore.pull(a, 0, 20, Deadline.after(3500)).get(5, TimeUnit.SECONDS);
            assertThat(afterLocalSend).extracting(ChatMessage::getContent)
                    .containsExactly("local-new", "from-other-replica", "hello-1");

            // 4. 本副本撤回世界消息：立刻作废快照并隐藏被撤回消息
            var retracted = cachedStore.retractWorld(7, second.message().getSendTimeMs(), "audit", Deadline.after(3500))
                    .get(5, TimeUnit.SECONDS);
            assertThat(retracted.retracted()).isTrue();
            List<ChatMessage> afterRetract = cachedStore.pull(a, 0, 20, Deadline.after(3500)).get(5, TimeUnit.SECONDS);
            assertThat(afterRetract).extracting(ChatMessage::getContent)
                    .containsExactly("from-other-replica", "hello-1");

            // 5. 私聊始终直读，不经过世界快照缓存
            f.clock.addAndGet(100);
            assertThat(cachedStore.send(a, privateReq(7, 8, "p1", "secret"), Deadline.after(3500))
                    .get(5, TimeUnit.SECONDS).tip()).isZero();
            List<ChatMessage> priv = cachedStore.pull(a, 8, 20, Deadline.after(3500)).get(5, TimeUnit.SECONDS);
            assertThat(priv).extracting(ChatMessage::getContent).containsExactly("secret");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void Redis发布器按契约消息号封包发布消息与撤回_异常不外抛() throws Exception {
        RTopic topic = mock(RTopic.class);
        RFuture<Long> okFuture = mock(RFuture.class);
        when(topic.publishAsync(any())).thenReturn(okFuture);

        ChatService.WorldPublisher publisher = ChatConfiguration.redisWorldPublisher(topic, 383, 435);
        ChatMessage msg = ChatMessage.newBuilder()
                .setSenderPlayerId(7).setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD)
                .setContent("hi").setSendTimeMs(12345L).build();
        ChatRetractedNotify notify = ChatRetractedNotify.newBuilder()
                .setSenderPlayerId(7).setSendTimeMs(12345L).build();

        publisher.publishMessage(msg);
        publisher.publishRetracted(notify);

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(topic, times(2)).publishAsync(captor.capture());

        MessageContent first = MessageContent.parseFrom(captor.getAllValues().get(0));
        assertThat(first.getMessageId()).isEqualTo(383);
        assertThat(ChatMessage.parseFrom(first.getSerializedMessage())).isEqualTo(msg);

        MessageContent second = MessageContent.parseFrom(captor.getAllValues().get(1));
        assertThat(second.getMessageId()).isEqualTo(435);
        assertThat(ChatRetractedNotify.parseFrom(second.getSerializedMessage())).isEqualTo(notify);

        // Redis 故障时吞掉异常，不破坏已提交的 SQL 主链路
        when(topic.publishAsync(any())).thenThrow(new IllegalStateException("redis down"));
        publisher.publishMessage(msg);
        publisher.publishRetracted(notify);
    }
}
