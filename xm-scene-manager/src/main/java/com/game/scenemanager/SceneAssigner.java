package com.game.scenemanager;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.table.SceneErrorTip;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 场景分配规则：给一个要进游戏的玩家挑 (scene 节点, 场景)。
 *
 * <p>规则：
 * <ol>
 *   <li>原实例：请求带了 {@code preferred_scene_id}（login 读到的玩家位置记录：在线顶号 / 断线重连租约内），该实例还在
 *       本 zone 可用节点的目录里、且<b>没在排空</b>就直接用它，不看人数（基线 scene_manager 注释里的设计意图；它实际只按 location 定 zone、
 *       落默认主世界，见 PARITY）；同时给它记一条软预占，让并发分配看得见（批次 5.1，scene-channels-spec §4.11 第 3 步，D20）。
 *       不在了 / 在排空中走下一条。</li>
 *   <li>目标地图：请求带了 {@code preferred_scene_config_id}，它是世界地图、且本 zone 有<b>没在排空</b>的频道承载它 → 用它；
 *       否则用默认世界地图（{@link WorldSceneConfigs#defaultConfigId()}）。与 mmorpg「解析失败回落默认大世界」一致。</li>
 *   <li>在目标地图的频道里选并软预占（{@link ChannelSelector}）：负载 = 目录人数 + 未到期预占，取最小；并列时取节点号小的，
 *       再并列取场景号小的（结果确定，便于排查）。预占关闭（{@code reservation-ttl = 0}）时只看目录人数。</li>
 *   <li>一个都没有 → 应答带 {@link #TIP_NO_SCENE}，不抛异常。</li>
 * </ol>
 *
 * <p>契约：
 * <ul>
 *   <li>业务拒绝（参数错、无场景）放在 {@code tip_id}；{@link SceneNodeSource} 与预占存储抛出的基础设施异常原样向上抛，
 *       由调用方（Dubbo 提供方）转成调用失败，不伪装成「无场景」。</li>
 *   <li>多副本安全：唯一的副作用是软预占（带 TTL、以 player_id 为成员，重复分配不重复计数）；没有可变字段，线程安全。</li>
 *   <li>人数来自节点每 5s 的上报（节点在计划应用后会立即补发一次）；一个上报周期内的突发进场由软预占摊开。</li>
 * </ul>
 */
public final class SceneAssigner {

    private static final Logger log = LoggerFactory.getLogger(SceneAssigner.class);

    /**
     * 没有可分配的场景。选 {@code kEnterSceneNotFound(3000)}：scene_error 段里语义最贴近「想进入的场景未找到」，
     * 且是该段「场景找不到」类码里唯一在 Tip 表有文案的（3007 kEnterSceneSceneNotFound 没有文案，客户端弹不出字）。
     * 这是给 login 的内部拒绝码；mmorpg 面向客户端统一推 3023 kEnterSceneFailed，是否照做由 login 决定。
     */
    public static final int TIP_NO_SCENE = SceneErrorTip.scene_error.kEnterSceneNotFound_VALUE;

    /** 请求参数不合法（zone_id 为 0）。调用方编程错误，不是玩家能触发的状态。 */
    public static final int TIP_BAD_REQUEST = SceneErrorTip.scene_error.kEnterSceneParamError_VALUE;

    private final SceneNodeSource source;
    private final WorldSceneConfigs worldConfigs;
    private final ChannelSelector selector;

    /** 不预占（只按目录人数选；单测与只读场景用）。 */
    public SceneAssigner(SceneNodeSource source, WorldSceneConfigs worldConfigs) {
        this(source, worldConfigs, ChannelSelector.withoutReservations(source));
    }

    public SceneAssigner(SceneNodeSource source, WorldSceneConfigs worldConfigs, ChannelSelector selector) {
        this.source = source;
        this.worldConfigs = worldConfigs;
        this.selector = selector;
    }

    public AssignSceneResponse assign(AssignSceneRequest request) {
        int zoneId = request.getZoneId();
        if (zoneId == 0) {
            log.warn("场景分配请求缺 zone_id player={}", request.getPlayerId());
            return tip(TIP_BAD_REQUEST);
        }

        List<SceneNodeInfo> nodes = source.list(zoneId).stream().filter(n -> isUsable(n, zoneId)).toList();
        long playerId = request.getPlayerId();
        int preferred = request.getPreferredSceneConfigId();

        Optional<ChannelSelector.Choice> best = Optional.empty();
        if (request.getPreferredSceneId() != 0) {
            ChannelSelector.Choice instance = findInstance(nodes, request.getPreferredSceneNodeId(), request.getPreferredSceneId());
            if (instance != null && !instance.scene().getDraining()) {
                selector.reserveInstance(zoneId, instance.scene().getSceneId(), playerId);
                best = Optional.of(instance);
            } else {
                log.info("原场景实例{}，按地图重选 player={} node={} scene={} preferred={}",
                        instance == null ? "已不在" : "在排空中", playerId, request.getPreferredSceneNodeId(),
                        Long.toUnsignedString(request.getPreferredSceneId()), preferred);
            }
        }
        if (best.isEmpty() && preferred != 0) {
            if (worldConfigs.isWorld(preferred)) {
                best = selector.select(zoneId, nodes, preferred, 0, playerId);
            } else {
                log.info("期望地图不是世界地图，回落默认 player={} preferred={}", playerId, preferred);
            }
        }
        if (best.isEmpty()) {
            best = selector.select(zoneId, nodes, worldConfigs.defaultConfigId(), 0, playerId);
        }
        if (best.isEmpty()) {
            log.warn("没有可分配的场景 zone={} preferred={} default={} nodes={} player={}",
                    zoneId, preferred, worldConfigs.defaultConfigId(), nodes.size(), playerId);
            return tip(TIP_NO_SCENE);
        }

        ChannelSelector.Choice choice = best.get();
        log.debug("场景已分配 zone={} player={} node={} scene={} config={} count={}",
                zoneId, playerId, choice.nodeId(), Long.toUnsignedString(choice.scene().getSceneId()),
                choice.scene().getSceneConfigId(), choice.scene().getPlayerCount());
        return AssignSceneResponse.newBuilder()
                .setSceneNodeId(choice.nodeId())
                .setSceneId(choice.scene().getSceneId())
                .setSceneConfigId(choice.scene().getSceneConfigId())
                .build();
    }

    /** 在（已过滤的）可用节点上找指定的场景实例（玩家位置记录里的原实例）；没指定或已不在返回 null（在排空中也返回，由调用方判断）。 */
    private static ChannelSelector.Choice findInstance(List<SceneNodeInfo> nodes, int nodeId, long sceneId) {
        if (sceneId == 0) {
            return null;
        }
        for (SceneNodeInfo node : nodes) {
            if (node.getNodeId() != nodeId) {
                continue;
            }
            for (SceneEntry scene : node.getScenesList()) {
                if (scene.getSceneId() == sceneId) {
                    return new ChannelSelector.Choice(node.getNodeId(), scene);
                }
            }
        }
        return null;
    }

    /**
     * 目录条目是否可分配。条目由节点自己上报，这里只挡住明显不可达 / 串 zone 的坏条目（上报方 bug），
     * 挡住即告警：把玩家分到一个 gate 连不上的节点，失败会推迟到进场阶段才暴露。
     * 频道计划的领导者用同一判法决定「节点在场」（{@code com.game.scenemanager.world.DirectoryView}）。
     */
    public static boolean isUsable(SceneNodeInfo node, int zoneId) {
        // link_port 是 uint32：超过 2^31 的值在 Java int 里为负，按无符号比较才能挡住。
        int port = node.getLinkPort();
        if (node.getNodeId() == 0 || node.getZoneId() != zoneId
                || node.getLinkHost().isEmpty() || port == 0 || Integer.compareUnsigned(port, 65535) > 0) {
            log.warn("跳过不完整的 scene 节点目录条目 zone={} entryZone={} node={} link={}:{}",
                    zoneId, node.getZoneId(), node.getNodeId(), node.getLinkHost(), node.getLinkPort());
            return false;
        }
        return true;
    }

    private static AssignSceneResponse tip(int tipId) {
        return AssignSceneResponse.newBuilder().setTipId(tipId).build();
    }
}
