package com.game.navigation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 服务器 Z-up 米制坐标的导航边界；内部索引不可变，落位、移动和路径各自使用局部工作区。
 * 不读取文件、不持有场景/玩家，也不替调用方处理出生点兜底、速度清零或移动额度。
 */
public final class ServerNavMeshQuery {
    private static final NavMesh.Vec3 SNAP_EXTENTS = new NavMesh.Vec3(2, 4, 2);
    private static final double HIT_PULLBACK = 0.05;

    /** 与导航 Y-up 的 Vec3 分开建模，避免业务边界重复换轴。 */
    public record Position(double x, double y, double z) {
        public Position {
            if (!valid(x) || !valid(y) || !valid(z))
                throw new IllegalArgumentException("服务器坐标必须有限且绝对值不超过 1e9 米");
        }
        private static boolean valid(double value) { return Double.isFinite(value) && Math.abs(value) <= 1e9; }
    }

    public record SurfacePosition(long polygonReference, Position position) {
        public SurfacePosition { Objects.requireNonNull(position); }
    }

    public enum PlacementStatus { FOUND, NOT_FOUND, LIMIT_REACHED }

    public record Placement(PlacementStatus status, Optional<SurfacePosition> surface) {
        public Placement {
            Objects.requireNonNull(status); Objects.requireNonNull(surface);
            if ((status == PlacementStatus.FOUND) != surface.isPresent())
                throw new IllegalArgumentException("落位状态与地表结果不一致");
        }
    }

    /** 非 CLEAR 一律停止本次移动；LIMIT_REACHED 只说明预算不足，不能解释为确定撞墙。 */
    public enum MoveStatus { CLEAR, HIT_WALL, END_HEIGHT_OUT_OF_RANGE, INVALID_START, LIMIT_REACHED }

    /**
     * position 是本次能证明的安全位置。起点未找到或起点搜索耗尽时为空，调用方保留原位置并另行恢复。
     * 起点已找到但后续预算耗尽时只返回吸附后的起点，不应用未完成射线的部分结果。
     */
    public record MoveResult(MoveStatus status, Optional<SurfacePosition> position) {
        public MoveResult {
            Objects.requireNonNull(status); Objects.requireNonNull(position);
            if ((status == MoveStatus.CLEAR || status == MoveStatus.HIT_WALL
                    || status == MoveStatus.END_HEIGHT_OUT_OF_RANGE) && position.isEmpty())
                throw new IllegalArgumentException("移动裁决缺少安全位置");
            if (status == MoveStatus.INVALID_START && position.isPresent())
                throw new IllegalArgumentException("非法起点不得返回伪造的安全位置");
        }
    }

    /** 只有 COMPLETE 带完整地表路径；保留跨门双方引用/高度，不把台阶点合并。 */
    public record PathResult(NavMeshPathQuery.Status status, List<Long> corridor, List<SurfacePosition> points) {
        public PathResult {
            Objects.requireNonNull(status);
            corridor = List.copyOf(corridor); points = List.copyOf(points);
        }
    }

    private final NavMeshQuery query;
    private final NavMeshPathQuery paths;
    private final NavMeshQuery.Filter filter;

    public ServerNavMeshQuery(NavMesh mesh) {
        this(mesh, NavMeshQuery.Filter.walkable(),
                new NavMeshQuery.Limits(1_048_576, 256), NavMeshPathQuery.Limits.defaults());
    }

    public ServerNavMeshQuery(NavMesh mesh, NavMeshQuery.Filter filter,
                              NavMeshQuery.Limits queryLimits, NavMeshPathQuery.Limits pathLimits) {
        this.query = new NavMeshQuery(Objects.requireNonNull(mesh), Objects.requireNonNull(queryLimits));
        this.paths = new NavMeshPathQuery(mesh, Objects.requireNonNull(pathLimits));
        this.filter = Objects.requireNonNull(filter);
    }

    /** 服务器 (x,y,z) 转导航 (y,z,x)；Position 构造已经做数值校验。 */
    public static NavMesh.Vec3 toNavigation(Position position) {
        Objects.requireNonNull(position);
        return new NavMesh.Vec3(position.y(), position.z(), position.x());
    }

    /** 导航 (x,y,z) 转服务器 (z,x,y)。 */
    public static Position toServer(NavMesh.Vec3 position) {
        Objects.requireNonNull(position);
        return new Position(position.z(), position.x(), position.y());
    }

    /** 水平候选范围 ±2 米、垂直差严格小于 4 米；不以原点或出生点表示未找到。 */
    public Placement place(Position position) {
        var nearest = nearest(position);
        PlacementStatus status = switch (nearest.status()) {
            case FOUND -> PlacementStatus.FOUND;
            case NOT_FOUND -> PlacementStatus.NOT_FOUND;
            case LIMIT_REACHED -> PlacementStatus.LIMIT_REACHED;
        };
        return new Placement(status, nearest.surface().map(ServerNavMeshQuery::toServer));
    }

