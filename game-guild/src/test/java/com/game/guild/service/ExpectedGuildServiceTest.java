package com.game.guild.service;

import static com.game.guild.service.GuildServiceFixture.ZONE;
import static com.game.guild.service.GuildServiceFixture.d;
import static com.game.guild.service.ScriptedStore.guild;
import static com.game.guild.service.ScriptedStore.withRole;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTip;
import com.game.guild.store.GuildData;
import com.game.guild.store.GuildStore.Disbanded;
import com.game.guild.store.GuildStore.Kicked;
import com.game.guild.store.GuildStore.Left;
import com.game.guild.store.GuildStore.RoleChanged;
import com.game.guild.store.GuildStore.Transferred;
import com.game.guild.store.TxOutcome;
import com.game.proto.TipInfoMessage;
import com.game.proto.guild.DisbandGuildRequest;
import com.game.proto.guild.GuildInfo;
import com.game.proto.guild.KickGuildMemberRequest;
import com.game.proto.guild.KickGuildMemberResponse;
import com.game.proto.guild.LeaveGuildRequest;
import com.game.proto.guild.SetGuildMemberRoleRequest;
import com.game.proto.guild.SetGuildMemberRoleResponse;
import com.game.proto.guild.TransferGuildLeaderRequest;
import com.game.proto.guild.TransferGuildLeaderResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 写请求的 expected_guild_id 栅栏（mmorpg docs/design/client-request-ordering.md O14 / §6.4；基线 go/guild 的 expected_guild_test.go 与
 * expected_guild_integration_test.go）。五个写方法（退帮 / 解散 / 踢人 / 转让 / 任免）一张表跑同一组契约：
 * <ul>
 *   <li>0 = 旧客户端：按此刻所在帮执行，行为不变，计 absent；</li>
 *   <li>与当前帮一致：照常成功，不计数；</li>
 *   <li>不一致（在别的帮 / 不在任何帮）：14002、不碰存储写、不推送，计 mismatch；回包有 guild 字段的附操作者当前帮会快照；</li>
 *   <li>复核与写入之间换帮：存储替身在「锁住 guild 行」之前先把操作者换到别的帮，再按锁内顺序复核——服务把事务绑在预期帮上，
 *       存储只能答「不是成员」，服务据此拒绝，绝不作用到新帮。</li>
 * </ul>
 * 存储替身的「锁内复核」照 JdbcGuildStore 的判定顺序写（帮不在 → GUILD_GONE；锁不到本人成员行 → NOT_MEMBER；解散不是帮主时再区分成员 /
 * 非成员）；锁本身的原子性由 JdbcGuildStoreMysqlTest 在真库上钉住。
 */
class ExpectedGuildServiceTest {

    private static final long ME = 42;
    private static final long T = 7;
    private static final long Q = 8;
    private static final long G = 10;
    private static final long H = 11;
    private static final long H_LEADER = 20;
    private static final long H_MEMBER = 21;

    /** 一次调用的回包：tip（没有为默认实例）+ 回包里的 guild（没有该字段或没填为 null）。 */
    private record Reply(TipInfoMessage tip, GuildInfo guild) {
    }

    /** 一个写方法在本表里的全部差异。{@code arm} 给存储装脚本：{@code locked} 是「锁内复核」，返回 null = 通过并照常写。 */
    private record Case(ExpectedGuildMetrics.Method method, String write, int myRole, boolean hasView,
                        BiFunction<GuildServiceFixture, Long, Reply> call, Arm arm) {
    }

    @FunctionalInterface
    private interface Arm {
        void arm(GuildServiceFixture f, Supplier<GuildReject> locked);
    }

