package com.game.navigation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static com.game.navigation.NavMesh.*;
import static com.game.navigation.NavMeshQuery.*;
import static org.junit.jupiter.api.Assertions.*;

class NavMeshQueryTest {
    private static final Filter ALL = Filter.walkable();
    private static final Vec3 EXTENTS = new Vec3(2, 4, 2);
    private static final Edge WALL = new Edge(EdgeKind.WALL, -1, -1);

    @Test void nearestUsesHorizontalProjectionAndDetailHeightThenThreeDimensionalDistance() {
        var query = new NavMeshQuery(mesh(square(0, 0, 0, 0, 10, 0, 10, 1, -1)));
        var result = query.nearestPoint(new Vec3(5, 3, 5), EXTENTS, ALL);
        assertEquals(NearestStatus.FOUND, result.status());
        assertEquals(new Vec3(5, 0, 5), result.surface().orElseThrow().position());
        assertEquals(9, result.distanceSquared());
        var edge = query.nearestPoint(new Vec3(-1, 1, 5), EXTENTS, ALL);
        assertEquals(new Vec3(0, 0, 5), edge.surface().orElseThrow().position());
        assertEquals(2, edge.distanceSquared());
    }

    @Test void nearestSearchHasExplicitRealBoundsAndStrictVerticalExtent() {
        var query = new NavMeshQuery(mesh(square(0, 0, 0, 0, 10, 0, 10, 1, -1)));
        assertEquals(NearestStatus.NOT_FOUND, query.nearestPoint(new Vec3(-3, 0, 5), EXTENTS, ALL).status());
        assertEquals(NearestStatus.NOT_FOUND, query.nearestPoint(new Vec3(5, 4, 5), EXTENTS, ALL).status());
        assertEquals(NearestStatus.NOT_FOUND, query.nearestPoint(new Vec3(5, -100, 5), EXTENTS, ALL).status());
        assertEquals(NearestStatus.FOUND, query.nearestPoint(new Vec3(5, 3.99, 5), EXTENTS, ALL).status());
    }

    @Test void nearestSelectsHeightLayerAndStableUnsignedReferenceForTies() {
        Tile lower = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        Tile upper = square(1, 0, 0, 0, 10, 0, 10, 1, -1);
        upper = atHeight(upper, 5, 1);
        var query = new NavMeshQuery(mesh(upper, lower));
        assertEquals(upper.reference(), query.nearestPoint(new Vec3(5, 3, 5), EXTENTS, ALL)
                .surface().orElseThrow().polygonReference());
        assertEquals(lower.reference(), query.nearestPoint(new Vec3(5, 2.5, 5), EXTENTS, ALL)
                .surface().orElseThrow().polygonReference());
    }

    @Test void flagsRequireIncludedBitAndExclusionWins() {
        var query = new NavMeshQuery(mesh(square(0, 0, 0, 0, 10, 0, 10, 3, -1)));
        var point = new Vec3(5, 0, 5);
        assertEquals(NearestStatus.FOUND, query.nearestPoint(point, EXTENTS, new Filter(2, 0)).status());
        assertEquals(NearestStatus.NOT_FOUND, query.nearestPoint(point, EXTENTS, new Filter(2, 1)).status());
        assertEquals(NearestStatus.NOT_FOUND, query.nearestPoint(point, EXTENTS, new Filter(0, 0)).status());
        var noFlags = new NavMeshQuery(mesh(square(0, 0, 0, 0, 10, 0, 10, 0, -1)));
        assertEquals(NearestStatus.NOT_FOUND, noFlags.nearestPoint(point, EXTENTS, ALL).status());
    }

    @Test void detailVertexHeightIsUsedForNearestAndRayEndpoint() {
        Tile square = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        var peak = new Tile(square.reference(), square.slot(), square.key(), new Bounds(new Vec3(0, 0, 0), new Vec3(10, 2, 10)),
                4, square.vertices(), square.polygons(), List.of(new DetailMesh(0, 0, 1, 4)), List.of(new Vec3(5, 2, 5)),
                List.of(new DetailTriangle(0, 1, 4, 1), new DetailTriangle(1, 2, 4, 1),
                        new DetailTriangle(2, 3, 4, 1), new DetailTriangle(3, 0, 4, 1)), List.of(), List.of());
        var query = new NavMeshQuery(mesh(peak));
        assertEquals(new Vec3(5, 2, 5), query.nearestPoint(new Vec3(5, 3, 5), EXTENTS, ALL).surface().orElseThrow().position());
        var result = query.raycast(peak.reference(), new Vec3(1, 100, 5), new Vec3(9, -100, 5), ALL);
        assertEquals(RaycastStatus.CLEAR, result.status());
        assertEquals(0.4, result.surface().orElseThrow().position().y(), 1e-12);
        assertEquals(1, result.fraction());
    }

