package com.game.team.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPushes;
import com.game.proto.MessageContent;
import com.game.proto.team.ApplyJoinTeamRequest;
import com.game.proto.team.CreateTeamRequest;
import com.game.proto.team.HandleApplicationRequest;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.team.match.FakeMatchTeamService;
import com.game.team.match.MatchTeamBattle;
import com.game.team.metrics.TeamMetrics;
import com.game.team.presence.SessionReads;
import com.game.team.proto.TeamRecord;
import com.game.team.push.TeamPushes;
import com.game.team.rules.Decision;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.SessionState;
import com.game.team.store.Bind;
import com.game.team.store.IndexEntry;
import com.game.team.store.InMemoryTeamRedis;
import com.game.team.store.MembersSnapshot;
import com.game.team.store.RedissonTeamRedis;
import com.game.team.store.Snapshot;
import com.game.team.store.TeamRedis;
import com.game.team.store.TeamScript;
import com.game.team.store.TeamStore;
import com.game.team.view.MemberDisplay;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.redisson.api.RedissonClient;

/**
 * 整队开战服务层用例的夹具（对应基线 {@code team_battle_test.go:106-175} 的 battleHarness）。存储后端可换：内存替身
 * （{@link InMemoryTeamRedis}，时钟手拨，缺省档就跑）或真 Redis（DB 12，带 {@code -Dxm.it.redis} 才跑）——同一批用例两边都跑，
 * 替身与真脚本对不上就会有一边失败。
 *
 * <ul>
 *   <li>队伍记录、开战锁、EndMatch 走真 {@link TeamStore}；xm-match 是 {@link FakeMatchTeamService}，经真的 {@link MatchTeamBattle} 适配；</li>
 *   <li>推送执行器与 {@code team-match-end} 执行器缺省<b>同步</b>（基线测试把 asyncFn 换成同步执行）：211 返回时后台收尾与推送都已完成，
 *       断言是确定的；{@link #rejectBackground} 模拟收尾执行器已满；</li>
 *   <li>EndMatch 的退避不真睡，记在 {@link #sleeps}；并发时序用存储层的测试缝（{@link TeamStore.Hooks}）与替身的
 *       {@code afterCheckPassed} 在固定点插入；</li>
 *   <li>{@link #hgets} / {@link #evals} 数 Redis 调用：自由读必先 HGET 索引，所以「回包视图是同源的」可以断言成「这次请求没有 HGET」；</li>
 *   <li>id 取随机大号（≥ 2^63），真 Redis 上只删自己分配过的 id 的四类键。</li>
 * </ul>
 */
final class TeamMatchFixture {

    static final int ZONE = 1;
    static final int CONFIG = 1;
    /** 替身 xm-match 回的开战锁时长（秒；基线 testLockSeconds）。 */
    static final int LOCK_SECONDS = 83;

    /** 存储后端。 */
    interface Backend {

        TeamRedis redis();

        /** 把 Redis 时钟往前拨；拨不动（真 Redis）返回 false，用例自行跳过。 */
        boolean advance(long millis);

        /** 删掉夹具分配过的键。 */
        void delete(List<String> keys);
    }

    /** 内存后端：时钟固定在一个任意的起点，只有 {@link Backend#advance} 会动它。 */
    static Backend inMemory() {
        InMemoryTeamRedis redis = new InMemoryTeamRedis(1_900_000_000_000L);
        return new Backend() {
            @Override
            public TeamRedis redis() {
                return redis;
            }

            @Override
            public boolean advance(long millis) {
                redis.advance(millis);
                return true;
            }

            @Override
            public void delete(List<String> keys) {
                keys.forEach(redis::del);
            }
        };
    }

    static Backend redis(RedissonClient client) {
        TeamRedis redis = new RedissonTeamRedis(client);
        return new Backend() {
            @Override
            public TeamRedis redis() {
                return redis;
            }

            @Override
            public boolean advance(long millis) {
                return false;
            }

            @Override
            public void delete(List<String> keys) {
                for (int i = 0; i < keys.size(); i += 256) {
                    client.getKeys().delete(keys.subList(i, Math.min(keys.size(), i + 256)).toArray(String[]::new));
                }
            }
        };
    }

    /** 一条记录下来的 213 快照推送。 */
    record Pushed(long playerId, TeamSnapshotS2C snapshot) {
    }

