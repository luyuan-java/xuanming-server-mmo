package com.game.match.spectate;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.ListResult;
import com.game.match.support.MatchTips;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Objects;

/**
 * 164 {@code MatchService.ListWatchableBattles} 的入口处理器（spectate-spec §3.3、§4.11 给 match-spec §8.1 的 164 一行）。
 * 只有 Redis 操作，跑在共用的 {@code match-worker} 上（不是 inline，也不带自己的执行器）。应答规则：
 *
 * <table>
 *   <caption>164 的各列</caption>
 *   <tr><th>情形</th><th>应答</th></tr>
 *   <tr><td>正常（含空列表、含批读落点失败后变短的列表）</td><td>应答体 {@code ListWatchableBattlesResponse}；空列表是 0 字节，gate 按应答类型照常回包</td></tr>
 *   <tr><td>会话没绑定玩家</td><td><b>照常回列表</b>（BW4：不看身份；请求体里的 {@code player_id} 也忽略）</td></tr>
 *   <tr><td>请求体解析失败</td><td>信封 1003（让异常抛给派发器）</td></tr>
 *   <tr><td>读索引失败</td><td>信封 1003（164 没有 in-band 错误字段）</td></tr>
 *   <tr><td>工作池满 / 排队超预算</td><td>信封 1003（M29），计 {@code list_watchable_total{result="overloaded"}}</td></tr>
 * </table>
 * 无状态，线程安全。
 */
public final class ListWatchableHandler implements MatchMethodHandler {

    private static final Reply UNAVAILABLE = Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);

    private final WatchableListService service;
    private final MatchMetrics metrics;

    public ListWatchableHandler(WatchableListService service, MatchMetrics metrics) {
        this.service = Objects.requireNonNull(service, "service");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public String method() {
        return MatchMethods.LIST_WATCHABLE_BATTLES;
    }

    @Override
    public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
        ListWatchableBattlesRequest request = ListWatchableBattlesRequest.parseFrom(body);
        return switch (service.list(request.getLimit(), deadline)) {
            case WatchableListService.Result.Listed listed -> Reply.body(listed.response());
            case WatchableListService.Result.IndexUnavailable unavailable -> UNAVAILABLE;
        };
    }

    /** 过载时请求没有进过流程：这里记它的出口。 */
    @Override
    public Reply onOverload() {
        metrics.listWatchable(ListResult.OVERLOADED);
        return UNAVAILABLE;
    }
}
