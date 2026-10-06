package com.game.match.lifecycle;

import com.game.match.id.MatchIds;
import java.util.Objects;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 健康组件 {@code matchLease}（lead 裁决 2；match-spec §9.8「租约丢失」）：发号租约<b>真正丢失</b>时 DOWN——{@code /actuator/health} 整体回 503，
 * 编排层据此重启进程。租约丢失不会自愈（号已不属于本进程），进程本身不退出：取消排队、查状态、补签 179 还要给手里有票、在打的玩家服务，
 * 但凑单已停、一切要发号的入口与排队入口都在拒收，只有重启才能恢复。本机切片没有编排层，看到这条 DOWN（或 {@code xm_match_lease_lost = 1}、
 * 丢失时那条 ERROR 日志）要手工重启 xm-match。
 *
 * <p><b>续期滞后不算 DOWN</b>：Redis 抖动时租约暂时不能用来发号（{@link MatchIds#leaseValid()} 为假）、续期恢复后自动恢复——那段时间重启进程没有任何好处，
 * 只在详情里标出来。
 *
 * <p>只读两个 volatile 标志，不碰 Redis：管理端口的探活线程上随便调。
 */
public final class MatchLeaseHealthIndicator implements HealthIndicator {

    /** 详情键：租约状态。 */
    static final String DETAIL_LEASE = "lease";
    static final String LEASE_VALID = "valid";
    static final String LEASE_LAGGING = "renewal_lagging";
    static final String LEASE_LOST = "lost";
    /** 详情键：丢失时给值班的处置提示。 */
    static final String DETAIL_ACTION = "action";
    static final String ACTION_RESTART = "restart xm-match（租约丢失不会自愈；排队与开局入口已拒收，取消 / 查询 / 补签照常）";

    private final MatchIds ids;

    public MatchLeaseHealthIndicator(MatchIds ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public Health health() {
        if (ids.leaseLost()) {
            return Health.down().withDetail(DETAIL_LEASE, LEASE_LOST).withDetail(DETAIL_ACTION, ACTION_RESTART).build();
        }
        return Health.up().withDetail(DETAIL_LEASE, ids.leaseValid() ? LEASE_VALID : LEASE_LAGGING).build();
    }
}