    /** 一支队伍的可观察状态：记录版本、记录字节、每名给定玩家的索引 (tid, epoch)。 */
    record State(long version, ByteString record, Map<Long, IndexEntry> indexes) {
    }

    @FunctionalInterface
    interface BeforeCommit {
        void accept(Decision decision, List<Object> keys, List<byte[]> args);
    }

    final Backend backend;
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final TeamMetrics metrics = new TeamMetrics(meters, false);
    final FakeMatchTeamService match = new FakeMatchTeamService().open(CONFIG, 5);
    final TeamStore store;
    final SessionReads sessions;
    final TeamService service;

    /** mutate 每轮 S_READ 之后（基线 afterReadHook）。 */
    final AtomicReference<Consumer<Bind>> afterRead = new AtomicReference<>(bind -> {
    });
    /** S_COMMIT 发出之前（基线 beforeCommitEvalHook）。 */
    final AtomicReference<BeforeCommit> beforeCommit = new AtomicReference<>((decision, keys, args) -> {
    });
    /** 清开战锁每一轮 S_READ 之后（基线 afterMatchReadHook）。 */
    final AtomicReference<LongConsumer> afterMatchRead = new AtomicReference<>(teamId -> {
    });
    /** 非 null 的返回值顶替这段脚本的真实执行（注入故障用）。 */
    final AtomicReference<BiFunction<TeamScript, List<Object>, CompletionStage<Object>>> evalOverride =
            new AtomicReference<>((script, keys) -> null);
    /** EndMatch 的退避（毫秒），按发生顺序；不真睡。 */
    final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
    /** EndMatch 截止用的单调时钟（缺省真时钟）。 */
    volatile LongSupplier nanoTime = System::nanoTime;
    /** true = {@code team-match-end} 执行器拒收（已满）。 */
    volatile boolean rejectBackground;
    /** true = 本进程已经开始停机（生产里由 {@code TeamShutdown} 在上下文关闭事件里置位）。 */
    volatile boolean stopping;

    final AtomicInteger hgets = new AtomicInteger();
    final Map<TeamScript, AtomicInteger> evals = new EnumMap<>(TeamScript.class);

    final Map<Long, SessionState> states = new ConcurrentHashMap<>();
    private final List<Pushed> pushes = Collections.synchronizedList(new ArrayList<>());
    private final Deque<Runnable> background = new ArrayDeque<>();
    private boolean drainingBackground;
    private final long pidBase;
    private final AtomicLong nextTid;
    private final AtomicInteger nextPid = new AtomicInteger();
    private final Set<Long> pids = ConcurrentHashMap.newKeySet();
    private final Set<Long> tids = ConcurrentHashMap.newKeySet();

