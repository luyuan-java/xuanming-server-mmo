package com.game.scene.transfer;

import com.game.discovery.world.ChannelSwitchCooldown;
import com.game.discovery.world.WorldChannelDirectoryStore;
import com.game.proto.SceneChannelDirectory;
import com.game.scene.world.PlayerChannels;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 共享分线目录与冷却的阻塞存储适配：只在有界 worker 调 IO，所有结果回场景逻辑线程。 */
public final class SharedPlayerChannels implements PlayerChannels {
    private static final Logger log = LoggerFactory.getLogger(SharedPlayerChannels.class);
    private final int zoneId;
    private final WorldChannelDirectoryStore directories;
    private final ChannelSwitchCooldown cooldown;
    private final Executor worker;
    private final Executor logic;
    private final Duration timeout;

    public SharedPlayerChannels(int zoneId, WorldChannelDirectoryStore directories, ChannelSwitchCooldown cooldown,
                                Executor worker, Executor logic) {
        this(zoneId, directories, cooldown, worker, logic, Duration.ofSeconds(3));
    }

    SharedPlayerChannels(int zoneId, WorldChannelDirectoryStore directories, ChannelSwitchCooldown cooldown,
                         Executor worker, Executor logic, Duration timeout) {
        this.zoneId = zoneId;
        this.directories = Objects.requireNonNull(directories);
        this.cooldown = Objects.requireNonNull(cooldown);
        this.worker = Objects.requireNonNull(worker);
        this.logic = Objects.requireNonNull(logic);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("分线 IO 超时必须为正");
    }

    @Override
    public void directory(int configId, Consumer<SceneChannelDirectory> onDone) {
        CompletableFuture<SceneChannelDirectory> result = new CompletableFuture<>();
        result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((value, error) -> {
            if (error != null) log.debug("读分线目录失败 zone={} config={}: {}", zoneId, configId, error.toString());
            post(() -> onDone.accept(error == null ? value : null));
        });
        try {
            worker.execute(() -> {
                if (result.isDone()) return;
                try {
                    byte[] bytes = directories.read(zoneId, configId);
                    SceneChannelDirectory value = bytes == null ? null : SceneChannelDirectory.parseFrom(bytes);
                    if (value != null && (value.getZoneId() != zoneId || value.getSceneConfigId() != configId)) {
                        throw new IllegalStateException("分线目录所属区服或地图不符");
                    }
                    result.complete(value);
                } catch (Exception error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
    }

    @Override
    public void switched(long playerId, int seconds, Runnable onDone) {
        switched(playerId, seconds, onDone, () -> { });
    }

    @Override
    public void switched(long playerId, int seconds, Runnable onDone, Runnable onUndelivered) {
        if (seconds == 0) { post(onDone, onUndelivered); return; }
        CompletableFuture<Void> result = new CompletableFuture<>();
        result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((ignored, error) -> {
            if (error != null) log.warn("已成功切线，写共享冷却失败（不回滚）: {}", error.toString());
            post(onDone, onUndelivered);
        });
        try {
            worker.execute(() -> {
                if (result.isDone()) return;
                try {
                    cooldown.start(playerId, Duration.ofSeconds(Integer.toUnsignedLong(seconds)));
                    result.complete(null);
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
    }

    private void post(Runnable action) {
        post(action, () -> { });
    }

    private void post(Runnable action, Runnable onUndelivered) {
        try { logic.execute(action); }
        catch (RejectedExecutionException stopped) {
            log.debug("逻辑线程已停止，分线回调交给未投递清理");
            onUndelivered.run();
        }
    }
}
