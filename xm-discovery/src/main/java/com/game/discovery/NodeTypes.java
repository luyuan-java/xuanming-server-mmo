package com.game.discovery;

/**
 * 节点类型名：{@link NodeIdLease} 与 {@link NodeDirectory} 的 Redis 键段（见 {@link RedisKeys}）。
 * 写方与读方必须用同一个值，所以这里是唯一出处，各模块不再写字符串字面量。
 * 改值等于换键空间：在线节点的租约与目录条目会「消失」，只能停服切换。
 */
public final class NodeTypes {

    /** xm-gate：节点号租约（session 高位）+ gate 目录（gateway 按它挑 gate）。 */
    public static final String GATE = "gate";
    /** xm-scene：节点号租约（雪花 worker）+ scene 目录（scene-manager 分配场景、gate 连 scene）。 */
    public static final String SCENE = "scene";
    /** xm-login：player_id 雪花 worker 的租约（不发布目录）。 */
    public static final String LOGIN = "login";

    private NodeTypes() {
    }
}
