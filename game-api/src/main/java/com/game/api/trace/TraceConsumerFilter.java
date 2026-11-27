package com.game.api.trace;

import com.game.api.ClientMessageService;
import com.game.api.proto.ClientCall;
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
 * Dubbo 消费端链路追踪过滤器：
 * <ul>
 *   <li>从当前线程 {@link TraceContext} 派生 CLIENT 子 span（无当前上下文时新建根），将 W3C {@code traceparent} 与 {@code player_id} 写入 Dubbo 附件；</li>
 *   <li>本地确定未发出或调用异常时同样记录标错的 CLIENT span（对齐主仓 distributed-tracing.md §10.3）；</li>
 *   <li>span 名固定低基数 {@code <SimpleService>/<method>}，不带玩家 ID。</li>
 * </ul>
 */
@Activate(group = CommonConstants.CONSUMER, order = -8000)
public final class TraceConsumerFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String iface = invoker.getInterface().getName();
        if (!iface.startsWith("com.game.")) {
            return invoker.invoke(invocation);
        }
        long playerId = extractPlayerId(invocation);
        TraceContext parent = TraceContext.current();
        TraceContext clientSpan = parent != null ? parent.newChild(playerId) : TraceContext.newRoot(playerId);
        invocation.setAttachment(TraceContext.TRACEPARENT_HEADER, clientSpan.toTraceparent());
        if (clientSpan.playerId() != 0L) {
            invocation.setAttachment(TraceContext.PLAYER_ID_ATTACHMENT, Long.toUnsignedString(clientSpan.playerId()));
        }
        String spanName = invoker.getInterface().getSimpleName() + "/" + invocation.getMethodName();
        long startNano = System.currentTimeMillis() * 1_000_000L;
        try {
            Result result = invoker.invoke(invocation);
            if (result instanceof org.apache.dubbo.rpc.AsyncRpcResult) {
                result.whenCompleteWithContext((res, err) -> recordClientSpan(clientSpan, spanName, startNano, res, err));
            } else if (result != null) {
                recordClientSpan(clientSpan, spanName, startNano, result, null);
            }
            return result;
        } catch (Throwable localFailure) {
            // 本地确定没发出（如无可用节点、熔断、序列化失败）也记录标错的 CLIENT span
            OtlpHttpSpanExporter.recordGlobal(new OtlpHttpSpanExporter.SpanRecord(
                    clientSpan.traceId(), clientSpan.spanId(), clientSpan.parentSpanId(),
                    spanName, OtlpHttpSpanExporter.SpanKind.CLIENT,
                    startNano, System.currentTimeMillis() * 1_000_000L,
                    true, localFailure.getClass().getSimpleName(), Map.of("rpc.system", "dubbo")));
            throw localFailure;
        }
    }

    private static void recordClientSpan(TraceContext clientSpan, String spanName, long startNano,
                                         Result res, Throwable err) {
        boolean isErr = err != null || (res != null && res.hasException());
        String errMsg = err != null ? err.getClass().getSimpleName()
                : (res != null && res.getException() != null ? res.getException().getClass().getSimpleName() : "");
        OtlpHttpSpanExporter.recordGlobal(new OtlpHttpSpanExporter.SpanRecord(
                clientSpan.traceId(), clientSpan.spanId(), clientSpan.parentSpanId(),
                spanName, OtlpHttpSpanExporter.SpanKind.CLIENT,
                startNano, System.currentTimeMillis() * 1_000_000L,
                isErr, errMsg, Map.of("rpc.system", "dubbo")));
    }

    static long extractPlayerId(Invocation invocation) {
        Object[] args = invocation.getArguments();
        if (args != null && args.length > 0 && args[0] instanceof ClientCall call && call.hasSession()) {
            return call.getSession().getPlayerId();
        }
        return 0L;
    }
}
