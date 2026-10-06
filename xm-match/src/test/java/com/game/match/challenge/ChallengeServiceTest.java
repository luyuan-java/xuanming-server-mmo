package com.game.match.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.presence.PlayerPushes;
import com.game.match.challenge.ChallengeStore.ChallengeRecord;
import com.game.match.gather.FailPolicy;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerPusher;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.ManualRedisClock;
import com.game.match.testing.RecordingPushes;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.MatchMode;
import com.game.proto.match.RespondChallengeRequest;
import com.game.proto.match.RespondChallengeResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 切磋 152 / 151（match-spec §6.1 两张表的每一行、§6.3、§15.2）：判定顺序、tip 码与 {@code parameters[0]} 的中文串、156 / 154 的字段与先后、
 * 记录的一次性消费、gather 的名单与失败通知、各出口的指标。依赖全是替身：玩家状态、切磋存储（内存，带手拨的「Redis 时间」）、发号、推送、开局管线。
 * 推送回调的执行器用同步的（{@code Runnable::run}），断言不必等线程。
 */
class ChallengeServiceTest {

    private static final long A = 1001;
    private static final long B = 1002;
    private static final long C = 1003;
    private static final long TTL_MS = 60_000;
    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final int INVITE_ID = REGISTRY.requireId("MatchService", "NotifyChallengeInvite");
    private static final int RESULT_ID = REGISTRY.requireId("MatchService", "NotifyChallengeResult");

    private final FakePlayerStatus players = new FakePlayerStatus().online(A, 1, 7).online(B, 2, 8).online(C, 1, 7);
    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryChallengeStore store = new InMemoryChallengeStore(clock);
    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private final MatchIds ids = new MatchIds(new Snowflake(5), leaseValid::get, () -> false);
    private final RecordingPushes pushes = new RecordingPushes();
    private final FakeGatherLauncher gather = new FakeGatherLauncher();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    /** 推送与开局的先后（「先推 154 再 gather」靠它断言）。 */
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger nonceSeq = new AtomicInteger();
    private final ChallengeService service = service(store);

    private ChallengeService service(ChallengeStore backing) {
        PlayerPusher pusher = (playerId, content) -> {
            events.add("push:" + playerId);
            return pushes.push(playerId, content);
        };
        GatherLauncher launcher = new GatherLauncher() {
            @Override
            public CompletableFuture<GatherResult> launch(GatherPlan plan) {
                events.add("launch");
                return gather.launch(plan);
            }

            @Override
            public int availablePermits() {
                return gather.availablePermits();
            }

            @Override
            public boolean awaitIdle(Duration timeout) {
                return gather.awaitIdle(timeout);
            }
        };
        return new ChallengeService(players, backing, ids, pusher, launcher, metrics, Runnable::run, TTL_MS, INVITE_ID, RESULT_ID,
                () -> "nonce-" + nonceSeq.incrementAndGet());
    }

    private static SessionContext session(long playerId) {
        return SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst").setSessionId(9).setZoneId(1)
                .setAccount("acc-" + playerId).setPlayerId(playerId).build();
    }

    private static Deadline d() {
        return Deadline.after(3_000);
    }

    private ChallengePlayerResponse challenge(long challenger, long target, int config) {
        return service.challenge(session(challenger), ChallengePlayerRequest.newBuilder().setTargetPlayerId(target).setBattleConfigId(config).build(),
                d());
    }

    private RespondChallengeResponse respond(long responder, long challengeId, boolean accept) {
        return service.respond(session(responder), RespondChallengeRequest.newBuilder().setChallengeId(challengeId).setAccept(accept).build(), d());
    }

    /** A 向 B 发起一条邀请并清掉记账，返回 challenge_id。 */
    private long invited(int config) {
        long id = challenge(A, B, config).getChallengeId();
        assertThat(id).isNotZero();
        pushes.sent.clear();
        pushes.futures.clear();
        events.clear();
        players.reads.clear();
        store.calls.clear();
        return id;
    }

    private double count(String stage, String result) {
        return meters.get("xm.match.challenges").tags("stage", stage, "result", result).counter().count();
    }

    private double pushed(String kind, String outcome) {
        return meters.get("xm.match.pushes").tags("kind", kind, "outcome", outcome).counter().count();
    }

