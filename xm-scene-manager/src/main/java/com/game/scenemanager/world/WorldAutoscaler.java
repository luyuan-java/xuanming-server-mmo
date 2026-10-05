package com.game.scenemanager.world;

import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.scenemanager.world.PlanEvent.AutoscaleAction;
import com.game.scenemanager.world.PlanEvent.AutoscaleOutcome;
import com.game.scenemanager.world.WorldChannelProperties.Autoscale;
import com.game.scenemanager.world.WorldChannelProperties.Coverage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规划步骤 P4：按人数自动扩缩容（缺省关；scene-channels-spec §4.7）。判定照搬基线 {@code autoscaleOneWorldMap}
 * （mmorpg world_autoscale.go:177-257），Java 收紧三处（D9）：
 * <ol>
 *   <li>负载集只取「ACTIVE、节点在场、目录里已建出且没在排空」的频道，人数取节点上报（节点权威计数，不含预占）；</li>
 *   <li>缩容牺牲者必须有<b>同节点同图</b>的 ACTIVE 兄弟（per-node 模式强制；hash 模式下牺牲者 0 人可免）——5.1 的改派只能落同节点；</li>
 *   <li>余量只算同节点兄弟（基线算全图余量，:259-274）。</li>
 * </ol>
 * 冷却读不到 = 快照失败、整拍不动（比基线「读错当不在冷却」保守，:491-494，B15）。缩容开始只改计划：记录转 DRAINING(SCALE_IN)、
 * 期望数 −1、打冷却；节点拉到计划后同节点改派、空了销毁，领导者在 P3 收尾（D19）。扩容只改期望数 +1 与冷却，同一次写入里 P5 把新频道补出来。
 */
final class WorldAutoscaler {

    private static final Logger log = LoggerFactory.getLogger(WorldAutoscaler.class);

    /** 负载集里的一个频道。 */
    record Load(WorldChannel channel, long players) {

        int nodeId() {
            return channel.getNodeId();
        }

        long sceneId() {
            return channel.getSceneId();
        }
    }

    /** 人少的在前（基线 sort by players）；并列按节点号、场景号无符号升序，结果确定（基线依赖 SMEMBERS 顺序，B13）。 */
    static final Comparator<Load> BY_PLAYERS = Comparator.comparingLong(Load::players)
            .thenComparing(Load::nodeId, Integer::compareUnsigned)
            .thenComparing(Load::sceneId, Long::compareUnsigned);

    private WorldAutoscaler() {
    }

    /** 对每张 World 图（表序）跑一次决策。 */
    static void run(PlanDraft draft, MirrorSources mirrors) {
        for (int conf : draft.worldConfs) {
            decide(draft, conf, mirrors);
        }
    }

    /** 某图的负载集，按 {@link #BY_PLAYERS} 排好。 */
    static List<Load> loads(PlanDraft draft, int conf) {
        List<Load> loads = new ArrayList<>();
        for (WorldChannel channel : draft.active(conf)) {
            if (!draft.writable(channel)) {
                continue;
            }
            DirectoryView.Node node = draft.directory().node(channel.getNodeId());
            if (node == null) {
                continue; // 节点缺席：不参与决策（宽限期内照常计入 ACTIVE 数，由 P2 处理）
            }
            DirectoryView.Scene scene = node.scenes().get(channel.getSceneId());
            if (scene == null || scene.draining()) {
                continue; // 还没建出来 / 节点上在排空（计划刚回滚还没应用）
            }
            loads.add(new Load(channel, scene.players()));
        }
        loads.sort(BY_PLAYERS);
        return loads;
    }

