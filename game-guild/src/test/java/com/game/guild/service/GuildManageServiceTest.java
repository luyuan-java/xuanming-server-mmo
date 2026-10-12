package com.game.guild.service;

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
import com.game.guild.store.GuildData;
import com.game.guild.store.GuildStore.ApplicantRow;
import com.game.guild.store.GuildStore.Applied;
import com.game.guild.store.GuildStore.ApplicationRow;
import com.game.guild.store.GuildStore.Kicked;
import com.game.guild.store.GuildStore.Reviewed;
import com.game.guild.store.GuildStore.RoleChanged;
import com.game.guild.store.GuildStore.Transferred;
import com.game.guild.store.TxOutcome;
import com.game.proto.TipInfoMessage;
import com.game.proto.guild.ApplyJoinGuildRequest;
import com.game.proto.guild.CancelGuildApplicationRequest;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.KickGuildMemberRequest;
import com.game.proto.guild.KickGuildMemberResponse;
import com.game.proto.guild.ListGuildApplicationsRequest;
import com.game.proto.guild.ListGuildApplicationsResponse;
import com.game.proto.guild.ListMyGuildApplicationsRequest;
import com.game.proto.guild.ListMyGuildApplicationsResponse;
import com.game.proto.guild.ReviewGuildApplicationRequest;
import com.game.proto.guild.ReviewGuildApplicationResponse;
import com.game.proto.guild.SetGuildMemberRoleRequest;
import com.game.proto.guild.SetGuildMemberRoleResponse;
import com.game.proto.guild.TransferGuildLeaderRequest;
import com.game.proto.guild.TransferGuildLeaderResponse;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 服务层：任免 / 踢人 / 转让 / 申请 / 撤回 / 本人申请列表 / 待审名单 / 审批的完整顺序与结局（guild-spec §3.8–§3.15、§11.2；基线
 * guild_manage_logic_test.go 的 TestTargetValidationBeforeRepo、TestReviewApproveChecksApplicantZoneBeforeRepo、guild_logic_names_test.go）。
 */
class GuildManageServiceTest {

    private static final long ME = 42;
    private static final long T = 7;
    private static final long G = 10;

    private final GuildServiceFixture f = new GuildServiceFixture();

    @BeforeEach
    void inZone() {
        f.zones.put(ME, ZONE);
    }

    @AfterEach
    void close() {
        f.close();
    }

    // ================================================================ 目标校验在碰库之前（guild_manage_logic_test.go:251）

    private record Case(String name, Function<GuildServiceFixture, TipInfoMessage> run, GuildTip want) {
    }

