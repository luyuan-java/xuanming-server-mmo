package com.game.team.push;

import com.game.common.deadline.Deadline;
import com.game.discovery.presence.PlayerPushes;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamEventS2C;
import com.game.proto.team.TeamEventType;
import com.game.proto.team.TeamIncomingInviteView;
import com.game.proto.team.TeamInviteS2C;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.proto.team.TeamView;
import com.game.team.metrics.TeamMetrics;
import com.game.team.metrics.TeamMetrics.PushKind;
import com.game.team.metrics.TeamMetrics.PushOutcome;
import com.game.team.presence.DisplayLoader;
import com.game.team.rules.Decision;
import com.game.team.rules.TeamRules;
import com.game.team.store.CommitResult;
import com.game.team.store.MembersChangedException;
import com.game.team.store.MembersSnapshot;
import com.game.team.store.TeamStore;
import com.game.team.view.MemberDisplay;
import com.game.team.view.TeamViews;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提交后的 S2C 推送（基线 go/match/internal/team/notify.go，team-spec §4.4–§4.6、§6.8；替代 Kafka gate-cmd，D6）。
 *
 * <p>契约：
 * <ul>
 *   <li>整批交给独立的 {@code team-push} 执行器异步执行，批内按落盘顺序<b>串行</b>（修复提交在前、主提交在后），整批一个独立预算
 *       （缺省 3 s，基线 pushBatchBudget），<b>不继承</b>请求的 Deadline；失败只记日志与指标，绝不影响 RPC 结果；</li>
 *   <li>推送至多一次（{@code PlayerPushes}）：不在线就不推，客户端靠 GetMyTeam / ListMyInvites 拉取自愈；</li>
 *   <li>每份视图的 epoch 只取自 S_COMMIT / S_READ_MEMBERS 的同源结果（{@link TeamViews}），每个收件人一份自己的视图
 *       （epoch 各不相同、申请与已发邀请只给队长），所以逐人推，<b>不能</b>用 {@code pushToPlayers} 广播同一份；</li>
 *   <li>RPC 调用者本人不收自己这次提交的快照与 INVITE_REVOKED（他的回包里已经是同一次提交构建的视图），被拒申请人也排除调用者；</li>
 *   <li>Java 不发 scene 刷新信号（D7：scene 进场时现读成员关系），所以基线 publish 的 healed 名单（只用于 scene 信号）在这里没有对应。</li>
 * </ul>
 * 执行器拒绝时整批放弃：按本批计划要推的种类逐条记 error 并打日志。批内一条推送超出批预算就记 error、不再发出（基线 ctx 过期后
 * PushToPlayer 立即失败）。结局映射：SENT → ok，OFFLINE → offline，GATE_UNREACHABLE / 异常 / 超时 → error。线程安全。
 */
public final class TeamPushes {

    private static final Logger log = LoggerFactory.getLogger(TeamPushes.class);

    /** 推一条 {@code MessageContent} 给一个玩家（生产 {@code PlayerPushes::pushToPlayer}）。 */
    @FunctionalInterface
    public interface Pusher {
        CompletionStage<PlayerPushes.Outcome> push(long playerId, MessageContent content);
    }

    /**
     * 三个推送消息号（取自 {@code MessageIdRegistry}，代码里不写数字）。
     *
     * @param snapshot 213 NotifyTeamSnapshot
     * @param invite   215 NotifyTeamInvite
     * @param event    203 NotifyTeamEvent
     */
    public record MessageIds(int snapshot, int invite, int event) {
    }

    /** 一条计划好的推送：收件人、种类，以及拿到展示缓存后生成的消息。 */
    record Planned(long playerId, PushKind kind, Function<Map<Long, MemberDisplay>, Message> message) {
    }

    /** 一次提交的推送计划（纯函数算出，不做 I/O）。 */
    record Plan(boolean needsDisplay, List<Planned> pushes) {
    }

    private final TeamStore store;
    private final DisplayLoader display;
    private final Pusher pusher;
    private final Executor executor;
    private final TeamMetrics metrics;
    private final long batchBudgetMillis;
    private final MessageIds messageIds;

