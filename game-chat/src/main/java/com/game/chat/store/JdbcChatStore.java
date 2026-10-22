package com.game.chat.store;

import com.game.api.proto.SessionContext;
import com.game.chat.ChatProperties;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.chat.ChatChannelType;
import com.game.proto.chat.ChatMessage;
import com.game.proto.chat.SendChatRequest;
import com.game.table.CommonErrorTip;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;

/** SQL提交即聊天授权与历史真相；永久请求收据不受Redis、过期或角色换主影响。 */
public final class JdbcChatStore {
    private static final int ACCEPTED = 1;
    private static final int RATE_TIP = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;
    private static final String COLUMNS = "player_id,request_id,owner_account,owner_revision,"
            + "request_digest,request_payload,message_payload,stream_key,state,accepted_ms,result_tip";
    private final DataSource source;
    private final ChatRoleAuthority authority;
    private final ChatPolicy policy;
    private final int rate;
    private final int historyMax;
    private final long historyTtlMs;

    public JdbcChatStore(DataSource source, ChatRoleAuthority authority, ChatProperties rules) {
        this.source = source;
        this.authority = authority;
        policy = new ChatPolicy(rules);
        rate = rules.rateLimitPerSecond();
        historyMax = rules.historyMaxEntries();
        historyTtlMs = Math.multiplyExact(rules.historyTtl().toSeconds(), 1000);
    }

    public void initializePolicy() throws SQLException { policy.initialize(source); }

    public record Sent(int tip, boolean duplicate) { }
    private record Stored(long player, byte[] requestId, byte[] account, long revision,
            byte[] digest, byte[] request, byte[] message, byte[] stream,
            int state, long acceptedMs, int tip) { }

    /** 入参已由ChatService规范化；请求号为空仍保每次独立发言的旧客户端行为。 */
    public CompletableFuture<Sent> send(SessionContext original, SendChatRequest normalized, Deadline deadline) {
        if (original == null) return CompletableFuture.failedFuture(new SecurityException("聊天缺少原会话"));
        long player = original.getPlayerId();
        if (normalized == null || !normalized.hasMessage() || normalized.getMessage().getSenderPlayerId() != player
                || normalized.getMessage().getSendTimeMs() != 0 || utf8(normalized.getRequestId()).length > 64) {
            return CompletableFuture.failedFuture(new SecurityException("聊天提交缺少规范化原请求"));
        }
        long peer = normalized.getMessage().getChannel() == ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE
                ? normalized.getMessage().getTargetPlayerId() : 0;
        if ((normalized.getMessage().getChannel() != ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE
                && normalized.getMessage().getChannel() != ChatChannelType.CHAT_CHANNEL_TYPE_WORLD)
                || (normalized.getMessage().getChannel() == ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE && (peer == 0 || peer == player))
                || (normalized.getMessage().getChannel() == ChatChannelType.CHAT_CHANNEL_TYPE_WORLD
                    && normalized.getMessage().getTargetPlayerId() != 0)) {
            return CompletableFuture.failedFuture(new SecurityException("聊天提交原频道或目标无效"));
        }
        String requestId = normalized.getRequestId().isEmpty() ? "auto_" + UUID.randomUUID() : normalized.getRequestId();
        byte[] key = utf8(requestId);
        byte[] payload = normalized.toByteArray();
        byte[] digest = digest(payload);
        return authority.execute(original, deadline, transaction -> {
            policy.require(transaction);
            Stored old = transaction.one("SELECT " + COLUMNS + " FROM chat_send_authority WHERE player_id=? AND request_id=?",
                    JdbcChatStore::stored, AuthorityJdbc.u64(player), key);
            if (old != null) {
                if (!Arrays.equals(old.account(), utf8(original.getAccount())) || old.revision() != original.getOwnerRevision()
                        || !Arrays.equals(old.digest(), digest) || !Arrays.equals(old.request(), payload)) {
                    throw new SecurityException("聊天请求号的原身份或内容不符");
                }
                validateDecision(old);
                if (old.acceptedMs() <= 0 || !Arrays.equals(old.stream(), stream(player, peer))
                        || !Arrays.equals(old.message(), normalized.getMessage().toBuilder()
                            .setSendTimeMs(old.acceptedMs()).build().toByteArray())) {
                    throw new SQLException("聊天成功收据的原正文或分区不符");
                }
                // 已有收据只返回原决定，不能再捕获当前目标、补发、改正文或改限速结果。
                return new Sent(old.tip(), true);
            }
            long now = now(transaction);
            long bucket = now - now % 1000;
            long accepted = transaction.count("SELECT COUNT(*) FROM chat_send_authority WHERE player_id=? AND state=1 "
                    + "AND accepted_ms>=? AND accepted_ms<?", AuthorityJdbc.u64(player), bucket, Math.addExact(bucket, 1000));
            // 与原聊天合同一致：未接受的限速拒绝不占UUID，下个窗口同号可重新发言。
            if (accepted >= rate) return new Sent(RATE_TIP, false);
            byte[] stream = stream(player, peer);
            ChatMessage message = normalized.getMessage().toBuilder().setSendTimeMs(now).build();
            transaction.update("INSERT INTO chat_send_authority(" + COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    AuthorityJdbc.u64(player), key, utf8(original.getAccount()), original.getOwnerRevision(),
                    digest, payload, message.toByteArray(), stream, ACCEPTED, now, 0);
            return new Sent(0, false);
        });
    }