    @Test
    void 参数级拒绝全部发生在碰存储之前() {
        List<Case> cases = List.of(
                new Case("任免目标为 0", x -> x.manage.setGuildMemberRole(ME,
                        SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(0).setRole(1).build(), d()).getErrorMessage(),
                        GuildTip.TARGET_ID_ZERO),
                new Case("任免目标是自己", x -> x.manage.setGuildMemberRole(ME,
                        SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(ME).setRole(1).build(), d()).getErrorMessage(),
                        GuildTip.CANNOT_TARGET_SELF),
                new Case("任免成帮主", x -> x.manage.setGuildMemberRole(ME,
                        SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(T).setRole(3).build(), d()).getErrorMessage(),
                        GuildTip.ROLE_NOT_ASSIGNABLE),
                new Case("任免成空号职位", x -> x.manage.setGuildMemberRole(ME,
                        SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(T).setRole(2).build(), d()).getErrorMessage(),
                        GuildTip.ROLE_NOT_ASSIGNABLE),
                new Case("踢人目标为 0", x -> x.manage.kickGuildMember(ME,
                        KickGuildMemberRequest.newBuilder().setTargetPlayerId(0).build(), d()).getErrorMessage(),
                        GuildTip.TARGET_ID_ZERO),
                new Case("踢自己", x -> x.manage.kickGuildMember(ME,
                        KickGuildMemberRequest.newBuilder().setTargetPlayerId(ME).build(), d()).getErrorMessage(),
                        GuildTip.CANNOT_TARGET_SELF),
                new Case("转让目标为 0", x -> x.manage.transferGuildLeader(ME,
                        TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(0).build(), d()).getErrorMessage(),
                        GuildTip.TARGET_ID_ZERO),
                new Case("转让给自己", x -> x.manage.transferGuildLeader(ME,
                        TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(ME).build(), d()).getErrorMessage(),
                        GuildTip.CANNOT_TARGET_SELF),
                new Case("审批的申请人为 0", x -> x.manage.reviewGuildApplication(ME,
                        ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(0).setApprove(true).build(), d())
                        .getErrorMessage(), GuildTip.APPLICANT_ID_ZERO),
                new Case("审批自己的申请", x -> x.manage.reviewGuildApplication(ME,
                        ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(ME).setApprove(true).build(), d())
                        .getErrorMessage(), GuildTip.CANNOT_REVIEW_OWN),
                new Case("申请的帮会 id 为 0", x -> x.manage.applyJoinGuild(ME,
                        ApplyJoinGuildRequest.newBuilder().setGuildId(0).build(), d()).getErrorMessage(),
                        GuildTip.APPLY_GUILD_ID_ZERO),
                new Case("撤回的帮会 id 为 0", x -> x.manage.cancelGuildApplication(ME,
                        CancelGuildApplicationRequest.newBuilder().setGuildId(0).build(), d()).getErrorMessage(),
                        GuildTip.CANCEL_GUILD_ID_ZERO));
        for (Case c : cases) {
            try (GuildServiceFixture x = new GuildServiceFixture()) {
                x.zones.put(ME, ZONE);
                assertThat(c.run().apply(x)).as(c.name()).isEqualTo(c.want().proto());
                assertThat(x.called("store.")).as(c.name()).isFalse();
            }
        }
    }

