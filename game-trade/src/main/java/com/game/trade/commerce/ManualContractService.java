package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.game.common.deadline.Deadline;
import com.game.proto.AssetSnapshot;
import com.game.proto.trade.ListManualContractsRequest;
import com.game.proto.trade.ListManualContractsResponse;
import com.game.proto.trade.ManualContractActionVerdict;
import com.game.proto.trade.ManualContractLeg;
import com.game.proto.trade.ManualContractMode;
import com.game.proto.trade.ManualContractParty;
import com.game.proto.trade.ManualContractVerdict;
import com.game.proto.trade.ResolveManualContractRequest;
import com.game.proto.trade.ResolveManualContractResponse;
import com.game.proto.trade.TradeAssetOpStatus;
import com.game.proto.trade.TradeCommerceLeg;
import com.game.proto.trade.TradeCommerceManualAction;
import com.game.proto.trade.TradeCommerceRecord;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.trade.store.pb.TradeAssetOpRow;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 交易转人工合同的查询与处置服务（对齐 Go {@code manual.go} 与 {@code logic/manual_contract_ops.go}）：
 * <ul>
 *   <li>锁序与引擎严格一致：先按无符号 player_id 升序锁角色归属，再锁拍品 guard 与拍品行，最后锁合同与联动出价；</li>
 *   <li>核对 {@code expect_version}（与联动 {@code linked_version}）与 {@code approval_id} 幂等；</li>
 *   <li>支持 {@code SHOW} / {@code DRY_RUN} / {@code EXECUTE}，自由文本带双引号转义打入 {@code [CommerceManual]} 审计行。</li>
 * </ul>
 */
public final class ManualContractService {

    public static final int LIST_PAGE_MAX = 500;
    private static final Logger log = LoggerFactory.getLogger(ManualContractService.class);

    /** 读取参与方与改绑目标角色归属事实（锁内与锁外共用接口，便于单测注入）。 */
    public interface RoleFactReader {
        Map<Long, ManualContractPlanner.RoleFact> read(Connection txOrNull, Collection<Long> playerIds, boolean forUpdate)
                throws SQLException;
    }

    /** 默认 JDBC 实现：锁内经 {@code PlayerRouteAuthority.lock} 后按升序 {@code SELECT ... FOR UPDATE} 读 {@code player} 表。 */
    public static final class SqlRoleFactReader implements RoleFactReader {
        private final DataSource source;

        public SqlRoleFactReader(DataSource source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        @Override
        public Map<Long, ManualContractPlanner.RoleFact> read(Connection txOrNull, Collection<Long> playerIds, boolean forUpdate)
                throws SQLException {
            TreeSet<Long> sorted = new TreeSet<>(Long::compareUnsigned);
            if (playerIds != null) {
                for (Long id : playerIds) {
                    if (id != null && id != 0L) {
                        sorted.add(id);
                    }
                }
            }
            if (sorted.isEmpty()) {
                return Map.of();
            }
            if (txOrNull != null) {
                return readOn(txOrNull, sorted, forUpdate);
            }
            try (Connection c = source.getConnection()) {
                return readOn(c, sorted, false);
            }
        }

        private static Map<Long, ManualContractPlanner.RoleFact> readOn(
                Connection c, TreeSet<Long> sorted, boolean forUpdate) throws SQLException {
            if (forUpdate) {
                com.game.common.player.PlayerRouteAuthority.lock(c, sorted, 2);
            }
            Map<Long, ManualContractPlanner.RoleFact> out = new HashMap<>(sorted.size() * 2);
            String sql = "SELECT account,owner_revision,trade_listing_id FROM player WHERE player_id=?"
                    + (forUpdate ? " FOR UPDATE" : "");
            for (long id : sorted) {
                try (var s = c.prepareStatement(sql)) {
                    s.setQueryTimeout(2);
                    s.setObject(1, id < 0 ? new java.math.BigInteger(Long.toUnsignedString(id)) : id);
                    try (var rs = s.executeQuery()) {
                        if (!rs.next()) {
                            out.put(id, ManualContractPlanner.RoleFact.deleted(id));
                        } else {
                            String acc = rs.getString(1);
                            long rev = Long.parseUnsignedLong(rs.getString(2));
                            boolean frozen = !"0".equals(rs.getString(3));
                            if (acc == null || acc.isEmpty() || rev == 0L) {
                                out.put(id, ManualContractPlanner.RoleFact.deleted(id));
                            } else {
                                out.put(id, ManualContractPlanner.RoleFact.present(id, acc, rev, frozen));
                            }
                        }
                    }
                }
            }
            return out;
        }
    }

