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
 *       进场时当场判；之后位置变化按下文「重判」的节奏判（修基线缺口 3：基线只在换格那一刻判距离）；</li>
 *   <li><b>出视野</b>：三维距离 &gt; {@link #LEAVE_RADIUS}，或对方离开场景。进 10 m、出 20 m 构成滞回带，
 *       边界附近来回走不会反复 47 / 64（基线出视野靠「离开 7 个六边形格」，实际在 20–73 m 之间，随格子形状变化）；</li>
 *   <li><b>对称通知</b>：任何「W 的表加入 / 删除 T」都记进 {@link ViewChanges}，由调用方给 W 发 47 / 64
 *       （修基线缺口 1、2：基线只通知移动者本人，旁人留下残影或永远看不见新来者）；</li>
 *   <li>表满时不挤人（基线生产数据里只有 kNormal 一档优先级，满了就拒绝），被拒的一对等任一方下次重判再判。</li>
 * </ul>
 *
 * <p><b>重判</b>：位置变化的玩家登记进待刷新名单（{@link #moved}），{@link #refresh} 时：
 * <ul>
 *   <li><b>静止的人</b>（速度为零：刚停步、挂机停推、站着被挪位）立即按当前位置重判；</li>
 *   <li><b>移动中的人</b>相对上次重判的位置累计位移（三维）达到 {@link #REFRESH_DISTANCE} 才重判，不足的留在名单里等之后的刷新。</li>
 * </ul>
 * 代价：移动中的进 / 出视野最多晚约 1 m 位移（双方都在动时合计约 2 m；以 9 m/s 跑约晚 100 ms），相对 10 m 的滞回带可以忽略；
 * 停下来的那一刻一定按精确位置判一次，所以不会有「停在 9 m 外永远看不见」。换来的是人群里的重判次数从每人每帧一次
 * 降到约每走 1 m 一次（9 m/s 约每 3 帧、慢走更少）。是否换格不影响重判：候选总是按重判时所在格的 3×3 邻域取，
 * 出视野按距离判，与格子无关。
 *
 * <p><b>格子</b>：均匀方格（{@link GridIndex}，边长 {@link #CELL_SIZE} ≥ 视野半径，3×3 邻域），只做「进视野」的候选查询；
 * 出视野直接遍历兴趣列表判距离，不依赖格子形状。格子只是服务端内部表示，客户端看不到
 * （基线是 20 m 的平顶六边形、7 格邻域，换成方格不影响协议）。格子里存的是兴趣状态本身，候选不再按玩家查表。
 *
 * <p><b>开销</b>：每次重判 O(3×3 格内人数 + 兴趣列表长度)；建立兴趣先判容量再查重，人群里（表都满）每个候选只剩一次距离判断
 * 与两次容量判断。推迟的人每帧只做一次距离比较。帧内不新建集合：待刷新名单、候选缓冲、视野变化缓冲都复用
 * （去重靠兴趣状态上的标记），按条目取邻域不装箱（{@link GridIndex#collectAround}）；仅有的分配是遍历集合的迭代器。
 * 出生点人群基准见 {@code ViewCrowdBenchmarkTest}（默认跳过）。
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
    /** 移动中的人相对上次重判的位置累计位移（米，三维）达到它才重判（见类注释「重判」）。 */
    static final double REFRESH_DISTANCE = 1.0;
    private static final double REFRESH_DISTANCE_SQUARED = REFRESH_DISTANCE * REFRESH_DISTANCE;

    /**
     * 进场结果。
     *
     * @param seen   进场者看得见的实体（加进了进场者的表，给进场者发 47）
     * @param seers  看得见进场者的实体（进场者加进了它们的表，给它们发进场者的 21）
     */
    record Entered(List<ScenePlayer> seen, List<ScenePlayer> seers) {
    }

    private final int capacity;
    private final GridIndex<Interest> grid = new GridIndex<>(CELL_SIZE);
    private final Map<ScenePlayer, Interest> interests = new HashMap<>();
    /**
     * 位置变过、还没重判的玩家（按登记先后，每人至多一次：{@link Interest#pendingRefresh} 去重）。
     * 移动中累计位移不足 {@link #REFRESH_DISTANCE} 的人跨帧留在这里。
     */
    private final List<Interest> pendingRefresh = new ArrayList<>();
    /** 候选查询的复用缓冲，避免每次查询分配集合。 */
    private final List<Interest> candidates = new ArrayList<>();

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
        Interest mine = new Interest(entrant);
        interests.put(entrant, mine);
        Vec3 at = entrant.position();
        mine.evaluatedAt = at;
        grid.insert(mine, at.x(), at.y());

        List<ScenePlayer> seen = new ArrayList<>();
        List<ScenePlayer> seers = new ArrayList<>();
        candidates.clear();
        grid.collectAround(mine, candidates);
        for (int c = 0; c < candidates.size(); c++) {
            Interest theirs = candidates.get(c);
            if (theirs == mine || !theirs.player.position().within(at, VIEW_RADIUS)) {
                continue;
            }
            if (link(mine, theirs)) {
                seen.add(theirs.player);
            }
            if (link(theirs, mine)) {
                seers.add(theirs.player);
            }
        }
        candidates.clear();
        return new Entered(seen, seers);
    }

    /**
     * 玩家离开本场景：从格子、双方的兴趣列表与待刷新名单里彻底清掉（不留悬空引用），返回离开前看得见它的实体（51 的收件人）。
     * 离开者自己的兴趣列表静默清空（基线：离场者收不到 64 / 51）。不在本索引里返回空列表。
     */
    List<ScenePlayer> leave(ScenePlayer leaver) {
        Interest mine = interests.remove(leaver);
        if (mine == null) {
            return List.of();
        }
        grid.remove(mine);
        if (mine.pendingRefresh) {
            // 离开不在帧内热路径上；线性删除换来待刷新名单里永远只有在场的人。
            pendingRefresh.remove(mine);
            mine.pendingRefresh = false;
        }
        for (ScenePlayer target : mine.watching) {
            interests.get(target).watchedBy.remove(leaver);
        }
        List<ScenePlayer> watchers = new ArrayList<>(mine.watchedBy);
        for (ScenePlayer watcher : watchers) {
            interests.get(watcher).watching.remove(leaver);
        }
        return watchers;
    }

    /** 玩家位置已变：更新格子，并登记到之后的 {@link #refresh} 重判（还没重判前多次移动只登记一次）。 */
    void moved(ScenePlayer player) {
        Interest interest = interests.get(player);
        if (interest == null) {
            throw new IllegalStateException("玩家不在本场景的视野索引里: " + player.playerId());
        }
        Vec3 at = player.position();
        grid.move(interest, at.x(), at.y());
        if (!interest.pendingRefresh) {
            interest.pendingRefresh = true;
            pendingRefresh.add(interest);
        }
    }

    /**
     * 处理待刷新名单：移动中且累计位移不足 {@link #REFRESH_DISTANCE} 的留到之后（保持原先后），其余每人 E 重判——
     * 先按离开半径删掉 E 看得见的、看得见 E 的远端实体，再在 3×3 格里找视野半径内还没建立的兴趣（双向各判一次）。
     * 全部变化按观察者记进 {@code out}。
     */
    void refresh(ViewChanges out) {
        if (pendingRefresh.isEmpty()) {
            return;
        }
        // 下标遍历并原地压缩：推迟的人按原先后挪到前面。刷新期间名单不会变（这里不调 moved），也省掉迭代器。
        int kept = 0;
        for (int i = 0; i < pendingRefresh.size(); i++) {
            Interest mine = pendingRefresh.get(i);
            Vec3 at = mine.player.position();
            if (!mine.player.velocity().isOrigin()
                    && at.distanceSquared(mine.evaluatedAt) < REFRESH_DISTANCE_SQUARED) {
                pendingRefresh.set(kept++, mine);
                continue;
            }
            mine.pendingRefresh = false;
            mine.evaluatedAt = at;
            reevaluate(mine, at, out);
        }
        candidates.clear();
        // 从尾部删，每次 O(1)，也不建子列表视图。
        for (int last = pendingRefresh.size() - 1; last >= kept; last--) {
            pendingRefresh.remove(last);
        }
    }

    /** 按 {@code at} 重判 {@code mine} 的出视野与进视野（双向）。 */
    private void reevaluate(Interest mine, Vec3 at, ViewChanges out) {
        ScenePlayer mover = mine.player;
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
        grid.collectAround(mine, candidates);
        boolean mineFull = isFull(mine);
        for (int c = 0; c < candidates.size(); c++) {
            Interest theirs = candidates.get(c);
            // 双方的表都满时两个方向都建不了，距离不必算（人群里的常态）。
            if (theirs == mine || (mineFull && isFull(theirs))
                    || !theirs.player.position().within(at, VIEW_RADIUS)) {
                continue;
            }
            if (link(mine, theirs)) {
                out.added(mover, theirs.player);
                mineFull = isFull(mine);
            }
            if (link(theirs, mine)) {
                out.added(theirs.player, mover);
            }
        }
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

    /** 位置变过、还没重判的玩家数（含移动中累计位移不足、被推迟的）。 */
    int pendingMoves() {
        return pendingRefresh.size();
    }

    void clear() {
        grid.clear();
        interests.clear();
        pendingRefresh.clear();
        candidates.clear();
    }

    /**
     * 建立 watcher → target 的兴趣；watcher 的表已满或已存在返回 false。
     * 先判容量再查重：人群里表都满，省掉每个候选一次哈希查找；{@code add} 本身兼做查重。
     */
    private boolean link(Interest watcher, Interest target) {
        if (isFull(watcher) || !watcher.watching.add(target.player)) {
            return false;
        }
        target.watchedBy.add(watcher.player);
        return true;
    }

    private boolean isFull(Interest interest) {
        return interest.watching.size() >= capacity;
    }

    /** 一个玩家在本场景的兴趣状态。两张表只由本类改，且互为镜像：A ∈ watching(B) ⇔ B ∈ watchedBy(A)。 */
    private static final class Interest {

        final ScenePlayer player;
        final Set<ScenePlayer> watching = new LinkedHashSet<>();
        final Set<ScenePlayer> watchedBy = new LinkedHashSet<>();
        final Set<ScenePlayer> watchingView = Collections.unmodifiableSet(watching);
        final Set<ScenePlayer> watchedByView = Collections.unmodifiableSet(watchedBy);
        /** 已在待刷新名单里（名单去重用，避免每帧往哈希集合里插节点）。 */
        boolean pendingRefresh;
        /** 上次重判（或进场）时的位置：移动中的人相对它累计位移不足 {@link ViewIndex#REFRESH_DISTANCE} 就推迟重判。 */
        Vec3 evaluatedAt;

        Interest(ScenePlayer player) {
            this.player = player;
        }
    }
}
