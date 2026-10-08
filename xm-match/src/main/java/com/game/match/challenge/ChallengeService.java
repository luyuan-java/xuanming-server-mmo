package com.game.match.challenge;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.presence.PlayerPushes;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.challenge.ChallengeStore.ChallengeRecord;
import com.game.match.challenge.ChallengeStore.ConsumeResult;
import com.game.match.challenge.ChallengeStore.InviteResult;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.ChallengeResult;
import com.game.match.metrics.MatchMetrics.ChallengeStage;
import com.game.match.metrics.MatchMetrics.PushKind;
import com.game.match.port.PlayerPusher;
import com.game.match.port.PlayerStatusReader;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.MessageContent;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.RespondChallengeRequest;
import com.game.proto.match.RespondChallengeResponse;
import com.google.protobuf.MessageLite;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 切磋（场景发起 PK）：152 ChallengePlayer 与 151 RespondChallenge（match-spec §6；基线 {@code go/match/internal/logic/challengelogic.go}）。
 * 客户端可见行为逐条照搬——判定顺序、tip 码、{@code parameters[0]} 的中文串、156 / 154 的字段与先后——差别只在存储的原子性与时钟：
 * <ul>
 *   <li>发起（占坑 + 写记录）与消费（核对目标 + 删记录 + 摘占坑）各是一段原子脚本（{@link ChallengeStore}）。两条并发的接受只有一条拿到记录，
 *       另一条回 16012「切磋邀请已过期」，不会开两次 gather（修基线 G8，M17）。</li>
 *   <li>{@code expires_at_ms} 与过期判定都用 Redis 时间，不用本机时钟（M7）。</li>
 *   <li>身份只取 {@code SessionContext.player_id}，请求体里的 {@code player_id} 忽略（M3）；{@code challenger_name} 是会话里已认证的账号名
 *       （同基线「一期用账号名占位」）。</li>
 * </ul>
 *
 * <p><b>发起时只做咨询性检查，不冻结任何人</b>；应战时对双方的战斗锁再查一遍，冻结发生在之后的 gather（由 scene 的备战写锁保证不串局）。
 * 读战斗锁失败一律按「在战斗中」（基线如此，§12.1 第 8 条）。切磋不检查双方的排队票据，也不计分。
 *
 * <p><b>线程</b>：两个入口在 {@code match-worker} 上调用，阻塞等 Redis（至多到请求截止）。推送是异步的：156 的结果要决定应答，所以在工作线程上
 * 等它（截止内）；154 的结果只计数。给推送结果计数、gather 失败后再推 154 都在 {@code match-push} 执行器上做（推送的 stage 在 Redis 客户端线程上
 * 完成、gather 的 future 在 gather 的虚拟线程上完成，都不许在上面阻塞）。线程安全，无可变状态。
 */
