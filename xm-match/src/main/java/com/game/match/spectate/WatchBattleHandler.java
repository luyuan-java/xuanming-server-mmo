package com.game.match.spectate;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.WatchOutcome;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.match.WatchBattleRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 163 {@code MatchService.WatchBattle} 的入口处理器（spectate-spec §4.11 给 match-spec §8.1 的 163 一行）。<b>不在 {@code match-worker} 上跑</b>：
 * 经 {@link #executor()} 把 163 交给它自己的执行器（{@link SpectateExecutor}：每个请求一条虚拟线程 + 在途上限，lead 裁决 2）。应答规则：
 * <ul>
 *   <li>请求体解析失败 → 信封 1003（让异常抛给派发器）；</li>
 *   <li>会话没绑定玩家、业务拒绝、依赖故障、预算不足 → 都是 in-band（应答体自己的 {@code error_message}），见 {@link WatchBattleService}；</li>
 *   <li>在途已满（执行器拒收）、或轮到执行时预算已用完 → in-band 16004「服务器繁忙,请稍后再试」，另计
 *       {@code xm_match_watch_battle_total{outcome="overloaded"}}（这时没有进过处理流程，标记从未写入）；</li>
 *   <li>处理流程里的未预期异常 → 原样抛给派发器（信封 1003）；已抢到的标记由 {@link WatchBattleService} 按值尽力释放。</li>
 * </ul>
 * 身份只取 {@code session.player_id}：请求体里的 {@code player_id} 解析出来也不用（W12）。无状态、线程安全。
 */
public final class WatchBattleHandler implements MatchMethodHandler {

    private static final Reply OVERLOADED = Reply.body(MatchTips.watchRejected(MatchTip.BUSY));

    private final WatchBattleService service;
    private final Executor executor;
    private final MatchMetrics metrics;

    /**
     * @param executor 163 自己的执行器（生产为 {@link SpectateExecutor}）：{@code execute} 不阻塞、受理不了抛 {@code RejectedExecutionException}、
     *                 受理的任务恰好执行一次、许可由它自己归还
     */
    public WatchBattleHandler(WatchBattleService service, Executor executor, MatchMetrics metrics) {
        this.service = Objects.requireNonNull(service, "service");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public String method() {
        return MatchMethods.WATCH_BATTLE;
    }

    /** 163 的执行器：每次都是同一个；不阻塞、不抛。 */
    @Override
    public Executor executor() {
        return executor;
    }

    @Override
    public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
        WatchBattleRequest request = WatchBattleRequest.parseFrom(body);
        return Reply.body(service.watch(session, request.getBattleId(), deadline));
    }

    @Override
    public Reply onOverload() {
        metrics.watchBattle(WatchOutcome.OVERLOADED);
        return OVERLOADED;
    }
}