    private static void assertTip(TipInfoMessage tip, int code, String text) {
        assertThat(tip.getId()).isEqualTo(code);
        assertThat(tip.getParametersList()).containsExactly(text);
    }

    private static ChallengeResultS2C result(RecordingPushes.Pushed pushed) throws Exception {
        assertThat(pushed.messageId()).isEqualTo(RESULT_ID);
        return ChallengeResultS2C.parseFrom(pushed.body());
    }

    // ================================================================ 152 ChallengePlayer

    @Test
    void 消息号取自契约_156是邀请_154是结果() {
        assertThat(INVITE_ID).isEqualTo(156);
        assertThat(RESULT_ID).isEqualTo(154);
    }

    @Test
    void 发起_会话没有玩家_16004缺少玩家身份_不读任何依赖() {
        ChallengePlayerResponse response = challenge(0, B, 0);

        assertTip(response.getErrorMessage(), 16004, "缺少玩家身份");
        assertThat(response.getChallengeId()).isZero();
        assertThat(players.reads).isEmpty();
        assertThat(store.calls).isEmpty();
        assertThat(count("invite", "internal")).isEqualTo(1);
    }

    @Test
    void 发起_目标为0或是自己_16007不能挑战自己() {
        assertTip(challenge(A, 0, 0).getErrorMessage(), 16007, "不能挑战自己");
        assertTip(challenge(A, A, 0).getErrorMessage(), 16007, "不能挑战自己");

        assertThat(players.reads).as("先于任何读").isEmpty();
        assertThat(count("invite", "self")).isEqualTo(2);
    }

    @Test
    void 发起_发起者有战斗锁_16010_读锁失败也算有() {
        players.inBattle(A, true);
        assertTip(challenge(A, B, 0).getErrorMessage(), 16010, "战斗尚未结束,无法发起切磋");

        players.inBattle(A, false).failLock(A);
        assertTip(challenge(A, B, 0).getErrorMessage(), 16010, "战斗尚未结束,无法发起切磋");

        assertThat(players.reads).as("发起者的锁没过，不再读目标").containsExactly("lock:1001", "lock:1001");
        assertThat(store.calls).isEmpty();
        assertThat(pushes.sent).isEmpty();
        assertThat(count("invite", "self_busy")).isEqualTo(2);
    }

    @Test
    void 发起_目标有战斗锁_16009_读锁失败也算有() {
        players.inBattle(B, true);
        assertTip(challenge(A, B, 0).getErrorMessage(), 16009, "对方正在战斗中");

        players.inBattle(B, false).failLock(B);
        assertTip(challenge(A, B, 0).getErrorMessage(), 16009, "对方正在战斗中");

        assertThat(store.calls).isEmpty();
        assertThat(count("invite", "target_busy")).isEqualTo(2);
    }

    @Test
    void 发起_读目标在线目录失败_16004服务器繁忙() {
        players.failPresence(B);

        assertTip(challenge(A, B, 0).getErrorMessage(), 16004, "服务器繁忙,请稍后再试");

        assertThat(store.calls).isEmpty();
        assertThat(count("invite", "internal")).isEqualTo(1);
    }

    @Test
    void 发起_目标不在线_16008_断线重连租约中的人也算不在线() {
        players.disconnected(B);

        assertTip(challenge(A, B, 0).getErrorMessage(), 16008, "对方不在线");

        assertThat(store.calls).isEmpty();
        assertThat(count("invite", "target_offline")).isEqualTo(1);
    }

    @Test
    void 发起_判定顺序_发起者的锁先于目标的锁先于目标在线() {
        players.inBattle(A, true).inBattle(B, true).disconnected(B);
        assertThat(challenge(A, B, 0).getErrorMessage().getId()).as("三条都中：先回发起者忙").isEqualTo(16010);

        players.inBattle(A, false);
        assertThat(challenge(A, B, 0).getErrorMessage().getId()).as("目标忙且不在线：先回对方在战斗中").isEqualTo(16009);

        players.inBattle(B, false);
        assertThat(challenge(A, B, 0).getErrorMessage().getId()).isEqualTo(16008);
        assertThat(players.reads).containsExactly("lock:1001", "lock:1001", "lock:1002", "lock:1001", "lock:1002", "presence:1002");
    }