    private static List<Case> cases() {
        return List.of(
                new Case(ExpectedGuildMetrics.Method.LEAVE, "store.leave", GuildRoles.MEMBER, false,
                        (f, expected) -> {
                            var r = f.guilds.leaveGuild(ME, LeaveGuildRequest.newBuilder().setExpectedGuildId(expected).build(), d());
                            return new Reply(r.getErrorMessage(), null);
                        },
                        (f, locked) -> f.store.leave = g -> {
                            GuildReject reject = locked.get();
                            return reject != null ? TxOutcome.reject(reject) : TxOutcome.ok(new Left(without(f, G, ME), ME));
                        }),
                new Case(ExpectedGuildMetrics.Method.DISBAND, "store.disband", GuildRoles.LEADER, false,
                        (f, expected) -> {
                            var r = f.guilds.disbandGuild(ME, DisbandGuildRequest.newBuilder().setExpectedGuildId(expected).build(),
                                    d());
                            return new Reply(r.getErrorMessage(), null);
                        },
                        (f, locked) -> f.store.disband = g -> {
                            GuildReject reject = locked.get();
                            return reject != null ? TxOutcome.reject(reject)
                                    : TxOutcome.ok(new Disbanded(G, ZONE, ME, f.store.guilds.get(G).memberIds()));
                        }),
                new Case(ExpectedGuildMetrics.Method.KICK, "store.kick", GuildRoles.OFFICER, true,
                        (f, expected) -> {
                            KickGuildMemberResponse r = f.manage.kickGuildMember(ME,
                                    KickGuildMemberRequest.newBuilder().setTargetPlayerId(T).setExpectedGuildId(expected).build(), d());
                            return new Reply(r.getErrorMessage(), r.hasGuild() ? r.getGuild() : null);
                        },
                        (f, locked) -> f.store.kick = target -> {
                            GuildReject reject = locked.get();
                            return reject != null ? TxOutcome.reject(reject) : TxOutcome.ok(new Kicked(without(f, G, T), ME, T));
                        }),
                new Case(ExpectedGuildMetrics.Method.TRANSFER, "store.transfer", GuildRoles.LEADER, true,
                        (f, expected) -> {
                            TransferGuildLeaderResponse r = f.manage.transferGuildLeader(ME,
                                    TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(T).setExpectedGuildId(expected).build(),
                                    d());
                            return new Reply(r.getErrorMessage(), r.hasGuild() ? r.getGuild() : null);
                        },
                        (f, locked) -> f.store.transfer = target -> {
                            GuildReject reject = locked.get();
                            return reject != null ? TxOutcome.reject(reject)
                                    : TxOutcome.ok(new Transferred(withRole(withRole(f.store.guilds.get(G), T, GuildRoles.LEADER),
                                    ME, GuildRoles.OFFICER), ME, T));
                        }),
                new Case(ExpectedGuildMetrics.Method.SET_ROLE, "store.setRole", GuildRoles.LEADER, true,
                        (f, expected) -> {
                            SetGuildMemberRoleResponse r = f.manage.setGuildMemberRole(ME, SetGuildMemberRoleRequest.newBuilder()
                                    .setTargetPlayerId(T).setRole(GuildRoles.OFFICER).setExpectedGuildId(expected).build(), d());
                            return new Reply(r.getErrorMessage(), r.hasGuild() ? r.getGuild() : null);
                        },
                        (f, locked) -> f.store.setRole = (target, role) -> {
                            GuildReject reject = locked.get();
                            return reject != null ? TxOutcome.reject(reject)
                                    : TxOutcome.ok(new RoleChanged(withRole(f.store.guilds.get(G), T, role), true, ME, T));
                        }));
    }

    // ================================================================ 夹具

    /** G：我按 myRole 入帮（我是帮主时 T、Q 是成员，否则 Q 是帮主、T 是成员）；H：H_LEADER + H_MEMBER。 */
    private static GuildServiceFixture world(int myRole) {
        GuildServiceFixture f = new GuildServiceFixture();
        f.zones.put(ME, ZONE);
        GuildData g = myRole == GuildRoles.LEADER ? guild(G, ZONE, ME, T, Q) : guild(G, ZONE, Q, ME, T);
        if (myRole == GuildRoles.OFFICER) {
            g = withRole(g, ME, GuildRoles.OFFICER);
        }
        f.store.put(g);
        f.store.put(guild(H, ZONE, H_LEADER, H_MEMBER));
        return f;
    }

    /** 存储替身的锁内复核（JdbcGuildStore 的判定顺序）。 */
    private static GuildReject locked(GuildServiceFixture f, long guildId, boolean disband) {
        GuildData g = f.store.guilds.get(guildId);
        if (g == null) {
            return GuildReject.GUILD_GONE;
        }
        if (disband && g.leaderId() != ME) {
            return g.hasMember(ME) ? GuildReject.RANK_TOO_LOW : GuildReject.NOT_MEMBER;
        }
        return g.hasMember(ME) ? null : GuildReject.NOT_MEMBER;
    }

    private static Supplier<GuildReject> lockedOn(GuildServiceFixture f, Case c, long guildId) {
        return () -> locked(f, guildId, c.method() == ExpectedGuildMetrics.Method.DISBAND);
    }

    /** 帮会去掉一名成员后的快照。 */
    private static GuildData without(GuildServiceFixture f, long guildId, long playerId) {
        GuildData g = f.store.guilds.get(guildId);
        List<GuildData.Member> members = new ArrayList<>();
        for (GuildData.Member m : g.members()) {
            if (m.playerId() != playerId) {
                members.add(m);
            }
        }
        return new GuildData(g.guildId(), g.name(), g.leaderId(), g.level(), g.announcement(), g.createTimeMs(), g.maxMembers(),
                g.zoneId(), g.score(), g.funds(), members);
    }