public final class ChallengeService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeService.class);

    private final PlayerStatusReader players;
    private final ChallengeStore store;
    private final MatchIds ids;
    private final PlayerPusher pusher;
    private final GatherLauncher gather;
    private final MatchMetrics metrics;
    private final Executor pushExecutor;
    private final long challengeTtlMs;
    private final int inviteMessageId;
    private final int resultMessageId;
    private final Supplier<String> nonces;

    /**
     * @param pushExecutor    {@code match-push}（测试可传 {@code Runnable::run}）
     * @param challengeTtlMs  邀请的寿命（{@code xm.match.challenge-ttl}，缺省 60 s；客户端可见：156 的 {@code expires_at_ms}）
     * @param inviteMessageId 156（{@code MatchService.NotifyChallengeInvite} 的消息号，经 {@code MessageIdRegistry} 取）
     * @param resultMessageId 154（{@code MatchService.NotifyChallengeResult}）
     */
    public ChallengeService(PlayerStatusReader players, ChallengeStore store, MatchIds ids, PlayerPusher pusher, GatherLauncher gather,
                            MatchMetrics metrics, Executor pushExecutor, long challengeTtlMs, int inviteMessageId, int resultMessageId) {
        this(players, store, ids, pusher, gather, metrics, pushExecutor, challengeTtlMs, inviteMessageId, resultMessageId,
                () -> UUID.randomUUID().toString());
    }

    /** @param nonces 每个 151 请求的消费 nonce（生产是随机 UUID；测试注入固定值以模拟「同一请求的脚本被重发」） */
    ChallengeService(PlayerStatusReader players, ChallengeStore store, MatchIds ids, PlayerPusher pusher, GatherLauncher gather,
                     MatchMetrics metrics, Executor pushExecutor, long challengeTtlMs, int inviteMessageId, int resultMessageId,
                     Supplier<String> nonces) {
        this.players = Objects.requireNonNull(players, "players");
        this.store = Objects.requireNonNull(store, "store");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.pusher = Objects.requireNonNull(pusher, "pusher");
        this.gather = Objects.requireNonNull(gather, "gather");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.pushExecutor = Objects.requireNonNull(pushExecutor, "pushExecutor");
        if (challengeTtlMs < 1) {
            throw new IllegalArgumentException("切磋邀请的 TTL 必须 ≥ 1 ms: " + challengeTtlMs);
        }
        this.challengeTtlMs = challengeTtlMs;
        this.inviteMessageId = inviteMessageId;
        this.resultMessageId = resultMessageId;
        this.nonces = Objects.requireNonNull(nonces, "nonces");
    }

    // ================================================================ 152 ChallengePlayer

    /**
     * 发起切磋（§6.1 的 11 行，顺序即语义）。成功时目标收到 156，应答只带 {@code challenge_id}；任何拒绝都只带 {@code error_message}。
     * 永不抛依赖异常（故障都落在 16004 里）。
     */
    public ChallengePlayerResponse challenge(SessionContext session, ChallengePlayerRequest request, Deadline d) {
        long challengerId = session.getPlayerId();
        if (challengerId == 0) {
            return inviteRejected(ChallengeResult.INTERNAL, MatchTip.NO_IDENTITY);
        }
        long targetId = request.getTargetPlayerId();
        if (targetId == 0 || targetId == challengerId) {
            return inviteRejected(ChallengeResult.SELF, MatchTip.CHALLENGE_SELF);
        }
        // 咨询性检查双方的战斗锁（权威复查在应战时做）；读失败也算有
        if (lockedOrUnknown(challengerId, d, "发起者")) {
            return inviteRejected(ChallengeResult.SELF_BUSY, MatchTip.CHALLENGE_SELF_BUSY);
        }
        if (lockedOrUnknown(targetId, d, "目标")) {
            return inviteRejected(ChallengeResult.TARGET_BUSY, MatchTip.CHALLENGE_TARGET_BUSY);
        }
        // 目标在线（邀请弹窗要经 gate 推到目标客户端）：在线目录有条目 = 在游戏里
        Optional<PlayerPresence> presence;
        try {
            presence = players.presence(targetId, d);
        } catch (Deadline.DependencyException e) {
            log.error("[challenge] 读目标在线目录失败 target={}", id(targetId), e);
            return inviteRejected(ChallengeResult.INTERNAL, MatchTip.BUSY);
        }
        if (presence.isEmpty()) {
            return inviteRejected(ChallengeResult.TARGET_OFFLINE, MatchTip.CHALLENGE_TARGET_OFFLINE);
        }
        // challenge_id 与 battle_id 同源；发不出（租约无效 / 已丢失、时钟回拨）绝不拿别的号顶替
        OptionalLong issued = ids.nextChallengeId();
        if (issued.isEmpty()) {
            log.error("[challenge] challenge_id 发号失败（发号租约无效或时钟回拨） challenger={}", id(challengerId));
            return inviteRejected(ChallengeResult.INTERNAL, MatchTip.BUSY);
        }
        long challengeId = issued.getAsLong();
        int configId = request.getBattleConfigId();

        InviteResult invited;
        try {
            invited = store.invite(challengeId, challengerId, targetId, configId, challengeTtlMs, d);
        } catch (Deadline.DependencyException e) {
            // 结局不明：脚本可能已经占了坑。尽力清一次（只摘值等于本 id 的占坑），清不掉靠 TTL
            log.error("[challenge] 写切磋记录失败 challenge={} target={}", id(challengeId), id(targetId), e);
            cleanup(challengeId, targetId, d);
            return inviteRejected(ChallengeResult.INTERNAL, MatchTip.BUSY);
        }
        if (!(invited instanceof InviteResult.Created created)) {
            return inviteRejected(ChallengeResult.PENDING, MatchTip.CHALLENGE_PENDING);
        }

        ChallengeInviteS2C invite = ChallengeInviteS2C.newBuilder()
                .setChallengeId(challengeId)
                .setChallengerId(challengerId)
                .setChallengerName(session.getAccount())
                .setBattleConfigId(configId)
                .setExpiresAtMs(created.expiresAtMs())
                .build();
        PlayerPushes.Outcome pushed;
        try {
            pushed = d.await(push(PushKind.INVITE, targetId, inviteMessageId, invite, challengeId), "推切磋邀请");
        } catch (Deadline.DependencyException e) {
            pushed = null; // 推送异常完成 / 截止内没有结果：与目标刚好下线走同一个出口（原因已在推送回调里记过日志）
        }
        if (pushed != PlayerPushes.Outcome.SENT) {
            log.warn("[challenge] 邀请没推到，清理记录 challenge={} target={} outcome={}", id(challengeId), id(targetId),
                    pushed == null ? "error" : pushed);
            cleanup(challengeId, targetId, d);
            return inviteRejected(ChallengeResult.PUSH_FAILED, MatchTip.CHALLENGE_INVITE_PUSH_FAILED);
        }

        log.info("[challenge] 挑战已发起 challenge={} challenger={} target={} config={}", id(challengeId), id(challengerId), id(targetId),
                Integer.toUnsignedString(configId));
        metrics.challenge(ChallengeStage.INVITE, ChallengeResult.OK);
        return MatchTips.challengeSent(challengeId);
    }

    // ================================================================ 151 RespondChallenge

    /**
     * 应战 / 拒战（§6.1 的 10 行）。拒绝邀请、或接受并已进入开局管线时应答是空消息；其余带 {@code error_message}。
     * 记录一经消费就作废：之后无论过期、拒绝、接受、还是双方有人在战斗中，这条邀请都不能再用。永不抛依赖异常。
     */
    public RespondChallengeResponse respond(SessionContext session, RespondChallengeRequest request, Deadline d) {
        long responderId = session.getPlayerId();
        if (responderId == 0) {
            return respondRejected(ChallengeResult.INTERNAL, MatchTip.NO_IDENTITY);
        }
        long challengeId = request.getChallengeId();

        // 先读一次，按基线的顺序回码（读失败 → 不存在 → 不是目标），再原子消费
        Optional<ChallengeRecord> seen;
        try {
            seen = store.read(challengeId, d);
        } catch (Deadline.DependencyException e) {
            log.error("[challenge] 读切磋记录失败 challenge={}", id(challengeId), e);
            return respondRejected(ChallengeResult.INTERNAL, MatchTip.BUSY);
        }
        if (seen.isEmpty()) {
            return respondRejected(ChallengeResult.EXPIRED, MatchTip.CHALLENGE_EXPIRED);
        }
        if (seen.get().targetId() != responderId) {
            return respondRejected(ChallengeResult.NOT_TARGET, MatchTip.CHALLENGE_NOT_TARGET);
        }

        ConsumeResult consumed;
        try {
            consumed = store.consume(challengeId, responderId, nonces.get(), d);
        } catch (Deadline.DependencyException e) {
            // 结局不明：不知道自己是不是唯一的消费者，不能往下开局
            log.error("[challenge] 消费切磋记录失败 challenge={} responder={}", id(challengeId), id(responderId), e);
            return respondRejected(ChallengeResult.INTERNAL, MatchTip.BUSY);
        }
        if (consumed instanceof ConsumeResult.NotTarget) {
            return respondRejected(ChallengeResult.NOT_TARGET, MatchTip.CHALLENGE_NOT_TARGET);
        }
        if (!(consumed instanceof ConsumeResult.Consumed taken)) {
            // 读到之后被并发的另一条应答消费了，或刚好过期
            return respondRejected(ChallengeResult.EXPIRED, MatchTip.CHALLENGE_EXPIRED);
        }
        ChallengeRecord record = taken.record();
        long challengerId = record.challengerId();
        if (record.expiresAtMs() != 0 && Long.compareUnsigned(taken.redisNowMs(), record.expiresAtMs()) >= 0) {
            return respondRejected(ChallengeResult.EXPIRED, MatchTip.CHALLENGE_EXPIRED);
        }

        // 拒战：只通知发起者
        if (!request.getAccept()) {
            pushResult(challengerId, challengeId, false, responderId);
            log.info("[challenge] 已拒绝 challenge={} challenger={} responder={}", id(challengeId), id(challengerId), id(responderId));
            metrics.challenge(ChallengeStage.RESPOND, ChallengeResult.DECLINED);
            return RespondChallengeResponse.getDefaultInstance();
        }

        // 应战：复查双方的战斗锁（发起之后任何一方都可能已经开战）；读失败按在战斗中
        if (lockedOrUnknown(challengerId, d, "发起者")) {
            pushResult(challengerId, challengeId, false, responderId);
            return respondRejected(ChallengeResult.CHALLENGER_BUSY, MatchTip.CHALLENGE_CHALLENGER_BUSY);
        }
        if (lockedOrUnknown(responderId, d, "应战者")) {
            pushResult(challengerId, challengeId, false, responderId);
            return respondRejected(ChallengeResult.RESPONDER_BUSY, MatchTip.CHALLENGE_RESPONDER_BUSY);
        }

        // 先给发起者、再给应答者推成局通知，然后才进开局管线（客户端可见的先后：154 先于 177）。只发起、不等它们完成；
        // 两条的 future 留给开局失败时用：补推的 154 false 必须排在它们之后
        CompletableFuture<PlayerPushes.Outcome> acceptedToChallenger = pushResult(challengerId, challengeId, true, responderId);
        CompletableFuture<PlayerPushes.Outcome> acceptedToResponder = pushResult(responderId, challengeId, true, responderId);
        launchGather(challengeId, record.configId(), challengerId, responderId,
                CompletableFuture.allOf(acceptedToChallenger, acceptedToResponder));

        log.info("[challenge] 已应战，进入开局管线 challenge={} challenger={} responder={} config={}", id(challengeId), id(challengerId),
                id(responderId), Integer.toUnsignedString(record.configId()));
        metrics.challenge(ChallengeStage.RESPOND, ChallengeResult.ACCEPTED);
        return RespondChallengeResponse.getDefaultInstance();
    }

    // ================================================================ 内部

    /**
     * 异步开局（名单 [发起者, 应战者]，不带票据）；失败时已冻结的人已由管线解冻，这里只能事后给双方各再推一次 154 false。
     *
     * <p><b>154 false 排在两条 154 true 之后</b>（§8.3「gather 失败再各推一次 false」；基线的推送是同步的，天然有序）：每条推送都是一条独立的
     * 「异步查在线目录 + 异步发布」链，彼此没有先后保证；而 gather 可以不经任何 I/O 就失败（发号租约无效、在途许可用完、名单不合法），
     * 那时 false 只比 true 晚几百微秒发起，true 的命令只要有一条被 Redis 客户端重发，false 就先到 gate——客户端最后看到的是 accepted = true，
     * 却永远等不到 177。所以失败回调先等 {@code acceptedPushes} 结束（成功、不在线、异常完成都算结束；它在 Redis 应答之后才结束），
     * 再在 {@code match-push} 上推 false。只是在 future 上接续，不阻塞任何线程；等待以 Redis 客户端的命令超时为界。
     *
     * @param acceptedPushes 两条 154 true 都有了结局（可能是异常完成）
     */
    private void launchGather(long challengeId, int configId, long challengerId, long responderId, CompletableFuture<Void> acceptedPushes) {
        CompletableFuture<GatherResult> launched;
        try {
            launched = gather.launch(GatherPlan.challenge(configId, challengerId, responderId));
        } catch (RuntimeException e) {
            // 记录损坏（发起者为 0 / 与应战者相同）才会到这里：名单不合法，当作开局失败
            launched = CompletableFuture.failedFuture(e);
        }
        // 这个回调在 gather 的线程（或已完成时的调用线程）上，只做接续；日志与推送都切到 match-push
        launched.whenComplete((result, error) -> {
            if (error == null && result != null && result.ok()) {
                return;
            }
            // whenCompleteAsync：154 true 推送异常完成时回调照样执行（不能用 thenRun，那样推送一出错 false 就发不出去了）
            acceptedPushes.whenCompleteAsync((ignored, pushError) -> {
                log.error("[challenge] 应战后开局失败 challenge={} challenger={} responder={} outcome={}", id(challengeId), id(challengerId),
                        id(responderId), result == null ? "error" : result.outcome().label(), error);
                pushResult(challengerId, challengeId, false, responderId);
                pushResult(responderId, challengeId, false, responderId);
            }, pushExecutor);
        });
    }

    /**
     * 推一条 154（尽力而为：结果只计数，不影响应答）。
     *
     * @return 这条推送有了结局之后完成（计数已做；推送异常时异常完成）。只有「接受」的两条有人用它排序，其余调用点直接丢弃
     */
    private CompletableFuture<PlayerPushes.Outcome> pushResult(long toPlayerId, long challengeId, boolean accepted, long responderId) {
        ChallengeResultS2C result = ChallengeResultS2C.newBuilder().setChallengeId(challengeId).setAccepted(accepted).setResponderId(responderId).build();
        return push(PushKind.RESULT, toPlayerId, resultMessageId, result, challengeId);
    }

    /**
     * 推一条消息并给结果计数（计数与日志在 {@code match-push} 上）。返回的 future 在计数之后以推送的原结果完成；推送异常时异常完成。
     */
    private CompletableFuture<PlayerPushes.Outcome> push(PushKind kind, long toPlayerId, int messageId, MessageLite body, long challengeId) {
        CompletionStage<PlayerPushes.Outcome> stage;
        try {
            stage = pusher.push(toPlayerId, MessageContent.newBuilder().setMessageId(messageId).setSerializedMessage(body.toByteString()).build());
            if (stage == null) {
                stage = CompletableFuture.failedFuture(new IllegalStateException("推送没有返回 stage"));
            }
        } catch (RuntimeException e) {
            stage = CompletableFuture.failedFuture(e);
        }
        return stage.whenCompleteAsync((outcome, error) -> {
            if (error != null || outcome == null) {
                metrics.pushError(kind);
                log.error("[challenge] 推送失败 message_id={} player={} challenge={}", messageId, id(toPlayerId), id(challengeId), error);
                return;
            }
            metrics.push(kind, outcome);
            if (outcome == PlayerPushes.Outcome.OFFLINE) {
                log.info("[challenge] 推送目标不在线，放弃 message_id={} player={} challenge={}", messageId, id(toPlayerId), id(challengeId));
            } else if (outcome != PlayerPushes.Outcome.SENT) {
                log.error("[challenge] 推送没人收（gate 不可达） message_id={} player={} challenge={}", messageId, id(toPlayerId), id(challengeId));
            }
        }, pushExecutor).toCompletableFuture();
    }

    /** 战斗锁在不在；读失败也算在（切磋的口径，基线 {@code chl.go:70-88}、{@code :247-266}）。 */
    private boolean lockedOrUnknown(long playerId, Deadline d, String who) {
        try {
            return players.inBattle(playerId, d);
        } catch (Deadline.DependencyException e) {
            log.error("[challenge] 查{}战斗锁失败，按在战斗中处理 player={}", who, id(playerId), e);
            return true;
        }
    }

    /** 清理记录与占坑（尽力而为，用请求剩余的预算）：清不掉只影响 TTL 之内对同一目标再次发起。 */
    private void cleanup(long challengeId, long targetId, Deadline d) {
        try {
            store.delete(challengeId, targetId, d);
        } catch (Deadline.DependencyException e) {
            log.error("[challenge] 清理切磋记录失败（靠 TTL 自愈） challenge={} target={}", id(challengeId), id(targetId), e);
        }
    }

    private ChallengePlayerResponse inviteRejected(ChallengeResult result, MatchTip tip) {
        metrics.challenge(ChallengeStage.INVITE, result);
        return MatchTips.challengeRejected(tip);
    }

    private RespondChallengeResponse respondRejected(ChallengeResult result, MatchTip tip) {
        metrics.challenge(ChallengeStage.RESPOND, result);
        return MatchTips.respondRejected(tip);
    }

    private static String id(long value) {
        return Long.toUnsignedString(value);
    }
}
