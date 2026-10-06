package com.game.api;

/**
 * Dubbo group 取值。{@link ClientMessageService} 的 group 等于消息号所在 proto 的一级目录（{@code MessageMethod#domain()}）；
 * 服务对服务的类型化接口另有自己的 group（见各常量）。
 */
public final class DubboGroups {

    public static final String LOGIN = "login";

    /** 好友（{@code proto/friend/...}），由 xm-friend 提供。 */
    public static final String FRIEND = "friend";

    /** 聊天（{@code proto/chat/...}），由 xm-chat 提供。 */
    public static final String CHAT = "chat";

    /** 组队（{@code proto/team/...}），由 xm-team 提供。 */
    public static final String TEAM = "team";

    /**
     * 帮会（{@code proto/guild/...}，服务 {@code guildpb.GuildService}），由 xm-guild 提供；
     * 内部查询 {@link GuildInternalService} 也在这个 group。
     */
    public static final String GUILD = "guild";

    /** 聚宝斋 / 交易（{@code proto/trade/...}），由 xm-trade 提供。 */
    public static final String TRADE = "trade";

    /**
     * 通用资产通道（{@link SceneAssetOpService}），每个 scene 节点各自导出、{@code register = false}，调用方按节点目录里的地址直连。
     * 同一端口上还导出 {@link SceneBattleService}（group {@link #SCENE_BATTLE}），两者靠 group 区分。
     */
    public static final String SCENE_ASSET = "scene-asset";

    /**
     * scene 节点的回合制战斗入口（{@link SceneBattleService}，scene-battle-spec §7.3）：与 {@link #SCENE_ASSET} 同一个端口、各自一个 group，
     * {@code register = false}，调用方（battle、dev gather，6.4 起还有 match）按节点目录直连。
     */
    public static final String SCENE_BATTLE = "scene-battle";

    /**
     * battle 节点控制面（{@link BattleNodeService}，基线 gRPC {@code BattleNode}），每个 battle 节点各自导出、{@code register = false}，
     * 调用方（6.4 的 match）按 Redis 节点目录里的 {@code BattleNodeInfo.rpc_host / rpc_port} 直连。{@code battle} 留给将来
     * {@code proto/battle/...} 的 {@link ClientMessageService}（battle-node-spec §10.5），所以服务对服务的接口另起名（先例 {@link #SCENE_ASSET}）。
     */
    public static final String BATTLE_NODE = "battle-node";

    private DubboGroups() {
    }
}
