package com.game.common.team;

/** Team规则与Match全局准入指纹共用的实际硬约束；不含连接地址或线程池等实例配置。 */
public final class TeamPolicyContract {
    public static final int CAPACITY=5;
    public static final long APPLICATION_TTL_MS=120_000L;
    public static final int MAX_APPLICATIONS=10;
    public static final long INVITE_TTL_MS=60_000L;
    public static final int MAX_INVITES_PER_TEAM=10;
    public static final int MAX_PENDING_INVITES_PER_INVITEE=10;
    private TeamPolicyContract(){}
    public static String hardRules(){return "team-v1|"+CAPACITY+"|"+APPLICATION_TTL_MS+"|"+MAX_APPLICATIONS+"|"+INVITE_TTL_MS+"|"+MAX_INVITES_PER_TEAM+"|"+MAX_PENDING_INVITES_PER_INVITEE;}
}
