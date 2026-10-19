package com.game.inbox.store;

import static com.game.inbox.store.InboxSql.ITEM_COLUMNS;
import static com.game.inbox.store.InboxSql.ITEM_PK;
import static com.game.inbox.store.InboxSql.ITEM_TABLE;
import static com.game.inbox.store.InboxSql.PENDING;
import static com.game.inbox.store.InboxSql.STREAM_COLUMNS;
import static com.game.inbox.store.InboxSql.STREAM_PK;
import static com.game.inbox.store.InboxSql.STREAM_TABLE;
import static com.game.inbox.store.InboxSql.u32;
import static com.game.inbox.store.InboxSql.u64;
import static com.game.inbox.store.InboxStore.tip;

import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.discovery.sql.AuthorityJdbc.Session;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.registry.InboxRegistry;
import com.game.inbox.rules.InboxLabels;
import com.game.inbox.rules.InboxRules;
import com.game.inbox.rules.InboxRules.VoidAction;
import com.game.inbox.rules.InboxShards;
import com.game.inbox.rules.InboxTips;
import com.game.inbox.rules.LedgerProofs;
import com.game.inbox.rules.LedgerProofs.ForceVoidProof;
import com.game.player.store.inbox.InboxLedgerRules;
import com.game.player.store.state.InboxLedgerState;
import com.game.proto.player_inbox.GetPlayerInboxSummaryResponse;
import com.game.proto.player_inbox.ListPlayerInboxItemsRequest;
import com.game.proto.player_inbox.ListPlayerInboxItemsResponse;
import com.game.proto.player_inbox.LookupPlayerInboxRequest;
import com.game.proto.player_inbox.LookupPlayerInboxResponse;
import com.game.proto.player_inbox.PlayerInboxAuditAction;
import com.game.proto.player_inbox.PlayerInboxDirective;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxFinalizer;
import com.game.proto.player_inbox.PlayerInboxForceVoidVerdict;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.PlayerInboxItemView;
import com.game.proto.player_inbox.PlayerInboxProducerKind;
import com.game.proto.player_inbox.PlayerInboxReconcileCursor;
import com.game.proto.player_inbox.PlayerInboxReconcileDiff;
import com.game.proto.player_inbox.PlayerInboxReconcileDiffKind;
import com.game.proto.player_inbox.PlayerInboxStreamSummary;
import com.game.proto.player_inbox.PlayerInboxVoidMode;
import com.game.proto.player_inbox.PlayerInboxVoidReason;
import com.game.proto.player_inbox.ReconcilePlayerInboxRequest;
import com.game.proto.player_inbox.ReconcilePlayerInboxResponse;
import com.game.proto.player_inbox.VoidPlayerInboxItemResponse;
import com.game.proto.player_inbox.player_inbox_item_row;
import com.game.proto.player_inbox.player_inbox_stream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运维面的存储操作（mmorpg {@code store_admin.go} 与 {@code reconcile.go} 的 {@code ReconcilePlayer}；设计 §5.2、§7.9、§9.3）：作废（普通 /
 * 强制模式）、按玩家概况、列条目、按幂等键查、按玩家对账。锁序与事务口径同 {@link InboxStore}。线程安全（构造后只读）。
 */
public final class InboxAdminStore {

    private static final Logger log = LoggerFactory.getLogger(InboxAdminStore.class);

    /** ReconcilePlayer 单个玩家读出的行数上限（单条流在保留期内不超过数百行）。 */
    static final int PLAYER_ITEMS_LIMIT = 5000;

    /**
     * 一次运维作废的输入。
     *
     * @param note 进审计 reason；强制模式必填（工单号与依据）
     * @param mode 缺省 NORMAL = 原语义；FORCE_MISMATCH = 强制模式
     */
    public record VoidParams(long playerId, PlayerInboxDomain domain, long seq, PlayerInboxVoidReason reason, String operator, String note,
                             String peerAddr, PlayerInboxVoidMode mode) {
    }

    private final AuthorityJdbc jdbc;
    private final InboxRegistry registry;
    private final LedgerReader ledgers;
    private final InboxMetrics metrics;
    private final LongSupplier nowMs;

