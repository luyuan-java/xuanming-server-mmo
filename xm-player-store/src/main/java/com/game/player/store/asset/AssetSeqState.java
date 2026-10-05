package com.game.player.store.asset;

/**
 * 某个 (流, 纪元, seq) 在通用资产通道账本里的状态（同基线 C++ {@code AssetOpSeqState} / Go {@code assetop.SeqState}，逐项对应、顺序相同）。
 * scene 的在线账本与调用方读已落盘账本共用这一个枚举与同一份判定代码（{@link AssetLedgerRules#classify}）。
 */
public enum AssetSeqState {
    /** seq = 0、纪元 = 0、watermark 已接近溢出，或账本加载时判了损坏。一律 fail-closed。 */
    INVALID,
    /** 请求纪元 < 账本纪元：旧流水簿上的号，结局不可采信。 */
    STALE_EPOCH,
    /** seq ≤ watermark：已滑出窗口，结局不可知。 */
    BEHIND_WINDOW,
    /** 窗口内未见（含「请求纪元更大、按空账本看」）。 */
    UNSEEN,
    /** seq > watermark + 1024 但没超跳号上限：必为未见，记账时窗口上滑。 */
    AHEAD_OF_WINDOW,
    /** seq > max_seq + 1024（纪元更大时为 seq > 1024）。 */
    JUMP_TOO_FAR,
    /** 已应用（含部分发放）。 */
    APPLIED,
    /** 已拒绝（含中止占位）。 */
    REJECTED
}
