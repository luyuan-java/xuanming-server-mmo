package com.game.team.rules;

import com.game.table.TeamErrorTip;
import java.util.Set;

/**
 * 组队业务错误码（基线 go/match/internal/team/errors.go，team-spec §0.3），经 {@code TeamResponse.error_message.id} 返回客户端。
 *
 * <p>码值只能取自导表生成的 {@code TeamErrorTip.team_error}，不许手写数字（同基线 errors_test.go:92 TestNoHandWrittenTipCodes，
 * Java 侧由 {@code TeamTipsTest} 扫描本文件钉住）。保留不用的码：4000、4009、4010、4012、4015、4016。
 *
 * <p>故障分类：基线由 Tip.xlsx 的 fault 列生成（go/shared/generated/tip/faults.go:56），team 段只有 4030 是故障。
 * Java 的配置表没有 fault 列，所以这里写死 {@link #FAULTS} = {{@link #INTERNAL}}，并用测试钉住。
 */
public final class TeamTips {

    /** 成功（不设 {@code error_message}）。 */
    public static final int OK = TeamErrorTip.team_error.kTeam_errorOK_VALUE;

    // ---- 复用 team_error 组既有码（errors.go:12-37） ----

    /** 缺 session / 目标 id 为 0 / 对自己申请或邀请 / 转让目标为 0。 */
    public static final int PLAYER_ID = TeamErrorTip.team_error.kTeamPlayerId_VALUE;
    /** 申请、同意、邀请、接受邀请时队伍已满（开战不产生它，超员是 4028，见 team-spec §8.4）。 */
    public static final int MEMBERS_FULL = TeamErrorTip.team_error.kTeamMembersFull_VALUE;
    /** 建队时自己已在队；申请、邀请、接受时自己或对方已在队。 */
    public static final int MEMBER_IN_TEAM = TeamErrorTip.team_error.kTeamMemberInTeam_VALUE;
    /** 踢人或转让的目标不在本队（parameters[0] = target，target=0 时不带）。 */
    public static final int MEMBER_NOT_IN_TEAM = TeamErrorTip.team_error.kTeamMemberNotInTeam_VALUE;
    /** 踢自己。 */
    public static final int KICK_SELF = TeamErrorTip.team_error.kTeamKickSelf_VALUE;
    /** 非队长踢人。 */
    public static final int KICK_NOT_LEADER = TeamErrorTip.team_error.kTeamKickNotLeader_VALUE;
    /** 转让给自己。 */
    public static final int APPOINT_SELF = TeamErrorTip.team_error.kTeamAppointSelf_VALUE;
    /** 非队长转让。 */
    public static final int APPOINT_NOT_LEADER = TeamErrorTip.team_error.kTeamAppointLeaderNotLeader_VALUE;
    /** 审批的申请不存在或已过期。 */
    public static final int APPLICATION_NOT_FOUND = TeamErrorTip.team_error.kTeamNotInApplicantList_VALUE;
    /** 自己或目标没有队伍、队伍已解散、expected_team_id 不匹配。 */
    public static final int NO_TEAM = TeamErrorTip.team_error.kTeamHasNotTeamId_VALUE;
    /** 非队长解散。 */
    public static final int DISBAND_NOT_LEADER = TeamErrorTip.team_error.kTeamDismissNotLeader_VALUE;
    /** 邀请目标不在线（只在服务前置）。 */
    public static final int TARGET_OFFLINE = TeamErrorTip.team_error.kTeamPlayerNotFound_VALUE;

    // ---- 新增码 4018..4030（errors.go:39-66） ----

    /** 非队长审批、邀请、开战。 */
    public static final int NOT_LEADER = TeamErrorTip.team_error.kTeamNotLeader_VALUE;
    /** 查不到 home zone（fail-closed，zone 必须写进记录）。 */
    public static final int HOME_ZONE_UNKNOWN = TeamErrorTip.team_error.kTeamHomeZoneUnknown_VALUE;
    /** {@code allowCrossZone=false} 时跨区组队。 */
    public static final int CROSS_ZONE_DENIED = TeamErrorTip.team_error.kTeamCrossZoneDenied_VALUE;
    /** 邀请不存在或已过期。 */
    public static final int INVITE_NOT_FOUND = TeamErrorTip.team_error.kTeamInviteNotFound_VALUE;
    /** 被邀请人待处理的邀请已达上限（提交脚本返回 {@code {-3,i}}；parameters[0] = 被邀请人）。 */
    public static final int INVITE_LIMIT = TeamErrorTip.team_error.kTeamInviteLimit_VALUE;
    /** 开战锁有效期间变更名单 / 重复开战。 */
    public static final int IN_MATCH = TeamErrorTip.team_error.kTeamInMatch_VALUE;
    /** 开战或转让时队员不在线（parameters[0] = player_id）。 */
    public static final int MEMBER_OFFLINE = TeamErrorTip.team_error.kTeamMemberOffline_VALUE;
    /** 开战时队员在战斗中（parameters[0] = player_id）。 */
    public static final int MEMBER_IN_BATTLE = TeamErrorTip.team_error.kTeamMemberInBattle_VALUE;
    /** 开战时队员在排队、不在场景或票据冲突（parameters[0] = player_id）。 */
    public static final int MEMBER_NOT_READY = TeamErrorTip.team_error.kTeamMemberNotReady_VALUE;
    /** 该副本没有配置组队人数。 */
    public static final int DUNGEON_NOT_OPEN = TeamErrorTip.team_error.kTeamDungeonNotOpen_VALUE;
    /** 队伍人数超过副本上限。 */
    public static final int SIZE_EXCEEDED = TeamErrorTip.team_error.kTeamSizeExceeded_VALUE;
    /** 提交重试耗尽 / 请求预算已过期放弃提交 / 锁内名单与成员集合不等。 */
    public static final int STATE_CHANGED = TeamErrorTip.team_error.kTeamStateChanged_VALUE;
    /** Redis、home zone、发号等依赖故障（team 段唯一的 fault）。 */
    public static final int INTERNAL = TeamErrorTip.team_error.kTeamInternal_VALUE;

    /** team 段内的故障码集合（基线 faults.go:56 只有 TeamInternal）。 */
    public static final Set<Integer> FAULTS = Set.of(INTERNAL);

    /**
     * team 段的声明（基线 go/shared/generated/tip/segments.go:33，源头是 Tip.xlsx 组头 {@code //team_error base=4000 width=1000}）。
     * 这是段声明而不是 tip 码；Java 没有生成的段表，只能照抄。
     */
    private static final int SEGMENT_BASE = 4000;
    private static final int SEGMENT_WIDTH = 1000;

    private TeamTips() {
    }

    /** 是否是故障（打 ERROR / 计故障指标）；其余非 0 码都是业务拒绝。 */
    public static boolean isFault(int code) {
        return FAULTS.contains(code);
    }

    /** 码是否落在 team 段 {@code [4000, 5000)} 内（基线判属看 [Base, Base+Width)，不看已分配上界）。 */
    public static boolean isTeamCode(int code) {
        return code >= SEGMENT_BASE && code < SEGMENT_BASE + SEGMENT_WIDTH;
    }
}
