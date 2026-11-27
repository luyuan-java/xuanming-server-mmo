package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.game.proto.AssetBundle;
import com.game.proto.AssetItemInstance;
import com.game.proto.CurrencyAmount;
import com.game.proto.ItemComp;
import com.game.proto.SealedSkillBookData;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.trade.CommerceOrderKind;
import com.game.proto.trade.TradeCommerceLeg;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.proto.trade.TradeWantedSkillBookTerms;
import com.game.proto.trade.WantedSkillBookOrderPhase;
import com.game.proto.trade.WantedSkillBookOrderView;
import com.game.table.ConfigTables;
import com.game.table.SkillScrollCraftTable;
import com.game.table.SkillTable;
import com.game.table.TradeErrorTip;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Objects;
import java.util.Optional;

/**
 * 悬赏求书求购单（kind 7，对齐主仓 {@code go/trade/internal/commerce/wanted_skill_book.go}）。
 *
 * <p>发榜人是买家（{@code buyer_player_id}），建单先托管流通银两（{@code wanted_escrow}）后全服挂榜（{@code wanted_open}）；
 * 揭榜人是卖家（{@code seller_player_id}），揭榜托管一枚未绑定常规封卷道籍 3414（{@code wanted_fill}），按扣出快照核对条款：
 * <ul>
 *   <li>相符：先交书给发榜人（{@code buyer_receive}），到账后再交税后 95% 银两给揭榜人（{@code seller_receive}，5% 税销毁）；
 *       若发榜人收书被拒，整单退回且退发榜人全额 100% 悬赏。</li>
 *   <li>不符：原样退书（{@code seller_return_<attempt>}），退书到账后清零揭榜人回到挂榜；书未扣到则直接回挂榜。</li>
 * </ul>
 */
public final class WantedSkillBook {

    public static final int KIND = CommerceOrderKind.COMMERCE_ORDER_KIND_SKILL_BOOK_WANTED_VALUE;
    public static final long REWARD_MIN_GOLD = 100L;
    public static final long REWARD_MAX_GOLD = 50_000L;
    public static final int MAX_ACTIVE_ORDERS = 3;
    public static final long TAX_BPS = 500L;
    public static final int SEALED_SKILL_BOOK_CONFIG_ID = 3414;

    public static final String STAGE_ESCROW = "wanted_escrow";
    public static final String STAGE_OPEN = "wanted_open";
    public static final String STAGE_FILL = "wanted_fill";

    public static final String FULFILL_SCOPE = "wanted_fulfill";
    public static final String DEBIT_PREFIX = "seller_debit_";
    public static final String RETURN_PREFIX = "seller_return_";

    public static final int ERR_LIMIT = TradeErrorTip.trade_error.kTradeWantedOrderLimitReached_VALUE;
    public static final int ERR_MISMATCH = TradeErrorTip.trade_error.kTradeWantedBookMismatch_VALUE;
    public static final int ERR_DISABLED = TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE;

    private WantedSkillBook() {
    }

    /** 技能在配表里的展示信息。 */
    public record SkillInfo(int scrollId, String skillName, String scrollName) {
    }

    /** 查询一门技能的展示信息及当前是否允许悬赏求书（生产读 ConfigTables，单测可注入内存实现）。 */
    public interface SkillCatalog {
        Optional<SkillInfo> lookup(int skillTableId);

        boolean isEligible(int skillTableId);

