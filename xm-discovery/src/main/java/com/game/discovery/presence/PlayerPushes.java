package com.game.discovery.presence;

import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.PlayerPresence;
import com.game.discovery.proto.PushTarget;
import com.game.discovery.RedisKeys;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 任意 Java 服务向在线玩家推送（mmorpg {@code PushToPlayer} / {@code BroadcastToPlayers} / {@code KickPlayer} 的 Java 版）。
 *
 * <p>做法：查在线目录（{@link PlayerPresenceDirectory}）得到玩家所在 gate 与会话，把 {@code xm.api.GatePush} 发布到该 gate 的
 * pub/sub 频道（{@link RedisKeys#gatePushTopic}），gate 在会话所属 EventLoop 上按玩家栅栏核对后下发。多人推送按 gate 分组，
 * 每个 gate 一条消息。
 *
 * <p>语义与基线一致：<b>至多一次</b>。玩家不在线不推；gate 掉线、频道抖动、玩家恰好下线都会丢，业务方以客户端拉取兜底，
 * 推送失败不得回传成业务失败。比基线多一层玩家栅栏：会话号复用 / 换角色后不会推错人。
 *
 * <p>全部方法异步（不阻塞调用线程）；Redis 故障时返回的 stage 异常完成，调用方只记日志。
 */
public final class PlayerPushes {

    /** 一次推送对某个玩家的结局。 */
    public enum Outcome {
        /** 已发布到玩家所在 gate 的频道且该 gate 在订阅（不代表客户端已收到）。 */
        SENT,
        /** 玩家不在游戏里（在线目录没有条目）。 */
        OFFLINE,
        /** 发布了但没有订阅者（gate 已不在、频道重连中）：等于丢了。 */
        GATE_UNREACHABLE
    }

    private final RedissonClient redis;
    private final PlayerPresenceDirectory directory;

    public PlayerPushes(RedissonClient redis, PlayerPresenceDirectory directory) {
        this.redis = redis;
        this.directory = directory;
    }

    /** 推一条消息给一个玩家。{@code messageContent} 是客户端协议的 {@code MessageContent}（id 填 0）。 */
    public CompletionStage<Outcome> pushToPlayer(long playerId, MessageLite messageContent) {
        ByteString content = messageContent.toByteString();
        return directory.findAsync(playerId).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.completedFuture(Outcome.OFFLINE)
                : publish(found.get(), List.of(found.get()), GatePush.newBuilder().setMessageContent(content)));
    }

    /** 同一条消息推给多个玩家：按 gate 分组，每个 gate 发一条。返回每个玩家的结局。 */
    public CompletionStage<Map<Long, Outcome>> pushToPlayers(Collection<Long> playerIds, MessageLite messageContent) {
        ByteString content = messageContent.toByteString();
        return directory.findAllAsync(playerIds).thenCompose(online -> {
            Map<Long, Outcome> outcomes = new LinkedHashMap<>();
            for (Long id : playerIds) {
                outcomes.put(id, Outcome.OFFLINE);
            }
            Map<String, List<PlayerPresence>> byGate = new HashMap<>();
            for (PlayerPresence p : online.values()) {
                byGate.computeIfAbsent(p.getZoneId() + ":" + p.getGateNodeId() + ":" + p.getGateInstanceId(),
                        k -> new ArrayList<>()).add(p);
            }
            List<CompletableFuture<Void>> sends = new ArrayList<>();
            for (List<PlayerPresence> group : byGate.values()) {
                sends.add(publish(group.get(0), group, GatePush.newBuilder().setMessageContent(content))
                        .thenAccept(outcome -> {
                            synchronized (outcomes) {
                                group.forEach(p -> outcomes.put(p.getPlayerId(), outcome));
                            }
                        }).toCompletableFuture());
            }
            return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).thenApply(done -> outcomes);
        });
    }

    /** 踢下线：gate 推 23 {tipId} 后断开（玩家栅栏同上）。 */
    public CompletionStage<Outcome> kick(long playerId, int tipId) {
        if (tipId <= 0) {
            throw new IllegalArgumentException("踢下线必须带 tip: " + tipId);
        }
        return directory.findAsync(playerId).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.completedFuture(Outcome.OFFLINE)
                : publish(found.get(), List.of(found.get()), GatePush.newBuilder().setKickTipId(tipId)));
    }

    /** 发一条 GatePush 给 {@code gate} 所在的 gate 实例，目标是 {@code targets}（都在同一个 gate 实例上）。 */
    private CompletionStage<Outcome> publish(PlayerPresence gate, List<PlayerPresence> targets, GatePush.Builder push) {
        push.setGateInstanceId(gate.getGateInstanceId());
        for (PlayerPresence t : targets) {
            push.addTargets(PushTarget.newBuilder().setSessionId(t.getSessionId()).setPlayerId(t.getPlayerId()));
        }
        return redis.getTopic(RedisKeys.gatePushTopic(gate.getZoneId(), gate.getGateNodeId()), ByteArrayCodec.INSTANCE)
                .publishAsync(push.build().toByteArray())
                .thenApply(receivers -> receivers != null && receivers > 0 ? Outcome.SENT : Outcome.GATE_UNREACHABLE);
    }
}