    @Test
    void 发起_发号失败_16004服务器繁忙_不写记录不推送() {
        leaseValid.set(false);

        assertTip(challenge(A, B, 0).getErrorMessage(), 16004, "服务器繁忙,请稍后再试");

        assertThat(players.reads).as("发号在三项检查之后").containsExactly("lock:1001", "lock:1002", "presence:1002");
        assertThat(store.calls).isEmpty();
        assertThat(pushes.sent).isEmpty();
        assertThat(count("invite", "internal")).isEqualTo(1);
    }

    @Test
    void 发起_目标已有待应答的邀请_16011_先到的邀请不动() {
        long first = invited(0);

        ChallengePlayerResponse response = challenge(C, B, 0);

        assertTip(response.getErrorMessage(), 16011, "对方已有待处理的切磋邀请");
        assertThat(response.getChallengeId()).isZero();
        assertThat(store.pendingOn(B)).hasValue(first);
        assertThat(pushes.sent).as("后来者不给目标推任何东西").isEmpty();
        assertThat(count("invite", "pending")).isEqualTo(1);
    }

    @Test
    void 发起_写记录出错_16004服务器繁忙_执行前失败没有残留() {
        store.faults.failNext("invite");

        assertTip(challenge(A, B, 0).getErrorMessage(), 16004, "服务器繁忙,请稍后再试");

        assertThat(store.pendingOn(B)).isEmpty();
        assertThat(pushes.sent).isEmpty();
        assertThat(count("invite", "internal")).isEqualTo(1);
    }

    @Test
    void 发起_写记录结局不明_其实已占坑_清理后目标可以再被邀请() {
        store.faults.failNext("invite:after");

        assertTip(challenge(A, B, 0).getErrorMessage(), 16004, "服务器繁忙,请稍后再试");

        assertThat(store.pendingOn(B)).as("脚本已执行、应答丢了：占坑被清掉").isEmpty();
        assertThat(store.calls).hasSize(2).last().asString().startsWith("delete(");
        assertThat(pushes.sent).isEmpty();
        assertThat(challenge(C, B, 0).getChallengeId()).as("别人随后可以向同一目标发起").isNotZero();
    }

    @Test
    void 发起_156没推到_目标刚好下线_gate不可达_推送异常_都清理并回16004邀请发送失败() {
        Runnable[] failures = {
                () -> pushes.outcome(B, PlayerPushes.Outcome.OFFLINE),
                () -> pushes.outcome(B, PlayerPushes.Outcome.GATE_UNREACHABLE),
                () -> pushes.failFor(B)};
        for (Runnable failure : failures) {
            failure.run();

            ChallengePlayerResponse response = challenge(A, B, 0);

            assertTip(response.getErrorMessage(), 16004, "邀请发送失败,请稍后再试");
            assertThat(response.getChallengeId()).isZero();
            assertThat(store.pendingOn(B)).as("占坑已清：不挡 60 s 内的下一次发起").isEmpty();
            long attempted = pushes.sent.get(pushes.sent.size() - 1).playerId();
            assertThat(attempted).isEqualTo(B);
        }
        assertThat(pushes.sent).hasSize(3);
        assertThat(store.calls.stream().filter(c -> c.startsWith("delete("))).hasSize(3);
        assertThat(count("invite", "push_failed")).isEqualTo(3);
        assertThat(pushed("156", "offline")).isEqualTo(1);
        assertThat(pushed("156", "gate_unreachable")).isEqualTo(1);
        assertThat(pushed("156", "error")).isEqualTo(1);
    }

    @Test
    void 发起_156在请求截止内没有结果_按没推到处理() {
        pushes.hold();

        ChallengePlayerResponse response = service.challenge(session(A), ChallengePlayerRequest.newBuilder().setTargetPlayerId(B).build(),
                Deadline.after(150));

        assertTip(response.getErrorMessage(), 16004, "邀请发送失败,请稍后再试");
        assertThat(pushes.sent).hasSize(1);
        assertThat(count("invite", "push_failed")).isEqualTo(1);
    }

