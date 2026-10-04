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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link TeamStore} 的控制流与故障映射（team-spec §6.4 的约定），用假 {@link TeamRedis} 编排回复，不起 Redis：
 * Redis 失败 / 超出预算 → {@link DependencyException}；提交前预算过期 → 4029 且不发提交；冲突累计 3 次 → 4029；
 * 会话读取违约 → 全员 UNKNOWN；自由读失败仍带出 healed；S_READ_MEMBERS 持续变化 → {@link MembersChangedException}。
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
}
