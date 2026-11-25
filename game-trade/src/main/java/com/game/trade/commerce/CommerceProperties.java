package com.game.trade.commerce;

import com.game.proto.common.base.CurrencyType;
import com.game.proto.trade.TradeMarketView;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 交易白名单与当面交易防骗/卖家信用配置是受信服务端配置；未配置的模板和币种一律不能交易。 */
@ConfigurationProperties("game.trade.commerce")
public record CommerceProperties(
        boolean enabled,
        Set<Integer> items,
        Set<Integer> pets,
        Set<Integer> currencies,
        boolean instances,
        List<Market> markets,
        PlayerTrade playerTrade,
        SellerCredit sellerCredit) {

    public record Market(int itemConfigId, int currencyType, String title) {}

    /** 游戏内当面交易防骗配置项（对应 Go {@code PlayerTradeConf}）。 */
    public record PlayerTrade(
            String mode,
            Set<Integer> consumableItems,
            Long maxDailyCurrencyAmount,
            Integer referenceCurrencyType,
            Long minFriendDurationMs,
            Integer maxReferenceDeviationPct,
            Long coolingLockDurationMs,
            Boolean allowAcquaintanceCoolingLock) {

        public PlayerTradeGuard.Policy toPolicy() {
            return new PlayerTradeGuard.Policy(
                    mode == null ? "" : mode,
                    consumableItems == null ? Set.of() : consumableItems,
                    maxDailyCurrencyAmount == null ? 0L : maxDailyCurrencyAmount,
                    referenceCurrencyType == null ? CurrencyType.CURRENCY_TYPE_GOLD_VALUE : referenceCurrencyType,
                    minFriendDurationMs == null ? 0L : minFriendDurationMs,
                    maxReferenceDeviationPct == null ? 0 : maxReferenceDeviationPct,
                    coolingLockDurationMs == null ? 0L : coolingLockDurationMs,
                    Boolean.TRUE.equals(allowAcquaintanceCoolingLock));
        }
    }

    /** 卖家信用评分与防刷分策略配置项（对应 Go {@code SellerCreditConf}）。 */
    public record SellerCredit(
            Long reviewWindowMs,
            Long pairScoreWindowMs,
            Integer maxScoredReviewsPerPairInWindow,
            Long minScoreAmountMinor,
            Long postEnvChangeScoreCooldownMs) {

        public SellerCreditService.Policy toPolicy() {
            return new SellerCreditService.Policy(
                    reviewWindowMs == null ? 0L : reviewWindowMs,
                    pairScoreWindowMs == null ? 0L : pairScoreWindowMs,
                    maxScoredReviewsPerPairInWindow == null ? 0 : maxScoredReviewsPerPairInWindow,
                    minScoreAmountMinor == null ? 0L : minScoreAmountMinor,
                    postEnvChangeScoreCooldownMs == null ? 0L : postEnvChangeScoreCooldownMs);
        }
    }

    public CommerceProperties(
            boolean enabled,
            Set<Integer> items,
            Set<Integer> pets,
            Set<Integer> currencies,
            boolean instances,
            List<Market> markets) {
        this(enabled, items, pets, currencies, instances, markets, null, null);
    }

    @ConstructorBinding
    public CommerceProperties {
        items = items == null ? Set.of() : Set.copyOf(items);
        pets = pets == null ? Set.of() : Set.copyOf(pets);
        currencies = currencies == null ? Set.of() : Set.copyOf(currencies);
        markets = markets == null ? List.of() : List.copyOf(markets);
        for (var m : markets)
            if (!items.contains(m.itemConfigId()) || !currencies.contains(m.currencyType()) || m.title() == null
                    || m.title().isBlank())
                throw new IllegalArgumentException("全服材料目录必须来自可交易模板与币种，且有玩家可读的名称");
        if (markets.stream().map(Market::itemConfigId).distinct().count() != markets.size())
            throw new IllegalArgumentException("每个材料目录只允许一个结算币种，防止深度混币种");
        for (int item : items)
            if (markets.stream().noneMatch(m -> m.itemConfigId() == item))
                throw new IllegalArgumentException("可交易物品缺少服务端名称目录");
        if (playerTrade != null) {
            playerTrade.toPolicy().validate();
        }
        if (sellerCredit != null) {
            sellerCredit.toPolicy().validate();
        }
    }

    PlayerTradeGuard.Policy playerTradePolicy() {
        return playerTrade == null ? PlayerTradeGuard.Policy.disabled() : playerTrade.toPolicy();
    }

    SellerCreditService.Policy sellerCreditPolicy() {
        return sellerCredit == null ? SellerCreditService.Policy.defaults() : sellerCredit.toPolicy();
    }

    CommerceRules.Policy policy() {
        return new CommerceRules.Policy(items, pets, currencies, instances,
                markets.stream()
                        .map(m -> TradeMarketView.newBuilder()
                                .setMarketId(1)
                                .setItemConfigId(m.itemConfigId())
                                .setCurrencyType(m.currencyType())
                                .setTitle(m.title())
                                .build())
                        .toList());
    }
}
