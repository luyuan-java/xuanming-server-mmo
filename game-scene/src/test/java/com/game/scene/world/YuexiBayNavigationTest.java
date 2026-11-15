package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

@EnabledIf("com.game.scene.world.CurrentMapNavigationTest#yuexiDataPresent")
class YuexiBayNavigationTest {
    private static final int COLUMNS = 173;
    private static final int ROWS = 144;
    private static final List<Vec3> LANDMARKS = List.of(
            fromPixel(272, 336), fromPixel(204, 2564), fromPixel(1912, 940),
            fromPixel(1264, 2316), fromPixel(2036, 2840), fromPixel(2936, 2308),
            fromPixel(3316, 3044), fromPixel(4220, 1960), fromPixel(3424, 3564),
            fromPixel(4308, 904), fromPixel(1236, 3660));
    private static final List<Vec3> BLOCKED = List.of(
            fromPixel(5400, 2256), fromPixel(4324, 3000), fromPixel(4580, 2068),
            fromPixel(3884, 3740), fromPixel(1040, 3896), fromPixel(2120, 1204),
            fromPixel(2724, 3752), fromPixel(2780, 1560), fromPixel(3120, 2540),
            fromPixel(1876, 3172), fromPixel(848, 2148), fromPixel(4228, 536),
            fromPixel(4780, 724), fromPixel(5960, 2800));

    @Test
    void 正式月汐湾全部栅格与客户端位序一致且入口两座码头和镇内地标连通() throws Exception {
        Path root = CurrentMapNavigationTest.navigationRoot();
        Path source = root.resolve(CurrentMapNavigation.YUEXI_FOLDER);
        CurrentMapNavigation grid = CurrentMapNavigation.loadYuexi(root);
        byte[] mask = Base64.getDecoder().decode(Files.readAllBytes(source.resolve("walkmask.txt")));
        int count = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int at = row * COLUMNS + col;
                boolean expected = (mask[at / 8] & (0x80 >>> (at % 8))) != 0;
                Vec3 center = new Vec3(72 - (row + 0.5) / 2, (col + 0.5) / 2, 0);
                assertThat(grid.isWalkable(center)).as("row=%s col=%s", row, col).isEqualTo(expected);
                if (expected) count++;
            }
        }
        assertThat(count).isEqualTo(952);
        assertThat(LANDMARKS).allSatisfy(point -> {
            assertThat(grid.isWalkable(point)).as("地标 %s", point).isTrue();
            assertThat(grid.sameConnectedDomain(grid.spawn(), point)).as("地标 %s", point).isTrue();
        });
        assertThat(BLOCKED).allSatisfy(point -> assertThat(grid.isWalkable(point)).as("禁行点 %s", point).isFalse());
        assertThat(grid.isWalkable(new Vec3(30, 72, 0))).isFalse();
        for (int i = 0; i < 2; i++) {
            Vec3 pier = LANDMARKS.get(7 + i), boat = BLOCKED.get(2 + i);
            SceneNavigation.Move move = grid.trace(pier, boat);
            assertThat(move.blocked()).isTrue();
            assertThat(grid.isWalkable(move.position())).isTrue();
        }
    }

    @Test
    void 版本化合同缺失和遮罩篡改都拒绝装配(@TempDir Path root) throws Exception {
        assertThatThrownBy(() -> CurrentMapNavigation.loadYuexi(root)).isInstanceOf(RuntimeException.class);
        Path original = CurrentMapNavigationTest.navigationRoot().resolve(CurrentMapNavigation.YUEXI_FOLDER);
        Path folder = Files.createDirectories(root.resolve(CurrentMapNavigation.YUEXI_FOLDER));
        Files.copy(original.resolve("source.json"), folder.resolve("source.json"));
        byte[] text = Files.readAllBytes(original.resolve("walkmask.txt"));
        text[0] = text[0] == 'A' ? (byte) 'B' : (byte) 'A';
        Files.write(folder.resolve("walkmask.txt"), text);
        assertThatThrownBy(() -> CurrentMapNavigation.loadYuexi(root))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA256");
        Path source = folder.resolve("source.json");
        Files.writeString(source, Files.readString(source) + "\n");
        assertThatThrownBy(() -> CurrentMapNavigation.loadYuexi(root))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source.json SHA256");
    }

    private static Vec3 fromPixel(int x, int y) {
        return new Vec3(72 - y * 72.0 / 5120, x * 72.0 / 5120, 0);
    }
}
