package com.game.player.store;

/**
 * {@code player} 行上的归属三列（加锁读出的一刻）。不可变，可跨线程传递。
 *
 * @param ownerEpoch 当前归属围栏
 * @param released   true = 当前 epoch 的写者已写回并释放（或从未进过场景）；false = 有写者持有
 * @param leaseUntil 当前写者的租约到期时刻（Unix 毫秒）；交出事务把它当作「这一次交出」的标识（见 {@link PlayerStore#handOffOwnership}）
 */
public record OwnerState(long ownerEpoch, boolean released, long leaseUntil) {
}
