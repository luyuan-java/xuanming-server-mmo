package com.game.scene.world;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 均匀方格空间索引（只看 x / y，z 不参与）。只在场景逻辑线程上使用，不加锁。
 *
 * <p>格子 {@code (cx, cy) = (floor(x / cellSize), floor(y / cellSize))}。{@link #collectNear} 取某点所在格及其 8 个邻格
 * （3×3 共 9 格）里的全部条目：只要 {@code cellSize} 不小于查询半径，与该点水平距离不超过查询半径的条目一定在这 9 格里
 * （格边界的浮点舍入另见 {@link ViewIndex#CELL_SIZE} 的取值说明）。
 *
 * <p>契约：
 * <ul>
 *   <li>坐标必须有限：非有限值说明上游校验漏了，抛 {@link IllegalArgumentException}（坐标由移动校验保证有限）；</li>
 *   <li>同一条目只能在索引里出现一次，重复插入 / 移动或删除不存在的条目都是调用方的 bug，抛 {@link IllegalStateException}；</li>
 *   <li>同一格内按插入顺序迭代，9 格按 (dx, dy) 从 (-1,-1) 到 (1,1) 的固定顺序，结果可复现；</li>
 *   <li>空格子立即回收，玩家满地图跑也不会让格子表无限增长；</li>
 *   <li>条目按引用相等（{@code T} 的 {@code equals / hashCode}）登记，调用方要保证它们在索引里期间不变。</li>
 * </ul>
 *
 * <p>开销：插入 / 移动 / 删除 O(1)；{@link #collectNear} 为 9 次格子查找 + 结果条目数，结果写进调用方复用的集合，
 * 不为每次查询分配新集合。格子键是 {@code long}，查表时装箱（小对象、短命）。
 */
final class GridIndex<T> {

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

    /** 把 (x, y) 所在格及 8 个邻格里的全部条目追加到 {@code out}（不先清空 {@code out}）。 */
    void collectNear(double x, double y, Collection<? super T> out) {
        int cx = cellIndex(x);
        int cy = cellIndex(y);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                Cell<T> cell = cells.get(cellKey(cx + dx, cy + dy));
                if (cell != null) {
                    // 逐个 add：ArrayList.addAll 会先 toArray 拷一份，这是每 tick 的热路径。
                    for (T item : cell.items) {
                        out.add(item);
                    }
                }
            }
        }
    }

    void clear() {
        cells.clear();
        cellOf.clear();
    }

    private Cell<T> cellAt(int cx, int cy) {
        return cells.computeIfAbsent(cellKey(cx, cy), k -> new Cell<>(cx, cy));
    }

    private void detach(T item, Cell<T> cell) {
        cell.items.remove(item);
        if (cell.items.isEmpty()) {
            cells.remove(cellKey(cell.cx, cell.cy));
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

        Cell(int cx, int cy) {
            this.cx = cx;
            this.cy = cy;
        }
    }
}
