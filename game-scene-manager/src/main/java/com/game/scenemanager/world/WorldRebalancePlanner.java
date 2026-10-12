package com.game.scenemanager.world;

import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldChannels;
import com.game.discovery.world.WorldPlanSnapshot;
import com.game.scenemanager.world.PlanEvent.MigrationOutcome;
import com.game.scenemanager.world.PlanEvent.MigrationReason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规划步骤 P6：择机迁移（只在 {@code coverage=hash}；scene-channels-spec §4.8）。对应基线 {@code PlanWorldChannelRebalance} 的
 * opportunistic 一支（mmorpg world_rebalance.go:195-201）；urgent 一支（节点已死）在 Java 由 P2 删记录 + P5 补建完成，不占预算。
 *
 * <p>候选 = ACTIVE、World 图、节点是活节点、目录里已建出且没在排空、<b>人数 0</b>、<b>未到期预占 0</b>（迁移前对候选显式读一次
 * {@code ZCOUNT resv}，读不到就不迁）、不是镜像源、当前节点 ≠ 落点(conf, slot)。永远不迁有人的频道（同基线 :43-49、:195-197）。
 * 迁移 = 同一次写入里：落点节点上加一条<b>同 slot</b> 的新 ACTIVE 记录（新号，D4）+ 旧记录转 DRAINING(REBALANCE)。
 * 旧节点下一秒拉到计划，空频道立即销毁，领导者 P3 收尾。
 *
 * <p>预算 {@code rebalance.max-migrations-per-tick}（0 关闭、&lt;0 当 10，同 :127-133）按<b>计划出的</b>条数计（Java 计划即写入，
 * 不存在基线「失败不占预算」，B9 后半）。
 */
final class WorldRebalancePlanner {

    private static final Logger log = LoggerFactory.getLogger(WorldRebalancePlanner.class);

    private WorldRebalancePlanner() {
    }

    /**
     * 写入前要读预占数的候选（协调者拿去 {@code countReservations}，再把结果放进 {@link PlanInput#reservationCounts()}）。
     * 只按快照判断，是 {@link #run} 实际候选的超集（P1 / P4 可能已把其中几条转了排空）。
     */
    static List<Long> probe(WorldPlanSnapshot snapshot, DirectoryView directory, List<Integer> liveNodes,
                            Collection<Integer> worldConfs) {
        List<Long> out = new ArrayList<>();
        Set<Integer> live = new HashSet<>(liveNodes);
        Set<Integer> confs = new HashSet<>(worldConfs);
        for (WorldChannel channel : snapshot.channels().values()) {
            if (confs.contains(channel.getSceneConfigId()) && misalignedIdle(channel, directory, live, liveNodes)) {
                out.add(channel.getSceneId());
            }
        }
        return out;
    }

    /** 统计候选、计积压，到期时在预算内计划迁移。 */
    static void run(PlanDraft draft, MirrorSources mirrors, SceneIdSource ids) {
        List<Integer> liveNodes = draft.input.liveNodes();
        if (liveNodes.isEmpty()) {
            return;
        }
        Set<Integer> live = new HashSet<>(liveNodes);
        List<WorldChannel> candidates = new ArrayList<>();
        for (int conf : draft.worldConfs) {
            for (WorldChannel channel : draft.active(conf)) {
                if (draft.writable(channel) && misalignedIdle(channel, draft.directory(), live, liveNodes)) {
                    candidates.add(channel);
                }
            }
        }
        int budget = draft.props.rebalance().maxMigrationsPerTick();
        int planned = 0;
        if (draft.input.rebalanceDue() && budget > 0) {
            for (WorldChannel channel : candidates) {
                if (planned >= budget || draft.creationStopped) {
                    break;
                }
                Long reserved = draft.input.reservationCounts().get(channel.getSceneId());
                if (reserved == null || reserved != 0) {
                    continue; // 读不到预占数或有人刚被分进来：不迁
                }
                if (WorldAutoscaler.isMirrorSource(mirrors, channel.getSceneId())) {
                    continue;
                }
                OptionalLong id = ids.tryNext();
                if (id.isEmpty()) {
                    draft.noLease = true;
                    draft.creationStopped = true;
                    break;
                }
                int target = ChannelPlacement.hashTarget(channel.getSceneConfigId(), channel.getSlot(), liveNodes);
                draft.put(draft.newActive(id.getAsLong(), channel.getSceneConfigId(), target, channel.getSlot()));
                draft.put(draft.draining(channel, DrainReason.DRAIN_REBALANCE));
                draft.events.add(new PlanEvent.Migration(MigrationReason.BETTER_HOME, MigrationOutcome.PLANNED,
                        channel.getSceneConfigId()));
                planned++;
                log.info("择机迁移：zone={} conf={} slot={} scene={} node {} → 新频道 scene={} node {}",
                        Integer.toUnsignedString(draft.input.zoneId()), Integer.toUnsignedString(channel.getSceneConfigId()),
                        channel.getSlot(), Long.toUnsignedString(channel.getSceneId()),
                        Integer.toUnsignedString(channel.getNodeId()), Long.toUnsignedString(id.getAsLong()),
                        Integer.toUnsignedString(target));
            }
        }
        draft.betterHomePending = candidates.size() - planned;
    }

    /** ACTIVE、节点是活节点、已建出且没在排空、0 人、当前节点不是落点。 */
    private static boolean misalignedIdle(WorldChannel channel, DirectoryView directory, Set<Integer> live,
                                          List<Integer> sortedLive) {
        if (channel.getState() != ChannelState.CHANNEL_ACTIVE || !live.contains(channel.getNodeId())) {
            return false;
        }
        DirectoryView.Node node = directory.node(channel.getNodeId());
        if (node == null) {
            return false;
        }
        DirectoryView.Scene scene = node.scenes().get(channel.getSceneId());
        if (scene == null || scene.draining() || scene.players() != 0) {
            return false;
        }
        if (Integer.compareUnsigned(channel.getSlot(), WorldChannels.MAX_SLOT) > 0) {
            return false;
        }
        return ChannelPlacement.hashTarget(channel.getSceneConfigId(), channel.getSlot(), sortedLive) != channel.getNodeId();
    }
}