    private static void decide(PlanDraft draft, int conf, MirrorSources mirrors) {
        Autoscale cfg = draft.props.autoscale();
        List<Load> loads = loads(draft, conf);
        if (loads.isEmpty()) {
            return;
        }
        if (draft.inCooldown(conf)) {
            return;
        }
        int minCh = Math.max(1, cfg.minChannels());
        Load lightest = loads.get(0);

        // ── 缩容（基线 :214-229）
        if (loads.size() > minCh && lightest.players() < cfg.scaleInPlayers()) {
            Load victim = pickScaleInVictim(loads, cfg, draft.props.coverage(), mirrors);
            if (victim != null) {
                int base = draft.scalingBase(conf);
                draft.put(draft.draining(victim.channel(), DrainReason.DRAIN_SCALE_IN));
                draft.setDesired(conf, cfg.clamp(base - 1));
                draft.cooldownSet.put(conf, draft.nowMs + cfg.cooldown().toMillis());
                draft.events.add(new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.OK, conf));
                log.info("缩容：开始排空频道 zone={} conf={} scene={} node={} players={} (<{})",
                        Integer.toUnsignedString(draft.input.zoneId()), Integer.toUnsignedString(conf),
                        Long.toUnsignedString(victim.sceneId()), Integer.toUnsignedString(victim.nodeId()),
                        victim.players(), cfg.scaleInPlayers());
                return;
            }
            draft.events.add(new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.NO_VICTIM, conf));
            log.debug("缩容：最空的频道 {} 人（<{}）但没有可安全排空的（无同节点兄弟 / 无余量 / 镜像源） zone={} conf={}",
                    lightest.players(), cfg.scaleInPlayers(), Integer.toUnsignedString(draft.input.zoneId()),
                    Integer.toUnsignedString(conf));
        }

        // ── 扩容：要求所有频道都到线（基线 :231-254；loads 升序，看最空的就够）
        if (lightest.players() >= cfg.scaleOutPlayers()) {
            int desired = draft.scalingBase(conf);
            if (desired >= cfg.effectiveMaxChannels()) {
                draft.events.add(new PlanEvent.Autoscale(AutoscaleAction.SCALE_OUT, AutoscaleOutcome.MAX_REACHED, conf));
                draft.anomalies.add("扩容到顶：conf=" + Integer.toUnsignedString(conf) + " 全部 " + loads.size()
                        + " 个频道 ≥ " + cfg.scaleOutPlayers() + " 人，但期望数 " + desired + " 已达上限 " + cfg.effectiveMaxChannels());
                return;
            }
            draft.setDesired(conf, cfg.clamp(desired + 1));
            draft.cooldownSet.put(conf, draft.nowMs + cfg.cooldown().toMillis());
            draft.events.add(new PlanEvent.Autoscale(AutoscaleAction.SCALE_OUT, AutoscaleOutcome.OK, conf));
            log.info("扩容：zone={} conf={} 全部 {} 个频道 ≥ {} 人，期望数 {} → {}", Integer.toUnsignedString(draft.input.zoneId()),
                    Integer.toUnsignedString(conf), loads.size(), cfg.scaleOutPlayers(), desired, cfg.clamp(desired + 1));
        }
    }

    /**
     * 按升序找第一个可以安全排空的频道（同基线 pickScaleInVictim 的骨架，world_autoscale.go:295-322）：
     * 人数 &lt; 缩容线（遇到 ≥ 即停）、不是镜像源（查询出错按「是」）、有同节点兄弟（per-node 强制；hash 下 0 人可免）、同节点余量够。
     * 没有返回 null。
     */
    static Load pickScaleInVictim(List<Load> loads, Autoscale cfg, Coverage coverage, MirrorSources mirrors) {
        for (Load candidate : loads) {
            if (candidate.players() >= cfg.scaleInPlayers()) {
                break; // 升序，后面只会更多人
            }
            if (isMirrorSource(mirrors, candidate.sceneId())) {
                continue;
            }
            List<Load> siblings = new ArrayList<>();
            for (Load other : loads) {
                if (other.nodeId() == candidate.nodeId() && other.sceneId() != candidate.sceneId()
                        && other.channel().getState() == ChannelState.CHANNEL_ACTIVE) {
                    siblings.add(other);
                }
            }
            boolean siblingExempt = coverage == Coverage.HASH && candidate.players() == 0;
            if (siblings.isEmpty() && !siblingExempt) {
                continue;
            }
            if (!hasHeadroomFor(siblings, candidate, cfg.scaleOutPlayers())) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * 把牺牲者的人并进 {@code others} 后会不会把某个频道推过扩容线（基线 hasHeadroomFor，world_autoscale.go:259-274）：
     * 余量 = Σ max(0, 扩容线 − 人数) ≥ 牺牲者人数。Java 只把同节点兄弟传进来。
     */
    static boolean hasHeadroomFor(List<Load> others, Load victim, long scaleOutThreshold) {
        long headroom = 0;
        for (Load other : others) {
            if (other.sceneId() == victim.sceneId()) {
                continue;
            }
            long room = scaleOutThreshold - other.players();
            if (room > 0) {
                headroom += room;
            }
        }
        return headroom >= victim.players();
    }

    /** 镜像源查询；出错按「是」（fail-closed，基线 channelHasMirrors，world_autoscale.go:281-293）。 */
    static boolean isMirrorSource(MirrorSources mirrors, long sceneId) {
        try {
            return mirrors.isSource(sceneId);
        } catch (RuntimeException e) {
            log.warn("查不清频道是否镜像源，按「是」处理（不缩容 / 不迁移） scene={}", Long.toUnsignedString(sceneId), e);
            return true;
        }
    }
}
