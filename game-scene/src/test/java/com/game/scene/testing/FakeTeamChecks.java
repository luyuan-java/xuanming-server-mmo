package com.game.scene.testing;

import com.game.scene.world.TeamChecks;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 跨 zone 传送受理前在队检查的假实现（批次 5.4）。记录每次调用；缺省<b>挂起不回</b>，由测试决定何时、以哪种结果完成
 * （测试线程就是逻辑线程）。也可以装一段脚本自动回——结果经给定的执行器投递，不在 {@code check} 的调用栈内。
 *
 * <p>只在一个线程上用（测试线程）。
 */
public final class FakeTeamChecks implements TeamChecks {

    /** 一次在队检查。 */
    public record PendingCheck(long playerId, Duration timeout, Consumer<TeamCheck> callback) {

        public void complete(TeamCheck result) {
            callback.accept(result);
        }

        public void notInTeam() {
            complete(TeamCheck.NOT_IN_TEAM);
        }

        public void inTeam() {
            complete(TeamCheck.IN_TEAM);
        }

        /** 读失败 / 超时 / 数据损坏。 */
        public void unknown() {
            complete(TeamCheck.UNKNOWN);
        }
    }

    private final Deque<PendingCheck> pending = new ArrayDeque<>();
    private final List<PendingCheck> calls = new ArrayList<>();
    private Executor logic;
    private Function<PendingCheck, TeamCheck> script;

    @Override
    public void check(long playerId, Duration timeout, Consumer<TeamCheck> onDone) {
        PendingCheck call = new PendingCheck(playerId, timeout, onDone);
        calls.add(call);
        if (script == null) {
            pending.add(call);
            return;
        }
        Function<PendingCheck, TeamCheck> reply = script;
        logic.execute(() -> call.complete(reply.apply(call)));
    }

    /**
     * 之后的每次检查都自动回 {@code script} 的结果，经 {@code logic} 投递（传 {@link ManualExecutor} 时由测试排空才真的回）。
     * 例：{@code replyWith(logic, call -> TeamCheck.NOT_IN_TEAM)}。
     */
    public void replyWith(Executor logic, Function<PendingCheck, TeamCheck> script) {
        this.logic = logic;
        this.script = script;
    }

    /** 回到缺省：之后的检查挂起，等测试 {@link #take()} 后手工完成。 */
    public void hang() {
        this.logic = null;
        this.script = null;
    }

    /** 取出最早一个挂起的检查。 */
    public PendingCheck take() {
        PendingCheck p = pending.poll();
        if (p == null) {
            throw new IllegalStateException("没有挂起的在队检查");
        }
        return p;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** 到目前为止的全部检查（含已完成与自动回的），按发生顺序。 */
    public List<PendingCheck> calls() {
        return calls;
    }
}
