package com.game.common;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 部署命名空间的定格状态机（{@link GameNamespace} 是它的静态门面；单元测试直接 new 一个，不碰进程级的那一个）。
 *
 * <p>只有两种状态：未定格、已定格（值 + 来源）。第一次 {@link #get()} 或 {@link #set} 用 CAS 定格，之后不可再改、没有 reset。
 * 回落只认两个精确的名字（系统属性 {@value GameNamespace#PROPERTY}、环境变量 {@value GameNamespace#ENV}），不做 Spring 那套宽松名。
 */
final class NamespaceHolder {

    /** 定格的值与来源。 */
    record Fixed(String value, String source) {
    }

    private final AtomicReference<Fixed> fixed = new AtomicReference<>();
    private final Function<String, String> systemProperty;
    private final Function<String, String> environment;

    /**
     * @param systemProperty 系统属性查找（生产 {@code System::getProperty}）
     * @param environment    环境变量查找（生产 {@code System::getenv}）
     */
    NamespaceHolder(Function<String, String> systemProperty, Function<String, String> environment) {
        this.systemProperty = Objects.requireNonNull(systemProperty, "systemProperty");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** 已定格就返回；否则按「系统属性 → 环境变量 → 缺省」解析、校验、定格（来源记为 fallback）。回落值非法抛 {@link IllegalArgumentException}，不定格。 */
    String get() {
        Fixed current = fixed.get();
        if (current != null) {
            return current.value();
        }
        Fixed candidate = fallback();
        fixed.compareAndSet(null, candidate);
        return fixed.get().value();
    }

    /** 先校验再定格；同值幂等，异值抛 {@link IllegalStateException}；校验失败抛 {@link IllegalArgumentException} 且不定格。 */
    void set(String value, String source) {
        String src = source == null || source.isBlank() ? "未注明" : source;
        GameNamespace.validate(value, src);
        Fixed want = new Fixed(value, src);
        if (fixed.compareAndSet(null, want)) {
            return;
        }
        Fixed have = fixed.get();
        if (!have.value().equals(value)) {
            throw new IllegalStateException("部署命名空间 " + GameNamespace.PROPERTY + " 在本进程里已定格为 " + GameNamespace.quote(have.value())
                    + "（来源 " + have.source() + "），不能再设为 " + GameNamespace.quote(value) + "（来源 " + src + "）：一个进程只能有一个命名空间，"
                    + "所有入口必须取同一个值（main() 在 SpringApplication.run 之前、EnvironmentPostProcessor 在设定之前都不得触碰名字类）");
        }
    }

    /** 已定格的来源；未定格为空串。 */
    String source() {
        Fixed current = fixed.get();
        return current == null ? "" : current.source();
    }

    private Fixed fallback() {
        String fromProperty = systemProperty.apply(GameNamespace.PROPERTY);
        if (fromProperty != null) {
            String source = "fallback（系统属性 " + GameNamespace.PROPERTY + "）";
            return new Fixed(GameNamespace.validate(fromProperty, source), source);
        }
        String fromEnv = environment.apply(GameNamespace.ENV);
        if (fromEnv != null) {
            String source = "fallback（环境变量 " + GameNamespace.ENV + "）";
            return new Fixed(GameNamespace.validate(fromEnv, source), source);
        }
        return new Fixed(GameNamespace.DEFAULT, "fallback（缺省）");
    }
}
