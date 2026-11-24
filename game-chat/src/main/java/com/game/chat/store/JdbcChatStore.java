package com.game.chat.store;

import com.game.api.proto.SessionContext;
import com.game.chat.ChatProperties;
import com.game.common.deadline.Deadline;
import com.game.common.text.GoSpaces;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.chat.ChatChannelType;
import com.game.proto.chat.ChatMessage;
import com.game.proto.chat.ReportChatRequest;
import com.game.proto.chat.SendChatRequest;
import com.game.table.ChatErrorTip;
import com.game.table.CommonErrorTip;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;

/** SQL提交即聊天授权与历史真相；永久请求收据不受Redis、过期或角色换主影响。 */
public final class JdbcChatStore {
    private static final int ACCEPTED = 1;
    private static final int RATE_TIP = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;
    private static final int LEVEL_TOO_LOW_TIP = ChatErrorTip.chat_error.kChatLevelTooLow_VALUE;
    private static final int DUPLICATE_SPAM_TIP = ChatErrorTip.chat_error.kChatDuplicateSpam_VALUE;
    private static final int PRIVATE_TARGET_LIMIT_TIP = ChatErrorTip.chat_error.kChatPrivateTargetLimit_VALUE;
    private static final int MESSAGE_NOT_FOUND_TIP = ChatErrorTip.chat_error.kChatMessageNotFound_VALUE;
    private static final int MAX_REPEAT_SCAN_ROWS = 64;
    private static final int MIN_SPAM_NORMALIZED_CODEPOINTS = 4;
    private static final int MAX_RETRACT_REASON_BYTES = 128;
    private static final int MAX_REPORT_DETAIL_BYTES = 200;
    private static final int PROFILE_CACHE_MAX_ENTRIES = 1 << 16;
    private static final long PROFILE_CACHE_TTL_NANOS = 30_000_000_000L;
    private static final String COLUMNS = "player_id,request_id,owner_account,owner_revision,"
            + "request_digest,request_payload,message_payload,stream_key,state,accepted_ms,result_tip";
    private final DataSource source;
    private final ChatRoleAuthority authority;
    private final ChatPolicy policy;
    private final int rate;
    private final int historyMax;
    private final long historyTtlMs;
    private final int worldChannelCooldownMs;
    private final int worldMinLevel;
    private final int duplicateSpamWindowMs;
    private final int duplicateSpamMaxSenders;
    private final int duplicateSpamMaxPerSender;
    private final int newAccountAgeHours;
    private final int newAccountDailyPrivateLimit;
    private final ProfileReader profileReader;
    private final Map<Long, CachedProfile> profileCache = new HashMap<>();

    /** 防刷与世界频道最低等级所需的玩家基础档案。 */
    public record PlayerProfile(int level, long accountCreatedMs) { }

    /** 玩家档案查询接缝（未装配时不启用等级与新号年龄门槛）。 */
    @FunctionalInterface
    public interface ProfileReader {
        PlayerProfile getProfile(long playerId) throws Exception;
    }

    private record CachedProfile(PlayerProfile profile, long expiresNanos) { }
    private record RetractedKey(long senderPlayerId, long acceptedMs) { }

    /** 世界频道撤回结果（retracted=true 表示本次首次写入撤回记录，可向在线玩家广播撤回通知）。 */
    public record RetractResult(boolean retracted, int tip) { }

    /** 带冷却标记与首次提交原件的发送结果。 */
    public record SendOutcome(Sent sent, boolean cooldown, ChatMessage message) { }

    public JdbcChatStore(DataSource source, ChatRoleAuthority authority, ChatProperties rules) {
        this(source, authority, rules, null);
    }

