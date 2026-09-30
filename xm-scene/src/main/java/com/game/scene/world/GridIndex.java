package com.game.scene.world;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 均匀方格空间索引（只看 x / y，z 不参与）。只在场景逻辑线程上使用，不加锁。
 *
 * <p>格子 {@code (cx, cy) = (floor(x / cellSize), floor(y / cellSize))}。邻域是所在格及其 8 个邻格（3×3 共 9 格）：
 * 只要 {@code cellSize} 不小于查询半径，与该点水平距离不超过查询半径的条目一定在这 9 格里
 * （格边界的浮点舍入另见 {@link ViewIndex#CELL_SIZE} 的取值说明）。
 *
 * <p>契约：
 * <ul>
 *   <li>坐标必须有限：非有限值说明上游校验漏了，抛 {@link IllegalArgumentException}（坐标由移动校验保证有限）；</li>
 *   <li>同一条目只能在索引里出现一次，重复插入 / 移动或删除不存在的条目都是调用方的 bug，抛 {@link IllegalStateException}；</li>
 *   <li>同一格内按插入顺序迭代，9 格按 (dx, dy) 从 (-1,-1) 到 (1,1) 的固定顺序，结果可复现；</li>
 *   <li>空格子立即回收，玩家满地图跑也不会让格子表无限增长；</li>
 *   <li>条目按 {@code T} 的 {@code equals / hashCode} 登记，调用方要保证它们在索引里期间不变。</li>
 * </ul>
 *
 * <p><b>开销</b>：每个格子持有 3×3 邻域里现存格子的引用（建格时互相挂上、回收时互相摘掉），所以帧内热路径
 * {@link #collectAround}（按条目查邻域）是 9 次数组访问，不按坐标查表、不装箱，结果写进调用方复用的集合。
 * 按坐标查格要以 {@code long} 键查表（装箱），只发生在插入、换格与 {@link #collectNear} 上。
 * 插入 / 移动 / 删除 O(1)（建格、回收格各多 8 次查表 / 引用更新）。
 */
final class GridIndex<T> {

    /** 3×3 邻域内第 k 格（k = (dx+1)·3 + (dy+1)）相对本格的偏移；k = 4 是本格，k 与 8 − k 互为反方向。 */
    private static final int[] NEIGHBOR_DX = {-1, -1, -1, 0, 0, 0, 1, 1, 1};
    private static final int[] NEIGHBOR_DY = {-1, 0, 1, -1, 0, 1, -1, 0, 1};
    private static final int SELF = 4;
    private static final int LAST = NEIGHBOR_DX.length - 1;

    private final double cellSize;
    private final Map<Long, Cell<T>> cells = new HashMap<>();
    private final Map<T, Cell<T>> cellOf = new HashMap<>();

    GridIndex(double cellSize) {
        if (!(cellSize > 0) || !Double.isFinite(cellSize)) {
            throw new IllegalArgumentException("格子边长必须为正的有限值: " + cellSize);
        }
        this.cellSize = cellSize;
    }

    double cellSize() {
        return cellSize;
    }

    /** 坐标所在格的序号。超出 int 范围的坐标夹到 ±(2^31 - 2)，保证 ±1 的邻格不溢出。 */
    int cellIndex(double coordinate) {
        requireFinite(coordinate);
        double index = Math.floor(coordinate / cellSize);
        return (int) Math.max(Integer.MIN_VALUE + 1, Math.min(Integer.MAX_VALUE - 1, index));
    }

    static long cellKey(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xFFFF_FFFFL);
    }

    void insert(T item, double x, double y) {
        if (cellOf.containsKey(item)) {
            throw new IllegalStateException("条目已在格子索引里: " + item);
        }
        Cell<T> cell = cellAt(cellIndex(x), cellIndex(y));
        cell.items.add(item);
        cellOf.put(item, cell);
    }

    /**
     * 条目移动到新坐标。返回是否换了格子（同格内移动什么都不改）。
     */
    boolean move(T item, double x, double y) {
        Cell<T> from = cellOf.get(item);
        if (from == null) {
            throw new IllegalStateException("条目不在格子索引里: " + item);
        }
        int cx = cellIndex(x);
        int cy = cellIndex(y);
        if (from.cx == cx && from.cy == cy) {
            return false;
        }
        detach(item, from);
        Cell<T> to = cellAt(cx, cy);
        to.items.add(item);
        cellOf.put(item, to);
        return true;
    }

    /** 删除条目；不在索引里返回 false。 */
    boolean remove(T item) {
        Cell<T> cell = cellOf.remove(item);
        if (cell == null) {
            return false;
        }
        detach(item, cell);
        return true;
    }

    boolean contains(T item) {
        return cellOf.containsKey(item);
    }

    int size() {
        return cellOf.size();
    }

    /** 当前非空格子数（空格子会被立即回收）。 */
    int occupiedCells() {
        return cells.size();
    }

    /**
     * 帧内热路径：把 {@code item} 所在格及 8 个邻格里的全部条目（含它自己）追加到 {@code out}（不先清空）。
     * 条目必须在索引里，否则是调用方的 bug，抛 {@link IllegalStateException}。
     */
    void collectAround(T item, Collection<? super T> out) {
        Cell<T> cell = cellOf.get(item);
        if (cell == null) {
            throw new IllegalStateException("条目不在格子索引里: " + item);
        }
        for (Cell<T> near : cell.around) {
            if (near != null) {
                // 逐个 add：ArrayList.addAll 会先 toArray 拷一份。
                for (T each : near.items) {
                    out.add(each);
                }
            }
        }
    }

    /** 把任意点 (x, y) 所在格及 8 个邻格里的全部条目追加到 {@code out}（不先清空）。按坐标查 9 次表，不在帧内热路径上用。 */
    void collectNear(double x, double y, Collection<? super T> out) {
        int cx = cellIndex(x);
        int cy = cellIndex(y);
        for (int k = 0; k <= LAST; k++) {
            Cell<T> cell = cells.get(cellKey(cx + NEIGHBOR_DX[k], cy + NEIGHBOR_DY[k]));
            if (cell != null) {
                for (T each : cell.items) {
                    out.add(each);
                }
            }
        }
    }

    void clear() {
        cells.clear();
        cellOf.clear();
    }

    /** 取格子；不存在就建出来，并与 3×3 邻域里现存的格子互相挂上。 */
    private Cell<T> cellAt(int cx, int cy) {
        Cell<T> cell = cells.get(cellKey(cx, cy));
        if (cell != null) {
            return cell;
        }
        cell = new Cell<>(cx, cy);
        for (int k = 0; k <= LAST; k++) {
            if (k == SELF) {
                continue;
            }
            Cell<T> neighbor = cells.get(cellKey(cx + NEIGHBOR_DX[k], cy + NEIGHBOR_DY[k]));
            if (neighbor != null) {
                cell.around[k] = neighbor;
                neighbor.around[LAST - k] = cell;
            }
        }
        cells.put(cellKey(cx, cy), cell);
        return cell;
    }

    /** 从格子里摘掉条目；格子空了就回收，并从邻格的邻域引用里摘掉自己（不留指向已回收格子的引用）。 */
    private void detach(T item, Cell<T> cell) {
        cell.items.remove(item);
        if (!cell.items.isEmpty()) {
            return;
        }
        cells.remove(cellKey(cell.cx, cell.cy));
        for (int k = 0; k <= LAST; k++) {
            Cell<T> neighbor = cell.around[k];
            if (k != SELF && neighbor != null) {
                neighbor.around[LAST - k] = null;
            }
        }
    }

    private static void requireFinite(double coordinate) {
        if (!Double.isFinite(coordinate)) {
            throw new IllegalArgumentException("格子索引的坐标必须有限: " + coordinate);
        }
    }

    private static final class Cell<T> {

        final int cx;
        final int cy;
        final LinkedHashSet<T> items = new LinkedHashSet<>();
        /** 3×3 邻域里现存的格子，下标同 {@link #NEIGHBOR_DX}；本格在 {@link #SELF}，不存在的邻格为 null。 */
        final Cell<T>[] around;

        @SuppressWarnings("unchecked")
        Cell(int cx, int cy) {
            this.cx = cx;
            this.cy = cy;
            this.around = (Cell<T>[]) new Cell<?>[LAST + 1];
            around[SELF] = this;
        }
    }
}
