package com.game.mail;

import com.game.discovery.serviceauth.DomainClientDispatch;

/** 仅测试包桥接：邮箱列表完整沿用生产装配，不扩大生产 API，也不复制派发规则。 */
public final class RankMailClientFixture {
    private RankMailClientFixture() {}

    public static DomainClientDispatch dispatcher(MailStore store) {
        return new MailConfiguration().clientDispatch(store);
    }
}