        static SkillCatalog fromTables(ConfigTables tables) {
            Objects.requireNonNull(tables, "tables");
            return new SkillCatalog() {
                @Override
                public Optional<SkillInfo> lookup(int skillTableId) {
                    Optional<SkillTable> skillOpt = tables.skill().find(skillTableId);
                    if (skillOpt.isEmpty()) {
                        return Optional.empty();
                    }
                    SkillTable skill = skillOpt.get();
                    String scrollName = tables.skillScrollCraft().find(skill.getScrollId())
                            .map(SkillScrollCraftTable::getName)
                            .orElse("");
                    return Optional.of(new SkillInfo(skill.getScrollId(), skill.getName(), scrollName));
                }

                @Override
                public boolean isEligible(int skillTableId) {
                    Optional<SkillTable> skillOpt = tables.skill().find(skillTableId);
                    if (skillOpt.isEmpty()) {
                        return false;
                    }
                    SkillTable skill = skillOpt.get();
                    Optional<SkillScrollCraftTable> scrollOpt = tables.skillScrollCraft().find(skill.getScrollId());
                    if (scrollOpt.isEmpty()) {
                        return false;
                    }
                    SkillScrollCraftTable scroll = scrollOpt.get();
                    return skill.getSeasonId() == 0
                            && skill.getBattleModel() == 1
                            && skill.getRarityTier() == 0
                            && scroll.getEnabled() != 0
                            && scroll.getRarityTier() == 0;
                }
            };
        }
    }

    /** 集市税：悬赏额 × 5% 向上取整（与 C++ ProxyTaxGold / Go WantedTaxGold 逐位一致）。 */
    public static long taxGold(long rewardGold) {
        if (rewardGold == 0L) {
            return 0L;
        }
        return Long.divideUnsigned(rewardGold * TAX_BPS + 9999L, 10_000L);
    }

    /** 揭榜人税后实得（与 C++ ProxyNetGold / Go WantedSellerNetGold 逐位一致）。 */
    public static long sellerNetGold(long rewardGold) {
        long tax = taxGold(rewardGold);
        return Long.compareUnsigned(rewardGold, tax) <= 0 ? 0L : rewardGold - tax;
    }

    /** 流通银两（币种 0）：绑定银两绝不进求购单。 */
    public static AssetBundle rewardBundle(long amount) {
        return AssetBundle.newBuilder()
                .addCurrencies(CurrencyAmount.newBuilder()
                        .setCurrencyType(CurrencyType.CURRENCY_TYPE_GOLD_VALUE)
                        .setAmount(amount))
                .build();
    }

    static String debitRole(long attempt, String requestKey) {
        return DEBIT_PREFIX + Long.toUnsignedString(attempt) + "_" + requestKey;
    }

    static String returnRole(long attempt) {
        return RETURN_PREFIX + Long.toUnsignedString(attempt);
    }

    static TradeCommerceLeg.Builder attemptDebit(TradeCommerceWorkflow.Builder w, long attempt) {
        String prefix = DEBIT_PREFIX + Long.toUnsignedString(attempt) + "_";
        for (TradeCommerceLeg.Builder l : w.getLegsBuilderList()) {
            if (l.getRole().startsWith(prefix)) {
                return l;
            }
        }
        return null;
    }

    record MatchedAttempt(long attempt, TradeCommerceLeg leg) {
    }

    static MatchedAttempt attemptByKey(TradeCommerceWorkflow.Builder w, String requestKey) {
        String suffix = "_" + requestKey;
        for (TradeCommerceLeg l : w.getLegsList()) {
            String role = l.getRole();
            if (!role.startsWith(DEBIT_PREFIX) || !role.endsWith(suffix)) {
                continue;
            }
            String numPart = role.substring(DEBIT_PREFIX.length(), role.length() - suffix.length());
            try {
                long attempt = Long.parseUnsignedLong(numPart);
                return new MatchedAttempt(attempt, l);
            } catch (NumberFormatException ignored) {
                // ignore malformed role
            }
        }
        return null;
    }

