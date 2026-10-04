package com.game.guild.store;

import static com.game.guild.store.GuildMysqlFixture.NOW;
import static com.game.guild.store.GuildMysqlFixture.RULES;
import static com.game.guild.store.GuildMysqlFixture.TTL;
import static com.game.guild.store.GuildMysqlFixture.assertRejectIn;
import static com.game.guild.store.GuildMysqlFixture.async;
import static com.game.guild.store.GuildMysqlFixture.concurrently;
import static com.game.guild.store.GuildMysqlFixture.d;
import static com.game.guild.store.GuildMysqlFixture.execOn;
import static com.game.guild.store.GuildMysqlFixture.finish;
import static com.game.guild.store.GuildMysqlFixture.sleep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.store.GuildMysqlFixture.GateHooks;
import com.game.guild.store.GuildMysqlFixture.Recorder;
import com.game.guild.store.GuildMysqlFixture.Result;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 写事务的并发锁序回归（真库，缺省跳过；移植 mmorpg guild_lock_order_mysql_test.go:158-1378 全部场景，guild-spec §11.3「并发」）。
 *
 * <p><b>判据</b>（基线契约 P6）：事务基座的 {@link GuildTxListener#deadlockObserved} 记录为空——1213 会被重跑吸收掉，调用方只看得见
 * 最终成功，只看返回值看不出死锁；再加各用例对业务结局与不变量的断言。锁等待超时（1205）不进这个判据：它说明有人持锁过久，由各用例对
 * 返回值的断言看住（不在允许集合里就红）。并发参与者里只调被测接口、收集结局，断言全在主线程做。
 *
 * <p>与基线的一处编排差异：「解散 ‖ 审批通过，uk 上 p 的后继是解散帮的成员」一例，基线靠一笔 4.5 的未决捐献（guild_asset_op 行）把解散停在
 * 「提前截止」那一步；4.4 还没有那张表，这里用 {@link GateHooks}（同一钩子位置点锁一张测试闸门表的行）停在同一位置，交错完全相同。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class GuildLockOrderMysqlTest {

    /** 无编排的对撞轮数：单轮不撞是运气，几十轮都不撞才说明取锁顺序真的一致。 */
    private static final int ROUNDS = 30;
    /** 带「先卡住一方」编排的轮数（每轮有几百毫秒的有界等待）。 */
    private static final int STAGED_ROUNDS = 10;
    /** 放行第二批参与者后、松开占锁事务前的等待（只影响修复前能否稳定复现，不影响判据）。 */
    private static final long SETTLE_MS = 100;

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

    /** 申请成功时必须是新插入（在 p 的守卫下读不到活行）；否则当成失败带出（并发参与者里不做断言，结局交回主线程）。 */
    private static TxOutcome<GuildStore.Applied> mustInsert(TxOutcome<GuildStore.Applied> out) {
        if (out.isOk() && !out.orThrow().inserted()) {
            throw new IllegalStateException("申请应当新插入一行，却走了刷新分支");
        }
        return out;
    }

    private static GuildData founding(long guildId, long leaderId, String name, int zone, long now) {
        return new GuildData(guildId, name, leaderId, 1, "", now, 30, zone, 0, 0,
                List.of(new GuildData.Member(leaderId, GuildRoles.LEADER, now, now, 0, 0)));
    }

    @Test
    void 批准_拒绝_撤回同一申请人() throws SQLException {
        long g1 = 7601, g2 = 7602, g3 = 7603, l1 = 8601, l2 = 8602, l3 = 8603, applicant = 8609;
        int zone = 2;
        db.seedGuild(g1, zone, 50, l1);
        db.seedGuild(g2, zone, 50, l2);
        db.seedGuild(g3, zone, 50, l3);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            db.exec("DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?", g1, applicant);
            db.exec("DELETE FROM guild_application WHERE player_id = ?", applicant);
            for (long g : new long[] {g1, g2, g3}) {
                db.seedApplication(g, applicant, now, now + TTL);
            }
            List<Result> r = concurrently(
                    () -> store.reviewApplication(g1, l1, applicant, true, zone, now, d()),
                    () -> store.reviewApplication(g2, l2, applicant, false, 0, now, d()),
                    () -> store.cancelApplication(g3, applicant, now, d()));
            assertThat(r.get(0).ok()).as("round %d：批准一方的申请行没人动，必须成功 %s", round, r).isTrue();
            assertRejectIn(r.get(1), GuildReject.APPLICATION_NOT_FOUND);
            assertRejectIn(r.get(2), GuildReject.APPLICATION_NOT_FOUND);
            assertThat(db.roleOf(g1, applicant)).as("round %d", round).isEqualTo(GuildRoles.MEMBER);
            assertThat(db.playerApplications(applicant)).as("round %d：I2", round).isZero();
        }
        recorder.assertNoDeadlocks("Review(G1,p,通过) ‖ Review(G2,p,拒绝) ‖ Cancel(G3,p)");
    }

    @Test
    void 申请_撤回_解散_过期审批四方动申请表() throws SQLException {
        long g1 = 7611, g2 = 7612, g4 = 7614, l1 = 8611, l2 = 8612, l4 = 8614, m = 8615, applicant = 8619, expiredX = 8618;
        int zone = 2;
        db.seedGuild(g1, zone, 50, l1);
        db.seedGuild(g4, zone, 50, l4);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            db.exec("DELETE FROM guild WHERE guild_id = ?", g2);
            db.exec("DELETE FROM guild_member WHERE guild_id = ?", g2);
            for (long p : new long[] {applicant, expiredX, m}) {
                db.exec("DELETE FROM guild_application WHERE player_id = ?", p);
            }
            db.seedGuild(g2, zone, 1, 50, l2, Map.of(m, GuildRoles.MEMBER));
            db.seedApplication(g2, applicant, now, now + TTL);
            db.seedApplication(g4, applicant, now - TTL, now - 1);   // 本人的过期行（申请事务内惰性清理）
            db.seedApplication(g4, m, now, now + TTL);               // 成员的残留申请（解散按 I3 删）
            db.seedApplication(g1, expiredX, now - TTL, now - 1);    // 本帮的过期行（提交后清理或审批过期分支删）

            List<Result> r = concurrently(
                    () -> mustInsert(store.applyToGuild(g1, applicant, zone, now, RULES, d())),
                    () -> store.cancelApplication(g2, applicant, now, d()),
                    () -> store.disbandGuild(g2, l2, now, null, d()),
                    () -> store.reviewApplication(g1, l1, expiredX, false, 0, now, d()));
            assertThat(r.get(0).ok()).as("round %d 申请 %s", round, r).isTrue();
            assertRejectIn(r.get(1), GuildReject.APPLICATION_NOT_FOUND);
            assertThat(r.get(2).ok()).as("round %d 解散 %s", round, r).isTrue();
            assertThat(r.get(3).ok()).as("round %d：过期申请不能被审批成功", round).isFalse();
            assertRejectIn(r.get(3), GuildReject.APPLICATION_NOT_FOUND);

            assertThat(db.guildRows(g2)).isZero();
            assertThat(db.memberships(m)).isZero();
            assertThat(db.application(g1, applicant)).as("round %d：新申请落地", round).isEqualTo(1);
            assertThat(db.application(g2, applicant)).isZero();
            assertThat(db.application(g4, applicant)).as("round %d：本人过期行被惰性清理", round).isZero();
            assertThat(db.playerApplications(m)).as("round %d：I3", round).isZero();
            assertThat(db.playerApplications(expiredX)).as("round %d：本帮过期行被删", round).isZero();
        }
        recorder.assertNoDeadlocks("Apply(p→G1) ‖ Cancel(G2,p) ‖ Disband(G2) ‖ Review(G1,x,过期)");
    }

    @Test
    void 删除标记记录上重插_申请与两次撤回() throws SQLException {
        long g = 7671, leader = 8671, p = 8679;
        int zone = 2;
        db.seedGuild(g, zone, 50, leader);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            db.exec("DELETE FROM guild_application WHERE player_id = ?", p);
            db.seedApplication(g, p, now, now + TTL);
            assertThat(store.cancelApplication(g, p, now, d()).isOk()).as("先撤回一次，留下删除标记记录").isTrue();

            List<Result> r = concurrently(
                    () -> mustInsert(store.applyToGuild(g, p, zone, now, RULES, d())),
                    () -> store.cancelApplication(g, p, now, d()),
                    () -> store.cancelApplication(g, p, now, d()));
            assertThat(r.get(0).ok()).as("round %d 申请 %s", round, r).isTrue();
            assertRejectIn(r.get(1), GuildReject.APPLICATION_NOT_FOUND);
            assertRejectIn(r.get(2), GuildReject.APPLICATION_NOT_FOUND);
            assertThat(db.application(g, p)).isLessThanOrEqualTo(1);
        }
        recorder.assertNoDeadlocks("ApplyToGuild(p→G) ‖ CancelApplication(G,p) ×2");
    }

    @Test
    void 退帮_与别帮批准同一人() throws SQLException {
        long g = 7621, g2 = 7622, g3 = 7623, lg = 8621, l2 = 8622, l3 = 8623, p = 8629;
        int zone = 2;
        db.seedGuild(g, zone, 50, lg);
        db.seedGuild(g2, zone, 50, l2);
        db.seedGuild(g3, zone, 50, l3);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            db.exec("DELETE FROM guild_member WHERE player_id = ?", p);
            db.exec("DELETE FROM guild_application WHERE player_id = ?", p);
            db.seedMember(g, p, GuildRoles.MEMBER);
            db.seedApplication(g2, p, now, now + TTL);
            db.seedApplication(g3, p, now, now + TTL);

            List<Result> r = concurrently(
                    () -> store.leaveGuild(g, p, now, d()),
                    () -> store.reviewApplication(g2, l2, p, true, zone, now, d()));
            assertThat(r.get(0).ok()).as("round %d 退帮 %s", round, r).isTrue();
            assertThat(r.get(1).ok()).as("round %d：残留申请不能被批成第二个帮的成员", round).isFalse();
            assertRejectIn(r.get(1), GuildReject.APPLICATION_NOT_FOUND);
            assertThat(db.memberships(p)).isZero();
            assertThat(db.playerApplications(p)).as("round %d：I3 / 1062 分支都删干净", round).isZero();
        }
        recorder.assertNoDeadlocks("LeaveGuild(G,p) ‖ Review(G2,p,通过)");
    }

    @Test
    void 同名建帮三方_首个因已入帮回滚() throws SQLException {
        long home = 7631, leader = 8631, p1 = 8632, p2 = 8633, p3 = 8634;
        int zone = 2;
        db.seedGuild(home, zone, 1, 50, leader, Map.of(p1, GuildRoles.MEMBER));
        db.seedState(p1, p2, p3);
        for (int round = 0; round < STAGED_ROUNDS; round++) {
            long now = NOW + round;
            String name = "lock-order-race-" + round;
            long gA = 7640 + round * 3L;
            long gB = gA + 1;
            long gC = gA + 2;
            Connection blocker = db.begin();
            List<Result> r;
            try {
                execOn(blocker, "SELECT player_id FROM guild_player_state WHERE player_id = ? FOR UPDATE", p1);
                CompletableFuture<Result> t1 = async(() -> store.createGuild(founding(gA, p1, name, zone, now), d()));
                db.awaitLockWaits(1, 300);
                CompletableFuture<Result> t2 = async(() -> store.createGuild(founding(gB, p2, name, zone, now), d()));
                CompletableFuture<Result> t3 = async(() -> store.createGuild(founding(gC, p3, name, zone, now), d()));
                sleep(SETTLE_MS);
                finish(blocker, false);
                r = List.of(t1.join(), t2.join(), t3.join());
            } finally {
                if (!blocker.isClosed()) {
                    finish(blocker, false);
                }
            }
            assertThat(r.get(0).reject()).as("round %d：p1 已入帮 %s", round, r).isEqualTo(GuildReject.ALREADY_IN_GUILD);
            int winners = 0;
            for (Result x : r.subList(1, 3)) {
                if (x.ok()) {
                    winners++;
                } else {
                    assertThat(x.reject()).as("round %d：输家只能是撞名 %s", round, r).isEqualTo(GuildReject.NAME_TAKEN);
                }
            }
            assertThat(winners).as("round %d：同名恰好成一个 %s", round, r).isEqualTo(1);
            assertThat(db.guildRows(gA)).isZero();
            assertThat(db.memberships(p1)).isEqualTo(1);
            db.exec("DELETE FROM guild_member WHERE guild_id IN (?, ?)", gB, gC);
            db.exec("DELETE FROM guild WHERE guild_id IN (?, ?)", gB, gC);
        }
        recorder.assertNoDeadlocks("同名 CreateGuild 三方（首个因已入帮回滚）");
    }

    @Test
    void 解散_与踢人退帮同帮对撞() throws SQLException {
        long g = 7651, other = 7652, leader = 8651, officer = 8652, m1 = 8653, m2 = 8654, m3 = 8655, outsider = 8656;
        int zone = 2;
        db.seedGuild(other, zone, 50, 8659);
        long[] people = {leader, officer, m1, m2, m3};
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            db.exec("DELETE FROM guild WHERE guild_id = ?", g);
            db.exec("DELETE FROM guild_member WHERE guild_id = ?", g);
            db.exec("DELETE FROM guild_application WHERE player_id = ?", outsider);
            for (long p : people) {
                db.exec("DELETE FROM guild_application WHERE player_id = ?", p);
            }
            db.seedGuild(g, zone, 1, 50, leader, Map.of(officer, GuildRoles.OFFICER, m1, GuildRoles.MEMBER,
                    m2, GuildRoles.MEMBER, m3, GuildRoles.MEMBER));
            for (long p : new long[] {m1, m2, m3}) {
                db.seedApplication(other, p, now, now + TTL);
            }
            db.seedApplication(g, outsider, now, now + TTL);

            List<Result> r = concurrently(
                    () -> store.disbandGuild(g, leader, now, null, d()),
                    () -> store.kickMember(g, leader, m1, now, d()),
                    () -> store.leaveGuild(g, m2, now, d()),
                    () -> store.kickMember(g, officer, m3, now, d()));
            assertThat(r.get(0).ok()).as("round %d 解散 %s", round, r).isTrue();
            for (Result x : r.subList(1, 4)) {
                // 解散先提交：帮会行已经没了（踢人的事务外预读也可能先看到帮会不在）
                assertRejectIn(x, GuildReject.GUILD_GONE);
            }
            assertThat(db.guildRows(g)).isZero();
            for (long p : people) {
                assertThat(db.memberships(p)).as("round %d player %d", round, p).isZero();
                assertThat(db.playerApplications(p)).as("round %d player %d：I3", round, p).isZero();
            }
            assertThat(db.application(g, outsider)).isZero();
        }
        recorder.assertNoDeadlocks("Disband(G) ‖ Kick / Leave(G 的成员)");
    }

    @Test
    void 删成员行的三个事务都先拿玩家守卫() throws SQLException {
        long g = 7661, leader = 8661, p = 8662;
        db.seedGuild(g, 2, 1, 50, leader, Map.of(p, GuildRoles.MEMBER));
        db.seedState(leader, p);
        Connection blocker = db.begin();
        try {
            execOn(blocker, "SELECT player_id FROM guild_player_state WHERE player_id = ? FOR UPDATE", p);
            Map<String, Supplier<TxOutcome<?>>> calls = new java.util.LinkedHashMap<>();
            calls.put("leave", () -> store.leaveGuild(g, p, NOW, d()));
            calls.put("kick", () -> store.kickMember(g, leader, p, NOW, d()));
            calls.put("disband", () -> store.disbandGuild(g, leader, NOW, null, d()));
            for (Map.Entry<String, Supplier<TxOutcome<?>>> call : calls.entrySet()) {
                long start = System.nanoTime();
                TxOutcome<?> out = call.getValue().get();
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                assertThat(out.rejection()).as("%s 必须在 p 的状态行上等锁", call.getKey()).isEqualTo(GuildReject.WRITE_CONFLICT);
                assertThat(elapsed).as("%s 没有等满锁等待上限：它没拿玩家守卫", call.getKey()).isGreaterThanOrEqualTo(900);
                assertThat(db.memberships(p)).isEqualTo(1);
                assertThat(db.guildRows(g)).isEqualTo(1);
            }
        } finally {
            finish(blocker, false);
        }
        assertThat(store.leaveGuild(g, p, NOW, d()).isOk()).as("占锁事务结束后同一操作必须成功").isTrue();
        assertThat(db.memberships(p)).isZero();
    }

    @Test
    void 补建状态行不等已被锁住的已存在行() throws SQLException {
        long held = 8671, missing = 8672;
        db.seedState(held);
        Connection blocker = db.begin();
        try {
            execOn(blocker, "SELECT player_id FROM guild_player_state WHERE player_id = ? FOR UPDATE", held);
            assertThat(store.ensurePlayerStateRows(GuildTxOp.APPLY, NOW, d(), List.of(missing, held, missing)))
                    .as("已存在且被锁住的行不该让建行等待（等了就是 1205 → WRITE_CONFLICT）").isNull();
        } finally {
            finish(blocker, false);
        }
        assertThat(db.count("SELECT COUNT(*) FROM guild_player_state WHERE player_id IN (?, ?)", held, missing))
                .as("缺的行建出来，重复的 id 只建一次").isEqualTo(2);
    }

    @Test
    void 申请删本人过期行_与该行的过期审批() throws SQLException {
        long g1 = 7681, g4 = 7684, l1 = 8681, l4 = 8684, p = 8689;
        int zone = 2;
        db.seedGuild(g1, zone, 50, l1);
        db.seedGuild(g4, zone, 50, l4);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            db.exec("DELETE FROM guild_application WHERE player_id = ?", p);
            db.seedApplication(g4, p, now - TTL, now - 1);
            List<Result> r = concurrently(
                    () -> store.applyToGuild(g1, p, zone, now, RULES, d()),
                    () -> store.reviewApplication(g4, l4, p, false, 0, now, d()));
            assertThat(r.get(0).ok()).as("round %d 申请 %s", round, r).isTrue();
            assertThat(r.get(1).ok()).isFalse();
            assertRejectIn(r.get(1), GuildReject.APPLICATION_NOT_FOUND);
            assertThat(db.application(g4, p)).isZero();
            assertThat(db.application(g1, p)).isEqualTo(1);
        }
        recorder.assertNoDeadlocks("Apply(p→G1) 删本人过期行 (G4,p) ‖ Review(G4,p) 过期分支");
    }

    @Test
    void 建帮清申请_与别帮拒绝和本人撤回() throws SQLException {
        long g2 = 7692, g3 = 7693, l2 = 8692, l3 = 8693, p = 8699;
        int zone = 2;
        db.seedGuild(g2, zone, 50, l2);
        db.seedGuild(g3, zone, 50, l3);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            long founded = 7700 + round;
            db.exec("DELETE FROM guild_application WHERE player_id = ?", p);
            db.seedApplication(g2, p, now, now + TTL);
            db.seedApplication(g3, p, now, now + TTL);
            String name = String.format("lock-order-create-%02d", round);
            List<Result> r = concurrently(
                    () -> store.createGuild(founding(founded, p, name, zone, now), d()),
                    () -> store.reviewApplication(g2, l2, p, false, 0, now, d()),
                    () -> store.cancelApplication(g3, p, now, d()));
            assertThat(r.get(0).ok()).as("round %d 建帮 %s", round, r).isTrue();
            assertRejectIn(r.get(1), GuildReject.APPLICATION_NOT_FOUND);
            assertRejectIn(r.get(2), GuildReject.APPLICATION_NOT_FOUND);
            assertThat(db.playerApplications(p)).as("round %d：I2", round).isZero();
            db.exec("DELETE FROM guild_member WHERE player_id = ?", p);
            db.exec("DELETE FROM guild WHERE guild_id = ?", founded);
        }
        recorder.assertNoDeadlocks("CreateGuild(p) ‖ Review(G2,p,拒绝) ‖ Cancel(G3,p)");
    }

    @Test
    void 退帮与被踢删申请_与别帮拒绝和本人撤回() throws SQLException {
        long g = 7771, g2 = 7772, g3 = 7773, lg = 8771, l2 = 8772, l3 = 8773, leaver = 8778, kicked = 8779;
        int zone = 2;
        db.seedGuild(g, zone, 50, lg);
        db.seedGuild(g2, zone, 50, l2);
        db.seedGuild(g3, zone, 50, l3);
        for (int round = 0; round < ROUNDS; round++) {
            long now = NOW + round;
            for (long p : new long[] {leaver, kicked}) {
                db.exec("DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?", g, p);
                db.exec("DELETE FROM guild_application WHERE player_id = ?", p);
                db.seedMember(g, p, GuildRoles.MEMBER);
                db.seedApplication(g2, p, now, now + TTL);
                db.seedApplication(g3, p, now, now + TTL);
            }
            List<Result> r = concurrently(
                    () -> store.leaveGuild(g, leaver, now, d()),
                    () -> store.kickMember(g, lg, kicked, now, d()),
                    () -> store.reviewApplication(g2, l2, leaver, false, 0, now, d()),
                    () -> store.cancelApplication(g3, leaver, now, d()),
                    () -> store.reviewApplication(g2, l2, kicked, false, 0, now, d()),
                    () -> store.cancelApplication(g3, kicked, now, d()));
            assertThat(r.get(0).ok()).as("round %d 退帮 %s", round, r).isTrue();
            assertThat(r.get(1).ok()).as("round %d 踢人 %s", round, r).isTrue();
            for (Result x : r.subList(2, 6)) {
                assertRejectIn(x, GuildReject.APPLICATION_NOT_FOUND);
            }
            for (long p : new long[] {leaver, kicked}) {
                assertThat(db.memberships(p)).isZero();
                assertThat(db.playerApplications(p)).as("round %d player %d：I3", round, p).isZero();
            }
        }
        recorder.assertNoDeadlocks("Leave / Kick(G,p) 删 p 名下申请 ‖ Review(G2,p,拒绝) ‖ Cancel(G3,p)");
    }

    /**
     * 把两个查重插入者的重复检查对齐到同一时刻（基线 lockOrderStageUniqueInserters）：占锁事务先执行 {@code holds}（删掉 uk 上的目标行，
     * 不提交）→ 同时放行两路 → 等到至少一方锁等待（或等满上限）→ 再给另一方 SETTLE_MS 排进来 → 提交占锁事务。
     */
    private List<Result> stageUniqueInserters(List<Object[]> holds, java.util.concurrent.Callable<TxOutcome<?>> first,
                                              java.util.concurrent.Callable<TxOutcome<?>> second) throws SQLException {
        Connection blocker = db.begin();
        try {
            for (Object[] stmt : holds) {
                execOn(blocker, (String) stmt[0], java.util.Arrays.copyOfRange(stmt, 1, stmt.length));
            }
            CompletableFuture<Result> a = async(first);
            CompletableFuture<Result> b = async(second);
            db.awaitLockWaits(1, 300);
            sleep(SETTLE_MS);
            finish(blocker, true);
            return List.of(a.join(), b.join());
        } finally {
            if (!blocker.isClosed()) {
                finish(blocker, false);
            }
        }
    }

    @Test
    void 唯一键查重插入者_同名建帮_名字刚被解散的帮用过() throws SQLException {
        long p1 = 8701, p2 = 8702;
        int zone = 2;
        db.seedState(p1, p2);
        for (int round = 0; round < STAGED_ROUNDS; round++) {
            long now = NOW + round;
            long gOld = 7710 + round * 10L;
            String name = String.format("lock-order-same-%02d", round);
            db.seedNamedGuild(gOld, 8709, name, zone);
            List<Result> r = stageUniqueInserters(
                    List.<Object[]>of(new Object[] {"DELETE FROM guild WHERE guild_id = ?", gOld}),
                    () -> store.createGuild(founding(gOld - 1, p1, name, zone, now), d()),
                    () -> store.createGuild(founding(gOld + 1, p2, name, zone, now), d()));
            int winners = 0;
            for (Result x : r) {
                if (x.ok()) {
                    winners++;
                } else {
                    assertThat(x.reject()).as("round %d：输家只能是撞名 %s", round, r).isEqualTo(GuildReject.NAME_TAKEN);
                }
            }
            assertThat(winners).as("round %d %s", round, r).isEqualTo(1);
            assertThat(db.guildRows(gOld - 1) + db.guildRows(gOld + 1)).isEqualTo(1);
            db.exec("DELETE FROM guild_member WHERE player_id IN (?, ?)", p1, p2);
            db.exec("DELETE FROM guild WHERE guild_id IN (?, ?)", gOld - 1, gOld + 1);
        }
        recorder.assertNoDeadlocks("同名建帮 ×2（名字刚被解散的帮用过）");
    }

    @Test
    void 唯一键查重插入者_uk_guild上相邻的两个名字() throws SQLException {
        long p1 = 8721, p2 = 8722;
        int zone = 2;
        db.seedState(p1, p2);
        for (int round = 0; round < STAGED_ROUNDS; round++) {
            long now = NOW + round;
            long gA = 7810 + round * 10L;
            long gB = gA + 5;
            String nameA = String.format("lock-order-adj-%02d-a", round);
            String nameB = String.format("lock-order-adj-%02d-b", round);
            db.seedNamedGuild(gA, 8729, nameA, zone);
            db.seedNamedGuild(gB, 8728, nameB, zone);
            List<Result> r = stageUniqueInserters(
                    List.<Object[]>of(new Object[] {"DELETE FROM guild WHERE guild_id = ?", gA},
                            new Object[] {"DELETE FROM guild WHERE guild_id = ?", gB}),
                    () -> store.createGuild(founding(gA + 1, p1, nameA, zone, now), d()),
                    () -> store.createGuild(founding(gB - 1, p2, nameB, zone, now), d()));
            assertThat(r.get(0).ok()).as("round %d：建 'a' %s", round, r).isTrue();
            assertThat(r.get(1).ok()).as("round %d：建 'b' %s", round, r).isTrue();
            db.exec("DELETE FROM guild_member WHERE player_id IN (?, ?)", p1, p2);
            db.exec("DELETE FROM guild WHERE guild_id IN (?, ?)", gA + 1, gB - 1);
        }
        recorder.assertNoDeadlocks("建帮 'a' ‖ 建帮 'b'（两名在 uk_guild 上相邻、都刚被解散）");
    }

    @Test
    void 唯一键查重插入者_uk_guild_member上相邻的两个刚离帮玩家() throws SQLException {
        long p1 = 8750, p2 = 8751;
        int zone = 2;
        db.seedState(p1, p2);
        for (int round = 0; round < STAGED_ROUNDS; round++) {
            long now = NOW + round;
            long base = 7900 + round * 10L;
            long gLo = base + 1, gOld = base + 5, gHi = base + 9;
            long leaderLo = 8600 + round * 2L, leaderOld = 8601 + round * 2L;
            db.seedGuild(gLo, zone, 50, leaderLo);
            db.seedGuild(gOld, zone, 1, 50, leaderOld, Map.of(p1, GuildRoles.MEMBER, p2, GuildRoles.MEMBER));
            db.seedApplication(gLo, p2, now, now + TTL);
            String name = String.format("lock-order-member-%02d", round);
            List<Result> r = stageUniqueInserters(
                    List.<Object[]>of(new Object[] {"DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?", gOld, p1},
                            new Object[] {"DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?", gOld, p2}),
                    () -> store.createGuild(founding(gHi, p1, name, zone, now), d()),
                    () -> store.reviewApplication(gLo, leaderLo, p2, true, zone, now, d()));
            assertThat(r.get(0).ok()).as("round %d：p1 建帮 %s", round, r).isTrue();
            assertThat(r.get(1).ok()).as("round %d：gLo 批准 p2 %s", round, r).isTrue();
            assertThat(db.roleOf(gHi, p1)).isEqualTo(GuildRoles.LEADER);
            assertThat(db.roleOf(gLo, p2)).isEqualTo(GuildRoles.MEMBER);
            assertThat(db.playerApplications(p2)).isZero();
            db.exec("DELETE FROM guild_member WHERE player_id IN (?, ?)", p1, p2);
            db.exec("DELETE FROM guild_application WHERE player_id = ?", p2);
            db.exec("DELETE FROM guild WHERE guild_id = ?", gHi);
        }
        recorder.assertNoDeadlocks("建帮(p1) ‖ 审批通过(p2)，两人在 uk_guild_member 上相邻、都刚离帮");
    }

    @Test
    void 补建状态行_首插者回滚() throws SQLException {
        long player = 8674;
        Connection first = db.begin();
        try {
            execOn(first, "INSERT INTO guild_player_state (player_id, updated_ms) VALUES (?, ?)", player, NOW);
            CompletableFuture<Result> b = async(() -> TxOutcome.ok(ensure(player)));
            CompletableFuture<Result> c = async(() -> TxOutcome.ok(ensure(player)));
            db.awaitLockWaits(2, 500);
            finish(first, false);
            Result rb = b.join();
            Result rc = c.join();
            assertThat(rb.error()).as("首插者回滚后补建者 B 必须成功").isNull();
            assertThat(rc.error()).as("首插者回滚后补建者 C 必须成功").isNull();
        } finally {
            if (!first.isClosed()) {
                finish(first, false);
            }
        }
        assertThat(db.stateRows(player)).isEqualTo(1);
        recorder.assertNoDeadlocks("建状态行：首插者回滚（补建者在全局插入守卫下串行）");
    }

    private GuildReject ensure(long player) {
        GuildReject reject = store.ensurePlayerStateRows(GuildTxOp.APPLY, NOW, d(), List.of(player));
        if (reject != null) {
            throw new AssertionError("补建状态行被拒: " + reject);
        }
        return null;
    }

    // ================================================================ 全局插入守卫（哨兵行）

    private long guardUpdatedMs() throws SQLException {
        return db.queryU64("SELECT updated_ms FROM guild_player_state WHERE player_id = 0");
    }

    @Test
    void 哨兵行_幂等_被持有时不排队_并发首建恰好一行() throws SQLException {
        GuildStartupChecks startup = db.startup(recorder);
        assertThat(db.stateRows(0)).as("建表之后必须已有哨兵行").isEqualTo(1);
        assertThat(guardUpdatedMs()).isEqualTo(NOW);
        startup.ensureGlobalInsertGuard(NOW + 1, d());
        startup.ensureGlobalInsertGuard(NOW + 2, d());
        assertThat(db.stateRows(0)).isEqualTo(1);
        assertThat(guardUpdatedMs()).as("行已在时不改写").isEqualTo(NOW);

        Connection blocker = db.begin();
        try {
            execOn(blocker, "SELECT player_id FROM guild_player_state WHERE player_id = 0 FOR UPDATE");
            long start = System.nanoTime();
            startup.ensureGlobalInsertGuard(NOW + 3, d());
            assertThat((System.nanoTime() - start) / 1_000_000).as("哨兵正被持有时，启动步骤不该排在锁后面").isLessThan(900);
        } finally {
            finish(blocker, false);
        }

        db.exec("DELETE FROM guild_player_state WHERE player_id = 0");
        List<Result> r = concurrently(
                () -> TxOutcome.ok(guard(startup, NOW + 10)),
                () -> TxOutcome.ok(guard(startup, NOW + 11)),
                () -> TxOutcome.ok(guard(startup, NOW + 12)),
                () -> TxOutcome.ok(guard(startup, NOW + 13)));
        for (Result x : r) {
            assertThat(x.error()).as("并发首建 %s", r).isNull();
        }
        assertThat(db.stateRows(0)).as("并发首建恰好一行").isEqualTo(1);
        recorder.assertNoDeadlocks("ensureGlobalInsertGuard 并发首建");
    }

    private static Void guard(GuildStartupChecks startup, long now) {
        startup.ensureGlobalInsertGuard(now, d());
        return null;
    }

    @Test
    void 哨兵行_等命名锁超过连接URL的socketTimeout也不断连_持锁者建好后照常启动() throws Exception {
        db.exec("DELETE FROM guild_player_state WHERE player_id = 0");
        com.alibaba.druid.pool.DruidDataSource shortSocket = new com.alibaba.druid.pool.DruidDataSource();
        shortSocket.setUrl(GuildMysqlFixture.BASE_URL + "/" + db.database + GuildMysqlFixture.PARAMS + "&socketTimeout=1000");
        shortSocket.setUsername(GuildMysqlFixture.USER);
        shortSocket.setPassword(GuildMysqlFixture.PASSWORD);
        // 持锁方用独立的非池化连接：关连接即结束会话、命名锁随之释放，失败也不会把锁留给后面的用例
        try (Connection holder = java.sql.DriverManager.getConnection(
                GuildMysqlFixture.BASE_URL + "/" + db.database + GuildMysqlFixture.PARAMS, GuildMysqlFixture.USER,
                GuildMysqlFixture.PASSWORD)) {
            try (var ps = holder.prepareStatement("SELECT GET_LOCK(?, 5)")) {
                ps.setString(1, GuildStartupChecks.INSERT_GUARD_INIT_LOCK_NAME);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next() && rs.getLong(1) == 1).as("持锁方先拿到命名锁").isTrue();
                }
            }
            // 持锁 1.5 s（超过 socketTimeout=1000）后建好哨兵行再放锁：等锁的一方应在拿到锁后复读、照常成功
            CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
                sleep(1_500);
                try {
                    execOn(holder, "INSERT IGNORE INTO guild_player_state (player_id, updated_ms) VALUES (0, ?)", NOW + 30);
                    execOn(holder, "SELECT RELEASE_LOCK(?)", GuildStartupChecks.INSERT_GUARD_INIT_LOCK_NAME);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            GuildStartupChecks startup = new GuildStartupChecks(new GuildTx(shortSocket::getConnection, 10, recorder));
            Throwable failure = null;
            try {
                startup.ensureGlobalInsertGuard(NOW + 31, d());
            } catch (RuntimeException e) {
                failure = e;
            }
            assertThatCode(release::join).as("持锁方建行与放锁").doesNotThrowAnyException();
            assertThat(failure).as("等锁方").isNull();
        } finally {
            shortSocket.close();
        }
        assertThat(db.stateRows(0)).isEqualTo(1);
        assertThat(guardUpdatedMs()).as("持锁者建的那一行").isEqualTo(NOW + 30);
    }

    @Test
    void 哨兵行_启动期首插者回滚() throws SQLException {
        GuildStartupChecks startup = db.startup(recorder);
        db.exec("DELETE FROM guild_player_state WHERE player_id = 0");
        Connection first = db.begin();
        try {
            execOn(first, "INSERT INTO guild_player_state (player_id, updated_ms) VALUES (0, ?)", NOW);
            CompletableFuture<Result> b = async(() -> TxOutcome.ok(guard(startup, NOW + 1)));
            CompletableFuture<Result> c = async(() -> TxOutcome.ok(guard(startup, NOW + 2)));
            db.awaitLockWaits(1, 500);
            sleep(SETTLE_MS);
            finish(first, false);
            assertThat(b.join().error()).as("首插者回滚后 B 必须成功").isNull();
            assertThat(c.join().error()).as("首插者回滚后 C 必须成功").isNull();
        } finally {
            if (!first.isClosed()) {
                finish(first, false);
            }
        }
        assertThat(db.stateRows(0)).isEqualTo(1);
        recorder.assertNoDeadlocks("启动期建哨兵：首插者回滚（建哨兵者在命名锁下串行）");
    }

    @Test
    void 解散_与审批通过_uk上p的后继是解散帮的成员() throws Exception {
        long g = 7671, g2 = 7672, gOld = 7673, lg = 8671, l2 = 8672, lOld = 8673, p = 8790, q = 8791;
        int zone = 2;
        db.seedGuild(g, zone, 1, 50, lg, Map.of(q, GuildRoles.MEMBER));
        db.seedGuild(g2, zone, 50, l2);
        db.seedGuild(gOld, zone, 1, 50, lOld, Map.of(p, GuildRoles.MEMBER));
        db.seedState(lg, q, p);
        db.seedApplication(g, p, NOW, NOW + TTL);
        db.seedApplication(g2, p, NOW, NOW + TTL);

        // 一个 RR 事务先做一次一致性读建出读视图，挡住 purge：否则 (p,gOld) 的删除标记项随时可能被清掉，查重根本不扫 (q,G)
        Connection reader = db.dataSource.getConnection();
        reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        reader.setAutoCommit(false);
        Connection blocker = null;
        try {
            execOn(reader, "SELECT COUNT(*) FROM guild_member");
            db.exec("DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?", gOld, p);

            // 占锁事务持有闸门行：解散会停在「提前截止」（删成员与删申请之后）
            blocker = db.begin();
            execOn(blocker, "SELECT id FROM " + GuildMysqlFixture.GATE_TABLE + " WHERE id = 1 FOR UPDATE");
            hooks.blockOn = g;

            CompletableFuture<Result> disband = async(() -> store.disbandGuild(g, lg, NOW, null, d()));
            db.awaitLockWaits(1, 300);
            CompletableFuture<Result> approve = async(() -> store.reviewApplication(g2, l2, p, true, zone, NOW, d()));
            db.awaitLockWaits(2, 300);
            sleep(SETTLE_MS);
            finish(blocker, true);

            assertThat(disband.join().ok()).as("解散 %s", disband.join()).isTrue();
            assertThat(approve.join().ok()).as("G2 批准 p %s", approve.join()).isTrue();
        } finally {
            hooks.blockOn = 0;
            if (blocker != null && !blocker.isClosed()) {
                finish(blocker, false);
            }
            finish(reader, false);
        }
        assertThat(db.guildRows(g)).isZero();
        assertThat(db.memberships(q)).isZero();
        assertThat(db.roleOf(g2, p)).isEqualTo(GuildRoles.MEMBER);
        assertThat(db.playerApplications(p)).as("I2 / I3：p 名下申请清干净").isZero();
        recorder.assertNoDeadlocks("Disband(G) ‖ Review(G2,p,通过)，uk 上 p 的后继是 G 的成员");
    }

    @Test
    void 哨兵行缺失_取守卫的路径一律fail_closed_不取的照常() throws SQLException {
        long g = 7951, gNew = 7952, leader = 8951, founder = 8952, applicant = 8953, rejectee = 8954, fresh = 8955;
        int zone = 2;
        db.seedGuild(g, zone, 50, leader);
        db.seedState(founder, applicant);
        db.seedApplication(g, applicant, NOW, NOW + TTL);
        db.seedApplication(g, rejectee, NOW, NOW + TTL);
        db.exec("DELETE FROM guild_player_state WHERE player_id = 0");

        GuildData founding = founding(gNew, founder, "guard-missing", zone, NOW);
        assertGuardMissing(() -> store.createGuild(founding, d()));
        assertThat(db.guildRows(gNew)).isZero();
        assertThat(db.memberships(founder)).isZero();

        assertGuardMissing(() -> store.reviewApplication(g, leader, applicant, true, zone, NOW, d()));
        assertThat(db.memberships(applicant)).isZero();
        assertThat(db.application(g, applicant)).as("审批通过被拒时申请保留").isEqualTo(1);

        assertGuardMissing(() -> store.ensurePlayerStateRows(GuildTxOp.APPLY, NOW, d(), List.of(fresh)));
        assertGuardMissing(() -> store.applyToGuild(g, fresh, zone, NOW, RULES, d()));
        assertThat(db.stateRows(fresh)).as("请求路径上绝不补建").isZero();
        assertThat(db.playerApplications(fresh)).isZero();

        assertThat(store.reviewApplication(g, leader, rejectee, false, 0, NOW, d()).isOk()).as("拒绝分支不取守卫").isTrue();
        assertThat(db.application(g, rejectee)).isZero();

        db.startup(recorder).ensureGlobalInsertGuard(NOW, d());
        assertThat(store.createGuild(founding, d()).isOk()).isTrue();
        assertThat(store.reviewApplication(g, leader, applicant, true, zone, NOW, d()).isOk()).isTrue();
        assertThat(store.applyToGuild(g, fresh, zone, NOW, RULES, d()).isOk()).isTrue();
    }

    private static void assertGuardMissing(Runnable call) {
        assertThatThrownBy(call::run).as("哨兵缺失是部署问题，不是忙").isInstanceOf(GuildStoreException.class)
                .extracting(e -> ((GuildStoreException) e).kind())
                .isEqualTo(GuildStoreException.Kind.GLOBAL_INSERT_GUARD_MISSING);
    }

    @Test
    void 只有唯一键查重插入者取全局守卫() throws SQLException {
        long g = 7961, gNew = 7962, leader = 8961, member = 8962, applicant = 8963, rejectee = 8964, applier = 8965,
                founder = 8966, fresh = 8967;
        int zone = 2;
        db.seedGuild(g, zone, 1, 50, leader, Map.of(member, GuildRoles.MEMBER));
        db.seedState(leader, member, applicant, applier, founder);
        db.seedApplication(g, applicant, NOW, NOW + TTL);
        db.seedApplication(g, rejectee, NOW, NOW + TTL);

        Connection blocker = db.begin();
        try {
            execOn(blocker, "SELECT player_id FROM guild_player_state WHERE player_id = 0 FOR UPDATE");
            Map<String, Supplier<GuildReject>> blocked = new java.util.LinkedHashMap<>();
            blocked.put("createGuild", () -> store.createGuild(founding(gNew, founder, "guard-holder", zone, NOW), d()).rejection());
            blocked.put("review(通过)", () -> store.reviewApplication(g, leader, applicant, true, zone, NOW, d()).rejection());
            blocked.put("ensurePlayerStateRows(缺行)",
                    () -> store.ensurePlayerStateRows(GuildTxOp.APPLY, NOW, d(), List.of(fresh)));
            for (Map.Entry<String, Supplier<GuildReject>> call : blocked.entrySet()) {
                long start = System.nanoTime();
                GuildReject reject = call.getValue().get();
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                assertThat(reject).as("%s 必须在哨兵行上等锁", call.getKey()).isEqualTo(GuildReject.WRITE_CONFLICT);
                assertThat(elapsed).as("%s 没有等满锁等待上限：它没取全局插入守卫", call.getKey()).isGreaterThanOrEqualTo(900);
            }
            assertThat(db.guildRows(gNew)).isZero();
            assertThat(db.memberships(founder)).isZero();
            assertThat(db.memberships(applicant)).isZero();
            assertThat(db.application(g, applicant)).isEqualTo(1);
            assertThat(db.stateRows(fresh)).isZero();

            // 不取守卫的路径：拒绝先做（腾出队列名额，RULES.maxPerGuild = 2），再申请、退帮
            assertThat(store.reviewApplication(g, leader, rejectee, false, 0, NOW, d()).isOk()).isTrue();
            assertThat(store.applyToGuild(g, applier, zone, NOW, RULES, d()).orThrow().inserted()).isTrue();
            assertThat(store.leaveGuild(g, member, NOW, d()).isOk()).isTrue();

            // G4：踢非成员 / 批准不存在的申请，事务外预读就答复，不建状态行、不取守卫（取了就会等满 1 s）
            long stranger = 8968, outsider = 8969, noSuchGuild = 7963;
            assertThat(store.kickMember(g, leader, stranger, NOW, d()).rejection()).isEqualTo(GuildReject.TARGET_NOT_MEMBER);
            assertThat(store.kickMember(g, outsider, stranger, NOW, d()).rejection()).as("操作者不在的优先级高于目标不在")
                    .isEqualTo(GuildReject.NOT_MEMBER);
            assertThat(store.kickMember(noSuchGuild, leader, stranger, NOW, d()).rejection()).as("帮会不在的优先级最高")
                    .isEqualTo(GuildReject.GUILD_GONE);
            assertThat(store.reviewApplication(g, leader, stranger, true, zone, NOW, d()).rejection())
                    .isEqualTo(GuildReject.APPLICATION_NOT_FOUND);
            assertThat(store.reviewApplication(g, outsider, stranger, true, zone, NOW, d()).rejection())
                    .isEqualTo(GuildReject.NOT_MEMBER);
            assertThat(store.reviewApplication(g, applier, stranger, true, zone, NOW, d()).rejection()).as("申请人不是成员")
                    .isEqualTo(GuildReject.NOT_MEMBER);
            assertThat(db.stateRows(stranger)).as("不给不存在的目标 / 申请人建状态行").isZero();
        } finally {
            finish(blocker, false);
        }
        assertThat(store.createGuild(founding(gNew, founder, "guard-holder", zone, NOW), d()).isOk()).isTrue();
        assertThat(store.reviewApplication(g, leader, applicant, true, zone, NOW, d()).isOk()).isTrue();
    }
}
