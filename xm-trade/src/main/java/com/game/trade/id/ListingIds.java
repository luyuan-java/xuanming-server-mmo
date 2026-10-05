package com.game.trade.id;

import com.game.common.id.Snowflake;
import java.util.function.BooleanSupplier;

/**
 * listing_id 发号器（trade-spec §5.7，T4）：雪花号，worker 取 xm-trade 的全服节点号租约（{@code NodeTypes.TRADE}，作用域 0）。
 * 基线只由 data_service 号段发（biz_tag {@code trade_listing}，没有 snowflake 回退；servicecontext.go:30-32、:270-291），Java 没有号段服务。
 * 写法照 xm-guild 的 GuildIds。
 *
 * <p>客户端可见的唯一差异：编号变长（2026-01-01 纪元起约 18 位十进制）。客户端按 {@code ulong} 解析、按字符串显示（JubaozhaiClient.cs:288-289、:343），
 * 按编号搜索照常可用；雪花符号位恒 0，所以恒 &lt; 2^63，robot 用 {@code MaxInt64} 当「不存在」的编号仍然安全。
 *
 * <p>fail-closed（基线 admin_logic.go:80-93：出错或拿到 0 都绝不写进主键）：
 * <ul>
 *   <li>每次发号前检查租约<b>当前有效</b>（生产为 {@code NodeIdLease::isValid}：未丢失且最近一次成功续期不足 2/3 TTL），无效即拒绝；</li>
 *   <li>时钟回拨超过容忍值时 {@link Snowflake} 抛异常，同样拒绝；</li>
 *   <li>发出 0 也拒绝。</li>
 * </ul>
 * 调用方（播种）把任何异常定性为 in-band 1003（计 error）。续期恢复后自动恢复发号。线程安全。
 */
public final class ListingIds {

    private final Snowflake snowflake;
    private final BooleanSupplier leaseValid;

    /** @param leaseValid 租约当前是否可用来发号（每次发号前调用） */
    public ListingIds(Snowflake snowflake, BooleanSupplier leaseValid) {
        this.snowflake = snowflake;
        this.leaseValid = leaseValid;
    }

    /** @throws IllegalStateException 节点号租约无效（已丢失或续期滞后）、时钟回拨、或发出了 0 */
    public long nextId() {
        if (!leaseValid.getAsBoolean()) {
            throw new IllegalStateException("listing_id 节点号租约无效（已丢失或续期滞后），拒绝发号");
        }
        long id = snowflake.nextId();
        if (id == 0) {
            throw new IllegalStateException("listing_id 雪花发出了 0，拒绝使用");
        }
        return id;
    }
}
