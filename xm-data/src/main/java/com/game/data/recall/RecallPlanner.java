package com.game.data.recall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.audit.proto.AssetKind;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogQuery;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.InvalidProtocolBufferException;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量回收的计划与 dry-run（data-ops-spec §5.2–§5.4；对应 108 的基线可用面）。真正执行随批次 7.2c（离线编辑、去重、落库完整性闸）。
 *
 * <ul>
 *   <li><b>只匹配获得行</b>：{@code to_player <> 0}，物品行，或货币行且 {@code currency_delta > 0}——基线把扣减行也选进来、数量记 0（H12）；</li>
 *   <li>金币（币种 0）可以作目标（基线 B2：入参校验与 QueryLog 都把 0 当「没给」）；</li>
 *   <li>指定玩家时逐人走 {@code idx_txlog_to}；全服走 {@code idx_txlog_item} / {@code idx_txlog_currency}；原因在已缩小的范围里过滤；</li>
 *   <li>同一 tx_id 只计一次；{@code recall_source} 里已有的源流水标 {@code alreadyRecalled}、不计入应回收量（7.2c 起才会有行）；</li>
 *   <li><b>截断</b>：游标扫到 {@code max-rows + 1} 行即判截断 → 422 {@code result_truncated}、零变更，作业审计照写
 *       （dry-run 也写，同基线 recall_logic.go:137-160）；不做热表 {@code COUNT(*)}；</li>
 *   <li>按玩家汇总应回收量，并按已落盘状态估算可回收量与缺口（只是估算：计划与执行之间余额会变，9.2 第 11 条）。</li>
 * </ul>
 * 只读（除截断时的一条审计作业行），线程安全。
 */
public final class RecallPlanner {

    private static final Logger audit = LoggerFactory.getLogger("xm.audit.ops");

    public static final int MAX_PLAYERS = 1000;
    /** 估算现有量的玩家数上限（全服查询可能命中很多人）。 */
    static final int MAX_ESTIMATES = 1000;

    /** 已校验的回收计划参数。 */
    public record Plan(List<Long> players, int kind, Integer currencyType, Integer itemConfigId, long sinceMs, long untilMs,
                       List<Integer> reasons, String reason) {

        String canonical() {
            return "players=" + players + "\nkind=" + kind + "\ncurrencyType=" + currencyType + "\nitemConfigId="
                    + itemConfigId + "\nsinceMs=" + sinceMs + "\nuntilMs=" + untilMs + "\nreasons=" + reasons;
        }
    }

    private final TransactionLogQueryService txlog;
    private final PersistedPlayerMapper players;
    private final OpsJobStore jobs;
    private final OpsIds ids;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final int maxRows;
    private final String runner;

    public RecallPlanner(TransactionLogQueryService txlog, PersistedPlayerMapper players, OpsJobStore jobs, OpsIds ids,
                         TransactionTemplate tx, ObjectMapper json, Clock clock, int maxRows, String runner) {
        this.txlog = txlog;
        this.players = players;
        this.jobs = jobs;
        this.ids = ids;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.maxRows = maxRows;
        this.runner = runner;
    }

