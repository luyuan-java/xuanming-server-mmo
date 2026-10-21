package com.game.trade.commerce;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 真实资产与角色托管可用后显式启用；货币默认仅金币，绑定货币始终拒绝。 */
@ConfigurationProperties("game.trade.rmb")
public record RmbConsignmentProperties(boolean enabled, Set<Integer> currencies) {
    public RmbConsignmentProperties {
        currencies = currencies == null ? Set.of(0) : Set.copyOf(currencies);
        if (currencies.stream().anyMatch(c -> c != 0 && c != 1)) throw new IllegalArgumentException("人民币寄售币种非法");
    }
}
