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
    /**
     * xm-scene：全服唯一号（资产流水号、快照号）的雪花 worker 租约，作用域 0（全服）。与 {@link #SCENE} 的按 zone 租约分开：
     * 按 zone 分会让不同 zone 的 scene 拿到同一个 worker、发出相同的号。
     */
    public static final String SCENE_GUID = "scene-guid";
    /** xm-team：team_id 雪花 worker 的租约，作用域 0（全服；理由同 {@link #SCENE_GUID}）。 */
    public static final String TEAM = "team";
    /** xm-guild：guild_id 雪花 worker 的租约，作用域 0（全服；理由同 {@link #SCENE_GUID}）。 */
    public static final String GUILD = "guild";
    /** xm-trade：listing_id 雪花 worker 的租约，作用域 0（全服）。 */
    public static final String TRADE = "trade";
    /**
     * xm-scene-manager：主世界频道 scene_id 雪花 worker 的租约，作用域 0（全服；理由同 {@link #SCENE_GUID}——频道号要全服唯一，
     * 跨 zone 引用不撞号）。批次 5.1，scene-channels-spec §4.5、D18；常量见 {@code com.game.discovery.world.WorldChannels}。
     */
    public static final String SCENE_MANAGER = "scene-manager";
    /**
     * xm-battle：节点号租约（票据里的 {@code battle_node_id}）+ battle 目录（{@code xm.api.BattleNodeInfo}，6.4 的 match 按它挑节点），
     * <b>作用域 0</b>：基线 battle 是全局池、不分 zone（{@code deploy.yaml:46}；{@code gather.go:212}）。批次 6.2，battle-node-spec §7.1、§7.10。
     */
    public static final String BATTLE = "battle";
    /**
     * xm-match：battle_id 与 challenge_id 雪花 worker 的租约，作用域 0（全服；理由同 {@link #SCENE_GUID}），不发布目录。批次 6.4，match-spec §9.1。
     * battle_id 必须是时间在高位的雪花号：scene 按 battle_id 无符号升序当作局序应用待结算记录（scene-battle-spec D13）。
     * 基线的 team_id 也出自同一个发号器，Java 的 team_id 归 {@link #TEAM}（M23）。
     */
    public static final String MATCH = "match";

    private NodeTypes() {
    }
}
