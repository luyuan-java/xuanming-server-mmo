package com.game.scene.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一个场景实例的视野（AOI）：格子索引 + 有向两张索引（观察者→目标、目标→观察者）。只在场景逻辑线程上使用，不加锁。
 *
 * <p><b>模型</b>（契约文档 {@code docs/reference/mmorpg-client-contract-aoi.md} §2 / §9 与 PARITY 2026-11-06 AOI 单向关注）：
 * <ul>
 *   <li>兴趣是<b>单向</b>的：{@code watching(W)} 是 W 客户端正在显示的实体，{@code watchers(T)} 是正在显示 T 的观察者
 *       （反向索引，移动/属性/技能广播的收件人）。两张表永远互为镜像；</li>
 *   <li>一条观察关系区分<b>空间来源</b>（网格邻近且非隐身）与<b>显式来源</b>（玩法登记，身份 = {@code kind + instanceId}，
 *       可并存多份，可赋予高于普通空间关注的权重及侦测隐身授权 {@code detectsStealth}），客户端永远只有一份可见状态；</li>
 *   <li>网格外的显式关注者同样在 {@code watchers(T)} 中收到广播，且目标不因此看到关注者；</li>
 *   <li>离场 / 换场景 / 销毁按两张索引与显式反向索引 O(度) 清理，不扫全节点；</li>
 *   <li>隐身开始与结束立即重判：无侦测授权的观察者收一次移除，解除时按最新位置重新加入；侦测授权只由显式来源给出；</li>
 *   <li>容量满时按有效权重淘汰最低权重项并通知观察者本人；名额空出后立即补回仍有效的暂隐显式关注。</li>
 * </ul>
 */
final class ViewIndex {

    /** 视野半径（2026-11-19 对齐主仓 {@code view.h}：50 m，三维距离，含等号）。 */
    static final double VIEW_RADIUS = 50.0;
    /** 离开半径：距离超过它才出空间视野（滞回带 = 视野半径 × 2，100 m）。 */
    static final double LEAVE_RADIUS = 2 * VIEW_RADIUS;
    /** 格子边长（50 m）：不小于 {@link #VIEW_RADIUS}，3×3 邻域覆盖任意三维距离 ≤ 50 m 的点。 */
    static final double CELL_SIZE = VIEW_RADIUS;
    /** 兴趣列表容量（基线 {@code kAoiListCapacityDefault} = 100）。 */
    static final int DEFAULT_CAPACITY = 100;
    /** 移动中的人相对上次重判的位置累计位移（米，三维）达到它才重判。 */
    static final double REFRESH_DISTANCE = 1.0;
    private static final double REFRESH_DISTANCE_SQUARED = REFRESH_DISTANCE * REFRESH_DISTANCE;

    /** 空间普通实体默认权重。 */
    static final int WEIGHT_SPATIAL_NORMAL = 10;
    /** 空间队友优先权重。 */
    static final int WEIGHT_TEAMMATE = 50;
    /** 战斗交手显式关注权重（除钉住外最高，L625）。 */
    static final int WEIGHT_COMBAT = 100;
    /** 钉住最高权重（L625）。 */
    static final int WEIGHT_PINNED = 1000;
    /** 战斗交手显式关注来源 kind。 */
    static final int KIND_COMBAT = 100;

    /** 显式关注来源身份键：{@code kind + instanceId} 唯一确定一份显式来源。 */
    record ExplicitKey(int kind, long instanceId) {
    }

    /** 一份显式关注来源：允许同一对 (observer, target) 并存多份来源，客户端仅见一份可见状态。 */
    record ExplicitSource(int kind, long instanceId, int weight, boolean detectsStealth) {
        ExplicitKey key() {
            return new ExplicitKey(kind, instanceId);
        }
    }

    record Entered(List<ScenePlayer> seen, List<ScenePlayer> seers, ViewChanges displaced) {
    }

    record Reentered(List<ScenePlayer> lostWatching, List<ScenePlayer> lostWatchers,
                     List<ScenePlayer> seen, List<ScenePlayer> seers, ViewChanges displaced) {
    }

