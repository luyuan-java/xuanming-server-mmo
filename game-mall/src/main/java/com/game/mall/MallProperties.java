package com.game.mall;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本进程专用调用密钥；支付适配器与网关分权，缺失配置时在绑定阶段拒绝启动。 */
@ConfigurationProperties("game.mall")
public record MallProperties(Boolean requireTidb, String gatewaySecret, String paymentSecret,
                             String paymentSubject, Integer maxUnpaidOrders) {
    public MallProperties {
        requireTidb = requireTidb == null || requireTidb;
        paymentSubject = paymentSubject == null ? "payment-adapter" : paymentSubject;
        maxUnpaidOrders = maxUnpaidOrders == null ? 5 : maxUnpaidOrders;
        if (!strong(gatewaySecret) || !strong(paymentSecret) || gatewaySecret.equals(paymentSecret)) {
            throw new IllegalArgumentException("game.mall 必须配置两把不同且至少32字节的调用密钥");
        }
        if (!paymentSubject.matches("[a-z][a-z0-9-]{2,63}")) {
            throw new IllegalArgumentException("game.mall.payment-subject 非法");
        }
        if (maxUnpaidOrders < 1 || maxUnpaidOrders > 100) {
            throw new IllegalArgumentException("game.mall.max-unpaid-orders 必须在1到100之间");
        }
    }

    private static boolean strong(String secret) {
        if (secret == null) {
            return false;
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= 32 && bytes <= 4096;
    }
}
