package com.game.common;

import java.util.Locale;
import java.util.Optional;

/**
 * 进程运行模式（环境变量 {@code XM_RUN_MODE} / 配置 {@code xm.run-mode}），同 mmorpg 的 {@code GATE_RUN_MODE} / {@code SCENE_RUN_MODE}。
 *
 * <p>唯一用途是 GM 类客户端指令（方法名 {@code Gm*} / {@code Debug*} / {@code Test*}）的闸门：只有 dev / test 放行，
 * 其余（含未设、写错）一律按 prod 拒绝——fail-closed，生产环境忘了配也不会把 GM 指令放出去。
 * 认识的取值同基线（{@code token_security.h ParseRunMode}）：大小写不敏感、去首尾空白，含别名。
 */
public enum RunMode {
    PROD,
    DEV,
    TEST;

    /** null、空串与不认识的值都是 PROD；不认识的值调用方应告警（见 {@link #isRecognized}）。 */
    public static RunMode parse(String value) {
        return lookup(value).orElse(PROD);
    }

    /**
     * 是不是认识的取值（null / 空串 = 未配置，算认识）。把 dev 拼成 "develop" 而静默按生产跑是最容易踩的坑，
     * 启动时据此打一条 WARN。
     */
    public static boolean isRecognized(String value) {
        return lookup(value).isPresent();
    }

    private static Optional<RunMode> lookup(String value) {
        if (value == null) {
            return Optional.of(PROD);
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "", "prod", "production", "release", "live" -> Optional.of(PROD);
            case "dev", "development", "local" -> Optional.of(DEV);
            case "test", "testing" -> Optional.of(TEST);
            default -> Optional.empty();
        };
    }

    public boolean allowsGmCommands() {
        return this == DEV || this == TEST;
    }
}
