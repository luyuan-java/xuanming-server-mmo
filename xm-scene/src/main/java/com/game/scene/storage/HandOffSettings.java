package com.game.scene.storage;

import com.game.player.store.PlayerStore;
import com.game.scene.ownership.OwnerLeaseRenewer;
import java.time.Duration;

/**
 * 交出归属（跨节点换图）在存储层的两个参数（scene-handoff-spec §5.2、§6.2）。
 *
 * <p>启动期校验（不满足即拒启）：
 * <ul>
 *   <li>{@code 续约周期 < leaseMargin < 租约 − 续约周期}：上界——健康续约下剩余租约不低于「租约 − 续约周期」，边际再大，续约正常也会
 *       「租约不足」；下界——边际要盖住一次交出尝试的最长时长（单次尝试受连接 socketTimeout 约束，与续约周期同量级）以及
 *       「结局不明 → 探测出结论」的窗口；</li>
 *   <li>{@code statementTimeout < leaseMargin}：探测的截止是「第一次交出尝试起 leaseMargin − {@link #PROBE_DEADLINE_SLACK}」，
 *       一次加锁读的时限必须明显小于它；</li>
 *   <li>{@code leaseMargin > PROBE_DEADLINE_SLACK}、{@code statementTimeout ≥ 1 s}（JDBC 查询超时的粒度是秒）。</li>
 * </ul>
 *
 * @param leaseMargin      交出要求的剩余租约下限 M（{@code xm.scene.transfer-lease-margin}，缺省 15 s）：每次尝试只在
 *                         {@code owner_lease_until ≥ now + M} 时提交，于是从提交起 M 之内不可能有人夺到下一代
 * @param statementTimeout 交出事务（不含提交）与探测加锁读的时限（{@code xm.scene.transfer-probe-statement-timeout}，缺省 3 s）：
 *                         行锁被占时尝试以超时失败（瞬时故障，退避重试 / 交给探测），不会等到锁释放后才在冻结里给结论
 */
public record HandOffSettings(Duration leaseMargin, Duration statementTimeout) {

    /** 探测截止比「第一次尝试 + M」提前的量：给结局投递、逻辑线程处理与时钟偏差留的余量。 */
    public static final Duration PROBE_DEADLINE_SLACK = Duration.ofSeconds(2);

    public static final HandOffSettings DEFAULT = new HandOffSettings(Duration.ofSeconds(15), Duration.ofSeconds(3));

    public HandOffSettings {
        if (leaseMargin == null || statementTimeout == null) {
            throw new IllegalArgumentException("交出参数不能为空");
        }
        Duration renew = OwnerLeaseRenewer.PERIOD;
        Duration lease = PlayerStore.OWNER_LEASE;
        if (leaseMargin.compareTo(renew) <= 0 || leaseMargin.compareTo(lease.minus(renew)) >= 0) {
            throw new IllegalArgumentException("xm.scene.transfer-lease-margin 必须在续约周期 " + renew + " 与 租约 − 续约周期 "
                    + lease.minus(renew) + " 之间（不含端点）: " + leaseMargin);
        }
        if (leaseMargin.compareTo(PROBE_DEADLINE_SLACK) <= 0) {
            throw new IllegalArgumentException("xm.scene.transfer-lease-margin 必须大于 " + PROBE_DEADLINE_SLACK + ": " + leaseMargin);
        }
        if (statementTimeout.compareTo(Duration.ofSeconds(1)) < 0 || statementTimeout.compareTo(leaseMargin) >= 0) {
            throw new IllegalArgumentException("xm.scene.transfer-probe-statement-timeout 必须 ≥ 1s 且小于 transfer-lease-margin "
                    + leaseMargin + ": " + statementTimeout);
        }
    }

    /** 探测截止：第一次交出尝试（单调时钟纳秒）起 M − {@link #PROBE_DEADLINE_SLACK}。 */
    long probeDeadlineNanos(long firstAttemptNanos) {
        return firstAttemptNanos + leaseMargin.minus(PROBE_DEADLINE_SLACK).toNanos();
    }
}