    @Test void clockwiseAndCounterclockwiseFacesGiveTheSameProjectionAndWallNormal() {
        Tile original = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        Tile reversed = new Tile(original.reference(), original.slot(), original.key(), original.bounds(), 4,
                original.vertices(), List.of(new Polygon(List.of(3, 2, 1, 0), List.of(WALL, WALL, WALL, WALL), 1, 0, PolygonType.GROUND)),
                original.detailMeshes(), List.of(), original.detailTriangles(), List.of(), List.of());
        for (Tile tile : List.of(original, reversed)) {
            var query = new NavMeshQuery(mesh(tile));
            assertEquals(new Vec3(5, 0, 5), query.nearestPoint(new Vec3(5, 1, 5), EXTENTS, ALL).surface().orElseThrow().position());
            var hit = query.raycast(tile.reference(), new Vec3(5, 0, 5), new Vec3(15, 0, 5), ALL);
            assertEquals(RaycastStatus.HIT_WALL, hit.status());
            assertEquals(new Vec3(1, 0, 0), hit.normal());
        }
    }

    @Test void offMeshConnectionsAreNeverGroundCandidatesOrRayStarts() {
        Tile ground = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        var vertices = new ArrayList<>(ground.vertices());
        vertices.add(new Vec3(20, 0, 5)); vertices.add(new Vec3(30, 0, 5));
        var polygons = new ArrayList<>(ground.polygons());
        polygons.add(new Polygon(List.of(4, 5), List.of(WALL, WALL), 1, 0, PolygonType.OFF_MESH_POINT));
        var tile = new Tile(ground.reference(), ground.slot(), ground.key(), ground.bounds(), 4, vertices, polygons,
                ground.detailMeshes(), ground.detailVertices(), ground.detailTriangles(), List.of(),
                List.of(new OffMeshConnection(vertices.get(4), vertices.get(5), 1, -1, 1, 1, 0x80, true, false)));
        var query = new NavMeshQuery(mesh(tile));
        assertEquals(NearestStatus.NOT_FOUND, query.nearestPoint(new Vec3(20, 0, 5), EXTENTS, ALL).status());
        assertEquals(RaycastStatus.INVALID_START, query.raycast(tile.polygonReference(1), vertices.get(4), vertices.get(5), ALL).status());
    }

    @Test void rayWalksInternalNeighbourAndReturnsHeightFromFinalFace() {
        Tile tile = splitSquare(1, 1);
        var result = new NavMeshQuery(mesh(tile)).raycast(tile.polygonReference(0), new Vec3(1, 0, 5), new Vec3(9, 200, 5), ALL);
        assertEquals(RaycastStatus.CLEAR, result.status());
        assertEquals(List.of(tile.polygonReference(0), tile.polygonReference(1)), result.visitedPolygons());
        assertEquals(new Vec3(9, 0, 5), result.surface().orElseThrow().position());
        assertThrows(UnsupportedOperationException.class, () -> result.visitedPolygons().clear());
    }

    @Test void wallReturnsFractionSurfacePointAndOutwardHorizontalNormal() {
        Tile tile = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        var result = new NavMeshQuery(mesh(tile)).raycast(tile.reference(), new Vec3(5, 0, 5), new Vec3(15, 99, 5), ALL);
        assertEquals(RaycastStatus.HIT_WALL, result.status());
        assertEquals(0.5, result.fraction());
        assertEquals(new Vec3(10, 0, 5), result.surface().orElseThrow().position());
        assertEquals(new Vec3(1, 0, 0), result.normal());
    }

