package com.game.team.rules;

import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamInviteRecord;
import com.game.team.proto.TeamMemberRecord;
import com.game.team.proto.TeamRecord;
import java.util.HashMap;
import java.util.Map;

/** 规则单测的造数（照基线 rules_test.go:15-65）。nowMs 一律是固定常量，不读墙钟。 */
final class RuleFixtures {

    static final long NOW = 1_900_000_000_000L;
    static final long TID = 7_000_001L;
    static final int ZONE = 1;

    private RuleFixtures() {
    }

    /** 造一支队伍：members[0] 是队长，join_seq 依次 1..n。 */
    static TeamRecord newRecord(long... members) {
        TeamRecord.Builder b = TeamRecord.newBuilder()
                .setTeamId(TID)
                .setLeaderId(members[0])
                .setZoneId(ZONE)
                .setCreatedAtMs(NOW - 1000);
        for (int i = 0; i < members.length; i++) {
            b.addMembers(TeamMemberRecord.newBuilder()
                    .setPlayerId(members[i])
                    .setZoneId(ZONE)
                    .setJoinedAtMs(NOW - 1000)
                    .setJoinSeq(i + 1));
        }
        return b.setNextJoinSeq(members.length + 1).build();
    }

    static TeamRecord withApplication(TeamRecord rec, long pid, int zone, long appliedAt, long expireAt) {
        return rec.toBuilder().addApplications(TeamApplicationRecord.newBuilder()
                .setPlayerId(pid)
                .setZoneId(zone)
                .setAppliedAtMs(appliedAt)
                .setExpireAtMs(expireAt)).build();
    }

    static TeamRecord withInvite(TeamRecord rec, long invitee, int zone, long invitedAt, long expireAt) {
        return rec.toBuilder().addInvites(TeamInviteRecord.newBuilder()
                .setInviteeId(invitee)
                .setInviterId(rec.getLeaderId())
                .setZoneId(zone)
                .setInvitedAtMs(invitedAt)
                .setExpireAtMs(expireAt)).build();
    }

    static TeamRecord withLock(TeamRecord rec, long expireAt) {
        return rec.toBuilder()
                .setMatchLockToken("lock-token")
                .setMatchLockExpireAtMs(expireAt)
                .clearMatchLockRoster()
                .addAllMatchLockRoster(TeamRules.memberIds(rec))
                .build();
    }

    static Map<Long, SessionState> allOnline(long... ids) {
        Map<Long, SessionState> s = new HashMap<>();
        for (long id : ids) {
            s.put(id, SessionState.ONLINE);
        }
        return s;
    }

    static Decision apply(Op op, TeamRecord rec) {
        return TeamRules.apply(op, rec, NOW, null, RuleConfig.DEFAULT);
    }
}
