package com.game.team.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPushes;
import com.game.discovery.team.TeamRedisFields;
import com.game.proto.MessageContent;
import com.game.proto.team.ApplyJoinTeamRequest;
import com.game.proto.team.CreateTeamRequest;
import com.game.proto.team.HandleApplicationRequest;
import com.game.proto.team.TeamEventS2C;
import com.game.proto.team.TeamInviteS2C;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.team.dispatch.TeamDispatcher;
import com.game.team.match.NoTeamBattle;
import com.game.team.match.TeamBattlePort;
import com.game.team.metrics.TeamMetrics;
import com.game.team.presence.SessionReads;
import com.game.team.proto.TeamRecord;
import com.game.team.push.TeamPushes;
import com.game.team.rules.Decision;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.SessionState;
import com.game.team.store.Bind;
import com.game.team.store.RedissonTeamRedis;
import com.game.team.store.TeamRedis;
import com.game.team.store.TeamScript;
import com.game.team.store.TeamStore;
import com.game.team.view.MemberDisplay;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 服务层真 Redis 测试的夹具（team-spec §10.3；对应基线 service_test.go 的 serviceHarness）。
 *
 * <ul>
 *   <li>存储是真的（Redis DB 12，七段 Lua）；在线态、展示资料、home zone、team_id 发号、推送都是假的、可编程的；</li>
 *   <li>推送执行器是同步的（基线测试把 asyncFn 换成同步执行），推送在 RPC 返回前就已记录，断言是确定的；</li>
 *   <li>并发时序用存储层的测试缝（{@link TeamStore.Hooks}）与包一层的 {@link TeamRedis}（在某段脚本发出前插入动作）做成确定性用例；</li>
 *   <li>id 取随机大号（玩家号与队伍号都 ≥ 2^63），只删自己分配过的 id 的四类键，不 FLUSHDB。</li>
 * </ul>
 */
final class TeamServiceFixture {

    static final int DB = 12;
    static final int ZONE = 1;
    static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    static final int SNAPSHOT = REGISTRY.requireId(TeamMethods.SERVICE, TeamMethods.NOTIFY_TEAM_SNAPSHOT);
    static final int INVITE = REGISTRY.requireId(TeamMethods.SERVICE, TeamMethods.NOTIFY_TEAM_INVITE);
    static final int EVENT = REGISTRY.requireId(TeamMethods.SERVICE, TeamMethods.NOTIFY_TEAM_EVENT);