    TeamMatchFixture(Backend backend) {
        this.backend = backend;
        for (TeamScript script : TeamScript.values()) {
            evals.put(script, new AtomicInteger());
        }
        long r = ThreadLocalRandom.current().nextLong(1L << 38) * 16_384;
        this.pidBase = Long.MIN_VALUE + (1L << 57) + r;
        this.nextTid = new AtomicLong(Long.MIN_VALUE + (1L << 58) + r);
        TeamRedis real = backend.redis();
        TeamRedis counting = new TeamRedis() {
            @Override
            public CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
                evals.get(script).incrementAndGet();
                CompletionStage<Object> injected = evalOverride.get().apply(script, keys);
                return injected != null ? injected : real.eval(script, keys, args);
            }

            @Override
            public CompletionStage<byte[]> hget(String key, String field) {
                hgets.incrementAndGet();
                return real.hget(key, field);
            }
        };
        this.store = new TeamStore(counting, new TeamStore.Hooks() {
            @Override
            public void afterRead(Bind bind) {
                afterRead.get().accept(bind);
            }

            @Override
            public void beforeCommitEval(Decision decision, List<Object> keys, List<byte[]> args) {
                beforeCommit.get().accept(decision, keys, args);
            }

            @Override
            public void afterMatchRead(long teamId) {
                afterMatchRead.get().accept(teamId);
            }

            @Override
            public void endMatchSleep(long millis) {
                sleeps.add(millis);
            }

            @Override
            public long nanoTime() {
                return nanoTime.getAsLong();
            }
        });
        this.sessions = new SessionReads() {
            @Override
            public Map<Long, SessionState> load(Collection<Long> members, Deadline deadline) {
                Map<Long, SessionState> out = new LinkedHashMap<>();
                for (long pid : members) {
                    out.put(pid, states.getOrDefault(pid, SessionState.ABSENT));
                }
                return out;
            }

            @Override
            public boolean isOnline(long playerId, Deadline deadline) {
                return states.get(playerId) == SessionState.ONLINE;
            }
        };
        TeamPushes teamPushes = new TeamPushes(store, this::display, this::push, Runnable::run, metrics, Duration.ofSeconds(3),
                new TeamPushes.MessageIds(TeamServiceFixture.SNAPSHOT, TeamServiceFixture.INVITE, TeamServiceFixture.EVENT));
        this.service = new TeamService(store, sessions, this::display, (ids, deadline) -> {
            Map<Long, Integer> out = new LinkedHashMap<>();
            for (long id : ids) {
                out.put(id, ZONE);
            }
            return out;
        }, () -> {
            long tid = nextTid.incrementAndGet();
            tids.add(tid);
            return tid;
        }, new MatchTeamBattle(match), this::runBackground, teamPushes, metrics, RuleConfig.DEFAULT, () -> stopping);
        match.lockTtlSeconds = LOCK_SECONDS;
        match.zone = ZONE;
    }

    /**
     * {@code team-match-end} 的同步替身：任务在提交它的线程上<b>排队串行</b>执行——任务里再投的任务排在后面，不嵌套
     * （真执行器也不会在 execute 里嵌套执行）。
     */
    private synchronized void runBackground(Runnable task) {
        if (rejectBackground) {
            throw new RejectedExecutionException("team-match-end 已满（测试）");
        }
        background.add(task);
        if (drainingBackground) {
            return;
        }
        drainingBackground = true;
        try {
            Runnable next;
            while ((next = background.poll()) != null) {
                next.run();
            }
        } finally {
            drainingBackground = false;
        }
    }

    private Map<Long, MemberDisplay> display(Collection<Long> ids, Deadline deadline) {
        Map<Long, MemberDisplay> out = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id != null && id != 0) {
                out.put(id, new MemberDisplay(states.get(id) == SessionState.ONLINE, false, 10, 2,
                        "p" + Long.toUnsignedString(id), "", 1));
            }
        }
        return out;
    }

    private CompletionStage<PlayerPushes.Outcome> push(long playerId, MessageContent content) {
        if (content.getMessageId() == TeamServiceFixture.SNAPSHOT) {
            try {
                pushes.add(new Pushed(playerId, TeamSnapshotS2C.parseFrom(content.getSerializedMessage())));
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError(e);
            }
        }
        return CompletableFuture.completedFuture(states.get(playerId) == SessionState.ONLINE
                ? PlayerPushes.Outcome.SENT : PlayerPushes.Outcome.OFFLINE);
    }

    // ---------------------------------------------------------------- id、建队与清理

    /** 下一个没用过的玩家（≥ 2^63）：会话 ONLINE，登记清理。 */
    long player() {
        long id = pidBase + nextPid.incrementAndGet();
        pids.add(id);
        states.put(id, SessionState.ONLINE);
        return id;
    }

    /** 一个从没建过的队伍号（登记清理）。 */
    long unusedTid() {
        long tid = nextTid.incrementAndGet();
        tids.add(tid);
        return tid;
    }

    /**
     * 新建一支 n 人的队伍：第一个人建队当队长，其余按顺序申请入队（join_seq 1..n）；全员在线。建完清空推送记录。
     *
     * @return {@code [team_id, 队长, 队员…]}
     */
    long[] team(int n) {
        long[] out = new long[n + 1];
        for (int i = 1; i <= n; i++) {
            out[i] = player();
        }
        out[0] = create(out[1]);
        for (int i = 2; i <= n; i++) {
            join(out[1], out[0], out[i]);
        }
        takePushes();
        return out;
    }

    long create(long leader) {
        TeamResponse resp = service.createTeam(leader, CreateTeamRequest.getDefaultInstance(), deadline());
        assertThat(resp.hasErrorMessage()).as("create: %s", resp).isFalse();
        return resp.getTeam().getTeamId();
    }

    TeamResponse apply(long applicant, long target) {
        return service.applyJoinTeam(applicant, ApplyJoinTeamRequest.newBuilder().setTargetPlayerId(target).build(), deadline());
    }

    /** 走真实路径入队：申请 → 队长同意。 */
    void join(long leader, long tid, long pid) {
        assertThat(apply(pid, leader).hasErrorMessage()).isFalse();
        TeamResponse resp = service.handleApplication(leader, HandleApplicationRequest.newBuilder().setApplicantId(pid)
                .setApprove(true).setExpectedTeamId(tid).build(), deadline());
        assertThat(resp.hasErrorMessage()).as("approve: %s", resp).isFalse();
    }

    TeamResponse start(long caller, long tid) {
        return start(caller, tid, deadline());
    }

    TeamResponse start(long caller, long tid, Deadline deadline) {
        return service.startTeamMatch(caller, StartTeamMatchRequest.newBuilder().setBattleConfigId(CONFIG).setExpectedTeamId(tid)
                .build(), deadline);
    }

    void cleanup() {
        List<String> keys = new ArrayList<>();
        for (long t : tids) {
            keys.add(RedisKeys.teamRecord(t));
            keys.add(RedisKeys.teamInfo(t));
        }
        for (long p : pids) {
            keys.add(RedisKeys.teamPlayer(p));
            keys.add(RedisKeys.teamInvite(p));
        }
        backend.delete(keys);
    }

    static Deadline deadline() {
        return Deadline.after(3500);
    }

    // ---------------------------------------------------------------- 观察

    /** 队伍记录（不存在为 null）。 */
    TeamRecord record(long tid) {
        return store.read(0, tid, deadline()).record();
    }

    long versionOf(long tid) {
        return store.read(0, tid, deadline()).version();
    }

    /** Redis TIME（毫秒）。 */
    long nowMs() {
        return store.read(0, 0, deadline()).nowMs();
    }

    /** 玩家索引的 epoch（索引必须存在且指向 tid）。 */
    long epochOf(long tid, long pid) {
        Snapshot snap = store.read(pid, tid, deadline());
        assertThat(snap.playerTeamId()).as("玩家 %s 的索引应指向本队", Long.toUnsignedString(pid)).isEqualTo(tid);
        return snap.playerEpoch();
    }

    State state(long tid, long... players) {
        List<Long> ids = new ArrayList<>();
        for (long p : players) {
            ids.add(p);
        }
        Snapshot rec = store.read(0, tid, deadline());
        Map<Long, IndexEntry> indexes = new LinkedHashMap<>();
        for (long p : ids) {
            Snapshot snap = store.read(p, tid, deadline());
            indexes.put(p, new IndexEntry(snap.playerTeamId(), snap.playerEpoch()));
        }
        return new State(rec.version(), rec.record() == null ? ByteString.EMPTY : rec.record().toByteString(), indexes);
    }

    /** 按 S_READ_MEMBERS 读成员索引（断言「索引指向哪里」用）。 */
    MembersSnapshot members(long tid, List<Long> known) {
        return store.readMembers(tid, known, deadline());
    }

    List<Pushed> takePushes() {
        synchronized (pushes) {
            List<Pushed> out = new ArrayList<>(pushes);
            pushes.clear();
            return out;
        }
    }

    /** 取出指定原因的快照推送：接收者 → 推送内容（同一原因不应重复推给同一人）。 */
    static Map<Long, TeamSnapshotS2C> byReason(List<Pushed> pushes, TeamChangeReason reason) {
        Map<Long, TeamSnapshotS2C> out = new LinkedHashMap<>();
        for (Pushed p : pushes) {
            if (p.snapshot().getReason() != reason) {
                continue;
            }
            TeamSnapshotS2C dup = out.put(p.playerId(), p.snapshot());
            assertThat(dup).as("原因 %s 重复推给 %s", reason, Long.toUnsignedString(p.playerId())).isNull();
        }
        return out;
    }

    void resetCounters() {
        hgets.set(0);
        evals.values().forEach(counter -> counter.set(0));
    }

    double matches(String outcome) {
        return meters.get("xm.team.matches").tag("outcome", outcome).counter().count();
    }

    double commitRetries() {
        return meters.get("xm.team.commit.retries").tag("op", TeamMethods.START_TEAM_MATCH).counter().count();
    }

    static String u(long id) {
        return Long.toUnsignedString(id);
    }
}
