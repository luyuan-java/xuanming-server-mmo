package com.game.match.id;

import com.game.common.id.LeaseGatedSnowflake;
import com.game.common.id.Snowflake;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

/**
 * xm-match 的发号器（match-spec §9.1「发号」、§9.8「租约丢失」）：battle_id 与 challenge_id 出自同一个雪花 worker
 * （{@code NodeTypes.MATCH} 的全服节点号租约，作用域 0；基线 {@code svcCtx.BattleIDGen}，team_id 在 Java 归 xm-team）。
 *
 * <p><b>battle_id 必须是时间在高位的雪花号</b>（随时间递增）：scene 的待结算记录每局一个字段，进场恢复按 battle_id 无符号升序应用并以此当作局序
 * （scene-battle-spec D13）。不许换成随机号。
 *
 * <p>fail-closed：租约无效（已丢失，或续期滞后超过 2/3 TTL）、时钟回拨超出容忍、或发出了 0，一律返回空——<b>绝不拿 0 或自造号顶替</b>；
 * 调用方按各入口的「内部错误」口径回（gather {@code internal}、切磋 16004、活动 INTERNAL）。
 *
 * <p>两种「发不出号」要分开看（lead 裁决 2）：
 * <ul>
 *   <li>{@link #leaseValid()} 为假、{@link #leaseLost()} 为假 = <b>续期滞后</b>：Redis 抖动，续期恢复后自动恢复。期间凑单暂停、
 *       PVE_SOLO / 切磋 / 整队 / 活动这些要发号的入口在建票、加锁之前就拒，1V1 / 5V5 的排队照常入队（恢复后照常成局）。</li>
 *   <li>{@link #leaseLost()} 为真 = <b>租约真正丢失</b>：号已不属于本进程，<b>不会自愈</b>，只能重启。这时连 1V1 / 5V5 的排队也要拒
 *       （否则票据入队后永不成局）；健康检查 DOWN，等编排层重启。取消排队、查状态、补签 179 不受影响。</li>
 * </ul>
 * 线程安全。
 */
public final class MatchIds {

    private final LeaseGatedSnowflake ids;
    private final BooleanSupplier leaseLost;

    /**
     * @param snowflake  worker = 租约的节点号
     * @param leaseValid 租约此刻能否用来发号（生产为 {@code NodeIdLease::isValid}）
     * @param leaseLost  租约是否已真正丢失（生产为 {@code NodeIdLease::isLost}）
     */
    public MatchIds(Snowflake snowflake, BooleanSupplier leaseValid, BooleanSupplier leaseLost) {
        this.ids = new LeaseGatedSnowflake(Objects.requireNonNull(snowflake, "snowflake"), Objects.requireNonNull(leaseValid, "leaseValid"));
        this.leaseLost = Objects.requireNonNull(leaseLost, "leaseLost");
    }

    /** 发一个 battle_id；发不出为空（原因见类注释）。 */
    public OptionalLong nextBattleId() {
        return ids.tryNext();
    }

    /** 发一个 challenge_id（与 battle_id 同源，互不重号）；发不出为空。 */
    public OptionalLong nextChallengeId() {
        return ids.tryNext();
    }

    /** 租约此刻能否用来发号（与发号的前置检查同一个判定）：给「必败的请求先别建票 / 加锁 / 弹组」用。 */
    public boolean leaseValid() {
        return ids.leaseValid();
    }

    /** 租约是否已真正丢失（不会自愈）：为真时一切开局类入口与排队入口都拒，健康检查 DOWN。 */
    public boolean leaseLost() {
        return leaseLost.getAsBoolean();
    }
}
