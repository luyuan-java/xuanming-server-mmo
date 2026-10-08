package com.game.robot.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景的「步骤 → 结果行」记账（批次 6.4 的 battle-smoke / match-activity / match-5v5 / team；同基线 Go robot 的
 * {@code BATTLE_SMOKE_OK …} / {@code BATTLE_SMOKE_FAIL step=… reason=…}，供外层脚本按子串消费）。
 *
 * <p>用法：每进入一步调一次 {@link #step}（记下「这一步从报告的第几条检查开始」）；场景跑完后 {@link #line} 给出一行结论：
 * 全部通过 → {@code <标记>_OK <字段>}；否则 → {@code <标记>_FAIL step=<第一条失败检查所在的步骤> reason=<那条检查的名字与细节>}。
 * 标记与步骤号是纯 ASCII（脚本按子串匹配，不受标准输出编码影响）；{@code reason} 是给人看的中文，压成一行。
 *
 * <p>只在场景线程上用，不加锁。
 */
final class StepTrack {

    /** 还没进入任何一步就失败（建连、解析消息号之前）。 */
    static final String BEFORE_FIRST_STEP = "start";
    /** 一条检查都没跑到。 */
    static final String NO_CHECK = "none";
    /** reason 的长度上限（一行日志，细节看上面的逐条报告）。 */
    static final int MAX_REASON_CHARS = 300;

    private record Mark(String step, int firstItem) {
    }

    private final String marker;
    private final List<Mark> marks = new ArrayList<>();

    /** @param marker 结果行的前缀，如 {@code BATTLE_SMOKE}（大写字母、数字、下划线） */
    StepTrack(String marker) {
        if (!marker.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("结果行的标记只能是大写字母、数字、下划线：" + marker);
        }
        this.marker = marker;
    }

    /**
     * 进入一步。
     *
     * @param step 步骤号：小写字母、数字、连字符（如 {@code 4-pve-solo}、{@code s7-team-battle}）
     */
    void step(String step, CheckReport report) {
        if (!step.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("步骤号只能是小写字母、数字、连字符：" + step);
        }
        marks.add(new Mark(step, report.items().size()));
    }

    /** 当前所在的步骤；还没进入任何一步时是 {@value #BEFORE_FIRST_STEP}。 */
    String current() {
        return marks.isEmpty() ? BEFORE_FIRST_STEP : marks.get(marks.size() - 1).step();
    }

    /** 报告里第 {@code itemIndex} 条检查属于哪一步。 */
    String stepOf(int itemIndex) {
        String step = BEFORE_FIRST_STEP;
        for (Mark mark : marks) {
            if (mark.firstItem() > itemIndex) {
                break;
            }
            step = mark.step();
        }
        return step;
    }

    /**
     * 结果行。
     *
     * @param okFields 通过时跟在 {@code _OK} 后面的字段（{@code key=value} 空格分隔；空串则只有标记）
     */
    String line(CheckReport report, String okFields) {
        List<CheckReport.Item> items = report.items();
        if (items.isEmpty()) {
            return marker + "_FAIL step=" + NO_CHECK + " reason=没有完成任何检查";
        }
        for (int i = 0; i < items.size(); i++) {
            CheckReport.Item item = items.get(i);
            if (!item.passed()) {
                return marker + "_FAIL step=" + stepOf(i) + " reason=" + oneLine(item.name() + (item.detail().isEmpty() ? "" : "：" + item.detail()));
            }
        }
        return okFields.isEmpty() ? marker + "_OK" : marker + "_OK " + okFields;
    }

    /** 压成一行：换行与连续空白折成一个空格，超长截断。 */
    static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > MAX_REASON_CHARS ? flat.substring(0, MAX_REASON_CHARS) + "…" : flat;
    }
}
