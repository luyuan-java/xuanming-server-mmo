package com.game.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.common.deadline.Deadline;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.TimeoutCountDown;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link CallerDeadline}：gate 的 Dubbo 超时倒计时真的能过 Triple 的线、提供方在 {@code handle} 入口读得到（提供方与调用方各在自己的
 * Dubbo 框架模型里，等价于两个进程；调用方鉴权过滤器照常生效，测试密钥由 surefire 注入 {@code GAME_DUBBO_SECRET}）。钉住：
 * 调用方在 {@code handle} 上开 {@code enable-timeout-countdown} 时提供方拿到「调用超时 − 传播余量」以内的截止；没开时为空；
 * 同一条提供方线程上的下一次调用不会读到上一次留下的倒计时。
 */
class CallerDeadlineTest {

    private static final int CALL_TIMEOUT_MS = 5_000;

    private static IsolatedDubboModule server;
    /** 两个调用方各用自己的框架模型（等价于新旧两个 gate 进程），免得同一模型里两个同接口同地址的引用互相顶替。 */
    private static IsolatedDubboModule clientWith;
    private static IsolatedDubboModule clientWithout;
    /** 与 game-gate 的 {@code GateConfiguration} 同样配置：handle 不重试、开超时倒计时。 */
    private static ClientMessageService withCountdown;
    /** 没开倒计时的调用方（旧 gate）。 */
    private static ClientMessageService withoutCountdown;

    /** 假后端：handle 在入口读调用方截止，把剩余毫秒（或 "absent"）写进应答体带回。 */
    static final class Provider implements ClientMessageService {

        @Override
        public CompletableFuture<ClientReply> handle(ClientCall call) {
            Optional<Deadline> caller = CallerDeadline.fromRpcContext();
            String observed = caller.map(d -> Long.toString(d.remainingMillis())).orElse("absent");
            return CompletableFuture.completedFuture(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8(observed)).build());
        }

        @Override
        public CompletableFuture<Ack> sessionClosed(SessionClosed event) {
            return CompletableFuture.completedFuture(Ack.getDefaultInstance());
        }

        @Override
        public CompletableFuture<Ack> abandonEnter(AbandonedEnter event) {
            return CompletableFuture.completedFuture(Ack.getDefaultInstance());
        }
    }

    @BeforeAll
    static void start() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = IsolatedDubboModule.create("game-api-test-caller-deadline-provider");
        ProtocolConfig protocol = new ProtocolConfig("tri", port);
        protocol.setHost("127.0.0.1");
        ServiceConfig<ClientMessageService> service = new ServiceConfig<>(server.module());
        service.setInterface(ClientMessageService.class);
        service.setRef(new Provider());
        service.setGroup(DubboGroups.FRIEND);
        service.setRegister(false);
        service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        service.setProtocol(protocol);
        service.export();

        clientWith = IsolatedDubboModule.create("game-api-test-caller-deadline-caller");
        clientWithout = IsolatedDubboModule.create("game-api-test-caller-deadline-old-caller");
        withCountdown = reference(clientWith, port, true);
        withoutCountdown = reference(clientWithout, port, false);
    }

    private static ClientMessageService reference(IsolatedDubboModule client, int port, boolean countdown) {
        ReferenceConfig<ClientMessageService> reference = new ReferenceConfig<>(client.module());
        reference.setInterface(ClientMessageService.class);
        reference.setGroup(DubboGroups.FRIEND);
        reference.setUrl("tri://127.0.0.1:" + port);
        reference.setCheck(false);
        reference.setTimeout(CALL_TIMEOUT_MS);
        MethodConfig handle = new MethodConfig();
        handle.setName("handle");
        handle.setRetries(0);
        if (countdown) {
            handle.setParameters(Map.of(CommonConstants.ENABLE_TIMEOUT_COUNTDOWN_KEY, "true"));
        }
        reference.setMethods(List.of(handle));
        return reference.get();
    }

    @AfterAll
    static void stop() {
        if (clientWith != null) {
            clientWith.close();
        }
        if (clientWithout != null) {
            clientWithout.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static String observed(ClientMessageService remote) throws Exception {
        return remote.handle(ClientCall.newBuilder().setMessageId(1).build()).get(10, TimeUnit.SECONDS).getBody().toStringUtf8();
    }

    @Test
    void 调用方开了超时倒计时_提供方读到调用超时减传播余量以内的截止() throws Exception {
        String observed = observed(withCountdown);

        assertThat(observed).isNotEqualTo("absent");
        assertThat(Long.parseLong(observed)).isBetween(1L, CALL_TIMEOUT_MS - Deadline.CALLER_MARGIN_MILLIS);
    }

    @Test
    void 调用方没开超时倒计时_为空_也读不到同一线程上一次调用留下的倒计时() throws Exception {
        // 先发一次带倒计时的，再发不带的：提供方线程复用时不得读到上一次的倒计时
        observed(withCountdown);
        assertThat(observed(withoutCountdown)).isEqualTo("absent");
    }

    @Test
    void 不在Dubbo调用里_为空() {
        assertThat(CallerDeadline.fromRpcContext()).isEmpty();
    }

    @Test
    void 倒计时已用完_截止一开始就到期() {
        try {
            RpcContext.getServerAttachment().setObjectAttachment(CommonConstants.TIME_COUNTDOWN_KEY,
                    TimeoutCountDown.newCountDown(Deadline.CALLER_MARGIN_MILLIS, TimeUnit.MILLISECONDS));
            Optional<Deadline> caller = CallerDeadline.fromRpcContext();
            assertThat(caller).isPresent();
            assertThat(caller.get().expired()).as("剩余不超过传播余量").isTrue();
        } finally {
            RpcContext.removeServerAttachment();
        }
    }
}
