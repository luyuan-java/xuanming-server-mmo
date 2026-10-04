package com.game.guild.service;

import com.game.common.deadline.Deadline;
import com.game.guild.presence.OnlineStatuses;
import com.game.guild.presence.PlayerNames;
import com.game.guild.rank.GuildRanks.RankEntry;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTableRules.LevelDisplay;
import com.game.guild.store.GuildData;
import com.game.guild.store.GuildStore.ApplicantRow;
import com.game.guild.store.GuildStore.ApplicationRow;
import com.game.proto.guild.GuildApplicantView;
import com.game.proto.guild.GuildApplicationView;
import com.game.proto.guild.GuildInfo;
import com.game.proto.guild.GuildMember;
import com.game.proto.guild.GuildRankEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端视图装配（基线 toProtoGuild + guildInfoFor，guild_logic.go:715-786、guild_manage_logic.go:766-802；applicantViews /
 * myApplicationViews / enrichRankEntries；guild-spec §4.1、§3.13–§3.14、§5.6–§5.7）。
 *
 * <p>展示字段一律 fail-open：在线状态读不到按离线（{@link OnlineStatuses}）、名字取不到留空（{@link PlayerNames}）、GuildLevel 缺行时
 * {@code max_officers} / {@code upgrade_cost_funds} 留 0 并记 ERROR、待审数读失败留 0 并记 ERROR——少几个展示字段不值得让整次读失败。
 * 每个填充点只发<b>一次</b>批量取名（成员 + 帮主合并成一次；榜单一页的帮主一次；待审名单一次；本人申请列表的帮主一次）。
 *
 * <p>阻塞（MySQL / 等 Redis），只在工作线程上调用。线程安全。
 */
public final class GuildViews {

    private static final Logger log = LoggerFactory.getLogger(GuildViews.class);

    /** 帮会有效待审数（生产 {@code GuildStore::countLiveApplications}，A13 直读 MySQL、不进缓存）。 */
    @FunctionalInterface
    public interface PendingCounter {
        long count(long guildId, long nowMs, Deadline deadline);
    }

    /** 本人视角的一条申请与它指向的帮会（已按可见区过滤）。 */
    public record MyApplication(ApplicationRow row, GuildData guild) {
    }

    private final OnlineStatuses online;
    private final PlayerNames names;
    private final GuildTableLookup tables;
    private final PendingCounter pending;
    private final LongSupplier clockMs;

    public GuildViews(OnlineStatuses online, PlayerNames names, GuildTableLookup tables, PendingCounter pending,
                      LongSupplier clockMs) {
        this.online = online;
        this.names = names;
        this.tables = tables;
        this.pending = pending;
        this.clockMs = clockMs;
    }

    /**
     * GuildInfo（基线 guildInfoFor = toProtoGuild + 待审数）。
     *
     * <ol>
     *   <li><b>先</b>查在线（成员 id）、<b>再</b>取名（成员 id + 帮主 id 一次；帮主单独追加，坏数据下帮主不在成员表里也能显示帮主名）；</li>
     *   <li>成员按快照顺序（player_id 升序）；{@code officer_count} = role 为 1 的人数；score 不进 GuildInfo；</li>
     *   <li>{@code pending_application_count}：viewer 在快照里且档位 ≥ 长老才现算（A13，不进缓存），否则 0——只用于展示，不用于授权。</li>
     * </ol>
     *
     * @param viewer 请求者（客户端来源恒为会话 player_id；0 = 没有请求者视角）
     */
    public GuildInfo guildInfo(GuildData g, long viewer, Deadline deadline) {
        List<Long> memberIds = g.memberIds();
        Set<Long> onlineNow = online.onlineOf(memberIds, deadline);
        List<Long> nameIds = new ArrayList<>(memberIds.size() + 1);
        nameIds.addAll(memberIds);
        nameIds.add(g.leaderId());
        Map<Long, String> nameOf = names.namesOf(nameIds, deadline);

        GuildInfo.Builder info = GuildInfo.newBuilder()
                .setGuildId(g.guildId()).setName(g.name()).setLeaderId(g.leaderId())
                .setLeaderName(nameOf.getOrDefault(g.leaderId(), ""))
                .setLevel(g.level()).setAnnouncement(g.announcement()).setCreateTimeMs(g.createTimeMs())
                .setMaxMembers(g.maxMembers()).setZoneId(g.zoneId()).setFunds(g.funds());
        int officers = 0;
        for (GuildData.Member m : g.members()) {
            if (m.role() == GuildRoles.OFFICER) {
                officers++;
            }
            info.addMembers(GuildMember.newBuilder()
                    .setPlayerId(m.playerId()).setName(nameOf.getOrDefault(m.playerId(), "")).setRole(m.role())
                    .setJoinTimeMs(m.joinTimeMs()).setLastActiveMs(m.lastActiveMs())
                    .setContributionTotal(m.contributionTotal()).setContributionBalance(m.contributionBalance())
                    .setOnline(onlineNow.contains(m.playerId())));
        }
        info.setOfficerCount(officers);
        LevelDisplay display = tables.levelDisplay(g.level());
        if (display != null) {
            info.setMaxOfficers(display.maxOfficers()).setUpgradeCostFunds(display.upgradeCostFunds());
        } else {
            log.error("[guild] GuildLevel row {} missing while rendering guild {}", Integer.toUnsignedString(g.level()),
                    Long.toUnsignedString(g.guildId()));
        }
        info.setPendingApplicationCount(pendingCount(g, viewer, deadline));
        return info.build();
    }