    /**
     * 先吸附起点，再验证整条水平线段。墙前按三维输入线段长度回缩 5 cm，并沿原地表重查回缩点。
     * 终点只接受射线实际到达的层，不能经另一次全局最近点跳上未连通的楼层。
     */
    public MoveResult move(Position from, Position to) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        var start = nearest(from);
        if (start.status() == NavMeshQuery.NearestStatus.LIMIT_REACHED)
            return move(MoveStatus.LIMIT_REACHED, null);
        if (start.status() != NavMeshQuery.NearestStatus.FOUND)
            return move(MoveStatus.INVALID_START, null);
        var origin = start.surface().orElseThrow();
        NavMesh.Vec3 end = toNavigation(to);
        var ray = query.raycast(origin.polygonReference(), origin.position(), end, filter);
        if (ray.status() == NavMeshQuery.RaycastStatus.LIMIT_REACHED)
            return move(MoveStatus.LIMIT_REACHED, origin);
        if (ray.status() == NavMeshQuery.RaycastStatus.INVALID_START)
            return move(MoveStatus.INVALID_START, null);
        if (ray.status() == NavMeshQuery.RaycastStatus.CLEAR) {
            var destination = ray.surface().orElseThrow();
            if (Math.abs(destination.position().y() - end.y()) < SNAP_EXTENTS.y())
                return move(MoveStatus.CLEAR, destination);
            return retreat(origin, end, 1, MoveStatus.END_HEIGHT_OUT_OF_RANGE);
        }
        return retreat(origin, end, ray.fraction(), MoveStatus.HIT_WALL);
    }

    /** 吸附起终点后委托有界地表路径查询；不可达、未找到和预算不足均没有部分点列。 */
    public PathResult findPath(Position from, Position to) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        var start = nearest(from);
        if (start.status() == NavMeshQuery.NearestStatus.LIMIT_REACHED)
            return pathFailure(NavMeshPathQuery.Status.LIMIT_REACHED);
        if (start.status() != NavMeshQuery.NearestStatus.FOUND)
            return pathFailure(NavMeshPathQuery.Status.INVALID_START);
        var end = nearest(to);
        if (end.status() == NavMeshQuery.NearestStatus.LIMIT_REACHED)
            return pathFailure(NavMeshPathQuery.Status.LIMIT_REACHED);
        if (end.status() != NavMeshQuery.NearestStatus.FOUND)
            return pathFailure(NavMeshPathQuery.Status.INVALID_END);
        var a = start.surface().orElseThrow();
        var b = end.surface().orElseThrow();
        var result = paths.findPath(a.polygonReference(), a.position(), b.polygonReference(), b.position(), filter);
        return new PathResult(result.status(), result.corridor(), result.points().stream()
                .map(ServerNavMeshQuery::toServer).toList());
    }

    private NavMeshQuery.NearestResult nearest(Position position) {
        return query.nearestPoint(toNavigation(position), SNAP_EXTENTS, filter);
    }

    private MoveResult retreat(NavMeshQuery.SurfacePoint origin, NavMesh.Vec3 end, double fraction,
                               MoveStatus reason) {
        NavMesh.Vec3 start = origin.position();
        double dx = end.x() - start.x(), dy = end.y() - start.y(), dz = end.z() - start.z();
        double length = Math.hypot(Math.hypot(dx, dy), dz);
        double t = length > HIT_PULLBACK ? Math.max(0, fraction - HIT_PULLBACK / length) : 0;
        if (t == 0) return move(reason, origin);
        var target = new NavMesh.Vec3(start.x() + dx * t, start.y() + dy * t, start.z() + dz * t);
        // 再沿同一起点验证，既取得该处的细节高度，也避免全局最近点把回缩位置吸到墙后/别层。
        var corrected = query.raycast(origin.polygonReference(), start, target, filter);
        if (corrected.status() == NavMeshQuery.RaycastStatus.LIMIT_REACHED)
            return move(MoveStatus.LIMIT_REACHED, origin);
        return move(reason, corrected.status() == NavMeshQuery.RaycastStatus.CLEAR
                ? corrected.surface().orElseThrow() : origin);
    }

    private static SurfacePosition toServer(NavMeshQuery.SurfacePoint point) {
        return new SurfacePosition(point.polygonReference(), toServer(point.position()));
    }

    private static MoveResult move(MoveStatus status, NavMeshQuery.SurfacePoint point) {
        return new MoveResult(status, Optional.ofNullable(point).map(ServerNavMeshQuery::toServer));
    }

    private static PathResult pathFailure(NavMeshPathQuery.Status status) {
        return new PathResult(status, List.of(), List.of());
    }
}
