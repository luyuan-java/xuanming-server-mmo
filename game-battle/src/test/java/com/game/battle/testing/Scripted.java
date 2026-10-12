package com.game.battle.testing;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 假端口一类调用的<b>可编排结局</b>（scene-battle-spec §13.5「可控完成次序」）。每次调用给出一个「自然结局」的算法（通常是把假件的内存模型
 * 推进一步），本类决定它什么时候、以什么方式交回调用方：
 *
 * <ul>
 *   <li>缺省 {@link #auto()}：当场按自然结局完成；</li>
 *   <li>{@link #hold()}：返回悬着的 future，进 {@link #pending}，由测试逐个 {@link Pending#release()}（此刻才算自然结局，模拟命令这时才落地）、
 *       {@link Pending#complete}（强给一个值，不走模型）或 {@link Pending#fail}；</li>
 *   <li>{@link #failWith}：当场异常完成（不走模型）；{@link #throwing}：同步抛出；{@link #replyWith}：当场给固定值（不走模型，可以是 null）；
 *       {@link #returnNullFuture()}：返回 null（坏端口）。</li>
 * </ul>
 * 编排对<b>之后</b>的调用生效，已经返回的 future 不受影响。线程安全。
 *
 * @param <T> 结局类型
 */
public final class Scripted<T> {

    private enum Mode { AUTO, HOLD, FAIL, THROW, FIXED, NULL_FUTURE }

    /** 一次悬着的调用。 */
    public static final class Pending<T> {
        private final String label;
        private final Supplier<T> natural;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        private Pending(String label, Supplier<T> natural) {
            this.label = label;
            this.natural = natural;
        }

        /** 这次调用的标签（同它在 {@link CallJournal} 里的条目）。 */
        public String label() {
            return label;
        }

        public boolean isDone() {
            return future.isDone();
        }

        /** 现在才算自然结局并交回（模拟命令这时才落地、回复这时才到）。返回交回的值。 */
        public T release() {
            T value = natural.get();
            future.complete(value);
            return value;
        }

        /** 强给一个值（不走模型）。 */
        public void complete(T value) {
            future.complete(value);
        }

        /** 异常完成（不走模型：命令没有落地，或落地了但回复丢了——由测试另行推进模型）。 */
        public void fail(Throwable error) {
            future.completeExceptionally(error);
        }
    }

    /** 悬着的调用（{@link #hold()} 期间发起的，按发起顺序；已完成的不移除）。 */
    public final List<Pending<T>> pending = new CopyOnWriteArrayList<>();

    private volatile Mode mode = Mode.AUTO;
    private volatile Throwable failure;
    private volatile RuntimeException thrown;
    private volatile T fixed;

    public Scripted<T> auto() {
        mode = Mode.AUTO;
        return this;
    }

    public Scripted<T> hold() {
        mode = Mode.HOLD;
        return this;
    }

    public Scripted<T> failWith(Throwable error) {
        failure = error;
        mode = Mode.FAIL;
        return this;
    }

    public Scripted<T> throwing(RuntimeException error) {
        thrown = error;
        mode = Mode.THROW;
        return this;
    }

    public Scripted<T> replyWith(T value) {
        fixed = value;
        mode = Mode.FIXED;
        return this;
    }

    public Scripted<T> returnNullFuture() {
        mode = Mode.NULL_FUTURE;
        return this;
    }

    /** 最近一次悬着的调用。 */
    public Pending<T> last() {
        return pending.get(pending.size() - 1);
    }

    /** 按发起顺序放行全部还悬着的调用（各按自然结局）。 */
    public void releaseAll() {
        for (Pending<T> p : pending) {
            if (!p.isDone()) {
                p.release();
            }
        }
    }

    /** 假件在每次调用时调：按当前编排给出 future。 */
    public CompletableFuture<T> invoke(String label, Supplier<T> natural) {
        switch (mode) {
            case HOLD -> {
                Pending<T> p = new Pending<>(label, natural);
                pending.add(p);
                return p.future;
            }
            case FAIL -> {
                return CompletableFuture.failedFuture(failure);
            }
            case THROW -> throw thrown;
            case FIXED -> {
                return CompletableFuture.completedFuture(fixed);
            }
            case NULL_FUTURE -> {
                return null;
            }
            default -> {
                return CompletableFuture.completedFuture(natural.get());
            }
        }
    }
}
