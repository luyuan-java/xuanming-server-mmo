package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.TipInfoMessage;
import com.game.proto.guild.ApplyJoinGuildRequest;
import com.game.proto.guild.ApplyJoinGuildResponse;
import com.game.proto.guild.CancelGuildApplicationRequest;
import com.game.proto.guild.CancelGuildApplicationResponse;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.DisbandGuildRequest;
import com.game.proto.guild.DisbandGuildResponse;
import com.game.proto.guild.DonateToGuildRequest;
import com.game.proto.guild.DonateToGuildResponse;
import com.game.proto.guild.GetGuildRankByGuildRequest;
import com.game.proto.guild.GetGuildRankByGuildResponse;
import com.game.proto.guild.GetGuildRankRequest;
import com.game.proto.guild.GetGuildRankResponse;
import com.game.proto.guild.GetPlayerGuildRequest;
import com.game.proto.guild.GetPlayerGuildResponse;
import com.game.proto.guild.GuildApplicantView;
import com.game.proto.guild.GuildApplicationView;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.GuildChangedS2C;
import com.game.proto.guild.GuildInfo;
import com.game.proto.guild.GuildMember;
import com.game.proto.guild.GuildRankEntry;
import com.game.proto.guild.KickGuildMemberRequest;
import com.game.proto.guild.KickGuildMemberResponse;
import com.game.proto.guild.LeaveGuildRequest;
import com.game.proto.guild.LeaveGuildResponse;
import com.game.proto.guild.ListGuildApplicationsRequest;
import com.game.proto.guild.ListGuildApplicationsResponse;
import com.game.proto.guild.ListMyGuildApplicationsRequest;
import com.game.proto.guild.ListMyGuildApplicationsResponse;
import com.game.proto.guild.ReviewGuildApplicationRequest;
import com.game.proto.guild.ReviewGuildApplicationResponse;
import com.game.proto.guild.SetAnnouncementRequest;
import com.game.proto.guild.SetAnnouncementResponse;
import com.game.proto.guild.SetGuildMemberRoleRequest;
import com.game.proto.guild.SetGuildMemberRoleResponse;
import com.game.proto.guild.TransferGuildLeaderRequest;
import com.game.proto.guild.TransferGuildLeaderResponse;
import com.game.proto.guild.UpdateGuildScoreRequest;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.CommonErrorTip;
import com.game.table.GuildErrorTip;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 帮会核心端到端（对应 mmorpg robot/guild_smoke_scenario.go，五个同区机器人 A / B / D / E / F 经 gate → xm-guild；
 * guild-spec §11.6）。基线第 0–10 步与管理段 M1–M10 照跑（Go :264-704）：
 * <ol>
 *   <li>第 0 步 预清理：各自撤回遗留的待审申请，在帮就解散（帮主）或退帮（成员），固定标签可重复跑（Go :264-275、:908-948）；</li>
 *   <li>第 1–2 步 A 建帮（请求体 zone_id 故意填错 → 帮会落 A 的归属区、A 是帮主）→ 再建 14000（Go :277-303）；</li>
 *   <li>第 3 步 B 在本区榜上看得到（名次 ≠ 0、名字对）（Go :305-316）；第 4 步跨区：本机切片单 zone，跳过并记观察（D18）；</li>
 *   <li>第 5 步 B 未入帮改公告 14006；第 6 步 B 申请 → 两侧列表各见一条 → A 通过 → B 收 MEMBER_JOINED、
 *       自己查到在册、成员名与帮主名非空（Go :350-428）；</li>
 *   <li>第 7 步 公告写读、700 字节 14011；第 8 步 B 伪造帮主 id 退帮按会话身份受理；
 *       第 9 步 上行 8 → 信封 1003 且分数仍 0（Go :430-492）；</li>
 *   <li>M1–M10 管理段：重复申请只一条、未入帮列待审 14002、批准 / 拒绝推送、拒绝后再审 14018、任命长老
 *       （{@code officer_count == 1 && max_officers == 2}）与幂等任命、长老踢人边界、两次转让与降级、退帮推送（Go :494-680）；</li>
 *   <li>第 10 步 解散 → 14002 / 14007，且删掉 F 的待审申请（Go :682-704）。</li>
 * </ol>
 * Java 增项（钉住契约细节，guild-spec §11.6）：帮名为空 / 25 个汉字 / 含控制字符 → 14009；全角大写的同名 → 14010；任免 role = 2 → 14006；
 * Apply(0) → 14001；Cancel(0) → 14018；Review 申请人 0 → 14018、审批自己 → 14015；Kick(0) → 14014；普通成员列待审 → 14016；
 * 帮主退帮 → 14004；长老解散 → 14005；有待审时帮主的 {@code pending_application_count > 0}、普通成员为 0；GetGuildRank 页长 60 回显 50、
 * 0 回显 20；上行 220 → 信封 1003；DonateToGuild(0) → 14027（4.5 接线后 D13 的 1006 占位撤销；经济本身见 {@link GuildEconomyScenario}）。
 * 另按推送矩阵（§4.3）核对每条推送的 actor / target，
 * 并补两条基线没等的推送：申请 → 帮主收 APPLICATION_RECEIVED、改公告 → 成员收 ANNOUNCEMENT_CHANGED。
 *
 * <p>账号是 run-tag 新号（D18，基线是固定的 robot_9201–9213）。推送按 {@link com.game.robot.client.Inbox} 的到达序：
 * 触发动作之前记 mark、之后从 mark 起找（Go 的 clearPushes / waitPush）。限频（Go :96-111）：同一机器人相邻请求至少隔
 * {@link #REQUEST_SPACING}，同一消息号任意 {@link #SAME_ID_WINDOW} 内至多 {@value #SAME_ID_MAX_IN_WINDOW} 次
 * （第 4 次等最早那次滑出窗口，等价于基线在同号第 4 次前插 1.1 s 退火，也守住 8 / 220 等表外号缺省每秒 3 条）。
 * 等推送 {@link #PUSH_TIMEOUT}，超时即失败（推送丢了是缺陷，不是「慢」）。
 */
public final class GuildScenario {

    private static final String SERVICE = "GuildService";
    private static final String REF = "PARITY「帮会核心」行";
    /** 同一机器人相邻请求的最小间隔（Go guildSmokeRequestSpacing）。 */
    static final Duration REQUEST_SPACING = Duration.ofMillis(300);
    /** 同号退火窗口（Go guildSmokeSameIdCooldown：大于 gate 限频的 1 s 滑动窗口）。 */
    static final Duration SAME_ID_WINDOW = Duration.ofMillis(1100);
    /** 同号在 {@link #SAME_ID_WINDOW} 内至多发几次（gate 表外号缺省每秒 3 条，帮会写 5 条、读 10 条）。 */
    static final int SAME_ID_MAX_IN_WINDOW = 3;
    /** 等一条推送（Go guildSmokePushTimeout）。 */
    static final Duration PUSH_TIMEOUT = Duration.ofSeconds(5);
    /** 超长公告：大于服务端上限 600 字节，整包仍小于 gate 的 1 KB，拒绝才来自 xm-guild 而不是 gate（Go :114）。 */
    static final int OVERSIZE_ANNOUNCEMENT_BYTES = 700;
    /** 客户端可取的最大页长（MaxRankPageSize）与缺省页长（guild-spec §5.6）。 */
    static final int MAX_RANK_PAGE_SIZE = 50;
    static final int DEFAULT_RANK_PAGE_SIZE = 20;
    /** 帮名上限 24 个码点（MaxGuildNameRunes），25 个即非法。 */
    static final int MAX_NAME_CODE_POINTS = 24;
    /** 成员身份持久化编码：0 成员 / 1 长老 / 3 帮主，2 是刻意跳过的空号（guild-spec §2.1）。 */
    static final int ROLE_MEMBER = 0;
    static final int ROLE_OFFICER = 1;
    static final int ROLE_UNASSIGNABLE = 2;
    static final int ROLE_LEADER = 3;
    /**
     * GuildLevel 第 1 级的长老位上限。与基线一样<b>故意</b>写死（Go :127-131）：同时盯住「配表被人改了」与「服务端没按配表算」；
     * GuildScenarioTest 核对它与同步来的配表一致。
     */
    static final int LEVEL1_MAX_OFFICERS = 2;
    /** GuildDonate 表里没有的捐献项（表校验要求 id ≠ 0，GuildEconomyScenarioTest 钉住）。 */
    static final int UNKNOWN_DONATE_ID = 0;

    private static final int TIP_ALREADY_IN_GUILD = GuildErrorTip.guild_error.kGuildAlreadyInGuild_VALUE;
    private static final int TIP_NOT_FOUND = GuildErrorTip.guild_error.kGuildNotFound_VALUE;
    private static final int TIP_NOT_IN_GUILD = GuildErrorTip.guild_error.kGuildNotInGuild_VALUE;
    private static final int TIP_LEADER_CANT_LEAVE = GuildErrorTip.guild_error.kGuildLeaderCantLeave_VALUE;
    private static final int TIP_NOT_LEADER = GuildErrorTip.guild_error.kGuildNotLeader_VALUE;
    private static final int TIP_NO_PERMISSION = GuildErrorTip.guild_error.kGuildNoPermission_VALUE;
    private static final int TIP_NOT_RANKED = GuildErrorTip.guild_error.kGuildNotRanked_VALUE;
    private static final int TIP_NAME_INVALID = GuildErrorTip.guild_error.kGuildNameInvalid_VALUE;
    private static final int TIP_NAME_TAKEN = GuildErrorTip.guild_error.kGuildNameTaken_VALUE;
    private static final int TIP_ANNOUNCEMENT_TOO_LONG = GuildErrorTip.guild_error.kGuildAnnouncementTooLong_VALUE;
    private static final int TIP_HOME_ZONE_UNKNOWN = GuildErrorTip.guild_error.kGuildHomeZoneUnknown_VALUE;
    private static final int TIP_TARGET_NOT_MEMBER = GuildErrorTip.guild_error.kGuildTargetNotMember_VALUE;
    private static final int TIP_CANNOT_TARGET_SELF = GuildErrorTip.guild_error.kGuildCannotTargetSelf_VALUE;
    private static final int TIP_RANK_TOO_LOW = GuildErrorTip.guild_error.kGuildRankTooLow_VALUE;
    private static final int TIP_APPLICATION_NOT_FOUND = GuildErrorTip.guild_error.kGuildApplicationNotFound_VALUE;
    private static final int TIP_ASSET_PENDING = GuildErrorTip.guild_error.kGuildAssetPending_VALUE;
    private static final int TIP_ASSET_REJECTED = GuildErrorTip.guild_error.kGuildAssetRejected_VALUE;
    private static final int TIP_SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    private static final int TIP_FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlayerFlow flow;
    private final String accountA;
    private final String accountB;
    private final String accountD;
    private final String accountE;
    private final String accountF;
    private final int zoneId;
    private final Duration requestTimeout;
    private final int create;
    private final int getPlayerGuild;
    private final int leave;
    private final int disband;
    private final int setAnnouncement;
    private final int setRole;
    private final int kick;
    private final int transfer;
    private final int apply;
    private final int cancel;
    private final int listMine;
    private final int listApplicants;
    private final int review;
    private final int updateScore;
    private final int getRank;
    private final int getRankByGuild;
    private final int notify;
    private final int donate;
    private final CheckReport report = new CheckReport();
    private final List<GameConnection> connections = new ArrayList<>();

    /** @param zoneId 本区（第 1 步断言帮会 zone_id == 建帮者的归属区 == 新号建角所在区） */
    public GuildScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag, int zoneId,
                         Duration requestTimeout) {
        this.flow = flow;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountD = accountName(accountPrefix, runTag, "d");
        this.accountE = accountName(accountPrefix, runTag, "e");
        this.accountF = accountName(accountPrefix, runTag, "f");
        this.zoneId = zoneId;
        this.requestTimeout = requestTimeout;
        this.create = registry.requireId(SERVICE, "CreateGuild");
        this.getPlayerGuild = registry.requireId(SERVICE, "GetPlayerGuild");
        this.leave = registry.requireId(SERVICE, "LeaveGuild");
        this.disband = registry.requireId(SERVICE, "DisbandGuild");
        this.setAnnouncement = registry.requireId(SERVICE, "SetAnnouncement");
        this.setRole = registry.requireId(SERVICE, "SetGuildMemberRole");
        this.kick = registry.requireId(SERVICE, "KickGuildMember");
        this.transfer = registry.requireId(SERVICE, "TransferGuildLeader");
        this.apply = registry.requireId(SERVICE, "ApplyJoinGuild");
        this.cancel = registry.requireId(SERVICE, "CancelGuildApplication");
        this.listMine = registry.requireId(SERVICE, "ListMyGuildApplications");
        this.listApplicants = registry.requireId(SERVICE, "ListGuildApplications");
        this.review = registry.requireId(SERVICE, "ReviewGuildApplication");
        this.updateScore = registry.requireId(SERVICE, "UpdateGuildScore");
        this.getRank = registry.requireId(SERVICE, "GetGuildRank");
        this.getRankByGuild = registry.requireId(SERVICE, "GetGuildRankByGuild");
        this.notify = registry.requireId(SERVICE, "NotifyGuildChanged");
        this.donate = registry.requireId(SERVICE, "DonateToGuild");
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "gd" + runTag + "_" + suffix;
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
            connections.forEach(GameConnection::close);
        }
        return report;
    }

    private void runChecks() throws RobotException {
        Bot a = enter("A", accountA);
        Bot b = enter("B", accountB);
        Bot d = enter("D", accountD);
        Bot e = enter("E", accountE);
        Bot f = enter("F", accountF);
        List<Bot> bots = List.of(a, b, d, e, f);
        report.note("A=" + uid(a.id()) + " B=" + uid(b.id()) + " D=" + uid(d.id()) + " E=" + uid(e.id()) + " F=" + uid(f.id())
                + " zone=" + zoneId);

        // ---- 第 0 步：回到「不在任何帮会、也没有待审申请」（先撤申请再退帮，Go :264-275） ----
        List<String> leftovers = new ArrayList<>();
        for (Bot bot : bots) {
            String problem = cancelAllApplications(bot);
            if (problem.isEmpty()) {
                problem = leaveAnyGuild(bot);
            }
            if (!problem.isEmpty()) {
                leftovers.add(bot.name + "：" + problem);
            }
        }
        must(leftovers.isEmpty(), "第 0 步 预清理：五人都不在帮、没有待审申请", String.join("；", leftovers));

        // ---- 第 1 步：A 建帮，zone 由服务端按归属区决定 ----
        String nonce = nonce();
        String guildName = "烟测" + nonce;
        int spoofedZone = zoneId + 1000;
        CreateGuildResponse created = a.call(create, CreateGuildRequest.newBuilder().setName(guildName).setZoneId(spoofedZone)
                .build(), CreateGuildResponse.parser());
        GuildInfo guild = created.getGuild();
        long gid = guild.getGuildId();
        must(tipOf(created) == 0 && gid != 0 && guild.getLeaderId() == a.id() && roleOf(guild, a.id()) == ROLE_LEADER
                        && guild.getName().equals(guildName),
                "第 1 步 A 建帮：受理，A 是帮主、帮名原样", describe(created));
        report.check(guild.getZoneId() == zoneId, "第 1 步 帮会 zone_id == A 的归属区（忽略请求体 zone_id=" + spoofedZone + "）",
                "zone_id=" + guild.getZoneId() + "，期望 " + zoneId, REF);

        // ---- 第 2 步：已在帮会里不能再建 ----
        expect(a, create, CreateGuildRequest.newBuilder().setName(guildName + "二").build(), CreateGuildResponse.parser(),
                TIP_ALREADY_IN_GUILD, "第 2 步 A 再建一个 → 14000");

        // Java 增项：帮名校验先于一切外部查询（§2.4、§3.2）；规范化撞名在事务里判、失败不留成员行（B 之后照常申请）
        expect(b, create, CreateGuildRequest.newBuilder().setName("").build(), CreateGuildResponse.parser(),
                TIP_NAME_INVALID, "帮名为空 → 14009");
        expect(b, create, CreateGuildRequest.newBuilder().setName("帮".repeat(MAX_NAME_CODE_POINTS + 1)).build(),
                CreateGuildResponse.parser(), TIP_NAME_INVALID, "帮名 " + (MAX_NAME_CODE_POINTS + 1) + " 个汉字 → 14009");
        expect(b, create, CreateGuildRequest.newBuilder().setName("烟测\u0001" + nonce).build(), CreateGuildResponse.parser(),
                TIP_NAME_INVALID, "帮名含控制字符 U+0001 → 14009");
        String fullWidth = "烟测" + fullWidthUpper(nonce);
        expect(b, create, CreateGuildRequest.newBuilder().setName(fullWidth).build(), CreateGuildResponse.parser(),
                TIP_NAME_TAKEN, "全角大写的同名「" + fullWidth + "」与「" + guildName + "」撞名（NFKC + 小写）→ 14010");

        // ---- 第 3 步：同区 B 在本区榜上看得到 ----
        GetGuildRankByGuildResponse sameZone = b.call(getRankByGuild, GetGuildRankByGuildRequest.newBuilder().setGuildId(gid).build(),
                GetGuildRankByGuildResponse.parser());
        report.check(tipOf(sameZone) == 0 && sameZone.getEntry().getRank() != 0 && sameZone.getEntry().getName().equals(guildName),
                "第 3 步 B 在本区榜上看到帮会（名次 ≠ 0、名字对）", describe(sameZone), REF);

        // Java 增项：页长夹到 50、0 回显 20、page 0 回显 1；请求体 zone_id 被归属区覆盖（伪造的区没有帮会，total 必 ≥ 1）
        GetGuildRankResponse page60 = b.call(getRank, GetGuildRankRequest.newBuilder().setPage(1).setPageSize(60)
                .setZoneId(spoofedZone).build(), GetGuildRankResponse.parser());
        boolean listed = page60.getEntriesList().stream().anyMatch(en -> en.getGuildId() == gid);
        report.check(tipOf(page60) == 0 && page60.getPageSize() == MAX_RANK_PAGE_SIZE && page60.getPage() == 1
                        && page60.getEntriesCount() <= MAX_RANK_PAGE_SIZE && page60.getTotalCount() >= 1
                        && (listed || page60.getTotalCount() > MAX_RANK_PAGE_SIZE),
                "GetGuildRank 页长 60 → 回显 50；请求体 zone 被忽略、本区榜里有该帮", describe(page60) + " 含本帮=" + listed, REF);
        GetGuildRankResponse page0 = b.call(getRank, GetGuildRankRequest.getDefaultInstance(), GetGuildRankResponse.parser());
        report.check(tipOf(page0) == 0 && page0.getPageSize() == DEFAULT_RANK_PAGE_SIZE && page0.getPage() == 1,
                "GetGuildRank 页长 0 / 页 0 → 回显 20 / 1", describe(page0), REF);

        report.note("第 4 步跨区（别区 GetGuild 14001、名次 14007、榜单不可见、申请 14001、同名 14010）：本机切片只有一个 zone，跳过（D18）；"
                + "跨区可见性由 xm-guild 服务单测与 MySQL 测试覆盖");

        // ---- 第 5 步：非成员不能改公告 ----
        expect(b, setAnnouncement, SetAnnouncementRequest.newBuilder().setGuildId(gid).setAnnouncement("非成员不该能改").build(),
                SetAnnouncementResponse.parser(), TIP_NO_PERMISSION, "第 5 步 B 未入帮改公告 → 14006");

        // ---- 第 6 步：B 申请入帮，A 审批通过 ----
        expect(b, apply, ApplyJoinGuildRequest.newBuilder().setGuildId(gid).build(), ApplyJoinGuildResponse.parser(), 0,
                "第 6 步 B 申请入帮");
        ListMyGuildApplicationsResponse bApps = b.call(listMine, ListMyGuildApplicationsRequest.getDefaultInstance(),
                ListMyGuildApplicationsResponse.parser());
        report.check(tipOf(bApps) == 0 && hasApplication(bApps.getApplicationsList(), gid),
                "第 6 步 B 的待审申请里有该帮", describeTip(bApps) + " " + bApps.getApplicationsCount() + " 条", REF);
        ListGuildApplicationsResponse applicants = a.call(listApplicants, ListGuildApplicationsRequest.getDefaultInstance(),
                ListGuildApplicationsResponse.parser());
        report.check(tipOf(applicants) == 0 && hasApplicant(applicants.getApplicantsList(), b.id()),
                "第 6 步 A 的待审名单里有 B", describeTip(applicants) + " " + applicants.getApplicantsCount() + " 条", REF);
        int bMark = b.mark();
        ReviewGuildApplicationResponse approvedB = a.call(review, ReviewGuildApplicationRequest.newBuilder()
                .setApplicantPlayerId(b.id()).setApprove(true).build(), ReviewGuildApplicationResponse.parser());
        must(tipOf(approvedB) == 0 && roleOf(approvedB.getGuild(), b.id()) == ROLE_MEMBER && roleOf(approvedB.getGuild(), a.id()) == ROLE_LEADER,
                "第 6 步 A 通过 B：回包快照里 B 是成员、含操作者 A", describe(approvedB));
        expectPush(b, bMark, GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_JOINED, gid, a.id(), b.id(),
                "第 6 步 B 收到 MEMBER_JOINED（actor = A、target = B）");
        GuildInfo bGuild = myGuild(b);
        must(bGuild.getGuildId() == gid && roleOf(bGuild, b.id()) == ROLE_MEMBER, "第 6 步 B 自己查到在册、是成员", describe(bGuild));
        List<String> unnamed = unnamedMembers(bGuild);
        report.check(unnamed.isEmpty() && !bGuild.getLeaderName().isEmpty(), "第 6 步 成员名与帮主名都非空（名字读 xm_java.player，D5）",
                unnamed.isEmpty() ? "leader_name=" + bGuild.getLeaderName() : "没有名字的成员 " + unnamed, REF);

        // ---- 第 7 步：公告 ----
        String announcement = "烟测公告 " + nonce();
        bMark = b.mark();
        SetAnnouncementResponse announced = a.call(setAnnouncement, SetAnnouncementRequest.newBuilder().setGuildId(gid)
                .setAnnouncement(announcement).build(), SetAnnouncementResponse.parser());
        report.check(tipOf(announced) == 0 && announced.getGuild().getAnnouncement().equals(announcement),
                "第 7 步 A 改公告：受理，回包快照带新公告", describeTip(announced), REF);
        expectPush(b, bMark, GuildChangeKind.GUILD_CHANGE_KIND_ANNOUNCEMENT_CHANGED, gid, a.id(), 0,
                "第 7 步 B 收到 ANNOUNCEMENT_CHANGED（actor = A、target = 0）");
        bGuild = myGuild(b);
        report.check(bGuild.getAnnouncement().equals(announcement), "第 7 步 B 读到新公告",
                "读到「" + bGuild.getAnnouncement() + "」", REF);
        expect(a, setAnnouncement, SetAnnouncementRequest.newBuilder().setGuildId(gid)
                        .setAnnouncement("x".repeat(OVERSIZE_ANNOUNCEMENT_BYTES)).build(), SetAnnouncementResponse.parser(),
                TIP_ANNOUNCEMENT_TOO_LONG, "第 7 步 " + OVERSIZE_ANNOUNCEMENT_BYTES + " 字节公告 → 14011");

        // ---- 第 8 步：请求体伪造 player_id 不生效 ----
        LeaveGuildResponse spoofLeave = b.call(leave, LeaveGuildRequest.newBuilder().setPlayerId(a.id()).build(),
                LeaveGuildResponse.parser());
        must(tipOf(spoofLeave) == 0, "第 8 步 B 伪造帮主 A 的 player_id 退帮 → 按会话身份让 B 退出（不是 14004）",
                describeTip(spoofLeave) + (tipOf(spoofLeave) == TIP_LEADER_CANT_LEAVE ? "：服务端按请求体身份处理了" : ""));
        expect(b, getPlayerGuild, GetPlayerGuildRequest.getDefaultInstance(), GetPlayerGuildResponse.parser(), TIP_NOT_IN_GUILD,
                "第 8 步 B 退帮后查自己的帮会 → 14002");
        GuildInfo aGuild = myGuild(a);
        report.check(aGuild.getLeaderId() == a.id() && aGuild.getMembersCount() == 1, "第 8 步 A 仍是帮主、只剩 1 人",
                describe(aGuild), REF);

        // ---- 第 9 步：内部方法 UpdateGuildScore 对客户端关闭（send + await，同 FriendScenario 上行 235） ----
        expectEnvelope(a, updateScore, UpdateGuildScoreRequest.newBuilder().setGuildId(gid).setScore(999_999).setZoneId(zoneId).build(),
                "第 9 步 上行 8 UpdateGuildScore → 信封 1003、没有业务回包");
        GetGuildRankByGuildResponse scoreAfter = a.call(getRankByGuild, GetGuildRankByGuildRequest.newBuilder().setGuildId(gid).build(),
                GetGuildRankByGuildResponse.parser());
        report.check(tipOf(scoreAfter) == 0 && scoreAfter.getEntry().getScore() == 0, "第 9 步 之后查名次成功且积分仍是 0",
                describe(scoreAfter), REF);
        report.note("第 9 步只能观察到信封拒绝与积分未变；拒绝原因（会话方法白名单）由 xm-guild 指标 forbidden 与 GuildDispatcherTest 核对");
        // Java 增项：上行推送占位 220 → 信封 1003（220 的应答是 Empty，tip ≠ 0 时 gate 照样回信封）
        expectEnvelope(a, notify, GuildChangedS2C.newBuilder().setKind(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_JOINED)
                        .setGuildId(gid).setActorPlayerId(a.id()).setTargetPlayerId(a.id()).build(),
                "上行 220 NotifyGuildChanged → 信封 1003、没有业务回包");

        // ---- 管理段 M1–M10：此刻帮里只有帮主 A，D / E / F 都不在任何帮会 ----
        // M1：D、E、F 各申请；F 再申请一次（同帮重复申请是刷新有效期，不新增行）
        int aMark = a.mark();
        expect(d, apply, ApplyJoinGuildRequest.newBuilder().setGuildId(gid).build(), ApplyJoinGuildResponse.parser(), 0,
                "M1 D 申请入帮");
        expectPush(a, aMark, GuildChangeKind.GUILD_CHANGE_KIND_APPLICATION_RECEIVED, gid, d.id(), d.id(),
                "M1 帮主 A 收到 D 的 APPLICATION_RECEIVED（actor = target = D）");
        expect(e, apply, ApplyJoinGuildRequest.newBuilder().setGuildId(gid).build(), ApplyJoinGuildResponse.parser(), 0,
                "M1 E 申请入帮");
        expect(f, apply, ApplyJoinGuildRequest.newBuilder().setGuildId(gid).build(), ApplyJoinGuildResponse.parser(), 0,
                "M1 F 申请入帮");
        expect(f, apply, ApplyJoinGuildRequest.newBuilder().setGuildId(gid).build(), ApplyJoinGuildResponse.parser(), 0,
                "M1 F 重复申请同一帮会 → 受理（刷新有效期）");
        ListMyGuildApplicationsResponse fApps = f.call(listMine, ListMyGuildApplicationsRequest.getDefaultInstance(),
                ListMyGuildApplicationsResponse.parser());
        report.check(tipOf(fApps) == 0 && fApps.getApplicationsCount() == 1 && hasApplication(fApps.getApplicationsList(), gid),
                "M1 F 连申请两次后待审恰好 1 条且指向该帮", describeTip(fApps) + " " + fApps.getApplicationsCount() + " 条", REF);

        // M2：待审名单是管理侧信息
        expect(e, listApplicants, ListGuildApplicationsRequest.getDefaultInstance(), ListGuildApplicationsResponse.parser(),
                TIP_NOT_IN_GUILD, "M2 未入帮的 E 列帮会待审名单 → 14002");
        // Java 增项：入口参数校验
        expect(e, apply, ApplyJoinGuildRequest.newBuilder().setGuildId(0).build(), ApplyJoinGuildResponse.parser(), TIP_NOT_FOUND,
                "ApplyJoinGuild(guild_id = 0) → 14001");
        expect(e, cancel, CancelGuildApplicationRequest.newBuilder().setGuildId(0).build(), CancelGuildApplicationResponse.parser(),
                TIP_APPLICATION_NOT_FOUND, "CancelGuildApplication(guild_id = 0) → 14018");

        // M3：A 审批 D、E 通过；D 收 MEMBER_JOINED
        int dMark = d.mark();
        ReviewGuildApplicationResponse approvedD = a.call(review, ReviewGuildApplicationRequest.newBuilder()
                .setApplicantPlayerId(d.id()).setApprove(true).build(), ReviewGuildApplicationResponse.parser());
        must(tipOf(approvedD) == 0 && roleOf(approvedD.getGuild(), d.id()) == ROLE_MEMBER, "M3 A 通过 D：回包快照里 D 是成员",
                describe(approvedD));
        expectPush(d, dMark, GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_JOINED, gid, a.id(), d.id(),
                "M3 D 收到 MEMBER_JOINED（actor = A、target = D）");
        // Java 增项：待审数只对长老 / 帮主非 0（此刻 E、F 待审）
        GuildInfo leaderView = myGuild(a);
        report.check(leaderView.getPendingApplicationCount() > 0, "有待审时帮主 A 的 GetPlayerGuild 里 pending_application_count > 0",
                "pending_application_count=" + leaderView.getPendingApplicationCount(), REF);
        GuildInfo memberView = myGuild(d);
        report.check(memberView.getGuildId() == gid && roleOf(memberView, d.id()) == ROLE_MEMBER
                        && memberView.getPendingApplicationCount() == 0,
                "普通成员 D 的 GetPlayerGuild 里 pending_application_count == 0", describe(memberView), REF);
        expect(d, listApplicants, ListGuildApplicationsRequest.getDefaultInstance(), ListGuildApplicationsResponse.parser(),
                TIP_RANK_TOO_LOW, "普通成员 D 列待审 → 14016");
        expect(a, review, ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(0).setApprove(true).build(),
                ReviewGuildApplicationResponse.parser(), TIP_APPLICATION_NOT_FOUND, "Review(申请人 = 0) → 14018");
        expect(a, review, ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(a.id()).setApprove(true).build(),
                ReviewGuildApplicationResponse.parser(), TIP_CANNOT_TARGET_SELF, "审批自己 → 14015");
        ReviewGuildApplicationResponse approvedE = a.call(review, ReviewGuildApplicationRequest.newBuilder()
                .setApplicantPlayerId(e.id()).setApprove(true).build(), ReviewGuildApplicationResponse.parser());
        must(tipOf(approvedE) == 0 && roleOf(approvedE.getGuild(), e.id()) == ROLE_MEMBER, "M3 A 通过 E：回包快照里 E 是成员",
                describe(approvedE));

        // M4：拒绝只推给申请人；拒绝删掉申请行，再审 14018
        int fMark = f.mark();
        expect(a, review, ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(f.id()).setApprove(false).build(),
                ReviewGuildApplicationResponse.parser(), 0, "M4 A 拒绝 F");
        expectPush(f, fMark, GuildChangeKind.GUILD_CHANGE_KIND_APPLICATION_REJECTED, gid, a.id(), f.id(),
                "M4 F 收到 APPLICATION_REJECTED（actor = A、target = F）");
        fApps = f.call(listMine, ListMyGuildApplicationsRequest.getDefaultInstance(), ListMyGuildApplicationsResponse.parser());
        report.check(tipOf(fApps) == 0 && fApps.getApplicationsCount() == 0, "M4 F 被拒后待审清空",
                describeTip(fApps) + " " + fApps.getApplicationsCount() + " 条", REF);
        expect(a, review, ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(f.id()).setApprove(false).build(),
                ReviewGuildApplicationResponse.parser(), TIP_APPLICATION_NOT_FOUND, "M4 再审同一条申请 → 14018");

        // M5：任命 D 为长老（第二次幂等、不推送）
        dMark = d.mark();
        SetGuildMemberRoleResponse promoted = a.call(setRole, SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(d.id())
                .setRole(ROLE_OFFICER).build(), SetGuildMemberRoleResponse.parser());
        String officerProblem = tipOf(promoted) != 0 ? describeTip(promoted) : officerProblem(promoted.getGuild(), d.id());
        must(officerProblem.isEmpty(), "M5 A 任命 D 为长老：D role = 1、officer_count = 1、max_officers = " + LEVEL1_MAX_OFFICERS,
                officerProblem);
        expectPush(d, dMark, GuildChangeKind.GUILD_CHANGE_KIND_ROLE_CHANGED, gid, a.id(), d.id(),
                "M5 D 收到 ROLE_CHANGED（actor = A、target = D）");
        SetGuildMemberRoleResponse promotedAgain = a.call(setRole, SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(d.id())
                .setRole(ROLE_OFFICER).build(), SetGuildMemberRoleResponse.parser());
        String againProblem = tipOf(promotedAgain) != 0 ? describeTip(promotedAgain) : officerProblem(promotedAgain.getGuild(), d.id());
        report.check(againProblem.isEmpty(), "M5 重复任命同一职位 → 幂等受理、快照不变", againProblem, REF);
        // Java 增项：管理入口与权限矩阵（§2.2）
        expect(a, setRole, SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(d.id()).setRole(ROLE_UNASSIGNABLE).build(),
                SetGuildMemberRoleResponse.parser(), TIP_NO_PERMISSION, "任免 role = 2（空号）→ 14006");
        expect(a, kick, KickGuildMemberRequest.newBuilder().setTargetPlayerId(0).build(), KickGuildMemberResponse.parser(),
                TIP_TARGET_NOT_MEMBER, "KickGuildMember(target = 0) → 14014");
        expect(a, leave, LeaveGuildRequest.getDefaultInstance(), LeaveGuildResponse.parser(), TIP_LEADER_CANT_LEAVE,
                "帮主 A 退帮 → 14004");
        expect(d, disband, DisbandGuildRequest.getDefaultInstance(), DisbandGuildResponse.parser(), TIP_NOT_LEADER,
                "长老 D 解散 → 14005");

        // M6：长老的权限边界
        int eMark = e.mark();
        KickGuildMemberResponse kicked = d.call(kick, KickGuildMemberRequest.newBuilder().setTargetPlayerId(e.id()).build(),
                KickGuildMemberResponse.parser());
        report.check(tipOf(kicked) == 0 && roleOf(kicked.getGuild(), e.id()) < 0, "M6 长老 D 踢普通成员 E：受理，快照里没有 E",
                describe(kicked), REF);
        expectPush(e, eMark, GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_KICKED, gid, d.id(), e.id(),
                "M6 被踢的 E 收到 MEMBER_KICKED（actor = D、target = E）");
        expect(e, getPlayerGuild, GetPlayerGuildRequest.getDefaultInstance(), GetPlayerGuildResponse.parser(), TIP_NOT_IN_GUILD,
                "M6 E 被踢后查自己的帮会 → 14002");
        expect(d, kick, KickGuildMemberRequest.newBuilder().setTargetPlayerId(a.id()).build(), KickGuildMemberResponse.parser(),
                TIP_RANK_TOO_LOW, "M6 长老踢帮主 → 14016");
        expect(d, kick, KickGuildMemberRequest.newBuilder().setTargetPlayerId(d.id()).build(), KickGuildMemberResponse.parser(),
                TIP_CANNOT_TARGET_SELF, "M6 长老踢自己 → 14015");
        expect(d, setRole, SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(a.id()).setRole(ROLE_OFFICER).build(),
                SetGuildMemberRoleResponse.parser(), TIP_RANK_TOO_LOW, "M6 长老任免帮主 → 14016");
        expect(d, kick, KickGuildMemberRequest.newBuilder().setTargetPlayerId(e.id()).build(), KickGuildMemberResponse.parser(),
                TIP_TARGET_NOT_MEMBER, "M6 再踢已离帮的 E → 14014");

        // M7：转让给 D（D 腾出长老位，A 降为长老）
        dMark = d.mark();
        TransferGuildLeaderResponse toD = a.call(transfer, TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(d.id()).build(),
                TransferGuildLeaderResponse.parser());
        String toDProblem = tipOf(toD) != 0 ? describeTip(toD) : leadershipProblem(toD.getGuild(), d.id(), a.id());
        must(toDProblem.isEmpty(), "M7 A 转让帮主给 D：D 是帮主（leader_id 与成员表一致）、A 降为长老", toDProblem);
        expectPush(d, dMark, GuildChangeKind.GUILD_CHANGE_KIND_LEADER_TRANSFERRED, gid, a.id(), d.id(),
                "M7 D 收到 LEADER_TRANSFERRED（actor = A、target = D）");

        // M8：转回 A（恢复成「A 是帮主」，第 10 步才解散得了）
        TransferGuildLeaderResponse toA = d.call(transfer, TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(a.id()).build(),
                TransferGuildLeaderResponse.parser());
        String toAProblem = tipOf(toA) != 0 ? describeTip(toA) : leadershipProblem(toA.getGuild(), a.id(), d.id());
        must(toAProblem.isEmpty(), "M8 D 转让帮主回 A：A 是帮主、D 降为长老", toAProblem);

        // M9：D 退帮，留守的 A 收到 MEMBER_LEFT
        aMark = a.mark();
        expect(d, leave, LeaveGuildRequest.getDefaultInstance(), LeaveGuildResponse.parser(), 0, "M9 D 退帮");
        expectPush(a, aMark, GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_LEFT, gid, d.id(), d.id(),
                "M9 A 收到 MEMBER_LEFT（actor = target = D）");

        // M10 前半：F 重新申请，给第 10 步的解散留一条待审行（不断言 APPLICATION_RECEIVED：60 s 冷却，Go :665-671）
        expect(f, apply, ApplyJoinGuildRequest.newBuilder().setGuildId(gid).build(), ApplyJoinGuildResponse.parser(), 0,
                "M10 F 重新申请");

        // Java 增项：经济号已由 4.5 接线（D13 的 in-band 1006 占位撤销，guild-economy-spec §7.9）。用配表里没有的捐献项 0：
        // 判在发号与写行之前，不动任何资产——通道开着回 14027「donate option not found」，关着更早回 14026（economy_logic.go:718-726）
        DonateToGuildResponse donated = a.call(donate, DonateToGuildRequest.newBuilder().setDonateId(UNKNOWN_DONATE_ID).build(),
                DonateToGuildResponse.parser());
        int donateTip = tipOf(donated);
        report.check((donateTip == TIP_ASSET_REJECTED || donateTip == TIP_ASSET_PENDING) && !donated.hasDonation(),
                "DonateToGuild(donate_id = 0) → 14027（通道关闭时 14026），不再是 4.4 占位的 1006、不带捐献视图",
                describeTip(donated) + (donateTip == TIP_FEATURE_UNAVAILABLE ? "：xm-guild 还是 4.4 的占位派发" : ""), REF);

        // ---- 第 10 步：解散 ----
        expect(a, disband, DisbandGuildRequest.getDefaultInstance(), DisbandGuildResponse.parser(), 0, "第 10 步 A 解散");
        expect(a, getPlayerGuild, GetPlayerGuildRequest.getDefaultInstance(), GetPlayerGuildResponse.parser(), TIP_NOT_IN_GUILD,
                "第 10 步 解散后 A 查自己的帮会 → 14002");
        expect(a, getRankByGuild, GetGuildRankByGuildRequest.newBuilder().setGuildId(gid).build(),
                GetGuildRankByGuildResponse.parser(), TIP_NOT_RANKED, "第 10 步 解散后帮会从本区榜消失 → 14007");
        // M10 后半：解散连带删掉该帮的全部待审申请
        fApps = f.call(listMine, ListMyGuildApplicationsRequest.getDefaultInstance(), ListMyGuildApplicationsResponse.parser());
        report.check(tipOf(fApps) == 0 && fApps.getApplicationsCount() == 0, "M10 解散后 F 的待审申请清空",
                describeTip(fApps) + " " + fApps.getApplicationsCount() + " 条", REF);

        report.note("guild_id=" + uid(gid) + " zone=" + zoneId + " leader=A officer=D kicked=E rejected=F（同基线 GUILD_MGMT_OK / "
                + "GUILD_SMOKE_OK 的字段；单 zone，没有 C）");
    }

    // ================================================================ 预清理（Go cancelAllApplications / leaveAnyGuild）

    /**
     * 撤回本人全部待审申请。只撤刚列出来的那几条，每条都必须受理：撤不掉说明列表与服务端状态对不上（Go :908-927）。
     *
     * @return 空串 = 已没有待审申请；否则是失败原因
     */
    private String cancelAllApplications(Bot bot) throws RobotException {
        ListMyGuildApplicationsResponse mine = bot.call(listMine, ListMyGuildApplicationsRequest.getDefaultInstance(),
                ListMyGuildApplicationsResponse.parser());
        if (tipOf(mine) != 0) {
            return "列本人申请 " + describeTip(mine) + hint(tipOf(mine));
        }
        for (GuildApplicationView application : mine.getApplicationsList()) {
            CancelGuildApplicationResponse cancelled = bot.call(cancel, CancelGuildApplicationRequest.newBuilder()
                    .setGuildId(application.getGuildId()).build(), CancelGuildApplicationResponse.parser());
            if (tipOf(cancelled) != 0) {
                return "撤回遗留申请（帮会 " + uid(application.getGuildId()) + "）" + describeTip(cancelled);
            }
        }
        return "";
    }

    /**
     * 把机器人带回「不在任何帮会」：最多 3 轮 GetPlayerGuild → 帮主解散 / 成员退帮（Go :929-948）。
     *
     * @return 空串 = 已不在帮；否则是失败原因
     */
    private String leaveAnyGuild(Bot bot) throws RobotException {
        for (int attempt = 0; attempt < 3; attempt++) {
            GetPlayerGuildResponse mine = bot.call(getPlayerGuild, GetPlayerGuildRequest.getDefaultInstance(),
                    GetPlayerGuildResponse.parser());
            if (tipOf(mine) == TIP_NOT_IN_GUILD) {
                return "";
            }
            if (tipOf(mine) != 0) {
                return "GetPlayerGuild " + describeTip(mine) + hint(tipOf(mine));
            }
            Message response = mine.getGuild().getLeaderId() == bot.id()
                    ? bot.call(disband, DisbandGuildRequest.getDefaultInstance(), DisbandGuildResponse.parser())
                    : bot.call(leave, LeaveGuildRequest.getDefaultInstance(), LeaveGuildResponse.parser());
            if (tipOf(response) != 0) {
                return "离开残留帮会 " + uid(mine.getGuild().getGuildId()) + "：" + describeTip(response);
            }
        }
        return "连续 3 次退帮 / 解散后仍在帮会里";
    }

    // ================================================================ 请求与断言

    /** GetPlayerGuild：必须受理且带帮会（不在帮会里也算错误，Go myGuild）。 */
    private GuildInfo myGuild(Bot bot) throws RobotException {
        GetPlayerGuildResponse response = bot.call(getPlayerGuild, GetPlayerGuildRequest.getDefaultInstance(),
                GetPlayerGuildResponse.parser());
        if (tipOf(response) != 0 || !response.hasGuild()) {
            throw new RobotException(bot.name + " GetPlayerGuild 期望受理且带帮会，实得 " + describeTip(response) + hint(tipOf(response)));
        }
        return response.getGuild();
    }

    /** 发请求并断言业务 tip（应答体 error_message.id）；信封拒绝与超时抛出：它们说明请求没到 xm-guild 业务逻辑。 */
    private <T extends Message> T expect(Bot bot, int messageId, Message request, Parser<T> parser, int want, String name)
            throws RobotException {
        T response = bot.call(messageId, request, parser);
        int got = tipOf(response);
        report.check(got == want, name, describeTip(response) + (got == want ? "" : "，期望 tip=" + want + hint(got)), REF);
        return response;
    }

    /** 只发不等业务回包：断言收到同 (message_id, id) 的信封 1003、消息体为空（同 FriendScenario 上行 235）。 */
    private void expectEnvelope(Bot bot, int messageId, Message body, String name) throws RobotException {
        int mark = bot.mark();
        long requestId = bot.send(messageId, body);
        Optional<Received> reply = bot.connection().await(mark,
                r -> r.messageId() == messageId && r.requestId() == requestId, requestTimeout);
        report.check(reply.isPresent() && reply.get().envelopeTipId() == TIP_SERVICE_UNAVAILABLE
                        && reply.get().content().getSerializedMessage().isEmpty(), name,
                reply.map(r -> "信封 tip=" + r.envelopeTipId() + " 消息体 " + r.content().getSerializedMessage().size() + " 字节")
                        .orElse(requestTimeout.toMillis() + " ms 内没有收到 id=" + requestId + " 的回包" + bot.describeSince(mark)),
                REF);
    }

    /** 等一条 (kind, guild_id) 的推送，并核对 actor / target（推送矩阵 §4.3）。 */
    private void expectPush(Bot bot, int mark, GuildChangeKind kind, long guildId, long actor, long target, String name)
            throws RobotException {
        Optional<GuildChangedS2C> change = awaitChange(bot, mark, kind, guildId);
        report.check(change.isPresent() && change.get().getActorPlayerId() == actor && change.get().getTargetPlayerId() == target,
                name, change.map(GuildScenario::describe).orElse(PUSH_TIMEOUT.toSeconds() + " s 内没收到；此刻收到的推送：["
                        + describePushes(bot, mark) + "]" + bot.describeSince(mark)), REF);
    }

    /** 从 {@code mark} 起等第一条 id = 0、消息号 220、kind 与 guild_id 相符的推送。 */
    private Optional<GuildChangedS2C> awaitChange(Bot bot, int mark, GuildChangeKind kind, long guildId) throws RobotException {
        Optional<Received> push = bot.connection().await(mark, r -> {
            if (r.messageId() != notify || r.requestId() != 0) {
                return false;
            }
            GuildChangedS2C change = r.parseOrNull(GuildChangedS2C.parser());
            return change != null && change.getKind() == kind && change.getGuildId() == guildId;
        }, PUSH_TIMEOUT);
        return push.map(r -> r.parseOrNull(GuildChangedS2C.parser()));
    }

    /** 此刻留底的 220 推送（只用于失败信息：收到别的 kind 说明推送矩阵错了，一条都没有才是投递链路的问题）。 */
    private String describePushes(Bot bot, int mark) {
        List<String> parts = new ArrayList<>();
        for (Received r : bot.connection().inbox().snapshot(mark)) {
            if (r.messageId() == notify && r.requestId() == 0) {
                GuildChangedS2C change = r.parseOrNull(GuildChangedS2C.parser());
                parts.add(change == null ? "解不开的 220" : describe(change));
            }
        }
        return parts.isEmpty() ? "空" : String.join(" ", parts);
    }

    /** 后续步骤依赖的检查：不通过即中止（记一条失败后抛出）。 */
    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) {
            throw new RobotException("「" + name + "」未通过，后续步骤依赖它");
        }
    }

    private Bot enter(String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        connections.add(player.connection());
        return new Bot(name, player, requestTimeout);
    }

    // ================================================================ 纯函数（GuildScenarioTest 钉住）

    /** 成员的 role；不在册返回 -1。 */
    static int roleOf(GuildInfo guild, long playerId) {
        for (GuildMember member : guild.getMembersList()) {
            if (member.getPlayerId() == playerId) {
                return member.getRole();
            }
        }
        return -1;
    }

    /**
     * 快照里 target 是长老，且长老数与长老位上限都对得上（Go guildSmokeCheckOfficer：只查 role 看不出「officer_count 算错」）。
     *
     * @return 空串 = 符合；否则是不符之处
     */
    static String officerProblem(GuildInfo guild, long target) {
        int role = roleOf(guild, target);
        if (role != ROLE_OFFICER) {
            return "快照里 " + uid(target) + " 的 role=" + role + "（-1 = 不在册），期望长老 " + ROLE_OFFICER;
        }
        if (guild.getOfficerCount() != 1) {
            return "officer_count=" + guild.getOfficerCount() + "，期望 1";
        }
        if (guild.getMaxOfficers() != LEVEL1_MAX_OFFICERS) {
            return "max_officers=" + guild.getMaxOfficers() + "，期望 " + LEVEL1_MAX_OFFICERS + "（GuildLevel 第 1 级）";
        }
        return "";
    }

    /**
     * 转让之后：newLeader 既是 leader_id 也在成员表里是帮主（双存储一致），旧帮主 demoted 降为长老（Go guildSmokeCheckLeadership）。
     *
     * @return 空串 = 符合；否则是不符之处
     */
    static String leadershipProblem(GuildInfo guild, long newLeader, long demoted) {
        if (guild.getLeaderId() != newLeader) {
            return "leader_id=" + uid(guild.getLeaderId()) + "，期望 " + uid(newLeader);
        }
        int leaderRole = roleOf(guild, newLeader);
        if (leaderRole != ROLE_LEADER) {
            return "新帮主 " + uid(newLeader) + " 在册 role=" + leaderRole + "，期望 " + ROLE_LEADER;
        }
        int demotedRole = roleOf(guild, demoted);
        if (demotedRole != ROLE_OFFICER) {
            return "原帮主 " + uid(demoted) + " 在册 role=" + demotedRole + "，期望降为长老 " + ROLE_OFFICER;
        }
        return "";
    }

    /** 名字为空的成员（无符号十进制）。 */
    static List<String> unnamedMembers(GuildInfo guild) {
        List<String> out = new ArrayList<>();
        for (GuildMember member : guild.getMembersList()) {
            if (member.getName().isEmpty()) {
                out.add(uid(member.getPlayerId()));
            }
        }
        return out;
    }

    static boolean hasApplication(List<GuildApplicationView> applications, long guildId) {
        return applications.stream().anyMatch(application -> application.getGuildId() == guildId);
    }

    static boolean hasApplicant(List<GuildApplicantView> applicants, long playerId) {
        return applicants.stream().anyMatch(applicant -> applicant.getPlayerId() == playerId);
    }

    /** 把 ASCII 小写字母与数字换成全角大写（NFKC 再小写后与原串相同，用来验「规范化撞名」）。 */
    static String fullWidthUpper(String ascii) {
        StringBuilder out = new StringBuilder(ascii.length());
        for (int i = 0; i < ascii.length(); i++) {
            char c = ascii.charAt(i);
            if (c >= 'a' && c <= 'z') {
                out.append((char) ('Ａ' + (c - 'a')));
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) ('Ａ' + (c - 'A')));
            } else if (c >= '0' && c <= '9') {
                out.append((char) ('０' + (c - '0')));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 8 个十六进制字符：帮名全服唯一且解散后才释放，每次运行用新名字（Go guildSmokeNonce）。 */
    static String nonce() {
        byte[] buf = new byte[4];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /**
     * 帮会应答的 {@code error_message}（所有 GuildService 应答的第 1 个字段，见 GuildScenarioTest）；没设时为缺省实例。
     */
    static TipInfoMessage errorOf(Message response) {
        Descriptors.FieldDescriptor field = response.getDescriptorForType().findFieldByName("error_message");
        if (field == null || !response.hasField(field)) {
            return TipInfoMessage.getDefaultInstance();
        }
        return (TipInfoMessage) response.getField(field);
    }

    static int tipOf(Message response) {
        return errorOf(response).getId();
    }

    static String describeTip(Message response) {
        TipInfoMessage tip = errorOf(response);
        return "tip=" + tip.getId() + (tip.getParametersCount() > 0 ? " parameters=" + tip.getParametersList() : "");
    }

    private static String hint(int tip) {
        return tip == TIP_HOME_ZONE_UNKNOWN ? "（角色归属区未确认：xm_java.player.zone_id 为 0）" : "";
    }

    static String describe(CreateGuildResponse response) {
        return describeTip(response) + (response.hasGuild() ? " " + describe(response.getGuild()) : " 无帮会");
    }

    static String describe(ReviewGuildApplicationResponse response) {
        return describeTip(response) + (response.hasGuild() ? " " + describe(response.getGuild()) : " 无帮会");
    }

    static String describe(KickGuildMemberResponse response) {
        return describeTip(response) + (response.hasGuild() ? " " + describe(response.getGuild()) : " 无帮会");
    }

    static String describe(GetGuildRankByGuildResponse response) {
        GuildRankEntry entry = response.getEntry();
        return describeTip(response) + " guild_id=" + uid(entry.getGuildId()) + " name=" + entry.getName() + " rank="
                + Integer.toUnsignedString(entry.getRank()) + " score=" + entry.getScore() + " member_count="
                + entry.getMemberCount() + " leader_name=" + entry.getLeaderName();
    }

    static String describe(GetGuildRankResponse response) {
        return describeTip(response) + " page=" + response.getPage() + " page_size=" + response.getPageSize() + " total_count="
                + response.getTotalCount() + " entries=" + response.getEntriesCount();
    }

    /** 帮会摘要：号、名、区、帮主、长老数 / 上限、待审数、成员（player_id:role）。号一律按无符号十进制。 */
    static String describe(GuildInfo guild) {
        StringBuilder out = new StringBuilder("guild_id=").append(uid(guild.getGuildId()))
                .append(" name=").append(guild.getName())
                .append(" zone=").append(Integer.toUnsignedString(guild.getZoneId()))
                .append(" leader=").append(uid(guild.getLeaderId()))
                .append(" officers=").append(guild.getOfficerCount()).append('/').append(guild.getMaxOfficers())
                .append(" pending=").append(guild.getPendingApplicationCount())
                .append(" members=[");
        for (int i = 0; i < guild.getMembersCount(); i++) {
            GuildMember member = guild.getMembers(i);
            out.append(i == 0 ? "" : ", ").append(uid(member.getPlayerId())).append(':').append(member.getRole());
        }
        return out.append(']').toString();
    }

    static String describe(GuildChangedS2C change) {
        return change.getKind() + "(guild=" + uid(change.getGuildId()) + " actor=" + uid(change.getActorPlayerId()) + " target="
                + uid(change.getTargetPlayerId()) + ")";
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    /**
     * 同一机器人的发送节拍（Go pace + 同号退火的通用写法）：相邻请求至少隔 {@code spacing}；同一消息号任意 {@code window} 内
     * 至多 {@code maxInWindow} 次。只在场景线程上用，不加锁。
     */
    static final class Pacer {

        private final long spacingNanos;
        private final long windowNanos;
        private final int maxInWindow;
        /** 每个消息号最近 {@code maxInWindow} 次发送时刻（最早的在队首）。 */
        private final Map<Integer, ArrayDeque<Long>> recent = new HashMap<>();
        private long lastSendNanos;
        private boolean sent;

        Pacer(Duration spacing, Duration window, int maxInWindow) {
            this.spacingNanos = spacing.toNanos();
            this.windowNanos = window.toNanos();
            this.maxInWindow = maxInWindow;
        }

        /** 在 {@code nowNanos} 发 {@code messageId} 之前还要等多久（纳秒，≥ 0）。 */
        long delayNanos(int messageId, long nowNanos) {
            long wait = sent ? spacingNanos - (nowNanos - lastSendNanos) : 0;
            ArrayDeque<Long> times = recent.get(messageId);
            if (times != null && times.size() >= maxInWindow) {
                wait = Math.max(wait, windowNanos - (nowNanos - times.peekFirst()));
            }
            return Math.max(0, wait);
        }

        /** 记下一次发送。 */
        void record(int messageId, long nowNanos) {
            lastSendNanos = nowNanos;
            sent = true;
            ArrayDeque<Long> times = recent.computeIfAbsent(messageId, id -> new ArrayDeque<>(maxInWindow + 1));
            times.addLast(nowNanos);
            while (times.size() > maxInWindow) {
                times.pollFirst();
            }
        }
    }

    /** 一个已进场的机器人。只在场景线程上使用；每次发送先过 {@link Pacer}（帮会经济场景复用同一份节拍）。 */
    static final class Bot {

        final String name;
        final EnteredPlayer player;
        final Duration requestTimeout;
        private final Pacer pacer = new Pacer(REQUEST_SPACING, SAME_ID_WINDOW, SAME_ID_MAX_IN_WINDOW);

        Bot(String name, EnteredPlayer player, Duration requestTimeout) {
            this.name = name;
            this.player = player;
            this.requestTimeout = requestTimeout;
        }

        long id() {
            return player.playerId();
        }

        GameConnection connection() {
            return player.connection();
        }

        int mark() {
            return connection().inbox().size();
        }

        String describeSince(int mark) {
            return connection().describeSince(mark);
        }

        /** 发请求等应答；信封错误（限频 1008、故障 1003 ……）与超时抛出：它们说明请求没到 guild 业务逻辑。 */
        <T extends Message> T call(int messageId, Message body, Parser<T> parser) throws RobotException {
            pace(messageId);
            try {
                return connection().call(messageId, body, parser, requestTimeout);
            } catch (RobotException e) {
                throw new RobotException(name + "：" + e.getMessage(), e);
            }
        }

        /** 只发不等。 */
        long send(int messageId, Message body) throws RobotException {
            pace(messageId);
            return connection().send(messageId, body);
        }

        private void pace(int messageId) throws RobotException {
            long waitNanos = pacer.delayNanos(messageId, System.nanoTime());
            if (waitNanos > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(waitNanos));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RobotException("等待被中断", e);
                }
            }
            pacer.record(messageId, System.nanoTime());
        }
    }
}
