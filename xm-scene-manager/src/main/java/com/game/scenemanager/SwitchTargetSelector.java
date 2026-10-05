package com.game.scenemanager;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在线换图（63）的跨节点选目标（批次 5.2，scene-handoff-spec §5.4）：源 scene 节点本地解析不了去向时（显式 scene_id 不在本节点；
 * 或只带地图而本节点没有该图的承载中频道，只在 {@code coverage=hash} 下出现），由这里在节点目录里选 (节点, 场景)。
 *
 * <p>规则：
 * <ol>
 *   <li>{@code zone_id} 或 {@code player_id} 为 0、或 {@code want_scene_id} 与 {@code want_scene_config_id} 都为 0 → {@link SceneAssigner#TIP_BAD_REQUEST}，
 *       不读目录（scene 侧已先挡掉「三个号全 0」，到这里是调用方编程错误）。</li>
 *   <li><b>显式场景号</b>（{@code want_scene_id ≠ 0}）：在本 zone 可用节点的目录里找这个场景号。找不到、在排空中（5.1 D11）、
 *       或出现在不止一个节点上（节点身份歧义，同基线 {@code isKnownNodeIdentityAmbiguous} 拒绝而不是任选其一）→ {@link SceneAssigner#TIP_NO_SCENE}；
 *       指定了配置而与该场景不符 → {@link SceneAssigner#TIP_BAD_REQUEST}。<b>不回落</b>到「按地图挑」，与基线显式 scene_id 的解析一致
 *       （mmorpg enterscenelogic.go resolveScene 的 Case 1）。选中后给它记一条软预占，让并发分配看得见这位正在赶来的玩家
 *       （同 5.1 D20「原实例直接用且也写预占」；基线显式解析不预占）。</li>
 *   <li><b>只带地图</b>（{@code want_scene_id = 0}）：地图必须是世界地图（否则 {@link SceneAssigner#TIP_NO_SCENE}；副本地图 17–19 不是世界地图，
 *       在这里就挡住了），然后 {@link ChannelSelector}：排除 {@code from_scene_id}、按「目录人数 + 未到期预占」选最少者并写软预占（5.1 D7，拒绝出口不需要退还）。
 *       <b>镜像的配置号就是源频道的世界地图</b>，上面那条挡不住镜像，靠 {@link ChannelSelector} 的种类过滤（只认主世界频道，批次 5.3 R1、勘误 E13）——
 *       私有实例不能按人数塞人。没有候选 → {@link SceneAssigner#TIP_NO_SCENE}。选中的频道可能就在源节点上（目录比源节点的本地视图新），
 *       由 scene 自己按本地换处理。</li>
 *   <li>显式场景号命中镜像 / 副本实例照常返回并写预占（任何知道号的同 zone 玩家都能按号加入，同基线不查 creators；5.3 §6.12）；
 *       实例在回收宽限 / 级联排空中目录报 draining → {@link Result#DRAINING}。</li>
 * </ol>
 *
 * <p>契约（同 {@link SceneAssigner}）：
 * <ul>
 *   <li>只做选择：不铸 epoch、不写位置记录、不碰归属；唯一的副作用是软预占（带 TTL、以 player_id 为成员）。</li>
 *   <li>业务拒绝放在 {@code tip_id}（scene 对客户端一律推 3023，这里的码只进日志与指标）；{@link SceneNodeSource} 与预占存储抛出的基础设施异常
 *       原样上抛，由 Dubbo 提供方转成调用失败（scene 推 1003），不伪装成「无场景」。</li>
 *   <li>无可变状态，线程安全（Dubbo 业务线程并发调用）。</li>
 * </ul>
 */
public final class SwitchTargetSelector {

    private static final Logger log = LoggerFactory.getLogger(SwitchTargetSelector.class);

    /** 一次选目标的结局（{@code xm_scene_manager_switch_seconds{result}}；{@code error} 由提供方在异常时计）。 */
    public enum Result {
        OK,
        /** 显式场景号不在目录里 / 身份歧义；只带地图时没有可选频道或地图不是世界地图。 */
        NOT_FOUND,
        /** 显式场景号在排空中。 */
        DRAINING,
        /** 参数不合法（zone / player 为 0、两个期望号都为 0、显式场景号与期望配置不符）。 */
        BAD_REQUEST
    }

    /** 选择结果：给调用方的应答 + 指标用的结局（应答里 not_found 与 draining 同码，靠这里区分）。 */
    public record Selection(Result result, SelectSwitchTargetResponse response) {
    }

    private final SceneNodeSource source;
    private final WorldSceneConfigs worldConfigs;
    private final ChannelSelector selector;

    public SwitchTargetSelector(SceneNodeSource source, WorldSceneConfigs worldConfigs, ChannelSelector selector) {
        this.source = source;
        this.worldConfigs = worldConfigs;
        this.selector = selector;
    }

    public Selection select(SelectSwitchTargetRequest request) {
        int zoneId = request.getZoneId();
        long playerId = request.getPlayerId();
        long wantSceneId = request.getWantSceneId();
        int wantConfigId = request.getWantSceneConfigId();
        if (zoneId == 0 || playerId == 0 || (wantSceneId == 0 && wantConfigId == 0)) {
            log.warn("换图选目标请求参数不全 zone={} player={} wantScene={} wantConfig={}",
                    zoneId, playerId, Long.toUnsignedString(wantSceneId), wantConfigId);
            return reject(Result.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        }
        if (wantSceneId == 0 && !worldConfigs.isWorld(wantConfigId)) {
            log.info("换图期望地图不是世界地图，不按负载选 zone={} player={} wantConfig={}", zoneId, playerId, wantConfigId);
            return reject(Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
        }

        List<SceneNodeInfo> nodes = source.list(zoneId).stream().filter(n -> SceneAssigner.isUsable(n, zoneId)).toList();
        return wantSceneId != 0
                ? selectExplicit(request, nodes)
                : selectByMap(request, nodes);
    }

    /** 显式场景号：只认目录里这一个实例，不回落按地图挑。 */
    private Selection selectExplicit(SelectSwitchTargetRequest request, List<SceneNodeInfo> nodes) {
        long wantSceneId = request.getWantSceneId();
        ChannelSelector.Choice found = null;
        int holders = 0;
        for (SceneNodeInfo node : nodes) {
            for (SceneEntry scene : node.getScenesList()) {
                if (scene.getSceneId() == wantSceneId) {
                    if (found == null) {
                        found = new ChannelSelector.Choice(node.getNodeId(), scene);
                    }
                    holders++;
                }
            }
        }
        if (found == null) {
            log.info("换图目标场景不在目录里 zone={} player={} from={}/{} want={}", request.getZoneId(), request.getPlayerId(),
                    request.getFromSceneNodeId(), Long.toUnsignedString(request.getFromSceneId()),
                    Long.toUnsignedString(wantSceneId));
            return reject(Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
        }
        if (holders > 1) {
            // 场景号由发号器发、迁移一律换新号（5.1 D4），同号出现在两个节点上只能是节点身份歧义或上报方 bug：拒绝而不是任选其一。
            log.warn("换图目标场景号出现在多个节点的目录里，拒绝 zone={} player={} want={} holders={}",
                    request.getZoneId(), request.getPlayerId(), Long.toUnsignedString(wantSceneId), holders);
            return reject(Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
        }
        SceneEntry scene = found.scene();
        if (request.getWantSceneConfigId() != 0 && scene.getSceneConfigId() != request.getWantSceneConfigId()) {
            log.info("换图目标场景与期望地图不符 zone={} player={} want={} wantConfig={} actualConfig={}",
                    request.getZoneId(), request.getPlayerId(), Long.toUnsignedString(wantSceneId),
                    request.getWantSceneConfigId(), scene.getSceneConfigId());
            return reject(Result.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        }
        if (scene.getDraining()) {
            log.info("换图目标场景在排空中 zone={} player={} node={} want={}", request.getZoneId(), request.getPlayerId(),
                    found.nodeId(), Long.toUnsignedString(wantSceneId));
            return reject(Result.DRAINING, SceneAssigner.TIP_NO_SCENE);
        }
        selector.reserveInstance(request.getZoneId(), wantSceneId, request.getPlayerId());
        return chosen(request, found);
    }

    /** 只带地图（已确认是世界地图）：频道里排除源场景、按负载选并软预占。 */
    private Selection selectByMap(SelectSwitchTargetRequest request, List<SceneNodeInfo> nodes) {
        int wantConfigId = request.getWantSceneConfigId();
        Optional<ChannelSelector.Choice> best =
                selector.select(request.getZoneId(), nodes, wantConfigId, request.getFromSceneId(), request.getPlayerId());
        if (best.isEmpty()) {
            log.info("换图没有可选的频道 zone={} player={} wantConfig={} from={}/{} nodes={}", request.getZoneId(),
                    request.getPlayerId(), wantConfigId, request.getFromSceneNodeId(),
                    Long.toUnsignedString(request.getFromSceneId()), nodes.size());
            return reject(Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
        }
        return chosen(request, best.get());
    }

    private static Selection chosen(SelectSwitchTargetRequest request, ChannelSelector.Choice choice) {
        log.debug("换图目标已选 zone={} player={} from={}/{} to={}/{} config={} count={}", request.getZoneId(),
                request.getPlayerId(), request.getFromSceneNodeId(), Long.toUnsignedString(request.getFromSceneId()),
                choice.nodeId(), Long.toUnsignedString(choice.scene().getSceneId()), choice.scene().getSceneConfigId(),
                choice.scene().getPlayerCount());
        return new Selection(Result.OK, SelectSwitchTargetResponse.newBuilder()
                .setSceneNodeId(choice.nodeId())
                .setSceneId(choice.scene().getSceneId())
                .setSceneConfigId(choice.scene().getSceneConfigId())
                .build());
    }

    private static Selection reject(Result result, int tipId) {
        return new Selection(result, SelectSwitchTargetResponse.newBuilder().setTipId(tipId).build());
    }
}