    private final CommerceStore store;
    private final RoleFactReader roles;
    private final LongSupplier clock;
    private final String environment;

    public ManualContractService(CommerceStore store, RoleFactReader roles, LongSupplier clock, String environment) {
        this.store = Objects.requireNonNull(store, "store");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.environment = environment == null ? "" : environment.strip();
    }

    public String environment() {
        return environment;
    }

    /** 分页列所有处于人工隔离态的合同。 */
    public ListManualContractsResponse listManual(ListManualContractsRequest req) {
        int limit = req.getLimit() <= 0 ? 50 : Math.min(LIST_PAGE_MAX, req.getLimit());
        try {
            List<TradeCommerceRow> rows = store.listManual(req.getAfterUpdatedMs(), req.getAfterCommerceId(), limit);
            ListManualContractsResponse.Builder resp = ListManualContractsResponse.newBuilder()
                    .setTarget(environment);
            for (TradeCommerceRow row : rows) {
                resp.addContracts(toRecord(row));
            }
            if (rows.size() == limit) {
                TradeCommerceRow last = rows.getLast();
                resp.setNextAfterUpdatedMs(last.getUpdatedMs());
                resp.setNextAfterCommerceId(last.getCommerceId());
            }
            return resp.build();
        } catch (SQLException failure) {
            log.warn("list manual contracts failed", failure);
            return ListManualContractsResponse.newBuilder().setTarget(environment).setRetry(true).build();
        }
    }

