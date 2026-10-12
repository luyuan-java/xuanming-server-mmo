package com.game.scenemanager;

import com.game.table.WorldTable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 可按负载分配的「大世界」场景配置集合，以及请求没指定地图时的默认落点。
 *
 * <p>口径与 mmorpg 一致（go/scene_manager 的 {@code worldConfIds} / {@code defaultWorldConfID}）：
 * <ul>
 *   <li>世界地图 = World 表各行的 {@code scene_id}（它是 BaseScene id，即客户端看到的 {@code scene_config_id}），
 *       <b>不是</b> World 表自己的行 id；</li>
 *   <li>默认落点 = World 表<b>按表序</b>第一行的 {@code scene_id}（当前数据为 1，
 *       见 docs/reference/mmorpg-client-contract-scene.md §1「首登场景配置 id = 1」）。</li>
 * </ul>
 * 只有世界地图能按负载分配；副本 / 镜像是私有实例，不能因为「人少」就把别人塞进去。
 *
 * @param defaultConfigId 默认世界地图的 scene_config_id，必须属于 {@code worldConfigIds}
 * @param worldConfigIds  全部世界地图的 scene_config_id，非空、不含 0
 */
public record WorldSceneConfigs(int defaultConfigId, Set<Integer> worldConfigIds) {

    public WorldSceneConfigs {
        // 保留表序（频道铺设按表序逐图进行，scene-channels-spec §4.6.2 P5）；不可修改、不含 null
        worldConfigIds = Collections.unmodifiableSet(new LinkedHashSet<>(List.copyOf(worldConfigIds)));
        if (worldConfigIds.isEmpty()) {
            throw new IllegalArgumentException("World 表没有任何世界地图，无法确定默认落点");
        }
        if (worldConfigIds.contains(0)) {
            throw new IllegalArgumentException("世界地图的 scene_config_id 不能为 0");
        }
        if (!worldConfigIds.contains(defaultConfigId)) {
            throw new IllegalArgumentException("默认世界地图 " + defaultConfigId + " 不在 World 表的世界地图里 " + worldConfigIds);
        }
    }

    /**
     * 从 World 表构建。
     *
     * @param overrideDefault 显式指定的默认世界地图（scene_config_id）；为 null 时取表序第一行的 {@code scene_id}。
     *                        指定值必须是 World 表登记过的世界地图，否则抛 {@link IllegalArgumentException}（启动即失败）。
     */
    public static WorldSceneConfigs fromWorldTable(List<WorldTable> table, Integer overrideDefault) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (WorldTable row : table) {
            // scene_id 为 0 的行是坏数据（0 不是合法的场景配置），跳过而不是让它变成「默认地图」。
            if (row.getSceneId() != 0) {
                ids.add(row.getSceneId());
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("World 表为空（或 scene_id 全为 0），无法确定默认落点");
        }
        int defaultId = overrideDefault != null ? overrideDefault : ids.iterator().next();
        return new WorldSceneConfigs(defaultId, ids);
    }

    public boolean isWorld(int sceneConfigId) {
        return worldConfigIds.contains(sceneConfigId);
    }

    /** 全部世界地图，按 World 表序（{@link #fromWorldTable} 构建时；直接构造时按传入集合的迭代顺序）。 */
    public List<Integer> orderedConfigIds() {
        return List.copyOf(worldConfigIds);
    }
}
