package com.game.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.game.navigation.NavMesh.*;

/** 以不可变凸面和细节三角形组织查询几何，不导入原生 link 缓存或查询节点池。 */
final class NavMeshGeometry {
    private NavMeshGeometry() { }
    static final double EPSILON = 1e-8;

    record Triangle(Vec3 a, Vec3 b, Vec3 c) { }
    record Surface(PolygonLocation location, List<Vec3> vertices, List<Triangle> triangles,
                   Bounds bounds, double orientation) {
        Surface { vertices = List.copyOf(vertices); triangles = List.copyOf(triangles); }
        long reference() { return location.reference(); }
        Polygon polygon() { return location.polygon(); }
        Vec3 edgeStart(int edge) { return vertices.get(edge); }
        Vec3 edgeEnd(int edge) { return vertices.get((edge + 1) % vertices.size()); }
    }
    record Clip(double entry, double exit, List<Integer> exitEdges) {
        Clip { exitEdges = List.copyOf(exitEdges); }
    }

    static Surface surface(PolygonLocation location) {
        var tile = location.tile();
        var vertices = location.polygon().vertexIndices().stream().map(tile.vertices()::get).toList();
        var detail = tile.detailMeshes().get(location.polygonIndex());
        var triangles = new ArrayList<Triangle>();
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (var vertex : vertices) checkPoint(vertex);
        for (int i = 0; i < detail.triangleCount(); i++) {
            var indices = tile.detailTriangles().get(detail.triangleBase() + i);
            var triangle = new Triangle(tile.detailVertex(location.polygonIndex(), indices.a()),
                    tile.detailVertex(location.polygonIndex(), indices.b()), tile.detailVertex(location.polygonIndex(), indices.c()));
            triangles.add(triangle);
            for (var vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                checkPoint(vertex);
                minX = Math.min(minX, vertex.x()); minY = Math.min(minY, vertex.y()); minZ = Math.min(minZ, vertex.z());
                maxX = Math.max(maxX, vertex.x()); maxY = Math.max(maxY, vertex.y()); maxZ = Math.max(maxZ, vertex.z());
            }
        }
        double direction = 0;
        for (int i = 1; i + 1 < vertices.size() && direction == 0; i++)
            direction = Math.signum(cross(vertices.getFirst(), vertices.get(i), vertices.get(i + 1)));
        if (triangles.isEmpty() || direction == 0) throw new IllegalArgumentException("缺少可查询的地面几何");
        return new Surface(location, vertices, triangles,
                new Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ)), direction);
    }

    static boolean overlaps(Bounds bounds, Vec3 center, Vec3 extents) {
        for (int axis = 0; axis < 3; axis++) {
            if (bounds.minimum().axis(axis) > center.axis(axis) + extents.axis(axis)
                    || bounds.maximum().axis(axis) < center.axis(axis) - extents.axis(axis)) return false;
        }
        return true;
    }

    static boolean contains(Surface surface, Vec3 point) {
        for (int edge = 0; edge < surface.vertices().size(); edge++) {
            Vec3 a = surface.edgeStart(edge), b = surface.edgeEnd(edge);
            if (surface.orientation() * cross(a, b, point) < -EPSILON * horizontalLength(a, b)) return false;
        }
        return true;
    }

    /** 水平投影在凸面内则保留 X/Z，在面外则夹到最近水平边；Y 随后由细节三角形求出。 */
    static Optional<Vec3> closest(Surface surface, Vec3 point, Work budget) {
        Vec3 projected = point;
        if (!contains(surface, point)) {
            double best = Double.POSITIVE_INFINITY;
            for (int edge = 0; edge < surface.vertices().size(); edge++) {
                Vec3 a = surface.edgeStart(edge), b = surface.edgeEnd(edge);
                double dx = b.x() - a.x(), dz = b.z() - a.z();
                double fraction = clamp(((point.x() - a.x()) * dx + (point.z() - a.z()) * dz) / (dx * dx + dz * dz));
                Vec3 candidate = new Vec3(a.x() + fraction * dx, point.y(), a.z() + fraction * dz);
                double distance = horizontalDistanceSquared(point, candidate);
                if (distance < best) { best = distance; projected = candidate; }
            }
        }
        return onSurface(surface, projected, budget);
    }

    static Optional<Vec3> onSurface(Surface surface, Vec3 point, Work budget) {
        for (var triangle : surface.triangles()) {
            budget.charge();
            double denominator = cross(triangle.a(), triangle.b(), triangle.c());
            double a = cross(point, triangle.b(), triangle.c()) / denominator;
            double b = cross(triangle.a(), point, triangle.c()) / denominator;
            double c = 1 - a - b;
            // 只容忍数值舍入；不能把跨墙点或缺少覆盖的细节面当作已有高度。
            if (a < -1e-10 || b < -1e-10 || c < -1e-10) continue;
            a = Math.max(0, a); b = Math.max(0, b); c = Math.max(0, c);
            // 用相对高度插值，常量平面保持原值；绝对高度的三项相加会把 3 舍入为 3.0000000000000004。
            double y = triangle.a().y() + ((triangle.b().y() - triangle.a().y()) * b
                    + (triangle.c().y() - triangle.a().y()) * c) / (a + b + c);
            if (!Double.isFinite(y)) continue;
            return Optional.of(new Vec3(point.x(), y, point.z()));
        }
        return Optional.empty();
    }

    /** 用凸半平面裁剪整条水平线段；返回在当前面的参数区间与所有并列离开边。 */
    static Optional<Clip> clip(Surface surface, Vec3 start, Vec3 end) {
        double entry = 0, exit = 1;
        var edges = new ArrayList<Integer>();
        for (int edge = 0; edge < surface.vertices().size(); edge++) {
            Vec3 a = surface.edgeStart(edge), b = surface.edgeEnd(edge);
            double d0 = surface.orientation() * cross(a, b, start);
            double d1 = surface.orientation() * cross(a, b, end);
            double delta = d1 - d0;
            if (delta == 0) {
                if (d0 < -EPSILON * horizontalLength(a, b)) return Optional.empty();
                continue;
            }
            double crossing = -d0 / delta;
            if (delta > 0) entry = Math.max(entry, crossing);
            else if (crossing < exit) {
                exit = crossing;
                edges.clear(); edges.add(edge);
            } else if (crossing == exit) edges.add(edge);
            if (entry > exit + 1e-12) return Optional.empty();
        }
        if (exit < 0 || entry > 1) return Optional.empty();
        return Optional.of(new Clip(Math.max(0, entry), clamp(exit), edges));
    }

    static Vec3 normal(Surface surface, int edge) {
        Vec3 a = surface.edgeStart(edge), b = surface.edgeEnd(edge);
        double length = horizontalLength(a, b);
        double x = surface.orientation() * (b.z() - a.z()) / length;
        double z = -surface.orientation() * (b.x() - a.x()) / length;
        return new Vec3(x == 0 ? 0 : x, 0, z == 0 ? 0 : z);
    }
    static Vec3 interpolate(Vec3 start, Vec3 end, double fraction) {
        return new Vec3(start.x() + (end.x() - start.x()) * fraction,
                start.y() + (end.y() - start.y()) * fraction, start.z() + (end.z() - start.z()) * fraction);
    }
    static double distanceSquared(Vec3 a, Vec3 b) {
        double dy = a.y() - b.y();
        return horizontalDistanceSquared(a, b) + dy * dy;
    }
    private static double horizontalDistanceSquared(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }
    private static double horizontalLength(Vec3 a, Vec3 b) { return Math.hypot(a.x() - b.x(), a.z() - b.z()); }
    private static double cross(Vec3 a, Vec3 b, Vec3 c) {
        return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
    }
    static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    static void checkPoint(Vec3 point) {
        if (point == null) throw new IllegalArgumentException("导航点不能为空");
        for (int axis = 0; axis < 3; axis++)
            if (!Double.isFinite(point.axis(axis)) || Math.abs(point.axis(axis)) > 1e9)
                throw new IllegalArgumentException("导航坐标必须有限且不超过固定读取器默认世界范围");
    }
    static final class Work {
        private int remaining;
        Work(int remaining) { this.remaining = remaining; }
        void charge() { if (remaining-- == 0) throw new LimitReached(); }
    }
    static final class LimitReached extends RuntimeException {
        LimitReached() { super("导航查询达到工作上限", null, false, false); }
    }
}
