package com.game.mall;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 首次登记冻结时长和费率，后续绑定新配置不能改写原单。 */
@ConfigurationProperties("game.mall.consignment")
public record RmbConsignmentProperties(
        Long noticeMs,
        Long saleMs,
        Long coolingMs,
        Integer feeBps,
        Set<Integer> currencies,
        Long smallBatchMaxPriceFen,
        Long smallBatchNoticeMs) {

    public RmbConsignmentProperties() {
        this(null, null, null, null, null, null, null);
    }

    public RmbConsignmentProperties(Long noticeMs, Long saleMs, Long coolingMs, Integer feeBps, Set<Integer> currencies) {
        this(noticeMs, saleMs, coolingMs, feeBps, currencies, null, null);
    }

    @ConstructorBinding
    public RmbConsignmentProperties {
        noticeMs = noticeMs == null ? 172_800_000L : noticeMs;
        saleMs = saleMs == null ? 604_800_000L : saleMs;
        coolingMs = coolingMs == null ? 259_200_000L : coolingMs;
        feeBps = feeBps == null ? 500 : feeBps;
        currencies = currencies == null ? Set.of(0) : Set.copyOf(currencies);
        smallBatchMaxPriceFen = smallBatchMaxPriceFen == null ? 0L : smallBatchMaxPriceFen;
        smallBatchNoticeMs = smallBatchNoticeMs == null ? 0L : smallBatchNoticeMs;
        long maximum = 366L * 86_400_000;
        if (noticeMs < 0 || noticeMs > maximum
                || smallBatchNoticeMs < 0 || smallBatchNoticeMs > maximum
                || smallBatchMaxPriceFen < 0 || smallBatchMaxPriceFen > MallOrderStore.MAX_AMOUNT_MINOR
                || (smallBatchMaxPriceFen > 0 && smallBatchNoticeMs > noticeMs)
                || saleMs < 1 || saleMs > maximum
                || coolingMs < 0 || coolingMs > maximum
                || feeBps < 0 || feeBps > 10_000
                || currencies.stream().anyMatch(value -> value == null || value < 0 || value > 1)) {
            throw new IllegalArgumentException("人民币寄售规则非法；绑定货币不能交易");
        }
    }

    /**
     * 计算指定资产类型与价格在首次登记时应冻结的公示时长（毫秒）：
     * 当 {@code smallBatchMaxPriceFen > 0} 时，价格不高于此阈值的 {@code ITEMS}/{@code CURRENCY}
     * 采用 {@code smallBatchNoticeMs}；{@code PET}/{@code CHARACTER} 始终走完整 {@code noticeMs}。
     */
    public long noticeMsFor(String assetKind, long priceFen) {
        if (smallBatchMaxPriceFen > 0
                && ("ITEMS".equals(assetKind) || "CURRENCY".equals(assetKind))
                && priceFen > 0
                && priceFen <= smallBatchMaxPriceFen) {
            return Math.min(smallBatchNoticeMs, noticeMs);
        }
        return noticeMs;
    }
}
