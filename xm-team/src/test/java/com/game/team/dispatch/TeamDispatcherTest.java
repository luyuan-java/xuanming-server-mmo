package com.game.team.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.contract.TeamContractFixtures;
import com.game.discovery.presence.PlayerPushes;
import com.game.proto.team.ApplyJoinTeamRequest;
import com.game.proto.team.ListMyInvitesResponse;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.proto.team.TeamView;
import com.game.team.match.NoTeamBattle;
import com.game.team.metrics.TeamMetrics;
import com.game.team.presence.SessionReads;
import com.game.team.push.TeamPushes;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.SessionState;
import com.game.team.rules.TeamTips;
import com.game.team.service.TeamMethods;
import com.game.team.service.TeamService;
import com.game.team.store.TeamRedis;
import com.game.team.store.TeamScript;
import com.game.team.store.TeamStore;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 派发层（team-spec §6.3，§10.3「TeamDispatcher」）：路由、上行推送号、解析先于身份、缺身份 in-band 4001、过载 / 排队超预算 / 处理器异常
 * in-band 4030、不认识的号、指标 result、契约缺号启动失败。不连 Redis：存储层用假 {@link TeamRedis}。
 */
class TeamDispatcherTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final long NOW = 1_800_000_000_000L;
    private static final long ME = Long.MIN_VALUE + 5;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TeamMetrics metrics = new TeamMetrics(registry, false);
    private final AtomicInteger redisCalls = new AtomicInteger();

    /** 假 Redis 的三种行为。 */
    enum Mode {
        /** 谁都没有队伍。 */
        NO_TEAM,
        /** 所有调用异常完成（依赖故障）。 */
        FAIL,
        /** 同步抛出（模拟程序缺陷：异常越过存储层的故障约定）。 */
        THROW
    }

    private final class FakeRedis implements TeamRedis {

        private final Mode mode;

        FakeRedis(Mode mode) {
            this.mode = mode;
        }

        @Override
        public CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
            redisCalls.incrementAndGet();
            return switch (mode) {
                case THROW -> throw new IllegalStateException("bug");
                case FAIL -> CompletableFuture.failedFuture(new IllegalStateException("redis down"));
                case NO_TEAM -> switch (script) {
                    case READ -> CompletableFuture.completedFuture(List.of(ascii(""), ascii(NOW), ascii(""), ascii(""), -2L,
                            ascii(NOW)));
                    case INVITE_LIST -> CompletableFuture.completedFuture(List.of(ascii(NOW)));
                    default -> CompletableFuture.failedFuture(new IllegalStateException("unexpected " + script));
                };
            };
        }

        @Override
        public CompletionStage<byte[]> hget(String key, String field) {
            redisCalls.incrementAndGet();
            return switch (mode) {
                case THROW -> throw new IllegalStateException("bug");
                case FAIL -> CompletableFuture.failedFuture(new IllegalStateException("redis down"));
                case NO_TEAM -> CompletableFuture.completedFuture(null);
            };
        }
    }

    private static byte[] ascii(Object v) {
        return String.valueOf(v).getBytes(StandardCharsets.US_ASCII);
    }

    private static final SessionReads NO_SESSIONS = new SessionReads() {
        @Override
        public Map<Long, SessionState> load(Collection<Long> members, Deadline deadline) {
            return Map.of();
        }

        @Override
        public boolean isOnline(long playerId, Deadline deadline) {
            return false;
        }
    };

    private final List<Long> pushed = Collections.synchronizedList(new ArrayList<>());

    private TeamService service(Mode mode) {
        TeamStore store = new TeamStore(new FakeRedis(mode));
        TeamPushes pushes = new TeamPushes(store, (ids, d) -> Map.of(), (pid, content) -> {
            pushed.add(pid);
            return CompletableFuture.completedFuture(PlayerPushes.Outcome.SENT);
        }, Runnable::run, metrics, Duration.ofSeconds(3), new TeamPushes.MessageIds(213, 215, 203));
        return new TeamService(store, NO_SESSIONS, (ids, d) -> Map.of(), (ids, d) -> Map.of(), () -> 1L,
                NoTeamBattle.INSTANCE, pushes, metrics, RuleConfig.DEFAULT);
    }

    private TeamDispatcher dispatcher(Mode mode, Executor executor, long budgetMillis) {
        return new TeamDispatcher(REGISTRY, service(mode), executor, metrics, budgetMillis);
    }

    private TeamDispatcher dispatcher(Mode mode) {
        return dispatcher(mode, Runnable::run, 3500);
    }

    private static int id(String method) {
        return REGISTRY.requireId(TeamMethods.SERVICE, method);
    }

    private static ClientCall call(int messageId, long playerId, ByteString body) {
        return ClientCall.newBuilder().setMessageId(messageId).setBody(body)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(9).setPlayerId(playerId)).build();
    }

    private double requests(String method, String result) {
        return registry.get("xm.team.requests").tag("method", method).tag("result", result).timer().count();
    }

    private static final ByteString BAD_BODY = ByteString.copyFrom(new byte[] {(byte) 0xFF, 0x01});

    @Test
    void 接管12个C2S与3个推送占位_号来自契约() {
        TeamDispatcher d = dispatcher(Mode.THROW);
        assertThat(d.routedMessageIds()).hasSize(12)
                .containsExactlyInAnyOrderElementsOf(TeamMethods.REQUESTS.stream().map(TeamDispatcherTest::id).toList());
        assertThat(d.pushMessageIds()).containsExactlyInAnyOrder(id("NotifyTeamSnapshot"), id("NotifyTeamInvite"),
                id("NotifyTeamEvent"));
    }

    @Test
    void 上行推送号_tip0空body_不看身份不碰存储_计forbidden() {
        TeamDispatcher d = dispatcher(Mode.THROW);
        for (String method : TeamMethods.PUSHES) {
            ClientReply reply = d.dispatch(call(id(method), 0, ByteString.EMPTY)).join();
            assertThat(reply).isEqualTo(ClientReply.getDefaultInstance());
            assertThat(requests(method, "forbidden")).isEqualTo(1);
        }
        ByteString snapshot = TeamSnapshotS2C.newBuilder().setTeam(TeamView.newBuilder().setTeamId(1)).build().toByteString();
        assertThat(d.dispatch(call(id("NotifyTeamSnapshot"), ME, snapshot)).join()).isEqualTo(ClientReply.getDefaultInstance());
        // 推送占位也先解码（基线 gRPC 解码在业务之前）：坏包回信封 1003
        assertThat(d.dispatch(call(id("NotifyTeamEvent"), ME, BAD_BODY)).join().getTipId()).isEqualTo(1003);
        assertThat(requests("NotifyTeamEvent", "bad_request")).isEqualTo(1);
        assertThat(redisCalls).hasValue(0);
        assertThat(pushed).isEmpty();
    }

    @Test
    void 缺身份回in_band4001_不带视图_两种应答类型() throws Exception {
        TeamDispatcher d = dispatcher(Mode.THROW);
        for (String method : TeamMethods.REQUESTS) {
            for (ClientCall c : List.of(call(id(method), 0, ByteString.EMPTY),
                    ClientCall.newBuilder().setMessageId(id(method)).build())) {
                ClientReply reply = d.dispatch(c).join();
                assertThat(reply.getTipId()).as(method).isZero();
                if (method.equals(TeamMethods.LIST_MY_INVITES)) {
                    ListMyInvitesResponse r = ListMyInvitesResponse.parseFrom(reply.getBody());
                    assertThat(r).isEqualTo(ListMyInvitesResponse.newBuilder()
                            .setErrorMessage(com.game.proto.TipInfoMessage.newBuilder().setId(TeamTips.PLAYER_ID)).build());
                } else {
                    TeamResponse r = TeamResponse.parseFrom(reply.getBody());
                    assertThat(r.getErrorMessage().getId()).as(method).isEqualTo(TeamTips.PLAYER_ID);
                    assertThat(r.getErrorMessage().getParametersList()).isEmpty();
                    assertThat(r.hasTeam()).as("%s：没有身份不回任何视图", method).isFalse();
                }
            }
            assertThat(requests(method, "unauthenticated")).isEqualTo(2);
        }
        assertThat(redisCalls).hasValue(0);
    }

    @Test
    void 请求体解析先于身份检查_坏包回信封1003() {
        TeamDispatcher d = dispatcher(Mode.THROW);
        int apply = id(TeamMethods.APPLY_JOIN_TEAM);
        ClientReply unauthBad = d.dispatch(call(apply, 0, BAD_BODY)).join();
        assertThat(unauthBad).isEqualTo(ClientReply.newBuilder().setTipId(1003).build());
        ClientReply bad = d.dispatch(call(apply, ME, BAD_BODY)).join();
        assertThat(bad.getTipId()).isEqualTo(1003);
        assertThat(bad.getBody()).isEmpty();
        assertThat(requests(TeamMethods.APPLY_JOIN_TEAM, "bad_request")).isEqualTo(2);
        assertThat(redisCalls).hasValue(0);
    }

    @Test
    void 不认识的消息号_契约里没有回1013_有但不归team回1006() {
        TeamDispatcher d = dispatcher(Mode.THROW);
        assertThat(d.dispatch(call(65_000, ME, ByteString.EMPTY)).join().getTipId()).isEqualTo(1013);
        int login = REGISTRY.requireId("ClientPlayerLogin", "Login");
        assertThat(d.dispatch(call(login, ME, ByteString.EMPTY)).join().getTipId()).isEqualTo(1006);
        assertThat(requests(TeamMetrics.UNROUTED, "unsupported")).isEqualTo(2);
    }

    @Test
    void 正常应答_业务拒绝_依赖故障的结果分类() throws Exception {
        TeamDispatcher ok = dispatcher(Mode.NO_TEAM);
        ClientReply reply = ok.dispatch(call(id(TeamMethods.GET_MY_TEAM), ME, ByteString.EMPTY)).join();
        TeamResponse mine = TeamResponse.parseFrom(reply.getBody());
        assertThat(mine.hasErrorMessage()).isFalse();
        assertThat(mine.getTeam()).isEqualTo(TeamView.newBuilder().setCapacity(5).setMembershipEpoch(NOW).setServerTimeMs(NOW)
                .build());
        assertThat(requests(TeamMethods.GET_MY_TEAM, "ok")).isEqualTo(1);

        ListMyInvitesResponse invites = ListMyInvitesResponse.parseFrom(
                ok.dispatch(call(id(TeamMethods.LIST_MY_INVITES), ME, ByteString.EMPTY)).join().getBody());
        assertThat(invites).isEqualTo(ListMyInvitesResponse.newBuilder().setServerTimeMs(NOW).build());

        // 目标为自己：4001 + 调用者自由读视图（与缺身份的 4001 不同，带视图）
        ByteString self = ApplyJoinTeamRequest.newBuilder().setTargetPlayerId(ME).build().toByteString();
        TeamResponse rejected = TeamResponse.parseFrom(ok.dispatch(call(id(TeamMethods.APPLY_JOIN_TEAM), ME, self)).join()
                .getBody());
        assertThat(rejected.getErrorMessage().getId()).isEqualTo(TeamTips.PLAYER_ID);
        assertThat(rejected.hasTeam()).isTrue();
        assertThat(requests(TeamMethods.APPLY_JOIN_TEAM, "business_error")).isEqualTo(1);

        // 依赖故障：in-band 4030，自由读也失败 → 不带视图
        TeamDispatcher down = dispatcher(Mode.FAIL);
        TeamResponse failed = TeamResponse.parseFrom(down.dispatch(call(id(TeamMethods.GET_MY_TEAM), ME, ByteString.EMPTY))
                .join().getBody());
        assertThat(failed.getErrorMessage().getId()).isEqualTo(TeamTips.INTERNAL);
        assertThat(failed.hasTeam()).isFalse();
        assertThat(requests(TeamMethods.GET_MY_TEAM, "internal_error")).isEqualTo(1);
    }

    @Test
    void 处理器异常回in_band4030_不带视图和参数() throws Exception {
        TeamDispatcher d = dispatcher(Mode.THROW);
        ClientReply reply = d.dispatch(call(id(TeamMethods.GET_MY_TEAM), ME, ByteString.EMPTY)).join();
        assertThat(reply.getTipId()).isZero();
        assertThat(TeamResponse.parseFrom(reply.getBody())).isEqualTo(TeamResponse.newBuilder()
                .setErrorMessage(com.game.proto.TipInfoMessage.newBuilder().setId(TeamTips.INTERNAL)).build());
        assertThat(requests(TeamMethods.GET_MY_TEAM, "internal_error")).isEqualTo(1);
    }

    @Test
    void 工作队列满回in_band4030_不带视图和参数() throws Exception {
        TeamDispatcher d = dispatcher(Mode.NO_TEAM, task -> {
            throw new RejectedExecutionException("full");
        }, 3500);
        ClientReply reply = d.dispatch(call(id(TeamMethods.CREATE_TEAM), ME, ByteString.EMPTY)).join();
        assertThat(reply.getTipId()).isZero();
        assertThat(TeamResponse.parseFrom(reply.getBody())).isEqualTo(TeamResponse.newBuilder()
                .setErrorMessage(com.game.proto.TipInfoMessage.newBuilder().setId(TeamTips.INTERNAL)).build());
        ListMyInvitesResponse list = ListMyInvitesResponse.parseFrom(
                d.dispatch(call(id(TeamMethods.LIST_MY_INVITES), ME, ByteString.EMPTY)).join().getBody());
        assertThat(list.getErrorMessage().getId()).isEqualTo(TeamTips.INTERNAL);
        assertThat(list.getErrorMessage().getParametersList()).isEmpty();
        assertThat(requests(TeamMethods.CREATE_TEAM, "overloaded")).isEqualTo(1);
        assertThat(requests(TeamMethods.LIST_MY_INVITES, "overloaded")).isEqualTo(1);
        assertThat(redisCalls).hasValue(0);
    }

    @Test
    void 排队等过了预算回in_band4030() throws Exception {
        Executor slow = task -> CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            task.run();
        });
        TeamDispatcher d = dispatcher(Mode.NO_TEAM, slow, 30);
        ClientReply reply = d.dispatch(call(id(TeamMethods.GET_MY_TEAM), ME, ByteString.EMPTY)).join();
        TeamResponse r = TeamResponse.parseFrom(reply.getBody());
        assertThat(r.getErrorMessage().getId()).isEqualTo(TeamTips.INTERNAL);
        assertThat(r.hasTeam()).isFalse();
        assertThat(requests(TeamMethods.GET_MY_TEAM, "overloaded")).isEqualTo(1);
        assertThat(redisCalls).hasValue(0);
    }

    @Test
    void 结果分类按4030判故障() {
        assertThat(TeamDispatcher.resultOf(TeamResponse.getDefaultInstance())).isEqualTo(TeamMetrics.RequestResult.OK);
        assertThat(TeamDispatcher.resultOf(TeamResponse.newBuilder().setErrorMessage(
                com.game.proto.TipInfoMessage.newBuilder().setId(TeamTips.STATE_CHANGED)).build()))
                .isEqualTo(TeamMetrics.RequestResult.BUSINESS_ERROR);
        assertThat(TeamDispatcher.resultOf(TeamResponse.newBuilder().setErrorMessage(
                com.game.proto.TipInfoMessage.newBuilder().setId(TeamTips.INTERNAL)).build()))
                .isEqualTo(TeamMetrics.RequestResult.INTERNAL_ERROR);
    }

    @Test
    void 契约缺号即启动失败() {
        for (String method : List.of(TeamMethods.CREATE_TEAM, TeamMethods.START_TEAM_MATCH, TeamMethods.NOTIFY_TEAM_INVITE)) {
            MessageIdRegistry broken = TeamContractFixtures.registryWithout(TeamMethods.SERVICE + method);
            assertThat(broken.idOf(TeamMethods.SERVICE, method)).isEmpty();
            assertThatThrownBy(() -> new TeamDispatcher(broken, service(Mode.THROW), Runnable::run, metrics, 3500))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(method);
        }
    }
}
