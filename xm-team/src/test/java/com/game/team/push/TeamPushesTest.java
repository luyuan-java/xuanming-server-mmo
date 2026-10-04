package com.game.team.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.presence.PlayerPushes;
import com.game.proto.MessageContent;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamEventS2C;
import com.game.proto.team.TeamEventType;
import com.game.proto.team.TeamInviteS2C;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.team.metrics.TeamMetrics;
import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamInviteRecord;
import com.game.team.proto.TeamMemberRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Decision;
import com.game.team.store.CommitResult;
import com.game.team.store.IndexEntry;
import com.game.team.store.TeamRedis;
import com.game.team.store.TeamScript;
import com.game.team.store.TeamStore;
import com.game.team.view.MemberDisplay;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 推送管道（team-spec §4.4–§4.6、§6.8；基线 notify.go）：收件人矩阵、调用者排除、批内顺序、执行器拒绝、批预算、结局映射、MEMBER_ONLINE。
 * 不连 Redis：提交结果直接构造，S_READ_MEMBERS 用假 {@link TeamRedis} 回放。
 */
class TeamPushesTest {

    private static final int SNAPSHOT = 213;
    private static final int INVITE = 215;
    private static final int EVENT = 203;
    private static final long NOW = 1_800_000_000_000L;
    private static final long TID = Long.MIN_VALUE + 500;
    private static final long A = Long.MIN_VALUE + 1; // 队长
    private static final long B = Long.MIN_VALUE + 2;
    private static final long C = Long.MIN_VALUE + 3;
    private static final long D = Long.MIN_VALUE + 4;
    private static final long X = Long.MIN_VALUE + 9; // 队外

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TeamMetrics metrics = new TeamMetrics(registry, false);
    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

    record Sent(long playerId, int messageId, MessageContent content) {

        TeamSnapshotS2C snapshot() {
            assertThat(messageId).isEqualTo(SNAPSHOT);
            try {
                return TeamSnapshotS2C.parseFrom(content.getSerializedMessage());
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError(e);
            }
        }

        TeamEventS2C event() {
            assertThat(messageId).isEqualTo(EVENT);
            try {
                return TeamEventS2C.parseFrom(content.getSerializedMessage());
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError(e);
            }
        }

        TeamInviteS2C invite() {
            assertThat(messageId).isEqualTo(INVITE);
            try {
                return TeamInviteS2C.parseFrom(content.getSerializedMessage());
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError(e);
            }
        }
    }

    private TeamPushes.Pusher recording(PlayerPushes.Outcome outcome) {
        return (pid, content) -> {
            sent.add(new Sent(pid, content.getMessageId(), content));
            return CompletableFuture.completedFuture(outcome);
        };
    }

    private TeamPushes pushes(TeamPushes.Pusher pusher, Executor executor, Duration budget, TeamRedis redis) {
        Map<Long, MemberDisplay> online = Map.of(A, display(true), B, display(true), C, display(true), D, display(false));
        return new TeamPushes(new TeamStore(redis == null ? new FailingRedis() : redis), (ids, d) -> online, pusher,
                executor, metrics, budget, new TeamPushes.MessageIds(SNAPSHOT, INVITE, EVENT));
    }

    private TeamPushes pushes() {
        return pushes(recording(PlayerPushes.Outcome.SENT), Runnable::run, Duration.ofSeconds(3), null);
    }

    private static MemberDisplay display(boolean online) {
        return new MemberDisplay(online, false, 1, 1, "n", "", 0);
    }

    private double pushCount(String kind, String outcome) {
        return registry.get("xm.team.pushes").tag("kind", kind).tag("outcome", outcome).counter().count();
    }

    // ---------------------------------------------------------------- 构造提交结果

    private static TeamRecord record(long leader, long... members) {
        TeamRecord.Builder b = TeamRecord.newBuilder().setTeamId(TID).setLeaderId(leader).setZoneId(1)
                .setNextJoinSeq(members.length + 1);
        for (int i = 0; i < members.length; i++) {
            b.addMembers(TeamMemberRecord.newBuilder().setPlayerId(members[i]).setZoneId(1).setJoinSeq(i + 1));
        }
        return b.build();
    }

