package com.game.gm;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立 Web GM 管理后台配置（{@code game.gm.*}）。密钥统一经环境变量读取，不落配置文件明文。 */
@ConfigurationProperties("game.gm")
public record GmAdminProperties(
        String gatewayUrl,
        String gatewayAdminKey,
        Boolean mailEnabled,
        Boolean matchOpsEnabled,
        Boolean playerCommandsEnabled,
        Long callBudgetMs,
        Boolean autoInitSchema,
        Map<String, OperatorEntry> operators,
        Boolean allowInsecureInternalGateway) {

    public record OperatorEntry(String username, List<String> permissions) {
    }

    /** 兼容 8 参构造（默认不允许非回环明文 HTTP）。 */
    public GmAdminProperties(
            String gatewayUrl,
            String gatewayAdminKey,
            Boolean mailEnabled,
            Boolean matchOpsEnabled,
            Boolean playerCommandsEnabled,
            Long callBudgetMs,
            Boolean autoInitSchema,
            Map<String, OperatorEntry> operators) {
        this(gatewayUrl, gatewayAdminKey, mailEnabled, matchOpsEnabled, playerCommandsEnabled,
                callBudgetMs, autoInitSchema, operators, false);
    }

    public GmAdminProperties {
        gatewayUrl = (gatewayUrl == null || gatewayUrl.isBlank()) ? "http://127.0.0.1:18106" : gatewayUrl.strip();
        gatewayAdminKey = gatewayAdminKey == null ? "" : gatewayAdminKey.strip();
        mailEnabled = mailEnabled != null && mailEnabled;
        matchOpsEnabled = matchOpsEnabled != null && matchOpsEnabled;
        playerCommandsEnabled = playerCommandsEnabled != null && playerCommandsEnabled;
        callBudgetMs = callBudgetMs == null ? 5000L : callBudgetMs;
        if (callBudgetMs <= 0 || callBudgetMs > 10_000L) {
            throw new IllegalArgumentException("game.gm.call-budget-ms 必须在 [1, 10000] ms 内: " + callBudgetMs);
        }
        autoInitSchema = autoInitSchema == null || autoInitSchema;
        operators = operators == null ? Map.of() : Map.copyOf(operators);
        allowInsecureInternalGateway = allowInsecureInternalGateway != null && allowInsecureInternalGateway;
    }
}
