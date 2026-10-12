package com.game.common;

import java.util.Objects;

/**
 * Kafka 侧一切名字的唯一出处：topic 基名、消费组缺省值、client.id（以及将来若加的 transactional.id / group.instance.id）都以 {@code <ns>-} 开头
 * （{@code <ns>} = 部署命名空间，见 {@link GameNamespace}）。缺省命名空间 {@code game} 下生成的名字与引入命名空间之前逐字节相同
 * （如 {@code game-transaction-log}、{@code game-data-admin}）。
 *
 * <p>带命名空间的不可变实例：Spring 进程里由 game-discovery 的 {@code GameNamespaceAutoConfiguration} 注册成 bean（使用方自己定义了同类 bean 时让路），经构造器注入给使用方
 * （game-audit 的 {@code AuditTopics} / {@code BattleResultTopics} 由它构造）；测试直接构造任意命名空间的实例。放在 game-common（纯 Java、不依赖
 * kafka-clients），装配它的 game-discovery 就不必依赖 game-audit。main 代码不得静态持有它，也不得在装配类与命令行工具之外 {@code new} 它。
 *
 * <p>多租户边界（这是命名隔离，不是安全边界）：将来接入 SASL 之后，ACL 按 PREFIXED 资源名 {@code <ns>-} 授权 Topic、Group（有事务时再加
 * TransactionalId）；配额只能按 SASL principal（user 实体）配，Kafka 的配额没有前缀匹配。client.id 带前缀只是为了在 broker 日志与指标里认出租户，
 * 不承载配额（client.id 里还带节点地址等变量）。
 */
public final class KafkaNames {

    /** Kafka topic 名的长度上限（broker 的 {@code Topic.MAX_NAME_LENGTH}）。 */
    static final int MAX_NAME_LENGTH = 249;

    private final GameNamespace namespace;
    private final String prefix;

    public KafkaNames(GameNamespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.prefix = namespace.value() + "-";
    }

    /** 本实例的部署命名空间。 */
    public GameNamespace namespace() {
        return namespace;
    }

    /** 前缀 {@code <ns>-}（缺省 {@code game-}）：显式覆盖的消费组不带它时，装配处据此打 WARN。 */
    public String prefix() {
        return prefix;
    }

    // ------------------------------------------------------------------ topic 基名（实际 topic 名带代次后缀，见 game-audit 的 AuditTopics）

    /** 资产流水 topic 的基名 {@code <ns>-transaction-log}。 */
    public String transactionLogTopicBase() {
        return name("transaction-log");
    }

    /** 玩家快照 topic 的基名 {@code <ns>-player-snapshot}。 */
    public String playerSnapshotTopicBase() {
        return name("player-snapshot");
    }

    /** 对局结果 topic 的基名 {@code <ns>-battle-result}。 */
    public String battleResultTopicBase() {
        return name("battle-result");
    }

    // ------------------------------------------------------------------ 消费组缺省值（配置没给时用）

    /** game-data 消费资产流水的组 {@code <ns>-data-transaction-log}。 */
    public String dataTransactionLogGroup() {
        return name("data-transaction-log");
    }

    /** game-data 消费玩家快照的组 {@code <ns>-data-player-snapshot}。 */
    public String dataPlayerSnapshotGroup() {
        return name("data-player-snapshot");
    }

    /** game-match 评分消费对局结果的组 {@code <ns>-match-rating}。 */
    public String matchRatingGroup() {
        return name("match-rating");
    }

    // ------------------------------------------------------------------ client.id

    /** game-data 的 topic 管理客户端 {@code <ns>-data-admin}。 */
    public String dataAdminClientId() {
        return name("data-admin");
    }

    /** game-data 的消费者 {@code <ns>-data-<group>}（例：{@code game-data-game-data-transaction-log}）；group 是实际生效的消费组（可能是显式配置的值）。 */
    public String dataConsumerClientId(String group) {
        return client("data-" + group);
    }

    /** game-match 的 topic 管理客户端 {@code <ns>-match-admin}。 */
    public String matchAdminClientId() {
        return name("match-admin");
    }

    /** game-match 评分消费者 {@code <ns>-match-rating-<instanceId>}。 */
    public String matchRatingClientId(String instanceId) {
        return client("match-rating-" + instanceId);
    }

    /** scene 审计生产者 {@code <ns>-scene-audit-z<zone>-n<node>}（topic 管理客户端由调用方再拼 {@code -admin}）。 */
    public String sceneAuditClientId(int zoneId, int nodeId) {
        return client("scene-audit-z" + zoneId + "-n" + nodeId);
    }

    /** battle 对局结果生产者 {@code <ns>-battle-result-<通告地址>-<RPC 端口>}（topic 管理客户端由调用方再拼 {@code -admin}）。 */
    public String battleResultClientId(String advertiseHost, int rpcPort) {
        return client("battle-result-" + advertiseHost + "-" + rpcPort);
    }

    @Override
    public String toString() {
        return "KafkaNames[" + namespace + "]";
    }

    /**
     * topic 基名 / 消费组 / 固定的 client.id：{@code <ns>-<suffix>}。{@code suffix} 非空、只许 {@code [A-Za-z0-9._-]}（topic 的合法字符），
     * 全名不超过 249 个字符。自己校验，不用 kafka-clients 的 internals 包。包内可见只为测试校验规则。
     *
     * @throws IllegalArgumentException suffix 不合法（调用方的错：这些后缀都是代码里的常量）
     */
    String name(String suffix) {
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
        String name = prefix + suffix;
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Kafka 名字超过 " + MAX_NAME_LENGTH + " 个字符: " + name);
        }
        return name;
    }

    /**
     * 带变量的 client.id：{@code <ns>-<suffix>}。只要求 suffix 非空——client.id 里可能带调用方给的值（通告地址、显式配置的消费组、实例 id），
     * Kafka 不限制 client.id 的字符集，这里也不收紧（否则 IPv6 通告地址之类的现有部署会被拒启）。包内可见只为测试。
     */
    String client(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            throw new IllegalArgumentException("Kafka client.id 的后缀不能为空");
        }
        return prefix + suffix;
    }
}
