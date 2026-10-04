package com.game.guild.service;

import static com.game.guild.service.GuildServiceFixture.NOW;
import static com.game.guild.service.GuildServiceFixture.ZONE;
import static com.game.guild.service.GuildServiceFixture.d;
import static com.game.guild.service.ScriptedStore.guild;
import static com.game.guild.service.ScriptedStore.withRole;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildTip;
import com.game.guild.rules.GuildTips;
import com.game.guild.store.GuildData;
import com.game.guild.store.GuildStore.AnnouncementUpdated;
import com.game.guild.store.GuildStore.Created;
import com.game.guild.store.GuildStore.Disbanded;
import com.game.guild.store.GuildStore.Left;
import com.game.guild.store.GuildStoreException;
import com.game.guild.store.TxOutcome;
import com.game.proto.TipInfoMessage;
import com.game.proto.guild.ApplyJoinGuildRequest;
import com.game.proto.guild.CancelGuildApplicationRequest;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.DisbandGuildRequest;
import com.game.proto.guild.GetGuildRequest;
import com.game.proto.guild.GetGuildResponse;
import com.game.proto.guild.GetPlayerGuildRequest;
import com.game.proto.guild.GetPlayerGuildResponse;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.GuildInfo;
import com.game.proto.guild.KickGuildMemberRequest;
import com.game.proto.guild.LeaveGuildRequest;
import com.game.proto.guild.LeaveGuildResponse;
import com.game.proto.guild.ReviewGuildApplicationRequest;
import com.game.proto.guild.SetAnnouncementRequest;
import com.game.proto.guild.SetAnnouncementResponse;
import com.game.proto.guild.SetGuildMemberRoleRequest;
import com.game.proto.guild.TransferGuildLeaderRequest;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 服务层：建 / 查 / 退 / 解散 / 公告的完整前置顺序与结局（guild-spec §3.2–§3.7、§11.2；基线 client_zone_test.go、merge_fence_test.go、
 * guild_manage_logic_test.go 的公共前置用例、guild_logic_names_test.go），以及所有写 RPC 共用的会话 → 归属区 → 闸门顺序。
 */
class GuildServiceTest {

    private static final long ME = 42;
    private static final long G = 10;

    private final GuildServiceFixture f = new GuildServiceFixture();

    @AfterEach
    void close() {
        f.close();
    }

    private void meInZone() {
        f.zones.put(ME, ZONE);
    }

    // ================================================================ 写 RPC 的公共前置（§3.1）

    /** 一个写 RPC：跑一次，返回应答的 error_message。 */
    private record Write(String name, Function<GuildServiceFixture, TipInfoMessage> run) {
    }

