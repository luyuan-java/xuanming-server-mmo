package com.game.scene.testing;

import com.game.api.proto.ZoneRedirect;
import com.game.scene.world.TravelTargets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 跨 zone 传送选目标的假实现（批次 5.4）。记录每次调用；缺省<b>挂起不回</b>，由测试决定何时、以哪种结局完成
 * （模拟结果投递回逻辑线程：测试线程就是逻辑线程）。也可以装一段脚本自动回——脚本的结果经给定的执行器投递，
 * 不在 {@code selectTravel} 的调用栈内，与真实现的线程纪律一致。
 *
 * <p>只在一个线程上用（测试线程）。
 */
public final class FakeTravelTargets implements TravelTargets {

    /** 一次选目标请求。 */
    public record PendingTravel(long playerId, int toZoneId, int wantSceneConfigId, Consumer<TravelSelection> callback) {

        public void complete(TravelSelection selection) {
            callback.accept(selection);
        }

        /** 选中：解析后的地图与重定向载荷。 */
        public void chosen(int sceneConfigId, ZoneRedirect redirect) {
            complete(new TravelSelection.Chosen(sceneConfigId, redirect));
        }

        /** scene-manager 业务拒绝。 */
        public void refused(int tipId) {
            complete(new TravelSelection.Refused(tipId));
        }

        /** 调用失败 / 超时 / 应答残缺。 */
        public void failed(String reason) {
            complete(new TravelSelection.Failed(reason));
        }
    }

    private final Deque<PendingTravel> pending = new ArrayDeque<>();
    private final List<PendingTravel> calls = new ArrayList<>();
    private Executor logic;
    private Function<PendingTravel, TravelSelection> script;

    @Override
    public void selectTravel(long playerId, int toZoneId, int wantSceneConfigId, Consumer<TravelSelection> onDone) {
        PendingTravel call = new PendingTravel(playerId, toZoneId, wantSceneConfigId, onDone);
        calls.add(call);
        if (script == null) {
            pending.add(call);
            return;
        }
        Function<PendingTravel, TravelSelection> reply = script;
        logic.execute(() -> call.complete(reply.apply(call)));
    }

    /**
     * 之后的每次请求都自动回 {@code script} 的结果，经 {@code logic} 投递（传 {@link ManualExecutor} 时由测试排空才真的回）。
     * 例：{@code replyWith(logic, call -> new TravelSelection.Failed("测试"))}。
     */
    public void replyWith(Executor logic, Function<PendingTravel, TravelSelection> script) {
        this.logic = logic;
        this.script = script;
    }

    /** 回到缺省：之后的请求挂起，等测试 {@link #take()} 后手工完成。 */
    public void hang() {
        this.logic = null;
        this.script = null;
    }

    /** 取出最早一个挂起的请求。 */
    public PendingTravel take() {
        PendingTravel p = pending.poll();
        if (p == null) {
            throw new IllegalStateException("没有挂起的跨 zone 选目标请求");
        }
        return p;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** 到目前为止的全部请求（含已完成与自动回的），按发生顺序。 */
    public List<PendingTravel> calls() {
        return calls;
    }
}
