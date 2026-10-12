package com.game.scene.location;

import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.PlayerLocationDirectory.Refresh;
import com.game.discovery.proto.PlayerLocation;
import com.game.scene.world.PlayerLocations;
import com.game.scene.world.ScenePlayer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家位置写进 Redis（{@link PlayerLocationDirectory}）。方法在场景逻辑线程上调用：在逻辑线程上读完玩家状态、取好写序号、
 * 组好消息，再异步发出，不阻塞；失败只记告警（位置只是 login 选场景的提示，写不上时重连 / 顶号按首登落默认主世界）。
 *
 * <p>唯一带完成回调的写口是待落点（{@link #awaitingPlacement}，批次 5.4）：Redis 的结果在 Redisson 的回调线程上出来，
 * 那里只做投递（回调里只用到调用时取好的玩家号与回调本身，不碰 {@link ScenePlayer}），回到逻辑线程再调 {@code onDone}。
 */
public final class RedisPlayerLocations implements PlayerLocations {

    private static final Logger log = LoggerFactory.getLogger(RedisPlayerLocations.class);

    /** 待落点记录存活时长的下限（zone-travel-spec §5.9：TTL 截到 [1 s, 300 s]；票据快过期或时钟偏差算出非正数时也至少留 1 s）。 */
    static final Duration MIN_PLACEMENT_TTL = Duration.ofSeconds(1);

    private final PlayerLocationDirectory directory;
    private final int zoneId;
    private final int sceneNodeId;
    /** 投递回场景逻辑线程：只有带完成回调的写口（{@link #awaitingPlacement}）用它。 */
    private final Executor logic;

    /**
     * @param logic 场景逻辑线程的执行器（已停止时抛拒绝异常，那时回调丢弃）
     */
    public RedisPlayerLocations(PlayerLocationDirectory directory, int zoneId, int sceneNodeId, Executor logic) {
        this.directory = directory;
        this.zoneId = zoneId;
        this.sceneNodeId = sceneNodeId;
        this.logic = Objects.requireNonNull(logic, "logic");
    }

    @Override
    public void entered(ScenePlayer player) {
        watch(directory.putAsync(locationOf(player), player.nextLocationSeq()), "写入", player.playerId());
    }

    @Override
    public void disconnected(ScenePlayer player) {
        watch(directory.leaseAsync(locationOf(player), player.nextLocationSeq()), "转成重连租约", player.playerId());
    }

    @Override
    public void loggedOut(ScenePlayer player) {
        watch(directory.removeAsync(player.playerId(), player.ownerEpoch(), player.nextLocationSeq()), "登出",
                player.playerId());
    }

    @Override
    public void loggedOutWhileLoading(long playerId, long ownerEpoch) {
        // 这次进场还没写过记录，序号从 1 起即可：它的 epoch 比任何更早的记录都新
        watch(directory.removeAsync(playerId, ownerEpoch, 1), "登出（加载中）", playerId);
    }

    @Override
    public void refresh(Collection<ScenePlayer> players) {
        if (players.isEmpty()) {
            return;
        }
        List<Refresh> refreshes = new ArrayList<>(players.size());
        for (ScenePlayer player : players) {
            refreshes.add(new Refresh(locationOf(player), player.locationSeq()));
        }
        directory.refreshAsync(refreshes).whenComplete((restored, failure) -> {
            if (failure != null) {
                log.warn("玩家位置续期失败 人数={}: {}", refreshes.size(), failure.toString());
            } else if (restored > 0) {
                log.warn("玩家位置续期时补回了丢失的记录 条数={}", restored);
            }
        });
    }

    /**
     * 跨 zone 传送的待落点（zone-travel-spec §5.3、§5.9）：{@code (epoch, seq) = (removed.ownerEpoch(), removed.nextLocationSeq())}——
     * 交出前持有的 E 与续接的序号（不是交出铸出的 E+1：位置记录只由持有者以自己写过的 epoch 写）；值是
     * {@code {zone = 目标, 节点号 0, 场景号 0, scene_config_id = 解析后的地图, owner_epoch = E}}，状态 {@code l}，
     * TTL = {@code ttl} 截到 [1 s, 300 s]（{@link PlayerLocationDirectory#AWAIT_PLACEMENT_MAX_TTL}）。
     *
     * <p>{@code onDone} 恰好一次、一定经逻辑执行器投递（不在本次调用栈内）：true = 写上了；false = 没写上（已有更新的写，例如别的登录
     * 已经以更大的 epoch 进场；Redis 故障；参数不合法被目录当场拒绝）。逻辑执行器已停止时什么都不做——归属已在库里定案（已释放），
     * 记录写没写上都只影响第二条腿的落点。
     *
     * @throws NullPointerException {@code removed} / {@code ttl} / {@code onDone} 为 null（编程错误，在取序号与发命令之前抛）
     */
    @Override
    public void awaitingPlacement(ScenePlayer removed, int targetZoneId, int sceneConfigId, Duration ttl,
                                  Consumer<Boolean> onDone) {
        Objects.requireNonNull(removed, "removed");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(onDone, "onDone");
        // 在逻辑线程上读完实例：之后的 Redisson 回调线程只拿到玩家号
        long playerId = removed.playerId();
        PlayerLocation pending = PlayerLocation.newBuilder()
                .setPlayerId(playerId)
                .setZoneId(targetZoneId)
                .setSceneConfigId(sceneConfigId)
                .setOwnerEpoch(removed.ownerEpoch())
                .build();
        long seq = removed.nextLocationSeq();
        Duration clamped = clampPlacementTtl(ttl);
        CompletionStage<Boolean> write;
        try {
            write = directory.awaitPlacementAsync(pending, seq, clamped);
        } catch (RuntimeException e) {
            // 目录在发命令之前拒绝了参数（目标 zone 为 0 等调用方的编程错误）：按没写上，回调仍恰好一次、仍经逻辑执行器
            log.error("待落点参数被拒，按没写上处理 player={} zone={} scene_config={} seq={}", Long.toUnsignedString(playerId),
                    Integer.toUnsignedString(targetZoneId), Integer.toUnsignedString(sceneConfigId), seq, e);
            write = CompletableFuture.failedFuture(e);
        }
        write.whenComplete((applied, failure) -> {
            boolean written = failure == null && Boolean.TRUE.equals(applied);
            if (failure != null) {
                log.warn("玩家位置写待落点失败 player={} zone={}: {}", Long.toUnsignedString(playerId),
                        Integer.toUnsignedString(targetZoneId), failure.toString());
            } else if (!written) {
                log.info("玩家位置待落点没生效（已有更新的写） player={} zone={}", Long.toUnsignedString(playerId),
                        Integer.toUnsignedString(targetZoneId));
            }
            try {
                logic.execute(() -> onDone.accept(written));
            } catch (RejectedExecutionException e) {
                log.debug("场景逻辑线程已停止，丢弃待落点的写结果 player={}", Long.toUnsignedString(playerId));
            }
        });
    }

    /** 待落点 TTL 截到 [1 s, {@link PlayerLocationDirectory#AWAIT_PLACEMENT_MAX_TTL}]。 */
    static Duration clampPlacementTtl(Duration ttl) {
        if (ttl.compareTo(MIN_PLACEMENT_TTL) < 0) {
            return MIN_PLACEMENT_TTL;
        }
        return ttl.compareTo(PlayerLocationDirectory.AWAIT_PLACEMENT_MAX_TTL) > 0
                ? PlayerLocationDirectory.AWAIT_PLACEMENT_MAX_TTL : ttl;
    }

    /** 玩家此刻（离场后为最后所在）的场景实例。 */
    private PlayerLocation locationOf(ScenePlayer player) {
        return PlayerLocation.newBuilder()
                .setPlayerId(player.playerId())
                .setZoneId(zoneId)
                .setSceneNodeId(sceneNodeId)
                .setSceneId(player.scene().sceneId())
                .setSceneConfigId(player.scene().configId())
                .setOwnerEpoch(player.ownerEpoch())
                .build();
    }

    private static void watch(CompletionStage<Boolean> stage, String what, long playerId) {
        stage.whenComplete((done, failure) -> {
            if (failure != null) {
                log.warn("玩家位置{}失败 player={}: {}", what, Long.toUnsignedString(playerId), failure.toString());
            } else if (!done) {
                log.debug("玩家位置{}没生效（已有更新的写） player={}", what, Long.toUnsignedString(playerId));
            }
        });
    }
}
