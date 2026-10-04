package com.game.team.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.TeamInfo;
import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamMemberRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Decision;
import com.game.team.rules.InviteAdd;
import com.game.team.rules.Op;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.SessionState;
import com.game.team.rules.TeamRules;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 提交集合校验（基线 store.go:851-902 validateCommitSets，加 D25 的 {@code Left ⊇ 旧 \ 新}）与 S_COMMIT 的 KEYS / ARGV 组装
 * （store.go:815-849）。纯函数，不起 Redis。
 */
class CommitSetsTest {

    private static final long NOW = 1_900_000_000_000L;
    private static final long TID = Long.MIN_VALUE + 7; // ≥ 2^63
    private static final long A = Long.MIN_VALUE + 101, B = Long.MIN_VALUE + 102, C = Long.MIN_VALUE + 103;

    private static TeamRecord team(long... members) {
        TeamRecord.Builder b = TeamRecord.newBuilder().setTeamId(TID).setLeaderId(members[0]).setZoneId(1)
                .setCreatedAtMs(NOW - 1000);
        for (int i = 0; i < members.length; i++) {
            b.addMembers(TeamMemberRecord.newBuilder().setPlayerId(members[i]).setZoneId(1).setJoinedAtMs(NOW - 1000)
                    .setJoinSeq(i + 1));
        }
        return b.setNextJoinSeq(members.length + 1).build();
    }

    private static Decision decision(TeamRecord rec, List<Long> joined, List<Long> kept, List<Long> left,
                                     List<InviteAdd> added, List<Long> removed) {
        return new Decision(0, 0, true, rec, joined, kept, left, added, removed,
                TeamChangeReason.TEAM_CHANGE_REASON_HEALED, 0, false, rec == null, List.of(), 0, 0);
    }

    private static void rejects(Decision d, TeamRecord before, String messagePart) {
        assertThatThrownBy(() -> CommitSets.validate(TID, d, before)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messagePart);
    }

    @Test
    void 规则层产出的各类决策都通过校验() {
        TeamRecord rec = team(A, B);
        Map<Long, SessionState> online = Map.of(A, SessionState.ONLINE, B, SessionState.ONLINE);
        TeamRecord withApp = rec.toBuilder().addApplications(TeamApplicationRecord.newBuilder().setPlayerId(C).setZoneId(1)
                .setAppliedAtMs(NOW).setExpireAtMs(NOW + 60_000)).build();
        List<Decision> decisions = List.of(
                TeamRules.apply(Op.create(A, TID, 1), null, NOW, null, RuleConfig.DEFAULT),
                TeamRules.apply(Op.apply(C, 1), rec, NOW, online, RuleConfig.DEFAULT),
                TeamRules.apply(Op.handleApplication(A, C, true), withApp, NOW, online, RuleConfig.DEFAULT),
                TeamRules.apply(Op.invite(A, C, 1), rec, NOW, online, RuleConfig.DEFAULT),
                TeamRules.apply(Op.kick(A, B), rec, NOW, online, RuleConfig.DEFAULT),
                TeamRules.apply(Op.leave(B), rec, NOW, online, RuleConfig.DEFAULT),
                TeamRules.apply(Op.disband(A), rec, NOW, online, RuleConfig.DEFAULT),
                TeamRules.repairRemoveMembers(rec, List.of(B), NOW, online),
                TeamRules.repairRemoveMembers(rec, List.of(A, B), NOW, online));
        for (Decision d : decisions) {
            assertThat(d.changed()).isTrue();
            TeamRecord before = d.reason() == TeamChangeReason.TEAM_CHANGE_REASON_CREATED ? null
                    : d.joined().contains(C) ? withApp : rec;
            assertThatCode(() -> CommitSets.validate(TID, d, before)).as("reason=%s", d.reason()).doesNotThrowAnyException();
        }
    }

    @Test
    void 违反集合契约即拒绝() {
        TeamRecord rec = team(A, B);
        assertThatCode(() -> CommitSets.validate(TID, decision(rec, List.of(), List.of(A, B), List.of(), List.of(),
                List.of()), rec)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CommitSets.validate(0, decision(rec, List.of(), List.of(A, B), List.of(), List.of(),
                List.of()), rec)).hasMessageContaining("team_id 为 0");
        rejects(decision(rec, List.of(0L), List.of(A, B), List.of(), List.of(), List.of()), rec, "player_id 0");
        rejects(decision(rec, List.of(A), List.of(A, B), List.of(), List.of(), List.of()), rec, "同时出现");
        rejects(decision(rec, List.of(), List.of(A, B), List.of(B), List.of(), List.of()), rec, "同时出现");
        rejects(decision(null, List.of(), List.of(A), List.of(B), List.of(), List.of()), rec, "解散提交不能有");
        rejects(decision(rec.toBuilder().setTeamId(TID + 1).build(), List.of(), List.of(A, B), List.of(), List.of(), List.of()),
                rec, "不一致");
        rejects(decision(rec, List.of(), List.of(A), List.of(), List.of(), List.of()), rec, "Joined ∪ Kept");
        rejects(decision(rec, List.of(C), List.of(A, B), List.of(), List.of(), List.of()), rec, "Joined ∪ Kept");
        rejects(decision(rec.toBuilder().setLeaderId(C).build(), List.of(), List.of(A, B), List.of(), List.of(), List.of()),
                rec, "队长");
        long d1 = Long.MIN_VALUE + 201, d2 = Long.MIN_VALUE + 202, d3 = Long.MIN_VALUE + 203, d4 = Long.MIN_VALUE + 204;
        TeamRecord six = team(A, B, C, d1, d2, d3);
        rejects(decision(six, List.of(), List.of(A, B, C, d1, d2, d3), List.of(), List.of(), List.of()), six, "超过容量");
        rejects(decision(rec, List.of(), List.of(A, B), List.of(), List.of(new InviteAdd(0, NOW)), List.of()), rec, "InvitesAdded");
        rejects(decision(rec, List.of(), List.of(A, B), List.of(), List.of(new InviteAdd(d4, NOW), new InviteAdd(d4, NOW)),
                List.of()), rec, "InvitesAdded");
        rejects(decision(rec, List.of(), List.of(A, B), List.of(), List.of(), List.of(0L)), rec, "InvitesRemoved");
        rejects(decision(rec, List.of(), List.of(A, B), List.of(), List.of(), List.of(d4, d4)), rec, "InvitesRemoved");
        rejects(decision(rec, List.of(), List.of(A, B), List.of(), List.of(new InviteAdd(d4, NOW)), List.of(d4)), rec,
                "InvitesRemoved");
    }