    private static Decision decision(TeamRecord rec, List<Long> joined, List<Long> kept, List<Long> left,
                                     TeamChangeReason reason, long actor, boolean lot, List<Long> revoked, long rejected,
                                     long invited) {
        return new Decision(0, 0, true, rec, joined, kept, left, List.of(), List.of(), reason, actor, lot, rec == null,
                revoked, rejected, invited);
    }

    /** 每个 J/K 成员的索引指向本队（epoch = 100 + 序号），L 置 0（epoch = 200 + 序号）；foreign 里的人指向别队。 */
    private static CommitResult commit(Decision d, long version, List<Long> foreign) {
        Map<Long, IndexEntry> idx = new LinkedHashMap<>();
        int i = 0;
        for (long pid : d.joined()) {
            idx.put(pid, new IndexEntry(foreign.contains(pid) ? TID + 1 : TID, 100 + i++));
        }
        for (long pid : d.kept()) {
            idx.put(pid, new IndexEntry(foreign.contains(pid) ? TID + 1 : TID, 100 + i++));
        }
        for (long pid : d.left()) {
            idx.put(pid, new IndexEntry(foreign.contains(pid) ? TID + 1 : 0, 200 + i++));
        }
        return new CommitResult(TID, version, d, Collections.unmodifiableMap(idx), NOW);
    }

    private static CommitResult commit(Decision d) {
        return commit(d, 7, List.of());
    }

    // ================================================================ 收件人矩阵

    @Test
    void 建队不推() {
        CommitResult c = commit(decision(record(A, A), List.of(A), List.of(), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_CREATED, A, false, List.of(), 0, 0));
        assertThat(TeamPushes.snapshotRecipients(c)).isEmpty();
        pushes().publish(A, List.of(c));
        assertThat(sent).isEmpty();
    }

    @Test
    void 申请与邀请增减只推队长_惰性转让时推全员_都排除调用者() {
        TeamRecord rec = record(A, A, B, C).toBuilder().addApplications(TeamApplicationRecord.newBuilder().setPlayerId(X)
                .setZoneId(1).setAppliedAtMs(NOW).setExpireAtMs(NOW + 120_000)).build();
        CommitResult apply = commit(decision(rec, List.of(), List.of(A, B, C), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED, X, false, List.of(), 0, 0));
        assertThat(TeamPushes.snapshotRecipients(apply)).containsExactly(A);
        pushes().publish(X, List.of(apply));
        assertThat(sent).hasSize(1);
        TeamSnapshotS2C s = sent.get(0).snapshot();
        assertThat(sent.get(0).playerId()).isEqualTo(A);
        assertThat(s.getReason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED);
        assertThat(s.getActorId()).isEqualTo(X);
        assertThat(s.getTeam().getApplicationsList()).hasSize(1);
        assertThat(s.getTeam().getMembershipEpoch()).isEqualTo(100);
        assertThat(s.getTeam().getVersion()).isEqualTo(7);

        // 同一次提交惰性转让了队长（新队长 B）：改推全员（除调用者 X 外的 J∪K∪L）
        sent.clear();
        CommitResult withLot = commit(decision(rec.toBuilder().setLeaderId(B).build(), List.of(), List.of(A, B, C), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED, X, true, List.of(), 0, 0));
        assertThat(TeamPushes.snapshotRecipients(withLot)).containsExactly(A, B, C);
        pushes().publish(X, List.of(withLot));
        assertThat(sent).extracting(Sent::playerId).containsExactly(A, B, C);
        assertThat(sent.get(1).snapshot().getTeam().getApplicationsList()).as("新队长看申请").hasSize(1);
        assertThat(sent.get(0).snapshot().getTeam().getApplicationsList()).as("非队长只看计数").isEmpty();
        assertThat(sent.get(0).snapshot().getTeam().getApplicationCount()).isEqualTo(1);

        // 队长自己就是调用者（如拒绝申请、过期清理由队长触发）：快照不推
        sent.clear();
        CommitResult byLeader = commit(decision(rec, List.of(), List.of(A, B, C), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED, A, false, List.of(), 0, 0));
        pushes().publish(A, List.of(byLeader));
        assertThat(sent).isEmpty();

        CommitResult noRecord = commit(decision(null, List.of(), List.of(), List.of(A),
                TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED, 0, false, List.of(), 0, 0));
        assertThat(TeamPushes.snapshotRecipients(noRecord)).isEmpty();
    }