    /**
     * 校验请求、组成计划（§5.2 的筛选规则）。
     *
     * @param txlogRetention 流水保留期（0 = 永久）：{@code sinceMs} 不得早于「现在 − 保留期」，已清掉的流水会让集合不完整
     * @param maxWindow      不指定玩家（全服）时的时间窗上限
     */
    public static Plan plan(List<Long> playerIds, String kind, Long currencyType, Long itemConfigId, Long sinceMs,
                            Long untilMs, List<Integer> reasons, String reason, long nowMs, Duration txlogRetention,
                            Duration maxWindow) {
        int k = switch (kind == null ? "" : kind) {
            case "currency" -> AssetKind.ASSET_CURRENCY_VALUE;
            case "item" -> AssetKind.ASSET_ITEM_VALUE;
            default -> throw OpsException.badRequest("kind 必填：currency / item");
        };
        Integer ct = null;
        Integer ic = null;
        if (k == AssetKind.ASSET_CURRENCY_VALUE) {
            if (currencyType == null || itemConfigId != null) {
                throw OpsException.badRequest("kind=currency 时 currencyType 必填（0 = 金币，有效）、不能给 itemConfigId");
            }
            ct = u32("currencyType", currencyType);
        } else {
            if (itemConfigId == null || itemConfigId == 0 || currencyType != null) {
                throw OpsException.badRequest("kind=item 时 itemConfigId 必填且非 0、不能给 currencyType");
            }
            ic = u32("itemConfigId", itemConfigId);
        }
        if (sinceMs == null || untilMs == null || sinceMs >= untilMs) {
            throw OpsException.badRequest("sinceMs / untilMs 必填，半开窗口 [sinceMs, untilMs)");
        }
        if (!txlogRetention.isZero() && sinceMs < nowMs - txlogRetention.toMillis()) {
            throw OpsException.badRequest("sinceMs 早于流水保留期（" + txlogRetention + "）：已清掉的流水会让回收集合不完整");
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(playerIds == null ? List.of() : playerIds));
        if (distinct.size() > MAX_PLAYERS) {
            throw OpsException.badRequest("players 至多 " + MAX_PLAYERS + " 个");
        }
        if (distinct.contains(0L)) {
            throw OpsException.badRequest("players 里不能有 0");
        }
        // 窗口宽度按溢出安全的方式算：untilMs − sinceMs 溢出（如 sinceMs = Long.MIN_VALUE）也算超上限，否则全服回收能绕过窗口上限
        if (distinct.isEmpty() && TransactionLogQueryService.windowExceeds(sinceMs, untilMs, maxWindow)) {
            throw OpsException.badRequest("不指定玩家（全服）时时间窗不超过 " + maxWindow.toHours() + " 小时");
        }
        List<Integer> rs = new ArrayList<>(new LinkedHashSet<>(reasons == null ? List.of() : reasons));
        if (rs.stream().anyMatch(r -> r == null || r <= 0)) {
            throw OpsException.badRequest("reasons 里的原因必须是正整数");
        }
        return new Plan(List.copyOf(distinct), k, ct, ic, sinceMs, untilMs, List.copyOf(rs), reason);
    }

