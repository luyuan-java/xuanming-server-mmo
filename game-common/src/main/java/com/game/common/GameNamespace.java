package com.game.common;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 部署命名空间（配置 {@code game.namespace}，环境变量 {@code GAME_NAMESPACE}，缺省 {@code game}）：共享中间件里一切名字的租户段。
 * 两个游戏（或同一游戏的两套部署）共用同一套 Redis / Kafka / MySQL 时，各取不同的命名空间就互不串数据：
 * <ul>
 *   <li>Redis 键与 pub/sub 频道的前缀 {@code <ns>:}（唯一出处 {@code com.game.discovery.RedisKeys}）；</li>
 *   <li>Kafka topic、消费组缺省值与 client.id 的前缀 {@code <ns>-}（唯一出处 {@link KafkaNames}）；</li>
 *   <li>MySQL 库名 {@code <ns>}（各 application.yaml 的 JDBC URL 写 {@code ${game.namespace:game}}，连库后核对 {@code DATABASE()}，见 {@link NamespaceDatabaseCheck}）。</li>
 * </ul>
 * Nacos namespace 不由它自动推导：nacos profile 下由部署显式配置（{@code GAME_NACOS_ADDR} 里带 {@code ?namespace=}，或 Dubbo 自带的配置项
 * {@code dubbo.registry.parameters.namespace}，architecture.md §6.2）。代码身份不跟它走：配置键前缀 {@code game.}、环境变量前缀 {@code GAME_}、Java 包、
 * 指标名、HTTP 运维头、Dubbo 鉴权附件键、HMAC 域分隔串都不变。
 *
 * <p><b>注入式，没有进程级全局值</b>：本类是不可变的值类型，构造即校验。Spring 进程里由 game-discovery 的 {@code GameNamespaceProperties}
 * （{@code @ConfigurationProperties} + {@code @Validated}，非法值由 Spring Boot 的绑定校验报告拒启）绑定，{@code GameNamespaceAutoConfiguration}
 * 注册成 bean，再由它构造 {@code RedisKeys} / {@link KafkaNames} bean 注入给使用方；不起 Spring 的命令行工具用 {@link #fromEnv} 取值后自己
 * {@code new} 名字类；测试直接构造任意命名空间的实例，同一个 JVM 里可以并存多个命名空间。
 *
 * <p>合法取值 {@value #REGEX}：小写字母开头、只含小写字母与数字、1–{@value #MAX_LENGTH} 个字符，且不是 {@code mysql} / {@code sys}
 * （MySQL 系统库）/ {@code public}（Nacos 缺省命名空间 ID）。不 trim、不转小写：空串、首尾空白、大写一律拒绝（fail-closed）。
 * 字符集保证它在 Redis 键、Kafka topic、MySQL 库名与账号名、K8s 标签值与 namespace 名、Nacos namespace ID 里都合法且无需转义，
 * 且「{@code <ns>:}」「{@code <ns>-}」两类前缀互不为前缀、不进 hash tag、不含 glob 元字符。
 *
 * @param value 命名空间的值（构造时校验，非法抛 {@link IllegalArgumentException}）
 */
public record GameNamespace(String value) {

    /** 配置键（Spring 宽松绑定下也认 {@code --game.namespace}、{@code -Dgame.namespace}、{@code GAME_NAMESPACE}）。 */
    public static final String PROPERTY = "game.namespace";
    /** 环境变量名（部署一律经它给值；不起 Spring 的命令行工具只认它，见 {@link #fromEnv}）。 */
    public static final String ENV = "GAME_NAMESPACE";
    /** 缺省命名空间：缺省时生成的每一个名字都与引入命名空间之前逐字节相同。 */
    public static final String DEFAULT_VALUE = "game";
    /**
     * 最长 28 个字符：标准 MySQL 账号名是 {@code <ns>_app}（ops-release-spec §7.5 / Q21），MySQL 用户名上限 32 个字符，减去后缀 {@code _app}。
     * 校验直接强制这条约定，超长的值在启动时就被拒绝，不会拖到 {@code CREATE USER} 才失败。
     */
    public static final int MAX_LENGTH = 28;
    /**
     * 合法取值（含保留字）的正则；{@code GameNamespaceProperties} 的 {@code @Pattern} 与 {@link #isValid} 共用这一个编译期常量。
     * 长度上限与 {@link #MAX_LENGTH} 一致（{@code {0,27}} = 首字母之后至多 27 个）。
     */
    public static final String REGEX = "(?!(?:mysql|sys|public)$)[a-z][a-z0-9]{0,27}";
    /**
     * 规则说明：{@code @Pattern} 的 message 与报错共用。不得含花括号与美元符（会被 Bean Validation 当成消息插值）。
     */
    public static final String RULE = "必须小写字母开头、只含小写字母与数字、1–28 个字符（不去空白、不转小写），且不是 mysql / sys / public";

    private static final Pattern PATTERN = Pattern.compile(REGEX);
    /** 保留字（已编进 {@link #REGEX}；这里只用来把报错原因说具体）。 */
    private static final Set<String> RESERVED = Set.of("mysql", "sys", "public");

    /** 构造即校验；非法抛 {@link IllegalArgumentException}（来源记「构造参数」）。 */
    public GameNamespace {
        requireValid(value, "构造参数");
    }

    /** 校验并构造（来源记「调用方」）。 */
    public static GameNamespace of(String value) {
        return of(value, "调用方");
    }

    /**
     * 先校验再构造；非法抛 {@link IllegalArgumentException}，消息点名 {@value #PROPERTY} / {@value #ENV}、带原值（{@link #quote}）与来源。
     *
     * @param source 这个值从哪来（只进报错，如 {@code "game.namespace"}、{@code "环境变量 GAME_NAMESPACE"}）
     */
    public static GameNamespace of(String value, String source) {
        requireValid(value, source);
        return new GameNamespace(value);
    }

    /** 是不是合法的命名空间（null 不合法）。 */
    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    /** {@link #fromEnv} 的结果：命名空间与它的来源（「环境变量 GAME_NAMESPACE」或「缺省」，供工具打印）。 */
    public record Sourced(GameNamespace namespace, String source) {

        public Sourced {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * 不起 Spring 的命令行工具取命名空间：环境变量映射里有 {@value #ENV} 就按原样校验（空串照样拒绝，不当成「没设」），没有才取
     * {@value #DEFAULT_VALUE}。不读系统属性、yaml 或命令行参数：部署一律经环境变量给命名空间，工具与服务进程才保证同值。
     *
     * @param env 环境变量映射（生产传 {@code System.getenv()}，测试可替换）
     * @throws IllegalArgumentException 取值非法
     */
    public static Sourced fromEnv(Map<String, String> env) {
        String value = env.get(ENV);
        if (value == null) {
            return new Sourced(new GameNamespace(DEFAULT_VALUE), "缺省");
        }
        String source = "环境变量 " + ENV;
        return new Sourced(of(value, source), source);
    }

    @Override
    public String toString() {
        return value;
    }

    private static void requireValid(String value, String source) {
        if (isValid(value)) {
            return;
        }
        String why = value == null ? "不能为 null"
                : RESERVED.contains(value) ? "是保留字（mysql / sys 是 MySQL 系统库，public 是 Nacos 的缺省命名空间）"
                : RULE;
        throw new IllegalArgumentException("部署命名空间 " + PROPERTY + "（环境变量 " + ENV + "）的取值 " + quote(value) + "（来源 " + source + "）不合法："
                + why);
    }

    /** 报错里展示原值：带引号、控制字符转义，首尾空白看得见。 */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
