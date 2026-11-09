package com.game.navigation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.game.navigation.NavMesh.*;
import static com.game.navigation.NavMeshQuery.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 固定产物的几何自洽回归；不是 C++ 查询金样或场景移动验收。 */
class FixedNavMeshQueriesTest {
    private record Asset(String name, int bytes, String sha256) { }
    private static final List<Asset> ASSETS = List.of(
            new Asset("main", 121728, "8a3243a2480419c06e0dbf7ce802eb68b121d9790f9bd6e9f115306873ddf1f5"),
            new Asset("penglai", 113936, "97de694834d8d64ae291058bd8ccb356545e72a7e27c88017c6c0536e471bf83"),
            new Asset("donghai", 124888, "6fc006672b4c63d9a042ee0a3a94905a7dd42fecdf477e0accf73e31df64df87"),
            new Asset("lanxian", 102088, "191afe6382d9ec7b3ad94eca572cc05f865e6d93a9347600ff01cee3363ef2da"),
            new Asset("dungeon", 175024, "07f11490a9de3fd25ed5fc2b251a1d64b0c5ccf57ab4d5e0eec83880a9a239a4"),
            new Asset("mirror", 175024, "07f11490a9de3fd25ed5fc2b251a1d64b0c5ccf57ab4d5e0eec83880a9a239a4"));

    @Test void allHashPinnedSurfacesSupportNearestAndLocalGroundRays() throws Exception {
        Path root = fixedDirectory();
        for (var asset : ASSETS) {
            NavMesh mesh = read(root, asset);
            var query = new NavMeshQuery(mesh);
            int checked = 0;
            for (var tile : mesh.tiles()) {
                for (int polygon = 0; polygon < tile.detailMeshes().size(); polygon++) {
                    var detail = tile.detailMeshes().get(polygon);
                    var first = tile.detailTriangles().get(detail.triangleBase());
                    var last = tile.detailTriangles().get(detail.triangleBase() + detail.triangleCount() - 1);
                    Vec3 from = centroid(tile, polygon, first), to = centroid(tile, polygon, last);
                    var nearest = query.nearestPoint(new Vec3(from.x(), from.y() + 0.1, from.z()), new Vec3(0.01, 1, 0.01), Filter.walkable());
                    assertEquals(NearestStatus.FOUND, nearest.status(), asset.name());
                    assertPoint(from, nearest.surface().orElseThrow().position());
                    var ray = query.raycast(tile.polygonReference(polygon), from, to, Filter.walkable());
                    assertEquals(RaycastStatus.CLEAR, ray.status(), asset.name());
                    assertPoint(to, ray.surface().orElseThrow().position());
                    checked++;
                }
            }
            assertTrue(checked > 0, asset.name());
        }
    }

    @Test void eachFixedAssetHasARealWallAndTraversableCrossTilePortal() throws Exception {
        Path root = fixedDirectory();
        for (var asset : ASSETS) {
            NavMesh mesh = read(root, asset);
            var query = new NavMeshQuery(mesh);
            boolean wallChecked = false, portalChecked = false;
            for (var tile : mesh.tiles()) {
                for (int index = 0; index < tile.detailMeshes().size(); index++) {
                    var polygon = tile.polygons().get(index);
                    for (int edge = 0; edge < polygon.edges().size(); edge++) {
                        Vec3 a = tile.vertices().get(polygon.vertexIndices().get(edge));
                        Vec3 b = tile.vertices().get(polygon.vertexIndices().get((edge + 1) % polygon.vertexIndices().size()));
                        var adjacency = polygon.edges().get(edge);
                        if (!wallChecked && adjacency.kind() == EdgeKind.WALL) {
                            Vec3 normal = outward(tile, polygon, a, b);
                            Vec3 middle = new Vec3((a.x() + b.x()) / 2, (a.y() + b.y()) / 2, (a.z() + b.z()) / 2);
                            var hit = query.raycast(tile.polygonReference(index), offset(middle, normal, -0.01), offset(middle, normal, 0.01), Filter.walkable());
                            assertEquals(RaycastStatus.HIT_WALL, hit.status(), asset.name());
                            assertEquals(0.5, hit.fraction(), 1e-6);
                            assertPoint(middle, hit.surface().orElseThrow().position());
                            wallChecked = true;
                        }
                        if (!portalChecked && adjacency.kind() == EdgeKind.PORTAL)
                            portalChecked = checkOnePortal(mesh, query, tile, index, a, b, adjacency.portalSide());
                    }
                }
            }
            assertTrue(wallChecked, asset.name() + " 必须实际检查墙");
            assertTrue(portalChecked, asset.name() + " 必须实际穿过相邻 tile 门户");
        }
    }