    @Test
    void 发起_成功_应答只带challenge_id_目标收到156_字段齐全_过期时刻取存储时间() throws Exception {
        ChallengePlayerResponse response = challenge(A, B, 0xFFFF_FFF0);

        long challengeId = response.getChallengeId();
        assertThat(challengeId).isNotZero();
        assertThat(response.hasErrorMessage()).as("成功的应答体不带 error_message").isFalse();
        assertThat(Snowflake.workerOf(challengeId)).as("challenge_id 出自 match 的发号器").isEqualTo(5);

        assertThat(pushes.sent).hasSize(1);
        RecordingPushes.Pushed pushed = pushes.sent.get(0);
        assertThat(pushed.playerId()).isEqualTo(B);
        assertThat(pushed.messageId()).isEqualTo(156);
        assertThat(pushed.content().getId()).as("推送的 MessageContent.id 填 0").isZero();
        assertThat(pushed.content().hasErrorMessage()).isFalse();
        ChallengeInviteS2C invite = ChallengeInviteS2C.parseFrom(pushed.body());
        assertThat(invite.getChallengeId()).isEqualTo(challengeId);
        assertThat(invite.getChallengerId()).isEqualTo(A);
        assertThat(invite.getChallengerName()).as("账号名（会话里已认证的账号）").isEqualTo("acc-1001");
        assertThat(invite.getBattleConfigId()).as("不校验，原样带上").isEqualTo(0xFFFF_FFF0);
        assertThat(invite.getExpiresAtMs()).isEqualTo(clock.peekMs() + TTL_MS);
        assertThat(Math.abs(invite.getExpiresAtMs() - System.currentTimeMillis())).as("替身时钟离本机时钟很远：证明没用本机时钟")
                .isGreaterThan(Duration.ofDays(1).toMillis());

        assertThat(store.recordOf(challengeId)).hasValue(new ChallengeRecord(A, B, 0xFFFF_FFF0, clock.peekMs() + TTL_MS));
        assertThat(store.pendingOn(B)).hasValue(challengeId);
        assertThat(gather.plans).as("发起不冻结任何人、不开局").isEmpty();
        assertThat(count("invite", "ok")).isEqualTo(1);
        assertThat(pushed("156", "sent")).isEqualTo(1);
    }

    @Test
    void 发起_身份只取会话_请求体里的player_id被忽略() throws Exception {
        ChallengePlayerResponse response = service.challenge(session(A),
                ChallengePlayerRequest.newBuilder().setPlayerId(C).setTargetPlayerId(B).build(), d());

        assertThat(response.getChallengeId()).isNotZero();
        assertThat(ChallengeInviteS2C.parseFrom(pushes.sent.get(0).body()).getChallengerId()).isEqualTo(A);
        assertThat(players.reads).doesNotContain("lock:1003");

        // 请求体里填自己、目标也填会话里的自己：仍然是「挑战自己」
        assertThat(service.challenge(session(A), ChallengePlayerRequest.newBuilder().setPlayerId(C).setTargetPlayerId(A).build(), d())
                .getErrorMessage().getId()).isEqualTo(16007);
    }

    @Test
    void 发起_邀请过期后_目标可以再被邀请() {
        long first = invited(0);
        clock.advanceMs(TTL_MS);

        long second = challenge(C, B, 0).getChallengeId();

        assertThat(second).isNotZero().isNotEqualTo(first);
        assertThat(store.pendingOn(B)).hasValue(second);
    }

    // ================================================================ 151 RespondChallenge

    @Test
    void 应答_会话没有玩家_16004缺少玩家身份() {
        long id = invited(0);

        assertTip(respond(0, id, true).getErrorMessage(), 16004, "缺少玩家身份");

        assertThat(store.calls).isEmpty();
        assertThat(count("respond", "internal")).isEqualTo(1);
    }

    @Test
    void 应答_读记录失败_16004服务器繁忙_记录不被消费() {
        long id = invited(0);
        store.faults.failNext("read");

        assertTip(respond(B, id, true).getErrorMessage(), 16004, "服务器繁忙,请稍后再试");

        assertThat(store.recordOf(id)).isPresent();
        assertThat(pushes.sent).isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(count("respond", "internal")).isEqualTo(1);
    }

    @Test
    void 应答_记录不存在_16012切磋邀请已过期() {
        assertTip(respond(B, 424242, true).getErrorMessage(), 16012, "切磋邀请已过期");
        assertTip(respond(B, 0, false).getErrorMessage(), 16012, "切磋邀请已过期");

        assertThat(store.calls).as("只读了一下，没有去消费").containsExactly("read(424242)", "read(0)");
        assertThat(count("respond", "expired")).isEqualTo(2);
    }

