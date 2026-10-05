package com.game.common.id;

import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「租约有效才发号」的雪花发号器：worker 取自某个节点号租约，每次发号前先问租约此刻是否可用来发号
 * （生产为 {@code NodeIdLease::isValid}：未丢失，且距最近一次成功续期不足 2/3 TTL）。
 *
 * <p>与 xm-scene 的 {@code SceneGuids}（资产流水号 / 快照号）同一写法，挪到这里供多个进程共用：
 * xm-scene-manager 用它发主世界频道的 scene_id（批次 5.1，scene-channels-spec §4.5、D18；对应基线 SceneIDGen 的
 * 「租约确认丢失先 Fence 再停」，mmorpg go/scene_manager/scene_manager_service.go:231-260）。
 *
 * <p>fail-closed：租约失效（丢失或续期滞后）、时钟回拨超出 {@link Snowflake} 的容忍、或发出了 0（0 不是合法 id），
 * 一律返回空，绝不返回 0 或自造号；调用方走自己的兜底（频道铺设是「本拍停止全部新建」）。续期恢复后自动恢复发号。线程安全。
 */
public final class LeaseGatedSnowflake {

    private static final Logger log = LoggerFactory.getLogger(LeaseGatedSnowflake.class);

    private final Snowflake snowflake;
    private final BooleanSupplier leaseValid;

    /** @param leaseValid 租约当前是否可用来发号（每次发号前调用） */
    public LeaseGatedSnowflake(Snowflake snowflake, BooleanSupplier leaseValid) {
        this.snowflake = snowflake;
        this.leaseValid = leaseValid;
    }

    /** 租约当前是否可用来发号（与 {@link #tryNext()} 的前置检查同一个判定；给调用方提前跳过整批新建用）。 */
    public boolean leaseValid() {
        return leaseValid.getAsBoolean();
    }

    /** 发一个号；租约无效、时钟回拨超出容忍或发出 0 时为空。 */
    public OptionalLong tryNext() {
        if (!leaseValid.getAsBoolean()) {
            return OptionalLong.empty();
        }
        long id;
        try {
            id = snowflake.nextId();
        } catch (IllegalStateException e) {
            log.warn("发号失败：{}", e.getMessage());
            return OptionalLong.empty();
        }
        if (id == 0) {
            log.warn("雪花发出了 0，拒绝使用");
            return OptionalLong.empty();
        }
        return OptionalLong.of(id);
    }
}