    public JdbcChatStore(DataSource source, ChatRoleAuthority authority, ChatProperties rules, ProfileReader profileReader) {
        this.source = source;
        this.authority = authority;
        policy = new ChatPolicy(rules);
        rate = rules.rateLimitPerSecond();
        historyMax = rules.historyMaxEntries();
        historyTtlMs = Math.multiplyExact(rules.historyTtl().toSeconds(), 1000);
        worldChannelCooldownMs = rules.worldChannelCooldownMs();
        worldMinLevel = rules.worldMinLevel();
        duplicateSpamWindowMs = rules.duplicateSpamWindowMs();
        duplicateSpamMaxSenders = rules.duplicateSpamMaxSenders();
        duplicateSpamMaxPerSender = rules.duplicateSpamMaxPerSender();
        newAccountAgeHours = rules.newAccountAgeHours();
        newAccountDailyPrivateLimit = rules.newAccountDailyPrivateLimit();
        this.profileReader = profileReader;
    }

    public void initializePolicy() throws SQLException { policy.initialize(source); }

    public record Sent(int tip, boolean duplicate) { }
    private record Stored(long player, byte[] requestId, byte[] account, long revision,
            byte[] digest, byte[] request, byte[] message, byte[] stream,
            int state, long acceptedMs, int tip) { }

    /** 入参已由ChatService规范化；请求号为空仍保每次独立发言的旧客户端行为。 */
    public CompletableFuture<Sent> send(SessionContext original, SendChatRequest normalized, Deadline deadline) {
        String raw = normalized != null && normalized.hasMessage() ? normalized.getMessage().getContent() : "";
        return sendDetailed(original, normalized, raw, 0, deadline).thenApply(SendOutcome::sent);
    }