    @Test
    void 加入_踢人_解散_自愈的收件人与视图() {
        // 加入：J∪K 除调用者（队长）外；各人 epoch 取自同一次提交
        CommitResult joined = commit(decision(record(A, A, B, C), List.of(C), List.of(A, B), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED, C, false, List.of(), 0, 0));
        pushes().publish(A, List.of(joined));
        assertThat(sent).extracting(Sent::playerId).containsExactly(C, B);
        assertThat(sent.get(0).snapshot().getTeam().getMembershipEpoch()).isEqualTo(100);
        assertThat(sent.get(1).snapshot().getTeam().getMembershipEpoch()).isEqualTo(102);
        assertThat(sent.get(0).snapshot().getActorId()).isEqualTo(C);

        // 踢人：保留成员 + 被踢者（team_id=0 的空视图，epoch 为提交后的新值）
        sent.clear();
        CommitResult kicked = commit(decision(record(A, A, B), List.of(), List.of(A, B), List.of(C),
                TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_KICKED, C, false, List.of(), 0, 0));
        pushes().publish(A, List.of(kicked));
        assertThat(sent).extracting(Sent::playerId).containsExactly(B, C);
        TeamSnapshotS2C empty = sent.get(1).snapshot();
        assertThat(empty.getTeam().getTeamId()).isZero();
        assertThat(empty.getTeam().getVersion()).isZero();
        assertThat(empty.getTeam().getMembershipEpoch()).isEqualTo(202);
        assertThat(empty.getTeam().getCapacity()).isEqualTo(5);

        // 解散：其余成员收空视图 DISBANDED；未过期邀请的被邀请人收 203 INVITE_REVOKED（actor = 调用者），调用者本人不收
        sent.clear();
        CommitResult disbanded = commit(decision(null, List.of(), List.of(), List.of(A, B),
                TeamChangeReason.TEAM_CHANGE_REASON_DISBANDED, A, false, List.of(X, A, X), 0, 0));
        pushes().publish(A, List.of(disbanded));
        assertThat(sent).extracting(Sent::playerId).containsExactly(B, X);
        assertThat(sent.get(0).snapshot().getReason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_DISBANDED);
        assertThat(sent.get(0).snapshot().getTeam().getTeamId()).isZero();
        TeamEventS2C revoked = sent.get(1).event();
        assertThat(revoked.getType()).isEqualTo(TeamEventType.TEAM_EVENT_TYPE_INVITE_REVOKED);
        assertThat(revoked.getTeamId()).isEqualTo(TID);
        assertThat(revoked.getActorId()).isEqualTo(A);

        // 自愈（修复提交，caller=0 不排除任何人）：被移出者的索引已指向别队，不拼视图、跳过
        sent.clear();
        CommitResult healed = commit(decision(record(A, A, B), List.of(), List.of(A, B), List.of(C),
                TeamChangeReason.TEAM_CHANGE_REASON_HEALED, C, false, List.of(), 0, 0), 8, List.of(C));
        pushes().publish(0, List.of(healed));
        assertThat(sent).extracting(Sent::playerId).containsExactly(A, B);
        assertThat(sent.get(0).snapshot().getReason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_HEALED);
    }

    @Test
    void 拒绝申请_申请人收203_actor为记录里的队长_调用者是申请人时不推() {
        CommitResult rejected = commit(decision(record(B, A, B), List.of(), List.of(A, B), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED, X, false, List.of(), X, 0));
        pushes().publish(B, List.of(rejected));
        assertThat(sent).extracting(Sent::playerId).containsExactly(X);
        TeamEventS2C ev = sent.get(0).event();
        assertThat(ev.getType()).isEqualTo(TeamEventType.TEAM_EVENT_TYPE_APPLICATION_REJECTED);
        assertThat(ev.getTeamId()).isEqualTo(TID);
        assertThat(ev.getActorId()).isEqualTo(B);

        sent.clear();
        pushes().publish(X, List.of(rejected));
        assertThat(sent).extracting(Sent::playerId).as("申请人自己（调用者）不推 203；队长收快照").containsExactly(B);
    }

