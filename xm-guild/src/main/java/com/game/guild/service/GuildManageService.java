package com.game.guild.service;

import com.game.common.deadline.Deadline;
import com.game.guild.cache.GuildCache;
import com.game.guild.cache.pb.GuildSnapshot;
import com.game.guild.push.GuildPushes;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTableRules.ApplicationRules;
import com.game.guild.rules.GuildTip;
import com.game.guild.service.GuildAccess.Membership;
import com.game.guild.service.GuildAccess.ZoneCheck;
import com.game.guild.service.GuildViews.MyApplication;
import com.game.guild.store.GuildStore;
import com.game.guild.store.GuildStore.Applied;
import com.game.guild.store.GuildStore.ApplicationRow;
import com.game.guild.store.GuildStore.Kicked;
import com.game.guild.store.GuildStore.Reviewed;
import com.game.guild.store.GuildStore.RoleChanged;
import com.game.guild.store.GuildStore.Transferred;
import com.game.guild.store.TxOutcome;
import com.game.proto.guild.ApplyJoinGuildRequest;
import com.game.proto.guild.ApplyJoinGuildResponse;
import com.game.proto.guild.CancelGuildApplicationRequest;
import com.game.proto.guild.CancelGuildApplicationResponse;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.KickGuildMemberRequest;
import com.game.proto.guild.KickGuildMemberResponse;
import com.game.proto.guild.ListGuildApplicationsRequest;
import com.game.proto.guild.ListGuildApplicationsResponse;
import com.game.proto.guild.ListMyGuildApplicationsRequest;
import com.game.proto.guild.ListMyGuildApplicationsResponse;
import com.game.proto.guild.ReviewGuildApplicationRequest;
import com.game.proto.guild.ReviewGuildApplicationResponse;
import com.game.proto.guild.SetGuildMemberRoleRequest;
import com.game.proto.guild.SetGuildMemberRoleResponse;
import com.game.proto.guild.TransferGuildLeaderRequest;
import com.game.proto.guild.TransferGuildLeaderResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 成员管理与入帮申请（基线 guild_manage_logic.go:369-762；guild-spec §3.8–§3.15）：任免 / 踢人 / 转让 / 申请 / 撤回 / 本人申请列表 /
 * 待审名单 / 审批。这些请求体里<b>没有</b> player_id（协议刻意不放），操作者一律取会话。
 *
 * <p>前置顺序即错误优先级，各方法逐条照基线（§3）；检查归属区与闸门的范围<b>不对称</b>，必须照抄（§9.2 第 9 条）：
 * ListGuildApplications 两者都不查；ListMyGuildApplications 查归属区不查闸门；审批拒绝也查审批人的区与闸门。
 * 写回包一律用事务内快照（含操作者本人，客户端 {@code Apply(GuildInfo)} 没有本人就判无效）。
 *
 * <p>全部方法阻塞，只在 guild-worker 线程上调用。线程安全。
 */
public final class GuildManageService {

    private static final Logger log = LoggerFactory.getLogger(GuildManageService.class);

    /**
     * 新建的申请要不要推给审批人（基线 ApplyPushGate / TryMarkApplyPush，guild_manage_repo.go:679-700）：同一（帮, 申请人）60 s 至多一次；
     * Redis 出错也不推（N3）。生产 {@code ApplyPushCooldown::tryMark}。
     */
    @FunctionalInterface
    public interface ApplyPushGate {
        boolean tryMark(long guildId, long playerId, Deadline deadline);
    }

    private final GuildStore store;
    private final GuildCache cache;
    private final GuildAccess access;
    private final GuildViews views;
    private final GuildPushes pushes;
    private final ApplyPushGate applyPushGate;
    private final GuildTableLookup tables;
    private final LongSupplier clockMs;

    public GuildManageService(GuildStore store, GuildCache cache, GuildAccess access, GuildViews views, GuildPushes pushes,
                              ApplyPushGate applyPushGate, GuildTableLookup tables, LongSupplier clockMs) {
        this.store = store;
        this.cache = cache;
        this.access = access;
        this.views = views;
        this.pushes = pushes;
        this.applyPushGate = applyPushGate;
        this.tables = tables;
        this.clockMs = clockMs;
    }

