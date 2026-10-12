package com.game.gate.presence;

import com.game.discovery.proto.PlayerPresence;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.gate.session.PresenceRecorder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本 gate 在玩家在线目录里的那一部分（gate 是在线目录的唯一写者）。
 *
 * <p>{@link #online} / {@link #offline} 由会话 EventLoop 调用，只改本地表并发出异步写（不阻塞）；
 * 本地表是「本 gate 认为在游戏里的玩家」的权威副本，{@link #refreshNow} 每 TTL/3 按它续期（一次往返），
 * Redis 抖动丢了的条目在下一轮补回。gate 进程死掉后条目最多一个 TTL 自然消失。
 *
 * <p>同一玩家在本 gate 上换了会话（重登到同一个 gate）：本地表以归属 epoch 更高的登录为准（确认可能乱序到达），
 * 旧会话下线只撤销它自己写的那份。
 *
 * <p>发往 Redis 的写没有执行次序的保证，所以：同一名玩家的写入与撤销排成一队逐条发（{@link #enqueue}）；
 * 续期在途时下线的条目，续期回来后再撤销一次（{@link #removeGoneSince}）。否则已下线的玩家会在目录里多留至多一个 TTL。
 */
public final class GatePresence implements PresenceRecorder {

    private static final Logger log = LoggerFactory.getLogger(GatePresence.class);

    /** 续期周期：TTL 的 1/3，留两次失败的余量。 */
    public static final Duration REFRESH_PERIOD = PlayerPresenceDirectory.TTL.dividedBy(3);

    private final PlayerPresenceDirectory directory;
    private final int zoneId;
    private final int gateNodeId;
    private final String gateInstanceId;
    private final LongSupplier clockMs;
    private final ConcurrentHashMap<Long, PlayerPresence> online = new ConcurrentHashMap<>();
    /** 每名玩家最后一条还没有结局的目录写（写入或撤销；有结局即移除）：同一玩家的下一条排在它后面发，见 {@link #enqueue}。 */
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private ScheduledFuture<?> refreshTask;

    public GatePresence(PlayerPresenceDirectory directory, int zoneId, int gateNodeId, String gateInstanceId,
                        LongSupplier clockMs) {
        this.directory = directory;
        this.zoneId = zoneId;
        this.gateNodeId = gateNodeId;
        this.gateInstanceId = gateInstanceId;
        this.clockMs = clockMs;
    }

    @Override
    public void online(long playerId, int sessionId, long ownerEpoch) {
        PlayerPresence entry = PlayerPresence.newBuilder()
                .setPlayerId(playerId)
                .setZoneId(zoneId)
                .setGateNodeId(gateNodeId)
                .setGateInstanceId(gateInstanceId)
                .setSessionId(sessionId)
                .setOnlineSinceMs(clockMs.getAsLong())
                .setOwnerEpoch(ownerEpoch)
                .build();
        // 同一玩家在本 gate 上的两次登录，确认可能乱序到达（不同会话在不同 EventLoop、可能来自不同 scene 链路）：
        // 以归属 epoch 定先后，旧登录的迟到确认不覆盖新登录的条目（否则旧会话随后被踢时会把新会话的条目一并撤掉）。
        PlayerPresence kept = online.compute(playerId,
                (id, existing) -> existing != null && existing.getOwnerEpoch() > ownerEpoch ? existing : entry);
        if (kept != entry) {
            log.info("丢弃旧登录迟到的在线登记 player={} session={} epoch={} 现有 epoch={}", Long.toUnsignedString(playerId),
                    Integer.toUnsignedString(sessionId), ownerEpoch, kept.getOwnerEpoch());
            return;
        }
        enqueue(playerId, () -> directory.putAsync(entry), error -> log.warn("写在线目录失败（下一轮续期补回） player={} session={}",
                Long.toUnsignedString(playerId), Integer.toUnsignedString(sessionId), error));
    }

    @Override
    public void offline(long playerId, int sessionId) {
        PlayerPresence entry = online.get(playerId);
        if (entry == null || entry.getSessionId() != sessionId || !online.remove(playerId, entry)) {
            return;   // 不是这个会话写的（同一玩家已在本 gate 的新会话上）
        }
        remove(entry);
    }

    /** 撤销一条自己写的条目（按值比较后删除：已被别的会话覆盖的不动）。 */
    private void remove(PlayerPresence entry) {
        enqueue(entry.getPlayerId(), () -> directory.removeAsync(entry), error -> log.warn("撤销在线目录失败（条目最多 {} 后过期） player={}",
                PlayerPresenceDirectory.TTL, Long.toUnsignedString(entry.getPlayerId()), error));
    }

    /**
     * 同一名玩家的目录写（写入、撤销）按发出的次序逐条执行：上一条有了结局（成功或失败）才发下一条。
     *
     * <p>它们是各自异步的 Redis 命令，Redisson 不保证按发出的次序执行。进场后立刻断线（写入还在途）时，撤销若先执行就是空操作，
     * 随后写入落地，留下一条没人续期、要等 TTL 才消失的条目——这段时间别的服务会把已下线的玩家当成在线
     * （2026-10-06 robot team：邀请刚登出的账号偶发回 0 而不是 4017）；同一玩家先后两个会话的两次写入乱序，则是旧条目盖住新条目。
     * 不同玩家之间互不等待；只挂回调、不阻塞调用线程。
     */
    private void enqueue(long playerId, Supplier<CompletionStage<?>> write, Consumer<Throwable> onError) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        CompletableFuture<Void> previous = tails.put(playerId, settled);
        Runnable send = () -> {
            CompletionStage<?> stage;
            try {
                stage = write.get();
            } catch (RuntimeException e) {
                stage = CompletableFuture.failedFuture(e);
            }
            stage.whenComplete((result, error) -> {
                if (error != null) {
                    onError.accept(error);
                }
                tails.remove(playerId, settled);
                settled.complete(null);
            });
        };
        if (previous == null) {
            send.run();
        } else {
            previous.whenComplete((ignored, error) -> send.run());
        }
    }

    /** 本 gate 认为在游戏里的玩家数。 */
    public int size() {
        return online.size();
    }

    public synchronized void start(ScheduledExecutorService scheduler) {
        if (refreshTask == null) {
            long period = REFRESH_PERIOD.toMillis();
            refreshTask = scheduler.scheduleAtFixedRate(this::refreshNow, period, period, TimeUnit.MILLISECONDS);
        }
    }

    /** 停止续期并撤销本 gate 写的全部条目（停服 / 丢租约时；尽力而为，失败的随 TTL 过期）。 */
    public synchronized void stop() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
        for (PlayerPresence entry : List.copyOf(online.values())) {
            offline(entry.getPlayerId(), entry.getSessionId());
        }
    }

    /** 一轮续期（调度线程上；包内可见供测试直接驱动）。 */
    void refreshNow() {
        List<PlayerPresence> snapshot = List.copyOf(online.values());
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            directory.refreshAsync(snapshot).whenComplete((restored, error) -> {
                if (error != null) {
                    log.warn("在线目录续期失败（连续失败超过 {} 条目会过期） 条目={}", PlayerPresenceDirectory.TTL,
                            snapshot.size(), error);
                } else if (restored > 0) {
                    log.info("在线目录续期补回了 {} 条丢失的条目", restored);
                }
                removeGoneSince(snapshot);
            });
        } catch (RuntimeException e) {
            log.warn("在线目录续期提交失败 条目={}", snapshot.size(), e);
        }
    }

    /**
     * 续期在途时下线（或换了会话）的条目再撤销一次。续期脚本对「键不在」的条目会补回（那是给 Redis 抖动丢键准备的），
     * 而它与下线时发出的撤销同样没有执行次序的保证：撤销先执行、续期后执行，刚删的条目就被补回来、要等 TTL 才消失。
     * 撤销按值比较后才删，所以对已被新会话覆盖的键是空操作；续期失败时也照做（批里先执行的那几条可能已经补回了）。
     */
    private void removeGoneSince(List<PlayerPresence> snapshot) {
        for (PlayerPresence entry : snapshot) {
            if (!entry.equals(online.get(entry.getPlayerId()))) {
                remove(entry);
            }
        }
    }
}
