package com.game.robot.flow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个账号各步骤的耗时（毫秒），按记录顺序保存。失败时已完成的步骤仍保留，汇总里能看到卡在哪一步。
 * 只在该账号自己的线程里写，汇总时读（线程结束之后），不需要同步。
 */
public final class Timings {

    private final Map<String, Long> millis = new LinkedHashMap<>();

    /** 记一步：{@code startNanos} 到现在。 */
    public void record(String step, long startNanos) {
        millis.put(step, (System.nanoTime() - startNanos) / 1_000_000);
    }

    /** 记一步：已算好的毫秒数（例如「从发出进游戏到 79 到达」这种跨越多个调用的区间）。 */
    public void recordMillis(String step, long ms) {
        millis.put(step, ms);
    }

    /** 某步的毫秒数；没走到这一步为 null。 */
    public Long get(String step) {
        return millis.get(step);
    }

    public Map<String, Long> asMap() {
        return Collections.unmodifiableMap(millis);
    }
}
