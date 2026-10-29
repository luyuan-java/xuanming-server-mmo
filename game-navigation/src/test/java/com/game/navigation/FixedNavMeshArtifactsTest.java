package com.game.navigation;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 人工显式提供固定提交导出目录；hash 不符即失败，不将当前工作区文件冒充固定版本。 */
class FixedNavMeshArtifactsTest {
    private record Expected(String name, int bytes, int tiles, int polygons, int vertices,
                            int triangles, String sha256) { }
    private static final List<Expected> EXPECTED = List.of(
            new Expected("main", 121728, 55, 599, 1134, 994, "8a3243a2480419c06e0dbf7ce802eb68b121d9790f9bd6e9f115306873ddf1f5"),
            new Expected("penglai", 113936, 40, 551, 1117, 1000, "97de694834d8d64ae291058bd8ccb356545e72a7e27c88017c6c0536e471bf83"),
            new Expected("donghai", 124888, 47, 616, 1189, 1075, "6fc006672b4c63d9a042ee0a3a94905a7dd42fecdf477e0accf73e31df64df87"),
            new Expected("lanxian", 102088, 45, 486, 1006, 900, "191afe6382d9ec7b3ad94eca572cc05f865e6d93a9347600ff01cee3363ef2da"),
            new Expected("dungeon", 175024, 61, 848, 1676, 1534, "07f11490a9de3fd25ed5fc2b251a1d64b0c5ccf57ab4d5e0eec83880a9a239a4"),
            new Expected("mirror", 175024, 61, 848, 1676, 1534, "07f11490a9de3fd25ed5fc2b251a1d64b0c5ccf57ab4d5e0eec83880a9a239a4"));

    @Test
    void readsAllSixHashPinnedProductsAndResolvesEveryPolygon() throws Exception {
        String directory = System.getProperty("game.it.navmesh.fixedDir");
        assumeTrue(directory != null && !directory.isBlank(), "未显式提供固定产物目录；本用例未运行");
        Path root = Path.of(directory);
        for (Expected expected : EXPECTED) {
            Path path = root.resolve(expected.name() + "_scene.bin");
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(expected.bytes() + 1);
                assertEquals(expected.bytes(), bytes.length, expected.name());
                assertEquals(-1, input.read(), expected.name());
            }
            assertEquals(expected.bytes(), bytes.length, expected.name());
            assertEquals(expected.sha256(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), expected.name());
            NavMesh mesh = new MsetReader().read(bytes);
            assertEquals(expected.tiles(), mesh.tiles().size(), expected.name());
            assertEquals(expected.polygons(), mesh.tiles().stream().mapToInt(t -> t.polygons().size()).sum());
            assertEquals(expected.vertices(), mesh.tiles().stream().mapToInt(t -> t.vertices().size()).sum());
            assertEquals(expected.triangles(), mesh.tiles().stream().mapToInt(t -> t.detailTriangles().size()).sum());
            for (NavMesh.Tile tile : mesh.tiles()) {
                assertTrue(tile.offMeshConnections().isEmpty());
                assertTrue(tile.detailVertices().isEmpty());
                assertEquals(tile.polygons().size() * 2 - 1, tile.bvTree().size());
                for (int i = 0; i < tile.polygons().size(); i++) {
                    assertSame(tile, mesh.resolve(tile.polygonReference(i)).orElseThrow().tile());
                }
            }
        }
    }
}
