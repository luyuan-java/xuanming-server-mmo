package com.game.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.TreeSet;

import static com.game.navigation.NavMesh.*;
import static com.game.navigation.NavMeshGeometry.*;
import static com.game.navigation.NavMeshQuery.*;

/**
 * ground 走廊 A* 与走廊内可见性拉直。坐标为 Y-up 米；不换轴、不接场景、不跳跃 off-mesh。
 * 共享索引不可变，队列、父节点、门户高度区间和预算均归单次调用所有。
 */
public final class NavMeshPathQuery {
    public record Limits(int maxWork, int maxSearchPolygons, int maxPathPoints) {
        public Limits {
            if (maxWork < 1 || maxWork > 16_777_216 || maxSearchPolygons < 1 || maxSearchPolygons > 65_536
                    || maxPathPoints < 1 || maxPathPoints > 262_144)
                throw new IllegalArgumentException("路径工作量、搜索面或输出点上限非法");
        }
        public static Limits defaults() { return new Limits(1_048_576, 4_096, 16_384); }
    }
    public enum Status { COMPLETE, UNREACHABLE, INVALID_START, INVALID_END, LIMIT_REACHED }
    public record Result(Status status, List<Long> corridor, List<SurfacePoint> points) {
        public Result {
            Objects.requireNonNull(status);
            corridor = List.copyOf(corridor); points = List.copyOf(points);
        }
    }
    private record Passage(Surface from, Surface to, Vec3 start, Vec3 end) {
        Vec3 midpoint() { return interpolate(start, end, 0.5); }
    }
    private record Node(long reference, double cost, double estimate, Node parent, Passage entrance) { }
    private record Interval(double lower, double upper) { }
    private final NavMeshQuery geometry;
    private final Limits limits;

    public NavMeshPathQuery(NavMesh mesh) { this(mesh, Limits.defaults()); }
    public NavMeshPathQuery(NavMesh mesh, Limits limits) {
        geometry = new NavMeshQuery(Objects.requireNonNull(mesh));
        this.limits = Objects.requireNonNull(limits);
    }

    /**
     * 引用明确指定起终层；XZ 必须在各自面内，Y 从细节面取得，不自动吸附。
     * 只有完整搜索和拉直均成功才返回 COMPLETE；失败不泄露可误当完整路径的部分点列。
     */
    public Result findPath(long startReference, Vec3 start, long endReference, Vec3 end, Filter filter) {
        checkPoint(start); checkPoint(end); Objects.requireNonNull(filter);
        Surface first = geometry.indexedSurface(startReference), last = geometry.indexedSurface(endReference);
        if (first == null || !filter.permits(first.polygon()) || !contains(first, start)) return failure(Status.INVALID_START);
        if (last == null || !filter.permits(last.polygon()) || !contains(last, end)) return failure(Status.INVALID_END);
        var work = new Work(limits.maxWork());
        try {
            var projectedStart = onSurface(first, start, work);
            if (projectedStart.isEmpty()) return failure(Status.INVALID_START);
            var projectedEnd = onSurface(last, end, work);
            if (projectedEnd.isEmpty()) return failure(Status.INVALID_END);
            var search = new Search(projectedStart.get(), projectedEnd.get(), first, last, filter, work);
            Node goal = search.run();
            if (goal == null) return failure(Status.UNREACHABLE);
            var corridor = new ArrayList<Long>();
            var passages = new ArrayList<Passage>();
            for (Node node = goal; node != null; node = node.parent()) {
                work.charge(); corridor.add(node.reference());
                if (node.entrance() != null) passages.add(node.entrance());
            }
            Collections.reverse(corridor); Collections.reverse(passages);
            var points = straighten(first, projectedStart.get(), projectedEnd.get(), passages, work);
            return points.isEmpty() ? failure(Status.UNREACHABLE) : new Result(Status.COMPLETE, corridor, points.get());
        } catch (LimitReached exhausted) {
            return failure(Status.LIMIT_REACHED);
        }
    }

