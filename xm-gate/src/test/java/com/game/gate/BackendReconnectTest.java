package com.game.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.utils.UrlUtils;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * gate 对后端的直连引用在「后端不在 / 晚于 gate 起来 / 中途重启」时的行为（批次 6.4 接 xm-match 时用真 Triple 测出来并钉住）。
 *
 * <p>结论（Dubbo 3.3.6 的 Triple 客户端）：
 * <ol>
 *   <li>后端不在时引用照样建得出来（{@code check = false}），调用<b>立刻</b>失败（不挂满 5 s 超时），dispatcher 把它翻成带请求号的信封 1003；</li>
 *   <li>建引用时的首次建连没连上，下一次重连排在 60 s 之后（{@code max(dubbo.application.least-reconnect-duration = 60 s, 心跳超时 / 3)}；
 *       机制见 xm-api {@code SceneAssetOpClients#RECONNECT_INTERVAL} 的说明），所以后端起来之后引用<b>不会马上</b>恢复——晚于 gate 启动的后端
 *       要等到 gate 的下一次重连（本机实测 57–60 s）才通；</li>
 *   <li>重连是自动的：到点之后同一个引用自己连上，不用重启 gate。</li>
 * </ol>
 *
 * <p>由此定下两件事。其一，本机切片（{@code tools/local/start-slice.sh}）里<b>后端必须先于 gate 启动</b>：xm-match 排在 xm-team 与 xm-gate 之前，
 * 而不是规格草案写的「xm-battle 之后」（那样它晚于 gate，切片报「全部就绪」后的头一分钟里 MatchService 的 10 个号一律 1003）。
 * 其二，运行中重启任何一个后端（例如 xm-match 的发号租约丢失后必须重启它，match-spec §9.8）都不用重启 gate，但后端回来之后最长还要等约一个
 * 重连周期，这段时间它的消息仍是信封 1003。按节点直连的编程式客户端（{@code NodeRpcClients}）早已把这个间隔压到 1 s；gate 这几个静态直连的
 * 引用（{@link GateConfiguration}）用的还是 Dubbo 缺省的 60 s——要不要一并压小是运维取舍，本类只钉现状。
 *
 * <p>测法：调用方与提供方各在自己的 Dubbo 框架模型里（等价于两个进程），调用方 / 提供方鉴权过滤器照常生效（测试密钥由 surefire 注入
 * {@code XM_DUBBO_SECRET}）。引用按 {@link GateConfiguration#matchClientMessageService()} 上的注解建——group、{@code check}、方法级重试都取自
 * 那个注解，直连地址换成本测试的随机端口，超时取 gate 的 {@code dubbo.consumer.timeout}（5 s）。第 3 条要等到重连那一刻：生产参数下是 60 s，
 * 用例里只把重连间隔调到 1 s（见 {@link #FAST_RECONNECT}，与 {@code NodeRpcClients} 用的是同两个 URL 参数），走的仍是同一段重连代码。
 */
class BackendReconnectTest {

    /** gate 调后端的超时（application.yaml 的 dubbo.consumer.timeout；{@code GateConfigurationTest} 钉住这个值）。 */
    private static final int CALL_TIMEOUT_MS = 5_000;
    /** 每次等调用结论的上限：比调用超时再多一截，只为不让用例挂住（任何 future 都不无限等）。 */
    private static final long VERDICT_WAIT_MS = CALL_TIMEOUT_MS + 5_000L;
    /** 「不在的后端要立刻失败」的上限：实测 1–40 ms，放宽到 2 s 仍远小于调用超时。 */
    private static final long FAIL_FAST_MS = 2_000;
    /** 后端起来之后观察多久「仍然连不上」：生产参数下重连间隔是 60 s，这里看 5 s（1 s 一次的重连早就该连上了）。 */
    private static final Duration STILL_DOWN_WINDOW = Duration.ofSeconds(5);
    /** 重连间隔调到 1 s 之后等引用恢复的上限（实测 2–4 s；Windows 上连一个关着的本机端口自己就要约 2 s 才报拒绝）。 */
    private static final Duration RECOVERY_LIMIT = Duration.ofSeconds(30);
    /**
     * 把重连间隔从 60 s 调到 1 s 的两个 URL 参数：间隔 = max(least-reconnect-duration, 心跳超时 / 3)，心跳超时缺省 = 3 × 心跳，
     * 所以两个一起给（同 {@code NodeRpcClients} / {@code SceneAssetOpClients} 的做法）。只用于等「自己连上」那一刻，gate 的生产引用不设它们。
     */
    private static final Map<String, String> FAST_RECONNECT = Map.of(
            Constants.LEAST_RECONNECT_DURATION_KEY, "1000",
            Constants.HEARTBEAT_KEY, "1000");
    private static final int MATCH_JOIN_QUEUE = 157;

    private IsolatedDubboModule client;
    private IsolatedDubboModule server;

    /** 假的 xm-match：把自己的标记、消息号、会话里的玩家号与请求体带回，并数被调了几次。 */
    private static final class EchoBackend implements ClientMessageService {

        final AtomicInteger handled = new AtomicInteger();
        private final String tag;

        EchoBackend(String tag) {
            this.tag = tag;
        }

        @Override
        public CompletableFuture<ClientReply> handle(ClientCall call) {
            handled.incrementAndGet();
            return CompletableFuture.completedFuture(ClientReply.newBuilder()
                    .setBody(ByteString.copyFromUtf8(tag + ":" + call.getMessageId() + ":" + call.getSession().getPlayerId() + ":"
                            + call.getBody().toStringUtf8()))
                    .build());
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

    @AfterEach
    void closeModules() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void 后端先于gate启动_引用建好就能用_带着会话身份与消息号过线() throws Exception {
        int port = freePort();
        EchoBackend backend = new EchoBackend("up");
        server = export(port, backend);

        ClientMessageService match = referTo(port, Map.of());

        ClientReply reply = invoke(match, "join").get(VERDICT_WAIT_MS, TimeUnit.MILLISECONDS);
        assertThat(reply.getBody().toStringUtf8()).isEqualTo("up:157:42:join");
        assertThat(reply.getTipId()).isZero();
        assertThat(backend.handled.get()).isEqualTo(1);
    }

    @Test
    void 后端不在时引用照样建得出来_调用立刻失败_后端起来之后也不会马上恢复_所以切片里后端必须先于gate启动() throws Exception {
        int port = freePort();

        // gate 先起、后端端口上没有人：check = false 的引用照样建得出来（referTo 里断言）
        ClientMessageService match = referTo(port, Map.of());

        // 第一次调用：future 立刻以「上游不可用」失败，不是挂满 5 s 的调用超时——否则同一会话排在后面的 match 请求都要跟着等
        // （同一后端的请求按会话串行，ClientDispatcher.drainBackend）
        long startedNanos = System.nanoTime();
        Throwable first = failureOf(invoke(match, "before-1"));
        assertThat(elapsedMs(startedNanos)).as("后端不在时第一次调用失败的用时（ms）").isLessThan(FAIL_FAST_MS);
        assertThat(first).isInstanceOf(RpcException.class).hasMessageContaining("is unavailable");
        // 之后 Dubbo 把这个后端记为不可用：调用在代理里当场抛「No provider available」，不产生 future。
        // dispatcher 对这两种形态一视同仁（callBackend 把当场抛出的异常折成失败的 future）→ 都是带请求号的信封 1003
        startedNanos = System.nanoTime();
        Throwable second = failureOf(invoke(match, "before-2"));
        assertThat(elapsedMs(startedNanos)).as("后端不在时第二次调用失败的用时（ms）").isLessThan(FAIL_FAST_MS);
        assertThat(second).isInstanceOf(RpcException.class).hasMessageContaining("No provider available");

        // 后端起来（同一个地址）。生产参数下 Dubbo 每 60 s 才重连一次，所以接下来这几秒里引用仍然不可用
        assertThat(Constants.LEAST_RECONNECT_DURATION).as("Dubbo 的最小重连间隔（ms）").isEqualTo(60_000L);
        assertThat(UrlUtils.getIdleTimeout(URL.valueOf("tri://127.0.0.1:" + port)) / Constants.HEARTBEAT_CHECK_TICK)
                .as("不带参数的直连地址：缺省心跳 60 s → 心跳超时 180 s → 除以 3 是 60 s；重连间隔 = max(60 s, 60 s)").isEqualTo(60_000);
        EchoBackend backend = new EchoBackend("late");
        server = export(port, backend);
        long deadline = System.nanoTime() + STILL_DOWN_WINDOW.toNanos();
        int attempts = 0;
        while (System.nanoTime() < deadline) {
            attempts++;
            CompletableFuture<ClientReply> call = invoke(match, "after-start-" + attempts);
            Throwable stillDown;
            try {
                ClientReply reply = call.get(VERDICT_WAIT_MS, TimeUnit.MILLISECONDS);
                throw new AssertionError("后端起来不到 " + STILL_DOWN_WINDOW.toSeconds() + " s 引用就自己连上了（第 " + attempts + " 次调用，应答 "
                        + reply.getBody().toStringUtf8() + "）：Dubbo 的重连间隔不再是 60 s。start-slice.sh 里「后端先于 gate 启动」的"
                        + "次序约束与本类、GateConfiguration 的注释可以按新的间隔重写");
            } catch (ExecutionException e) {
                stillDown = e.getCause();
            }
            assertThat(stillDown).as("第 %d 次调用", attempts).isInstanceOf(RpcException.class);
            Thread.sleep(250);
        }
        assertThat(attempts).as("观察窗口里确实试了很多次").isGreaterThan(10);
        assertThat(backend.handled.get()).as("这段时间没有一次调用到过后端").isZero();
    }

    @Test
    void 重连是自动的_晚起来的后端与重启过的后端_同一个引用到点都自己连上_不用重启gate() throws Exception {
        int port = freePort();

        // 1. gate 先起、后端不在（重连间隔调到 1 s，其余与生产相同）
        ClientMessageService match = referTo(port, FAST_RECONNECT);
        assertThat(failureOf(invoke(match, "before"))).isInstanceOf(RpcException.class);

        // 2. 后端晚起来：不重建引用，轮询到调用成功为止
        EchoBackend first = new EchoBackend("first");
        server = export(port, first);
        ClientReply reply = awaitReply(match, "after-start");
        assertThat(reply.getBody().toStringUtf8()).isEqualTo("first:157:42:after-start");
        assertThat(first.handled.get()).as("连上之前失败的那些调用没有到过后端（handle 不重试，也没有被悄悄补发）").isEqualTo(1);

        // 3. 后端重启（先停、再在同一个端口起一个新的）：停着的时候调用失败，起来之后同一个引用再次连上
        server.close();
        server = null;
        assertThat(failureOf(invoke(match, "while-down"))).as("后端停着时调用失败").isInstanceOf(RpcException.class);

        EchoBackend second = new EchoBackend("second");
        server = export(port, second);
        ClientReply afterRestart = awaitReply(match, "after-restart");
        assertThat(afterRestart.getBody().toStringUtf8()).as("应答来自重启后的那个后端").isEqualTo("second:157:42:after-restart");
        assertThat(second.handled.get()).isEqualTo(1);
        assertThat(first.handled.get()).as("旧后端没有再收到调用").isEqualTo(1);
    }

    // ================================================================ 工具

    /**
     * 按 GateConfiguration 里 match 引用的注解建一个直连引用（地址换成本测试的端口）。
     *
     * @param urlParameters 附加的 URL 参数；生产没有（空表），只有等「自己连上」的用例给 {@link #FAST_RECONNECT}
     */
    private ClientMessageService referTo(int port, Map<String, String> urlParameters) throws NoSuchMethodException {
        DubboReference annotation = GateConfiguration.class.getDeclaredMethod("matchClientMessageService")
                .getAnnotation(DubboReference.class);
        assertThat(annotation.group()).isEqualTo(DubboGroups.MATCH);
        assertThat(annotation.url()).as("直连地址来自配置项，这里换成随机端口").isEqualTo("${xm.dubbo.match-url:}");
        assertThat(annotation.parameters()).as("生产引用不带额外的 URL 参数（重连间隔用 Dubbo 缺省的 60 s）").isEmpty();

        client = IsolatedDubboModule.create("xm-gate-test-backend-caller");
        ReferenceConfig<ClientMessageService> reference = new ReferenceConfig<>(client.module());
        reference.setInterface(ClientMessageService.class);
        reference.setGroup(annotation.group());
        reference.setCheck(annotation.check());
        reference.setUrl("tri://127.0.0.1:" + port);
        reference.setTimeout(CALL_TIMEOUT_MS);
        reference.setMethods(Arrays.stream(annotation.methods()).map(m -> {
            MethodConfig method = new MethodConfig();
            method.setName(m.name());
            method.setRetries(m.retries());
            return method;
        }).toList());
        if (!urlParameters.isEmpty()) {
            reference.setParameters(new HashMap<>(urlParameters)); // 可变 Map：Dubbo 刷新配置时可能往里写
        }
        ClientMessageService proxy = reference.get();
        assertThat(proxy).as("后端在不在引用都建得出来（check = false）").isNotNull();
        return proxy;
    }

    private static IsolatedDubboModule export(int port, ClientMessageService backend) {
        IsolatedDubboModule module = IsolatedDubboModule.create("xm-gate-test-backend-provider");
        try {
            ProtocolConfig protocol = new ProtocolConfig("tri", port);
            protocol.setHost("127.0.0.1");
            ServiceConfig<ClientMessageService> service = new ServiceConfig<>(module.module());
            service.setInterface(ClientMessageService.class);
            service.setRef(backend);
            service.setGroup(DubboGroups.MATCH);
            service.setRegister(false);
            service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
            service.setProtocol(protocol);
            service.export();
            return module;
        } catch (RuntimeException e) {
            module.close();
            throw e;
        }
    }

    private static ClientCall call(String body) {
        return ClientCall.newBuilder()
                .setSession(SessionContext.newBuilder().setGateNodeId(3).setSessionId(7).setZoneId(1).setPlayerId(42))
                .setMessageId(MATCH_JOIN_QUEUE)
                .setRequestId(1)
                .setBody(ByteString.copyFromUtf8(body))
                .build();
    }

    /**
     * 发一次调用，写法同 {@code ClientDispatcher.callBackend}：代理当场抛出的异常也折成失败的 future（后端被 Dubbo 记为不可用之后，
     * 调用在代理里就抛 {@code RpcException}「No provider available」，不产生 future）。
     */
    private static CompletableFuture<ClientReply> invoke(ClientMessageService match, String body) {
        try {
            return match.handle(call(body));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 等一次调用失败并返回原因；成功、或到了 {@link #VERDICT_WAIT_MS} 还没有结论都算用例失败。 */
    private static Throwable failureOf(CompletableFuture<ClientReply> future) throws InterruptedException {
        try {
            ClientReply reply = future.get(VERDICT_WAIT_MS, TimeUnit.MILLISECONDS);
            throw new AssertionError("后端不在，调用却成功了: " + reply);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("后端不在时调用既没成功也没失败，超过 " + VERDICT_WAIT_MS + " ms 仍无结论", e);
        }
    }

    /** 轮询到调用成功为止（每次调用都有界），超过 {@link #RECOVERY_LIMIT} 失败并带上最后一次的原因。 */
    private static ClientReply awaitReply(ClientMessageService match, String body) throws InterruptedException {
        long deadline = System.nanoTime() + RECOVERY_LIMIT.toNanos();
        Throwable last = null;
        int attempts = 0;
        while (System.nanoTime() < deadline) {
            attempts++;
            try {
                return invoke(match, body).get(VERDICT_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                last = e.getCause();
            } catch (TimeoutException e) {
                last = e;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("后端起来 " + RECOVERY_LIMIT + " 后引用仍然不可用（试了 " + attempts + " 次）", last);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
