package com.game.table.load;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 表内 tip 码引用校验（基线导表器 enum_gen.py {@code validate_tip_references}：生成前 fail-closed）：每个 tip 引用必须是 0 或一个
 * 当前活动的 tip 码，防止 tip 码轴重排后表里的旧数字静默变成未知码。收集全部失配后一次性报告（只列前若干条）。
 */
public final class TipReferenceProblems {

    static final int MAX_LISTED = 50;

    private final long[] validCodes;
    private final List<String> listed = new ArrayList<>();
    private int total;

    /** @param validCodes 合法 tip 码（含 0），升序 */
    public TipReferenceProblems(long[] validCodes) {
        this.validCodes = validCodes.clone();
        Arrays.sort(this.validCodes);
    }

    /** uint32 / int32 列：按无符号看（uint32 大值、int32 负数都不是合法码）。 */
    public void check(String column, Object rowKey, int value) {
        check(column, rowKey, Integer.toUnsignedLong(value));
    }

    /**
     * @param column 形如 {@code MessageLimiter.tip_message}
     * @param rowKey 该行主键
     */
    public void check(String column, Object rowKey, long value) {
        if (value == 0 || Arrays.binarySearch(validCodes, value) >= 0) {
            return;
        }
        total++;
        if (listed.size() < MAX_LISTED) {
            listed.add(column + "：行 " + rowKey + " 引用的 tip 码 " + Long.toUnsignedString(value) + " 不存在");
        }
    }

    public int total() {
        return total;
    }

    /** @throws TableLoadException 有任何失配 */
    public void throwIfAny() {
        if (total == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder("配置表 tip 引用校验失败，共 ").append(total).append(" 处");
        if (total > listed.size()) {
            sb.append("（只列前 ").append(listed.size()).append(" 处）");
        }
        for (String line : listed) {
            sb.append("\n  ").append(line);
        }
        throw new TableLoadException(sb.toString());
    }
}
