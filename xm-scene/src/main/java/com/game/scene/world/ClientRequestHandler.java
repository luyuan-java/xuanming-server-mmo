package com.game.scene.world;

import static com.game.scene.world.SceneMessageIds.push;
import static com.game.scene.world.SceneMessageIds.reply;
import static com.game.scene.world.SceneMessageIds.tip;

import com.game.api.proto.ClientForward;
import com.game.common.RunMode;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.Empty;
import com.game.proto.ListSkillsResponse;
import com.game.proto.MessageContent;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.MoveSyncC2S;
import com.game.proto.PlayerSkillComp;
import com.game.proto.PlayerSkillListComp;
import com.game.proto.ReleaseSkillRequest;
import com.game.proto.ReleaseSkillResponse;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoS2C;
import com.game.proto.SkillUsedS2C;
import com.game.proto.TipInfoMessage;
import com.game.table.CommonErrorTip;
import com.game.table.SceneErrorTip;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进场后的客户端消息（gate 经节点链路转来的 {@code ClientForward}）：校验后按消息号分发给注册的处理器。只在场景逻辑线程上调用。
 *
 * <p>规则按契约文档 §4：
 * <ul>
 *   <li>静默丢弃（不回包）：会话不在本节点或还在加载、player_id 与会话不符、消息号未知或不是 scene 的客户端服务、
 *       请求体解析失败、字段超规模或有符号整数为负（{@link RequestFieldCheck}，基线 ProtoFieldChecker）；</li>
 *   <li>GM 类指令（方法名 {@code Gm*} / {@code Debug*} / {@code Test*}）在运行模式不是 dev / test 时回应答内
 *       {@code error_message{1006}}，不调处理器——gate 是第一道锁，这里防绕开 gate 直连链路端口（基线 SCENE_RUN_MODE）；</li>
 *   <li>应答类型是 {@code Empty} 的方法（所有 Notify*、SceneInfoC2S、移动三条上行）不回包；没有处理器的这类方法静默忽略；</li>
 *   <li>通过会话与消息号校验的每条消息都刷新该玩家的活跃帧（挂机判定，见 {@link SceneWorld#touch}）；</li>
 *   <li>应答的 {@code message_id} 同请求、{@code id} 回显请求号；应答里的 {@code error_message} 总是带上（成功时 id=0）；</li>
 *   <li>没有处理器的方法回 {@code kFeatureUnavailable}(1006)，不断连、不抛异常。136 TeleportRequest 也在其中
 *       （基线是空桩，回 id=0 的空 tip；Java 如实说「不支持」，PARITY 登记为有意差异）。</li>
 * </ul>
 * 处理器按功能注册（{@link SceneFeature}）；本类自己注册场景核心的移动、技能、换场景、场景信息。
 */
public final class ClientRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientRequestHandler.class);

    private static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    private static final int INVALID_TABLE_ID = CommonErrorTip.common_error.kInvalidTableId_VALUE;
    private static final int ENTER_PARAM_ERROR = SceneErrorTip.scene_error.kEnterSceneParamError_VALUE;
    private static final int ENTER_IN_CURRENT_SCENE = SceneErrorTip.scene_error.kEnterSceneYouInCurrentScene_VALUE;
    private static final int ENTER_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;

    private record Registered(Class<? extends Message> requestType, PlayerRequestHandler<Message> handler) {
    }

    private final SceneWorld world;
    private final MessageIdRegistry registry;
    private final SceneMessageIds ids;
    private final SceneTables tables;
    private final RunMode runMode;
    private final Map<Integer, Registered> handlers = new HashMap<>();

    /** 只有场景核心功能、生产运行模式（测试用）。 */
    public ClientRequestHandler(SceneWorld world, MessageIdRegistry registry, SceneMessageIds ids, SceneTables tables) {
        this(world, registry, ids, tables, RunMode.PROD, List.of());
    }

    /**
     * @param runMode  运行模式：只决定 GM 类指令放不放行
     * @param features 玩法功能（各自注册自己的方法；同一方法注册两次启动即失败）
     */
    public ClientRequestHandler(SceneWorld world, MessageIdRegistry registry, SceneMessageIds ids, SceneTables tables,
                                RunMode runMode, List<SceneFeature> features) {
        this.world = world;
        this.registry = registry;
        this.ids = ids;
        this.tables = tables;
        this.runMode = runMode;
        registerCore();
        SceneFeature.Registrar registrar = new SceneFeature.Registrar() {
            @Override
            public <Q extends Message> void on(String service, String method, Class<Q> requestType,
                                               PlayerRequestHandler<Q> handler) {
                register(registry.requireId(service, method), requestType, handler);
            }
        };
        for (SceneFeature feature : features) {
            feature.register(registrar);
        }
    }

    private void registerCore() {
        register(ids.moveSync(), MoveSyncC2S.class, (call, req) -> world.applyMove(call.player(), MoveInput.of(req)));
        register(ids.moveStart(), MoveStartC2S.class, (call, req) -> world.applyMove(call.player(), MoveInput.of(req)));
        register(ids.moveStop(), MoveStopC2S.class, (call, req) -> world.applyMove(call.player(), MoveInput.of(req)));
        register(ids.listSkills(), Message.class, (call, req) -> listSkills(call));
        register(ids.releaseSkill(), ReleaseSkillRequest.class, this::releaseSkill);
        register(ids.enterScene(), EnterSceneC2SRequest.class, this::enterScene);
        // 应答是 Empty 不回包，改推 31（基线 player_scene_handler.cpp SceneInfoC2S）。
        register(ids.sceneInfoC2S(), Message.class, (call, req) -> world.sendTo(call.player(),
                push(ids.notifySceneInfo(), SceneInfoS2C.newBuilder().addSceneInfo(call.player().scene().info()).build())));
    }

    @SuppressWarnings("unchecked")
    private <Q extends Message> void register(int messageId, Class<Q> requestType, PlayerRequestHandler<Q> handler) {
        MessageMethod method = registry.byId(messageId)
                .orElseThrow(() -> new IllegalStateException("契约里没有消息号 " + messageId));
        if (!method.playerService() || !method.clientService()) {
            throw new IllegalStateException(method.key() + " 不是 scene 的客户端玩家服务，不能在 scene 注册");
        }
        if (!requestType.isInstance(method.requestPrototype())) {
            throw new IllegalStateException(method.key() + " 的请求类型是 " + method.requestPrototype().getClass().getName()
                    + "，注册的是 " + requestType.getName());
        }
        if (handlers.putIfAbsent(messageId, new Registered(requestType, (PlayerRequestHandler<Message>) handler)) != null) {
            throw new IllegalStateException(method.key() + " 注册了两次");
        }
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
        world.touch(player);
        Message request;
        try {
            request = method.requestPrototype().getParserForType().parseFrom(forward.getBody());
        } catch (InvalidProtocolBufferException e) {
            log.warn("请求体解析失败，丢弃 message_id={} player={}", forward.getMessageId(), player.playerId());
            return;
        }
        String violation = RequestFieldCheck.violation(request);
        if (violation != null) {
            log.warn("请求字段超规模或含负数，丢弃 {} player={}：{}", method.key(), player.playerId(), violation);
            return;
        }
        long requestId = forward.getRequestId();
        boolean hasResponse = !(method.responsePrototype() instanceof Empty);

        if (method.gmCommand() && !runMode.allowsGmCommands()) {
            log.warn("运行模式不允许 GM 指令（绕过了 gate 的闸？），拒绝 {} player={}", method.key(), player.playerId());
            if (hasResponse) {
                replyUnavailable(player, method, requestId);
            }
            return;
        }
        Registered registered = handlers.get(method.messageId());
        if (registered == null) {
            if (hasResponse) {
                replyUnavailable(player, method, requestId);
            } else {
                log.debug("客户端发来无应答的方法，忽略 {} player={}", method.key(), player.playerId());
            }
            return;
        }
        PlayerCall call = new PlayerCall(world, player, method, requestId);
        try {
            registered.handler().handle(call, request);
        } catch (RuntimeException e) {
            // 处理器的编程错误：留 ERROR 待修；逻辑线程继续服务其他玩家，下面照常补应答
            log.error("处理器抛出异常 {} player={}", method.key(), player.playerId(), e);
        }
        if (hasResponse && !call.replied()) {
            // 处理器的编程错误（没回或中途抛了）：客户端在等应答，回 1006 不让它卡住
            log.error("处理器没有回应答，补回 1006 {} player={}", method.key(), player.playerId());
            replyUnavailable(player, method, requestId);
        }
    }

    /**
     * 77：{@code skill_list} 必须存在（没有技能也要是空列表，robot 靠它发就绪信号）；{@code error_message} 存在且 id=0。
     */
    private void listSkills(PlayerCall call) {
        PlayerSkillListComp.Builder list = PlayerSkillListComp.newBuilder();
        for (int skillTableId : call.player().skills()) {
            list.addSkillList(PlayerSkillComp.newBuilder().setSkillTableId(skillTableId));
        }
        call.reply(ListSkillsResponse.newBuilder().setErrorMessage(tip(0)).setSkillList(list).build());
    }

    /**
     * 84：技能不存在或未拥有回 {@code kInvalidTableId}(1001)。基线写的 1001 会被 TRANSFER_ERROR_MESSAGE 覆盖成空 tip
     * （契约文档 §7.5），Java 版如实回码；robot 对任何非空 error_message 都只记告警，不受影响。
     * 成功时先向自己和看得见自己的玩家广播 70，再回应答（与基线同序）。战斗结算（冷却、消耗、命中）首批不做。
     */
    private void releaseSkill(PlayerCall call, ReleaseSkillRequest request) {
        ScenePlayer player = call.player();
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
            world.broadcastToSelfAndWatchers(player, push(ids.notifySkillUsed(), used));
        }
        call.reply(ReleaseSkillResponse.newBuilder().setErrorMessage(tip(tipId)).build());
    }

    /**
     * 63：按基线顺序校验（契约文档 §4.3），先回应答（无错 = 已受理），再在本节点内换场景。
     * Java 场景节点都是主世界节点、没有战斗、换场景同步完成，所以 3004 / 3014 与「战斗在途」的 3023 不会出现；
     * 镜像场景与跨节点换场景首批不做，回 3023。
     */
    private void enterScene(PlayerCall call, EnterSceneC2SRequest request) {
        ScenePlayer player = call.player();
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
        call.reply(EnterSceneC2SResponse.newBuilder().setErrorMessage(tip(tipId)).build());
        if (target != null && target != current) {
            world.switchScene(player, target);
        }
    }

    /**
     * 不能处理的方法：应答里有 {@code TipInfoMessage error_message} 字段就写进去（与基线拒绝码的线上形态一致），
     * 否则放在信封的 {@code MessageContent.error_message}。
     */
    private void replyUnavailable(ScenePlayer player, MessageMethod method, long requestId) {
        log.debug("回 kFeatureUnavailable {} player={}", method.key(), player.playerId());
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
