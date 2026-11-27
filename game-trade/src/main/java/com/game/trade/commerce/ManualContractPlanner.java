package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.game.proto.AssetBundle;
import com.game.proto.AssetItemInstance;
import com.game.proto.CurrencyAmount;
import com.game.proto.ItemGrant;
import com.game.proto.trade.TradeCommerceLeg;
import com.game.proto.trade.TradeCommerceManualAction;
import com.game.proto.trade.TradeCommercePartialPart;
import com.game.proto.trade.TradeCommerceQuarantine;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.proto.trade.TradeRoleIdentity;
import com.game.trade.store.pb.TradeCommerceRow;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 交易转人工合同处置纯规则引擎（对齐主仓 {@code go/trade/internal/commerce/manual_plan.go}，
 * 见 {@code docs/design/jubaozhai-market.md} §15.7、§15.7.1）。
 *
 * <p>纯函数、不访问数据库：先完整校验所有拒绝条件再修改 builder，拒绝时合同原样不动；
 * 仅修改合同行字段与待发新腿（{@code op_id=0}），绝不直接改玩家余额或资产待办表。
 */
public final class ManualContractPlanner {

    static final int MAX_NEW_LEGS_PER_STEP = 8;
    static final int MANUAL_OPERATOR_MAX_BYTES = 64;
    static final int MANUAL_REASON_MAX_BYTES = 191;
    static final String PARTY_SELLER = "seller";
    static final String PARTY_BUYER = "buyer";

    private ManualContractPlanner() {
    }

    /** 6 种人工处置动作。 */
    public enum Action {
        RESUME("resume"),
        REISSUE("reissue"),
        REFUND("refund"),
        PARTIAL("partial"),
        REDIRECT("redirect"),
        VOID_AUCTION("void-auction");

        private final String wire;

        Action(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Optional<Action> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            for (Action a : values()) {
                if (a.wire.equals(raw)) {
                    return Optional.of(a);
                }
            }
            return Optional.empty();
        }
    }

    /** 前置条件不满足（拒绝且未改动）。 */
    public static final class NotAllowedException extends RuntimeException {
        public NotAllowedException(String message) {
            super(message);
        }
    }

    /** 合同（或联动出价）版本已变化（拒绝且未改动）。 */
    public static final class VersionChangedException extends RuntimeException {
        public VersionChangedException(String message) {
            super(message);
        }
    }

    /** 合同不存在。 */
    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 一条腿的人工认定缺口：只允许点名实例 GUID、宝宝、整条货币（可堆叠数量物品整批成败，不入缺口）。 */
    public record PartialMissing(List<Long> instances, boolean pet, List<Integer> currencies) {
        public PartialMissing {
            instances = instances == null ? List.of() : List.copyOf(instances);
            currencies = currencies == null ? List.of() : List.copyOf(currencies);
        }

        public boolean isEmpty() {
            return instances.isEmpty() && !pet && currencies.isEmpty();
        }

        public String format() {
            List<String> parts = new ArrayList<>(instances.size() + currencies.size() + 1);
            for (long id : instances) {
                parts.add("instance:" + Long.toUnsignedString(id));
            }
            if (pet) {
                parts.add("pet");
            }
            for (int c : currencies) {
                parts.add("currency:" + Integer.toUnsignedString(c));
            }
            return String.join(",", parts);
        }
    }

    public record PartialPart(String leg, PartialMissing missing) {
    }

    public static PartialPart parsePartialPart(String spec) {
        if (spec == null) {
            throw new NotAllowedException("部分发放须写成 <腿名>=<缺口>");
        }
        int eq = spec.indexOf('=');
        String leg = eq >= 0 ? spec.substring(0, eq).trim() : "";
        String body = eq >= 0 ? spec.substring(eq + 1).trim() : "";
        if (eq < 0 || leg.isEmpty() || body.isEmpty()) {
            throw new NotAllowedException("部分发放须写成 <腿名>=<缺口>，缺口为逗号分隔的 instance:<guid>、pet、currency:<类型>");
        }
        return new PartialPart(leg, parsePartialMissing(body));
    }

