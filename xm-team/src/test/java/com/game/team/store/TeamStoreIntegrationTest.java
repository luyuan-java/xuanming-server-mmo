package com.game.team.store;

import static com.game.team.store.TeamRedisFixture.ZONE;
import static com.game.team.store.TeamRedisFixture.u;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamRedisFields;
import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamInviteRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Decision;
import com.game.team.rules.InviteAdd;
import com.game.team.rules.Op;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.TeamLimits;
import com.game.team.rules.TeamRules;
import com.game.team.rules.TeamTips;
import com.game.team.store.TeamReplies.CommitOutcome;
import com.game.team.store.TeamReplies.CommitStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;

/**
 * 组队存储层在真 Redis 上的行为，逐个对应基线 go/match/internal/team/store_test.go（设计稿 team-system.md §I.3 #6-#11、#19-#22；
 * team-spec §10.2）。开战锁钉版本提交与 EndMatch 的用例（TestSelfHealing 第 4 个子用例）随批次 6.4 补。
 *
 * <p>默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}（DB 12，随机 ≥ 2^63 的 id，只删自己的键）。
 * 基线用 miniredis 钉死 TIME，这里改成「调用前后各读一次 Redis TIME」夹住断言；需要时钟前进的地方改写截止或删键来模拟，逐条注明。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class TeamStoreIntegrationTest {

    private static final RuleConfig CFG = RuleConfig.DEFAULT;
    private static final long IDLE_TTL = TeamStore.IDLE_TTL_SECONDS;

    private static RedissonClient redis;
    private final AtomicReference<Consumer<Bind>> afterRead = new AtomicReference<>(b -> {
    });
    private TeamRedisFixture fx;

    @BeforeAll
    static void connect() {
        redis = TeamRedisFixture.connect();
    }

    @AfterAll
    static void shutdown() {
        redis.shutdown();
    }

    @BeforeEach
    void setUp() {
        fx = new TeamRedisFixture(redis, new TeamStore.Hooks() {
            @Override
            public void afterRead(Bind bind) {
                afterRead.get().accept(bind);
            }
        });
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    private static String rec(long tid) {
        return RedisKeys.teamRecord(tid);
    }

    private static String info(long tid) {
        return RedisKeys.teamInfo(tid);
    }

    private static String idx(long pid) {
        return RedisKeys.teamPlayer(pid);
    }

    private static String inv(long pid) {
        return RedisKeys.teamInvite(pid);
    }

    private static void assertIdleTtl(long ttlSeconds, String key) {
        assertThat(ttlSeconds).as("TTL of %s", key).isBetween(IDLE_TTL - 2, IDLE_TTL);
    }

    // ================================================================ #6 TestCommitRejectBranchesWriteNothing（store_test.go:90）

    @Test
    void 提交脚本的拒绝分支不产生任何写入() {
        long tidA = fx.tid(1001), tidB = fx.tid(1002);
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3), p5 = fx.pid(5), p6 = fx.pid(6), p9 = fx.pid(9);
        fx.mustCreate(p1, tidA);
        fx.mustJoin(p1, tidA, p3);
        fx.mustCreate(p2, tidB);
        long now = fx.nowMs();
        TeamRecord recA = fx.loadRecord(tidA);
        String verA = fx.ver(tidA);

        // 版本冲突
        Decision d = TeamRules.apply(Op.apply(p6, ZONE), recA, now, null, CFG);
        TeamRedisFixture.KeyState before = fx.state();
        assertThat(fx.store.commit(tidA, "99", d, now, recA, fx.deadline()).status()).isEqualTo(CommitStatus.CONFLICT);
        fx.assertUnchanged(before);

        // 非建队操作遇到记录不存在一律冲突，绝不复活已删除的记录
        long ghost = fx.tid(7);
        TeamRecord ghostRec = recA.toBuilder().setTeamId(ghost).build();
        d = TeamRules.apply(Op.apply(p6, ZONE), ghostRec, now, null, CFG);
        before = fx.state();
        assertThat(fx.store.commit(ghost, "1", d, now, ghostRec, fx.deadline()).status()).isEqualTo(CommitStatus.CONFLICT);
        fx.assertUnchanged(before);
        assertThat(fx.exists(rec(ghost))).isFalse();

        // 建队哨兵遇到已存在记录
        d = TeamRules.apply(Op.create(p9, tidA, ZONE), null, now, null, CFG);
        before = fx.state();
        assertThat(fx.store.commit(tidA, TeamStore.NEW_TEAM_VERSION, d, now, null, fx.deadline()).status())
                .isEqualTo(CommitStatus.CONFLICT);
        fx.assertUnchanged(before);

        // {-1} 新成员已在别队
        TeamRecord recWithApp = recA.toBuilder().addApplications(TeamApplicationRecord.newBuilder().setPlayerId(p2)
                .setZoneId(ZONE).setAppliedAtMs(now).setExpireAtMs(now + TeamLimits.APPLICATION_TTL_MS)).build();
        d = TeamRules.apply(Op.handleApplication(p1, p2, true), recWithApp, now, null, CFG);
        assertThat(d.joined()).containsExactly(p2);
        before = fx.state();
        CommitOutcome out = fx.store.commit(tidA, verA, d, now, recWithApp, fx.deadline());
        assertThat(out.status()).isEqualTo(CommitStatus.MEMBER_IN_TEAM);
        assertThat(out.index()).isEqualTo(1);
        fx.assertUnchanged(before);

        // {-1} 经 mutate 回 4003[新成员]：申请挂着的同时自己建了队
        fx.mustCommit(Bind.target(p5, tidA), Op.apply(p5, ZONE));
        fx.mustCreate(p5, fx.tid(1005));
        before = fx.state();
        MutateResult res = fx.mutate(Bind.caller(p1, tidA), Op.handleApplication(p1, p5, true));
        assertThat(res.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(res.code()).isEqualTo(TeamTips.MEMBER_IN_TEAM);
        assertThat(res.param()).isEqualTo(p5);
        assertThat(res.decision().code()).as("Lua 拒绝不是规则拒绝：服务层改用自由读").isZero();
        fx.assertUnchanged(before);

        // {-2} 保留成员索引指向别队
        TeamRecord rec = fx.loadRecord(tidA);
        String ver = fx.ver(tidA);
        fx.hset(idx(p3), TeamRedisFields.TID, u(fx.tid(555)));
        d = TeamRules.apply(Op.apply(p6, ZONE), rec, now, null, CFG);
        assertThat(d.kept()).containsExactly(p1, p3);
        before = fx.state();
        out = fx.store.commit(tidA, ver, d, now, rec, fx.deadline());
        assertThat(out.status()).isEqualTo(CommitStatus.INDEX_MISMATCH);
        assertThat(out.index()).isEqualTo(2);
        fx.assertUnchanged(before);
        fx.hset(idx(p3), TeamRedisFields.TID, u(tidA));
    }

    // ================================================================ #7 TestEpochSemantics（store_test.go:165）

    @Test
    void epoch_只在tid变化时变_缺失时按Redis时钟起种_十四位不丢精度() {
        long tidA = fx.tid(2001), tidB = fx.tid(2002);
        long p1 = fx.pid(1), p3 = fx.pid(3), p7 = fx.pid(7);

        long t0 = fx.nowMs();
        CommitResult created = fx.mustCreate(p1, tidA);
        long t1 = fx.nowMs();
        IndexEntry e1 = created.indexes().get(p1);
        assertThat(e1.teamId()).isEqualTo(tidA);
        assertThat(e1.epoch()).as("索引缺失时按 S_COMMIT 的 Redis TIME 毫秒数 +1 起种").isBetween(t0 + 1, t1 + 1);
        assertThat(fx.hget(idx(p1), TeamRedisFields.EPOCH)).as("13 位毫秒不丢精度").isEqualTo(u(e1.epoch()));
        assertThat(created.version()).isEqualTo(1);

        CommitResult applied = fx.mustCommit(Bind.target(p3, tidA), Op.apply(p3, ZONE));
        assertThat(applied.indexes().get(p1)).as("tid 不变 epoch 不变").isEqualTo(e1);
        assertThat(applied.version()).isEqualTo(2);
        t0 = fx.nowMs();
        CommitResult joined = fx.mustCommit(Bind.caller(p1, tidA), Op.handleApplication(p1, p3, true));
        t1 = fx.nowMs();
        IndexEntry e3 = joined.indexes().get(p3);
        assertThat(e3.teamId()).isEqualTo(tidA);
        assertThat(e3.epoch()).isBetween(t0 + 1, t1 + 1);
        assertThat(joined.version()).as("每次提交严格 +1").isEqualTo(3);

        CommitResult kicked = fx.mustCommit(Bind.caller(p1, tidA), Op.kick(p1, p3));
        assertThat(kicked.indexes().get(p3)).as("已存在时 +1").isEqualTo(new IndexEntry(0, e3.epoch() + 1));
        assertThat(fx.exists(idx(p3))).as("离队不删索引").isTrue();
        assertThat(fx.hget(idx(p3), TeamRedisFields.TID)).isEqualTo("0");

        CommitResult recreated = fx.mustCreate(p3, tidB);
        assertThat(recreated.indexes().get(p3)).isEqualTo(new IndexEntry(tidB, e3.epoch() + 2));

        // 14 位 epoch：string.format("%.0f") 精确写回
        fx.hset(idx(p3), TeamRedisFields.EPOCH, "12345678901234");
        CommitResult left = fx.mustCommit(Bind.caller(p3, tidB), Op.leave(p3));
        assertThat(left.decision().disbanded()).isTrue();
        assertThat(left.indexes().get(p3)).isEqualTo(new IndexEntry(0, 12_345_678_901_235L));
        assertThat(fx.hget(idx(p3), TeamRedisFields.EPOCH)).isEqualTo("12345678901235");

        // 保留成员索引被删：下一次提交用新时钟重新起种并重建
        fx.del(idx(p1));
        t0 = fx.nowMs();
        CommitResult rebuilt = fx.mustCommit(Bind.target(p7, tidA), Op.apply(p7, ZONE));
        t1 = fx.nowMs();
        assertThat(rebuilt.indexes().get(p1).teamId()).isEqualTo(tidA);
        assertThat(rebuilt.indexes().get(p1).epoch()).isBetween(t0 + 1, t1 + 1);
        assertThat(fx.hget(idx(p1), TeamRedisFields.TID)).isEqualTo(u(tidA));
    }

    // ================================================================ #7 补充 TestMissingIndexEpochIsMonotonic（store_test.go:214）

    /** 客户端快照接受条件（team-id 冲突判定另算；store_test.go:209-212）。 */
    private static boolean clientAccepts(long curEpoch, long curVersion, long inEpoch, long inVersion) {
        return Long.compareUnsigned(inEpoch, curEpoch) > 0
                || inEpoch == curEpoch && Long.compareUnsigned(inVersion, curVersion) >= 0;
    }

    @Test
    void 索引缺失时读路径回报的epoch不回退() {
        long tid = fx.tid(2101);
        long p1 = fx.pid(1), p3 = fx.pid(3), p9 = fx.pid(9);

        // 缺失索引的读回报 nowMs，随后起种严格更大（同一毫秒也成立：起种是 nowMs+1）
        FreeRead fresh = fx.store.readFree(p1, fx.deadline());
        assertThat(fresh.ok()).isTrue();
        assertThat(fresh.snapshot().playerTeamId()).isZero();
        assertThat(fresh.snapshot().playerEpoch()).as("索引缺失回报 Redis nowMs").isEqualTo(fresh.snapshot().nowMs());
        assertThat(fx.exists(idx(p1))).as("读路径不写索引").isFalse();
        CommitResult created = fx.mustCreate(p1, tid);
        assertThat(Long.compareUnsigned(created.indexes().get(p1).epoch(), fresh.snapshot().playerEpoch())).isPositive();

        // 整队 24h 空闲过期：记录、投影、全员索引同批消失。真 Redis 拨不动时钟：删键，并等 Redis 时钟走过最后发出的 epoch
        // （真实过期至少隔 24h）。之后的空视图必须能被客户端接受。
        CommitResult joined = fx.mustJoin(p1, tid, p3);
        fx.del(rec(tid), info(tid), idx(p1), idx(p3));
        fx.waitRedisAfter(Math.max(joined.indexes().get(p1).epoch(), joined.indexes().get(p3).epoch()));
        for (long pid : List.of(p1, p3)) {
            IndexEntry last = joined.indexes().get(pid);
            FreeRead gone = fx.store.readFree(pid, fx.deadline());
            assertThat(gone.ok()).isTrue();
            assertThat(gone.snapshot().playerTeamId()).isZero();
            assertThat(clientAccepts(last.epoch(), joined.version(), gone.snapshot().playerEpoch(), 0))
                    .as("player %s 过期前 epoch=%d，过期后空视图 epoch=%d 必须被接受", u(pid), last.epoch(),
                            gone.snapshot().playerEpoch()).isTrue();
            // 离队 / 解散等未绑定分支的回包视图同样取这份 S_READ
            MutateResult res = fx.mutate(Bind.caller(pid, tid), Op.leave(pid));
            assertThat(res.outcome()).isEqualTo(Outcome.NOT_BOUND);
            assertThat(res.snapshot().playerEpoch()).isEqualTo(res.snapshot().nowMs());
            assertThat(res.snapshot().playerEpoch()).isGreaterThanOrEqualTo(gone.snapshot().playerEpoch());
        }

        // 过期后（可能同一毫秒）重新建队：起种仍严格大于刚回报的空视图 epoch
        FreeRead again = fx.store.readFree(p3, fx.deadline());
        CommitResult recreated = fx.mustCreate(p3, fx.tid(2102));
        assertThat(recreated.indexes().get(p3).epoch()).isGreaterThan(again.snapshot().playerEpoch());

        // 孤儿自愈在 epoch 缺失时的起种与 S_COMMIT 同口径（nowMs+1）
        fx.hset(idx(p9), TeamRedisFields.TID, u(fx.tid(424242)));
        long t0 = fx.nowMs();
        FreeRead orphan = fx.store.readFree(p9, fx.deadline());
        long t1 = fx.nowMs();
        assertThat(orphan.ok()).isTrue();
        assertThat(orphan.healed()).isTrue();
        assertThat(orphan.snapshot().playerTeamId()).isZero();
        assertThat(orphan.snapshot().playerEpoch()).isBetween(t0 + 1, t1 + 1);
    }

    // ================================================================ #8 TestProjectionAndDisband（store_test.go:267）

    @Test
    void 投影钉住scene契约_解散删记录与投影() throws Exception {
        long tid = fx.tid(3001);
        long p1 = fx.pid(1), p3 = fx.pid(3);
        fx.mustCreate(p1, tid);
        CommitResult joined = fx.mustJoin(p1, tid, p3);

        TeamInfo projection = TeamInfo.parseFrom(fx.getBytes(info(tid)));
        assertThat(projection.getTeamId()).isEqualTo(tid);
        assertThat(projection.getLeaderId()).isEqualTo(p1);
        assertThat(projection.getMembersList()).containsExactlyInAnyOrder(p1, p3);
        for (String key : List.of(rec(tid), info(tid), idx(p1), idx(p3))) {
            assertIdleTtl(fx.ttlSeconds(key), key);
        }

        CommitResult disbanded = fx.mustCommit(Bind.caller(p1, tid), Op.disband(p1));
        assertThat(disbanded.version()).as("解散时是被删记录的最后一版 +1").isEqualTo(joined.version() + 1);
        assertThat(fx.exists(rec(tid))).isFalse();
        assertThat(fx.exists(info(tid))).isFalse();
        for (long pid : List.of(p1, p3)) {
            assertThat(disbanded.indexes().get(pid).teamId()).isZero();
            assertThat(fx.hget(idx(pid), TeamRedisFields.TID)).isEqualTo("0");
            assertThat(disbanded.indexes().get(pid).epoch()).isNotZero();
        }

        // 解散后重放：绑定仍是旧队但索引已是 0 → 不绑定
        assertThat(fx.mutate(Bind.caller(p1, tid), Op.disband(p1)).outcome()).isEqualTo(Outcome.NOT_BOUND);
    }

    // ================================================================ #9 TestSelfHealing（store_test.go:300）

    @Test
    void 自愈_成员索引被删_下一次提交重建() {
        long tid = fx.tid(4001);
        long p1 = fx.pid(1), p3 = fx.pid(3), p7 = fx.pid(7);
        fx.mustCreate(p1, tid);
        fx.mustJoin(p1, tid, p3);
        fx.del(idx(p3));
        fx.mustCommit(Bind.target(p7, tid), Op.apply(p7, ZONE));
        assertThat(fx.hget(idx(p3), TeamRedisFields.TID)).isEqualTo(u(tid));
    }

    @Test
    void 自愈_索引指向别队_由修复提交移出后重算原操作() {
        long tid = fx.tid(4002), other = fx.tid(555);
        long p1 = fx.pid(1), p3 = fx.pid(3), p7 = fx.pid(7);
        fx.mustCreate(p1, tid);
        fx.mustJoin(p1, tid, p3);
        fx.hset(idx(p3), TeamRedisFields.TID, u(other));

        MutateResult res = fx.mutate(Bind.target(p7, tid), Op.apply(p7, ZONE));

        assertThat(res.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(res.repairs()).hasSize(1);
        CommitResult fix = res.repairs().get(0);
        assertThat(fix.decision().reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_HEALED);
        assertThat(fix.decision().left()).containsExactly(p3);
        assertThat(fix.indexes().get(p3).teamId()).as("不改别队的索引，如实回报").isEqualTo(other);
        assertThat(res.commit().version()).isEqualTo(fix.version() + 1);
        TeamRecord rec = fx.loadRecord(tid);
        assertThat(TeamRules.memberIds(rec)).containsExactly(p1);
        assertThat(TeamRules.findApplication(rec, p7)).isNotNull();
        assertThat(fx.hget(idx(p3), TeamRedisFields.TID)).isEqualTo(u(other));
    }

    /** Lua 只回报第一个错位下标；修复提交本身再 {-2} 时必须级联移出，否则每轮都卡在同一个人身上。 */
    @Test
    void 自愈_两名保留成员索引都指向别队_修复一次移出两人_原操作照常提交() {
        long tid = fx.tid(4006), o1 = fx.tid(555), o2 = fx.tid(556);
        long p1 = fx.pid(1), p3 = fx.pid(3), p4 = fx.pid(4), p7 = fx.pid(7);
        fx.mustCreate(p1, tid);
        fx.mustJoin(p1, tid, p3);
        fx.mustJoin(p1, tid, p4);
        fx.hset(idx(p3), TeamRedisFields.TID, u(o1));
        fx.hset(idx(p4), TeamRedisFields.TID, u(o2));

        MutateResult res = fx.mutate(Bind.target(p7, tid), Op.apply(p7, ZONE));

        assertThat(res.outcome()).as("code=%d", res.code()).isEqualTo(Outcome.COMMITTED);
        assertThat(res.repairs()).hasSize(1);
        CommitResult fix = res.repairs().get(0);
        assertThat(fix.decision().reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_HEALED);
        assertThat(fix.decision().left()).as("Left 相对原记录计算，两名错位者都在").containsExactly(p3, p4);
        assertThat(fix.decision().kept()).containsExactly(p1);
        assertThat(fix.indexes().get(p3).teamId()).as("不改别队的索引，如实回报").isEqualTo(o1);
        assertThat(fix.indexes().get(p4).teamId()).isEqualTo(o2);
        assertThat(res.commit().version()).isEqualTo(fix.version() + 1);
        TeamRecord rec = fx.loadRecord(tid);
        assertThat(TeamRules.memberIds(rec)).containsExactly(p1);
        assertThat(TeamRules.findApplication(rec, p7)).isNotNull();
    }

    @Test
    void 自愈_孤儿索引_自由读与mutate都会治() {
        long t1 = fx.tid(4003), t2 = fx.tid(4004), t5 = fx.tid(4005);
        long p1 = fx.pid(1), p2 = fx.pid(2), p5 = fx.pid(5);
        CommitResult created = fx.mustCreate(p1, t1);
        fx.del(rec(t1));
        FreeRead snap = fx.store.readFree(p1, fx.deadline());
        assertThat(snap.ok()).isTrue();
        assertThat(snap.healed()).isTrue();
        assertThat(snap.snapshot().playerTeamId()).isZero();
        assertThat(snap.snapshot().record()).isNull();
        assertThat(snap.snapshot().playerEpoch()).isEqualTo(created.indexes().get(p1).epoch() + 1);

        fx.mustCreate(p2, t2);
        fx.del(rec(t2));
        MutateResult res = fx.mutate(Bind.caller(p2, t2), Op.leave(p2));
        assertThat(res.outcome()).isEqualTo(Outcome.RECORD_MISSING);
        assertThat(res.healedOrphan()).isTrue();
        assertThat(fx.hget(idx(p2), TeamRedisFields.TID)).isEqualTo("0");

        assertThat(fx.store.healOrphan(p1, t1, fx.deadline())).as("索引已不指向该队时不写").isFalse();
        fx.mustCreate(p5, t5);
        assertThat(fx.store.healOrphan(p5, t5, fx.deadline())).as("记录还在时不写").isFalse();
    }

    // ================================================================ #10 TestTouchRenewsWithoutBumpingVersion（store_test.go:433）

    @Test
    void 续期不涨版本_投影缺失时重写_旧快照不能续期() {
        long tid = fx.tid(5001);
        long p1 = fx.pid(1), p7 = fx.pid(7);
        fx.mustCreate(p1, tid);

        // 基线 FastForward(13h)：真 Redis 改成把记录、投影、索引的剩余 TTL 都压到 11h
        for (String key : List.of(rec(tid), info(tid), idx(p1))) {
            fx.pexpire(key, TimeUnit.HOURS.toMillis(11));
        }
        FreeRead read = fx.store.readFree(p1, fx.deadline());
        Snapshot snap = read.snapshot();
        assertThat(snap.recordTtlSeconds()).isBetween(11 * 3600L - 2, 11 * 3600L);
        assertThat(TeamStore.needsTouch(snap)).isTrue();
        fx.del(info(tid));

        assertThat(fx.store.touch(snap, fx.deadline())).isTrue();
        assertThat(fx.ver(tid)).as("触碰不改 ver").isEqualTo("1");
        assertIdleTtl(fx.ttlSeconds(rec(tid)), rec(tid));
        assertIdleTtl(fx.ttlSeconds(idx(p1)), idx(p1));
        assertThat(fx.exists(info(tid))).as("投影缺失时按该版记录重写").isTrue();
        assertIdleTtl(fx.ttlSeconds(info(tid)), info(tid));

        FreeRead fresh = fx.store.readFree(p1, fx.deadline());
        assertThat(TeamStore.needsTouch(fresh.snapshot())).isFalse();

        fx.mustCommit(Bind.target(p7, tid), Op.apply(p7, ZONE));
        assertThat(fx.store.touch(snap, fx.deadline())).as("ver 已变的旧快照不能续期").isFalse();

        // 基线 FastForward(25h)：整体空闲过期 → 删键
        fx.del(rec(tid), info(tid), idx(p1));
        FreeRead gone = fx.store.readFree(p1, fx.deadline());
        assertThat(gone.snapshot().playerTeamId()).as("整体空闲过期后视为无队").isZero();
        assertThat(gone.snapshot().record()).isNull();
    }

    /** team-spec §10.2「不给已去别队的索引续期」（基线 scripts.go:210-214 的分支，store_test 没有单独覆盖）。 */
    @Test
    void 续期不给已去别队的成员索引续命() {
        long tid = fx.tid(5002), other = fx.tid(5003);
        long p4 = fx.pid(4), p6 = fx.pid(6);
        fx.mustCreate(p4, tid);
        fx.mustJoin(p4, tid, p6);
        Snapshot snap = fx.store.readFree(p4, fx.deadline()).snapshot();
        fx.hset(idx(p6), TeamRedisFields.TID, u(other));
        fx.pexpire(idx(p6), 100_000);
        fx.pexpire(idx(p4), 100_000);

        assertThat(fx.store.touch(snap, fx.deadline())).isTrue();
        assertThat(fx.ttlSeconds(idx(p6))).isLessThanOrEqualTo(100);
        assertIdleTtl(fx.ttlSeconds(idx(p4)), idx(p4));
    }

    // ================================================================ #19 TestCreateSentinelAndDisbandAcceptRace（store_test.go:472）

    @Test
    void 建队哨兵_解散与接受邀请交错时不重建记录() {
        long tid = fx.tid(6001), tid2 = fx.tid(6002);
        long p1 = fx.pid(1), p4 = fx.pid(4), p5 = fx.pid(5), p6 = fx.pid(6);
        fx.mustCreate(p1, tid);
        fx.mustCommit(Bind.caller(p1, tid), Op.invite(p1, p4, ZONE));

        AtomicBoolean once = new AtomicBoolean();
        afterRead.set(b -> {
            if (b.playerId() != p4 || !once.compareAndSet(false, true)) {
                return;
            }
            MutateResult disband = fx.mutate(Bind.caller(p1, tid), Op.disband(p1));
            assertThat(disband.outcome()).isEqualTo(Outcome.COMMITTED);
        });
        MutateResult res = fx.mutate(Bind.target(p4, tid), Op.respondInvite(p4, true));
        afterRead.set(b -> {
        });
        assertThat(res.outcome()).as("首次提交 {0}，重读发现记录已删").isEqualTo(Outcome.RECORD_MISSING);
        assertThat(res.conflicts()).isEqualTo(1);
        assertThat(fx.exists(rec(tid))).as("不重建记录").isFalse();
        assertThat(fx.exists(info(tid))).as("不重建投影").isFalse();
        assertThat(fx.hget(idx(p4), TeamRedisFields.TID)).isNotEqualTo(u(tid));

        fx.mustCreate(p5, tid2);
        MutateResult dup = fx.mutate(Bind.create(p6, tid2), Op.create(p6, tid2, ZONE));
        assertThat(dup.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(dup.code()).as("新发的 team_id 已有记录是发号器故障").isEqualTo(TeamTips.INTERNAL);
        assertThat(fx.exists(idx(p6))).isFalse();
    }

    // ================================================================ #20 TestReinviteAfterExpiryKeepsIndex（store_test.go:508）

    @Test
    void 过期后重邀同一人_反查索引不丢_先ZREM后ZADD() {
        long tid = fx.tid(7001);
        long p1 = fx.pid(1), p9 = fx.pid(9);
        fx.mustCreate(p1, tid);
        CommitResult first = fx.mustCommit(Bind.caller(p1, tid), Op.invite(p1, p9, ZONE));
        assertThat(fx.zscore(inv(p9), u(tid))).isEqualTo((double) (first.nowMs() + TeamLimits.INVITE_TTL_MS));

        // 恰好过期：基线把 Redis 时钟拨到截止；真 Redis 改成把记录里的截止与反查项的 score 改到「此刻」
        long expire = fx.nowMs();
        TeamRecord rec = fx.loadRecord(tid);
        TeamRecord.Builder b = rec.toBuilder();
        for (int i = 0; i < b.getInvitesCount(); i++) {
            b.setInvites(i, b.getInvites(i).toBuilder().setExpireAtMs(expire));
        }
        fx.writeRecord(tid, b.build());
        fx.zadd(inv(p9), expire, u(tid));

        CommitResult again = fx.mustCommit(Bind.caller(p1, tid), Op.invite(p1, p9, ZONE));
        assertThat(again.decision().invitesRemoved()).as("ID := ID \\ IA").doesNotContain(p9);
        assertThat(fx.zscore(inv(p9), u(tid))).isEqualTo((double) (again.nowMs() + TeamLimits.INVITE_TTL_MS));

        // 故意喂重叠集合：校验层拒绝；绕过校验直接执行 Lua，先 ZREM 后 ZADD 仍不丢索引
        TeamRecord current = fx.loadRecord(tid);
        long overlapExpire = expire + 5 * TeamLimits.INVITE_TTL_MS;
        Decision overlap = new Decision(TeamTips.OK, 0, true, current, List.of(), TeamRules.memberIds(current), List.of(),
                List.of(new InviteAdd(p9, overlapExpire)), List.of(p9), TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED,
                p1, false, false, List.of(), 0, p9);
        assertThatThrownBy(() -> CommitSets.validate(tid, overlap, current)).isInstanceOf(IllegalStateException.class);
        CommitSets.Call call = CommitSets.build(tid, fx.ver(tid), overlap);
        List<Object> raw = TeamReplies.multi(fx.teamRedis.eval(TeamScript.COMMIT, call.keys(), call.args())
                .toCompletableFuture().join(), "S_COMMIT");
        assertThat(raw.get(0)).isEqualTo(1L);
        assertThat(fx.zscore(inv(p9), u(tid))).isEqualTo((double) overlapExpire);
    }

    // ================================================================ #21 TestInviteeLimitIsAtomic（store_test.go:548）

    @Test
    void 被邀请人待处理邀请上限是原子的() throws Exception {
        long invitee = fx.pid(50);
        int teams = TeamLimits.MAX_PENDING_INVITES_PER_INVITEE + 1;
        for (int i = 0; i < teams; i++) {
            fx.mustCreate(fx.pid(101 + i), fx.tid(8101 + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(teams);
        List<Future<MutateResult>> futures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < teams; i++) {
                long leader = fx.pid(101 + i), tid = fx.tid(8101 + i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return fx.mutate(Bind.caller(leader, tid), Op.invite(leader, invitee, ZONE));
                }));
            }
            start.countDown();
            int committed = 0, limited = 0;
            for (int i = 0; i < teams; i++) {
                MutateResult res = futures.get(i).get(30, TimeUnit.SECONDS);
                switch (res.outcome()) {
                    case COMMITTED -> committed++;
                    case REJECTED -> {
                        assertThat(res.code()).isEqualTo(TeamTips.INVITE_LIMIT);
                        assertThat(res.param()).isEqualTo(invitee);
                        assertThat(TeamRules.findInvite(fx.loadRecord(fx.tid(8101 + i)), invitee))
                                .as("被拒的队伍记录里不能有这条邀请").isNull();
                        limited++;
                    }
                    default -> throw new AssertionError("意外结局 " + res.outcome());
                }
            }
            assertThat(committed).isEqualTo(TeamLimits.MAX_PENDING_INVITES_PER_INVITEE);
            assertThat(limited).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // 确定性复验：拒绝分支前后状态完全一致
        long p112 = fx.pid(112), t112 = fx.tid(8112);
        fx.mustCreate(p112, t112);
        TeamRedisFixture.KeyState before = fx.state();
        MutateResult res = fx.mutate(Bind.caller(p112, t112), Op.invite(p112, invitee, ZONE));
        assertThat(res.code()).isEqualTo(TeamTips.INVITE_LIMIT);
        assertThat(res.param()).isEqualTo(invitee);
        fx.assertUnchanged(before);

        // 已有本队条目 = 刷新，不占新名额
        for (int i = 0; i < teams; i++) {
            long tid = fx.tid(8101 + i);
            if (fx.zscore(inv(invitee), u(tid)) != null) {
                fx.mustCommit(Bind.caller(fx.pid(101 + i), tid), Op.invite(fx.pid(101 + i), invitee, ZONE));
                break;
            }
        }

        // 全部过期后不再计数，且写入前清掉过期反查项。基线把时钟拨过截止；真 Redis 改成把反查项的 score 改到过去
        long past = fx.nowMs() - 1;
        for (String member : fx.zmembers(inv(invitee))) {
            fx.zadd(inv(invitee), past, member);
        }
        fx.mustCommit(Bind.caller(p112, t112), Op.invite(p112, invitee, ZONE));
        assertThat(fx.zmembers(inv(invitee))).containsExactly(u(t112));
    }

    /**
     * 基线隐患 §8.1 第 1 条（照搬，D19 未采纳）：本队在被邀请人的 ZSET 里有一条<b>已过期、还没清掉</b>的旧项时，ZSCORE 非空于是跳过上限判定，
     * 随后先清过期项再 ZADD——对方最终持有 11 条有效邀请。没有残留项的第 12 支队伍照常被 4022 拒绝。
     */
    @Test
    void 基线隐患_本队有过期残留项时跳过上限判定_被邀请人可持有11条有效邀请() {
        long invitee = fx.pid(60);
        int cap = TeamLimits.MAX_PENDING_INVITES_PER_INVITEE;
        for (int i = 0; i < cap; i++) {
            long leader = fx.pid(201 + i), tid = fx.tid(9201 + i);
            fx.mustCreate(leader, tid);
            fx.mustCommit(Bind.caller(leader, tid), Op.invite(leader, invitee, ZONE));
        }
        assertThat(fx.zcountAfter(inv(invitee), fx.nowMs())).isEqualTo(cap);

        long leader11 = fx.pid(211), tid11 = fx.tid(9211);
        fx.mustCreate(leader11, tid11);
        fx.zadd(inv(invitee), fx.nowMs() - 1, u(tid11)); // 本队一条已过期、还没清掉的旧项

        MutateResult res = fx.mutate(Bind.caller(leader11, tid11), Op.invite(leader11, invitee, ZONE));
        assertThat(res.outcome()).as("ZSCORE 非空 → 跳过上限判定").isEqualTo(Outcome.COMMITTED);
        assertThat(fx.zcountAfter(inv(invitee), fx.nowMs())).as("上界是 11").isEqualTo(cap + 1);

        long leader12 = fx.pid(212), tid12 = fx.tid(9212);
        fx.mustCreate(leader12, tid12);
        MutateResult limited = fx.mutate(Bind.caller(leader12, tid12), Op.invite(leader12, invitee, ZONE));
        assertThat(limited.code()).isEqualTo(TeamTips.INVITE_LIMIT);
        assertThat(limited.param()).isEqualTo(invitee);
    }

    // ================================================================ #22 TestRedisClockDrivesExpiry（store_test.go:611）

    @Test
    void 时钟只随Redis_TIME变化() {
        long t1 = fx.tid(9001), t2 = fx.tid(9002), t3 = fx.tid(9003);
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3), p5 = fx.pid(5), p6 = fx.pid(6), p20 = fx.pid(20), p21 = fx.pid(21);

        // 截止之前同意成功
        fx.mustCreate(p1, t1);
        fx.mustCommit(Bind.target(p3, t1), Op.apply(p3, ZONE));
        fx.mustCommit(Bind.caller(p1, t1), Op.handleApplication(p1, p3, true));

        // expire <= Redis now 即过期：基线把时钟拨到截止；真 Redis 改成把申请截止改到「此刻」
        fx.mustCreate(p2, t2);
        fx.mustCommit(Bind.target(p5, t2), Op.apply(p5, ZONE));
        long now = fx.nowMs();
        TeamRecord rec2 = fx.loadRecord(t2);
        fx.writeRecord(t2, rec2.toBuilder().setApplications(0, rec2.getApplications(0).toBuilder().setExpireAtMs(now)).build());
        MutateResult res = fx.mutate(Bind.caller(p2, t2), Op.handleApplication(p2, p5, true));
        assertThat(res.code()).isEqualTo(TeamTips.APPLICATION_NOT_FOUND);

        // 开战锁有效性只看 Redis 时钟
        fx.mustJoin(p1, t1, p6);
        TeamRecord rec1 = fx.loadRecord(t1);
        long lockExpire = fx.nowMs() + 2_000;
        TeamRecord locked = rec1.toBuilder().setMatchLockToken("tok").setMatchLockExpireAtMs(lockExpire)
                .clearMatchLockRoster().addAllMatchLockRoster(TeamRules.memberIds(rec1)).build();
        Decision lock = new Decision(TeamTips.OK, 0, true, locked, List.of(), TeamRules.memberIds(locked), List.of(),
                List.of(), List.of(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED, p1, false, false, List.of(), 0, 0);
        assertThat(fx.store.commit(t1, fx.ver(t1), lock, now, rec1, fx.deadline()).status()).isEqualTo(CommitStatus.OK);
        assertThat(fx.mutate(Bind.caller(p1, t1), Op.kick(p1, p6)).code()).isEqualTo(TeamTips.IN_MATCH);
        fx.waitRedisAfter(lockExpire - 1); // Redis now >= 截止 → 锁无效
        fx.mustCommit(Bind.caller(p1, t1), Op.kick(p1, p6));

        // 记录里的时间戳取本轮 S_READ 的 Redis 时钟，与 JVM 墙钟无关
        fx.mustCreate(p20, t3);
        long before = fx.nowMs();
        CommitResult invited = fx.mustCommit(Bind.caller(p20, t3), Op.invite(p20, p21, ZONE));
        long after = fx.nowMs();
        assertThat(invited.nowMs()).isBetween(before, after);
        TeamInviteRecord invite = TeamRules.findInvite(fx.loadRecord(t3), p21);
        assertThat(invite.getInvitedAtMs()).isEqualTo(invited.nowMs());
        assertThat(invite.getExpireAtMs()).isEqualTo(invited.nowMs() + TeamLimits.INVITE_TTL_MS);
    }

    // ================================================================ S_READ_MEMBERS / S_INVITE_LIST / S_INVITE_PRUNE（store_test.go:655、:674）

    @Test
    void 读成员_tid与epoch配对_过期成员表自动重读() {
        long tid = fx.tid(9101), other = fx.tid(555);
        long p1 = fx.pid(1), p3 = fx.pid(3);
        fx.mustCreate(p1, tid);
        CommitResult joined = fx.mustJoin(p1, tid, p3);
        fx.hset(idx(p3), TeamRedisFields.TID, u(other));

        MembersSnapshot snap = fx.store.readMembers(tid, List.of(p1), fx.deadline()); // 传入过期的成员表 → 自动用新表重读
        assertThat(snap.version()).isEqualTo(joined.version());
        assertThat(snap.indexes().get(p1)).isEqualTo(new IndexEntry(tid, joined.indexes().get(p1).epoch()));
        assertThat(snap.indexes().get(p3).teamId()).as("tid 不等的成员如实回报，由服务层跳过推送").isEqualTo(other);

        long t0 = fx.nowMs();
        MembersSnapshot missing = fx.store.readMembers(fx.tid(424242), null, fx.deadline());
        long t1 = fx.nowMs();
        assertThat(missing.record()).isNull();
        assertThat(missing.version()).isZero();
        assertThat(missing.nowMs()).isBetween(t0, t1);
    }

    @Test
    void 邀请列表按Redis时钟剔除过期项_剪除按score做CAS() {
        long tid = fx.tid(9201);
        long p1 = fx.pid(1), p4 = fx.pid(4);
        fx.mustCreate(p1, tid);
        CommitResult first = fx.mustCommit(Bind.caller(p1, tid), Op.invite(p1, p4, ZONE));
        fx.zadd(inv(p4), first.nowMs() - 1, u(fx.tid(777))); // 已过期的残留项

        long t0 = fx.nowMs();
        InviteList list = fx.store.listInvites(p4, fx.deadline());
        long t1 = fx.nowMs();
        assertThat(list.nowMs()).isBetween(t0, t1);
        assertThat(list.entries()).as("过期项被 Redis 时钟剔除").hasSize(1);
        assertThat(list.entries().get(0).teamId()).isEqualTo(tid);
        assertThat(list.entries().get(0).expireAtMs()).isEqualTo(first.nowMs() + TeamLimits.INVITE_TTL_MS);
        assertThat(fx.zscore(inv(p4), u(fx.tid(777)))).as("S_INVITE_LIST 会写").isNull();

        // LIST 之后、PRUNE 之前队长重邀（score 变化）→ 不删新项
        fx.waitRedisAfter(first.nowMs());
        CommitResult second = fx.mustCommit(Bind.caller(p1, tid), Op.invite(p1, p4, ZONE));
        assertThat(second.nowMs()).isGreaterThan(first.nowMs());
        assertThat(fx.store.pruneInvite(p4, tid, list.entries().get(0).score(), fx.deadline())).isFalse();
        assertThat(fx.zscore(inv(p4), u(tid))).isNotNull();

        InviteList fresh = fx.store.listInvites(p4, fx.deadline());
        assertThat(fx.store.pruneInvite(p4, tid, fresh.entries().get(0).score(), fx.deadline())).isTrue();
        assertThat(fx.zscore(inv(p4), u(tid))).isNull();
    }
}