    static RedissonClient connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        return Redisson.create(config);
    }

    /** 一条记录下来的推送（消息体已按消息号解析）。 */
    record Pushed(long playerId, int messageId, Message message) {

        TeamSnapshotS2C snapshot() {
            assertThat(messageId).isEqualTo(SNAPSHOT);
            return (TeamSnapshotS2C) message;
        }

        TeamEventS2C event() {
            assertThat(messageId).isEqualTo(EVENT);
            return (TeamEventS2C) message;
        }

        TeamInviteS2C invite() {
            assertThat(messageId).isEqualTo(INVITE);
            return (TeamInviteS2C) message;
        }
    }

    final RedissonClient redis;
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final TeamMetrics metrics = new TeamMetrics(meters, false);
    final TeamStore store;
    final TeamService service;
    final TeamDispatcher dispatcher;

    /** mutate 每轮 S_READ 之后（基线 afterReadHook）。 */
    final AtomicReference<Consumer<Bind>> afterRead = new AtomicReference<>(b -> {
    });
    /** S_COMMIT 发出之前（基线 beforeCommitEvalHook）。 */
    final AtomicReference<BeforeCommit> beforeCommit = new AtomicReference<>((d, k, a) -> {
    });
    /** 任意一段脚本发出之前（基线 beforeInvitePruneHook 等：这里按脚本与键判断）。 */
    final AtomicReference<BiConsumer<TeamScript, List<Object>>> beforeEval = new AtomicReference<>((s, k) -> {
    });
    /** 非 null 的返回值顶替这段脚本的真实执行（注入故障用）。 */
    final AtomicReference<java.util.function.BiFunction<TeamScript, List<Object>, CompletionStage<Object>>> evalOverride =
            new AtomicReference<>((s, k) -> null);

    /** 会话四态（缺省：登记过的玩家 ONLINE，其余 ABSENT）；{@link #sessionOverride} 非 null 时整体接管 load。 */
    final Map<Long, SessionState> states = new ConcurrentHashMap<>();
    volatile Function<Collection<Long>, Map<Long, SessionState>> sessionOverride;
    volatile RuntimeException onlineCheckError;
    /** home zone（缺项 = 4019）；{@link #zoneError} 非 null 时查询失败（4030）。 */
    final Map<Long, Integer> zones = new ConcurrentHashMap<>();
    volatile RuntimeException zoneError;
    /** team_id 发号；{@link #idError} 非 null 时发号失败。 */
    private final AtomicLong nextTid;
    volatile RuntimeException idError;
    volatile TeamBattlePort battle = NoTeamBattle.INSTANCE;

    private final List<Pushed> pushes = Collections.synchronizedList(new ArrayList<>());
    private final long pidBase;
    private final Set<Long> pids = ConcurrentHashMap.newKeySet();
    private final Set<Long> tids = ConcurrentHashMap.newKeySet();

    @FunctionalInterface
    interface BeforeCommit {
        void accept(Decision decision, List<Object> keys, List<byte[]> args);
    }

    TeamServiceFixture(RedissonClient redis) {
        this.redis = redis;
        long r = ThreadLocalRandom.current().nextLong(1L << 38) * 16_384;
        this.pidBase = Long.MIN_VALUE + (1L << 55) + r;
        this.nextTid = new AtomicLong(Long.MIN_VALUE + (1L << 56) + r);
        TeamRedis real = new RedissonTeamRedis(redis);
        TeamRedis intercepting = new TeamRedis() {
            @Override
            public CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
                beforeEval.get().accept(script, keys);
                CompletionStage<Object> injected = evalOverride.get().apply(script, keys);
                return injected != null ? injected : real.eval(script, keys, args);
            }

            @Override
            public CompletionStage<byte[]> hget(String key, String field) {
                return real.hget(key, field);
            }
        };
        this.store = new TeamStore(intercepting, new TeamStore.Hooks() {
            @Override
            public void afterRead(Bind bind) {
                afterRead.get().accept(bind);
            }

            @Override
            public void beforeCommitEval(Decision decision, List<Object> keys, List<byte[]> args) {
                beforeCommit.get().accept(decision, keys, args);
            }
        });
        SessionReads sessions = new SessionReads() {
            @Override
            public Map<Long, SessionState> load(Collection<Long> members, Deadline deadline) {
                Function<Collection<Long>, Map<Long, SessionState>> override = sessionOverride;
                if (override != null) {
                    return override.apply(members);
                }
                Map<Long, SessionState> out = new LinkedHashMap<>();
                for (long pid : members) {
                    out.put(pid, states.getOrDefault(pid, SessionState.ABSENT));
                }
                return out;
            }

            @Override
            public boolean isOnline(long playerId, Deadline deadline) {
                RuntimeException e = onlineCheckError;
                if (e != null) {
                    throw e;
                }
                return states.get(playerId) == SessionState.ONLINE;
            }
        };
        TeamPushes teamPushes = new TeamPushes(store, this::display, this::push, Runnable::run, metrics,
                Duration.ofSeconds(3), new TeamPushes.MessageIds(SNAPSHOT, INVITE, EVENT));
        this.service = new TeamService(store, sessions, this::display, (ids, deadline) -> {
            RuntimeException e = zoneError;
            if (e != null) {
                throw e;
            }
            Map<Long, Integer> out = new LinkedHashMap<>();
            for (long id : ids) {
                Integer z = zones.get(id);
                if (z != null) {
                    out.put(id, z);
                }
            }
            return out;
        }, () -> {
            RuntimeException e = idError;
            if (e != null) {
                throw e;
            }
            long tid = nextTid.incrementAndGet();
            tids.add(tid);
            return tid;
        }, playerBattle(), teamPushes, metrics, RuleConfig.DEFAULT);
        this.dispatcher = new TeamDispatcher(REGISTRY, service, Runnable::run, metrics, 3500);
    }

    private TeamBattlePort playerBattle() {
        return configId -> battle.teamSizeFor(configId);
    }

    /** 展示缓存：在线 = 会话 ONLINE；资料是固定的假值。 */
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
        Message msg;
        try {
            if (content.getMessageId() == SNAPSHOT) {
                msg = TeamSnapshotS2C.parseFrom(content.getSerializedMessage());
            } else if (content.getMessageId() == INVITE) {
                msg = TeamInviteS2C.parseFrom(content.getSerializedMessage());
            } else if (content.getMessageId() == EVENT) {
                msg = TeamEventS2C.parseFrom(content.getSerializedMessage());
            } else {
                throw new AssertionError("未知推送号 " + content.getMessageId());
            }
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
        pushes.add(new Pushed(playerId, content.getMessageId(), msg));
        return CompletableFuture.completedFuture(states.get(playerId) == SessionState.ONLINE
                ? PlayerPushes.Outcome.SENT : PlayerPushes.Outcome.OFFLINE);
    }

    // ---------------------------------------------------------------- id 与清理

    /** 第 n 号玩家（≥ 2^63），登记清理。 */
    long pid(int n) {
        long id = pidBase + n;
        pids.add(id);
        return id;
    }

    /** 一个从没建过的队伍号（登记清理）。 */
    long unusedTid() {
        long tid = nextTid.incrementAndGet();
        tids.add(tid);
        return tid;
    }

    /** 登记玩家：会话 ONLINE、home zone。 */
    void addPlayers(int zone, long... ids) {
        for (long id : ids) {
            states.put(id, SessionState.ONLINE);
            zones.put(id, zone);
        }
    }

    List<String> allKeys() {
        List<String> keys = new ArrayList<>();
        for (long t : tids) {
            keys.add(RedisKeys.teamRecord(t));
            keys.add(RedisKeys.teamInfo(t));
        }
        for (long p : pids) {
            keys.add(RedisKeys.teamPlayer(p));
            keys.add(RedisKeys.teamInvite(p));
        }
        return keys;
    }

    void cleanup() {
        List<String> keys = allKeys();
        for (int i = 0; i < keys.size(); i += 256) {
            redis.getKeys().delete(keys.subList(i, Math.min(keys.size(), i + 256)).toArray(String[]::new));
        }
    }

    // ---------------------------------------------------------------- RPC 便捷方法

    static Deadline deadline() {
        return Deadline.after(3500);
    }

    long create(long leader) {
        TeamResponse resp = service.createTeam(leader, CreateTeamRequest.getDefaultInstance(), deadline());
        assertThat(resp.hasErrorMessage()).as("create: %s", resp).isFalse();
        assertThat(resp.getTeam().getTeamId()).isNotZero();
        return resp.getTeam().getTeamId();
    }

    TeamResponse apply(long applicant, long target) {
        return service.applyJoinTeam(applicant, ApplyJoinTeamRequest.newBuilder().setTargetPlayerId(target).build(),
                deadline());
    }

    /** 走真实路径入队：申请 → 队长同意。 */
    void join(long leader, long tid, long pid) {
        assertThat(apply(pid, leader).hasErrorMessage()).isFalse();
        TeamResponse resp = service.handleApplication(leader, HandleApplicationRequest.newBuilder().setApplicantId(pid)
                .setApprove(true).setExpectedTeamId(tid).build(), deadline());
        assertThat(resp.hasErrorMessage()).as("approve: %s", resp).isFalse();
    }

    List<Pushed> takePushes() {
        synchronized (pushes) {
            List<Pushed> out = new ArrayList<>(pushes);
            pushes.clear();
            return out;
        }
    }

    ClientReply dispatch(int messageId, SessionContext session, ByteString body) {
        ClientCall.Builder call = ClientCall.newBuilder().setMessageId(messageId).setBody(body);
        if (session != null) {
            call.setSession(session);
        }
        return dispatcher.dispatch(call.build()).join();
    }

    // ---------------------------------------------------------------- Redis 读写（断言 / 造数，绕过 Lua）

    String hget(String key, String field) {
        return redis.<String, String>getMap(key, StringCodec.INSTANCE).get(field);
    }

    void hset(String key, String field, String value) {
        redis.<String, String>getMap(key, StringCodec.INSTANCE).put(field, value);
    }

    long epochOf(long pid) {
        return Long.parseUnsignedLong(hget(RedisKeys.teamPlayer(pid), TeamRedisFields.EPOCH));
    }

    String tidOf(long pid) {
        return hget(RedisKeys.teamPlayer(pid), TeamRedisFields.TID);
    }

    long versionOf(long tid) {
        return Long.parseUnsignedLong(hget(RedisKeys.teamRecord(tid), TeamRedisFields.VER));
    }

    boolean exists(String key) {
        return redis.getKeys().countExists(key) == 1;
    }

    void del(String... keys) {
        redis.getKeys().delete(keys);
    }

    long ttlSeconds(String key) {
        long ms = redis.getKeys().remainTimeToLive(key);
        return ms < 0 ? ms : ms / 1000;
    }

    void expireSeconds(String key, long seconds) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, "return redis.call('EXPIRE', KEYS[1], ARGV[1])",
                RScript.ReturnType.INTEGER, List.<Object>of(key), Long.toString(seconds));
    }

    TeamRecord loadRecord(long tid) {
        byte[] raw = redis.<byte[], byte[]>getMap(RedisKeys.teamRecord(tid), ByteArrayCodec.INSTANCE)
                .get(TeamRedisFields.PB.getBytes(StandardCharsets.US_ASCII));
        assertThat(raw).as("记录 %s 不存在", Long.toUnsignedString(tid)).isNotNull();
        try {
            return TeamRecord.parseFrom(raw);
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    /** 直接改写记录的 pb（ver 不变；造「过期的申请」这类真 Redis 拨不动时钟做不到的状态）。 */
    void writeRecord(long tid, TeamRecord rec) {
        redis.<byte[], byte[]>getMap(RedisKeys.teamRecord(tid), ByteArrayCodec.INSTANCE)
                .put(TeamRedisFields.PB.getBytes(StandardCharsets.US_ASCII), rec.toByteArray());
    }

    void zadd(String key, double score, String member) {
        redis.<String>getScoredSortedSet(key, StringCodec.INSTANCE).add(score, member);
    }

    Double zscore(String key, String member) {
        return redis.<String>getScoredSortedSet(key, StringCodec.INSTANCE).getScore(member);
    }

    List<String> zmembers(String key) {
        return new ArrayList<>(redis.<String>getScoredSortedSet(key, StringCodec.INSTANCE).readAll());
    }

    /** Redis TIME（毫秒）。 */
    long nowMs() {
        Long ms = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY,
                "local t = redis.call('TIME') return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)",
                RScript.ReturnType.INTEGER);
        return ms;
    }

    /** 忙等到 Redis TIME 严格大于 {@code ms}。 */
    void waitRedisAfter(long ms) {
        while (Long.compareUnsigned(nowMs(), ms) <= 0) {
            LockSupport.parkNanos(500_000);
        }
    }

    /** 本夹具分配过的全部键的 DUMP 与 PTTL（替代基线 mr.Dump）。 */
    record KeyState(Map<String, String> dumps, Map<String, Long> pttls) {
    }

    KeyState state() {
        List<String> keys = allKeys();
        List<Object> reply = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, """
                local out = {}
                for i = 1, #KEYS do
                  out[#out + 1] = redis.call('DUMP', KEYS[i]) or ''
                  out[#out + 1] = redis.call('PTTL', KEYS[i])
                end
                return out
                """, RScript.ReturnType.MULTI, new ArrayList<Object>(keys));
        Map<String, String> dumps = new LinkedHashMap<>();
        Map<String, Long> pttls = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            dumps.put(keys.get(i), HexFormat.of().formatHex((byte[]) reply.get(2 * i)));
            pttls.put(keys.get(i), (Long) reply.get(2 * i + 1));
        }
        return new KeyState(dumps, pttls);
    }

    /** 内容逐字节相同，且没有任何键被续期。 */
    void assertUnchanged(KeyState before) {
        KeyState after = state();
        assertThat(after.dumps()).isEqualTo(before.dumps());
        for (Map.Entry<String, Long> e : before.pttls().entrySet()) {
            long b = e.getValue();
            long a = after.pttls().get(e.getKey());
            if (b < 0) {
                assertThat(a).as("TTL of %s", e.getKey()).isEqualTo(b);
            } else {
                assertThat(a).as("TTL of %s 不应被续期", e.getKey()).isLessThanOrEqualTo(b);
            }
        }
    }

    /** 执行一段 S_COMMIT（基线测试用来模拟「同一段 EVAL 被重发」：钩子里先执行一次，store 再发一次）。 */
    void evalCommit(List<Object> keys, List<byte[]> args) {
        redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, TeamScript.COMMIT.lua(),
                RScript.ReturnType.MULTI, new ArrayList<>(keys), args.toArray());
    }

    static String u(long id) {
        return Long.toUnsignedString(id);
    }

    static DependencyException redisDown() {
        return new DependencyException("redis down");
    }
}
