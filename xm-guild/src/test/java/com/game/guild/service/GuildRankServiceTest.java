package com.game.guild.service;

import static com.game.guild.service.GuildServiceFixture.ZONE;
import static com.game.guild.service.GuildServiceFixture.d;
import static com.game.guild.service.ScriptedStore.guild;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import com.game.guild.rules.GuildTip;
import com.game.proto.guild.GetGuildRankByGuildRequest;
import com.game.proto.guild.GetGuildRankByGuildResponse;
import com.game.proto.guild.GetGuildRankRequest;
import com.game.proto.guild.GetGuildRankResponse;
import com.game.proto.guild.GuildRankEntry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 排行两个读 RPC（guild-spec §5.6–§5.7；基线 client_zone_test.go:186-236、guild_logic_names_test.go:113-152、rank_page_test.go 的回显）。
 */
class GuildRankServiceTest {

    private static final long ME = 42;

    private final GuildServiceFixture f = new GuildServiceFixture();

    @BeforeEach
    void inZone() {
        f.zones.put(ME, ZONE);
    }

    @AfterEach
    void close() {
        f.close();
    }

    private GetGuildRankResponse page(int page, int pageSize, int zone) {
        return f.rankService.getGuildRank(ME,
                GetGuildRankRequest.newBuilder().setPage(page).setPageSize(pageSize).setZoneId(zone).build(), d());
    }

    @Test
    void 回显实际生效的页码与页长_客户端页长夹到50_缺省20与1() {
        assertThat(page(0, 0, 0)).satisfies(r -> {
            assertThat(r.getPage()).isEqualTo(1);
            assertThat(r.getPageSize()).isEqualTo(20);
        });
        assertThat(page(1, 60, 0).getPageSize()).isEqualTo(50);
        assertThat(page(3, 50, 0)).satisfies(r -> {
            assertThat(r.getPage()).isEqualTo(3);
            assertThat(r.getPageSize()).isEqualTo(50);
        });
        // page_size 是 uint32：0xFFFFFFFF 按无符号取 min 也是 50
        assertThat(page(1, -1, 0).getPageSize()).isEqualTo(50);
    }

    @Test
    void 只看本区榜_请求体zone被归属区覆盖_条目补帮会与帮主名_帮主名整页一次() {
        f.store.put(guild(10, ZONE, 5, 6));
        f.store.put(guild(11, ZONE, 7));
        f.names.put(5L, "五");
        f.names.put(7L, "七");
        f.rankRedis.put(RedisKeys.guildRankZone(ZONE), 10, 30);
        f.rankRedis.put(RedisKeys.guildRankZone(ZONE), 11, 20);
        f.rankRedis.put(RedisKeys.guildRankZone(ZONE), 12, 10); // 幽灵条目：帮会已不存在
        f.rankRedis.put(RedisKeys.guildRankZone(ZONE + 1), 99, 100);
        f.rankRedis.put(RedisKeys.guildRankAll(), 99, 100);
        GetGuildRankResponse r = page(1, 20, ZONE + 1);
        assertThat(r.getTotalCount()).isEqualTo(3);
        assertThat(r.getEntriesList()).extracting(GuildRankEntry::getGuildId).containsExactly(10L, 11L, 12L);
        assertThat(r.getEntriesList()).extracting(GuildRankEntry::getRank).containsExactly(1, 2, 3);
        assertThat(r.getEntries(0)).satisfies(e -> {
            assertThat(e.getName()).isEqualTo("帮10");
            assertThat(e.getLeaderId()).isEqualTo(5);
            assertThat(e.getLeaderName()).isEqualTo("五");
            assertThat(e.getMemberCount()).isEqualTo(2);
            assertThat(e.getLevel()).isEqualTo(1);
            assertThat(e.getScore()).isEqualTo(30);
        });
        assertThat(r.getEntries(2)).satisfies(e -> {
            assertThat(e.getName()).isEmpty();
            assertThat(e.getLeaderId()).isZero();
            assertThat(e.getScore()).isEqualTo(10);
        });
        assertThat(f.nameCalls).containsExactly(List.of(5L, 7L));
    }

    @Test
    void 读不到帮会快照只记日志_条目只带id分数名次() {
        f.store.put(guild(10, ZONE, 5));
        f.rankRedis.put(RedisKeys.guildRankZone(ZONE), 10, 30);
        f.cacheRedis.failReads = true;
        GetGuildRankResponse r = page(1, 20, 0);
        assertThat(r.getEntriesList()).singleElement().satisfies(e -> {
            assertThat(e.getGuildId()).isEqualTo(10);
            assertThat(e.getRank()).isEqualTo(1);
            assertThat(e.getName()).isEmpty();
        });
    }

    @Test
    void 读排行故障与归属区未知() {
        f.rankRedis.failReads = true;
        assertThatThrownBy(() -> page(1, 20, 0)).isInstanceOf(DependencyException.class);
        f.zones.clear();
        assertThat(page(1, 20, 0).getErrorMessage()).isEqualTo(GuildTip.HOME_ZONE_UNKNOWN.proto());
        assertThat(f.fenceZones).isEmpty();
    }

    @Test
    void 单帮名次_别区的帮与guild_id为0都回14007() {
        f.store.put(guild(99, ZONE + 1, 5));
        f.rankRedis.put(RedisKeys.guildRankZone(ZONE + 1), 99, 100);
        assertThat(f.rankService.getGuildRankByGuild(ME,
                GetGuildRankByGuildRequest.newBuilder().setGuildId(99).setZoneId(ZONE + 1).build(), d()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_RANKED.proto());
        assertThat(f.rankService.getGuildRankByGuild(ME,
                GetGuildRankByGuildRequest.newBuilder().setGuildId(0).build(), d()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_RANKED.proto());
    }

    @Test
    void 单帮名次_补帮会与单个帮主名_帮会读不到时只带id分数名次_读快照故障原样抛() {
        f.store.put(guild(10, ZONE, 5, 6));
        f.names.put(5L, "五");
        f.rankRedis.put(RedisKeys.guildRankZone(ZONE), 11, 50);
        f.rankRedis.put(RedisKeys.guildRankZone(ZONE), 10, 30);
        GetGuildRankByGuildResponse r = f.rankService.getGuildRankByGuild(ME,
                GetGuildRankByGuildRequest.newBuilder().setGuildId(10).build(), d());
        assertThat(r.getEntry()).satisfies(e -> {
            assertThat(e.getRank()).isEqualTo(2);
            assertThat(e.getScore()).isEqualTo(30);
            assertThat(e.getLeaderName()).isEqualTo("五");
            assertThat(e.getMemberCount()).isEqualTo(2);
        });
        assertThat(f.nameCalls).containsExactly(List.of(5L));
        GetGuildRankByGuildResponse ghost = f.rankService.getGuildRankByGuild(ME,
                GetGuildRankByGuildRequest.newBuilder().setGuildId(11).build(), d());
        assertThat(ghost.getEntry().getRank()).isEqualTo(1);
        assertThat(ghost.getEntry().getName()).isEmpty();
        f.cacheRedis.failReads = true;
        assertThatThrownBy(() -> f.rankService.getGuildRankByGuild(ME,
                GetGuildRankByGuildRequest.newBuilder().setGuildId(10).build(), d())).isInstanceOf(DependencyException.class);
    }
}
