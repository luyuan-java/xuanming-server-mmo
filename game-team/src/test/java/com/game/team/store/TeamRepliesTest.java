package com.game.team.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline.DependencyException;
import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamMemberRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Decision;
import com.game.team.store.TeamReplies.CommitOutcome;
import com.game.team.store.TeamReplies.CommitStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 脚本回复的解析（基线 store.go parseUint / read / commit / ListInvites 的回复部分），纯函数，不起 Redis。 */
class TeamRepliesTest {

    private static final long BIG = Long.MIN_VALUE + 12_345; // ≥ 2^63

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static List<Object> reply(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    // ================================================================ parseUint（store.go:922-938）

    @Test
    void parseUint_十进制字符串与整数_非法一律为0() {
        assertThat(TeamReplies.parseUint(b("123"))).isEqualTo(123);
        assertThat(TeamReplies.parseUint("123")).isEqualTo(123);
        assertThat(TeamReplies.parseUint(5L)).isEqualTo(5);
        assertThat(TeamReplies.parseUint(-1L)).as("负整数为 0").isZero();
        assertThat(TeamReplies.parseUint(b(""))).as("空串为 0（Lua 占位）").isZero();
        assertThat(TeamReplies.parseUint(b("+5"))).as("Go ParseUint 不认符号").isZero();
        assertThat(TeamReplies.parseUint(b(" 5"))).isZero();
        assertThat(TeamReplies.parseUint(b("1e3"))).isZero();
        assertThat(TeamReplies.parseUint(b("abc"))).isZero();
        assertThat(TeamReplies.parseUint(b("18446744073709551615"))).as("uint64 最大值").isEqualTo(-1L);
        assertThat(TeamReplies.parseUint(b("18446744073709551616"))).as("溢出为 0").isZero();
        assertThat(TeamReplies.parseUint(b("0000000000000000000000042"))).as("前导零同 Go").isEqualTo(42);
        assertThat(TeamReplies.parseUint(b(Long.toUnsignedString(BIG)))).isEqualTo(BIG);
        assertThat(TeamReplies.parseUint(null)).isZero();
        assertThat(TeamReplies.parseUint(1.5d)).isZero();
    }

    @Test
    void 索引tid严格解析_非法即故障() {
        assertThat(TeamReplies.parseIndexTid(b("0"), 1)).isZero();
        assertThat(TeamReplies.parseIndexTid(b(Long.toUnsignedString(BIG)), 1)).isEqualTo(BIG);
        for (String bad : List.of("", "x", "+1", "-1", "18446744073709551616", "1 ")) {
            assertThatThrownBy(() -> TeamReplies.parseIndexTid(b(bad), 1)).as("tid=%s", bad)
                    .isInstanceOf(DependencyException.class);
        }
    }

    // ================================================================ S_READ

    @Test
    void 读回复_记录存在与缺失() {
        TeamRecord rec = TeamRecord.newBuilder().setTeamId(BIG).setLeaderId(BIG + 1)
                .addMembers(TeamMemberRecord.newBuilder().setPlayerId(BIG + 1).setJoinSeq(1)).build();
        Snapshot snap = TeamReplies.parseRead(BIG + 1, BIG, reply(b(Long.toUnsignedString(BIG)), b("77"), b("3"),
                rec.toByteArray(), 86_000L, b("1900000000000")));
        assertThat(snap).isEqualTo(new Snapshot(BIG + 1, BIG, 77, BIG, 3, rec, 86_000, 1_900_000_000_000L));

        Snapshot missing = TeamReplies.parseRead(5, 6, reply(b(""), b("1900000000000"), b(""), b(""), -2L,
                b("1900000000000")));
        assertThat(missing.playerTeamId()).isZero();
        assertThat(missing.playerEpoch()).isEqualTo(1_900_000_000_000L);
        assertThat(missing.version()).isZero();
        assertThat(missing.record()).isNull();
        assertThat(missing.recordTtlSeconds()).isEqualTo(-2);

        Snapshot ttlNotInt = TeamReplies.parseRead(5, 6, reply(b(""), b("1"), b(""), b(""), b("x"), b("1")));
        assertThat(ttlNotInt.recordTtlSeconds()).as("TTL 不是整数按 0（同基线 arr[4].(int64)）").isZero();
    }

    @Test
    void 读回复_形状不对或记录解不开是故障() {
        assertThatThrownBy(() -> TeamReplies.parseRead(1, 2, reply(b(""), b("1")))).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseRead(1, 2, 7L)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseRead(1, 2, reply(b("2"), b("1"), b("1"), new byte[]{(byte) 0xFF, 0x01},
                -1L, b("1")))).isInstanceOf(DependencyException.class);
        // pb 不是字节串：按空字节解析成空记录（同基线 s, _ := v.(string)）
        Snapshot empty = TeamReplies.parseRead(1, 2, reply(b("2"), b("1"), b("1"), 9L, -1L, b("1")));
        assertThat(empty.record()).isEqualTo(TeamRecord.getDefaultInstance());
    }

    // ================================================================ S_COMMIT（store.go:774-809）

    private static Decision decision(List<Long> joined, List<Long> kept, List<Long> left) {
        return new Decision(0, 0, true, null, joined, kept, left, List.of(), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED, 0, false, false, List.of(), 0, 0);
    }

