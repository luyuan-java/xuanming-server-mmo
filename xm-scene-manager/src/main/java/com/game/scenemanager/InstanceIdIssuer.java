package com.game.scenemanager;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.CreateInstanceRequest;
import com.game.api.proto.CreateInstanceResponse;
import com.game.scenemanager.world.NodeAvailability;
import com.game.scenemanager.world.SceneIdSource;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 镜像 / 副本实例的取号与放置（批次 5.3，dungeon-mirror-spec §6.6；对应基线 SM createInstance 的「选点 + 发号」两步，
 * go/scene_manager/internal/logic/createscenelogic.go:454-472、:546-551）。<b>无状态</b>：不读也不写 Redis（发号租约的续期在
 * {@link SceneIdAllocator} 自己的线程上）、不写软预占、不调 scene，没有可回滚的东西（D1、D3）。
 *
 * <ol>
 *   <li><b>参数校验</b>，不合法回 {@link SceneAssigner#TIP_BAD_REQUEST}（3005，调用方编程错误）：{@code zone_id}、{@code requester_scene_node_id}、
 *       {@code scene_config_id} 为 0；{@code kind} 不是 MIRROR / DUNGEON；MIRROR 缺 {@code source_scene_id} / {@code mirror_config_id} /
 *       {@code player_id} 或带了 {@code dungeon_config_id}；DUNGEON 缺 {@code dungeon_config_id} 或带了 {@code source_scene_id} / {@code mirror_config_id}。
 *       <b>不复核配表与源</b>：节点是权威，已在逻辑线程上校验过；再读可能过时的目录只会多出误拒（与 5.2 {@code selectSwitchTarget} 的「只做选择」口径一致）。</li>
 *   <li><b>放置</b>：恒为发起节点（D2：发起节点就是源节点，它当然活着；基线缺省配置下同样恒共置）。5.5 的节点级排空标记接在
 *       {@link NodeAvailability}：发起节点不接新实例时回 {@link #TIP_NODE_UNAVAILABLE}（5.3 装配 {@link NodeAvailability#ALL}，不会出现）。</li>
 *   <li><b>发号</b>：{@link SceneIdSource#tryNext()}（生产 = {@link SceneIdAllocator}，与主世界频道同一个全服租约 worker，Q8）；
 *       为空（租约无效 / 已丢失）→ {@link Result#NO_LEASE}，提供方把它转成调用失败（scene 推 23 {1003}，Q12），不是业务拒绝。</li>
 * </ol>
 * 线程安全（Dubbo 业务线程并发调用）：无可变字段，发号器自身线程安全。
 */
public final class InstanceIdIssuer {

    private static final Logger log = LoggerFactory.getLogger(InstanceIdIssuer.class);

    /** 发起节点不接新实例（5.5 钩子）：同「没有可分配的场景」{@link SceneAssigner#TIP_NO_SCENE}（3000）。 */
    public static final int TIP_NODE_UNAVAILABLE = SceneAssigner.TIP_NO_SCENE;

    /** 一次取号的结局（{@code xm_scene_manager_instance_seconds{result}}；{@code error} 由提供方在异常时计）。 */
    public enum Result {
        OK,
        /** 参数不合法（{@link SceneAssigner#TIP_BAD_REQUEST}）。 */
        BAD_REQUEST,
        /** 发起节点不接新实例（{@link #TIP_NODE_UNAVAILABLE}，5.5 钩子）。 */
        NODE_UNAVAILABLE,
        /** 发号租约无效：调用失败，不是业务拒绝。 */
        NO_LEASE
    }

    /**
     * 取号结果。
     *
     * @param result   结局
     * @param response 给调用方的应答；{@link Result#NO_LEASE} 时为 null（提供方以异常完成 future）
     */
    public record Issue(Result result, CreateInstanceResponse response) {
    }

    private final SceneIdSource ids;
    private final NodeAvailability availability;

    /**
     * @param ids          全服 scene_id 发号（生产 {@link SceneIdAllocator}）
     * @param availability 节点能否接新实例（5.3 用 {@link NodeAvailability#ALL}；实现须线程安全，这里在 Dubbo 业务线程上调）
     */
    public InstanceIdIssuer(SceneIdSource ids, NodeAvailability availability) {
        this.ids = ids;
        this.availability = availability;
    }

    public Issue issue(CreateInstanceRequest request) {
        String problem = problem(request);
        if (problem != null) {
            log.warn("实例取号请求参数不合法（{}）zone={} node={} player={} kind={} source={} conf={} mirror={} dungeon={}", problem,
                    Integer.toUnsignedString(request.getZoneId()), Integer.toUnsignedString(request.getRequesterSceneNodeId()),
                    Long.toUnsignedString(request.getPlayerId()), request.getKindValue(),
                    Long.toUnsignedString(request.getSourceSceneId()), Integer.toUnsignedString(request.getSceneConfigId()),
                    Integer.toUnsignedString(request.getMirrorConfigId()), Integer.toUnsignedString(request.getDungeonConfigId()));
            return reject(Result.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        }
        int nodeId = request.getRequesterSceneNodeId();
        if (!availability.acceptsNewChannels(request.getZoneId(), nodeId)) {
            log.info("发起节点不接新实例，拒绝取号 zone={} node={} player={} kind={}", Integer.toUnsignedString(request.getZoneId()),
                    Integer.toUnsignedString(nodeId), Long.toUnsignedString(request.getPlayerId()), request.getKind());
            return reject(Result.NODE_UNAVAILABLE, TIP_NODE_UNAVAILABLE);
        }
        OptionalLong id = ids.tryNext();
        if (id.isEmpty()) {
            log.warn("scene_id 发号租约无效，实例取号失败 zone={} node={} player={} kind={}", Integer.toUnsignedString(request.getZoneId()),
                    Integer.toUnsignedString(nodeId), Long.toUnsignedString(request.getPlayerId()), request.getKind());
            return new Issue(Result.NO_LEASE, null);
        }
        log.debug("实例已取号 zone={} node={} player={} kind={} source={} conf={} scene_id={}",
                Integer.toUnsignedString(request.getZoneId()), Integer.toUnsignedString(nodeId),
                Long.toUnsignedString(request.getPlayerId()), request.getKind(), Long.toUnsignedString(request.getSourceSceneId()),
                Integer.toUnsignedString(request.getSceneConfigId()), Long.toUnsignedString(id.getAsLong()));
        return new Issue(Result.OK, CreateInstanceResponse.newBuilder()
                .setSceneNodeId(nodeId)
                .setSceneId(id.getAsLong())
                .build());
    }

    /** 参数为什么不合法；合法为 null。 */
    static String problem(CreateInstanceRequest request) {
        if (request.getZoneId() == 0) {
            return "zone_id 为 0";
        }
        if (request.getRequesterSceneNodeId() == 0) {
            return "requester_scene_node_id 为 0";
        }
        if (request.getSceneConfigId() == 0) {
            return "scene_config_id 为 0";
        }
        ChannelKind kind = request.getKind();
        if (kind == ChannelKind.CHANNEL_KIND_MIRROR) {
            if (request.getSourceSceneId() == 0 || request.getMirrorConfigId() == 0 || request.getPlayerId() == 0) {
                return "镜像缺 source_scene_id / mirror_config_id / player_id";
            }
            if (request.getDungeonConfigId() != 0) {
                return "镜像不能带 dungeon_config_id";
            }
            return null;
        }
        if (kind == ChannelKind.CHANNEL_KIND_DUNGEON) {
            if (request.getDungeonConfigId() == 0) {
                return "副本缺 dungeon_config_id";
            }
            if (request.getSourceSceneId() != 0 || request.getMirrorConfigId() != 0) {
                return "副本不能带 source_scene_id / mirror_config_id";
            }
            return null;
        }
        return "kind 不是 MIRROR / DUNGEON";
    }

    private static Issue reject(Result result, int tipId) {
        return new Issue(result, CreateInstanceResponse.newBuilder().setTipId(tipId).build());
    }
}
