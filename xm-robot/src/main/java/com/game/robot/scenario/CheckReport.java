package com.game.robot.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次场景运行的判定结果：逐条检查（通过 / 失败 + 细节 + 依据的契约章节）与不参与判定的观察记录。
 * 至少有一条检查、且全部通过才算通过——一条都没跑到（例如第一步就抛异常）不能算通过。线程安全。
 */
public final class CheckReport {

    /** 一条检查。{@code ref} 是依据（契约文档章节），可为空串。 */
    public record Item(boolean passed, String name, String detail, String ref) {
    }

    private final List<Item> items = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public synchronized void check(boolean passed, String name, String detail, String ref) {
        items.add(new Item(passed, name, detail, ref));
    }

    public void pass(String name, String detail, String ref) {
        check(true, name, detail, ref);
    }

    public void fail(String name, String detail, String ref) {
        check(false, name, detail, ref);
    }

    /** 观察记录：不影响结论（例如「服务端对跳跃选择了纠偏」「66 带了 entity_id」）。 */
    public synchronized void note(String text) {
        notes.add(text);
    }

    public synchronized boolean passed() {
        return !items.isEmpty() && items.stream().allMatch(Item::passed);
    }

    public synchronized List<Item> items() {
        return List.copyOf(items);
    }

    public synchronized List<String> notes() {
        return List.copyOf(notes);
    }

    public synchronized long failures() {
        return items.stream().filter(i -> !i.passed()).count();
    }

    /** 中文汇总：检查逐条列出，观察记录附后，最后一行是结论。 */
    public synchronized String render(String title) {
        StringBuilder out = new StringBuilder();
        out.append("== ").append(title).append(" ==\n");
        for (Item item : items) {
            out.append(item.passed() ? "[通过] " : "[失败] ").append(item.name());
            if (!item.detail().isEmpty()) {
                out.append("：").append(item.detail());
            }
            if (!item.ref().isEmpty()) {
                out.append("  〔").append(item.ref()).append("〕");
            }
            out.append('\n');
        }
        if (!notes.isEmpty()) {
            out.append("-- 观察记录（不参与判定）--\n");
            for (String note : notes) {
                out.append("  · ").append(note).append('\n');
            }
        }
        long failed = failures();
        out.append("结论：");
        if (items.isEmpty()) {
            out.append("失败（没有完成任何检查）");
        } else if (failed == 0) {
            out.append("通过（").append(items.size()).append(" 项检查全部通过）");
        } else {
            out.append("失败（").append(failed).append(" / ").append(items.size()).append(" 项未通过）");
        }
        return out.append('\n').toString();
    }
}
