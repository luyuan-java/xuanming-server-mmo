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

    /**
     * 通用资产通道（{@link SceneAssetOpService}），每个 scene 节点各自导出、{@code register = false}，调用方按节点目录里的地址直连。
     * 只作分组标识（同一端口上不会有别的服务）。
     */
    public static final String SCENE_ASSET = "scene-asset";

    private DubboGroups() {
    }
}
