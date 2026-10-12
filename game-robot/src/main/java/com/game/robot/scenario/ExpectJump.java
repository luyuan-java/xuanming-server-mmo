package com.game.robot.scenario;

import java.util.Locale;

/**
 * 移动场景里「超速跳跃」负向检查的期望（movement 契约 §4.3 / §4.4 / §9 第 6 条）。
 * 契约允许两种服务端行为：基线在无导航网格时 fail-open 原样接受（不回 137）；
 * 服务端若做位移校验（Java 版 {@code MoveGuard}），截断后水平偏差 &gt; 0.5 m 就必须给本人回 137。
 */
public enum ExpectJump {
    /** 两种都接受，只校验观察到的那一种自洽且符合契约。 */
    AUTO,
    /** 必须纠偏：收到 137，且落盘位置 = 137 的 server_location。 */
    CORRECT,
    /** 必须 fail-open：不回 137，且落盘位置 = 上报的跳跃位置。 */
    ACCEPT;

    public static ExpectJump parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
