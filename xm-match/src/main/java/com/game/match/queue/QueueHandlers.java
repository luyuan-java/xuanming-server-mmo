package com.game.match.queue;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.JoinOutcome;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.match.CancelQueueRequest;
import com.game.proto.match.GetQueueStatusRequest;
import com.game.proto.match.JoinQueueRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 排队三个号的处理器（match-spec §8.1 的应答规则）：解析请求体、取会话里的玩家号、调 {@link QueueService}、把结果装成应答。
 *
 * <table>
 *   <caption>§8.1 里归这三个号的各列</caption>
 *   <tr><th>号</th><th>正常</th><th>会话没绑定玩家</th><th>请求体解析失败</th><th>依赖故障</th><th>过载</th></tr>
 *   <tr><td>157</td><td>应答体</td><td>in-band 16004「缺少玩家身份」</td><td>信封 1003</td><td>in-band 16004「服务器繁忙,请稍后再试」</td>
 *       <td>in-band 16004「服务器繁忙,请稍后再试」</td></tr>
 *   <tr><td>148</td><td>Empty（gate 不回包）</td><td>Empty</td><td>信封 1003</td><td>信封 1003</td><td>信封 1003</td></tr>
 *   <tr><td>153</td><td>应答体</td><td>{@code {state = 5}}</td><td>信封 1003</td><td>信封 1003</td><td>信封 1003</td></tr>
 * </table>
 * 解析在身份检查之前：请求体坏了一律是信封 1003（解析失败的异常直接抛给派发器）。三个处理器都进工作池（要等 Redis）。无状态，线程安全。
 */
public final class QueueHandlers {

    private static final Logger log = LoggerFactory.getLogger(QueueHandlers.class);

    private QueueHandlers() {
    }

    /** 157 JoinQueue。 */
    public static final class Join implements MatchMethodHandler {

        private final QueueService service;
        private final MatchMetrics metrics;

        public Join(QueueService service, MatchMetrics metrics) {
            this.service = Objects.requireNonNull(service, "service");
            this.metrics = Objects.requireNonNull(metrics, "metrics");
        }

        @Override
        public String method() {
            return MatchMethods.JOIN_QUEUE;
        }

        @Override
        public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
            JoinQueueRequest request = JoinQueueRequest.parseFrom(body);
            return Reply.body(service.join(session.getPlayerId(), request, deadline));
        }

        /** 过载时请求体没有解析过，模式未知：指标的 mode 记 unknown。 */
        @Override
        public Reply onOverload() {
            metrics.joinQueue(-1, JoinOutcome.OVERLOADED);
            return Reply.body(MatchTips.joinRejected(MatchTip.BUSY));
        }
    }

    /** 148 CancelQueue：应答类型是 Empty，成功回 0 字节的应答体（gate 不回包）。 */
    public static final class Cancel implements MatchMethodHandler {

        private final QueueService service;

        public Cancel(QueueService service) {
            this.service = Objects.requireNonNull(service, "service");
        }

        @Override
        public String method() {
            return MatchMethods.CANCEL_QUEUE;
        }

        @Override
        public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
            CancelQueueRequest request = CancelQueueRequest.parseFrom(body);
            try {
                service.cancel(session.getPlayerId(), request.getQueueTicket(), deadline);
            } catch (Deadline.DependencyException e) {
                log.error("[match] CancelQueue 读票 / 删票失败 player={}: {}", Long.toUnsignedString(session.getPlayerId()), QueueService.why(e));
                return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
            }
            return Reply.empty();
        }

        @Override
        public Reply onOverload() {
            return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
        }
    }

    /** 153 GetQueueStatus。 */
    public static final class Status implements MatchMethodHandler {

        private final QueueService service;

        public Status(QueueService service) {
            this.service = Objects.requireNonNull(service, "service");
        }

        @Override
        public String method() {
            return MatchMethods.GET_QUEUE_STATUS;
        }

        @Override
        public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
            GetQueueStatusRequest.parseFrom(body); // 请求里只有 player_id，忽略；解析只为判坏包
            try {
                return Reply.body(service.status(session.getPlayerId(), deadline));
            } catch (Deadline.DependencyException e) {
                log.error("[match] GetQueueStatus 读票失败 player={}: {}", Long.toUnsignedString(session.getPlayerId()), QueueService.why(e));
                return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
            }
        }

        @Override
        public Reply onOverload() {
            return Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
        }
    }
}
