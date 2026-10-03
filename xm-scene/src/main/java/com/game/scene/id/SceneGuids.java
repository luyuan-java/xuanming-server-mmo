package com.game.scene.id;

import com.game.common.id.Snowflake;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 场景节点发的全服唯一号（资产流水号、快照号，以后的物品 uuid）。雪花号，worker 取自<b>全服范围</b>的号段租约
 * （{@code NodeTypes.SCENE_GUID}，作用域 0）——不能用场景节点自己的按 zone 租约：两个 zone 的第一台 scene 会拿到同一个 worker、
 * 发出逐位相同的号，落库时按主键去重就把其中一条静默吞掉。
 *
 * <p>租约失效（丢失或续期滞后）或时钟回拨超出容忍时发不出号，返回空（fail-closed，调用方走兜底）。线程安全。
 */
public final class SceneGuids {

    private static final Logger log = LoggerFactory.getLogger(SceneGuids.class);

    private final Snowflake snowflake;
    private final BooleanSupplier leaseValid;

    public SceneGuids(Snowflake snowflake, BooleanSupplier leaseValid) {
        this.snowflake = snowflake;
        this.leaseValid = leaseValid;
    }

    public OptionalLong tryNext() {
        if (!leaseValid.getAsBoolean()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(snowflake.nextId());
        } catch (IllegalStateException e) {
            log.warn("发号失败：{}", e.getMessage());
            return OptionalLong.empty();
        }
    }
}
