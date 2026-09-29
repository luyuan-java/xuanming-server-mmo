package com.game.api;

/**
 * {@link ClientMessageService} 的 Dubbo group 取值，等于消息号所在 proto 的一级目录
 * （{@code MessageMethod#domain()}）。
 */
public final class DubboGroups {

    public static final String LOGIN = "login";

    private DubboGroups() {
    }
}