    @Test void eachHashPinnedAssetSupportsLocalAndInternalGroundPaths() throws Exception {
        Path root = fixedDirectory();
        for (var asset : ASSETS) {
            NavMesh mesh = read(root, asset);
            var paths = new NavMeshPathQuery(mesh);
            int localChecked = 0;
            boolean internalChecked = false;
            for (var tile : mesh.tiles()) {
                for (int polygon = 0; polygon < tile.detailMeshes().size(); polygon++) {
                    var detail = tile.detailMeshes().get(polygon);
                    Vec3 from = centroid(tile, polygon, tile.detailTriangles().get(detail.triangleBase()));
                    Vec3 to = centroid(tile, polygon, tile.detailTriangles().get(detail.triangleBase() + detail.triangleCount() - 1));
                    var local = paths.findPath(tile.polygonReference(polygon), from, tile.polygonReference(polygon), to, Filter.walkable());
                    assertEquals(NavMeshPathQuery.Status.COMPLETE, local.status(), asset.name());
                    assertEquals(List.of(tile.polygonReference(polygon)), local.corridor());
                    assertPoint(from, local.points().getFirst().position());
                    assertPoint(to, local.points().getLast().position());
                    localChecked++;
                    if (internalChecked) continue;
                    for (var edge : tile.polygons().get(polygon).edges()) {
                        if (edge.kind() != EdgeKind.INTERNAL) continue;
                        int neighbor = edge.polygonIndex();
                        var neighborDetail = tile.detailMeshes().get(neighbor);
                        Vec3 destination = centroid(tile, neighbor, tile.detailTriangles().get(neighborDetail.triangleBase()));
                        var crossing = paths.findPath(tile.polygonReference(polygon), from, tile.polygonReference(neighbor), destination, Filter.walkable());
                        assertEquals(NavMeshPathQuery.Status.COMPLETE, crossing.status(), asset.name());
                        assertEquals(tile.polygonReference(polygon), crossing.corridor().getFirst().longValue());
                        assertEquals(tile.polygonReference(neighbor), crossing.corridor().getLast().longValue());
                        assertPoint(from, crossing.points().getFirst().position());
                        assertPoint(destination, crossing.points().getLast().position());
                        internalChecked = true;
                        break;
                    }
                }
            }
            assertTrue(localChecked > 0, asset.name());
            assertTrue(internalChecked, asset.name() + " 必须实际检查内部邻面路径");
        }
    }

