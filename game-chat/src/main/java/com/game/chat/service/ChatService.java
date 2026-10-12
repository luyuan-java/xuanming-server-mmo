package com.game.chat.service;

import com.game.chat.metrics.ChatMetrics;
import com.game.chat.metrics.ChatMetrics.Channel;
import com.game.chat.metrics.ChatMetrics.Outcome;
import com.game.chat.store.ChatStore;
import com.game.common.text.GoSpaces;
import com.game.discovery.RedisKeys;
import com.game.proto.TipInfoMessage;
import com.game.proto.chat.ChatChannelType;
import com.game.proto.chat.ChatMessage;
import com.game.proto.chat.PullChatHistoryRequest;
import com.game.proto.chat.PullChatHistoryResponse;
import com.game.proto.chat.SendChatRequest;
import com.game.proto.chat.SendChatResponse;
import com.game.table.CommonErrorTip;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 聊天业务（mmorpg go/chat internal/logic/chat_logic.go，v1：世界频道 + 私聊，PARITY「聊天」行）。
 *
 * <p>发言顺序是刻意的：先做全部纯校验（身份 → 频道 → 内容 → request_id），再按 幂等 → 限速 → 写入 碰 Redis：
 * <ul>
 *   <li>纯校验在前：被拒的请求不消耗限速额度、不占幂等键；</li>
 *   <li>幂等在限速之前：首发已写入（键为 done）的重发无论当前是否超速都回成功（同一请求同一结果）；</li>
 *   <li>幂等键先占成 {@code pending:<随机 token>}，写入成功后才改 done；占住之后任何一步失败都<b>释放</b>（值相等才删），
 *       让客户端能用同一个 request_id 重试——写入超时但实际已落库时会多一条重复消息，聊天里重复比静默丢失可接受；</li>
 *   <li>同 request_id 的并发请求：后到者读到 pending 回 1008 让它稍后重试，<b>不是</b>回成功（否则先到者随后失败时客户端以为发出去了）。</li>
 * </ul>
 * 发言人只认会话（请求体里的 sender 被覆盖），时刻由服务端盖，内容存 trim 后的值；成功后不推送给任何人（各自拉取）。
 * 业务结果一律 in-band（{@code TipInfoMessage{id}}，不带参数，同基线）。聊天正文不进日志。
 *
 * <p>全部方法异步、不阻塞调用线程（只发 Redis 异步命令、在回调里组下一步）；每步受整请求预算约束，释放 / 完结幂等键有独立的 1 s 上界
 * （它们发生在原请求预算可能已到期的时刻）。
 */
