package com.game.common;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 部署命名空间（配置 {@code game.namespace}，环境变量 {@code GAME_NAMESPACE}，缺省 {@code game}）：共享中间件里一切名字的租户段。
 * 两个游戏（或同一游戏的两套部署）共用同一套 Redis / Kafka / MySQL（以及 Nacos）时，各取不同的命名空间就互不串数据：
 * <ul>
 *   <li>Redis 键与 pub/sub 频道的前缀 {@code <ns>:}（唯一出处 {@code com.game.discovery.RedisKeys}）；</li>
 *   <li>Kafka topic、消费组缺省值与 client.id 的前缀 {@code <ns>-}（唯一出处 {@code com.game.audit.KafkaNames}）；</li>
 *   <li>MySQL 缺省库名 {@code <ns>}（各 application.yaml 的 JDBC URL 写 {@code ${game.namespace:game}}）；</li>
 *   <li>Nacos：命名空间不是 {@code game} 时映射成同名的 Nacos namespace（见 game-discovery 的 {@code GameNamespaceEnvironmentPostProcessor}）。</li>
 * </ul>
 * 代码身份不跟它走：配置键前缀 {@code game.}、环境变量前缀 {@code GAME_}、Java 包、指标名、HTTP 运维头、Dubbo 鉴权附件键、HMAC 域分隔串都不变。
 *
 * <p><b>进程内只定一次（定格），没有 reset</b>：第一次 {@link #get()} 或 {@link #set} 就定下来，之后再设不同的值抛 {@link IllegalStateException}。
 * 名字类（RedisKeys、KafkaNames 等）在类初始化时取这个值，定格保证类初始化时取到的与之后每次取到的恒等。
 * Spring 进程由 EnvironmentPostProcessor 在建任何 bean 之前 {@link #set}；不起 Spring 的入口（命令行工具）必须在生成第一个名字之前显式 {@link #set}。
 * {@link #get()} 在未定格时按「系统属性 {@value #PROPERTY} → 环境变量 {@value #ENV} → {@value #DEFAULT}」回落，只是兜底（单元测试、未来的非 Spring 入口）。
 *
 * <p>合法取值 {@code [a-z][a-z0-9]{0,31}}，且不是 {@code mysql} / {@code sys}（MySQL 系统库）/ {@code public}（Nacos 缺省命名空间 ID）。
 * 不 trim、不转小写：空串、首尾空白、大写一律拒绝（fail-closed）。字符集保证它在 Redis 键、Kafka topic、MySQL 库名、K8s 标签值、
 * Nacos namespace ID 里都合法且无需转义，且「{@code <ns>:}」「{@code <ns>-}」两类前缀互不为前缀、不进 hash tag、不含 glob 元字符。
 */
public final class GameNamespace {

    /** 配置键（Spring 宽松绑定下也认 {@code --game.namespace}、{@code -Dgame.namespace}、{@code GAME_NAMESPACE}）。 */
    public static final String PROPERTY = "game.namespace";
    /** 环境变量名。 */
    public static final String ENV = "GAME_NAMESPACE";
    /** 缺省命名空间：缺省时生成的每一个名字都与引入命名空间之前逐字节相同。 */
    public static final String DEFAULT = "game";
    /** 最长 32 个字符（按 {@code <ns>_app} 起 MySQL 账号时，命名空间不能超过 28 个字符：MySQL 用户名上限 32）。 */
    public static final int MAX_LENGTH = 32;
    /** 合法取值的正则（写进报错与文档）。 */
    public static final String PATTERN_TEXT = "[a-z][a-z0-9]{0,31}";

    private static final Pattern PATTERN = Pattern.compile(PATTERN_TEXT);
    /** 正则挡不住的保留字：MySQL 系统库 mysql / sys；Nacos 缺省命名空间 ID public（映射过去会与缺省的 game 落进同一个 namespace）。 */
    private static final Set<String> RESERVED = Set.of("mysql", "sys", "public");

    private static final NamespaceHolder GLOBAL = new NamespaceHolder(System::getProperty, System::getenv);

    private GameNamespace() {
    }

    /** 本进程的命名空间（未定格时按回落顺序解析并定格；回落值非法抛 {@link IllegalArgumentException}，不定格）。 */
    public static String get() {
        return GLOBAL.get();
    }

    /**
     * 定格本进程的命名空间：先校验，再定格。已定格为同一个值时幂等；值不同抛 {@link IllegalStateException}（消息里有两次的值与来源）；
     * 校验失败抛 {@link IllegalArgumentException}，不定格。
     *
     * @param source 这个值从哪来（写进日志与报错，如 {@code "Spring Environment（systemEnvironment）"}、{@code "AuditFallbackReplay"}）
     */
    public static void set(String value, String source) {
        GLOBAL.set(value, source);
    }

    /** 已定格的值的来源；未定格时为空串（只供日志）。 */
    public static String source() {
        return GLOBAL.source();
    }

    /** 是不是合法的命名空间（null 不合法）。 */
    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches() && !RESERVED.contains(value);
    }

    /**
     * 校验并原样返回；非法抛 {@link IllegalArgumentException}，消息点名 {@value #PROPERTY} / {@value #ENV}、带原值与来源。
     *
     * @param source 这个值从哪来（只进报错）
     */
    public static String validate(String value, String source) {
        if (isValid(value)) {
            return value;
        }
        String why;
        if (value == null) {
            why = "不能为 null";
        } else if (RESERVED.contains(value)) {
            why = "是保留字（mysql / sys 是 MySQL 系统库，public 是 Nacos 的缺省命名空间）";
        } else {
            why = "必须匹配 " + PATTERN_TEXT + "（小写字母开头，只含小写字母与数字，1–" + MAX_LENGTH + " 个字符；不去空白、不转小写）";
        }
        throw new IllegalArgumentException("部署命名空间 " + PROPERTY + "（环境变量 " + ENV + "）的取值 " + quote(value) + "（来源 " + source + "）"
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
