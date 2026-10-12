package com.game.discovery.world;

import com.game.api.proto.ChannelState;
import com.game.api.proto.WorldChannel;

/**
 * 频道计划写入批次里的一条改动（scene-channels-spec §4.4 写入 Lua 的 {@code (op, field, value)} 三元组）。
 * 一拍的全部改动打成一个 {@link WorldPlanBatch}，由 {@link WorldChannelStore#write} 在一段 Lua 里按顺序原子执行。
 *
 * <p>conf（scene_config_id）与 scene_id 都按无符号处理；HASH 字段是它们的无符号十进制。
 */
public sealed interface WorldPlanOp {

    /** {@code S}：HSET ch（新增或改写一条频道记录）。{@code plan_version} 由 {@link WorldPlanBatch} 统一填成「期望 ver + 1」。 */
    record PutChannel(WorldChannel channel) implements WorldPlanOp {
        public PutChannel {
            if (channel == null) {
                throw new IllegalArgumentException("频道记录不能为空");
            }
            if (channel.getSceneId() == 0 || channel.getSceneConfigId() == 0 || channel.getNodeId() == 0) {
                throw new IllegalArgumentException("频道记录的 scene_id / scene_config_id / node_id 不能为 0: " + channel);
            }
            if (channel.getState() != ChannelState.CHANNEL_ACTIVE && channel.getState() != ChannelState.CHANNEL_DRAINING) {
                throw new IllegalArgumentException("频道记录状态只能是 ACTIVE / DRAINING: " + channel);
            }
            if (Integer.compareUnsigned(channel.getSlot(), WorldChannels.MAX_SLOT) > 0) {
                throw new IllegalArgumentException("slot 超出 [0, " + WorldChannels.MAX_SLOT + "]: " + channel);
            }
        }
    }

    /** {@code D}：HDEL ch（删一条频道记录；不存在也不报错）。 */
    record RemoveChannel(long sceneId) implements WorldPlanOp {
        public RemoveChannel {
            if (sceneId == 0) {
                throw new IllegalArgumentException("scene_id 不能为 0");
            }
        }
    }

    /** {@code Q}：HSET desired（扩缩容改写期望数；钳制由规划器做，这里只要求 ≥ 1）。 */
    record SetDesired(int sceneConfigId, int count) implements WorldPlanOp {
        public SetDesired {
            requireConfAndCount(sceneConfigId, count);
        }
    }

    /** {@code N}：HSETNX desired（首次播种；已有值不覆盖，同基线 world_autoscale.go:87-92）。 */
    record SeedDesired(int sceneConfigId, int count) implements WorldPlanOp {
        public SeedDesired {
            requireConfAndCount(sceneConfigId, count);
        }
    }

    /** {@code X}：HDEL desired（孤儿 conf，同基线 orphan_cleanup.go:201-204）。 */
    record RemoveDesired(int sceneConfigId) implements WorldPlanOp {
    }

    /** {@code C}：HSET cooldown（冷却到期毫秒，Redis TIME）。 */
    record SetCooldown(int sceneConfigId, long untilMs) implements WorldPlanOp {
        public SetCooldown {
            if (sceneConfigId == 0) {
                throw new IllegalArgumentException("scene_config_id 不能为 0");
            }
        }
    }

    /** {@code Y}：HDEL cooldown（冷却已过期顺手清掉，或孤儿 conf）。 */
    record RemoveCooldown(int sceneConfigId) implements WorldPlanOp {
    }

    private static void requireConfAndCount(int sceneConfigId, int count) {
        if (sceneConfigId == 0) {
            throw new IllegalArgumentException("scene_config_id 不能为 0");
        }
        if (count < 1) {
            throw new IllegalArgumentException("期望频道数必须 ≥ 1: " + count);
        }
    }
}