    @Test
    void 审批的两个参数检查先于归属区查询() {
        f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(0).setApprove(true).build(), d());
        f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(ME).setApprove(true).build(), d());
        assertThat(f.zoneCalls).isEmpty();
    }

    // ================================================================ SetGuildMemberRole（§3.8）

    @Test
    void 任免成功_推ROLE_CHANGED给快照除帮主_回包含操作者() {
        GuildData before = guild(G, ZONE, ME, T, 8);
        f.store.put(before);
        GuildData after = withRole(before, T, 1);
        f.store.setRole = (target, role) -> TxOutcome.ok(new RoleChanged(after, true, ME, target));
        SetGuildMemberRoleResponse r = f.manage.setGuildMemberRole(ME,
                SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(T).setRole(1).build(), d());
        assertThat(r.getGuild().getOfficerCount()).isEqualTo(1);
        assertThat(r.getGuild().getMembersList()).extracting(m -> m.getPlayerId()).contains(ME);
        GuildServiceFixture.Push push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_ROLE_CHANGED);
        assertThat(push.change().getActorPlayerId()).isEqualTo(ME);
        assertThat(push.change().getTargetPlayerId()).isEqualTo(T);
        assertThat(push.recipients()).containsExactly(T, 8L);
        assertThat(f.indexOf("invalidate " + RedisKeys.guildSnapshot(G))).isBetween(0, f.indexOf("push"));
        // 长老上限按帮会等级现查配表
        assertThat(f.store.lastCaps.maxOfficers(1)).hasValue(GuildServiceFixture.MAX_OFFICERS);
    }

    @Test
    void 幂等任命_不失效不推送_仍回快照() {
        GuildData g = withRole(guild(G, ZONE, ME, T), T, 1);
        f.store.put(g);
        f.store.setRole = (target, role) -> TxOutcome.ok(new RoleChanged(g, false, ME, target));
        f.journal.clear();
        SetGuildMemberRoleResponse r = f.manage.setGuildMemberRole(ME,
                SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(T).setRole(1).build(), d());
        assertThat(r.hasGuild()).isTrue();
        assertThat(f.pushes).isEmpty();
        assertThat(f.called("invalidate " + RedisKeys.guildSnapshot(G))).isFalse();
    }

    @Test
    void 任免的事务拒绝() {
        f.store.put(guild(G, ZONE, ME, T));
        f.store.setRole = (target, role) -> TxOutcome.reject(GuildReject.OFFICER_LIMIT);
        assertThat(f.manage.setGuildMemberRole(ME,
                SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(T).setRole(1).build(), d()).getErrorMessage())
                .isEqualTo(GuildTip.OFFICER_LIMIT.proto());
        f.store.setRole = (target, role) -> TxOutcome.reject(GuildReject.LEVEL_CONFIG_MISSING);
        assertThatThrownBy(() -> f.manage.setGuildMemberRole(ME,
                SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(T).setRole(1).build(), d()))
                .isInstanceOf(GuildFaultException.class);
        assertThat(f.pushes).isEmpty();
    }

    // ================================================================ KickGuildMember（§3.9）

    @Test
    void 踢人成功_推MEMBER_KICKED给快照除操作者再加被踢者() {
        GuildData before = withRole(guild(G, ZONE, 5, ME, T, 8), ME, 1);
        f.store.put(before);
        GuildData after = withRole(guild(G, ZONE, 5, ME, 8), ME, 1);
        f.store.kick = target -> TxOutcome.ok(new Kicked(after, ME, target));
        KickGuildMemberResponse r = f.manage.kickGuildMember(ME,
                KickGuildMemberRequest.newBuilder().setTargetPlayerId(T).build(), d());
        assertThat(r.getGuild().getMembersCount()).isEqualTo(3);
        GuildServiceFixture.Push push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_KICKED);
        assertThat(push.change().getActorPlayerId()).isEqualTo(ME);
        assertThat(push.change().getTargetPlayerId()).isEqualTo(T);
        assertThat(push.recipients()).containsExactly(5L, 8L, T);
        int pushAt = f.indexOf("push");
        assertThat(f.indexOf("invalidate " + RedisKeys.guildSnapshot(G))).isBetween(0, pushAt);
        assertThat(f.indexOf("invalidate " + RedisKeys.guildOfPlayer(T))).isBetween(0, pushAt);
    }

    @Test
    void 重踢已离开的人14014_操作者已不在帮14002并自愈映射() {
        f.store.put(guild(G, ZONE, 5, ME));
        f.store.kick = target -> TxOutcome.reject(GuildReject.TARGET_NOT_MEMBER);
        assertThat(f.manage.kickGuildMember(ME, KickGuildMemberRequest.newBuilder().setTargetPlayerId(T).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.TARGET_NOT_MEMBER.proto());
        f.store.kick = target -> {
            f.store.playerGuild.remove(ME);
            return TxOutcome.reject(GuildReject.NOT_MEMBER);
        };
        f.journal.clear();
        assertThat(f.manage.kickGuildMember(ME, KickGuildMemberRequest.newBuilder().setTargetPlayerId(T).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.NOT_A_MEMBER.proto());
        assertThat(f.indexOf("invalidate " + RedisKeys.guildOfPlayer(ME))).isGreaterThan(f.indexOf("store.kick"));
        assertThat(f.pushes).isEmpty();
    }

    // ================================================================ TransferGuildLeader（§3.10）

    @Test
    void 转让成功_推LEADER_TRANSFERRED给快照除原帮主_原帮主是长老看得到待审数() {
        f.store.put(guild(G, ZONE, ME, T, 8));
        GuildData after = new GuildData(G, "帮", T, 1, "", 1, 30, ZONE, 0, 0,
                withRole(withRole(guild(G, ZONE, ME, T, 8), T, 3), ME, 1).members());
        f.store.pending.put(G, 2L);
        f.store.transfer = target -> TxOutcome.ok(new Transferred(after, ME, target));
        TransferGuildLeaderResponse r = f.manage.transferGuildLeader(ME,
                TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(T).build(), d());
        assertThat(r.getGuild().getLeaderId()).isEqualTo(T);
        assertThat(r.getGuild().getPendingApplicationCount()).isEqualTo(2);
        GuildServiceFixture.Push push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_LEADER_TRANSFERRED);
        assertThat(push.change().getActorPlayerId()).isEqualTo(ME);
        assertThat(push.change().getTargetPlayerId()).isEqualTo(T);
        assertThat(push.recipients()).containsExactly(T, 8L);
    }

    @Test
    void 转让重放14016_leader不一致是故障() {
        f.store.put(guild(G, ZONE, ME, T));
        f.store.transfer = target -> TxOutcome.reject(GuildReject.RANK_TOO_LOW);
        assertThat(f.manage.transferGuildLeader(ME, TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(T).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.RANK_TOO_LOW.proto());
        f.store.transfer = target -> TxOutcome.reject(GuildReject.LEADER_MISMATCH);
        assertThatThrownBy(() -> f.manage.transferGuildLeader(ME,
                TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(T).build(), d()))
                .isInstanceOf(GuildFaultException.class);
    }

    // ================================================================ ApplyJoinGuild（§3.11）

    @Test
    void 申请新建行_冷却拿到才推APPLICATION_RECEIVED给审批人_申请人是actor与target() {
        f.store.apply = g -> TxOutcome.ok(new Applied(true, List.of(5L, 6L)));
        assertThat(f.manage.applyJoinGuild(ME, ApplyJoinGuildRequest.newBuilder().setGuildId(G).build(), d())
                .hasErrorMessage()).isFalse();
        assertThat(f.store.lastApplyZone).isEqualTo(ZONE);
        assertThat(f.store.lastRules).isEqualTo(GuildServiceFixture.RULES);
        GuildServiceFixture.Push push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_APPLICATION_RECEIVED);
        assertThat(push.change().getActorPlayerId()).isEqualTo(ME);
        assertThat(push.change().getTargetPlayerId()).isEqualTo(ME);
        assertThat(push.recipients()).containsExactly(5L, 6L);
        assertThat(f.cooldownCalls).containsExactly(G + ":" + ME);
        // 申请不失效任何缓存
        assertThat(f.called("invalidate")).isFalse();
    }

    @Test
    void 申请_冷却中或同帮刷新都不推() {
        f.cooldownAllows = false;
        f.store.apply = g -> TxOutcome.ok(new Applied(true, List.of(5L)));
        f.manage.applyJoinGuild(ME, ApplyJoinGuildRequest.newBuilder().setGuildId(G).build(), d());
        assertThat(f.pushes).isEmpty();
        f.cooldownAllows = true;
        f.cooldownCalls.clear();
        f.store.apply = g -> TxOutcome.ok(new Applied(false, List.of()));
        f.manage.applyJoinGuild(ME, ApplyJoinGuildRequest.newBuilder().setGuildId(G).build(), d());
        assertThat(f.pushes).isEmpty();
        assertThat(f.cooldownCalls).isEmpty(); // 刷新不占冷却键
    }

    @Test
    void 申请_已在帮预检14000_配表缺行是故障_事务拒绝按申请人不在目标帮自愈() {
        f.store.put(guild(11, ZONE, 5, ME));
        assertThat(f.manage.applyJoinGuild(ME, ApplyJoinGuildRequest.newBuilder().setGuildId(G).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.ALREADY_IN_GUILD.proto());
        assertThat(f.called("store.apply")).isFalse();

        f.store.playerGuild.clear();
        f.cache.verifyGuildIdOf(ME, 11, d());
        f.rules = null;
        assertThatThrownBy(() -> f.manage.applyJoinGuild(ME, ApplyJoinGuildRequest.newBuilder().setGuildId(G).build(), d()))
                .isInstanceOf(GuildFaultException.class).hasMessage("GuildRule row 1 missing");

        f.rules = GuildServiceFixture.RULES;
        for (GuildReject reject : List.of(GuildReject.GUILD_GONE, GuildReject.ZONE_MISMATCH, GuildReject.GUILD_FULL,
                GuildReject.APPLICATION_LIMIT, GuildReject.QUEUE_FULL, GuildReject.WRITE_CONFLICT)) {
            f.store.apply = g -> TxOutcome.reject(reject);
            TipInfoMessage tip = f.manage.applyJoinGuild(ME, ApplyJoinGuildRequest.newBuilder().setGuildId(G).build(), d())
                    .getErrorMessage();
            assertThat(tip).as(reject.name()).isEqualTo(switch (reject) {
                case GUILD_GONE, ZONE_MISMATCH -> GuildTip.GUILD_NOT_FOUND.proto();
                case GUILD_FULL -> GuildTip.GUILD_FULL.proto();
                case APPLICATION_LIMIT -> GuildTip.APPLICATION_LIMIT.proto();
                case QUEUE_FULL -> GuildTip.APPLICATION_QUEUE_FULL.proto();
                default -> GuildTip.WRITE_CONFLICT.proto();
            });
        }
        assertThat(f.pushes).isEmpty();
    }

    // ================================================================ CancelGuildApplication（§3.12）

    @Test
    void 撤回成功不推送_重复撤回或过期都回14018() {
        f.store.cancel = g -> TxOutcome.ok(null);
        assertThat(f.manage.cancelGuildApplication(ME, CancelGuildApplicationRequest.newBuilder().setGuildId(G).build(), d())
                .hasErrorMessage()).isFalse();
        f.store.cancel = g -> TxOutcome.reject(GuildReject.APPLICATION_NOT_FOUND);
        assertThat(f.manage.cancelGuildApplication(ME, CancelGuildApplicationRequest.newBuilder().setGuildId(G).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.APPLICATION_NOT_FOUND.proto());
        f.store.cancel = g -> TxOutcome.commitThenReject(GuildReject.APPLICATION_NOT_FOUND);
        assertThat(f.manage.cancelGuildApplication(ME, CancelGuildApplicationRequest.newBuilder().setGuildId(G).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.APPLICATION_NOT_FOUND.proto());
        assertThat(f.pushes).isEmpty();
        assertThat(f.called("invalidate")).isFalse();
    }

    // ================================================================ ListMyGuildApplications（§3.13）

    @Test
    void 本人申请列表_查归属区不查闸门_已入帮回空表() {
        f.merging = true;
        f.store.put(guild(11, ZONE, 5, ME));
        ListMyGuildApplicationsResponse r = f.manage.listMyGuildApplications(ME,
                ListMyGuildApplicationsRequest.getDefaultInstance(), d());
        assertThat(r.hasErrorMessage()).isFalse();
        assertThat(r.getApplicationsList()).isEmpty();
        assertThat(f.fenceZones).isEmpty();
        assertThat(f.called("store.listMy")).isFalse();
    }

    @Test
    void 本人申请列表_跳过已解散与别区的帮_帮主名一次批量_上限10() {
        f.store.put(guild(11, ZONE, 5, 6));
        f.store.put(guild(12, ZONE + 1, 7));
        f.store.put(guild(14, ZONE, 8));
        f.names.put(5L, "五");
        f.names.put(8L, "八");
        f.store.myApplications = List.of(new ApplicationRow(11, 300, 900), new ApplicationRow(12, 200, 800),
                new ApplicationRow(13, 150, 750), new ApplicationRow(14, 100, 700));
        ListMyGuildApplicationsResponse r = f.manage.listMyGuildApplications(ME,
                ListMyGuildApplicationsRequest.getDefaultInstance(), d());
        assertThat(r.getApplicationsList()).extracting(v -> v.getGuildId()).containsExactly(11L, 14L);
        assertThat(r.getApplications(0).getLeaderName()).isEqualTo("五");
        assertThat(r.getApplications(0).getMemberCount()).isEqualTo(2);
        assertThat(r.getApplications(0).getMaxMembers()).isEqualTo(30);
        assertThat(r.getApplications(0).getApplyMs()).isEqualTo(300);
        assertThat(r.getApplications(0).getExpireMs()).isEqualTo(900);
        assertThat(f.nameCalls).containsExactly(List.of(5L, 8L));
        assertThat(f.store.lastListLimit).isEqualTo(10);
    }

    @Test
    void 本人申请列表_任一行读帮会出错就整体故障() {
        f.store.myApplications = List.of(new ApplicationRow(11, 300, 900));
        f.cacheRedis.failReads = true;
        assertThatThrownBy(() -> f.manage.listMyGuildApplications(ME, ListMyGuildApplicationsRequest.getDefaultInstance(), d()))
                .isInstanceOf(DependencyException.class);
    }

    // ================================================================ ListGuildApplications（§3.14）

    @Test
    void 待审名单_不查归属区与闸门_成员14016_不是成员14002并自愈() {
        f.zones.clear();
        f.merging = true;
        f.store.put(guild(G, ZONE, 5, ME));
        assertThat(f.manage.listGuildApplications(ME, ListGuildApplicationsRequest.getDefaultInstance(), d())
                .getErrorMessage()).isEqualTo(GuildTip.OFFICER_RANK_REQUIRED.proto());
        assertThat(f.zoneCalls).isEmpty();
        assertThat(f.fenceZones).isEmpty();
        // 缓存说在 G，MySQL 的成员行已经没有他
        f.store.guilds.put(G, guild(G, ZONE, 5));
        f.store.playerGuild.remove(ME);
        f.journal.clear();
        assertThat(f.manage.listGuildApplications(ME, ListGuildApplicationsRequest.getDefaultInstance(), d())
                .getErrorMessage()).isEqualTo(GuildTip.NOT_A_MEMBER.proto());
        assertThat(f.called("invalidate " + RedisKeys.guildOfPlayer(ME))).isTrue();
    }

    @Test
    void 待审名单_长老可见_在线与名字各一次批量_条数上限取配表() {
        f.store.put(withRole(guild(G, ZONE, 5, ME), ME, 1));
        f.store.applicants = List.of(new ApplicantRow(20, 100, 900), new ApplicantRow(21, 110, 910));
        f.names.put(20L, "二十");
        f.online.add(21L);
        ListGuildApplicationsResponse r = f.manage.listGuildApplications(ME, ListGuildApplicationsRequest.getDefaultInstance(),
                d());
        assertThat(r.getApplicantsList()).extracting(v -> v.getPlayerId()).containsExactly(20L, 21L);
        assertThat(r.getApplicants(0).getName()).isEqualTo("二十");
        assertThat(r.getApplicants(1).getOnline()).isTrue();
        assertThat(r.getApplicants(0).getApplyMs()).isEqualTo(100);
        assertThat(f.nameCalls).containsExactly(List.of(20L, 21L));
        assertThat(f.onlineCalls).containsExactly(List.of(20L, 21L));
        assertThat(f.store.lastListLimit).isEqualTo(GuildServiceFixture.RULES.maxPerGuild());
        f.rules = null;
        assertThatThrownBy(() -> f.manage.listGuildApplications(ME, ListGuildApplicationsRequest.getDefaultInstance(), d()))
                .isInstanceOf(GuildFaultException.class);
    }

    // ================================================================ ReviewGuildApplication（§3.15、§7.10）

    @Test
    void 审批通过_审批人与申请人一条IN查询_申请人没有归属区回14018_不碰事务() {
        f.store.put(withRole(guild(G, ZONE, 5, ME), ME, 1));
        ReviewGuildApplicationResponse r = f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(T).setApprove(true).build(), d());
        assertThat(r.getErrorMessage()).isEqualTo(GuildTip.APPLICANT_HOME_ZONE_UNKNOWN.proto());
        assertThat(f.zoneCalls).containsExactly(List.of(ME, T));
        assertThat(f.called("store.review")).isFalse();
    }

    @Test
    void 审批通过_归属区查询故障是故障不是放行() {
        f.zoneFailure = new DependencyException("mysql down");
        assertThatThrownBy(() -> f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(T).setApprove(true).build(), d()))
                .isInstanceOf(DependencyException.class);
    }

    @Test
    void 审批人归属区未知先于闸门先于申请人归属区() {
        f.zones.clear();
        f.zones.put(T, ZONE);
        f.merging = true;
        assertThat(f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(T).setApprove(true).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.HOME_ZONE_UNKNOWN.proto());
        assertThat(f.fenceZones).isEmpty();
        // 审批人有区、合服中：14013 先于申请人缺区的 14018
        f.zones.put(ME, ZONE);
        f.zones.remove(T);
        assertThat(f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(T).setApprove(true).build(), d())
                .getErrorMessage()).isEqualTo(GuildTip.ZONE_MERGING.proto());
    }

    @Test
    void 审批拒绝_不查申请人归属区_仍查审批人的区与闸门_只推申请人() {
        GuildData g = withRole(guild(G, ZONE, 5, ME), ME, 1);
        f.store.put(g);
        f.store.review = (applicant, approve) -> TxOutcome.ok(new Reviewed(g, false, ME, applicant));
        ReviewGuildApplicationResponse r = f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(T).setApprove(false).build(), d());
        assertThat(r.getGuild().getGuildId()).isEqualTo(G);
        assertThat(f.zoneCalls).containsExactly(List.of(ME));
        assertThat(f.fenceZones).containsExactly(ZONE);
        assertThat(f.store.lastReviewZone).isZero();
        GuildServiceFixture.Push push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_APPLICATION_REJECTED);
        assertThat(push.change().getActorPlayerId()).isEqualTo(ME);
        assertThat(push.change().getTargetPlayerId()).isEqualTo(T);
        assertThat(push.recipients()).containsExactly(T);
        assertThat(f.called("invalidate")).isFalse(); // 拒绝只动了申请行
    }

    @Test
    void 审批通过_传申请人归属区_失效后推MEMBER_JOINED给快照除审批人() {
        f.zones.put(T, ZONE);
        f.store.put(withRole(guild(G, ZONE, 5, ME), ME, 1));
        GuildData after = withRole(guild(G, ZONE, 5, ME, T), ME, 1);
        f.store.review = (applicant, approve) -> TxOutcome.ok(new Reviewed(after, true, ME, applicant));
        ReviewGuildApplicationResponse r = f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(T).setApprove(true).build(), d());
        assertThat(r.getGuild().getMembersList()).extracting(m -> m.getPlayerId()).contains(T, ME);
        assertThat(f.store.lastReviewZone).isEqualTo(ZONE);
        GuildServiceFixture.Push push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_JOINED);
        assertThat(push.change().getTargetPlayerId()).isEqualTo(T);
        assertThat(push.recipients()).containsExactly(5L, T);
        int pushAt = f.indexOf("push");
        assertThat(f.indexOf("invalidate " + RedisKeys.guildSnapshot(G))).isBetween(0, pushAt);
        assertThat(f.indexOf("invalidate " + RedisKeys.guildOfPlayer(T))).isBetween(0, pushAt);
    }

    @Test
    void 审批的事务拒绝_已失效一律14018_帮满14003_成员14016() {
        f.zones.put(T, ZONE);
        f.store.put(withRole(guild(G, ZONE, 5, ME), ME, 1));
        f.store.review = (applicant, approve) -> TxOutcome.commitThenReject(GuildReject.APPLICATION_NOT_FOUND);
        assertThat(review(true)).isEqualTo(GuildTip.APPLICATION_NOT_FOUND.proto());
        f.store.review = (applicant, approve) -> TxOutcome.reject(GuildReject.GUILD_FULL);
        assertThat(review(true)).isEqualTo(GuildTip.GUILD_FULL.proto());
        f.store.review = (applicant, approve) -> TxOutcome.reject(GuildReject.RANK_TOO_LOW);
        assertThat(review(false)).isEqualTo(GuildTip.RANK_TOO_LOW.proto());
        assertThat(f.pushes).isEmpty();
    }

    @Test
    void 推送失败绝不影响回包_按收件人计session_error() {
        f.zones.put(T, ZONE);
        f.store.put(withRole(guild(G, ZONE, 5, ME), ME, 1));
        GuildData after = withRole(guild(G, ZONE, 5, ME, T), ME, 1);
        f.store.review = (applicant, approve) -> TxOutcome.ok(new Reviewed(after, true, ME, applicant));
        f.pushThrows = true;
        ReviewGuildApplicationResponse r = f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(T).setApprove(true).build(), d());
        assertThat(r.hasErrorMessage()).isFalse();
        assertThat(r.getGuild().getMembersCount()).isEqualTo(3);
        f.pushThrows = false;
        f.pushFails = true;
        r = f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(T).setApprove(true).build(), d());
        assertThat(r.hasErrorMessage()).isFalse();
        assertThat(f.pushCount(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_JOINED, "session_error")).isEqualTo(4);
    }

    private TipInfoMessage review(boolean approve) {
        return f.manage.reviewGuildApplication(ME,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(T).setApprove(approve).build(), d())
                .getErrorMessage();
    }
}
