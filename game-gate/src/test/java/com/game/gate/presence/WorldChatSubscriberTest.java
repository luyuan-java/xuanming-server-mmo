package com.game.gate.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.game.gate.session.ClientDispatcher;
import com.game.gate.session.ClientSession;
import com.game.gate.session.SessionIdAllocator;
import com.game.gate.session.SessionRegistry;
import com.game.proto.MessageContent;
import com.game.proto.chat.ChatMessage;
import com.game.proto.chat.ChatRetractedNotify;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;

class WorldChatSubscriberTest {

    private static final int NOTIFY_WORLD_CHAT = 383;
    private static final int NOTIFY_WORLD_CHAT_RETRACTED = 435;

    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(1));
    private final ClientDispatcher dispatcher = mock(ClientDispatcher.class);
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final WorldChatSubscriber subscriber = new WorldChatSubscriber(
            mock(RTopic.class), registry, dispatcher, NOTIFY_WORLD_CHAT, NOTIFY_WORLD_CHAT_RETRACTED,
            10.0, 20.0, nanos::get);

    @Test
    void 广播世界消息与撤回到全部在线会话_过滤非法消息号与坏包() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        EmbeddedChannel ch2 = new EmbeddedChannel();
        ClientSession s1 = registry.open(ch1, "127.0.0.1");
        ClientSession s2 = registry.open(ch2, "127.0.0.2");

        MessageContent chat = MessageContent.newBuilder()
                .setMessageId(NOTIFY_WORLD_CHAT)
                .setSerializedMessage(ChatMessage.newBuilder().setSenderPlayerId(7).setContent("hello").build().toByteString())
                .build();
        MessageContent retract = MessageContent.newBuilder()
                .setMessageId(NOTIFY_WORLD_CHAT_RETRACTED)
                .setSerializedMessage(ChatRetractedNotify.newBuilder().setSenderPlayerId(7).setSendTimeMs(100).build().toByteString())
                .build();
        MessageContent unrelated = MessageContent.newBuilder().setMessageId(999).build();

        subscriber.accept(new byte[] {(byte) 0xff});
        subscriber.accept(unrelated.toByteArray());
        subscriber.accept(chat.toByteArray());
        subscriber.accept(retract.toByteArray());

        ch1.runPendingTasks();
        ch2.runPendingTasks();

        verify(dispatcher).deliverWorldChat(s1, chat);
        verify(dispatcher).deliverWorldChat(s2, chat);
        verify(dispatcher).deliverWorldChat(s1, retract);
        verify(dispatcher).deliverWorldChat(s2, retract);
        verify(dispatcher, never()).deliverWorldChat(any(), eq(unrelated));
    }

    @Test
    void 普通世界聊天受每秒10条突发20条限流_撤回通知永不限流() {
        EmbeddedChannel ch = new EmbeddedChannel();
        ClientSession s = registry.open(ch, "127.0.0.1");

        MessageContent chat = MessageContent.newBuilder()
                .setMessageId(NOTIFY_WORLD_CHAT)
                .setSerializedMessage(ChatMessage.newBuilder().setSenderPlayerId(7).setContent("flood").build().toByteString())
                .build();
        MessageContent retract = MessageContent.newBuilder()
                .setMessageId(NOTIFY_WORLD_CHAT_RETRACTED)
                .setSerializedMessage(ChatRetractedNotify.newBuilder().setSenderPlayerId(7).setSendTimeMs(99).build().toByteString())
                .build();

        // 突发 20 条全部放行，第 21~25 条被丢弃
        for (int i = 0; i < 25; i++) {
            subscriber.accept(chat.toByteArray());
        }
        // 即使普通聊天令牌已耗尽，合规撤回通知仍须立即放行
        subscriber.accept(retract.toByteArray());
        ch.runPendingTasks();

        verify(dispatcher, times(20)).deliverWorldChat(s, chat);
        verify(dispatcher, times(1)).deliverWorldChat(s, retract);

        // 100ms 后补充 1 枚令牌（10/s），再放行 1 条
        nanos.addAndGet(100_000_000L);
        subscriber.accept(chat.toByteArray());
        subscriber.accept(chat.toByteArray());
        ch.runPendingTasks();
        verify(dispatcher, times(21)).deliverWorldChat(s, chat);
        assertThat(registry.size()).isEqualTo(1);
    }
}