    private int pendingCount(GuildData g, long viewer, Deadline deadline) {
        if (viewer == 0) {
            return 0;
        }
        GuildData.Member me = g.member(viewer);
        if (me == null || !GuildRoles.seesPendingApplicationCount(me.role())) {
            return 0;
        }
        try {
            return (int) pending.count(g.guildId(), clockMs.getAsLong(), deadline);
        } catch (RuntimeException e) {
            log.error("[guild] count pending applications of guild {}: {}", Long.toUnsignedString(g.guildId()), e.toString());
            return 0;
        }
    }

    /** 审批人视角的待审名单（基线 applicantViews，guild_manage_logic.go:663-682）：在线一次批量读 + 名字一次批量读，共用同一份 id。 */
    public List<GuildApplicantView> applicantViews(List<ApplicantRow> rows, Deadline deadline) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (ApplicantRow row : rows) {
            ids.add(row.playerId());
        }
        Set<Long> onlineNow = online.onlineOf(ids, deadline);
        Map<Long, String> nameOf = names.namesOf(ids, deadline);
        List<GuildApplicantView> views = new ArrayList<>(rows.size());
        for (ApplicantRow row : rows) {
            views.add(GuildApplicantView.newBuilder()
                    .setPlayerId(row.playerId()).setName(nameOf.getOrDefault(row.playerId(), ""))
                    .setOnline(onlineNow.contains(row.playerId()))
                    .setApplyMs(row.applyMs()).setExpireMs(row.expireMs()).build());
        }
        return views;
    }

    /**
     * 申请人视角的申请列表（基线 myApplicationViews，guild_manage_logic.go:580-614）：调用方已逐行读帮会、按可见区过滤；
     * 帮主名循环之后一次批量回填。{@code member_count = len(members)}。
     */
    public List<GuildApplicationView> applicationViews(List<MyApplication> applications, Deadline deadline) {
        List<Long> leaderIds = new ArrayList<>(applications.size());
        for (MyApplication a : applications) {
            leaderIds.add(a.guild().leaderId());
        }
        Map<Long, String> nameOf = names.namesOf(leaderIds, deadline);
        List<GuildApplicationView> views = new ArrayList<>(applications.size());
        for (MyApplication a : applications) {
            GuildData g = a.guild();
            views.add(GuildApplicationView.newBuilder()
                    .setGuildId(g.guildId()).setGuildName(g.name()).setLevel(g.level())
                    .setMemberCount(g.members().size()).setMaxMembers(g.maxMembers())
                    .setLeaderId(g.leaderId()).setLeaderName(nameOf.getOrDefault(g.leaderId(), ""))
                    .setApplyMs(a.row().applyMs()).setExpireMs(a.row().expireMs()).build());
        }
        return views;
    }

    /**
     * 排行一页（基线 enrichRankEntries，guild_logic.go:680-711）：读不到的帮会（读失败或幽灵条目）只带 guild_id / score / rank；
     * 帮主名整页一次批量查。
     *
     * @param guilds 本页读到的帮会（D15：一次多键 GET；缺席 = 读不到）
     */
    public List<GuildRankEntry> rankEntries(List<RankEntry> entries, Map<Long, GuildData> guilds, Deadline deadline) {
        List<Long> leaderIds = new ArrayList<>(entries.size());
        for (RankEntry e : entries) {
            GuildData g = guilds.get(e.guildId());
            if (g != null) {
                leaderIds.add(g.leaderId());
            }
        }
        Map<Long, String> nameOf = names.namesOf(leaderIds, deadline);
        List<GuildRankEntry> out = new ArrayList<>(entries.size());
        for (RankEntry e : entries) {
            out.add(rankEntry(e, guilds.get(e.guildId()), nameOf));
        }
        return out;
    }

    /** 单帮名次（基线 GetGuildRankByGuild，guild_logic.go:658-675）：帮会读不到时只带 id / score / rank；帮主名单独查一次。 */
    public GuildRankEntry rankEntry(RankEntry e, GuildData g, Deadline deadline) {
        Map<Long, String> nameOf = g == null ? Map.of() : names.namesOf(List.of(g.leaderId()), deadline);
        return rankEntry(e, g, nameOf);
    }

    private static GuildRankEntry rankEntry(RankEntry e, GuildData g, Map<Long, String> nameOf) {
        GuildRankEntry.Builder b = GuildRankEntry.newBuilder().setGuildId(e.guildId()).setScore(e.score()).setRank(e.rank());
        if (g != null) {
            b.setName(g.name()).setLeaderId(g.leaderId()).setLevel(g.level()).setMemberCount(g.members().size())
                    .setLeaderName(nameOf.getOrDefault(g.leaderId(), ""));
        }
        return b.build();
    }
}
