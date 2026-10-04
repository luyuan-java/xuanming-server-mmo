package com.game.team.store;

import static com.game.team.store.TeamRedisFixture.ZONE;
import static com.game.team.store.TeamRedisFixture.u;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamRedisFields;
import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamInviteRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Op;
import com.game.team.rules.SessionState;
import com.game.team.rules.TeamLimits;
import com.game.team.rules.TeamRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;

/**
 * 并发不变量模糊测试（基线 store_test.go:706-915 TestConcurrentInvariantsFuzz，20 worker × 2000 轮；team-spec §1.2 第 10 条、§10.2）。
 * 按 CI 预算缩成 8 线程 × 300 轮、20 名玩家，六条终态不变量全部检查：
 * <ol>
 *   <li>每个成员的索引 tid == 本队；</li>
 *   <li>索引 tid ≠ 0 时，对应队伍包含该玩家；</li>
 *   <li>每个玩家的 epoch 单调，且同一个 epoch 只配对一个 tid；</li>
 *   <li>记录里未过期的邀请都在反查索引里；</li>
 *   <li>投影与记录一致；</li>
 *   <li>每个 (tid, ver) 只提交一次。</li>
 * </ol>
 * 另外检查：成员数 ≤ 容量、队长是成员、一人不在两队、没有 mutate 抛异常。
 *
 * <p>默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}（DB 12，只删自己的键）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class TeamStoreFuzzIntegrationTest {

    private static final int PLAYERS = 20;
    private static final int WORKERS = 8;
    private static final int ITERATIONS = 300;
    private static final int TID_BASE = 100_000;

    private static RedissonClient redis;
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
        fx = new TeamRedisFixture(redis);
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    /** 观测记录（store_test.go:708-765）。 */
    private static final class Recorder {
        private final List<String> failures = new ArrayList<>();
        private final Map<Long, Map<Long, Long>> epochTid = new HashMap<>(); // player → epoch → tid
        private final Map<Long, Long> maxEpoch = new HashMap<>();
        private final Map<List<Long>, Integer> versions = new HashMap<>(); // (tid, ver) → 提交次数
        private final Map<String, Integer> stats = new java.util.TreeMap<>(); // 结局 / 提交原因 → 次数（防止模糊测试空转）

        synchronized void fail(String message) {
            if (failures.size() < 20) {
                failures.add(message);
            }
        }

        synchronized void observe(long pid, long epoch, long tid) {
            Map<Long, Long> m = epochTid.computeIfAbsent(pid, k -> new HashMap<>());
            Long prev = m.put(epoch, tid);
            if (prev != null && prev != tid) {
                fail("player " + u(pid) + " epoch " + epoch + " 同时配对 tid " + u(prev) + " 与 " + u(tid));
            }
            maxEpoch.merge(pid, epoch, (a, b) -> Long.compareUnsigned(a, b) >= 0 ? a : b);
        }

        void observeCommit(CommitResult c) {
            for (Map.Entry<Long, IndexEntry> e : c.indexes().entrySet()) {
                observe(e.getKey(), e.getValue().epoch(), e.getValue().teamId());
            }
            synchronized (this) {
                versions.merge(List.of(c.teamId(), c.version()), 1, Integer::sum);
                stats.merge("reason:" + c.decision().reason().name(), 1, Integer::sum);
            }
        }

        void observeMutate(MutateResult res) {
            Snapshot snap = res.snapshot();
            observe(snap.playerId(), snap.playerEpoch(), snap.playerTeamId());
            synchronized (this) {
                stats.merge("outcome:" + res.outcome() + (res.code() != 0 ? "/" + res.code() : ""), 1, Integer::sum);
                stats.merge("conflicts", res.conflicts(), Integer::sum);
            }
            for (CommitResult repair : res.repairs()) {
                observeCommit(repair);
            }
            if (res.commit() != null) {
                observeCommit(res.commit());
            }
        }
    }

    @Test
    void 并发名册操作后六条终态不变量成立() throws Exception {
        long[] players = new long[PLAYERS + 1];
        Map<Long, SessionState> states = new HashMap<>();
        for (int i = 1; i <= PLAYERS; i++) {
            players[i] = fx.pid(i);
            states.put(players[i], SessionState.ONLINE);
        }
        states.put(players[1], SessionState.ABSENT); // 覆盖惰性转让
        states.put(players[2], SessionState.ABSENT);
        Map<Long, SessionState> frozen = Map.copyOf(states);
        fx.sessions = (members, deadline) -> {
            Map<Long, SessionState> out = new HashMap<>();
            for (long m : members) {
                SessionState s = frozen.get(m);
                if (s != null) {
                    out.put(m, s);
                }
            }
            return out;
        };
        Recorder rec = new Recorder();
        AtomicInteger nextTid = new AtomicInteger(TID_BASE);

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < WORKERS; w++) {
            long seed = w + 1;
            futures.add(pool.submit(() -> runWorker(seed, players, rec, nextTid)));
        }
        try {
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rec.failures).isEmpty();

        for (Map.Entry<List<Long>, Integer> e : rec.versions.entrySet()) {
            assertThat(e.getValue()).as("tid=%s ver=%d 被提交了 %d 次（ver CAS 失效）", u(e.getKey().get(0)), e.getKey().get(1),
                    e.getValue()).isEqualTo(1);
        }

        // 终态不变量
        long now = fx.nowMs();
        Map<Long, Long> teamOf = new HashMap<>();
        for (int n = TID_BASE + 1; n <= nextTid.get(); n++) {
            long tid = fx.tid(n);
            if (!fx.exists(RedisKeys.teamRecord(tid))) {
                continue;
            }
            TeamRecord r = fx.loadRecord(tid);
            assertThat(r.getMembersCount()).isLessThanOrEqualTo(TeamLimits.CAPACITY);
            assertThat(TeamRules.findMember(r, r.getLeaderId())).as("队长必须是成员 tid=%s", u(tid)).isNotNull();
            for (long m : TeamRules.memberIds(r)) {
                Long prev = teamOf.put(m, tid);
                assertThat(prev).as("player %s 同时在 %s 与 %s", u(m), prev == null ? "-" : u(prev), u(tid)).isNull();
                assertThat(fx.hget(RedisKeys.teamPlayer(m), TeamRedisFields.TID)).as("成员索引必须指向记录").isEqualTo(u(tid));
            }
            for (TeamInviteRecord inv : r.getInvitesList()) {
                if (Long.compareUnsigned(inv.getExpireAtMs(), now) > 0) {
                    assertThat(fx.zscore(RedisKeys.teamInvite(inv.getInviteeId()), u(tid)))
                            .as("邀请反查索引 ⊇ 记录中未过期邀请 tid=%s invitee=%s", u(tid), u(inv.getInviteeId())).isNotNull();
                }
            }
            TeamInfo info = TeamInfo.parseFrom(fx.getBytes(RedisKeys.teamInfo(tid)));
            assertThat(info.getTeamId()).isEqualTo(tid);
            assertThat(info.getLeaderId()).as("投影与记录同步").isEqualTo(r.getLeaderId());
            assertThat(info.getMembersList()).containsExactlyInAnyOrderElementsOf(TeamRules.memberIds(r));
        }
        for (int i = 1; i <= PLAYERS; i++) {
            long pid = players[i];
            String key = RedisKeys.teamPlayer(pid);
            String tidStr = fx.hget(key, TeamRedisFields.TID);
            if (tidStr != null && !tidStr.equals("0")) {
                assertThat(teamOf.get(pid)).as("索引指向的队伍必须包含该玩家 player=%s", u(pid))
                        .isEqualTo(Long.parseUnsignedLong(tidStr));
            }
            long observed = rec.maxEpoch.getOrDefault(pid, 0L);
            if (!fx.exists(key)) {
                // 从未组过队：只观察到读路径对缺失索引回报的 Redis nowMs，不得超过此刻
                assertThat(Long.compareUnsigned(observed, now)).as("缺失索引回报的 epoch player=%s", u(pid)).isLessThanOrEqualTo(0);
                continue;
            }
            long epoch = Long.parseUnsignedLong(fx.hget(key, TeamRedisFields.EPOCH));
            assertThat(Long.compareUnsigned(epoch, observed)).as("epoch 单调 player=%s", u(pid)).isGreaterThanOrEqualTo(0);
        }
        // 防空转：真的提交过足够多次（各类结局与原因的分布打印出来供排查；只断言稳定成立的下限，避免交错不同导致偶发失败）
        System.out.println("[team fuzz] " + rec.stats);
        assertThat(rec.stats.getOrDefault("outcome:COMMITTED", 0)).as("提交次数 %s", rec.stats).isGreaterThan(100);
        assertThat(rec.stats.getOrDefault("reason:TEAM_CHANGE_REASON_CREATED", 0)).isPositive();
    }

    private void runWorker(long seed, long[] players, Recorder rec, AtomicInteger nextTid) {
        Random rng = new Random(seed);
        LongSupplier randPid = () -> players[rng.nextInt(PLAYERS) + 1];
        for (int i = 0; i < ITERATIONS; i++) {
            long pid = randPid.getAsLong();
            try {
                FreeRead read = fx.store.readFree(pid, fx.deadline());
                if (read.status() == FreeRead.Status.UNSTABLE) {
                    continue;
                }
                if (!read.ok()) {
                    rec.fail("readFree: " + read.error());
                    return;
                }
                Snapshot snap = read.snapshot();
                rec.observe(pid, snap.playerEpoch(), snap.playerTeamId());
                long tid = snap.playerTeamId();
                LongSupplier randMember = () -> {
                    List<Long> ids = TeamRules.memberIds(snap.record());
                    return ids.isEmpty() ? randPid.getAsLong() : ids.get(rng.nextInt(ids.size()));
                };
                switch (rng.nextInt(10)) {
                    case 0 -> {
                        long id = fx.tid(nextTid.incrementAndGet());
                        rec.observeMutate(fx.mutate(Bind.create(pid, id), Op.create(pid, id, ZONE)));
                    }
                    case 1 -> {
                        FreeRead target = fx.store.readFree(randPid.getAsLong(), fx.deadline());
                        if (target.ok() && target.snapshot().playerTeamId() != 0) {
                            rec.observeMutate(fx.mutate(Bind.target(pid, target.snapshot().playerTeamId()), Op.apply(pid, ZONE)));
                        }
                    }
                    case 2 -> {
                        List<TeamApplicationRecord> apps = snap.record() == null ? List.of() : snap.record().getApplicationsList();
                        if (!apps.isEmpty()) {
                            long applicant = apps.get(rng.nextInt(apps.size())).getPlayerId();
                            rec.observeMutate(fx.mutate(Bind.caller(pid, tid),
                                    Op.handleApplication(pid, applicant, rng.nextInt(3) != 0)));
                        }
                    }
                    case 3 -> rec.observeMutate(fx.mutate(Bind.caller(pid, tid), Op.invite(pid, randPid.getAsLong(), ZONE)));
                    case 4 -> {
                        InviteList list = fx.store.listInvites(pid, fx.deadline());
                        if (!list.entries().isEmpty()) {
                            InviteIndexEntry e = list.entries().get(rng.nextInt(list.entries().size()));
                            rec.observeMutate(fx.mutate(Bind.target(pid, e.teamId()), Op.respondInvite(pid, rng.nextInt(3) != 0)));
                        }
                    }
                    case 5 -> rec.observeMutate(fx.mutate(Bind.caller(pid, tid), Op.leave(pid)));
                    case 6 -> rec.observeMutate(fx.mutate(Bind.caller(pid, tid), Op.kick(pid, randMember.getAsLong())));
                    case 7 -> rec.observeMutate(fx.mutate(Bind.caller(pid, tid), Op.transferLeader(pid, randMember.getAsLong())));
                    case 8 -> rec.observeMutate(fx.mutate(Bind.caller(pid, tid), Op.disband(pid)));
                    default -> {
                        if (tid != 0) {
                            rec.observeMutate(fx.mutate(Bind.caller(pid, tid), Op.refresh(pid)));
                            try {
                                MembersSnapshot ms = fx.store.readMembers(tid, TeamRules.memberIds(snap.record()), fx.deadline());
                                for (Map.Entry<Long, IndexEntry> e : ms.indexes().entrySet()) {
                                    rec.observe(e.getKey(), e.getValue().epoch(), e.getValue().teamId());
                                }
                            } catch (MembersChangedException ignored) {
                                // 成员表持续变化：基线同样跳过
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                rec.fail("第 " + i + " 轮异常: " + e);
                return;
            }
        }
    }
}
