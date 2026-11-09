package com.game.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

import static com.game.navigation.NavMesh.*;
import static com.game.navigation.ServerNavMeshQuery.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerNavMeshQueryTest {
    private static final Edge WALL = new Edge(EdgeKind.WALL, -1, -1);

    @Test void coordinateConversionDoesNotSwapServerHeightWithHorizontalAxis() {
        Position server = new Position(123, -456, 7);
        Vec3 navigation = toNavigation(server);
        assertEquals(new Vec3(-456, 7, 123), navigation);
        assertEquals(server, toServer(navigation));
    }

    @Test void invalidCoordinatesCannotEnterPlacementOrMovement() {
        assertThrows(IllegalArgumentException.class, () -> new Position(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Position(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new Position(0, 0, Double.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Position(1_000_000_001, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> toServer(new Vec3(0, Double.NaN, 0)));
        assertDoesNotThrow(() -> new Position(-1e9, 1e9, 0));
    }

    @Test void placementConvertsBothAxesAndProjectsGroundHeight() {
        var query = query(square(0, 10, 20, 30, 40, x -> 3, 0));
        var result = query.place(new Position(35, 21, 4));
        assertEquals(PlacementStatus.FOUND, result.status());
        assertEquals(new Position(35, 20, 3), result.surface().orElseThrow().position());
        assertEquals(256, result.surface().orElseThrow().polygonReference());
    }

    @Test void placementRetainsStrictVerticalAndHorizontalCandidateBoundaries() {
        var query = query(square(0, 10, 20, 30, 40, x -> 3, 0));
        assertEquals(PlacementStatus.NOT_FOUND, query.place(new Position(35, 15, 7)).status());
        assertEquals(PlacementStatus.FOUND, query.place(new Position(35, 15, Math.nextDown(7))).status());
        assertEquals(PlacementStatus.FOUND, query.place(new Position(35, 22, 3)).status());
        var outside = query.place(new Position(35, 22.001, 3));
        assertEquals(PlacementStatus.NOT_FOUND, outside.status());
        assertTrue(outside.surface().isEmpty());
    }

    @Test void clearMovementKeepsTheTraversedSurfaceAndProjectsHeight() {
        var query = query(square(0, 10, 20, 30, 40, x -> 3, 0));
        var result = query.move(new Position(35, 15, 3.5), new Position(36, 16, 2.5));
        assertEquals(MoveStatus.CLEAR, result.status());
        assertEquals(new Position(36, 16, 3), result.position().orElseThrow().position());
    }

    @Test void allFourWallsPullBackFiveCentimetresInServerCoordinates() {
        var query = query(flatSquare());
        List<Position> targets = List.of(new Position(15, 5, 0), new Position(-5, 5, 0),
                new Position(5, 15, 0), new Position(5, -5, 0));
        List<Position> expected = List.of(new Position(9.95, 5, 0), new Position(0.05, 5, 0),
                new Position(5, 9.95, 0), new Position(5, 0.05, 0));
        for (int i = 0; i < targets.size(); i++) {
            var result = query.move(new Position(5, 5, 0), targets.get(i));
            assertEquals(MoveStatus.HIT_WALL, result.status());
            assertPosition(expected.get(i), result.position().orElseThrow().position());
        }
    }

    @Test void wallCloserThanPullbackNeverMovesBehindTheStart() {
        var query = query(flatSquare());
        Position from = new Position(5, 9.99, 0);
        assertEquals(from, query.move(from, new Position(5, 10.01, 0)).position().orElseThrow().position());
        assertEquals(from, query.move(from, new Position(5, 20, 0)).position().orElseThrow().position());
        Position boundary = new Position(5, 10, 0);
        var result = query.move(boundary, new Position(5, 11, 0));
        assertEquals(MoveStatus.HIT_WALL, result.status());
        assertEquals(boundary, result.position().orElseThrow().position());
    }

    @Test void pullbackReprojectsSlopeInsteadOfKeepingClientSuppliedHeight() {
        var query = query(square(0, 0, 10, 0, 10, x -> x / 10, 0));
        var result = query.move(new Position(5, 5, 0.5), new Position(5, 15, 2));
        assertEquals(MoveStatus.HIT_WALL, result.status());
        Position safe = result.position().orElseThrow().position();
        double expectedY = 10 - 10 * 0.05 / Math.hypot(10, 1.5);
        assertEquals(expectedY, safe.y(), 1e-10);
        assertEquals(safe.y() / 10, safe.z(), 1e-10);
    }

    @Test void missingStartHasNoSyntheticOriginOrSpawn() {
        var result = query(flatSquare()).move(new Position(100, 100, 0), new Position(5, 5, 0));
        assertEquals(MoveStatus.INVALID_START, result.status());
        assertTrue(result.position().isEmpty());
    }

    @Test void stationaryMoveKeepsSurfaceHeightAndEndHeightFailureIsExplicit() {
        var query = query(flatSquare());
        var same = query.move(new Position(5, 5, 1), new Position(5, 5, -1));
        assertEquals(MoveStatus.CLEAR, same.status());
        assertEquals(new Position(5, 5, 0), same.position().orElseThrow().position());
        var tooHigh = query.move(new Position(5, 5, 0), new Position(5, 5, 4));
        assertEquals(MoveStatus.END_HEIGHT_OUT_OF_RANGE, tooHigh.status());
        assertEquals(new Position(5, 5, 0), tooHigh.position().orElseThrow().position());
    }

    @Test void overlappingDisconnectedFloorCannotCaptureMovementEndpoint() {
        Tile lower = flatSquare(), upper = square(1, 0, 10, 0, 10, x -> 2, 1);
        var query = query(lower, upper);
        Position from = new Position(5, 5, 0), to = new Position(6, 6, 2);
        assertEquals(upper.reference(), query.place(to).surface().orElseThrow().polygonReference());
        var move = query.move(from, to);
        assertEquals(MoveStatus.CLEAR, move.status());
        assertEquals(lower.reference(), move.position().orElseThrow().polygonReference());
        assertEquals(new Position(6, 6, 0), move.position().orElseThrow().position());
        var path = query.findPath(from, to);
        assertEquals(NavMeshPathQuery.Status.UNREACHABLE, path.status());
        assertTrue(path.points().isEmpty());
    }

    @Test void filteredSurfaceIsNotAValidStartOrPlacement() {
        var query = new ServerNavMeshQuery(mesh(flatSquare()), new NavMeshQuery.Filter(0, 0),
                NavMeshQuery.Limits.defaults(), NavMeshPathQuery.Limits.defaults());
        assertEquals(PlacementStatus.NOT_FOUND, query.place(new Position(5, 5, 0)).status());
        assertEquals(MoveStatus.INVALID_START, query.move(new Position(5, 5, 0), new Position(6, 6, 0)).status());
    }

    @Test void exhaustedStartSearchDoesNotReturnPartialPlacement() {
        var query = limited(mesh(flatSquare()), new NavMeshQuery.Limits(1, 1), NavMeshPathQuery.Limits.defaults());
        var placement = query.place(new Position(5, 5, 0));
        assertEquals(PlacementStatus.LIMIT_REACHED, placement.status());
        assertTrue(placement.surface().isEmpty());
        var movement = query.move(new Position(5, 5, 0), new Position(6, 6, 0));
        assertEquals(MoveStatus.LIMIT_REACHED, movement.status());
        assertTrue(movement.position().isEmpty());
    }

    @Test void exhaustedRayReturnsSnappedStartInsteadOfPartialProgress() {
        var query = limited(mesh(splitSquare()), new NavMeshQuery.Limits(1000, 1), NavMeshPathQuery.Limits.defaults());
        var result = query.move(new Position(5, 1, 1), new Position(5, 9, 0));
        assertEquals(MoveStatus.LIMIT_REACHED, result.status());
        assertEquals(new Position(5, 1, 0), result.position().orElseThrow().position());
    }

    @Test void pathsSnapEndpointsConvertAxesAndExposeImmutableLists() {
        var query = query(square(0, 10, 20, 30, 40, x -> 3, 0));
        var result = query.findPath(new Position(32, 9, 2), new Position(38, 19, 4));
        assertEquals(NavMeshPathQuery.Status.COMPLETE, result.status());
        assertEquals(new Position(32, 10, 3), result.points().getFirst().position());
        assertEquals(new Position(38, 19, 3), result.points().getLast().position());
        assertEquals(List.of(256L), result.corridor());
        assertThrows(UnsupportedOperationException.class, () -> result.points().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.corridor().clear());
    }

    @Test void pathsDistinguishStartEndAndBudgetFailuresWithoutPartialPoints() {
        var query = query(flatSquare());
        Position valid = new Position(5, 5, 0), missing = new Position(100, 100, 0);
        assertPathFailure(NavMeshPathQuery.Status.INVALID_START, query.findPath(missing, valid));
        assertPathFailure(NavMeshPathQuery.Status.INVALID_END, query.findPath(valid, missing));
        var shortSearch = limited(mesh(flatSquare()), new NavMeshQuery.Limits(1, 1), NavMeshPathQuery.Limits.defaults());
        assertPathFailure(NavMeshPathQuery.Status.LIMIT_REACHED, shortSearch.findPath(valid, valid));
        var shortPath = limited(mesh(flatSquare()), NavMeshQuery.Limits.defaults(), new NavMeshPathQuery.Limits(1, 1, 1));
        assertPathFailure(NavMeshPathQuery.Status.LIMIT_REACHED, shortPath.findPath(valid, new Position(6, 6, 0)));
    }

    @Test void concurrentMovementKeepsIndependentResults() throws Exception {
        var query = query(flatSquare());
        try (var workers = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<MoveResult>>();
            for (int i = 0; i < 24; i++) {
                futures.add(workers.submit(() -> query.move(new Position(5, 5, 0), new Position(15, 5, 0))));
            }
            var expected = futures.getFirst().get(2, TimeUnit.SECONDS);
            assertEquals(MoveStatus.HIT_WALL, expected.status());
            assertPosition(new Position(9.95, 5, 0), expected.position().orElseThrow().position());
            for (var future : futures) assertEquals(expected, future.get(2, TimeUnit.SECONDS));
        }
    }

    private static void assertPosition(Position expected, Position actual) {
        assertEquals(expected.x(), actual.x(), 1e-10);
        assertEquals(expected.y(), actual.y(), 1e-10);
        assertEquals(expected.z(), actual.z(), 1e-10);
    }

    private static void assertPathFailure(NavMeshPathQuery.Status status, PathResult result) {
        assertEquals(status, result.status());
        assertTrue(result.points().isEmpty());
        assertTrue(result.corridor().isEmpty());
    }

    private static ServerNavMeshQuery query(Tile... tiles) { return new ServerNavMeshQuery(mesh(tiles)); }

    private static ServerNavMeshQuery limited(NavMesh mesh, NavMeshQuery.Limits limits, NavMeshPathQuery.Limits pathLimits) {
        return new ServerNavMeshQuery(mesh, NavMeshQuery.Filter.walkable(), limits, pathLimits);
    }

    private static NavMesh mesh(Tile... tiles) {
        return new NavMesh(new Parameters(1.8, 0, 0.35, 4, new Vec3(0, -1, 0), 10, 10,
                16, 16, new ReferenceLayout(4, 4, 31)), List.of(tiles));
    }

    private static Tile flatSquare() { return square(0, 0, 10, 0, 10, x -> 0, 0); }

    private static Tile square(int slot, double minX, double maxX, double minZ, double maxZ,
                               DoubleUnaryOperator height, int layer) {
        double left = height.applyAsDouble(minX), right = height.applyAsDouble(maxX);
        var vertices = List.of(new Vec3(minX, left, minZ), new Vec3(maxX, right, minZ),
                new Vec3(maxX, right, maxZ), new Vec3(minX, left, maxZ));
        return new Tile(256L | ((long) slot << 4), slot, new TileKey(0, 0, layer),
                new Bounds(new Vec3(minX, Math.min(left, right), minZ), new Vec3(maxX, Math.max(left, right), maxZ)),
                4, vertices, List.of(new Polygon(List.of(0, 1, 2, 3), List.of(WALL, WALL, WALL, WALL), 1, 0, PolygonType.GROUND)),
                List.of(new DetailMesh(0, 0, 0, 2)), List.of(),
                List.of(new DetailTriangle(0, 1, 2, 5), new DetailTriangle(0, 2, 3, 20)), List.of(), List.of());
    }

    private static Tile splitSquare() {
        return new Tile(256, 0, new TileKey(0, 0, 0), new Bounds(new Vec3(0, 0, 0), new Vec3(10, 0, 10)), 8,
                List.of(new Vec3(0, 0, 0), new Vec3(5, 0, 0), new Vec3(5, 0, 10), new Vec3(0, 0, 10),
                        new Vec3(10, 0, 0), new Vec3(10, 0, 10)),
                List.of(new Polygon(List.of(0, 1, 2, 3), List.of(WALL, new Edge(EdgeKind.INTERNAL, 1, -1), WALL, WALL), 1, 0, PolygonType.GROUND),
                        new Polygon(List.of(1, 4, 5, 2), List.of(WALL, WALL, WALL, new Edge(EdgeKind.INTERNAL, 0, -1)), 1, 0, PolygonType.GROUND)),
                List.of(new DetailMesh(0, 0, 0, 2), new DetailMesh(0, 2, 0, 2)), List.of(),
                List.of(new DetailTriangle(0, 1, 2, 5), new DetailTriangle(0, 2, 3, 20),
                        new DetailTriangle(0, 1, 2, 5), new DetailTriangle(0, 2, 3, 20)), List.of(), List.of());
    }
}