    public CompletableFuture<List<ChatMessage>> pull(SessionContext original, long peer, int limit, Deadline deadline) {
        if (original == null) return CompletableFuture.failedFuture(new SecurityException("聊天缺少原会话"));
        long player = original.getPlayerId();
        if (peer == player || limit <= 0) return CompletableFuture.failedFuture(new SecurityException("聊天读取原分区或窗口无效"));
        int boundedLimit = Math.min(Math.min(limit, ChatProperties.MAX_PULL_MESSAGES), historyMax);
        return authority.execute(original, deadline, transaction -> {
            policy.require(transaction);
            byte[] stream = stream(player, peer);
            long now = now(transaction);
            List<Stored> rows = transaction.list("SELECT " + COLUMNS + " FROM chat_send_authority "
                    + "WHERE stream_key=? AND state=1 AND accepted_ms<=? ORDER BY accepted_ms DESC,player_id DESC,request_id DESC LIMIT ?",
                    JdbcChatStore::stored, stream, now, boundedLimit);
            var messages = new ArrayList<ChatMessage>();
            long previous = now;
            for (Stored row : rows) {
                validateDecision(row);
                if (row.acceptedMs() > now || row.acceptedMs() <= 0) throw new SQLException("聊天原时间无效");
                if (previous - row.acceptedMs() >= historyTtlMs) break;
                previous = row.acceptedMs();
                try {
                    ChatMessage message = ChatMessage.parseFrom(row.message());
                    SendChatRequest request = SendChatRequest.parseFrom(row.request());
                    if (!Arrays.equals(digest(row.request()), row.digest()) || message.getSenderPlayerId() != row.player()
                            || message.getSendTimeMs() != row.acceptedMs()
                            || !message.toBuilder().setSendTimeMs(0).build().equals(request.getMessage())) {
                        throw new SQLException("聊天SQL原消息与授权收据不符");
                    }
                    if (peer == 0) {
                        if (message.getChannel() != ChatChannelType.CHAT_CHANNEL_TYPE_WORLD || message.getTargetPlayerId() != 0) {
                            throw new SQLException("世界聊天原分区不符");
                        }
                    } else {
                        boolean outgoing = row.player() == player && message.getTargetPlayerId() == peer;
                        boolean incoming = row.player() == peer && message.getTargetPlayerId() == player;
                        if (message.getChannel() != ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE || (!outgoing && !incoming)) {
                            throw new SQLException("私聊原角色号与分区不符");
                        }
                    }
                    messages.add(message);
                    if (messages.size() == boundedLimit) break;
                } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                    throw new SQLException("SQL聊天原件损坏，不能借缓存补全", invalid);
                }
            }
            return List.copyOf(messages);
        });
    }

    private static byte[] stream(long player, long peer) {
        if (peer == 0) return utf8("world:v2");
        boolean first = Long.compareUnsigned(player, peer) < 0;
        return utf8("private:v2:" + Long.toUnsignedString(first ? player : peer) + ':'
                + Long.toUnsignedString(first ? peer : player));
    }
    private static long now(AuthorityJdbc.Session transaction) throws SQLException {
        BigDecimal value = transaction.one("SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000.000", row -> row.getBigDecimal(1));
        try {
            long now = value == null ? 0 : value.longValueExact();
            if (now <= 0) throw new ArithmeticException();
            return now;
        } catch (ArithmeticException invalid) { throw new SQLException("聊天数据库时钟不可用", invalid); }
    }
    private static long revision(String value) throws SQLException {
        try {
            long parsed = Long.parseUnsignedLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) { throw new SQLException("聊天归属修订无效", invalid); }
    }
    private static Stored stored(ResultSet row) throws SQLException {
        return new Stored(AuthorityJdbc.u64(row, 1), row.getBytes(2), row.getBytes(3), revision(row.getString(4)),
                row.getBytes(5), row.getBytes(6), row.getBytes(7), row.getBytes(8),
                row.getInt(9), row.getLong(10), row.getInt(11));
    }
    private static void validateDecision(Stored row) throws SQLException {
        if (row.state() != ACCEPTED || row.tip() != 0 || row.account() == null || row.account().length == 0
                || row.digest() == null || row.digest().length != 32 || row.request() == null || row.message() == null) {
            throw new SQLException("聊天永久决定损坏或状态未知");
        }
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
