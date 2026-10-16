package com.game.login.auth;

import java.util.Optional;

/** 生产口令尝试的跨副本门；未知结果失败关闭，调用方不得换结局重做同一次完成。 */
public interface PasswordFailureQuota {
    enum Domain { KNOWN, UNKNOWN }
    enum Outcome { SUCCESS, MISMATCH, ABORT }

    /** 不包含账号明文；本地单调期限保守地从发送 Begin 前算起，Redis 仍独立复核租期。 */
    record Attempt(String key, String token, long expiresNanos) {
        public boolean expired() { return System.nanoTime() - expiresNanos >= 0; }
    }

    /** 账号取权威 canonical 原值；UNKNOWN 才使用形状合法的原始输入。拒绝或不可确认均为空。 */
    Optional<Attempt> begin(Domain domain, String identity);

    /** 只有当前租期内的同 token / 同 outcome 可确认；过期、相反结局或不可确认均返回 false。 */
    boolean finish(Attempt attempt, Outcome outcome);
}
