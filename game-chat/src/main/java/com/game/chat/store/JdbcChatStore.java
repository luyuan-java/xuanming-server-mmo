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
    /** 世界频道平台补审扫描回看窗口（15 分钟，对齐 Go platformRecheckWindowMs）。 */
    public static final long PLATFORM_RECHECK_WINDOW_MS = 15L * 60 * 1000;
    /** 单次补审认领租期（20 秒，同时作为平台不可用时的重试间隔，对齐 Go platformRecheckLeaseMs）。 */
    public static final long PLATFORM_RECHECK_LEASE_MS = 20_000L;
    /** 单条待补审最多尝试送审次数（对齐 Go PlatformRecheckMaxAttempts）。 */
    public static final int PLATFORM_RECHECK_MAX_ATTEMPTS = 3;
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
    private final long worldHistoryCacheNanos;
    private final java.util.function.LongSupplier nanoClock;
    private final ProfileReader profileReader;
    private final Map<Long, CachedProfile> profileCache = new HashMap<>();
    private final Object worldCacheLock = new Object();
    private volatile WorldSnapshot worldSnapshot;
    private long worldCacheEpoch;
    private CompletableFuture<List<ChatMessage>> worldRefreshInFlight;

    private record WorldSnapshot(List<ChatMessage> messages, long fetchedAtNanos) { }

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

    /** 世界频道平台补审结局（存在 chat_platform_recheck_done.outcome，对齐 Go authority.RecheckOutcome）。 */
    public enum RecheckOutcome {
        PASSED(1),
        RETRACTED(2),
        GAVE_UP(3);

        private final int code;

        RecheckOutcome(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** 认领到的一条世界频道平台补审任务（对齐 Go authority.PlatformRecheckTask）。 */
    public record PlatformRecheckTask(
            long senderPlayerId,
            long acceptedMs,
            int attempt,
            String content,
            boolean alreadyRetracted,
            boolean exhausted,
            boolean missing) { }

    private record RecheckCandidate(long senderPlayerId, long acceptedMs, byte[] rawContent) { }

    /** 带冷却标记与首次提交原件的发送结果。 */
    public record SendOutcome(Sent sent, boolean cooldown, ChatMessage message) { }

    public JdbcChatStore(DataSource source, ChatRoleAuthority authority, ChatProperties rules) {
        this(source, authority, rules, null, System::nanoTime);
    }

    public JdbcChatStore(DataSource source, ChatRoleAuthority authority, ChatProperties rules, ProfileReader profileReader) {
        this(source, authority, rules, profileReader, System::nanoTime);
    }

    public JdbcChatStore(DataSource source, ChatRoleAuthority authority, ChatProperties rules,
                         ProfileReader profileReader, java.util.function.LongSupplier nanoClock) {
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
        worldHistoryCacheNanos = Math.max(0L, rules.worldHistoryCacheMs()) * 1_000_000L;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        this.profileReader = profileReader;
    }

    /** 本副本世界频道写入或撤回提交后立即作废近窗快照，并递增代次阻止旧在途回填覆盖新数据。 */
    void invalidateWorldSnapshot() {
        synchronized (worldCacheLock) {
            worldCacheEpoch++;
            worldSnapshot = null;
        }
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
        return sendDetailed(original, normalized, rawContent, preRejectTip, false, deadline);
    }

    /**
     * 完整发送路径：可选在世界消息落库同一事务内原子登记持久化平台补审待办（{@code chat_platform_recheck}），
     * 保障进程崩溃、重启或突发流量下不丢待补审任务。
     */
    public CompletableFuture<SendOutcome> sendDetailed(SessionContext original, SendChatRequest normalized,
            String rawContent, int preRejectTip, boolean enqueuePlatformRecheck, Deadline deadline) {
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
            if (peer == 0 && enqueuePlatformRecheck && !spamSource.isEmpty()) {
                byte[] rawBytes = utf8(spamSource);
                if (rawBytes.length > 4096) {
                    rawBytes = Arrays.copyOf(rawBytes, 4096);
                }
                transaction.update(
                        "INSERT IGNORE INTO chat_platform_recheck(sender_player_id,accepted_ms,raw_content) VALUES(?,?,?)",
                        AuthorityJdbc.u64(player), now, rawBytes);
            }
            return new SendOutcome(new Sent(0, false), false, message);
        }).whenComplete((outcome, err) -> {
            if (err == null && peer == 0 && outcome != null && outcome.sent().tip() == 0 && !outcome.sent().duplicate()) {
                invalidateWorldSnapshot();
            }
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
        }).whenComplete((res, err) -> {
            if (err == null && res != null && res.retracted()) {
                invalidateWorldSnapshot();
            }
        });
    }

    /**
     * 认领至多 {@code limit} 条待补审世界消息（对齐 Go {@code ClaimPlatformRechecks}）：
     * 仅扫描回看窗口内未写结局的任务，按每条独立短事务用 {@code INSERT IGNORE} 竞争认领槽位，防多副本重复送审。
     */
    public CompletableFuture<List<PlatformRecheckTask>> claimPlatformRechecks(int limit, Deadline deadline) {
        if (limit <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("补审批量上限必须大于 0"));
        }
        int scanLimit = Math.min(limit * 4, 256);
        return authority.executeInternal(deadline, tx -> {
            long now = now(tx);
            long since = now > PLATFORM_RECHECK_WINDOW_MS ? now - PLATFORM_RECHECK_WINDOW_MS : 0L;
            return tx.list(
                    "SELECT r.sender_player_id,r.accepted_ms,r.raw_content FROM chat_platform_recheck r "
                            + "WHERE r.accepted_ms>=? AND NOT EXISTS ("
                            + "SELECT 1 FROM chat_platform_recheck_done d "
                            + "WHERE d.sender_player_id=r.sender_player_id AND d.accepted_ms=r.accepted_ms) "
                            + "ORDER BY r.accepted_ms ASC LIMIT ?",
                    row -> new RecheckCandidate(AuthorityJdbc.u64(row, 1), row.getLong(2), row.getBytes(3)),
                    since, scanLimit);
        }).thenCompose(candidates -> {
            CompletableFuture<List<PlatformRecheckTask>> chain =
                    CompletableFuture.completedFuture(new ArrayList<>(Math.min(limit, candidates.size())));
            for (RecheckCandidate candidate : candidates) {
                chain = chain.thenCompose(acc -> {
                    if (acc.size() >= limit) {
                        return CompletableFuture.completedFuture(acc);
                    }
                    return claimOnePlatformRecheck(candidate, deadline).thenApply(task -> {
                        if (task != null) {
                            acc.add(task);
                        }
                        return acc;
                    });
                });
            }
            return chain;
        });
    }

    private CompletableFuture<PlatformRecheckTask> claimOnePlatformRecheck(RecheckCandidate c, Deadline deadline) {
        return authority.executeInternal(deadline, tx -> {
            long now = now(tx);
            long[] stats = tx.one(
                    "SELECT COUNT(*), COALESCE(MAX(claimed_ms), 0) FROM chat_platform_recheck_claim "
                            + "WHERE sender_player_id=? AND accepted_ms=?",
                    row -> new long[] {row.getLong(1), row.getLong(2)},
                    AuthorityJdbc.u64(c.senderPlayerId()), c.acceptedMs());
            int attempts = stats == null ? 0 : (int) stats[0];
            long lastClaimMs = stats == null ? 0L : stats[1];
            if (attempts > 0 && lastClaimMs + PLATFORM_RECHECK_LEASE_MS > now) {
                return null;
            }
            if (attempts >= PLATFORM_RECHECK_MAX_ATTEMPTS) {
                return new PlatformRecheckTask(c.senderPlayerId(), c.acceptedMs(), attempts, "", false, true, false);
            }
            int claimed = tx.update(
                    "INSERT IGNORE INTO chat_platform_recheck_claim(sender_player_id,accepted_ms,attempt,claimed_ms) "
                            + "VALUES(?,?,?,?)",
                    AuthorityJdbc.u64(c.senderPlayerId()), c.acceptedMs(), attempts, now);
            if (claimed == 0) {
                return null;
            }
            Integer retracted = tx.one(
                    "SELECT 1 FROM chat_world_retraction WHERE sender_player_id=? AND accepted_ms=? LIMIT 1",
                    row -> row.getInt(1), AuthorityJdbc.u64(c.senderPlayerId()), c.acceptedMs());
            if (retracted != null) {
                return new PlatformRecheckTask(c.senderPlayerId(), c.acceptedMs(), attempts, "", true, false, false);
            }
            Integer originalExists = tx.one(
                    "SELECT 1 FROM chat_send_authority WHERE player_id=? AND state=1 AND accepted_ms=? AND stream_key=? LIMIT 1",
                    row -> row.getInt(1), AuthorityJdbc.u64(c.senderPlayerId()), c.acceptedMs(), stream(c.senderPlayerId(), 0));
            if (originalExists == null) {
                return new PlatformRecheckTask(c.senderPlayerId(), c.acceptedMs(), attempts, "", false, false, true);
            }
            String raw = c.rawContent() == null ? "" : new String(c.rawContent(), StandardCharsets.UTF_8);
            return new PlatformRecheckTask(c.senderPlayerId(), c.acceptedMs(), attempts, raw, false, false, false);
        });
    }

    /** 追加写入平台补审最终结局（INSERT IGNORE 幂等，对齐 Go {@code FinishPlatformRecheck}）。 */
    public CompletableFuture<Void> finishPlatformRecheck(long senderPlayerId, long acceptedMs,
                                                         RecheckOutcome outcome, Deadline deadline) {
        if (senderPlayerId == 0 || acceptedMs <= 0 || outcome == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("平台补审结局参数非法"));
        }
        return authority.executeInternal(deadline, tx -> {
            long now = now(tx);
            tx.update(
                    "INSERT IGNORE INTO chat_platform_recheck_done(sender_player_id,accepted_ms,outcome,done_ms) "
                            + "VALUES(?,?,?,?)",
                    AuthorityJdbc.u64(senderPlayerId), acceptedMs, outcome.code(), now);
            return null;
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
        if (peer == 0 && worldHistoryCacheNanos > 0) {
            return pullWorldCached(original, boundedLimit, deadline);
        }
        return authority.execute(original, deadline, transaction -> pullInTransaction(transaction, player, peer, boundedLimit));
    }

    /** 世界频道每副本近窗快照（默认 500ms，单飞回填，防本副本提交后旧回填覆盖）；私聊不经过此处。 */
    private CompletableFuture<List<ChatMessage>> pullWorldCached(SessionContext original, int boundedLimit, Deadline deadline) {
        if (original.getPlayerId() == 0 || original.getAccount().isEmpty() || original.getOwnerRevision() <= 0) {
            return CompletableFuture.failedFuture(new SecurityException("聊天缺少原账号归属身份"));
        }
        long nowNanos = nanoClock.getAsLong();
        WorldSnapshot snap = worldSnapshot;
        if (snap != null && nowNanos - snap.fetchedAtNanos() < worldHistoryCacheNanos) {
            return CompletableFuture.completedFuture(slice(snap.messages(), boundedLimit));
        }
        CompletableFuture<List<ChatMessage>> shared;
        boolean leader = false;
        long epoch;
        synchronized (worldCacheLock) {
            snap = worldSnapshot;
            if (snap != null && nanoClock.getAsLong() - snap.fetchedAtNanos() < worldHistoryCacheNanos) {
                return CompletableFuture.completedFuture(slice(snap.messages(), boundedLimit));
            }
            if (worldRefreshInFlight != null) {
                shared = worldRefreshInFlight;
                epoch = worldCacheEpoch;
            } else {
                shared = new CompletableFuture<>();
                worldRefreshInFlight = shared;
                epoch = worldCacheEpoch;
                leader = true;
            }
        }
        if (leader) {
            int fullWindow = Math.min(ChatProperties.MAX_PULL_MESSAGES, historyMax);
            authority.executeInternal(deadline, tx -> pullInTransaction(tx, original.getPlayerId(), 0L, fullWindow))
                    .whenComplete((loaded, err) -> {
                        synchronized (worldCacheLock) {
                            if (worldRefreshInFlight == shared) {
                                worldRefreshInFlight = null;
                            }
                            if (err == null && loaded != null && worldCacheEpoch == epoch) {
                                worldSnapshot = new WorldSnapshot(loaded, nanoClock.getAsLong());
                            }
                        }
                        if (err != null) {
                            shared.completeExceptionally(err);
                        } else {
                            shared.complete(loaded);
                        }
                    });
        }
        return shared.thenApply(all -> slice(all, boundedLimit));
    }

    private static List<ChatMessage> slice(List<ChatMessage> messages, int limit) {
        return messages.size() <= limit ? messages : List.copyOf(messages.subList(0, limit));
    }

    private List<ChatMessage> pullInTransaction(AuthorityJdbc.Session transaction, long player, long peer, int boundedLimit)
            throws SQLException {
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