    @Test
    void 应答_邀请的TTL已过_16012() {
        long id = invited(0);
        clock.advanceMs(TTL_MS);

        assertTip(respond(B, id, true).getErrorMessage(), 16012, "切磋邀请已过期");

        assertThat(pushes.sent).isEmpty();
        assertThat(gather.plans).isEmpty();
    }

    @Test
    void 应答_应答者不是目标_16013_记录不被消费_真正的目标之后照常应答() {
        long id = invited(0);

        assertTip(respond(C, id, true).getErrorMessage(), 16013, "该邀请不是发给你的");

        assertThat(store.recordOf(id)).as("不消费").isPresent();
        assertThat(store.calls).containsExactly("read(" + id + ")");
        assertThat(pushes.sent).isEmpty();
        assertThat(count("respond", "not_target")).isEqualTo(1);

        assertThat(respond(B, id, false).hasErrorMessage()).isFalse();
        assertThat(store.recordOf(id)).isEmpty();
    }

    @Test
    void 应答_消费之后才判过期_到点即过期_记录已作废_不推送() {
        // 记录还在、但写在里面的过期时刻已到（多实例时钟不一致时的情形）：照基线先消费再判
        store.putRecord(8001, new ChallengeRecord(A, B, 0, clock.peekMs()), 30_000);

        assertTip(respond(B, 8001, true).getErrorMessage(), 16012, "切磋邀请已过期");

        assertThat(store.recordOf(8001)).as("一次性消费：过期也作废").isEmpty();
        assertThat(pushes.sent).isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(count("respond", "expired")).isEqualTo(1);
    }

    @Test
    void 应答_过期判定取存储时间_差一毫秒没到就不算_过期时刻为0的记录不判过期() throws Exception {
        store.putRecord(8001, new ChallengeRecord(A, B, 0, clock.peekMs() + 1), 30_000);
        store.putRecord(8002, new ChallengeRecord(A, B, 0, 0), 30_000);

        assertThat(respond(B, 8001, false).hasErrorMessage()).isFalse();
        assertThat(respond(B, 8002, false).hasErrorMessage()).as("同基线：expires_at_ms 解析不出来（0）时不判过期").isFalse();

        assertThat(pushes.sent).hasSize(2);
        assertThat(result(pushes.sent.get(0)).getChallengeId()).isEqualTo(8001);
        assertThat(count("respond", "declined")).isEqualTo(2);
    }

    @Test
    void 应答_拒绝_只给发起者推154false_应答是空消息_记录作废_不开局() throws Exception {
        long id = invited(0);

        RespondChallengeResponse response = respond(B, id, false);

        assertThat(response).isEqualTo(RespondChallengeResponse.getDefaultInstance());
        assertThat(response.toByteString().isEmpty()).as("应答体 0 字节").isTrue();
        assertThat(pushes.sent).hasSize(1);
        assertThat(pushes.sent.get(0).playerId()).as("只推发起者").isEqualTo(A);
        assertThat(pushes.sent.get(0).content().getId()).isZero();
        ChallengeResultS2C pushed = result(pushes.sent.get(0));
        assertThat(pushed).isEqualTo(ChallengeResultS2C.newBuilder().setChallengeId(id).setAccepted(false).setResponderId(B).build());
        assertThat(store.recordOf(id)).isEmpty();
        assertThat(store.pendingOn(B)).as("占坑已摘").isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(players.reads).as("拒绝不查战斗锁").isEmpty();
        assertThat(count("respond", "declined")).isEqualTo(1);
        assertThat(pushed("154", "sent")).isEqualTo(1);

        assertTip(respond(B, id, false).getErrorMessage(), 16012, "切磋邀请已过期");
    }

