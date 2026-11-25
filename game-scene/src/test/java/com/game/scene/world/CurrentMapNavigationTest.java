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
    static Path configRoot() {
        String override = System.getProperty("game.currentMapsTestConfigDir");
        if (override != null) return Path.of(override);
        // surefire 的工作目录是模块目录；从仓库根目录跑时也能找到。
        return Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data") : Path.of("config-data");
    }

    static Path navigationRoot() {
        return configRoot().resolve("navmesh/current_maps");
    }

    static boolean yuexiDataPresent() {
        return System.getProperty("game.currentMapsTestConfigDir") != null
                || Files.isRegularFile(navigationRoot().resolve(CurrentMapNavigation.YUEXI_FOLDER).resolve("source.json"));
    }

    @Test
    void 新版三张正式主世界导航可加载且出生点与外域阻挡一致() {
        CurrentMapNavigation biyuan = CurrentMapNavigation.loadVersionedCurrentMap(
                navigationRoot(), CurrentMapNavigation.BIYUAN_FOLDER, 5);
        assertThat(biyuan.isWalkable(biyuan.spawn())).isTrue();
        assertThat(biyuan.isWalkable(new Vec3(186.0, 116.25, 0))).isTrue();
        assertThat(biyuan.isWalkable(new Vec3(252.0, 160.0, 0))).isFalse();

        CurrentMapNavigation bajing = CurrentMapNavigation.loadVersionedCurrentMap(
                navigationRoot(), CurrentMapNavigation.BAJING_FOLDER, 2);
        assertThat(bajing.isWalkable(bajing.spawn())).isTrue();
        assertThat(bajing.isWalkable(new Vec3(200.41666666666666, 166.66666666666669, 0))).isTrue();
        assertThat(bajing.isWalkable(new Vec3(198.33333333333334, -12.5, 0))).isFalse();

        CurrentMapNavigation yuexi = CurrentMapNavigation.loadYuexi(navigationRoot());
        assertThat(yuexi.isWalkable(yuexi.spawn())).isTrue();
        assertThat(yuexi.isWalkable(new Vec3(Double.NaN, 100, 0))).isFalse();
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
        assertThatThrownBy(() -> CurrentMapNavigation.loadVersionedCurrentMap(
                root, CurrentMapNavigation.BIYUAN_FOLDER, 5)).isInstanceOf(RuntimeException.class);
        Path target = Files.createDirectories(root.resolve(CurrentMapNavigation.BIYUAN_FOLDER));
        Files.copy(navigationRoot().resolve(CurrentMapNavigation.BIYUAN_FOLDER).resolve("source.json"),
                target.resolve("source.json"));
        byte[] bytes = Files.readAllBytes(navigationRoot().resolve(CurrentMapNavigation.BIYUAN_FOLDER).resolve("walkmask.txt"));
        bytes[0] = bytes[0] == 'A' ? (byte) 'B' : (byte) 'A';
        Files.write(target.resolve("walkmask.txt"), bytes);
        assertThatThrownBy(() -> CurrentMapNavigation.loadVersionedCurrentMap(
                root, CurrentMapNavigation.BIYUAN_FOLDER, 5))
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
