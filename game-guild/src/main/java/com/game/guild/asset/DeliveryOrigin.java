package com.game.guild.asset;

/**
 * 一次终结是谁触发的（E9：代替基线挂在 ctx 上的 syncDeliveryKey，economy_logic.go:169-185；guild-economy-spec §5）。
 * 终结后的回调据此决定推不推送：只有 {@link #LOOP} 推（FUNDS_CHANGED / DELIVERY_DONE 只推本人）。
 */
public enum DeliveryOrigin {
    /** 请求路径提交后的同步投递：调用方自己拿着回包，不推送。 */
    SYNC,
    /** 后台重投循环。 */
    LOOP,
    /** 人工终结（assetopfix）：不推送（基线 OnFinalized 为 nil）。 */
    MANUAL
}