    /**
     * 把我移出 G（我是帮主时先把帮主交给 Q）；{@code joinH} 时随即作为成员加入 H。只改存储替身的权威表，<b>不</b>失效缓存——
     * 等同于「换帮已提交、提交后失效还没跑到」。
     */
    private static void moveMeOut(GuildServiceFixture f, int myRole, boolean joinH) {
        GuildData g = without(f, G, ME);
        if (myRole == GuildRoles.LEADER) {
            g = withRole(new GuildData(g.guildId(), g.name(), Q, g.level(), g.announcement(), g.createTimeMs(), g.maxMembers(),
                    g.zoneId(), g.score(), g.funds(), g.members()), Q, GuildRoles.LEADER);
        }
        f.store.guilds.put(G, g);
        f.store.playerGuild.remove(ME);
        if (joinH) {
            GuildData h = f.store.guilds.get(H);
            List<GuildData.Member> members = new ArrayList<>(h.members());
            members.add(new GuildData.Member(ME, GuildRoles.MEMBER, 2000, 2000, 0, 0));
            members.sort((a, b) -> Long.compareUnsigned(a.playerId(), b.playerId()));
            f.store.put(new GuildData(h.guildId(), h.name(), h.leaderId(), h.level(), h.announcement(), h.createTimeMs(),
                    h.maxMembers(), h.zoneId(), h.score(), h.funds(), members));
        }
    }

    private static double count(GuildServiceFixture f, ExpectedGuildMetrics.Method method, ExpectedGuildMetrics.Outcome outcome) {
        return f.meters.get("game.guild.expected.guild").tag("method", method.label()).tag("outcome", outcome.label()).counter()
                .count();
    }

    /** 「不在预期帮」的共同判据：14002、计一次 mismatch、不推送；有 guild 字段的附当前帮会快照（wantView = 0 表示不附）。 */
    private static void assertRefused(GuildServiceFixture f, Case c, Reply reply, long wantView) {
        assertThat(reply.tip()).as(c.method().label()).isEqualTo(GuildTip.NOT_IN_EXPECTED_GUILD.proto());
        assertThat(reply.tip().getId()).isEqualTo(com.game.guild.rules.GuildTips.NOT_IN_GUILD);
        assertThat(count(f, c.method(), ExpectedGuildMetrics.Outcome.MISMATCH)).isEqualTo(1);
        assertThat(count(f, c.method(), ExpectedGuildMetrics.Outcome.ABSENT)).isZero();
        assertThat(f.pushes).as("被拒不推送").isEmpty();
        if (!c.hasView()) {
            return;
        }
        if (wantView == 0) {
            assertThat(reply.guild()).as("不在任何帮：不附快照").isNull();
        } else {
            assertThat(reply.guild()).as("附操作者当前所在帮会的快照").isNotNull();
            assertThat(reply.guild().getGuildId()).isEqualTo(wantView);
        }
    }

    // ================================================================ 用例

    @Test
    void 不带字段的旧客户端_按此刻所在帮执行_计absent() {
        for (Case c : cases()) {
            try (GuildServiceFixture f = world(c.myRole())) {
                c.arm().arm(f, lockedOn(f, c, G));
                Reply reply = c.call().apply(f, 0L);
                assertThat(reply.tip().getId()).as(c.method().label()).isZero();
                assertThat(f.called(c.write() + " g=" + G)).as(c.method().label()).isTrue();
                assertThat(count(f, c.method(), ExpectedGuildMetrics.Outcome.ABSENT)).isEqualTo(1);
                assertThat(count(f, c.method(), ExpectedGuildMetrics.Outcome.MISMATCH)).isZero();
            }
        }
    }

    @Test
    void 带的就是当前帮_照常成功_不计数() {
        for (Case c : cases()) {
            try (GuildServiceFixture f = world(c.myRole())) {
                c.arm().arm(f, lockedOn(f, c, G));
                Reply reply = c.call().apply(f, G);
                assertThat(reply.tip().getId()).as(c.method().label()).isZero();
                assertThat(f.called(c.write() + " g=" + G)).as(c.method().label()).isTrue();
                if (c.hasView()) {
                    assertThat(reply.guild().getGuildId()).as("成功回包仍是事务内的 G 快照").isEqualTo(G);
                }
                assertThat(count(f, c.method(), ExpectedGuildMetrics.Outcome.ABSENT)).isZero();
                assertThat(count(f, c.method(), ExpectedGuildMetrics.Outcome.MISMATCH)).isZero();
            }
        }
    }