    Reentered reenter(ScenePlayer player) {
        List<ScenePlayer> lostWatching = List.copyOf(watching(player));
        List<ScenePlayer> lostWatchers = leave(player);
        Entered entered = enter(player);
        return new Reentered(lostWatching, lostWatchers, entered.seen(), entered.seers(), entered.displaced());
    }

    private final int capacity;
    private int effectiveCapacity;
    private final GridIndex<Interest> grid = new GridIndex<>(CELL_SIZE);
    private final Map<ScenePlayer, Interest> interests = new HashMap<>();
    private final List<Interest> pendingRefresh = new ArrayList<>();
    private final List<Interest> candidates = new ArrayList<>();

    ViewIndex() {
        this(DEFAULT_CAPACITY);
    }

    ViewIndex(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("兴趣列表容量必须为正数: " + capacity);
        }
        this.capacity = capacity;
        this.effectiveCapacity = capacity;
    }

    int capacity() {
        return capacity;
    }

    int effectiveCapacity() {
        return effectiveCapacity;
    }

    /**
     * 服务器压力动态调节有效视野容量（L625）：
     * 收缩时按有效权重从低到高、同权重先远后近裁表；回升时先补暂隐显式关注再补附近空间候选。
     */
    void setEffectiveCapacity(int nextCapacity, ViewChanges out) {
        int clamped = Math.max(1, Math.min(capacity, nextCapacity));
        if (clamped == effectiveCapacity) {
            return;
        }
        boolean shrunk = clamped < effectiveCapacity;
        effectiveCapacity = clamped;
        if (shrunk) {
            for (Interest obs : interests.values()) {
                while (obs.watching.size() > effectiveCapacity) {
                    ScenePlayer victim = pickTrimVictim(obs);
                    if (victim == null) {
                        break;
                    }
                    obs.watching.remove(victim);
                    Interest tgt = interests.get(victim);
                    if (tgt != null) {
                        tgt.watchedBy.remove(obs.player);
                    }
                    if (out != null) {
                        out.removed(obs.player, victim);
                    }
                }
            }
        } else {
            for (Interest obs : interests.values()) {
                backfillSuppressedExplicit(obs, out);
                backfillNearbySpatial(obs, out);
            }
        }
    }

    private static ScenePlayer pickTrimVictim(Interest obs) {
        Vec3 origin = obs.player.position();
        ScenePlayer victim = null;
        int victimWeight = Integer.MAX_VALUE;
        double victimDistSq = -1.0;
        for (ScenePlayer target : obs.watching) {
            int w = effectiveWeight(obs, target);
            double distSq = target.position().distanceSquared(origin);
            if (victim == null || w < victimWeight || (w == victimWeight && distSq > victimDistSq)) {
                victim = target;
                victimWeight = w;
                victimDistSq = distSq;
            }
        }
        return victim;
    }

    Entered enter(ScenePlayer entrant) {
        if (interests.containsKey(entrant)) {
            throw new IllegalStateException("玩家已在本场景的视野索引里: " + entrant.playerId());
        }
        Interest mine = new Interest(entrant);
        interests.put(entrant, mine);
        Vec3 at = entrant.position();
        mine.evaluatedAt = at;
        grid.insert(mine, at.x(), at.y());

        List<ScenePlayer> seers = new ArrayList<>();
        ViewChanges displaced = new ViewChanges();
        candidates.clear();
        grid.collectAround(mine, candidates);
        for (int c = 0; c < candidates.size(); c++) {
            Interest theirs = candidates.get(c);
            if (theirs == mine || !theirs.player.position().within(at, VIEW_RADIUS)) {
                continue;
            }
            if (!theirs.stealthed) {
                mine.spatialTargets.add(theirs.player);
                link(mine, theirs, null);
            }
            if (!mine.stealthed) {
                theirs.spatialTargets.add(mine.player);
                if (link(theirs, mine, displaced)) {
                    seers.add(theirs.player);
                }
            }
        }
        candidates.clear();
        return new Entered(new ArrayList<>(mine.watching), seers, displaced);
    }

    /** 玩家离开本场景：按正反向两张索引与显式关注反向索引彻底清理，不扫全节点。 */
    List<ScenePlayer> leave(ScenePlayer leaver) {
        return leave(leaver, null);
    }

    List<ScenePlayer> leave(ScenePlayer leaver, ViewChanges backfillOut) {
        Interest mine = interests.remove(leaver);
        if (mine == null) {
            return List.of();
        }
        grid.remove(mine);
        if (mine.pendingRefresh) {
            pendingRefresh.remove(mine);
            mine.pendingRefresh = false;
        }
        for (ScenePlayer target : mine.watching) {
            Interest targetState = interests.get(target);
            if (targetState != null) {
                targetState.watchedBy.remove(leaver);
            }
        }
        for (ScenePlayer target : new ArrayList<>(mine.explicitTargets.keySet())) {
            Interest targetState = interests.get(target);
            if (targetState != null) {
                targetState.explicitObservers.remove(leaver);
            }
        }
        mine.explicitTargets.clear();
        mine.spatialTargets.clear();

        List<ScenePlayer> watchers = new ArrayList<>(mine.watchedBy);
        for (ScenePlayer watcher : watchers) {
            Interest watcherState = interests.get(watcher);
            if (watcherState != null) {
                watcherState.watching.remove(leaver);
                watcherState.spatialTargets.remove(leaver);
            }
        }
        for (ScenePlayer observer : new ArrayList<>(mine.explicitObservers)) {
            Interest obsState = interests.get(observer);
            if (obsState != null) {
                obsState.explicitTargets.remove(leaver);
            }
        }
        mine.explicitObservers.clear();

        if (backfillOut != null) {
            for (ScenePlayer watcher : watchers) {
                Interest watcherState = interests.get(watcher);
                if (watcherState != null) {
                    backfillSuppressedExplicit(watcherState, backfillOut);
                    backfillNearbySpatial(watcherState, backfillOut);
                }
            }
        }
        return watchers;
    }

    /** 登记一条单向显式关注（不反向建立 target→observer）；可跨网格、可多来源并存、可携带侦测隐身授权与权重。 */
    boolean addExplicitInterest(ScenePlayer observer, ScenePlayer target, ExplicitSource source, ViewChanges out) {
        Objects.requireNonNull(source, "source");
        if (observer == null || target == null || observer == target) {
            return false;
        }
        Interest obs = interests.get(observer);
        Interest tgt = interests.get(target);
        if (obs == null || tgt == null) {
            return false;
        }
        obs.explicitTargets.computeIfAbsent(target, k -> new LinkedHashMap<>()).put(source.key(), source);
        tgt.explicitObservers.add(observer);
        if (!canSee(obs, tgt)) {
            return false;
        }
        if (obs.watching.contains(target)) {
            return false;
        }
        if (link(obs, tgt, out)) {
            if (out != null) {
                out.added(observer, target);
            }
            return true;
        }
        return false;
    }

    /** 撤除一条显式关注来源；仍存在其他有效来源（另一显式来源或未隐身空间来源）时保持可见不断链，否则移除并回补暂隐显式关注。 */
    boolean removeExplicitInterest(ScenePlayer observer, ScenePlayer target, int kind, long instanceId, ViewChanges out) {
        if (observer == null || target == null) {
            return false;
        }
        Interest obs = interests.get(observer);
        Interest tgt = interests.get(target);
        if (obs == null || tgt == null) {
            return false;
        }
        Map<ExplicitKey, ExplicitSource> map = obs.explicitTargets.get(target);
        if (map == null || map.remove(new ExplicitKey(kind, instanceId)) == null) {
            return false;
        }
        if (map.isEmpty()) {
            obs.explicitTargets.remove(target);
            tgt.explicitObservers.remove(observer);
        }
        if (obs.watching.contains(target) && !canSee(obs, tgt)) {
            obs.watching.remove(target);
            tgt.watchedBy.remove(observer);
            if (out != null) {
                out.removed(observer, target);
            }
            backfillSuppressedExplicit(obs, out);
            backfillNearbySpatial(obs, out);
        }
        return true;
    }

    /**
     * 切换目标隐身态并立即重判视野：
     * <ul>
     *   <li>进入隐身：无侦测授权的观察者收一次移除，保留非侦测显式登记为暂隐，并为空出名额的观察者回补其他显式关注；</li>
     *   <li>解除隐身：按目标最新位置对显式关注者与 3×3 空间邻域重判并重新创建可见边。</li>
     * </ul>
     */
    void setStealth(ScenePlayer target, boolean stealthed, ViewChanges out) {
        Interest tgt = interests.get(target);
        if (tgt == null || tgt.stealthed == stealthed) {
            return;
        }
        tgt.stealthed = stealthed;
        if (stealthed) {
            List<ScenePlayer> currentWatchers = new ArrayList<>(tgt.watchedBy);
            for (ScenePlayer watcher : currentWatchers) {
                Interest obs = interests.get(watcher);
                if (obs != null && !hasStealthDetection(obs, target)) {
                    obs.watching.remove(target);
                    tgt.watchedBy.remove(watcher);
                    if (out != null) {
                        out.removed(watcher, target);
                    }
                    backfillSuppressedExplicit(obs, out);
                    backfillNearbySpatial(obs, out);
                }
            }
            return;
        }
        // 隐身结束：按最新位置重判显式关注者与空间邻域
        Vec3 at = target.position();
        for (ScenePlayer observer : new ArrayList<>(tgt.explicitObservers)) {
            Interest obs = interests.get(observer);
            if (obs != null && !obs.watching.contains(target) && link(obs, tgt, out)) {
                if (out != null) {
                    out.added(observer, target);
                }
            }
        }
        candidates.clear();
        grid.collectAround(tgt, candidates);
        for (int c = 0; c < candidates.size(); c++) {
            Interest obs = candidates.get(c);
            if (obs == tgt || !obs.player.position().within(at, VIEW_RADIUS)) {
                continue;
            }
            obs.spatialTargets.add(target);
            if (!obs.watching.contains(target) && link(obs, tgt, out)) {
                if (out != null) {
                    out.added(obs.player, target);
                }
            }
        }
        candidates.clear();
    }

    boolean isStealthed(ScenePlayer target) {
        Interest tgt = interests.get(target);
        return tgt != null && tgt.stealthed;
    }

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

    void refresh(ViewChanges out) {
        if (pendingRefresh.isEmpty()) {
            return;
        }
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
        for (int last = pendingRefresh.size() - 1; last >= kept; last--) {
            pendingRefresh.remove(last);
        }
    }

    void refreshTeam(ScenePlayer player, ViewChanges out) {
        Interest mine = interests.get(player);
        if (mine == null) return;
        reevaluate(mine, player.position(), out);
        candidates.clear();
    }

    private void reevaluate(Interest mine, Vec3 at, ViewChanges out) {
        ScenePlayer mover = mine.player;
        boolean mineFreed = false;
        for (Iterator<ScenePlayer> it = mine.watching.iterator(); it.hasNext(); ) {
            ScenePlayer target = it.next();
            if (!target.position().within(at, LEAVE_RADIUS)) {
                mine.spatialTargets.remove(target);
                Interest tgt = interests.get(target);
                if (tgt == null || !canSee(mine, tgt)) {
                    it.remove();
                    if (tgt != null) {
                        tgt.watchedBy.remove(mover);
                    }
                    out.removed(mover, target);
                    mineFreed = true;
                }
            }
        }
        if (mineFreed) {
            backfillSuppressedExplicit(mine, out);
            backfillNearbySpatial(mine, out);
        }
        for (Iterator<ScenePlayer> it = mine.watchedBy.iterator(); it.hasNext(); ) {
            ScenePlayer watcher = it.next();
            if (!watcher.position().within(at, LEAVE_RADIUS)) {
                Interest obs = interests.get(watcher);
                if (obs != null) {
                    obs.spatialTargets.remove(mover);
                    if (!canSee(obs, mine)) {
                        it.remove();
                        obs.watching.remove(mover);
                        out.removed(watcher, mover);
                        backfillSuppressedExplicit(obs, out);
                        backfillNearbySpatial(obs, out);
                    }
                } else {
                    it.remove();
                }
            }
        }
        candidates.clear();
        grid.collectAround(mine, candidates);
        boolean mineFull = isFull(mine);
        for (int c = 0; c < candidates.size(); c++) {
            Interest theirs = candidates.get(c);
            if (theirs == mine || !theirs.player.position().within(at, VIEW_RADIUS)) {
                continue;
            }
            if (!theirs.stealthed) {
                mine.spatialTargets.add(theirs.player);
                if (!(mineFull && !teammates(mover, theirs.player) && !mine.explicitTargets.containsKey(theirs.player))) {
                    if (link(mine, theirs, out)) {
                        out.added(mover, theirs.player);
                        mineFull = isFull(mine);
                    }
                }
            }
            if (!mine.stealthed) {
                theirs.spatialTargets.add(mover);
                if (!(isFull(theirs) && !teammates(theirs.player, mover) && !theirs.explicitTargets.containsKey(mover))) {
                    if (link(theirs, mine, out)) {
                        out.added(theirs.player, mover);
                    }
                }
            }
        }
    }

    Set<ScenePlayer> watchers(ScenePlayer target) {
        Interest interest = interests.get(target);
        return interest == null ? Set.of() : interest.watchedByView;
    }

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

    int pendingMoves() {
        return pendingRefresh.size();
    }

    void clear() {
        grid.clear();
        interests.clear();
        pendingRefresh.clear();
        candidates.clear();
    }

    private boolean canSee(Interest watcher, Interest target) {
        if (target.stealthed) {
            return hasStealthDetection(watcher, target.player);
        }
        return watcher.spatialTargets.contains(target.player) || watcher.explicitTargets.containsKey(target.player);
    }

    private static boolean hasStealthDetection(Interest watcher, ScenePlayer target) {
        Map<ExplicitKey, ExplicitSource> map = watcher.explicitTargets.get(target);
        if (map == null || map.isEmpty()) {
            return false;
        }
        for (ExplicitSource src : map.values()) {
            if (src.detectsStealth()) {
                return true;
            }
        }
        return false;
    }

    private static int effectiveWeight(Interest watcher, ScenePlayer target) {
        int weight = Integer.MIN_VALUE;
        if (watcher.spatialTargets.contains(target)) {
            weight = teammates(watcher.player, target) ? WEIGHT_TEAMMATE : WEIGHT_SPATIAL_NORMAL;
        }
        Map<ExplicitKey, ExplicitSource> map = watcher.explicitTargets.get(target);
        if (map != null) {
            for (ExplicitSource src : map.values()) {
                if (src.weight() > weight) {
                    weight = src.weight();
                }
            }
        }
        return weight == Integer.MIN_VALUE ? WEIGHT_SPATIAL_NORMAL : weight;
    }

    /**
     * 建立 watcher → target 的可见边；容量满时按有效权重淘汰最低权重且严格低于候选权重的最早实体并通知观察者本人。
     */
    private boolean link(Interest watcher, Interest target, ViewChanges out) {
        if (watcher.watching.contains(target.player)) {
            return false;
        }
        if (isFull(watcher)) {
            int candidateWeight = effectiveWeight(watcher, target.player);
            ScenePlayer victim = null;
            int victimWeight = candidateWeight;
            for (ScenePlayer existing : watcher.watching) {
                int w = effectiveWeight(watcher, existing);
                if (w < victimWeight) {
                    victimWeight = w;
                    victim = existing;
                }
            }
            if (victim == null) {
                return false;
            }
            watcher.watching.remove(victim);
            Interest victimState = interests.get(victim);
            if (victimState != null) {
                victimState.watchedBy.remove(watcher.player);
            }
            if (out != null) {
                out.removed(watcher.player, victim);
            }
        }
        if (!watcher.watching.add(target.player)) {
            return false;
        }
        target.watchedBy.add(watcher.player);
        return true;
    }

    /** 容量空出后按有效权重从高到低补回因满员被暂隐的显式关注目标。 */
    private void backfillSuppressedExplicit(Interest watcher, ViewChanges out) {
        if (watcher.explicitTargets.isEmpty() || isFull(watcher)) {
            return;
        }
        List<Interest> eligible = new ArrayList<>();
        for (ScenePlayer target : watcher.explicitTargets.keySet()) {
            if (watcher.watching.contains(target)) {
                continue;
            }
            Interest tgt = interests.get(target);
            if (tgt != null && canSee(watcher, tgt)) {
                eligible.add(tgt);
            }
        }
        if (eligible.isEmpty()) {
            return;
        }
        eligible.sort((a, b) -> Integer.compare(
                effectiveWeight(watcher, b.player), effectiveWeight(watcher, a.player)));
        for (Interest tgt : eligible) {
            if (isFull(watcher)) {
                break;
            }
            if (watcher.watching.add(tgt.player)) {
                tgt.watchedBy.add(watcher.player);
                if (out != null) {
                    out.added(watcher.player, tgt.player);
                }
            }
        }
    }

    /** 名额空出后补回附近 3×3 网格内被拒/被挤掉的空间候选，无需等实体再次移动（L625）。 */
    private void backfillNearbySpatial(Interest watcher, ViewChanges out) {
        if (isFull(watcher)) {
            return;
        }
        Vec3 at = watcher.player.position();
        List<Interest> nearby = new ArrayList<>();
        grid.collectAround(watcher, nearby);
        List<Interest> eligible = new ArrayList<>();
        for (int i = 0; i < nearby.size(); i++) {
            Interest theirs = nearby.get(i);
            if (theirs == watcher || theirs.stealthed || watcher.watching.contains(theirs.player)) {
                continue;
            }
            if (!theirs.player.position().within(at, VIEW_RADIUS)) {
                continue;
            }
            watcher.spatialTargets.add(theirs.player);
            eligible.add(theirs);
        }
        if (eligible.isEmpty()) {
            return;
        }
        eligible.sort((a, b) -> {
            int cmp = Integer.compare(effectiveWeight(watcher, b.player), effectiveWeight(watcher, a.player));
            if (cmp != 0) {
                return cmp;
            }
            return Double.compare(a.player.position().distanceSquared(at), b.player.position().distanceSquared(at));
        });
        for (Interest tgt : eligible) {
            if (isFull(watcher)) {
                break;
            }
            if (watcher.watching.add(tgt.player)) {
                tgt.watchedBy.add(watcher.player);
                if (out != null) {
                    out.added(watcher.player, tgt.player);
                }
            }
        }
    }

    private static boolean teammates(ScenePlayer first, ScenePlayer second) {
        return first.teamId() != 0 && first.teamId() == second.teamId();
    }

    private boolean isFull(Interest interest) {
        return interest.watching.size() >= effectiveCapacity;
    }

    /** 一个玩家在本场景的兴趣状态。两张可见表互为镜像：A ∈ watching(B) ⇔ B ∈ watchedBy(A)。 */
    private static final class Interest {

        final ScenePlayer player;
        final Set<ScenePlayer> watching = new LinkedHashSet<>();
        final Set<ScenePlayer> watchedBy = new LinkedHashSet<>();
        final Set<ScenePlayer> watchingView = Collections.unmodifiableSet(watching);
        final Set<ScenePlayer> watchedByView = Collections.unmodifiableSet(watchedBy);
        /** 空间邻近来源目标集合。 */
        final Set<ScenePlayer> spatialTargets = new HashSet<>();
        /** 显式关注正向表：target → (kind, instanceId) → source。 */
        final Map<ScenePlayer, Map<ExplicitKey, ExplicitSource>> explicitTargets = new LinkedHashMap<>();
        /** 显式关注反向表：谁显式关注了本实体（供离场 O(度) 清理）。 */
        final Set<ScenePlayer> explicitObservers = new HashSet<>();
        /** 是否处于隐身态。 */
        boolean stealthed;
        boolean pendingRefresh;
        Vec3 evaluatedAt;

        Interest(ScenePlayer player) {
            this.player = player;
        }
    }
}
