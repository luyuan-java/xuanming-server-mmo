package com.game.payment.core;

/**
 * 实名状态只读策略接口（仅在 {@code minor_spending_gate=realname} 时调用）。
 */
@FunctionalInterface
public interface RealNameReader {

    /** 账号实名状态（{@code verified=false} 或 {@code pi} 非法时非小游戏渠道拒付 31008）。 */
    record RealNameStatus(boolean verified, String pi) {}

    /** 按买家角色号查询其所属账号的实名认证状态；查询不可用时抛异常（fail-closed 返 31006）。 */
    RealNameStatus lookupByPlayer(long playerId);
}