    /** A* 在固定面锚点图上求解；权重是经合法门户中点的三维折线长度，不承诺连续空间最短路。 */
    private final class Search {
        private final Surface first, last;
        private final Filter filter;
        private final Work work;
        private final Map<Long, Vec3> anchors = new HashMap<>();
        private final Map<Long, Node> best = new HashMap<>();
        private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
            int byEstimate = Double.compare(a.estimate(), b.estimate());
            if (byEstimate != 0) return byEstimate;
            int byCost = Double.compare(a.cost(), b.cost());
            return byCost != 0 ? byCost : Long.compareUnsigned(a.reference(), b.reference());
        });

        Search(Vec3 start, Vec3 end, Surface first, Surface last, Filter filter, Work work) {
            this.first = first; this.last = last; this.filter = filter; this.work = work;
            anchors.put(last.reference(), end); anchors.put(first.reference(), start);
        }
        Node run() {
            Node initial = new Node(first.reference(), 0, distance(anchor(first), anchor(last)), null, null);
            best.put(first.reference(), initial); open.add(initial);
            while (!open.isEmpty()) {
                work.charge();
                Node current = open.remove();
                if (best.get(current.reference()) != current) continue;
                if (current.reference() == last.reference()) return current;
                Surface from = geometry.indexedSurface(current.reference());
                for (Passage passage : passages(from, filter, work)) {
                    work.charge();
                    Vec3 crossing = passage.midpoint();
                    var departure = onSurface(from, crossing, work);
                    var arrival = onSurface(passage.to(), crossing, work);
                    if (departure.isEmpty() || arrival.isEmpty()) continue;
                    double cost = current.cost() + distance(anchor(from), departure.get())
                            + distance(departure.get(), arrival.get()) + distance(arrival.get(), anchor(passage.to()));
                    Node previous = best.get(passage.to().reference());
                    if (previous != null && cost >= previous.cost()) continue;
                    if (previous == null && best.size() == limits.maxSearchPolygons()) throw new LimitReached();
                    Node next = new Node(passage.to().reference(), cost,
                            cost + distance(anchor(passage.to()), anchor(last)), current, passage);
                    best.put(next.reference(), next); open.add(next);
                }
            }
            return null;
        }
        private Vec3 anchor(Surface surface) {
            Vec3 existing = anchors.get(surface.reference());
            if (existing != null) return existing;
            work.charge();
            double x = 0, z = 0;
            for (Vec3 vertex : surface.vertices()) { x += vertex.x(); z += vertex.z(); }
            Vec3 point = onSurface(surface, new Vec3(x / surface.vertices().size(), 0, z / surface.vertices().size()), work)
                    .orElseThrow(() -> new IllegalArgumentException("凸面中心缺少细节覆盖"));
            anchors.put(surface.reference(), point);
            return point;
        }
    }

    /** 按两侧细节三角形切分门户，再解线性高度差约束，不能只凭边中点猜整条门可走。 */
    private List<Passage> passages(Surface from, Filter filter, Work work) {
        var result = new ArrayList<Passage>();
        for (int edge = 0; edge < from.vertices().size(); edge++) {
            for (Connection connection : geometry.connections(from, edge, work)) {
                if (!filter.permits(connection.target().polygon())) continue;
                Vec3 a = connection.start(), b = connection.end();
                double length = Math.hypot(b.x() - a.x(), b.z() - a.z());
                // 两类邻边都向内缩，禁止仅触角点的路线；不是角色半径或额外碰撞余量。
                double inset = 2 * EPSILON / length;
                if (inset >= 0.5) continue;
                var cuts = cuts(from, a, b, work);
                cuts.addAll(cuts(connection.target(), a, b, work));
                var ordered = new ArrayList<>(cuts);
                for (int i = 1; i < ordered.size(); i++) {
                    work.charge();
                    double lower = Math.max(inset, ordered.get(i - 1)), upper = Math.min(1 - inset, ordered.get(i));
                    if (lower >= upper) continue;
                    Vec3 p = interpolate(a, b, lower), q = interpolate(a, b, upper);
                    var pFrom = onSurface(from, p, work); var qFrom = onSurface(from, q, work);
                    var pTo = onSurface(connection.target(), p, work); var qTo = onSurface(connection.target(), q, work);
                    if (pFrom.isEmpty() || qFrom.isEmpty() || pTo.isEmpty() || qTo.isEmpty()) continue;
                    double d0 = pTo.get().y() - pFrom.get().y(), d1 = qTo.get().y() - qFrom.get().y();
                    var allowed = heightInterval(d0, d1, geometry.walkableClimb());
                    if (allowed.isEmpty()) continue;
                    Vec3 begin = interpolate(p, q, allowed.get().lower()), finish = interpolate(p, q, allowed.get().upper());
                    if (Math.hypot(finish.x() - begin.x(), finish.z() - begin.z()) <= 2 * EPSILON) continue;
                    result.add(new Passage(from, connection.target(), begin, finish));
                }
            }
        }
        result.sort((a, b) -> {
            int target = Long.compareUnsigned(a.to().reference(), b.to().reference());
            if (target != 0) return target;
            int x = Double.compare(a.start().x(), b.start().x());
            return x != 0 ? x : Double.compare(a.start().z(), b.start().z());
        });
        return List.copyOf(result);
    }

    private static Optional<Interval> heightInterval(double d0, double d1, double climb) {
        double delta = d1 - d0;
        if (delta == 0) return Math.abs(d0) <= climb + EPSILON ? Optional.of(new Interval(0, 1)) : Optional.empty();
        double a = (-climb - d0) / delta, b = (climb - d0) / delta;
        double lower = Math.max(0, Math.min(a, b)), upper = Math.min(1, Math.max(a, b));
        return lower < upper ? Optional.of(new Interval(lower, upper)) : Optional.empty();
    }

    /** 逐次取最远可见的后续门户中点或终点；每段只能按已选走廊顺序跨门。 */
    private Optional<List<SurfacePoint>> straighten(Surface first, Vec3 start, Vec3 end, List<Passage> passages, Work work) {
        var result = new ArrayList<SurfacePoint>();
        Surface current = first;
        Vec3 position = start;
        int index = 0;
        append(result, new SurfacePoint(current.reference(), position));
        while (true) {
            work.charge();
            var direct = trace(current, position, end, passages, index, passages.size(), work);
            if (direct.isPresent()) {
                for (SurfacePoint point : direct.get()) append(result, point);
                return Optional.of(List.copyOf(result));
            }
            boolean advanced = false;
            for (int target = passages.size() - 1; target >= index; target--) {
                work.charge();
                Passage gateway = passages.get(target);
                var partial = trace(current, position, gateway.midpoint(), passages, index, target, work);
                if (partial.isEmpty()) continue;
                var arrival = onSurface(gateway.to(), gateway.midpoint(), work);
                var departure = onSurface(gateway.from(), gateway.midpoint(), work);
                if (arrival.isEmpty() || departure.isEmpty()
                        || Math.abs(arrival.get().y() - departure.get().y()) > geometry.walkableClimb() + EPSILON) continue;
                for (SurfacePoint point : partial.get()) append(result, point);
                current = gateway.to(); position = arrival.get(); index = target + 1;
                append(result, new SurfacePoint(current.reference(), position));
                advanced = true; break;
            }
            if (!advanced) return Optional.empty();
        }
    }

    private Optional<List<SurfacePoint>> trace(Surface first, Vec3 start, Vec3 end, List<Passage> passages,
                                               int from, int to, Work work) {
        var result = new ArrayList<SurfacePoint>();
        Surface current = first;
        double progress = 0;
        for (int i = from; i < to; i++) {
            work.charge();
            Passage passage = passages.get(i);
            if (passage.from().reference() != current.reference()) return Optional.empty();
            var crossing = intersection(start, end, passage.start(), passage.end());
            if (crossing.isEmpty() || crossing.get() < progress || crossing.get() > 1) return Optional.empty();
            Vec3 point = interpolate(start, end, crossing.get());
            if (!groundSegment(current, interpolate(start, end, progress), point, result, work)) return Optional.empty();
            var departure = onSurface(current, point, work); var arrival = onSurface(passage.to(), point, work);
            if (departure.isEmpty() || arrival.isEmpty()
                    || Math.abs(departure.get().y() - arrival.get().y()) > geometry.walkableClimb() + EPSILON) return Optional.empty();
            current = passage.to(); progress = crossing.get();
            append(result, new SurfacePoint(current.reference(), arrival.get()));
        }
        return groundSegment(current, interpolate(start, end, progress), end, result, work)
                ? Optional.of(result) : Optional.empty();
    }

    /** 按细节三角形边界补高度折点；不能把起终高度直接连线而穿过地形。 */
    private boolean groundSegment(Surface surface, Vec3 start, Vec3 end, List<SurfacePoint> output, Work work) {
        work.charge();
        if (!contains(surface, start) || !contains(surface, end)) return false;
        var parameters = new ArrayList<>(cuts(surface, start, end, work));
        for (int i = 0; i < parameters.size(); i++) {
            Vec3 position = interpolate(start, end, parameters.get(i));
            var height = onSurface(surface, position, work);
            if (height.isEmpty()) return false;
            if (i > 0) {
                double middle = (parameters.get(i - 1) + parameters.get(i)) / 2;
                if (onSurface(surface, interpolate(start, end, middle), work).isEmpty()) return false;
            }
            append(output, new SurfacePoint(surface.reference(), height.get()));
        }
        return true;
    }

    /** 直线参数在每个细节三角形内的区间端点；并列端点按 double 有序去重。 */
    private static TreeSet<Double> cuts(Surface surface, Vec3 start, Vec3 end, Work work) {
        var result = new TreeSet<Double>(); result.add(0.0); result.add(1.0);
        for (Triangle triangle : surface.triangles()) {
            work.charge();
            double area = cross(triangle.a(), triangle.b(), triangle.c());
            double[] atStart = weights(triangle, start, area), atEnd = weights(triangle, end, area);
            double lower = 0, upper = 1;
            for (int i = 0; i < 3; i++) {
                double delta = atEnd[i] - atStart[i];
                if (delta == 0) { if (atStart[i] < -1e-12) { lower = 1; upper = 0; break; } }
                else if (delta > 0) lower = Math.max(lower, -atStart[i] / delta);
                else upper = Math.min(upper, -atStart[i] / delta);
            }
            if (lower <= upper && lower <= 1 && upper >= 0) {
                result.add(clamp(lower)); result.add(clamp(upper));
            }
        }
        return result;
    }
    private static double[] weights(Triangle triangle, Vec3 point, double area) {
        double a = cross(point, triangle.b(), triangle.c()) / area;
        double b = cross(triangle.a(), point, triangle.c()) / area;
        return new double[]{a, b, 1 - a - b};
    }
    private static Optional<Double> intersection(Vec3 start, Vec3 end, Vec3 a, Vec3 b) {
        double dx = end.x() - start.x(), dz = end.z() - start.z();
        double px = b.x() - a.x(), pz = b.z() - a.z();
        double denominator = dx * pz - dz * px;
        if (denominator == 0) return Optional.empty();
        double x = a.x() - start.x(), z = a.z() - start.z();
        double t = (x * pz - z * px) / denominator;
        double u = (x * dz - z * dx) / denominator;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? Optional.of(t) : Optional.empty();
    }
    private void append(List<SurfacePoint> output, SurfacePoint point) {
        if (!output.isEmpty() && output.getLast().equals(point)) return;
        if (output.size() == limits.maxPathPoints()) throw new LimitReached();
        output.add(point);
    }
    private static double cross(Vec3 a, Vec3 b, Vec3 c) {
        return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
    }
    private static double distance(Vec3 a, Vec3 b) { return Math.sqrt(distanceSquared(a, b)); }
    private static Result failure(Status status) { return new Result(status, List.of(), List.of()); }
}
