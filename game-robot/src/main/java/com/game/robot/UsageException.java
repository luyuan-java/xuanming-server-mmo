package com.game.robot;

/** 命令行 / 环境变量不合法，或请求了帮助（{@link #isHelp()}）。 */
public final class UsageException extends Exception {

    private final boolean help;

    UsageException(String message) {
        this(message, false);
    }

    private UsageException(String message, boolean help) {
        super(message);
        this.help = help;
    }

    static UsageException help() {
        return new UsageException("", true);
    }

    public boolean isHelp() {
        return help;
    }
}
