package com.game.trade.commerce;

import com.game.api.proto.AssetOutcome;
import com.game.common.deadline.Deadline;
import com.game.trade.store.pb.TradeAssetOpRow;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 交易卡死资产指令（{@code trade_asset_op}）人工终结服务（对齐主仓 {@code go/trade/cmd/assetopfix} 与帮会 {@code AssetOpFixMain}）：
 * <ul>
 *   <li>{@link #listStuck(long, int, Deadline)}：列出早于 {@code createdBeforeMs} 的未决行（按 {@code created_ms, op_id} 升序）；</li>
 *   <li>{@link #resolve(long, int, String, String, Deadline)}：前置硬门禁（{@code status == 1 PENDING}、{@code attempts >= 10}、
 *       {@code last_outcome == UNKNOWN}），仅允许终结为 {@code APPLIED(2)/REJECTED(3)/ABORTED(4)}，单事务 CAS 落库并记录操作人与原因。</li>
 * </ul>
 */
public final class TradeAssetOpFixService {

    private static final Logger log = LoggerFactory.getLogger(TradeAssetOpFixService.class);

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_APPLIED = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_ABORTED = 4;
    public static final int MIN_ATTEMPTS_FOR_MANUAL_RESOLVE = 10;

    public enum Outcome {
        RESOLVED,
        NOT_FOUND,
        NOT_PENDING,
        PRECONDITION_FAILED
    }

    public record Resolution(Outcome outcome, TradeAssetOpRow before, int targetStatus, String detail) {
    }

    private final CommerceStore store;
    private final LongSupplier clock;

    public TradeAssetOpFixService(CommerceStore store, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 列出创建时间早于 {@code createdBeforeMs} 的 PENDING 交易资产待办，至多 {@code limit} 条。 */
    public List<TradeAssetOpRow> listStuck(long createdBeforeMs, int limit, Deadline budget) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        return store.ops("status=1 AND created_ms<? ORDER BY created_ms ASC, op_id ASC LIMIT ?",
                createdBeforeMs, Math.min(limit, 500));
    }

    /** 人工终结卡在 UNKNOWN 且尝试次数 ≥ 10 的交易资产待办。 */
    public Resolution resolve(long opId, int targetStatus, String operator, String reason, Deadline budget) throws SQLException {
        if (opId == 0L) {
            throw new IllegalArgumentException("op_id 不能为 0");
        }
        if (targetStatus != STATUS_APPLIED && targetStatus != STATUS_REJECTED && targetStatus != STATUS_ABORTED) {
            throw new IllegalArgumentException("仅允许终结为 APPLIED(2)/REJECTED(3)/ABORTED(4): " + targetStatus);
        }
        if (operator == null || operator.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("operator 与 reason 必填");
        }
        long now = clock.getAsLong();
        return store.transaction(budget, c -> {
            List<TradeAssetOpRow> rows = store.ops(c, "op_id=? FOR UPDATE", opId);
            if (rows.isEmpty()) {
                return new Resolution(Outcome.NOT_FOUND, null, targetStatus, "op_id 不存在");
            }
            TradeAssetOpRow row = rows.getFirst();
            if (row.getStatus() != STATUS_PENDING) {
                return new Resolution(Outcome.NOT_PENDING, row, targetStatus, "当前状态非 PENDING: " + row.getStatus());
            }
            if (Integer.compareUnsigned(row.getAttempts(), MIN_ATTEMPTS_FOR_MANUAL_RESOLVE) < 0
                    || row.getLastOutcome() != AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE) {
                return new Resolution(Outcome.PRECONDITION_FAILED, row, targetStatus,
                        "不满足前置条件（须 attempts>=10 且 last_outcome=UNKNOWN）");
            }
            int updated = CommerceStore.execute(c,
                    "UPDATE trade_asset_op SET status=?,durable=?,lease_until_ms=0,next_attempt_ms=0,"
                            + "resolved_by=?,resolve_reason=?,updated_ms=? WHERE op_id=? AND status=1",
                    targetStatus, targetStatus == STATUS_APPLIED ? 1 : 0,
                    operator.strip(), reason.strip(), now, opId);
            if (updated != 1) {
                return new Resolution(Outcome.NOT_PENDING, row, targetStatus, "并发已被终结");
            }
            log.warn("trade_asset_op_manual_resolved op_id={} player={} from_status=1 to_status={} operator={} reason={}",
                    Long.toUnsignedString(opId), Long.toUnsignedString(row.getPlayerId()),
                    targetStatus, operator.strip(), reason.strip());
            return new Resolution(Outcome.RESOLVED, row, targetStatus, "ok");
        });
    }
}
