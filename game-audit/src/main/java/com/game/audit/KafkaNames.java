package com.game.audit;

import com.game.common.GameNamespace;

/**
 * Kafka 侧一切名字的唯一出处：topic 基名、消费组缺省值、client.id（以及将来若加的 transactional.id / group.instance.id）都以
 * {@code <ns>-} 开头（{@code <ns>} = 部署命名空间 {@code game.namespace}，缺省 {@code game}，见 {@link GameNamespace}）。
 * 缺省命名空间下生成的名字与引入命名空间之前逐字节相同（如 {@code game-transaction-log}、{@code game-data-admin}）。
 *
 * <p>前缀对应 Kafka 官方多租户惯例里的 PREFIXED 资源名：将来有了租户身份（SASL）就能按 {@code <ns>-} 写前缀 ACL / 配额。
 * 本轮只是命名隔离（防误串），不是安全边界。
 */
public final class KafkaNames {

    /** Kafka topic 名的长度上限（broker 的 {@code Topic.MAX_NAME_LENGTH}）。 */
    static final int MAX_NAME_LENGTH = 249;

    private KafkaNames() {
    }

    /** 本进程的前缀 {@code <ns>-}。 */
    public static String prefix() {
        return GameNamespace.get() + "-";
    }

    /**
     * topic 基名 / 消费组缺省值：{@code <ns>-<suffix>}。{@code suffix} 非空、只许 {@code [A-Za-z0-9._-]}（topic 的合法字符），全名不超过 249 个字符。
     * 自己校验，不用 kafka-clients 的 internals 包。
     *
     * @throws IllegalArgumentException suffix 不合法（调用方的错：这些后缀都是代码里的常量）
     */
    public static String of(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            throw new IllegalArgumentException("Kafka 名字的后缀不能为空");
        }
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!legal) {
                throw new IllegalArgumentException("Kafka 名字的后缀只许 [A-Za-z0-9._-]: \"" + suffix + "\"");
            }
        }
        String name = prefix() + suffix;
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Kafka 名字超过 " + MAX_NAME_LENGTH + " 个字符: " + name);
        }
        return name;
    }

    /**
     * client.id：{@code <ns>-<suffix>}。只要求 suffix 非空——client.id 里可能带调用方给的值（通告地址、显式配置的消费组、实例 id），
     * Kafka 不限制 client.id 的字符集，这里也不收紧（否则 IPv6 通告地址之类的现有部署会被拒启）。
     */
    public static String clientId(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            throw new IllegalArgumentException("Kafka client.id 的后缀不能为空");
        }
        return prefix() + suffix;
    }
}