    @Test
    void 应答_接受但发起者有战斗锁_给发起者推154false_16012发起者已进入其它战斗_读锁失败同样() throws Exception {
        long first = invited(0);
        players.inBattle(A, true);

        assertTip(respond(B, first, true).getErrorMessage(), 16012, "发起者已进入其它战斗");

        assertThat(pushes.sent).hasSize(1);
        assertThat(pushes.sent.get(0).playerId()).isEqualTo(A);
        assertThat(result(pushes.sent.get(0)).getAccepted()).isFalse();
        assertThat(result(pushes.sent.get(0)).getResponderId()).isEqualTo(B);
        assertThat(store.recordOf(first)).as("记录已消费，不能再用").isEmpty();
        assertThat(gather.plans).isEmpty();

        players.inBattle(A, false);
        long second = invited(0);
        players.failLock(A);
        assertTip(respond(B, second, true).getErrorMessage(), 16012, "发起者已进入其它战斗");
        assertThat(players.reads).as("发起者没过就不再查应答者").containsExactly("lock:1001");
        assertThat(gather.plans).isEmpty();
        assertThat(count("respond", "challenger_busy")).isEqualTo(2);
    }

    @Test
    void 应答_接受但应答者有战斗锁_给发起者推154false_16010无法应战_读锁失败同样() throws Exception {
        long first = invited(0);
        players.inBattle(B, true);

        assertTip(respond(B, first, true).getErrorMessage(), 16010, "战斗尚未结束,无法应战");

        assertThat(pushes.sent).hasSize(1);
        assertThat(pushes.sent.get(0).playerId()).as("只通知发起者").isEqualTo(A);
        assertThat(result(pushes.sent.get(0)).getAccepted()).isFalse();
        assertThat(players.reads).as("权威复查：先发起者后应答者").containsExactly("lock:1001", "lock:1002");
        assertThat(gather.plans).isEmpty();

        players.inBattle(B, false);
        long second = invited(0);
        players.failLock(B);
        assertTip(respond(B, second, true).getErrorMessage(), 16010, "战斗尚未结束,无法应战");
        assertThat(gather.plans).isEmpty();
        assertThat(count("respond", "responder_busy")).isEqualTo(2);
    }