    @Test void allFourWallNormalsUseCanonicalZeroComponents() {
        Tile tile = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        var query = new NavMeshQuery(mesh(tile));
        var ends = List.of(new Vec3(15, 0, 5), new Vec3(-5, 0, 5), new Vec3(5, 0, 15), new Vec3(5, 0, -5));
        var normals = List.of(new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1));
        for (int i = 0; i < ends.size(); i++) {
            var hit = query.raycast(tile.reference(), new Vec3(5, 0, 5), ends.get(i), ALL);
            assertEquals(RaycastStatus.HIT_WALL, hit.status());
            assertEquals(normals.get(i), hit.normal());
        }
    }

    @Test void filteredInternalNeighbourIsWallInsteadOfSkippingAcrossIt() {
        Tile tile = splitSquare(1, 2);
        var result = new NavMeshQuery(mesh(tile)).raycast(tile.reference(), new Vec3(1, 0, 5), new Vec3(9, 0, 5), new Filter(1, 0));
        assertEquals(RaycastStatus.HIT_WALL, result.status());
        assertEquals(new Vec3(5, 0, 5), result.surface().orElseThrow().position());
        assertEquals(1, result.visitedPolygons().size());
    }

    @Test void adjacentTilesConnectThroughActualReciprocalPortalOverlap() {
        Tile left = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile right = square(1, 1, 0, 10, 20, 2, 8, 1, 3);
        var query = new NavMeshQuery(mesh(right, left));
        var forward = query.raycast(left.reference(), new Vec3(5, 0, 5), new Vec3(15, 0, 5), ALL);
        assertEquals(RaycastStatus.CLEAR, forward.status());
        assertEquals(List.of(left.reference(), right.reference()), forward.visitedPolygons());
        var reverse = query.raycast(right.reference(), new Vec3(15, 0, 5), new Vec3(5, 0, 5), ALL);
        assertEquals(RaycastStatus.CLEAR, reverse.status());
        assertEquals(List.of(right.reference(), left.reference()), reverse.visitedPolygons());
    }

    @Test void portalMissingNeighbourGapAndEndpointAreConservativeWalls() {
        Tile left = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile right = square(1, 1, 0, 10, 20, 2, 8, 1, 3);
        var query = new NavMeshQuery(mesh(left, right));
        for (double z : new double[]{1, 2, 8, 9, 2 + 5e-9, 8 - 5e-9}) {
            var result = query.raycast(left.reference(), new Vec3(5, 0, z), new Vec3(15, 0, z), ALL);
            assertEquals(RaycastStatus.HIT_WALL, result.status());
            assertEquals(0.5, result.fraction());
        }
        assertEquals(RaycastStatus.CLEAR, query.raycast(left.reference(), new Vec3(5, 0, 2 + 1e-6), new Vec3(15, 0, 2 + 1e-6), ALL).status());
        assertEquals(RaycastStatus.HIT_WALL, new NavMeshQuery(mesh(left))
                .raycast(left.reference(), new Vec3(5, 0, 5), new Vec3(15, 0, 5), ALL).status());
        var noBacklink = square(1, 1, 0, 10, 20, 0, 10, 1, -1);
        assertEquals(RaycastStatus.HIT_WALL, new NavMeshQuery(mesh(left, noBacklink))
                .raycast(left.reference(), new Vec3(5, 0, 5), new Vec3(15, 0, 5), ALL).status());
    }

    @Test void crossTileHeightMustBeWithinClimbAtTheActualCrossing() {
        Tile left = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile right = square(1, 1, 0, 10, 20, 0, 10, 1, 3);
        var accepted = new NavMeshQuery(mesh(left, atHeight(right, 0.35, 0)))
                .raycast(left.reference(), new Vec3(5, 0, 5), new Vec3(15, 0, 5), ALL);
        assertEquals(RaycastStatus.CLEAR, accepted.status());
        assertEquals(0.35, accepted.surface().orElseThrow().position().y(), 1e-12);
        var blocked = new NavMeshQuery(mesh(left, atHeight(right, 0.36, 0)))
                .raycast(left.reference(), new Vec3(5, 0, 5), new Vec3(15, 0, 5), ALL);
        assertEquals(RaycastStatus.HIT_WALL, blocked.status());
        assertEquals(0.5, blocked.fraction());
    }

    @Test void multiplePortalLayersChooseStableReferenceWithoutChangingTheRequestedStartLayer() {
        Tile left = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile first = atHeight(square(1, 1, 0, 10, 20, 0, 10, 1, 3), 0.2, 1);
        Tile second = atHeight(square(2, 1, 0, 10, 20, 0, 10, 1, 3), 0.1, 2);
        for (var mesh : List.of(mesh(left, first, second), mesh(second, first, left))) {
            var ray = new NavMeshQuery(mesh).raycast(left.reference(), new Vec3(5, 999, 5), new Vec3(15, -999, 5), ALL);
            assertEquals(RaycastStatus.CLEAR, ray.status());
            assertEquals(List.of(left.reference(), first.reference()), ray.visitedPolygons());
            assertEquals(0.2, ray.surface().orElseThrow().position().y(), 1e-12);
        }
    }

    @Test void tangentZeroLengthAndClosedEndpointHaveDefinedResults() {
        Tile tile = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        var query = new NavMeshQuery(mesh(tile));
        assertEquals(RaycastStatus.CLEAR, query.raycast(tile.reference(), new Vec3(0, 0, 0), new Vec3(10, 100, 0), ALL).status());
        var stationary = query.raycast(tile.reference(), new Vec3(5, 8, 5), new Vec3(5, -8, 5), ALL);
        assertEquals(RaycastStatus.CLEAR, stationary.status());
        assertEquals(new Vec3(5, 0, 5), stationary.surface().orElseThrow().position());
        var leaves = query.raycast(tile.reference(), new Vec3(0, 0, 5), new Vec3(-1, 0, 5), ALL);
        assertEquals(RaycastStatus.HIT_WALL, leaves.status());
        assertEquals(0, leaves.fraction());
    }

    @Test void cornerContactDoesNotJumpToDisconnectedDiagonalTile() {
        Tile start = square(0, 0, 0, 0, 10, 0, 10, 1, 1);
        Tile diagonal = square(1, 1, 1, 10, 20, 10, 20, 1, 3);
        var result = new NavMeshQuery(mesh(start, diagonal)).raycast(start.reference(), new Vec3(5, 0, 5), new Vec3(15, 0, 15), ALL);
        assertEquals(RaycastStatus.HIT_WALL, result.status());
        assertEquals(0.5, result.fraction());
        assertEquals(new Vec3(10, 0, 10), result.surface().orElseThrow().position());
    }

    @Test void invalidReferenceFilteredStartAndOffSurfaceStartAreDistinctFromWall() {
        Tile tile = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        var query = new NavMeshQuery(mesh(tile));
        assertEquals(RaycastStatus.INVALID_START, query.raycast(0, new Vec3(5, 0, 5), new Vec3(6, 0, 5), ALL).status());
        assertEquals(RaycastStatus.INVALID_START, query.raycast(tile.reference() + (1L << 8), new Vec3(5, 0, 5), new Vec3(6, 0, 5), ALL).status());
        assertEquals(RaycastStatus.INVALID_START, query.raycast(tile.reference(), new Vec3(-1, 0, 5), new Vec3(6, 0, 5), ALL).status());
        var filtered = query.raycast(tile.reference(), new Vec3(5, 0, 5), new Vec3(6, 0, 5), new Filter(0, 0));
        assertEquals(RaycastStatus.INVALID_START, filtered.status());
        assertTrue(filtered.surface().isEmpty());
        assertTrue(filtered.visitedPolygons().isEmpty());
    }

    @Test void workAndVisitedLimitsDoNotReturnPartialNearestOrClearRay() {
        Tile tile = splitSquare(1, 1);
        var query = new NavMeshQuery(mesh(tile), new Limits(1, 1));
        var nearest = query.nearestPoint(new Vec3(1, 0, 5), EXTENTS, ALL);
        assertEquals(NearestStatus.LIMIT_REACHED, nearest.status());
        assertTrue(nearest.surface().isEmpty());
        var limited = new NavMeshQuery(mesh(tile), new Limits(100, 1))
                .raycast(tile.reference(), new Vec3(1, 0, 5), new Vec3(9, 0, 5), ALL);
        assertEquals(RaycastStatus.LIMIT_REACHED, limited.status());
        assertEquals(0.5, limited.fraction());
        assertEquals(1, limited.visitedPolygons().size());
        assertEquals(new Vec3(5, 0, 5), limited.surface().orElseThrow().position());
    }

    @Test void unsignedHighReferencesRemainOpaqueAndTieBreakWithoutSignedOrdering() {
        Tile first = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        Tile second = square(1, 0, 0, 0, 10, 0, 10, 1, -1);
        first = withReference(first, Long.MIN_VALUE);
        second = withReference(atHeight(second, 0, 1), (1L << 40) | (1L << 20));
        var parameters = new Parameters(1.8, 0, 0.35, 4, new Vec3(0, -1, 0), 10, 10,
                1 << 20, 1 << 20, new ReferenceLayout(20, 20, 24));
        var query = new NavMeshQuery(new NavMesh(parameters, List.of(first, second)));
        assertEquals(second.reference(), query.nearestPoint(new Vec3(5, 0, 5), EXTENTS, ALL).surface().orElseThrow().polygonReference());
        var ray = query.raycast(first.reference(), new Vec3(1, 0, 1), new Vec3(9, 0, 9), ALL);
        assertEquals(RaycastStatus.CLEAR, ray.status());
        assertEquals(Long.MIN_VALUE, ray.surface().orElseThrow().polygonReference());
    }

    @Test void rejectsNonfiniteWorldCoordinatesInvalidExtentsAndLimits() {
        Tile tile = square(0, 0, 0, 0, 10, 0, 10, 1, -1);
        var query = new NavMeshQuery(mesh(tile));
        assertThrows(IllegalArgumentException.class, () -> query.nearestPoint(new Vec3(Double.NaN, 0, 0), EXTENTS, ALL));
        assertThrows(IllegalArgumentException.class, () -> query.nearestPoint(new Vec3(0, 0, 0), new Vec3(1, 0, 1), ALL));
        assertThrows(IllegalArgumentException.class, () -> query.raycast(tile.reference(), new Vec3(1, 0, 1), new Vec3(Double.POSITIVE_INFINITY, 0, 1), ALL));
        assertThrows(IllegalArgumentException.class, () -> new Filter(65536, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 0));
    }

    @Test void queriesReaderProducedGeometryWithoutBvOrSerializedRuntimeLinks() throws Exception {
        var bytes = ByteBuffer.allocate(400).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(MsetReader.MSET_MAGIC).putInt(1).putInt(1).putInt(0);
        bytes.putDouble(1.8).putDouble(0).putDouble(0.35).putDouble(4);
        vector(bytes, new Vec3(0, -1, 0)); bytes.putDouble(10).putDouble(10).putInt(1).putInt(16);
        bytes.putLong(16).putInt(288).putInt(0);
        bytes.putShort((short) 7).putShort((short) 0).putShort((short) 1).putShort((short) 4).putInt(0).putInt(0);
        for (int value : new int[]{4, 1, 0, 2, 0, 0, 1, 0}) bytes.putShort((short) value);
        vector(bytes, new Vec3(0, -1, 0)); vector(bytes, new Vec3(10, 1, 10));
        for (var vertex : square(0, 0, 0, 0, 10, 0, 10, 1, -1).vertices()) vector(bytes, vertex);
        bytes.putInt(0xdeadbeef);
        for (int value : new int[]{0, 1, 2, 3, 0, 0}) bytes.putShort((short) value);
        for (int i = 0; i < 6; i++) bytes.putShort((short) 0);
        bytes.putShort((short) 1).put((byte) 4).put((byte) 0);
        for (int i = 0; i < 64; i++) bytes.put((byte) 0xa5);
        bytes.putShort((short) 0).putShort((short) 0).put((byte) 0).put((byte) 2).putShort((short) 0);
        bytes.put(new byte[]{0, 1, 2, 5, 0, 2, 3, 20});
        assertEquals(bytes.capacity(), bytes.position());
        NavMesh mesh = new MsetReader().read(bytes.array());
        var query = new NavMeshQuery(mesh);
        assertEquals(new Vec3(5, 0, 5), query.nearestPoint(new Vec3(5, 2, 5), EXTENTS, ALL).surface().orElseThrow().position());
        var hit = query.raycast(16, new Vec3(5, 0, 5), new Vec3(20, 0, 5), ALL);
        assertEquals(RaycastStatus.HIT_WALL, hit.status());
        assertEquals(1.0 / 3, hit.fraction(), 1e-12);
    }

    @Test void concurrentCallsHaveIndependentWorkspacesAndLeaveMeshUnchanged() throws Exception {
        Tile tile = splitSquare(1, 1);
        var mesh = mesh(tile);
        var query = new NavMeshQuery(mesh);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<java.util.concurrent.Future<RaycastResult>>();
            for (int i = 0; i < 32; i++) calls.add(workers.submit(() -> query.raycast(tile.reference(), new Vec3(1, 0, 5), new Vec3(9, 0, 5), ALL)));
            for (var call : calls) assertEquals(List.of(tile.reference(), tile.polygonReference(1)), call.get(2, TimeUnit.SECONDS).visitedPolygons());
        }
        assertSame(tile, mesh.tiles().getFirst());
        assertEquals(EdgeKind.INTERNAL, tile.polygons().getFirst().edges().get(1).kind());
    }

    private static NavMesh mesh(Tile... tiles) {
        return new NavMesh(new Parameters(1.8, 0, 0.35, 4, new Vec3(0, -1, 0), 10, 10,
                16, 16, new ReferenceLayout(4, 4, 31)), List.of(tiles));
    }
    private static Tile square(int slot, int tileX, int tileZ, double minX, double maxX, double minZ, double maxZ, int flags, int portalEdge) {
        var vertices = List.of(new Vec3(minX, 0, minZ), new Vec3(maxX, 0, minZ), new Vec3(maxX, 0, maxZ), new Vec3(minX, 0, maxZ));
        var edges = new ArrayList<>(List.of(WALL, WALL, WALL, WALL));
        if (portalEdge >= 0) edges.set(portalEdge, new Edge(EdgeKind.PORTAL, -1, new int[]{6, 0, 2, 4}[portalEdge]));
        return new Tile(256L | ((long) slot << 4), slot, new TileKey(tileX, tileZ, 0),
                new Bounds(new Vec3(minX, 0, minZ), new Vec3(maxX, 0, maxZ)), 4, vertices,
                List.of(new Polygon(List.of(0, 1, 2, 3), edges, flags, 0, PolygonType.GROUND)),
                List.of(new DetailMesh(0, 0, 0, 2)), List.of(),
                List.of(new DetailTriangle(0, 1, 2, 5), new DetailTriangle(0, 2, 3, 20)), List.of(), List.of());
    }
    private static Tile splitSquare(int leftFlags, int rightFlags) {
        return new Tile(256, 0, new TileKey(0, 0, 0), new Bounds(new Vec3(0, 0, 0), new Vec3(10, 0, 10)), 8,
                List.of(new Vec3(0, 0, 0), new Vec3(5, 0, 0), new Vec3(5, 0, 10), new Vec3(0, 0, 10), new Vec3(10, 0, 0), new Vec3(10, 0, 10)),
                List.of(new Polygon(List.of(0, 1, 2, 3), List.of(WALL, new Edge(EdgeKind.INTERNAL, 1, -1), WALL, WALL), leftFlags, 0, PolygonType.GROUND),
                        new Polygon(List.of(1, 4, 5, 2), List.of(WALL, WALL, WALL, new Edge(EdgeKind.INTERNAL, 0, -1)), rightFlags, 0, PolygonType.GROUND)),
                List.of(new DetailMesh(0, 0, 0, 2), new DetailMesh(0, 2, 0, 2)), List.of(),
                List.of(new DetailTriangle(0, 1, 2, 5), new DetailTriangle(0, 2, 3, 20), new DetailTriangle(0, 1, 2, 5), new DetailTriangle(0, 2, 3, 20)), List.of(), List.of());
    }
    private static Tile atHeight(Tile tile, double height, int layer) {
        return new Tile(tile.reference(), tile.slot(), new TileKey(tile.key().x(), tile.key().z(), layer),
                new Bounds(new Vec3(tile.bounds().minimum().x(), height, tile.bounds().minimum().z()), new Vec3(tile.bounds().maximum().x(), height, tile.bounds().maximum().z())),
                tile.serializedLinkCapacity(), tile.vertices().stream().map(v -> new Vec3(v.x(), height, v.z())).toList(), tile.polygons(), tile.detailMeshes(),
                tile.detailVertices(), tile.detailTriangles(), tile.bvTree(), tile.offMeshConnections());
    }
    private static Tile withReference(Tile tile, long reference) {
        return new Tile(reference, tile.slot(), tile.key(), tile.bounds(), tile.serializedLinkCapacity(), tile.vertices(), tile.polygons(),
                tile.detailMeshes(), tile.detailVertices(), tile.detailTriangles(), tile.bvTree(), tile.offMeshConnections());
    }
    private static void vector(ByteBuffer bytes, Vec3 vector) { bytes.putDouble(vector.x()).putDouble(vector.y()).putDouble(vector.z()); }
}
