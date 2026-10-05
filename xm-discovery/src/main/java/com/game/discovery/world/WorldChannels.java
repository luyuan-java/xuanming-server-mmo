package com.game.discovery.world;

import com.game.common.id.Snowflake;
import com.game.discovery.NodeTypes;
import java.time.Duration;

/**
 * 主世界频道（批次 5.1）的常量（scene-channels-spec §5.2「常量」、§4.4、§4.5、§4.11）。scene-manager 与 scene 共用这一个出处。
 */
public final class WorldChannels {

    /** hash 覆盖模式的落点键 = {@code scene_config_id * SLOT_STRIDE + slot}（D5；基线补建键 conf*1000+i，world_init.go:600-607）。 */
    public static final int SLOT_STRIDE = 1000;
    /** 最大 slot（含）：slot 取 {@code [0, MAX_SLOT]}，落点键不跨到下一个 conf。 */
    public static final int MAX_SLOT = SLOT_STRIDE - 2;
    /** 每图频道数上限（slot 0..998 共 999 个）：{@code channel-count-by-config} / {@code max-channels} 超过即拒启（§4.7、§5.2）。 */
    public static final int MAX_CHANNELS_PER_MAP = MAX_SLOT + 1;

    /** scene_id 发号租约的节点类型（全服作用域 0，号段 {@code [1, 1023]}，TTL 15 s；§4.5、D18）。 */
    public static final String ID_LEASE_NODE_TYPE = NodeTypes.SCENE_MANAGER;
    public static final int ID_LEASE_ZONE = 0;
    public static final int ID_LEASE_MIN_ID = 1;
    public static final int ID_LEASE_MAX_ID = Snowflake.MAX_WORKER;
    public static final Duration ID_LEASE_TTL = Duration.ofSeconds(15);

    /** 分 zone 领导锁的缺省 TTL（基线 LeaderLockTTLSeconds = 30，config.go:65-74）；续期间隔 TTL/3，有效期 2/3 TTL（§4.4）。 */
    public static final Duration DEFAULT_LEADER_LOCK_TTL = Duration.ofSeconds(30);
    /** 进场软预占的缺省 TTL（§4.11；必须 ≥ login 的归属夺取等待，scene-manager 启动时校验，Q8）。 */
    public static final Duration DEFAULT_RESERVATION_TTL = Duration.ofSeconds(10);

    private WorldChannels() {
    }

    /**
     * 启动校验（Q8）：软预占 TTL 必须覆盖 login 的归属夺取等待（{@code xm.login.owner-claim-wait}）——预占在玩家真正进场、节点上报新人数之前
     * 就到期，并发分配就看不见这个人，扎堆防不住。{@code reservationTtl = 0} 表示关闭预占，不校验。
     *
     * @throws IllegalStateException 不满足时（带两个值与配置键，启动失败）
     */
    public static void requireReservationTtlCovers(Duration reservationTtl, Duration ownerClaimWait) {
        if (reservationTtl.isNegative()) {
            throw new IllegalStateException("xm.scene-manager.world.reservation-ttl 不能为负: " + reservationTtl);
        }
        if (!reservationTtl.isZero() && reservationTtl.compareTo(ownerClaimWait) < 0) {
            throw new IllegalStateException("xm.scene-manager.world.reservation-ttl（" + reservationTtl
                    + "）必须 ≥ login 的归属夺取等待 xm.login.owner-claim-wait（" + ownerClaimWait + "），或设为 0 关闭预占");
        }
    }
}
