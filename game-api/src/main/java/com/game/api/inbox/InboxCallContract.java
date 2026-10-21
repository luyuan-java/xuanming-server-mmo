package com.game.api.inbox;

/** scene与Inbox共用的正规内部签名方法域；密钥值只从进程环境注入。 */
public final class InboxCallContract {
    private InboxCallContract() {}
    public static final String FETCH = "/player_inbox.PlayerInbox/FetchItems";
    public static final String ACK = "/player_inbox.PlayerInbox/AckItems";
    public static final String CALLER = "scene";
    public static final String SECRET_ENV = "MMORPG_INTERNAL_SECRET_SCENE";
}
