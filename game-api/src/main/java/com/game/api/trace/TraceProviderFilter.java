package com.game.api.trace;

import com.game.api.proto.ClientCall;
import com.game.api.proto.SessionClosed;
import com.game.common.trace.OtlpHttpSpanExporter;
import com.game.common.trace.TraceContext;
import java.util.Map;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * Dubbo 提供端链路追踪过滤器：
 * <ul>
 *   <li>读取入站 W3C {@code traceparent}（无效或缺失则开根），派生 SERVER 子 span 并注入 SLF4J MDC（{@code trace_id}/{@code span_id}/{@code player_id}）；</li>
 *   <li>span 计时覆盖异步回复发出为止（{@code Result.whenCompleteWithContext}）；</li>
 *   <li>仅服务端故障类异常（非业务错误码）标错导出，且绝不把 {@code trace_id} 写回给客户端。</li>
 * </ul>
 */
@Activate(group = CommonConstants.PROVIDER, order = -8000)
public final class TraceProviderFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String iface = invoker.getInterface().getName();
        if (!iface.startsWith("com.game.")) {
            return invoker.invoke(invocation);
        }
        String incomingTraceparent = invocation.getAttachment(TraceContext.TRACEPARENT_HEADER);
        long playerId = extractPlayerId(invocation);
        TraceContext serverSpan = TraceContext.continueOrRoot(incomingTraceparent, playerId);
        String spanName = invoker.getInterface().getSimpleName() + "/" + invocation.getMethodName();
        long startNano = System.currentTimeMillis() * 1_000_000L;

        try (TraceContext.Scope ignored = serverSpan.attach()) {
            Result result = invoker.invoke(invocation);
            if (result instanceof org.apache.dubbo.rpc.AsyncRpcResult) {
                result.whenCompleteWithContext((res, err) -> recordServerSpan(serverSpan, spanName, startNano, res, err));
            } else if (result != null) {
                recordServerSpan(serverSpan, spanName, startNano, result, null);
            }
            return result;
        } catch (Throwable ex) {
            boolean serverFault = isServerFault(ex);
            OtlpHttpSpanExporter.recordGlobal(new OtlpHttpSpanExporter.SpanRecord(
                    serverSpan.traceId(), serverSpan.spanId(), serverSpan.parentSpanId(),
                    spanName, OtlpHttpSpanExporter.SpanKind.SERVER,
                    startNano, System.currentTimeMillis() * 1_000_000L,
                    serverFault, ex.getClass().getSimpleName(), Map.of("rpc.system", "dubbo")));
            throw ex;
        }
    }

    private static void recordServerSpan(TraceContext serverSpan, String spanName, long startNano,
                                         Result res, Throwable err) {
        Throwable ex = err != null ? err : (res != null ? res.getException() : null);
        boolean serverFault = isServerFault(ex);
        OtlpHttpSpanExporter.recordGlobal(new OtlpHttpSpanExporter.SpanRecord(
                serverSpan.traceId(), serverSpan.spanId(), serverSpan.parentSpanId(),
                spanName, OtlpHttpSpanExporter.SpanKind.SERVER,
                startNano, System.currentTimeMillis() * 1_000_000L,
                serverFault, serverFault && ex != null ? ex.getClass().getSimpleName() : "",
                Map.of("rpc.system", "dubbo")));
    }

    /** 仅服务端故障标错（对齐主仓 gRPC UNKNOWN/DEADLINE_EXCEEDED/UNIMPLEMENTED/INTERNAL/UNAVAILABLE/DATA_LOSS）；鉴权/业务拒收不标错。 */
    static boolean isServerFault(Throwable ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof RpcException rpc) {
            int code = rpc.getCode();
            return code == RpcException.UNKNOWN_EXCEPTION
                    || code == RpcException.NETWORK_EXCEPTION
                    || code == RpcException.TIMEOUT_EXCEPTION
                    || code == RpcException.SERIALIZATION_EXCEPTION
                    || code == RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER;
        }
        return true;
    }

    private static long extractPlayerId(Invocation invocation) {
        Object[] args = invocation.getArguments();
        if (args != null && args.length > 0) {
            if (args[0] instanceof ClientCall call && call.hasSession() && call.getSession().getPlayerId() != 0L) {
                return call.getSession().getPlayerId();
            }
            if (args[0] instanceof SessionClosed closed && closed.hasSession() && closed.getSession().getPlayerId() != 0L) {
                return closed.getSession().getPlayerId();
            }
        }
        String raw = invocation.getAttachment(TraceContext.PLAYER_ID_ATTACHMENT);
        if (raw != null && !raw.isBlank()) {
            try {
                return Long.parseUnsignedLong(raw.strip());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }
}
