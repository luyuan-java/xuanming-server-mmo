package com.game.guild.service;

import com.game.common.deadline.Deadline;
import com.game.guild.cache.GuildCache;
import com.game.guild.cache.pb.GuildSnapshot;
import com.game.guild.rank.GuildRanks;
import com.game.guild.rank.GuildRanks.RankEntry;
import com.game.guild.rank.GuildRanks.RankPage;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildTip;
import com.game.guild.service.GuildAccess.ZoneCheck;
import com.game.guild.store.GuildData;
import com.game.proto.guild.GetGuildRankByGuildRequest;
import com.game.proto.guild.GetGuildRankByGuildResponse;
import com.game.proto.guild.GetGuildRankRequest;
import com.game.proto.guild.GetGuildRankResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 帮会排行的两个读 RPC（基线 guild_logic.go:594-678；guild-spec §5.6–§5.9）。客户端只看<b>本区榜</b>：zone 以归属区覆盖（全服榜 zone 0
 * 只对内部调用开放，Java 4.4 没有入口，D12）；单页条数夹到 50，回包回显<b>实际生效</b>的 page / page_size（客户端翻页器只用回包里的值）。
 * 写榜（入榜 / 清榜 / 重建）不在这里：建帮、解散与启动期由 {@link GuildService} / 装配调用 {@link GuildRanks}。
 *
 * <p>阻塞，只在工作线程上调用。线程安全。
 */
public final class GuildRankService {

    private final GuildRanks ranks;
    private final GuildCache cache;
    private final GuildAccess access;
    private final GuildViews views;

    public GuildRankService(GuildRanks ranks, GuildCache cache, GuildAccess access, GuildViews views) {
        this.ranks = ranks;
        this.cache = cache;
        this.access = access;
        this.views = views;
    }

    /**
     * 一页排行（guild_logic.go:596-634；§5.6）：page_size 0 → 20、page 0 → 1 → 归属区（14012 / 故障）→ zone 以归属区覆盖、page_size 夹到 50 →
     * 读页（出错 → 故障）→ 一次多键 GET 补帮会（D15，读不到的条目只带 id / score / rank）→ 帮主名整页一次。
     * {@code total_count = ZCARD}（uint32 截断同基线）。
     */
    public GetGuildRankResponse getGuildRank(long me, GetGuildRankRequest request, Deadline deadline) {
        int pageSize = request.getPageSize() == 0 ? GuildLimits.DEFAULT_RANK_PAGE_SIZE : request.getPageSize();
        int page = request.getPage() == 0 ? GuildLimits.DEFAULT_RANK_PAGE : request.getPage();
        ZoneCheck zone = access.clientZone(me, deadline);
        if (zone.rejected()) {
            return GetGuildRankResponse.newBuilder().setErrorMessage(zone.tip().proto()).build();
        }
        if (Integer.compareUnsigned(pageSize, GuildLimits.MAX_RANK_PAGE_SIZE) > 0) {
            pageSize = GuildLimits.MAX_RANK_PAGE_SIZE; // page_size 是 uint32：按无符号取 min（同基线 min(pageSize, 50)）
        }
        RankPage rankPage = ranks.page(zone.zoneId(), page, pageSize, deadline);
        List<Long> ids = new ArrayList<>(rankPage.entries().size());
        for (RankEntry e : rankPage.entries()) {
            ids.add(e.guildId());
        }
        Map<Long, GuildData> guilds = new HashMap<>();
        for (Map.Entry<Long, GuildSnapshot> e : cache.guildsForDisplay(ids, deadline).entrySet()) {
            guilds.put(e.getKey(), GuildSnapshots.toData(e.getValue()));
        }
        return GetGuildRankResponse.newBuilder()
                .addAllEntries(views.rankEntries(rankPage.entries(), guilds, deadline))
                .setTotalCount((int) rankPage.total())
                .setPage(page)
                .setPageSize(pageSize)
                .build();
    }

    /**
     * 单帮名次（guild_logic.go:637-678；§5.7）：归属区 → 本区榜上查名次（一段 Lua 同时取名次与分数，D16；不在榜上 → 14007：别区的帮、
     * guild_id = 0 都落到这里）→ 读帮会（出错 → 故障；读不到 → 条目只带 id / score / rank）→ 单个帮主名。
     */
    public GetGuildRankByGuildResponse getGuildRankByGuild(long me, GetGuildRankByGuildRequest request, Deadline deadline) {
        ZoneCheck zone = access.clientZone(me, deadline);
        if (zone.rejected()) {
            return GetGuildRankByGuildResponse.newBuilder().setErrorMessage(zone.tip().proto()).build();
        }
        Optional<RankEntry> entry = ranks.rankOf(request.getGuildId(), zone.zoneId(), deadline);
        if (entry.isEmpty()) {
            return GetGuildRankByGuildResponse.newBuilder().setErrorMessage(GuildTip.NOT_RANKED.proto()).build();
        }
        GuildData guild = cache.guild(entry.get().guildId(), deadline).map(GuildSnapshots::toData).orElse(null);
        return GetGuildRankByGuildResponse.newBuilder().setEntry(views.rankEntry(entry.get(), guild, deadline)).build();
    }
}
