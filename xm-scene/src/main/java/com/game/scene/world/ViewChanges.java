package com.game.scene.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 一次视野刷新（{@link ViewIndex#refresh}）产生的变化，按观察者归并：谁的兴趣列表新加了谁、删掉了谁。
 * 场景逻辑据此给每个观察者发<b>一条</b> 47（新加的）和<b>一条</b> 64（删掉的）。只在场景逻辑线程上使用。
 *
 * <p>观察者按第一次出现变化的先后排列，每个观察者的列表按变化发生的先后排列，结果可复现。
 * 同一次刷新里同一对 (观察者, 目标) 不会既加又删：刷新期间位置不变，而进视野要求距离 ≤ 视野半径、
 * 出视野要求距离 &gt; 离开半径，两者互斥。
 */
final class ViewChanges {

    /** 一个观察者的变化。 */
    static final class Delta {

        private final List<ScenePlayer> added = new ArrayList<>(4);
        private final List<ScenePlayer> removed = new ArrayList<>(4);

        List<ScenePlayer> added() {
            return added;
        }

        List<ScenePlayer> removed() {
            return removed;
        }
    }

    private final Map<ScenePlayer, Delta> byWatcher = new LinkedHashMap<>();

    void added(ScenePlayer watcher, ScenePlayer target) {
        delta(watcher).added.add(target);
    }

    void removed(ScenePlayer watcher, ScenePlayer target) {
        delta(watcher).removed.add(target);
    }

    boolean isEmpty() {
        return byWatcher.isEmpty();
    }

    /** 观察者 → 变化，按观察者第一次出现的先后。 */
    void forEach(BiConsumer<ScenePlayer, Delta> action) {
        byWatcher.forEach(action);
    }

    Delta of(ScenePlayer watcher) {
        return byWatcher.get(watcher);
    }

    void clear() {
        byWatcher.clear();
    }

    private Delta delta(ScenePlayer watcher) {
        return byWatcher.computeIfAbsent(watcher, w -> new Delta());
    }
}
