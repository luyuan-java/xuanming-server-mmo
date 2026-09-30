package com.game.scene.world;

import static com.game.scene.world.SceneMessageIds.push;
import static com.game.scene.world.SceneMessageIds.reply;
import static com.game.scene.world.SceneMessageIds.tip;

import com.game.api.proto.ClientForward;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.Empty;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.game.proto.PlayerSkillComp;
import com.game.proto.PlayerSkillListComp;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.ListSkillsResponse;
import com.game.proto.ReleaseSkillRequest;
import com.game.proto.ReleaseSkillResponse;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoS2C;
import com.game.proto.SkillUsedS2C;
import com.game.table.CommonErrorTip;
import com.game.table.SceneErrorTip;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进场后的客户端消息（gate 经节点链路转来的 {@code ClientForward}）。只在场景逻辑线程上调用。
 *
 * <p>规则按契约文档 §4：
 * <ul>
 *   <li>静默丢弃（不回包）：会话不在本节点或还在加载、player_id 与会话不符、消息号未知或不是 scene 的客户端服务、
 *       请求体解析失败；</li>
 *   <li>应答类型是 {@code Empty} 的方法（所有 Notify*、SceneInfoC2S）不回包；</li>
 *   <li>应答的 {@code message_id} 同请求、{@code id} 回显请求号；应答里的 {@code error_message} 总是带上
 *       （成功时 id=0，与基线线上形态一致）；</li>
 *   <li>首批未实现的方法回 {@code kFeatureUnavailable}(1006)，不断连、不抛异常。</li>
 * </ul>
 */
