package com.game.guild.asset;

import com.game.api.proto.AssetBundle;
import com.game.common.deadline.Deadline;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 单测用的资产账本 Store：内存里一张 {@code guild_asset_op}，语义照 {@link JdbcGuildAssetStore} 的接口契约（领取 CAS、令牌重排、
 * {@code status = PENDING} 的终结 CAS、E12 的不覆盖答复列、终结同写 next_attempt_ms = now）。不做对侧账，只把终结回调与失效交给 Listener。
 * 每次调用写进 journal；{@link #failure} 非 null 时下一次写抛它（依赖故障）。线程安全（每行按对象锁）。
 */
public class InMemoryAssetStore implements GuildAssetStore {

    public final Map<Long, GuildAssetOpRow> rows = new ConcurrentHashMap<>();
    public final List<String> journal = Collections.synchronizedList(new ArrayList<>());
    public final List<FinalizedOp> finalized = Collections.synchronizedList(new ArrayList<>());
    /** 非 null 时下一次 reschedule / finalize 抛它（只抛一次）。 */
    public volatile RuntimeException failure;
    /** 领取之后、回读之前插一脚（模拟人工终结抢先 / 换令牌）。 */
    public volatile Consumer<Long> afterClaimCas;
    private final Listener listener;

    public InMemoryAssetStore(Listener listener) {
        this.listener = listener == null ? Listener.NONE : listener;
    }

    public InMemoryAssetStore() {
        this(Listener.NONE);
    }

    /** 插一行 PENDING（测试准备数据）。 */
    public GuildAssetOpRow insert(GuildAssetOpRow row) {
        rows.put(row.getOpId(), row);
        return row;
    }

    /** 一行 PENDING 指令的缺省形状（捐献：流 1、tx 24、一笔货币）。 */
    public static GuildAssetOpRow.Builder pending(long opId, long playerId, long seq, AssetBundle bundle) {
        return GuildAssetOpRow.newBuilder().setOpId(opId).setPlayerId(playerId).setStream(1).setSeq(seq).setGuildId(77)
                .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                .setPayload(bundle.toByteString()).setRefId(1).setRefCount(1).setTxType(24).setStreamEpoch(1_700_000_000_000L)
                .setCreatedMs(1_000).setUpdatedMs(1_000);
    }

    public GuildAssetOpRow row(long opId) {
        return rows.get(opId);
    }

    private RuntimeException takeFailure() {
        RuntimeException e = failure;
        failure = null;
        return e;
    }

    @Override
    public List<Long> listDue(long nowMs, int limit, Deadline deadline) {
        journal.add("listDue");
        List<GuildAssetOpRow> due = new ArrayList<>();
        for (GuildAssetOpRow r : rows.values()) {
            if (r.getStatus() == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING
                    && Long.compareUnsigned(r.getNextAttemptMs(), nowMs) <= 0
                    && Long.compareUnsigned(r.getLeaseUntilMs(), nowMs) < 0) {
                due.add(r);
            }
        }
        due.sort(Comparator.comparingLong(GuildAssetOpRow::getNextAttemptMs).thenComparingLong(GuildAssetOpRow::getOpId));
        return due.stream().limit(Math.max(0, limit)).map(GuildAssetOpRow::getOpId).toList();
    }

    @Override
    public ClaimResult claim(long opId, long nowMs, long leaseUntilMs, long poisonUntilMs, long token, Deadline deadline) {
        journal.add("claim " + opId);
        GuildAssetOpRow r = rows.get(opId);
        synchronized (this) {
            if (r == null || r.getStatus() != GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING
                    || Long.compareUnsigned(r.getLeaseUntilMs(), nowMs) >= 0) {
                return Lost.INSTANCE;
            }
            rows.put(opId, r.toBuilder().setLeaseUntilMs(leaseUntilMs).setLeaseToken(token).setUpdatedMs(nowMs).build());
        }
        Consumer<Long> hook = afterClaimCas;
        if (hook != null) {
            hook.accept(opId);
        }
        r = rows.get(opId);
        if (r.getStatus() != GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING || r.getLeaseToken() != token) {
            return Lost.INSTANCE;
        }
        AssetBundle bundle;
        try {
            bundle = r.getPayload().isEmpty() ? null : AssetBundle.parseFrom(r.getPayload());
        } catch (InvalidProtocolBufferException e) {
            bundle = null;
        }
        if (bundle == null) {
            rows.put(opId, r.toBuilder().setLastOutcome(0).setNextAttemptMs(poisonUntilMs).setLeaseUntilMs(0).setUpdatedMs(nowMs)
                    .build());
            return new Poisoned("op_id=" + opId + ": undecodable");
        }
        return new Claimed(new AssetOp(r.getOpId(), r.getPlayerId(), r.getStream(), r.getSeq(), r.getStreamEpoch(), r.getOpId(),
                r.getTxType(), bundle, r.getAttempts(), r.getDeadlineMs(), token, r.getLastReason()));
    }

    @Override
    public synchronized RescheduleResult reschedule(AssetOp op, long nextAttemptMs, AssetOpResult answer, long nowMs,
                                                    Deadline settle) {
        journal.add("reschedule " + op.opId() + " next=" + nextAttemptMs + " outcome=" + answer.outcomeNumber() + " reason="
                + answer.reason() + " durable=" + answer.durable());
        RuntimeException e = takeFailure();
        if (e != null) {
            throw e;
        }
        GuildAssetOpRow r = rows.get(op.opId());
        if (r == null || r.getStatus() != GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING || r.getLeaseToken() != op.leaseToken()) {
            return RescheduleResult.LEASE_LOST;
        }
        rows.put(op.opId(), r.toBuilder().setAttempts(r.getAttempts() + 1).setNextAttemptMs(nextAttemptMs).setLeaseUntilMs(0)
                .setDurable(answer.durable() ? 1 : 0).setLastOutcome(answer.outcomeNumber()).setLastReason(answer.reason())
                .setUpdatedMs(nowMs).build());
        return RescheduleResult.RESCHEDULED;
    }

    @Override
    public synchronized RescheduleResult rescheduleWithoutAnswer(AssetOp op, long nextAttemptMs, long nowMs, Deadline settle) {
        journal.add("rescheduleWithoutAnswer " + op.opId() + " next=" + nextAttemptMs);
        RuntimeException e = takeFailure();
        if (e != null) {
            throw e;
        }
        GuildAssetOpRow r = rows.get(op.opId());
        if (r == null || r.getStatus() != GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING || r.getLeaseToken() != op.leaseToken()) {
            return RescheduleResult.LEASE_LOST;
        }
        rows.put(op.opId(), r.toBuilder().setAttempts(r.getAttempts() + 1).setNextAttemptMs(nextAttemptMs).setLeaseUntilMs(0)
                .setUpdatedMs(nowMs).build());
        return RescheduleResult.RESCHEDULED;
    }

    @Override
    public FinalizeResult finalizeOp(long opId, AssetOpStatus status, AssetOpResult result, long nowMs, DeliveryOrigin origin,
                                     Deadline settle) {
        journal.add("finalize " + opId + " " + status.label() + " " + origin);
        RuntimeException e = takeFailure();
        if (e != null) {
            throw e;
        }
        GuildAssetOpRow r;
        synchronized (this) {
            r = rows.get(opId);
            if (r == null || r.getStatus() != GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING) {
                return FinalizeResult.NOT_FINALIZED;
            }
            rows.put(opId, r.toBuilder().setStatus(AssetOpStatus.toRecord(status)).setDurable(1)
                    .setLastOutcome(result.outcomeNumber()).setLastReason(result.reason())
                    .setReasonTipId(status == AssetOpStatus.REJECTED ? result.reason() : 0).setLeaseUntilMs(0)
                    .setNextAttemptMs(nowMs).setUpdatedMs(nowMs).build());
        }
        FinalizedOp op = new FinalizedOp(opId, r.getPlayerId(), r.getGuildId(), r.getKind(), status, origin);
        finalized.add(op);
        listener.finalized(op);
        return new FinalizeResult(true, op, Counterparty.NONE);
    }

    @Override
    public FinalizeResult resolveManually(ManualResolution resolution, long nowMs, Deadline deadline) {
        String invalid = resolution.validate();
        if (invalid != null) {
            throw new IllegalArgumentException(invalid);
        }
        journal.add("resolve " + resolution.opId() + " " + resolution.status().label() + " by " + resolution.operator());
        GuildAssetOpRow r;
        synchronized (this) {
            r = rows.get(resolution.opId());
            if (r == null || r.getStatus() != GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING) {
                return FinalizeResult.NOT_FINALIZED;
            }
            rows.put(resolution.opId(), r.toBuilder().setStatus(AssetOpStatus.toRecord(resolution.status()))
                    .setResolvedBy(resolution.operator()).setResolveReason(resolution.reason()).setLeaseUntilMs(0)
                    .setNextAttemptMs(nowMs).setUpdatedMs(nowMs).build());
        }
        FinalizedOp op = new FinalizedOp(resolution.opId(), r.getPlayerId(), r.getGuildId(), r.getKind(), resolution.status(),
                DeliveryOrigin.MANUAL);
        finalized.add(op);
        listener.finalized(op);
        return new FinalizeResult(true, op, Counterparty.NONE);
    }

    @Override
    public OptionalLong oldestPendingCreatedMs(int stream, Deadline deadline) {
        return rows.values().stream()
                .filter(r -> r.getStream() == stream && r.getStatus() == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                .mapToLong(GuildAssetOpRow::getCreatedMs).min();
    }

    @Override
    public Optional<GuildAssetOpRow> getOp(long opId, Deadline deadline) {
        return Optional.ofNullable(rows.get(opId));
    }

    @Override
    public List<GuildAssetOpRow> listStuck(long createdBeforeMs, int limit, Deadline deadline) {
        return rows.values().stream()
                .filter(r -> r.getStatus() == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING
                        && Long.compareUnsigned(r.getCreatedMs(), createdBeforeMs) < 0)
                .sorted(Comparator.comparingLong(GuildAssetOpRow::getCreatedMs).thenComparingLong(GuildAssetOpRow::getOpId))
                .limit(Math.max(0, limit)).toList();
    }

    @Override
    public CleanupReport cleanupOnce(long nowMs, Duration terminalRetention, Duration counterRetention) {
        journal.add("cleanup");
        return new CleanupReport(0, 0);
    }

    @Override
    public AppliedOpsPage listAppliedSince(AppliedOpsQuery query, Deadline deadline) {
        String invalid = query.invalidReason();
        if (invalid != null) {
            throw new IllegalArgumentException(invalid);
        }
        List<GuildAssetOpRow> hits = rows.values().stream()
                .filter(r -> (r.getStatus() == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED
                        || r.getStatus() == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL)
                        && Long.compareUnsigned(r.getNextAttemptMs(), query.sinceMs()) > 0
                        && query.playerIds().contains(r.getPlayerId())
                        && Long.compareUnsigned(r.getOpId(), query.afterOpId()) > 0)
                .sorted((x, y) -> Long.compareUnsigned(x.getOpId(), y.getOpId()))
                .limit(query.limit() + 1L).toList();
        long next = 0;
        if (hits.size() > query.limit()) {
            hits = hits.subList(0, query.limit());
            next = hits.getLast().getOpId();
        }
        return new AppliedOpsPage(hits.stream().map(r -> new AppliedOpBrief(r.getOpId(), r.getPlayerId(), r.getGuildId(),
                r.getStream(), r.getKindValue(), r.getStatusValue(), r.getFundsDelta(), r.getContributionDelta(),
                r.getUpdatedMs())).toList(), next);
    }
}
