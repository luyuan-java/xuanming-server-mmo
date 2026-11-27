package com.game.login.character;

import com.game.common.id.AuthoritativeIdIssuer;
import com.game.common.id.Snowflake;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * player_id 发号器：
 * <ul>
 *   <li>权威模式（{@link #PlayerIdGenerator(AuthoritativeIdIssuer)}）：使用三份独立预留记录 + 全局号源登记处的权威号源，
 *       无可用可信上界或存储故障时直接抛 {@link IllegalStateException}，绝不回退本地 Snowflake；</li>
 *   <li>兼容/单测模式（{@link #PlayerIdGenerator(Snowflake, BooleanSupplier)}）：雪花号 + 租约有效性门禁（双钟 fail-closed）。</li>
 * </ul>
 */
public final class PlayerIdGenerator {

    private final AuthoritativeIdIssuer authoritative;
    private final Snowflake snowflake;
    private final BooleanSupplier leaseValid;

    /** 权威三记录预留发号器（生产推荐：无跨进程重号风险，故障 fail-closed 不回退 Snowflake）。 */
    public PlayerIdGenerator(AuthoritativeIdIssuer authoritative) {
        this.authoritative = Objects.requireNonNull(authoritative, "authoritative");
        this.snowflake = null;
        this.leaseValid = null;
    }

    /** @param leaseValid 租约当前是否可用来发号（每次发号前调用） */
    public PlayerIdGenerator(Snowflake snowflake, BooleanSupplier leaseValid) {
        this.authoritative = null;
        this.snowflake = Objects.requireNonNull(snowflake, "snowflake");
        this.leaseValid = Objects.requireNonNull(leaseValid, "leaseValid");
    }

    /** @throws IllegalStateException 权威预留失败、节点号租约无效（已丢失或续期滞后），或时钟回拨 */
    public long nextId() {
        if (authoritative != null) {
            return authoritative.nextId();
        }
        if (!leaseValid.getAsBoolean()) {
            throw new IllegalStateException("节点号租约无效（已丢失或续期滞后），拒绝发号");
        }
        return snowflake.nextId();
    }
}
