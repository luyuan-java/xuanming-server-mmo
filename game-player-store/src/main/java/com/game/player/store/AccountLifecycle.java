package com.game.player.store;

/**
 * 账号的实名与注销生命周期视图（与权威主仓 {@code account_directory} 生命周期列同口径）。
 *
 * @param account                 账号标识
 * @param realNamePi              NPPA 38 位实名标识；空串表示未实名
 * @param deletionState           0=正常（NONE），1=冷静期（PENDING），2=执行中（DELETING）
 * @param deletionRequestedMs     注销申请时刻（Unix 毫秒）
 * @param deletionExecuteAfterMs  冷静期满可执行时刻（Unix 毫秒）
 * @param deletionLeaseUntilMs    执行者租约截止时刻（Unix 毫秒）
 * @param deletionSource          1=本人申请，2=运维代办
 * @param previousDeletedMs       若本账号为注销后新建，且命中 {@code account_deletion_receipt} 则为上次完成注销时刻（Unix 毫秒），否则为 0
 */
public record AccountLifecycle(
        String account,
        String realNamePi,
        int deletionState,
        long deletionRequestedMs,
        long deletionExecuteAfterMs,
        long deletionLeaseUntilMs,
        int deletionSource,
        long previousDeletedMs) {

    public static final int DELETION_NONE = 0;
    public static final int DELETION_PENDING = 1;
    public static final int DELETION_DELETING = 2;

    public static final int SOURCE_USER = 1;
    public static final int SOURCE_ADMIN = 2;

    public AccountLifecycle {
        realNamePi = realNamePi == null ? "" : realNamePi;
    }

    public boolean deletingOrPending() {
        return deletionState != DELETION_NONE;
    }

    public AccountLifecycle withPreviousDeletedMs(long prevDeletedMs) {
        return new AccountLifecycle(account, realNamePi, deletionState, deletionRequestedMs,
                deletionExecuteAfterMs, deletionLeaseUntilMs, deletionSource, prevDeletedMs);
    }
}
