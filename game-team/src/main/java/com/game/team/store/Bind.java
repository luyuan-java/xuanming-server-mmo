package com.game.team.store;

/**
 * 决定 {@link TeamStore#mutate} 操作哪一个队伍（基线 store.go:75-108，team-spec §1.7.1）。整个 mutate（含重试）只绑定这一个 tid，绝不换队。
 *
 * @param mode     绑定方式
 * @param playerId 调用者（S_READ 读它的索引；规则层的 caller）
 * @param teamId   要操作的队伍（无符号）
 */
public record Bind(Mode mode, long playerId, long teamId) {

    /** 绑定方式。 */
    public enum Mode {
        /** 带 expected_team_id 的写 RPC：调用者索引 tid ≠ teamId（含 teamId=0）时不写，{@link Outcome#NOT_BOUND}。 */
        CALLER,
        /** RespondInvite（请求里的 team_id）/ ApplyJoinTeam（前置读到的目标所在队）：调用者自己的 tid 只交给规则判断。 */
        TARGET,
        /** 建队：expectedVer = "new"，记录必须不存在；已存在回 Rejected(4030)（发号器故障）。 */
        CREATE
    }

    /** 带 expected_team_id 的写 RPC（Handle / Invite / Leave / Kick / Transfer / Disband / StartTeamMatch，GetMyTeam 取自由读的 tid）。 */
    public static Bind caller(long playerId, long expectedTeamId) {
        return new Bind(Mode.CALLER, playerId, expectedTeamId);
    }

    /** RespondInvite / ApplyJoinTeam：操作 teamId。 */
    public static Bind target(long playerId, long teamId) {
        return new Bind(Mode.TARGET, playerId, teamId);
    }

    /** CreateTeam：newTeamId 由发号器给出。 */
    public static Bind create(long playerId, long newTeamId) {
        return new Bind(Mode.CREATE, playerId, newTeamId);
    }

    @Override
    public String toString() {
        return "Bind[" + mode + " player=" + Long.toUnsignedString(playerId) + " team=" + Long.toUnsignedString(teamId) + "]";
    }
}
