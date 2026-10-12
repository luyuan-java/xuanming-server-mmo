package com.game.team.store;

import com.game.discovery.RedisKeys;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Decision;
import com.game.team.rules.InviteAdd;
import com.game.team.rules.TeamLimits;
import com.game.team.rules.TeamRules;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S_COMMIT 的集合校验与 KEYS / ARGV 组装（基线 store.go:812-902 buildCommitArgs / validateCommitSets）。纯函数，无 I/O。
 */
final class CommitSets {

    private static final byte[] EMPTY = new byte[0];

    private CommitSets() {
    }

    /** 一次 S_COMMIT 调用的键与参数（布局见 {@link TeamScript#COMMIT}）。 */
    record Call(List<Object> keys, List<byte[]> args) {
    }

    /**
     * 提交前校验 Decision 的集合契约（store.go:851-902）。违反即程序缺陷，抛 {@link IllegalStateException}，绝不带着错的集合去写。
     *
     * <ul>
     *   <li>teamId ≠ 0；joined / kept / left 不含 0、两两不相交（三者合起来无重复）；</li>
     *   <li>解散（record 为 null）不带 joined / kept；否则记录的 team_id 等于绑定的 tid、{@code joined ∪ kept} 按多重集等于新记录成员、
     *       队长在成员里、成员数不超过容量；</li>
     *   <li>invitesAdded 不含 0、不重复；invitesRemoved 不含 0、不重复、不与 invitesAdded 重叠；</li>
     *   <li><b>D25（Java 增项，只会更严）</b>：{@code before} 非 null 时 {@code left ⊇ before 成员 \ 新记录成员}——基线 rules.go:152 的注释
     *       说会校验，实现漏了（team-spec §8.1 第 6 条、§8.4）。</li>
     * </ul>
     *
     * @param before 本轮规则的输入记录（建队为 null）
     */
    static void validate(long teamId, Decision d, TeamRecord before) {
        if (teamId == 0) {
            throw new IllegalStateException("提交的 team_id 为 0");
        }
        Map<Long, String> seen = new HashMap<>();
        checkGroup("Joined", d.joined(), seen);
        checkGroup("Kept", d.kept(), seen);
        checkGroup("Left", d.left(), seen);
        TeamRecord rec = d.record();
        if (rec == null) {
            if (!d.joined().isEmpty() || !d.kept().isEmpty()) {
                throw new IllegalStateException("解散提交不能有 Joined / Kept");
            }
        } else {
            if (rec.getTeamId() != teamId) {
                throw new IllegalStateException("记录 team_id " + Long.toUnsignedString(rec.getTeamId()) + " 与绑定 "
                        + Long.toUnsignedString(teamId) + " 不一致");
            }
            List<Long> joinedAndKept = new ArrayList<>(d.joined());
            joinedAndKept.addAll(d.kept());
            if (!TeamRules.sameIdSet(TeamRules.memberIds(rec), joinedAndKept)) {
                throw new IllegalStateException("Joined ∪ Kept 与新记录成员集合不一致");
            }
            if (TeamRules.findMember(rec, rec.getLeaderId()) == null) {
                throw new IllegalStateException("队长 " + Long.toUnsignedString(rec.getLeaderId()) + " 不在成员里");
            }
            if (rec.getMembersCount() > TeamLimits.CAPACITY) {
                throw new IllegalStateException("成员数 " + rec.getMembersCount() + " 超过容量");
            }
        }
        Set<Long> added = new HashSet<>();
        for (InviteAdd a : d.invitesAdded()) {
            if (a.inviteeId() == 0 || !added.add(a.inviteeId())) {
                throw new IllegalStateException("InvitesAdded 含非法或重复项 " + Long.toUnsignedString(a.inviteeId()));
            }
        }
        Set<Long> removed = new HashSet<>();
        for (long pid : d.invitesRemoved()) {
            if (pid == 0 || added.contains(pid) || !removed.add(pid)) {
                throw new IllegalStateException("InvitesRemoved 含非法、重复或与 InvitesAdded 重叠的项 " + Long.toUnsignedString(pid));
            }
        }
        if (before != null) {
            Set<Long> after = new HashSet<>(rec == null ? List.of() : TeamRules.memberIds(rec));
            Set<Long> left = new HashSet<>(d.left());
            for (long pid : TeamRules.memberIds(before)) {
                if (!after.contains(pid) && !left.contains(pid)) {
                    throw new IllegalStateException("旧成员 " + Long.toUnsignedString(pid) + " 不在新记录里也不在 Left 里（D25）");
                }
            }
        }
    }

    private static void checkGroup(String name, List<Long> group, Map<Long, String> seen) {
        for (long pid : group) {
            if (pid == 0) {
                throw new IllegalStateException(name + " 含 player_id 0");
            }
            String prev = seen.putIfAbsent(pid, name);
            if (prev != null) {
                throw new IllegalStateException("player " + Long.toUnsignedString(pid) + " 同时出现在 " + prev + " 与 " + name);
            }
        }
    }

    /**
     * 组装 S_COMMIT 的 KEYS / ARGV（store.go:815-849）。只翻译，不去重、不改集合：集合的正确性由 {@link #validate} 把关，
     * Lua 的「先 ZREM 后 ZADD」是第二道保险。数字一律无符号 ASCII 十进制；recPb / projPb 在解散时为空。
     *
     * @param expectedVer 十进制 ver，或建队用 {@code "new"}
     */
    static Call build(long teamId, String expectedVer, Decision d) {
        byte[] recPb = EMPTY;
        byte[] projPb = EMPTY;
        if (d.record() != null) {
            recPb = d.record().toByteArray();
            projPb = TeamStore.projectionOf(d.record()).toByteArray();
        }
        List<Object> keys = new ArrayList<>(2 + d.joined().size() + d.kept().size() + d.left().size()
                + d.invitesAdded().size() + d.invitesRemoved().size());
        keys.add(RedisKeys.teamRecord(teamId));
        keys.add(RedisKeys.teamInfo(teamId));
        for (List<Long> group : List.of(d.joined(), d.kept(), d.left())) {
            for (long pid : group) {
                keys.add(RedisKeys.teamPlayer(pid));
            }
        }
        for (InviteAdd a : d.invitesAdded()) {
            keys.add(RedisKeys.teamInvite(a.inviteeId()));
        }
        for (long pid : d.invitesRemoved()) {
            keys.add(RedisKeys.teamInvite(pid));
        }
        List<byte[]> args = new ArrayList<>(11 + d.invitesAdded().size());
        args.add(ascii(expectedVer));
        args.add(recPb);
        args.add(projPb);
        args.add(TeamStore.TTL_ARG.clone());
        args.add(ascii(Long.toUnsignedString(teamId)));
        args.add(ascii(Integer.toString(d.joined().size())));
        args.add(ascii(Integer.toString(d.kept().size())));
        args.add(ascii(Integer.toString(d.left().size())));
        args.add(ascii(Integer.toString(d.invitesAdded().size())));
        args.add(ascii(Integer.toString(d.invitesRemoved().size())));
        for (InviteAdd a : d.invitesAdded()) {
            args.add(ascii(Long.toUnsignedString(a.expireAtMs())));
        }
        args.add(ascii(Integer.toString(TeamLimits.MAX_PENDING_INVITES_PER_INVITEE)));
        return new Call(List.copyOf(keys), List.copyOf(args));
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