public final class ClientRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientRequestHandler.class);

    private static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    private static final int INVALID_TABLE_ID = CommonErrorTip.common_error.kInvalidTableId_VALUE;
    private static final int ENTER_PARAM_ERROR = SceneErrorTip.scene_error.kEnterSceneParamError_VALUE;
    private static final int ENTER_IN_CURRENT_SCENE = SceneErrorTip.scene_error.kEnterSceneYouInCurrentScene_VALUE;
    private static final int ENTER_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;

    private final SceneWorld world;
    private final MessageIdRegistry registry;
    private final SceneMessageIds ids;
    private final SceneTables tables;

    public ClientRequestHandler(SceneWorld world, MessageIdRegistry registry, SceneMessageIds ids, SceneTables tables) {
        this.world = world;
        this.registry = registry;
        this.ids = ids;
        this.tables = tables;
    }

    public void onClientForward(long linkId, ClientForward forward) {
        SessionKey key = new SessionKey(linkId, forward.getSessionId());
        ScenePlayer player = world.playerBySession(key);
        if (player == null) {
            log.debug("会话不在本节点或仍在加载，丢弃 message_id={} session={}", forward.getMessageId(), key);
            return;
        }
        if (player.playerId() != forward.getPlayerId()) {
            log.warn("转发帧的 player_id 与会话上的玩家不符，丢弃 帧={} 会话上={} message_id={}",
                    forward.getPlayerId(), player.playerId(), forward.getMessageId());
            return;
        }
        Optional<MessageMethod> found = registry.byId(forward.getMessageId());
        // 只有标了 OptionIsPlayerService 的客户端服务归 scene 处理（按服务语义，不看 proto 所在目录）
        if (found.isEmpty() || !found.get().playerService() || !found.get().clientService()) {
            log.warn("不是 scene 的客户端消息，丢弃 message_id={} player={}", forward.getMessageId(), player.playerId());
            return;
        }
        MessageMethod method = found.get();
        Message request;
        try {
            request = method.requestPrototype().getParserForType().parseFrom(forward.getBody());
        } catch (InvalidProtocolBufferException e) {
            log.warn("请求体解析失败，丢弃 message_id={} player={}", forward.getMessageId(), player.playerId());
            return;
        }

        int messageId = method.messageId();
        long requestId = forward.getRequestId();
        if (messageId == ids.listSkills()) {
            listSkills(player, messageId, requestId);
        } else if (messageId == ids.releaseSkill()) {
            releaseSkill(player, (ReleaseSkillRequest) request, messageId, requestId);
        } else if (messageId == ids.enterScene()) {
            enterScene(player, (EnterSceneC2SRequest) request, messageId, requestId);
        } else if (messageId == ids.sceneInfoC2S()) {
            // 应答是 Empty 不回包，改推 31（基线 player_scene_handler.cpp SceneInfoC2S）。
            world.sendTo(player, push(ids.notifySceneInfo(),
                    SceneInfoS2C.newBuilder().addSceneInfo(player.scene().info()).build()));
        } else if (method.responsePrototype() instanceof Empty) {
            log.debug("客户端发来无应答的方法，忽略 {} player={}", method.key(), player.playerId());
        } else {
            replyUnavailable(player, method, requestId);
        }
    }

    /**
     * 77：{@code skill_list} 必须存在（没有技能也要是空列表，robot 靠它发就绪信号）；{@code error_message} 存在且 id=0。
     */
    private void listSkills(ScenePlayer player, int messageId, long requestId) {
        PlayerSkillListComp.Builder list = PlayerSkillListComp.newBuilder();
        for (int skillTableId : player.skills()) {
            list.addSkillList(PlayerSkillComp.newBuilder().setSkillTableId(skillTableId));
        }
        ListSkillsResponse response = ListSkillsResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setSkillList(list)
                .build();
        world.sendTo(player, reply(messageId, requestId, response));
    }

    /**
     * 84：技能不存在或未拥有回 {@code kInvalidTableId}(1001)。基线写的 1001 会被 TRANSFER_ERROR_MESSAGE 覆盖成空 tip
     * （契约文档 §7.5），Java 版如实回码；robot 对任何非空 error_message 都只记告警，不受影响。
     * 成功时先向自己和视野内玩家广播 70，再回应答（与基线同序）。战斗结算（冷却、消耗、命中）首批不做。
     */
    private void releaseSkill(ScenePlayer player, ReleaseSkillRequest request, int messageId, long requestId) {
        int skillTableId = request.getSkillTableId();
        int tipId = 0;
        if (!tables.skillExists(skillTableId) || !player.hasSkill(skillTableId)) {
            tipId = INVALID_TABLE_ID;
            log.debug("放技能被拒：技能不存在或未拥有 player={} skill_table_id={}", player.playerId(), skillTableId);
        } else {
            SkillUsedS2C used = SkillUsedS2C.newBuilder()
                    .setEntity(player.entity())
                    .addTargetEntity(request.getTargetId())
                    .setSkillTableId(skillTableId)
                    .setPosition(request.getPosition())
                    .build();
            world.broadcastToSelfAndViewers(player, push(ids.notifySkillUsed(), used));
        }
        world.sendTo(player, reply(messageId, requestId,
                ReleaseSkillResponse.newBuilder().setErrorMessage(tip(tipId)).build()));
    }

    /**
     * 63：按基线顺序校验（契约文档 §4.3），先回应答（无错 = 已受理），再在本节点内换场景。
     * Java 场景节点都是主世界节点、没有战斗、换场景同步完成，所以 3004 / 3014 与「战斗在途」的 3023 不会出现；
     * 镜像场景与跨节点换场景首批不做，回 3023。
     */
    private void enterScene(ScenePlayer player, EnterSceneC2SRequest request, int messageId, long requestId) {
        SceneInfoComp want = request.getSceneInfo();
        Scene current = player.scene();
        Scene target = null;
        int tipId;
        if (want.getSceneConfigId() == 0 && want.getSceneId() == 0 && want.getMirrorConfigId() == 0) {
            tipId = ENTER_PARAM_ERROR;
        } else if (want.getMirrorConfigId() != 0 && want.getSceneId() == 0) {
            tipId = ENTER_FAILED;
        } else if (want.getSceneId() != 0 && want.getSceneId() == current.sceneId()) {
            tipId = ENTER_IN_CURRENT_SCENE;
        } else {
            target = world.resolveSwitchTarget(current, want.getSceneId(), want.getSceneConfigId());
            if (target == null) {
                tipId = ENTER_FAILED;
            } else if (want.getSceneConfigId() != 0 && target.configId() != want.getSceneConfigId()) {
                tipId = ENTER_PARAM_ERROR;
                target = null;
            } else {
                tipId = 0;
            }
        }
        world.sendTo(player, reply(messageId, requestId,
                EnterSceneC2SResponse.newBuilder().setErrorMessage(tip(tipId)).build()));
        if (target != null && target != current) {
            world.switchScene(player, target);
        }
    }

    /**
     * 首批未实现的方法：应答里有 {@code TipInfoMessage error_message} 字段就写进去（与基线拒绝码的线上形态一致），
     * 否则放在信封的 {@code MessageContent.error_message}。
     */
    private void replyUnavailable(ScenePlayer player, MessageMethod method, long requestId) {
        log.debug("未实现的方法，回 kFeatureUnavailable {} player={}", method.key(), player.playerId());
        Message prototype = method.responsePrototype();
        FieldDescriptor field = prototype.getDescriptorForType().findFieldByName("error_message");
        MessageContent content;
        if (field != null && field.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                && field.getMessageType().getFullName().equals(TipInfoMessage.getDescriptor().getFullName())) {
            Message response = prototype.toBuilder().setField(field, tip(FEATURE_UNAVAILABLE)).build();
            content = reply(method.messageId(), requestId, response);
        } else {
            content = MessageContent.newBuilder()
                    .setMessageId(method.messageId())
                    .setId(requestId)
                    .setErrorMessage(tip(FEATURE_UNAVAILABLE))
                    .build();
        }
        world.sendTo(player, content);
    }
}
