package com.game.team.rules;

/**
 * 名册操作种类（基线 rules.go:61-76）。开战锁不走这里，见 {@link TeamRules#lockMatch} / {@link TeamRules#releaseMatchLock}。
 */
public enum OpKind {
    /** CreateTeam：建队。 */
    CREATE,
    /** GetMyTeam、StartTeamMatch 第 1 步：只做过期清理与惰性转让队长。 */
    REFRESH,
    /** ApplyJoinTeam：申请加入。 */
    APPLY,
    /** HandleApplication：队长审批申请。 */
    HANDLE_APPLICATION,
    /** InviteToTeam：队长邀请。 */
    INVITE,
    /** RespondInvite：被邀请人接受或拒绝。 */
    RESPOND_INVITE,
    /** LeaveTeam：离队。 */
    LEAVE,
    /** KickMember：队长踢人。 */
    KICK,
    /** TransferLeader：队长转让。 */
    TRANSFER_LEADER,
    /** DisbandTeam：队长解散。 */
    DISBAND
}
