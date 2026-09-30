package com.game.login.character;

import com.game.common.id.Snowflake;
import java.util.function.BooleanSupplier;

/**
 * player_id 发号器：雪花号，worker 为 xm-login 的节点号租约。player_id 只由 xm-login 产生（雪花节点隔离不变量）。
 *
 * <p>每次发号前检查租约<b>当前有效</b>（生产为 {@code NodeIdLease::isValid}：未丢失且最近一次成功续期不足 2/3 TTL），
 * 无效即拒绝发号（fail-closed）：只看「是否已丢失」不够——Redis 抖动期间续期失败、丢失回调还没触发时，
 * 键可能已经过期、号可能已被别的实例占用，继续发会与它撞号。续期恢复后自动恢复发号。
 * 时钟回拨超过容忍值时 {@link Snowflake} 抛异常，同样拒绝发号。线程安全。
 */
public final class PlayerIdGenerator {

    private final Snowflake snowflake;
    private final BooleanSupplier leaseValid;

    /** @param leaseValid 租约当前是否可用来发号（每次发号前调用） */
    public PlayerIdGenerator(Snowflake snowflake, BooleanSupplier leaseValid) {
        this.snowflake = snowflake;
        this.leaseValid = leaseValid;
    }

    /** @throws IllegalStateException 节点号租约无效（已丢失或续期滞后），或时钟回拨 */
    public long nextId() {
        if (!leaseValid.getAsBoolean()) {
            throw new IllegalStateException("节点号租约无效（已丢失或续期滞后），拒绝发号");
        }
        return snowflake.nextId();
    }
}
