package com.game.table.load;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

/** 收集外键失配，全部检查完一次性报告（只列前若干条，避免一列填错刷屏）。 */
public final class ForeignKeyProblems {

    private static final System.Logger LOG = System.getLogger(ForeignKeyProblems.class.getName());
    static final int MAX_LISTED = 50;

    private final List<String> listed = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private int total;

    /**
     * @param foreignKey 形如 {@code Mission.reward_id -> Reward.id}
     * @param rowKey     引用方那一行的主键
     * @param value      在目标列里找不到的取值
     */
    public void add(String foreignKey, Object rowKey, Object value) {
        total++;
        if (listed.size() < MAX_LISTED) {
            listed.add(foreignKey + "：行 " + rowKey + " 引用的 " + value + " 不存在");
        }
    }

    /**
     * 目标列没有任何非空取值（空表、或该列全空）：没法校验，跳过并告警，不算失配。
     * 与导表器 {@code foreign_key.py} 一致——那边同样只告警、照常产出，C++ / Go 也照常加载。
     */
    public void skipEmptyTarget(String foreignKey) {
        skipped.add(foreignKey);
        LOG.log(Level.WARNING, "外键 {0} 的目标列没有任何取值（空表？），跳过校验", foreignKey);
    }

    public int total() {
        return total;
    }

    /** 因目标为空而跳过的外键。 */
    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    /** @throws TableLoadException 有任何失配 */
    public void throwIfAny() {
        if (total == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder("配置表外键校验失败，共 ").append(total).append(" 处");
        if (total > listed.size()) {
            sb.append("（只列前 ").append(listed.size()).append(" 处）");
        }
        for (String line : listed) {
            sb.append("\n  ").append(line);
        }
        throw new TableLoadException(sb.toString());
    }
}
