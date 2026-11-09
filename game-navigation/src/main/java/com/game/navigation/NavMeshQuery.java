package com.game.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.game.navigation.NavMesh.*;
import static com.game.navigation.NavMeshGeometry.*;

/**
 * 纯 Java 地表查询；坐标沿用 NavMesh 的 Y-up、米，不做服务器换轴或移动裁决。
 * 索引和值对象不可变，每次调用的预算、访问集合和临时结果独立，可并发复用。
 */
public final class NavMeshQuery {
    public record Filter(int includeFlags, int excludeFlags) {
        public Filter {
            if ((includeFlags & ~0xffff) != 0 || (excludeFlags & ~0xffff) != 0)
                throw new IllegalArgumentException("导航过滤标志必须为 uint16");
        }
        public static Filter walkable() { return new Filter(0xffff, 0); }
        boolean permits(Polygon polygon) {
            return polygon.type() == PolygonType.GROUND && (polygon.flags() & includeFlags) != 0
                    && (polygon.flags() & excludeFlags) == 0;
        }
    }
    public record Limits(int maxWork, int maxVisitedPolygons) {
        public Limits {
            if (maxWork < 1 || maxWork > 16_777_216 || maxVisitedPolygons < 1 || maxVisitedPolygons > 65_536)
                throw new IllegalArgumentException("导航查询工作量或经过面数量上限非法");
        }
        public static Limits defaults() { return new Limits(1_048_576, 4_096); }
    }
    public record SurfacePoint(long polygonReference, Vec3 position) {
        public SurfacePoint { Objects.requireNonNull(position); }
    }
    public enum NearestStatus { FOUND, NOT_FOUND, LIMIT_REACHED }
    public record NearestResult(NearestStatus status, Optional<SurfacePoint> surface, double distanceSquared) {
        public NearestResult { Objects.requireNonNull(status); Objects.requireNonNull(surface); }
    }
    public enum RaycastStatus { CLEAR, HIT_WALL, INVALID_START, LIMIT_REACHED }
    public record RaycastResult(RaycastStatus status, double fraction, Optional<SurfacePoint> surface,
                                Vec3 normal, List<Long> visitedPolygons) {
        public RaycastResult {
            Objects.requireNonNull(status); Objects.requireNonNull(surface); Objects.requireNonNull(normal);
            visitedPolygons = List.copyOf(visitedPolygons);
        }
    }
    private record Boundary(int x, int z, int side) { }
    private record Portal(Surface surface, int edge) { }
    /** 真实邻边重叠；高度与过滤在每次查询中判定，不能据此直接宣布可通行。 */
    record Connection(Surface target, Vec3 start, Vec3 end, boolean external) { }
    private static final Vec3 ZERO = new Vec3(0, 0, 0);
    private final Map<Long, Surface> surfaces;
    private final List<Surface> orderedSurfaces;
    private final Map<Boundary, List<Portal>> portals;
    private final double climb;
    private final Limits limits;

