package com.game.guild.rules;

/**
 * 帮会职位编码与权限判定（基线 constants.go:8-45、guild_manage_repo.go:428-457、guild_repo.go:402-407；guild-spec §2.1–§2.3）。
 *
 * <p>持久化编码<b>不连续</b>：0 成员 / 1 长老 / 3 帮主，2 是刻意跳过的空号（constants.go:9-13）。所以权限一律比 {@link Rank}，
 * 不比 role 原值——{@code role >= OFFICER} 会把未知编码（存量脏数据 2，或将来新加的职位）一起放进来，等于凭空发权限。
 *
 * <p>这些判定只对<b>事务内锁住的 MySQL 行</b>做（§2.2）；缓存里的 role 只用于展示（{@link #seesPendingApplicationCount}）。
 * role 是 proto 的 uint32，Java 按 int 的位模式持有：除 0 / 1 / 3 之外的任何值（含按位解释成负数的大 uint32）都是 {@link Rank#NONE}。
 * 全是纯函数，可在任意线程调用。
 */
public final class GuildRoles {

    /** 成员。 */
    public static final int MEMBER = 0;
    /** 长老。 */
    public static final int OFFICER = 1;
    /** 帮主：只能经转让产生（{@link #assignableRole} 不收它）。 */
    public static final int LEADER = 3;

    /**
     * 职位档位：连续、可直接比大小（constants.go:15-25）。声明顺序就是档位高低，序号与基线常量值相同（NONE=0 … LEADER=3）。
     */
    public enum Rank {
        /** 未知编码（含空号 2）：所有「至少长老」的判定自动拒绝（fail-closed）。 */
        NONE,
        MEMBER,
        OFFICER,
        LEADER;

        /** {@code this >= other}。 */
        public boolean atLeast(Rank other) {
            return compareTo(other) >= 0;
        }

        /** {@code this > other}。 */
        public boolean above(Rank other) {
            return compareTo(other) > 0;
        }
    }

    private GuildRoles() {
    }

    /** role 编码 → 档位；未知编码一律 {@link Rank#NONE}（constants.go:30-41：default 分支是 fail-closed 的落点，不许透传）。 */
    public static Rank rank(int role) {
        return switch (role) {
            case MEMBER -> Rank.MEMBER;
            case OFFICER -> Rank.OFFICER;
            case LEADER -> Rank.LEADER;
            default -> Rank.NONE;
        };
    }

    /** 任免只能设 0 / 1（constants.go:43-45）：放行 3 等于开了「自己升自己」的后门，放行 2 会造出判不出档位的成员。 */
    public static boolean assignableRole(int role) {
        return role == MEMBER || role == OFFICER;
    }

    /** 任免：只有帮主（guild_manage_repo.go:433）。目标是帮主的拒绝在事务里另判（:1323-1326）。 */
    public static boolean canAssignRole(int actorRole) {
        return rank(actorRole) == Rank.LEADER;
    }

    /** 转让：只有帮主（guild_manage_repo.go:435-437）；事务里还要求 {@code guild.leader_id == 自己}，否则是故障。 */
    public static boolean canTransferLeader(int actorRole) {
        return rank(actorRole) == Rank.LEADER;
    }

    /** 列待审 / 审批：长老与帮主（guild_manage_repo.go:439-441）。 */
    public static boolean canReviewApplications(int actorRole) {
        return rank(actorRole).atLeast(Rank.OFFICER);
    }

    /** 改公告：长老与帮主（guild_repo.go:405-407）。 */
    public static boolean canSetAnnouncement(int role) {
        return rank(role).atLeast(Rank.OFFICER);
    }

    /**
     * 踢人：自己至少是长老、对方档位已知、且自己<b>严格</b>高于对方（guild_manage_repo.go:443-448）。
     * 帮主可踢长老与成员；长老只能踢成员；没有人能踢帮主；未知编码既不能踢人也不能被踢（§9.1 第 18 条，照搬）。
     */
    public static boolean canKick(int actorRole, int targetRole) {
        Rank a = rank(actorRole);
        Rank t = rank(targetRole);
        return a.atLeast(Rank.OFFICER) && t != Rank.NONE && a.above(t);
    }

    /**
     * 只用于展示：GuildInfo 的 {@code pending_application_count} 只给本帮长老 / 帮主看（guild_manage_logic.go:772-788 guildInfoFor）。
     * 入参是快照里的 role，<b>不得</b>用于授权（:790-791）。
     */
    public static boolean seesPendingApplicationCount(int role) {
        return rank(role).atLeast(Rank.OFFICER);
    }

    /**
     * 转让之后原帮主的新 role（guild_manage_repo.go:450-457）：长老位有空 → 长老，否则降为成员（宁可降成成员也不超编）。
     * 两个入参都是 uint32（{@code officersAfterTarget} = 新帮主就位后的长老数，{@code maxOfficers} = GuildLevel[level].max_officers），按无符号比较。
     */
    public static int demotedLeaderRole(int officersAfterTarget, int maxOfficers) {
        return Integer.compareUnsigned(officersAfterTarget, maxOfficers) < 0 ? OFFICER : MEMBER;
    }

    /**
     * 任免 / 踢人 / 转让的公共目标校验，在碰库之前做（guild_manage_logic.go:335-343 targetTip）。
     * target = 0 回 14014（「这个人不在帮里」就是最准确的解释）、target = 自己回 14015；通过返回 null。
     */
    public static GuildTip targetTip(long actor, long target) {
        if (target == 0) {
            return GuildTip.TARGET_ID_ZERO;
        }
        if (target == actor) {
            return GuildTip.CANNOT_TARGET_SELF;
        }
        return null;
    }
}
