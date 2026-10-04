package com.game.team.service;

import static com.game.team.service.TeamServiceFixture.ZONE;
import static com.game.team.service.TeamServiceFixture.deadline;
import static com.game.team.service.TeamServiceFixture.u;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.discovery.team.TeamRedisFields;
import com.game.proto.TipInfoMessage;
import com.game.proto.team.ApplyJoinTeamRequest;
import com.game.proto.team.CreateTeamRequest;
import com.game.proto.team.DisbandTeamRequest;
import com.game.proto.team.GetMyTeamRequest;
import com.game.proto.team.HandleApplicationRequest;
import com.game.proto.team.InviteToTeamRequest;
import com.game.proto.team.KickMemberRequest;
import com.game.proto.team.LeaveTeamRequest;
import com.game.proto.team.ListMyInvitesRequest;
import com.game.proto.team.ListMyInvitesResponse;
import com.game.proto.team.RespondInviteRequest;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamEventS2C;
import com.game.proto.team.TeamEventType;
import com.game.proto.team.TeamIncomingInviteView;
import com.game.proto.team.TeamInviteS2C;
import com.game.proto.team.TeamMatchState;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.proto.team.TeamView;
import com.game.proto.team.TransferLeaderRequest;
import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.SessionState;
import com.game.team.rules.TeamLimits;
import com.game.team.rules.TeamTips;
import com.game.team.service.TeamServiceFixture.Pushed;
import com.game.team.store.TeamScript;
import com.game.team.store.TeamStore;
import com.google.protobuf.Message;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;

