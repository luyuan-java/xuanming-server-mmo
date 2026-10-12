package com.game.robot.scenario;

import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * travel 场景的留底（批次 5.4，zone-travel-spec §11.11；对照基线 {@code travel_smoke_scenario.go:435-479}、{@code :737-799}）：
 * 场景打开过的每条连接按打开先后编号（从 1 起），收到的全部下行按到达时刻合成<b>一条</b>时间线——(连接序号, 消息号, 包体) 都在
 * {@link Entry#frame()} 里。跨连接比先后只能靠到达时刻（{@link Received#receivedNanos()}，同一把 {@link System#nanoTime()} 钟），
 * 单条连接内的序号比不了两条连接。
 *
 * <p>判据（{@link #problems}）按「窗口」给：窗口是一段时间（起点含、终点不含），各自期望恰好几条 124（0 或 1）；期望 1 条且要落地的窗口里，
 * 每一条 79 都必须晚于那条 124、124 之后至少有一条 79。窗口由场景在动作之前取时刻划出（例：发去程 226 之前、关掉 T7 的连接之前），
 * 跨连接的 124 也算在内——「一次动作恰好一跳」看的是整个客户端，不是某一条连接。
 *
 * <p>线程：场景线程登记连接与取时间线；各连接的收件箱自带锁，取的是那一刻的快照。
 */
final class TravelLedger {

    /** 时间线上的一条：哪条连接（序号与用途）、那条下行本身。 */
    record Entry(int connection, String label, Received frame) {

        long nanos() {
            return frame.receivedNanos();
        }

        int messageId() {
            return frame.messageId();
        }

        /** 报告里用：{@code #连接序号(用途)第 n 条}。 */
        String where() {
            return "#" + connection + "(" + label + ")第 " + frame.index() + " 条";
        }
    }

    /**
     * 一个窗口。
     *
     * @param name      报告里的名字（如「T5 去程」）
     * @param fromNanos 起点（含），{@link System#nanoTime()}
     * @param toNanos   终点（不含）；{@link Long#MAX_VALUE} = 到现在为止
     * @param redirects 期望的 124 条数（0 或 1）
     * @param lands     期望 1 条时：124 之后要不要有 79（跟随落地）。拿到 124 不跟随的窗口（travel-abandon）为 false
     */
    record Window(String name, long fromNanos, long toNanos, int redirects, boolean lands) {

        Window {
            if (redirects < 0 || redirects > 1) {
                throw new IllegalArgumentException("一个窗口只期望 0 或 1 条 124：" + redirects);
            }
        }

        static Window none(String name, long fromNanos, long toNanos) {
            return new Window(name, fromNanos, toNanos, 0, false);
        }

        static Window hop(String name, long fromNanos, long toNanos) {
            return new Window(name, fromNanos, toNanos, 1, true);
        }

        static Window unfollowed(String name, long fromNanos, long toNanos) {
            return new Window(name, fromNanos, toNanos, 1, false);
        }

        boolean contains(long nanos) {
            return nanos >= fromNanos && nanos < toNanos;
        }
    }

    private final List<GameConnection> connections = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    /**
     * 登记一条连接（打开之后立即登记：之前到的下行也都在它的收件箱里）。
     *
     * @param label 用途（报告里用，如 {@code A-home}、{@code A-visit}、{@code B-ticket}）
     * @return 连接序号（从 1 起）
     */
    int add(String label, GameConnection connection) {
        connections.add(connection);
        labels.add(label);
        return connections.size();
    }

    /** 登记过几条连接。 */
    int size() {
        return connections.size();
    }

    /** 此刻的时间线：全部连接的全部下行按到达时刻排序（同一时刻按连接序号、再按连接内序号）。 */
    List<Entry> timeline() {
        List<Entry> all = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            for (Received frame : connections.get(i).inbox().snapshot(0)) {
                all.add(new Entry(i + 1, labels.get(i), frame));
            }
        }
        all.sort(ORDER);
        return all;
    }

    static final Comparator<Entry> ORDER = Comparator.comparingLong(Entry::nanos)
            .thenComparingInt(Entry::connection)
            .thenComparingInt(e -> e.frame().index());

    /**
     * 按窗口核对 124 与 79（纯函数）。
     *
     * @param timeline   {@link #timeline()}（已按到达时刻排序）
     * @param windows    窗口（可以不相接；不在任何窗口里的 124 另报）
     * @param redirectId 124 的消息号
     * @param enterScene 79 的消息号
     * @return 每条不符一句话；空 = 全部符合
     */
    static List<String> problems(List<Entry> timeline, List<Window> windows, int redirectId, int enterScene) {
        List<String> problems = new ArrayList<>();
        for (Window window : windows) {
            List<Entry> redirects = new ArrayList<>();
            List<Entry> scenes = new ArrayList<>();
            for (Entry entry : timeline) {
                if (!window.contains(entry.nanos())) {
                    continue;
                }
                if (entry.messageId() == redirectId) {
                    redirects.add(entry);
                } else if (entry.messageId() == enterScene) {
                    scenes.add(entry);
                }
            }
            if (redirects.size() != window.redirects()) {
                problems.add("窗口「" + window.name() + "」有 " + redirects.size() + " 条 124（期望恰好 " + window.redirects() + " 条）"
                        + (redirects.isEmpty() ? "" : "：" + wheres(redirects)));
                continue;
            }
            if (window.redirects() == 0 || !window.lands()) {
                continue;
            }
            Entry redirect = redirects.get(0);
            List<Entry> early = scenes.stream().filter(e -> ORDER.compare(e, redirect) < 0).toList();
            if (!early.isEmpty()) {
                problems.add("窗口「" + window.name() + "」里 79 早于 124（" + redirect.where() + "）：" + wheres(early)
                        + "——跟随 124 之前就进了场");
            }
            if (scenes.stream().noneMatch(e -> ORDER.compare(e, redirect) > 0)) {
                problems.add("窗口「" + window.name() + "」124（" + redirect.where() + "）之后没有 79：跟随之后没落地");
            }
        }
        List<Entry> stray = new ArrayList<>();
        for (Entry entry : timeline) {
            if (entry.messageId() == redirectId && windows.stream().noneMatch(w -> w.contains(entry.nanos()))) {
                stray.add(entry);
            }
        }
        if (!stray.isEmpty()) {
            problems.add("有 124 不在任何窗口里：" + wheres(stray));
        }
        return problems;
    }

    /** 时间线上 124 的条数（全部连接合计）。 */
    static long count(List<Entry> timeline, int messageId) {
        return timeline.stream().filter(e -> e.messageId() == messageId).count();
    }

    private static String wheres(List<Entry> entries) {
        List<String> parts = new ArrayList<>();
        for (Entry entry : entries) {
            parts.add(entry.where());
        }
        return String.join("、", parts);
    }
}
