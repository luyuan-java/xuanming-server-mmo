package com.game.match.testing;

import com.game.match.gather.GatherHooks;
import com.game.match.proto.BattlePlacement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link GatherHooks} 的测试替身：记下两个钩子各被调了几次、带什么参数，并把它们记进一条共享的事件序列（断言「beforePrepare 先于第一次备战」
 * 「失败路径不调 onStarted」「onStarted 在补写落点之后」）。可以让钩子抛异常，验证管线把它吞掉、不影响开局。
 *
 * <pre>
 * List&lt;String&gt; events = new CopyOnWriteArrayList&lt;&gt;();          // 与 FakeSceneBattle / InMemoryPlacementStore 共用同一条序列
 * RecordingGatherHooks hooks = new RecordingGatherHooks(events);
 * hooks.throwOnBeforePrepare = true;
 * assertThat(events).containsSubsequence("hooks.beforePrepare", "scene.prepare:1001");
 * </pre>
 */
public final class RecordingGatherHooks implements GatherHooks {

    /** {@code beforePrepare} 收到的名单，按调用顺序。 */
    public final List<List<Long>> beforePrepare = new CopyOnWriteArrayList<>();
    /** {@code onStarted} 收到的落点记录，按调用顺序。 */
    public final List<BattlePlacement> started = new CopyOnWriteArrayList<>();
    /** 事件序列：{@code "hooks.beforePrepare"} / {@code "hooks.onStarted"}。 */
    public final List<String> events;
    public volatile boolean throwOnBeforePrepare;
    public volatile boolean throwOnStarted;

    public RecordingGatherHooks() {
        this(new CopyOnWriteArrayList<>());
    }

    /** @param events 与别的替身共用的事件序列 */
    public RecordingGatherHooks(List<String> events) {
        this.events = events;
    }

    @Override
    public void beforePrepare(List<Long> members) {
        beforePrepare.add(List.copyOf(members));
        events.add("hooks.beforePrepare");
        if (throwOnBeforePrepare) {
            throw new IllegalStateException("注入的故障: beforePrepare");
        }
    }

    @Override
    public void onStarted(BattlePlacement placement) {
        started.add(placement);
        events.add("hooks.onStarted");
        if (throwOnStarted) {
            throw new IllegalStateException("注入的故障: onStarted");
        }
    }
}
