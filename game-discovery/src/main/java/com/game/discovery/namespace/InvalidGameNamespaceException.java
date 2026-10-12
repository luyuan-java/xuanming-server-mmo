package com.game.discovery.namespace;

/**
 * 部署命名空间拒启：取值非法（{@link #isConflict()} 为 false），或与进程里已定格的值冲突（为 true）。由 {@link GameNamespaceEnvironmentPostProcessor}
 * （建任何 bean 之前）或 {@link GameNamespaceAutoConfiguration}（上下文内核对）抛出，{@link GameNamespaceFailureAnalyzer} 按 Spring Boot 的标准格式报告。
 * 消息里有原值与来源，不含任何秘密。
 */
public class InvalidGameNamespaceException extends IllegalStateException {

    private final String value;
    private final String origin;
    private final boolean conflict;

    /**
     * @param value  Spring Environment 解析出的取值（可能为 null）
     * @param origin 取值的来源（属性源名）
     * @param cause  {@code GameNamespace} 抛的 {@link IllegalArgumentException}（非法）或 {@link IllegalStateException}（冲突）
     */
    public InvalidGameNamespaceException(String value, String origin, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.value = value;
        this.origin = origin;
        this.conflict = cause instanceof IllegalStateException;
    }

    /** Spring Environment 解析出的取值。 */
    public String value() {
        return value;
    }

    /** 取值的来源（属性源名，没配时为「缺省」）。 */
    public String origin() {
        return origin;
    }

    /** true = 与已定格的值冲突；false = 取值本身非法。 */
    public boolean isConflict() {
        return conflict;
    }
}