    @Test
    void 已在别的帮_预检拒绝_不碰存储写_附当前帮快照() {
        for (Case c : cases()) {
            try (GuildServiceFixture f = world(c.myRole())) {
                moveMeOut(f, c.myRole(), true);
                c.arm().arm(f, lockedOn(f, c, G));
                Reply reply = c.call().apply(f, G);
                assertRefused(f, c, reply, H);
                assertThat(f.called(c.write())).as("预检已确认不在 G：不进事务（也不碰 G 的任何行）").isFalse();
            }
        }
    }

    @Test
    void 不在任何帮_预检拒绝_不附快照() {
        for (Case c : cases()) {
            try (GuildServiceFixture f = world(c.myRole())) {
                moveMeOut(f, c.myRole(), false);
                c.arm().arm(f, lockedOn(f, c, G));
                Reply reply = c.call().apply(f, G);
                assertRefused(f, c, reply, 0);
                assertThat(f.called(c.write())).isFalse();
            }
        }
    }

    @Test
    void 预检之后才换帮_事务绑在预期帮上_锁内复核拒绝_不作用到新帮() {
        for (Case c : cases()) {
            try (GuildServiceFixture f = world(c.myRole())) {
                // 预检读到的映射缓存是 G：先读进缓存，之后存储里的换帮不会失效它
                assertThat(f.cache.guildIdOf(ME, d())).isEqualTo(G);
                c.arm().arm(f, () -> {
                    moveMeOut(f, c.myRole(), true); // 换帮在本事务锁住 G 之前提交
                    return locked(f, G, c.method() == ExpectedGuildMetrics.Method.DISBAND);
                });
                Reply reply = c.call().apply(f, G);
                assertThat(f.called(c.write() + " g=" + G)).as("事务绑的是预期帮 G").isTrue();
                assertThat(f.called(c.write() + " g=" + H)).as("绝不作用到新帮 H").isFalse();
                assertRefused(f, c, reply, H);
                assertThat(f.cache.guildIdOf(ME, d())).as("拒绝时按 MySQL 纠正陈旧映射").isEqualTo(H);
            }
        }
    }

    @Test
    void 旧客户端在换帮之后的退帮_按新帮执行_行为不变() {
        try (GuildServiceFixture f = world(GuildRoles.MEMBER)) {
            moveMeOut(f, GuildRoles.MEMBER, true);
            f.store.leave = g -> TxOutcome.ok(new Left(without(f, g, ME), ME));
            Reply withField = new Reply(
                    f.guilds.leaveGuild(ME, LeaveGuildRequest.newBuilder().setExpectedGuildId(G).build(), d()).getErrorMessage(),
                    null);
            assertThat(withField.tip()).isEqualTo(GuildTip.NOT_IN_EXPECTED_GUILD.proto());
            assertThat(f.called("store.leave")).as("带字段的晚到退帮不能把玩家从新帮会移出").isFalse();

            assertThat(f.guilds.leaveGuild(ME, LeaveGuildRequest.getDefaultInstance(), d()).hasErrorMessage()).isFalse();
            assertThat(f.called("store.leave g=" + H)).as("旧客户端：按服务端此刻查到的帮会执行（这正是字段要防的事）").isTrue();
        }
    }

    @Test
    void 旧客户端解散_存储答已不是成员_仍回14005不自愈() {
        try (GuildServiceFixture f = world(GuildRoles.LEADER)) {
            f.store.disband = g -> TxOutcome.reject(GuildReject.NOT_MEMBER);
            TipInfoMessage tip = f.guilds.disbandGuild(ME, DisbandGuildRequest.getDefaultInstance(), d()).getErrorMessage();
            assertThat(tip).as("区分之前这一支就是 RANK_TOO_LOW：旧客户端答复逐字不变").isEqualTo(GuildTip.NOT_GUILD_LEADER.proto());
            assertThat(count(f, ExpectedGuildMetrics.Method.DISBAND, ExpectedGuildMetrics.Outcome.MISMATCH)).isZero();
        }
    }

    @Test
    void 预期帮在事务里已解散_回14002() {
        for (Case c : cases()) {
            try (GuildServiceFixture f = world(c.myRole())) {
                assertThat(f.cache.guildIdOf(ME, d())).isEqualTo(G);
                c.arm().arm(f, () -> GuildReject.GUILD_GONE);
                Reply reply = c.call().apply(f, G);
                assertThat(reply.tip()).as(c.method().label()).isEqualTo(GuildTip.NOT_IN_EXPECTED_GUILD.proto());
                assertThat(count(f, c.method(), ExpectedGuildMetrics.Outcome.MISMATCH)).isEqualTo(1);
                assertThat(f.pushes).isEmpty();
            }
        }
    }
}
