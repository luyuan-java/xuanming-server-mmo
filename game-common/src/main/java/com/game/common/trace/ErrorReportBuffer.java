package com.game.common.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界错误汇总缓冲（对齐主仓 {@code docs/design/error-reporting.md}）：
 * 每条事件自动附带当前线程 {@link TraceContext} 的 {@code trace_id} / {@code span_id} / {@code player_id}，
 * 暴露 {@code error_reporter_events_total}、{@code error_reporter_dropped_events_total}、{@code error_reporter_buffered_events} 三项计数。
 */
public final class ErrorReportBuffer {

    public record ErrorEvent(
            long timestampMs,
            String category,
            String message,
            String traceId,
            String spanId,
            long playerId) {
    }

    private final ArrayBlockingQueue<ErrorEvent> queue;
    private final AtomicLong totalEvents = new AtomicLong();
    private final AtomicLong droppedEvents = new AtomicLong();

    public ErrorReportBuffer(int capacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    public boolean record(String category, String message) {
        TraceContext ctx = TraceContext.current();
        return record(System.currentTimeMillis(), category, message,
                ctx == null ? "" : ctx.traceId(),
                ctx == null ? "" : ctx.spanId(),
                ctx == null ? 0L : ctx.playerId());
    }

    public boolean record(long timestampMs, String category, String message,
                          String traceId, String spanId, long playerId) {
        totalEvents.incrementAndGet();
        ErrorEvent event = new ErrorEvent(
                timestampMs,
                category == null ? "" : category,
                message == null ? "" : message,
                traceId == null ? "" : traceId,
                spanId == null ? "" : spanId,
                playerId);
        if (!queue.offer(event)) {
            droppedEvents.incrementAndGet();
            return false;
        }
        return true;
    }

    public List<ErrorEvent> drain(int max) {
        List<ErrorEvent> out = new ArrayList<>(Math.min(max, queue.size()));
        queue.drainTo(out, max);
        return out;
    }

    public long totalEvents() {
        return totalEvents.get();
    }

    public long droppedEvents() {
        return droppedEvents.get();
    }

    public int bufferedEvents() {
        return queue.size();
    }
}
