package com.game.mall;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 首次登记冻结时长和费率，后续绑定新配置不能改写原单。 */
@ConfigurationProperties("game.mall.consignment")
public record RmbConsignmentProperties(Long noticeMs, Long saleMs, Long coolingMs, Integer feeBps, Set<Integer> currencies) {
    public RmbConsignmentProperties() { this(null, null, null, null, null); }
    @ConstructorBinding public RmbConsignmentProperties {
        noticeMs = noticeMs == null ? 172_800_000L : noticeMs;
        saleMs = saleMs == null ? 604_800_000L : saleMs;
        coolingMs = coolingMs == null ? 259_200_000L : coolingMs;
        feeBps = feeBps == null ? 500 : feeBps;
        currencies = currencies == null ? Set.of(0) : Set.copyOf(currencies);
        long maximum = 366L * 86_400_000;
        if (noticeMs < 0 || noticeMs > maximum || saleMs < 1 || saleMs > maximum || coolingMs < 0 || coolingMs > maximum
                || feeBps < 0 || feeBps > 10_000 || currencies.stream().anyMatch(value -> value < 0 || value > 1)) {
            throw new IllegalArgumentException("人民币寄售规则非法；绑定货币不能交易");
        }
    }
}