    @Test
    void 邀请推215带server_time_邀请已过期则不推() {
        TeamRecord rec = record(A, A).toBuilder().addInvites(TeamInviteRecord.newBuilder().setInviteeId(X).setInviterId(A)
                .setZoneId(1).setInvitedAtMs(NOW).setExpireAtMs(NOW + 60_000)).build();
        CommitResult invited = commit(decision(rec, List.of(), List.of(A), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED, X, false, List.of(), 0, X));
        pushes().publish(A, List.of(invited));
        assertThat(sent).hasSize(1);
        TeamInviteS2C inv = sent.get(0).invite();
        assertThat(sent.get(0).playerId()).isEqualTo(X);
        assertThat(inv.getServerTimeMs()).isEqualTo(NOW);
        assertThat(inv.getInvite().getTeamId()).isEqualTo(TID);
        assertThat(inv.getInvite().getInviter().getPlayerId()).isEqualTo(A);
        assertThat(inv.getInvite().getInviter().getIsLeader()).isTrue();
        assertThat(inv.getInvite().getMemberCount()).isEqualTo(1);
        assertThat(inv.getInvite().getExpireAtMs()).isEqualTo(NOW + 60_000);

        sent.clear();
        TeamRecord expired = rec.toBuilder().setInvites(0, rec.getInvites(0).toBuilder().setExpireAtMs(NOW)).build();
        pushes().publish(A, List.of(commit(decision(expired, List.of(), List.of(A), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED, X, false, List.of(), 0, X))));
        assertThat(sent).isEmpty();
    }

    // ================================================================ 批内顺序、拒绝、预算、结局

    @Test
    void 批内按落盘顺序串行_修复提交在前() {
        CommitResult repair = commit(decision(record(A, A, B), List.of(), List.of(A, B), List.of(C),
                TeamChangeReason.TEAM_CHANGE_REASON_HEALED, C, false, List.of(), 0, 0), 8, List.of());
        CommitResult main = commit(decision(record(A, A, B, D), List.of(D), List.of(A, B), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED, D, false, List.of(), 0, 0), 9, List.of());
        pushes().publish(A, List.of(repair, main));
        assertThat(sent).extracting(s -> s.playerId() + ":" + s.snapshot().getTeam().getVersion())
                .containsExactly(B + ":8", C + ":0", D + ":9", B + ":9");
        assertThat(pushCount("snapshot", "ok")).isEqualTo(4);
    }

    @Test
    void 执行器拒绝时整批放弃_按计划的种类逐条记error() {
        TeamRecord rec = record(A, A, B).toBuilder().addInvites(TeamInviteRecord.newBuilder().setInviteeId(X).setInviterId(A)
                .setZoneId(1).setInvitedAtMs(NOW).setExpireAtMs(NOW + 60_000)).build();
        CommitResult disbandLike = commit(decision(null, List.of(), List.of(), List.of(A, B),
                TeamChangeReason.TEAM_CHANGE_REASON_DISBANDED, A, false, List.of(X), 0, 0));
        CommitResult invite = commit(decision(rec, List.of(), List.of(A, B), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED, X, false, List.of(), 0, X));
        Executor full = task -> {
            throw new RejectedExecutionException("full");
        };
        TeamPushes p = pushes(recording(PlayerPushes.Outcome.SENT), full, Duration.ofSeconds(3), null);
        p.publish(A, List.of(disbandLike, invite));
        p.publishOnline(A, TID, List.of(A, B));
        assertThat(sent).isEmpty();
        assertThat(pushCount("snapshot", "error")).as("B 的 DISBANDED + 在线态刷新").isEqualTo(2);
        assertThat(pushCount("event", "error")).isEqualTo(1);
        assertThat(pushCount("invite", "error")).isEqualTo(1);
    }

    @Test
    void 批预算用完_剩余收件人记error且不再发出() {
        List<Long> attempted = Collections.synchronizedList(new ArrayList<>());
        TeamPushes.Pusher hang = (pid, content) -> {
            attempted.add(pid);
            return new CompletableFuture<>();
        };
        CommitResult joined = commit(decision(record(A, A, B, C), List.of(C), List.of(A, B), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED, C, false, List.of(), 0, 0));
        pushes(hang, Runnable::run, Duration.ofMillis(30), null).publish(A, List.of(joined));
        assertThat(attempted).containsExactly(C);
        assertThat(pushCount("snapshot", "error")).isEqualTo(2);
    }

