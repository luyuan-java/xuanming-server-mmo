package com.game.battle.directory;

import com.game.api.proto.BattleNodeInfo;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定期把本 battle 节点写进 Redis 节点目录（{@code xm:nodes:battle:0}，{@link BattleNodeInfo}，每 {@link #PERIOD} 一次、TTL {@link #TTL}；
 * battle-node-spec §7.10、§7.11）。6.4 的 match 只从 {@code accepting = true} 的条目里随机挑节点；目录最多滞后 5 s，所以 match 的
 * 「NOT_ALLOCATABLE 后换节点重试一次」仍要保留。
 *
 * <p>每次发布取一份快照（{@link Snapshot}，任意线程可读的原子量：准入闸、租约有效性、房间数、直连数），身份部分（节点号、实例、地址、指纹）
 * 构造时给定、不变。发布在调用方的调度线程上做（Redis I/O），不占逻辑线程。
 *
 * <p>停止语义：{@link #stop} / {@link #stopAfterLeaseLost} 返回后不会再有发布落地（发布与停止持同一把锁），所以「先停再删条目」不会被迟到的发布复活。
 * 快照里租约已丢失时也不发布：节点号可能已归别的实例，写进去会盖掉别人的条目。
 *
 * <p>线程安全。
 */
public final class BattleDirectoryPublisher {

    private static final Logger log = LoggerFactory.getLogger(BattleDirectoryPublisher.class);

    public static final Duration PERIOD = Duration.ofSeconds(5);
    public static final Duration TTL = Duration.ofSeconds(15);

    /**
     * 目录条目的存取（生产实现 {@link RedisBattleDirectory}，按本节点号读写 {@code NodeDirectory<BattleNodeInfo>} 的作用域 0）。
     * 实现可以阻塞（在调度线程上调用），失败抛运行时异常。
     */
    public interface Directory {

        /** 写入 / 覆盖本节点的条目，TTL {@code ttl}。 */
        void publish(BattleNodeInfo info, Duration ttl);

        /** 读本节点号下此刻的条目（可能是别的实例写的）；没有为空。 */
        Optional<BattleNodeInfo> find();

        /** 删除本节点号下的条目。 */
        void remove();
    }

    /**
     * 一次发布的可变部分（任意线程可读）。
     *
     * @param admissionOpen   准入闸是否 OPEN
     * @param leaseValid      节点号租约此刻是否有效（{@code NodeIdLease.isValid()}）
     * @param leaseLost       节点号租约是否已丢失（丢失后不再发布）
     * @param roomCount       房间数
     * @param connectionCount 直连数（含未握手）
     */
    public record Snapshot(boolean admissionOpen, boolean leaseValid, boolean leaseLost, int roomCount, int connectionCount) {

        /** {@code accepting}：准入闸 OPEN 且租约有效。 */
        public boolean accepting() {
            return admissionOpen && leaseValid && !leaseLost;
        }
    }

    private final Directory directory;
    private final BattleNodeInfo identity;
    private final Supplier<Snapshot> snapshot;
    private ScheduledFuture<?> task;
    private boolean stopped;

    /**
     * @param identity 身份部分（node_id / instance_id / rpc_host / rpc_port / client_host / client_port / table_fingerprint）；
     *                 accepting / room_count / connection_count 每次发布时填
     * @param snapshot 取可变部分（不得阻塞、不得碰逻辑线程独占的状态）
     */
    public BattleDirectoryPublisher(Directory directory, BattleNodeInfo identity, Supplier<Snapshot> snapshot) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** 身份部分（日志与测试用）。 */
    public BattleNodeInfo identity() {
        return identity;
    }

    /**
     * 立即发布一次（在调用线程上，阻塞到 Redis 返回）：启动时「开闸之后、就绪日志之前」的首发用它。失败只打 WARN（下一轮重试）。
     *
     * @return 是否真的写进了目录
     */
    public boolean publishNow() {
        return publishOnce();
    }

    /** 开始周期发布（第一次在 {@link #PERIOD} 之后；首发由调用方先 {@link #publishNow()}）。已停止时什么也不做。 */
    public synchronized void start(ScheduledExecutorService scheduler) {
        if (task == null && !stopped) {
            task = scheduler.scheduleWithFixedDelay(this::publishOnce, PERIOD.toMillis(), PERIOD.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    synchronized boolean publishOnce() {
        if (stopped) {
            return false;
        }
        Snapshot s;
        try {
            s = snapshot.get();
        } catch (RuntimeException e) {
            log.warn("取 battle 节点快照失败，本轮不发布节点目录", e);
            return false;
        }
        if (s.leaseLost()) {
            return false;
        }
        BattleNodeInfo info = identity.toBuilder()
                .setAccepting(s.accepting())
                .setRoomCount(Math.max(0, s.roomCount()))
                .setConnectionCount(Math.max(0, s.connectionCount()))
                .build();
        try {
            directory.publish(info, TTL);
            return true;
        } catch (RuntimeException e) {
            log.warn("发布 battle 节点目录失败，下一轮重试 node_id={}", identity.getNodeId(), e);
            return false;
        }
    }

    /**
     * 停止发布（正常停机）。{@code removeEntry = true} 时同时删除目录条目（读方最多滞后 5 s 就看不到本节点）。幂等。
     */
    public synchronized void stop(boolean removeEntry) {
        if (stopped) {
            return;
        }
        stopped = true;
        cancelTask();
        if (removeEntry) {
            try {
                directory.remove();
            } catch (RuntimeException e) {
                log.warn("删除 battle 节点目录条目失败（TTL 到期后自动消失） node_id={}", identity.getNodeId(), e);
            }
        }
    }

    /**
     * 租约丢失时停止发布，并<b>尽力</b>删除条目（battle-node-spec §7.10 第 2 步）：节点号可能已被别的实例占用并写了它自己的条目，
     * 所以只在条目的 {@code instance_id} 仍是本实例时才删。「读 → 比 → 删」不是原子的：极小的窗口里可能删掉新持有者刚写的条目，
     * 它下一轮（≤ 5 s）就会重写，代价只是 match 短暂看不到它。读 / 删失败只打 WARN（条目 TTL 到期后自动消失）。幂等。
     */
    public synchronized void stopAfterLeaseLost() {
        if (stopped) {
            return;
        }
        stopped = true;
        cancelTask();
        try {
            Optional<BattleNodeInfo> current = directory.find();
            if (current.isPresent() && identity.getInstanceId().equals(current.get().getInstanceId())) {
                directory.remove();
                log.info("租约丢失：已删除本实例的 battle 节点目录条目 node_id={}", identity.getNodeId());
            } else if (current.isPresent()) {
                log.info("租约丢失：节点目录条目已属于别的实例，不删 node_id={} 条目实例={}", identity.getNodeId(),
                        current.get().getInstanceId());
            }
        } catch (RuntimeException e) {
            log.warn("租约丢失后删除 battle 节点目录条目失败（TTL 到期后自动消失） node_id={}", identity.getNodeId(), e);
        }
    }

    public synchronized boolean stopped() {
        return stopped;
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel(false);
        }
    }
}
