package com.game.guild.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.guild.rules.GuildRoles.Rank;
import org.junit.jupiter.api.Test;

/** 职位编码与权限纯函数（基线 guild_manage_repo_test.go:245-307、guild_repo_test.go:22-36、guild_manage_logic_test.go:251）。 */
class GuildRolesTest {

    /** 未知编码样本：空号 2、将来的 4、按位解释成负数的大 uint32。 */
    private static final int[] UNKNOWN_ROLES = {2, 4, -1, Integer.MIN_VALUE};

    @Test
    void 编码是0_1_3且2是空号() {
        assertThat(GuildRoles.MEMBER).isZero();
        assertThat(GuildRoles.OFFICER).isEqualTo(1);
        assertThat(GuildRoles.LEADER).isEqualTo(3);
    }

    @Test
    void 档位映射_未知编码一律NONE() {
        assertThat(GuildRoles.rank(GuildRoles.MEMBER)).isEqualTo(Rank.MEMBER);
        assertThat(GuildRoles.rank(GuildRoles.OFFICER)).isEqualTo(Rank.OFFICER);
        assertThat(GuildRoles.rank(GuildRoles.LEADER)).isEqualTo(Rank.LEADER);
        for (int role : UNKNOWN_ROLES) {
            assertThat(GuildRoles.rank(role)).as("role=%d", role).isEqualTo(Rank.NONE);
        }
        // 档位序号与基线常量值相同（RankNone=0 … RankLeader=3），可直接比大小
        assertThat(Rank.NONE.ordinal()).isZero();
        assertThat(Rank.MEMBER.ordinal()).isEqualTo(1);
        assertThat(Rank.OFFICER.ordinal()).isEqualTo(2);
        assertThat(Rank.LEADER.ordinal()).isEqualTo(3);
        assertThat(Rank.LEADER.above(Rank.OFFICER)).isTrue();
        assertThat(Rank.OFFICER.above(Rank.OFFICER)).isFalse();
        assertThat(Rank.OFFICER.atLeast(Rank.OFFICER)).isTrue();
        assertThat(Rank.MEMBER.atLeast(Rank.OFFICER)).isFalse();
    }

    @Test
    void 只有0和1可任免() {
        assertThat(GuildRoles.assignableRole(GuildRoles.MEMBER)).isTrue();
        assertThat(GuildRoles.assignableRole(GuildRoles.OFFICER)).isTrue();
        assertThat(GuildRoles.assignableRole(GuildRoles.LEADER)).as("帮主只能经转让产生").isFalse();
        for (int role : UNKNOWN_ROLES) {
            assertThat(GuildRoles.assignableRole(role)).as("role=%d", role).isFalse();
        }
    }

    /** TestCanKickMatrix（guild_manage_repo_test.go:251-269）：职位必须严格高于对方；未知编码既不能踢人也不能被踢。 */
    @Test
    void 踢人矩阵() {
        int m = GuildRoles.MEMBER;
        int o = GuildRoles.OFFICER;
        int l = GuildRoles.LEADER;
        int[][] allowed = {{l, o}, {l, m}, {o, m}};
        int[][] denied = {{o, o}, {o, l}, {m, m}, {l, l}, {2, m}, {l, 2}, {m, o}, {m, l}, {2, 2}, {4, m}, {l, 4}, {-1, m}};
        for (int[] c : allowed) {
            assertThat(GuildRoles.canKick(c[0], c[1])).as("actor=%d target=%d", c[0], c[1]).isTrue();
        }
        for (int[] c : denied) {
            assertThat(GuildRoles.canKick(c[0], c[1])).as("actor=%d target=%d", c[0], c[1]).isFalse();
        }
    }

    /** TestCanAssignTransferReview（guild_manage_repo_test.go:273-283）+ canSetAnnouncement（guild_repo_test.go:22-36）。 */
    @Test
    void 任免与转让只有帮主_审批公告看待审数长老以上() {
        for (int role : new int[] {GuildRoles.MEMBER, GuildRoles.OFFICER, 2, GuildRoles.LEADER, 4, -1}) {
            boolean leaderOnly = role == GuildRoles.LEADER;
            assertThat(GuildRoles.canAssignRole(role)).as("canAssignRole role=%d", role).isEqualTo(leaderOnly);
            assertThat(GuildRoles.canTransferLeader(role)).as("canTransferLeader role=%d", role).isEqualTo(leaderOnly);

            boolean officerUp = role == GuildRoles.OFFICER || role == GuildRoles.LEADER;
            assertThat(GuildRoles.canReviewApplications(role)).as("canReviewApplications role=%d", role).isEqualTo(officerUp);
            assertThat(GuildRoles.canSetAnnouncement(role)).as("canSetAnnouncement role=%d", role).isEqualTo(officerUp);
            assertThat(GuildRoles.seesPendingApplicationCount(role)).as("seesPendingApplicationCount role=%d", role)
                    .isEqualTo(officerUp);
        }
    }

    /** TestDemotedLeaderRole（guild_manage_repo_test.go:286-303）：长老位有空就补进去，没空就降成成员。 */
    @Test
    void 转让后原帮主的落点() {
        int[][] cases = {{0, 2, GuildRoles.OFFICER}, {1, 2, GuildRoles.OFFICER}, {2, 2, GuildRoles.MEMBER},
                {3, 2, GuildRoles.MEMBER}, {0, 0, GuildRoles.MEMBER}};
        for (int[] c : cases) {
            assertThat(GuildRoles.demotedLeaderRole(c[0], c[1])).as("officers=%d cap=%d", c[0], c[1]).isEqualTo(c[2]);
        }
        // uint32 语义：大于 2^31 的上限按无符号比较（有符号比较会把它当负数而降成成员）
        assertThat(GuildRoles.demotedLeaderRole(5, -1)).isEqualTo(GuildRoles.OFFICER);
        assertThat(GuildRoles.demotedLeaderRole(-1, 6)).isEqualTo(GuildRoles.MEMBER);
    }

    /** targetTip（guild_manage_logic.go:335-343；TestTargetValidationBeforeRepo :251）。 */
    @Test
    void 目标校验_0回14014_自己回14015() {
        assertThat(GuildRoles.targetTip(42, 0)).isEqualTo(GuildTip.TARGET_ID_ZERO);
        assertThat(GuildRoles.targetTip(42, 0).code()).isEqualTo(GuildTips.TARGET_NOT_MEMBER);
        assertThat(GuildRoles.targetTip(42, 0).reason()).isEqualTo("target player id is zero");
        assertThat(GuildRoles.targetTip(42, 42)).isEqualTo(GuildTip.CANNOT_TARGET_SELF);
        assertThat(GuildRoles.targetTip(42, 42).code()).isEqualTo(GuildTips.CANNOT_TARGET_SELF);
        assertThat(GuildRoles.targetTip(42, 42).reason()).isEqualTo("cannot target self");
        assertThat(GuildRoles.targetTip(42, 7)).isNull();
        // 无符号大 id：只比位模式相等
        assertThat(GuildRoles.targetTip(-2L, -2L)).isEqualTo(GuildTip.CANNOT_TARGET_SELF);
        assertThat(GuildRoles.targetTip(-2L, -3L)).isNull();
        // 操作者为 0 不会出现（无会话已在派发层回信封）；target=0 先判
        assertThat(GuildRoles.targetTip(0, 0)).isEqualTo(GuildTip.TARGET_ID_ZERO);
    }
}
