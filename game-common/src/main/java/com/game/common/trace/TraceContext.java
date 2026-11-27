package com.game.common.trace;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * 跨语言统一 W3C {@code traceparent} 上下文与 SLF4J MDC 传播（对齐主仓 {@code docs/design/distributed-tracing.md}）：
 * <ul>
 *   <li>线协议严格遵循 W3C Trace Context：{@code 00-<32位小写hex trace_id>-<16位小写hex span_id>-<2位hex flags>}，全零 ID 一律判无效；</li>
 *   <li>SLF4J MDC 固定键名 {@code trace_id} / {@code span_id} / {@code player_id}（十进制无符号串），仅非空/非零时写入；</li>
 *   <li>提供 {@link #attach()} 作用域句柄与 {@link #wrap(Runnable)} / {@link #wrap(Supplier)} 异步续接，不把追踪泄漏给周期定时任务。</li>
 * </ul>
 */
public final class TraceContext {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String PLAYER_ID_ATTACHMENT = "x-trace-player-id";

    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SPAN_ID = "span_id";
    public static final String MDC_PLAYER_ID = "player_id";

    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_SPAN_ID = "0000000000000000";
    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final byte traceFlags;
    private final long playerId;

    private TraceContext(String traceId, String spanId, String parentSpanId, byte traceFlags, long playerId) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId == null ? "" : parentSpanId;
        this.traceFlags = traceFlags;
        this.playerId = playerId;
    }

    /** 开启新的根追踪（gate 入站消息/建连/断线、公网 HTTP 网关入口，绝不采信客户端外部头）。 */
    public static TraceContext newRoot(long playerId) {
        return new TraceContext(randomTraceId(), randomSpanId(), "", (byte) 0x01, playerId);
    }

    public static TraceContext newRoot() {
        return newRoot(0L);
    }

    /** 基于父追踪上下文派生子 span（用于出站 RPC/HTTP/Kafka 或入站服务端 span）。 */
    public TraceContext newChild(long overridePlayerId) {
        long effectivePlayer = overridePlayerId != 0L ? overridePlayerId : this.playerId;
        return new TraceContext(this.traceId, randomSpanId(), this.spanId, this.traceFlags, effectivePlayer);
    }

    public TraceContext newChild() {
        return newChild(this.playerId);
    }

    public TraceContext withPlayerId(long newPlayerId) {
        if (newPlayerId == 0L || newPlayerId == this.playerId) {
            return this;
        }
        return new TraceContext(this.traceId, this.spanId, this.parentSpanId, this.traceFlags, newPlayerId);
    }

    /** 解析 W3C traceparent（严格小写 55 字节，拒绝全零 trace_id/span_id 或未知版本 ff），非法返回 null。 */
    public static TraceContext parseTraceparent(String header, long playerId) {
        if (header == null) {
            return null;
        }
        String s = header.strip();
        if (s.length() != 55 || s.charAt(2) != '-' || s.charAt(35) != '-' || s.charAt(52) != '-') {
            return null;
        }
        String version = s.substring(0, 2);
        String tid = s.substring(3, 35);
        String sid = s.substring(36, 52);
        String flags = s.substring(53, 55);
        if (!isLowerHex(version) || "ff".equals(version)
                || !isLowerHex(tid) || ZERO_TRACE_ID.equals(tid)
                || !isLowerHex(sid) || ZERO_SPAN_ID.equals(sid)
                || !isLowerHex(flags)) {
            return null;
        }
        byte flagByte = (byte) Integer.parseInt(flags, 16);
        return new TraceContext(tid, sid, "", flagByte, playerId);
    }

    /** 入站接续：解析有效 traceparent 并派生本服务子 span；无效或缺失时新建根追踪。 */
    public static TraceContext continueOrRoot(String traceparent, long playerId) {
        TraceContext parent = parseTraceparent(traceparent, playerId);
        return parent != null ? parent.newChild(playerId) : newRoot(playerId);
    }

    /** 按 W3C 格式输出 {@code 00-<trace_id>-<span_id>-<flags>}。 */
    public String toTraceparent() {
        return "00-" + traceId + "-" + spanId + "-" + HEX.toHexDigits(traceFlags);
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public byte traceFlags() {
        return traceFlags;
    }

    public boolean sampled() {
        return (traceFlags & 0x01) != 0;
    }

    public long playerId() {
        return playerId;
    }

    public static TraceContext current() {
        return CURRENT.get();
    }

    /** 将本上下文绑定到当前线程与 SLF4J MDC，关闭作用域时精确还原前值。 */
    public Scope attach() {
        TraceContext prev = CURRENT.get();
        String prevTrace = MDC.get(MDC_TRACE_ID);
        String prevSpan = MDC.get(MDC_SPAN_ID);
        String prevPlayer = MDC.get(MDC_PLAYER_ID);
        CURRENT.set(this);
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_SPAN_ID, spanId);
        if (playerId != 0L) {
            MDC.put(MDC_PLAYER_ID, Long.toUnsignedString(playerId));
        } else {
            MDC.remove(MDC_PLAYER_ID);
        }
        return () -> {
            if (prev == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prev);
            }
            restoreMdc(MDC_TRACE_ID, prevTrace);
            restoreMdc(MDC_SPAN_ID, prevSpan);
            restoreMdc(MDC_PLAYER_ID, prevPlayer);
        };
    }

    /** 异步回调续接：捕获调用时刻的 TraceContext，在异步线程执行前安装、执行后还原。 */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        TraceContext captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            try (Scope ignored = captured.attach()) {
                task.run();
            }
        };
    }

    public static <T> Supplier<T> wrap(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        TraceContext captured = CURRENT.get();
        if (captured == null) {
            return supplier;
        }
        return () -> {
            try (Scope ignored = captured.attach()) {
                return supplier.get();
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> callable) {
        Objects.requireNonNull(callable, "callable");
        TraceContext captured = CURRENT.get();
        if (captured == null) {
            return callable;
        }
        return () -> {
            try (Scope ignored = captured.attach()) {
                return callable.call();
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static void restoreMdc(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }

    private static String randomTraceId() {
        byte[] bytes = new byte[16];
        String hex;
        do {
            RNG.nextBytes(bytes);
            hex = HEX.formatHex(bytes);
        } while (ZERO_TRACE_ID.equals(hex));
        return hex;
    }

    private static String randomSpanId() {
        byte[] bytes = new byte[8];
        String hex;
        do {
            RNG.nextBytes(bytes);
            hex = HEX.formatHex(bytes);
        } while (ZERO_SPAN_ID.equals(hex));
        return hex;
    }

    private static boolean isLowerHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return !s.isEmpty();
    }
}