    @Test
    void 预算从提交时刻起算_在执行器里排队超过预算的整批按error丢弃_不读资料不发出() throws InterruptedException {
        List<Runnable> queued = new ArrayList<>();
        List<Long> displayLoads = Collections.synchronizedList(new ArrayList<>());
        TeamPushes p = new TeamPushes(new TeamStore(new FailingRedis()), (ids, d) -> {
            displayLoads.add((long) ids.size());
            return Map.of();
        }, recording(PlayerPushes.Outcome.SENT), queued::add, metrics, Duration.ofMillis(20),
                new TeamPushes.MessageIds(SNAPSHOT, INVITE, EVENT));
        CommitResult joined = commit(decision(record(A, A, B, C), List.of(C), List.of(A, B), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED, C, false, List.of(), 0, 0));
        p.publish(A, List.of(joined));
        p.publishOnline(B, TID, List.of(A, B, C));
        Thread.sleep(50);
        queued.forEach(Runnable::run);
        assertThat(sent).isEmpty();
        assertThat(displayLoads).isEmpty();
        assertThat(pushCount("snapshot", "error")).as("C、B 的 MEMBER_JOINED + 在线态刷新").isEqualTo(3);
    }

    @Test
    void 结局映射_SENT_ok_OFFLINE_offline_GATE_UNREACHABLE与异常为error() {
        CommitResult kicked = commit(decision(record(A, A, B), List.of(), List.of(A, B), List.of(C),
                TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_KICKED, C, false, List.of(), 0, 0));
        pushes(recording(PlayerPushes.Outcome.OFFLINE), Runnable::run, Duration.ofSeconds(3), null).publish(A, List.of(kicked));
        assertThat(pushCount("snapshot", "offline")).isEqualTo(2);
        pushes(recording(PlayerPushes.Outcome.GATE_UNREACHABLE), Runnable::run, Duration.ofSeconds(3), null)
                .publish(A, List.of(kicked));
        assertThat(pushCount("snapshot", "error")).isEqualTo(2);
        pushes((pid, c) -> CompletableFuture.failedFuture(new IllegalStateException("redis down")), Runnable::run,
                Duration.ofSeconds(3), null).publish(A, List.of(kicked));
        assertThat(pushCount("snapshot", "error")).isEqualTo(4);
        pushes((pid, c) -> {
            throw new IllegalStateException("closed");
        }, Runnable::run, Duration.ofSeconds(3), null).publish(A, List.of(kicked));
        assertThat(pushCount("snapshot", "error")).isEqualTo(6);
        assertThat(pushCount("snapshot", "ok")).isZero();
    }

    // ================================================================ MEMBER_ONLINE

    /** S_READ_MEMBERS 的假回放：按请求的成员键数回 {@code {ver, pb, nowMs, tid_i, epoch_i...}}。 */
    private static final class MembersRedis implements TeamRedis {

        private final Function<List<Long>, Object> reply;
        final List<List<Long>> asked = Collections.synchronizedList(new ArrayList<>());

        MembersRedis(Function<List<Long>, Object> reply) {
            this.reply = reply;
        }

        @Override
        public CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
            if (script != TeamScript.READ_MEMBERS) {
                return CompletableFuture.failedFuture(new IllegalStateException("unexpected " + script));
            }
            List<Long> members = new ArrayList<>();
            for (Object key : keys.subList(1, keys.size())) {
                String k = (String) key;
                members.add(Long.parseUnsignedLong(k.substring(k.lastIndexOf(':') + 1)));
            }
            asked.add(members);
            return CompletableFuture.completedFuture(reply.apply(members));
        }

