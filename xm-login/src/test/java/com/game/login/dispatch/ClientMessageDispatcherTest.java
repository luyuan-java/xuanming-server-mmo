package com.game.login.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.BindAccount;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.contract.MessageIdRegistry;
import com.game.login.account.AccountLogin;
import com.game.login.auth.DevPasswordRule;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.handler.LeaveGameHandler;
import com.game.login.handler.LoginHandler;
import com.game.login.metrics.LoginMetrics;
import com.game.login.testing.InMemoryLoginDevices;
import com.game.login.testing.InMemoryLoginTokens;
import com.game.player.store.PlayerStore;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.CreatePlayerRequest;
import com.game.proto.login.CreatePlayerResponse;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.login.LoginEmptyResponse;
import com.game.proto.login.LoginNodeDisconnectRequest;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.table.CommonErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientMessageDispatcherTest {

    private static MessageIdRegistry registry;

    private static final SessionContext SESSION = SessionContext.newBuilder()
            .setGateNodeId(1).setSessionId(3).setZoneId(1).build();
    private static final Executor DIRECT = Runnable::run;
    private static final int UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;

    @BeforeAll
    static void loadRegistry() {
        registry = MessageIdRegistry.loadFromClasspath();
    }

    /** 记录收到的请求、按给定行为应答的处理器替身。 */
    private static final class StubHandler<R extends Message> implements ClientMessageHandler<R> {
        final String method;
        final Class<R> requestType;
        final Class<? extends Message> responseType;
        final Function<TipInfoMessage, Optional<Message>> failure;
        final List<R> received = new CopyOnWriteArrayList<>();
        Function<R, CompletableFuture<HandlerReply>> behavior = r -> CompletableFuture.completedFuture(HandlerReply.none());

        StubHandler(String method, Class<R> requestType, Class<? extends Message> responseType,
                    Function<TipInfoMessage, Optional<Message>> failure) {
            this.method = method;
            this.requestType = requestType;
            this.responseType = responseType;
            this.failure = failure;
        }

        @Override
        public String methodName() {
            return method;
        }

        @Override
        public Class<R> requestType() {
            return requestType;
        }

        @Override
        public Class<? extends Message> responseType() {
            return responseType;
        }

        @Override
        public CompletableFuture<HandlerReply> handle(SessionContext session, R request) {
            received.add(request);
            return behavior.apply(request);
        }

        @Override
        public Optional<Message> failureBody(TipInfoMessage tip) {
            return failure.apply(tip);
        }
    }

    private final StubHandler<LoginRequest> login = new StubHandler<>("Login", LoginRequest.class, LoginResponse.class,
            tip -> Optional.of(LoginResponse.newBuilder().setErrorMessage(tip).build()));
    private final StubHandler<CreatePlayerRequest> create = new StubHandler<>("CreatePlayer",
            CreatePlayerRequest.class, CreatePlayerResponse.class,
            tip -> Optional.of(CreatePlayerResponse.newBuilder().setErrorMessage(tip).build()));
    private final StubHandler<EnterGameRequest> enter = new StubHandler<>("EnterGame",
            EnterGameRequest.class, EnterGameResponse.class,
            tip -> Optional.of(EnterGameResponse.newBuilder().setErrorMessage(tip).build()));
    private final StubHandler<LeaveGameRequest> leave = new StubHandler<>("LeaveGame",
            LeaveGameRequest.class, LoginEmptyResponse.class, tip -> Optional.empty());
    private final StubHandler<LoginNodeDisconnectRequest> disconnect = new StubHandler<>("Disconnect",
            LoginNodeDisconnectRequest.class, LoginEmptyResponse.class, tip -> Optional.empty());

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LoginMetrics metrics = new LoginMetrics(meters);

    private ClientMessageDispatcher dispatcher(Executor executor) {
        return new ClientMessageDispatcher(registry, List.of(login, create, enter, leave, disconnect), executor, metrics);
    }

    private static ClientCall call(int messageId, Message body) {
        return call(messageId, body.toByteString());
    }

    private static ClientCall call(int messageId, ByteString body) {
        return ClientCall.newBuilder().setSession(SESSION).setMessageId(messageId).setBody(body).setRequestId(42).build();
    }

    /** 没出现过的组合计 0。 */
    private long requests(String method, String result) {
        Timer timer = meters.find("xm.login.requests").tag("method", method).tag("result", result).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void 消息号与契约一致() {
        assertThat(registry.requireId(ClientMessageDispatcher.SERVICE, "Login")).isEqualTo(48);
        assertThat(registry.requireId(ClientMessageDispatcher.SERVICE, "CreatePlayer")).isEqualTo(14);
        assertThat(registry.requireId(ClientMessageDispatcher.SERVICE, "EnterGame")).isEqualTo(26);
        assertThat(registry.requireId(ClientMessageDispatcher.SERVICE, "LeaveGame")).isEqualTo(17);
        assertThat(registry.requireId(ClientMessageDispatcher.SERVICE, "Disconnect")).isEqualTo(58);
        assertThat(dispatcher(DIRECT).routedMessageIds()).containsExactlyInAnyOrder(48, 14, 26, 17, 58);
    }

    @Test
    void 按消息号派发到对应处理器() {
        ClientMessageDispatcher dispatcher = dispatcher(DIRECT);
        LoginRequest loginRequest = LoginRequest.newBuilder().setAccount("robot_0001").build();
        EnterGameRequest enterRequest = EnterGameRequest.newBuilder().setPlayerId(9).build();

        dispatcher.dispatch(call(48, loginRequest)).join();
        dispatcher.dispatch(call(14, CreatePlayerRequest.getDefaultInstance())).join();
        dispatcher.dispatch(call(26, enterRequest)).join();
        dispatcher.dispatch(call(17, LeaveGameRequest.getDefaultInstance())).join();
        dispatcher.dispatch(call(58, LoginNodeDisconnectRequest.getDefaultInstance())).join();

        assertThat(login.received).containsExactly(loginRequest);
        assertThat(create.received).containsExactly(CreatePlayerRequest.getDefaultInstance());
        assertThat(enter.received).containsExactly(enterRequest);
        assertThat(leave.received).hasSize(1);
        assertThat(disconnect.received).hasSize(1);
    }

    @Test
    void 应答体与指令原样转给gate() throws Exception {
        LoginResponse body = LoginResponse.getDefaultInstance();
        SessionDirective bind = SessionDirective.newBuilder()
                .setBindAccount(BindAccount.newBuilder().setAccount("robot_0001")).build();
        login.behavior = r -> CompletableFuture.completedFuture(HandlerReply.of(body, bind));

        ClientReply reply = dispatcher(DIRECT).dispatch(call(48, LoginRequest.getDefaultInstance())).join();

        assertThat(reply.getTipId()).isZero();
        assertThat(reply.getBody()).isEqualTo(body.toByteString());
        assertThat(reply.getDirectivesList()).containsExactly(bind);
    }

    @Test
    void 空应答类型不回包() {
        ClientReply reply = dispatcher(DIRECT).dispatch(call(17, LeaveGameRequest.getDefaultInstance())).join();
        assertThat(reply).isEqualTo(ClientReply.getDefaultInstance());
    }

    @Test
    void LeaveGame真处理器_无应答体只带UnbindPlayer指令() {
        ClientMessageDispatcher dispatcher = new ClientMessageDispatcher(registry, List.of(new LeaveGameHandler()), DIRECT,
                metrics);
        ClientCall inGame = call(17, LeaveGameRequest.getDefaultInstance()).toBuilder()
                .setSession(SESSION.toBuilder().setAccount("robot_0001").setPlayerId(9))
                .build();

        ClientReply reply = dispatcher.dispatch(inGame).join();

        assertThat(reply.getBody()).isEmpty();
        assertThat(reply.getTipId()).isZero();
        assertThat(reply.getDirectivesList()).extracting(SessionDirective::getKindCase)
                .containsExactly(SessionDirective.KindCase.UNBIND_PLAYER);
    }

    @Test
    void 不认识的消息号回传输层tip() {
        ClientMessageDispatcher dispatcher = dispatcher(DIRECT);

        ClientReply unknown = dispatcher.dispatch(call(999_999, ByteString.EMPTY)).join();
        assertThat(unknown.getTipId()).isEqualTo(CommonErrorTip.common_error.kMessageIdNotFound_VALUE);
        assertThat(unknown.getBody()).isEmpty();

        int refreshToken = registry.requireId(ClientMessageDispatcher.SERVICE, "RefreshToken");
        ClientReply notImplemented = dispatcher.dispatch(call(refreshToken, ByteString.EMPTY)).join();
        assertThat(notImplemented.getTipId()).isEqualTo(CommonErrorTip.common_error.kFeatureUnavailable_VALUE);
        assertThat(notImplemented.getBody()).isEmpty();

        assertThat(requests(LoginMetrics.UNROUTED, "unsupported")).as("方法标签不随消息号增长").isEqualTo(2);
    }

    @Test
    void 请求体解析失败回传输层tip_不进处理器() {
        // 字段 1 声明长度 5，实际只有 1 字节。
        ByteString truncated = ByteString.copyFrom(new byte[]{0x0A, 0x05, 'a'});
        ClientReply reply = dispatcher(DIRECT).dispatch(call(48, truncated)).join();

        assertThat(reply.getTipId()).isEqualTo(CommonErrorTip.common_error.kRequestMessageParseError_VALUE);
        assertThat(reply.getBody()).isEmpty();
        assertThat(login.received).isEmpty();
        assertThat(requests("Login", "bad_request")).isEqualTo(1);
    }

    @Test
    void 处理器故障转成应答体里的服务不可用() throws Exception {
        login.behavior = r -> {
            throw new IllegalStateException("db down");
        };
        enter.behavior = r -> CompletableFuture.failedFuture(new IllegalStateException("db down"));
        ClientMessageDispatcher dispatcher = dispatcher(DIRECT);

        ClientReply loginReply = dispatcher.dispatch(call(48, LoginRequest.getDefaultInstance())).join();
        assertThat(loginReply.getTipId()).isZero();
        assertThat(LoginResponse.parseFrom(loginReply.getBody()).getErrorMessage().getId()).isEqualTo(UNAVAILABLE);

        ClientReply enterReply = dispatcher.dispatch(call(26, EnterGameRequest.getDefaultInstance())).join();
        assertThat(EnterGameResponse.parseFrom(enterReply.getBody()).getErrorMessage().getId()).isEqualTo(UNAVAILABLE);

        assertThat(requests("Login", "internal_error")).as("同步抛出").isEqualTo(1);
        assertThat(requests("EnterGame", "internal_error")).as("future 异常完成").isEqualTo(1);
    }

    @Test
    void 空应答类型的处理器故障不回包() {
        leave.behavior = r -> {
            throw new IllegalStateException("boom");
        };
        ClientReply reply = dispatcher(DIRECT).dispatch(call(17, LeaveGameRequest.getDefaultInstance())).join();
        assertThat(reply).isEqualTo(ClientReply.getDefaultInstance());
    }

    @Test
    void 工作队列满时快速回服务不可用() throws Exception {
        Executor full = task -> {
            throw new RejectedExecutionException("full");
        };
        ClientReply reply = dispatcher(full).dispatch(call(14, CreatePlayerRequest.getDefaultInstance())).join();

        assertThat(CreatePlayerResponse.parseFrom(reply.getBody()).getErrorMessage().getId()).isEqualTo(UNAVAILABLE);
        assertThat(create.received).isEmpty();
        assertThat(requests("CreatePlayer", "overloaded")).isEqualTo(1);
    }

    @Test
    void 指标_业务拒绝与成功按应答体的error_message区分() {
        login.behavior = r -> CompletableFuture.completedFuture(HandlerReply.of(LoginResponse.getDefaultInstance()));
        enter.behavior = r -> CompletableFuture.completedFuture(HandlerReply.of(EnterGameResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(2028)).build()));
        ClientMessageDispatcher dispatcher = dispatcher(DIRECT);

        dispatcher.dispatch(call(48, LoginRequest.getDefaultInstance())).join();
        dispatcher.dispatch(call(26, EnterGameRequest.getDefaultInstance())).join();
        dispatcher.dispatch(call(17, LeaveGameRequest.getDefaultInstance())).join();

        assertThat(requests("Login", "ok")).as("全默认值的成功应答（0 字节）也是成功").isEqualTo(1);
        assertThat(requests("EnterGame", "business_error")).isEqualTo(1);
        assertThat(requests("LeaveGame", "ok")).as("空应答类型不回包，按成功计").isEqualTo(1);
    }

    @Test
    void 处理器类型与契约不符时启动失败() {
        StubHandler<CreatePlayerRequest> wrong = new StubHandler<>("Login", CreatePlayerRequest.class,
                LoginResponse.class, tip -> Optional.empty());
        assertThatThrownBy(() -> new ClientMessageDispatcher(registry, List.of(wrong), DIRECT, metrics))
                .isInstanceOf(IllegalStateException.class);

        StubHandler<LoginRequest> wrongResponse = new StubHandler<>("Login", LoginRequest.class,
                LoginEmptyResponse.class, tip -> Optional.empty());
        assertThatThrownBy(() -> new ClientMessageDispatcher(registry, List.of(wrongResponse), DIRECT, metrics))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 方法缺号或重复时启动失败() {
        StubHandler<LoginRequest> missing = new StubHandler<>("NoSuchMethod", LoginRequest.class,
                LoginResponse.class, tip -> Optional.empty());
        assertThatThrownBy(() -> new ClientMessageDispatcher(registry, List.of(missing), DIRECT, metrics))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClientMessageDispatcher(registry, List.of(login, login), DIRECT, metrics))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 与真实Login处理器串起来_成功应答不带error_message() throws Exception {
        PlayerStore store = mock(PlayerStore.class);
        when(store.listPlayers("robot_0001")).thenReturn(List.of());
        InMemoryLoginTokens tokens = new InMemoryLoginTokens();
        LoginHandler realLogin = new LoginHandler(new AccountLogin(LoginAuthenticator.withDevPassword(
                new DevPasswordRule("secret", List.of("robot_")), tokens), store, tokens, new InMemoryLoginDevices(3)));
        ClientMessageDispatcher dispatcher = new ClientMessageDispatcher(registry, List.of(realLogin), DIRECT, metrics);

        ClientReply reply = dispatcher.dispatch(call(48,
                LoginRequest.newBuilder().setAccount("robot_0001").setPassword("secret").build())).join();

        assertThat(reply.getTipId()).isZero();
        LoginResponse response = LoginResponse.parseFrom(reply.getBody());
        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(reply.getDirectivesList()).extracting(d -> d.getBindAccount().getAccount())
                .containsExactly("robot_0001");
        assertThat(requests("Login", "ok")).isEqualTo(1);
    }
}