    /**
     * @param store       S_READ_MEMBERS（MEMBER_ONLINE）
     * @param display     展示缓存（读 MySQL，所以执行器允许阻塞）
     * @param executor    {@code team-push} 执行器（有界；测试可传同步执行器）
     * @param batchBudget 一批推送的总预算（{@code xm.team.push-batch-budget}，缺省 3 s）
     */
    public TeamPushes(TeamStore store, DisplayLoader display, Pusher pusher, Executor executor, TeamMetrics metrics,
                      Duration batchBudget, MessageIds messageIds) {
        this.store = store;
        this.display = display;
        this.pusher = pusher;
        this.executor = executor;
        this.metrics = metrics;
        this.batchBudgetMillis = batchBudget.toMillis();
        this.messageIds = messageIds;
    }

    // ================================================================ 入口

    /**
     * 异步推送一批已落盘的提交（基线 notify.go:69-93 publish）。commits 按落盘顺序传入（修复提交在前、主提交在后）。
     *
     * @param caller RPC 调用者（排除在快照、INVITE_REVOKED、APPLICATION_REJECTED 之外）；0 = 不排除任何人（6.4 的开战结果推送）
     */
    public void publish(long caller, List<CommitResult> commits) {
        List<CommitResult> batch = commits == null ? List.of() : commits.stream().filter(Objects::nonNull).toList();
        if (batch.isEmpty()) {
            return;
        }
        // 预算从提交时刻起算（基线 goroutine 与 3 s ctx 在 publish 时就开始）：在执行器里排队超过预算的整批按 error 丢弃、不读 MySQL，
        // 不把过时的视图 / 邀请（客户端按 server_time_ms 推算过期）晚几分钟送达
        Deadline deadline = Deadline.after(batchBudgetMillis);
        try {
            executor.execute(() -> {
                if (deadline.expired()) {
                    dropStale(caller, batch);
                    return;
                }
                for (CommitResult c : batch) {
                    try {
                        pushCommit(deadline, caller, c);
                    } catch (RuntimeException e) {
                        log.error("[team] 推送一次提交时出错 team={} version={}", Long.toUnsignedString(c.teamId()),
                                Long.toUnsignedString(c.version()), e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            int n = countAsError(caller, batch);
            log.warn("[team] 推送执行器已满，放弃一批 {} 次提交的 {} 条推送 team={}", batch.size(), n,
                    Long.toUnsignedString(batch.get(batch.size() - 1).teamId()));
        }
    }

    private void dropStale(long caller, List<CommitResult> batch) {
        int n = countAsError(caller, batch);
        log.warn("[team] 一批 {} 次提交的 {} 条推送在推送执行器里排队超过预算，放弃 team={}", batch.size(), n,
                Long.toUnsignedString(batch.get(batch.size() - 1).teamId()));
    }

    /** 整批放弃：每条计划中的推送按其 kind 计一次 error（计划不做 I/O）；返回条数。 */
    private int countAsError(long caller, List<CommitResult> batch) {
        int n = 0;
        for (CommitResult c : batch) {
            for (Planned p : plan(caller, c).pushes()) {
                metrics.push(p.kind(), PushOutcome.ERROR);
                n++;
            }
        }
        return n;
    }

    /**
     * GetMyTeam{notify_online}：异步把当前视图推给其他在线队员（MEMBER_ONLINE，version 不变；基线 notify.go:95-102、:175-206）。
     *
     * @param knownMembers 回包视图里的成员 id（S_READ_MEMBERS 按它读；不等时用记录里的成员重读）
     */
    public void publishOnline(long caller, long teamId, Collection<Long> knownMembers) {
        List<Long> known = knownMembers == null ? List.of() : List.copyOf(knownMembers);
        Deadline deadline = Deadline.after(batchBudgetMillis);
        try {
            executor.execute(() -> {
                if (deadline.expired()) {
                    metrics.push(PushKind.SNAPSHOT, PushOutcome.ERROR);
                    log.warn("[team] 在线态刷新在推送执行器里排队超过预算，放弃 team={}", Long.toUnsignedString(teamId));
                    return;
                }
                try {
                    pushOnlineRefresh(deadline, caller, teamId, known);
                } catch (RuntimeException e) {
                    log.error("[team] 在线态刷新推送出错 team={}", Long.toUnsignedString(teamId), e);
                }
            });
        } catch (RejectedExecutionException e) {
            metrics.push(PushKind.SNAPSHOT, PushOutcome.ERROR);
            log.warn("[team] 推送执行器已满，放弃在线态刷新 team={}", Long.toUnsignedString(teamId));
        }
    }

    /**
     * 异步推一次不经提交的开战结果（基线 service.go:568-595）：按 S_READ_MEMBERS 给记录里索引 tid == 本队的<b>当前</b>成员（含发起人，
     * 不看是否在线——离线由推送结局 offline 表达）推当前视图，{@code match_state} 按锁此刻是否有效算，{@code actor_id = 0}。
     * 记录已不在或成员表持续变化时放弃，交给客户端拉取自愈。
     *
     * @param knownMembers 锁内名单（S_READ_MEMBERS 按它读；不等时用记录里的成员重读）
     * @param reason       MATCH_ENDED / MATCH_FAILED
     * @param tip          结果原因（可为 null）
     */
    public void publishMatchView(long teamId, Collection<Long> knownMembers, TeamChangeReason reason, TipInfoMessage tip) {
        List<Long> known = knownMembers == null ? List.of() : List.copyOf(knownMembers);
        Deadline deadline = Deadline.after(batchBudgetMillis);
        try {
            executor.execute(() -> {
                if (deadline.expired()) {
                    metrics.push(PushKind.SNAPSHOT, PushOutcome.ERROR);
                    log.warn("[team] 开战结果推送在推送执行器里排队超过预算，放弃 team={}", Long.toUnsignedString(teamId));
                    return;
                }
                try {
                    pushMatchView(deadline, teamId, known, reason, tip);
                } catch (RuntimeException e) {
                    log.error("[team] 开战结果推送出错 team={}", Long.toUnsignedString(teamId), e);
                }
            });
        } catch (RejectedExecutionException e) {
            metrics.push(PushKind.SNAPSHOT, PushOutcome.ERROR);
            log.warn("[team] 推送执行器已满，放弃开战结果推送 team={}", Long.toUnsignedString(teamId));
        }
    }

    // ================================================================ 提交推送

    /** 按 Decision 给各接收者推送（基线 notify.go:104-148 pushCommit）。 */
    void pushCommit(Deadline deadline, long caller, CommitResult c) {
        Plan plan = plan(caller, c);
        Map<Long, MemberDisplay> dc = plan.needsDisplay()
                ? display.load(TeamViews.rosterIds(c.decision().record()), deadline) : TeamViews.noDisplay();
        for (Planned p : plan.pushes()) {
            send(deadline, p.playerId(), p.kind(), p.message().apply(dc));
        }
    }

    /**
     * 一次提交要推哪些（纯函数）：
     * <ol>
     *   <li>213 快照：{@link #snapshotRecipients} 排除 caller、且能同源构建视图的人（{@code TeamSnapshotS2C{view, reason, actor}}，
     *       提交带 {@link CommitResult#pushTip()} 时每份都附上它——整队开战建票失败的 MATCH_FAILED）；</li>
     *   <li>215 邀请：{@code invitedPlayer ≠ 0} 且记录里有给他的未过期邀请（{@code TeamInviteS2C{invite, server_time_ms = 本轮 S_READ nowMs}}）；</li>
     *   <li>203 APPLICATION_REJECTED：被拒申请人（≠ caller），actor = 记录里的队长；</li>
     *   <li>203 INVITE_REVOKED：解散时未过期邀请的被邀请人（排除 caller），actor = 本次 actor。</li>
     * </ol>
     * 展示缓存只在记录存在且（有快照收件人或有被邀请人）时加载（基线 notify.go:108-111，收件人按排除 caller 之前算）。
     */
    static Plan plan(long caller, CommitResult c) {
        Decision d = c.decision();
        List<Long> recipients = snapshotRecipients(c);
        boolean needsDisplay = d.record() != null && (!recipients.isEmpty() || d.invitedPlayer() != 0);
        List<Planned> pushes = new ArrayList<>();
        for (long pid : recipients) {
            if (pid == caller || !TeamViews.commitViewable(c, pid)) {
                continue; // 调用者看回包；索引已指向别队（修复提交里被移出者）的不拼视图，交给他自己的拉取
            }
            pushes.add(new Planned(pid, PushKind.SNAPSHOT, dc -> {
                TeamView view = TeamViews.viewFromCommit(c, pid, dc).orElseThrow();
                return snapshot(view, d.reason(), d.actor(), c.pushTip());
            }));
        }
        long invited = d.invitedPlayer();
        if (invited != 0 && TeamViews.incomingInviteView(d.record(), invited, c.nowMs(), TeamViews.noDisplay()) != null) {
            pushes.add(new Planned(invited, PushKind.INVITE, dc -> {
                TeamIncomingInviteView invite = TeamViews.incomingInviteView(d.record(), invited, c.nowMs(), dc);
                return TeamInviteS2C.newBuilder().setInvite(invite).setServerTimeMs(c.nowMs()).build();
            }));
        }
        long rejected = d.rejectedApplicant();
        if (rejected != 0 && rejected != caller) {
            long leader = d.record() == null ? 0 : d.record().getLeaderId();
            TeamEventS2C event = TeamEventS2C.newBuilder().setType(TeamEventType.TEAM_EVENT_TYPE_APPLICATION_REJECTED)
                    .setTeamId(c.teamId()).setActorId(leader).build();
            pushes.add(new Planned(rejected, PushKind.EVENT, dc -> event));
        }
        for (long pid : TeamViews.uniqueIds(d.revokedInvitees())) {
            if (pid == caller) {
                continue;
            }
            TeamEventS2C event = TeamEventS2C.newBuilder().setType(TeamEventType.TEAM_EVENT_TYPE_INVITE_REVOKED)
                    .setTeamId(c.teamId()).setActorId(d.actor()).build();
            pushes.add(new Planned(pid, PushKind.EVENT, dc -> event));
        }
        return new Plan(needsDisplay, List.copyOf(pushes));
    }

    /**
     * 快照推送的接收者（调用者由调用方另行排除；基线 notify.go:150-173）：
     * <ul>
     *   <li>建队：无（回包即视图）；</li>
     *   <li>申请 / 邀请增减、过期清理：只推队长（申请与已发邀请只有队长可见；记录为 null 时无）；同一次提交若惰性转让了队长，成员都要知道，改推全员；</li>
     *   <li>其余（加入、离队、踢人、转让、解散、自愈、开战状态）：J ∪ K ∪ L 全员，移出者收到 team_id=0 的空视图。</li>
     * </ul>
     */
    public static List<Long> snapshotRecipients(CommitResult c) {
        Decision d = c.decision();
        if (!d.leaderOfflineTransferred()) {
            TeamChangeReason reason = d.reason();
            if (reason == TeamChangeReason.TEAM_CHANGE_REASON_CREATED) {
                return List.of();
            }
            if (reason == TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED
                    || reason == TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED) {
                return d.record() == null ? List.of() : List.of(d.record().getLeaderId());
            }
        }
        List<Long> all = new ArrayList<>(d.joined().size() + d.kept().size() + d.left().size());
        all.addAll(d.joined());
        all.addAll(d.kept());
        all.addAll(d.left());
        return TeamViews.uniqueIds(all);
    }

    // ================================================================ 在线态刷新

    /** 用 S_READ_MEMBERS 给 tid == 本队的其他在线队员推当前视图（基线 notify.go:175-206）。 */
    void pushOnlineRefresh(Deadline deadline, long caller, long teamId, List<Long> knownMembers) {
        MembersSnapshot snap = readMembers(deadline, teamId, knownMembers, "在线态刷新");
        if (snap == null) {
            return;
        }
        Map<Long, MemberDisplay> dc = display.load(TeamViews.rosterIds(snap.record()), deadline);
        for (long pid : TeamRules.memberIds(snap.record())) {
            MemberDisplay d = dc.get(pid);
            if (pid == caller || d == null || !d.online()) {
                continue;
            }
            Optional<TeamView> view = TeamViews.viewFromMembers(snap, pid, dc);
            if (view.isEmpty()) {
                continue; // 索引 tid ≠ 本队（已离队 / 索引缺失）：不推，交给他自己的拉取自愈
            }
            send(deadline, pid, PushKind.SNAPSHOT,
                    snapshot(view.get(), TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_ONLINE, caller, null));
        }
    }

    // ================================================================ 不经提交的开战结果

    /**
     * 不经提交的开战结果推送（基线 service.go:568-595 pushMatchView）。EndMatch 没有清锁提交（锁已被清 / 重新加锁 / 自然过期 / 截止）时，
     * 仍尽力给队员推一次当前视图与结果原因，保证客户端不停在 STARTING。
     */
    void pushMatchView(Deadline deadline, long teamId, List<Long> knownMembers, TeamChangeReason reason, TipInfoMessage tip) {
        MembersSnapshot snap = readMembers(deadline, teamId, knownMembers, "开战结果推送");
        if (snap == null) {
            return;
        }
        Map<Long, MemberDisplay> dc = display.load(TeamViews.rosterIds(snap.record()), deadline);
        for (long pid : TeamRules.memberIds(snap.record())) {
            Optional<TeamView> view = TeamViews.viewFromMembers(snap, pid, dc);
            if (view.isEmpty()) {
                continue; // 索引 tid ≠ 本队（已离队 / 索引缺失）：不推，交给他自己的拉取自愈
            }
            send(deadline, pid, PushKind.SNAPSHOT, snapshot(view.get(), reason, 0, tip));
        }
    }

    /**
     * S_READ_MEMBERS 加上推送路径的失败处理：成员表持续变化 → 记 {@code members_changed / skipped}；读失败 → 记 {@code snapshot / error}；
     * 记录已不存在 → 静默。这三种都返回 null（放弃这次推送，交给客户端拉取自愈）。
     */
    private MembersSnapshot readMembers(Deadline deadline, long teamId, List<Long> knownMembers, String what) {
        MembersSnapshot snap;
        try {
            snap = store.readMembers(teamId, knownMembers, deadline);
        } catch (MembersChangedException e) {
            metrics.push(PushKind.MEMBERS_CHANGED, PushOutcome.SKIPPED);
            return null;
        } catch (RuntimeException e) {
            log.error("[team] {}读成员失败 team={}: {}", what, Long.toUnsignedString(teamId), e.toString());
            metrics.push(PushKind.SNAPSHOT, PushOutcome.ERROR);
            return null;
        }
        return snap.record() == null ? null : snap;
    }

    /** 组一条 213：{@code tip} 为 null 时不设 {@code tip} 字段。 */
    private static TeamSnapshotS2C snapshot(TeamView view, TeamChangeReason reason, long actor, TipInfoMessage tip) {
        TeamSnapshotS2C.Builder b = TeamSnapshotS2C.newBuilder().setTeam(view).setReason(reason).setActorId(actor);
        if (tip != null) {
            b.setTip(tip);
        }
        return b.build();
    }

    // ================================================================ 单条推送

    /** 推一条 S2C 并记 {@code xm_team_pushes_total}；离线不算错误（基线 notify.go:208-220）。 */
    private void send(Deadline deadline, long playerId, PushKind kind, Message message) {
        if (deadline.expired()) {
            metrics.push(kind, PushOutcome.ERROR);
            log.warn("[team] 推送批预算已用完，跳过 kind={} player={}", kind, Long.toUnsignedString(playerId));
            return;
        }
        MessageContent content = MessageContent.newBuilder().setMessageId(messageIdOf(kind))
                .setSerializedMessage(message.toByteString()).build();
        PlayerPushes.Outcome outcome;
        try {
            outcome = deadline.await(pusher.push(playerId, content), "推送");
        } catch (RuntimeException e) {
            metrics.push(kind, PushOutcome.ERROR);
            log.warn("[team] 推送失败 kind={} player={} message_id={}: {}", kind, Long.toUnsignedString(playerId),
                    content.getMessageId(), e.toString());
            return;
        }
        PushOutcome mapped = outcome == null ? PushOutcome.ERROR : switch (outcome) {
            case SENT -> PushOutcome.OK;
            case OFFLINE -> PushOutcome.OFFLINE;
            case GATE_UNREACHABLE -> PushOutcome.ERROR;
        };
        metrics.push(kind, mapped);
        if (mapped == PushOutcome.ERROR) {
            log.warn("[team] 推送没有送达 gate kind={} player={} message_id={}", kind, Long.toUnsignedString(playerId),
                    content.getMessageId());
        }
    }

    private int messageIdOf(PushKind kind) {
        return switch (kind) {
            case SNAPSHOT, MEMBERS_CHANGED -> messageIds.snapshot();
            case INVITE -> messageIds.invite();
            case EVENT -> messageIds.event();
        };
    }
}