    @Test
    void 应答_接受_先推发起者再推应答者154true_然后才开局_名单发起者在前_不带票据() throws Exception {
        long id = invited(77);

        RespondChallengeResponse response = respond(B, id, true);

        assertThat(response.toByteString().isEmpty()).as("应答体为空").isTrue();
        assertThat(events).as("154 在 gather 开始之前：先发起者、后应答者").containsExactly("push:1001", "push:1002", "launch");
        for (RecordingPushes.Pushed pushed : pushes.sent) {
            assertThat(result(pushed)).isEqualTo(ChallengeResultS2C.newBuilder().setChallengeId(id).setAccepted(true).setResponderId(B).build());
        }
        assertThat(gather.plans).hasSize(1);
        GatherPlan plan = gather.plans.get(0);
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVP_CHALLENGE);
        assertThat(plan.members()).as("[发起者, 应战者] = 站位顺序").containsExactly(A, B);
        assertThat(plan.battleConfigId()).as("邀请里的配置号原样传给开局管线").isEqualTo(77);
        assertThat(plan.tickets()).isEmpty();
        assertThat(plan.onFail()).isEqualTo(FailPolicy.NO_TICKETS);
        assertThat(plan.presetBattleId()).isZero();
        assertThat(plan.activityContext()).isNull();
        assertThat(store.recordOf(id)).isEmpty();
        assertThat(count("respond", "accepted")).isEqualTo(1);
        assertThat(pushes.sent).as("开局成功：不再推送（177 / 143 由 battle 推）").hasSize(2);
    }

    @Test
    void 应答_接受后开局失败_再给双方各推一次154false_发起者在前() throws Exception {
        long id = invited(0);
        gather.hold();

        assertThat(respond(B, id, true).hasErrorMessage()).as("应答先回去，不等 gather").isFalse();
        assertThat(pushes.sent).hasSize(2);

        gather.complete(0, GatherResult.failed(GatherOutcome.PREPARE_FAILED, 9001));

        assertThat(events).containsExactly("push:1001", "push:1002", "launch", "push:1001", "push:1002");
        assertThat(result(pushes.sent.get(2))).isEqualTo(ChallengeResultS2C.newBuilder().setChallengeId(id).setAccepted(false).setResponderId(B).build());
        assertThat(result(pushes.sent.get(3))).isEqualTo(ChallengeResultS2C.newBuilder().setChallengeId(id).setAccepted(false).setResponderId(B).build());
        assertThat(pushed("154", "sent")).isEqualTo(4);
    }

    @Test
    void 应答_开局过载同样按失败通知双方() throws Exception {
        long id = invited(0);
        gather.permits(0);

        assertThat(respond(B, id, true).hasErrorMessage()).isFalse();

        assertThat(gather.plans).hasSize(1);
        assertThat(pushes.sent).extracting(RecordingPushes.Pushed::playerId).containsExactly(A, B, A, B);
        assertThat(result(pushes.sent.get(1)).getAccepted()).isTrue();
        assertThat(result(pushes.sent.get(2)).getAccepted()).isFalse();
        assertThat(result(pushes.sent.get(3)).getAccepted()).isFalse();
    }

    @Test
    void 应答_两条接受都读到了记录_只有一条开局_另一条16012() {
        StaleReads stale = new StaleReads(store);
        ChallengeService racing = service(stale);
        long id = invited(0);
        RespondChallengeRequest accept = RespondChallengeRequest.newBuilder().setChallengeId(id).setAccept(true).build();

        RespondChallengeResponse first = racing.respond(session(B), accept, d());
        RespondChallengeResponse second = racing.respond(session(B), accept, d());

        assertThat(first.hasErrorMessage()).isFalse();
        assertTip(second.getErrorMessage(), 16012, "切磋邀请已过期");
        assertThat(stale.reads.get()).as("第二条确实读到了（过时的）记录，是消费那一步挡住的").isEqualTo(2);
        assertThat(gather.plans).as("不会开两次 gather").hasSize(1);
        assertThat(pushes.sent).as("第二条不推任何东西：不会出现多余的 154").hasSize(2);
        assertThat(count("respond", "accepted")).isEqualTo(1);
        assertThat(count("respond", "expired")).isEqualTo(1);
    }

    @Test
    void 应答_消费结局不明_16004服务器繁忙_不推送不开局() {
        long id = invited(0);
        store.faults.failNext("consume:after");

        assertTip(respond(B, id, true).getErrorMessage(), 16004, "服务器繁忙,请稍后再试");

        assertThat(pushes.sent).isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(count("respond", "internal")).isEqualTo(1);
    }

    @Test
    void 应答_154推不到只计数_不影响应答也不影响开局() {
        long id = invited(0);
        pushes.failFor(A).outcome(B, PlayerPushes.Outcome.OFFLINE);

        RespondChallengeResponse response = respond(B, id, true);

        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(gather.plans).hasSize(1);
        assertThat(pushed("154", "error")).isEqualTo(1);
        assertThat(pushed("154", "offline")).isEqualTo(1);
        assertThat(count("respond", "accepted")).isEqualTo(1);
    }

    @Test
    void 应答_记录里的发起者损坏_名单不合法_按开局失败通知() throws Exception {
        store.putRecord(8001, new ChallengeRecord(B, B, 0, clock.peekMs() + TTL_MS), 30_000);

        assertThat(respond(B, 8001, true).hasErrorMessage()).isFalse();

        assertThat(gather.plans).as("名单 [B, B] 不合法：没有交给开局管线").isEmpty();
        assertThat(pushes.sent).hasSize(4);
        assertThat(result(pushes.sent.get(3)).getAccepted()).isFalse();
    }

    /** 读总是返回第一次读到的结果：模拟两条应答都在消费之前读到了记录（基线 G8 的竞态窗口）。 */
    private static final class StaleReads implements ChallengeStore {
        final AtomicInteger reads = new AtomicInteger();
        private final ChallengeStore delegate;
        private final Map<Long, Optional<ChallengeRecord>> seen = new ConcurrentHashMap<>();

        StaleReads(ChallengeStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public InviteResult invite(long challengeId, long challengerId, long targetId, int configId, long ttlMs, Deadline d) {
            return delegate.invite(challengeId, challengerId, targetId, configId, ttlMs, d);
        }

        @Override
        public void delete(long challengeId, long targetId, Deadline d) {
            delegate.delete(challengeId, targetId, d);
        }

        @Override
        public Optional<ChallengeRecord> read(long challengeId, Deadline d) {
            reads.incrementAndGet();
            return seen.computeIfAbsent(challengeId, id -> delegate.read(id, d));
        }

        @Override
        public ConsumeResult consume(long challengeId, long responderId, String nonce, Deadline d) {
            return delegate.consume(challengeId, responderId, nonce, d);
        }
    }
}
