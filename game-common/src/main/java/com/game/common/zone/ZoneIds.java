package com.game.common.zone;

/** zone 的外部非负数值与内部 uint32 位模式；0 是否可用由具体入口决定。 */
public final class ZoneIds {
    public static final long MAX = 0xffff_ffffL;

    private ZoneIds() {
    }

    /** 可选区号：0 保留为未指定；转换前校验，不能把溢出截成另一个区。 */
    public static int optionalBits(long value) {
        if (value < 0 || value > MAX) {
            throw new IllegalArgumentException("zone_id 须在 0–4294967295");
        }
        return (int) value;
    }

    public static int requiredBits(long value) {
        if (value == 0) {
            throw new IllegalArgumentException("zone_id 须在 1–4294967295");
        }
        return optionalBits(value);
    }
}
