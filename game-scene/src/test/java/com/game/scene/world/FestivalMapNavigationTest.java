package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class FestivalMapNavigationTest {
    static Path root() throws Exception {
        return Path.of(FestivalMapNavigationTest.class.getResource("/festival-maps-20261109").toURI());
    }

    @Test
    @EnabledIfSystemProperty(named = "game.festivalTestTables", matches = ".+")
    void 真实隔离导表与九图读取器配对_默认装配拒绝候选表() throws Exception {
        ConfigTables config = ConfigTables.load(Path.of(System.getProperty("game.festivalTestTables")));
        Path currentRoot = CurrentMapNavigationTest.navigationRoot();
        Path festivalRoot = Path.of(System.getProperty("game.festivalNavigationRoot", root().toString()));
        assertThatThrownBy(() -> ConfigSceneTables.from(config, currentRoot)).isInstanceOf(IllegalStateException.class);
        ConfigSceneTables tables = ConfigSceneTables.from(config, currentRoot, festivalRoot);
        for (int id : new int[]{1001, 1002, 5008, 10000, 19004, 21003, 23006, 28007, 35004}) {
            assertThat(tables.worldSceneConfigIds()).contains(id);
            assertThat(tables.navigation(id)).isInstanceOf(CurrentMapNavigation.class);
            assertThat(tables.navigation(id).isWalkable(tables.spawnPoint(id))).isTrue();
        }
    }

    @Test
    void 九图所有格与冻结位图一致_安全格和平台连通性保持() throws Exception {
        String[] ids = {"01001", "01002", "05008", "10000", "19004", "21003", "23006", "28007", "35004"};
        int[] counts = {250, 309, 2313, 7268, 48, 3218, 833, 1481, 2566};
        int[] components = {1, 1, 1, 2, 1, 1, 1, 1, 3};
        for (int m = 0; m < ids.length; m++) {
            CurrentMapNavigation grid = FestivalMapNavigation.load(root(), ids[m]);
            JsonNode source = new ObjectMapper().readTree(root().resolve(ids[m] + "/source.json").toFile());
            byte[] mask = Base64.getDecoder().decode(Files.readAllBytes(root().resolve(ids[m] + "/walkmask.txt")));
            int cols = source.path("walkmaskColumns").asInt(), rows = source.path("walkmaskRows").asInt();
            double size = source.path("cellSize").asDouble();
            JsonNode w = source.path("worldRect"), e = source.path("effectiveWorldRect");
            double ox = w.path("x").asDouble(), oz = w.path("y").asDouble();
            double ex = e.path("x").asDouble(), ez = e.path("y").asDouble();
            double xmax = ex + e.path("width").asDouble(), zmax = ez + e.path("height").asDouble();
            boolean[] walkable = new boolean[cols * rows];
            Vec3[] centers = new Vec3[walkable.length];
            int count = 0;
            for (int bottom = 0; bottom < rows; bottom++) for (int col = 0; col < cols; col++) {
                int at = bottom * cols + col, bit = (rows - 1 - bottom) * cols + col;
                double left = Math.max(ox + col * size, ex), right = Math.min(ox + (col + 1) * size, xmax);
                double lower = Math.max(oz + bottom * size, ez), upper = Math.min(oz + (bottom + 1) * size, zmax);
                boolean expected = (mask[bit / 8] & (128 >> (bit % 8))) != 0;
                Vec3 point = new Vec3((lower + upper) / 2, (left + right) / 2, 0);
                centers[at] = point;
                if (left >= right || lower >= upper) {
                    assertThat(expected).as("填充区不能开放 %s:%s", ids[m], at).isFalse();
                    continue;
                }
                assertThat(grid.isWalkable(point)).as("%s col=%s bottomRow=%s", ids[m], col, bottom).isEqualTo(expected);
                walkable[at] = expected;
                if (expected) count++;
            }
            assertThat(count).as(ids[m]).isEqualTo(counts[m]);
            assertThat(grid.isWalkable(grid.spawn())).isTrue();
            assertThat(grid.isWalkable(new Vec3(grid.spawn().x(), xmax, 0))).isFalse();
            assertThat(grid.isWalkable(new Vec3(zmax, grid.spawn().y(), 0))).isFalse();
            assertThat(grid.isWalkable(new Vec3(ez - 0.001, ex, 0))).isFalse();
            List<Integer> representatives = new ArrayList<>();
            boolean[] seen = new boolean[walkable.length];
            for (int i = 0; i < walkable.length; i++) {
                if (!walkable[i] || seen[i]) continue;
                representatives.add(i);
                ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(i); seen[i] = true;
                while (!queue.isEmpty()) {
                    int at = queue.remove(), x = at % cols, y = at / cols;
                    for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int xx = x + step[0], yy = y + step[1], next = yy * cols + xx;
                        if (xx >= 0 && yy >= 0 && xx < cols && yy < rows && walkable[next] && !seen[next]) {
                            seen[next] = true; queue.add(next);
                        }
                    }
                }
            }
            assertThat(representatives).as(ids[m]).hasSize(components[m]);
            for (int i = 0; i < representatives.size(); i++) for (int j = i + 1; j < representatives.size(); j++) {
                SceneNavigation.Move move = grid.trace(centers[representatives.get(i)], centers[representatives.get(j)]);
                assertThat(move.blocked()).as("断开平台不可直穿 %s", ids[m]).isTrue();
                assertThat(grid.isWalkable(move.position())).isTrue();
                assertThat(grid.sameConnectedDomain(centers[representatives.get(i)], centers[representatives.get(j)]))
                        .as("断开平台不能借归队瞬移连接 %s", ids[m]).isFalse();
            }
        }
    }

    @Test
    void 非整格边界_有效裁切_负原点_行序_斜角均不可绕过() {
        CurrentMapNavigation grid = new CurrentMapNavigation(new byte[]{(byte) 0xfc}, new Vec3(21, -8, 0),
                3, 2, 2, -10, 20, -9.5, 20, -5.6, 22.4);
        assertThat(grid.isWalkable(new Vec3(22.3, -5.7, 0))).isTrue();
        assertThat(grid.isWalkable(new Vec3(22.4, -5.7, 0))).isFalse();
        assertThat(grid.isWalkable(new Vec3(21, -5.6, 0))).isFalse();
        assertThat(grid.isWalkable(new Vec3(21, -9.51, 0))).isFalse();
        for (Vec3 target : List.of(new Vec3(25, -8, 0), new Vec3(21, -4, 0), new Vec3(21, -10, 0))) {
            SceneNavigation.Move move = grid.trace(grid.spawn(), target);
            assertThat(move.blocked()).isTrue();
            assertThat(grid.isWalkable(move.position())).isTrue();
        }
        CurrentMapNavigation diagonal = new CurrentMapNavigation(new byte[]{(byte) 0x60}, new Vec3(1, 1, 0),
                2, 2, 2, 0, 0, 0, 0, 4, 4);
        assertThat(diagonal.isWalkable(new Vec3(3, 3, 0))).isTrue();
        assertThat(diagonal.trace(diagonal.spawn(), new Vec3(3, 3, 0)).blocked()).isTrue();
        assertThat(diagonal.sameConnectedDomain(diagonal.spawn(), new Vec3(3, 3, 0))).isFalse();
        CurrentMapNavigation corner = new CurrentMapNavigation(new byte[]{(byte) 0xd0}, new Vec3(3, 1, 0),
                2, 2, 2, 0, 0, 0, 0, 4, 4);
        assertThat(corner.trace(corner.spawn(), new Vec3(1, 3, 0)).blocked()).isTrue();
        assertThat(corner.sameConnectedDomain(corner.spawn(), new Vec3(1, 3, 0))).isTrue();
        assertThat(grid.trace(new Vec3(Double.NaN, 0, 0), grid.spawn()).position()).isEqualTo(grid.spawn());
        assertThat(grid.trace(grid.spawn(), new Vec3(Double.POSITIVE_INFINITY, 0, 0)).blocked()).isTrue();
    }

    @Test
    void 缺失_损坏_自洽改写source或mask_未知地图均拒绝(@TempDir Path temp) throws Exception {
        assertThatThrownBy(() -> FestivalMapNavigation.load(temp, "19004")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> FestivalMapNavigation.load(root(), "019004")).isInstanceOf(IllegalArgumentException.class);
        Path folder = Files.createDirectories(temp.resolve("19004"));
        byte[] source = Files.readAllBytes(root().resolve("19004/source.json"));
        byte[] mask = Files.readAllBytes(root().resolve("19004/walkmask.txt"));
        Files.write(folder.resolve("source.json"), source);
        mask[0] = 'B'; Files.write(folder.resolve("walkmask.txt"), mask);
        assertThatThrownBy(() -> FestivalMapNavigation.load(temp, "19004")).isInstanceOf(IllegalArgumentException.class);
        Files.write(folder.resolve("source.json"), (new String(source, java.nio.charset.StandardCharsets.UTF_8) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> FestivalMapNavigation.load(temp, "19004")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source.json SHA256");
        assertThatThrownBy(() -> new CurrentMapNavigation(new byte[]{(byte) 0xff}, new Vec3(1, 1, 0),
                2, 2, 2, 0, 0, 0, 0, 4, 4)).isInstanceOf(IllegalArgumentException.class);
    }
}
