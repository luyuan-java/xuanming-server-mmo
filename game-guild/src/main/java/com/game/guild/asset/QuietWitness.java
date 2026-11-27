package com.game.guild.asset;

import com.game.guild.store.pb.GuildAssetOpRow;
import java.util.Objects;

/**
 * 人工终结安静 CAS 的三列见证（对齐主仓 {@code assetop.QuietWitness} 与 2026-11-26 §6.2 补记）：
 * 读证据时的 {@code lease_until_ms}、{@code last_send_ms} 与 {@code attempts}。
 *
 * <p>条件写必须三列全等（{@link #matches}）；跨越「从未应用」观察窗口时允许只领没发的本地 NOT_HERE 重排
 * （只推进 {@code attempts}，{@code lease_until_ms} 与 {@code last_send_ms} 不变，见 {@link #leaseAndSendUnchanged}），
 * 随后的条件写使用窗口后第二次读到的新见证。
 */
public record QuietWitness(long leaseUntilMs, long lastSendMs, int attempts) {

    /** 判「从未应用」前的最短本机单调时钟观察窗口：1 分钟。 */
    public static final long MIN_OBSERVE_WINDOW_MS = 60_000L;

    public static QuietWitness of(GuildAssetOpRow row) {
        Objects.requireNonNull(row, "row");
        return new QuietWitness(row.getLeaseUntilMs(), row.getLastSendMs(), row.getAttempts());
    }

    /** 条件写复核：三列全部未变才放行。 */
    public boolean matches(GuildAssetOpRow row) {
        return row != null
                && row.getLeaseUntilMs() == leaseUntilMs
                && row.getLastSendMs() == lastSendMs
                && row.getAttempts() == attempts;
    }

    /** 跨观察窗口复核：只比租约与发送时刻（允许期间发生不发 RPC 的本地重排推进 attempts）。 */
    public boolean leaseAndSendUnchanged(GuildAssetOpRow row) {
        return row != null
                && row.getLeaseUntilMs() == leaseUntilMs
                && row.getLastSendMs() == lastSendMs;
    }

    /** 观察窗口时长：{@code max(1 分钟, 2 × 租约)}。 */
    public static long observeWindowMs(long leaseMs) {
        long clamped = Math.max(0L, leaseMs);
        long twice = clamped > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : clamped * 2L;
        return Math.max(MIN_OBSERVE_WINDOW_MS, twice);
    }
}
