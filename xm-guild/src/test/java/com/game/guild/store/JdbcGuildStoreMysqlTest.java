package com.game.guild.store;

import static com.game.guild.store.GuildMysqlFixture.NOW;
import static com.game.guild.store.GuildMysqlFixture.RULES;
import static com.game.guild.store.GuildMysqlFixture.TTL;
import static com.game.guild.store.GuildMysqlFixture.assertRejectIn;
import static com.game.guild.store.GuildMysqlFixture.concurrently;
import static com.game.guild.store.GuildMysqlFixture.d;
import static com.game.guild.store.GuildMysqlFixture.okCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.store.GuildMysqlFixture.GateHooks;
import com.game.guild.store.GuildMysqlFixture.Recorder;
import com.game.guild.store.GuildMysqlFixture.Result;
import com.game.guild.store.GuildStore.ApplicantRow;
import com.game.guild.store.GuildStore.Applied;
import com.game.guild.store.GuildStore.Disbanded;
import com.game.guild.store.GuildStore.Kicked;
import com.game.guild.store.GuildStore.OfficerCaps;
import com.game.guild.store.GuildStore.Reviewed;
import com.game.guild.store.GuildStore.RoleChanged;
import com.game.guild.store.GuildStore.Transferred;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * {@link JdbcGuildStore} 连真 MySQL 的业务用例（缺省跳过；guild-spec §11.3「业务」「并发」「Java 增项」）。移植
 * mmorpg guild_manage_repo_test.go:618-1829 与 guild_repo_zone_test.go:82-151：任命到上限、长老不能任免、陈旧的高职位不生效、
 * 等级配置缺失 fail-closed；踢人矩阵；转让三种降级与 leader 不一致 fail-closed；退帮；申请刷新（同一毫秒、帮满时刷新）、每人上限只数
 * 未过期、帮会队列满、每次至多清 10 条、审批人名单；撤回；I1 过滤；审批通过的全部分支（含 1062 子分支）；建帮删自己的申请；解散按 MySQL
 * 授权并删申请、事务内判闸门；公告回快照；锁等待封顶；并发四例；跨区与规范化撞名。基线里经缓存的那几例（陈旧映射自愈、被踢后的陈旧快照）
 * 只移植 MySQL 一侧：存储读到的必须是真相。Java 增项：≥ 2^63 的无符号 id 全流程、子预算用完不写库、COMMIT 结果不明、转让后帮主数不为 1。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class JdbcGuildStoreMysqlTest {

    private static final int ROUNDS = 20;

    private static GuildMysqlFixture db;
    private Recorder recorder;
    private GateHooks hooks;
    private JdbcGuildStore store;

    @BeforeAll
    static void createDatabase() throws SQLException {
        db = GuildMysqlFixture.create();
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void reset() throws SQLException {
        db.reset();
        recorder = new Recorder();
        hooks = new GateHooks();
        store = db.store(recorder, hooks);
    }

    private static OfficerCaps capOf(int n) {
        return level -> OptionalInt.of(n);
    }

    private static Map<Long, Integer> roles(Object... pairs) {
        Map<Long, Integer> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((Long) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    private static GuildData founding(long guildId, long leaderId, String name, int zone, long now) {
        return new GuildData(guildId, name, leaderId, 1, "", now, 30, zone, 0, 0,
                List.of(new GuildData.Member(leaderId, GuildRoles.LEADER, now, now, 0, 0)));
    }

    private static Map<Long, Integer> rolesOf(GuildData guild) {
        Map<Long, Integer> out = new java.util.HashMap<>();
        for (GuildData.Member m : guild.members()) {
            out.put(m.playerId(), m.role());
        }
        return out;
    }

    // ================================================================ 成员管理

    @Test
    void 任命到上限_幂等任命不写库() throws SQLException {
        long g = 7101, leader = 8101, m1 = 8102, m2 = 8103, m3 = 8104;
        db.seedGuild(g, 2, 1, 50, leader, roles(m1, GuildRoles.MEMBER, m2, GuildRoles.MEMBER, m3, GuildRoles.MEMBER));

        RoleChanged res = store.setMemberRole(g, leader, m1, GuildRoles.OFFICER, capOf(2), d()).orThrow();
        assertThat(res.changed()).isTrue();
        assertThat(rolesOf(res.guild()).get(m1)).as("快照必须含本次写").isEqualTo(GuildRoles.OFFICER);
        assertThat(res.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.SET_ROLE, g));
        assertThat(store.setMemberRole(g, leader, m2, GuildRoles.OFFICER, capOf(2), d()).orThrow().changed()).isTrue();

        assertThat(store.setMemberRole(g, leader, m3, GuildRoles.OFFICER, capOf(2), d()).rejection())
                .isEqualTo(GuildReject.OFFICER_LIMIT);
        assertThat(db.roleOf(g, m3)).as("被上限拒绝后不能留下半写").isEqualTo(GuildRoles.MEMBER);

        RoleChanged again = store.setMemberRole(g, leader, m1, GuildRoles.OFFICER, capOf(2), d()).orThrow();
        assertThat(again.changed()).isFalse();
        assertThat(again.guild()).isNotNull();
        assertThat(again.invalidation().isEmpty()).isTrue();
        assertThat(again.pushRecipients()).isEmpty();
        assertThat(db.officers(g)).isEqualTo(2);
    }

    @Test
    void 长老不能任免_帮主的职位任免接口动不了() throws SQLException {
        long g = 7111, leader = 8111, officer = 8112, member = 8113;
        db.seedGuild(g, 2, 1, 50, leader, roles(officer, GuildRoles.OFFICER, member, GuildRoles.MEMBER));

        assertThat(store.setMemberRole(g, officer, member, GuildRoles.OFFICER, capOf(5), d()).rejection())
                .isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(db.roleOf(g, member)).isEqualTo(GuildRoles.MEMBER);
        assertThat(store.setMemberRole(g, officer, leader, GuildRoles.MEMBER, capOf(5), d()).rejection())
                .isEqualTo(GuildReject.RANK_TOO_LOW);
        // 发起者换成帮主、目标是（脏数据下的）另一个帮主：锁内「目标是帮主就拒」的防御分支
        db.exec("UPDATE guild_member SET role = ? WHERE guild_id = ? AND player_id = ?", GuildRoles.LEADER, g, officer);
        assertThat(store.setMemberRole(g, leader, officer, GuildRoles.MEMBER, capOf(5), d()).rejection())
                .isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(db.leaderOf(g)).isEqualTo(leader);
    }

    @Test
    void 授权只看锁内MySQL_读到过的旧职位不生效() throws SQLException {
        long g = 7121, leader = 8121, member = 8122;
        db.seedGuild(g, 2, 1, 50, leader, roles(member, GuildRoles.MEMBER));
        GuildData before = store.loadGuild(g, d()).orElseThrow();
        assertThat(rolesOf(before).get(leader)).isEqualTo(GuildRoles.LEADER);

        // 绕开存储直接在权威库降权（模拟「降职写成功但缓存没失效」）
        db.exec("UPDATE guild_member SET role = ? WHERE guild_id = ? AND player_id = ?", GuildRoles.MEMBER, g, leader);
        assertThat(store.setMemberRole(g, leader, member, GuildRoles.OFFICER, capOf(5), d()).rejection())
                .isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(db.roleOf(g, member)).isEqualTo(GuildRoles.MEMBER);
        // 改公告同理（guild_repo_test.go:38）
        db.exec("UPDATE guild_member SET role = ? WHERE guild_id = ? AND player_id = ?", GuildRoles.OFFICER, g, member);
        assertThat(store.updateAnnouncement(g, member, "ok", d()).isOk()).isTrue();
        db.exec("UPDATE guild_member SET role = ? WHERE guild_id = ? AND player_id = ?", GuildRoles.MEMBER, g, member);
        assertThat(store.updateAnnouncement(g, member, "stale", d()).rejection())
                .isEqualTo(GuildReject.ANNOUNCEMENT_FORBIDDEN);
    }

    @Test
    void 等级配置缺失fail_closed_降为成员不查上限() throws SQLException {
        long g = 7131, leader = 8131, member = 8132;
        db.seedGuild(g, 2, 1, 50, leader, roles(member, GuildRoles.MEMBER));
        OfficerCaps missing = level -> OptionalInt.empty();

        assertThat(store.setMemberRole(g, leader, member, GuildRoles.OFFICER, missing, d()).rejection())
                .isEqualTo(GuildReject.LEVEL_CONFIG_MISSING);
        assertThat(db.roleOf(g, member)).isEqualTo(GuildRoles.MEMBER);
        db.exec("UPDATE guild_member SET role = ? WHERE guild_id = ? AND player_id = ?", GuildRoles.OFFICER, g, member);
        assertThat(store.setMemberRole(g, leader, member, GuildRoles.MEMBER, missing, d()).isOk())
                .as("配表缺失不该连收权都做不了").isTrue();
    }

    @Test
    void 踢人矩阵_被踢者的映射要失效() throws SQLException {
        long g = 7141, leader = 8141, officer1 = 8142, officer2 = 8143, member = 8144, outsider = 8145;
        db.seedGuild(g, 2, 1, 50, leader, roles(officer1, GuildRoles.OFFICER, officer2, GuildRoles.OFFICER,
                member, GuildRoles.MEMBER));

        assertThat(store.kickMember(g, officer1, officer2, NOW, d()).rejection()).as("平级不能互踢")
                .isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(store.kickMember(g, officer1, leader, NOW, d()).rejection()).as("没有人能踢帮主")
                .isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(store.kickMember(g, leader, outsider, NOW, d()).rejection()).isEqualTo(GuildReject.TARGET_NOT_MEMBER);
        assertThat(db.stateRows(outsider)).as("踢非成员不建状态行").isZero();

        Kicked res = store.kickMember(g, leader, officer2, NOW, d()).orThrow();
        assertThat(rolesOf(res.guild())).as("快照不能再含被踢者").doesNotContainKey(officer2);
        assertThat(db.memberships(officer2)).isZero();
        assertThat(res.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.KICK, g, officer2));
        assertThat(res.pushRecipients()).containsExactly(officer1, member, officer2);
        assertThat(hooks.calls).contains("accelerate:" + g + ":[" + officer2 + "]:" + NOW);
        assertThat(store.kickMember(g, officer1, member, NOW, d()).isOk()).as("长老可踢成员").isTrue();
        assertThat(store.kickMember(g, leader, officer2, NOW, d()).rejection()).as("重踢").isEqualTo(GuildReject.TARGET_NOT_MEMBER);
    }

    @Test
    void 转让_长老位有空原帮主补长老() throws SQLException {
        long g = 7151, leader = 8151, officer = 8152, member = 8153;
        db.seedGuild(g, 2, 1, 50, leader, roles(officer, GuildRoles.OFFICER, member, GuildRoles.MEMBER));
        Transferred res = store.transferLeader(g, leader, member, capOf(2), d()).orThrow();
        Map<Long, Integer> r = rolesOf(res.guild());
        assertThat(r.get(member)).isEqualTo(GuildRoles.LEADER);
        assertThat(r.get(leader)).isEqualTo(GuildRoles.OFFICER);
        assertThat(res.guild().leaderId()).isEqualTo(member);
        assertThat(db.leaderOf(g)).as("leader_id 与 role=3 同事务改写").isEqualTo(member);
        assertThat(res.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.TRANSFER, g));
        assertThat(store.transferLeader(g, leader, member, capOf(2), d()).rejection()).as("重放")
                .isEqualTo(GuildReject.RANK_TOO_LOW);
    }

    @Test
    void 转让_长老位满原帮主降成员() throws SQLException {
        long g = 7161, leader = 8161, officer = 8162, member = 8163;
        db.seedGuild(g, 2, 1, 50, leader, roles(officer, GuildRoles.OFFICER, member, GuildRoles.MEMBER));
        Map<Long, Integer> r = rolesOf(store.transferLeader(g, leader, member, capOf(1), d()).orThrow().guild());
        assertThat(r.get(member)).isEqualTo(GuildRoles.LEADER);
        assertThat(r.get(leader)).isEqualTo(GuildRoles.MEMBER);
        assertThat(r.get(officer)).as("既有长老不因转让被挤掉").isEqualTo(GuildRoles.OFFICER);
    }

    @Test
    void 转让_目标原是长老腾出一个位() throws SQLException {
        long g = 7171, leader = 8171, officer = 8172;
        db.seedGuild(g, 2, 1, 50, leader, roles(officer, GuildRoles.OFFICER));
        Map<Long, Integer> r = rolesOf(store.transferLeader(g, leader, officer, capOf(1), d()).orThrow().guild());
        assertThat(r.get(officer)).isEqualTo(GuildRoles.LEADER);
        assertThat(r.get(leader)).isEqualTo(GuildRoles.OFFICER);
        assertThat(db.officers(g)).as("长老数不能超过 cap").isEqualTo(1);
    }

    @Test
    void 转让_leader_id不一致fail_closed() throws SQLException {
        long g = 7181, leader = 8181, member = 8182, bogus = 8183;
        db.seedGuild(g, 2, 1, 50, leader, roles(member, GuildRoles.MEMBER));
        db.exec("UPDATE guild SET leader_id = ? WHERE guild_id = ?", bogus, g);
        assertThat(store.transferLeader(g, leader, member, capOf(2), d()).rejection()).isEqualTo(GuildReject.LEADER_MISMATCH);
        assertThat(db.leaderOf(g)).as("拒写之后数据原封不动").isEqualTo(bogus);
        assertThat(db.roleOf(g, member)).isEqualTo(GuildRoles.MEMBER);
        assertThat(db.roleOf(g, leader)).isEqualTo(GuildRoles.LEADER);
    }

    @Test
    void 转让_写后帮主不止一人是内部错误且回滚() throws SQLException {
        long g = 7185, leader = 8185, member = 8186, dirty = 8187;
        db.seedGuild(g, 2, 1, 50, leader, roles(member, GuildRoles.MEMBER, dirty, GuildRoles.LEADER));
        assertThatThrownBy(() -> store.transferLeader(g, leader, member, capOf(2), d()))
                .isInstanceOf(GuildStoreException.class)
                .extracting(e -> ((GuildStoreException) e).kind()).isEqualTo(GuildStoreException.Kind.INVARIANT_BROKEN);
        assertThat(db.leaderOf(g)).isEqualTo(leader);
        assertThat(db.roleOf(g, member)).isEqualTo(GuildRoles.MEMBER);
    }

    @Test
    void 退帮_帮主不能退_重放不是成员() throws SQLException {
        long g = 7191, leader = 8191, member = 8192;
        db.seedGuild(g, 2, 1, 50, leader, roles(member, GuildRoles.MEMBER));
        assertThat(store.leaveGuild(g, leader, NOW, d()).rejection()).isEqualTo(GuildReject.LEADER_CANT_LEAVE);
        assertThat(db.memberships(leader)).isEqualTo(1);

        GuildStore.Left res = store.leaveGuild(g, member, NOW, d()).orThrow();
        assertThat(rolesOf(res.guild())).doesNotContainKey(member);
        assertThat(res.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.LEAVE, g, member));
        assertThat(res.pushRecipients()).containsExactly(leader);
        assertThat(store.leaveGuild(g, member, NOW, d()).rejection()).isEqualTo(GuildReject.NOT_MEMBER);
        assertThat(store.leaveGuild(7199, member, NOW, d()).rejection()).isEqualTo(GuildReject.GUILD_GONE);
        // leader_id 指着他（role 已被改坏）同样拒
        db.seedMember(g, member, GuildRoles.MEMBER);
        db.exec("UPDATE guild SET leader_id = ? WHERE guild_id = ?", member, g);
        assertThat(store.leaveGuild(g, member, NOW, d()).rejection()).isEqualTo(GuildReject.LEADER_CANT_LEAVE);
    }

    // ================================================================ 申请

    @Test
    void 申请_同帮重复申请刷新有效期() throws SQLException {
        long g = 7201, leader = 8201, applicant = 8202;
        db.seedGuild(g, 2, 50, leader);
        Applied first = store.applyToGuild(g, applicant, 0, NOW, RULES, d()).orThrow();
        assertThat(first.inserted()).isTrue();
        assertThat(first.reviewerIds()).containsExactly(leader);
        long firstExpire = db.expireOf(g, applicant);

        Applied second = store.applyToGuild(g, applicant, 0, NOW + 1000, RULES, d()).orThrow();
        assertThat(second.inserted()).as("刷新不是新申请，不重复推送").isFalse();
        assertThat(second.reviewerIds()).isEmpty();
        assertThat(db.expireOf(g, applicant)).isGreaterThan(firstExpire);
        assertThat(db.playerApplications(applicant)).isEqualTo(1);
    }

    @Test
    void 申请_同一毫秒双击也算刷新成功() throws SQLException {
        long g = 7211, leader = 8211, applicant = 8212;
        db.seedGuild(g, 2, 50, leader);
        assertThat(store.applyToGuild(g, applicant, 0, NOW, RULES, d()).orThrow().inserted()).isTrue();
        Applied again = store.applyToGuild(g, applicant, 0, NOW, RULES, d()).orThrow();
        assertThat(again.inserted()).isFalse();
        assertThat(db.playerApplications(applicant)).isEqualTo(1);
    }

    @Test
    void 申请_帮满时已在队列的人刷新照常成功() throws SQLException {
        long g = 7215, leader = 8215, filler = 8216, p = 8217, q = 8218;
        db.seedGuild(g, 2, 2, leader);
        assertThat(store.applyToGuild(g, p, 0, NOW, RULES, d()).orThrow().inserted()).isTrue();
        long firstExpire = db.expireOf(g, p);
        db.seedMember(g, filler, GuildRoles.MEMBER); // 帮会现在满了

        assertThat(store.applyToGuild(g, p, 0, NOW + 5000, RULES, d()).orThrow().inserted()).isFalse();
        assertThat(db.expireOf(g, p)).isGreaterThan(firstExpire);
        assertThat(store.applyToGuild(g, q, 0, NOW + 5000, RULES, d()).rejection()).isEqualTo(GuildReject.GUILD_FULL);
        assertThat(db.playerApplications(q)).isZero();
    }

    @Test
    void 申请_每人上限只数未过期_过期行被惰性删除() throws SQLException {
        long applicant = 8221;
        long[] guilds = {7221, 7222, 7223, 7224};
        for (int i = 0; i < guilds.length; i++) {
            db.seedGuild(guilds[i], 2, 50, 8300 + i);
        }
        for (int i = 0; i < 3; i++) {
            assertThat(store.applyToGuild(guilds[i], applicant, 0, NOW, RULES, d()).orThrow().inserted()).isTrue();
        }
        assertThat(store.applyToGuild(guilds[3], applicant, 0, NOW, RULES, d()).rejection())
                .isEqualTo(GuildReject.APPLICATION_LIMIT);

        db.exec("UPDATE guild_application SET expire_ms = ? WHERE guild_id = ? AND player_id = ?", NOW - 1, guilds[0], applicant);
        assertThat(store.applyToGuild(guilds[3], applicant, 0, NOW, RULES, d()).orThrow().inserted()).isTrue();
        assertThat(db.application(guilds[0], applicant)).as("过期行必须被惰性清理").isZero();
        assertThat(db.playerApplications(applicant)).isEqualTo(3);
    }

    @Test
    void 申请_帮会队列按I1计_已入他帮的申请人不占名额() throws SQLException {
        long target = 7231, other = 7232, leader = 8231, p1 = 8232, p2 = 8233, p3 = 8234;
        db.seedGuild(target, 2, 50, leader);
        db.seedGuild(other, 2, 50, 8235);
        for (long p : new long[] {p1, p2}) {
            assertThat(store.applyToGuild(target, p, 0, NOW, RULES, d()).orThrow().inserted()).isTrue();
        }
        assertThat(store.applyToGuild(target, p3, 0, NOW, RULES, d()).rejection()).isEqualTo(GuildReject.QUEUE_FULL);
        db.seedMember(other, p1, GuildRoles.MEMBER);
        assertThat(store.applyToGuild(target, p3, 0, NOW, RULES, d()).orThrow().inserted()).isTrue();
    }

    @Test
    void 申请_提交后每次至多清本帮10条过期申请() throws SQLException {
        long target = 7241, leader = 8241, first = 8242, second = 8243, base = 9240;
        int backlog = 15;
        db.seedGuild(target, 2, 50, leader);
        for (int i = 0; i < backlog; i++) {
            db.seedApplication(target, base + i, NOW - TTL, NOW - 1);
        }
        String expiredLeft = "SELECT COUNT(*) FROM guild_application WHERE guild_id = ? AND expire_ms <= ?";

        assertThat(store.applyToGuild(target, first, 0, NOW, RULES, d()).orThrow().inserted()).isTrue();
        assertThat(db.count(expiredLeft, target, NOW)).isEqualTo(backlog - 10);
        assertThat(db.application(target, base)).as("按 player_id 升序先清最小的").isZero();
        assertThat(db.application(target, base + 10)).as("第 11 行留给下一次").isEqualTo(1);

        assertThat(store.applyToGuild(target, second, 0, NOW, RULES, d()).orThrow().inserted()).isTrue();
        assertThat(db.count(expiredLeft, target, NOW)).as("积压被下一次申请清完").isZero();
        assertThat(db.application(target, first)).as("未过期的申请不受清理影响").isEqualTo(1);
    }

    @Test
    void 申请_帮满_已入帮_帮会不存在() throws SQLException {
        long full = 7245, open = 7246, fullLeader = 8245, openLeader = 8246, outsider = 8247;
        db.seedGuild(full, 2, 1, fullLeader);
        db.seedGuild(open, 2, 50, openLeader);
        assertThat(store.applyToGuild(full, outsider, 0, NOW, RULES, d()).rejection()).isEqualTo(GuildReject.GUILD_FULL);
        assertThat(db.playerApplications(outsider)).isZero();
        assertThat(store.applyToGuild(open, fullLeader, 0, NOW, RULES, d()).rejection()).isEqualTo(GuildReject.ALREADY_IN_GUILD);
        assertThat(db.playerApplications(fullLeader)).isZero();
        assertThat(store.applyToGuild(7249, outsider, 0, NOW, RULES, d()).rejection()).isEqualTo(GuildReject.GUILD_GONE);
    }

    @Test
    void 申请_审批人名单只有长老与帮主且升序() throws SQLException {
        long g = 7251, leader = 8255, officer = 8252, member1 = 8253, member2 = 8254, applicant = 8256;
        db.seedGuild(g, 2, 1, 50, leader, roles(officer, GuildRoles.OFFICER, member1, GuildRoles.MEMBER,
                member2, GuildRoles.MEMBER));
        Applied res = store.applyToGuild(g, applicant, 0, NOW, RULES, d()).orThrow();
        assertThat(res.reviewerIds()).as("普通成员不是审批人").containsExactly(officer, leader);
    }

    @Test
    void 申请_别区的帮像不存在一样_不留申请行() throws SQLException {
        long g = 7261, applicant = 8261, internal = 8262;
        db.exec("INSERT INTO guild (guild_id, name, name_norm, leader_id, level, announcement, create_time_ms, max_members,"
                + " zone_id, score, funds) VALUES (?, 'zone-two-guild', 'zone-two-guild', 9101, 1, '', 1, 50, 2, 0, 0)", g);
        assertThat(store.applyToGuild(g, applicant, 3, NOW, RULES, d()).rejection()).isEqualTo(GuildReject.ZONE_MISMATCH);
        assertThat(db.playerApplications(applicant)).as("zone 不符时不能留下申请行").isZero();
        assertThat(store.applyToGuild(g, applicant, 2, NOW, RULES, d()).orThrow().inserted()).isTrue();
        assertThat(store.applyToGuild(g, internal, 0, NOW, RULES, d()).orThrow().inserted()).as("requiredZone=0 不校验").isTrue();
    }

    @Test
    void 撤回_有效申请删掉_重复撤回与撤回过期行都回不存在_过期行也删() throws SQLException {
        long g = 7265, leader = 8265, applicant = 8266;
        db.seedGuild(g, 2, 50, leader);
        store.applyToGuild(g, applicant, 0, NOW, RULES, d()).orThrow();

        assertThat(store.cancelApplication(g, applicant, NOW, d()).isOk()).isTrue();
        assertThat(db.application(g, applicant)).isZero();
        TxOutcome<Void> again = store.cancelApplication(g, applicant, NOW, d());
        assertThat(again).isInstanceOf(TxOutcome.Reject.class);
        assertThat(again.rejection()).isEqualTo(GuildReject.APPLICATION_NOT_FOUND);

        db.seedApplication(g, applicant, NOW - 100, NOW - 1);
        TxOutcome<Void> expired = store.cancelApplication(g, applicant, NOW, d());
        assertThat(expired).as("先提交删除再回不存在").isInstanceOf(TxOutcome.CommitThenReject.class);
        assertThat(expired.rejection()).isEqualTo(GuildReject.APPLICATION_NOT_FOUND);
        assertThat(db.application(g, applicant)).as("过期行也要顺手删掉").isZero();
    }

    @Test
    void 待审名单与计数同用I1() throws SQLException {
        long g = 7271, other = 7272, leader = 8271, live = 8272, expired = 8273, joined = 8274;
        db.seedGuild(g, 2, 50, leader);
        db.seedGuild(other, 2, 50, 8275);
        db.seedApplication(g, live, NOW - 30, NOW + TTL);
        db.seedApplication(g, expired, NOW - 20, NOW - 1);
        db.seedApplication(g, joined, NOW - 10, NOW + TTL);
        db.seedMember(other, joined, GuildRoles.MEMBER);

        List<ApplicantRow> rows = store.listApplicants(g, NOW, 50, d());
        assertThat(rows).extracting(ApplicantRow::playerId).containsExactly(live);
        assertThat(rows.getFirst().applyMs()).isEqualTo(NOW - 30);
        assertThat(store.countLiveApplications(g, NOW, d())).isEqualTo(1);
        assertThat(store.listApplicants(g, NOW, 0, d())).as("limit=0 是「别查了」").isEmpty();
    }

    @Test
    void 本人申请列表_按申请时间倒序_帮号升序_限条数() throws SQLException {
        long p = 8281;
        db.seedApplication(7283, p, NOW - 10, NOW + TTL);
        db.seedApplication(7281, p, NOW - 5, NOW + TTL);
        db.seedApplication(7282, p, NOW - 5, NOW + TTL);
        db.seedApplication(7284, p, NOW - 1, NOW - 1); // 已过期
        List<GuildStore.ApplicationRow> rows = store.listMyApplications(p, NOW, 10, d());
        assertThat(rows).extracting(GuildStore.ApplicationRow::guildId).containsExactly(7281L, 7282L, 7283L);
        assertThat(store.listMyApplications(p, NOW, 2, d())).hasSize(2);
        assertThat(store.listMyApplications(p, NOW, 0, d())).isEmpty();
    }

    // ================================================================ 审批

    @Test
    void 审批通过_删光申请人所有帮的申请_映射要失效() throws SQLException {
        long g1 = 7301, g2 = 7302, l1 = 8301, l2 = 8302, applicant = 8303;
        int zone = 2;
        db.seedGuild(g1, zone, 50, l1);
        db.seedGuild(g2, zone, 50, l2);
        for (long g : new long[] {g1, g2}) {
            store.applyToGuild(g, applicant, zone, NOW, RULES, d()).orThrow();
        }
        assertThat(store.playerGuildId(applicant, d())).isZero();

        Reviewed res = store.reviewApplication(g1, l1, applicant, true, zone, NOW, d()).orThrow();
        assertThat(res.approved()).isTrue();
        assertThat(rolesOf(res.guild()).get(applicant)).isEqualTo(GuildRoles.MEMBER);
        assertThat(db.roleOf(g1, applicant)).isEqualTo(GuildRoles.MEMBER);
        assertThat(db.playerApplications(applicant)).as("I2：两条申请都要没").isZero();
        assertThat(res.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.REVIEW, g1, applicant));
        assertThat(res.pushRecipients()).containsExactly(applicant);
        assertThat(store.playerGuildId(applicant, d())).isEqualTo(g1);
        assertThat(store.reviewApplication(g1, l1, applicant, true, zone, NOW, d()).rejection()).as("重放")
                .isEqualTo(GuildReject.APPLICATION_NOT_FOUND);
    }

    @Test
    void 审批通过_帮会已换区_删跨区申请并提交() throws SQLException {
        long g = 7311, leader = 8311, applicant = 8312;
        db.seedGuild(g, 2, 50, leader);
        store.applyToGuild(g, applicant, 2, NOW, RULES, d()).orThrow();
        db.exec("UPDATE guild SET zone_id = 3 WHERE guild_id = ?", g); // 模拟合服回滚
        TxOutcome<Reviewed> out = store.reviewApplication(g, leader, applicant, true, 2, NOW, d());
        assertThat(out).isInstanceOf(TxOutcome.CommitThenReject.class);
        assertThat(out.rejection()).isEqualTo(GuildReject.APPLICATION_NOT_FOUND);
        assertThat(db.application(g, applicant)).as("跨区申请必须被删掉").isZero();
        assertThat(db.memberships(applicant)).isZero();
    }

    @Test
    void 审批拒绝_删申请不加人_回快照_不看申请人归属区() throws SQLException {
        long g = 7321, leader = 8321, applicant = 8322;
        db.seedGuild(g, 2, 50, leader);
        db.seedApplication(g, applicant, NOW - 10, NOW + TTL);
        Reviewed res = store.reviewApplication(g, leader, applicant, false, 0, NOW, d()).orThrow();
        assertThat(res.approved()).isFalse();
        assertThat(res.guild()).isNotNull();
        assertThat(res.invalidation().isEmpty()).as("拒绝不失效").isTrue();
        assertThat(res.pushRecipients()).containsExactly(applicant);
        assertThat(db.application(g, applicant)).isZero();
        assertThat(db.memberships(applicant)).isZero();
        assertThat(db.stateRows(applicant)).as("拒绝不建申请人的状态行").isZero();
    }

    @Test
    void 审批_过期申请删掉并提交() throws SQLException {
        long g = 7331, leader = 8331, applicant = 8332;
        db.seedGuild(g, 2, 50, leader);
        db.seedApplication(g, applicant, NOW - 100, NOW - 1);
        TxOutcome<Reviewed> out = store.reviewApplication(g, leader, applicant, true, 2, NOW, d());
        assertThat(out).isInstanceOf(TxOutcome.CommitThenReject.class);
        assertThat(db.application(g, applicant)).as("删除必须被提交").isZero();
        assertThat(db.memberships(applicant)).isZero();
        // 拒绝分支遇过期同样删行并提交
        db.seedApplication(g, applicant, NOW - 100, NOW);
        assertThat(store.reviewApplication(g, leader, applicant, false, 0, NOW, d()))
                .isInstanceOf(TxOutcome.CommitThenReject.class);
        assertThat(db.application(g, applicant)).isZero();
    }

    @Test
    void 审批通过_帮满回滚并保留申请() throws SQLException {
        long g = 7341, leader = 8341, applicant = 8342;
        db.seedGuild(g, 2, 1, leader);
        db.seedApplication(g, applicant, NOW - 10, NOW + TTL);
        TxOutcome<Reviewed> out = store.reviewApplication(g, leader, applicant, true, 2, NOW, d());
        assertThat(out).isInstanceOf(TxOutcome.Reject.class);
        assertThat(out.rejection()).isEqualTo(GuildReject.GUILD_FULL);
        assertThat(db.application(g, applicant)).as("满员时必须保留申请").isEqualTo(1);
        assertThat(db.memberships(applicant)).isZero();
    }

    @Test
    void 审批通过_申请人已入他帮_1062子分支删申请并提交() throws SQLException {
        long g1 = 7351, g2 = 7352, l1 = 8351, l2 = 8352, applicant = 8353;
        db.seedGuild(g1, 2, 50, l1);
        db.seedGuild(g2, 2, 50, l2);
        db.seedApplication(g1, applicant, NOW - 10, NOW + TTL);
        db.seedMember(g2, applicant, GuildRoles.MEMBER);
        TxOutcome<Reviewed> out = store.reviewApplication(g1, l1, applicant, true, 2, NOW, d());
        assertThat(out).isInstanceOf(TxOutcome.CommitThenReject.class);
        assertThat(out.rejection()).isEqualTo(GuildReject.APPLICATION_NOT_FOUND);
        assertThat(db.application(g1, applicant)).isZero();
        assertThat(db.roleOf(g1, applicant)).as("不能在 g1 留下成员行").isNull();
        assertThat(db.roleOf(g2, applicant)).as("他在 g2 的成员关系不受影响").isEqualTo(GuildRoles.MEMBER);
    }

    @Test
    void 审批_普通成员不能审批_申请原样留着() throws SQLException {
        long g = 7361, leader = 8361, member = 8362, applicant = 8363;
        db.seedGuild(g, 2, 1, 50, leader, roles(member, GuildRoles.MEMBER));
        db.seedApplication(g, applicant, NOW - 10, NOW + TTL);
        assertThat(store.reviewApplication(g, member, applicant, true, 2, NOW, d()).rejection())
                .isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(store.reviewApplication(g, member, applicant, false, 0, NOW, d()).rejection())
                .isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(db.application(g, applicant)).isEqualTo(1);
        assertThat(db.memberships(applicant)).isZero();
        assertThat(store.reviewApplication(g, 8369, applicant, false, 0, NOW, d()).rejection())
                .isEqualTo(GuildReject.NOT_MEMBER);
        assertThat(store.reviewApplication(7369, leader, applicant, false, 0, NOW, d()).rejection())
                .isEqualTo(GuildReject.GUILD_GONE);
    }

    // ================================================================ 建帮 / 解散 / 公告

    @Test
    void 建帮_删掉建帮者自己的申请_解散后也不复活() throws SQLException {
        long g1 = 7371, g2 = 7372, l1 = 8371, founder = 8372;
        db.seedGuild(g1, 2, 50, l1);
        assertThat(store.applyToGuild(g1, founder, 2, NOW, RULES, d()).orThrow().inserted()).isTrue();

        GuildStore.Created created = store.createGuild(founding(g2, founder, "founder-guild", 2, NOW), d()).orThrow();
        assertThat(created.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.CREATE, g2, founder));
        assertThat(store.listApplicants(g1, NOW, 50, d())).isEmpty();
        GuildData stored = store.loadGuild(g2, d()).orElseThrow();
        assertThat(stored.name()).isEqualTo("founder-guild");
        assertThat(stored.level()).isEqualTo(1);
        assertThat(stored.maxMembers()).isEqualTo(30);
        assertThat(stored.zoneId()).isEqualTo(2);
        assertThat(stored.funds()).isZero();
        assertThat(stored.score()).isZero();
        assertThat(stored.members()).containsExactly(new GuildData.Member(founder, GuildRoles.LEADER, NOW, NOW, 0, 0));
        assertThat(db.queryU64("SELECT 1 FROM guild WHERE guild_id = ? AND name_norm = 'founder-guild'", g2)).isEqualTo(1L);

        store.disbandGuild(g2, founder, NOW, null, d()).orThrow();
        assertThat(store.listApplicants(g1, NOW, 50, d())).isEmpty();
        assertThat(db.playerApplications(founder)).isZero();
        assertThat(store.createGuild(founding(7373, l1, "second", 2, NOW), d()).rejection()).as("已入帮")
                .isEqualTo(GuildReject.ALREADY_IN_GUILD);
    }

    @Test
    void 建帮_帮名全服唯一_跨区同名与规范化撞名_失败不留成员行() throws SQLException {
        assertThat(store.createGuild(founding(7381, 8381, "同名帮", 1, 1), d()).isOk()).isTrue();
        assertThat(store.createGuild(founding(7382, 8382, "同名帮", 2, 1), d()).rejection()).isEqualTo(GuildReject.NAME_TAKEN);
        assertThat(db.memberships(8382)).isZero();

        assertThat(store.createGuild(founding(7383, 8383, "Qing云", 1, 1), d()).isOk()).isTrue();
        long leader = 8384;
        long gid = 7384;
        for (String variant : new String[] {"qing云", "ＱＩＮＧ云", "Qing云 "}) {
            assertThat(store.createGuild(founding(gid, leader, variant, 2, 1), d()).rejection()).as(variant)
                    .isEqualTo(GuildReject.NAME_TAKEN);
            assertThat(db.memberships(leader)).as(variant).isZero();
            gid++;
            leader++;
        }
        // 撞的是 guild 主键（发号出错）：不是撞名，是故障
        assertThatThrownBy(() -> store.createGuild(founding(7381, 8390, "另一个名字", 1, 1), d()))
                .isInstanceOf(com.game.common.deadline.Deadline.DependencyException.class);
        assertThat(db.memberships(8390)).isZero();
    }

    @Test
    void 解散_按MySQL的leader_id授权_删本帮申请与成员在别帮的申请() throws SQLException {
        long g = 7391, other = 7392, leader = 8391, officer = 8392, member = 8393, outsider = 8394;
        int zone = 5;
        db.seedGuild(g, zone, 1, 50, leader, roles(officer, GuildRoles.OFFICER, member, GuildRoles.MEMBER));
        db.seedGuild(other, zone, 50, 8395);
        db.seedApplication(g, outsider, NOW - 10, NOW + TTL);
        db.seedApplication(other, member, NOW - 10, NOW + TTL);

        assertThat(store.disbandGuild(g, officer, NOW, null, d()).rejection()).isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(db.guildRows(g)).isEqualTo(1);

        Disbanded res = store.disbandGuild(g, leader, NOW, null, d()).orThrow();
        assertThat(res.zoneId()).as("清榜只能信删除事务里读到的 zone").isEqualTo(zone);
        assertThat(res.memberIds()).as("player_id 升序").containsExactly(leader, officer, member);
        assertThat(res.pushRecipients()).containsExactly(officer, member);
        assertThat(res.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.DISBAND, g, leader, officer, member));
        assertThat(db.guildRows(g)).isZero();
        for (long p : new long[] {leader, officer, member}) {
            assertThat(db.memberships(p)).isZero();
        }
        assertThat(db.application(g, outsider)).as("本帮的待审申请要清掉").isZero();
        assertThat(db.playerApplications(member)).as("I3：成员在他帮的申请也要清掉").isZero();
        assertThat(hooks.calls).contains("accelerate:" + g + ":[" + leader + ", " + officer + ", " + member + "]:" + NOW,
                "activity:" + g);
        assertThat(store.disbandGuild(g, leader, NOW, null, d()).rejection()).as("重放").isEqualTo(GuildReject.GUILD_GONE);
    }

    @Test
    void 解散_事务内按行里的zone判合服闸门() throws SQLException {
        long g = 7396, leader = 8396, member = 8397;
        int zone = 6;
        db.seedGuild(g, zone, 1, 50, leader, roles(member, GuildRoles.MEMBER));
        List<Integer> fenced = new ArrayList<>();
        GuildStore.ZoneFence merging = z -> {
            fenced.add(z);
            return true;
        };
        assertThat(store.disbandGuild(g, leader, NOW, merging, d()).rejection()).isEqualTo(GuildReject.ZONE_MERGING);
        assertThat(fenced).as("闸门拿事务内读到的 zone_id 调").containsExactly(zone);
        assertThat(db.guildRows(g)).as("闸门拒绝必须整体回滚").isEqualTo(1);
        assertThat(db.memberships(member)).isEqualTo(1);

        GuildStore.ZoneFence unreadable = z -> {
            throw new IllegalStateException("redis: connection refused");
        };
        assertThat(store.disbandGuild(g, leader, NOW, unreadable, d()).rejection()).as("读不出来同样拒绝（fail-closed）")
                .isEqualTo(GuildReject.ZONE_MERGING);
        assertThat(db.guildRows(g)).isEqualTo(1);

        fenced.clear();
        assertThat(store.disbandGuild(g, member, NOW, merging, d()).rejection()).as("非帮主先吃 RANK_TOO_LOW")
                .isEqualTo(GuildReject.RANK_TOO_LOW);
        assertThat(fenced).isEmpty();

        assertThat(store.disbandGuild(g, leader, NOW, GuildStore.ZoneFence.OPEN, d()).orThrow().zoneId()).isEqualTo(zone);
        assertThat(db.guildRows(g)).isZero();
    }

    @Test
    void 公告_回事务内完整快照_成员不能改() throws SQLException {
        long g = 7401, leader = 8401, officer = 8402, member = 8403;
        db.seedGuild(g, 2, 1, 50, leader, roles(officer, GuildRoles.OFFICER, member, GuildRoles.MEMBER));
        GuildStore.AnnouncementUpdated res = store.updateAnnouncement(g, officer, "今晚八点集合", d()).orThrow();
        assertThat(res.guild().announcement()).isEqualTo("今晚八点集合");
        assertThat(res.guild().members()).as("完整快照").hasSize(3);
        assertThat(res.invalidation()).isEqualTo(Invalidation.of(GuildTxOp.ANNOUNCEMENT, g));
        assertThat(res.pushRecipients()).containsExactly(leader, member);
        assertThat(store.updateAnnouncement(g, officer, "今晚八点集合", d()).isOk()).as("同样的文本照写").isTrue();

        assertThat(store.updateAnnouncement(g, member, "普通成员不该写进去", d()).rejection())
                .isEqualTo(GuildReject.ANNOUNCEMENT_FORBIDDEN);
        assertThat(store.updateAnnouncement(g, 8409, "外人", d()).rejection()).isEqualTo(GuildReject.ANNOUNCEMENT_FORBIDDEN);
        assertThat(store.updateAnnouncement(7409, officer, "没这个帮", d()).rejection()).isEqualTo(GuildReject.GUILD_GONE);
        assertThat(store.loadGuild(g, d()).orElseThrow().announcement()).isEqualTo("今晚八点集合");
    }

    @Test
    void 退帮踢人解散都带走本人的残留申请() throws SQLException {
        long g = 7411, elsewhere = 7412, leader = 8411, leaver = 8412, kicked = 8413, stayer = 8414;
        db.seedGuild(g, 2, 1, 50, leader, roles(leaver, GuildRoles.MEMBER, kicked, GuildRoles.MEMBER, stayer, GuildRoles.MEMBER));
        db.seedGuild(elsewhere, 2, 50, 8415);

        db.seedApplication(elsewhere, leaver, NOW - 10, NOW + TTL);
        store.leaveGuild(g, leaver, NOW, d()).orThrow();
        assertThat(db.playerApplications(leaver)).isZero();

        db.seedApplication(elsewhere, kicked, NOW - 10, NOW + TTL);
        store.kickMember(g, leader, kicked, NOW, d()).orThrow();
        assertThat(db.playerApplications(kicked)).isZero();

        db.seedApplication(elsewhere, stayer, NOW - 10, NOW + TTL);
        db.seedApplication(elsewhere, leader, NOW - 10, NOW + TTL);
        store.disbandGuild(g, leader, NOW, null, d()).orThrow();
        assertThat(db.playerApplications(stayer)).isZero();
        assertThat(db.playerApplications(leader)).isZero();
    }

    @Test
    void 锁等待封顶_1205归一成写冲突() throws SQLException {
        long g = 7421, leader = 8421, member = 8422;
        db.seedGuild(g, 2, 1, 50, leader, roles(member, GuildRoles.MEMBER));
        Connection blocker = db.begin();
        GuildMysqlFixture.execOn(blocker, "SELECT level FROM guild WHERE guild_id = ? FOR UPDATE", g);

        long start = System.nanoTime();
        TxOutcome<Kicked> out = store.kickMember(g, leader, member, NOW, d());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(out.rejection()).as("1205 必须归一到写冲突，不是内部错误").isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(elapsedMs).as("在 innodb_lock_wait_timeout 内返回").isLessThan(2_500);
        assertThat(recorder.lockWaits).containsExactly("kick");

        GuildMysqlFixture.finish(blocker, false);
        assertThat(store.kickMember(g, leader, member, NOW, d()).isOk()).as("占锁事务结束后同一操作必须成功").isTrue();
    }

    @Test
    void 存储读到的是MySQL真相() throws SQLException {
        long g1 = 7431, g2 = 7432, l1 = 8431, l2 = 8432, player = 8433;
        db.seedGuild(g1, 2, 1, 50, l1, roles(player, GuildRoles.MEMBER));
        assertThat(store.playerGuildId(player, d())).isEqualTo(g1);
        assertThat(store.memberRole(g1, player, d())).hasValue(GuildRoles.MEMBER);

        db.exec("DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?", g1, player);
        assertThat(store.playerGuildId(player, d())).isZero();
        assertThat(store.memberRole(g1, player, d())).isEmpty();
        assertThat(store.loadGuild(g1, d()).orElseThrow().hasMember(player)).isFalse();

        db.seedGuild(g2, 2, 50, l2);
        db.seedMember(g2, player, GuildRoles.MEMBER);
        assertThat(store.playerGuildId(player, d())).isEqualTo(g2);
        assertThat(store.loadGuild(g2, d()).orElseThrow().hasMember(player)).isTrue();
        assertThat(store.loadGuild(7439, d())).isEmpty();
        assertThat(store.guildZoneId(g2, d())).hasValue(2);
        assertThat(store.guildZoneId(7439, d())).isEmpty();
        db.exec("UPDATE guild SET score = -5 WHERE guild_id = ?", g2);
        assertThat(store.guildScore(g2, d())).hasValue(new GuildStore.GuildScore(g2, 2, -5));
        assertThat(store.allGuildScores(d())).containsExactlyInAnyOrder(new GuildStore.GuildScore(g1, 2, 0),
                new GuildStore.GuildScore(g2, 2, -5));
        List<GuildStore.GuildScore> scanned = new ArrayList<>();
        store.scanGuildScores(d(), scanned::add);
        assertThat(scanned).hasSize(2);
    }

    // ================================================================ 并发

    @Test
    void 并发_两帮同时批准同一申请人_恰好一方成功() throws SQLException {
        long g1 = 7441, g2 = 7442, l1 = 8441, l2 = 8442, applicant = 8443;
        int zone = 2;
        db.seedGuild(g1, zone, 50, l1);
        db.seedGuild(g2, zone, 50, l2);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            db.exec("DELETE FROM guild_member WHERE player_id = ?", applicant);
            db.exec("DELETE FROM guild_application WHERE player_id = ?", applicant);
            for (long g : new long[] {g1, g2}) {
                store.applyToGuild(g, applicant, zone, now, RULES, d()).orThrow();
            }
            List<Result> results = concurrently(
                    () -> store.reviewApplication(g1, l1, applicant, true, zone, now, d()),
                    () -> store.reviewApplication(g2, l2, applicant, true, zone, now, d()));
            assertThat(okCount(results)).as("round %d %s", round, results).isEqualTo(1);
            results.forEach(r -> assertRejectIn(r, GuildReject.APPLICATION_NOT_FOUND, GuildReject.WRITE_CONFLICT));
            assertThat(db.memberships(applicant)).as("round %d", round).isEqualTo(1);
            assertThat(db.playerApplications(applicant)).as("round %d：I2 清干净", round).isZero();
        }
    }

    @Test
    void 并发_同一玩家同时申请多帮守住每人上限() throws SQLException {
        long applicant = 8451;
        long[] seeded = {7451, 7452};
        long[] targets = {7453, 7454, 7455};
        int i = 0;
        for (long g : new long[] {7451, 7452, 7453, 7454, 7455}) {
            db.seedGuild(g, 2, 50, 8460 + i++);
        }
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            db.exec("DELETE FROM guild_application WHERE player_id = ?", applicant);
            for (long g : seeded) {
                db.seedApplication(g, applicant, now, now + TTL);
            }
            List<Result> results = concurrently(
                    () -> store.applyToGuild(targets[0], applicant, 0, now, RULES, d()),
                    () -> store.applyToGuild(targets[1], applicant, 0, now, RULES, d()),
                    () -> store.applyToGuild(targets[2], applicant, 0, now, RULES, d()));
            assertThat(okCount(results)).as("round %d：已有 2 条只能再进 1 条 %s", round, results).isEqualTo(1);
            results.forEach(r -> assertRejectIn(r, GuildReject.APPLICATION_LIMIT, GuildReject.WRITE_CONFLICT));
            assertThat(db.liveApplications(applicant, now)).as("round %d", round).isEqualTo(RULES.maxPerPlayer());
        }
    }

    @Test
    void 并发_同时任命两个长老守住上限() throws SQLException {
        long g = 7461, leader = 8461, a = 8462, b = 8463;
        db.seedGuild(g, 2, 1, 50, leader, roles(a, GuildRoles.MEMBER, b, GuildRoles.MEMBER));
        for (int round = 0; round < ROUNDS; round++) {
            db.exec("UPDATE guild_member SET role = ? WHERE guild_id = ? AND player_id IN (?, ?)", GuildRoles.MEMBER, g, a, b);
            List<Result> results = concurrently(
                    () -> store.setMemberRole(g, leader, a, GuildRoles.OFFICER, capOf(1), d()),
                    () -> store.setMemberRole(g, leader, b, GuildRoles.OFFICER, capOf(1), d()));
            assertThat(okCount(results)).as("round %d %s", round, results).isEqualTo(1);
            results.forEach(r -> assertRejectIn(r, GuildReject.OFFICER_LIMIT, GuildReject.WRITE_CONFLICT));
            assertThat(db.officers(g)).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    void 并发_建帮与申请抢同一玩家_I2与I3都成立() throws SQLException {
        long g1 = 7471, l1 = 8471, player = 8472;
        int zone = 2;
        db.seedGuild(g1, zone, 50, l1);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            long founded = 7480 + round;
            db.exec("DELETE FROM guild_member WHERE player_id = ?", player);
            db.exec("DELETE FROM guild_application WHERE player_id = ?", player);
            db.exec("DELETE FROM guild WHERE guild_id = ?", founded);
            GuildData founding = founding(founded, player, "race-guild-" + round, zone, now);
            List<Result> results = concurrently(
                    () -> store.createGuild(founding, d()),
                    () -> store.applyToGuild(g1, player, zone, now, RULES, d()));
            Result create = results.get(0);
            assertRejectIn(results.get(1), GuildReject.ALREADY_IN_GUILD, GuildReject.WRITE_CONFLICT);
            assertRejectIn(create, GuildReject.WRITE_CONFLICT);
            if (!create.ok()) {
                continue;
            }
            assertThat(store.countLiveApplications(g1, now, d())).as("round %d：I2", round).isZero();
            Disbanded res = store.disbandGuild(founded, player, now, null, d()).orThrow();
            assertThat(res.memberIds()).containsExactly(player);
            assertThat(store.listApplicants(g1, now, 50, d())).extracting(ApplicantRow::playerId)
                    .as("round %d：解散之后旧申请不许复活", round).doesNotContain(player);
            assertThat(db.playerApplications(player)).as("round %d：I3 删的是物理行", round).isZero();
        }
        recorder.assertNoDeadlocks("建帮 ‖ 申请");
    }

    // ================================================================ Java 增项

    @Test
    void 大于2的63次方的无符号id全流程() throws SQLException {
        long g = Long.MIN_VALUE + 101;          // 2^63 + 101
        long small = 8501;                     // 小 id 与大 id 混在同一帮：锁序比较必须按无符号
        long leader = Long.MIN_VALUE + 7;      // 2^63 + 7
        long big = -2L;                        // 2^64 - 2
        long other = Long.MIN_VALUE + 202;
        int zone = 0x8000_0001;                // uint32 大值
        assertThat(store.createGuild(founding(g, leader, "unsigned-guild", zone, NOW), d()).orThrow().guildId()).isEqualTo(g);
        assertThat(store.playerGuildId(leader, d())).isEqualTo(g);
        assertThat(db.stateRows(leader)).isEqualTo(1);

        for (long p : new long[] {small, big}) {
            assertThat(store.applyToGuild(g, p, zone, NOW, RULES, d()).orThrow().reviewerIds()).containsExactly(leader);
            assertThat(store.reviewApplication(g, leader, p, true, zone, NOW, d()).isOk()).isTrue();
        }
        GuildData snap = store.loadGuild(g, d()).orElseThrow();
        assertThat(snap.memberIds()).as("成员按无符号升序").containsExactly(small, leader, big);
        assertThat(snap.zoneId()).isEqualTo(zone);
        assertThat(snap.leaderId()).isEqualTo(leader);

        assertThat(store.setMemberRole(g, leader, big, GuildRoles.OFFICER, capOf(2), d()).orThrow().changed()).isTrue();
        assertThat(store.setMemberRole(g, leader, small, GuildRoles.OFFICER, capOf(2), d()).orThrow().changed()).isTrue();
        assertThat(store.kickMember(g, big, small, NOW, d()).rejection()).as("平级").isEqualTo(GuildReject.RANK_TOO_LOW);
        Transferred transferred = store.transferLeader(g, leader, big, capOf(2), d()).orThrow();
        assertThat(transferred.guild().leaderId()).isEqualTo(big);
        assertThat(rolesOf(transferred.guild()).get(leader)).isEqualTo(GuildRoles.OFFICER);
        assertThat(store.kickMember(g, big, small, NOW, d()).isOk()).isTrue();
        assertThat(store.leaveGuild(g, leader, NOW, d()).isOk()).isTrue();

        db.seedGuild(other, zone, 50, 8509);
        db.seedApplication(other, big, NOW, NOW + TTL);
        db.seedApplication(g, Long.MIN_VALUE + 9, NOW, NOW + TTL);
        assertThat(store.listApplicants(g, NOW, 10, d())).extracting(ApplicantRow::playerId).containsExactly(Long.MIN_VALUE + 9);
        Disbanded disbanded = store.disbandGuild(g, big, NOW, null, d()).orThrow();
        assertThat(disbanded.memberIds()).containsExactly(big);
        assertThat(disbanded.zoneId()).isEqualTo(zone);
        assertThat(db.playerApplications(big)).as("I3").isZero();
        assertThat(db.playerApplications(Long.MIN_VALUE + 9)).as("本帮申请").isZero();
        recorder.assertNoDeadlocks("无符号全流程");
    }

    @Test
    void 子预算用完_回写冲突且什么都没写() throws SQLException {
        long g = 7511, leader = 8511, member = 8512;
        db.seedGuild(g, 2, 1, 50, leader, roles(member, GuildRoles.MEMBER));
        db.seedApplication(7519, member, NOW, NOW + TTL);
        hooks.sleepMillis = GuildTxOp.KICK.budgetMillis() + 200; // 钩子之后的快照读撞上子预算检查

        TxOutcome<Kicked> out = store.kickMember(g, leader, member, NOW, d());
        assertThat(out.rejection()).isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(recorder.budgets).containsExactly("kick");
        assertThat(db.memberships(member)).as("删成员行已回滚").isEqualTo(1);
        assertThat(db.playerApplications(member)).as("删申请已回滚").isEqualTo(1);

        hooks.sleepMillis = 0;
        assertThat(store.kickMember(g, leader, member, NOW, d()).isOk()).isTrue();
    }

    @Test
    void 提交时连接被掐断_结果不明回写冲突() throws SQLException {
        long g = 7521, leader = 8521, member = 8522;
        db.seedGuild(g, 2, 1, 50, leader, roles(member, GuildRoles.MEMBER));
        GuildTx tx = db.tx(recorder);
        tx.commitHookForTest(c -> {
            long connectionId;
            try (Statement st = c.createStatement(); var rs = st.executeQuery("SELECT CONNECTION_ID()")) {
                rs.next();
                connectionId = rs.getLong(1);
            }
            db.exec("KILL CONNECTION " + connectionId);
        });
        JdbcGuildStore killing = new JdbcGuildStore(tx, GuildTxHooks.NONE);
        TxOutcome<GuildStore.AnnouncementUpdated> out = killing.updateAnnouncement(g, leader, "lost", d());
        assertThat(out.rejection()).as("提交结果不明 → 稍后重试").isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(store.loadGuild(g, d()).orElseThrow().announcement()).as("连接在 COMMIT 之前断开，服务端回滚").isEmpty();
        assertThat(store.updateAnnouncement(g, leader, "ok", d()).isOk()).as("池里的坏连接不影响后续").isTrue();
    }

    @Test
    void 结构同步幂等_四张表的索引齐全() throws SQLException {
        GuildTables.sync(db.dataSource, java.time.Duration.ofMinutes(1));
        Map<String, List<String>> expected = Map.of(
                "guild", List.of("PRIMARY", "idx_guild_0", "idx_guild_1", "uk_guild"),
                "guild_player_state", List.of("PRIMARY"),
                "guild_member", List.of("PRIMARY", "uk_guild_member"),
                "guild_application", List.of("PRIMARY", "idx_guild_application_0", "idx_guild_application_1"));
        for (Map.Entry<String, List<String>> e : expected.entrySet()) {
            List<String> indexes = new ArrayList<>();
            try (Connection c = db.dataSource.getConnection();
                 var ps = c.prepareStatement("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS"
                         + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME")) {
                ps.setString(1, e.getKey());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        indexes.add(rs.getString(1));
                    }
                }
            }
            assertThat(indexes).as(e.getKey()).containsExactlyInAnyOrderElementsOf(e.getValue());
        }
    }

    @Test
    void 启动检查_测试库版本满足下限_哨兵行已在() throws SQLException {
        String version = db.startup(GuildTxListener.NONE).checkServerVersion(d());
        assertThat(ServerVersion.rejection(version)).as("测试库版本 %s", version).isNull();
        assertThat(db.stateRows(JdbcGuildStore.GLOBAL_INSERT_GUARD_PLAYER_ID)).isEqualTo(1);
    }
}