    public NavMeshQuery(NavMesh mesh) { this(mesh, Limits.defaults()); }
    public NavMeshQuery(NavMesh mesh, Limits limits) {
        Objects.requireNonNull(mesh);
        this.limits = Objects.requireNonNull(limits);
        this.climb = mesh.parameters().walkableClimb();
        var byReference = new HashMap<Long, Surface>();
        var byBoundary = new HashMap<Boundary, List<Portal>>();
        for (var tile : mesh.tiles()) {
            for (int index = 0; index < tile.polygons().size(); index++) {
                if (tile.polygons().get(index).type() != PolygonType.GROUND) continue;
                Surface surface = surface(new PolygonLocation(tile, index));
                byReference.put(surface.reference(), surface);
                for (int edge = 0; edge < surface.polygon().edges().size(); edge++) {
                    var adjacency = surface.polygon().edges().get(edge);
                    if (adjacency.kind() == EdgeKind.PORTAL) {
                        var key = new Boundary(tile.key().x(), tile.key().z(), adjacency.portalSide());
                        byBoundary.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Portal(surface, edge));
                    }
                }
            }
        }
        surfaces = Map.copyOf(byReference);
        orderedSurfaces = byReference.values().stream()
                .sorted((a, b) -> Long.compareUnsigned(a.reference(), b.reference())).toList();
        var immutablePortals = new HashMap<Boundary, List<Portal>>();
        byBoundary.forEach((key, values) -> immutablePortals.put(key, values.stream()
                .sorted((a, b) -> Long.compareUnsigned(a.surface().reference(), b.surface().reference())).toList()));
        portals = Map.copyOf(immutablePortals);
    }

    /**
     * 先以真实几何 AABB 选候选面，再比较水平夹持/细节高度后的三维距离。
     * 垂直差必须严格小于 halfExtents.y；等距按无符号引用稳定选择，不使用原生 128 项截断。
     */
    public NearestResult nearestPoint(Vec3 point, Vec3 halfExtents, Filter filter) {
        checkPoint(point); checkPoint(halfExtents); Objects.requireNonNull(filter);
        if (halfExtents.x() < 0 || halfExtents.y() <= 0 || halfExtents.z() < 0)
            throw new IllegalArgumentException("搜索半径的水平值非负，垂直值必须为正");
        var budget = new Work(limits.maxWork());
        SurfacePoint nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        try {
            for (var surface : orderedSurfaces) {
                budget.charge();
                if (!filter.permits(surface.polygon()) || !overlaps(surface.bounds(), point, halfExtents)) continue;
                var candidate = closest(surface, point, budget);
                if (candidate.isEmpty() || Math.abs(candidate.get().y() - point.y()) >= halfExtents.y()) continue;
                double squared = distanceSquared(point, candidate.get());
                if (squared < distance) {
                    nearest = new SurfacePoint(surface.reference(), candidate.get()); distance = squared;
                }
            }
        } catch (LimitReached exhausted) {
            return new NearestResult(NearestStatus.LIMIT_REACHED, Optional.empty(), Double.POSITIVE_INFINITY);
        }
        return new NearestResult(nearest == null ? NearestStatus.NOT_FOUND : NearestStatus.FOUND,
                Optional.ofNullable(nearest), distance);
    }

    /**
     * 仅沿可行走地表测试水平线段，端点 Y 不决定射线是否穿墙；输出 Y 来自细节面。
     * 起点引用必须为可走 ground 且 X/Z 位于该面；不自动吸附起点或跳跃 off-mesh。
     * 门户须有反向邻边、真实开区间重叠，且实际穿越点的高度差不超过 climb。
     */
    public RaycastResult raycast(long startReference, Vec3 start, Vec3 end, Filter filter) {
        checkPoint(start); checkPoint(end); Objects.requireNonNull(filter);
        Surface current = surfaces.get(startReference);
        if (current == null || !filter.permits(current.polygon()) || !contains(current, start))
            return ray(RaycastStatus.INVALID_START, 0, null, ZERO, List.of());
        var budget = new Work(limits.maxWork());
        var visited = new ArrayList<Long>();
        var seen = new HashSet<Long>();
        SurfacePoint last = null;
        double progress = 0;
        try {
            var startPoint = onSurface(current, start, budget);
            if (startPoint.isEmpty()) return ray(RaycastStatus.INVALID_START, 0, null, ZERO, List.of());
            last = new SurfacePoint(current.reference(), startPoint.get());
            while (true) {
                budget.charge();
                if (visited.size() == limits.maxVisitedPolygons() || !seen.add(current.reference()))
                    return ray(RaycastStatus.LIMIT_REACHED, progress, last, ZERO, visited);
                visited.add(current.reference());
                if (contains(current, end)) {
                    var endPoint = onSurface(current, end, budget);
                    if (endPoint.isPresent()) return ray(RaycastStatus.CLEAR, 1,
                            new SurfacePoint(current.reference(), endPoint.get()), ZERO, visited);
                }
                var interval = clip(current, start, end);
                if (interval.isEmpty() || interval.get().exit() < progress - 1e-12 || interval.get().exitEdges().isEmpty())
                    return ray(RaycastStatus.HIT_WALL, progress, last, ZERO, visited);
                var crossing = interpolate(start, end, interval.get().exit());
                var hit = onSurface(current, crossing, budget);
                if (hit.isEmpty()) return ray(RaycastStatus.HIT_WALL, progress, last, ZERO, visited);
                progress = interval.get().exit();
                last = new SurfacePoint(current.reference(), hit.get());
                Surface next = null;
                for (int edge : interval.get().exitEdges()) {
                    var candidate = neighbour(current, edge, start, end, progress, hit.get(), filter, seen, budget);
                    if (candidate != null && (next == null || Long.compareUnsigned(candidate.reference(), next.reference()) < 0)) next = candidate;
                }
                if (next == null) return ray(RaycastStatus.HIT_WALL, progress, last,
                        normal(current, interval.get().exitEdges().getFirst()), visited);
                current = next;
            }
        } catch (LimitReached exhausted) {
            return ray(RaycastStatus.LIMIT_REACHED, progress, last, ZERO, visited);
        }
    }

    private Surface neighbour(Surface from, int edge, Vec3 start, Vec3 end, double progress,
                              Vec3 crossing, Filter filter, HashSet<Long> seen, Work budget) {
        for (var connection : connections(from, edge, budget)) {
            if (connection.external()) {
                Vec3 a = connection.start(), b = connection.end();
                int along = Math.abs(a.x() - b.x()) > Math.abs(a.z() - b.z()) ? 0 : 2;
                double lower = Math.min(a.axis(along), b.axis(along));
                double upper = Math.max(a.axis(along), b.axis(along));
                if (crossing.axis(along) <= lower + EPSILON || crossing.axis(along) >= upper - EPSILON) continue;
            }
            if (continues(connection.target(), start, end, progress, crossing, filter, seen, budget)) return connection.target();
        }
        return null;
    }

    Surface indexedSurface(long reference) { return surfaces.get(reference); }
    double walkableClimb() { return climb; }

    /** 最近点、射线和路径共用同一不可变索引及邻接发现，不使用存盘 runtime links。 */
    List<Connection> connections(Surface from, int edge, Work budget) {
        budget.charge();
        var adjacency = from.polygon().edges().get(edge);
        if (adjacency.kind() == EdgeKind.WALL) return List.of();
        if (adjacency.kind() == EdgeKind.INTERNAL) {
            long reference = from.location().tile().polygonReference(adjacency.polygonIndex());
            Surface candidate = surfaces.get(reference);
            return candidate == null ? List.of() : List.of(new Connection(candidate, from.edgeStart(edge), from.edgeEnd(edge), false));
        }
        int side = adjacency.portalSide();
        var tile = from.location().tile().key();
        long x = (long) tile.x() + (side == 0 ? 1 : side == 4 ? -1 : 0);
        long z = (long) tile.z() + (side == 2 ? 1 : side == 6 ? -1 : 0);
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return List.of();
        var candidates = portals.getOrDefault(new Boundary((int) x, (int) z, (side + 4) % 8), List.of());
        Vec3 a = from.edgeStart(edge), b = from.edgeEnd(edge);
        int along = side == 0 || side == 4 ? 2 : 0;
        int across = along == 2 ? 0 : 2;
        var result = new ArrayList<Connection>();
        for (var portal : candidates) {
            budget.charge();
            Vec3 c = portal.surface().edgeStart(portal.edge()), d = portal.surface().edgeEnd(portal.edge());
            if (Math.abs(a.axis(across) - c.axis(across)) > EPSILON
                    || Math.abs(b.axis(across) - d.axis(across)) > EPSILON) continue;
            double lower = Math.max(Math.min(a.axis(along), b.axis(along)), Math.min(c.axis(along), d.axis(along)));
            double upper = Math.min(Math.max(a.axis(along), b.axis(along)), Math.max(c.axis(along), d.axis(along)));
            if (upper - lower <= 2 * EPSILON) continue;
            double delta = b.axis(along) - a.axis(along);
            result.add(new Connection(portal.surface(), interpolate(a, b, (lower - a.axis(along)) / delta),
                    interpolate(a, b, (upper - a.axis(along)) / delta), true));
        }
        return List.copyOf(result);
    }

    private boolean continues(Surface candidate, Vec3 start, Vec3 end, double progress, Vec3 crossing,
                              Filter filter, HashSet<Long> seen, Work budget) {
        budget.charge();
        if (candidate == null || seen.contains(candidate.reference()) || !filter.permits(candidate.polygon())) return false;
        var interval = clip(candidate, start, end);
        if (interval.isEmpty() || interval.get().entry() > progress + 1e-12 || interval.get().exit() <= progress + 1e-12) return false;
        var height = onSurface(candidate, crossing, budget);
        return height.isPresent() && Math.abs(height.get().y() - crossing.y()) <= climb + EPSILON;
    }

    private static RaycastResult ray(RaycastStatus status, double fraction, SurfacePoint surface, Vec3 normal, List<Long> visited) {
        return new RaycastResult(status, fraction, Optional.ofNullable(surface), normal, visited);
    }
}
