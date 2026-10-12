package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTableRules.ApplicationRules;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 不碰库就该拒绝的入参（基线 guild_manage_repo_test.go:592-608 TestEnsurePlayerStateRowsRejectsGuardPlayerID 及各写方法的入参断言），
 * 与无符号排序 / 绑定的小工具。取连接即失败：任何一次数据库访问都会让用例红。
 */
class JdbcGuildStoreUnitTest {

    private final AtomicInteger connects = new AtomicInteger();

    private JdbcGuildStore store() {
        GuildTx tx = new GuildTx(maxWait -> {
            connects.incrementAndGet();
            throw new SQLException("本用例不许访问数据库");
        }, 3, GuildTxListener.NONE);
        return new JdbcGuildStore(tx, GuildTxHooks.NONE);
    }

    private static Deadline d() {
        return Deadline.after(5_000);
    }

    @Test
    void 状态行补建拒绝把守卫id当玩家_不碰库() {
        JdbcGuildStore s = store();
        for (List<Long> ids : List.of(List.of(0L), List.of(8801L, 0L, 8802L))) {
            assertThatThrownBy(() -> s.ensurePlayerStateRows(GuildTxOp.APPLY, 1, d(), ids))
                    .as("ids=%s", ids).isInstanceOf(IllegalArgumentException.class);
        }
        // 空列表不碰库、直接成功（解散一个已没有成员的帮时预读为空）
        assertThat(s.ensurePlayerStateRows(GuildTxOp.DISBAND, 1, d(), List.of())).isNull();
        assertThat(connects).hasValue(0);
    }

    @Test
    void 写方法的入参违约在碰库之前拒绝() {
        JdbcGuildStore s = store();
        GuildStore.OfficerCaps caps = level -> OptionalInt.of(2);
        assertThatThrownBy(() -> s.setMemberRole(1, 5, 5, GuildRoles.OFFICER, caps, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.setMemberRole(1, 5, 6, GuildRoles.LEADER, caps, d()))
                .as("帮主只能经转让产生").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.setMemberRole(1, 5, 6, 2, caps, d())).as("2 是空号").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.kickMember(1, 5, 5, 1, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.kickMember(1, 5, 6, 0, d())).as("now 必须 > 0").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.transferLeader(1, 5, 5, caps, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.leaveGuild(1, 5, 0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.applyToGuild(1, 5, 0, 1, new ApplicationRules(0, 3, 50), d()))
                .as("规则为 0 = 配表没读到").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.applyToGuild(1, 5, 0, 1, new ApplicationRules(1, 0, 50), d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.applyToGuild(1, 5, 0, 1, new ApplicationRules(1, 3, 0), d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.reviewApplication(1, 5, 5, false, 0, 1, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.reviewApplication(1, 5, 6, true, 0, 1, d()))
                .as("通过时申请人归属区必须已知").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.disbandGuild(1, 5, 0, null, d())).isInstanceOf(IllegalArgumentException.class);
        GuildData twoMembers = new GuildData(1, "g", 5, 1, "", 1, 30, 2, 0, 0, List.of(
                new GuildData.Member(5, GuildRoles.LEADER, 1, 1, 0, 0), new GuildData.Member(6, GuildRoles.MEMBER, 1, 1, 0, 0)));
        assertThatThrownBy(() -> s.createGuild(twoMembers, d())).as("恰好一名建帮成员").isInstanceOf(IllegalArgumentException.class);
        GuildData badName = new GuildData(1, "   ", 5, 1, "", 1, 30, 2, 0, 0,
                List.of(new GuildData.Member(5, GuildRoles.LEADER, 1, 1, 0, 0)));
        assertThatThrownBy(() -> s.createGuild(badName, d())).as("帮名无法规范化").isInstanceOf(IllegalArgumentException.class);
        assertThat(connects).hasValue(0);
    }

    @Test
    void 无符号排序与主键序() {
        long big = Long.MIN_VALUE + 5; // 2^63 + 5
        assertThat(JdbcGuildStore.sortedUnique(List.of(big, 7L, big, 3L, -1L, 0L)))
                .containsExactly(0L, 3L, 7L, big, -1L);
        List<JdbcGuildStore.AppKey> keys = new ArrayList<>(List.of(new JdbcGuildStore.AppKey(big, 1),
                new JdbcGuildStore.AppKey(2, big), new JdbcGuildStore.AppKey(2, 3)));
        keys.sort(JdbcGuildStore::compareAppKeys);
        assertThat(keys).containsExactly(new JdbcGuildStore.AppKey(2, 3), new JdbcGuildStore.AppKey(2, big),
                new JdbcGuildStore.AppKey(big, 1));
    }

    @Test
    void 参数绑定按无符号() {
        assertThat(GuildJdbc.bindValue(5L)).isEqualTo(5L);
        assertThat(GuildJdbc.bindValue(-1L)).isEqualTo(new BigInteger("18446744073709551615"));
        assertThat(GuildJdbc.bindValue(-1)).isEqualTo(4_294_967_295L);
        assertThat(GuildJdbc.bindValue("名")).isEqualTo("名");
        assertThatThrownBy(() -> GuildJdbc.bindValue(GuildTxOp.KICK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuildJdbc.bindValue(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(JdbcGuildStore.placeholders(3)).isEqualTo("?,?,?");
    }
}
