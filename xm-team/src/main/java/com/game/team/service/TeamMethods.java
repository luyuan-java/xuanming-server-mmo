package com.game.team.service;

import java.util.List;

/**
 * {@code ClientPlayerTeam} 的方法名（{@code message_id.txt} 的键后缀；基线 server.go:26-40 的 method 常量）。
 *
 * <p>同时是指标 label 的固定取值：{@code xm_team_requests_seconds{method}}、{@code xm_team_commit_retries_total{op}}
 * （基线 team_rpc_total{method} / team_commit_retry_total{op}）。不含 player_id / team_id。
 */
public final class TeamMethods {

    /** proto/team/team.proto 的客户端服务名（message_id.txt 的键前缀）。 */
    public static final String SERVICE = "ClientPlayerTeam";

    public static final String CREATE_TEAM = "CreateTeam";
    public static final String GET_MY_TEAM = "GetMyTeam";
    public static final String APPLY_JOIN_TEAM = "ApplyJoinTeam";
    public static final String HANDLE_APPLICATION = "HandleApplication";
    public static final String INVITE_TO_TEAM = "InviteToTeam";
    public static final String RESPOND_INVITE = "RespondInvite";
    public static final String LIST_MY_INVITES = "ListMyInvites";
    public static final String LEAVE_TEAM = "LeaveTeam";
    public static final String KICK_MEMBER = "KickMember";
    public static final String TRANSFER_LEADER = "TransferLeader";
    public static final String DISBAND_TEAM = "DisbandTeam";
    public static final String START_TEAM_MATCH = "StartTeamMatch";

    /** S2C 推送占位（服务端推送借 rpc 声明拿消息号；客户端上行时空操作，基线 server.go:163-175）。 */
    public static final String NOTIFY_TEAM_SNAPSHOT = "NotifyTeamSnapshot";
    public static final String NOTIFY_TEAM_INVITE = "NotifyTeamInvite";
    public static final String NOTIFY_TEAM_EVENT = "NotifyTeamEvent";

    /** 12 个 C2S 方法（team.proto:24-35 的顺序）。 */
    public static final List<String> REQUESTS = List.of(CREATE_TEAM, GET_MY_TEAM, APPLY_JOIN_TEAM, HANDLE_APPLICATION,
            INVITE_TO_TEAM, RESPOND_INVITE, LIST_MY_INVITES, LEAVE_TEAM, KICK_MEMBER, TRANSFER_LEADER, DISBAND_TEAM,
            START_TEAM_MATCH);

    /** 3 个推送占位（team.proto:38-40）。 */
    public static final List<String> PUSHES = List.of(NOTIFY_TEAM_SNAPSHOT, NOTIFY_TEAM_INVITE, NOTIFY_TEAM_EVENT);

    private TeamMethods() {
    }
}
