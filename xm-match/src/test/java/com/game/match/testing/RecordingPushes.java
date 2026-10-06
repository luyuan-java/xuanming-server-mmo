package com.game.match.testing;

import com.game.discovery.presence.PlayerPushes;
import com.game.match.port.PlayerPusher;
import com.game.proto.MessageContent;
import com.google.protobuf.MessageLite;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link PlayerPusher} 的测试替身：记下每条推送（谁、哪个消息号、推送体字节）与先后次序，按玩家预置结局。
 *
 * <pre>
 * RecordingPushes pushes = new RecordingPushes();                    // 缺省：人人 SENT，立即完成
 * pushes.outcome(1002, PlayerPushes.Outcome.OFFLINE);                 // 推给 1002 的结局
 * pushes.failFor(1002);                                               // 推给 1002 的 stage 异常完成（Redis 故障）
 * pushes.hold();                                                      // 之后的推送返回未完成的 stage，测试用 complete(下标, 结局) 完成
 * assertThat(pushes.sent).extracting(RecordingPushes.Pushed::playerId).containsExactly(1001L, 1002L);   // 先发起者后应答者
 * ChallengeResultS2C body = ChallengeResultS2C.parseFrom(pushes.sent.get(0).body());
 * </pre>
 * 线程安全。
 */
public final class RecordingPushes implements PlayerPusher {

    /**
     * 一条推送。
     *
     * @param messageId {@code MessageContent.message_id}（156 / 154）
     * @param body      {@code MessageContent.serialized_message}（推送体的字节）
     * @param content   原始的 {@code MessageContent}（断言 {@code id = 0}、没有 {@code error_message} 用）
     */
    public record Pushed(long playerId, int messageId, com.google.protobuf.ByteString body, MessageContent content) {
    }

    /** 全部推送，按调用顺序。 */
    public final List<Pushed> sent = new CopyOnWriteArrayList<>();
    /** 每次推送返回的 future，与 {@link #sent} 同下标。 */
    public final List<CompletableFuture<PlayerPushes.Outcome>> futures = new CopyOnWriteArrayList<>();
    private final Map<Long, PlayerPushes.Outcome> outcomes = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> failing = new ConcurrentHashMap<>();
    private volatile boolean hold;

    public RecordingPushes outcome(long playerId, PlayerPushes.Outcome outcome) {
        outcomes.put(playerId, outcome);
        failing.remove(playerId);
        return this;
    }

    public RecordingPushes failFor(long playerId) {
        failing.put(playerId, true);
        return this;
    }

    public RecordingPushes hold() {
        this.hold = true;
        return this;
    }

    public RecordingPushes complete(int index, PlayerPushes.Outcome outcome) {
        futures.get(index).complete(outcome);
        return this;
    }

    /** 推给某个玩家的全部消息，按先后。 */
    public List<Pushed> sentTo(long playerId) {
        return sent.stream().filter(p -> p.playerId() == playerId).toList();
    }

    @Override
    public synchronized CompletionStage<PlayerPushes.Outcome> push(long playerId, MessageLite messageContent) {
        MessageContent content;
        try {
            content = messageContent instanceof MessageContent mc ? mc : MessageContent.parseFrom(messageContent.toByteString());
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("推送的不是 MessageContent", e);
        }
        sent.add(new Pushed(playerId, content.getMessageId(), content.getSerializedMessage(), content));
        CompletableFuture<PlayerPushes.Outcome> future = new CompletableFuture<>();
        futures.add(future);
        if (failing.containsKey(playerId)) {
            future.completeExceptionally(new IllegalStateException("注入的故障: 推送 player=" + Long.toUnsignedString(playerId)));
        } else if (!hold) {
            future.complete(outcomes.getOrDefault(playerId, PlayerPushes.Outcome.SENT));
        }
        return future;
    }
}
