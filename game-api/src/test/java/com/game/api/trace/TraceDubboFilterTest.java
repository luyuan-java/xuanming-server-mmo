package com.game.api.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.ClientMessageService;
import com.game.api.proto.ClientCall;
import com.game.api.proto.SessionContext;
import com.game.common.trace.OtlpHttpSpanExporter;
import com.game.common.trace.TraceContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** 覆盖 2026-11-19 Dubbo traceparent 端到端传播、MDC 三字段、本地未发出标错与 OTLP 导出。 */
class TraceDubboFilterTest {

    @Test
    void Dubbo消费端与提供端自动传播traceparent与playerId_导出CLIENT与SERVER_span_本地失败亦记录标错span() {
        List<String> exportedJson = new ArrayList<>();
        try (OtlpHttpSpanExporter exporter = new OtlpHttpSpanExporter(
                "game-test", URI.create("http://127.0.0.1:4318/v1/traces"),
                64, 64, 60_000L, Duration.ofSeconds(5),
                (u, body, t) -> {
                    exportedJson.add(new String(body, StandardCharsets.UTF_8));
                    return true;
                })) {
            OtlpHttpSpanExporter.setGlobal(exporter);
            TraceConsumerFilter consumer = new TraceConsumerFilter();
            TraceProviderFilter provider = new TraceProviderFilter();

            AtomicReference<String> serverMdcTrace = new AtomicReference<>();
            AtomicReference<String> serverMdcPlayer = new AtomicReference<>();

            Invoker<ClientMessageService> biz = stubInvoker(ClientMessageService.class, inv -> {
                serverMdcTrace.set(MDC.get(TraceContext.MDC_TRACE_ID));
                serverMdcPlayer.set(MDC.get(TraceContext.MDC_PLAYER_ID));
                return new AppResponse("done");
            });
            Invoker<ClientMessageService> wire = stubInvoker(ClientMessageService.class,
                    inv -> provider.invoke(biz, inv));

            TraceContext root = TraceContext.newRoot(55001L);
            ClientCall call = ClientCall.newBuilder()
                    .setSession(SessionContext.newBuilder().setPlayerId(55001L).build())
                    .setMessageId(200)
                    .build();
            RpcInvocation invocation = new RpcInvocation("handle", ClientMessageService.class.getName(), "",
                    new Class<?>[]{ClientCall.class}, new Object[]{call});

            try (TraceContext.Scope ignored = root.attach()) {
                assertThat(consumer.invoke(wire, invocation).getValue()).isEqualTo("done");
            }
            assertThat(serverMdcTrace.get()).isEqualTo(root.traceId());
            assertThat(serverMdcPlayer.get()).isEqualTo("55001");
            assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();

            // 本地确定未发出（消费端抛出 RpcException）也要记标错 CLIENT span
            Invoker<ClientMessageService> localFail = stubInvoker(ClientMessageService.class, inv -> {
                throw new RpcException(RpcException.NETWORK_EXCEPTION, "no provider");
            });
            try (TraceContext.Scope ignored = root.attach()) {
                assertThatThrownBy(() -> consumer.invoke(localFail, new RpcInvocation("handle",
                        ClientMessageService.class.getName(), "", new Class<?>[]{ClientCall.class}, new Object[]{call})))
                        .isInstanceOf(RpcException.class);
            }

            assertThat(exporter.flushOnce()).isGreaterThanOrEqualTo(3);
            assertThat(exportedJson.getFirst())
                    .contains(root.traceId())
                    .contains("ClientMessageService/handle")
                    .contains("\"code\":2"); // 本地失败的 error span
        } finally {
            OtlpHttpSpanExporter.setGlobal(null);
        }
    }

    private static <T> Invoker<T> stubInvoker(Class<T> iface, java.util.function.Function<Invocation, Result> handler) {
        return new Invoker<>() {
            @Override public Class<T> getInterface() { return iface; }
            @Override public Result invoke(Invocation invocation) { return handler.apply(invocation); }
            @Override public org.apache.dubbo.common.URL getUrl() { return null; }
            @Override public boolean isAvailable() { return true; }
            @Override public void destroy() {}
        };
    }
}
