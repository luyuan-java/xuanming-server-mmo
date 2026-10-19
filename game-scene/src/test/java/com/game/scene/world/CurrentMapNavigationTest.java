package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentMapNavigationTest {
    static Path navigationRoot() {
        return Files.isDirectory(Path.of("../config-data/navmesh/current_maps"))
                ? Path.of("../config-data/navmesh/current_maps") : Path.of("config-data/navmesh/current_maps");
    }

    @Test
    void 三图全部67500格与共享客户端遮罩逐位一致() throws Exception {
        for (String key : new String[]{"main", "island", "village"}) {
            CurrentMapNavigation navigation = CurrentMapNavigation.load(navigationRoot(), key);
            byte[] bits = Base64.getDecoder().decode(Files.readAllBytes(navigationRoot().resolve(key).resolve("walkmask.txt")));
            for (int row = 0; row < 150; row++) {
                for (int col = 0; col < 150; col++) {
                    int index = row * 150 + col;
                    boolean expected = (bits[index / 8] & (1 << (7 - index % 8))) != 0;
                    assertThat(navigation.isWalkable(new Vec3(299 - 2 * row, 51 + 2 * col, 0)))
                            .as("%s row=%s col=%s", key, row, col).isEqualTo(expected);
                }
            }
            assertThat(navigation.isWalkable(navigation.spawn())).isTrue();
            for (Vec3 outside : new Vec3[]{new Vec3(-0.1, 100, 0), new Vec3(300, 100, 0),
                    new Vec3(100, 49.9, 0), new Vec3(100, 350, 0), new Vec3(Double.NaN, 100, 0)}) {
                assertThat(navigation.isWalkable(outside)).isFalse();
            }
        }
    }

    @Test
    void 两端可走仍不能穿过中间墙_撞墙前退回并停步() {
        CurrentMapNavigation navigation = wallMap();
        Vec3 from = new Vec3(21, 69, 0), to = new Vec3(21, 73, 0);
        assertThat(navigation.isWalkable(to)).isTrue();
        SceneNavigation.Move result = navigation.trace(from, to);
        assertThat(result.blocked()).isTrue();
        assertThat(result.position().y()).isBetween(69.9, 70.0);
        assertThat(navigation.isWalkable(result.position())).isTrue();
        assertThat(navigation.trace(to, from).blocked()).isTrue();
    }

    @Test
    void 斜角不能从两个障碍之间穿过去_负零分量不漏查() {
        byte[] mask = fullMask();
        block(mask, 1, 0);
        block(mask, 0, 1);
        CurrentMapNavigation navigation = new CurrentMapNavigation(mask, new Vec3(1, 51, 0));
        assertThat(navigation.trace(new Vec3(1, 51, 0), new Vec3(3, 53, 0)).blocked()).isTrue();
        assertThat(navigation.trace(new Vec3(0.0, 51, 0), new Vec3(-0.0, 55, 0)).blocked()).isTrue();
    }

    @Test
    void 平面地图归零高度_非法旧位置回出生_离开边界被夹住() {
        CurrentMapNavigation navigation = new CurrentMapNavigation(fullMask(), new Vec3(1, 51, 0));
        assertThat(navigation.trace(new Vec3(21, 69, 3), new Vec3(25, 73, 9)).position())
                .isEqualTo(new Vec3(25, 73, 0));
        assertThat(navigation.velocity(new Vec3(3, 4, 5))).isEqualTo(new Vec3(3, 4, 0));
        assertThat(navigation.trace(new Vec3(999, 100, 0), new Vec3(25, 73, 0)).position())
                .isEqualTo(navigation.spawn());
        SceneNavigation.Move result = navigation.trace(new Vec3(299, 349, 0), new Vec3(310, 360, 0));
        assertThat(result.blocked()).isTrue();
        assertThat(navigation.isWalkable(result.position())).isTrue();
    }

    @Test
    void 合同缺失或遮罩被改动就拒绝加载(@TempDir Path root) throws Exception {
        assertThatThrownBy(() -> CurrentMapNavigation.load(root, "main")).isInstanceOf(RuntimeException.class);
        Path target = Files.createDirectory(root.resolve("main"));
        Files.copy(navigationRoot().resolve("main/source.json"), target.resolve("source.json"));
        byte[] bytes = Files.readAllBytes(navigationRoot().resolve("main/walkmask.txt"));
        bytes[0] = bytes[0] == 'A' ? (byte) 'B' : (byte) 'A';
        Files.write(target.resolve("walkmask.txt"), bytes);
        assertThatThrownBy(() -> CurrentMapNavigation.load(root, "main"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA256");
    }

    @Test
    void 旧无导航地图保留坐标高度及速度语义() {
        Vec3 to = new Vec3(999, -500, 30);
        assertThat(SceneNavigation.NONE.trace(Vec3.ORIGIN, to)).isEqualTo(new SceneNavigation.Move(to, false));
        assertThat(SceneNavigation.NONE.velocity(to)).isSameAs(to);
    }

    static byte[] fullMask() {
        byte[] mask = new byte[2813];
        Arrays.fill(mask, (byte) 0xff);
        mask[2812] = (byte) 0xf0;
        return mask;
    }

    static void block(byte[] mask, int col, int bottomRow) {
        int bit = (149 - bottomRow) * 150 + col;
        mask[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
    }

    static CurrentMapNavigation wallMap() {
        byte[] mask = fullMask();
        for (int row = 0; row < 150; row++) block(mask, 10, row);
        return new CurrentMapNavigation(mask, new Vec3(21, 69, 0));
    }
}
