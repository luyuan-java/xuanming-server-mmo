package com.game.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

import static com.game.navigation.NavMesh.*;
import static com.game.navigation.NavMeshPathQuery.*;
import static com.game.navigation.NavMeshQuery.Filter;
import static org.junit.jupiter.api.Assertions.*;

/** 地表路径的几何、失败与工作区合同；不依赖原生查询结果或运行时 link 字节。 */
class NavMeshPathQueryTest {
    private static final Edge WALL = new Edge(EdgeKind.WALL, -1, -1);
    private static final Filter WALKABLE = Filter.walkable();

    @Test void samePolygonProjectsHeightAndReturnsImmutableValues() {
        Tile tile = square(0, 0, 0, 0, 10, 0, 10, 1);
        var result = path(mesh(tile), tile, new Vec3(1, 300, 2), tile, new Vec3(9, -300, 8));
        assertEquals(Status.COMPLETE, result.status());
        assertEquals(List.of(tile.reference()), result.corridor());
        assertEquals(new Vec3(1, 0, 2), result.points().getFirst().position());
        assertEquals(new Vec3(9, 0, 8), result.points().getLast().position());
        assertThrows(UnsupportedOperationException.class, () -> result.corridor().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.points().clear());
    }

    @Test void zeroHorizontalDistanceProducesOneProjectedPoint() {
        Tile tile = square(0, 0, 0, 0, 10, 0, 10, 1);
        var result = path(mesh(tile), tile, new Vec3(5, 9, 5), tile, new Vec3(5, -9, 5));
        assertEquals(Status.COMPLETE, result.status());
        assertEquals(List.of(new NavMeshQuery.SurfacePoint(tile.reference(), new Vec3(5, 0, 5))), result.points());
    }

    @Test void internalAdjacencyProducesGroundPointsOnBothSides() {
        Tile tile = splitSquare();
        var result = new NavMeshPathQuery(mesh(tile)).findPath(tile.reference(), new Vec3(1, 0, 5),
                tile.polygonReference(1), new Vec3(9, 0, 5), WALKABLE);
        assertEquals(Status.COMPLETE, result.status());
        assertEquals(List.of(tile.reference(), tile.polygonReference(1)), result.corridor());
        assertGroundSegments(mesh(tile), result);
        assertTrue(result.points().stream().anyMatch(p -> p.polygonReference() == tile.polygonReference(1) && p.position().x() == 5));
    }

