package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GridIndexTest {

    private final GridIndex<String> grid = new GridIndex<>(20);

    @Test
    void 格子序号按floor取整_负坐标与格边界() {
        assertThat(grid.cellIndex(0)).isZero();
        assertThat(grid.cellIndex(19.999)).isZero();
        assertThat(grid.cellIndex(20)).as("恰在格边上归右边的格").isEqualTo(1);
        assertThat(grid.cellIndex(-0.0)).isZero();
        assertThat(grid.cellIndex(-0.001)).as("负坐标向下取整，不是向 0 取整").isEqualTo(-1);
        assertThat(grid.cellIndex(-20)).isEqualTo(-1);
        assertThat(grid.cellIndex(-20.001)).isEqualTo(-2);
        assertThat(grid.cellIndex(180)).isEqualTo(9);
    }

    @Test
    void 超大坐标夹在int范围内_邻格不溢出() {
        assertThat(grid.cellIndex(1e300)).isEqualTo(Integer.MAX_VALUE - 1);
        assertThat(grid.cellIndex(-1e300)).isEqualTo(Integer.MIN_VALUE + 1);

        grid.insert("far", 1e300, -1e300);
        List<String> near = new ArrayList<>();
        grid.collectNear(1e300, -1e300, near);
        assertThat(near).containsExactly("far");
    }

    @Test
    void 非有限坐标与非法格子边长直接拒绝() {
        assertThatThrownBy(() -> grid.insert("nan", Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grid.insert("inf", 0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(grid.size()).isZero();
        assertThatThrownBy(() -> new GridIndex<String>(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GridIndex<String>(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 插入_移动_删除() {
        grid.insert("a", 5, 5);
        grid.insert("b", 25, 5);
        assertThat(grid.size()).isEqualTo(2);
        assertThat(grid.occupiedCells()).isEqualTo(2);

        assertThat(grid.move("a", 15, 15)).as("同格内移动").isFalse();
        assertThat(grid.move("a", 30, 5)).as("换到 b 所在的格").isTrue();
        assertThat(grid.occupiedCells()).as("空出来的格立即回收").isEqualTo(1);

        List<String> near = new ArrayList<>();
        grid.collectNear(30, 5, near);
        assertThat(near).containsExactly("b", "a");

        assertThat(grid.remove("b")).isTrue();
        assertThat(grid.remove("b")).isFalse();
        assertThat(grid.contains("a")).isTrue();
        assertThat(grid.remove("a")).isTrue();
        assertThat(grid.size()).isZero();
        assertThat(grid.occupiedCells()).isZero();
    }

    @Test
    void 重复插入或移动不存在的条目是调用方bug() {
        grid.insert("a", 0, 0);
        assertThatThrownBy(() -> grid.insert("a", 1, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> grid.move("ghost", 1, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 邻域是3x3共9格_隔一格的不在内() {
        // 查询点在格 (0,0)。
        grid.insert("center", 10, 10);
        grid.insert("west", -10, 10);
        grid.insert("northEast", 30, 30);
        grid.insert("southWest", -0.5, -0.5);
        grid.insert("twoEast", 40, 10);
        grid.insert("twoSouth", 10, -20.5);

        List<String> near = new ArrayList<>();
        grid.collectNear(10, 10, near);

        assertThat(near).containsExactlyInAnyOrder("center", "west", "northEast", "southWest");
    }

    @Test
    void 邻域结果按固定格序与格内插入顺序_追加而不清空() {
        grid.insert("b", 5, 5);
        grid.insert("a", 6, 6);
        grid.insert("w", -5, 5);
        List<String> near = new ArrayList<>(List.of("existing"));

        grid.collectNear(5, 5, near);

        // (dx,dy) 从 (-1,-1) 到 (1,1)：先西边一列，再本列；格内按插入顺序。
        assertThat(near).containsExactly("existing", "w", "b", "a");
    }

    @Test
    void 按条目取邻域_与按坐标取邻域同序同内容_含自己() {
        grid.insert("b", 5, 5);
        grid.insert("a", 6, 6);
        grid.insert("w", -5, 5);
        grid.insert("far", 45, 5);

        List<String> around = new ArrayList<>();
        grid.collectAround("a", around);
        List<String> near = new ArrayList<>();
        grid.collectNear(6, 6, near);

        assertThat(around).containsExactly("w", "b", "a").isEqualTo(near);
        assertThatThrownBy(() -> grid.collectAround("ghost", new ArrayList<>()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 格子反复建出与回收之后_邻格引用仍与按坐标查表一致() {
        // 随机插入 / 移动 / 删除，逼出「邻格先回收再重建」「一格回收时两侧邻格都要摘掉引用」等情形，
        // 每一步都拿按条目取邻域（走邻格引用）和按坐标查表对照。
        Random random = new Random(20260930L);
        GridIndex<Integer> index = new GridIndex<>(20);
        double[][] at = new double[40][];
        for (int step = 0; step < 20_000; step++) {
            int item = random.nextInt(at.length);
            double x = (random.nextDouble() - 0.5) * 200;
            double y = (random.nextDouble() - 0.5) * 200;
            if (at[item] == null) {
                index.insert(item, x, y);
                at[item] = new double[] {x, y};
            } else if (random.nextInt(4) == 0) {
                assertThat(index.remove(item)).isTrue();
                at[item] = null;
                continue;
            } else {
                index.move(item, x, y);
                at[item][0] = x;
                at[item][1] = y;
            }
            assertSameNeighborhood(index, item, at[item], step);
            if (step % 97 == 0) {
                for (int other = 0; other < at.length; other++) {
                    if (at[other] != null) {
                        assertSameNeighborhood(index, other, at[other], step);
                    }
                }
            }
        }
    }

    private static void assertSameNeighborhood(GridIndex<Integer> index, int item, double[] at, int step) {
        List<Integer> around = new ArrayList<>();
        index.collectAround(item, around);
        List<Integer> near = new ArrayList<>();
        index.collectNear(at[0], at[1], near);
        assertThat(around).as("第 %s 步，条目 %s", step, item).isEqualTo(near);
    }

    @Test
    void 边长不小于半径时_半径内的点一定在邻域里() {
        Random random = new Random(20260930L);
        double radius = 20;
        for (int i = 0; i < 20_000; i++) {
            double cx = (random.nextDouble() - 0.5) * 2_000;
            double cy = (random.nextDouble() - 0.5) * 2_000;
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = random.nextDouble() * radius;
            GridIndex<Integer> index = new GridIndex<>(radius);
            index.insert(i, cx + Math.cos(angle) * distance, cy + Math.sin(angle) * distance);
            List<Integer> near = new ArrayList<>();
            index.collectNear(cx, cy, near);
            assertThat(near).as("中心 (%s,%s) 距离 %s", cx, cy, distance).containsExactly(i);
        }
    }
}
