package com.game.guild.service;

import com.game.guild.cache.pb.GuildMemberSnapshot;
import com.game.guild.cache.pb.GuildSnapshot;
import com.game.guild.store.GuildData;
import java.util.ArrayList;
import java.util.List;

/**
 * 存储的 {@link GuildData} 与缓存值 {@link GuildSnapshot}（Java 自有 proto，D10）之间的一一转换。两者字段相同（基线 GuildData / MemberData，
 * guild_repo.go:86-114）；成员顺序原样保留（存储按 player_id 无符号升序装配）。纯函数。
 */
public final class GuildSnapshots {

    private GuildSnapshots() {
    }

    /** 回填缓存用（缓存回源 = {@code store.loadGuild} 之后转成快照）。 */
    public static GuildSnapshot toSnapshot(GuildData g) {
        GuildSnapshot.Builder b = GuildSnapshot.newBuilder()
                .setGuildId(g.guildId()).setName(g.name()).setLeaderId(g.leaderId()).setLevel(g.level())
                .setAnnouncement(g.announcement()).setCreateTimeMs(g.createTimeMs()).setMaxMembers(g.maxMembers())
                .setZoneId(g.zoneId()).setScore(g.score()).setFunds(g.funds());
        for (GuildData.Member m : g.members()) {
            b.addMembers(GuildMemberSnapshot.newBuilder()
                    .setPlayerId(m.playerId()).setRole(m.role()).setJoinTimeMs(m.joinTimeMs())
                    .setLastActiveMs(m.lastActiveMs()).setContributionTotal(m.contributionTotal())
                    .setContributionBalance(m.contributionBalance()));
        }
        return b.build();
    }

    /** 读缓存后转回领域对象（视图装配只认 {@link GuildData}）。 */
    public static GuildData toData(GuildSnapshot s) {
        List<GuildData.Member> members = new ArrayList<>(s.getMembersCount());
        for (GuildMemberSnapshot m : s.getMembersList()) {
            members.add(new GuildData.Member(m.getPlayerId(), m.getRole(), m.getJoinTimeMs(), m.getLastActiveMs(),
                    m.getContributionTotal(), m.getContributionBalance()));
        }
        return new GuildData(s.getGuildId(), s.getName(), s.getLeaderId(), s.getLevel(), s.getAnnouncement(),
                s.getCreateTimeMs(), s.getMaxMembers(), s.getZoneId(), s.getScore(), s.getFunds(), members);
    }
}
