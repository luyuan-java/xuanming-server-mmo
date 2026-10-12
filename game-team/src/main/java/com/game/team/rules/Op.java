package com.game.team.rules;

/**
 * 一次名册操作的输入（基线 rules.go:78-137）。用下面的静态工厂创建，不要手填种类与字段组合。
 *
 * <p>所有 id 都是无符号 64 位（Java 里按位存进 {@code long}），zone 是无符号 32 位（存进 {@code int}）；规则层只做相等比较。
 *
 * @param kind         操作种类
 * @param caller       调用者（只取自会话）
 * @param callerTeamId 调用者索引当前指向的队伍（S_READ 的 tidNow；0 = 无队）。由存储层从同一次读填入（{@link #withCallerTeamId}），
 *                     调用方不填（基线 store.go:217）
 * @param callerZone   CREATE：建队者 home zone（即队伍 zone）；APPLY：申请人 home zone
 * @param target       HANDLE_APPLICATION：申请人；INVITE / KICK / TRANSFER_LEADER：目标玩家
 * @param targetZone   INVITE：被邀请人 home zone
 * @param newTeamId    CREATE：新发的 team_id
 * @param accept       HANDLE_APPLICATION 的 approve / RESPOND_INVITE 的 accept
 */
public record Op(OpKind kind, long caller, long callerTeamId, int callerZone, long target, int targetZone,
                 long newTeamId, boolean accept) {

    /** 建队：caller 为队长，zone 为建队者 home zone，teamId 由发号器给出（rules.go:97-100）。 */
    public static Op create(long caller, long teamId, int zone) {
        return new Op(OpKind.CREATE, caller, 0, zone, 0, 0, teamId, false);
    }

    /** GetMyTeam 的清理 + 惰性转让（rules.go:102-103）。 */
    public static Op refresh(long caller) {
        return new Op(OpKind.REFRESH, caller, 0, 0, 0, 0, 0, false);
    }

    /** 申请加入；目标队伍由存储层按目标玩家绑定（rules.go:105-108）。 */
    public static Op apply(long caller, int callerZone) {
        return new Op(OpKind.APPLY, caller, 0, callerZone, 0, 0, 0, false);
    }

    /** 队长审批申请（rules.go:110-113）。 */
    public static Op handleApplication(long caller, long applicant, boolean approve) {
        return new Op(OpKind.HANDLE_APPLICATION, caller, 0, 0, applicant, 0, 0, approve);
    }

    /** 队长邀请 target，targetZone 为其 home zone（rules.go:115-118）。 */
    public static Op invite(long caller, long target, int targetZone) {
        return new Op(OpKind.INVITE, caller, 0, 0, target, targetZone, 0, false);
    }

    /** 被邀请人接受或拒绝；队伍由存储层按请求里的 team_id 绑定（rules.go:120-123）。 */
    public static Op respondInvite(long caller, boolean accept) {
        return new Op(OpKind.RESPOND_INVITE, caller, 0, 0, 0, 0, 0, accept);
    }

    /** 离队（rules.go:125-126）。 */
    public static Op leave(long caller) {
        return new Op(OpKind.LEAVE, caller, 0, 0, 0, 0, 0, false);
    }

    /** 队长踢人（rules.go:128-129）。 */
    public static Op kick(long caller, long target) {
        return new Op(OpKind.KICK, caller, 0, 0, target, 0, 0, false);
    }

    /** 队长转让（rules.go:131-134）。 */
    public static Op transferLeader(long caller, long target) {
        return new Op(OpKind.TRANSFER_LEADER, caller, 0, 0, target, 0, 0, false);
    }

    /** 队长解散（rules.go:136-137）。 */
    public static Op disband(long caller) {
        return new Op(OpKind.DISBAND, caller, 0, 0, 0, 0, 0, false);
    }

    /** 返回填好 {@code callerTeamId} 的副本（存储层在每轮 S_READ 之后调用）。 */
    public Op withCallerTeamId(long callerTeamId) {
        return new Op(kind, caller, callerTeamId, callerZone, target, targetZone, newTeamId, accept);
    }
}
