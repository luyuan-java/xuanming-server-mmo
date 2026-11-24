package com.game.chat;

import com.game.chat.service.SensitiveWordFilter;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * game-chat 业务配置（{@code game.chat.*}）。缺省值与启动校验照 mmorpg go/chat internal/config（etc/chat.yaml）。
 *
 * @param maxContentBytes            内容按原始 UTF-8 字节数的上限（缺省 512；超了回 1010）
 * @param rateLimitPerSecond         每人每秒发言条数（缺省 5；窗口固定 1 s）
 * @param historyMaxEntries          每个频道保留的条数（缺省 200）
 * @param historyTtl                 历史的过期（缺省 7 天，每次写入续期）
 * @param historyDefaultLimit        拉取时 limit = 0 的取值（缺省 20）
 * @param historyMaxLimit            拉取上限（缺省 50；不得小于缺省值）
 * @param requestIdTtl               v1 Redis夹具的幂等键过期（缺省60s）；正式SQL成功收据永久，不使用此TTL
 * @param requestBudget              整请求预算（缺省 3500 ms，须先于 gate 调后端的 5 s Dubbo 超时）
 * @param sensitiveWords             按配置顺序屏蔽的字面词表，大小写敏感；缺省空表（未启用内容安全服务时回落使用）
 * @param worldChannelCooldownMs     同一玩家相邻两条世界频道消息的最短间隔（毫秒，缺省 0 = 不限；上限 600000）
 * @param worldMinLevel              世界频道发言的最低角色等级（缺省 0 = 不限；上限 1000）
 * @param duplicateSpamWindowMs      跨副本重复刷屏时间窗（毫秒，缺省 0 = 关闭；上限 600000）
 * @param duplicateSpamMaxSenders    同窗内不同发送者重复同一归一化文本上限（缺省 0 = 不限；上限 100）
 * @param duplicateSpamMaxPerSender  同窗内同一发送者重复同一归一化文本次数上限（缺省 0 = 不限；上限 100）
 * @param newAccountAgeHours         新账号私聊限制的账号年龄阈值（小时，缺省 0 = 关闭；上限 720）
 * @param newAccountDailyPrivateLimit 新账号每日私聊不同目标上限（缺省 0 = 不限；上限 1000）
 * @param contentGuardEnabled        是否启用内容安全 Dubbo 服务（缺省 false）
 */
@ConfigurationProperties("game.chat")
public record ChatProperties(
        Integer maxContentBytes,
        Integer rateLimitPerSecond,
        Integer historyMaxEntries,
        Duration historyTtl,
        Integer historyDefaultLimit,
        Integer historyMaxLimit,
        Duration requestIdTtl,
        Duration requestBudget,
        List<String> sensitiveWords,
        Integer worldChannelCooldownMs,
        Integer worldMinLevel,
        Integer duplicateSpamWindowMs,
        Integer duplicateSpamMaxSenders,
        Integer duplicateSpamMaxPerSender,
        Integer newAccountAgeHours,
        Integer newAccountDailyPrivateLimit,
        Boolean contentGuardEnabled) {

    public static final Duration MAX_REQUEST_BUDGET = Duration.ofMillis(4000);
    /** 单次读窗硬预算；历史容量可配置，不能使一轮SQL读取无界。 */
    public static final int MAX_PULL_MESSAGES = 200;

    @ConstructorBinding
    public ChatProperties {
        // 在任何外部连接装配前拒绝无界或非法词表，并保留调用方不可修改的配置快照。
        new SensitiveWordFilter(sensitiveWords);
        sensitiveWords = sensitiveWords == null ? List.of() : List.copyOf(sensitiveWords);
        maxContentBytes = positiveOr(maxContentBytes, 512, "max-content-bytes");
        rateLimitPerSecond = positiveOr(rateLimitPerSecond, 5, "rate-limit-per-second");
        historyMaxEntries = positiveOr(historyMaxEntries, 200, "history-max-entries");
        historyTtl = seconds(historyTtl, Duration.ofDays(7), "history-ttl");
        historyDefaultLimit = positiveOr(historyDefaultLimit, 20, "history-default-limit");
        historyMaxLimit = positiveOr(historyMaxLimit, 50, "history-max-limit");
        if (historyMaxLimit > MAX_PULL_MESSAGES) {
            throw new IllegalArgumentException("game.chat.history-max-limit 不能大于 " + MAX_PULL_MESSAGES);
        }
        if (historyDefaultLimit > historyMaxLimit) {
            throw new IllegalArgumentException("game.chat.history-default-limit（" + historyDefaultLimit
                    + "）不能大于 history-max-limit（" + historyMaxLimit + "）");
        }
        requestIdTtl = seconds(requestIdTtl, Duration.ofSeconds(60), "request-id-ttl");
        requestBudget = requestBudget == null ? Duration.ofMillis(3500) : requestBudget;
        if (requestBudget.isNegative() || requestBudget.isZero() || requestBudget.compareTo(MAX_REQUEST_BUDGET) > 0) {
            throw new IllegalArgumentException("game.chat.request-budget 必须在 (0, " + MAX_REQUEST_BUDGET + "] 内: " + requestBudget);
        }
        worldChannelCooldownMs = rangeOr(worldChannelCooldownMs, 0, 600_000, "world-channel-cooldown-ms");
        worldMinLevel = rangeOr(worldMinLevel, 0, 1_000, "world-min-level");
        duplicateSpamWindowMs = rangeOr(duplicateSpamWindowMs, 0, 600_000, "duplicate-spam-window-ms");
        duplicateSpamMaxSenders = rangeOr(duplicateSpamMaxSenders, 0, 100, "duplicate-spam-max-senders");
        duplicateSpamMaxPerSender = rangeOr(duplicateSpamMaxPerSender, 0, 100, "duplicate-spam-max-per-sender");
        newAccountAgeHours = rangeOr(newAccountAgeHours, 0, 720, "new-account-age-hours");
        newAccountDailyPrivateLimit = rangeOr(newAccountDailyPrivateLimit, 0, 1_000, "new-account-daily-private-limit");
        contentGuardEnabled = Boolean.TRUE.equals(contentGuardEnabled);
    }

    /** 保留既有 9 参构造器（兼容单测与旧装配）。 */
    public ChatProperties(
            Integer maxContentBytes,
            Integer rateLimitPerSecond,
            Integer historyMaxEntries,
            Duration historyTtl,
            Integer historyDefaultLimit,
            Integer historyMaxLimit,
            Duration requestIdTtl,
            Duration requestBudget,
            List<String> sensitiveWords) {
        this(maxContentBytes, rateLimitPerSecond, historyMaxEntries, historyTtl, historyDefaultLimit,
                historyMaxLimit, requestIdTtl, requestBudget, sensitiveWords, 0, 0, 0, 0, 0, 0, 0, false);
    }

    private static int rangeOr(Integer value, int min, int max, String name) {
        int v = value == null ? 0 : value;
        if (v < min || v > max) {
            throw new IllegalArgumentException("game.chat." + name + " 必须在 [" + min + ", " + max + "] 内: " + v);
        }
        return v;
    }

    /** Redis 的 EX 以秒为单位：不足 1 秒的正值拒绝（会被截成 0）。 */
    private static Duration seconds(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.toSeconds() <= 0) {
            throw new IllegalArgumentException("game.chat." + name + " 必须至少 1 秒: " + value);
        }
        return value;
    }

    private static int positiveOr(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("game.chat." + name + " 必须为正: " + value);
        }
        return value;
    }
}
