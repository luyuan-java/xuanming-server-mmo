package com.game.gate.session;

import com.game.proto.ClientRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 每会话发往 scene 的保序队列与换节点暂存重投状态机（对齐 {@code docs/design/client-scene-request-handoff-hold.md} 定案及 S13 旧场景消息闸）：
 * <ul>
 *   <li><b>请求-响应单飞</b>：同一时刻至多 1 条请求在途，后续请求与单向消息按到达顺序排队（上限 {@value #MAX_DEPTH}）；</li>
 *   <li><b>旧场景消息闸（S13）</b>：每条消息入队时记录收包时刻的场景路由 {@link RouteKey}；发出或重投时若当前路由已变，
 *       置 {@code receivedOnPreviousRoute = true} 交由 scene 拦截白名单外的场景绑定消息；</li>
 *   <li><b>稍后重投</b>：scene 回 {@code retry_later}（确定未执行）时放回队头，间隔不小于 {@value #RETRY_INTERVAL_MS} ms 重投，
 *       自收包起超过 {@value #MAX_WAIT_MS} ms（或已推 124 重定向）回信封 1003；超时/断连等结果未知不重投；</li>
 *   <li><b>单向消息</b>：前面没有未发请求即直发；有则跟在后面、待前面发出即刻跟发（不等回包）；队头进入重投等待时，
 *       排在后面的单向消息不等它并立刻按各自收包路由发出；超 2s 或队列满直接丢弃；</li>
 *   <li><b>在途超时兜底</b>：单条在途超过 {@value #INFLIGHT_WATCHDOG_MS} ms（12s 调用上限 + 1s ≤ 15s 客户端超时）主动判丢回 1003；</li>
 *   <li><b>重定向（124）收口</b>：尚未发出的排队请求逐条回 1003；在途那条照常下发回包，若之后回 {@code retry_later} 当场回 1003。</li>
 * </ul>
 * 仅在所属会话的 Netty EventLoop 线程上读写。
 */
public final class SceneRequestLane {

    public static final int MAX_DEPTH = 32;
    public static final long RETRY_INTERVAL_MS = 200L;
    public static final long MAX_WAIT_MS = 2_000L;
    /** 预算不变式：12000ms 调用上限 + 1000ms 兜底余量 + 2000ms 等待上限 <= 15000ms 客户端超时。 */
    public static final long SCENE_CALL_TIMEOUT_MS = 12_000L;
    public static final long INFLIGHT_WATCHDOG_MS = SCENE_CALL_TIMEOUT_MS + 1_000L;

    /** 收包/发送时刻的场景路由身份（scene 节点 + 链路代次 + 可选场景实例号）。 */
    public record RouteKey(int sceneNodeId, long linkGen, long sceneId) {
        public static final RouteKey NONE = new RouteKey(0, 0L, 0L);

        public boolean isSameRoute(RouteKey other) {
            return other != null
                    && this.sceneNodeId == other.sceneNodeId
                    && this.linkGen == other.linkGen
                    && this.sceneId == other.sceneId;
        }
    }

    /** 发出一条场景帧的回调（由 ClientDispatcher 实现）。 */
    public interface Sender {
        /** @return true 已写入链路；false 确定未发出（无路由或链路不可用） */
        boolean sendToScene(ClientRequest request, boolean receivedOnPreviousRoute);

        /** 回带请求 id 的信封错误（1003 / 1008）。 */
        void failRequest(ClientRequest request, int tipId);

        /** 返回当前会话的场景路由。 */
        RouteKey currentRoute();
    }

    private static final class Entry {
        final ClientRequest request;
        final boolean oneWay;
        final RouteKey receiptRoute;
        final long receivedAtMs;

        Entry(ClientRequest request, boolean oneWay, RouteKey receiptRoute, long receivedAtMs) {
            this.request = request;
            this.oneWay = oneWay;
            this.receiptRoute = receiptRoute;
            this.receivedAtMs = receivedAtMs;
        }
    }

    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private Entry inFlight;
    private long inFlightSentAtMs;
    /** 队头请求正在等待重投的解锁时刻（0 = 无重投等待）。 */
    private long retryNotBeforeMs;
    private boolean redirected;

    /** 是否仍有在途或排队消息（gate 排空迁移遇到非空时本拍跳过）。 */
    public boolean hasPendingWork() {
        return inFlight != null || !queue.isEmpty();
    }

    public int depth() {
        return queue.size() + (inFlight != null ? 1 : 0);
    }

    public boolean isHoldingForRetry() {
        return retryNotBeforeMs > 0L && inFlight == null && !queue.isEmpty() && !queue.peekFirst().oneWay;
    }

    public long retryNotBeforeMs() {
        return retryNotBeforeMs;
    }

    /**
     * 入队一条发往 scene 的消息并尽量推进发出。
     *
     * @param oneWay true = 单向无应答（如移动上报）；false = 请求-响应
     */
    public void submit(ClientRequest request, boolean oneWay, long nowMs, Sender sender) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sender, "sender");
        RouteKey receipt = Objects.requireNonNullElse(sender.currentRoute(), RouteKey.NONE);
        if (redirected) {
            if (!oneWay) {
                sender.failRequest(request, ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
            }
            return;
        }
        if (oneWay) {
            // 定案 §2：前面没有还没发出的请求就直发；有则排在后面（队头等重投时不挡单向）
            if (!hasUnsentRequestReadyToSend() && !isHoldingForRetry()) {
                dispatchOneWay(new Entry(request, true, receipt, nowMs), nowMs, sender);
                return;
            }
            if (isHoldingForRetry()) {
                // 队头在等重投：单向消息不等它，立即按自身收包路由发出
                dispatchOneWay(new Entry(request, true, receipt, nowMs), nowMs, sender);
                return;
            }
            if (depth() >= MAX_DEPTH) {
                return; // 单向队列满直接丢弃
            }
            queue.addLast(new Entry(request, true, receipt, nowMs));
            return;
        }
        // 请求-响应
        if (depth() >= MAX_DEPTH) {
            sender.failRequest(request, ClientDispatcher.TIP_RATE_LIMIT_EXCEEDED);
            return;
        }
        queue.addLast(new Entry(request, false, receipt, nowMs));
        pump(nowMs, sender);
    }

    /** 收到 scene 对应某请求 id 的业务回包（完成单飞并推进下一条）。 */
    public void onResponse(long requestId, long nowMs, Sender sender) {
        if (inFlight != null && inFlight.request.getId() == requestId) {
            inFlight = null;
            inFlightSentAtMs = 0L;
            retryNotBeforeMs = 0L;
            pump(nowMs, sender);
        }
    }

    /**
     * 收到 scene 回 {@code ClientForwardRetryLater}（确定未执行）：
     * 放回队头、设定 >=200ms 重投等待；超 2000ms 或已重定向直接回 1003；立即冲刷排在其后的单向消息。
     */
    public void onRetryLater(long requestId, long nowMs, Sender sender) {
        if (inFlight == null || inFlight.request.getId() != requestId) {
            return;
        }
        Entry head = inFlight;
        inFlight = null;
        inFlightSentAtMs = 0L;
        if (redirected || nowMs - head.receivedAtMs >= MAX_WAIT_MS) {
            sender.failRequest(head.request, ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
            flushLeadingOneWays(nowMs, sender);
            pump(nowMs, sender);
            return;
        }
        queue.addFirst(head);
        retryNotBeforeMs = nowMs + RETRY_INTERVAL_MS;
        // 定案 §2：队头进入重投等待时，原排在其后的单向消息立即按各自收包路由发出
        flushOneWaysBehindHeldHead(nowMs, sender);
    }

    /** 定时或路由变化时推进：检查在途看门狗超时（13s 不重投）与重投退避到期。 */
    public void tick(long nowMs, Sender sender) {
        if (inFlight != null && nowMs - inFlightSentAtMs >= INFLIGHT_WATCHDOG_MS) {
            Entry timedOut = inFlight;
            inFlight = null;
            inFlightSentAtMs = 0L;
            // 结果未知，绝不重投
            sender.failRequest(timedOut.request, ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        }
        pump(nowMs, sender);
    }

    /**
     * 会话推 124 重定向前调用：把队列里尚未受理的请求逐条回 1003，单向丢弃；
     * 在途请求保持不取消（照常收正常回包；若回 retry_later 立即回 1003）。
     */
    public void onRedirect(Sender sender) {
        redirected = true;
        retryNotBeforeMs = 0L;
        while (!queue.isEmpty()) {
            Entry entry = queue.pollFirst();
            if (!entry.oneWay) {
                sender.failRequest(entry.request, ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
            }
        }
    }

    /** 链路断开或解绑场景时调用：在途（结果未知）不重投，未发出的排队请求回 1003。 */
    public void clearOnDisconnect(Sender sender) {
        inFlight = null;
        inFlightSentAtMs = 0L;
        retryNotBeforeMs = 0L;
        while (!queue.isEmpty()) {
            Entry entry = queue.pollFirst();
            if (!entry.oneWay && sender != null) {
                sender.failRequest(entry.request, ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
            }
        }
    }

    private void pump(long nowMs, Sender sender) {
        flushLeadingOneWays(nowMs, sender);
        if (inFlight != null || queue.isEmpty()) {
            return;
        }
        Entry head = queue.peekFirst();
        if (head.oneWay) {
            flushLeadingOneWays(nowMs, sender);
            return;
        }
        if (nowMs - head.receivedAtMs >= MAX_WAIT_MS) {
            queue.pollFirst();
            retryNotBeforeMs = 0L;
            sender.failRequest(head.request, ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
            pump(nowMs, sender);
            return;
        }
        if (retryNotBeforeMs > 0L && nowMs < retryNotBeforeMs) {
            return;
        }
        queue.pollFirst();
        retryNotBeforeMs = 0L;
        RouteKey current = Objects.requireNonNullElse(sender.currentRoute(), RouteKey.NONE);
        boolean stale = !head.receiptRoute.isSameRoute(current);
        if (!sender.sendToScene(head.request, stale)) {
            // 确定未发出：若尚未超等待上限，放回队头按 200ms 退避等待目标链路就绪
            if (!redirected && nowMs - head.receivedAtMs < MAX_WAIT_MS) {
                queue.addFirst(head);
                retryNotBeforeMs = nowMs + RETRY_INTERVAL_MS;
                flushOneWaysBehindHeldHead(nowMs, sender);
            } else {
                sender.failRequest(head.request, ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
                pump(nowMs, sender);
            }
            return;
        }
        inFlight = head;
        inFlightSentAtMs = nowMs;
        // 定案 §2：请求刚发出后，紧跟其后的单向消息立刻跟发，不等回包
        flushLeadingOneWays(nowMs, sender);
    }

    private void flushLeadingOneWays(long nowMs, Sender sender) {
        while (!queue.isEmpty() && queue.peekFirst().oneWay) {
            Entry ow = queue.pollFirst();
            dispatchOneWay(ow, nowMs, sender);
        }
    }

    private void flushOneWaysBehindHeldHead(long nowMs, Sender sender) {
        if (queue.size() <= 1) {
            return;
        }
        List<Entry> kept = new ArrayList<>(queue.size());
        List<Entry> toFlush = new ArrayList<>();
        boolean first = true;
        for (Entry e : queue) {
            if (first) {
                kept.add(e);
                first = false;
            } else if (e.oneWay) {
                toFlush.add(e);
            } else {
                kept.add(e);
            }
        }
        queue.clear();
        queue.addAll(kept);
        for (Entry ow : toFlush) {
            dispatchOneWay(ow, nowMs, sender);
        }
    }

    private void dispatchOneWay(Entry entry, long nowMs, Sender sender) {
        if (nowMs - entry.receivedAtMs > MAX_WAIT_MS) {
            return; // 超过 2 秒等待上限丢弃
        }
        RouteKey current = Objects.requireNonNullElse(sender.currentRoute(), RouteKey.NONE);
        boolean stale = !entry.receiptRoute.isSameRoute(current);
        sender.sendToScene(entry.request, stale);
    }

    private boolean hasUnsentRequestReadyToSend() {
        for (Entry e : queue) {
            if (!e.oneWay) {
                return true;
            }
        }
        return false;
    }
}