    private static boolean checkOnePortal(NavMesh mesh, NavMeshQuery query, Tile source, int polygon, Vec3 a, Vec3 b, int side) {
        int nextX = source.key().x() + (side == 0 ? 1 : side == 4 ? -1 : 0);
        int nextZ = source.key().z() + (side == 2 ? 1 : side == 6 ? -1 : 0);
        int along = side == 0 || side == 4 ? 2 : 0;
        int across = along == 2 ? 0 : 2;
        for (var target : mesh.tiles()) {
            if (target.key().x() != nextX || target.key().z() != nextZ) continue;
            for (var candidate : target.polygons()) {
                if (candidate.type() != PolygonType.GROUND) continue;
                for (int edge = 0; edge < candidate.edges().size(); edge++) {
                    var back = candidate.edges().get(edge);
                    if (back.kind() != EdgeKind.PORTAL || back.portalSide() != (side + 4) % 8) continue;
                    Vec3 c = target.vertices().get(candidate.vertexIndices().get(edge));
                    Vec3 d = target.vertices().get(candidate.vertexIndices().get((edge + 1) % candidate.vertexIndices().size()));
                    double lower = Math.max(Math.min(a.axis(along), b.axis(along)), Math.min(c.axis(along), d.axis(along)));
                    double upper = Math.min(Math.max(a.axis(along), b.axis(along)), Math.max(c.axis(along), d.axis(along)));
                    if (upper - lower < 0.1 || Math.abs(a.axis(across) - c.axis(across)) > 1e-9
                            || a.y() != b.y() || a.y() != c.y() || a.y() != d.y()) continue;
                    Vec3 middle = along == 2 ? new Vec3(a.x(), a.y(), (lower + upper) / 2) : new Vec3((lower + upper) / 2, a.y(), a.z());
                    Vec3 outward = new Vec3(side == 0 ? 1 : side == 4 ? -1 : 0, 0, side == 2 ? 1 : side == 6 ? -1 : 0);
                    var ray = query.raycast(source.polygonReference(polygon), offset(middle, outward, -0.01), offset(middle, outward, 0.01), Filter.walkable());
                    assertEquals(RaycastStatus.CLEAR, ray.status());
                    assertEquals(2, ray.visitedPolygons().size());
                    return true;
                }
            }
        }
        return false;
    }

    private static Vec3 outward(Tile tile, Polygon polygon, Vec3 a, Vec3 b) {
        double area = 0;
        for (int i = 0; i < polygon.vertexIndices().size(); i++) {
            var p = tile.vertices().get(polygon.vertexIndices().get(i));
            var q = tile.vertices().get(polygon.vertexIndices().get((i + 1) % polygon.vertexIndices().size()));
            area += p.x() * q.z() - p.z() * q.x();
        }
        double length = Math.hypot(b.x() - a.x(), b.z() - a.z());
        return new Vec3(Math.signum(area) * (b.z() - a.z()) / length, 0, -Math.signum(area) * (b.x() - a.x()) / length);
    }
    private static Vec3 offset(Vec3 point, Vec3 direction, double length) {
        return new Vec3(point.x() + direction.x() * length, point.y(), point.z() + direction.z() * length);
    }
    private static Vec3 centroid(Tile tile, int polygon, DetailTriangle triangle) {
        var a = tile.detailVertex(polygon, triangle.a());
        var b = tile.detailVertex(polygon, triangle.b());
        var c = tile.detailVertex(polygon, triangle.c());
        return new Vec3((a.x() + b.x() + c.x()) / 3, (a.y() + b.y() + c.y()) / 3, (a.z() + b.z() + c.z()) / 3);
    }
    private static void assertPoint(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), 1e-7); assertEquals(expected.y(), actual.y(), 1e-7); assertEquals(expected.z(), actual.z(), 1e-7);
    }
    private static Path fixedDirectory() {
        String directory = System.getProperty("game.it.navmesh.fixedDir");
        assumeTrue(directory != null && !directory.isBlank(), "未显式提供固定六产物目录；本用例未运行");
        return Path.of(directory);
    }
    private static NavMesh read(Path root, Asset asset) throws Exception {
        byte[] bytes;
        try (var input = Files.newInputStream(root.resolve(asset.name() + "_scene.bin"))) {
            bytes = input.readNBytes(asset.bytes() + 1);
            assertEquals(asset.bytes(), bytes.length);
            assertEquals(-1, input.read());
        }
        assertEquals(asset.sha256(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return new MsetReader().read(bytes);
    }
}