    public static PartialMissing parsePartialMissing(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new NotAllowedException("缺口为空");
        }
        List<Long> instances = new ArrayList<>();
        List<Integer> currencies = new ArrayList<>();
        Set<Long> seenInst = new HashSet<>();
        Set<Integer> seenCur = new HashSet<>();
        boolean pet = false;
        for (String raw : spec.split(",", -1)) {
            String token = raw.trim();
            int colon = token.indexOf(':');
            if ("pet".equals(token)) {
                if (pet) {
                    throw new NotAllowedException("缺口里 pet 写了两次");
                }
                pet = true;
            } else if (colon > 0 && "instance".equals(token.substring(0, colon))) {
                String val = token.substring(colon + 1);
                long id;
                try {
                    id = Long.parseUnsignedLong(val);
                } catch (NumberFormatException bad) {
                    throw new NotAllowedException("实例 guid 不合法: " + token);
                }
                if (id == 0L) {
                    throw new NotAllowedException("实例 guid 不能为 0");
                }
                if (!seenInst.add(id)) {
                    throw new NotAllowedException("缺口里实例重复: " + Long.toUnsignedString(id));
                }
                instances.add(id);
            } else if (colon > 0 && "currency".equals(token.substring(0, colon))) {
                String val = token.substring(colon + 1);
                int cur;
                try {
                    cur = Integer.parseUnsignedInt(val);
                } catch (NumberFormatException bad) {
                    throw new NotAllowedException("货币类型不合法: " + token);
                }
                if (!seenCur.add(cur)) {
                    throw new NotAllowedException("缺口里货币类型重复: " + Integer.toUnsignedString(cur));
                }
                currencies.add(cur);
            } else {
                throw new NotAllowedException("不认识的缺口项: " + token + "（仅支持 instance:<guid>、pet、currency:<类型>）");
            }
        }
        return new PartialMissing(instances, pet, currencies);
    }

    /** 一次人工处置请求参数。 */
    public record Request(
            long commerceId,
            long expectVersion,
            Action action,
            String operator,
            String reason,
            List<PartialPart> parts,
            String party,
            long toPlayerId,
            long linkedId,
            long linkedVersion,
            String approvalId,
            String approver) {

        public void validate() {
            if (commerceId == 0L || expectVersion == 0L) {
                throw new NotAllowedException("commerce_id 和 expect_version 都必须非 0");
            }
            if (action == null) {
                throw new NotAllowedException("未知人工处置动作");
            }
            validateText("operator", operator, MANUAL_OPERATOR_MAX_BYTES);
            validateText("reason", reason, MANUAL_REASON_MAX_BYTES);
            boolean hasApprovalId = approvalId != null && !approvalId.isEmpty();
            boolean hasApprover = approver != null && !approver.isEmpty();
            if (hasApprovalId != hasApprover) {
                throw new NotAllowedException("批准号与批准人要么都给，要么都不给");
            }
            List<PartialPart> partList = parts == null ? List.of() : parts;
            if (action == Action.PARTIAL) {
                if (partList.isEmpty() || partList.size() > MAX_NEW_LEGS_PER_STEP) {
                    throw new NotAllowedException("partial 要给出 1~" + MAX_NEW_LEGS_PER_STEP + " 条 <腿名>=<缺口>");
                }
                Set<String> seen = new HashSet<>();
                for (PartialPart p : partList) {
                    if (p == null || p.leg() == null || p.leg().isEmpty() || p.missing() == null || p.missing().isEmpty()) {
                        throw new NotAllowedException("partial 的每条都要有腿名和缺口");
                    }
                    if (!seen.add(p.leg())) {
                        throw new NotAllowedException("腿 " + p.leg() + " 给了两次");
                    }
                }
            } else if (!partList.isEmpty()) {
                throw new NotAllowedException("缺口只用于 partial");
            }
            if (action == Action.REDIRECT) {
                if (!PARTY_SELLER.equals(party) && !PARTY_BUYER.equals(party)) {
                    throw new NotAllowedException("redirect 的 party 只能是 seller 或 buyer");
                }
                if (toPlayerId == 0L) {
                    throw new NotAllowedException("redirect 要给目标角色");
                }
            } else if ((party != null && !party.isEmpty()) || toPlayerId != 0L) {
                throw new NotAllowedException("party、目标角色只用于 redirect");
            }
            if (action == Action.VOID_AUCTION) {
                if (linkedId == 0L || linkedVersion == 0L || linkedId == commerceId) {
                    throw new NotAllowedException("void-auction 要给拍品引用的出价号和出价版本");
                }
            } else if (linkedId != 0L || linkedVersion != 0L) {
                throw new NotAllowedException("联动的出价只用于 void-auction");
            }
        }

        private static void validateText(String name, String value, int limitBytes) {
            if (value == null || value.isBlank()) {
                throw new NotAllowedException(name + " 不能为空");
            }
            if (value.getBytes(StandardCharsets.UTF_8).length > limitBytes) {
                throw new NotAllowedException(name + " 不能超过 " + limitBytes + " 字节");
            }
            if (value.codePoints().anyMatch(Character::isISOControl)) {
                throw new NotAllowedException(name + " 不能带换行或控制字符");
            }
        }
    }

    /** 锁内读到的角色归属现状。 */
    public record RoleFact(long playerId, String account, long ownerRevision, boolean deleted, boolean frozen, boolean unknown) {
        public static RoleFact deleted(long playerId) {
            return new RoleFact(playerId, "", 0L, true, false, false);
        }

        public static RoleFact unknown(long playerId) {
            return new RoleFact(playerId, "", 0L, false, false, true);
        }

        public static RoleFact present(long playerId, String account, long ownerRevision, boolean frozen) {
            return new RoleFact(playerId, account == null ? "" : account, ownerRevision, false, frozen, false);
        }
    }

    /** 一方的角色已不可逆不归原身份（删角 / 换账号 / 修订号变更）。 */
    public record PartyRoleChange(String party, long playerId, String reason) {
    }

    /** 合同中一方的视图。 */
    record ContractParty(String name, long playerId, TradeRoleIdentity identity) {
    }

    /** 除合同本身外锁内可见的上下文事实。 */
    public static final class Context {
        final Request req;
        final List<PartyRoleChange> changes;
        final RoleFact redirectTarget;
        final TradeCommerceRow lot;
        final TradeCommerceWorkflow lotWorkflow;
        final TradeCommerceRow.Builder bid;
        final TradeCommerceWorkflow.Builder bidWorkflow;
        final List<PartyRoleChange> bidChanges;

        public Context(Request req, List<PartyRoleChange> changes, RoleFact redirectTarget,
                TradeCommerceRow lot, TradeCommerceWorkflow lotWorkflow,
                TradeCommerceRow.Builder bid, TradeCommerceWorkflow.Builder bidWorkflow,
                List<PartyRoleChange> bidChanges) {
            this.req = req;
            this.changes = changes == null ? List.of() : List.copyOf(changes);
            this.redirectTarget = redirectTarget;
            this.lot = lot;
            this.lotWorkflow = lotWorkflow;
            this.bid = bid;
            this.bidWorkflow = bidWorkflow;
            this.bidChanges = bidChanges == null ? List.of() : List.copyOf(bidChanges);
        }
    }

    /** 处置计划 / 结果。 */
    public static final class PlanResult {
        public long versionBefore;
        public TradeCommerceQuarantine quarantine;
        public String stage = "";
        public int status;
        public final List<String> newLegs = new ArrayList<>();
        public final List<String> supersededLegs = new ArrayList<>();
        public String party = "";
        public long fromPlayer;
        public long toPlayer;
        public TradeRoleIdentity identityBefore;
        public TradeRoleIdentity identityAfter;
        public final List<String> retargetedLegs = new ArrayList<>();
        public final List<TradeCommercePartialPart> partialParts = new ArrayList<>();
        public long linkedId;
        public PlanResult linked;
        public TradeCommerceManualAction audit;
        public boolean alreadyExecuted;
    }

    /** 判断某一方的角色是否已不可逆变更（删角 / 账号变 / 修订号变）；冻结与未知不算不可逆变更。 */
    public static List<PartyRoleChange> detectRoleChanges(
            com.game.trade.store.pb.TradeCommerceRowOrBuilder r,
            com.game.proto.trade.TradeCommerceWorkflowOrBuilder w,
            Map<Long, RoleFact> facts) {
        List<PartyRoleChange> out = new ArrayList<>(2);
        for (ContractParty p : contractParties(r, w)) {
            if (p.identity() == null || p.identity().getAccount().isEmpty() || p.identity().getOwnerRevision() == 0) {
                continue;
            }
            RoleFact fact = facts.get(p.playerId());
            if (fact == null || fact.unknown()) {
                continue;
            }
            if (fact.deleted()) {
                out.add(new PartyRoleChange(p.name(), p.playerId(), "deleted"));
            } else if (!p.identity().getAccount().equals(fact.account())) {
                out.add(new PartyRoleChange(p.name(), p.playerId(), "account_changed"));
            } else if (fact.ownerRevision() != p.identity().getOwnerRevision()) {
                out.add(new PartyRoleChange(p.name(), p.playerId(), "revision_changed"));
            }
        }
        return out;
    }

    static List<ContractParty> contractParties(
            com.game.trade.store.pb.TradeCommerceRowOrBuilder r,
            com.game.proto.trade.TradeCommerceWorkflowOrBuilder w) {
        List<ContractParty> out = new ArrayList<>(2);
        if (r.getSellerPlayerId() != 0L) {
            out.add(new ContractParty(PARTY_SELLER, r.getSellerPlayerId(),
                    w.hasSellerIdentity() ? w.getSellerIdentity() : null));
        }
        if (r.getBuyerPlayerId() != 0L) {
            out.add(new ContractParty(PARTY_BUYER, r.getBuyerPlayerId(),
                    w.hasBuyerIdentity() ? w.getBuyerIdentity() : null));
        }
        return out;
    }

    /** 按动作原地规划合同修改；任何拒绝在修改前抛出 {@link NotAllowedException}。 */
    public static PlanResult planManualAction(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w, Context c) {
        if (r.getStatus() != MANUAL || !"manual".equals(w.getStage())) {
            throw new NotAllowedException("合同不在人工状态(status=" + r.getStatus() + " stage=" + w.getStage() + ")");
        }
        if (!w.hasQuarantine() || w.getQuarantine().getStage().isEmpty()) {
            throw new NotAllowedException("合同没有转人工记录，只能取证后手工处理");
        }
        TradeCommerceQuarantine q = w.getQuarantine();
        if (c.req.action() != Action.REDIRECT && !c.changes.isEmpty()) {
            throw new NotAllowedException("参与方已不归原身份(" + describeChanges(c.changes) + ")，请先用 redirect 改绑");
        }
        PlanResult result = new PlanResult();
        result.quarantine = q;
        switch (c.req.action()) {
            case RESUME -> planResume(r, w, q);
            case REISSUE -> planReissue(r, w, q, result);
            case REFUND -> planRefund(r, w, c, null, result);
            case PARTIAL -> planPartial(r, w, q, c, result);
            case REDIRECT -> planRedirect(r, w, q, c, result);
            case VOID_AUCTION -> planVoidAuction(r, w, c, result);
        }
        if (result.newLegs.size() > MAX_NEW_LEGS_PER_STEP) {
            throw new NotAllowedException("要新建 " + result.newLegs.size() + " 条腿，超过引擎单步上限 " + MAX_NEW_LEGS_PER_STEP);
        }
        result.stage = w.getStage();
        result.status = r.getStatus();
        return result;
    }

    private static void planResume(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w, TradeCommerceQuarantine q) {
        if (reissuable(q)) {
            throw new NotAllowedException("发放未到账，直接 resume 仍会转人工，请用 reissue 或 refund");
        }
        w.setStage(q.getStage());
        r.setStatus(q.getStatus());
    }

    private static void planReissue(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w,
            TradeCommerceQuarantine q, PlanResult result) {
        if (!reissuable(q)) {
            throw new NotAllowedException("reissue 只处理发放或付款修订扣回未到账的合同(reason=" + q.getReason() + " stage=" + q.getStage() + ")");
        }
        boolean isReversal = MANUAL_REASON_PAYMENT_REVERSAL.equals(q.getReason());
        List<TradeCommerceLeg.Builder> failed = isReversal ? failedOfficialReversalLegs(w) : failedCredits(w);
        if (failed.isEmpty()) {
            throw new NotAllowedException("没有未到账的发放或扣回腿");
        }
        // 先算好全部新腿再落 builder
        List<PlannedLeg> additions = new ArrayList<>(failed.size());
        for (var l : failed) {
            String nextRole = reissueRole(w, l.getRole());
            additions.add(new PlannedLeg(nextRole, l.getRole(), l.getPlayerId(), l.getEscrowOwnerId(),
                    l.getStream(), l.getBundle()));
        }
        for (int i = 0; i < failed.size(); i++) {
            var oldLeg = failed.get(i);
            var add = additions.get(i);
            oldLeg.setSupersededBy(add.role);
            if (isReversal) {
                add(w, add.role, add.playerId, add.stream, add.bundle);
            } else {
                addEscrowLeg(w, add.role, add.playerId, add.escrowOwnerId, add.bundle);
            }
            result.newLegs.add(add.role);
            result.supersededLegs.add(oldLeg.getRole());
        }
        w.setStage(q.getStage());
        r.setStatus(q.getStatus());
    }

    static boolean reissuable(TradeCommerceQuarantine q) {
        if (q == null) {
            return false;
        }
        return switch (q.getReason()) {
            case MANUAL_REASON_FINAL_CREDIT -> "deliver".equals(q.getStage()) || "return".equals(q.getStage());
            case MANUAL_REASON_RMB_RETURN -> true;
            case MANUAL_REASON_PAYMENT_REVERSAL -> STAGE_OFFICIAL_DELIVER.equals(q.getStage());
            default -> false;
        };
    }

    private static List<TradeCommerceLeg.Builder> failedOfficialReversalLegs(TradeCommerceWorkflow.Builder w) {
        List<TradeCommerceLeg.Builder> out = new ArrayList<>();
        for (var l : w.getLegsBuilderList()) {
            if (!l.getSupersededBy().isEmpty() || !done(l) || applied(l) || l.getOutcome() == OUTCOME_PARTIAL) {
                continue;
            }
            if (l.getStream() == 4 || (l.getStream() == 3 && isOfficialReverseLeg(l.getRole()))) {
                out.add(l);
            }
        }
        return out;
    }

    private static List<TradeCommerceLeg.Builder> failedCredits(TradeCommerceWorkflow.Builder w) {
        List<TradeCommerceLeg.Builder> out = new ArrayList<>();
        for (var l : w.getLegsBuilderList()) {
            if (l.getStream() == 4 && l.getSupersededBy().isEmpty()
                    && done(l) && !applied(l) && l.getOutcome() != OUTCOME_PARTIAL) {
                out.add(l);
            }
        }
        return out;
    }

    static String reissueRole(TradeCommerceWorkflow.Builder w, String role) {
        String base = role;
        int n = 1;
        int hash = role.lastIndexOf('#');
        if (hash >= 0) {
            try {
                int parsed = Integer.parseInt(role.substring(hash + 1));
                if (parsed >= 2) {
                    base = role.substring(0, hash);
                    n = parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        for (int k = n + 1; ; k++) {
            String candidate = base + "#" + k;
            if (leg(w, candidate) == null) {
                return candidate;
            }
        }
    }

    static String refundLegRole(String debitRole) {
        return debitRole + "/refund";
    }

    static boolean isRefundLeg(String role) {
        if (role == null) {
            return false;
        }
        int hash = role.indexOf('#');
        String base = hash >= 0 ? role.substring(0, hash) : role;
        return base.endsWith("/refund");
    }

    private static long refundRecipient(TradeCommerceRow.Builder r, TradeCommerceLegOrBuilderLike debit) {
        long party = r.getSellerPlayerId();
        if (debit.role.startsWith("buyer_debit")) {
            party = r.getBuyerPlayerId();
        }
        return party == 0L ? debit.playerId : party;
    }

    private record TradeCommerceLegOrBuilderLike(String role, long playerId) {
    }

    private record PlannedLeg(String role, String supersedes, long playerId, long escrowOwnerId, int stream, AssetBundle bundle) {
    }

    private static void planRefund(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w, Context c,
            Map<String, AssetBundle> taken, PlanResult result) {
        checkRefundBlockers(r, w, c);
        List<PlannedLeg> returns = computeRefundReturns(r, w, taken);
        applyRefund(r, w, returns, result);
    }

    private static void checkRefundBlockers(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w, Context c) {
        switch (r.getKind()) {
            case 1 -> throw new NotAllowedException("官方商城交付的退款走人民币订单，不在这里处理");
            case 6 -> throw new NotAllowedException("人民币寄售有独立退回流程，不在这里处理");
            case BID_KIND -> {
                if (c.lot != null) {
                    if (c.lotWorkflow == null) {
                        throw new NotAllowedException("拍品 " + Long.toUnsignedString(c.lot.getCommerceId()) + " 的 workflow 解不开，判不了是否引用这条出价");
                    }
                    if (c.lotWorkflow.getWinningBidId() == r.getCommerceId()) {
                        throw new NotAllowedException("拍品 " + Long.toUnsignedString(c.lot.getCommerceId())
                                + " 引用这条出价：拍品已结案用 resume，拍品转人工用 void-auction");
                    }
                }
            }
            case 4 -> {
                if (r.getSide() == 0) {
                    throw new NotAllowedException("成交子单资产来自两张母单，不能单独退回；未到账用 reissue 或 partial");
                }
                long qty = Integer.toUnsignedLong(r.getQuantity());
                long filled = Integer.toUnsignedLong(r.getFilledQuantity());
                if (filled > qty) {
                    throw new NotAllowedException("市场单已成交量超过总量，数据矛盾，需人工取证处理");
                }
                if (filled > 0 && filled == qty) {
                    throw new NotAllowedException("市场单已全部成交，无剩余托管可退；用 resume 等待成交子单收尾");
                }
            }
            case 5 -> {
                if (w.getHighestBidder() != 0L) {
                    throw new NotAllowedException("拍品已有出价，托管与竞价合同互相引用，请用 void-auction");
                }
            }
            default -> {
            }
        }
        checkRefundCommon(w);
    }

    private static void checkRefundCommon(TradeCommerceWorkflow.Builder w) {
        for (var l : w.getLegsList()) {
            if (l.getStream() == 4 && l.getOutcome() == OUTCOME_PARTIAL) {
                throw new NotAllowedException("发放腿 " + l.getRole() + " 部分到账过，不能整单退回；缺口未到账请用 reissue");
            }
            if (l.getStream() != 4 || !l.getSupersededBy().isEmpty()) {
                continue;
            }
            if (applied(l)) {
                throw new NotAllowedException("发放腿 " + l.getRole() + " 已经到账，不能整单退回；未到账请用 reissue");
            }
            if (isRefundLeg(l.getRole())) {
                throw new NotAllowedException("合同已经整单退回过(腿 " + l.getRole() + ")，退回未到账请用 reissue");
            }
        }
    }

    private static List<PlannedLeg> computeRefundReturns(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w,
            Map<String, AssetBundle> taken) {
        List<PlannedLeg> out = new ArrayList<>();
        for (var l : w.getLegsList()) {
            if (l.getStream() != 3 || !l.getSupersededBy().isEmpty()) {
                continue;
            }
            AssetBundle bundle;
            if (applied(l)) {
                try {
                    bundle = transfer(l);
                } catch (IllegalStateException bad) {
                    throw new NotAllowedException("腿 " + l.getRole() + " 已扣出但托管原件转不动，需按 player_snapshot 人工补发: " + bad.getMessage());
                }
                if (r.getKind() == 4 && r.getSide() != 0 && "seller_debit".equals(l.getRole())) {
                    try {
                        bundle = marketRemainder(r);
                    } catch (RuntimeException bad) {
                        throw new NotAllowedException(bad.getMessage());
                    }
                }
            } else if (l.getOutcome() == OUTCOME_PARTIAL) {
                bundle = taken == null ? null : taken.get(l.getRole());
                if (bundle == null) {
                    throw new NotAllowedException("腿 " + l.getRole() + " 部分扣出，请用 partial 给出缺口");
                }
            } else {
                continue;
            }
            if (isEmptyBundle(bundle)) {
                continue;
            }
            long recipient = refundRecipient(r, new TradeCommerceLegOrBuilderLike(l.getRole(), l.getPlayerId()));
            out.add(new PlannedLeg(refundLegRole(l.getRole()), "", recipient, l.getPlayerId(), 4, bundle));
        }
        return out;
    }

    private static void applyRefund(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w,
            List<PlannedLeg> returns, PlanResult result) {
        for (var l : failedCredits(w)) {
            l.setSupersededBy(Action.REFUND.wire());
            result.supersededLegs.add(l.getRole());
        }
        for (var l : w.getLegsBuilderList()) {
            if (isHeldUnissuedSellerProceeds(l)) {
                l.setSupersededBy(Action.REFUND.wire());
                result.supersededLegs.add(l.getRole());
            }
        }
        for (var p : returns) {
            addEscrowLeg(w, p.role, p.playerId, p.escrowOwnerId, p.bundle);
            result.newLegs.add(p.role);
        }
        w.setStage("return");
        r.setStatus(CANCEL_PENDING);
    }

    private record CreditShortfall(TradeCommerceLeg.Builder leg, AssetBundle missing) {
    }

    private static void planPartial(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w,
            TradeCommerceQuarantine q, Context c, PlanResult result) {
        if (r.getKind() == 1) {
            throw new NotAllowedException("官方商城的部分发放需与商城订单一起处理，不在这里处理");
        }
        List<CreditShortfall> credits = new ArrayList<>();
        Map<String, AssetBundle> taken = new LinkedHashMap<>();
        Set<String> named = new HashSet<>();
        List<TradeCommercePartialPart> parts = new ArrayList<>();
        for (PartialPart part : c.req.parts()) {
            var l = leg(w, part.leg());
            if (l == null) {
                throw new NotAllowedException("合同里没有腿 " + part.leg());
            }
            if (!l.getSupersededBy().isEmpty()) {
                throw new NotAllowedException("腿 " + l.getRole() + " 已被 " + l.getSupersededBy() + " 取代");
            }
            if (l.getOutcome() != OUTCOME_PARTIAL) {
                throw new NotAllowedException("腿 " + l.getRole() + " 不是部分发放(outcome=" + l.getOutcome() + ")");
            }
            named.add(l.getRole());
            if (l.getStream() == 4) {
                AssetBundle missing = creditShortfall(l.getBundle(), part.missing());
                credits.add(new CreditShortfall(l, missing));
                parts.add(TradeCommercePartialPart.newBuilder().setLeg(l.getRole()).setMissing(missing).build());
            } else {
                DebitSplit split = debitTaken(l.getBundle(), part.missing());
                taken.put(l.getRole(), split.taken);
                parts.add(TradeCommercePartialPart.newBuilder().setLeg(l.getRole()).setMissing(split.missing).build());
            }
        }
        for (var l : w.getLegsList()) {
            if (l.getSupersededBy().isEmpty() && l.getOutcome() == OUTCOME_PARTIAL && !named.contains(l.getRole())) {
                throw new NotAllowedException("腿 " + l.getRole() + " 也是部分发放，必须在同一次 partial 中一起给出缺口");
            }
        }
        if (!credits.isEmpty() && !taken.isEmpty()) {
            throw new NotAllowedException("发放腿与托管扣款腿不能在同一次 partial 中混合处理");
        }
        if (r.getKind() == 6 && !taken.isEmpty()) {
            throw new NotAllowedException("人民币寄售的托管部分扣出需与商城订单一起处理");
        }
        if (!taken.isEmpty()) {
            planRefund(r, w, c, taken, result);
            result.partialParts.addAll(parts);
            return;
        }
        for (CreditShortfall s : credits) {
            String nextRole = reissueRole(w, s.leg.getRole());
            s.leg.setSupersededBy(nextRole);
            addEscrowLeg(w, nextRole, s.leg.getPlayerId(), s.leg.getEscrowOwnerId(), s.missing);
            result.newLegs.add(nextRole);
            result.supersededLegs.add(s.leg.getRole());
        }
        result.partialParts.addAll(parts);
        w.setStage(q.getStage());
        r.setStatus(q.getStatus());
    }

    private record AssetUnit(String kind, long id) {
        @Override
        public String toString() {
            return "pet".equals(kind) ? "pet" : kind + ":" + Long.toUnsignedString(id);
        }
    }

    /** 校验发放腿缺口（scene 固定发放顺序：可堆叠物品整批 → 实例逐件 → 宝宝 → 货币逐条，缺口必须是后缀）。 */
    static AssetBundle creditShortfall(AssetBundle b, PartialMissing m) {
        if (m == null || m.isEmpty()) {
            throw new NotAllowedException("缺口为空");
        }
        if (b.getItemUuidsCount() > 0 || b.getPetId() != 0L) {
            throw new NotAllowedException("发放腿带扣出用选择字段，数据异常，需人工取证处理");
        }
        List<AssetUnit> order = new ArrayList<>();
        for (AssetItemInstance it : b.getItemInstancesList()) {
            order.add(new AssetUnit("instance", it.getItemUuid()));
        }
        if (b.hasPetInstance()) {
            order.add(new AssetUnit("pet", 0L));
        }
        for (CurrencyAmount cur : b.getCurrenciesList()) {
            order.add(new AssetUnit("currency", Integer.toUnsignedLong(cur.getCurrencyType())));
        }
        Map<AssetUnit, Integer> positions = new HashMap<>(order.size() * 2);
        for (int i = 0; i < order.size(); i++) {
            if (positions.put(order.get(i), i) != null) {
                throw new NotAllowedException("包内 " + order.get(i) + " 不止一条，缺口点名不清");
            }
        }
        Set<AssetUnit> missing = toUnits(m);
        int first = order.size();
        for (AssetUnit u : missing) {
            Integer pos = positions.get(u);
            if (pos == null) {
                throw new NotAllowedException("包里没有 " + u);
            }
            if (pos < first) {
                first = pos;
            }
        }
        for (int i = first; i < order.size(); i++) {
            if (!missing.contains(order.get(i))) {
                throw new NotAllowedException("缺口不符合 scene 发放顺序：" + order.get(first) + " 未到账，后面的 " + order.get(i) + " 却算到账");
            }
        }
        if (first == 0 && b.getItemsCount() == 0) {
            throw new NotAllowedException("缺口是整条腿：一件都没到账不是部分发放，请用 reissue");
        }
        AssetBundle.Builder out = AssetBundle.newBuilder();
        for (AssetItemInstance it : b.getItemInstancesList()) {
            if (missing.contains(new AssetUnit("instance", it.getItemUuid()))) {
                out.addItemInstances(it);
            }
        }
        if (missing.contains(new AssetUnit("pet", 0L))) {
            out.setPetInstance(b.getPetInstance());
        }
        for (CurrencyAmount cur : b.getCurrenciesList()) {
            if (missing.contains(new AssetUnit("currency", Integer.toUnsignedLong(cur.getCurrencyType())))) {
                out.addCurrencies(cur);
            }
        }
        return out.build();
    }

    record DebitSplit(AssetBundle taken, AssetBundle missing) {
    }

    /** 校验托管扣款腿缺口：实例未扣齐则宝宝与货币皆未扣；已扣到实例或宝宝则拒绝退回（无 escrow_snapshot）。 */
    static DebitSplit debitTaken(AssetBundle b, PartialMissing m) {
        if (m == null || m.isEmpty()) {
            throw new NotAllowedException("缺口为空");
        }
        Set<Long> selected = new HashSet<>(b.getItemUuidsList());
        for (long id : m.instances()) {
            if (!selected.contains(id)) {
                throw new NotAllowedException("包里没有实例 " + Long.toUnsignedString(id));
            }
        }
        if (m.pet() && b.getPetId() == 0L) {
            throw new NotAllowedException("包里没有宝宝");
        }
        Map<Integer, Integer> curPos = new HashMap<>(b.getCurrenciesCount() * 2);
        for (int i = 0; i < b.getCurrenciesCount(); i++) {
            int t = b.getCurrencies(i).getCurrencyType();
            if (curPos.put(t, i) != null) {
                throw new NotAllowedException("包里货币 " + Integer.toUnsignedString(t) + " 不止一条，缺口点名不清");
            }
        }
        Set<Integer> missingCur = new HashSet<>();
        int firstCur = b.getCurrenciesCount();
        for (int t : m.currencies()) {
            Integer pos = curPos.get(t);
            if (pos == null) {
                throw new NotAllowedException("包里没有货币 " + Integer.toUnsignedString(t));
            }
            missingCur.add(t);
            if (pos < firstCur) {
                firstCur = pos;
            }
        }
        boolean allCurMissing = m.currencies().size() == b.getCurrenciesCount();
        if (!m.currencies().isEmpty() && m.currencies().size() != b.getCurrenciesCount() - firstCur) {
            throw new NotAllowedException("缺口不符合 scene 扣款顺序：货币缺的必须是包里最后几条");
        }
        if (!m.instances().isEmpty() && ((b.getPetId() != 0L && !m.pet()) || !allCurMissing)) {
            throw new NotAllowedException("缺口不符合 scene 扣款顺序：有实例没扣到时，宝宝和全部货币也不会扣");
        }
        if (m.pet() && !allCurMissing) {
            throw new NotAllowedException("缺口不符合 scene 扣款顺序：宝宝没扣到时，全部货币也不会扣");
        }
        if (m.instances().size() < b.getItemUuidsCount() || (b.getPetId() != 0L && !m.pet())) {
            throw new NotAllowedException("扣到的部分含实例或宝宝：部分扣出不留托管原件，拒绝自动退回，需按 player_snapshot 人工补发");
        }
        AssetBundle.Builder taken = AssetBundle.newBuilder();
        for (ItemGrant item : b.getItemsList()) {
            taken.addItems(item);
        }
        AssetBundle.Builder missing = AssetBundle.newBuilder();
        for (long id : m.instances()) {
            missing.addItemUuids(id);
        }
        if (m.pet()) {
            missing.setPetId(b.getPetId());
        }
        for (CurrencyAmount cur : b.getCurrenciesList()) {
            if (missingCur.contains(cur.getCurrencyType())) {
                missing.addCurrencies(cur);
            } else {
                taken.addCurrencies(cur);
            }
        }
        AssetBundle takenBuilt = taken.build();
        if (isEmptyBundle(takenBuilt)) {
            throw new NotAllowedException("缺口是整条腿：一件都没扣到不是部分扣出");
        }
        return new DebitSplit(takenBuilt, missing.build());
    }

    private static void planRedirect(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w,
            TradeCommerceQuarantine q, Context c, PlanResult result) {
        if (r.getKind() == 1 || r.getKind() == 6) {
            throw new NotAllowedException("官方商城与人民币寄售不走 redirect 改绑");
        }
        List<ContractParty> parties = contractParties(r, w);
        ContractParty party = parties.stream().filter(p -> p.name().equals(c.req.party())).findFirst().orElse(null);
        if (party == null) {
            throw new NotAllowedException("合同没有 " + c.req.party() + " 一方");
        }
        if (party.identity() == null || party.identity().getAccount().isEmpty()) {
            throw new NotAllowedException("合同缺少 " + party.name() + " 的原归属身份，需取证后手工处理");
        }
        boolean partyChanged = c.changes.stream().anyMatch(ch -> ch.party().equals(party.name()));
        if (!partyChanged) {
            throw new NotAllowedException(party.name() + " 的角色 " + Long.toUnsignedString(party.playerId()) + " 仍归原身份，不能改绑");
        }
        if (c.redirectTarget == null || c.redirectTarget.unknown() || c.redirectTarget.deleted() || c.redirectTarget.frozen()) {
            throw new NotAllowedException("目标角色 " + Long.toUnsignedString(c.req.toPlayerId()) + " 不存在、已删除或被冻结，不能改绑");
        }
        if (!party.identity().getAccount().equals(c.redirectTarget.account())) {
            throw new NotAllowedException("目标角色 " + Long.toUnsignedString(c.req.toPlayerId()) + " 不属于原账号");
        }
        for (ContractParty other : parties) {
            if (!other.name().equals(party.name()) && other.playerId() == c.req.toPlayerId()) {
                throw new NotAllowedException("目标角色 " + Long.toUnsignedString(c.req.toPlayerId()) + " 是合同的另一方");
            }
        }
        TradeRoleIdentity after = TradeRoleIdentity.newBuilder()
                .setAccount(party.identity().getAccount())
                .setOwnerRevision(c.redirectTarget.ownerRevision())
                .build();
        result.party = party.name();
        result.fromPlayer = party.playerId();
        result.toPlayer = c.req.toPlayerId();
        result.identityBefore = party.identity();
        result.identityAfter = after;

        if (PARTY_SELLER.equals(party.name())) {
            r.setSellerPlayerId(c.req.toPlayerId());
            w.setSellerIdentity(after);
        } else {
            r.setBuyerPlayerId(c.req.toPlayerId());
            w.setBuyerIdentity(after);
            if (r.getKind() == 5 && w.getHighestBidder() == party.playerId()) {
                w.setHighestBidder(c.req.toPlayerId());
            }
        }
        if (c.req.toPlayerId() != party.playerId()) {
            List<TradeCommerceLeg.Builder> snapshot = new ArrayList<>(w.getLegsBuilderList());
            for (var l : snapshot) {
                if (!l.getSupersededBy().isEmpty() || l.getPlayerId() != party.playerId()) {
                    continue;
                }
                if (l.getOpId() == 0L) {
                    l.setPlayerId(c.req.toPlayerId());
                    result.retargetedLegs.add(l.getRole());
                } else if (l.getStream() == 4 && done(l) && !applied(l) && l.getOutcome() != OUTCOME_PARTIAL) {
                    String nextRole = reissueRole(w, l.getRole());
                    l.setSupersededBy(nextRole);
                    addEscrowLeg(w, nextRole, c.req.toPlayerId(), l.getEscrowOwnerId(), l.getBundle());
                    result.newLegs.add(nextRole);
                    result.supersededLegs.add(l.getRole());
                }
            }
        }
        if (c.changes.size() > 1) {
            // 另一方也已不归原身份：保持人工状态，等另一方也 redirect 后再恢复
            return;
        }
        w.setStage(q.getStage());
        r.setStatus(q.getStatus());
    }

    private static void planVoidAuction(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w,
            Context c, PlanResult result) {
        if (r.getKind() != 5) {
            throw new NotAllowedException("void-auction 只用于拍品(当前 kind=" + r.getKind() + ")");
        }
        if (w.getHighestBidder() == 0L || w.getWinningBidId() == 0L) {
            throw new NotAllowedException("拍品没有出价，请用 refund");
        }
        if (c.bid == null || c.bidWorkflow == null) {
            throw new NotAllowedException("读不到拍品引用的出价 " + Long.toUnsignedString(w.getWinningBidId()));
        }
        if (w.getWinningBidId() != c.bid.getCommerceId()) {
            throw new NotAllowedException("拍品引用的是出价 " + Long.toUnsignedString(w.getWinningBidId())
                    + "，不是 " + Long.toUnsignedString(c.bid.getCommerceId()));
        }
        if (c.bid.getKind() != BID_KIND || c.bid.getParentId() != r.getCommerceId()) {
            throw new NotAllowedException("合同 " + Long.toUnsignedString(c.bid.getCommerceId()) + " 不是这件拍品的出价");
        }
        if (!c.bidChanges.isEmpty()) {
            throw new NotAllowedException("出价人已不归原身份(" + describeChanges(c.bidChanges) + ")，请先对出价 redirect");
        }
        boolean held = "bid_held".equals(c.bidWorkflow.getStage())
                || (c.bid.getStatus() == MANUAL && "manual".equals(c.bidWorkflow.getStage())
                    && c.bidWorkflow.hasQuarantine() && "bid_held".equals(c.bidWorkflow.getQuarantine().getStage()));
        if (!held) {
            throw new NotAllowedException("出价 " + Long.toUnsignedString(c.bid.getCommerceId())
                    + " 不在 bid_held(stage=" + c.bidWorkflow.getStage() + ")");
        }
        if (!applied(leg(c.bidWorkflow, "bid_debit"))) {
            throw new NotAllowedException("出价 " + Long.toUnsignedString(c.bid.getCommerceId()) + " 的冻结款未扣到");
        }
        for (var l : c.bidWorkflow.getLegsList()) {
            if (l.getStream() == 4 && l.getSupersededBy().isEmpty() && done(l)) {
                throw new NotAllowedException("出价 " + Long.toUnsignedString(c.bid.getCommerceId()) + " 已有发放腿 " + l.getRole());
            }
        }
        checkRefundCommon(w);
        var debit = leg(w, "seller_debit");
        if (!applied(debit)) {
            throw new NotAllowedException("拍品托管未扣到，与已有出价矛盾");
        }
        AssetBundle back;
        try {
            back = transfer(debit.build());
        } catch (IllegalStateException bad) {
            throw new NotAllowedException("拍品托管原件转不动，需按 player_snapshot 人工补发: " + bad.getMessage());
        }
        long recipient = refundRecipient(r, new TradeCommerceLegOrBuilderLike(debit.getRole(), debit.getPlayerId()));
        applyRefund(r, w, List.of(new PlannedLeg(refundLegRole(debit.getRole()), "", recipient, debit.getPlayerId(), 4, back)),
                result);

        PlanResult bidResult = new PlanResult();
        if (c.bid.getStatus() == MANUAL && c.bidWorkflow.hasQuarantine()) {
            bidResult.quarantine = c.bidWorkflow.getQuarantine();
        }
        c.bidWorkflow.setStage("bid_refund");
        c.bid.setStatus(CANCEL_PENDING);
        bidResult.stage = "bid_refund";
        bidResult.status = CANCEL_PENDING;
        result.linkedId = c.bid.getCommerceId();
        result.linked = bidResult;
    }

    private static Set<AssetUnit> toUnits(PartialMissing m) {
        Set<AssetUnit> units = new HashSet<>();
        for (long id : m.instances()) {
            units.add(new AssetUnit("instance", id));
        }
        if (m.pet()) {
            units.add(new AssetUnit("pet", 0L));
        }
        for (int c : m.currencies()) {
            units.add(new AssetUnit("currency", Integer.toUnsignedLong(c)));
        }
        return units;
    }

    private static String describeChanges(List<PartyRoleChange> changes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            PartyRoleChange ch = changes.get(i);
            sb.append(ch.party()).append('=').append(Long.toUnsignedString(ch.playerId()))
                    .append('(').append(ch.reason()).append(')');
        }
        return sb.toString();
    }

    /** 构造写入 {@code manual_actions} 的审计记录。 */
    static TradeCommerceManualAction buildAudit(Request req, PlanResult result, long nowMs) {
        TradeCommerceManualAction.Builder b = TradeCommerceManualAction.newBuilder()
                .setAction(req.action().wire())
                .setOperator(req.operator())
                .setReason(req.reason())
                .setAtMs(nowMs)
                .setVersionBefore(result.versionBefore)
                .addAllNewLegs(result.newLegs)
                .addAllSupersededLegs(result.supersededLegs)
                .setParty(result.party == null ? "" : result.party)
                .setFromPlayerId(result.fromPlayer)
                .setToPlayerId(result.toPlayer)
                .addAllRetargetedLegs(result.retargetedLegs)
                .setLinkedCommerceId(result.linkedId)
                .addAllPartialParts(result.partialParts)
                .setApprovalId(req.approvalId() == null ? "" : req.approvalId())
                .setApprover(req.approver() == null ? "" : req.approver());
        if (result.quarantine != null) {
            b.setQuarantine(result.quarantine);
        }
        if (result.identityBefore != null) {
            b.setIdentityBefore(result.identityBefore);
        }
        if (result.identityAfter != null) {
            b.setIdentityAfter(result.identityAfter);
        }
        return b.build();
    }
}
