package com.game.scene.testing;

import com.game.scene.world.PlayerLocations;
import com.game.scene.world.ScenePlayer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * 记录全部位置写的假写口（批次 5.4）。与 {@code RedisPlayerLocations} 一样：除续期外每次写都在调用当时取下一个位置序号，
 * 所以断言得出「待落点是 (E, q+1)、改回源场景是 (E, q+2)」这样的序号关系。
 *
 * <p>待落点写（{@link #awaitingPlacement}）缺省<b>挂起</b>：记进 {@link #events()} 并等测试 {@link #takePlacement()} 后手工完成——
 * 可以在测试线程上直接完成（测试线程就是逻辑线程），也可以{@linkplain PendingPlacement#completeOnAnotherThread 从另一条线程}
 * 经逻辑执行器投递回来（模拟真实现：Redis 的回调线程把结果投递回逻辑线程；执行器已停时结果丢弃、不抛）。
 * 调 {@link #completeInline} 后改成在 {@code awaitingPlacement} 的调用栈内当场完成（接口允许，调用方必须先放好墓碑与计数）。
 *
 * <p>除 {@code completeOnAnotherThread} 起的那条线程外，只在测试线程上用。
 */
public final class RecordingLocations implements PlayerLocations {

    /**
     * 一次普通的位置写。
     *
     * @param action  entered / disconnected / loggedOut / loggedOutWhileLoading
     * @param seq     这次写取到的位置序号（加载中登出固定为 1）
     * @param sceneId 写入时实例所在（离场后为最后所在）的场景；加载中登出为 0
     */
    public record Write(String action, long playerId, long ownerEpoch, long seq, long sceneId) {
    }

    /**
     * 一次待落点写。记录的是调用当时的参数；{@code seq} 是这次写取到的位置序号。
     */
    public static final class PendingPlacement {

        private final long playerId;
        private final long ownerEpoch;
        private final long seq;
        private final int targetZoneId;
        private final int sceneConfigId;
        private final Duration ttl;
        private final Consumer<Boolean> callback;
        private boolean completed;

        private PendingPlacement(long playerId, long ownerEpoch, long seq, int targetZoneId, int sceneConfigId, Duration ttl,
                                 Consumer<Boolean> callback) {
            this.playerId = playerId;
            this.ownerEpoch = ownerEpoch;
            this.seq = seq;
            this.targetZoneId = targetZoneId;
            this.sceneConfigId = sceneConfigId;
            this.ttl = ttl;
            this.callback = callback;
        }

        public long playerId() {
            return playerId;
        }

        public long ownerEpoch() {
            return ownerEpoch;
        }

        public long seq() {
            return seq;
        }

        public int targetZoneId() {
            return targetZoneId;
        }

        public int sceneConfigId() {
            return sceneConfigId;
        }

        public Duration ttl() {
            return ttl;
        }

        /** 在当前线程（测试线程 = 逻辑线程）上完成：true = 写上了，false = 没写上。完成两次是测试的错误。 */
        public void complete(boolean written) {
            if (completed) {
                throw new IllegalStateException("同一次待落点写完成了两次 player=" + playerId);
            }
            completed = true;
            callback.accept(written);
        }

        /**
         * 模拟真实现的线程路径：在<b>另一条线程</b>上「写完」，把结果经 {@code logic} 投递回逻辑线程（回调本身在 {@code logic} 里跑）。
         * {@code logic} 拒绝（逻辑线程已停）时结果丢弃、不抛，同真实现的契约。
         *
         * @return 那条线程把投递做完（或确认被拒）时完成；测试<b>先带超时等它</b>，再去排空 / 检查逻辑执行器。
         *         值 true = 已投递，false = 被拒、已丢弃
         */
        public CompletableFuture<Boolean> completeOnAnotherThread(Executor logic, boolean written) {
            if (completed) {
                throw new IllegalStateException("同一次待落点写完成了两次 player=" + playerId);
            }
            completed = true;
            CompletableFuture<Boolean> posted = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try {
                    logic.execute(() -> callback.accept(written));
                    posted.complete(true);
                } catch (RejectedExecutionException e) {
                    posted.complete(false);
                } catch (Throwable e) {
                    posted.completeExceptionally(e);
                }
            }, "fake-location-io");
            thread.setDaemon(true);
            thread.start();
            return posted;
        }

        public boolean completed() {
            return completed;
        }

        @Override
        public String toString() {
            return "PendingPlacement[player=" + playerId + ", epoch=" + ownerEpoch + ", seq=" + seq + ", zone=" + targetZoneId
                    + ", scene_config=" + sceneConfigId + ", ttl=" + ttl + ", completed=" + completed + "]";
        }
    }

    private final List<Object> events = new ArrayList<>();
    private final Deque<PendingPlacement> pendingPlacements = new ArrayDeque<>();
    private final List<List<Long>> refreshes = new ArrayList<>();
    /** 非 null = 待落点写在调用栈内当场以这个结果完成。 */
    private Boolean inlineResult;

    @Override
    public void entered(ScenePlayer player) {
        events.add(write("entered", player));
    }

    @Override
    public void disconnected(ScenePlayer player) {
        events.add(write("disconnected", player));
    }

    @Override
    public void loggedOut(ScenePlayer player) {
        events.add(write("loggedOut", player));
    }

    @Override
    public void loggedOutWhileLoading(long playerId, long ownerEpoch) {
        events.add(new Write("loggedOutWhileLoading", playerId, ownerEpoch, 1, 0));
    }

    @Override
    public void refresh(Collection<ScenePlayer> players) {
        refreshes.add(players.stream().map(ScenePlayer::playerId).toList());
    }

    @Override
    public void awaitingPlacement(ScenePlayer removed, int targetZoneId, int sceneConfigId, Duration ttl,
                                  Consumer<Boolean> onDone) {
        PendingPlacement placement = new PendingPlacement(removed.playerId(), removed.ownerEpoch(), removed.nextLocationSeq(),
                targetZoneId, sceneConfigId, ttl, onDone);
        events.add(placement);
        if (inlineResult != null) {
            placement.complete(inlineResult);
        } else {
            pendingPlacements.add(placement);
        }
    }

    private static Write write(String action, ScenePlayer player) {
        return new Write(action, player.playerId(), player.ownerEpoch(), player.nextLocationSeq(), player.scene().sceneId());
    }

    /** 之后的待落点写在调用栈内当场完成（true = 写上了，false = 没写上）。 */
    public void completeInline(boolean written) {
        this.inlineResult = written;
    }

    /** 回到缺省：之后的待落点写挂起，等测试手工完成。 */
    public void hangPlacements() {
        this.inlineResult = null;
    }

    /** 取出最早一个挂起的待落点写。 */
    public PendingPlacement takePlacement() {
        PendingPlacement p = pendingPlacements.poll();
        if (p == null) {
            throw new IllegalStateException("没有挂起的待落点写");
        }
        return p;
    }

    public int pendingPlacements() {
        return pendingPlacements.size();
    }

    /** 全部位置写（{@link Write} 与 {@link PendingPlacement}），按发生顺序；不含续期。 */
    public List<Object> events() {
        return events;
    }

    /** 普通位置写，按发生顺序。 */
    public List<Write> writes() {
        List<Write> out = new ArrayList<>();
        for (Object event : events) {
            if (event instanceof Write write) {
                out.add(write);
            }
        }
        return out;
    }

    /** 全部待落点写（含已完成的），按发生顺序。 */
    public List<PendingPlacement> placements() {
        List<PendingPlacement> out = new ArrayList<>();
        for (Object event : events) {
            if (event instanceof PendingPlacement placement) {
                out.add(placement);
            }
        }
        return out;
    }

    /** 每次续期的玩家号列表，按发生顺序。 */
    public List<List<Long>> refreshes() {
        return refreshes;
    }

    public void clear() {
        events.clear();
        refreshes.clear();
    }
}