    public InboxAdminStore(AuthorityJdbc jdbc, InboxRegistry registry, LedgerReader ledgers, InboxMetrics metrics, LongSupplier nowMs) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ledgers = Objects.requireNonNull(ledgers, "ledgers");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
    }

    // ================================================================ 作废（设计 §7.9、IB-5）

    /**
     * 普通模式：从未投递的在流行锁下直接改 VOIDED（finalized_by = ADMIN）、open_count − 1；已投递的只挂 directive = VOID 由消费方裁决；
     * 已挂过 VOID 指令幂等成功；已是终态 → ItemStateConflict；不存在 → ItemNotFound。改动行的分支与审计行（VOID）同库同事务。
     * 强制模式见 {@link #forceVoid}。
     */
    public VoidPlayerInboxItemResponse voidItem(Deadline budget, VoidParams p) throws SQLException {
        VoidPlayerInboxItemResponse.Builder resp = VoidPlayerInboxItemResponse.newBuilder();
        if (registry.domain(p.domain()).isEmpty() || p.playerId() == 0 || p.seq() == 0 || p.reason() == null
                || p.reason() == PlayerInboxVoidReason.PLAYER_INBOX_VOID_UNSPECIFIED || p.reason() == PlayerInboxVoidReason.UNRECOGNIZED
                || p.mode() == null || p.mode() == PlayerInboxVoidMode.UNRECOGNIZED) {
            return resp.setErrorMessage(tip(InboxTips.INVALID_PARAMETER)).build();
        }
        int text = InboxRules.validateText(p.operator(), InboxRules.MAX_OPERATOR_BYTES, true);
        if (text == 0) {
            text = InboxRules.validateText(p.note(), InboxRules.MAX_REASON_BYTES, false);
        }
        if (text != 0) {
            return resp.setErrorMessage(tip(text)).build();
        }
        if (p.mode() == PlayerInboxVoidMode.PLAYER_INBOX_VOID_MODE_FORCE_MISMATCH) {
            return forceVoid(budget, p);
        }
        int shard = InboxShards.rowShard(p.playerId());
        record Outcome(VoidAction action, PlayerInboxItemState state) {
        }
        Outcome outcome = jdbc.tx(budget, s -> {
            player_inbox_stream stream = InboxStore.lockStream(s, shard, p.playerId(), p.domain());
            if (stream == null) {
                return new Outcome(VoidAction.NOT_FOUND, PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED);
            }
            player_inbox_item_row row = InboxStore.readItem(s, shard, p.playerId(), p.domain().getNumber(), p.seq());
            VoidAction action = InboxRules.decideVoid(row);
            long now = nowMs.getAsLong();
            PlayerInboxItemState state;
            switch (action) {
                case NOT_FOUND -> {
                    return new Outcome(action, PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED);
                }
                case TERMINAL -> {
                    return new Outcome(action, InboxRules.stateOf(row.getState()));
                }
                case DIRECT -> {
                    InboxStore.requireOne(s.update("UPDATE " + ITEM_TABLE + " SET `state` = ?, `finalized_by` = ?, `void_reason` = ?,"
                                    + " `updated_ms` = ? WHERE " + ITEM_PK + " AND `state` = ? AND `delivery_count` = 0",
                            PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED_VALUE, PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_ADMIN_VALUE,
                            p.reason().getNumber(), u64(now), shard, u64(p.playerId()), u32(p.domain().getNumber()), u64(p.seq()), PENDING),
                            "作废条目");
                    decrementOpen(s, shard, p, stream, now, "作废");
                    state = PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED;
                }
                case DIRECTIVE -> {
                    InboxStore.requireOne(s.update("UPDATE " + ITEM_TABLE + " SET `directive` = ?, `void_reason` = ?, `updated_ms` = ? WHERE "
                                    + ITEM_PK + " AND `state` = ?", PlayerInboxDirective.PLAYER_INBOX_DIRECTIVE_VOID_VALUE, p.reason().getNumber(),
                            u64(now), shard, u64(p.playerId()), u32(p.domain().getNumber()), u64(p.seq()), PENDING), "挂作废指令");
                    state = PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING;
                }
                default -> state = PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING; // ALREADY_REQUESTED：幂等，不改行
            }
            // 内容列取自上面读到的 row（评审 L9）；void_reason 记本次请求的原因
            InboxStore.insertAudit(s, row, new InboxStore.Audit(PlayerInboxAuditAction.PLAYER_INBOX_AUDIT_VOID, p.operator(), p.peerAddr(),
                    p.note(), p.reason()), now);
            return new Outcome(action, state);
        });
        resp.setState(outcome.state()).setVoidRequested(outcome.action() == VoidAction.DIRECTIVE
                || outcome.action() == VoidAction.ALREADY_REQUESTED);
        int tip = InboxRules.voidTip(outcome.action());
        if (tip != 0) {
            resp.setErrorMessage(tip(tip));
        }
        String path = InboxRules.voidPath(outcome.action());
        if (path != null) {
            metrics.voided(InboxLabels.domain(p.domain()), InboxLabels.voidReason(p.reason()), path);
        }
        if (tip == 0) {
            log.info("[PlayerInbox] VoidItem player={} domain={} seq={} reason={} state={} void_requested={} operator={} peer={}",
                    Long.toUnsignedString(p.playerId()), p.domain().getNumber(), Long.toUnsignedString(p.seq()), InboxLabels.voidReason(p.reason()),
                    outcome.state(), resp.getVoidRequested(), p.operator(), p.peerAddr());
        }
        return resp.build();
    }

    /**
     * 强制模式（mode = FORCE_MISMATCH，评审 M1，PARITY 第 ① 条）：终结一条被 scene 回放核对判 MISMATCH、因而永久停用该域的已投递行。
     * <ol>
     *   <li>事务外读该玩家<b>已落盘</b>的账本（不在持有行锁时等它）；读失败抛（回 StoreUnavailable，可重试），不改任何状态；</li>
     *   <li>开事务锁流行，按主键复读行：不存在 / 已是终态沿用普通模式的结论；仍 PENDING 时按 {@link LedgerProofs#proveNeverExecuted} 判定。
     *       证明成立才以「仍 PENDING、已投递、request_id 不变」为条件改 VOIDED（finalized_by = ADMIN、void_reason = 请求的原因），open_count − 1，
     *       同事务写审计 FORCE_VOID（reason = note）；不成立 → ItemStateConflict + force_verdict，行不变、不写审计。</li>
     * </ol>
     * 成功以 {@code force_verdict == PROVEN} 且 {@code state == VOIDED} 为准。
     */
    private VoidPlayerInboxItemResponse forceVoid(Deadline budget, VoidParams p) throws SQLException {
        VoidPlayerInboxItemResponse.Builder resp = VoidPlayerInboxItemResponse.newBuilder();
        // 强制终结是高风险操作：审计必须写清工单与依据，note 必填
        int text = InboxRules.validateText(p.note(), InboxRules.MAX_REASON_BYTES, true);
        if (text != 0) {
            return resp.setErrorMessage(tip(text)).build();
        }
        LedgerReader.Read read = ledgers.read(budget, p.playerId());
        InboxLedgerState ledger = read.found() ? read.ledger() : null;
        int shard = InboxShards.rowShard(p.playerId());
        record Outcome(VoidAction action, ForceVoidProof proof, PlayerInboxItemState state, long requestId) {
        }
        Outcome outcome = jdbc.tx(budget, s -> {
            player_inbox_stream stream = InboxStore.lockStream(s, shard, p.playerId(), p.domain());
            if (stream == null) {
                return new Outcome(VoidAction.NOT_FOUND, null, PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED, 0);
            }
            player_inbox_item_row row = InboxStore.readItem(s, shard, p.playerId(), p.domain().getNumber(), p.seq());
            if (row == null) {
                return new Outcome(VoidAction.NOT_FOUND, null, PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED, 0);
            }
            if (row.getState() != PENDING) {
                return new Outcome(VoidAction.TERMINAL, null, InboxRules.stateOf(row.getState()), row.getRequestId());
            }
            ForceVoidProof proof = LedgerProofs.proveNeverExecuted(row, ledger);
            if (proof.verdict() != PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_PROVEN) {
                return new Outcome(VoidAction.FORCE_REFUSED, proof, PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING, row.getRequestId());
            }
            long now = nowMs.getAsLong();
            InboxStore.requireOne(s.update("UPDATE " + ITEM_TABLE + " SET `state` = ?, `finalized_by` = ?, `void_reason` = ?, `updated_ms` = ?"
                            + " WHERE " + ITEM_PK + " AND `state` = ? AND `delivery_count` > 0 AND `request_id` = ?",
                    PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED_VALUE, PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_ADMIN_VALUE,
                    p.reason().getNumber(), u64(now), shard, u64(p.playerId()), u32(p.domain().getNumber()), u64(p.seq()), PENDING,
                    u64(row.getRequestId())), "强制作废条目");
            decrementOpen(s, shard, p, stream, now, "强制作废");
            InboxStore.insertAudit(s, row, new InboxStore.Audit(PlayerInboxAuditAction.PLAYER_INBOX_AUDIT_FORCE_VOID, p.operator(),
                    p.peerAddr(), p.note(), p.reason()), now);
            return new Outcome(VoidAction.FORCED, proof, PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED, row.getRequestId());
        });
        resp.setState(outcome.state());
        if (outcome.proof() != null) {
            resp.setForceVerdict(outcome.proof().verdict());
        }
        int tip = InboxRules.voidTip(outcome.action());
        if (tip != 0) {
            resp.setErrorMessage(tip(tip));
        }
        String path = InboxRules.voidPath(outcome.action());
        if (path != null) {
            metrics.voided(InboxLabels.domain(p.domain()), InboxLabels.voidReason(p.reason()), path);
        }
        if (outcome.proof() != null) {
            log.info("[PlayerInbox] VoidItem FORCE {} player={} domain={} seq={} request_id={} verdict={} ledger_decided_through={}"
                            + " ledger_request_id_at_seq={} operator={} peer={}", outcome.action() == VoidAction.FORCED ? "finalized" : "refused",
                    Long.toUnsignedString(p.playerId()), p.domain().getNumber(), Long.toUnsignedString(p.seq()),
                    Long.toUnsignedString(outcome.requestId()), outcome.proof().verdict(), Long.toUnsignedString(outcome.proof().decidedThrough()),
                    Long.toUnsignedString(outcome.proof().ringRequestId()), p.operator(), p.peerAddr());
        }
        return resp.build();
    }

    private void decrementOpen(Session s, int shard, VoidParams p, player_inbox_stream stream, long now, String what) throws SQLException {
        if (stream.getOpenCount() == 0) {
            log.error("[PlayerInbox] {}一条 PENDING 行时 open_count 已是 0 player={} domain={} seq={}", what, Long.toUnsignedString(p.playerId()),
                    p.domain().getNumber(), Long.toUnsignedString(p.seq()));
        }
        InboxStore.requireOne(s.update("UPDATE " + STREAM_TABLE + " SET `open_count` = ?, `updated_ms` = ? WHERE " + STREAM_PK,
                u32(InboxRules.subtractOpenCount(stream.getOpenCount(), 1)), u64(now), shard, u64(p.playerId()), u32(p.domain().getNumber())),
                what + "后更新流行");
    }

    // ================================================================ 查询（设计 §5.2）

    /** 按玩家列出各域流的概况，另给 max_fence_epoch（各流 fence_epoch 的最大值）。 */
    public GetPlayerInboxSummaryResponse summary(Deadline budget, long playerId) throws SQLException {
        GetPlayerInboxSummaryResponse.Builder resp = GetPlayerInboxSummaryResponse.newBuilder().setPlayerId(playerId);
        if (playerId == 0) {
            return resp.setErrorMessage(tip(InboxTips.INVALID_PARAMETER)).build();
        }
        int shard = InboxShards.rowShard(playerId);
        List<player_inbox_stream> streams = jdbc.read(budget, s -> s.list("SELECT " + STREAM_COLUMNS + " FROM " + STREAM_TABLE
                + " WHERE `shard` = ? AND `player_id` = ? ORDER BY `domain` ASC", InboxSql::stream, shard, u64(playerId)));
        record Aggregate(long deliveredUnacked, long oldestCreatedMs) {
        }
        Map<Integer, Aggregate> aggregates = new HashMap<>();
        jdbc.read(budget, s -> s.list("SELECT `domain`, COALESCE(SUM(CASE WHEN `delivery_count` > 0 THEN 1 ELSE 0 END), 0) AS delivered,"
                + " COALESCE(MIN(`created_ms`), 0) AS oldest FROM " + ITEM_TABLE + " WHERE `shard` = ? AND `player_id` = ? AND `state` = ?"
                + " GROUP BY `domain`", row -> aggregates.put(u32(row, "domain"),
                new Aggregate(row.getLong("delivered"), InboxSql.u64(row, "oldest"))), shard, u64(playerId), PENDING));
        long maxFence = 0;
        for (player_inbox_stream stream : streams) {
            Aggregate agg = aggregates.getOrDefault(stream.getDomain(), new Aggregate(0, 0));
            resp.addStreams(PlayerInboxStreamSummary.newBuilder()
                    .setDomainValue(stream.getDomain())
                    .setOpenCount(stream.getOpenCount())
                    .setNextSeq(stream.getNextSeq())
                    .setFenceEpoch(stream.getFenceEpoch())
                    .setDeliveredUnackedCount((int) agg.deliveredUnacked())
                    .setOldestPendingCreatedMs(agg.oldestCreatedMs())
                    .setNotifyDueMs(stream.getNotifyDueMs()));
            if (Long.compareUnsigned(stream.getFenceEpoch(), maxFence) > 0) {
                maxFence = stream.getFenceEpoch();
            }
        }
        return resp.setMaxFenceEpoch(maxFence).build();
    }

    /** 按 player_id + domain（+ 可选 state）分页列条目，seq 升序，after_seq 翻页，limit ≤ 200。 */
    public ListPlayerInboxItemsResponse list(Deadline budget, ListPlayerInboxItemsRequest req) throws SQLException {
        ListPlayerInboxItemsResponse.Builder resp = ListPlayerInboxItemsResponse.newBuilder();
        if (registry.domain(req.getDomain()).isEmpty() || req.getPlayerId() == 0 || req.getState() == PlayerInboxItemState.UNRECOGNIZED) {
            return resp.setErrorMessage(tip(InboxTips.INVALID_PARAMETER)).build();
        }
        int limit = InboxRules.clampItems(req.getLimit(), InboxRules.LIST_DEFAULT_LIMIT, InboxRules.LIST_MAX_LIMIT);
        StringBuilder sql = new StringBuilder("SELECT " + ITEM_COLUMNS + " FROM " + ITEM_TABLE
                + " WHERE `shard` = ? AND `player_id` = ? AND `domain` = ? AND `seq` > ?");
        List<Object> args = new ArrayList<>(List.of(InboxShards.rowShard(req.getPlayerId()), u64(req.getPlayerId()),
                u32(req.getDomainValue()), u64(req.getAfterSeq())));
        if (req.getState() != PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED) {
            sql.append(" AND `state` = ?");
            args.add(req.getStateValue());
        }
        sql.append(" ORDER BY `seq` ASC LIMIT ?");
        args.add(limit + 1);
        List<player_inbox_item_row> rows = jdbc.read(budget, s -> s.list(sql.toString(), InboxSql::item, args.toArray()));
        boolean hasMore = rows.size() > limit;
        for (player_inbox_item_row row : hasMore ? rows.subList(0, limit) : rows) {
            resp.addItems(itemView(row));
        }
        return resp.setHasMore(hasMore).build();
    }

    /** 按幂等键 (player_id, producer_kind, request_id) 查条目与结局。 */
    public LookupPlayerInboxResponse lookup(Deadline budget, LookupPlayerInboxRequest req) throws SQLException {
        LookupPlayerInboxResponse.Builder resp = LookupPlayerInboxResponse.newBuilder();
        PlayerInboxProducerKind producer = req.getProducerKind();
        if (req.getPlayerId() == 0 || req.getRequestId() == 0 || producer == PlayerInboxProducerKind.UNRECOGNIZED
                || producer == PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_UNSPECIFIED) {
            return resp.setErrorMessage(tip(InboxTips.INVALID_PARAMETER)).build();
        }
        player_inbox_item_row row = jdbc.read(budget, s -> InboxStore.readItemByRequest(s, InboxShards.rowShard(req.getPlayerId()),
                req.getPlayerId(), producer, req.getRequestId()));
        if (row == null) {
            return resp.setErrorMessage(tip(InboxTips.ITEM_NOT_FOUND)).build();
        }
        return resp.setItem(itemView(row)).build();
    }

    /** 一行折成运维视图（全部列；载荷原样返回，由运维工具按 msg_type 的 payload_message 解码）。 */
    static PlayerInboxItemView itemView(player_inbox_item_row row) {
        return PlayerInboxItemView.newBuilder()
                .setDomainValue(row.getDomain())
                .setSeq(row.getSeq())
                .setMsgTypeValue(row.getMsgType())
                .setProducerKindValue(row.getProducerKind())
                .setRequestId(row.getRequestId())
                .setPayload(row.getPayload())
                .setStateValue(row.getState())
                .setDirectiveValue(row.getDirective())
                .setVoidReasonValue(row.getVoidReason())
                .setDeliveryCount(row.getDeliveryCount())
                .setLastDeliveredEpoch(row.getLastDeliveredEpoch())
                .setLastDeliveredMs(row.getLastDeliveredMs())
                .setOutcomeReason(row.getOutcomeReason())
                .setFinalizedByValue(row.getFinalizedBy())
                .setDecidedEpoch(row.getDecidedEpoch())
                .setIssuedAtMs(row.getIssuedAtMs())
                .setCreatedMs(row.getCreatedMs())
                .setUpdatedMs(row.getUpdatedMs())
                .setOperatorName(row.getOperatorName())
                .setNote(row.getNote())
                .build();
    }

    // ================================================================ 按玩家对账（设计 §9.3）

    /**
     * 运维手工对账：按单个玩家比对收件箱与已落盘账本，报告两类差异（FINAL_NOT_IN_LEDGER / PENDING_BUT_DECIDED，见
     * {@link LedgerProofs#reconcileDiff}）。只读。读不到存档 → ledger_found = false（结论未知，不比对）；账本结构不合法 → 抛
     * （回 StoreUnavailable 并记 ERROR：那份账本当不了证据，回空 diffs 会被读成「无差异」）。
     */
    public ReconcilePlayerInboxResponse reconcilePlayer(Deadline budget, ReconcilePlayerInboxRequest req) throws SQLException {
        ReconcilePlayerInboxResponse.Builder resp = ReconcilePlayerInboxResponse.newBuilder();
        PlayerInboxDomain domain = req.getDomain();
        if (req.getPlayerId() == 0 || (domain != PlayerInboxDomain.PLAYER_INBOX_DOMAIN_UNSPECIFIED && registry.domain(domain).isEmpty())) {
            return resp.setErrorMessage(tip(InboxTips.INVALID_PARAMETER)).build();
        }
        long playerId = req.getPlayerId();
        LedgerReader.Read read = ledgers.read(budget, playerId);
        resp.setLedgerFound(read.found());
        if (!read.found()) {
            return resp.build();
        }
        String invalid = InboxLedgerRules.validate(read.ledger(), true);
        if (invalid != null) {
            throw new SQLException("玩家 " + Long.toUnsignedString(playerId) + " 的已落盘收件箱账本结构不合法（" + invalid + "）；对账结论未知");
        }
        int shard = InboxShards.rowShard(playerId);
        StringBuilder sql = new StringBuilder("SELECT " + ITEM_COLUMNS + " FROM " + ITEM_TABLE + " WHERE `shard` = ? AND `player_id` = ?");
        List<Object> args = new ArrayList<>(List.of(shard, u64(playerId)));
        if (domain != PlayerInboxDomain.PLAYER_INBOX_DOMAIN_UNSPECIFIED) {
            sql.append(" AND `domain` = ?");
            args.add(u32(domain.getNumber()));
        }
        sql.append(" ORDER BY `domain` ASC, `seq` ASC LIMIT ?");
        args.add(PLAYER_ITEMS_LIMIT + 1);
        List<player_inbox_item_row> rows = jdbc.read(budget, s -> s.list(sql.toString(), InboxSql::item, args.toArray()));
        if (rows.size() > PLAYER_ITEMS_LIMIT) {
            rows = rows.subList(0, PLAYER_ITEMS_LIMIT);
            log.error("[PlayerInbox] ReconcilePlayer 只读了玩家 {} 的前 {} 行；结论不完整", Long.toUnsignedString(playerId), PLAYER_ITEMS_LIMIT);
        }
        List<PlayerInboxDomain> domains = domain == PlayerInboxDomain.PLAYER_INBOX_DOMAIN_UNSPECIFIED ? registry.domains() : List.of(domain);
        for (PlayerInboxDomain d : domains) {
            player_inbox_stream stream = jdbc.read(budget, s -> InboxStore.readStream(s, shard, playerId, d.getNumber()));
            long decidedThrough = InboxLedgerRules.decidedThrough(read.ledger(), d.getNumber());
            if (stream == null && decidedThrough == 0) {
                continue;
            }
            resp.addCursors(PlayerInboxReconcileCursor.newBuilder().setDomain(d).setLedgerDecidedThrough(decidedThrough)
                    .setInboxNextSeq(stream == null ? 0 : stream.getNextSeq()));
        }
        for (player_inbox_item_row row : rows) {
            PlayerInboxReconcileDiffKind kind = LedgerProofs.reconcileDiff(row, InboxLedgerRules.findCursor(read.ledger(), row.getDomain()));
            if (kind == PlayerInboxReconcileDiffKind.PLAYER_INBOX_RECONCILE_DIFF_UNSPECIFIED) {
                continue;
            }
            resp.addDiffs(PlayerInboxReconcileDiff.newBuilder().setDomainValue(row.getDomain()).setSeq(row.getSeq()).setKind(kind)
                    .setStateValue(row.getState()).setRequestId(row.getRequestId()));
        }
        return resp.build();
    }
}