    /**
     * 完整发送路径：在事务外查档案（可选），事务内依次判重、每秒限速、世界冷却、新号私聊人数限额、跨副本重复刷屏并落库。
     *
     * @param rawContent   规范化前的原始 trim 正文，用于跨副本重复刷屏归一化哈希（避免打码把不同词变成同一串 {@code ***}）
     * @param preRejectTip 事务外内容安全拒绝码（0 表示放行；非 0 时若有 request_id 先核对是否已有成功收据重放）
     */
    public CompletableFuture<SendOutcome> sendDetailed(SessionContext original, SendChatRequest normalized,
            String rawContent, int preRejectTip, Deadline deadline) {
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
        int effectivePreReject = preRejectTip;
        PlayerProfile profile = null;
        if (effectivePreReject == 0) {
            try {
                profile = loadPlayerProfile(player, peer);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
            if (peer == 0 && worldMinLevel > 0 && profile != null && profile.level() < worldMinLevel) {
                effectivePreReject = LEVEL_TOO_LOW_TIP;
            }
        }
        if (effectivePreReject != 0 && normalized.getRequestId().isEmpty()) {
            return CompletableFuture.completedFuture(new SendOutcome(new Sent(effectivePreReject, false), false, null));
        }
        final int preTip = effectivePreReject;
        final PlayerProfile resolvedProfile = profile;
        String requestId = normalized.getRequestId().isEmpty() ? "auto_" + UUID.randomUUID() : normalized.getRequestId();
        byte[] key = utf8(requestId);
        byte[] payload = normalized.toByteArray();
        byte[] digest = digest(payload);
        String spamSource = rawContent == null ? normalized.getMessage().getContent() : rawContent;
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
                return new SendOutcome(new Sent(old.tip(), true), false, null);
            }
            if (preTip != 0) {
                return new SendOutcome(new Sent(preTip, false), false, null);
            }
            long now = now(transaction);
            long bucket = now - now % 1000;
            long accepted = transaction.count("SELECT COUNT(*) FROM chat_send_authority WHERE player_id=? AND state=1 "
                    + "AND accepted_ms>=? AND accepted_ms<?", AuthorityJdbc.u64(player), bucket, Math.addExact(bucket, 1000));
            // 与原聊天合同一致：未接受的限速拒绝不占UUID，下个窗口同号可重新发言。
            if (accepted >= rate) return new SendOutcome(new Sent(RATE_TIP, false), false, null);
            byte[] stream = stream(player, peer);
            if (peer == 0 && worldChannelCooldownMs > 0) {
                long since = Math.max(0L, now - worldChannelCooldownMs);
                Integer cooling = transaction.one(
                        "SELECT 1 FROM chat_send_authority WHERE player_id=? AND state=1 AND accepted_ms>? AND stream_key=? LIMIT 1",
                        row -> row.getInt(1), AuthorityJdbc.u64(player), since, stream);
                if (cooling != null) {
                    return new SendOutcome(new Sent(RATE_TIP, false), true, null);
                }
            }
            int privTip = checkAndRecordPrivateTarget(transaction, player, peer, now, resolvedProfile);
            if (privTip != 0) {
                return new SendOutcome(new Sent(privTip, false), false, null);
            }
            int spamTip = checkAndRecordRepeatSpam(transaction, player, now, spamSource);
            if (spamTip != 0) {
                return new SendOutcome(new Sent(spamTip, false), false, null);
            }
            ChatMessage message = normalized.getMessage().toBuilder().setSendTimeMs(now).build();
            transaction.update("INSERT INTO chat_send_authority(" + COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    AuthorityJdbc.u64(player), key, utf8(original.getAccount()), original.getOwnerRevision(),
                    digest, payload, message.toByteArray(), stream, ACCEPTED, now, 0);
            return new SendOutcome(new Sent(0, false), false, message);
        });
    }

    /** 追加写入世界频道撤回记录（运行账号无 UPDATE/DELETE，不修改 chat_send_authority）。 */
    public CompletableFuture<RetractResult> retractWorld(long senderPlayerId, long acceptedMs, String reason, Deadline deadline) {
        String trimmedReason = reason == null ? "" : GoSpaces.trim(reason);
        byte[] reasonBytes = utf8(trimmedReason);
        if (senderPlayerId == 0 || acceptedMs <= 0 || reasonBytes.length > MAX_RETRACT_REASON_BYTES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("撤回世界聊天参数非法"));
        }
        return authority.executeInternal(deadline, transaction -> {
            policy.require(transaction);
            Integer found = transaction.one(
                    "SELECT 1 FROM chat_send_authority WHERE player_id=? AND state=1 AND accepted_ms=? AND stream_key=? LIMIT 1",
                    row -> row.getInt(1), AuthorityJdbc.u64(senderPlayerId), acceptedMs, stream(senderPlayerId, 0));
            if (found == null) {
                return new RetractResult(false, MESSAGE_NOT_FOUND_TIP);
            }
            long now = now(transaction);
            int inserted = transaction.update(
                    "INSERT IGNORE INTO chat_world_retraction(sender_player_id,accepted_ms,retracted_ms,reason) VALUES(?,?,?,?)",
                    AuthorityJdbc.u64(senderPlayerId), acceptedMs, now, reasonBytes);
            return new RetractResult(inserted > 0, 0);
        });
    }

    /** 核对原消息存在且对当前举报人可见后，将举报写入追加审计表 chat_report。 */
    public CompletableFuture<Integer> reportMessage(SessionContext original, ReportChatRequest request, Deadline deadline) {
        if (original == null) return CompletableFuture.failedFuture(new SecurityException("聊天举报缺少原会话"));
        long reporter = original.getPlayerId();
        if (request == null || request.getTargetSenderPlayerId() == 0 || request.getSendTimeMs() <= 0
                || request.getReason() == 0 || request.getReason() > 4
                || utf8(request.getDetail()).length > MAX_REPORT_DETAIL_BYTES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("聊天举报参数非法"));
        }
        ChatChannelType channel = request.getChannel();
        if (channel != ChatChannelType.CHAT_CHANNEL_TYPE_WORLD && channel != ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("聊天举报频道不支持"));
        }
        long target = request.getTargetSenderPlayerId();
        if (channel == ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE && reporter == target) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("不能举报自己的私聊"));
        }
        byte[] expectedStream = channel == ChatChannelType.CHAT_CHANNEL_TYPE_WORLD
                ? stream(target, 0) : stream(reporter, target);
        byte[] detailBytes = utf8(GoSpaces.trim(request.getDetail()));
        return authority.execute(original, deadline, transaction -> {
            policy.require(transaction);
            Integer exists = transaction.one(
                    "SELECT 1 FROM chat_send_authority WHERE player_id=? AND state=1 AND accepted_ms=? AND stream_key=? LIMIT 1",
                    row -> row.getInt(1), AuthorityJdbc.u64(target), request.getSendTimeMs(), expectedStream);
            if (exists == null) {
                return MESSAGE_NOT_FOUND_TIP;
            }
            long now = now(transaction);
            transaction.update(
                    "INSERT IGNORE INTO chat_report(reporter_player_id,channel,target_sender_player_id,send_time_ms,reason,detail,reported_ms) "
                            + "VALUES(?,?,?,?,?,?,?)",
                    AuthorityJdbc.u64(reporter), channel.getNumber(), AuthorityJdbc.u64(target),
                    request.getSendTimeMs(), request.getReason(), detailBytes, now);
            return 0;
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
            Set<RetractedKey> retracted = Set.of();
            if (peer == 0 && !rows.isEmpty()) {
                long minMs = rows.getFirst().acceptedMs();
                for (int i = 1; i < rows.size(); i++) {
                    if (rows.get(i).acceptedMs() < minMs) {
                        minMs = rows.get(i).acceptedMs();
                    }
                }
                List<RetractedKey> keys = transaction.list(
                        "SELECT sender_player_id,accepted_ms FROM chat_world_retraction WHERE accepted_ms>=?",
                        row -> new RetractedKey(AuthorityJdbc.u64(row, 1), row.getLong(2)), minMs);
                if (!keys.isEmpty()) {
                    retracted = new HashSet<>(keys);
                }
            }
            var messages = new ArrayList<ChatMessage>();
            long previous = now;
            for (Stored row : rows) {
                if (!retracted.isEmpty() && retracted.contains(new RetractedKey(row.player(), row.acceptedMs()))) {
                    continue;
                }
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

    private PlayerProfile loadPlayerProfile(long actor, long peer) throws Exception {
        boolean needLevel = peer == 0 && worldMinLevel > 0;
        boolean needAge = peer != 0 && newAccountAgeHours > 0 && newAccountDailyPrivateLimit > 0;
        if ((!needLevel && !needAge) || profileReader == null) {
            return null;
        }
        long nowNanos = System.nanoTime();
        synchronized (profileCache) {
            CachedProfile cached = profileCache.get(actor);
            if (cached != null && nowNanos < cached.expiresNanos()) {
                return cached.profile();
            }
        }
        PlayerProfile loaded = profileReader.getProfile(actor);
        if (loaded != null) {
            synchronized (profileCache) {
                if (!profileCache.containsKey(actor) && profileCache.size() >= PROFILE_CACHE_MAX_ENTRIES) {
                    profileCache.clear();
                }
                profileCache.put(actor, new CachedProfile(loaded, nowNanos + PROFILE_CACHE_TTL_NANOS));
            }
        }
        return loaded;
    }

    private int checkAndRecordPrivateTarget(AuthorityJdbc.Session tx, long actor, long peer, long now,
            PlayerProfile profile) throws SQLException {
        if (peer == 0 || newAccountAgeHours <= 0 || newAccountDailyPrivateLimit <= 0 || profile == null) {
            return 0;
        }
        long ageLimitMs = (long) newAccountAgeHours * 3_600_000L;
        if (profile.accountCreatedMs() > 0 && now > profile.accountCreatedMs()
                && (now - profile.accountCreatedMs()) >= ageLimitMs) {
            return 0;
        }
        int dayYmd = utcDayYmd(now);
        Integer exists = tx.one(
                "SELECT 1 FROM chat_private_daily_target WHERE sender_player_id=? AND day_ymd=? AND target_player_id=? LIMIT 1",
                row -> row.getInt(1), AuthorityJdbc.u64(actor), dayYmd, AuthorityJdbc.u64(peer));
        if (exists != null) {
            return 0;
        }
        long distinctCount = tx.count(
                "SELECT COUNT(*) FROM chat_private_daily_target WHERE sender_player_id=? AND day_ymd=?",
                AuthorityJdbc.u64(actor), dayYmd);
        if (distinctCount >= newAccountDailyPrivateLimit) {
            return PRIVATE_TARGET_LIMIT_TIP;
        }
        tx.update("INSERT IGNORE INTO chat_private_daily_target(sender_player_id,day_ymd,target_player_id) VALUES(?,?,?)",
                AuthorityJdbc.u64(actor), dayYmd, AuthorityJdbc.u64(peer));
        return 0;
    }

    private int checkAndRecordRepeatSpam(AuthorityJdbc.Session tx, long actor, long now, String content) throws SQLException {
        if (duplicateSpamWindowMs <= 0 || (duplicateSpamMaxSenders <= 0 && duplicateSpamMaxPerSender <= 0)) {
            return 0;
        }
        byte[] hash = normalizeSpamHash(content);
        if (hash == null) {
            return 0;
        }
        long since = Math.max(0L, now - duplicateSpamWindowMs);
        List<Long> senders = tx.list(
                "SELECT sender_player_id FROM chat_repeat_event WHERE content_hash=? AND accepted_ms>=? LIMIT ?",
                row -> AuthorityJdbc.u64(row, 1), hash, since, MAX_REPEAT_SCAN_ROWS);
        Set<Long> others = new HashSet<>();
        int selfCount = 0;
        for (long sender : senders) {
            if (sender == actor) {
                selfCount++;
            } else {
                others.add(sender);
            }
        }
        if (duplicateSpamMaxSenders > 0 && others.size() >= duplicateSpamMaxSenders) {
            return DUPLICATE_SPAM_TIP;
        }
        if (duplicateSpamMaxPerSender > 0 && selfCount >= duplicateSpamMaxPerSender) {
            return DUPLICATE_SPAM_TIP;
        }
        tx.update("INSERT IGNORE INTO chat_repeat_event(content_hash,accepted_ms,sender_player_id) VALUES(?,?,?)",
                hash, now, AuthorityJdbc.u64(actor));
        return 0;
    }

    /** 忽略空白、标点、符号、控制字符与全角/大小写差异计算归一化内容摘要（少于 4 个有效字符跳过）。 */
    static byte[] normalizeSpamHash(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(content.length());
        int codePoints = 0;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 'Ａ' && cp <= 'Ｚ') {
                cp = 'a' + (cp - 'Ａ');
            } else if (cp >= 'ａ' && cp <= 'ｚ') {
                cp = 'a' + (cp - 'ａ');
            } else if (cp >= '０' && cp <= '９') {
                cp = '0' + (cp - '０');
            } else if (cp >= 'A' && cp <= 'Z') {
                cp += 'a' - 'A';
            }
            if (isSpamIgnored(cp)) {
                continue;
            }
            sb.appendCodePoint(cp);
            codePoints++;
        }
        if (codePoints < MIN_SPAM_NORMALIZED_CODEPOINTS) {
            return null;
        }
        return digest(utf8(sb.toString()));
    }

    private static boolean isSpamIgnored(int cp) {
        if (Character.isWhitespace(cp)) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                 Character.CONNECTOR_PUNCTUATION, Character.OTHER_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
                 Character.CONTROL -> true;
            default -> false;
        };
    }

    static int utcDayYmd(long epochMs) {
        ZonedDateTime utc = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC);
        return utc.getYear() * 10000 + utc.getMonthValue() * 100 + utc.getDayOfMonth();
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
