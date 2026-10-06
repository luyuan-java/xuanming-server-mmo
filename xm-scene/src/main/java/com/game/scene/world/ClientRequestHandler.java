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
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoS2C;
import com.game.proto.TipInfoMessage;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.BattleGate;
import com.game.scene.metrics.SceneMetrics.FrozenRejection;
import com.game.scene.metrics.SceneMetrics.MoveResult;
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
 *       （基线是空桩，回 id=0 的空 tip；Java 如实说「不支持」，PARITY 登记为有意差异）；</li>
 *   <li><b>冻结闸</b>（跨节点换图的交出事务在途，{@link ScenePlayer#frozen()}；scene-handoff-spec §5.9，D7）：排在 GM 闸与
 *       「有没有处理器」之后、调处理器之前，按注册时声明的 {@link FreezePolicy} 处理——DROP 静默丢（移动）、REJECT 回应答内
 *       {@code error_message{1005}}（缺省，没声明的方法都是它），其余照常进处理器（GATED 由服务闸回基线码）。
 *       基线没有中心闸、各系统自己查冻结；Java 收拢到这里、缺省拒绝，漏掉的闸由交出时的快照比对兜底检测。
 *       选目标中（RESOLVING）不冻结，不经这道闸。</li>
 * </ul>
 * 处理器按功能注册（{@link SceneFeature}）；本类自己注册场景核心的移动、技能列表（77）、换场景、场景信息
 * （放技能 84 在 {@code SkillFeature}）。
 */
public final class ClientRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientRequestHandler.class);

    private static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    /** 冻结中被 {@link FreezePolicy#REJECT} 拒的方法回的码（基线各写入口冻结时回的 kInvalidParameter）。 */
    private static final int FROZEN_REJECTED = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int ENTER_PARAM_ERROR = SceneErrorTip.scene_error.kEnterSceneParamError_VALUE;
    private static final int ENTER_IN_CURRENT_SCENE = SceneErrorTip.scene_error.kEnterSceneYouInCurrentScene_VALUE;
    private static final int ENTER_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;
    private static final int ENTER_CHANGING_SCENE = SceneErrorTip.scene_error.kEnterSceneChangingScene_VALUE;

    private record Registered(Class<? extends Message> requestType, FreezePolicy freeze, BattlePolicy battle,
                              PlayerRequestHandler<Message> handler) {
    }

    private final SceneWorld world;
    private final MessageIdRegistry registry;
    private final SceneMessageIds ids;
    private final RunMode runMode;
    private final SceneMetrics metrics;
    private final Map<Integer, Registered> handlers = new HashMap<>();

    /** 只有场景核心功能、生产运行模式（测试用）。 */
    public ClientRequestHandler(SceneWorld world, MessageIdRegistry registry, SceneMessageIds ids) {
        this(world, registry, ids, RunMode.PROD, List.of());
    }

    /**
     * @param runMode  运行模式：只决定 GM 类指令放不放行
     * @param features 玩法功能（各自注册自己的方法；同一方法注册两次启动即失败）
     */
    public ClientRequestHandler(SceneWorld world, MessageIdRegistry registry, SceneMessageIds ids,
                                RunMode runMode, List<SceneFeature> features) {
        this.world = world;
        this.registry = registry;
        this.ids = ids;
        this.runMode = runMode;
        this.metrics = world.metrics();
        registerCore();
        SceneFeature.Registrar registrar = new SceneFeature.Registrar() {
            @Override
            public <Q extends Message> void on(String service, String method, Class<Q> requestType,
                                               FreezePolicy freeze, BattlePolicy battle, PlayerRequestHandler<Q> handler) {
                if (freeze == FreezePolicy.DROP) {
                    // DROP 在分发处按「移动」计数，只给场景核心的移动上行；玩法功能要么拒（REJECT）要么经服务闸（GATED）
                    throw new IllegalStateException(service + "." + method + " 声明了 DROP：冻结中静默丢只给移动上行");
                }
                if (battle == BattlePolicy.DROP || battle == BattlePolicy.STOP_ONLY) {
                    throw new IllegalStateException(service + "." + method + " 声明了战斗策略 " + battle + "：只给场景核心的移动上行");
                }
                register(registry.requireId(service, method), requestType, freeze, battle, handler);
            }
        };
        for (SceneFeature feature : features) {
            feature.register(registrar);
        }
    }

    private void registerCore() {
        // 移动上行冻结中静默丢（基线 player_movement_handler 同样静默丢冻结实体的上报）：冻结前已 stopMotion，位置停在快照上
        register(ids.moveSync(), MoveSyncC2S.class, FreezePolicy.DROP, BattlePolicy.DROP,
                (call, req) -> world.applyMove(call.player(), MoveInput.of(req)));
        register(ids.moveStart(), MoveStartC2S.class, FreezePolicy.DROP, BattlePolicy.DROP,
                (call, req) -> world.applyMove(call.player(), MoveInput.of(req)));
        register(ids.moveStop(), MoveStopC2S.class, FreezePolicy.DROP, BattlePolicy.STOP_ONLY,
                (call, req) -> world.applyMove(call.player(), MoveInput.of(req)));
        register(ids.listSkills(), Message.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW, (call, req) -> listSkills(call));
        // 63 自己判在途（选目标中 / 冻结中都回 3014），冻结闸对它放行
        // 63 自己判战斗在途（3023，先于换图在途 3014，scene-battle-spec §7.13）
        register(ids.enterScene(), EnterSceneC2SRequest.class, FreezePolicy.ALLOW, BattlePolicy.GATED, this::enterScene);
        // 应答是 Empty 不回包，改推 31（基线 player_scene_handler.cpp SceneInfoC2S）。
        register(ids.sceneInfoC2S(), Message.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW, (call, req) -> world.sendTo(call.player(),
                push(ids.notifySceneInfo(), SceneInfoS2C.newBuilder().addSceneInfo(call.player().scene().info()).build())));
    }

    @SuppressWarnings("unchecked")
    private <Q extends Message> void register(int messageId, Class<Q> requestType, FreezePolicy freeze, BattlePolicy battle,
                                              PlayerRequestHandler<Q> handler) {
        MessageMethod method = registry.byId(messageId)
                .orElseThrow(() -> new IllegalStateException("契约里没有消息号 " + messageId));
        if (!method.playerService() || !method.clientService()) {
            throw new IllegalStateException(method.key() + " 不是 scene 的客户端玩家服务，不能在 scene 注册");
        }
        if (!requestType.isInstance(method.requestPrototype())) {
            throw new IllegalStateException(method.key() + " 的请求类型是 " + method.requestPrototype().getClass().getName()
                    + "，注册的是 " + requestType.getName());
        }
        if (freeze == null) {
            throw new IllegalStateException(method.key() + " 没有冻结策略");
        }
        if (freeze == FreezePolicy.DROP && !(method.responsePrototype() instanceof Empty)) {
            // 静默丢一个带应答的请求会让客户端一直等
            throw new IllegalStateException(method.key() + " 有应答，冻结策略不能是 DROP");
        }
        if (battle == null) {
            throw new IllegalStateException(method.key() + " 没有战斗策略");
        }
        if ((battle == BattlePolicy.DROP || battle == BattlePolicy.STOP_ONLY) && !(method.responsePrototype() instanceof Empty)) {
            throw new IllegalStateException(method.key() + " 有应答，战斗策略不能是 " + battle);
        }
        if (handlers.putIfAbsent(messageId,
                new Registered(requestType, freeze, battle, (PlayerRequestHandler<Message>) handler)) != null) {
            throw new IllegalStateException(method.key() + " 注册了两次");
        }
    }

    /**
     * 某消息号注册时声明的冻结策略；没有处理器为 null（测试与排查用：核对 §5.9 的逐方法策略表）。
     */
    public FreezePolicy freezePolicy(int messageId) {
        Registered registered = handlers.get(messageId);
        return registered == null ? null : registered.freeze();
    }

    /** 某消息号注册时声明的战斗策略；没有处理器为 null（测试与排查用：核对 scene-battle-spec §7.13 的逐方法策略表）。 */
    public BattlePolicy battlePolicy(int messageId) {
        Registered registered = handlers.get(messageId);
        return registered == null ? null : registered.battle();
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
        if (player.frozen()) {
            if (!admitWhileFrozen(player, method, registered.freeze(), requestId, hasResponse)) {
                return;
            }
        } else if (player.inBattle() && !admitInBattle(player, method, registered.battle(), requestId, hasResponse)) {
            // 两种冻结互斥（scene-battle-spec §7.13）：交出冻结按 FreezePolicy，否则战斗在途按 BattlePolicy
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
     * 冻结闸（§5.9）：冻结中的玩家发来已注册的方法，按声明的策略决定进不进处理器。返回 false = 已处理完（丢弃或已回拒绝），不调处理器。
     */
    private boolean admitWhileFrozen(ScenePlayer player, MessageMethod method, FreezePolicy freeze, long requestId,
                                     boolean hasResponse) {
        switch (freeze) {
            case ALLOW, READ_ONLY, GATED -> {
                return true;
            }
            case DROP -> {
                // 注册时保证 DROP 只给场景核心的移动上行（应答 Empty）：每条移动上行在 moves{result} 里恰好计一次
                metrics.move(MoveResult.FROZEN);
                metrics.frozenRejection(FrozenRejection.MOVE);
                log.debug("冻结中，丢弃移动上行 {} player={}", method.key(), player.playerId());
                return false;
            }
            case REJECT -> {
                metrics.frozenRejection(FrozenRejection.REQUEST);
                log.info("冻结中（跨节点换图交出在途），拒绝 {} player={}", method.key(),
                        Long.toUnsignedString(player.playerId()));
                if (hasResponse) {
                    replyError(player, method, requestId, FROZEN_REJECTED);
                }
                return false;
            }
        }
        throw new IllegalStateException("未知冻结策略 " + freeze);
    }

    /**
     * 战斗在途闸（scene-battle-spec §7.13，D11）：回合制战斗在途的玩家发来已注册的方法，按声明的战斗策略决定进不进处理器。
     * 返回 false = 已处理完（丢弃、只清速度或已回拒绝），不调处理器。
     */
    private boolean admitInBattle(ScenePlayer player, MessageMethod method, BattlePolicy battle, long requestId,
                                  boolean hasResponse) {
        switch (battle) {
            case ALLOW, GATED -> {
                return true;
            }
            case DROP -> {
                // 134 / 132 静默丢（基线 mvh.cpp:170-173、:205-208）：每条移动上行在 moves{result} 里恰好计一次
                metrics.move(MoveResult.IN_BATTLE);
                metrics.battleGateReject(BattleGate.MOVE);
                log.debug("回合制战斗在途，丢弃移动上行 {} player={}", method.key(), player.playerId());
                return false;
            }
            case STOP_ONLY -> {
                // 131 只把速度清零、不收位置（基线 mvh.cpp:188-192）；备战时已停步，这里通常是空操作
                metrics.move(MoveResult.IN_BATTLE);
                metrics.battleGateReject(BattleGate.MOVE);
                if (!player.velocity().isOrigin()) {
                    world.haltForBattle(player);
                }
                return false;
            }
            case REJECT -> {
                metrics.battleGateReject(BattleGate.DEFAULT);
                log.info("回合制战斗在途，拒绝 {} player={}", method.key(), Long.toUnsignedString(player.playerId()));
                if (hasResponse) {
                    replyError(player, method, requestId, FROZEN_REJECTED);
                }
                return false;
            }
        }
        throw new IllegalStateException("未知战斗策略 " + battle);
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
     * 63：按基线顺序校验（契约文档 §4.3；player_scene_handler.cpp:37-171），先回应答（无错 = 已受理、不代表已到达），再换场景：
     * 战斗在途 3023（scene-battle-spec §7.13）→ 换图在途（选目标中 / 镜像取号中 / 冻结中）<b>3014</b> → 三个号全 0 回 3005
     * → 镜像分支（{@code mirror_config_id ≠ 0 且 scene_id = 0}，批次 5.3）→ scene_id 就是当前场景回 3008
     * → 去向（{@link SceneWorld#resolveSwitchTarget}，批次 5.1 scene-channels-spec §4.12）。
     * Java 场景节点都是同构的主世界节点（dungeon-mirror-spec D15）、没有「缺会话快照」，所以 3004 与第二个 3005 不会出现。
     * <ul>
     *   <li>镜像分支（dungeon-mirror-spec §6.7）：同步校验（当前场景不是主世界频道、M 不在 Mirror 表、当前频道在排空、停止接客、实例数上限）
     *       任一不满足回 3005、不发 79；否则先回 {@code {0}}，再进 RESOLVING 向 scene-manager 取实例号（{@link SceneWorld#beginMirrorCreate}），
     *       号回来后在本节点建镜像并换入：旁人 51 → 本人 79（镜像 info）/ 21 / 47，同图保留坐标。之后的失败以 23 推送：取号调用失败 {1003}、
     *       scene-manager 拒绝 / 玩家已不在源场景等 {3023}（D5、D6）；在途期间再发 63 回 3014（D4）；</li>
     *   <li>本节点内：同步换场景。指定排空中的频道回 3023（D11）；只带当前地图时在本节点同图频道里挑最空的（含自己、并列留原地，D17），
     *       挑回原频道 = 受理、不发 79；</li>
     *   <li>远端（批次 5.2，scene-handoff-spec §5.5）：先回 {@code {0}}，再经 scene-manager 选目标、冻结、交出、由 gate 改绑到目标节点
     *       （{@link SceneWorld#beginRemoteSwitch}）。之后的失败以 23 推送：选目标拒绝 / 交出没提交 {3023}、调用失败 {1003}（D12）；
     *       交出提交之后的失败 23 {3023} 后断开（D5）。在途期间再发 63 回 3014（基线 IsSceneChangeBusy 同码）。</li>
     * </ul>
     * 63 自己处理在途判定，冻结闸（§5.9）对它放行。
     */
    private void enterScene(PlayerCall call, EnterSceneC2SRequest request) {
        ScenePlayer player = call.player();
        SceneInfoComp want = request.getSceneInfo();
        Scene current = player.scene();
        Scene target = null;
        boolean remote = false;
        boolean mirror = false;
        int tipId;
        if (player.inBattle()) {
            // 回合制战斗在途（备战或战斗中）→ 3023，先于换图在途的 3014（基线 psh.cpp:54-61；scene-battle-spec §7.13）
            tipId = ENTER_FAILED;
            metrics.battleGateReject(BattleGate.ENTER_SCENE);
        } else if (world.switchInFlight(player)) {
            tipId = ENTER_CHANGING_SCENE;
        } else if (want.getSceneConfigId() == 0 && want.getSceneId() == 0 && want.getMirrorConfigId() == 0) {
            tipId = ENTER_PARAM_ERROR;
        } else if (want.getMirrorConfigId() != 0 && want.getSceneId() == 0) {
            // 镜像分支（批次 5.3 §6.7；uint32 只能判 ≠ 0）：请求里的 scene_config_id / dungeon_config_id / creators 都忽略（同基线）
            tipId = world.checkMirrorRequest(player, want.getMirrorConfigId());
            mirror = tipId == 0;
        } else if (want.getSceneId() != 0 && want.getSceneId() == current.sceneId()) {
            tipId = ENTER_IN_CURRENT_SCENE;
        } else {
            switch (world.resolveSwitchTarget(current, want.getSceneId(), want.getSceneConfigId())) {
                case SwitchTarget.Reject reject -> tipId = reject.tip();
                case SwitchTarget.Local local -> {
                    if (want.getSceneConfigId() != 0 && local.scene().configId() != want.getSceneConfigId()) {
                        tipId = ENTER_PARAM_ERROR;
                    } else {
                        tipId = 0;
                        target = local.scene();
                    }
                }
                case SwitchTarget.Remote ignored -> {
                    tipId = 0;
                    remote = true;
                }
            }
        }
        call.reply(EnterSceneC2SResponse.newBuilder().setErrorMessage(tip(tipId)).build());
        if (target != null && target != current) {
            world.switchScene(player, target);
        } else if (remote) {
            world.beginRemoteSwitch(player, want.getSceneId(), want.getSceneConfigId());
        } else if (mirror) {
            world.beginMirrorCreate(player, want.getMirrorConfigId());
        }
    }

    /** 不能处理的方法回 {@code kFeatureUnavailable}(1006)，形态见 {@link #replyError}。 */
    private void replyUnavailable(ScenePlayer player, MessageMethod method, long requestId) {
        log.debug("回 kFeatureUnavailable {} player={}", method.key(), player.playerId());
        replyError(player, method, requestId, FEATURE_UNAVAILABLE);
    }

    /**
     * 不进处理器、直接回一个拒绝码：应答里有 {@code TipInfoMessage error_message} 字段就写进去（与基线拒绝码的线上形态一致），
     * 否则放在信封的 {@code MessageContent.error_message}。
     */
    private void replyError(ScenePlayer player, MessageMethod method, long requestId, int tipId) {
        Message prototype = method.responsePrototype();
        FieldDescriptor field = prototype.getDescriptorForType().findFieldByName("error_message");
        MessageContent content;
        if (field != null && field.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                && field.getMessageType().getFullName().equals(TipInfoMessage.getDescriptor().getFullName())) {
            Message response = prototype.toBuilder().setField(field, tip(tipId)).build();
            content = reply(method.messageId(), requestId, response);
        } else {
            content = MessageContent.newBuilder()
                    .setMessageId(method.messageId())
                    .setId(requestId)
                    .setErrorMessage(tip(tipId))
                    .build();
        }
        world.sendTo(player, content);
    }
}
