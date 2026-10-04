package com.game.team.id;

import com.game.common.id.Snowflake;
import java.util.function.BooleanSupplier;

/**
 * team_id 发号器（team-spec §6.9，D15）：雪花号，worker 取 xm-team 的全服节点号租约（{@code NodeTypes.TEAM}，作用域 0）。
 * 基线与 battle_id / challenge_id 共用 BattleIDGen；客户端契约只要求非 0、唯一，Java 由 xm-team 独占这一种号（雪花节点隔离不变量）。
 *
 * <p>每次发号前检查租约<b>当前有效</b>（生产为 {@code NodeIdLease::isValid}：未丢失且最近一次成功续期不足 2/3 TTL），无效即拒绝
 * （fail-closed，同 xm-login 的 PlayerIdGenerator；基线失租被 fence 后返回错误，service.go:34-38）。续期恢复后自动恢复发号。
 * 时钟回拨超过容忍值时 {@link Snowflake} 抛异常，同样拒绝。调用方（CreateTeam）把任何异常与返回 0 都定性为 4030 + 空视图。线程安全。
 */
public final class TeamIds {

    private final Snowflake snowflake;
    private final BooleanSupplier leaseValid;

    /** @param leaseValid 租约当前是否可用来发号（每次发号前调用） */
    public TeamIds(Snowflake snowflake, BooleanSupplier leaseValid) {
        this.snowflake = snowflake;
        this.leaseValid = leaseValid;
    }

    /** @throws IllegalStateException 节点号租约无效（已丢失或续期滞后），或时钟回拨 */
    public long nextId() {
        if (!leaseValid.getAsBoolean()) {
            throw new IllegalStateException("team_id 节点号租约无效（已丢失或续期滞后），拒绝发号");
        }
        return snowflake.nextId();
    }
}
