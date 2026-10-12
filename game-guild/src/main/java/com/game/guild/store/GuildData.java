package com.game.guild.store;

import com.game.guild.rules.GuildRoles;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一个帮会的权威快照（基线 GuildData / MemberData，guild_repo.go:86-114）：guild 行 + 成员行，成员按 player_id <b>无符号升序</b>。
 * 由 {@code loadGuild} 装配（G9 + M11）；写事务在最后一次写之后、提交之前用同一事务读出它（txSnapshot），读得到本事务自己的写，
 * 既作响应体也作推送收件人来源（guild_manage_repo.go:461-467）。缓存层把它转成自己的快照 proto（含 score / funds / 成员两列帮贡，
 * 4.5 不改缓存格式，§6.1）。
 *
 * <p>整数字段是无符号位模式：id、毫秒时刻、funds、帮贡是 uint64（{@link Long#toUnsignedString} 展示），level / max_members / zone_id /
 * role 是 uint32；只有 {@code score} 是有符号 int64。{@code announcement} 读时 {@code COALESCE(announcement, '')}，恒非 null。
 * 不可变，任意线程可读。
 */
public record GuildData(long guildId, String name, long leaderId, int level, String announcement, long createTimeMs,
                        int maxMembers, int zoneId, long score, long funds, List<Member> members) {

    /** 一名成员（GuildMemberRow 的全部列）。{@code lastActiveMs} 只在入帮时写，恒等于 {@code joinTimeMs}（§9.1 第 12 条）。 */
    public record Member(long playerId, int role, long joinTimeMs, long lastActiveMs, long contributionTotal,
                         long contributionBalance) {
    }

    public GuildData {
        name = Objects.requireNonNullElse(name, "");
        announcement = Objects.requireNonNullElse(announcement, "");
        members = List.copyOf(members);
    }

    /** 快照里有没有这名玩家。 */
    public boolean hasMember(long playerId) {
        return member(playerId) != null;
    }

    /** 这名玩家的成员行；不在快照里返回 null。 */
    public Member member(long playerId) {
        for (Member m : members) {
            if (m.playerId() == playerId) {
                return m;
            }
        }
        return null;
    }

    /** 全体成员 id（快照顺序，即 player_id 无符号升序）。 */
    public List<Long> memberIds() {
        List<Long> out = new ArrayList<>(members.size());
        for (Member m : members) {
            out.add(m.playerId());
        }
        return out;
    }

    /**
     * 成员 id 减去 {@code excluded}（基线 membersExcept，push.go:294-315）：只做集合减法，保持快照顺序，不去重、不丢 0——
     * 去重丢 0 统一在推送组件里做（uniqueNonZero）。
     */
    public List<Long> memberIdsExcept(long... excluded) {
        List<Long> out = new ArrayList<>(members.size());
        outer:
        for (Member m : members) {
            for (long x : excluded) {
                if (m.playerId() == x) {
                    continue outer;
                }
            }
            out.add(m.playerId());
        }
        return out;
    }

    /** role 为长老（1）的人数（GuildInfo.officer_count，guild_logic.go:759-775）。 */
    public int officerCount() {
        int n = 0;
        for (Member m : members) {
            if (m.role() == GuildRoles.OFFICER) {
                n++;
            }
        }
        return n;
    }
}
