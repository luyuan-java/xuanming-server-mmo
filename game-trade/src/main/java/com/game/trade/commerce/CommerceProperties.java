package com.game.trade.commerce;

import com.game.proto.trade.TradeMarketView;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 交易白名单是受信服务端配置；未配置的模板和币种一律不能交易。 */
@ConfigurationProperties("game.trade.commerce")
public record CommerceProperties(boolean enabled, Set<Integer> items, Set<Integer> pets, Set<Integer> currencies,
    boolean instances, List<Market> markets) {
    public record Market(int itemConfigId, int currencyType, String title) {}
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
    }
    CommerceRules.Policy policy() {
        return new CommerceRules.Policy(items, pets, currencies, instances,
            markets.stream()
                .map(m
                    -> TradeMarketView.newBuilder()
                        .setMarketId(1)
                        .setItemConfigId(m.itemConfigId())
                        .setCurrencyType(m.currencyType())
                        .setTitle(m.title())
                        .build())
                .toList());
    }
}
