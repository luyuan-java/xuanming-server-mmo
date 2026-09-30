package com.game.scene.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个场景实例的视野（AOI）：格子索引 + 每个玩家的兴趣列表。只在场景逻辑线程上使用，不加锁。
 *
 * <p><b>模型</b>（契约文档 {@code docs/reference/mmorpg-client-contract-aoi.md} §2 / §9）：
 * <ul>
 *   <li>兴趣是<b>单向</b>的：{@code watching(W)} 是 W 看得见的实体（客户端显示的就是它），{@code watchers(T)} 是看得见 T 的实体
 *       （反向索引，66 / 70 / 51 的收件人）。两张表永远互为镜像；</li>
 *   <li><b>进视野</b>：双方同场景、三维距离 ≤ {@link #VIEW_RADIUS}（含等号）、观察者的表未满（{@link #DEFAULT_CAPACITY}）。
 *       任一方位置变化后的下一次 {@link #refresh} 都重新检查（修基线缺口 3：基线只在换格那一刻判距离）；</li>
 *   <li><b>出视野</b>：三维距离 &gt; {@link #LEAVE_RADIUS}，或对方离开场景。进 10 m、出 20 m 构成滞回带，
 *       边界附近来回走不会反复 47 / 64（基线出视野靠「离开 7 个六边形格」，实际在 20–73 m 之间，随格子形状变化）；</li>
 *   <li><b>对称通知</b>：任何「W 的表加入 / 删除 T」都记进 {@link ViewChanges}，由调用方给 W 发 47 / 64
 *       （修基线缺口 1、2：基线只通知移动者本人，旁人留下残影或永远看不见新来者）；</li>
 *   <li>表满时不挤人（基线生产数据里只有 kNormal 一档优先级，满了就拒绝），被拒的一对等任一方下次移动再判。</li>
 * </ul>
 *
 * <p><b>格子</b>：均匀方格（{@link GridIndex}），只做「进视野」的候选查询；出视野直接遍历兴趣列表判距离，不依赖格子形状。
 * 格子只是服务端内部表示，客户端看不到（基线是 20 m 的平顶六边形，换成方格不影响协议）。
 *
 * <p><b>开销</b>：每次 {@link #refresh} 只处理上次刷新以来移动过的玩家；每个移动者 O(3×3 格内人数 + 兴趣列表长度)。
 * 没人移动时什么都不做、不分配。
 */
final class ViewIndex {

    /** 视野半径（基线 {@code ViewRadius.radius = 10}，进场后写死，不来自配置表）。三维距离，含等号。 */
    static final double VIEW_RADIUS = 10.0;
    /** 离开半径：距离超过它才出视野（滞回带下沿，契约文档 §9「进 ≤ 10 m，出 ≥ 20 m」）。 */
    static final double LEAVE_RADIUS = 20.0;
    /**
     * 格子边长。必须不小于 {@link #VIEW_RADIUS}，3×3 邻域才一定覆盖视野圆；取 2 倍而不是恰好相等，是为了远离
     * 「两人恰好相距 10 m、除法舍入把其中一人分到隔一格」的浮点边界。与基线六边形格同尺度（size = 20 m）。
     */
    static final double CELL_SIZE = 2 * VIEW_RADIUS;
    /** 兴趣列表容量（基线 {@code kAoiListCapacityDefault}；生产数据里没人挂容量 / 压力组件，实际就是 100）。 */
    static final int DEFAULT_CAPACITY = 100;

    /**
     * 进场结果。
     *
     * @param seen   进场者看得见的实体（加进了进场者的表，给进场者发 47）
     * @param seers  看得见进场者的实体（进场者加进了它们的表，给它们发进场者的 21）
     */
    record Entered(List<ScenePlayer> seen, List<ScenePlayer> seers) {
    }

    private final int capacity;
    private final GridIndex<ScenePlayer> grid = new GridIndex<>(CELL_SIZE);
    private final Map<ScenePlayer, Interest> interests = new HashMap<>();
    /** 上次刷新以来位置变过的玩家（按先后）。 */
    private final Set<ScenePlayer> moved = new LinkedHashSet<>();
    /** 候选查询的复用缓冲，避免每次查询分配集合。 */
    private final List<ScenePlayer> candidates = new ArrayList<>();

    ViewIndex() {
        this(DEFAULT_CAPACITY);
    }

    ViewIndex(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("兴趣列表容量必须为正数: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * 玩家进入本场景：放进格子，按当前位置与视野内的人互相建立兴趣（能建的都建，受各自容量限制）。
     * 进场本身的下行（47 给进场者、21 给旁人）由调用方按返回值当场发出，不经过 {@link #refresh}。
     */
    Entered enter(ScenePlayer entrant) {
        if (interests.containsKey(entrant)) {
            throw new IllegalStateException("玩家已在本场景的视野索引里: " + entrant.playerId());
        }
        Interest mine = new Interest();
        interests.put(entrant, mine);
        Vec3 at = entrant.position();
        grid.insert(entrant, at.x(), at.y());

        List<ScenePlayer> seen = new ArrayList<>();
        List<ScenePlayer> seers = new ArrayList<>();
        candidates.clear();
        grid.collectNear(at.x(), at.y(), candidates);
        for (ScenePlayer other : candidates) {
            if (other == entrant || !other.position().within(at, VIEW_RADIUS)) {
                continue;
            }
            Interest theirs = interests.get(other);
            if (link(entrant, mine, other, theirs)) {
                seen.add(other);
            }
            if (link(other, theirs, entrant, mine)) {
                seers.add(other);
            }
        }
        candidates.clear();
        return new Entered(seen, seers);
    }

    /**
     * 玩家离开本场景：从格子和双方的兴趣列表里彻底清掉（不留悬空引用），返回离开前看得见它的实体（51 的收件人）。
     * 离开者自己的兴趣列表静默清空（基线：离场者收不到 64 / 51）。不在本索引里返回空列表。
     */
    List<ScenePlayer> leave(ScenePlayer leaver) {
        Interest mine = interests.remove(leaver);
        if (mine == null) {
            return List.of();
        }
        grid.remove(leaver);
        moved.remove(leaver);
        for (ScenePlayer target : mine.watching) {
            interests.get(target).watchedBy.remove(leaver);
        }
        List<ScenePlayer> watchers = new ArrayList<>(mine.watchedBy);
        for (ScenePlayer watcher : watchers) {
            interests.get(watcher).watching.remove(leaver);
        }
        return watchers;
    }

    /** 玩家位置已变：更新格子，并登记到下一次 {@link #refresh} 重新判定视野。 */
    void moved(ScenePlayer player) {
        Vec3 at = player.position();
        grid.move(player, at.x(), at.y());
        moved.add(player);
    }

    /**
     * 对上次刷新以来移动过的每个玩家 E：先按离开半径删掉 E 看得见的、看得见 E 的远端实体，再在 3×3 格里找视野半径内
     * 还没建立的兴趣（双向各判一次）。全部变化按观察者记进 {@code out}。
     */
    void refresh(ViewChanges out) {
        if (moved.isEmpty()) {
            return;
        }
        for (ScenePlayer mover : moved) {
            Interest mine = interests.get(mover);
            Vec3 at = mover.position();
            for (Iterator<ScenePlayer> it = mine.watching.iterator(); it.hasNext(); ) {
                ScenePlayer target = it.next();
                if (!target.position().within(at, LEAVE_RADIUS)) {
                    it.remove();
                    interests.get(target).watchedBy.remove(mover);
                    out.removed(mover, target);
                }
            }
            for (Iterator<ScenePlayer> it = mine.watchedBy.iterator(); it.hasNext(); ) {
                ScenePlayer watcher = it.next();
                if (!watcher.position().within(at, LEAVE_RADIUS)) {
                    it.remove();
                    interests.get(watcher).watching.remove(mover);
                    out.removed(watcher, mover);
                }
            }
            candidates.clear();
            grid.collectNear(at.x(), at.y(), candidates);
            for (ScenePlayer other : candidates) {
                if (other == mover || !other.position().within(at, VIEW_RADIUS)) {
                    continue;
                }
                Interest theirs = interests.get(other);
                if (link(mover, mine, other, theirs)) {
                    out.added(mover, other);
                }
                if (link(other, theirs, mover, mine)) {
                    out.added(other, mover);
                }
            }
        }
        candidates.clear();
        moved.clear();
    }

    /** 看得见 {@code target} 的实体（只读视图，按建立先后）。不在本索引里返回空集。 */
    Set<ScenePlayer> watchers(ScenePlayer target) {
        Interest interest = interests.get(target);
        return interest == null ? Set.of() : interest.watchedByView;
    }

    /** {@code watcher} 看得见的实体（只读视图，按建立先后）。不在本索引里返回空集。 */
    Set<ScenePlayer> watching(ScenePlayer watcher) {
        Interest interest = interests.get(watcher);
        return interest == null ? Set.of() : interest.watchingView;
    }

    boolean contains(ScenePlayer player) {
        return interests.containsKey(player);
    }

    int size() {
        return interests.size();
    }

    /** 还没被 {@link #refresh} 处理的移动者数。 */
    int pendingMoves() {
        return moved.size();
    }

    void clear() {
        grid.clear();
        interests.clear();
        moved.clear();
        candidates.clear();
    }

    /** 建立 watcher → target 的兴趣；已存在或 watcher 的表已满返回 false。 */
    private boolean link(ScenePlayer watcher, Interest watcherInterest, ScenePlayer target, Interest targetInterest) {
        if (watcherInterest.watching.contains(target) || watcherInterest.watching.size() >= capacity) {
            return false;
        }
        watcherInterest.watching.add(target);
        targetInterest.watchedBy.add(watcher);
        return true;
    }

    /** 一个玩家的兴趣状态。两张表只由本类改，且互为镜像：A ∈ watching(B) ⇔ B ∈ watchedBy(A)。 */
    private static final class Interest {

        final Set<ScenePlayer> watching = new LinkedHashSet<>();
        final Set<ScenePlayer> watchedBy = new LinkedHashSet<>();
        final Set<ScenePlayer> watchingView = Collections.unmodifiableSet(watching);
        final Set<ScenePlayer> watchedByView = Collections.unmodifiableSet(watchedBy);
    }
}
