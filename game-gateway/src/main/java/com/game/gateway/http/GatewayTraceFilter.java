package com.game.gateway.http;

import com.game.common.trace.OtlpHttpSpanExporter;
import com.game.common.trace.TraceContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * 公网 HTTP 入口追踪过滤器（对齐主仓 {@code docs/design/distributed-tracing.md}）：
 * <ul>
 *   <li>公网入口（{@code /api/**} 等）绝不采信外部客户端传入的 {@code traceparent}，一律开新根；</li>
 *   <li>内部管理入口（{@code /admin/**}）可接续内网调用方的 {@code traceparent}；</li>
 *   <li>严禁向客户端响应头回显 {@code trace_id} / {@code traceparent}。</li>
 * </ul>
 */
public final class GatewayTraceFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String uri = req.getRequestURI() == null ? "" : req.getRequestURI();
        boolean internalAdmin = uri.startsWith("/admin/");
        TraceContext ctx = internalAdmin
                ? TraceContext.continueOrRoot(req.getHeader(TraceContext.TRACEPARENT_HEADER), 0L)
                : TraceContext.newRoot(0L);
        String spanName = "gateway/" + (req.getMethod() == null ? "HTTP" : req.getMethod()) + " " + normalizePath(uri);
        long startNano = System.currentTimeMillis() * 1_000_000L;
        boolean fault = false;
        String errMsg = "";
        try (TraceContext.Scope ignored = ctx.attach()) {
            chain.doFilter(request, response);
            if (resp.getStatus() >= 500) {
                fault = true;
                errMsg = "HTTP_" + resp.getStatus();
            }
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            fault = true;
            errMsg = ex.getClass().getSimpleName();
            throw ex;
        } finally {
            OtlpHttpSpanExporter.recordGlobal(new OtlpHttpSpanExporter.SpanRecord(
                    ctx.traceId(), ctx.spanId(), ctx.parentSpanId(),
                    spanName, OtlpHttpSpanExporter.SpanKind.SERVER,
                    startNano, System.currentTimeMillis() * 1_000_000L,
                    fault, errMsg, Map.of("http.method", req.getMethod() == null ? "" : req.getMethod())));
        }
    }

    /** 截断路径中可能出现的高基数数字段，保持 span 名低基数。 */
    private static String normalizePath(String uri) {
        if (uri.isEmpty()) {
            return "/";
        }
        return uri.replaceAll("/[0-9]+", "/{id}");
    }
}