        @Override
        public CompletionStage<byte[]> hget(String key, String field) {
            return CompletableFuture.failedFuture(new IllegalStateException("unexpected hget"));
        }
    }

    private static byte[] ascii(Object v) {
        return String.valueOf(v).getBytes(StandardCharsets.US_ASCII);
    }

    private static Object membersReply(TeamRecord rec, List<Long> asked, Map<Long, Long> tidOf) {
        List<Object> out = new ArrayList<>();
        out.add(ascii(9));
        out.add(rec.toByteArray());
        out.add(ascii(NOW));
        for (long pid : asked) {
            out.add(ascii(Long.toUnsignedString(tidOf.getOrDefault(pid, TID))));
            out.add(ascii(300 + (pid - A)));
        }
        return out;
    }

    @Test
    void 在线态刷新只推在线_非调用者_索引在本队的成员() {
        TeamRecord rec = record(A, A, B, C, D);
        MembersRedis redis = new MembersRedis(asked -> membersReply(rec, asked, Map.of(C, TID + 1)));
        pushes(recording(PlayerPushes.Outcome.SENT), Runnable::run, Duration.ofSeconds(3), redis)
                .publishOnline(A, TID, List.of(A, B, C, D));
        assertThat(sent).hasSize(1);
        TeamSnapshotS2C s = sent.get(0).snapshot();
        assertThat(sent.get(0).playerId()).isEqualTo(B);
        assertThat(s.getReason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_ONLINE);
        assertThat(s.getActorId()).isEqualTo(A);
        assertThat(s.getTeam().getVersion()).isEqualTo(9);
        assertThat(s.getTeam().getMembershipEpoch()).as("epoch 取自同一次 S_READ_MEMBERS").isEqualTo(301);
        assertThat(s.getTeam().getServerTimeMs()).isEqualTo(NOW);
    }

    @Test
    void 在线态刷新_成员表持续变化记skipped_记录不存在静默_读失败记error() {
        // 每次都回一个与请求不同的成员表：重试 3 次后放弃
        long[] next = {Long.MIN_VALUE + 100};
        MembersRedis changing = new MembersRedis(asked -> membersReply(record(A, A, next[0]++), asked, Map.of()));
        pushes(recording(PlayerPushes.Outcome.SENT), Runnable::run, Duration.ofSeconds(3), changing)
                .publishOnline(A, TID, List.of(A, B));
        assertThat(changing.asked).hasSize(3);
        assertThat(pushCount("members_changed", "skipped")).isEqualTo(1);

        MembersRedis missing = new MembersRedis(asked -> {
            List<Object> out = new ArrayList<>(List.of(ascii(""), ascii(""), ascii(NOW)));
            for (int i = 0; i < asked.size(); i++) {
                out.add(ascii(""));
                out.add(ascii(0));
            }
            return out;
        });
        pushes(recording(PlayerPushes.Outcome.SENT), Runnable::run, Duration.ofSeconds(3), missing)
                .publishOnline(A, TID, List.of(A, B));
        assertThat(pushCount("snapshot", "error")).isZero();

        pushes(recording(PlayerPushes.Outcome.SENT), Runnable::run, Duration.ofSeconds(3), new FailingRedis())
                .publishOnline(A, TID, List.of(A, B));
        assertThat(pushCount("snapshot", "error")).isEqualTo(1);
        assertThat(sent).isEmpty();
    }

    /** 所有 Redis 调用都失败。 */
    private static final class FailingRedis implements TeamRedis {

        @Override
        public CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }

        @Override
        public CompletionStage<byte[]> hget(String key, String field) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
    }

    @Test
    void 计划不做IO_调用者为0时不排除任何人() {
        CommitResult kicked = commit(decision(record(A, A, B), List.of(), List.of(A, B), List.of(C),
                TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_KICKED, C, false, List.of(), 0, 0));
        assertThat(TeamPushes.plan(0, kicked).pushes()).extracting(TeamPushes.Planned::playerId).containsExactly(A, B, C);
        assertThat(TeamPushes.plan(A, kicked).pushes()).extracting(TeamPushes.Planned::playerId).containsExactly(B, C);
        assertThat(TeamPushes.plan(A, kicked).needsDisplay()).isTrue();
        CommitResult disbanded = commit(decision(null, List.of(), List.of(), List.of(A, B),
                TeamChangeReason.TEAM_CHANGE_REASON_DISBANDED, A, false, List.of(), 0, 0));
        assertThat(TeamPushes.plan(A, disbanded).needsDisplay()).as("记录已删：空视图不需要展示缓存").isFalse();
    }
}