/**
 * 组队服务层在真 Redis 上的行为，逐个对应基线 go/match/internal/team/service_test.go（设计稿 §I.3 #12-#15、#23-#27），
 * 外加批次 4.3 的 StartTeamMatch（team-spec §5.4）与各 RPC 的检查顺序 / 回包视图来源（§3）。
 *
 * <p>与基线的差别：Java 不发 scene 刷新信号（D7），所以基线断言 scene 收件人的地方只断言推送；真 Redis 拨不动 TIME，
 * 「整队空闲 24 h 过期」用删键模拟、时间断言按 Redis TIME 取值。
 *
 * <p>默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}（DB 12，随机 ≥ 2^63 的 id，只删自己的键）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class TeamServiceIntegrationTest {

    private static RedissonClient redis;
    private TeamServiceFixture fx;
    private TeamService svc;

    @BeforeAll
    static void connect() {
        redis = TeamServiceFixture.connect();
    }

    @AfterAll
    static void shutdown() {
        redis.shutdown();
    }

    @BeforeEach
    void setUp() {
        fx = new TeamServiceFixture(redis);
        svc = fx.service;
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    private static void requireCode(int want, TeamResponse resp) {
        assertThat(resp.getErrorMessage().getId()).as("resp=%s", resp).isEqualTo(want);
    }

    private static void requireParam(long want, TeamResponse resp) {
        assertThat(resp.getErrorMessage().getParametersList()).containsExactly(Long.toUnsignedString(want));
    }

    private static TeamSnapshotS2C requireSnapshot(Pushed p, long to, TeamChangeReason reason) {
        assertThat(p.playerId()).isEqualTo(to);
        TeamSnapshotS2C s = p.snapshot();
        assertThat(s.getReason()).isEqualTo(reason);
        return s;
    }

    private static TeamEventS2C requireEvent(Pushed p, long to, TeamEventType type) {
        assertThat(p.playerId()).isEqualTo(to);
        TeamEventS2C e = p.event();
        assertThat(e.getType()).isEqualTo(type);
        return e;
    }

    private void requireNoTeamKeys(long... players) {
        for (long p : players) {
            assertThat(fx.exists(RedisKeys.teamPlayer(p))).as("不应写入组队索引 %s", u(p)).isFalse();
        }
    }

    private TeamResponse kick(long caller, long target, long expected) {
        return svc.kickMember(caller, KickMemberRequest.newBuilder().setTargetPlayerId(target).setExpectedTeamId(expected)
                .build(), deadline());
    }

    private TeamResponse invite(long caller, long target, long expected) {
        return svc.inviteToTeam(caller, InviteToTeamRequest.newBuilder().setTargetPlayerId(target)
                .setExpectedTeamId(expected).build(), deadline());
    }

    private TeamResponse handle(long caller, long applicant, boolean approve, long expected) {
        return svc.handleApplication(caller, HandleApplicationRequest.newBuilder().setApplicantId(applicant)
                .setApprove(approve).setExpectedTeamId(expected).build(), deadline());
    }

    private TeamResponse respond(long caller, long tid, boolean accept) {
        return svc.respondInvite(caller, RespondInviteRequest.newBuilder().setTeamId(tid).setAccept(accept).build(),
                deadline());
    }

    private TeamResponse leave(long caller, long expected) {
        return svc.leaveTeam(caller, LeaveTeamRequest.newBuilder().setExpectedTeamId(expected).build(), deadline());
    }

    private TeamResponse disband(long caller, long expected) {
        return svc.disbandTeam(caller, DisbandTeamRequest.newBuilder().setExpectedTeamId(expected).build(), deadline());
    }

    private TeamResponse getMyTeam(long caller, boolean notifyOnline) {
        return svc.getMyTeam(caller, GetMyTeamRequest.newBuilder().setNotifyOnline(notifyOnline).build(), deadline());
    }

    private TeamResponse startMatch(long caller, long expected) {
        return svc.startTeamMatch(caller, StartTeamMatchRequest.newBuilder().setBattleConfigId(1).setExpectedTeamId(expected)
                .build(), deadline());
    }

    private double counter(String name, String tag, String value) {
        return fx.meters.get(name).tag(tag, value).counter().count();
    }

    // ================================================================ #12 / #27 home zone fail-closed（service_test.go:284-346）

    @Test
    void 建队查不到home_zone回4019_查询故障回4030_都不写() {
        long p1 = fx.pid(1);
        fx.addPlayers(ZONE, p1);
        fx.zones.remove(p1);
        TeamResponse resp = svc.createTeam(p1, CreateTeamRequest.getDefaultInstance(), deadline());
        requireCode(TeamTips.HOME_ZONE_UNKNOWN, resp);
        assertThat(resp.getTeam().getTeamId()).as("空视图").isZero();
        assertThat(resp.getTeam().getCapacity()).isEqualTo(5);
        requireNoTeamKeys(p1);

        fx.zones.put(p1, ZONE);
        fx.zoneError = new IllegalStateException("mysql down");
        resp = svc.createTeam(p1, CreateTeamRequest.getDefaultInstance(), deadline());
        requireCode(TeamTips.INTERNAL, resp);
        assertThat(resp.getTeam().getTeamId()).isZero();
        requireNoTeamKeys(p1);
        assertThat(fx.takePushes()).isEmpty();
    }

    @Test
    void 申请与邀请查不到home_zone都回4019_记录不变() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3);
        fx.addPlayers(ZONE, p1, p2, p3);
        long tid = fx.create(p1);

        fx.zones.remove(p2);
        TeamResponse applied = fx.apply(p2, p1);
        requireCode(TeamTips.HOME_ZONE_UNKNOWN, applied);
        assertThat(applied.getTeam().getTeamId()).as("申请人自己的视图（无队）").isZero();

        fx.zones.remove(p3);
        TeamResponse invited = invite(p1, p3, tid);
        requireCode(TeamTips.HOME_ZONE_UNKNOWN, invited);
        assertThat(invited.getTeam().getTeamId()).as("自由读：队长自己的视图").isEqualTo(tid);

        TeamRecord rec = fx.loadRecord(tid);
        assertThat(rec.getApplicationsList()).isEmpty();
        assertThat(rec.getInvitesList()).isEmpty();
    }

    // ================================================================ #13 缺 session fail-closed（service_test.go:350）

    @Test
    void 缺会话的12个方法_两种情形都回in_band4001无视图_无写无推送() throws Exception {
        long p1 = fx.pid(1), p2 = fx.pid(2);
        fx.addPlayers(ZONE, p1, p2);
        long tid = fx.create(p1);
        fx.join(p1, tid, p2);
        fx.takePushes();
        TeamServiceFixture.KeyState before = fx.state();

        Map<String, Message> bodies = new LinkedHashMap<>();
        bodies.put(TeamMethods.CREATE_TEAM, CreateTeamRequest.getDefaultInstance());
        bodies.put(TeamMethods.GET_MY_TEAM, GetMyTeamRequest.newBuilder().setNotifyOnline(true).build());
        bodies.put(TeamMethods.APPLY_JOIN_TEAM, ApplyJoinTeamRequest.newBuilder().setTargetPlayerId(p1).build());
        bodies.put(TeamMethods.HANDLE_APPLICATION, HandleApplicationRequest.newBuilder().setApplicantId(p2).setApprove(true)
                .setExpectedTeamId(tid).build());
        bodies.put(TeamMethods.INVITE_TO_TEAM, InviteToTeamRequest.newBuilder().setTargetPlayerId(p2).setExpectedTeamId(tid)
                .build());
        bodies.put(TeamMethods.RESPOND_INVITE, RespondInviteRequest.newBuilder().setTeamId(tid).setAccept(true).build());
        bodies.put(TeamMethods.LIST_MY_INVITES, ListMyInvitesRequest.getDefaultInstance());
        bodies.put(TeamMethods.LEAVE_TEAM, LeaveTeamRequest.newBuilder().setExpectedTeamId(tid).build());
        bodies.put(TeamMethods.KICK_MEMBER, KickMemberRequest.newBuilder().setTargetPlayerId(p2).setExpectedTeamId(tid)
                .build());
        bodies.put(TeamMethods.TRANSFER_LEADER, TransferLeaderRequest.newBuilder().setTargetPlayerId(p2)
                .setExpectedTeamId(tid).build());
        bodies.put(TeamMethods.DISBAND_TEAM, DisbandTeamRequest.newBuilder().setExpectedTeamId(tid).build());
        bodies.put(TeamMethods.START_TEAM_MATCH, StartTeamMatchRequest.newBuilder().setBattleConfigId(1)
                .setExpectedTeamId(tid).build());
        assertThat(bodies.keySet()).containsExactlyElementsOf(TeamMethods.REQUESTS);

        Map<String, SessionContext> sessions = new LinkedHashMap<>();
        sessions.put("无 session", null);
        sessions.put("player_id=0", SessionContext.newBuilder().setGateNodeId(1).setSessionId(3).build());
        for (Map.Entry<String, SessionContext> s : sessions.entrySet()) {
            for (Map.Entry<String, Message> m : bodies.entrySet()) {
                int id = TeamServiceFixture.REGISTRY.requireId(TeamMethods.SERVICE, m.getKey());
                ClientReply reply = fx.dispatch(id, s.getValue(), m.getValue().toByteString());
                assertThat(reply.getTipId()).as("%s/%s", s.getKey(), m.getKey()).isZero();
                if (m.getKey().equals(TeamMethods.LIST_MY_INVITES)) {
                    ListMyInvitesResponse r = ListMyInvitesResponse.parseFrom(reply.getBody());
                    assertThat(r.getErrorMessage().getId()).isEqualTo(TeamTips.PLAYER_ID);
                    assertThat(r.getInvitesList()).isEmpty();
                } else {
                    TeamResponse r = TeamResponse.parseFrom(reply.getBody());
                    assertThat(r.getErrorMessage().getId()).as("%s/%s", s.getKey(), m.getKey()).isEqualTo(TeamTips.PLAYER_ID);
                    assertThat(r.hasTeam()).as("%s/%s：没有身份不回任何视图", s.getKey(), m.getKey()).isFalse();
                }
            }
        }
        for (String method : TeamMethods.REQUESTS) {
            assertThat(fx.meters.get("xm.team.requests").tag("method", method).tag("result", "unauthenticated").timer()
                    .count()).isEqualTo(2);
        }
        // 客户端误调推送占位：空操作
        SessionContext me = SessionContext.newBuilder().setPlayerId(p1).build();
        for (String method : TeamMethods.PUSHES) {
            int id = TeamServiceFixture.REGISTRY.requireId(TeamMethods.SERVICE, method);
            Message body = switch (method) {
                case TeamMethods.NOTIFY_TEAM_SNAPSHOT -> TeamSnapshotS2C.newBuilder().setTeam(TeamView.newBuilder()
                        .setTeamId(tid)).build();
                case TeamMethods.NOTIFY_TEAM_INVITE -> TeamInviteS2C.newBuilder().setInvite(TeamIncomingInviteView.newBuilder()
                        .setTeamId(tid)).build();
                default -> TeamEventS2C.newBuilder().setTeamId(tid).build();
            };
            assertThat(fx.dispatch(id, me, body.toByteString())).isEqualTo(ClientReply.getDefaultInstance());
        }

        fx.assertUnchanged(before);
        assertThat(fx.takePushes()).isEmpty();
    }

    // ================================================================ #14 推送收件人、reason、队长可见性、各人 epoch（service_test.go:423）

    @Test
    void 推送收件人矩阵() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3), p4 = fx.pid(4);
        fx.addPlayers(ZONE, p1, p2, p3, p4);

        // 建队：回包即视图，不推送
        long tid = fx.create(p1);
        assertThat(fx.takePushes()).isEmpty();

        // 申请：只推队长 APPLICATION_CHANGED，队长视图带申请
        requireCode(0, fx.apply(p2, p1));
        List<Pushed> pushes = fx.takePushes();
        assertThat(pushes).hasSize(1);
        TeamSnapshotS2C snap = requireSnapshot(pushes.get(0), p1, TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED);
        assertThat(snap.getActorId()).isEqualTo(p2);
        assertThat(snap.getTeam().getApplicationsList()).extracting(a -> a.getPlayer().getPlayerId()).containsExactly(p2);
        requireCode(0, fx.apply(p3, p1));
        fx.takePushes();

        // 同意 2：新人收 MEMBER_JOINED（队长是调用者，看回包）；非队长看不到申请列表，只看到计数；各人 epoch 取自同一次提交
        TeamResponse resp = handle(p1, p2, true, tid);
        requireCode(0, resp);
        assertThat(resp.getTeam().getApplicationsList()).as("队长回包带剩余申请").hasSize(1);
        assertThat(resp.getTeam().getMembershipEpoch()).isEqualTo(fx.epochOf(p1));
        assertThat(resp.getTeam().getVersion()).isEqualTo(fx.versionOf(tid));
        pushes = fx.takePushes();
        assertThat(pushes).hasSize(1);
        snap = requireSnapshot(pushes.get(0), p2, TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED);
        assertThat(snap.getActorId()).isEqualTo(p2);
        assertThat(snap.getTeam().getTeamId()).isEqualTo(tid);
        assertThat(snap.getTeam().getApplicationsList()).as("申请只下发给队长").isEmpty();
        assertThat(snap.getTeam().getPendingInvitesList()).isEmpty();
        assertThat(snap.getTeam().getApplicationCount()).isEqualTo(1);
        assertThat(snap.getTeam().getMembershipEpoch()).isEqualTo(fx.epochOf(p2));
        assertThat(snap.getTeam().getVersion()).isEqualTo(fx.versionOf(tid));
        assertThat(snap.getTeam().getMembersList()).hasSize(2);

        // 拒绝 3：申请人不在队里、收不到视图，只收 APPLICATION_REJECTED（actor = 队长）
        requireCode(0, handle(p1, p3, false, tid));
        pushes = fx.takePushes();
        assertThat(pushes).hasSize(1);
        TeamEventS2C ev = requireEvent(pushes.get(0), p3, TeamEventType.TEAM_EVENT_TYPE_APPLICATION_REJECTED);
        assertThat(ev.getTeamId()).isEqualTo(tid);
        assertThat(ev.getActorId()).isEqualTo(p1);

        // 邀请 4：被邀请人收 NotifyTeamInvite；队长回包带已发出的邀请
        long before = fx.nowMs();
        resp = invite(p1, p4, tid);
        long after = fx.nowMs();
        requireCode(0, resp);
        assertThat(resp.getTeam().getPendingInvitesList()).hasSize(1);
        pushes = fx.takePushes();
        assertThat(pushes).hasSize(1);
        assertThat(pushes.get(0).playerId()).isEqualTo(p4);
        TeamInviteS2C inv = pushes.get(0).invite();
        assertThat(inv.getInvite().getTeamId()).isEqualTo(tid);
        assertThat(inv.getInvite().getInviter().getPlayerId()).isEqualTo(p1);
        assertThat(inv.getInvite().getLeaderId()).isEqualTo(p1);
        assertThat(inv.getInvite().getMemberCount()).isEqualTo(2);
        assertThat(inv.getServerTimeMs()).isBetween(before, after);
        assertThat(inv.getInvite().getExpireAtMs()).isEqualTo(inv.getServerTimeMs() + TeamLimits.INVITE_TTL_MS);

        // 踢 2：被踢者收 team_id=0 的空视图 MEMBER_KICKED，epoch 为提交后的新值
        requireCode(0, kick(p1, p2, tid));
        pushes = fx.takePushes();
        assertThat(pushes).hasSize(1);
        snap = requireSnapshot(pushes.get(0), p2, TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_KICKED);
        assertThat(snap.getTeam().getTeamId()).isZero();
        assertThat(snap.getTeam().getVersion()).isZero();
        assertThat(snap.getTeam().getMembershipEpoch()).isEqualTo(fx.epochOf(p2));
        assertThat(fx.tidOf(p2)).isEqualTo("0");

        // 解散：未过期邀请的被邀请人收 INVITE_REVOKED（actor = 调用者）；回包是带新 epoch 的空视图；记录与投影删除
        resp = disband(p1, tid);
        requireCode(0, resp);
        assertThat(resp.getTeam().getTeamId()).isZero();
        assertThat(resp.getTeam().getMembershipEpoch()).isEqualTo(fx.epochOf(p1));
        pushes = fx.takePushes();
        assertThat(pushes).hasSize(1);
        ev = requireEvent(pushes.get(0), p4, TeamEventType.TEAM_EVENT_TYPE_INVITE_REVOKED);
        assertThat(ev.getTeamId()).isEqualTo(tid);
        assertThat(ev.getActorId()).isEqualTo(p1);
        assertThat(fx.exists(RedisKeys.teamRecord(tid))).isFalse();
        assertThat(fx.exists(RedisKeys.teamInfo(tid))).isFalse();
    }

    // ================================================================ #7 / §H.3 整队过期后的空视图（service_test.go:519）

    @Test
    void 整队过期后的空视图能被客户端接受() {
        long p1 = fx.pid(1), p2 = fx.pid(2);
        fx.addPlayers(ZONE, p1, p2);
        long tid = fx.create(p1);
        fx.join(p1, tid, p2);
        fx.takePushes();
        long ver = fx.versionOf(tid);
        Map<Long, Long> curEpoch = Map.of(p1, fx.epochOf(p1), p2, fx.epochOf(p2));

        // 全队空闲 24 h：记录、投影、全员索引同批过期（真 Redis 拨不动时钟，删键模拟；再等 Redis 时钟越过旧 epoch）
        fx.del(RedisKeys.teamRecord(tid), RedisKeys.teamInfo(tid), RedisKeys.teamPlayer(p1), RedisKeys.teamPlayer(p2));
        fx.waitRedisAfter(Math.max(curEpoch.get(p1), curEpoch.get(p2)));

        java.util.function.BiConsumer<Long, TeamResponse> requireAccepted = (pid, resp) -> {
            assertThat(resp.getTeam().getTeamId()).isZero();
            long inEpoch = resp.getTeam().getMembershipEpoch();
            long cur = curEpoch.get(pid);
            boolean accepted = Long.compareUnsigned(inEpoch, cur) > 0
                    || (inEpoch == cur && Long.compareUnsigned(resp.getTeam().getVersion(), ver) >= 0);
            assertThat(accepted).as("player %s 当前 epoch=%s，空视图 epoch=%s 被丢弃", u(pid), u(cur), u(inEpoch)).isTrue();
        };
        TeamResponse mine = getMyTeam(p2, false);
        requireCode(0, mine);
        requireAccepted.accept(p2, mine);
        TeamResponse left = leave(p2, tid);
        requireCode(0, left);
        requireAccepted.accept(p2, left);
        TeamResponse disbanded = disband(p1, tid);
        requireCode(TeamTips.NO_TEAM, disbanded);
        requireAccepted.accept(p1, disbanded);

        requireNoTeamKeys(p1, p2); // 读路径与未绑定分支都不写
        assertThat(fx.exists(RedisKeys.teamRecord(tid))).isFalse();
        assertThat(fx.takePushes()).isEmpty();
    }

    // ================================================================ #15 冲突耗尽回 4029（service_test.go:580）

    @Test
    void 冲突累计三次回4029_不写入_失败回包带自由读视图() {
        long p1 = fx.pid(1), p2 = fx.pid(2);
        fx.addPlayers(ZONE, p1, p2);
        long tid = fx.create(p1);
        fx.join(p1, tid, p2);
        fx.takePushes();

        double retries = counter("xm.team.commit.retries", "op", TeamMethods.KICK_MEMBER);
        AtomicInteger rounds = new AtomicInteger();
        fx.afterRead.set(bind -> {
            if (bind.playerId() != p1 || bind.teamId() != tid) {
                return;
            }
            rounds.incrementAndGet();
            // 每轮读完都有别的实例抢先提交：ver 前移，本轮 S_COMMIT 必然 {0}
            fx.hset(RedisKeys.teamRecord(tid), TeamRedisFields.VER, Long.toUnsignedString(fx.versionOf(tid) + 1));
        });
        TeamResponse resp = kick(p1, p2, tid);
        fx.afterRead.set(b -> {
        });

        requireCode(TeamTips.STATE_CHANGED, resp);
        assertThat(rounds).hasValue(TeamStore.COMMIT_RETRIES);
        assertThat(counter("xm.team.commit.retries", "op", TeamMethods.KICK_MEMBER)).isEqualTo(retries + TeamStore.COMMIT_RETRIES);
        assertThat(fx.loadRecord(tid).getMembersList()).extracting(m -> m.getPlayerId()).contains(p2);
        assertThat(fx.tidOf(p2)).isEqualTo(u(tid));
        assertThat(resp.getTeam().getTeamId()).as("失败回包带调用者自由读视图").isEqualTo(tid);
        assertThat(fx.takePushes()).isEmpty();
    }

    // ================================================================ #24 迟到执行不误伤新队伍（service_test.go:613、:644）

    @Test
    void 迟到的离队不伤新队伍_回新队视图() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3);
        fx.addPlayers(ZONE, p1, p2, p3);
        long teamA = fx.create(p1);
        fx.join(p1, teamA, p2);
        long teamB = fx.create(p3);

        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<TeamServiceFixture.KeyState> afterHook = new AtomicReference<>();
        fx.afterRead.set(bind -> {
            if (fired.get() || bind.playerId() != p2 || bind.teamId() != teamA) {
                return;
            }
            fired.set(true);
            // 旧 LeaveTeam 挂在 S_READ 之后；期间玩家经别的实例离开 A、接受 B 的邀请
            requireCode(0, leave(p2, teamA));
            requireCode(0, invite(p3, p2, teamB));
            requireCode(0, respond(p2, teamB, true));
            afterHook.set(fx.state());
        });
        TeamResponse resp = leave(p2, teamA);
        fx.afterRead.set(b -> {
        });

        assertThat(fired).isTrue();
        requireCode(0, resp);
        assertThat(resp.getTeam().getTeamId()).as("回调用者当前（B）的视图").isEqualTo(teamB);
        fx.assertUnchanged(afterHook.get());
        assertThat(fx.tidOf(p2)).isEqualTo(u(teamB));
        assertThat(fx.loadRecord(teamB).getMembersList()).extracting(m -> m.getPlayerId()).contains(p2);
    }

    @Test
    void 迟到的解散不伤新队伍_回4013与新队视图() {
        long p4 = fx.pid(4);
        fx.addPlayers(ZONE, p4);
        long teamA = fx.create(p4);

        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<Long> teamB = new AtomicReference<>();
        AtomicReference<TeamServiceFixture.KeyState> afterHook = new AtomicReference<>();
        fx.afterRead.set(bind -> {
            if (fired.get() || bind.playerId() != p4 || bind.teamId() != teamA) {
                return;
            }
            fired.set(true);
            requireCode(0, disband(p4, teamA));
            teamB.set(fx.create(p4));
            afterHook.set(fx.state());
        });
        TeamResponse resp = disband(p4, teamA);
        fx.afterRead.set(b -> {
        });

        assertThat(fired).isTrue();
        requireCode(TeamTips.NO_TEAM, resp);
        assertThat(resp.getTeam().getTeamId()).as("4013 + 调用者当前（B）的视图").isEqualTo(teamB.get());
        fx.assertUnchanged(afterHook.get());
        assertThat(fx.loadRecord(teamB.get()).getLeaderId()).isEqualTo(p4);
    }

    // ================================================================ #25 预算已过期时不提交（service_test.go:673）

    @Test
    void 读完之后预算耗尽_不提交_回4029() {
        long p1 = fx.pid(1), p2 = fx.pid(2);
        fx.addPlayers(ZONE, p1, p2);
        long tid = fx.create(p1);
        fx.join(p1, tid, p2);
        fx.takePushes();

        Deadline budget = Deadline.after(500);
        AtomicReference<TeamServiceFixture.KeyState> beforeCommit = new AtomicReference<>();
        fx.afterRead.set(bind -> {
            if (bind.playerId() != p1) {
                return;
            }
            beforeCommit.set(fx.state());
            // 请求预算在读完之后耗尽：gate 已超时回错，处理仍在跑（迟到执行）
            while (!budget.expired()) {
                LockSupport.parkNanos(1_000_000);
            }
        });
        TeamResponse resp = svc.kickMember(p1, KickMemberRequest.newBuilder().setTargetPlayerId(p2).setExpectedTeamId(tid)
                .build(), budget);
        fx.afterRead.set(b -> {
        });

        requireCode(TeamTips.STATE_CHANGED, resp);
        fx.assertUnchanged(beforeCommit.get());
        assertThat(fx.loadRecord(tid).getMembersList()).extracting(m -> m.getPlayerId()).contains(p2);
        assertThat(fx.takePushes()).isEmpty();
    }

    // ================================================================ #26 notify_online 只推索引在本队的在线队员（service_test.go:704）

    @Test
    void 在线态刷新只推索引在本队的在线队员() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3), p4 = fx.pid(4);
        fx.addPlayers(ZONE, p1, p2, p3, p4);
        long tid = fx.create(p1);
        for (long pid : List.of(p2, p3, p4)) {
            fx.join(p1, tid, pid);
        }
        fx.takePushes();

        // 3 的索引已指向别的队；4 已下线（会话不存在，非队长不触发转让）
        fx.hset(RedisKeys.teamPlayer(p3), TeamRedisFields.TID, "424242");
        fx.states.put(p4, SessionState.ABSENT);

        TeamResponse resp = getMyTeam(p1, true);
        requireCode(0, resp);
        assertThat(resp.getTeam().getTeamId()).isEqualTo(tid);

        List<Pushed> pushes = fx.takePushes();
        assertThat(pushes).as("只推 tid==本队 且在线的其他队员").hasSize(1);
        TeamSnapshotS2C snap = requireSnapshot(pushes.get(0), p2, TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_ONLINE);
        assertThat(snap.getActorId()).isEqualTo(p1);
        assertThat(snap.getTeam().getMembershipEpoch()).as("epoch 取自同一次 S_READ_MEMBERS").isEqualTo(fx.epochOf(p2));
        assertThat(snap.getTeam().getVersion()).isEqualTo(fx.versionOf(tid));
        assertThat(snap.getTeam().getVersion()).as("在线态刷新不改 version").isEqualTo(resp.getTeam().getVersion());
        Map<Long, Boolean> online = new HashMap<>();
        for (TeamMemberView m : snap.getTeam().getMembersList()) {
            online.put(m.getPlayerId(), m.getIsOnline());
        }
        assertThat(online).isEqualTo(Map.of(p1, true, p2, true, p3, true, p4, false));
    }

    @Test
    void notify_online在错误回包上也触发_只要回包视图里有队伍() {
        long p1 = fx.pid(1), p2 = fx.pid(2);
        fx.addPlayers(ZONE, p1, p2);
        long tid = fx.create(p1);
        fx.join(p1, tid, p2);
        fx.takePushes();
        // 记录里放一条已过期的申请：GetMyTeam 的刷新要清理它（有变化、要提交）；每轮读完 ver 都被抢先前移 → 冲突耗尽 4029
        TeamRecord rec = fx.loadRecord(tid);
        fx.writeRecord(tid, rec.toBuilder().addApplications(TeamApplicationRecord.newBuilder().setPlayerId(fx.pid(9))
                .setZoneId(ZONE).setAppliedAtMs(1).setExpireAtMs(2)).build());
        fx.afterRead.set(bind -> {
            if (bind.playerId() == p1 && bind.teamId() == tid) {
                fx.hset(RedisKeys.teamRecord(tid), TeamRedisFields.VER, Long.toUnsignedString(fx.versionOf(tid) + 1));
            }
        });
        TeamResponse resp = getMyTeam(p1, true);
        fx.afterRead.set(b -> {
        });
        requireCode(TeamTips.STATE_CHANGED, resp);
        assertThat(resp.getTeam().getTeamId()).as("4029 + 自由读视图").isEqualTo(tid);
        List<Pushed> pushes = fx.takePushes();
        assertThat(pushes).hasSize(1);
        requireSnapshot(pushes.get(0), p2, TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_ONLINE);
    }

    // ================================================================ #23 ListMyInvites 的 CAS 清理（service_test.go:739）

    @Test
    void 列邀请清理残留索引不误删队长刚重邀写入的新项() {
        long p1 = fx.pid(1), p5 = fx.pid(5);
        fx.addPlayers(ZONE, p1, p5);
        long tid = fx.create(p1);
        String key = RedisKeys.teamInvite(p5);
        // 残留索引项：记录里没有给 5 的邀请，score 与之后的重邀不同
        fx.zadd(key, fx.nowMs() + 30_000, u(tid));

        AtomicBoolean fired = new AtomicBoolean();
        fx.beforeEval.set((script, keys) -> {
            if (script != TeamScript.INVITE_PRUNE || fired.get() || !keys.get(0).equals(key)) {
                return;
            }
            fired.set(true);
            // S_INVITE_LIST 之后、S_INVITE_PRUNE 之前，队长重邀：索引 score 被刷新
            requireCode(0, invite(p1, p5, tid));
        });
        ListMyInvitesResponse listed = svc.listMyInvites(p5, ListMyInvitesRequest.getDefaultInstance(), deadline());
        fx.beforeEval.set((s, k) -> {
        });
        assertThat(fired).isTrue();
        assertThat(listed.hasErrorMessage()).isFalse();
        assertThat(fx.zmembers(key)).as("score 已变，旧的清理不得删掉新索引项").contains(u(tid));
        long expire = fx.loadRecord(tid).getInvites(0).getExpireAtMs();
        assertThat(fx.zscore(key, u(tid))).isEqualTo((double) expire);

        // 再列一次：正文与索引一致，正常列出；没有正文的残留项被清理
        long stray = fx.unusedTid();
        fx.zadd(key, fx.nowMs() + 30_000, u(stray));
        long before = fx.nowMs();
        listed = svc.listMyInvites(p5, ListMyInvitesRequest.getDefaultInstance(), deadline());
        long after = fx.nowMs();
        assertThat(listed.getInvitesList()).hasSize(1);
        TeamIncomingInviteView got = listed.getInvites(0);
        assertThat(got.getTeamId()).isEqualTo(tid);
        assertThat(got.getInviter().getPlayerId()).isEqualTo(p1);
        assertThat(got.getInviter().getIsLeader()).isTrue();
        assertThat(got.getMemberCount()).isEqualTo(1);
        assertThat(got.getExpireAtMs()).isEqualTo(expire);
        assertThat(listed.getServerTimeMs()).isBetween(before, after);
        assertThat(fx.zmembers(key)).containsExactly(u(tid));
    }

    @Test
    void 列邀请按截止再按队伍号无符号排序_读队伍失败整体4030() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p5 = fx.pid(5);
        fx.addPlayers(ZONE, p1, p2, p5);
        long t1 = fx.create(p1);
        long t2 = fx.create(p2);
        requireCode(0, invite(p2, p5, t2));
        fx.waitRedisAfter(fx.nowMs());
        requireCode(0, invite(p1, p5, t1));
        ListMyInvitesResponse listed = svc.listMyInvites(p5, ListMyInvitesRequest.getDefaultInstance(), deadline());
        assertThat(listed.getInvitesList()).extracting(TeamIncomingInviteView::getTeamId).containsExactly(t2, t1);

        // 第二条邀请所在队伍的 S_READ 失败：整个 RPC 回 4030，不带部分结果（§8.1 第 15 条）
        AtomicInteger reads = new AtomicInteger();
        fx.evalOverride.set((script, keys) -> script == TeamScript.READ && reads.incrementAndGet() == 2
                ? java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("redis down")) : null);
        ListMyInvitesResponse failed = svc.listMyInvites(p5, ListMyInvitesRequest.getDefaultInstance(), deadline());
        fx.evalOverride.set((s, k) -> null);
        assertThat(failed).isEqualTo(ListMyInvitesResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(TeamTips.INTERNAL)).build());

        // 反查索引本身读失败：同样 4030，server_time_ms 为 0
        fx.evalOverride.set((script, keys) -> script == TeamScript.INVITE_LIST
                ? java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("redis down")) : null);
        failed = svc.listMyInvites(p5, ListMyInvitesRequest.getDefaultInstance(), deadline());
        fx.evalOverride.set((s, k) -> null);
        assertThat(failed.getErrorMessage().getId()).isEqualTo(TeamTips.INTERNAL);
        assertThat(failed.getServerTimeMs()).isZero();
    }

    // ================================================================ 批次 4.3 的 StartTeamMatch（team-spec §5.4）

    @Test
    void 开战_未绑定4013带视图_非队长4018_队长4027带IDLE视图_不加锁不推送() {
        long p1 = fx.pid(1), p2 = fx.pid(2);
        fx.addPlayers(ZONE, p1, p2);
        long tid = fx.create(p1);
        fx.join(p1, tid, p2);
        fx.takePushes();
        double rejected = counter("xm.team.matches", "outcome", "rejected");

        TeamResponse stale = startMatch(p1, tid + 1);
        requireCode(TeamTips.NO_TEAM, stale);
        assertThat(stale.getTeam().getTeamId()).as("4013 + 当前视图").isEqualTo(tid);

        TeamResponse notLeader = startMatch(p2, tid);
        requireCode(TeamTips.NOT_LEADER, notLeader);
        assertThat(notLeader.getTeam().getTeamId()).isEqualTo(tid);

        TeamResponse closed = startMatch(p1, tid);
        requireCode(TeamTips.DUNGEON_NOT_OPEN, closed);
        assertThat(closed.getErrorMessage().getParametersList()).isEmpty();
        assertThat(closed.getTeam().getTeamId()).isEqualTo(tid);
        assertThat(closed.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_IDLE);
        assertThat(closed.getTeam().getVersion()).isEqualTo(fx.versionOf(tid));
        assertThat(closed.getTeam().getMembershipEpoch()).isEqualTo(fx.epochOf(p1));

        assertThat(counter("xm.team.matches", "outcome", "rejected")).isEqualTo(rejected + 3);
        assertThat(fx.loadRecord(tid).getMatchLockToken()).isEmpty();
        assertThat(fx.loadRecord(tid).getMatchLockRosterList()).isEmpty();
        assertThat(fx.takePushes()).isEmpty();
    }

    @Test
    void 开战_刷新有提交时整轮重来_新队长拿到4027() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3);
        fx.addPlayers(ZONE, p1, p2, p3);
        long tid = fx.create(p1);
        fx.join(p1, tid, p2);
        fx.join(p1, tid, p3);
        fx.takePushes();
        long ver = fx.versionOf(tid);

        fx.states.put(p1, SessionState.ABSENT); // 队长登出：p2 来开战，刷新先惰性转让给 p2（占一轮），下一轮 p2 是队长
        TeamResponse resp = startMatch(p2, tid);
        requireCode(TeamTips.DUNGEON_NOT_OPEN, resp);
        assertThat(resp.getTeam().getLeaderId()).isEqualTo(p2);
        assertThat(resp.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_IDLE);
        assertThat(fx.versionOf(tid)).isEqualTo(ver + 1);
        List<Pushed> pushes = fx.takePushes();
        assertThat(pushes).extracting(Pushed::playerId).containsExactlyInAnyOrder(p1, p3);
        for (Pushed p : pushes) {
            assertThat(p.snapshot().getReason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_LEADER_OFFLINE_TRANSFERRED);
        }
    }

    @Test
    void 开战_每轮刷新都有提交时三轮用尽回4029() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3);
        fx.addPlayers(ZONE, p1, p2, p3);
        long tid = fx.create(p1);
        fx.join(p1, tid, p2);
        fx.join(p1, tid, p3);
        fx.takePushes();
        long ver = fx.versionOf(tid);

        // 每轮的会话：当前队长都「刚登出」→ 每轮都惰性转让一次
        AtomicInteger calls = new AtomicInteger();
        List<Map<Long, SessionState>> script = List.of(
                Map.of(p1, SessionState.ABSENT, p2, SessionState.ONLINE, p3, SessionState.ONLINE),
                Map.of(p1, SessionState.ONLINE, p2, SessionState.ABSENT, p3, SessionState.ONLINE),
                Map.of(p1, SessionState.ABSENT, p2, SessionState.ONLINE, p3, SessionState.ONLINE));
        fx.sessionOverride = members -> script.get(Math.min(calls.getAndIncrement(), script.size() - 1));
        double rejected = counter("xm.team.matches", "outcome", "rejected");
        TeamResponse resp = startMatch(p2, tid);
        fx.sessionOverride = null;

        requireCode(TeamTips.STATE_CHANGED, resp);
        assertThat(calls).hasValue(TeamService.MATCH_START_ROUNDS);
        assertThat(fx.versionOf(tid)).isEqualTo(ver + 3);
        assertThat(resp.getTeam().getTeamId()).as("4029 + 自由读视图").isEqualTo(tid);
        assertThat(counter("xm.team.matches", "outcome", "rejected")).isEqualTo(rejected + 1);
        for (Pushed p : fx.takePushes()) {
            assertThat(p.snapshot().getReason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_LEADER_OFFLINE_TRANSFERRED);
        }
    }

    // ================================================================ 各 RPC 的检查顺序与回包视图来源（§3）

    @Test
    void 建队_重放回4003带自己当队长的视图_发号失败回4030空视图() {
        long p1 = fx.pid(1), p2 = fx.pid(2);
        fx.addPlayers(ZONE, p1, p2);
        long tid = fx.create(p1);
        TeamResponse replay = svc.createTeam(p1, CreateTeamRequest.getDefaultInstance(), deadline());
        requireCode(TeamTips.MEMBER_IN_TEAM, replay);
        assertThat(replay.getErrorMessage().getParametersList()).isEmpty();
        assertThat(replay.getTeam().getTeamId()).isEqualTo(tid);
        assertThat(replay.getTeam().getLeaderId()).as("客户端把 4003 且 leader 是自己当成功").isEqualTo(p1);

        fx.idError = new IllegalStateException("租约无效");
        TeamResponse noId = svc.createTeam(p2, CreateTeamRequest.getDefaultInstance(), deadline());
        requireCode(TeamTips.INTERNAL, noId);
        assertThat(noId.getTeam().getTeamId()).isZero();
        assertThat(noId.getTeam().getMembershipEpoch()).as("空视图带索引缺失时的 Redis nowMs").isNotZero();
        requireNoTeamKeys(p2);
    }

    @Test
    void 建队提交结果未知被重发_回4030但自由读视图已能看到队伍() {
        long p1 = fx.pid(1);
        fx.addPlayers(ZONE, p1);
        AtomicBoolean fired = new AtomicBoolean();
        fx.beforeCommit.set((d, keys, args) -> {
            if (fired.compareAndSet(false, true)) {
                fx.evalCommit(keys, args); // 第一次已落盘、回复丢失，客户端驱动重发同一段 EVAL → {0}
            }
        });
        TeamResponse resp = svc.createTeam(p1, CreateTeamRequest.getDefaultInstance(), deadline());
        fx.beforeCommit.set((d, k, a) -> {
        });
        requireCode(TeamTips.INTERNAL, resp); // 照搬基线（§8.1 第 9 条，D20 未采纳）
        assertThat(resp.getTeam().getTeamId()).isNotZero();
        assertThat(resp.getTeam().getLeaderId()).isEqualTo(p1);
    }

    @Test
    void 申请_目标为0或自己4001带视图_自己在队4003_目标无队4013_成功回申请人自己的视图() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3);
        fx.addPlayers(ZONE, p1, p2, p3);
        TeamResponse zero = fx.apply(p2, 0);
        requireCode(TeamTips.PLAYER_ID, zero);
        assertThat(zero.hasTeam()).as("目标为 0 的 4001 带视图（与缺身份的 4001 不同）").isTrue();
        requireCode(TeamTips.PLAYER_ID, fx.apply(p2, p2));

        TeamResponse noTeam = fx.apply(p2, p3);
        requireCode(TeamTips.NO_TEAM, noTeam); // 照搬基线：文案对申请人有误导（§8.1 第 16 条）
        assertThat(noTeam.getTeam().getTeamId()).isZero();

        long tid = fx.create(p1);
        TeamResponse applied = fx.apply(p2, p1);
        requireCode(0, applied);
        assertThat(applied.getTeam().getTeamId()).as("申请人不在 J/K/L：自由读自己的视图").isZero();
        assertThat(fx.loadRecord(tid).getApplicationsList()).extracting(TeamApplicationRecord::getPlayerId).containsExactly(p2);

        long own = fx.create(p3);
        TeamResponse inTeam = fx.apply(p3, p1);
        requireCode(TeamTips.MEMBER_IN_TEAM, inTeam);
        assertThat(inTeam.getErrorMessage().getParametersList()).isEmpty();
        assertThat(inTeam.getTeam().getTeamId()).isEqualTo(own);
    }

    @Test
    void 同意申请时申请人已入别队_Lua回4003带申请人_自由读视图() {
        long p1 = fx.pid(1), p2 = fx.pid(2);
        fx.addPlayers(ZONE, p1, p2);
        long tid = fx.create(p1);
        requireCode(0, fx.apply(p2, p1));
        long other = fx.create(p2);
        fx.takePushes();

        TeamResponse resp = handle(p1, p2, true, tid);
        requireCode(TeamTips.MEMBER_IN_TEAM, resp);
        requireParam(p2, resp);
        assertThat(resp.getTeam().getTeamId()).isEqualTo(tid);
        assertThat(fx.tidOf(p2)).isEqualTo(u(other));
        assertThat(fx.takePushes()).isEmpty();
    }

    @Test
    void 邀请_前置检查早于绑定_离线4017_读会话故障4030_目标在队4003带目标() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3), p4 = fx.pid(4);
        fx.addPlayers(ZONE, p1, p2, p3, p4);
        long tid = fx.create(p1);

        fx.states.put(p2, SessionState.PRESENT);
        TeamResponse offline = invite(p1, p2, tid);
        requireCode(TeamTips.TARGET_OFFLINE, offline);
        assertThat(offline.getTeam().getTeamId()).isEqualTo(tid);
        // 调用者不在队时也先拿到 4017，而不是 4013（§8.1 第 14 条）
        requireCode(TeamTips.TARGET_OFFLINE, invite(p4, p2, 0));

        fx.onlineCheckError = new com.game.common.deadline.Deadline.DependencyException("在线目录条目损坏");
        requireCode(TeamTips.INTERNAL, invite(p1, p3, tid));
        fx.onlineCheckError = null;

        long own = fx.create(p3);
        TeamResponse inTeam = invite(p1, p3, tid);
        requireCode(TeamTips.MEMBER_IN_TEAM, inTeam);
        requireParam(p3, inTeam);
        assertThat(own).isNotZero();

        requireCode(TeamTips.PLAYER_ID, invite(p1, p1, tid));
        TeamResponse unbound = invite(p1, p4, tid + 1);
        requireCode(TeamTips.NO_TEAM, unbound);
        assertThat(unbound.getTeam().getTeamId()).isEqualTo(tid);
    }

    @Test
    void 应邀_team_id为0回4013带视图_接受成功回提交视图_记录缺失时清自己的反查项() {
        long p1 = fx.pid(1), p5 = fx.pid(5);
        fx.addPlayers(ZONE, p1, p5);
        TeamResponse zero = respond(p5, 0, true);
        requireCode(TeamTips.NO_TEAM, zero);
        assertThat(zero.hasTeam()).isTrue();

        long tid = fx.create(p1);
        requireCode(0, invite(p1, p5, tid));
        fx.takePushes();
        TeamResponse accepted = respond(p5, tid, true);
        requireCode(0, accepted);
        assertThat(accepted.getTeam().getTeamId()).isEqualTo(tid);
        assertThat(accepted.getTeam().getMembershipEpoch()).isEqualTo(fx.epochOf(p5));
        List<Pushed> pushes = fx.takePushes();
        assertThat(pushes).hasSize(1);
        requireSnapshot(pushes.get(0), p1, TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED);

        // 另一支队伍邀请 p6 后整队过期（删记录）：拒绝时记录缺失 → 先删自己的反查项，再回 4013
        long p6 = fx.pid(6), p7 = fx.pid(7);
        fx.addPlayers(ZONE, p6, p7);
        long t2 = fx.create(p7);
        requireCode(0, invite(p7, p6, t2));
        assertThat(fx.zmembers(RedisKeys.teamInvite(p6))).containsExactly(u(t2));
        fx.del(RedisKeys.teamRecord(t2), RedisKeys.teamInfo(t2));
        TeamResponse missing = respond(p6, t2, false);
        requireCode(TeamTips.NO_TEAM, missing);
        assertThat(missing.getTeam().getTeamId()).isZero();
        assertThat(fx.zmembers(RedisKeys.teamInvite(p6))).isEmpty();
    }

    @Test
    void 离队_踢人_转让的重放与拒绝语义() {
        long p1 = fx.pid(1), p2 = fx.pid(2), p3 = fx.pid(3);
        fx.addPlayers(ZONE, p1, p2, p3);
        long tid = fx.create(p1);
        fx.join(p1, tid, p2);
        fx.join(p1, tid, p3);
        fx.takePushes();

        // 用旧的 expected 离队：成功、不写，带当前视图
        TeamServiceFixture.KeyState before = fx.state();
        TeamResponse stale = leave(p2, tid + 1);
        requireCode(0, stale);
        assertThat(stale.getTeam().getTeamId()).isEqualTo(tid);
        fx.assertUnchanged(before);

        requireCode(TeamTips.KICK_SELF, kick(p1, p1, tid));
        requireCode(TeamTips.KICK_NOT_LEADER, kick(p2, p3, tid));
        TeamResponse self = svc.transferLeader(p1, TransferLeaderRequest.newBuilder().setTargetPlayerId(p1)
                .setExpectedTeamId(tid).build(), deadline());
        requireCode(TeamTips.APPOINT_SELF, self);
        assertThat(self.getTeam().getTeamId()).as("规则拒绝：同源快照视图").isEqualTo(tid);

        // 转让给不在线的成员：4024 带成员（转让目标要求 ONLINE，PRESENT 也不行）
        fx.states.put(p3, SessionState.PRESENT);
        TeamResponse offline = svc.transferLeader(p1, TransferLeaderRequest.newBuilder().setTargetPlayerId(p3)
                .setExpectedTeamId(tid).build(), deadline());
        requireCode(TeamTips.MEMBER_OFFLINE, offline);
        requireParam(p3, offline);
        fx.states.put(p3, SessionState.ONLINE);

        requireCode(0, kick(p1, p3, tid));
        TeamResponse replay = kick(p1, p3, tid);
        requireCode(TeamTips.MEMBER_NOT_IN_TEAM, replay);
        requireParam(p3, replay);
        TeamResponse kickZero = kick(p1, 0, tid);
        requireCode(TeamTips.MEMBER_NOT_IN_TEAM, kickZero);
        assertThat(kickZero.getErrorMessage().getParametersList()).isEmpty();

        // 转让：除调用者外全员收 LEADER_TRANSFERRED
        fx.takePushes();
        TeamResponse transferred = svc.transferLeader(p1, TransferLeaderRequest.newBuilder().setTargetPlayerId(p2)
                .setExpectedTeamId(tid).build(), deadline());
        requireCode(0, transferred);
        assertThat(transferred.getTeam().getLeaderId()).isEqualTo(p2);
        List<Pushed> pushes = fx.takePushes();
        assertThat(pushes).hasSize(1);
        assertThat(requireSnapshot(pushes.get(0), p2, TeamChangeReason.TEAM_CHANGE_REASON_LEADER_TRANSFERRED).getActorId())
                .isEqualTo(p2);

        // 最后一人离队即解散：回空视图（新 epoch），没有快照收件人
        requireCode(0, leave(p1, tid));
        TeamResponse last = leave(p2, tid);
        requireCode(0, last);
        assertThat(last.getTeam().getTeamId()).isZero();
        assertThat(last.getTeam().getMembershipEpoch()).isEqualTo(fx.epochOf(p2));
        assertThat(fx.exists(RedisKeys.teamRecord(tid))).isFalse();
    }

    @Test
    void 查队伍_记录TTL不足12小时时续期_不涨版本() {
        long p1 = fx.pid(1);
        fx.addPlayers(ZONE, p1);
        long tid = fx.create(p1);
        long ver = fx.versionOf(tid);
        fx.expireSeconds(RedisKeys.teamRecord(tid), 100);
        TeamResponse resp = getMyTeam(p1, false);
        requireCode(0, resp);
        assertThat(resp.getTeam().getVersion()).isEqualTo(ver);
        assertThat(fx.ttlSeconds(RedisKeys.teamRecord(tid))).isGreaterThan(TeamStore.TOUCH_THRESHOLD_SECONDS);
        assertThat(fx.versionOf(tid)).isEqualTo(ver);
    }

    @Test
    void 回包与推送的tip参数只放十进制玩家号() {
        long p1 = fx.pid(1), p3 = fx.pid(3);
        fx.addPlayers(ZONE, p1, p3);
        long tid = fx.create(p1);
        fx.create(p3);
        TeamResponse inTeam = invite(p1, p3, tid);
        TipInfoMessage tip = inTeam.getErrorMessage();
        assertThat(tip.getParametersList()).hasSize(1);
        assertThat(tip.getParameters(0)).matches("[0-9]+").isEqualTo(Long.toUnsignedString(p3));
        assertThat(Long.parseUnsignedLong(tip.getParameters(0))).isEqualTo(p3);
    }
}