    /** 统一处理 SHOW / DRY_RUN / EXECUTE。 */
    public ResolveManualContractResponse resolve(ResolveManualContractRequest req, Deadline deadline) {
        if (req.getMode() == ManualContractMode.MANUAL_CONTRACT_MODE_SHOW) {
            return show(req.getCommerceId(), deadline);
        }
        if (req.getMode() != ManualContractMode.MANUAL_CONTRACT_MODE_DRY_RUN
                && req.getMode() != ManualContractMode.MANUAL_CONTRACT_MODE_EXECUTE) {
            return refused("未知 mode=" + req.getMode());
        }
        if (environment.isEmpty() || !environment.equals(req.getExpectTarget())) {
            return refused("target_mismatch: 期望环境 \"" + quote(req.getExpectTarget())
                    + "\" 与本部署环境 \"" + quote(environment) + "\" 不符");
        }
        Optional<ManualContractPlanner.Action> actionOpt = ManualContractPlanner.Action.parse(req.getAction());
        if (actionOpt.isEmpty()) {
            return refused("未知动作 " + quote(req.getAction()));
        }
        List<ManualContractPlanner.PartialPart> parts = new ArrayList<>(req.getPartsCount());
        try {
            for (String p : req.getPartsList()) {
                parts.add(ManualContractPlanner.parsePartialPart(p));
            }
        } catch (ManualContractPlanner.NotAllowedException bad) {
            return refused(bad.getMessage());
        }
        String approvalId = req.hasApproval() ? req.getApproval().getApprovalId() : "";
        String approver = req.hasApproval() ? req.getApproval().getApprover() : "";
        ManualContractPlanner.Request parsed = new ManualContractPlanner.Request(
                req.getCommerceId(),
                req.getExpectVersion(),
                actionOpt.get(),
                req.getOperator(),
                req.getReason(),
                parts,
                req.getParty(),
                req.getToPlayerId(),
                req.getLinkedCommerceId(),
                req.getLinkedVersion(),
                approvalId,
                approver);
        boolean execute = req.getMode() == ManualContractMode.MANUAL_CONTRACT_MODE_EXECUTE;
        try {
            ManualContractPlanner.PlanResult res = executeOrDryRun(parsed, clock.getAsLong(), execute, deadline);
            String auditLine = formatAuditLine(execute ? "EXECUTE" : "DRY_RUN", environment, parsed, res, "OK", "");
            if (execute) {
                log.warn("{}", auditLine);
            } else {
                log.info("{}", auditLine);
            }
            ResolveManualContractResponse.Builder out = ResolveManualContractResponse.newBuilder()
                    .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_OK)
                    .setTarget(environment)
                    .setStage(res.stage)
                    .setStatus(res.status)
                    .setAuditLine(auditLine)
                    .setAlreadyExecuted(res.alreadyExecuted);
            if (res.audit != null) {
                out.setPlan(res.audit);
            }
            if (res.linked != null && res.linked.audit != null) {
                out.setLinkedPlan(res.linked.audit);
            }
            return out.build();
        } catch (ManualContractPlanner.NotFoundException nf) {
            return ResolveManualContractResponse.newBuilder()
                    .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_NOT_FOUND)
                    .setTarget(environment)
                    .setRefusal(nf.getMessage())
                    .build();
        } catch (ManualContractPlanner.VersionChangedException vc) {
            String auditLine = formatAuditLine(execute ? "EXECUTE" : "DRY_RUN", environment, parsed, null, "VERSION_CHANGED", vc.getMessage());
            log.warn("{}", auditLine);
            return ResolveManualContractResponse.newBuilder()
                    .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_VERSION_CHANGED)
                    .setTarget(environment)
                    .setRefusal(vc.getMessage())
                    .setAuditLine(auditLine)
                    .build();
        } catch (ManualContractPlanner.NotAllowedException na) {
            String auditLine = formatAuditLine(execute ? "EXECUTE" : "DRY_RUN", environment, parsed, null, "REFUSED", na.getMessage());
            log.warn("{}", auditLine);
            return ResolveManualContractResponse.newBuilder()
                    .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_REFUSED)
                    .setTarget(environment)
                    .setRefusal(na.getMessage())
                    .setAuditLine(auditLine)
                    .build();
        } catch (SQLException failure) {
            log.warn("manual contract resolve transient failure commerce_id={}", Long.toUnsignedString(req.getCommerceId()), failure);
            return ResolveManualContractResponse.newBuilder()
                    .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_RETRY)
                    .setTarget(environment)
                    .setRefusal(failure.getMessage() == null ? "数据库暂不可用" : failure.getMessage())
                    .build();
        }
    }

    private ResolveManualContractResponse show(long commerceId, Deadline deadline) {
        if (commerceId == 0L) {
            return refused("commerce_id 不能为 0");
        }
        try {
            TradeCommerceRow row = store.get(commerceId);
            if (row == null) {
                return ResolveManualContractResponse.newBuilder()
                        .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_NOT_FOUND)
                        .setTarget(environment)
                        .setRefusal("合同不存在")
                        .build();
            }
            ResolveManualContractResponse.Builder out = ResolveManualContractResponse.newBuilder()
                    .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_OK)
                    .setTarget(environment)
                    .setRecord(toRecord(row));
            TradeCommerceWorkflow w;
            try {
                w = TradeCommerceWorkflow.parseFrom(row.getWorkflowProto());
            } catch (InvalidProtocolBufferException bad) {
                return out.setRefusal("workflow 解不开: " + bad.getMessage()).build();
            }
            for (TradeCommerceLeg leg : w.getLegsList()) {
                ManualContractLeg.Builder lb = ManualContractLeg.newBuilder().setLeg(leg);
                if (leg.getOpId() != 0L) {
                    try {
                        var ops = store.ops("op_id=?", leg.getOpId());
                        if (ops.size() == 1) {
                            TradeAssetOpRow op = ops.getFirst();
                            lb.setOpStatus(TradeAssetOpStatus.forNumber(op.getStatus()) == null
                                    ? TradeAssetOpStatus.TRADE_ASSET_OP_STATUS_UNSPECIFIED
                                    : TradeAssetOpStatus.forNumber(op.getStatus()));
                            if (op.getStatus() != 1) {
                                lb.setFinalMs(op.getUpdatedMs());
                            }
                        } else {
                            lb.setReadFailed(true);
                        }
                    } catch (SQLException err) {
                        lb.setReadFailed(true);
                    }
                }
                out.addLegs(lb.build());
            }
            List<Long> partyIds = partyPlayers(row);
            Map<Long, ManualContractPlanner.RoleFact> facts = roles.read(null, partyIds, false);
            for (ManualContractPlanner.ContractParty p : ManualContractPlanner.contractParties(row, w)) {
                ManualContractPlanner.RoleFact f = facts.getOrDefault(p.playerId(), ManualContractPlanner.RoleFact.unknown(p.playerId()));
                boolean sameAcc = p.identity() != null && !f.deleted() && !f.unknown()
                        && p.identity().getAccount().equals(f.account());
                boolean changed = p.identity() != null && !f.unknown()
                        && (f.deleted() || !sameAcc || f.ownerRevision() != p.identity().getOwnerRevision());
                out.addParties(ManualContractParty.newBuilder()
                        .setParty(p.name())
                        .setPlayerId(p.playerId())
                        .setContractOwnerRevision(p.identity() == null ? 0L : p.identity().getOwnerRevision())
                        .setCurrentOwnerRevision(f.deleted() || f.unknown() ? 0L : f.ownerRevision())
                        .setSameAccount(sameAcc)
                        .setDeleted(f.deleted())
                        .setFrozen(f.frozen())
                        .setUnknown(f.unknown())
                        .setChanged(changed)
                        .build());
            }
            TradeCommerceRow linked = null;
            if (row.getKind() == BID_KIND && row.getParentId() != 0L) {
                linked = store.get(row.getParentId());
            } else if (row.getKind() == 5 && w.getWinningBidId() != 0L) {
                linked = store.get(w.getWinningBidId());
            }
            if (linked != null) {
                out.setLinked(toRecord(linked));
            }
            // 对无额外参数的动作给出试算结论，对 partial/redirect 提示候选
            long now = clock.getAsLong();
            for (ManualContractPlanner.Action a : ManualContractPlanner.Action.values()) {
                if (a == ManualContractPlanner.Action.PARTIAL || a == ManualContractPlanner.Action.REDIRECT) {
                    out.addActions(ManualContractActionVerdict.newBuilder()
                            .setAction(a.wire())
                            .setAllowed(false)
                            .setDetail("需要额外参数(" + (a == ManualContractPlanner.Action.PARTIAL ? "parts" : "party/to_player_id") + ")")
                            .build());
                    continue;
                }
                if (a == ManualContractPlanner.Action.VOID_AUCTION && (row.getKind() != 5 || linked == null)) {
                    out.addActions(ManualContractActionVerdict.newBuilder()
                            .setAction(a.wire())
                            .setAllowed(false)
                            .setDetail("非带出价拍品")
                            .build());
                    continue;
                }
                try {
                    ManualContractPlanner.Request probe = new ManualContractPlanner.Request(
                            row.getCommerceId(), row.getVersion(), a, "show-probe", "show-probe",
                            List.of(), "", 0L,
                            a == ManualContractPlanner.Action.VOID_AUCTION ? linked.getCommerceId() : 0L,
                            a == ManualContractPlanner.Action.VOID_AUCTION ? linked.getVersion() : 0L,
                            "", "");
                    ManualContractPlanner.PlanResult plan = executeOrDryRun(probe, now, false, deadline);
                    out.addActions(ManualContractActionVerdict.newBuilder()
                            .setAction(a.wire())
                            .setAllowed(true)
                            .setDetail("-> " + plan.stage + " new_legs=" + plan.newLegs)
                            .build());
                } catch (RuntimeException refused) {
                    out.addActions(ManualContractActionVerdict.newBuilder()
                            .setAction(a.wire())
                            .setAllowed(false)
                            .setDetail(refused.getMessage() == null ? "不允许" : refused.getMessage())
                            .build());
                }
            }
            return out.build();
        } catch (SQLException failure) {
            return ResolveManualContractResponse.newBuilder()
                    .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_RETRY)
                    .setTarget(environment)
                    .setRefusal(failure.getMessage() == null ? "读库失败" : failure.getMessage())
                    .build();
        }
    }

    /** 锁下执行（或 dry-run 试算）人工处置。 */
    public ManualContractPlanner.PlanResult executeOrDryRun(
            ManualContractPlanner.Request req, long nowMs, boolean execute, Deadline deadline) throws SQLException {
        req.validate();
        TradeCommerceRow pre = store.get(req.commerceId());
        if (pre == null) {
            throw new ManualContractPlanner.NotFoundException("合同不存在: " + Long.toUnsignedString(req.commerceId()));
        }
        Optional<ManualContractPlanner.PlanResult> priorDone = findAlreadyExecuted(pre, req.approvalId());
        if (priorDone.isPresent()) {
            return priorDone.get();
        }
        if (pre.getVersion() != req.expectVersion()) {
            throw new ManualContractPlanner.VersionChangedException("合同版本已变化(current=" + pre.getVersion()
                    + " expect=" + req.expectVersion() + ")");
        }
        List<Long> playersToLock = new ArrayList<>(partyPlayers(pre));
        long lotIdToLock = 0L;
        if (pre.getKind() == BID_KIND) {
            lotIdToLock = pre.getParentId();
        } else if (req.action() == ManualContractPlanner.Action.VOID_AUCTION && pre.getKind() == 5) {
            lotIdToLock = pre.getCommerceId();
            TradeCommerceRow bidPre = store.get(req.linkedId());
            if (bidPre == null) {
                throw new ManualContractPlanner.NotAllowedException("出价不存在: " + Long.toUnsignedString(req.linkedId()));
            }
            if (bidPre.getVersion() != req.linkedVersion()) {
                throw new ManualContractPlanner.VersionChangedException("出价版本已变化(current=" + bidPre.getVersion()
                        + " expect=" + req.linkedVersion() + ")");
            }
            playersToLock.addAll(partyPlayers(bidPre));
        }
        if (req.action() == ManualContractPlanner.Action.REDIRECT) {
            playersToLock.add(req.toPlayerId());
        }
        final long finalLotId = lotIdToLock;
        return store.transaction(deadline, c -> {
            // 1. 按无符号 player_id 升序锁角色归属
            Map<Long, ManualContractPlanner.RoleFact> facts = roles.read(c, playersToLock, true);
            ManualContractPlanner.RoleFact target = req.action() == ManualContractPlanner.Action.REDIRECT
                    ? facts.get(req.toPlayerId()) : null;
            // 2. 拍品 guard + 拍品行
            TradeCommerceRow lot = null;
            if (finalLotId != 0L) {
                CommerceStore.lockAuctionGuard(c, finalLotId);
                lot = store.lock(c, finalLotId);
            }
            // 3. 目标合同行
            TradeCommerceRow locked = (finalLotId == req.commerceId()) ? lot : store.lock(c, req.commerceId());
            if (locked == null) {
                throw new ManualContractPlanner.NotFoundException("合同不存在: " + Long.toUnsignedString(req.commerceId()));
            }
            Optional<ManualContractPlanner.PlanResult> lockedPrior = findAlreadyExecuted(locked, req.approvalId());
            if (lockedPrior.isPresent()) {
                return lockedPrior.get();
            }
            if (locked.getVersion() != req.expectVersion()) {
                throw new ManualContractPlanner.VersionChangedException("合同版本已变化(current=" + locked.getVersion()
                        + " expect=" + req.expectVersion() + ")");
            }
            TradeCommerceWorkflow.Builder w;
            try {
                w = TradeCommerceWorkflow.parseFrom(locked.getWorkflowProto()).toBuilder();
            } catch (InvalidProtocolBufferException bad) {
                throw new ManualContractPlanner.NotAllowedException("workflow 解不开，只能取证后手工修数据: " + bad.getMessage());
            }
            TradeCommerceWorkflow lotWorkflow = null;
            if (locked.getKind() == BID_KIND && lot != null) {
                try {
                    lotWorkflow = TradeCommerceWorkflow.parseFrom(lot.getWorkflowProto());
                } catch (InvalidProtocolBufferException ignored) {
                }
            }
            // 4. void-auction 联动出价锁在拍品之后
            TradeCommerceRow.Builder bidBuilder = null;
            TradeCommerceWorkflow.Builder bidWorkflow = null;
            List<ManualContractPlanner.PartyRoleChange> bidChanges = List.of();
            if (req.action() == ManualContractPlanner.Action.VOID_AUCTION && locked.getKind() == 5) {
                TradeCommerceRow bid = store.lock(c, req.linkedId());
                if (bid == null) {
                    throw new ManualContractPlanner.NotAllowedException("出价不存在: " + Long.toUnsignedString(req.linkedId()));
                }
                if (bid.getVersion() != req.linkedVersion()) {
                    throw new ManualContractPlanner.VersionChangedException("出价版本已变化(current=" + bid.getVersion()
                            + " expect=" + req.linkedVersion() + ")");
                }
                try {
                    bidWorkflow = TradeCommerceWorkflow.parseFrom(bid.getWorkflowProto()).toBuilder();
                } catch (InvalidProtocolBufferException bad) {
                    throw new ManualContractPlanner.NotAllowedException("出价 workflow 解不开: " + bad.getMessage());
                }
                settleLegOutcomes(c, bidWorkflow, Set.of(), false);
                bidBuilder = bid.toBuilder();
                bidChanges = ManualContractPlanner.detectRoleChanges(bid, bidWorkflow, facts);
            }
            // 5. 补记已终局腿并规划
            Set<String> partialNamed = new HashSet<>();
            if (req.action() == ManualContractPlanner.Action.PARTIAL && req.parts() != null) {
                for (var p : req.parts()) {
                    partialNamed.add(p.leg());
                }
            }
            settleLegOutcomes(c, w, partialNamed, req.action() == ManualContractPlanner.Action.REDIRECT);
            List<ManualContractPlanner.PartyRoleChange> changes = ManualContractPlanner.detectRoleChanges(locked, w, facts);
            TradeCommerceRow.Builder rBuilder = locked.toBuilder();
            ManualContractPlanner.Context ctx = new ManualContractPlanner.Context(
                    req, changes, target, lot, lotWorkflow, bidBuilder, bidWorkflow, bidChanges);
            ManualContractPlanner.PlanResult res = ManualContractPlanner.planManualAction(rBuilder, w, ctx);
            res.versionBefore = locked.getVersion();
            res.audit = ManualContractPlanner.buildAudit(req, res, nowMs);
            if (bidBuilder != null && res.linked != null) {
                res.linked.versionBefore = req.linkedVersion();
                TradeCommerceManualAction.Builder bidAudit = TradeCommerceManualAction.newBuilder()
                        .setAction(req.action().wire())
                        .setOperator(req.operator())
                        .setReason(req.reason())
                        .setAtMs(nowMs)
                        .setVersionBefore(req.linkedVersion())
                        .setLinkedCommerceId(locked.getCommerceId())
                        .setApprovalId(req.approvalId() == null ? "" : req.approvalId())
                        .setApprover(req.approver() == null ? "" : req.approver());
                if (res.linked.quarantine != null) {
                    bidAudit.setQuarantine(res.linked.quarantine);
                }
                res.linked.audit = bidAudit.build();
            }
            if (!execute) {
                return res;
            }
            w.addManualActions(res.audit);
            rBuilder.setWorkflowProto(w.build().toByteString()).setUpdatedMs(nowMs);
            store.save(c, rBuilder);
            if (bidBuilder != null && bidWorkflow != null && res.linked != null) {
                bidWorkflow.addManualActions(res.linked.audit);
                bidBuilder.setWorkflowProto(bidWorkflow.build().toByteString()).setUpdatedMs(nowMs);
                store.save(c, bidBuilder);
            }
            return res;
        });
    }

    private void settleLegOutcomes(Connection c, TradeCommerceWorkflow.Builder w,
            Set<String> partialLegs, boolean unissuedOk) throws SQLException {
        for (var l : w.getLegsBuilderList()) {
            if (!l.getSupersededBy().isEmpty() || l.getOutcome() != 0) {
                continue;
            }
            if (l.getOpId() == 0L) {
                if (unissuedOk || isSellerProceeds(l)) {
                    continue;
                }
                throw new ManualContractPlanner.NotAllowedException("腿 " + l.getRole() + " 尚未发出");
            }
            var ops = store.ops(c, "op_id=?", l.getOpId());
            if (ops.size() != 1) {
                throw new ManualContractPlanner.NotAllowedException("腿 " + l.getRole() + " op_id="
                        + Long.toUnsignedString(l.getOpId()) + " 的资产待办行不存在");
            }
            TradeAssetOpRow op = ops.getFirst();
            if (op.getStatus() == 1) {
                throw new ManualContractPlanner.NotAllowedException("腿 " + l.getRole() + " op_id="
                        + Long.toUnsignedString(l.getOpId()) + " 仍在途，请等终局");
            }
            if (op.getStatus() < 2 || op.getStatus() > 6) {
                throw new ManualContractPlanner.NotAllowedException("腿 " + l.getRole() + " 资产待办状态非法: " + op.getStatus());
            }
            if (op.getStatus() == OUTCOME_PARTIAL && !partialLegs.contains(l.getRole())) {
                throw new ManualContractPlanner.NotAllowedException("腿 " + l.getRole() + " 部分发放，请用 partial 给出缺口");
            }
            l.setOutcome(op.getStatus());
            if (!op.getEscrowSnapshot().isEmpty()) {
                try {
                    l.setSnapshot(AssetSnapshot.parseFrom(op.getEscrowSnapshot()));
                } catch (InvalidProtocolBufferException bad) {
                    throw new ManualContractPlanner.NotAllowedException("托管快照损坏: " + bad.getMessage());
                }
            }
        }
    }

    private static Optional<ManualContractPlanner.PlanResult> findAlreadyExecuted(TradeCommerceRow row, String approvalId) {
        if (approvalId == null || approvalId.isEmpty()) {
            return Optional.empty();
        }
        try {
            TradeCommerceWorkflow w = TradeCommerceWorkflow.parseFrom(row.getWorkflowProto());
            for (TradeCommerceManualAction act : w.getManualActionsList()) {
                if (approvalId.equals(act.getApprovalId())) {
                    ManualContractPlanner.PlanResult done = new ManualContractPlanner.PlanResult();
                    done.alreadyExecuted = true;
                    done.versionBefore = act.getVersionBefore();
                    done.quarantine = act.hasQuarantine() ? act.getQuarantine() : null;
                    done.stage = w.getStage();
                    done.status = row.getStatus();
                    done.audit = act;
                    return Optional.of(done);
                }
            }
        } catch (InvalidProtocolBufferException ignored) {
        }
        return Optional.empty();
    }

    private static List<Long> partyPlayers(TradeCommerceRow r) {
        List<Long> ids = new ArrayList<>(2);
        if (r.getSellerPlayerId() != 0L) {
            ids.add(r.getSellerPlayerId());
        }
        if (r.getBuyerPlayerId() != 0L) {
            ids.add(r.getBuyerPlayerId());
        }
        return ids;
    }

    private static TradeCommerceRecord toRecord(TradeCommerceRow r) {
        return TradeCommerceRecord.newBuilder()
                .setCommerceId(r.getCommerceId())
                .setKind(r.getKind())
                .setStatus(r.getStatus())
                .setVersion(r.getVersion())
                .setSellerPlayerId(r.getSellerPlayerId())
                .setBuyerPlayerId(r.getBuyerPlayerId())
                .setParentId(r.getParentId())
                .setCounterpartyId(r.getCounterpartyId())
                .setMarketId(r.getMarketId())
                .setItemConfigId(r.getItemConfigId())
                .setSide(r.getSide())
                .setUnitPrice(r.getUnitPrice())
                .setCurrencyType(r.getCurrencyType())
                .setQuantity(r.getQuantity())
                .setFilledQuantity(r.getFilledQuantity())
                .setWorkflowProto(r.getWorkflowProto())
                .setRequestKey(r.getRequestKey())
                .setRequestDigest(r.getRequestDigest())
                .setCreatedMs(r.getCreatedMs())
                .setUpdatedMs(r.getUpdatedMs())
                .setExpiresMs(r.getExpiresMs())
                .build();
    }

    private ResolveManualContractResponse refused(String message) {
        return ResolveManualContractResponse.newBuilder()
                .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_REFUSED)
                .setTarget(environment)
                .setRefusal(message)
                .build();
    }

    /** 构造结构化审计行：所有自由文本（operator/reason/refusal/target）统一双引号转义防日志注入。 */
    public static String formatAuditLine(String mode, String target, ManualContractPlanner.Request req,
            ManualContractPlanner.PlanResult res, String verdict, String refusal) {
        StringBuilder sb = new StringBuilder("[CommerceManual]");
        sb.append(" mode=").append(mode)
                .append(" target=\"").append(quote(target)).append('"')
                .append(" commerce_id=").append(Long.toUnsignedString(req.commerceId()))
                .append(" expect_version=").append(Long.toUnsignedString(req.expectVersion()))
                .append(" action=").append(req.action().wire())
                .append(" operator=\"").append(quote(req.operator())).append('"')
                .append(" reason=\"").append(quote(req.reason())).append('"')
                .append(" verdict=").append(verdict);
        if (res != null) {
            sb.append(" stage=").append(res.stage)
                    .append(" status=").append(res.status)
                    .append(" already_executed=").append(res.alreadyExecuted);
        }
        if (req.approvalId() != null && !req.approvalId().isEmpty()) {
            sb.append(" approval_id=\"").append(quote(req.approvalId())).append('"')
                    .append(" approver=\"").append(quote(req.approver())).append('"');
        }
        if (refusal != null && !refusal.isEmpty()) {
            sb.append(" refusal=\"").append(quote(refusal)).append('"');
        }
        return sb.toString();
    }

    static String quote(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replaceAll("\\p{Cntrl}", "?");
    }
}
