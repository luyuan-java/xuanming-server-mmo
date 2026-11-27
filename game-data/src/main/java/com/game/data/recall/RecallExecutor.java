package com.game.data.recall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.audit.proto.AssetKind;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.OpsRequests;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.ops.pb.RecallSourceRow;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogQuery;
import com.game.pbmysql.PbMysql;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量回收执行与孤儿角色摘除（L958，批次 7.2c）。
 *
 * <ul>
 *   <li>先预览后执行：强校验 {@code planDigest} 与 {@link RecallPlanner#planDigest} 一致；</li>
 *   <li>空回收计划回 HTTP 422 {@code empty_recall_plan}（业务错误码 37，不回 0）；</li>
 *   <li>每条源流水受 {@code recall_source(tx_id)} 主键去重，只回收一次；确定未回收到的源流水不落 {@code recall_source}（放回可重试）；</li>
 *   <li>离线围栏：仅修改 {@code owner_released = 1} 的角色，并推进 {@code owner_epoch} / {@code saved_epoch}；</li>
 *   <li>不可叠加物品按流水点名的 {@code item_uuid} 精确销毁，余额/堆叠不足按实际持有夹紧并记录缺口或欠款。</li>
 * </ul>
 */
public final class RecallExecutor {

    private static final Logger audit = LoggerFactory.getLogger("game.audit.ops");
    /** 空计划业务错误码（对齐 mmorpg recall_logic 约定：空计划回 37，不回 0）。 */
    public static final int EMPTY_PLAN_ERROR_CODE = 37;

    private final DataSource dataSource;
    private final TransactionLogQueryService txlog;
    private final OpsJobStore jobs;
    private final OpsIds ids;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final int maxRows;
    private final String runner;

    public RecallExecutor(DataSource dataSource, TransactionLogQueryService txlog, OpsJobStore jobs, OpsIds ids,
                          TransactionTemplate tx, ObjectMapper json, Clock clock, int maxRows, String runner) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.txlog = Objects.requireNonNull(txlog);
        this.jobs = Objects.requireNonNull(jobs);
        this.ids = Objects.requireNonNull(ids);
        this.tx = Objects.requireNonNull(tx);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
        this.maxRows = maxRows;
        this.runner = Objects.requireNonNull(runner);
    }

    /** 执行已预览的批量回收计划。 */
    public Map<String, Object> execute(RecallPlanner.Plan plan, String expectedPlanDigest, String shortfallMode,
                                       String operator) {
        String actualDigest = RecallPlanner.planDigest(plan);
        if (expectedPlanDigest == null || expectedPlanDigest.isBlank()
                || !actualDigest.equalsIgnoreCase(expectedPlanDigest.trim())) {
            throw new OpsException(HttpStatus.BAD_REQUEST, OpsException.PLAN_DIGEST_MISMATCH,
                    "planDigest 缺失或与当前预览计划不一致，请先 dryRun 再提交执行",
                    Map.of("expectedPlanDigest", actualDigest), null);
        }
        String mode = shortfallMode == null ? "report" : shortfallMode;
        List<TransactionLogEntry> rows = scanMatchingRows(plan);
        Set<Long> alreadyRecalled = rows.isEmpty() ? Set.of() : jobs.recalledAmong(txIdsOf(rows));

        Map<Long, List<TransactionLogEntry>> eligibleByPlayer = new TreeMap<>(Long::compareUnsigned);
        Set<String> seenCorrelations = new HashSet<>();
        for (TransactionLogEntry e : rows) {
            if (alreadyRecalled.contains(e.getTxId())) {
                continue;
            }
            if (e.getCorrelationId() != 0L) {
                String key = Long.toUnsignedString(e.getToPlayer()) + ":" + Long.toUnsignedString(e.getCorrelationId());
                if (!seenCorrelations.add(key)) {
                    continue;
                }
            }
            if (quantity(plan, e) <= 0L) {
                continue;
            }
            eligibleByPlayer.computeIfAbsent(e.getToPlayer(), ignored -> new ArrayList<>()).add(e);
        }
        if (eligibleByPlayer.isEmpty()) {
            throw new OpsException(HttpStatus.UNPROCESSABLE_ENTITY, OpsException.EMPTY_RECALL_PLAN,
                    "没有可回收的未处理源流水（空计划，错误码 37）",
                    Map.of("errorCode", EMPTY_PLAN_ERROR_CODE, "planDigest", actualDigest), null);
        }

        OptionalLong nextId = ids.tryNext();
        if (nextId.isEmpty()) {
            throw new OpsException(HttpStatus.SERVICE_UNAVAILABLE, OpsException.ID_UNAVAILABLE, "运维发号器暂不可用");
        }
        long jobId = nextId.getAsLong();
        long now = clock.millis();

        return tx.execute(status -> {
            Connection conn = DataSourceUtils.getConnection(dataSource);
            try {
                int affected = 0;
                int failed = 0;
                long totalRecovered = 0L;
                long totalShortfall = 0L;
                List<Map<String, Object>> playerSummaries = new ArrayList<>();

                for (Map.Entry<Long, List<TransactionLogEntry>> entry : eligibleByPlayer.entrySet()) {
                    long playerId = entry.getKey();
                    PlayerOutcome outcome = applyToOfflinePlayer(conn, jobId, plan, playerId, entry.getValue(), mode, now);
                    if (outcome.affected()) {
                        affected++;
                    } else if (outcome.failed()) {
                        failed++;
                    }
                    totalRecovered += outcome.recovered();
                    totalShortfall += outcome.shortfall();

                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("player", Long.toUnsignedString(playerId));
                    summary.put("outcome", outcome.status());
                    summary.put("owed", outcome.owed());
                    summary.put("recovered", outcome.recovered());
                    summary.put("shortfall", outcome.shortfall());
                    if ("debt".equals(mode) && outcome.shortfall() > 0L) {
                        summary.put("debtRecorded", outcome.shortfall());
                    }
                    summary.put("recordedSources", outcome.recordedSources());
                    summary.put("releasedSources", outcome.releasedSources());
                    playerSummaries.add(summary);

                    jobs.insertPlayer(OpsJobPlayerRow.newBuilder()
                            .setJobId(jobId)
                            .setPlayerId(playerId)
                            .setClaimedEpoch(outcome.newEpoch())
                            .setOutcome(outcome.status())
                            .setDetailJson(toJson(summary))
                            .setWrittenMs(now)
                            .build());
                }

                OpsJobStatus jobStatus = failed == 0 ? OpsJobStatus.OPS_JOB_SUCCEEDED
                        : (affected > 0 ? OpsJobStatus.OPS_JOB_PARTIAL : OpsJobStatus.OPS_JOB_FAILED);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("dryRun", false);
                result.put("jobId", Long.toUnsignedString(jobId));
                result.put("planDigest", actualDigest);
                result.put("status", jobStatus.name());
                result.put("playersPlanned", eligibleByPlayer.size());
                result.put("playersAffected", affected);
                result.put("playersFailed", failed);
                result.put("totalRecovered", totalRecovered);
                result.put("totalShortfall", totalShortfall);
                result.put("players", playerSummaries);

                String summaryJson = toJson(result);
                jobs.insertJob(OpsJobRow.newBuilder()
                        .setJobId(jobId)
                        .setIdemKey("recall:" + Long.toUnsignedString(jobId))
                        .setKind(OpsJobKind.OPS_JOB_RECALL)
                        .setStatus(jobStatus)
                        .setRequestJson(toJson(Map.of("dryRun", false, "planDigest", actualDigest, "plan", plan.canonical())))
                        .setRequestHash(OpsRequests.requestHash("POST", "/admin/recalls", plan.canonical()))
                        .setOperator(operator)
                        .setReason(plan.reason() == null ? "" : plan.reason())
                        .setResultCode(jobStatus == OpsJobStatus.OPS_JOB_SUCCEEDED ? "ok" : "partial")
                        .setPlayersPlanned(eligibleByPlayer.size())
                        .setPlayersAffected(affected)
                        .setPlayersFailed(failed)
                        .setRunner(runner)
                        .setCreatedMs(now)
                        .setStartedMs(now)
                        .setFinishedMs(now)
                        .setSummaryJson(summaryJson)
                        .build());
                jobs.insertEvent(OpsJobEventRow.newBuilder()
                        .setJobId(jobId)
                        .setSeq(1)
                        .setType(OpsJobEventType.OPS_JOB_EVENT_RESULT)
                        .setPayloadJson(summaryJson)
                        .setAtMs(now)
                        .build());
                audit.info("recall executed job={} operator={} planned={} affected={} failed={} recovered={} shortfall={}",
                        Long.toUnsignedString(jobId), operator, eligibleByPlayer.size(), affected, failed,
                        totalRecovered, totalShortfall);
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException("执行批量回收写入数据库失败: " + e.getMessage(), e);
            } finally {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        });
    }

    /**
     * 摘孤儿角色（L958）：仅摘除路由残留且 player_store 无写权行/无存档行的离线孤儿角色。
     * 仍在在线持有或已落盘有效玩家状态的角色一律拒绝（HTTP 409 player_not_orphan）。
     */
    public Map<String, Object> detachOrphanPlayer(long playerId, String operator, String reason) {
        if (playerId == 0L) {
            throw OpsException.badRequest("playerId 不能为 0");
        }
        long now = clock.millis();
        return tx.execute(status -> {
            Connection conn = DataSourceUtils.getConnection(dataSource);
            try {
                boolean hasPlayerRow = false;
                boolean ownerReleased = false;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT owner_released FROM player WHERE player_id = ? FOR UPDATE")) {
                    ps.setObject(1, PbMysql.uint64(playerId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            hasPlayerRow = true;
                            ownerReleased = rs.getInt(1) == 1;
                        }
                    }
                }
                boolean hasStateRow;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM player_state WHERE player_id = ?")) {
                    ps.setObject(1, PbMysql.uint64(playerId));
                    try (ResultSet rs = ps.executeQuery()) {
                        hasStateRow = rs.next() && rs.getLong(1) > 0;
                    }
                }
                if (hasPlayerRow && (!ownerReleased || hasStateRow)) {
                    throw new OpsException(HttpStatus.CONFLICT, OpsException.PLAYER_NOT_ORPHAN,
                            "角色仍有有效状态或未释放写权，不属于孤儿角色，拒绝摘除",
                            Map.of("player", Long.toUnsignedString(playerId), "ownerReleased", ownerReleased,
                                    "hasStateRow", hasStateRow), null);
                }
                int deletedPlayer = 0;
                if (hasPlayerRow) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM player WHERE player_id = ? AND owner_released = 1")) {
                        ps.setObject(1, PbMysql.uint64(playerId));
                        deletedPlayer = ps.executeUpdate();
                    }
                }
                audit.info("orphan player detached player={} hadPlayerRow={} deleted={} operator={} reason={}",
                        Long.toUnsignedString(playerId), hasPlayerRow, deletedPlayer, operator,
                        reason == null ? "" : reason);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("player", Long.toUnsignedString(playerId));
                out.put("detached", true);
                out.put("hadPlayerRow", hasPlayerRow);
                out.put("deletedPlayerRows", deletedPlayer);
                out.put("operator", operator);
                out.put("atMs", now);
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException("摘孤儿角色数据库操作失败: " + e.getMessage(), e);
            } finally {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        });
    }

    private record PlayerOutcome(String status, boolean affected, boolean failed, long owed, long recovered,
                                 long shortfall, long newEpoch, int recordedSources, int releasedSources) {}

    private PlayerOutcome applyToOfflinePlayer(Connection conn, long jobId, RecallPlanner.Plan plan, long playerId,
                                               List<TransactionLogEntry> txs, String shortfallMode, long now)
            throws SQLException {
        long owed = 0L;
        for (TransactionLogEntry e : txs) {
            owed += quantity(plan, e);
        }
        Long ownerEpoch = null;
        boolean ownerReleased = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT owner_epoch, owner_released FROM player WHERE player_id = ? FOR UPDATE")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ownerEpoch = rs.getLong(1);
                    ownerReleased = rs.getInt(2) == 1;
                }
            }
        }
        if (ownerEpoch == null) {
            return new PlayerOutcome("PLAYER_NOT_FOUND", false, true, owed, 0L, owed, 0L, 0, txs.size());
        }
        if (!ownerReleased) {
            // 在线角色未释放写权：全部源流水放回，待下线或踢线后重试。
            return new PlayerOutcome("ONLINE_BLOCKED", false, true, owed, 0L, owed, ownerEpoch, 0, txs.size());
        }
        byte[] stateBytes = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT data FROM player_state WHERE player_id = ? FOR UPDATE")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stateBytes = rs.getBytes(1);
                }
            }
        }
        if (stateBytes == null) {
            return new PlayerOutcome("STATE_NOT_FOUND", false, true, owed, 0L, owed, ownerEpoch, 0, txs.size());
        }
        PlayerState state;
        try {
            state = PlayerState.parseFrom(stateBytes);
        } catch (InvalidProtocolBufferException e) {
            return new PlayerOutcome("STATE_INVALID", false, true, owed, 0L, owed, ownerEpoch, 0, txs.size());
        }

        PlayerState.Builder updated = state.toBuilder();
        long totalRecovered = 0L;
        int recordedSources = 0;
        int releasedSources = 0;
        long assetId = plan.kind() == AssetKind.ASSET_CURRENCY_VALUE
                ? Integer.toUnsignedLong(plan.currencyType())
                : Integer.toUnsignedLong(plan.itemConfigId());

        if (plan.kind() == AssetKind.ASSET_CURRENCY_VALUE) {
            int currencyType = plan.currencyType();
            CurrencyState.Builder cur = updated.getCurrencyBuilder();
            long held = Integer.compareUnsigned(currencyType, cur.getBalancesCount()) < 0
                    ? cur.getBalances(currencyType) : 0L;
            long remaining = held;
            for (TransactionLogEntry e : txs) {
                long q = quantity(plan, e);
                long take = Math.min(remaining, q);
                remaining -= take;
                totalRecovered += take;
                if (take > 0L) {
                    jobs.insertRecallSource(RecallSourceRow.newBuilder()
                            .setTxId(e.getTxId()).setJobId(jobId).setPlayerId(playerId)
                            .setKind(plan.kind()).setAssetId(assetId)
                            .setMatchedQty(q).setRecoveredQty(take).setAtMs(now).build());
                    recordedSources++;
                } else {
                    releasedSources++;
                }
            }
            if (totalRecovered > 0L) {
                cur.setBalances(currencyType, remaining);
            }
        } else {
            int configId = plan.itemConfigId();
            List<BagItemState.Builder> items = new ArrayList<>();
            for (BagItemState item : updated.getBag().getItemsList()) {
                items.add(item.toBuilder());
            }
            for (TransactionLogEntry e : txs) {
                long q = quantity(plan, e);
                long take = deductItem(items, configId, e.getItemUuid(), q);
                totalRecovered += take;
                if (take > 0L) {
                    jobs.insertRecallSource(RecallSourceRow.newBuilder()
                            .setTxId(e.getTxId()).setJobId(jobId).setPlayerId(playerId)
                            .setKind(plan.kind()).setAssetId(assetId)
                            .setMatchedQty(q).setRecoveredQty(take).setAtMs(now).build());
                    recordedSources++;
                } else {
                    releasedSources++;
                }
            }
            if (totalRecovered > 0L) {
                BagState.Builder bag = updated.getBagBuilder().clearItems();
                for (BagItemState.Builder item : items) {
                    if (item.getStackSize() > 0) {
                        bag.addItems(item.build());
                    }
                }
            }
        }

        long newEpoch = ownerEpoch;
        if (totalRecovered > 0L) {
            newEpoch = ownerEpoch + 1L;
            byte[] nextBytes = updated.build().toByteArray();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE player SET owner_epoch = ?, updated_at = ? WHERE player_id = ? AND owner_epoch = ? AND owner_released = 1")) {
                ps.setLong(1, newEpoch);
                ps.setLong(2, now);
                ps.setObject(3, PbMysql.uint64(playerId));
                ps.setLong(4, ownerEpoch);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("玩家写权围栏 CAS 失败 player=" + Long.toUnsignedString(playerId));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE player_state SET data = ?, saved_epoch = ?, updated_at = ? WHERE player_id = ?")) {
                ps.setBytes(1, nextBytes);
                ps.setLong(2, newEpoch);
                ps.setLong(3, now);
                ps.setObject(4, PbMysql.uint64(playerId));
                ps.executeUpdate();
            }
        }

        long shortfall = Math.max(0L, owed - totalRecovered);
        String outcomeStatus;
        if (totalRecovered == owed) {
            outcomeStatus = "RECOVERED";
        } else if (totalRecovered > 0L) {
            outcomeStatus = "debt".equals(shortfallMode) ? "PARTIAL_DEBT" : "PARTIAL_SHORTFALL";
        } else {
            outcomeStatus = "debt".equals(shortfallMode) ? "DEBT_ONLY" : "NOTHING_HELD";
        }
        return new PlayerOutcome(outcomeStatus, totalRecovered > 0L, false, owed, totalRecovered, shortfall, newEpoch,
                recordedSources, releasedSources);
    }

    /**
     * 物品扣减：当源流水带具体 itemUuid 且数量为 1 时优先只销毁该点名实例（不可叠加实例保护）；
     * 若目标为堆叠物品（或未点名 UUID），再从同 configId 堆叠格扣减。
     */
    private static long deductItem(List<BagItemState.Builder> items, int configId, long itemUuid, long targetQty) {
        if (targetQty <= 0L) {
            return 0L;
        }
        long taken = 0L;
        if (itemUuid != 0L) {
            for (BagItemState.Builder b : items) {
                if (b.getItemUuid() == itemUuid && b.getConfigId() == configId && b.getStackSize() > 0) {
                    long have = Integer.toUnsignedLong(b.getStackSize());
                    long cut = Math.min(have, targetQty - taken);
                    b.setStackSize((int) (have - cut));
                    taken += cut;
                    if (taken >= targetQty) {
                        return taken;
                    }
                }
            }
            // 点名具体不可叠加实例（targetQty == 1）且背包中无该实例时，绝不误删玩家合法获得的同名其他实例。
            if (targetQty == 1L) {
                return taken;
            }
        }
        for (BagItemState.Builder b : items) {
            if (b.getConfigId() == configId && b.getStackSize() > 0) {
                long have = Integer.toUnsignedLong(b.getStackSize());
                long cut = Math.min(have, targetQty - taken);
                b.setStackSize((int) (have - cut));
                taken += cut;
                if (taken >= targetQty) {
                    break;
                }
            }
        }
        return taken;
    }

    private List<TransactionLogEntry> scanMatchingRows(RecallPlanner.Plan plan) {
        TransactionLogQuery base = TransactionLogQuery.builder()
                .kind(plan.kind())
                .currencyType(plan.currencyType())
                .itemConfigId(plan.itemConfigId())
                .acquisitionsOnly(true)
                .reasons(plan.reasons())
                .window(plan.sinceMs(), plan.untilMs())
                .build();
        Map<Long, TransactionLogEntry> matched = new LinkedHashMap<>();
        if (plan.players().isEmpty()) {
            txlog.scan(base, maxRows).forEach(e -> matched.putIfAbsent(e.getTxId(), e));
        } else {
            for (long player : plan.players()) {
                int budget = maxRows - matched.size();
                for (TransactionLogEntry e : txlog.scan(base.toBuilder().toPlayer(player).build(), budget)) {
                    matched.putIfAbsent(e.getTxId(), e);
                }
                if (matched.size() > maxRows) {
                    break;
                }
            }
        }
        if (matched.size() > maxRows) {
            throw new OpsException(HttpStatus.UNPROCESSABLE_ENTITY, OpsException.RESULT_TRUNCATED,
                    "匹配的源流水超过 " + maxRows + " 行：缩小时间窗或指定玩家后重试（零变更）",
                    Map.of("truncated", true, "maxRows", maxRows), null);
        }
        List<TransactionLogEntry> rows = new ArrayList<>(matched.values());
        rows.sort(TransactionLogQueryService.ORDER);
        return rows;
    }

    private static Set<Long> txIdsOf(List<TransactionLogEntry> rows) {
        Set<Long> out = new HashSet<>(rows.size());
        for (TransactionLogEntry e : rows) {
            out.add(e.getTxId());
        }
        return out;
    }

    private static long quantity(RecallPlanner.Plan plan, TransactionLogEntry e) {
        if (plan.kind() == AssetKind.ASSET_ITEM_VALUE) {
            return Integer.toUnsignedLong(e.getItemQuantity());
        }
        return Math.max(0L, e.getCurrencyDelta());
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化回收审计 JSON 失败", e);
        }
    }
}
