package com.game.player.store;

/** 账号最近一次认证解析出的渠道身份（供内容安全平台复审与聊天使用）。 */
public record AccountChannelIdentity(String account, String channel, String openId, long updatedAt) {

    public AccountChannelIdentity {
        channel = channel == null ? "" : channel;
        openId = openId == null ? "" : openId;
    }
}
