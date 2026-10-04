package com.game.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.chat.metrics.ChatMetrics;
import com.game.chat.store.ChatStore;
import com.game.discovery.RedisKeys;
import com.game.proto.chat.ChatChannelType;
import com.game.proto.chat.ChatMessage;
import com.game.proto.chat.PullChatHistoryRequest;
import com.game.proto.chat.PullChatHistoryResponse;
import com.game.proto.chat.SendChatRequest;
import com.game.proto.chat.SendChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MemoryStore store = new MemoryStore();
    private final AtomicLong clock = new AtomicLong(1_000);
    private final ChatService service = new ChatService(store, new ChatMetrics(registry), 512, 5, 200, Duration.ofDays(7),
            20, 50, Duration.ofSeconds(60), clock::get);

    private static SendChatRequest world(String content, String requestId) {
        return SendChatRequest.newBuilder().setRequestId(requestId).setMessage(ChatMessage.newBuilder()
                .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).setContent(content).setSenderPlayerId(999)
                .setTargetPlayerId(77)).build();
    }

    private static SendChatRequest privateTo(long target, String content) {
        return SendChatRequest.newBuilder().setMessage(ChatMessage.newBuilder()
                .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE).setTargetPlayerId(target).setContent(content)).build();
    }

    private int send(long sender, SendChatRequest request) {
        SendChatResponse r = service.send(sender, request, 1000).toCompletableFuture().join();
        return r.getErrorMessage().getId();
    }

    private PullChatHistoryResponse pull(long self, ChatChannelType channel, long peer, int limit) {
        return service.pull(self, PullChatHistoryRequest.newBuilder().setChannel(channel).setPeerPlayerId(peer)
                .setLimit(limit).build(), 1000).toCompletableFuture().join();
    }

    private double sends(String channel, String outcome) {
        return registry.get("xm.chat.sends").tag("channel", channel).tag("outcome", outcome).counter().count();
    }

    @Test
    void 校验顺序与拒绝码() {
        assertThat(send(0, world("hi", ""))).isEqualTo(1005);                         // 没有会话
        assertThat(send(1, SendChatRequest.getDefaultInstance())).isEqualTo(1005);   // 缺 message
        assertThat(send(1, privateTo(0, "hi"))).isEqualTo(1005);                     // 私聊没目标
        assertThat(send(1, privateTo(1, "hi"))).isEqualTo(1005);                     // 私聊自己
        for (ChatChannelType channel : List.of(ChatChannelType.CHAT_CHANNEL_TYPE_TEAM, ChatChannelType.CHAT_CHANNEL_TYPE_SYSTEM,
                ChatChannelType.CHAT_CHANNEL_TYPE_UNSPECIFIED)) {
            assertThat(send(1, SendChatRequest.newBuilder().setMessage(ChatMessage.newBuilder().setChannel(channel)
                    .setContent("x")).build())).as(channel.name()).isEqualTo(1006);
        }
        assertThat(send(1, SendChatRequest.newBuilder().setMessage(ChatMessage.newBuilder().setChannelValue(9)
                .setContent("x")).build())).isEqualTo(1005);                         // 未知枚举数值
        assertThat(send(1, world("x".repeat(513), ""))).isEqualTo(1010);             // 按字节数
        assertThat(send(1, world("汉".repeat(171), ""))).isEqualTo(1010);            // 513 字节
        assertThat(send(1, world(" 　\t ", ""))).isEqualTo(1010);                // trim 后为空
        assertThat(send(1, world("hi", "r".repeat(65)))).isEqualTo(1005);            // request_id 超长
        assertThat(store.lists).isEmpty();
        assertThat(store.counters).isEmpty();                                        // 被拒的请求不消耗限速额度
        assertThat(sends("unknown", "bad_request")).isEqualTo(1);
        assertThat(sends("world", "no_session")).isEqualTo(1);
    }

    @Test
    void 世界频道_发言人由会话覆盖_目标清零_时刻服务端盖_内容存trim后的值_拉取新在前() {
        assertThat(send(42, world("  hello  ", ""))).isZero();
        clock.set(2_000);
        assertThat(send(43, world("second", ""))).isZero();
        PullChatHistoryResponse history = pull(7, ChatChannelType.CHAT_CHANNEL_TYPE_WORLD, 0, 0);
        assertThat(history.getMessagesList()).extracting(ChatMessage::getContent).containsExactly("second", "hello");
        ChatMessage first = history.getMessages(1);
        assertThat(first.getSenderPlayerId()).isEqualTo(42);
        assertThat(first.getTargetPlayerId()).isZero();
        assertThat(first.getSendTimeMs()).isEqualTo(1_000);
        assertThat(store.ttls.get(RedisKeys.chatWorldLog())).isEqualTo(Duration.ofDays(7).toSeconds());
    }

    @Test
    void 私聊双方同一把键_第三者看不到() {
        assertThat(send(5, privateTo(9, "to nine"))).isZero();
        assertThat(send(9, privateTo(5, "to five"))).isZero();
        assertThat(pull(9, ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE, 5, 10).getMessagesCount()).isEqualTo(2);
        assertThat(pull(5, ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE, 9, 10).getMessagesCount()).isEqualTo(2);
        assertThat(pull(7, ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE, 9, 10).getMessagesCount()).isZero();
        assertThat(store.lists).containsKey(RedisKeys.chatPrivateLog(9, 5));
        assertThat(pull(5, ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE, 5, 10).getErrorMessage().getId()).isEqualTo(1005);
        assertThat(pull(5, ChatChannelType.CHAT_CHANNEL_TYPE_TEAM, 0, 10).getErrorMessage().getId()).isEqualTo(1006);
        assertThat(pull(0, ChatChannelType.CHAT_CHANNEL_TYPE_WORLD, 0, 10).getErrorMessage().getId()).isEqualTo(1005);
    }

    @Test
    void 幂等_同request_id重发不重复写_首发未完成回1008() {
        assertThat(send(1, world("once", "req-1"))).isZero();
        assertThat(send(1, world("once", "req-1"))).isZero();
        assertThat(store.lists.get(RedisKeys.chatWorldLog())).hasSize(1);
        assertThat(sends("world", "duplicate")).isEqualTo(1);
        // 首发还在途（键是 pending）
        store.strings.put(RedisKeys.chatRequestId(1, "req-2"), "pending:abc");
        assertThat(send(1, world("x", "req-2"))).isEqualTo(1008);
        assertThat(sends("world", "in_flight")).isEqualTo(1);
    }

    @Test
    void 幂等键置done完成之后才回包() {
        CompletableFuture<Boolean> held = new CompletableFuture<>();
        MemoryStore holding = new MemoryStore() {
            @Override
            public synchronized CompletionStage<Boolean> markDone(String key, String expected, String done, long ttl) {
                return held.thenCompose(v -> super.markDone(key, expected, done, ttl));
            }
        };
        ChatService s = new ChatService(holding, new ChatMetrics(registry), 512, 5, 200, Duration.ofDays(7), 20, 50,
                Duration.ofSeconds(60), clock::get);
        CompletableFuture<SendChatResponse> reply = s.send(1, world("x", "req-held"), 1000).toCompletableFuture();
        assertThat(reply).isNotDone();
        held.complete(true);
        assertThat(reply.join().hasErrorMessage()).isFalse();
        assertThat(holding.strings.get(RedisKeys.chatRequestId(1, "req-held"))).isEqualTo("done");
    }

    @Test
    void 限速_超过每秒5条回1008并释放幂等键() {
        for (int i = 0; i < 5; i++) {
            assertThat(send(1, world("m" + i, ""))).isZero();
        }
        assertThat(send(1, world("m5", "req-x"))).isEqualTo(1008);
        assertThat(store.strings).doesNotContainKey(RedisKeys.chatRequestId(1, "req-x"));
        assertThat(store.ttls.get(RedisKeys.chatRateLimit(1))).isEqualTo(1);
    }

    @Test
    void 写入失败回1003并释放幂等键_读失败回1003() {
        store.failAppend = true;
        assertThat(send(1, world("x", "req-f"))).isEqualTo(1003);
        assertThat(store.strings).doesNotContainKey(RedisKeys.chatRequestId(1, "req-f"));
        store.failAppend = false;
        store.failRange = true;
        assertThat(pull(1, ChatChannelType.CHAT_CHANNEL_TYPE_WORLD, 0, 0).getErrorMessage().getId()).isEqualTo(1003);
    }

    @Test
    void 拉取条数_0取缺省_超上限钳到上限_坏条目跳过() {
        for (int i = 0; i < 60; i++) {
            store.lists.computeIfAbsent(RedisKeys.chatWorldLog(), k -> new LinkedList<>()).addFirst(
                    ChatMessage.newBuilder().setContent("m" + i).setSendTimeMs(i).build().toByteArray());
        }
        store.lists.get(RedisKeys.chatWorldLog()).addFirst(new byte[] {(byte) 0xFF});
        assertThat(pull(1, ChatChannelType.CHAT_CHANNEL_TYPE_WORLD, 0, 0).getMessagesCount()).isEqualTo(19);
        assertThat(pull(1, ChatChannelType.CHAT_CHANNEL_TYPE_WORLD, 0, 1000).getMessagesCount()).isEqualTo(49);
        assertThat(service.clampLimit(-1)).isEqualTo(50); // uint32 的大值
    }

    /** 内存版 ChatStore（语义同五段脚本）。 */
    static class MemoryStore implements ChatStore {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, Long> counters = new HashMap<>();
        final Map<String, LinkedList<byte[]>> lists = new HashMap<>();
        final Map<String, Long> ttls = new HashMap<>();
        boolean failAppend;
        boolean failRange;

        @Override
        public synchronized CompletionStage<Boolean> claim(String key, String value, long ttlSeconds) {
            if (strings.containsKey(key)) {
                return CompletableFuture.completedFuture(false);
            }
            strings.put(key, value);
            ttls.put(key, ttlSeconds);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public synchronized CompletionStage<String> get(String key) {
            return CompletableFuture.completedFuture(strings.get(key));
        }

        @Override
        public synchronized CompletionStage<Boolean> markDone(String key, String expected, String done, long ttlSeconds) {
            if (!expected.equals(strings.get(key))) {
                return CompletableFuture.completedFuture(false);
            }
            strings.put(key, done);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public synchronized CompletionStage<Boolean> release(String key, String expected) {
            if (!expected.equals(strings.get(key))) {
                return CompletableFuture.completedFuture(false);
            }
            strings.remove(key);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public synchronized CompletionStage<Long> incrWithTtl(String key, long ttlSeconds) {
            long n = counters.merge(key, 1L, Long::sum);
            ttls.put(key, ttlSeconds);
            return CompletableFuture.completedFuture(n);
        }

        @Override
        public synchronized CompletionStage<Void> append(String key, byte[] message, int maxEntries, long ttlSeconds) {
            if (failAppend) {
                return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
            }
            LinkedList<byte[]> list = lists.computeIfAbsent(key, k -> new LinkedList<>());
            list.addFirst(message);
            while (list.size() > maxEntries) {
                list.removeLast();
            }
            ttls.put(key, ttlSeconds);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletionStage<List<byte[]>> range(String key, int limit) {
            if (failRange) {
                return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
            }
            List<byte[]> list = lists.getOrDefault(key, new LinkedList<>());
            return CompletableFuture.completedFuture(new ArrayList<>(list.subList(0, Math.min(limit, list.size()))));
        }
    }
}