    @Test
    void D25_旧成员不在新记录里就必须在Left里() {
        TeamRecord rec = team(A, B, C);
        TeamRecord withoutC = team(A, B);
        rejects(decision(withoutC, List.of(), List.of(A, B), List.of(), List.of(), List.of()), rec, "D25");
        rejects(decision(null, List.of(), List.of(), List.of(A, B), List.of(), List.of()), rec, "D25");
        assertThatCode(() -> CommitSets.validate(TID, decision(withoutC, List.of(), List.of(A, B), List.of(C), List.of(),
                List.of()), rec)).doesNotThrowAnyException();
        // Left 可以多出「索引指向本队但记录里没有」的人；建队（before 为 null）不查
        long stray = Long.MIN_VALUE + 999;
        assertThatCode(() -> CommitSets.validate(TID, decision(withoutC, List.of(), List.of(A, B), List.of(C, stray),
                List.of(), List.of()), rec)).doesNotThrowAnyException();
        assertThatCode(() -> CommitSets.validate(TID, decision(withoutC, List.of(), List.of(A, B), List.of(), List.of(),
                List.of()), null)).doesNotThrowAnyException();
    }

    private static String s(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Test
    void 参数布局_键按JKL_IA_ID_数字一律无符号十进制() {
        TeamRecord rec = team(A, B);
        Decision d = decision(rec, List.of(B), List.of(A), List.of(C),
                List.of(new InviteAdd(Long.MIN_VALUE + 301, -2L), new InviteAdd(Long.MIN_VALUE + 302, 5)),
                List.of(Long.MIN_VALUE + 303));
        CommitSets.Call call = CommitSets.build(TID, "41", d);
        assertThat(call.keys()).containsExactly(
                RedisKeys.teamRecord(TID), RedisKeys.teamInfo(TID),
                RedisKeys.teamPlayer(B), RedisKeys.teamPlayer(A), RedisKeys.teamPlayer(C),
                RedisKeys.teamInvite(Long.MIN_VALUE + 301), RedisKeys.teamInvite(Long.MIN_VALUE + 302),
                RedisKeys.teamInvite(Long.MIN_VALUE + 303));
        assertThat(call.keys().get(0)).isEqualTo("xm:{team}:rec:" + Long.toUnsignedString(TID));
        List<byte[]> a = call.args();
        assertThat(a).hasSize(13);
        assertThat(s(a.get(0))).isEqualTo("41");
        assertThat(a.get(1)).isEqualTo(rec.toByteArray());
        assertThat(a.get(2)).isEqualTo(TeamStore.projectionOf(rec).toByteArray());
        assertThat(s(a.get(3))).isEqualTo("86400");
        assertThat(s(a.get(4))).isEqualTo(Long.toUnsignedString(TID));
        assertThat(List.of(s(a.get(5)), s(a.get(6)), s(a.get(7)), s(a.get(8)), s(a.get(9))))
                .containsExactly("1", "1", "1", "2", "1");
        assertThat(s(a.get(10))).isEqualTo("18446744073709551614");
        assertThat(s(a.get(11))).isEqualTo("5");
        assertThat(s(a.get(12))).as("被邀请人待处理邀请上限").isEqualTo("10");
    }

    @Test
    void 参数布局_解散时记录与投影为空串_建队用new哨兵() {
        Decision disband = decision(null, List.of(), List.of(), List.of(A), List.of(), List.of());
        CommitSets.Call call = CommitSets.build(TID, "3", disband);
        assertThat(call.args().get(1)).isEmpty();
        assertThat(call.args().get(2)).isEmpty();
        Decision create = TeamRules.apply(Op.create(A, TID, 1), null, NOW, null, RuleConfig.DEFAULT);
        assertThat(s(CommitSets.build(TID, TeamStore.NEW_TEAM_VERSION, create).args().get(0))).isEqualTo("new");
    }

    @Test
    void 投影按join_seq升序只为确定性() throws Exception {
        TeamRecord rec = team(A, B).toBuilder()
                .setMembers(0, team(A, B).getMembers(0).toBuilder().setJoinSeq(9)).build();
        TeamInfo info = TeamInfo.parseFrom(TeamStore.projectionOf(rec).toByteArray());
        assertThat(info.getTeamId()).isEqualTo(TID);
        assertThat(info.getLeaderId()).isEqualTo(A);
        assertThat(info.getMembersList()).containsExactly(B, A);
    }
}
