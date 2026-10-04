package com.game.guild.id;

import com.game.common.id.Snowflake;
import java.util.function.BooleanSupplier;

/**
 * guild_id 发号器（guild-spec §1.12、§7.9，D8）：雪花号，worker 取 xm-guild 的全服节点号租约（{@code NodeTypes.GUILD}，作用域 0）。
 * 基线优先用 data_service 号段（biz_tag=guild），Java 没有号段服务；客户端只把 guild_id 当 uint64，不在乎来源。
 *
 * <p>fail-closed（基线 guild_logic.go:322-340：失败时<b>绝不</b>返回 0 或自造 id）：
 * <ul>
 *   <li>每次发号前检查租约<b>当前有效</b>（生产为 {@code NodeIdLease::isValid}：未丢失且最近一次成功续期不足 2/3 TTL），无效即拒绝；</li>
 *   <li>时钟回拨超过容忍值时 {@link Snowflake} 抛异常，同样拒绝；</li>
 *   <li>发出 0（不可能，但 0 不是合法 guild_id）也拒绝。</li>
 * </ul>
 * 调用方（CreateGuild）把任何异常定性为 14008（in-band 故障码，指标计 fault）。续期恢复后自动恢复发号。线程安全。
 */
public final class GuildIds {

    private final Snowflake snowflake;
    private final BooleanSupplier leaseValid;

    /** @param leaseValid 租约当前是否可用来发号（每次发号前调用） */
    public GuildIds(Snowflake snowflake, BooleanSupplier leaseValid) {
        this.snowflake = snowflake;
        this.leaseValid = leaseValid;
    }

    /** @throws IllegalStateException 节点号租约无效（已丢失或续期滞后）、时钟回拨、或发出了 0 */
    public long nextId() {
        if (!leaseValid.getAsBoolean()) {
            throw new IllegalStateException("guild_id 节点号租约无效（已丢失或续期滞后），拒绝发号");
        }
        long id = snowflake.nextId();
        if (id == 0) {
            throw new IllegalStateException("guild_id 雪花发出了 0，拒绝使用");
        }
        return id;
    }
}