    @Test void bentCorridorCannotBeReplacedByWallCrossingShortcut() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile bend = square(1, 1, 0, 10, 20, 0, 10, 1, 3, 2);
        Tile last = square(2, 1, 1, 10, 20, 10, 20, 1, 0);
        NavMesh mesh = mesh(first, bend, last);
        Vec3 start = new Vec3(1, 0, 1), end = new Vec3(11, 0, 19);
        assertEquals(NavMeshQuery.RaycastStatus.HIT_WALL, new NavMeshQuery(mesh).raycast(first.reference(), start, end, WALKABLE).status());
        var result = path(mesh, first, start, last, end);
        assertEquals(Status.COMPLETE, result.status());
        assertEquals(List.of(first.reference(), bend.reference(), last.reference()), result.corridor());
        assertGroundSegments(mesh, result);
        assertEquals(start, result.points().getFirst().position());
        assertEquals(end, result.points().getLast().position());
    }

    @Test void partialPortalUsesOnlyItsRealOverlap() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile last = square(1, 1, 0, 10, 20, 4, 6, 1, 3);
        NavMesh mesh = mesh(first, last);
        var result = path(mesh, first, new Vec3(1, 0, 1), last, new Vec3(19, 0, 5));
        assertEquals(Status.COMPLETE, result.status());
        assertGroundSegments(mesh, result);
        Vec3 crossing = result.points().stream().filter(p -> p.polygonReference() == last.reference()).findFirst().orElseThrow().position();
        assertEquals(10, crossing.x());
        assertTrue(crossing.z() > 4 && crossing.z() < 6);
    }

    @Test void slopedPortalUsesReachableSubintervalInsteadOfRejectingItsHighMidpoint() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile last = height(square(1, 1, 0, 10, 20, 0, 10, 1, 3), z -> z / 10, 0);
        NavMesh mesh = mesh(first, last);
        var result = path(mesh, first, new Vec3(2, 0, 8), last, new Vec3(18, 0, 9));
        assertEquals(Status.COMPLETE, result.status());
        assertGroundSegments(mesh, result);
        Vec3 crossing = result.points().stream().filter(p -> p.polygonReference() == last.reference()).findFirst().orElseThrow().position();
        assertTrue(crossing.z() > 0 && crossing.z() <= 3.5 + 1e-7);
        assertEquals(0.9, result.points().getLast().position().y(), 1e-12);
    }

    @Test void portalAboveClimbIsUnreachableWithoutPartialOutput() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile last = height(square(1, 1, 0, 10, 20, 0, 10, 1, 3), z -> 0.36, 0);
        assertFailure(Status.UNREACHABLE, path(mesh(first, last), first, new Vec3(5, 0, 5), last, new Vec3(15, 0, 5)));
    }

    @Test void missingReversePortalAndCornerOnlyContactRemainDisconnected() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile missing = square(1, 1, 0, 10, 20, 0, 10, 1);
        assertFailure(Status.UNREACHABLE, path(mesh(first, missing), first, new Vec3(5, 0, 5), missing, new Vec3(15, 0, 5)));
        Tile corner = square(1, 1, 0, 10, 20, 10, 20, 1, 3);
        assertFailure(Status.UNREACHABLE, path(mesh(first, corner), first, new Vec3(5, 0, 5), corner, new Vec3(15, 0, 15)));
    }

    @Test void invalidReferencesCoordinatesAndFiltersIdentifyTheInvalidEndpoint() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile last = square(1, 1, 0, 10, 20, 0, 10, 2, 3);
        var query = new NavMeshPathQuery(mesh(first, last));
        Vec3 start = new Vec3(5, 0, 5), end = new Vec3(15, 0, 5);
        assertFailure(Status.INVALID_START, query.findPath(0, start, last.reference(), end, WALKABLE));
        assertFailure(Status.INVALID_END, query.findPath(first.reference(), start, 0, end, WALKABLE));
        assertFailure(Status.INVALID_START, query.findPath(first.reference(), new Vec3(-1, 0, 5), last.reference(), end, WALKABLE));
        assertFailure(Status.INVALID_END, query.findPath(first.reference(), start, last.reference(), start, WALKABLE));
        assertFailure(Status.INVALID_START, query.findPath(first.reference(), start, last.reference(), end, new Filter(0, 0)));
        assertFailure(Status.INVALID_END, query.findPath(first.reference(), start, last.reference(), end, new Filter(1, 0)));
        assertFailure(Status.INVALID_END, query.findPath(first.reference(), start, last.reference(), end, new Filter(3, 2)));
    }

    @Test void explicitlyRequestedLayersDoNotSilentlySnapToAnotherSurface() {
        Tile low = square(0, 0, 0, 0, 10, 0, 10, 1);
        Tile high = height(square(1, 0, 0, 0, 10, 0, 10, 1), z -> 5, 1);
        var result = path(mesh(low, high), high, new Vec3(1, 0, 1), high, new Vec3(9, 0, 9));
        assertEquals(Status.COMPLETE, result.status());
        assertTrue(result.points().stream().allMatch(p -> p.position().y() == 5 && p.polygonReference() == high.reference()));
        assertFailure(Status.UNREACHABLE, path(mesh(low, high), low, new Vec3(1, 5, 1), high, new Vec3(9, 0, 9)));
    }

    @Test void workSearchAndOutputBudgetsNeverExposePartialSuccess() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile last = square(1, 1, 0, 10, 20, 0, 10, 1, 3);
        NavMesh mesh = mesh(first, last);
        for (var limits : List.of(new Limits(1, 20, 20), new Limits(10_000, 1, 20), new Limits(10_000, 20, 1))) {
            var result = new NavMeshPathQuery(mesh, limits).findPath(first.reference(), new Vec3(5, 0, 5), last.reference(), new Vec3(15, 0, 5), WALKABLE);
            assertFailure(Status.LIMIT_REACHED, result);
        }
    }

    @Test void equalRoutesChooseUnsignedReferenceRegardlessOfTileInputOrder() {
        Tile first = reference(square(0, 0, 0, 0, 10, 0, 10, 1, 1, 2), Long.MIN_VALUE);
        Tile north = reference(square(1, 0, 1, 0, 10, 10, 20, 1, 0, 1), Long.MIN_VALUE | (1L << 20));
        Tile east = reference(square(2, 1, 0, 10, 20, 0, 10, 1, 3, 2), (1L << 40) | (2L << 20));
        Tile last = reference(square(3, 1, 1, 10, 20, 10, 20, 1, 3, 0), (1L << 40) | (3L << 20));
        var parameters = new Parameters(1.8, 0, 0.35, 4, new Vec3(0, 0, 0), 10, 10,
                1 << 20, 1 << 20, new ReferenceLayout(20, 20, 24));
        for (var tiles : List.of(List.of(first, north, east, last), List.of(last, east, north, first))) {
            NavMesh mesh = new NavMesh(parameters, tiles);
            var result = path(mesh, first, new Vec3(5, 0, 5), last, new Vec3(15, 0, 15));
            assertEquals(Status.COMPLETE, result.status());
            assertEquals(List.of(first.reference(), east.reference(), last.reference()), result.corridor());
            assertGroundSegments(mesh, result);
        }
    }

    @Test void blockedIntermediateFlagsCannotBeTraversed() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile blocked = square(1, 1, 0, 10, 20, 0, 10, 2, 3, 1);
        Tile last = square(2, 2, 0, 20, 30, 0, 10, 1, 3);
        var query = new NavMeshPathQuery(mesh(first, blocked, last));
        assertFailure(Status.UNREACHABLE, query.findPath(first.reference(), new Vec3(5, 0, 5), last.reference(), new Vec3(25, 0, 5), new Filter(1, 0)));
        assertEquals(Status.COMPLETE, query.findPath(first.reference(), new Vec3(5, 0, 5), last.reference(), new Vec3(25, 0, 5), WALKABLE).status());
    }

    @Test void heightBreakpointsFollowTheDetailPeakInsteadOfCuttingThroughIt() {
        Tile base = square(0, 0, 0, 0, 10, 0, 10, 1);
        Tile hill = new Tile(base.reference(), base.slot(), base.key(), new Bounds(new Vec3(0, 0, 0), new Vec3(10, 3, 10)),
                0, base.vertices(), base.polygons(), List.of(new DetailMesh(0, 0, 1, 4)), List.of(new Vec3(5, 3, 5)),
                List.of(new DetailTriangle(0, 1, 4, 0), new DetailTriangle(1, 2, 4, 0),
                        new DetailTriangle(2, 3, 4, 0), new DetailTriangle(3, 0, 4, 0)), List.of(), List.of());
        NavMesh mesh = mesh(hill);
        var result = path(mesh, hill, new Vec3(0, 0, 5), hill, new Vec3(10, 0, 5));
        assertEquals(Status.COMPLETE, result.status());
        assertTrue(result.points().stream().anyMatch(p -> p.position().equals(new Vec3(5, 3, 5))));
        assertGroundSegments(mesh, result);
    }

    @Test void stepCrossingsKeepBothGroundHeights() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile last = height(square(1, 1, 0, 10, 20, 0, 10, 1, 3), z -> 0.25, 0);
        var result = path(mesh(first, last), first, new Vec3(5, 0, 5), last, new Vec3(15, 0, 5));
        assertEquals(Status.COMPLETE, result.status());
        assertTrue(result.points().contains(new NavMeshQuery.SurfacePoint(first.reference(), new Vec3(10, 0, 5))));
        assertTrue(result.points().contains(new NavMeshQuery.SurfacePoint(last.reference(), new Vec3(10, 0.25, 5))));
        assertGroundSegments(mesh(first, last), result);
    }

    @Test void invalidNumericInputsAndLimitsAreRejectedBeforeSearch() {
        Tile tile = square(0, 0, 0, 0, 10, 0, 10, 1);
        var query = new NavMeshPathQuery(mesh(tile));
        assertThrows(IllegalArgumentException.class, () -> query.findPath(tile.reference(), new Vec3(Double.NaN, 0, 5), tile.reference(), new Vec3(5, 0, 5), WALKABLE));
        assertThrows(IllegalArgumentException.class, () -> query.findPath(tile.reference(), new Vec3(5, 0, 5), tile.reference(), new Vec3(5, Double.POSITIVE_INFINITY, 5), WALKABLE));
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limits(16_777_217, 1, 1));
    }

    @Test void concurrentQueriesKeepSeparateSearchAndOutputState() throws Exception {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile last = square(1, 1, 0, 10, 20, 0, 10, 1, 3);
        var query = new NavMeshPathQuery(mesh(first, last));
        try (var workers = Executors.newFixedThreadPool(4)) {
            var results = new ArrayList<java.util.concurrent.Future<Result>>();
            for (int i = 0; i < 24; i++) results.add(workers.submit(() -> query.findPath(first.reference(), new Vec3(5, 0, 5), last.reference(), new Vec3(15, 0, 5), WALKABLE)));
            Result expected = results.getFirst().get(2, TimeUnit.SECONDS);
            assertEquals(Status.COMPLETE, expected.status());
            for (var result : results) assertEquals(expected, result.get(2, TimeUnit.SECONDS));
        }
        assertEquals(EdgeKind.PORTAL, first.polygons().getFirst().edges().get(1).kind());
    }

    private static Result path(NavMesh mesh, Tile first, Vec3 start, Tile last, Vec3 end) {
        return new NavMeshPathQuery(mesh).findPath(first.reference(), start, last.reference(), end, WALKABLE);
    }
    private static void assertFailure(Status status, Result result) {
        assertEquals(status, result.status());
        assertTrue(result.corridor().isEmpty());
        assertTrue(result.points().isEmpty());
    }
    private static void assertGroundSegments(NavMesh mesh, Result result) {
        for (int i = 0; i < result.points().size(); i++) {
            var point = result.points().get(i);
            var surface = NavMeshGeometry.surface(mesh.resolve(point.polygonReference()).orElseThrow());
            assertTrue(NavMeshGeometry.contains(surface, point.position()));
            assertEquals(point.position().y(), NavMeshGeometry.onSurface(surface, point.position(), new NavMeshGeometry.Work(1000)).orElseThrow().y(), 1e-7);
            if (i == 0) continue;
            var previous = result.points().get(i - 1);
            if (previous.polygonReference() != point.polygonReference()) {
                assertEquals(previous.position().x(), point.position().x(), 1e-7);
                assertEquals(previous.position().z(), point.position().z(), 1e-7);
                assertTrue(Math.abs(previous.position().y() - point.position().y()) <= mesh.parameters().walkableClimb() + 1e-7);
            } else {
                Vec3 middle = NavMeshGeometry.interpolate(previous.position(), point.position(), 0.5);
                assertEquals(middle.y(), NavMeshGeometry.onSurface(surface, middle, new NavMeshGeometry.Work(1000)).orElseThrow().y(), 1e-7);
            }
        }
    }
    private static NavMesh mesh(Tile... tiles) {
        return new NavMesh(new Parameters(1.8, 0, 0.35, 4, new Vec3(0, -1, 0), 10, 10,
                16, 16, new ReferenceLayout(4, 4, 31)), List.of(tiles));
    }
    private static Tile square(int slot, int tileX, int tileZ, double minX, double maxX, double minZ, double maxZ, int flags, int... portals) {
        var vertices = List.of(new Vec3(minX, 0, minZ), new Vec3(maxX, 0, minZ), new Vec3(maxX, 0, maxZ), new Vec3(minX, 0, maxZ));
        var edges = new ArrayList<>(List.of(WALL, WALL, WALL, WALL));
        for (int portal : portals) edges.set(portal, new Edge(EdgeKind.PORTAL, -1, new int[]{6, 0, 2, 4}[portal]));
        return new Tile(256L | ((long) slot << 4), slot, new TileKey(tileX, tileZ, 0),
                new Bounds(new Vec3(minX, 0, minZ), new Vec3(maxX, 0, maxZ)), 4, vertices,
                List.of(new Polygon(List.of(0, 1, 2, 3), edges, flags, 0, PolygonType.GROUND)),
                List.of(new DetailMesh(0, 0, 0, 2)), List.of(),
                List.of(new DetailTriangle(0, 1, 2, 5), new DetailTriangle(0, 2, 3, 20)), List.of(), List.of());
    }
    private static Tile splitSquare() {
        return new Tile(256, 0, new TileKey(0, 0, 0), new Bounds(new Vec3(0, 0, 0), new Vec3(10, 0, 10)), 8,
                List.of(new Vec3(0, 0, 0), new Vec3(5, 0, 0), new Vec3(5, 0, 10), new Vec3(0, 0, 10), new Vec3(10, 0, 0), new Vec3(10, 0, 10)),
                List.of(new Polygon(List.of(0, 1, 2, 3), List.of(WALL, new Edge(EdgeKind.INTERNAL, 1, -1), WALL, WALL), 1, 0, PolygonType.GROUND),
                        new Polygon(List.of(1, 4, 5, 2), List.of(WALL, WALL, WALL, new Edge(EdgeKind.INTERNAL, 0, -1)), 1, 0, PolygonType.GROUND)),
                List.of(new DetailMesh(0, 0, 0, 2), new DetailMesh(0, 2, 0, 2)), List.of(),
                List.of(new DetailTriangle(0, 1, 2, 5), new DetailTriangle(0, 2, 3, 20), new DetailTriangle(0, 1, 2, 5), new DetailTriangle(0, 2, 3, 20)), List.of(), List.of());
    }
    private static Tile height(Tile tile, DoubleUnaryOperator height, int layer) {
        var vertices = tile.vertices().stream().map(v -> new Vec3(v.x(), height.applyAsDouble(v.z()), v.z())).toList();
        double min = vertices.stream().mapToDouble(Vec3::y).min().orElseThrow(), max = vertices.stream().mapToDouble(Vec3::y).max().orElseThrow();
        return new Tile(tile.reference(), tile.slot(), new TileKey(tile.key().x(), tile.key().z(), layer),
                new Bounds(new Vec3(tile.bounds().minimum().x(), min, tile.bounds().minimum().z()), new Vec3(tile.bounds().maximum().x(), max, tile.bounds().maximum().z())),
                tile.serializedLinkCapacity(), vertices, tile.polygons(), tile.detailMeshes(), tile.detailVertices(), tile.detailTriangles(), tile.bvTree(), tile.offMeshConnections());
    }
    private static Tile reference(Tile tile, long reference) {
        return new Tile(reference, tile.slot(), tile.key(), tile.bounds(), tile.serializedLinkCapacity(), tile.vertices(), tile.polygons(),
                tile.detailMeshes(), tile.detailVertices(), tile.detailTriangles(), tile.bvTree(), tile.offMeshConnections());
    }
}