    @Test
    void 提交回复_成功按JKL顺序配对() {
        Decision d = decision(List.of(BIG + 1), List.of(BIG + 2), List.of(BIG + 3));
        CommitOutcome out = TeamReplies.parseCommit(reply(1L, b("8"),
                b(Long.toUnsignedString(BIG)), b("11"),
                b(Long.toUnsignedString(BIG)), b("22"),
                b("0"), b("33")), BIG, d, 1234);
        assertThat(out.status()).isEqualTo(CommitStatus.OK);
        CommitResult r = out.result();
        assertThat(r.teamId()).isEqualTo(BIG);
        assertThat(r.version()).isEqualTo(8);
        assertThat(r.nowMs()).as("取 S_READ 的 nowMs").isEqualTo(1234);
        assertThat(r.decision()).isSameAs(d);
        assertThat(r.indexes()).containsExactly(
                java.util.Map.entry(BIG + 1, new IndexEntry(BIG, 11)),
                java.util.Map.entry(BIG + 2, new IndexEntry(BIG, 22)),
                java.util.Map.entry(BIG + 3, new IndexEntry(0, 33)));
    }

    @Test
    void 提交回复_拒绝分支() {
        Decision d = decision(List.of(1L), List.of(2L), List.of());
        assertThat(TeamReplies.parseCommit(reply(0L), 9, d, 0).status()).isEqualTo(CommitStatus.CONFLICT);
        assertThat(TeamReplies.parseCommit(reply(-1L, 1L), 9, d, 0)).isEqualTo(new CommitOutcome(CommitStatus.MEMBER_IN_TEAM, 1, null));
        assertThat(TeamReplies.parseCommit(reply(-2L, 1L), 9, d, 0)).isEqualTo(new CommitOutcome(CommitStatus.INDEX_MISMATCH, 1, null));
        assertThat(TeamReplies.parseCommit(reply(-3L, 2L), 9, d, 0)).isEqualTo(new CommitOutcome(CommitStatus.INVITE_LIMIT, 2, null));
        assertThat(TeamReplies.parseCommit(reply(-2L, 0L), 9, d, 0).index()).as("非法下标记 0，调用方按越界处理").isZero();
        assertThat(TeamReplies.parseCommit(reply(-2L, b("1")), 9, d, 0).index()).isZero();
        assertThat(TeamReplies.parseCommit(reply(b("1"), b("2")), 9, d, 0).status())
                .as("首项不是整数按 0 = 冲突（同基线 code, _ := arr[0].(int64)）").isEqualTo(CommitStatus.CONFLICT);
    }

    @Test
    void 提交回复_形状不对或未知码是故障() {
        Decision d = decision(List.of(1L), List.of(), List.of());
        assertThatThrownBy(() -> TeamReplies.parseCommit(reply(), 9, d, 0)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseCommit(1L, 9, d, 0)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseCommit(reply(-1L), 9, d, 0)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseCommit(reply(-1L, 1L, 2L), 9, d, 0)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseCommit(reply(2L), 9, d, 0)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseCommit(reply(1L, b("2")), 9, d, 0))
                .as("成功分支长度必须是 2+2(nJ+nK+nL)").isInstanceOf(DependencyException.class);
    }

    // ================================================================ S_INVITE_LIST（store.go:416-436）

    @Test
    void 邀请列表回复_score原样保留() {
        InviteList list = TeamReplies.parseInviteList(reply(b("1900000000000"),
                b(Long.toUnsignedString(BIG)), b("1900000060000"),
                b("42"), b("1.5e3")));
        assertThat(list.nowMs()).isEqualTo(1_900_000_000_000L);
        assertThat(list.entries()).containsExactly(
                new InviteIndexEntry(BIG, 1_900_000_060_000L, "1900000060000"),
                new InviteIndexEntry(42, 1500, "1.5e3"));
        assertThat(TeamReplies.parseInviteList(reply(b("7"))).entries()).isEmpty();
    }

    @Test
    void 邀请列表回复_形状不对或score非法是故障() {
        assertThatThrownBy(() -> TeamReplies.parseInviteList(reply())).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseInviteList(reply(b("1"), b("2")))).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseInviteList(reply(b("1"), b("2"), b("abc"))))
                .isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.parseInviteList(reply(b("1"), b("2"), 5L))).isInstanceOf(DependencyException.class);
    }

    @Test
    void score截成无符号整数() {
        assertThat(TeamReplies.parseScore("2000000060000")).isEqualTo(2_000_000_060_000L);
        assertThat(TeamReplies.parseScore("1.9")).as("向零截断").isEqualTo(1);
        assertThat(TeamReplies.parseScore("-5")).isZero();
        assertThat(TeamReplies.parseScore("9223372036854775808")).as("2^63").isEqualTo(Long.MIN_VALUE);
        assertThat(TeamReplies.parseScore("1e30")).as("≥ 2^64 饱和").isEqualTo(-1L);
        for (String bad : List.of("", "inf", "NaN", "Infinity", "0x10", "1d", " 1")) {
            assertThatThrownBy(() -> TeamReplies.parseScore(bad)).as("score=%s", bad).isInstanceOf(DependencyException.class);
        }
    }

    @Test
    void 非数组回复是故障() {
        assertThatThrownBy(() -> TeamReplies.multi(null, "X")).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> TeamReplies.multi(b("x"), "X")).isInstanceOf(DependencyException.class);
    }
}