public final class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final int MAX_REQUEST_ID_BYTES = 64;
    static final long RATE_LIMIT_WINDOW_SECONDS = 1;
    static final Duration CLAIM_RELEASE_TIMEOUT = Duration.ofSeconds(1);
    static final String CLAIM_PENDING = "pending";
    static final String CLAIM_DONE = "done";

    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int MESSAGE_TOO_LONG = CommonErrorTip.common_error.kMessageSizeExceeded_VALUE;
    static final int RATE_LIMITED = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;
    static final int CHANNEL_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    static final int STORAGE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;

    private final ChatStore store;
    private final ChatMetrics metrics;
    private final int maxContentBytes;
    private final int rateLimitPerSecond;
    private final int historyMaxEntries;
    private final long historyTtlSeconds;
    private final int historyDefaultLimit;
    private final int historyMaxLimit;
    private final long requestIdTtlSeconds;
    private final LongSupplier clockMs;
    private final SecureRandom random = new SecureRandom();

    public ChatService(ChatStore store, ChatMetrics metrics, int maxContentBytes, int rateLimitPerSecond,
                       int historyMaxEntries, Duration historyTtl, int historyDefaultLimit, int historyMaxLimit,
                       Duration requestIdTtl, LongSupplier clockMs) {
        this.store = store;
        this.metrics = metrics;
        this.maxContentBytes = maxContentBytes;
        this.rateLimitPerSecond = rateLimitPerSecond;
        this.historyMaxEntries = historyMaxEntries;
        this.historyTtlSeconds = historyTtl.toSeconds();
        this.historyDefaultLimit = historyDefaultLimit;
        this.historyMaxLimit = historyMaxLimit;
        this.requestIdTtlSeconds = requestIdTtl.toSeconds();
        this.clockMs = clockMs;
    }

    /** 频道 → 历史键；拒绝时 key 为 null、带 tip 与结局。发言（peer = target）与拉取（peer = peer_player_id）共用。 */
    record Resolved(String key, int tip, Outcome outcome) {
    }

    static Resolved resolveLogKey(int channel, long self, long peer) {
        return switch (channel) {
            case ChatChannelType.CHAT_CHANNEL_TYPE_WORLD_VALUE -> new Resolved(RedisKeys.chatWorldLog(), 0, null);
            case ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE_VALUE -> peer == 0 || peer == self
                    ? new Resolved(null, INVALID_PARAMETER, Outcome.BAD_REQUEST)
                    : new Resolved(RedisKeys.chatPrivateLog(self, peer), 0, null);
            // 队伍要队伍服务给成员关系、系统只能服务端发：v1 都不开放
            case ChatChannelType.CHAT_CHANNEL_TYPE_TEAM_VALUE, ChatChannelType.CHAT_CHANNEL_TYPE_SYSTEM_VALUE,
                 ChatChannelType.CHAT_CHANNEL_TYPE_UNSPECIFIED_VALUE ->
                    new Resolved(null, CHANNEL_UNAVAILABLE, Outcome.CHANNEL_UNAVAILABLE);
            default -> new Resolved(null, INVALID_PARAMETER, Outcome.BAD_REQUEST); // 本版本不认识的枚举数值
        };
    }

    static Channel channelLabel(int channel) {
        return switch (channel) {
            case ChatChannelType.CHAT_CHANNEL_TYPE_WORLD_VALUE -> Channel.WORLD;
            case ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE_VALUE -> Channel.PRIVATE;
            case ChatChannelType.CHAT_CHANNEL_TYPE_TEAM_VALUE -> Channel.TEAM;
            case ChatChannelType.CHAT_CHANNEL_TYPE_SYSTEM_VALUE -> Channel.SYSTEM;
            case ChatChannelType.CHAT_CHANNEL_TYPE_UNSPECIFIED_VALUE -> Channel.UNSPECIFIED;
            default -> Channel.UNKNOWN;
        };
    }

    // ================================================================ 发言

    /**
     * @param sender   会话里的玩家号（0 = 会话没绑定玩家 → 1005）
     * @param budgetMs 整请求预算（毫秒）
     */
    public CompletionStage<SendChatResponse> send(long sender, SendChatRequest request, long budgetMs) {
        ChatMessage msg = request.getMessage();
        Channel label = channelLabel(msg.getChannelValue());
        if (sender == 0) {
            return sendRejected(label, INVALID_PARAMETER, Outcome.NO_SESSION);
        }
        if (!request.hasMessage()) {
            return sendRejected(label, INVALID_PARAMETER, Outcome.BAD_REQUEST);
        }
        Resolved resolved = resolveLogKey(msg.getChannelValue(), sender, msg.getTargetPlayerId());
        if (resolved.key() == null) {
            return sendRejected(label, resolved.tip(), resolved.outcome());
        }
        long target = msg.getChannelValue() == ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE_VALUE ? msg.getTargetPlayerId() : 0;
        // 按原始字节数限长（存储与带宽按字节算），trim 后不能为空
        if (msg.getContentBytes().size() > maxContentBytes) {
            return sendRejected(label, MESSAGE_TOO_LONG, Outcome.TOO_LONG);
        }
        String content = GoSpaces.trim(msg.getContent());
        if (content.isEmpty()) {
            return sendRejected(label, MESSAGE_TOO_LONG, Outcome.TOO_LONG);
        }
        String requestId = request.getRequestId();
        if (requestId.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_ID_BYTES) {
            return sendRejected(label, INVALID_PARAMETER, Outcome.BAD_REQUEST);
        }
        byte[] stored = ChatMessage.newBuilder()
                .setSenderPlayerId(sender).setTargetPlayerId(target).setChannelValue(msg.getChannelValue())
                .setContent(content).setSendTimeMs(clockMs.getAsLong()).build().toByteArray();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);

        if (requestId.isEmpty()) {
            return rateLimitAndWrite(sender, label, resolved.key(), stored, null, null, deadline);
        }
        String claimKey = RedisKeys.chatRequestId(sender, requestId);
        String claimValue = CLAIM_PENDING + ":" + token();
        return within(store.claim(claimKey, claimValue, requestIdTtlSeconds), deadline).handle((claimed, error) -> {
            if (error != null) {
                log.error("[chat] 幂等键 SET NX 失败 sender={}: {}", Long.toUnsignedString(sender), error.toString());
                return sendRejected(label, STORAGE, Outcome.STORAGE_ERROR);
            }
            if (!Boolean.TRUE.equals(claimed)) {
                return duplicate(claimKey, sender, label, deadline);
            }
            return rateLimitAndWrite(sender, label, resolved.key(), stored, claimKey, claimValue, deadline);
        }).thenCompose(stage -> stage);
    }

    private CompletionStage<SendChatResponse> rateLimitAndWrite(long sender, Channel label, String logKey, byte[] stored,
                                                               String claimKey, String claimValue, long deadline) {
        return within(store.incrWithTtl(RedisKeys.chatRateLimit(sender), RATE_LIMIT_WINDOW_SECONDS), deadline)
                .handle((count, error) -> {
                    if (error != null || count == null) {
                        log.error("[chat] 限速脚本失败 sender={}: {}", Long.toUnsignedString(sender),
                                error == null ? "返回空值" : error.toString());
                        return releaseClaim(claimKey, claimValue)
                                .thenCompose(v -> sendRejected(label, STORAGE, Outcome.STORAGE_ERROR));
                    }
                    if (count > rateLimitPerSecond) {
                        return releaseClaim(claimKey, claimValue)
                                .thenCompose(v -> sendRejected(label, RATE_LIMITED, Outcome.RATE_LIMITED));
                    }
                    return within(store.append(logKey, stored, historyMaxEntries, historyTtlSeconds), deadline)
                            .handle((done, appendError) -> {
                                if (appendError != null) {
                                    log.error("[chat] 写历史失败 sender={} key={}: {}", Long.toUnsignedString(sender), logKey,
                                            appendError.toString());
                                    return releaseClaim(claimKey, claimValue)
                                            .thenCompose(v -> sendRejected(label, STORAGE, Outcome.STORAGE_ERROR));
                                }
                                return markClaimDone(claimKey, claimValue, sender).thenApply(v -> {
                                    metrics.send(label, Outcome.OK);
                                    return SendChatResponse.getDefaultInstance();
                                });
                            }).thenCompose(stage -> stage);
                }).thenCompose(stage -> stage);
    }

    /** 幂等键已被占的重发：done → 成功（不重复写）；pending（或恰好过期读到空）→ 1008 稍后重试；读失败 → 1003。 */
    private CompletionStage<SendChatResponse> duplicate(String claimKey, long sender, Channel label, long deadline) {
        return within(store.get(claimKey), deadline).handle((state, error) -> {
            if (error != null) {
                log.error("[chat] 读幂等键状态失败 sender={}: {}", Long.toUnsignedString(sender), error.toString());
                metrics.send(label, Outcome.STORAGE_ERROR);
                return reject(SendChatResponse.newBuilder(), STORAGE);
            }
            if (CLAIM_DONE.equals(state)) {
                metrics.send(label, Outcome.DUPLICATE);
                return SendChatResponse.getDefaultInstance();
            }
            metrics.send(label, Outcome.IN_FLIGHT);
            return reject(SendChatResponse.newBuilder(), RATE_LIMITED);
        });
    }

    /**
     * 失败路径释放本次的占位（值相等才删），完成（或 1 s 超时）后才回包——同基线：客户端随即用同一 request_id 重试时占位已经释放。
     * 尽力而为：失败只记日志，键停在 pending 直到过期，期间同 request_id 重发回 1008。返回的 stage 永不异常完成。
     */
    private CompletionStage<Void> releaseClaim(String claimKey, String claimValue) {
        if (claimKey == null) {
            return CompletableFuture.completedFuture(null);
        }
        return store.release(claimKey, claimValue).toCompletableFuture()
                .orTimeout(CLAIM_RELEASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .handle((r, e) -> {
                    if (e != null) {
                        log.error("[chat] 释放幂等键失败 key={}: {}（TTL 内同 request_id 重发会回 1008，过期后可重试）", claimKey,
                                e.toString());
                    }
                    return null;
                });
    }

    /**
     * 写入成功后把占位改成 done，完成（或 1 s 超时）后才回包——客户端随即重发同一 request_id 时读到 done、按成功回。
     * 失败只记日志、不改变本次结果（消息已落库，回失败会诱发换新 request_id 重发 → 重复）。返回的 stage 永不异常完成。
     */
    private CompletionStage<Void> markClaimDone(String claimKey, String claimValue, long sender) {
        if (claimKey == null) {
            return CompletableFuture.completedFuture(null);
        }
        return store.markDone(claimKey, claimValue, CLAIM_DONE, requestIdTtlSeconds).toCompletableFuture()
                .orTimeout(CLAIM_RELEASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .handle((r, e) -> {
                    if (e != null) {
                        log.error("[chat] 幂等键置 done 失败 sender={} key={}: {}（消息已写入）", Long.toUnsignedString(sender),
                                claimKey, e.toString());
                    }
                    return null;
                });
    }

    private CompletionStage<SendChatResponse> sendRejected(Channel label, int tip, Outcome outcome) {
        metrics.send(label, outcome);
        return CompletableFuture.completedFuture(reject(SendChatResponse.newBuilder(), tip));
    }

    // ================================================================ 拉取

    /** 拉某频道最近 N 条（快照式，新在前；无游标）。私聊键由（会话里的自己, peer）决定：看不到第三者之间的记录。 */
    public CompletionStage<PullChatHistoryResponse> pull(long self, PullChatHistoryRequest request, long budgetMs) {
        Channel label = channelLabel(request.getChannelValue());
        if (self == 0) {
            return pullRejected(label, INVALID_PARAMETER, Outcome.NO_SESSION);
        }
        Resolved resolved = resolveLogKey(request.getChannelValue(), self, request.getPeerPlayerId());
        if (resolved.key() == null) {
            return pullRejected(label, resolved.tip(), resolved.outcome());
        }
        int limit = clampLimit(request.getLimit());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        return within(store.range(resolved.key(), limit), deadline).handle((raws, error) -> {
            if (error != null) {
                log.error("[chat] 读历史失败 player={} key={}: {}", Long.toUnsignedString(self), resolved.key(), error.toString());
                metrics.pull(label, Outcome.STORAGE_ERROR);
                return reject(PullChatHistoryResponse.newBuilder(), STORAGE);
            }
            List<ChatMessage> messages = new ArrayList<>(raws.size());
            for (byte[] raw : raws) {
                try {
                    messages.add(ChatMessage.parseFrom(raw));
                } catch (InvalidProtocolBufferException e) {
                    // 单条坏数据跳过而不是整体失败：一条损坏记录不该让整个频道对所有人不可读
                    log.error("[chat] 历史条目反序列化失败，已跳过 key={}", resolved.key());
                }
            }
            // 以服务端时间戳为准排一次（多实例时钟偏差会让写入顺序与时间不一致）；稳定排序：同毫秒保持写入顺序
            messages.sort(Comparator.comparingLong(ChatMessage::getSendTimeMs).reversed());
            metrics.pull(label, Outcome.OK);
            return PullChatHistoryResponse.newBuilder().addAllMessages(messages).build();
        });
    }

    int clampLimit(int requested) {
        if (requested == 0) {
            return historyDefaultLimit;
        }
        if (Integer.toUnsignedLong(requested) > historyMaxLimit) {
            return historyMaxLimit;
        }
        return requested;
    }

    private CompletionStage<PullChatHistoryResponse> pullRejected(Channel label, int tip, Outcome outcome) {
        metrics.pull(label, outcome);
        return CompletableFuture.completedFuture(reject(PullChatHistoryResponse.newBuilder(), tip));
    }

    // ================================================================ 工具

    private static <B extends com.google.protobuf.Message.Builder> com.google.protobuf.Message rejectRaw(B builder, int tip) {
        builder.setField(builder.getDescriptorForType().findFieldByName("error_message"),
                TipInfoMessage.newBuilder().setId(tip).build());
        return builder.build();
    }

    private static SendChatResponse reject(SendChatResponse.Builder builder, int tip) {
        return (SendChatResponse) rejectRaw(builder, tip);
    }

    private static PullChatHistoryResponse reject(PullChatHistoryResponse.Builder builder, int tip) {
        return (PullChatHistoryResponse) rejectRaw(builder, tip);
    }

    private static <T> CompletableFuture<T> within(CompletionStage<T> stage, long deadlineNanos) {
        long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        return stage.toCompletableFuture().orTimeout(remaining, TimeUnit.MILLISECONDS);
    }

    /** 每次占位都不同的随机串（基线 crypto/rand.Text：26 个 base32 字符）。 */
    private String token() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
