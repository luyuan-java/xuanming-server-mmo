package com.game.gate.presence;

import com.game.discovery.proto.PlayerPresence;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.gate.session.PresenceRecorder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
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
        directory.putAsync(entry).whenComplete((ok, error) -> {
            if (error != null) {
                log.warn("写在线目录失败（下一轮续期补回） player={} session={}", Long.toUnsignedString(playerId),
                        Integer.toUnsignedString(sessionId), error);
            }
        });
    }

    @Override
    public void offline(long playerId, int sessionId) {
        PlayerPresence entry = online.get(playerId);
        if (entry == null || entry.getSessionId() != sessionId || !online.remove(playerId, entry)) {
            return;   // 不是这个会话写的（同一玩家已在本 gate 的新会话上）
        }
        directory.removeAsync(entry).whenComplete((deleted, error) -> {
            if (error != null) {
                log.warn("撤销在线目录失败（条目最多 {} 后过期） player={}", PlayerPresenceDirectory.TTL,
                        Long.toUnsignedString(playerId), error);
            }
        });
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
            });
        } catch (RuntimeException e) {
            log.warn("在线目录续期提交失败 条目={}", snapshot.size(), e);
        }
    }
}