    /** 核对 scene 扣出回传的完整实例：未绑定、常规（rarity_tier=0）、模板 3414、数量 1、技能号匹配、要求署名时有署名。 */
    public static boolean bookMatches(AssetBundle book, TradeWantedSkillBookTerms terms) {
        if (book == null || terms == null
                || book.getItemInstancesCount() != 1
                || book.getItemsCount() != 0
                || book.getCurrenciesCount() != 0
                || book.hasPetInstance()) {
            return false;
        }
        AssetItemInstance instance = book.getItemInstances(0);
        if (instance.getConfigId() != SEALED_SKILL_BOOK_CONFIG_ID || instance.getStackSize() != 1) {
            return false;
        }
        ItemComp item;
        try {
            item = ItemComp.parseFrom(instance.getInstanceProto());
        } catch (InvalidProtocolBufferException invalid) {
            return false;
        }
        if (item.getItemId() != instance.getItemUuid()
                || item.getConfigId() != SEALED_SKILL_BOOK_CONFIG_ID
                || item.getSize() != 1
                || item.getBound()
                || !item.hasSealedSkillBook()) {
            return false;
        }
        SealedSkillBookData sealed = item.getSealedSkillBook();
        if (sealed.getRarityTier() != 0 || sealed.getSkillTableId() != terms.getSkillTableId()) {
            return false;
        }
        return !terms.getRequireMasterSignature()
                || (!sealed.getCrafterName().isEmpty() && sealed.getCrafterPlayerId() != 0L);
    }

    /** 推进求购单专属三个阶段（wanted_escrow / wanted_open / wanted_fill）；deliver/return 收尾走通用逻辑。 */
    static void plan(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w) {
        if (!w.hasWantedSkillBook()) {
            throw new IllegalStateException("求购单缺少条款");
        }
        TradeWantedSkillBookTerms terms = w.getWantedSkillBook();
        if (STAGE_ESCROW.equals(w.getStage())) {
            add(w, "buyer_debit", r.getBuyerPlayerId(), 3, rewardBundle(terms.getRewardGold()));
            var escrow = leg(w, "buyer_debit");
            if (!done(escrow)) {
                return;
            }
            if (!applied(escrow)) {
                w.setStage("done");
                r.setStatus(CANCELLED);
                return;
            }
            w.setStage(STAGE_OPEN);
            r.setStatus(WAITING);
        }
        if (STAGE_OPEN.equals(w.getStage()) && w.getCancelRequested()) {
            w.setStage("return");
            r.setStatus(CANCEL_PENDING);
            transferred(w, "buyer_return", r.getBuyerPlayerId(), leg(w, "buyer_debit"));
            return;
        }
        if (STAGE_FILL.equals(w.getStage())) {
            planFill(r, w, terms);
        }
    }

    private static void planFill(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w,
                                 TradeWantedSkillBookTerms terms) {
        long attempt = w.getQuantityReserved();
        var debit = attemptDebit(w, attempt);
        if (debit == null) {
            throw new IllegalStateException("求购单第 " + Long.toUnsignedString(attempt) + " 次揭榜缺托管腿");
        }
        if (!done(debit)) {
            return;
        }
        if (!applied(debit)) {
            reopen(r, w);
            return;
        }
        AssetBundle book = transfer(debit.build());
        if (!bookMatches(book, terms)) {
            String role = returnRole(attempt);
            addEscrowLeg(w, role, debit.getPlayerId(), debit.getPlayerId(), book);
            var returned = currentLeg(w, role);
            if (!done(returned)) {
                return;
            }
            if (!applied(returned)) {
                w.setStage("manual");
                r.setStatus(MANUAL);
                return;
            }
            reopen(r, w);
            return;
        }
        var escrow = leg(w, "buyer_debit");
        if (!applied(escrow)) {
            throw new IllegalStateException("求购单进入揭榜时悬赏银两没有托管");
        }
        w.setStage("deliver");
        r.setStatus(DELIVERING);
        addEscrowLeg(w, "buyer_receive", r.getBuyerPlayerId(), debit.getPlayerId(), book);
        addEscrowLeg(w, "seller_receive", r.getSellerPlayerId(), escrow.getPlayerId(),
                rewardBundle(terms.getSellerNetGold()));
    }

    private static void reopen(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w) {
        r.setSellerPlayerId(0L);
        w.clearSellerIdentity();
        w.setStage(STAGE_OPEN);
        r.setStatus(WAITING);
    }