    /** 所有过合服闸门的写 RPC（基线 fenceGuardedWrites：8 个管理写 + 退 / 解散 / 公告 / 建帮；审批用拒绝分支）。 */
    private static List<Write> fenceGuardedWrites() {
        return List.of(
                new Write("SetGuildMemberRole", x -> x.manage.setGuildMemberRole(ME,
                        SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(7).setRole(1).build(), d()).getErrorMessage()),
                new Write("KickGuildMember", x -> x.manage.kickGuildMember(ME,
                        KickGuildMemberRequest.newBuilder().setTargetPlayerId(7).build(), d()).getErrorMessage()),
                new Write("TransferGuildLeader", x -> x.manage.transferGuildLeader(ME,
                        TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(7).build(), d()).getErrorMessage()),
                new Write("ApplyJoinGuild", x -> x.manage.applyJoinGuild(ME,
                        ApplyJoinGuildRequest.newBuilder().setGuildId(1).build(), d()).getErrorMessage()),
                new Write("CancelGuildApplication", x -> x.manage.cancelGuildApplication(ME,
                        CancelGuildApplicationRequest.newBuilder().setGuildId(1).build(), d()).getErrorMessage()),
                new Write("ReviewGuildApplication", x -> x.manage.reviewGuildApplication(ME,
                        ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(7).setApprove(false).build(), d())
                        .getErrorMessage()),
                new Write("LeaveGuild", x -> x.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d())
                        .getErrorMessage()),
                new Write("DisbandGuild", x -> x.guilds.disbandGuild(ME, DisbandGuildRequest.getDefaultInstance(), d())
                        .getErrorMessage()),
                new Write("SetAnnouncement", x -> x.guilds.setAnnouncement(ME,
                        SetAnnouncementRequest.newBuilder().setGuildId(1).setAnnouncement("今晚八点集合").build(), d())
                        .getErrorMessage()),
                new Write("CreateGuild", x -> x.guilds.createGuild(ME,
                        CreateGuildRequest.newBuilder().setName("青云门").build(), d()).getErrorMessage()));
    }

    @Test
    void 合服中所有写RPC回14013_查的是请求者的归属区_不碰存储() {
        for (Write w : fenceGuardedWrites()) {
            try (GuildServiceFixture x = new GuildServiceFixture()) {
                x.zones.put(ME, ZONE);
                x.merging = true;
                TipInfoMessage tip = w.run().apply(x);
                assertThat(tip).as(w.name()).isEqualTo(GuildTip.ZONE_MERGING.proto());
                assertThat(x.fenceZones).as(w.name()).containsExactly(ZONE);
                assertThat(x.called("store.")).as(w.name()).isFalse();
            }
        }
    }

    @Test
    void 闸门读不出来_fail_closed回14013() {
        for (Write w : fenceGuardedWrites()) {
            try (GuildServiceFixture x = new GuildServiceFixture()) {
                x.zones.put(ME, ZONE);
                x.fenceFailure = new IllegalStateException("dial tcp: connection refused");
                assertThat(w.run().apply(x)).as(w.name()).isEqualTo(GuildTip.MERGE_FENCE_UNREADABLE.proto());
            }
        }
    }

    @Test
    void 归属区未知回14012_且先于闸门_不发闸门查询() {
        for (Write w : fenceGuardedWrites()) {
            try (GuildServiceFixture x = new GuildServiceFixture()) {
                x.merging = true;
                assertThat(w.run().apply(x)).as(w.name()).isEqualTo(GuildTip.HOME_ZONE_UNKNOWN.proto());
                assertThat(x.fenceZones).as(w.name()).isEmpty();
                assertThat(x.called("store.")).as(w.name()).isFalse();
            }
        }
    }

    @Test
    void 归属区查询故障原样抛_派发器回信封1003() {
        for (Write w : fenceGuardedWrites()) {
            try (GuildServiceFixture x = new GuildServiceFixture()) {
                x.zoneFailure = new DependencyException("读玩家资料失败");
                assertThatThrownBy(() -> w.run().apply(x)).as(w.name()).isInstanceOf(DependencyException.class);
            }
        }
    }

    // ================================================================ CreateGuild（§3.2）

    @Test
    void 建帮_帮名非法回14009_先于任何外部查询() {
        for (String bad : List.of("", "   ", "一二三四五六七八九十一二三四五六七八九十一二三四五", "青\u0001云", "㍿㍿㍿㍿㍿㍿㍿㍿㍿㍿㍿㍿㍿")) {
            try (GuildServiceFixture x = new GuildServiceFixture()) {
                CreateGuildResponse r = x.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName(bad).build(), d());
                assertThat(r.getErrorMessage()).as(bad).isEqualTo(GuildTip.INVALID_GUILD_NAME.proto());
                assertThat(x.zoneCalls).as(bad).isEmpty();
            }
        }
    }

    @Test
    void 建帮成功_身份与区都取服务端_回包是内存构造的帮会_入榜_不推送() {
        meInZone();
        f.names.put(ME, "建帮者");
        f.store.create = g -> TxOutcome.ok(new Created(g.guildId(), ME, g.zoneId()));
        CreateGuildResponse r = f.guilds.createGuild(ME,
                CreateGuildRequest.newBuilder().setPlayerId(777).setName("  青云门 ").setZoneId(99).build(), d());

        assertThat(r.hasErrorMessage()).isFalse();
        GuildInfo info = r.getGuild();
        assertThat(info.getGuildId()).isEqualTo(901);
        assertThat(info.getName()).isEqualTo("青云门");
        assertThat(info.getLeaderId()).isEqualTo(ME);
        assertThat(info.getLeaderName()).isEqualTo("建帮者");
        assertThat(info.getLevel()).isEqualTo(1);
        assertThat(info.getZoneId()).isEqualTo(ZONE);
        assertThat(info.getMaxMembers()).isEqualTo(GuildServiceFixture.MAX_MEMBERS);
        assertThat(info.getCreateTimeMs()).isEqualTo(NOW);
        assertThat(info.getMaxOfficers()).isEqualTo(GuildServiceFixture.MAX_OFFICERS);
        assertThat(info.getUpgradeCostFunds()).isEqualTo(GuildServiceFixture.UPGRADE_COST);
        assertThat(info.getMembersList()).singleElement().satisfies(m -> {
            assertThat(m.getPlayerId()).isEqualTo(ME);
            assertThat(m.getRole()).isEqualTo(3);
            assertThat(m.getJoinTimeMs()).isEqualTo(NOW);
            assertThat(m.getName()).isEqualTo("建帮者");
        });
        assertThat(f.zoneCalls).containsExactly(List.of(ME));
        assertThat(f.fenceZones).containsExactly(ZONE);
        // 提交后：失效 guild(G) 与建帮者映射，然后入榜；不推送
        assertThat(f.indexOf("invalidate " + RedisKeys.guildSnapshot(901))).isGreaterThan(f.indexOf("store.create"));
        assertThat(f.indexOf("invalidate " + RedisKeys.guildOfPlayer(ME))).isGreaterThan(f.indexOf("store.create"));
        assertThat(f.indexOf("rank.add 901")).isGreaterThan(f.indexOf("invalidate " + RedisKeys.guildSnapshot(901)));
        assertThat(f.rankRedis.zsets.get(RedisKeys.guildRankZone(ZONE))).containsKey("901");
        assertThat(f.pushes).isEmpty();
        // 名字一次批量（成员 + 帮主），在线一次
        assertThat(f.nameCalls).hasSize(1);
        assertThat(f.onlineCalls).hasSize(1);
        // 帮主视角：现算待审数
        assertThat(f.called("store.countLive 901")).isTrue();
    }

    @Test
    void 建帮_GuildLevel缺第1级是故障_不发号() {
        meInZone();
        f.initialMaxMembers = OptionalInt.empty();
        assertThatThrownBy(() -> f.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName("青云门").build(), d()))
                .isInstanceOf(GuildFaultException.class).hasMessage("GuildLevel row 1 missing");
        assertThat(f.nextGuildId.get()).isEqualTo(900);
    }

    @Test
    void 建帮_已在帮预检_缓存与MySQL都说在帮回14000_不发号() {
        meInZone();
        f.store.put(guild(G, ZONE, ME));
        CreateGuildResponse r = f.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName("青云门").build(), d());
        assertThat(r.getErrorMessage()).isEqualTo(GuildTip.ALREADY_IN_GUILD.proto());
        assertThat(f.nextGuildId.get()).isEqualTo(900);
        assertThat(f.called("store.create")).isFalse();
    }

    @Test
    void 建帮_缓存里陈旧的帮会映射经MySQL复核为0后放行() {
        meInZone();
        f.store.put(guild(G, ZONE, ME));
        f.cache.guildIdOf(ME, d()); // 映射缓存成 G
        f.store.playerGuild.remove(ME); // MySQL 里他已经不在任何帮
        f.store.create = g -> TxOutcome.ok(new Created(g.guildId(), ME, g.zoneId()));
        CreateGuildResponse r = f.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName("青云门").build(), d());
        assertThat(r.hasErrorMessage()).isFalse();
    }

    @Test
    void 建帮_发号失败或发出0回14008_不碰存储() {
        meInZone();
        f.guildIds = () -> {
            throw new IllegalStateException("guild_id 节点号租约无效");
        };
        CreateGuildResponse r = f.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName("青云门").build(), d());
        assertThat(r.getErrorMessage()).isEqualTo(GuildTip.ID_GENERATOR_UNAVAILABLE.proto());
        assertThat(r.getErrorMessage().getId()).isEqualTo(GuildTips.ID_GEN_UNAVAILABLE);
        f.guildIds = () -> 0;
        r = f.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName("青云门").build(), d());
        assertThat(r.getErrorMessage()).isEqualTo(GuildTip.ID_GENERATOR_UNAVAILABLE.proto());
        assertThat(f.called("store.create")).isFalse();
    }

    @Test
    void 建帮_事务结局映射() {
        meInZone();
        f.store.create = g -> TxOutcome.reject(GuildReject.NAME_TAKEN);
        assertThat(f.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName("青云门").build(), d()).getErrorMessage())
                .isEqualTo(GuildTip.NAME_TAKEN.proto());
        f.store.create = g -> TxOutcome.reject(GuildReject.WRITE_CONFLICT);
        assertThat(f.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName("青云门").build(), d()).getErrorMessage())
                .isEqualTo(GuildTip.WRITE_CONFLICT.proto());
        // 事务说已入帮而缓存说没有：以 MySQL 为准并顺手纠正 0 映射（verifyMapping(p, 0)）
        f.store.create = g -> {
            f.store.playerGuild.put(ME, 77L);
            return TxOutcome.reject(GuildReject.ALREADY_IN_GUILD);
        };
        f.journal.clear();
        assertThat(f.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName("青云门").build(), d()).getErrorMessage())
                .isEqualTo(GuildTip.ALREADY_IN_GUILD.proto());
        assertThat(f.indexOf("invalidate " + RedisKeys.guildOfPlayer(ME))).isGreaterThan(f.indexOf("store.create"));
        assertThat(f.pushes).isEmpty();
        // 存储内部错误按故障（先让他回到「不在任何帮」，否则预检就挡住了）
        f.store.playerGuild.remove(ME);
        f.cache.verifyGuildIdOf(ME, 77, d());
        f.store.create = g -> {
            throw new GuildStoreException(GuildStoreException.Kind.GLOBAL_INSERT_GUARD_MISSING, "哨兵行缺失");
        };
        assertThatThrownBy(() -> f.guilds.createGuild(ME, CreateGuildRequest.newBuilder().setName("青云门").build(), d()))
                .isInstanceOf(GuildStoreException.class);
    }

    // ================================================================ GetGuild（§3.3）

    @Test
    void 查帮_别区的帮与不存在的帮同一答复14001() {
        meInZone();
        f.store.put(guild(G, ZONE + 1, 5));
        assertThat(f.guilds.getGuild(ME, GetGuildRequest.newBuilder().setGuildId(G).build(), d()).getErrorMessage())
                .isEqualTo(GuildTip.GUILD_NOT_FOUND.proto());
        assertThat(f.guilds.getGuild(ME, GetGuildRequest.newBuilder().setGuildId(11).build(), d()).getErrorMessage())
                .isEqualTo(GuildTip.GUILD_NOT_FOUND.proto());
        assertThat(f.guilds.getGuild(ME, GetGuildRequest.newBuilder().setGuildId(0).build(), d()).getErrorMessage())
                .isEqualTo(GuildTip.GUILD_NOT_FOUND.proto());
        assertThat(f.fenceZones).isEmpty();
    }

    @Test
    void 查帮_成员与帮主名一次批量_帮主不在成员表里也取名_在线态_外人看不到待审数() {
        meInZone();
        GuildData g = withRole(guild(G, ZONE, 5, 6, 7), 6, 1);
        // 坏数据：leader_id 指向一个不在成员表里的人
        g = new GuildData(g.guildId(), g.name(), 99, g.level(), "公告", g.createTimeMs(), g.maxMembers(), g.zoneId(), 0, 0,
                g.members());
        f.store.put(g);
        f.names.putAll(java.util.Map.of(5L, "五", 6L, "六", 99L, "帮主"));
        f.online.add(6L);
        f.store.pending.put(G, 4L);
        GetGuildResponse r = f.guilds.getGuild(ME, GetGuildRequest.newBuilder().setGuildId(G).build(), d());
        GuildInfo info = r.getGuild();
        assertThat(info.getLeaderName()).isEqualTo("帮主");
        assertThat(info.getMembersList()).extracting(m -> m.getPlayerId()).containsExactly(5L, 6L, 7L);
        assertThat(info.getMembersList()).extracting(m -> m.getName()).containsExactly("五", "六", "");
        assertThat(info.getMembersList()).extracting(m -> m.getOnline()).containsExactly(false, true, false);
        assertThat(info.getOfficerCount()).isEqualTo(1);
        assertThat(info.getPendingApplicationCount()).isZero();
        assertThat(f.nameCalls).containsExactly(List.of(5L, 6L, 7L, 99L));
        // 先在线、后取名
        assertThat(f.indexOf("online")).isLessThan(f.indexOf("names"));
        assertThat(f.called("store.countLive")).isFalse();
    }

    @Test
    void 查帮_取名失败与在线读失败都不让读失败_GuildLevel缺行留0() {
        meInZone();
        f.store.put(guild(G, ZONE, 5));
        f.namesFail = true;
        f.onlineFails = true;
        f.levelDisplay = null;
        GuildInfo info = f.guilds.getGuild(ME, GetGuildRequest.newBuilder().setGuildId(G).build(), d()).getGuild();
        assertThat(info.getLeaderName()).isEmpty();
        assertThat(info.getMembers(0).getOnline()).isFalse();
        assertThat(info.getMaxOfficers()).isZero();
        assertThat(info.getUpgradeCostFunds()).isZero();
        assertThat(f.meters.get("xm.guild.profile.lookup.failures").counter().count()).isEqualTo(1);
        assertThat(f.meters.get("xm.guild.online.lookups").tag("outcome", "error").counter().count()).isEqualTo(1);
    }

    @Test
    void 查帮_读快照故障原样抛() {
        meInZone();
        f.store.readFailure = new DependencyException("mysql down");
        assertThatThrownBy(() -> f.guilds.getGuild(ME, GetGuildRequest.newBuilder().setGuildId(G).build(), d()))
                .isInstanceOf(DependencyException.class);
    }

    // ================================================================ GetPlayerGuild（§3.4）

    @Test
    void 查本人帮会_不查归属区_未入帮14002_长老看得到待审数_成员看不到() {
        assertThat(f.guilds.getPlayerGuild(ME, GetPlayerGuildRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_IN_ANY_GUILD.proto());
        f.store.put(withRole(guild(G, ZONE + 5, 5, ME, 7), ME, 1));
        f.store.pending.put(G, 3L);
        GetPlayerGuildResponse r = f.guilds.getPlayerGuild(ME, GetPlayerGuildRequest.newBuilder().setPlayerId(5).build(), d());
        assertThat(r.getGuild().getGuildId()).isEqualTo(G);
        assertThat(r.getGuild().getPendingApplicationCount()).isEqualTo(3);
        assertThat(f.zoneCalls).isEmpty();
        GetPlayerGuildResponse member = f.guilds.getPlayerGuild(7, GetPlayerGuildRequest.getDefaultInstance(), d());
        assertThat(member.getGuild().getPendingApplicationCount()).isZero();
    }

    @Test
    void 查本人帮会_待审数读失败留0() {
        f.store.put(guild(G, ZONE, ME));
        f.store.pendingFailure = new DependencyException("mysql down");
        assertThat(f.guilds.getPlayerGuild(ME, GetPlayerGuildRequest.getDefaultInstance(), d()).getGuild()
                .getPendingApplicationCount()).isZero();
    }

    @Test
    void 查本人帮会_双存储矛盾时复核仍在帮_原错误照回() {
        // 映射说在 G，G 的快照里没有他；复核仍说在 G，绕缓存直读快照还是没有他：fail-closed
        f.store.guilds.put(G, guild(G, ZONE, 5));
        f.store.playerGuild.put(ME, G);
        assertThatThrownBy(() -> f.guilds.getPlayerGuild(ME, GetPlayerGuildRequest.getDefaultInstance(), d()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 查本人帮会_解析途中恰好被踢_复核此刻不在帮回14002() {
        f.store.guilds.put(G, guild(G, ZONE, 5));
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        // resolve 里的两次 M5（映射回源、复核）都说 G；之后的复核（leftGuildWhileResolving）他已经不在任何帮
        f.store.playerGuildHook = p -> reads.incrementAndGet() <= 2 ? G : 0;
        assertThat(f.guilds.getPlayerGuild(ME, GetPlayerGuildRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_IN_ANY_GUILD.proto());
        assertThat(reads.get()).isEqualTo(3);
    }

    @Test
    void 查本人帮会_缓存映射陈旧时以MySQL为准() {
        f.store.put(guild(G, ZONE, 5, ME));
        f.cache.guildIdOf(ME, d());           // 映射缓存成 G
        f.store.guilds.put(G, guild(G, ZONE, 5)); // 他已被踢出 G
        f.invalidator.afterCommit(com.game.guild.cache.InvalidationOp.KICK, G, d());
        f.store.put(guild(11, ZONE, 8, ME));  // 又入了 11
        GetPlayerGuildResponse r = f.guilds.getPlayerGuild(ME, GetPlayerGuildRequest.getDefaultInstance(), d());
        assertThat(r.getGuild().getGuildId()).isEqualTo(11);
    }

    // ================================================================ LeaveGuild（§3.5）

    @Test
    void 退帮成功_先失效再推MEMBER_LEFT给剩余全体() {
        meInZone();
        GuildData before = guild(G, ZONE, 5, ME, 7);
        f.store.put(before);
        GuildData after = guild(G, ZONE, 5, 7);
        f.store.leave = g -> TxOutcome.ok(new Left(after, ME));
        LeaveGuildResponse r = f.guilds.leaveGuild(ME, LeaveGuildRequest.newBuilder().setPlayerId(5).build(), d());
        assertThat(r.hasErrorMessage()).isFalse();
        assertThat(f.journal).contains("store.leave g=10 p=42"); // 请求体里伪造的帮主 id 不起作用
        GuildServiceFixture.Push push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_LEFT);
        assertThat(push.change().getGuildId()).isEqualTo(G);
        assertThat(push.change().getActorPlayerId()).isEqualTo(ME);
        assertThat(push.change().getTargetPlayerId()).isEqualTo(ME);
        assertThat(push.recipients()).containsExactly(5L, 7L);
        int push0 = f.indexOf("push");
        assertThat(f.indexOf("invalidate " + RedisKeys.guildSnapshot(G))).isBetween(0, push0);
        assertThat(f.indexOf("invalidate " + RedisKeys.guildOfPlayer(ME))).isBetween(0, push0);
    }

    @Test
    void 退帮_不在任何帮回14002_不开事务() {
        meInZone();
        assertThat(f.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_IN_ANY_GUILD.proto());
        assertThat(f.called("store.leave")).isFalse();
    }

    @Test
    void 退帮_事务说不是成员_复核三种结局() {
        meInZone();
        // 1) 复核为 0：幂等成功，不推送
        f.store.put(guild(G, ZONE, 5, ME));
        f.store.leave = g -> {
            f.store.playerGuild.remove(ME);
            return TxOutcome.reject(GuildReject.NOT_MEMBER);
        };
        assertThat(f.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d()).hasErrorMessage()).isFalse();
        assertThat(f.pushes).isEmpty();
        // 2) 复核为别的帮：14000「guild membership changed, retry」
        f.store.playerGuild.put(ME, G);
        f.cache.verifyGuildIdOf(ME, 0, d());
        f.store.leave = g -> {
            f.store.playerGuild.put(ME, 77L);
            return TxOutcome.reject(GuildReject.GUILD_GONE);
        };
        assertThat(f.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.MEMBERSHIP_CHANGED.proto());
        // 3) 复核本身故障：原样抛
        f.store.leave = g -> {
            f.store.readFailure = new DependencyException("mysql down");
            return TxOutcome.reject(GuildReject.NOT_MEMBER);
        };
        assertThatThrownBy(() -> f.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d()))
                .isInstanceOf(DependencyException.class);
    }

    @Test
    void 退帮_帮主不能退14004_写冲突14021() {
        meInZone();
        f.store.put(guild(G, ZONE, ME));
        f.store.leave = g -> TxOutcome.reject(GuildReject.LEADER_CANT_LEAVE);
        assertThat(f.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.LEADER_CANT_LEAVE.proto());
        f.store.leave = g -> TxOutcome.reject(GuildReject.WRITE_CONFLICT);
        assertThat(f.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.WRITE_CONFLICT.proto());
        assertThat(f.pushes).isEmpty();
    }

    // ================================================================ DisbandGuild（§3.6）

    @Test
    void 解散_职位不够回14005不是14016() {
        meInZone();
        f.store.put(guild(G, ZONE, 5, ME));
        f.store.disband = g -> TxOutcome.reject(GuildReject.RANK_TOO_LOW);
        assertThat(f.guilds.disbandGuild(ME, DisbandGuildRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_GUILD_LEADER.proto());
    }

    @Test
    void 解散成功_失效_按事务内zone清榜_再推DISBANDED给除帮主外全体() {
        meInZone();
        f.store.put(guild(G, ZONE, ME, 5, 7));
        f.rankRedis.put(RedisKeys.guildRankZone(ZONE + 3), G, 0);
        f.rankRedis.sets.computeIfAbsent(RedisKeys.guildRankZones(), k -> new java.util.LinkedHashSet<>())
                .add(Integer.toString(ZONE + 3));
        f.store.disband = g -> TxOutcome.ok(new Disbanded(G, ZONE + 3, ME, List.of(5L, 7L, ME)));
        assertThat(f.guilds.disbandGuild(ME, DisbandGuildRequest.getDefaultInstance(), d()).hasErrorMessage()).isFalse();
        assertThat(f.store.lastFence).isNotNull();
        GuildServiceFixture.Push push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_DISBANDED);
        assertThat(push.change().getActorPlayerId()).isEqualTo(ME);
        assertThat(push.change().getTargetPlayerId()).isZero();
        assertThat(push.recipients()).containsExactly(5L, 7L);
        int inv = f.indexOf("invalidate " + RedisKeys.guildSnapshot(G));
        int rm = f.indexOf("rank.remove " + G);
        int pushAt = f.indexOf("push");
        assertThat(inv).isGreaterThanOrEqualTo(0).isLessThan(rm);
        assertThat(rm).isLessThan(pushAt);
        for (long m : List.of(5L, 7L, ME)) {
            assertThat(f.indexOf("invalidate " + RedisKeys.guildOfPlayer(m))).isBetween(0, pushAt);
        }
        assertThat(f.rankRedis.zsets.get(RedisKeys.guildRankZone(ZONE + 3))).doesNotContainKey(Long.toString(G));
    }

    @Test
    void 解散_帮会已没了回14001并自愈映射_重放同样() {
        meInZone();
        f.store.put(guild(G, ZONE, ME));
        f.store.disband = g -> {
            f.store.playerGuild.remove(ME);
            return TxOutcome.reject(GuildReject.GUILD_GONE);
        };
        f.journal.clear();
        assertThat(f.guilds.disbandGuild(ME, DisbandGuildRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.GUILD_NOT_FOUND.proto());
        assertThat(f.indexOf("invalidate " + RedisKeys.guildOfPlayer(ME))).isGreaterThan(f.indexOf("store.disband"));
    }

    // ================================================================ SetAnnouncement（§3.7）

    @Test
    void 公告_超长在碰身份与存储之前拒绝_600字节合法() {
        String tooLong = "a".repeat(601);
        SetAnnouncementResponse r = f.guilds.setAnnouncement(ME,
                SetAnnouncementRequest.newBuilder().setGuildId(G).setAnnouncement(tooLong).build(), d());
        assertThat(r.getErrorMessage()).isEqualTo(GuildTip.ANNOUNCEMENT_TOO_LONG.proto());
        assertThat(f.zoneCalls).isEmpty();
        // 200 个汉字 = 600 字节：合法（按未 trim 的 UTF-8 字节判）
        meInZone();
        GuildData snapshot = guild(G, ZONE, ME, 5);
        f.store.announce = (g, text) -> TxOutcome.ok(new AnnouncementUpdated(snapshot, ME));
        r = f.guilds.setAnnouncement(ME,
                SetAnnouncementRequest.newBuilder().setGuildId(G).setAnnouncement("公".repeat(200)).build(), d());
        assertThat(r.hasErrorMessage()).isFalse();
        // 多 1 个字节就超
        r = f.guilds.setAnnouncement(ME,
                SetAnnouncementRequest.newBuilder().setGuildId(G).setAnnouncement("公".repeat(200) + " ").build(), d());
        assertThat(r.getErrorMessage()).isEqualTo(GuildTip.ANNOUNCEMENT_TOO_LONG.proto());
    }

    @Test
    void 公告成功_回事务内快照_推ANNOUNCEMENT_CHANGED给快照除操作者_target为0() {
        meInZone();
        GuildData snapshot = new GuildData(G, "帮", 5, 1, "新公告", 1, 30, ZONE, 0, 0,
                withRole(guild(G, ZONE, 5, ME, 7), ME, 1).members());
        f.store.announce = (g, text) -> TxOutcome.ok(new AnnouncementUpdated(snapshot, ME));
        SetAnnouncementResponse r = f.guilds.setAnnouncement(ME,
                SetAnnouncementRequest.newBuilder().setGuildId(G).setAnnouncement("新公告").build(), d());
        assertThat(r.getGuild().getAnnouncement()).isEqualTo("新公告");
        GuildServiceFixture.Push push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_ANNOUNCEMENT_CHANGED);
        assertThat(push.change().getTargetPlayerId()).isZero();
        assertThat(push.recipients()).containsExactly(5L, 7L);
        assertThat(f.indexOf("invalidate " + RedisKeys.guildSnapshot(G))).isBetween(0, f.indexOf("push"));
    }

    @Test
    void 公告_不在该帮或职位不足14006_帮会不存在14001并以请求体guild_id自愈() {
        meInZone();
        f.store.announce = (g, text) -> TxOutcome.reject(GuildReject.ANNOUNCEMENT_FORBIDDEN);
        assertThat(f.guilds.setAnnouncement(ME, SetAnnouncementRequest.newBuilder().setGuildId(G).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.NO_PERMISSION.proto());
        f.store.announce = (g, text) -> TxOutcome.reject(GuildReject.GUILD_GONE);
        f.journal.clear();
        assertThat(f.guilds.setAnnouncement(ME, SetAnnouncementRequest.newBuilder().setGuildId(G).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.GUILD_NOT_FOUND.proto());
        assertThat(f.called("store.playerGuildId " + ME)).isTrue();
        assertThat(f.pushes).isEmpty();
    }

    // ================================================================ mapWriteErr（§2.7）

    @Test
    void 写结局映射整表_双存储矛盾与配表缺行是故障() {
        meInZone();
        f.store.put(guild(G, ZONE, ME));
        for (GuildReject reject : GuildReject.values()) {
            f.store.leave = g -> TxOutcome.reject(reject);
            if (reject == GuildReject.NOT_MEMBER || reject == GuildReject.GUILD_GONE) {
                continue; // 退帮对这两个另有幂等复核，见上
            }
            if (reject == GuildReject.LEADER_MISMATCH || reject == GuildReject.LEVEL_CONFIG_MISSING) {
                assertThatThrownBy(() -> f.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d()))
                        .as(reject.name()).isInstanceOf(GuildFaultException.class)
                        .hasMessage(GuildTips.INCONSISTENT_REASON);
                continue;
            }
            TipInfoMessage tip = f.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d()).getErrorMessage();
            assertThat(tip.getId()).as(reject.name()).isNotZero();
            assertThat(tip.getParametersCount()).as(reject.name()).isEqualTo(1);
        }
    }

    @Test
    void 解散的CommitThenReject与Reject同样映射() {
        meInZone();
        f.store.put(guild(G, ZONE, ME));
        f.store.disband = g -> TxOutcome.commitThenReject(GuildReject.ZONE_MERGING);
        assertThat(f.guilds.disbandGuild(ME, DisbandGuildRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.ZONE_MERGING.proto());
    }
}
