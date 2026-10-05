package com.game.scenemanager;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.world.ReservationCandidate;
import com.game.discovery.world.ReservationPick;
import com.game.discovery.world.WorldChannelStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 在一张世界地图的频道里选一个并软预占（scene-channels-spec §4.11 第 2、5 步，D7、D13、D20）。login 进游戏经 {@link SceneAssigner} 用它；
 * 5.2 的跨节点换场景也用它（{@code excludeSceneId} 排除当前频道）。
 *
 * <ul>
 *   <li><b>候选</b> = 可用节点目录里 {@code scene_config_id == conf}、{@code scene_id ≠ 0}、<b>没在排空</b>的场景条目。<b>不读计划</b>：
 *       节点拉到计划后 ≤1 s 内标排空并立即补发目录，这个窗口由节点的进场重定向兜住（§4.10.4，D13）。</li>
 *   <li><b>选择与预占</b>：候选按 (node_id, scene_id) 无符号升序排好，交给一段 Lua：负载 = 目录人数 + 未到期的别人的预占数，
 *       取严格最小（并列取排序靠前的：节点号小、再场景号小，D20），给它记一条 {@code player_id → now + ttl}。
 *       对应基线 {@code ReserveBestWorldChannelForEnter} 的 Lua 原子预占（mmorpg world_init.go:437-513、scene_atomic.go:46-101），
 *       但不是 INCR / DECR 硬计数：成员是 player_id、带 TTL，同一玩家重复分配（login 重试）不重复计数，拒绝出口不需要成对退还（D7）。</li>
 *   <li>{@code reservation-ttl = 0} 关闭预占：只按目录人数选（并列规则同上），不碰 Redis。</li>
 *   <li>Lua / Redis 出错原样上抛（基础设施故障，login 回 3023），不伪装成「没有频道」。</li>
 * </ul>
 * 无可变状态，线程安全（Dubbo 业务线程并发调用）。
 */
public final class ChannelSelector {

    /** 选中的频道。 */
    public record Choice(int nodeId, SceneEntry scene) {
    }

    /** (node_id, scene_id) 无符号升序：并列时的优先顺序（与改动前的 {@code Candidate.isBetterThan} 一致）。 */
    private static final Comparator<Choice> TIE_ORDER = Comparator
            .comparing(Choice::nodeId, Integer::compareUnsigned)
            .thenComparing(c -> c.scene().getSceneId(), Long::compareUnsigned);

    private final SceneNodeSource source;
    private final WorldChannelStore store;
    private final Duration reservationTtl;

    /**
     * @param store          软预占的存储；{@code reservationTtl} 为 0 时可为 null
     * @param reservationTtl 预占 TTL；0 = 关闭预占
     */
    public ChannelSelector(SceneNodeSource source, WorldChannelStore store, Duration reservationTtl) {
        if (reservationTtl.isNegative()) {
            throw new IllegalArgumentException("预占 TTL 不能为负: " + reservationTtl);
        }
        if (!reservationTtl.isZero() && store == null) {
            throw new IllegalArgumentException("开启预占时必须给出预占存储");
        }
        this.source = source;
        this.store = store;
        this.reservationTtl = reservationTtl;
    }

    /** 不预占（只按目录人数选）。 */
    public static ChannelSelector withoutReservations(SceneNodeSource source) {
        return new ChannelSelector(source, null, Duration.ZERO);
    }

    public boolean reservationsEnabled() {
        return !reservationTtl.isZero();
    }

    /**
     * 读目录后选频道（§4.11 第 6 步的 {@code ChannelSelector.select(zone, conf, excludeSceneId, playerId)}）。
     *
     * @param excludeSceneId 不选这个场景（5.2「换到同图别的频道」；5.1 传 0）
     * @return 没有候选为空
     */
    public Optional<Choice> select(int zoneId, int sceneConfigId, long excludeSceneId, long playerId) {
        List<SceneNodeInfo> usable = source.list(zoneId).stream().filter(n -> SceneAssigner.isUsable(n, zoneId)).toList();
        return select(zoneId, usable, sceneConfigId, excludeSceneId, playerId);
    }

    /** 同上，目录已由调用方读好并过滤过（{@link SceneAssigner} 一次分配只读一次目录）。 */
    Optional<Choice> select(int zoneId, List<SceneNodeInfo> usableNodes, int sceneConfigId, long excludeSceneId,
                            long playerId) {
        List<Choice> candidates = new ArrayList<>();
        for (SceneNodeInfo node : usableNodes) {
            for (SceneEntry scene : node.getScenesList()) {
                if (scene.getSceneConfigId() != sceneConfigId || scene.getSceneId() == 0 || scene.getDraining()
                        || (excludeSceneId != 0 && scene.getSceneId() == excludeSceneId)) {
                    continue;
                }
                candidates.add(new Choice(node.getNodeId(), scene));
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort(TIE_ORDER);
        if (!reservationsEnabled() || playerId == 0) {
            return Optional.of(leastLoaded(candidates));
        }
        List<ReservationCandidate> keys = new ArrayList<>(candidates.size());
        for (Choice c : candidates) {
            keys.add(new ReservationCandidate(c.scene().getSceneId(), c.scene().getPlayerCount()));
        }
        ReservationPick pick = store.reserve(zoneId, keys, playerId, reservationTtl);
        if (pick.index() < 0 || pick.index() >= candidates.size()) {
            throw new IllegalStateException("预占脚本回了越界的下标 " + pick.index() + "（候选 " + candidates.size() + " 个）");
        }
        return Optional.of(candidates.get(pick.index()));
    }

    /**
     * 给选中的原实例记一条预占（§4.11 第 3 步：原实例「还在就不看人数」直接用，但也写预占，让并发分配看得见）。预占关闭时什么也不做。
     */
    void reserveInstance(int zoneId, long sceneId, long playerId) {
        if (reservationsEnabled() && playerId != 0) {
            store.reserveScene(zoneId, sceneId, playerId, reservationTtl);
        }
    }

    /** 目录人数最少（uint32 无符号比较）；并列取排序靠前的。{@code sorted} 已按 {@link #TIE_ORDER} 排好、非空。 */
    private static Choice leastLoaded(List<Choice> sorted) {
        Choice best = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Choice c = sorted.get(i);
            if (Integer.compareUnsigned(c.scene().getPlayerCount(), best.scene().getPlayerCount()) < 0) {
                best = c;
            }
        }
        return best;
    }
}
