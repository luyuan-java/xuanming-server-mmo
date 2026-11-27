package com.game.scene.audit;

import com.game.audit.proto.TransactionLogRecord;
import com.game.player.store.audit.AuditOutboxEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 场景服提交绑定审计发件箱暂存器（PARITY 2026-11-06 + 2026-11-12 R13）：
 * <ul>
 *   <li><b>单一玩家不变量</b>：每条资产流水必须且仅指名一名玩家（{@code from_player} XOR {@code to_player}）；
 *       双玩家或无玩家流水直接拒收并按原因计数（双玩家转移拆成转出/转入两条各自随本方存档提交）；</li>
 *   <li><b>按玩家 8 MiB 暂存上限</b>：单条超过 2 MiB 拒收；超过每人 8 MiB 上限时丢弃最旧记录并计数告警（宁可缺一条，绝不发一条没落库的）；</li>
 *   <li><b>一笔存档带完、不分笔</b>：{@link #captureBatchForSave} 抓取该玩家当前全部到期审计条目（总字节 ≤ 8 MiB，保证含行数据 ≤ 16 MiB），
 *       仅在包含本次改动的存档提交确认（{@link #onSaveCommitted}）后才放行释放；</li>
 *   <li><b>围栏拒绝/夺权/移除丢弃</b>：存盘被围栏拒绝、结果未知、实体移除或失去归属时 {@link #discardPlayer} 丢弃未提交暂存，绝不外发未落库流水。</li>
 * </ul>
 */
public final class PlayerAuditOutboxBuffer {

    private static final Logger log = LoggerFactory.getLogger(PlayerAuditOutboxBuffer.class);

    public static final int MAX_SINGLE_ENTRY_BYTES = AuditOutboxEntry.MAX_SINGLE_ENTRY_BYTES;
    public static final int MAX_PLAYER_BUFFER_BYTES = AuditOutboxEntry.MAX_BATCH_BYTES;

    public enum DropReason {
        NO_SINGLE_PLAYER,
        ENTRY_TOO_LARGE,
        PLAYER_BUFFER_CAP,
        OUTBOX_DISABLED
    }

    private record StagedRecord(long seq, TransactionLogRecord record, int serializedSize) {
    }

    private static final class PlayerQueue {
        final Deque<StagedRecord> entries = new ArrayDeque<>();
        int totalBytes;
    }

    /** 一笔存档请求随附的不可变审计快照（不分笔，含至多 {@code upToSeq} 的全部待提交流水）。 */
    public record SaveAuditBatch(long playerId, long upToSeq, List<TransactionLogRecord> records, int totalBytes) {
        public static final SaveAuditBatch EMPTY = new SaveAuditBatch(0L, 0L, List.of(), 0);

        public boolean isEmpty() {
            return records.isEmpty();
        }
    }

    private final Consumer<TransactionLogRecord> committedSink;
    private final int maxPlayerBytes;
    private final Map<Long, PlayerQueue> queues = new HashMap<>();
    private final Map<DropReason, AtomicLong> drops = new HashMap<>();
    private long nextSeq;
    private boolean enabled = true;

    public PlayerAuditOutboxBuffer(Consumer<TransactionLogRecord> committedSink) {
        this(committedSink, MAX_PLAYER_BUFFER_BYTES);
    }

    public PlayerAuditOutboxBuffer(Consumer<TransactionLogRecord> committedSink, int maxPlayerBytes) {
        this.committedSink = Objects.requireNonNull(committedSink, "committedSink");
        if (maxPlayerBytes <= 0 || maxPlayerBytes > MAX_PLAYER_BUFFER_BYTES) {
            throw new IllegalArgumentException("maxPlayerBytes out of range: " + maxPlayerBytes);
        }
        this.maxPlayerBytes = maxPlayerBytes;
        for (DropReason r : DropReason.values()) {
            drops.put(r, new AtomicLong());
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long dropCount(DropReason reason) {
        return drops.get(reason).get();
    }

    /** 校验并暂存一条流水；返回 true 表示进入该玩家暂存队列，false 表示被拒收并已计数。 */
    public boolean stage(TransactionLogRecord record) {
        if (!enabled) {
            drops.get(DropReason.OUTBOX_DISABLED).incrementAndGet();
            return false;
        }
        if (record == null) {
            drops.get(DropReason.NO_SINGLE_PLAYER).incrementAndGet();
            return false;
        }
        long from = record.getFromPlayer();
        long to = record.getToPlayer();
        // 单一玩家不变量：恰好只有一侧非 0
        if ((from == 0L) == (to == 0L)) {
            drops.get(DropReason.NO_SINGLE_PLAYER).incrementAndGet();
            log.warn("audit_outbox_rejected reason=no_single_player from={} to={}",
                    Long.toUnsignedString(from), Long.toUnsignedString(to));
            return false;
        }
        long playerId = from != 0L ? from : to;
        int size = record.getSerializedSize();
        if (size <= 0 || size > MAX_SINGLE_ENTRY_BYTES || size > maxPlayerBytes) {
            drops.get(DropReason.ENTRY_TOO_LARGE).incrementAndGet();
            log.error("audit_outbox_rejected reason=entry_too_large player={} size={}",
                    Long.toUnsignedString(playerId), size);
            return false;
        }
        PlayerQueue q = queues.computeIfAbsent(playerId, k -> new PlayerQueue());
        while (q.totalBytes + size > maxPlayerBytes && !q.entries.isEmpty()) {
            StagedRecord evicted = q.entries.removeFirst();
            q.totalBytes -= evicted.serializedSize();
            drops.get(DropReason.PLAYER_BUFFER_CAP).incrementAndGet();
            log.error("audit_outbox_evicted_oldest reason=player_buffer_cap player={} evicted_seq={} remaining_bytes={}",
                    Long.toUnsignedString(playerId), evicted.seq(), q.totalBytes);
        }
        q.entries.addLast(new StagedRecord(++nextSeq, record, size));
        q.totalBytes += size;
        return true;
    }

    /** 抓取该玩家当前全部未提交审计记录随同一笔存档请求发送（不分笔）。 */
    public SaveAuditBatch captureBatchForSave(long playerId) {
        PlayerQueue q = queues.get(playerId);
        if (q == null || q.entries.isEmpty()) {
            return SaveAuditBatch.EMPTY;
        }
        List<TransactionLogRecord> copy = new ArrayList<>(q.entries.size());
        long lastSeq = 0L;
        for (StagedRecord s : q.entries) {
            copy.add(s.record());
            lastSeq = s.seq();
        }
        return new SaveAuditBatch(playerId, lastSeq, List.copyOf(copy), q.totalBytes);
    }

    /**
     * 存档已确认提交（或确认快照等价无需再写）时放行并移除至 {@code upToSeq} 的全部暂存流水。
     * 返回本次放行条数。
     */
    public int onSaveCommitted(long playerId, long upToSeq) {
        PlayerQueue q = queues.get(playerId);
        if (q == null || q.entries.isEmpty() || upToSeq <= 0L) {
            return 0;
        }
        int released = 0;
        while (!q.entries.isEmpty() && q.entries.peekFirst().seq() <= upToSeq) {
            StagedRecord s = q.entries.removeFirst();
            q.totalBytes -= s.serializedSize();
            committedSink.accept(s.record());
            released++;
        }
        if (q.entries.isEmpty()) {
            queues.remove(playerId);
        }
        return released;
    }

    /** 当前全部已暂存条目（含无待写快照等价放行用）。 */
    public int flushAllCommitted(long playerId) {
        PlayerQueue q = queues.get(playerId);
        if (q == null || q.entries.isEmpty()) {
            return 0;
        }
        return onSaveCommitted(playerId, q.entries.peekLast().seq());
    }

    /** 存档被围栏拒绝、实体移除或被夺权时丢弃全部未提交暂存（绝不外发未落库流水）。 */
    public int discardPlayer(long playerId) {
        PlayerQueue removed = queues.remove(playerId);
        return removed == null ? 0 : removed.entries.size();
    }

    public int pendingCount(long playerId) {
        PlayerQueue q = queues.get(playerId);
        return q == null ? 0 : q.entries.size();
    }

    public int pendingBytes(long playerId) {
        PlayerQueue q = queues.get(playerId);
        return q == null ? 0 : q.totalBytes;
    }
}
