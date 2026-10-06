package com.game.battle.testing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试假件共用的调用记录：假 Redis 端口、假定位器、记录型传输与假发布端口都往同一份里追加，下标就是<b>全局序号</b>，
 * 用来断言跨端口的先后（scene-battle-spec §13.5：「投递一定排在落库结局之后」）。线程安全（真线程的停机用例也用它）。
 *
 * <p>条目的写法（各假件统一）：{@code store:<pid>/<bid>}（发起落库）、{@code store-done:<pid>/<bid>=<返回值>}（落库的结局交回调用方）、
 * {@code exists:<pid>/<bid>}、{@code superseded?:<pid>/<bid>}、{@code locate:<pid>}、{@code deliver:<pid>/<bid>@<实例>#<attempt>}、
 * {@code confirm:<pid>/<bid>@<实例>}、{@code find:<zone>/<node>}、{@code activity-store:<bid>}、{@code activity-store-done:<bid>=<返回值>}、
 * {@code activity-exists:<bid>}、{@code publish:<bid>@<通道>}。id 一律按无符号十进制。
 *
 * <p>每条另记<b>追加它的线程名</b>（{@link #threadsOf}）：端口调用的条目是被测类发起调用的那条线程，{@code …-done} 的条目是完成 future 的那条线程。
 * 真线程用例拿它断言线程所有权（名单与端口调用只在 {@code battle-outbox} 上，D25）。
 */
public final class CallJournal {

    private record Line(String text, String thread) {
    }

    private final List<Line> lines = new CopyOnWriteArrayList<>();

    /** 追加一条（连同当前线程名），返回它的全局序号（从 0 起）。 */
    public int add(String entry) {
        synchronized (lines) {
            lines.add(new Line(entry, Thread.currentThread().getName()));
            return lines.size() - 1;
        }
    }

    /** 全部条目（按发生顺序的快照）。 */
    public List<String> entries() {
        return lines.stream().map(Line::text).toList();
    }

    /** 第一条等于 {@code entry} 的序号；没有为 -1。 */
    public int indexOf(String entry) {
        return entries().indexOf(entry);
    }

    /** 以 {@code prefix} 开头的条目（按发生顺序）。 */
    public List<String> starting(String prefix) {
        return lines.stream().map(Line::text).filter(e -> e.startsWith(prefix)).toList();
    }

    /** 以 {@code prefix} 开头的条目数。 */
    public int count(String prefix) {
        return starting(prefix).size();
    }

    /** 以 {@code prefix} 开头的条目各自是在哪条线程上追加的（按发生顺序，与 {@link #starting} 一一对应）。 */
    public List<String> threadsOf(String prefix) {
        return lines.stream().filter(line -> line.text().startsWith(prefix)).map(Line::thread).toList();
    }

    public void clear() {
        lines.clear();
    }

    @Override
    public String toString() {
        return entries().toString();
    }
}