    /** 推导客户端展示阶段（与 Go WantedPhase 逐项一致）。 */
    public static WantedSkillBookOrderPhase phase(TradeCommerceRow r, TradeCommerceWorkflow w) {
        switch (r.getStatus()) {
            case MANUAL -> {
                return WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_MANUAL;
            }
            case SETTLED -> {
                return WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_SETTLED;
            }
            case CANCELLED -> {
                return WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_CANCELLED;
            }
            case CANCEL_PENDING -> {
                return WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_CANCELLING;
            }
            default -> {
            }
        }
        return switch (w.getStage()) {
            case STAGE_ESCROW -> WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_ESCROWING;
            case STAGE_OPEN -> WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_OPEN;
            case STAGE_FILL -> WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_FULFILLING;
            case "deliver" -> WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_DELIVERING;
            case "return" -> WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_CANCELLING;
            default -> WantedSkillBookOrderPhase.WANTED_SKILL_BOOK_ORDER_PHASE_UNSPECIFIED;
        };
    }

    public static WantedSkillBookOrderView view(TradeCommerceRow r, TradeCommerceWorkflow w,
                                                long viewerPlayerId, SkillCatalog catalog) {
        TradeWantedSkillBookTerms t = w.getWantedSkillBook();
        SkillInfo info = catalog == null ? null : catalog.lookup(t.getSkillTableId()).orElse(null);
        return WantedSkillBookOrderView.newBuilder()
                .setOrderId(Long.toUnsignedString(r.getCommerceId()))
                .setPosterPlayerId(r.getBuyerPlayerId())
                .setPosterName(t.getPosterName())
                .setSkillTableId(t.getSkillTableId())
                .setSkillName(info == null ? "" : info.skillName())
                .setScrollId(t.getScrollId())
                .setScrollName(info == null ? "" : info.scrollName())
                .setRequireMasterSignature(t.getRequireMasterSignature())
                .setRewardGold(t.getRewardGold())
                .setSellerNetGold(t.getSellerNetGold())
                .setPhase(phase(r, w))
                .setFulfillerPlayerId(r.getSellerPlayerId())
                .setCreatedMs(r.getCreatedMs())
                .setVersion(r.getVersion())
                .setIsMine(viewerPlayerId != 0L && viewerPlayerId == r.getBuyerPlayerId())
                .build();
    }

    /** 公开悬赏榜翻页游标（reward_gold DESC, commerce_id DESC）。 */
    public record BoardCursor(long rewardGold, long commerceId) {
        public String format() {
            return Long.toUnsignedString(rewardGold) + ":" + Long.toUnsignedString(commerceId);
        }

        public static Optional<BoardCursor> parse(String raw) {
            if (raw == null || raw.isEmpty()) {
                return Optional.empty();
            }
            int sep = raw.indexOf(':');
            if (sep <= 0 || sep == raw.length() - 1) {
                return Optional.empty();
            }
            try {
                return Optional.of(new BoardCursor(
                        Long.parseUnsignedLong(raw.substring(0, sep)),
                        Long.parseUnsignedLong(raw.substring(sep + 1))));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
    }

    /** 我的悬赏单翻页游标（created_ms ASC, commerce_id ASC）。 */
    public record MyCursor(long createdMs, long commerceId) {
        public String format() {
            return Long.toUnsignedString(createdMs) + ":" + Long.toUnsignedString(commerceId);
        }

        public static Optional<MyCursor> parse(String raw) {
            if (raw == null || raw.isEmpty()) {
                return Optional.empty();
            }
            int sep = raw.indexOf(':');
            if (sep <= 0 || sep == raw.length() - 1) {
                return Optional.empty();
            }
            try {
                return Optional.of(new MyCursor(
                        Long.parseUnsignedLong(raw.substring(0, sep)),
                        Long.parseUnsignedLong(raw.substring(sep + 1))));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
    }
}
