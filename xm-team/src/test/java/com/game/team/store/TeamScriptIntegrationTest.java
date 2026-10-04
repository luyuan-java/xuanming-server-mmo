package com.game.team.store;

import static com.game.team.store.TeamRedisFixture.ZONE;
import static com.game.team.store.TeamRedisFixture.u;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamRedisFields;
import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Decision;
import com.game.team.rules.Op;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.TeamRules;
import com.game.team.rules.TeamTips;
import com.game.team.store.TeamReplies.CommitStatus;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNode;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * team-spec §10.2 的 Java 增项（真 Redis，默认跳过；{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 12，只删自己的键）：
 * <ul>
 *   <li>ByteArrayCodec 往返任意字节的 pb（含 0x00、非法 UTF-8）；</li>
 *   <li>重放同一段 S_COMMIT（模拟 Redisson 响应超时后重发）回 {@code {0}}，不重复写；建队的重发照搬基线回 4030（§8.1 第 9 条）；</li>
 *   <li>S_INVITE_LIST 以 READ_WRITE 执行（以 READ_ONLY 执行时 Redis 7 拒绝其中的写）。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class TeamScriptIntegrationTest {

    private static RedissonClient redis;
    private final List<TeamRedisFixture> fixtures = new ArrayList<>();

    @BeforeAll
    static void connect() {
        redis = TeamRedisFixture.connect();
    }

    @AfterAll
    static void shutdown() {
        redis.shutdown();
    }

    @AfterEach
    void tearDown() {
        fixtures.forEach(TeamRedisFixture::cleanup);
    }

    private TeamRedisFixture fixture(TeamStore.Hooks hooks) {
        TeamRedisFixture fx = new TeamRedisFixture(redis, hooks);
        fixtures.add(fx);
        return fx;
    }

    private static boolean validUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    @Test
    void 记录与投影的任意字节经ByteArrayCodec往返不变() throws Exception {
        TeamRedisFixture fx = fixture(TeamStore.Hooks.NONE);
        long leader = fx.pid(1), tid = fx.tid(1); // 都 ≥ 2^63：varint 里大多是 0x80-0xFF 的字节
        fx.mustCreate(leader, tid);
        TeamRecord before = fx.loadRecord(tid);
        TeamRecord odd = before.toBuilder()
                .setMatchLockToken("a\u0000b")                 // UTF-8 里的 0x00
                // packed 里的 0x00；2^64-1 的 varint 是 9 个 0xFF 加 0x01（0xFF 在 UTF-8 里永远非法）
                .clearMatchLockRoster().addMatchLockRoster(0).addMatchLockRoster(-1L).addMatchLockRoster(leader)
                .build();
        byte[] bytes = odd.toByteArray();
        assertThat(bytes).contains((byte) 0x00);
        assertThat(validUtf8(bytes)).as("造出来的 pb 必须不是合法 UTF-8").isFalse();
        assertThat(new String(bytes, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8))
                .as("若用 StringCodec（UTF-8）会把字节弄坏").isNotEqualTo(bytes);

        Decision d = new Decision(TeamTips.OK, 0, true, odd, List.of(), List.of(leader), List.of(), List.of(), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED, leader, false, false, List.of(), 0, 0);
        long now = fx.nowMs();
        assertThat(fx.store.commit(tid, fx.ver(tid), d, now, before, fx.deadline()).status()).isEqualTo(CommitStatus.OK);

        assertThat(fx.hgetBytes(RedisKeys.teamRecord(tid), TeamRedisFields.PB)).as("Redis 里的字节原样").isEqualTo(bytes);
        Snapshot snap = fx.store.read(leader, tid, fx.deadline());
        assertThat(snap.record()).as("S_READ 读回的记录原样").isEqualTo(odd);
        byte[] projection = fx.getBytes(RedisKeys.teamInfo(tid));
        assertThat(projection).isEqualTo(TeamStore.projectionOf(odd).toByteArray());
        assertThat(TeamInfo.parseFrom(projection).getLeaderId()).isEqualTo(leader);
        MembersSnapshot members = fx.store.readMembers(tid, List.of(leader), fx.deadline());
        assertThat(members.record()).isEqualTo(odd);
    }

    @Test
    void 重放同一段S_COMMIT回0且不重复写() {
        TeamRedisFixture fx = fixture(TeamStore.Hooks.NONE);
        long tid = fx.tid(1), p1 = fx.pid(1), p6 = fx.pid(6);
        fx.mustCreate(p1, tid);
        TeamRecord rec = fx.loadRecord(tid);
        Decision d = TeamRules.apply(Op.apply(p6, ZONE), rec, fx.nowMs(), null, RuleConfig.DEFAULT);
        CommitSets.Call call = CommitSets.build(tid, fx.ver(tid), d);

        List<Object> first = TeamReplies.multi(fx.teamRedis.eval(TeamScript.COMMIT, call.keys(), call.args())
                .toCompletableFuture().join(), "S_COMMIT");
        assertThat(first.get(0)).isEqualTo(1L);
        TeamRedisFixture.KeyState afterFirst = fx.state();

        List<Object> resent = TeamReplies.multi(fx.teamRedis.eval(TeamScript.COMMIT, call.keys(), call.args())
                .toCompletableFuture().join(), "S_COMMIT");
        assertThat(resent).containsExactly(0L);
        fx.assertUnchanged(afterFirst);
        assertThat(fx.ver(tid)).isEqualTo("2");
    }

    /** 提交结果未知、同一段 EVAL 被重发（team-spec §3.15）：一般操作按重放语义收敛；建队照搬基线回 4030（§8.1 第 9 条，D20 未采纳）。 */
    @Test
    void 提交被重发_一般操作重读后收敛_建队回4030但队伍已建好() {
        AtomicReference<String> replayOnce = new AtomicReference<>();
        AtomicReference<TeamRedisFixture> self = new AtomicReference<>();
        TeamRedisFixture fx = fixture(new TeamStore.Hooks() {
            @Override
            public void beforeCommitEval(Decision decision, List<Object> keys, List<byte[]> args) {
                String key = (String) keys.get(0);
                if (key.equals(replayOnce.get())) {
                    replayOnce.set(null);
                    // 第一次发送已在服务器执行、回复丢失；随后 TeamStore 再发同一段 = Redisson 重发
                    self.get().teamRedis.eval(TeamScript.COMMIT, keys, args).toCompletableFuture().join();
                }
            }
        });
        self.set(fx);
        long tid = fx.tid(1), p1 = fx.pid(1), p6 = fx.pid(6);
        fx.mustCreate(p1, tid);

        replayOnce.set(RedisKeys.teamRecord(tid));
        MutateResult apply = fx.mutate(Bind.target(p6, tid), Op.apply(p6, ZONE));
        assertThat(apply.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(apply.conflicts()).as("重发回 {0}，重读后按重放语义（刷新过期时间）再提交").isEqualTo(1);
        assertThat(apply.commit().version()).isEqualTo(3);
        assertThat(fx.loadRecord(tid).getApplicationsList()).hasSize(1);

        long tid2 = fx.tid(2), p2 = fx.pid(2);
        replayOnce.set(RedisKeys.teamRecord(tid2));
        MutateResult create = fx.mutate(Bind.create(p2, tid2), Op.create(p2, tid2, ZONE));
        assertThat(create.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(create.code()).as("重读发现新 tid 已有记录，走发号器故障分支").isEqualTo(TeamTips.INTERNAL);
        assertThat(create.conflicts()).isEqualTo(1);
        assertThat(fx.hget(RedisKeys.teamPlayer(p2), TeamRedisFields.TID)).as("但队伍已经建好，自由读能看到").isEqualTo(u(tid2));
        FreeRead view = fx.store.readFree(p2, fx.deadline());
        assertThat(view.snapshot().playerTeamId()).isEqualTo(tid2);
        assertThat(view.snapshot().record().getLeaderId()).isEqualTo(p2);
    }

    @Test
    void 邀请列表脚本以READ_WRITE执行() {
        List<TeamScript> issued = new ArrayList<>();
        TeamRedisFixture fx = fixture(TeamStore.Hooks.NONE);
        TeamRedis recording = new TeamRedis() {
            @Override
            public CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
                issued.add(script);
                return fx.teamRedis.eval(script, keys, args);
            }

            @Override
            public CompletionStage<byte[]> hget(String key, String field) {
                return fx.teamRedis.hget(key, field);
            }
        };
        long p4 = fx.pid(4), stale = fx.tid(9);
        String key = RedisKeys.teamInvite(p4);
        fx.zadd(key, fx.nowMs() - 1, u(stale));

        InviteList list = new TeamStore(recording).listInvites(p4, fx.deadline());
        assertThat(issued).containsExactly(TeamScript.INVITE_LIST);
        assertThat(TeamScript.INVITE_LIST.writes()).isTrue();
        assertThat(list.entries()).isEmpty();
        assertThat(fx.zscore(key, u(stale))).as("过期项真的被删了").isNull();

        // 以 READ_ONLY 执行同一段脚本：Redis 7 上 Redisson 发 EVALSHA_RO，服务器拒绝脚本里的写——模式选错就写不进去
        String version = redis.getRedisNodes(RedisNodes.SINGLE).getInstance().info(RedisNode.InfoSection.SERVER)
                .get("redis_version");
        assumeThat(Integer.parseInt(version.substring(0, version.indexOf('.')))).as("redis_version=" + version)
                .isGreaterThanOrEqualTo(7);
        fx.zadd(key, fx.nowMs() - 1, u(stale));
        AtomicBoolean ran = new AtomicBoolean();
        assertThatThrownBy(() -> {
            redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, TeamScript.INVITE_LIST.lua(),
                    RScript.ReturnType.MULTI, List.<Object>of(key));
            ran.set(true);
        }).hasMessageContaining("Write commands are not allowed from read-only scripts");
        assertThat(ran).isFalse();
        assertThat(fx.zscore(key, u(stale))).isNotNull();
    }
}
