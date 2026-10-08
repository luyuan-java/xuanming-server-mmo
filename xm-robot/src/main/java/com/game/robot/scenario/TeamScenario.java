package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.EnterSceneS2C;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.team.ApplyJoinTeamRequest;
import com.game.proto.team.CreateTeamRequest;
import com.game.proto.team.DisbandTeamRequest;
import com.game.proto.team.GetMyTeamRequest;
import com.game.proto.team.HandleApplicationRequest;
import com.game.proto.team.InviteToTeamRequest;
import com.game.proto.team.KickMemberRequest;
import com.game.proto.team.LeaveTeamRequest;
import com.game.proto.team.ListMyInvitesRequest;
import com.game.proto.team.ListMyInvitesResponse;
import com.game.proto.team.RespondInviteRequest;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamEventS2C;
import com.game.proto.team.TeamEventType;
import com.game.proto.team.TeamInviteS2C;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.proto.team.TeamView;
import com.game.proto.team.TransferLeaderRequest;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.ConfigTables;
import com.game.table.TeamErrorTip;
import com.game.table.WorldTable;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 组队端到端（对应 mmorpg robot/team_smoke_scenario.go，三个机器人 A / B / D 经 gate → xm-team；team-spec §10.5）：
 * <ol>
 *   <li>S0 预清理：各自 GetMyTeam，在队就解散（队长）或离队（队员）（Go :233-238、:773-802）；</li>
 *   <li>S1 A 建队（zone == 本区）→ B 申请 → A 拉取看到申请、version 增长 → A 同意：回包 2 人、version 增长；
 *       B 收 213 MEMBER_JOINED，version ≥ 回包、B 恰好一次（Go :240-301）；</li>
 *   <li>S2 重放同意：成员不变、B 的 join_seq 不变（Go :303-316）；</li>
 *   <li>S3 邀请 D（D 收 215）→ D 的 ListMyInvites 里有 → D 拒绝，A 收 INVITE_CHANGED 且其中没有 D → 再邀请 → D 接受（Go :318-359）；</li>
 *   <li>S4 踢 D：D 收 team_id = 0 的 MEMBER_KICKED，membership_epoch 变大；A → B → A 转让（Go :361-398）；</li>
 *   <li>S5 D 申请、A 拒绝 → D 收 203 APPLICATION_REJECTED（actor = 队长 A）（Go :400-419）；</li>
 *   <li>S6 A 换图（World 表候选，同 {@link ReconnectScenario}）→ B 10 s 内收到 79，scene_id 等于 A 的新场景（Go :421-439；
 *       跟随由 xm-scene 实现，team-spec §6.10）；</li>
 *   <li>开战拒绝码（批次 6.4，match-spec §15.5；这一条与下面两条在 {@link TeamMatchSteps}）：非队长 B 发 211 → 4018；A 发 211(2)
 *       （没配组队人数的副本）→ 4027 且视图 IDLE；B 持有 1V1 排队票时 A 发 211(1) → 4026，{@code parameters[0] = B}，随后 B 发 148 取消并用
 *       153 确认 NOT_QUEUED；</li>
 *   <li>S7 整队开战（Go :441-446、:1055-1100）：A 发 211(1) → 回包 STARTING；B 收 213 MATCH_STARTED（发起人 A 不收，以回包为准）；
 *       两人收到同一个 battle_id 的 177 / 143 → 都直连、开自动 → 都收到 150；两人都收到 213 MATCH_ENDED（开局成功、开战锁释放后即推，
 *       通常早于战斗结束，按 mark 扫描不会漏）。遇到 4025 / 4026 的过渡态在 20 s 内重试；</li>
 *   <li>S8 战斗中拒绝（Go :448-470）：B 单人 PVE 开战、已直连但尚未开自动（持战斗锁）时 A 发 211 → 4025，{@code parameters[0] = B}；
 *       先撞到别的队员（上一场结算尚未落地）时在 20 s 内重试；随后 B 把这一局打完；</li>
 *   <li>X1 / X2 跨区：本机切片单 zone，跳过并记观察（D16）；</li>
 *   <li>S9 A 解散：其余成员收 DISBANDED，team_id = 0（Go :534-567）。</li>
 * </ol>
 * Java 增项（钉住契约细节，team-spec §10.5）：重复建队 4003 带 leader = A 的视图；用不符的 expected 离队 → 成功带当前视图；
 * 踢自己 4005、转让给自己 4007、非队长踢人 4006；邀请已登出的账号 4017；notify_online → A 收 MEMBER_ONLINE 且 version 不变；
 * 上行 213 收不到任何回包（D13）；拒绝不存在的邀请幂等成功；RespondInvite(team_id = 0) → 4013。
 *
 * <p>开战拒绝码、S7、S8 三段（批次 6.4，需要切片带 xm-match、xm-battle 与 scene 的 SceneBattleService）各自独立：某一段中断时记失败并继续，
 * S9 照常跑——切片里没有 xm-match 时只有这三段失败，其余步骤不受影响。结果行（{@link #resultLine}，供外层脚本按子串消费）：
 * {@code TEAM_SMOKE_OK team_id=… zone=… player_a=… player_b=… player_d=… battle_id=…} / {@code TEAM_SMOKE_FAIL step=… reason=…}。
 *
 * <p>账号是 run-tag 新号（D16，基线是固定的 robot_9301–9304），第四个账号 E 只用来充当「已登出的玩家」：进场后立即 LeaveGame 断开。
 * 推送按 {@link com.game.robot.client.Inbox} 的到达序：触发动作之前记 mark、之后从 mark 起找（先到的推送不会漏，上一步的不会被误认）。
 * gate 对组队消息号限频每秒 3 条（GetMyTeam / ListMyInvites 5 条），同一机器人相邻请求至少隔 {@link #REQUEST_SPACING}（同 Go :97-99）。
 */
public final class TeamScenario {

    private static final String SERVICE = "ClientPlayerTeam";
    private static final String REF = "PARITY「组队」行";
    private static final String REF_FOLLOW = "PARITY「组队」行；team-spec §6.10";
    /** 同一机器人相邻请求的最小间隔（Go teamSmokeRequestSpacing）。 */
    static final Duration REQUEST_SPACING = Duration.ofMillis(400);
    /** 等组队推送（Go teamSmokePushTimeout：提交后异步推送，整批预算 3 s）。 */
    private static final Duration PUSH_TIMEOUT = Duration.ofSeconds(10);
    /** S6 跟随判定窗口，也是换图自身等 79 的上限（Go teamSmokeFollowTimeout）。 */
    private static final Duration FOLLOW_TIMEOUT = Duration.ofSeconds(10);
    /** 结果行的标记：{@code TEAM_SMOKE_OK …} / {@code TEAM_SMOKE_FAIL step=… reason=…}（同基线 Go robot）。 */
    static final String MARKER = "TEAM_SMOKE";

    private static final int TIP_MEMBER_IN_TEAM = TeamErrorTip.team_error.kTeamMemberInTeam_VALUE;
    private static final int TIP_KICK_SELF = TeamErrorTip.team_error.kTeamKickSelf_VALUE;
    private static final int TIP_KICK_NOT_LEADER = TeamErrorTip.team_error.kTeamKickNotLeader_VALUE;
    private static final int TIP_APPOINT_SELF = TeamErrorTip.team_error.kTeamAppointSelf_VALUE;
    private static final int TIP_NO_TEAM = TeamErrorTip.team_error.kTeamHasNotTeamId_VALUE;
    private static final int TIP_PLAYER_NOT_FOUND = TeamErrorTip.team_error.kTeamPlayerNotFound_VALUE;
    private static final int TIP_HOME_ZONE_UNKNOWN = TeamErrorTip.team_error.kTeamHomeZoneUnknown_VALUE;
    private static final int TIP_IN_MATCH = TeamErrorTip.team_error.kTeamInMatch_VALUE;

    private final PlayerFlow flow;
    private final Path tableDir;
    private final String accountA;
    private final String accountB;
    private final String accountD;
    private final String accountE;
    private final int zoneId;
    private final Duration requestTimeout;
    private final int createTeam;
    private final int getMyTeam;
    private final int applyJoin;
    private final int handleApplication;
    private final int invite;
    private final int respondInvite;
    private final int listInvites;
    private final int leaveTeam;
    private final int kick;
    private final int transfer;
    private final int disband;
    private final int notifySnapshot;
    private final int notifyInvite;
    private final int notifyEvent;
    private final int enterScene;
    private final int notifyEnterScene;
    private final int leaveGame;
    private final int sendTip;
    private final CheckReport report = new CheckReport();
    private final StepTrack steps = new StepTrack(MARKER);
    private final List<GameConnection> connections = new ArrayList<>();
    /** 开战段（批次 6.4）：开战拒绝码、S7、S8；它建的战斗直连由它自己收尾。 */
    private final TeamMatchSteps matchSteps;

    /** 结果行的字段（没跑到的保持 0）。 */
    private long teamId;
    private long playerA;
    private long playerB;
    private long playerD;

    /**
     * @param client   建战斗直连用（S7 / S8）
     * @param tableDir 配置表目录（S6 从 World 表选换图目标）
     * @param zoneId   本区（S1 断言队伍 zone_id == 建队者的 home zone == 新号建角所在区）
     */
    public TeamScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, Path tableDir, String accountPrefix, String runTag,
                        int zoneId, Duration requestTimeout) {
        this.flow = flow;
        this.matchSteps = new TeamMatchSteps(client, registry, report, steps, requestTimeout, MatchSupport.Tempo.STANDARD);
        this.tableDir = tableDir;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountD = accountName(accountPrefix, runTag, "d");
        this.accountE = accountName(accountPrefix, runTag, "e");
        this.zoneId = zoneId;
        this.requestTimeout = requestTimeout;
        this.createTeam = registry.requireId(SERVICE, "CreateTeam");
        this.getMyTeam = registry.requireId(SERVICE, "GetMyTeam");
        this.applyJoin = registry.requireId(SERVICE, "ApplyJoinTeam");
        this.handleApplication = registry.requireId(SERVICE, "HandleApplication");
        this.invite = registry.requireId(SERVICE, "InviteToTeam");
        this.respondInvite = registry.requireId(SERVICE, "RespondInvite");
        this.listInvites = registry.requireId(SERVICE, "ListMyInvites");
        this.leaveTeam = registry.requireId(SERVICE, "LeaveTeam");
        this.kick = registry.requireId(SERVICE, "KickMember");
        this.transfer = registry.requireId(SERVICE, "TransferLeader");
        this.disband = registry.requireId(SERVICE, "DisbandTeam");
        this.notifySnapshot = registry.requireId(SERVICE, "NotifyTeamSnapshot");
        this.notifyInvite = registry.requireId(SERVICE, "NotifyTeamInvite");
        this.notifyEvent = registry.requireId(SERVICE, "NotifyTeamEvent");
        this.enterScene = registry.requireId("SceneSceneClientPlayer", "EnterScene");
        this.notifyEnterScene = registry.requireId("SceneSceneClientPlayer", "NotifyEnterScene");
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.sendTip = registry.requireId("SceneClientPlayerCommon", "SendTipToClient");
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "tm" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.getMessage() == null ? e.toString() : e.getMessage(), REF);
        } finally {
            matchSteps.close();
            connections.forEach(GameConnection::close);
        }
        return report;
    }

    /** 结果行（跑完 {@link #run} 之后取）：{@code TEAM_SMOKE_OK …} 或 {@code TEAM_SMOKE_FAIL step=… reason=…}。 */
    public String resultLine() {
        return steps.line(report, "team_id=" + uid(teamId) + " zone=" + zoneId + " player_a=" + uid(playerA) + " player_b=" + uid(playerB)
                + " player_d=" + uid(playerD) + " battle_id=" + uid(matchSteps.teamBattleId()));
    }

    private void runChecks() throws RobotException {
        steps.step("login", report);
        Bot a = enter("A", accountA);
        Bot b = enter("B", accountB);
        Bot d = enter("D", accountD);
        // E 只充当「已登出的玩家」：进场拿到角色号后按契约收尾（发 LeaveGame 立即断开），gate 断线即删 xm:presence
        Bot e = enter("E", accountE);
        e.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
        e.connection().close();
        playerA = a.id();
        playerB = b.id();
        playerD = d.id();
        report.note("A=" + uid(a.id()) + " B=" + uid(b.id()) + " D=" + uid(d.id()) + " E（已登出）=" + uid(e.id()));

        // ---- S0：回到「不在任何队伍」 ----
        steps.step("s0-cleanup", report);
        List<String> leftovers = new ArrayList<>();
        for (Bot bot : List.of(a, b, d)) {
            String left = leaveAnyTeam(bot);
            if (!left.isEmpty()) {
                leftovers.add(bot.name + "：" + left);
            }
        }
        must(leftovers.isEmpty(), "S0 预清理：A / B / D 都不在任何队伍", leftovers.isEmpty() ? "" : String.join("；", leftovers));

        // ---- S1：A 建队 → B 申请 → A 同意 ----
        steps.step("s1-create-apply-approve", report);
        TeamResponse createResp = a.call(createTeam, CreateTeamRequest.getDefaultInstance(), TeamResponse.parser());
        TeamView created = createResp.getTeam();
        long tid = created.getTeamId();
        must(tipOf(createResp) == 0 && tid != 0 && created.getLeaderId() == a.id() && created.getMembersCount() == 1,
                "S1 A 建队：受理，A 是唯一成员且为队长", describe(createResp));
        teamId = tid;
        report.check(created.getZoneId() == zoneId, "S1 队伍 zone_id == A 的 home zone（本区）",
                "zone_id=" + created.getZoneId() + "，期望 " + zoneId, REF);
        TeamResponse dupCreate = a.call(createTeam, CreateTeamRequest.getDefaultInstance(), TeamResponse.parser());
        report.check(tipOf(dupCreate) == TIP_MEMBER_IN_TEAM && dupCreate.hasTeam() && dupCreate.getTeam().getTeamId() == tid
                        && dupCreate.getTeam().getLeaderId() == a.id(),
                "重复建队 → 4003，带当前视图且 leader_id == A（客户端按成功处理）", describe(dupCreate), REF);

        expectTip(b, applyJoin, ApplyJoinTeamRequest.newBuilder().setTargetPlayerId(a.id()).build(), 0, "S1 B 申请加入 A 的队伍");
        TeamView afterApply = myTeam(a);
        report.check(hasApplication(afterApply, b.id()) && Long.compareUnsigned(afterApply.getVersion(), created.getVersion()) > 0,
                "S1 A 拉取看到 B 的申请，version 增长", "申请 " + afterApply.getApplicationsCount() + " 条 version="
                        + uid(afterApply.getVersion()) + "（建队时 " + uid(created.getVersion()) + "）", REF);

        int bMark = b.mark();
        HandleApplicationRequest approveReq = HandleApplicationRequest.newBuilder().setApplicantId(b.id()).setApprove(true)
                .setExpectedTeamId(tid).build();
        TeamResponse approveResp = a.call(handleApplication, approveReq, TeamResponse.parser());
        TeamView approved = approveResp.getTeam();
        TeamMemberView bMember = onlyMember(approved, b.id());
        must(tipOf(approveResp) == 0 && approved.getMembersCount() == 2 && bMember != null
                        && Long.compareUnsigned(approved.getVersion(), afterApply.getVersion()) > 0,
                "S1 A 同意：回包 2 人、B 恰好一次、version 增长", describe(approveResp));
        Optional<TeamSnapshotS2C> joined = awaitSnapshot(b, bMark, s -> s.getReason() == TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED
                && s.getTeam().getTeamId() == tid);
        report.check(joined.isPresent() && Long.compareUnsigned(joined.get().getTeam().getVersion(), approved.getVersion()) >= 0
                        && countMember(joined.get().getTeam(), b.id()) == 1,
                "S1 B 收到 213 MEMBER_JOINED：version ≥ 同意回包、自己恰好出现一次",
                joined.map(s -> describe(s.getTeam())).orElse(PUSH_TIMEOUT.toSeconds() + " s 内没收到" + b.describeSince(bMark)), REF);

        // ---- S2：重放「同意」，状态只变化一次 ----
        steps.step("s2-replay", report);
        TeamResponse replay = a.call(handleApplication, approveReq, TeamResponse.parser());
        TeamMemberView replayB = onlyMember(replay.getTeam(), b.id());
        report.check(tipOf(replay) == 0 && replay.getTeam().getMembersCount() == 2 && replayB != null
                        && replayB.getJoinSeq() == bMember.getJoinSeq(),
                "S2 重放同意：受理，成员仍 2 人、B 的 join_seq 不变", describe(replay), REF);
        report.note("S2 重放前后 version：" + uid(approved.getVersion()) + " → " + uid(replay.getTeam().getVersion()));

        // Java 增项：迟到的旧离队请求（expected 与当前队伍不符）→ 成功、不写，回当前视图（team-spec §3.10）
        TeamResponse staleLeave = b.call(leaveTeam, LeaveTeamRequest.newBuilder().setExpectedTeamId(tid + 1).build(),
                TeamResponse.parser());
        report.check(tipOf(staleLeave) == 0 && staleLeave.getTeam().getTeamId() == tid
                        && countMember(staleLeave.getTeam(), b.id()) == 1,
                "B 用不符的 expected 离队 → 成功且仍在队（回当前视图）", describe(staleLeave), REF);

        // ---- S3：邀请 D（D 收 215）→ D 拒绝 → 再邀请 → D 接受 ----
        steps.step("s3-invite", report);
        InviteToTeamRequest inviteD = InviteToTeamRequest.newBuilder().setTargetPlayerId(d.id()).setExpectedTeamId(tid).build();
        int dMark = d.mark();
        TeamResponse invited = a.call(invite, inviteD, TeamResponse.parser());
        report.check(tipOf(invited) == 0 && hasPendingInvite(invited.getTeam(), d.id()),
                "S3 A 邀请 D：受理，队长视图里有给 D 的待处理邀请", describe(invited), REF);
        Optional<TeamInviteS2C> invitePush = awaitPush(d, dMark, notifyInvite, TeamInviteS2C.parser(),
                p -> p.getInvite().getTeamId() == tid, PUSH_TIMEOUT);
        report.check(invitePush.isPresent() && invitePush.get().getServerTimeMs() != 0
                        && invitePush.get().getInvite().getInviter().getPlayerId() == a.id()
                        && invitePush.get().getInvite().getLeaderId() == a.id(),
                "S3 D 收到 215 邀请推送（inviter = 队长 A、server_time_ms ≠ 0）",
                invitePush.map(p -> "inviter=" + uid(p.getInvite().getInviter().getPlayerId()) + " member_count="
                        + p.getInvite().getMemberCount() + " server_time_ms=" + p.getServerTimeMs())
                        .orElse(PUSH_TIMEOUT.toSeconds() + " s 内没收到" + d.describeSince(dMark)), REF);
        ListMyInvitesResponse dInvites = d.call(listInvites, ListMyInvitesRequest.getDefaultInstance(),
                ListMyInvitesResponse.parser());
        report.check(tipOf(dInvites.getErrorMessage()) == 0 && dInvites.getInvitesList().stream().anyMatch(i -> i.getTeamId() == tid),
                "S3 D 的 ListMyInvites 里有队伍 " + uid(tid), "tip=" + tipOf(dInvites.getErrorMessage()) + " "
                        + dInvites.getInvitesCount() + " 条", REF);
        int aMark = a.mark();
        expectTip(d, respondInvite, RespondInviteRequest.newBuilder().setTeamId(tid).setAccept(false).build(), 0, "S3 D 拒绝邀请");
        Optional<TeamSnapshotS2C> inviteChanged = awaitSnapshot(a, aMark,
                s -> s.getReason() == TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED && s.getTeam().getTeamId() == tid
                        && !hasPendingInvite(s.getTeam(), d.id()));
        report.check(inviteChanged.isPresent(), "S3 A 收到 INVITE_CHANGED，其中已没有给 D 的邀请",
                inviteChanged.map(s -> "actor_id=" + uid(s.getActorId()) + " " + describe(s.getTeam()))
                        .orElse(PUSH_TIMEOUT.toSeconds() + " s 内没收到" + a.describeSince(aMark)), REF);
        expectTip(a, invite, inviteD, 0, "S3 A 再次邀请 D");
        TeamResponse acceptResp = d.call(respondInvite, RespondInviteRequest.newBuilder().setTeamId(tid).setAccept(true).build(),
                TeamResponse.parser());
        TeamView accepted = acceptResp.getTeam();
        must(tipOf(acceptResp) == 0 && accepted.getTeamId() == tid && countMember(accepted, d.id()) == 1
                        && accepted.getMembersCount() == 3,
                "S3 D 接受邀请：在队伍里且共 3 人", describe(acceptResp));
        long dEpochInTeam = accepted.getMembershipEpoch();

        // ---- S4：踢 D（D 收空视图、epoch 变大）；A → B → A 转让 ----
        steps.step("s4-kick-transfer", report);
        dMark = d.mark();
        TeamResponse kicked = a.call(kick, KickMemberRequest.newBuilder().setTargetPlayerId(d.id()).setExpectedTeamId(tid).build(),
                TeamResponse.parser());
        report.check(tipOf(kicked) == 0 && countMember(kicked.getTeam(), d.id()) == 0, "S4 A 踢 D：受理，视图里没有 D",
                describe(kicked), REF);
        Optional<TeamSnapshotS2C> kickedPush = awaitSnapshot(d, dMark,
                s -> s.getReason() == TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_KICKED && s.getTeam().getTeamId() == 0);
        report.check(kickedPush.isPresent()
                        && Long.compareUnsigned(kickedPush.get().getTeam().getMembershipEpoch(), dEpochInTeam) > 0,
                "S4 D 收到 team_id = 0 的 MEMBER_KICKED，membership_epoch 大于在队时",
                kickedPush.map(s -> "epoch=" + uid(s.getTeam().getMembershipEpoch()) + "（在队时 " + uid(dEpochInTeam) + "）")
                        .orElse(PUSH_TIMEOUT.toSeconds() + " s 内没收到" + d.describeSince(dMark)), REF);
        TeamResponse toB = a.call(transfer, TransferLeaderRequest.newBuilder().setTargetPlayerId(b.id()).setExpectedTeamId(tid)
                .build(), TeamResponse.parser());
        report.check(tipOf(toB) == 0 && toB.getTeam().getLeaderId() == b.id(), "S4 A 转让队长给 B", describe(toB), REF);
        TeamResponse toA = b.call(transfer, TransferLeaderRequest.newBuilder().setTargetPlayerId(a.id()).setExpectedTeamId(tid)
                .build(), TeamResponse.parser());
        must(tipOf(toA) == 0 && toA.getTeam().getLeaderId() == a.id(), "S4 B 转回给 A", describe(toA));

        // Java 增项：规则层拒绝码（都带当前视图）
        expectTipWithView(a, kick, KickMemberRequest.newBuilder().setTargetPlayerId(a.id()).setExpectedTeamId(tid).build(),
                TIP_KICK_SELF, tid, "队长踢自己 → 4005");
        expectTipWithView(a, transfer, TransferLeaderRequest.newBuilder().setTargetPlayerId(a.id()).setExpectedTeamId(tid).build(),
                TIP_APPOINT_SELF, tid, "队长转让给自己 → 4007");
        expectTipWithView(b, kick, KickMemberRequest.newBuilder().setTargetPlayerId(a.id()).setExpectedTeamId(tid).build(),
                TIP_KICK_NOT_LEADER, tid, "非队长 B 踢人 → 4006");
        // Java 增项：邀请目标不在线（服务前置，早于队伍检查）→ 4017
        expectTip(a, invite, InviteToTeamRequest.newBuilder().setTargetPlayerId(e.id()).setExpectedTeamId(tid).build(),
                TIP_PLAYER_NOT_FOUND, "邀请已登出的账号 E → 4017");

        // Java 增项：notify_online → 其他在线队员收 MEMBER_ONLINE，version 不变（team-spec §4.6）
        aMark = a.mark();
        TeamResponse online = b.call(getMyTeam, GetMyTeamRequest.newBuilder().setNotifyOnline(true).build(), TeamResponse.parser());
        long onlineVersion = online.getTeam().getVersion();
        Optional<TeamSnapshotS2C> memberOnline = awaitSnapshot(a, aMark,
                s -> s.getReason() == TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_ONLINE && s.getTeam().getTeamId() == tid
                        && s.getActorId() == b.id());
        report.check(tipOf(online) == 0 && online.getTeam().getTeamId() == tid && memberOnline.isPresent()
                        && memberOnline.get().getTeam().getVersion() == onlineVersion,
                "B 带 notify_online 拉取 → A 收到 MEMBER_ONLINE（actor = B），version 与 B 的回包相同",
                memberOnline.map(s -> "推送 version=" + uid(s.getTeam().getVersion()) + "，B 回包 version=" + uid(onlineVersion)
                        + "（转让后 " + uid(toA.getTeam().getVersion()) + "）")
                        .orElse("B " + describe(online) + "；" + PUSH_TIMEOUT.toSeconds() + " s 内 A 没收到" + a.describeSince(aMark)),
                REF);

        // Java 增项：客户端上行 213（推送占位）→ gate 不回包（D13）。team 域的请求按会话串行，之后一次 GetMyTeam 的应答到达时
        // 213 必已处理完：此时还没有 (213, id) 的回包、也没有 23 tip，就是确实不回
        aMark = a.mark();
        long uplink = a.send(notifySnapshot, TeamSnapshotS2C.newBuilder().setReason(TeamChangeReason.TEAM_CHANGE_REASON_HEALED)
                .setActorId(b.id()).build());
        myTeam(a);
        List<Received> since = a.connection().inbox().snapshot(aMark);
        boolean replied = since.stream().anyMatch(r -> r.messageId() == notifySnapshot && r.requestId() == uplink);
        boolean tipped = since.stream().anyMatch(r -> r.messageId() == sendTip);
        report.check(!replied && !tipped, "客户端上行 213 → 收不到任何回包（也没有 23 tip）",
                (replied ? "收到了 213 的回包" : "") + (tipped ? " 收到了 23 tip" + a.describeSince(aMark) : ""), REF);

        // Java 增项：拒绝不存在的邀请 → 幂等成功；RespondInvite(team_id = 0) → 4013（带视图）
        expectTip(d, respondInvite, RespondInviteRequest.newBuilder().setTeamId(tid).setAccept(false).build(), 0,
                "D 拒绝一个不存在的邀请 → 幂等成功");
        TeamResponse zeroTeam = d.call(respondInvite, RespondInviteRequest.newBuilder().setTeamId(0).setAccept(true).build(),
                TeamResponse.parser());
        report.check(tipOf(zeroTeam) == TIP_NO_TEAM && zeroTeam.hasTeam(), "RespondInvite(team_id = 0) → 4013（带调用者视图）",
                describe(zeroTeam), REF);

        // ---- S5：D 申请，A 拒绝 → D 收 203 APPLICATION_REJECTED ----
        steps.step("s5-reject-application", report);
        expectTip(d, applyJoin, ApplyJoinTeamRequest.newBuilder().setTargetPlayerId(a.id()).build(), 0, "S5 D 申请加入");
        dMark = d.mark();
        TeamResponse rejected = a.call(handleApplication, HandleApplicationRequest.newBuilder().setApplicantId(d.id())
                .setApprove(false).setExpectedTeamId(tid).build(), TeamResponse.parser());
        report.check(tipOf(rejected) == 0 && !hasApplication(rejected.getTeam(), d.id()),
                "S5 A 拒绝 D 的申请：受理，视图里不再有 D 的申请", describe(rejected), REF);
        Optional<TeamEventS2C> rejectedEvent = awaitPush(d, dMark, notifyEvent, TeamEventS2C.parser(),
                ev -> ev.getType() == TeamEventType.TEAM_EVENT_TYPE_APPLICATION_REJECTED && ev.getTeamId() == tid, PUSH_TIMEOUT);
        report.check(rejectedEvent.isPresent() && rejectedEvent.get().getActorId() == a.id(),
                "S5 D 收到 203 APPLICATION_REJECTED（actor = 队长 A）",
                rejectedEvent.map(ev -> "actor_id=" + uid(ev.getActorId()))
                        .orElse(PUSH_TIMEOUT.toSeconds() + " s 内没收到" + d.describeSince(dMark)), REF);

        // ---- S6：A 换图，B 跟随 ----
        steps.step("s6-follow", report);
        follow(a, b);

        // ---- 开战拒绝码、S7、S8（批次 6.4，TeamMatchSteps）：各自独立，中断时记失败并继续，S9 照常跑 ----
        matchSteps.run(a, b, tid);
        report.note("X1 / X2 跨区：本机切片只有一个 zone，跳过；4020 由 xm-team 单测用不同 zone_id 的预置玩家覆盖（team-spec §10.5、D16）");

        // ---- S9：解散 ----
        steps.step("s9-disband", report);
        TeamView before = myTeam(a);
        List<Bot> waiters = new ArrayList<>();
        List<Integer> marks = new ArrayList<>();
        for (TeamMemberView member : before.getMembersList()) {
            if (member.getPlayerId() == a.id()) {
                continue;
            }
            Bot bot = member.getPlayerId() == b.id() ? b : member.getPlayerId() == d.id() ? d : null;
            if (bot == null) {
                must(false, "S9 队伍里只有本场景的账号", "有别的成员 " + uid(member.getPlayerId()) + "：" + describe(before));
            }
            waiters.add(bot);
            marks.add(bot.mark());
        }
        report.check(waiters.contains(b), "S9 解散前 B 仍在队（D 已被踢、申请被拒）", describe(before), REF);
        TeamResponse disbanded = a.call(disband, DisbandTeamRequest.newBuilder().setExpectedTeamId(tid).build(),
                TeamResponse.parser());
        report.check(tipOf(disbanded) == 0 && disbanded.hasTeam() && disbanded.getTeam().getTeamId() == 0,
                "S9 A 解散：受理，回包是 team_id = 0 的空视图", describe(disbanded), REF);
        for (int i = 0; i < waiters.size(); i++) {
            Bot bot = waiters.get(i);
            int mark = marks.get(i);
            Optional<TeamSnapshotS2C> gone = awaitSnapshot(bot, mark,
                    s -> s.getReason() == TeamChangeReason.TEAM_CHANGE_REASON_DISBANDED && s.getTeam().getTeamId() == 0);
            report.check(gone.isPresent(), "S9 " + bot.name + " 收到 DISBANDED，team_id = 0",
                    gone.map(s -> "epoch=" + uid(s.getTeam().getMembershipEpoch()) + " actor_id=" + uid(s.getActorId()))
                            .orElse(PUSH_TIMEOUT.toSeconds() + " s 内没收到" + bot.describeSince(mark)), REF);
        }
    }

    /** S6：A 换到一张 A、B 当前都不在的世界地图，B 应在 {@link #FOLLOW_TIMEOUT} 内跟进同一个场景实例。 */
    private void follow(Bot a, Bot b) throws RobotException {
        List<Integer> worlds;
        try {
            worlds = worldConfigs(ConfigTables.load(tableDir));
        } catch (RuntimeException e) {
            report.fail("S6 读 World 表选换图目标", tableDir + "：" + e, REF_FOLLOW);
            return;
        }
        // 跟随只在同一个 scene 节点内生效（跨节点、跨 zone 不跟随，team-spec §6.10 X2，同基线）。两个 scene 节点的切片上 A、B 登录时
        // 可能被分到不同节点的频道（同图不同 scene_id）：先让 B 用 63 换到 A 的实例（跨节点换图，批次 5.2），再验证跟随；
        // 否则这一步测到的只是「B 不在队长的节点上」。单节点切片上两人本来就在同一实例，这段不执行。
        if (b.scene.getSceneId() != a.scene.getSceneId()) {
            String before = "A 在 scene_config_id=" + a.scene.getSceneConfigId() + " scene_id=" + uid(a.scene.getSceneId())
                    + "，B 在 scene_config_id=" + b.scene.getSceneConfigId() + " scene_id=" + uid(b.scene.getSceneId());
            try {
                switchToInstance(b, a.scene);
            } catch (RobotException e) {
                report.fail("S6 前置：B 换到 A 所在的场景实例", before + "；" + e.getMessage(), REF_FOLLOW);
                return;
            }
            report.note("S6 前置：A、B 不在同一个场景实例（" + before + "），B 已换到 A 的实例（跟随不跨节点）");
        }
        int target = pickSceneConfig(worlds, a.scene.getSceneConfigId(), b.scene.getSceneConfigId());
        if (target == 0) {
            report.fail("S6 选换图目标", "World 表 " + worlds + " 里没有与 A（" + a.scene.getSceneConfigId() + "）、B（"
                    + b.scene.getSceneConfigId() + "）当前地图都不同的配置", REF_FOLLOW);
            return;
        }
        int bMark = b.mark();
        SceneInfoComp leader;
        try {
            leader = switchScene(a, target);
        } catch (RobotException e) {
            // 换图本身失败（scene 拒绝、没收到 79）与组队无关：记失败、继续后面的组队步骤；连接已断时后续调用会自行中止
            report.fail("S6 A 换图到 " + target, e.getMessage(), REF_FOLLOW);
            return;
        }
        Optional<EnterSceneS2C> followed = awaitPush(b, bMark, notifyEnterScene, EnterSceneS2C.parser(),
                s -> s.getSceneInfo().getSceneId() == leader.getSceneId(), FOLLOW_TIMEOUT);
        followed.ifPresent(s -> b.scene = s.getSceneInfo());
        report.check(followed.isPresent(), "S6 A 换图 → B " + FOLLOW_TIMEOUT.toSeconds() + " s 内收到 79、进入 A 的新场景实例",
                "A 换到 scene_config_id=" + target + " scene_id=" + uid(leader.getSceneId())
                        + followed.map(s -> "；B 进入 scene_id=" + uid(s.getSceneInfo().getSceneId())).orElse("；" + b.describeSince(bMark)),
                REF_FOLLOW);
    }

    /** 63 换到 {@code configId}：应答无错、随后的 79 是目标地图（同 {@link ReconnectScenario}）。返回新场景。 */
    private SceneInfoComp switchScene(Bot bot, int configId) throws RobotException {
        int mark = bot.mark();
        EnterSceneC2SResponse response = bot.call(enterScene, EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(configId)).build(), EnterSceneC2SResponse.parser());
        if (response.hasErrorMessage() && response.getErrorMessage().getId() != 0) {
            throw new RobotException(bot.name + " 换场景（63）到 " + configId + " 回 " + response.getErrorMessage().getId());
        }
        Optional<EnterSceneS2C> entered = awaitPush(bot, mark, notifyEnterScene, EnterSceneS2C.parser(),
                s -> s.getSceneInfo().getSceneConfigId() == configId, FOLLOW_TIMEOUT);
        if (entered.isEmpty()) {
            throw new RobotException(bot.name + " 换场景后 " + FOLLOW_TIMEOUT.toSeconds() + " s 内没收到目标地图的 79"
                    + bot.describeSince(mark));
        }
        bot.scene = entered.get().getSceneInfo();
        return bot.scene;
    }

    /** 63 指定场景实例（配置号 + scene_id）：应答无错、随后的 79 就是那个实例（可能在另一个 scene 节点上，即跨节点换图）。 */
    private void switchToInstance(Bot bot, SceneInfoComp target) throws RobotException {
        int mark = bot.mark();
        EnterSceneC2SResponse response = bot.call(enterScene, EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(target.getSceneConfigId()).setSceneId(target.getSceneId()))
                .build(), EnterSceneC2SResponse.parser());
        if (response.hasErrorMessage() && response.getErrorMessage().getId() != 0) {
            throw new RobotException(bot.name + " 换场景（63）到实例 " + uid(target.getSceneId()) + " 回 " + response.getErrorMessage().getId());
        }
        Optional<EnterSceneS2C> entered = awaitPush(bot, mark, notifyEnterScene, EnterSceneS2C.parser(),
                s -> s.getSceneInfo().getSceneId() == target.getSceneId(), FOLLOW_TIMEOUT);
        if (entered.isEmpty()) {
            throw new RobotException(bot.name + " 换场景后 " + FOLLOW_TIMEOUT.toSeconds() + " s 内没收到目标实例的 79"
                    + bot.describeSince(mark));
        }
        bot.scene = entered.get().getSceneInfo();
    }

    /**
     * 把机器人带回「不在任何队伍」（Go leaveAnyTeam）：最多 3 轮 GetMyTeam → 队长解散 / 队员离队。
     *
     * @return 空串 = 已不在队；否则是失败原因
     */
    private String leaveAnyTeam(Bot bot) throws RobotException {
        for (int attempt = 0; attempt < 3; attempt++) {
            TeamView view = myTeam(bot);
            long tid = view.getTeamId();
            if (tid == 0) {
                return "";
            }
            TeamResponse response = view.getLeaderId() == bot.id()
                    ? bot.call(disband, DisbandTeamRequest.newBuilder().setExpectedTeamId(tid).build(), TeamResponse.parser())
                    : bot.call(leaveTeam, LeaveTeamRequest.newBuilder().setExpectedTeamId(tid).build(), TeamResponse.parser());
            if (tipOf(response) == TIP_IN_MATCH) {
                return "残留队伍 " + uid(tid) + " 的开战锁仍有效（4023），稍后重跑";
            }
            if (tipOf(response) != 0) {
                return "离开残留队伍 " + uid(tid) + "：" + describe(response);
            }
        }
        return "连续 3 次离队 / 解散后仍在队伍里";
    }

    /** GetMyTeam（不带 notify_online）：必须受理且带视图（无队时 team_id = 0）。 */
    private TeamView myTeam(Bot bot) throws RobotException {
        TeamResponse response = bot.call(getMyTeam, GetMyTeamRequest.getDefaultInstance(), TeamResponse.parser());
        if (tipOf(response) != 0 || !response.hasTeam()) {
            throw new RobotException(bot.name + " GetMyTeam 期望受理且带视图，实得 " + describe(response)
                    + (tipOf(response) == TIP_HOME_ZONE_UNKNOWN ? "（角色区服信息异常）" : ""));
        }
        return response.getTeam();
    }

    private void expectTip(Bot bot, int messageId, Message request, int want, String name) throws RobotException {
        TeamResponse response = bot.call(messageId, request, TeamResponse.parser());
        report.check(tipOf(response) == want, name, describe(response), REF);
    }

    /** 业务拒绝（规则层）带调用者当前视图：断言码与视图里的队伍。 */
    private void expectTipWithView(Bot bot, int messageId, Message request, int want, long tid, String name)
            throws RobotException {
        TeamResponse response = bot.call(messageId, request, TeamResponse.parser());
        report.check(tipOf(response) == want && response.hasTeam() && response.getTeam().getTeamId() == tid, name + "（带当前视图）",
                describe(response), REF);
    }

    /** 后续步骤依赖的检查：不通过即中止（记一条失败后抛出）。 */
    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) {
            throw new RobotException("「" + name + "」未通过，后续步骤依赖它");
        }
    }

    private Optional<TeamSnapshotS2C> awaitSnapshot(Bot bot, int mark, Predicate<TeamSnapshotS2C> match) throws RobotException {
        return awaitPush(bot, mark, notifySnapshot, TeamSnapshotS2C.parser(), match, PUSH_TIMEOUT);
    }

    /** 从 {@code mark} 起等第一条消息号为 {@code messageId}、id = 0（推送）且满足条件的下行。 */
    private static <T extends Message> Optional<T> awaitPush(Bot bot, int mark, int messageId, Parser<T> parser,
                                                            Predicate<T> match, Duration timeout) throws RobotException {
        Optional<Received> push = bot.connection().await(mark, r -> {
            if (r.messageId() != messageId || r.requestId() != 0) {
                return false;
            }
            T body = r.parseOrNull(parser);
            return body != null && match.test(body);
        }, timeout);
        return push.map(r -> r.parseOrNull(parser));
    }

    private Bot enter(String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        connections.add(player.connection());
        return new Bot(name, player, requestTimeout);
    }

    static List<Integer> worldConfigs(ConfigTables tables) {
        Set<Integer> worlds = new LinkedHashSet<>();
        for (WorldTable row : tables.world().all()) {
            if (row.getSceneId() != 0) {
                worlds.add(row.getSceneId());
            }
        }
        return List.copyOf(worlds);
    }

    /**
     * 按配置顺序取第一个不在 {@code excluded} 里的地图；没有返回 0（Go teamSmokePickSceneConfig）。
     * S6 要排除 A、B 两人的当前地图：B 已在目标图时 A 换过去不会触发 B 进场，跟随断言就失真。
     */
    static int pickSceneConfig(Collection<Integer> candidates, int... excluded) {
        for (int candidate : candidates) {
            boolean skip = false;
            for (int e : excluded) {
                skip |= candidate == e;
            }
            if (!skip) {
                return candidate;
            }
        }
        return 0;
    }

    /** 视图里 {@code playerId} 出现的次数（成员集合语义，2 次即服务端缺陷）。 */
    static int countMember(TeamView view, long playerId) {
        return (int) view.getMembersList().stream().filter(m -> m.getPlayerId() == playerId).count();
    }

    /** 恰好出现一次时返回该成员，否则 null。 */
    static TeamMemberView onlyMember(TeamView view, long playerId) {
        List<TeamMemberView> found = view.getMembersList().stream().filter(m -> m.getPlayerId() == playerId).toList();
        return found.size() == 1 ? found.get(0) : null;
    }

    static boolean hasApplication(TeamView view, long playerId) {
        return view.getApplicationsList().stream().anyMatch(a -> a.getPlayer().getPlayerId() == playerId);
    }

    static boolean hasPendingInvite(TeamView view, long playerId) {
        return view.getPendingInvitesList().stream().anyMatch(i -> i.getInvitee().getPlayerId() == playerId);
    }

    private static int tipOf(TeamResponse response) {
        return tipOf(response.getErrorMessage());
    }

    private static int tipOf(TipInfoMessage tip) {
        return tip == null ? 0 : tip.getId();
    }

    static String describe(TeamResponse response) {
        StringBuilder out = new StringBuilder("tip=").append(tipOf(response));
        if (response.getErrorMessage().getParametersCount() > 0) {
            out.append(" parameters=").append(response.getErrorMessage().getParametersList());
        }
        return out.append(response.hasTeam() ? " " + describe(response.getTeam()) : " 无视图").toString();
    }

    /** 视图摘要：队伍、队长、版本、接收者 epoch、开战态、成员（按 join_seq）。玩家号 / 队伍号按无符号十进制。 */
    static String describe(TeamView view) {
        StringBuilder out = new StringBuilder("team_id=").append(uid(view.getTeamId()))
                .append(" leader=").append(uid(view.getLeaderId()))
                .append(" version=").append(uid(view.getVersion()))
                .append(" epoch=").append(uid(view.getMembershipEpoch()))
                .append(" match_state=").append(view.getMatchState())
                .append(" members=[");
        for (int i = 0; i < view.getMembersCount(); i++) {
            TeamMemberView member = view.getMembers(i);
            out.append(i == 0 ? "" : ", ").append(uid(member.getPlayerId())).append('#').append(Integer.toUnsignedString(member.getJoinSeq()));
        }
        return out.append(']').toString();
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    /**
     * 一个已进场的机器人。只在场景线程上使用：相邻两次请求至少隔 {@link #REQUEST_SPACING}（按本机器人计，同 Go pace），
     * 远离 gate 每个消息号每秒 3 条的限频。
     */
    private static final class Bot implements MatchSupport.Caller {

        final String name;
        final EnteredPlayer player;
        final Duration requestTimeout;
        /** 当前所在场景（进场时的 79，换图 / 跟随后更新）。 */
        SceneInfoComp scene;
        /** 上一次发请求的单调时刻（{@link #sent} 为 false 时无意义）。 */
        private long lastSendNanos;
        private boolean sent;

        Bot(String name, EnteredPlayer player, Duration requestTimeout) {
            this.name = name;
            this.player = player;
            this.requestTimeout = requestTimeout;
            this.scene = player.sceneInfo();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long id() {
            return player.playerId();
        }

        @Override
        public GameConnection connection() {
            return player.connection();
        }

        @Override
        public int mark() {
            return connection().inbox().size();
        }

        String describeSince(int mark) {
            return connection().describeSince(mark);
        }

        /** 发请求等应答；信封错误（限频 1008、热关停 1003 ……）与超时抛出：它们说明请求没到 team / match 的业务逻辑。 */
        @Override
        public <T extends Message> T call(int messageId, Message body, Parser<T> parser) throws RobotException {
            pace();
            try {
                return connection().call(messageId, body, parser, requestTimeout);
            } catch (RobotException e) {
                throw new RobotException(name + "：" + e.getMessage(), e);
            }
        }

        /** 只发不等。 */
        @Override
        public long send(int messageId, Message body) throws RobotException {
            pace();
            return connection().send(messageId, body);
        }

        private void pace() throws RobotException {
            long waitNanos = sent ? REQUEST_SPACING.toNanos() - (System.nanoTime() - lastSendNanos) : 0;
            if (waitNanos > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(waitNanos));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RobotException("等待被中断", e);
                }
            }
            lastSendNanos = System.nanoTime();
            sent = true;
        }
    }
}
