package com.game.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-chat 业务配置（{@code xm.chat.*}）。缺省值与启动校验照 mmorpg go/chat internal/config（etc/chat.yaml）。
 *
 * @param maxContentBytes     内容按原始 UTF-8 字节数的上限（缺省 512；超了回 1010）
 * @param rateLimitPerSecond  每人每秒发言条数（缺省 5；窗口固定 1 s）
 * @param historyMaxEntries   每个频道保留的条数（缺省 200）
 * @param historyTtl          历史的过期（缺省 7 天，每次写入续期）
 * @param historyDefaultLimit 拉取时 limit = 0 的取值（缺省 20）
 * @param historyMaxLimit     拉取上限（缺省 50；不得小于缺省值）
 * @param requestIdTtl        发言幂等键的过期（缺省 60 s）
 * @param requestBudget       整请求预算（缺省 3500 ms，须先于 gate 调后端的 5 s Dubbo 超时）
 */
@ConfigurationProperties("xm.chat")
public record ChatProperties(
        Integer maxContentBytes,
        Integer rateLimitPerSecond,
        Integer historyMaxEntries,
        Duration historyTtl,
        Integer historyDefaultLimit,
        Integer historyMaxLimit,
        Duration requestIdTtl,
        Duration requestBudget) {

    public static final Duration MAX_REQUEST_BUDGET = Duration.ofMillis(4000);

    public ChatProperties {
        maxContentBytes = positiveOr(maxContentBytes, 512, "max-content-bytes");
        rateLimitPerSecond = positiveOr(rateLimitPerSecond, 5, "rate-limit-per-second");
        historyMaxEntries = positiveOr(historyMaxEntries, 200, "history-max-entries");
        historyTtl = seconds(historyTtl, Duration.ofDays(7), "history-ttl");
        historyDefaultLimit = positiveOr(historyDefaultLimit, 20, "history-default-limit");
        historyMaxLimit = positiveOr(historyMaxLimit, 50, "history-max-limit");
        if (historyDefaultLimit > historyMaxLimit) {
            throw new IllegalArgumentException("xm.chat.history-default-limit（" + historyDefaultLimit
                    + "）不能大于 history-max-limit（" + historyMaxLimit + "）");
        }
        requestIdTtl = seconds(requestIdTtl, Duration.ofSeconds(60), "request-id-ttl");
        requestBudget = requestBudget == null ? Duration.ofMillis(3500) : requestBudget;
        if (requestBudget.isNegative() || requestBudget.isZero() || requestBudget.compareTo(MAX_REQUEST_BUDGET) > 0) {
            throw new IllegalArgumentException("xm.chat.request-budget 必须在 (0, " + MAX_REQUEST_BUDGET + "] 内: " + requestBudget);
        }
    }

    /** Redis 的 EX 以秒为单位：不足 1 秒的正值拒绝（会被截成 0）。 */
    private static Duration seconds(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.toSeconds() <= 0) {
            throw new IllegalArgumentException("xm.chat." + name + " 必须至少 1 秒: " + value);
        }
        return value;
    }

    private static int positiveOr(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("xm.chat." + name + " 必须为正: " + value);
        }
        return value;
    }
}
