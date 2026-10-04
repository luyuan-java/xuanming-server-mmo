package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.store.GuildStore.AnnouncementUpdated;
import com.game.guild.store.GuildStore.Created;
import com.game.guild.store.GuildStore.Disbanded;
import com.game.guild.store.GuildStore.Kicked;
import com.game.guild.store.GuildStore.Left;
import com.game.guild.store.GuildStore.Reviewed;
import com.game.guild.store.GuildStore.RoleChanged;
import com.game.guild.store.GuildStore.Transferred;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 写结果携带的提交后动作（不连库）：失效矩阵（guild-spec §1.11）与推送收件人（§4.3），以及快照 / 结局类型的小助手。
 */
class GuildStoreResultsTest {

    private static final long G = 7001;
    private static final long LEADER = 8001;
    private static final long OFFICER = 8002;
    private static final long MEMBER = 8003;

    private static GuildData guild(long... members) {
        List<GuildData.Member> rows = new java.util.ArrayList<>();
        for (long m : members) {
            int role = m == LEADER ? GuildRoles.LEADER : m == OFFICER ? GuildRoles.OFFICER : GuildRoles.MEMBER;
            rows.add(new GuildData.Member(m, role, 1, 1, 0, 0));
        }
        return new GuildData(G, "g", LEADER, 1, null, 1, 30, 2, 0, 0, rows);
    }

    @Test
    void 失效矩阵() {
        assertThat(new Created(G, LEADER, 2).invalidation()).isEqualTo(Invalidation.of(GuildTxOp.CREATE, G, LEADER));
        GuildData snap = guild(LEADER, OFFICER, MEMBER);
        assertThat(new RoleChanged(snap, true, LEADER, MEMBER).invalidation())
                .isEqualTo(Invalidation.of(GuildTxOp.SET_ROLE, G));
        assertThat(new RoleChanged(snap, false, LEADER, MEMBER).invalidation().isEmpty()).as("幂等任命不失效").isTrue();
        assertThat(new Kicked(guild(LEADER, OFFICER), LEADER, MEMBER).invalidation())
                .isEqualTo(Invalidation.of(GuildTxOp.KICK, G, MEMBER));
        assertThat(new Transferred(snap, LEADER, OFFICER).invalidation()).isEqualTo(Invalidation.of(GuildTxOp.TRANSFER, G));
        assertThat(new Left(guild(LEADER, OFFICER), MEMBER).invalidation()).isEqualTo(Invalidation.of(GuildTxOp.LEAVE, G, MEMBER));
        assertThat(new Reviewed(snap, true, LEADER, MEMBER).invalidation()).isEqualTo(Invalidation.of(GuildTxOp.REVIEW, G, MEMBER));
        assertThat(new Reviewed(snap, false, LEADER, 9001).invalidation().isEmpty()).as("拒绝不失效").isTrue();
        assertThat(new Disbanded(G, 2, LEADER, List.of(LEADER, OFFICER, MEMBER)).invalidation())
                .isEqualTo(Invalidation.of(GuildTxOp.DISBAND, G, LEADER, OFFICER, MEMBER));
        assertThat(new AnnouncementUpdated(snap, OFFICER).invalidation()).isEqualTo(Invalidation.of(GuildTxOp.ANNOUNCEMENT, G));
    }

    @Test
    void 推送收件人() {
        GuildData snap = guild(LEADER, OFFICER, MEMBER);
        assertThat(new RoleChanged(snap, true, LEADER, MEMBER).pushRecipients()).containsExactly(OFFICER, MEMBER);
        assertThat(new RoleChanged(snap, false, LEADER, MEMBER).pushRecipients()).as("幂等任命不推").isEmpty();
        assertThat(new Kicked(guild(LEADER, OFFICER), OFFICER, MEMBER).pushRecipients())
                .as("快照除操作者，再加被踢者").containsExactly(LEADER, MEMBER);
        assertThat(new Transferred(snap, LEADER, OFFICER).pushRecipients()).containsExactly(OFFICER, MEMBER);
        assertThat(new Left(guild(LEADER, OFFICER), MEMBER).pushRecipients()).containsExactly(LEADER, OFFICER);
        assertThat(new Reviewed(snap, true, OFFICER, MEMBER).pushRecipients()).as("含新成员、除审批人")
                .containsExactly(LEADER, MEMBER);
        assertThat(new Reviewed(guild(LEADER, OFFICER), false, OFFICER, 9001).pushRecipients()).containsExactly(9001L);
        assertThat(new Disbanded(G, 2, LEADER, List.of(LEADER, OFFICER, MEMBER)).pushRecipients())
                .containsExactly(OFFICER, MEMBER);
        assertThat(new AnnouncementUpdated(snap, OFFICER).pushRecipients()).containsExactly(LEADER, MEMBER);
    }

    @Test
    void 快照助手() {
        GuildData snap = guild(LEADER, OFFICER, MEMBER);
        assertThat(snap.announcement()).as("null 规整成空串").isEmpty();
        assertThat(snap.hasMember(OFFICER)).isTrue();
        assertThat(snap.member(9999)).isNull();
        assertThat(snap.memberIds()).containsExactly(LEADER, OFFICER, MEMBER);
        assertThat(snap.memberIdsExcept(LEADER, MEMBER)).containsExactly(OFFICER);
        assertThat(snap.officerCount()).isEqualTo(1);
        assertThatThrownBy(() -> snap.members().add(snap.members().getFirst())).as("不可变")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 失效目标判空() {
        assertThat(Invalidation.none(GuildTxOp.APPLY).isEmpty()).isTrue();
        assertThat(Invalidation.of(GuildTxOp.VERIFY_MAPPING, 0, 0L).isEmpty()).as("0 由失效组件忽略").isTrue();
        assertThat(Invalidation.of(GuildTxOp.VERIFY_MAPPING, 0, 42L).isEmpty()).isFalse();
    }

    @Test
    void 结局类型() {
        TxOutcome<String> ok = TxOutcome.ok("v");
        assertThat(ok.isOk()).isTrue();
        assertThat(ok.rejection()).isNull();
        assertThat(ok.orThrow()).isEqualTo("v");
        TxOutcome<String> reject = TxOutcome.reject(GuildReject.GUILD_FULL);
        assertThat(reject.isOk()).isFalse();
        assertThat(reject.rejection()).isEqualTo(GuildReject.GUILD_FULL);
        assertThatThrownBy(reject::orThrow).isInstanceOf(IllegalStateException.class);
        TxOutcome<Integer> carried = TxOutcome.<String>commitThenReject(GuildReject.APPLICATION_NOT_FOUND).rejectAs();
        assertThat(carried).isEqualTo(TxOutcome.commitThenReject(GuildReject.APPLICATION_NOT_FOUND));
        assertThatThrownBy(ok::rejectAs).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void op标签与子预算() {
        assertThat(GuildTxOp.DISBAND.budgetMillis()).isEqualTo(2_500L);
        assertThat(GuildTxOp.APPLY.budgetMillis()).isEqualTo(1_500L);
        assertThat(java.util.Arrays.stream(GuildTxOp.values()).map(GuildTxOp::label)).containsExactly(
                "create", "set_role", "kick", "transfer", "leave", "apply", "cancel", "review", "disband", "announcement",
                "verify_mapping", "score", "upgrade", "asset_finalize", "activity", "trial_settle", "donate", "shop",
                "insert_guard");
    }
}
