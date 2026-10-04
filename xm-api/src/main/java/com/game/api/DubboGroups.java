package com.game.api;

/**
 * {@link ClientMessageService} 的 Dubbo group 取值，等于消息号所在 proto 的一级目录
 * （{@code MessageMethod#domain()}）。
 */
public final class DubboGroups {

    public static final String LOGIN = "login";

    /** 好友（{@code proto/friend/...}），由 xm-friend 提供。 */
    public static final String FRIEND = "friend";

    /** 聊天（{@code proto/chat/...}），由 xm-chat 提供。 */
    public static final String CHAT = "chat";

    private DubboGroups() {
    }
}