    // ================================================================ SetGuildMemberRole（19）

    /**
     * 任免（guild_manage_logic.go:369-401；§3.8）：clientWrite → 目标校验（14014 / 14015）→ role 只许 0 / 1（否则 14006「role not assignable」，
     * 设 3 与 2 都拒）→ operatorGuild → 事务。目标已是该角色 → 成功、不写库、不失效、不推送；确有变化才推 ROLE_CHANGED 给快照除帮主。
     */
    public SetGuildMemberRoleResponse setGuildMemberRole(long me, SetGuildMemberRoleRequest request, Deadline deadline) {
        ZoneCheck zone = access.clientWrite(me, deadline);
        if (zone.rejected()) {
            return roleTip(zone.tip());
        }
        long target = request.getTargetPlayerId();
        GuildTip targetTip = GuildRoles.targetTip(me, target);
        if (targetTip != null) {
            return roleTip(targetTip);
        }
        // 帮主只能经转让产生：放行 3 等于开了「自己升自己」的后门；2 是刻意跳过的空号
        if (!GuildRoles.assignableRole(request.getRole())) {
            return roleTip(GuildTip.ROLE_NOT_ASSIGNABLE);
        }
        Membership membership = access.operatorGuild(me, deadline);
        if (membership.rejected()) {
            return roleTip(membership.tip());
        }
        long guildId = membership.guildId();
        TxOutcome<RoleChanged> outcome = store.setMemberRole(guildId, me, target, request.getRole(), tables::officerCap,
                deadline);
        if (!(outcome instanceof TxOutcome.Ok<RoleChanged> ok)) {
            return roleTip(access.replyFor(me, guildId, guildId, outcome.rejection(), deadline));
        }
        RoleChanged changed = ok.value();
        access.invalidate(changed.invalidation(), deadline);
        if (changed.changed()) {
            pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_ROLE_CHANGED, guildId, me, target, changed.pushRecipients());
        }
        return SetGuildMemberRoleResponse.newBuilder().setGuild(views.guildInfo(changed.guild(), me, deadline)).build();
    }

    private static SetGuildMemberRoleResponse roleTip(GuildTip tip) {
        return SetGuildMemberRoleResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ================================================================ KickGuildMember（217）

    /**
     * 踢人（guild_manage_logic.go:403-431；§3.9）：clientWrite → 目标校验 → operatorGuild → 事务。推 MEMBER_KICKED 给快照除操作者，
     * <b>再加被踢者本人</b>（否则他的界面会一直停在「我还在帮里」）。重踢已离开的人 → 14014。
     */
    public KickGuildMemberResponse kickGuildMember(long me, KickGuildMemberRequest request, Deadline deadline) {
        ZoneCheck zone = access.clientWrite(me, deadline);
        if (zone.rejected()) {
            return kickTip(zone.tip());
        }
        long target = request.getTargetPlayerId();
        GuildTip targetTip = GuildRoles.targetTip(me, target);
        if (targetTip != null) {
            return kickTip(targetTip);
        }
        Membership membership = access.operatorGuild(me, deadline);
        if (membership.rejected()) {
            return kickTip(membership.tip());
        }
        long guildId = membership.guildId();
        TxOutcome<Kicked> outcome = store.kickMember(guildId, me, target, clockMs.getAsLong(), deadline);
        if (!(outcome instanceof TxOutcome.Ok<Kicked> ok)) {
            return kickTip(access.replyFor(me, guildId, guildId, outcome.rejection(), deadline));
        }
        Kicked kicked = ok.value();
        access.invalidate(kicked.invalidation(), deadline);
        pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_KICKED, guildId, me, target, kicked.pushRecipients());
        return KickGuildMemberResponse.newBuilder().setGuild(views.guildInfo(kicked.guild(), me, deadline)).build();
    }

    private static KickGuildMemberResponse kickTip(GuildTip tip) {
        return KickGuildMemberResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ================================================================ TransferGuildLeader（216）

    /**
     * 转让帮主（guild_manage_logic.go:433-457；§3.10）：clientWrite → 目标校验 → operatorGuild → 事务（原帮主按长老位余量降为长老或成员）。
     * 推 LEADER_TRANSFERRED 给快照除原帮主；回包里 actor 已是长老或成员（是长老才看得到待审数）。重放 → 14016。
     */
    public TransferGuildLeaderResponse transferGuildLeader(long me, TransferGuildLeaderRequest request, Deadline deadline) {
        ZoneCheck zone = access.clientWrite(me, deadline);
        if (zone.rejected()) {
            return transferTip(zone.tip());
        }
        long target = request.getTargetPlayerId();
        GuildTip targetTip = GuildRoles.targetTip(me, target);
        if (targetTip != null) {
            return transferTip(targetTip);
        }
        Membership membership = access.operatorGuild(me, deadline);
        if (membership.rejected()) {
            return transferTip(membership.tip());
        }
        long guildId = membership.guildId();
        TxOutcome<Transferred> outcome = store.transferLeader(guildId, me, target, tables::officerCap, deadline);
        if (!(outcome instanceof TxOutcome.Ok<Transferred> ok)) {
            return transferTip(access.replyFor(me, guildId, guildId, outcome.rejection(), deadline));
        }
        Transferred transferred = ok.value();
        access.invalidate(transferred.invalidation(), deadline);
        pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_LEADER_TRANSFERRED, guildId, me, target,
                transferred.pushRecipients());
        return TransferGuildLeaderResponse.newBuilder().setGuild(views.guildInfo(transferred.guild(), me, deadline))
                .build();
    }

    private static TransferGuildLeaderResponse transferTip(GuildTip tip) {
        return TransferGuildLeaderResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ================================================================ ApplyJoinGuild（218）

    /**
     * 申请入帮（guild_manage_logic.go:459-505；§3.11）：clientWrite（拿到归属区）→ guild_id = 0 → 14001「guild id is zero」→ 已在帮预检
     * （14000）→ GuildRule[1]（缺行 → 故障）→ 事务（同帮重复申请 = 刷新有效期并成功）。映射自愈的比对基准是 0（申请人按定义不在目标帮）。
     * <b>只有新建行</b>、且冷却键拿到时才推 APPLICATION_RECEIVED（actor = target = 申请人）给事务内读到的长老与帮主。
     */
    public ApplyJoinGuildResponse applyJoinGuild(long me, ApplyJoinGuildRequest request, Deadline deadline) {
        ZoneCheck zone = access.clientWrite(me, deadline);
        if (zone.rejected()) {
            return applyTip(zone.tip());
        }
        long guildId = request.getGuildId();
        if (guildId == 0) {
            return applyTip(GuildTip.APPLY_GUILD_ID_ZERO);
        }
        if (access.alreadyInGuild(me, deadline)) {
            return applyTip(GuildTip.ALREADY_IN_GUILD);
        }
        ApplicationRules rules = requireApplicationRules();
        TxOutcome<Applied> outcome = store.applyToGuild(guildId, me, zone.zoneId(), clockMs.getAsLong(), rules, deadline);
        if (!(outcome instanceof TxOutcome.Ok<Applied> ok)) {
            return applyTip(access.replyFor(me, guildId, 0, outcome.rejection(), deadline));
        }
        Applied applied = ok.value();
        // 申请不失效任何缓存（pending_application_count 不进缓存）；刷新有效期对审批人来说什么都没变，不推
        if (applied.inserted() && applyPushGate.tryMark(guildId, me, deadline)) {
            pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_APPLICATION_RECEIVED, guildId, me, me, applied.reviewerIds());
        }
        return ApplyJoinGuildResponse.getDefaultInstance();
    }

    private static ApplyJoinGuildResponse applyTip(GuildTip tip) {
        return ApplyJoinGuildResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ================================================================ CancelGuildApplication（219）

    /**
     * 撤回申请（guild_manage_logic.go:507-530；§3.12）：clientWrite → guild_id = 0 → 14018「guild id is zero」→ 事务。从不推送；重复撤回 → 14018。
     */
    public CancelGuildApplicationResponse cancelGuildApplication(long me, CancelGuildApplicationRequest request,
                                                                 Deadline deadline) {
        ZoneCheck zone = access.clientWrite(me, deadline);
        if (zone.rejected()) {
            return cancelTip(zone.tip());
        }
        long guildId = request.getGuildId();
        if (guildId == 0) {
            return cancelTip(GuildTip.CANCEL_GUILD_ID_ZERO);
        }
        TxOutcome<Void> outcome = store.cancelApplication(guildId, me, clockMs.getAsLong(), deadline);
        if (!outcome.isOk()) {
            return cancelTip(access.replyFor(me, guildId, 0, outcome.rejection(), deadline));
        }
        return CancelGuildApplicationResponse.getDefaultInstance();
    }

    private static CancelGuildApplicationResponse cancelTip(GuildTip tip) {
        return CancelGuildApplicationResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ================================================================ ListMyGuildApplications（222）

    /**
     * 本人的申请列表（guild_manage_logic.go:534-614；§3.13）：查归属区<b>不查闸门</b>（合服窗口里看一眼自己的申请是安全的）→ 已在帮
     * （缓存 &gt; 0 且复核 &gt; 0）→ 成功并回空列表 → 未过期申请至多 10 条 → 每行读一次帮会（<b>任一行出错 → 整体故障</b>；不存在或不在本区 → 跳过）
     * → 帮主名一次批量回填。
     */
    public ListMyGuildApplicationsResponse listMyGuildApplications(long me, ListMyGuildApplicationsRequest request,
                                                                   Deadline deadline) {
        ZoneCheck zone = access.clientZone(me, deadline);
        if (zone.rejected()) {
            return ListMyGuildApplicationsResponse.newBuilder().setErrorMessage(zone.tip().proto()).build();
        }
        if (access.alreadyInGuild(me, deadline)) {
            return ListMyGuildApplicationsResponse.getDefaultInstance();
        }
        List<ApplicationRow> rows = store.listMyApplications(me, clockMs.getAsLong(), GuildLimits.MY_APPLICATIONS_LIMIT,
                deadline);
        List<MyApplication> visible = new ArrayList<>(rows.size());
        for (ApplicationRow row : rows) {
            // 读不出来就整体失败：静默跳过会让玩家看到一份「少了几行」的列表
            Optional<GuildSnapshot> guild = cache.guild(row.guildId(), deadline);
            if (guild.isEmpty() || guild.get().getZoneId() != zone.zoneId()) {
                continue; // 已解散，或回滚合服留下的跨区残留行（最多存在一个有效期）
            }
            visible.add(new MyApplication(row, GuildSnapshots.toData(guild.get())));
        }
        return ListMyGuildApplicationsResponse.newBuilder()
                .addAllApplications(views.applicationViews(visible, deadline)).build();
    }

    // ================================================================ ListGuildApplications（221）

    /**
     * 待审名单（guild_manage_logic.go:620-656；§3.14）：<b>不查归属区、不查闸门</b> → operatorGuild（14002）→ 非锁定读 MySQL 权威 role：
     * 不是成员 → 自愈映射后 14002「not a member of the guild」；不到长老 → 14016「officer rank required」→ GuildRule[1]（缺行 → 故障）→
     * 有效申请（I1 过滤）至多 max_pending_applications_per_guild 条 → 在线一次 + 名字一次。
     */
    public ListGuildApplicationsResponse listGuildApplications(long me, ListGuildApplicationsRequest request,
                                                               Deadline deadline) {
        Membership membership = access.operatorGuild(me, deadline);
        if (membership.rejected()) {
            return ListGuildApplicationsResponse.newBuilder().setErrorMessage(membership.tip().proto()).build();
        }
        long guildId = membership.guildId();
        // 授权读 MySQL 权威 role（非锁定读）：缓存里的 role 在降权后会说谎整整一个 TTL
        OptionalInt role = store.memberRole(guildId, me, deadline);
        if (role.isEmpty()) {
            access.verifyMapping(me, guildId, deadline);
            return ListGuildApplicationsResponse.newBuilder().setErrorMessage(GuildTip.NOT_A_MEMBER.proto()).build();
        }
        if (!GuildRoles.canReviewApplications(role.getAsInt())) {
            return ListGuildApplicationsResponse.newBuilder().setErrorMessage(GuildTip.OFFICER_RANK_REQUIRED.proto()).build();
        }
        ApplicationRules rules = requireApplicationRules();
        return ListGuildApplicationsResponse.newBuilder()
                .addAllApplicants(views.applicantViews(
                        store.listApplicants(guildId, clockMs.getAsLong(), rules.maxPerGuild(), deadline), deadline))
                .build();
    }

    // ================================================================ ReviewGuildApplication（223）

    /**
     * 审批（guild_manage_logic.go:689-762；§3.15、§7.10）：申请人 = 0 → 14018 → 审批自己 → 14015 → 归属区：通过时审批人与申请人<b>一条 IN 查询</b>，
     * 拒绝只查审批人（查询出错 → 故障）→ 审批人归属区未知 → 14012 → 闸门（审批人的区）→ 通过时申请人归属区未知 → 14018
     * 「applicant home zone unknown」→ operatorGuild → 事务。通过 → MEMBER_JOINED 给快照（含新成员）除审批人；拒绝 → APPLICATION_REJECTED
     * 只推申请人。通过与拒绝都回快照。
     */
    public ReviewGuildApplicationResponse reviewGuildApplication(long me, ReviewGuildApplicationRequest request,
                                                                 Deadline deadline) {
        long applicant = request.getApplicantPlayerId();
        if (applicant == 0) {
            return reviewTip(GuildTip.APPLICANT_ID_ZERO);
        }
        if (applicant == me) {
            return reviewTip(GuildTip.CANNOT_REVIEW_OWN);
        }
        boolean approve = request.getApprove();
        // 拒绝分支不查申请人的区：删掉一条申请在任何 zone 下都是安全的
        Map<Long, Integer> zones = access.homeZonesOf(approve ? List.of(me, applicant) : List.of(me), deadline);
        ZoneCheck mine = access.zoneOf(zones, me);
        if (mine.rejected()) {
            return reviewTip(mine.tip());
        }
        GuildTip fenced = access.mergeFenceTip(mine.zoneId());
        if (fenced != null) {
            return reviewTip(fenced);
        }
        int applicantZone = 0;
        if (approve) {
            Integer zone = zones.get(applicant);
            if (zone == null || zone == 0) {
                log.info("[guild] review refused: applicant {} has no home zone mapping", Long.toUnsignedString(applicant));
                return reviewTip(GuildTip.APPLICANT_HOME_ZONE_UNKNOWN);
            }
            applicantZone = zone;
        }
        Membership membership = access.operatorGuild(me, deadline);
        if (membership.rejected()) {
            return reviewTip(membership.tip());
        }
        long guildId = membership.guildId();
        TxOutcome<Reviewed> outcome = store.reviewApplication(guildId, me, applicant, approve, applicantZone,
                clockMs.getAsLong(), deadline);
        if (!(outcome instanceof TxOutcome.Ok<Reviewed> ok)) {
            return reviewTip(access.replyFor(me, guildId, guildId, outcome.rejection(), deadline));
        }
        Reviewed reviewed = ok.value();
        access.invalidate(reviewed.invalidation(), deadline);
        pushes.notify(reviewed.approved() ? GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_JOINED
                        : GuildChangeKind.GUILD_CHANGE_KIND_APPLICATION_REJECTED,
                guildId, me, applicant, reviewed.pushRecipients());
        return ReviewGuildApplicationResponse.newBuilder().setGuild(views.guildInfo(reviewed.guild(), me, deadline)).build();
    }

    private static ReviewGuildApplicationResponse reviewTip(GuildTip tip) {
        return ReviewGuildApplicationResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ================================================================ 工具

    /** GuildRule[1] 缺行是故障（基线 Internal {@code "GuildRule row 1 missing"}，guild_manage_logic.go:489-492、:647-650）。 */
    private ApplicationRules requireApplicationRules() {
        ApplicationRules rules = tables.applicationRules();
        if (rules == null) {
            log.error("[guild] GuildRule row 1 missing, cannot apply application rules");
            throw new GuildFaultException("GuildRule row 1 missing");
        }
        return rules;
    }
}