    private static int u32(String name, long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw OpsException.badRequest(name + " 必须是 0–4294967295");
        }
        return (int) value;
    }

    /** dry-run：逐行列出匹配的源流水、按玩家汇总应回收 / 可回收 / 缺口。截断时 422（并写一条审计作业）。 */
    public Map<String, Object> dryRun(Plan plan, String operator) {
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
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("truncated", true);
            details.put("maxRows", maxRows);
            OptionalLong jobId = auditTruncated(plan, operator);
            details.put("audited", jobId.isPresent());
            jobId.ifPresent(id -> details.put("jobId", Long.toUnsignedString(id)));
            throw new OpsException(HttpStatus.UNPROCESSABLE_ENTITY, OpsException.RESULT_TRUNCATED,
                    "匹配的源流水超过 " + maxRows + " 行：缩小时间窗或指定玩家后重试（零变更）", details, null);
        }
        List<TransactionLogEntry> rows = new ArrayList<>(matched.values());
        rows.sort(TransactionLogQueryService.ORDER);
        Set<Long> recalled = rows.isEmpty() ? Set.of() : jobs.recalledAmong(matched.keySet());

        Map<Long, BigInteger> owed = new TreeMap<>(Long::compareUnsigned);
        List<Map<String, Object>> rowViews = new ArrayList<>();
        for (TransactionLogEntry e : rows) {
            BigInteger qty = quantity(plan, e);
            boolean already = recalled.contains(e.getTxId());
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("txId", Long.toUnsignedString(e.getTxId()));
            v.put("player", Long.toUnsignedString(e.getToPlayer()));
            v.put("timeMs", e.getTimeMs());
            v.put("reason", Integer.toUnsignedLong(e.getReason()));
            v.put("itemUuid", Long.toUnsignedString(e.getItemUuid()));
            v.put("itemConfigId", Integer.toUnsignedLong(e.getItemConfigId()));
            v.put("currencyType", Integer.toUnsignedLong(e.getCurrencyType()));
            v.put("quantity", qty.toString());
            v.put("alreadyRecalled", already);
            rowViews.add(v);
            if (!already) {
                owed.merge(e.getToPlayer(), qty, BigInteger::add);
            }
        }
        List<Map<String, Object>> perPlayer = new ArrayList<>();
        BigInteger totalOwed = BigInteger.ZERO;
        BigInteger totalShortfall = BigInteger.ZERO;
        int estimated = 0;
        for (Map.Entry<Long, BigInteger> o : owed.entrySet()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("player", Long.toUnsignedString(o.getKey()));
            v.put("owed", o.getValue().toString());
            totalOwed = totalOwed.add(o.getValue());
            if (estimated < MAX_ESTIMATES) {
                estimated++;
                Holding held = holding(plan, o.getKey());
                v.put("held", held.amount() == null ? null : held.amount().toString());
                if (held.note() != null) {
                    v.put("heldNote", held.note());
                }
                if (held.amount() != null) {
                    BigInteger recoverable = o.getValue().min(held.amount());
                    BigInteger shortfall = o.getValue().subtract(recoverable);
                    v.put("recoverable", recoverable.toString());
                    v.put("shortfall", shortfall.toString());
                    totalShortfall = totalShortfall.add(shortfall);
                }
            }
            perPlayer.add(v);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", true);
        out.put("kind", plan.kind() == AssetKind.ASSET_CURRENCY_VALUE ? "currency" : "item");
        out.put("currencyType", plan.currencyType() == null ? null : Integer.toUnsignedLong(plan.currencyType()));
        out.put("itemConfigId", plan.itemConfigId() == null ? null : Integer.toUnsignedLong(plan.itemConfigId()));
        out.put("sinceMs", plan.sinceMs());
        out.put("untilMs", plan.untilMs());
        out.put("reasons", plan.reasons());
        out.put("rows", rowViews);
        out.put("players", perPlayer);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("rows", rowViews.size());
        totals.put("alreadyRecalled", recalled.size());
        totals.put("players", owed.size());
        totals.put("owed", totalOwed.toString());
        totals.put("estimatedShortfall", totalShortfall.toString());
        out.put("totals", totals);
        out.put("estimateTruncated", owed.size() > MAX_ESTIMATES);
        out.put("truncated", false);
        // 落库完整性闸（Kafka 消费组位点 vs untilMs）随执行一起在 7.2c 做；dry-run 现在不检查，照实声明
        out.put("ingestComplete", null);
        out.put("ingestCheck", "not_checked");
        out.put("fallbackCaveat", "流水在生产端是尽力而为的：没被 Kafka 确认的行在 scene 的兜底日志 xm.audit.fallback 里，"
                + "没回灌（AuditFallbackReplay）之前不在库里，这里也就匹配不到");
        out.put("estimateCaveat", "可回收量按已落盘状态估算；计划与执行之间余额会变，执行以作业内的实时读为准");
        return out;
    }

    /** 一行源流水的应回收量：物品 = 数量，货币 = 正的 delta（扣减行不会被选中）。 */
    private static BigInteger quantity(Plan plan, TransactionLogEntry e) {
        if (plan.kind() == AssetKind.ASSET_ITEM_VALUE) {
            return BigInteger.valueOf(Integer.toUnsignedLong(e.getItemQuantity()));
        }
        return BigInteger.valueOf(Math.max(0, e.getCurrencyDelta()));
    }

    record Holding(BigInteger amount, String note) {
    }

    /** 已落盘状态里的现有量：货币余额，或该配置所有实例的堆叠数之和（四个包都算）。 */
    private Holding holding(Plan plan, long playerId) {
        PersistedPlayer p = players.find(playerId);
        if (p == null) {
            return new Holding(BigInteger.ZERO, "player_not_found");
        }
        PlayerState state;
        try {
            state = PlayerState.parseFrom(p.stateBytes());
        } catch (InvalidProtocolBufferException e) {
            return new Holding(null, "state_invalid");
        }
        if (plan.kind() == AssetKind.ASSET_CURRENCY_VALUE) {
            // 币种是 uint32：≥ 2^31 的值放进 int 是负数，按无符号与下标上界比较（否则负下标越界、整个 dry-run 500）
            int type = plan.currencyType();
            long balance = Integer.compareUnsigned(type, state.getCurrency().getBalancesCount()) < 0
                    ? state.getCurrency().getBalances(type) : 0;
            return new Holding(new BigInteger(Long.toUnsignedString(balance)), null);
        }
        long sum = 0;
        for (BagItemState item : state.getBag().getItemsList()) {
            if (item.getConfigId() == plan.itemConfigId()) {
                sum += Integer.toUnsignedLong(item.getStackSize());
            }
        }
        return new Holding(BigInteger.valueOf(sum), null);
    }

    /**
     * 截断的审计：一条 REJECTED 作业（result_truncated）+ RESULT 事件，同一事务。号源无效或写失败只记日志、返回空——
     * 截断本身照样 422 零变更，审计行是附带的。
     */
    private OptionalLong auditTruncated(Plan plan, String operator) {
        OptionalLong jobId = ids.tryNext();
        long now = clock.millis();
        if (jobId.isEmpty()) {
            audit.error("[Recall] dry-run 截断，但号源无效、没写审计作业 operator={} kind={} window=[{},{})", operator,
                    plan.kind(), plan.sinceMs(), plan.untilMs());
            return OptionalLong.empty();
        }
        long id = jobId.getAsLong();
        try {
            String request = json.writeValueAsString(Map.of("dryRun", true, "plan", plan.canonical()));
            String payload = json.writeValueAsString(Map.of("truncated", true, "maxRows", maxRows));
            tx.executeWithoutResult(status -> {
                jobs.insertJob(OpsJobRow.newBuilder()
                        .setJobId(id)
                        .setIdemKey("auto:" + Long.toUnsignedString(id))
                        .setKind(OpsJobKind.OPS_JOB_RECALL)
                        .setStatus(OpsJobStatus.OPS_JOB_REJECTED)
                        .setRequestJson(request)
                        .setRequestHash(com.game.data.ops.OpsRequests.requestHash("POST", "/admin/recalls", plan.canonical()))
                        .setOperator(operator)
                        .setReason(plan.reason() == null ? "" : plan.reason())
                        .setResultCode(OpsException.RESULT_TRUNCATED)
                        .setPlayersPlanned(plan.players().size())
                        .setRunner(runner)
                        .setCreatedMs(now)
                        .setStartedMs(now)
                        .setFinishedMs(now)
                        .setSummaryJson(payload)
                        .build());
                jobs.insertEvent(OpsJobEventRow.newBuilder()
                        .setJobId(id)
                        .setSeq(1)
                        .setType(OpsJobEventType.OPS_JOB_EVENT_RESULT)
                        .setPayloadJson(payload)
                        .setAtMs(now)
                        .build());
            });
        } catch (JsonProcessingException | RuntimeException e) {
            audit.error("[Recall] dry-run 截断的审计作业写入失败 job={}：{}", Long.toUnsignedString(id), e.toString());
            return OptionalLong.empty();
        }
        audit.warn("[Recall] dry-run 截断 job={} operator={} kind={} window=[{},{}) maxRows={}", Long.toUnsignedString(id),
                operator, plan.kind(), plan.sinceMs(), plan.untilMs(), maxRows);
        return jobId;
    }
}
