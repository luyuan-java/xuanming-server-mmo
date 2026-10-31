package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.proto.eSpectateTier;
import java.util.List;

/**
 * 观战档位与赛事局标记的存储口（mmorpg battle-spectate-tiered-fanout.md §15.6；生产 {@link RedissonSpectateTierStore}）。两样都是提示性质的记账：
 * battle 的中继开关才是帧发不发的权威，这里丢了只是下一个观众多撞一次直连席。键的 TTL 同落点记录（覆盖整场战斗），用不着清理。
 *
 * <p>全部方法阻塞、至多等到 {@code d}，失败抛 {@link Deadline.DependencyException}（同 {@link SpectateStore} 的约定）；线程安全；id 是无符号 64 位的位模式。
 */
public interface SpectateTierStore {

    /** 这场的档位；没有记录 = DIRECT。 */
    eSpectateTier tier(long battleId, Deadline d);

    /** 只升不降：{@code target} 大于现值才写。返回写之后的档位。幂等、可重放。 */
    eSpectateTier raise(long battleId, eSpectateTier target, Deadline d);

    /** 置位 / 取消赛事局标记（取消只删标记，不降档）。标记寿命同落点记录，到期自己失效。 */
    void setFeatured(long battleId, boolean featured, Deadline d);

    /** 还没到期的赛事局，后标记的在前，至多 {@code limit} 场。 */
    List<Long> activeFeatured(int limit, Deadline d);
}
