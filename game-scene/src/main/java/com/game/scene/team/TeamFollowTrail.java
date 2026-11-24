package com.game.scene.team;

import com.game.scene.world.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 队长走过的累计里程折线（对齐 C++ {@code TeamFollowTrail}，{@code team_follow_formation.{h,cpp}}）。
 *
 * <p>核心性质：
 * <ul>
 *   <li>每喂入一个与头部水平距离 ≥ {@value #MIN_STEP} m 的点即追加折线段并累进 {@code odometer}；</li>
 *   <li>当队长折返经过旧轨迹（水平距离 ≤ {@value #RECYCLE_RADIUS} m 且绕行长度 ≥ {@value #RECYCLE_MIN_SKIP} m）且
 *       高于 {@code protectOdo}（不剪掉在途随从脚下的路）、直线可走时，剪除旧环路防止随从原路绕圈；</li>
 *   <li>支持按里程线性插值采样（{@link #pointAt}）、查找最近里程（{@link #nearestOdo}）、尾部拼接接近路径与裁剪旧历史。</li>
 * </ul>
 */
public final class TeamFollowTrail {

    /** 最小采样步长（米）。 */
    public static final double MIN_STEP = 0.05;
    /** 折返捷径回收判定半径（米）。 */
    public static final double RECYCLE_RADIUS = 1.5;
    /** 折返捷径最少要跳过的绕行路程（米）。 */
    public static final double RECYCLE_MIN_SKIP = 1.5;

    @FunctionalInterface
    public interface WalkableCheck {
        boolean walkable(Vec3 from, Vec3 to);

        WalkableCheck ALWAYS = (from, to) -> true;
    }

    private record Sample(Vec3 point, double odo) {
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public TeamFollowTrail(Vec3 start) {
        samples.add(new Sample(start, 0.0));
    }

    public static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    public int pointCount() {
        return samples.size();
    }

    public Vec3 head() {
        return samples.peekLast().point();
    }

    public Vec3 tail() {
        return samples.peekFirst().point();
    }

    public double headOdo() {
        return samples.peekLast().odo();
    }

    public double tailOdo() {
        return samples.peekFirst().odo();
    }

    /** 喂入队长本 tick 位置，必要时回收折返环路并追加新点。 */
    public void feed(Vec3 leader, double protectOdo, WalkableCheck walkable) {
        Sample head = samples.peekLast();
        double dist = horizontalDistance(head.point(), leader);
        if (dist < MIN_STEP) {
            return;
        }
        // 查找可回收的最早折返锚点（严格在 protectOdo 之后，避免截断正在走这段路的随从）
        List<Sample> list = new ArrayList<>(samples);
        int recycleIdx = -1;
        for (int i = 0; i < list.size() - 1; i++) {
            Sample candidate = list.get(i);
            if (candidate.odo() < protectOdo) {
                continue;
            }
            if (head.odo() - candidate.odo() < RECYCLE_MIN_SKIP) {
                break;
            }
            if (horizontalDistance(candidate.point(), leader) <= RECYCLE_RADIUS
                    && walkable.walkable(candidate.point(), leader)) {
                recycleIdx = i;
                break;
            }
        }
        if (recycleIdx >= 0) {
            // 保留 [0..recycleIdx]，丢弃旧环路并直接连向 leader
            samples.clear();
            for (int i = 0; i <= recycleIdx; i++) {
                samples.addLast(list.get(i));
            }
            Sample anchor = samples.peekLast();
            double step = horizontalDistance(anchor.point(), leader);
            if (step >= MIN_STEP) {
                samples.addLast(new Sample(leader, anchor.odo() + step));
            }
            return;
        }
        samples.addLast(new Sample(leader, head.odo() + dist));
    }

    /** 按里程线性插值采样点；越界时夹取到头/尾。 */
    public Vec3 pointAt(double odo) {
        if (odo <= tailOdo()) {
            return tail();
        }
        if (odo >= headOdo()) {
            return head();
        }
        Sample prev = null;
        for (Sample cur : samples) {
            if (prev != null && odo <= cur.odo()) {
                double span = cur.odo() - prev.odo();
                if (span <= 1e-9) {
                    return cur.point();
                }
                double t = (odo - prev.odo()) / span;
                return new Vec3(
                        prev.point().x() + (cur.point().x() - prev.point().x()) * t,
                        prev.point().y() + (cur.point().y() - prev.point().y()) * t,
                        prev.point().z() + (cur.point().z() - prev.point().z()) * t);
            }
            prev = cur;
        }
        return head();
    }

    /** 查找轨迹上离 {@code position} 水平距离不超过 {@code maxRadius} 的最近里程（优先靠近头部）。 */
    public OptionalDouble nearestOdo(Vec3 position, double maxRadius) {
        double bestDist = maxRadius;
        double bestOdo = Double.NaN;
        Sample prev = null;
        for (Sample cur : samples) {
            if (prev == null) {
                double d = horizontalDistance(cur.point(), position);
                if (d <= bestDist) {
                    bestDist = d;
                    bestOdo = cur.odo();
                }
            } else {
                double ax = prev.point().x(), ay = prev.point().y();
                double bx = cur.point().x(), by = cur.point().y();
                double dx = bx - ax, dy = by - ay;
                double lenSq = dx * dx + dy * dy;
                double t = lenSq <= 1e-12 ? 0.0 : Math.clamp(((position.x() - ax) * dx + (position.y() - ay) * dy) / lenSq, 0.0, 1.0);
                double px = ax + dx * t, py = ay + dy * t;
                double d = Math.hypot(position.x() - px, position.y() - py);
                if (d <= bestDist) {
                    bestDist = d;
                    bestOdo = prev.odo() + (cur.odo() - prev.odo()) * t;
                }
            }
            prev = cur;
        }
        return Double.isNaN(bestOdo) ? OptionalDouble.empty() : OptionalDouble.of(bestOdo);
    }

    /** 在轨迹尾部前接一段接近路径（{@code path[0]} 为随从位置，{@code path[last]} 为原队尾），里程向负方向延伸。 */
    public void prependPath(List<Vec3> path) {
        if (path == null || path.size() < 2) {
            return;
        }
        double curOdo = tailOdo();
        Vec3 prev = tail();
        for (int i = path.size() - 2; i >= 0; i--) {
            Vec3 pt = path.get(i);
            double d = horizontalDistance(prev, pt);
            if (d < MIN_STEP && i > 0) {
                continue;
            }
            curOdo -= d;
            samples.addFirst(new Sample(pt, curOdo));
            prev = pt;
        }
    }

    /** 把轨迹尾部裁切到指定里程，保证新随从接接近路径时不会绕远。 */
    public void trimTailTo(double targetOdo) {
        if (targetOdo <= tailOdo()) {
            return;
        }
        if (targetOdo >= headOdo()) {
            Sample h = samples.peekLast();
            samples.clear();
            samples.addLast(h);
            return;
        }
        Vec3 cutPoint = pointAt(targetOdo);
        while (samples.size() > 1 && samples.peekFirst().odo() <= targetOdo) {
            samples.pollFirst();
        }
        samples.addFirst(new Sample(cutPoint, targetOdo));
    }

    /** 清理不再被任何随从需要的旧尾部点（仅删除严格早于 {@code keepOdo} 且后继仍 ≤ {@code keepOdo} 的点）。 */
    public void pruneBefore(double keepOdo) {
        while (samples.size() > 2) {
            Sample first = samples.pollFirst();
            Sample second = samples.peekFirst();
            if (second.odo() > keepOdo) {
                samples.addFirst(first);
                break;
            }
        }
    }
}
