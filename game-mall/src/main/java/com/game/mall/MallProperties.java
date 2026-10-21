package com.game.mall;

import com.game.common.RunMode;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本进程专用调用密钥；支付适配器与网关分权，缺失配置时在绑定阶段拒绝启动。 */
@ConfigurationProperties("game.mall")
public record MallProperties(Boolean requireTidb, String gatewaySecret, String paymentSecret,
                             String paymentSubject, Integer maxUnpaidOrders, String deliverySecret,
                             String adminSecret, String adminSubject, Boolean mockPaymentEnabled,
                             Set<String> allowedPaymentChannels) {
    public MallProperties(Boolean requireTidb,String gatewaySecret,String paymentSecret,String paymentSubject,Integer maxUnpaidOrders){this(requireTidb,gatewaySecret,paymentSecret,paymentSubject,maxUnpaidOrders,null);}
    public MallProperties(Boolean requireTidb, String gatewaySecret, String paymentSecret, String paymentSubject,
                          Integer maxUnpaidOrders, String deliverySecret) {
        this(requireTidb, gatewaySecret, paymentSecret, paymentSubject, maxUnpaidOrders, deliverySecret, null, null);
    }
    public MallProperties(Boolean requireTidb, String gatewaySecret, String paymentSecret, String paymentSubject,
                          Integer maxUnpaidOrders, String deliverySecret, String adminSecret, String adminSubject) {
        this(requireTidb, gatewaySecret, paymentSecret, paymentSubject, maxUnpaidOrders, deliverySecret, adminSecret, adminSubject, false, Set.of());
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public MallProperties {
        requireTidb = requireTidb == null || requireTidb;
        paymentSubject = paymentSubject == null ? "payment-adapter" : paymentSubject;
        adminSubject = adminSubject == null ? "mall-admin" : adminSubject;
        maxUnpaidOrders = maxUnpaidOrders == null ? 5 : maxUnpaidOrders;
        mockPaymentEnabled = Boolean.TRUE.equals(mockPaymentEnabled);
        allowedPaymentChannels = allowedPaymentChannels == null ? Set.of() : Set.copyOf(allowedPaymentChannels);
        if (allowedPaymentChannels.size() > 32 || allowedPaymentChannels.stream().anyMatch(value ->
                !value.matches("[a-z][a-z0-9_-]{1,31}") || value.equals("mock"))) {
            throw new IllegalArgumentException("支付渠道允许集合非法；mock只能走独立事实适配器");
        }
        if(deliverySecret!=null&&!deliverySecret.isEmpty()&&(!strong(deliverySecret)||deliverySecret.equals(gatewaySecret)||deliverySecret.equals(paymentSecret)))throw new IllegalArgumentException("交付调用密钥必须与网关及支付密钥不同且至少32字节");
        if (adminSecret != null && !adminSecret.isEmpty() && (!strong(adminSecret) || adminSecret.equals(gatewaySecret)
                || adminSecret.equals(paymentSecret) || adminSecret.equals(deliverySecret))) {
            throw new IllegalArgumentException("管理调用密钥必须独立且至少32字节");
        }
        if (!adminSubject.matches("[a-z][a-z0-9-]{2,63}")) {
            throw new IllegalArgumentException("管理调用方名称非法");
        }
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

    void requireMockPayment(RunMode mode) {
        if (!mockPaymentEnabled || mode == null || mode == RunMode.PROD) {
            throw new MallException(503, "本地mock支付未启用或运行模式不允许");
        }
    }

    void requirePaymentChannel(String channel, RunMode mode) {
        if ("mock".equals(channel)) { requireMockPayment(mode); return; }
        if (!allowedPaymentChannels.contains(channel)) { throw new MallException(403, "支付渠道未配置允许"); }
    }

    private static boolean strong(String secret) {
        if (secret == null) {
            return false;
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= 32 && bytes <= 4096;
    }
}
