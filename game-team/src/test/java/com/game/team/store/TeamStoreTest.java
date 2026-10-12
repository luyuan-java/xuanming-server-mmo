package com.game.team.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamMemberRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Op;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.SessionState;
import com.game.team.rules.TeamTips;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link TeamStore} 的控制流与故障映射（team-spec §6.4 的约定），用假 {@link TeamRedis} 编排回复，不起 Redis：
 * Redis 失败 / 超出预算 → {@link DependencyException}；提交前预算过期 → 4029 且不发提交；冲突累计 3 次 → 4029；
 * 会话读取违约 → 全员 UNKNOWN；自由读失败仍带出 healed；S_READ_MEMBERS 持续变化 → {@link MembersChangedException}。
 *
 * <p>整队开战的部分（team-spec §1.7.6）：开战锁钉版本提交（不重读不重算、冲突即 retry、{@code {-2}} 修复钉在同一版本、结果未知抛故障）；
 * EndMatch 的专用循环（停止分支不写、冲突 / 故障退避重读且没有 3 次上限、修复后立即重读、每轮独立 2 s 预算、110 s 单调截止、
 * 退避被中断即停）；单轮清锁受调用方预算约束。真脚本上的同一组行为在 {@code TeamStoreIntegrationTest}。
 */
class TeamStoreTest {

    private static final long NOW = 1_900_000_000_000L;
    private static final long TID = Long.MIN_VALUE + 5; // ≥ 2^63
    private static final long A = Long.MIN_VALUE + 11, B = Long.MIN_VALUE + 12, C = Long.MIN_VALUE + 13;

    /** 按脚本编排回复的假 Redis；回复是 Throwable 时以异常完成，是 {@link #NEVER} 时永不完成。 */
    private static final class FakeRedis implements TeamRedis {
        static final Object NEVER = new Object();

        interface Handler {
            Object reply(TeamScript script, List<Object> keys, List<byte[]> args);
        }

        final List<TeamScript> calls = Collections.synchronizedList(new ArrayList<>());
        Handler onEval = (s, k, a) -> new IllegalStateException("未编排的脚本 " + s);
        java.util.function.Supplier<Object> onHget = () -> null;

        @Override
        public CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
            calls.add(script);
            return complete(onEval.reply(script, keys, args));
        }

        @Override
        public CompletionStage<byte[]> hget(String key, String field) {
            Object r = onHget.get();
            @SuppressWarnings("unchecked")
            CompletionStage<byte[]> stage = (CompletionStage<byte[]>) (CompletionStage<?>) complete(r);
            return stage;
        }

        long count(TeamScript script) {
            return calls.stream().filter(s -> s == script).count();
        }

