package com.game.trade.commerce;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("game.trade.mall")
public record OfficialMallProperties(boolean enabled, String url, String gatewaySecret, String deliverySecret) {
    public OfficialMallProperties {
        if (enabled
            && (url == null || gatewaySecret == null || gatewaySecret.length() < 32 || deliverySecret == null
                || deliverySecret.length() < 32 || gatewaySecret.equals(deliverySecret)))
            throw new IllegalArgumentException("商城地址与独立调用密钥未配置");
    }
}