        private static CompletionStage<Object> complete(Object r) {
            if (r == NEVER) {
                return new CompletableFuture<>();
            }
            return r instanceof Throwable t ? CompletableFuture.failedFuture(t) : CompletableFuture.completedFuture(r);
        }
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] u(long id) {
        return b(Long.toUnsignedString(id));
    }

    private static TeamRecord team(long... members) {
        TeamRecord.Builder r = TeamRecord.newBuilder().setTeamId(TID).setLeaderId(members[0]).setZoneId(1)
                .setCreatedAtMs(NOW - 1000);
        for (int i = 0; i < members.length; i++) {
            r.addMembers(TeamMemberRecord.newBuilder().setPlayerId(members[i]).setZoneId(1).setJoinedAtMs(NOW - 1000)
                    .setJoinSeq(i + 1));
        }
        return r.setNextJoinSeq(members.length + 1).build();
    }

    /** S_READ 回复：tidNow 为 null 表示索引缺失，rec 为 null 表示记录缺失。 */
    private static List<Object> read(Long tidNow, long epoch, long ver, TeamRecord rec) {
        return new ArrayList<>(List.of(tidNow == null ? b("") : u(tidNow), b(Long.toString(epoch)),
                rec == null ? b("") : b(Long.toString(ver)), rec == null ? b("") : rec.toByteArray(),
                rec == null ? -2L : 86_000L, b(Long.toString(NOW))));
    }

    private static Deadline deadline() {
        return Deadline.after(5_000);
    }

    private static MutateResult mutate(TeamStore store, Bind bind, Op op, SessionLoader sessions) {
        return store.mutate(bind, op, sessions, RuleConfig.DEFAULT, deadline());
    }

    // ================================================================ 故障 → DependencyException

    @Test
    void Redis失败或超出预算_mutate抛依赖故障() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        redis.onEval = (s, k, a) -> new RuntimeException("boom");
        assertThatThrownBy(() -> mutate(store, Bind.target(C, TID), Op.apply(C, 1), SessionLoader.NONE))
                .isInstanceOf(DependencyException.class).hasRootCauseMessage("boom");

        redis.onEval = (s, k, a) -> s == TeamScript.READ ? read(null, NOW, 1, team(A, B)) : new RuntimeException("commit boom");
        assertThatThrownBy(() -> mutate(store, Bind.target(C, TID), Op.apply(C, 1), SessionLoader.NONE))
                .isInstanceOf(DependencyException.class);

        redis.onEval = (s, k, a) -> FakeRedis.NEVER;
        assertThatThrownBy(() -> store.mutate(Bind.target(C, TID), Op.apply(C, 1), SessionLoader.NONE, RuleConfig.DEFAULT,
                Deadline.after(30))).isInstanceOf(DependencyException.class).hasMessageContaining("超过请求预算");
    }

    @Test
    void 回复形状不对或下标越界_是故障() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        redis.onEval = (s, k, a) -> List.of(b("1"));
        assertThatThrownBy(() -> store.read(A, TID, deadline())).isInstanceOf(DependencyException.class);

        TeamRecord withApp = team(A, B).toBuilder().addApplications(com.game.team.proto.TeamApplicationRecord.newBuilder()
                .setPlayerId(C).setZoneId(1).setAppliedAtMs(NOW).setExpireAtMs(NOW + 60_000)).build();
        redis.onEval = (s, k, a) -> s == TeamScript.READ ? read(TID, 7, 1, withApp) : List.of(-1L, 5L);
        assertThatThrownBy(() -> mutate(store, Bind.caller(A, TID), Op.handleApplication(A, C, true), SessionLoader.NONE))
                .isInstanceOf(DependencyException.class).hasMessageContaining("越界");
    }

    // ================================================================ 提交前预算过期 / 冲突耗尽

    @Test
    void 提交前预算已过期_回4029且不发提交() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        redis.onEval = (s, k, a) -> read(null, NOW, 1, team(A, B));
        SessionLoader slow = (members, deadline) -> {
            while (!deadline.expired()) {
                Thread.onSpinWait();
            }
            return Map.of();
        };
        MutateResult res = store.mutate(Bind.target(C, TID), Op.apply(C, 1), slow, RuleConfig.DEFAULT, Deadline.after(30));
        assertThat(res.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(res.code()).isEqualTo(TeamTips.STATE_CHANGED);
        assertThat(res.decision().code()).as("不是规则拒绝").isZero();
        assertThat(redis.count(TeamScript.COMMIT)).isZero();
    }

    @Test
    void 版本冲突累计三次回4029_不退避立即重读() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        redis.onEval = (s, k, a) -> s == TeamScript.READ ? read(null, NOW, 1, team(A, B)) : List.of(0L);
        MutateResult res = mutate(store, Bind.target(C, TID), Op.apply(C, 1), SessionLoader.NONE);
        assertThat(res.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(res.code()).isEqualTo(TeamTips.STATE_CHANGED);
        assertThat(res.conflicts()).isEqualTo(TeamStore.COMMIT_RETRIES);
        assertThat(redis.count(TeamScript.COMMIT)).isEqualTo(3);
        assertThat(redis.count(TeamScript.READ)).isEqualTo(3);
    }

    // ================================================================ 绑定 / 记录缺失 / 建队撞号

    @Test
    void 绑定与记录缺失() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        redis.onEval = (s, k, a) -> s == TeamScript.READ ? read(null, NOW, 1, team(A, B)) : 1L;
        MutateResult unbound = mutate(store, Bind.caller(A, 0), Op.leave(A), SessionLoader.NONE);
        assertThat(unbound.outcome()).as("expected=0 一律未绑定").isEqualTo(Outcome.NOT_BOUND);
        assertThat(unbound.snapshot().playerEpoch()).isEqualTo(NOW);
        assertThat(unbound.decision().code()).isZero();

        redis.onEval = (s, k, a) -> s == TeamScript.READ ? read(TID, 9, 0, null) : 1L;
        MutateResult missing = mutate(store, Bind.caller(A, TID), Op.leave(A), SessionLoader.NONE);
        assertThat(missing.outcome()).isEqualTo(Outcome.RECORD_MISSING);
        assertThat(missing.healedOrphan()).isTrue();
        assertThat(redis.count(TeamScript.HEAL_ORPHAN)).isEqualTo(1);

        redis.calls.clear();
        MutateResult targetMissing = mutate(store, Bind.target(C, TID + 1), Op.respondInvite(C, true), SessionLoader.NONE);
        assertThat(targetMissing.outcome()).isEqualTo(Outcome.RECORD_MISSING);
        assertThat(targetMissing.healedOrphan()).as("调用者索引不指向它：不治").isFalse();
        assertThat(redis.count(TeamScript.HEAL_ORPHAN)).isZero();

        redis.onEval = (s, k, a) -> read(null, NOW, 1, team(B));
        MutateResult dup = mutate(store, Bind.create(A, TID), Op.create(A, TID, 1), SessionLoader.NONE);
        assertThat(dup.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(dup.code()).isEqualTo(TeamTips.INTERNAL);
    }

    // ================================================================ 会话读取违约 → 全员 UNKNOWN

    @Test
    void 会话读取抛异常_按全员UNKNOWN_不惰性转让() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        AtomicInteger commits = new AtomicInteger();
        redis.onEval = (s, k, a) -> {
            if (s == TeamScript.READ) {
                return read(TID, 9, 4, team(A, B));
            }
            commits.incrementAndGet();
            return new ArrayList<>(List.of(1L, b("5"), u(TID), b("1"), u(TID), b("2")));
        };
        SessionLoader broken = (members, deadline) -> {
            throw new IllegalStateException("违约");
        };
        MutateResult res = mutate(store, Bind.caller(B, TID), Op.refresh(B), broken);
        assertThat(res.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(commits).hasValue(0);

        // 对照：队长确实 ABSENT 时会惰性转让并提交
        MutateResult transferred = mutate(store, Bind.caller(B, TID), Op.refresh(B),
                (members, deadline) -> Map.of(A, SessionState.ABSENT, B, SessionState.ONLINE));
        assertThat(transferred.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(transferred.commit().decision().reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_LEADER_OFFLINE_TRANSFERRED);
        assertThat(transferred.commit().version()).isEqualTo(5);
        assertThat(transferred.commit().nowMs()).isEqualTo(NOW);
    }

    // ================================================================ {-2} 修复后重算

    @Test
    void 保留成员索引错位_修复落盘后重读重算原操作() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        long other = TID + 100;
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        redis.onEval = (s, k, a) -> {
            if (s == TeamScript.READ) {
                return reads.getAndIncrement() == 0 ? read(null, NOW, 1, team(A, B)) : read(null, NOW, 2, team(A));
            }
            return switch (commits.getAndIncrement()) {
                case 0 -> List.of(-2L, 2L);                                                   // 原操作：B 的索引不是本队
                case 1 -> new ArrayList<>(List.of(1L, b("2"), u(TID), b("1"), u(other), b("8"))); // 修复：K=[A] L=[B]
                default -> new ArrayList<>(List.of(1L, b("3"), u(TID), b("1")));               // 重算后的原操作：K=[A]
            };
        };
        MutateResult res = mutate(store, Bind.target(C, TID), Op.apply(C, 1), SessionLoader.NONE);
        assertThat(res.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(res.repairs()).hasSize(1);
        CommitResult fix = res.repairs().get(0);
        assertThat(fix.decision().reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_HEALED);
        assertThat(fix.decision().left()).containsExactly(B);
        assertThat(fix.indexes().get(B)).isEqualTo(new IndexEntry(other, 8));
        assertThat(res.commit().version()).isEqualTo(3);
        assertThat(res.conflicts()).isZero();
    }

    // ================================================================ 自由读

    @Test
    void 自由读_先治过孤儿再失败_仍带出healed() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        AtomicInteger hgets = new AtomicInteger();
        redis.onHget = () -> hgets.getAndIncrement() == 0 ? u(TID) : new RuntimeException("hget boom");
        redis.onEval = (s, k, a) -> s == TeamScript.READ ? read(TID, 9, 0, null) : 1L;
        FreeRead r = store.readFree(A, deadline());
        assertThat(r.status()).isEqualTo(FreeRead.Status.FAILED);
        assertThat(r.healed()).isTrue();
        assertThat(r.error()).isInstanceOf(DependencyException.class);
        assertThat(r.snapshot()).isNull();
    }

    @Test
    void 自由读_索引持续变化_三轮后不稳定() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        redis.onHget = () -> u(TID);
        redis.onEval = (s, k, a) -> read(TID + 1, 9, 1, team(A));
        FreeRead r = store.readFree(A, deadline());
        assertThat(r.status()).isEqualTo(FreeRead.Status.UNSTABLE);
        assertThat(r.healed()).isFalse();
        assertThat(redis.count(TeamScript.READ)).isEqualTo(TeamStore.FREE_READ_RETRIES);
    }

    @Test
    void 自由读_索引tid不是十进制是故障_缺失为无队() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        redis.onHget = () -> b("abc");
        assertThat(store.readFree(A, deadline()).status()).isEqualTo(FreeRead.Status.FAILED);

        redis.onHget = () -> null;
        redis.onEval = (s, k, a) -> read(null, NOW, 0, null);
        FreeRead r = store.readFree(A, deadline());
        assertThat(r.ok()).isTrue();
        assertThat(r.snapshot().playerTeamId()).isZero();
        assertThat(r.snapshot().playerEpoch()).isEqualTo(NOW);
        assertThat(r.snapshot().record()).isNull();
    }

    // ================================================================ S_READ_MEMBERS

    private static List<Object> members(long ver, TeamRecord rec, int known) {
        List<Object> out = new ArrayList<>(List.of(b(Long.toString(ver)), rec.toByteArray(), b(Long.toString(NOW))));
        for (int i = 0; i < known; i++) {
            out.add(u(TID));
            out.add(b("1"));
        }
        return out;
    }

    @Test
    void 读成员_成员表持续变化抛MembersChanged_长度不对是故障() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        TeamRecord[] seq = {team(A, B), team(A, B, C), team(A)};
        AtomicInteger n = new AtomicInteger();
        redis.onEval = (s, k, a) -> members(1, seq[n.getAndIncrement() % 3], k.size() - 1);
        assertThatThrownBy(() -> store.readMembers(TID, List.of(A), deadline())).isInstanceOf(MembersChangedException.class);
        assertThat(redis.count(TeamScript.READ_MEMBERS)).isEqualTo(TeamStore.READ_MEMBERS_RETRIES);

        redis.onEval = (s, k, a) -> members(1, team(A), 0);
        assertThatThrownBy(() -> store.readMembers(TID, List.of(A), deadline())).isInstanceOf(DependencyException.class);
    }

    // ================================================================ 其余脚本的返回值

    @Test
    void 续期_自愈_剪除按返回值1判定() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        redis.onEval = (s, k, a) -> 1L;
        Snapshot snap = new Snapshot(A, TID, 9, TID, 3, team(A, B), 100, NOW);
        assertThat(store.touch(snap, deadline())).isTrue();
        assertThat(store.healOrphan(A, TID, deadline())).isTrue();
        assertThat(store.pruneInvite(A, TID, "1900000060000", deadline())).isTrue();
        redis.onEval = (s, k, a) -> 0L;
        assertThat(store.touch(snap, deadline())).isFalse();
        assertThat(store.healOrphan(A, TID, deadline())).isFalse();
        assertThat(store.pruneInvite(A, TID, "1900000060000", deadline())).isFalse();

        redis.calls.clear();
        assertThat(store.touch(new Snapshot(A, 0, 9, 0, 0, null, -2, NOW), deadline())).isFalse();
        assertThat(store.touch(null, deadline())).isFalse();
        assertThat(redis.calls).isEmpty();
        assertThat(TeamStore.needsTouch(snap)).isTrue();
        assertThat(TeamStore.needsTouch(new Snapshot(A, TID, 9, TID, 3, team(A), -1, NOW))).as("无 TTL 也要续").isTrue();
        assertThat(TeamStore.needsTouch(new Snapshot(A, TID, 9, TID, 3, team(A), TeamStore.TOUCH_THRESHOLD_SECONDS, NOW)))
                .isFalse();
        assertThat(TeamStore.needsTouch(new Snapshot(A, 0, 9, 0, 0, null, -2, NOW))).isFalse();
    }

    // ================================================================ 整队开战：钉版本提交与 EndMatch（store.go:449-689，team-spec §1.7.6）

    private static final long LOCK_MS = 83_000;

    /** 带开战锁的两人队（锁截止 = NOW + 83 s，名单队长在前）。 */
    private static TeamRecord locked(String token) {
        return team(A, B).toBuilder().setMatchLockToken(token).setMatchLockExpireAtMs(NOW + LOCK_MS)
                .addAllMatchLockRoster(List.of(A, B)).build();
    }

    /** 两名保留成员都留在本队的 S_COMMIT 成功回复。 */
    private static List<Object> committed(long newVer) {
        return new ArrayList<>(List.of(1L, b(Long.toString(newVer)), u(TID), b("1"), u(TID), b("2")));
    }

    private static TeamRecord recordArg(List<byte[]> args) {
        try {
            return TeamRecord.parseFrom(args.get(1));
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    private static String ascii(byte[] raw) {
        return new String(raw, StandardCharsets.US_ASCII);
    }

    /** 记下 EndMatch 的退避（不真睡）、可拨单调时钟的存储。 */
    private static final class MatchHooks implements TeamStore.Hooks {
        final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
        java.util.function.LongSupplier clock = System::nanoTime;
        boolean interruptOnSleep;

        @Override
        public void endMatchSleep(long millis) throws InterruptedException {
            sleeps.add(millis);
            if (interruptOnSleep) {
                throw new InterruptedException("停机");
            }
        }

        @Override
        public long nanoTime() {
            return clock.getAsLong();
        }
    }

    private static void assertBackoff(List<Long> sleeps) {
        for (int i = 0; i < sleeps.size(); i++) {
            long base = Math.min(TeamStore.END_MATCH_BACKOFF_INITIAL_MS << i, TeamStore.END_MATCH_BACKOFF_MAX_MS);
            assertThat((double) sleeps.get(i)).as("第 %d 次退避：50 ms 起翻倍、上限 1 s、±20%%", i)
                    .isBetween(Math.floor(base * 0.8), Math.ceil(base * 1.2));
        }
    }

    @Test
    void 开战锁提交_在本轮快照上钉版本写入令牌截止与名单_不重读不重算() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        List<String> expectedVers = new ArrayList<>();
        List<TeamRecord> written = new ArrayList<>();
        redis.onEval = (s, k, a) -> {
            expectedVers.add(ascii(a.get(0)));
            written.add(recordArg(a));
            return committed(8);
        };
        Snapshot snap = new Snapshot(A, TID, 9, TID, 7, team(A, B), 86_000, NOW);

        PinnedResult lock = store.commitMatchLock(snap, A, "tok", List.of(A, B), NOW + LOCK_MS, SessionLoader.NONE, deadline());

        assertThat(lock.code()).isZero();
        assertThat(lock.retry()).isFalse();
        assertThat(lock.repairs()).isEmpty();
        assertThat(lock.commit().version()).isEqualTo(8);
        assertThat(lock.commit().nowMs()).as("规则用的 now：本轮 S_READ 的时钟").isEqualTo(NOW);
        assertThat(lock.commit().decision().reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED);
        assertThat(lock.commit().decision().actor()).isEqualTo(A);
        assertThat(lock.commit().decision().kept()).containsExactlyInAnyOrder(A, B);
        assertThat(redis.calls).as("只有一次 S_COMMIT：不走 mutate 的重读重算").containsExactly(TeamScript.COMMIT);
        assertThat(expectedVers).as("ver 钉死在调用方给的快照上").containsExactly("7");
        assertThat(written.get(0).getMatchLockToken()).isEqualTo("tok");
        assertThat(written.get(0).getMatchLockExpireAtMs()).isEqualTo(NOW + LOCK_MS);
        assertThat(written.get(0).getMatchLockRosterList()).containsExactly(A, B);
    }

    @Test
    void 开战锁提交_快照缺记录是故障_规则拒绝与预算过期都不发提交() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        Snapshot snap = new Snapshot(A, TID, 9, TID, 7, team(A, B), 86_000, NOW);

        assertThatThrownBy(() -> store.commitMatchLock(null, A, "tok", List.of(A, B), NOW + LOCK_MS, SessionLoader.NONE, deadline()))
                .isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> store.commitMatchLock(new Snapshot(A, TID, 9, TID, 0, null, -2, NOW), A, "tok", List.of(A, B),
                NOW + LOCK_MS, SessionLoader.NONE, deadline())).isInstanceOf(DependencyException.class).hasMessageContaining("快照");

        record Row(String name, Snapshot snap, long caller, String token, List<Long> roster, long expire, int code) {
        }
        Snapshot inMatch = new Snapshot(A, TID, 9, TID, 7, locked("old"), 86_000, NOW);
        for (Row row : List.of(
                new Row("不是队长", snap, B, "tok", List.of(A, B), NOW + LOCK_MS, TeamTips.NOT_LEADER),
                new Row("锁有效", inMatch, A, "tok", List.of(A, B), NOW + LOCK_MS, TeamTips.IN_MATCH),
                new Row("令牌为空", snap, A, "", List.of(A, B), NOW + LOCK_MS, TeamTips.INTERNAL),
                new Row("截止不在未来", snap, A, "tok", List.of(A, B), NOW, TeamTips.INTERNAL),
                new Row("名单与成员集合不等", snap, A, "tok", List.of(A), NOW + LOCK_MS, TeamTips.STATE_CHANGED))) {
            PinnedResult res = store.commitMatchLock(row.snap(), row.caller(), row.token(), row.roster(), row.expire(),
                    SessionLoader.NONE, deadline());
            assertThat(res.code()).as(row.name()).isEqualTo(row.code());
            assertThat(res.commit()).as(row.name()).isNull();
            assertThat(res.retry()).as("%s：规则拒绝不是「重来」", row.name()).isFalse();
        }
        PinnedResult late = store.commitMatchLock(snap, A, "tok", List.of(A, B), NOW + LOCK_MS, SessionLoader.NONE, Deadline.after(0));
        assertThat(late.code()).as("预算已过期").isEqualTo(TeamTips.STATE_CHANGED);
        assertThat(redis.calls).as("以上都没有发出任何脚本").isEmpty();
    }

    @Test
    void 开战锁提交_版本冲突回retry_索引错位时修复提交钉在同一版本_意外返回与Redis故障抛依赖故障() {
        FakeRedis redis = new FakeRedis();
        TeamStore store = new TeamStore(redis);
        Snapshot snap = new Snapshot(A, TID, 9, TID, 7, team(A, B), 86_000, NOW);

        redis.onEval = (s, k, a) -> List.of(0L);
        PinnedResult conflict = store.commitMatchLock(snap, A, "tok", List.of(A, B), NOW + LOCK_MS, SessionLoader.NONE, deadline());
        assertThat(conflict.retry()).isTrue();
        assertThat(conflict.commit()).isNull();
        assertThat(conflict.code()).isZero();
        assertThat(conflict.repairs()).isEmpty();
        assertThat(redis.count(TeamScript.COMMIT)).as("钉版本：冲突不重试").isEqualTo(1);

        long other = TID + 100;
        List<String> expectedVers = new ArrayList<>();
        AtomicInteger commits = new AtomicInteger();
        redis.calls.clear();
        redis.onEval = (s, k, a) -> {
            expectedVers.add(ascii(a.get(0)));
            return commits.getAndIncrement() == 0 ? List.of(-2L, 2L)                               // 原决策：B 的索引不是本队
                    : new ArrayList<>(List.of(1L, b("8"), u(TID), b("1"), u(other), b("8")));      // 修复：K=[A] L=[B]
        };
        PinnedResult mismatch = store.commitMatchLock(snap, A, "tok", List.of(A, B), NOW + LOCK_MS, SessionLoader.NONE, deadline());
        assertThat(mismatch.retry()).as("修复落盘了，但锁本次没有提交：调用方整轮重来").isTrue();
        assertThat(mismatch.commit()).isNull();
        assertThat(mismatch.repairs()).hasSize(1);
        assertThat(mismatch.repairs().get(0).decision().reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_HEALED);
        assertThat(mismatch.repairs().get(0).decision().left()).containsExactly(B);
        assertThat(mismatch.repairs().get(0).indexes().get(B)).isEqualTo(new IndexEntry(other, 8));
        assertThat(expectedVers).as("修复提交与原决策钉在同一个 ver").containsExactly("7", "7");

        redis.onEval = (s, k, a) -> List.of(-1L, 1L);
        assertThatThrownBy(() -> store.commitMatchLock(snap, A, "tok", List.of(A, B), NOW + LOCK_MS, SessionLoader.NONE, deadline()))
                .as("决策里没有新成员，{-1} 不可能出现").isInstanceOf(DependencyException.class).hasMessageContaining("意外返回");
        redis.onEval = (s, k, a) -> new RuntimeException("reply lost");
        assertThatThrownBy(() -> store.commitMatchLock(snap, A, "tok", List.of(A, B), NOW + LOCK_MS, SessionLoader.NONE, deadline()))
                .as("结果未知：调用方必须按 token 清锁").isInstanceOf(DependencyException.class).hasRootCauseMessage("reply lost");
    }

    @Test
    void EndMatch_记录缺失_令牌不符_锁已过期_都停止_不提交不退避() {
        FakeRedis redis = new FakeRedis();
        MatchHooks hooks = new MatchHooks();
        TeamStore store = new TeamStore(redis, hooks);

        redis.onEval = (s, k, a) -> read(null, NOW, 0, null);
        assertThat(store.endMatch(TID, "tok", true, SessionLoader.NONE).stop()).isEqualTo(EndMatchStop.RECORD_MISSING);

        redis.onEval = (s, k, a) -> read(null, NOW, 8, locked("other"));
        EndMatchResult mismatch = store.endMatch(TID, "tok", true, SessionLoader.NONE);
        assertThat(mismatch.stop()).as("锁已被清或已重新加锁").isEqualTo(EndMatchStop.TOKEN_MISMATCH);
        assertThat(mismatch.commit()).isNull();
        redis.onEval = (s, k, a) -> read(null, NOW, 8, team(A, B));
        assertThat(store.endMatch(TID, "tok", true, SessionLoader.NONE).stop()).as("记录上没有锁").isEqualTo(EndMatchStop.TOKEN_MISMATCH);
        assertThat(store.endMatch(TID, null, true, SessionLoader.NONE).stop()).isEqualTo(EndMatchStop.TOKEN_MISMATCH);

        // now == 截止 算已过期（基线 TestMatchLockActiveBoundary）
        TeamRecord expiring = locked("tok").toBuilder().setMatchLockExpireAtMs(NOW).build();
        redis.onEval = (s, k, a) -> read(null, NOW, 8, expiring);
        assertThat(store.endMatch(TID, "tok", false, SessionLoader.NONE).stop()).isEqualTo(EndMatchStop.LOCK_EXPIRED);

        assertThat(redis.count(TeamScript.COMMIT)).as("停止分支不写").isZero();
        assertThat(redis.calls).as("S_READ 的玩家位传 0：每次只读一轮").hasSize(5).containsOnly(TeamScript.READ);
        assertThat(hooks.sleeps).as("停止分支不退避").isEmpty();
    }

    @Test
    void EndMatch_冲突与故障都退避后重读_没有三次上限_最终清锁() {
        FakeRedis redis = new FakeRedis();
        MatchHooks hooks = new MatchHooks();
        TeamStore store = new TeamStore(redis, hooks);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        List<String> expectedVers = new ArrayList<>();
        List<TeamRecord> written = new ArrayList<>();
        redis.onEval = (s, k, a) -> {
            if (s == TeamScript.READ) {
                int n = reads.getAndIncrement();
                // 第 1 轮读失败；之后每轮读到的 ver 都被别人推进了 1（锁期间队外玩家的申请）
                return n == 0 ? new RuntimeException("redis blip") : read(null, NOW + n, 10 + n, locked("tok"));
            }
            expectedVers.add(ascii(a.get(0)));
            written.add(recordArg(a));
            return commits.getAndIncrement() < 6 ? List.of(0L) : committed(18);
        };

        EndMatchResult res = store.endMatch(TID, "tok", true, SessionLoader.NONE);

        assertThat(res.stop()).isEqualTo(EndMatchStop.RELEASED);
        assertThat(res.conflicts()).as("6 次冲突都熬过去了（mutate 的 3 次上限不适用）").isEqualTo(6);
        assertThat(res.lastError()).as("最后一次故障留作参考").isInstanceOf(DependencyException.class).hasRootCauseMessage("redis blip");
        assertThat(res.repairs()).isEmpty();
        assertThat(res.commit().version()).isEqualTo(18);
        assertThat(res.commit().decision().reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED);
        assertThat(res.commit().decision().actor()).isZero();
        assertThat(expectedVers).as("每轮按刚读到的 ver 钉死").containsExactly("11", "12", "13", "14", "15", "16", "17");
        TeamRecord cleared = written.get(written.size() - 1);
        assertThat(cleared.getMatchLockToken()).isEmpty();
        assertThat(cleared.getMatchLockExpireAtMs()).isZero();
        assertThat(cleared.getMatchLockRosterList()).isEmpty();
        assertThat(hooks.sleeps).as("1 次故障 + 6 次冲突，各退避一次").hasSize(7);
        assertBackoff(hooks.sleeps);

        // ok = false → MATCH_FAILED
        redis.onEval = (s, k, a) -> s == TeamScript.READ ? read(null, NOW, 20, locked("tok")) : committed(21);
        assertThat(store.endMatch(TID, "tok", false, SessionLoader.NONE).commit().decision().reason())
                .isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED);
    }

    @Test
    void EndMatch_索引错位的修复落盘后立即重读_不退避不计冲突() {
        FakeRedis redis = new FakeRedis();
        MatchHooks hooks = new MatchHooks();
        TeamStore store = new TeamStore(redis, hooks);
        long other = TID + 100;
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        redis.onEval = (s, k, a) -> {
            if (s == TeamScript.READ) {
                // 修复之后：B 已被移出，锁内名单同步去掉他
                return reads.getAndIncrement() == 0 ? read(null, NOW, 7, locked("tok"))
                        : read(null, NOW, 8, team(A).toBuilder().setMatchLockToken("tok").setMatchLockExpireAtMs(NOW + LOCK_MS)
                                .addMatchLockRoster(A).build());
            }
            return switch (commits.getAndIncrement()) {
                case 0 -> List.of(-2L, 2L);                                                      // 清锁：B 的索引不是本队
                case 1 -> new ArrayList<>(List.of(1L, b("8"), u(TID), b("1"), u(other), b("8"))); // 修复：K=[A] L=[B]
                default -> new ArrayList<>(List.of(1L, b("9"), u(TID), b("1")));                 // 重读后的清锁：K=[A]
            };
        };

        EndMatchResult res = store.endMatch(TID, "tok", false, SessionLoader.NONE);

        assertThat(res.stop()).isEqualTo(EndMatchStop.RELEASED);
        assertThat(res.repairs()).hasSize(1);
        assertThat(res.repairs().get(0).decision().left()).containsExactly(B);
        assertThat(res.commit().version()).isEqualTo(9);
        assertThat(res.commit().decision().kept()).containsExactly(A);
        assertThat(res.conflicts()).isZero();
        assertThat(hooks.sleeps).as("修复后应立即重读，不应退避").isEmpty();
    }

    @Test
    void EndMatch_Redis持续故障_到110秒的单调截止放弃_锁留给自然过期() {
        FakeRedis redis = new FakeRedis();
        MatchHooks hooks = new MatchHooks();
        TeamStore store = new TeamStore(redis, hooks);
        redis.onEval = (s, k, a) -> new RuntimeException("redis down");
        // 单调时钟每问一次走 25 s：起点 0，25 / 50 / 75 / 100 s 各跑一轮，125 s 时越过 110 s
        AtomicInteger asked = new AtomicInteger();
        hooks.clock = () -> TimeUnit.SECONDS.toNanos(25L * asked.getAndIncrement());

        EndMatchResult res = store.endMatch(TID, "tok", true, SessionLoader.NONE);

        assertThat(res.stop()).isEqualTo(EndMatchStop.DEADLINE);
        assertThat(res.commit()).isNull();
        assertThat(res.lastError()).hasRootCauseMessage("redis down");
        assertThat(redis.count(TeamScript.READ)).isEqualTo(4);
        assertThat(hooks.sleeps).hasSize(4);
        assertBackoff(hooks.sleeps);
        assertThat(TeamStore.END_MATCH_MAX_DURATION_MS).isEqualTo(110_000);

        // 恰好到截止（110 s）就放弃：一轮都不跑
        redis.calls.clear();
        AtomicInteger asked2 = new AtomicInteger();
        hooks.clock = () -> asked2.getAndIncrement() == 0 ? 0 : TimeUnit.MILLISECONDS.toNanos(TeamStore.END_MATCH_MAX_DURATION_MS);
        assertThat(store.endMatch(TID, "tok", true, SessionLoader.NONE).stop()).isEqualTo(EndMatchStop.DEADLINE);
        assertThat(redis.calls).isEmpty();
    }

    @Test
    void EndMatch_每轮只有独立的2秒预算_Redis不应答也不会永远挂住() {
        FakeRedis redis = new FakeRedis();
        MatchHooks hooks = new MatchHooks();
        TeamStore store = new TeamStore(redis, hooks);
        redis.onEval = (s, k, a) -> FakeRedis.NEVER;
        // 起点与第一次检查都是 0：跑一轮（真等 2 s 的轮预算）；之后时钟跳过截止
        AtomicInteger asked = new AtomicInteger();
        hooks.clock = () -> asked.getAndIncrement() < 2 ? 0 : TimeUnit.SECONDS.toNanos(111);
        long started = System.nanoTime();

        EndMatchResult res = store.endMatch(TID, "tok", true, SessionLoader.NONE);

        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(res.stop()).isEqualTo(EndMatchStop.DEADLINE);
        assertThat(res.lastError()).hasMessageContaining("超过请求预算");
        assertThat(waited).as("一轮的预算是 %d ms", TeamStore.END_MATCH_ROUND_TIMEOUT_MS)
                .isBetween(TeamStore.END_MATCH_ROUND_TIMEOUT_MS - 100, TeamStore.END_MATCH_ROUND_TIMEOUT_MS + 3000);
        assertThat(redis.count(TeamScript.READ)).isEqualTo(1);
    }

    @Test
    void EndMatch_退避被中断即停止_保留线程的中断标志() {
        FakeRedis redis = new FakeRedis();
        MatchHooks hooks = new MatchHooks();
        hooks.interruptOnSleep = true;
        TeamStore store = new TeamStore(redis, hooks);
        redis.onEval = (s, k, a) -> s == TeamScript.READ ? read(null, NOW, 7, locked("tok")) : List.of(0L);

        EndMatchResult res;
        boolean interrupted;
        try {
            res = store.endMatch(TID, "tok", true, SessionLoader.NONE);
        } finally {
            interrupted = Thread.interrupted(); // 读出并清掉，别带进后面的用例
        }

        assertThat(res.stop()).as("进程正在停机：锁靠自然过期").isEqualTo(EndMatchStop.INTERRUPTED);
        assertThat(res.conflicts()).isEqualTo(1);
        assertThat(res.commit()).isNull();
        assertThat(interrupted).as("中断标志保持置位，线程池才能据此收尾").isTrue();
        assertThat(redis.count(TeamScript.READ)).as("不再重读").isEqualTo(1);
    }

    @Test
    void 单轮清锁_只跑一轮_受调用方预算约束_停止_已清_没清三种形态() {
        FakeRedis redis = new FakeRedis();
        MatchHooks hooks = new MatchHooks();
        TeamStore store = new TeamStore(redis, hooks);
        List<TeamRecord> written = new ArrayList<>();

        redis.onEval = (s, k, a) -> read(null, NOW, 8, locked("other"));
        LockRelease stopped = store.releaseMatchLockOnce(TID, "tok", SessionLoader.NONE, deadline());
        assertThat(stopped.stop()).isEqualTo(EndMatchStop.TOKEN_MISMATCH);
        assertThat(stopped.pinned()).isNull();

        redis.onEval = (s, k, a) -> {
            if (s == TeamScript.READ) {
                return read(null, NOW, 8, locked("tok"));
            }
            written.add(recordArg(a));
            return committed(9);
        };
        LockRelease released = store.releaseMatchLockOnce(TID, "tok", SessionLoader.NONE, deadline());
        assertThat(released.stop()).isNull();
        assertThat(released.pinned().commit().version()).isEqualTo(9);
        assertThat(released.pinned().commit().decision().reason()).as("单轮清锁一律 ok = false")
                .isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED);
        assertThat(written.get(0).getMatchLockToken()).isEmpty();

        redis.calls.clear();
        redis.onEval = (s, k, a) -> s == TeamScript.READ ? read(null, NOW, 8, locked("tok")) : List.of(0L);
        LockRelease conflict = store.releaseMatchLockOnce(TID, "tok", SessionLoader.NONE, deadline());
        assertThat(conflict.stop()).isNull();
        assertThat(conflict.pinned().commit()).as("冲突：没清，调用方转后台").isNull();
        assertThat(conflict.pinned().retry()).isTrue();
        assertThat(redis.calls).as("不重试").containsExactly(TeamScript.READ, TeamScript.COMMIT);
        assertThat(hooks.sleeps).as("单轮版本不退避").isEmpty();

        redis.onEval = (s, k, a) -> FakeRedis.NEVER;
        assertThatThrownBy(() -> store.releaseMatchLockOnce(TID, "tok", SessionLoader.NONE, Deadline.after(40)))
                .as("用的是调用方的预算，不是 EndMatch 的 2 s").isInstanceOf(DependencyException.class).hasMessageContaining("超过请求预算");
    }
}
