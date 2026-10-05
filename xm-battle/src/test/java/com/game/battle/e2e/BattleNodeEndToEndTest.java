package com.game.battle.e2e;

import static com.game.battle.e2e.E2eSupport.GET_BATTLE_STATE;
import static com.game.battle.e2e.E2eSupport.ITEM_HEAL;
import static com.game.battle.e2e.E2eSupport.NOTIFY_BATTLE_ASSIGNED;
import static com.game.battle.e2e.E2eSupport.NOTIFY_BATTLE_END;
import static com.game.battle.e2e.E2eSupport.NOTIFY_BATTLE_START;
import static com.game.battle.e2e.E2eSupport.NOTIFY_SPECTATE_END;
import static com.game.battle.e2e.E2eSupport.NOTIFY_SPECTATE_STATE;
import static com.game.battle.e2e.E2eSupport.NOTIFY_SPECTATE_TURN_RESULT;
import static com.game.battle.e2e.E2eSupport.NOTIFY_TURN_RESULT;
import static com.game.battle.e2e.E2eSupport.SET_AUTO_BATTLE;
import static com.game.battle.e2e.E2eSupport.STOP_WATCH_BATTLE;
import static com.game.battle.e2e.E2eSupport.SUBMIT_BATTLE_ACTION;
import static com.game.battle.e2e.E2eSupport.TIP_ENTITY_IS_NULL;
import static com.game.battle.e2e.E2eSupport.TIP_INVALID_PARAMETER;
import static com.game.battle.e2e.E2eSupport.TIP_MESSAGE_SIZE_EXCEEDED;
import static com.game.battle.e2e.E2eSupport.TIP_RATE_LIMIT_EXCEEDED;
import static com.game.battle.e2e.E2eSupport.admin;
import static com.game.battle.e2e.E2eSupport.admitted;
import static com.game.battle.e2e.E2eSupport.count;
import static com.game.battle.e2e.E2eSupport.createAdmitted;
import static com.game.battle.e2e.E2eSupport.hero;
import static com.game.battle.e2e.E2eSupport.nextBattleId;
import static com.game.battle.e2e.E2eSupport.pve;
import static com.game.battle.e2e.E2eSupport.pvp;
import static com.game.battle.e2e.E2eSupport.reissue;
import static com.game.battle.e2e.E2eSupport.routing;
import static com.game.battle.e2e.E2eSupport.tank;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.CreateBattleResult;
import com.game.battle.BattleApplication;
import com.game.battle.BattleIdentity;
import com.game.battle.BattleNode;
import com.game.battle.admin.DevBattleController;
import com.game.battle.e2e.DirectClient.Frame;
import com.game.battle.e2e.GatePushInbox.Delivery;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.protocol.BattleMessageIds.Upstream;
import com.game.common.token.BattleTickets;
import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.PushTarget;
import com.game.net.client.ClientFrames;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleStartS2C;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.MessageContent;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SpectateStateS2C;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * xm-battle 进程内端到端（连真 Redis，缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 14）：真 Spring 进程（租约、目录、
 * Dubbo Triple 导出、直连端口、房间、管理 Tomcat 全部是生产装配），外面只有三个测试替身——Triple 调用方（扮 match）、Redis 里登记在线目录并
 * 订阅推送频道的「gate」（{@link GatePushInbox}，收大厅公告 177 / 143）、阻塞 socket 直连客户端（{@link DirectClient}，按线上字节收发）。
 *
 * <p>逐条核对 battle-node-spec 的线上顺序 O1–O8、R1–R3：开局大厅先 177 后 143（一条 {@code GatePush{message_batch}}）、握手应答是第一帧、
 * 观众应答之后才 161、上行触发的结算 139 →（150）→ 应答 → FIN、定时结算、整场期限 DRAW（没有终局 139，观众 166 ABORTED + DRAW）、
 * Destroy 参战者只看到 FIN、重连顶替旧连接不发帧、165 应答 → FIN 且没有 166；以及直连面的闸门（握手前、握手拒绝串、信封错误、限频、非法包阈值）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
@SpringBootTest(classes = BattleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "xm.run-mode=dev",
                "xm.table-dir=../config-data/tables",
                "xm.advertise-host=127.0.0.1",
                "xm.redis.database=14",
                "XM_ADMIN_TOKEN=" + BattleNodeEndToEndTest.ADMIN_TOKEN,
                "XM_GATE_TOKEN_SECRET="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BattleNodeEndToEndTest {

    static final String ADMIN_TOKEN = "battle-e2e-admin-token";
    static final int CLIENT_PORT = E2eSupport.freePort();
    static final int RPC_PORT = E2eSupport.freePort();
    /** 扮演的 gate 所在 zone / 节点号：推送频道是全库共享的，取一个不会与本机切片撞上的值。 */
    static final int ZONE = 9701;
    static final int GATE_NODE = 977;
    /** 玩家号段：每次运行不同，避免撞上上一次运行留在 DB 14 的在线目录条目。 */
    static final long PLAYER_BASE = 7_000_000_000L + (System.currentTimeMillis() % 100_000_000L) * 100;

    private static IsolatedDubboModule clientModel;
    private static BattleNodeService battle;

    @LocalServerPort
    int managementPort;

    @Autowired
    BattleNode node;

    @Autowired
    BattleTickets tickets;

    @Autowired
    MeterRegistry meters;

    @Autowired
    RedissonClient redis;

    private GatePushInbox gate;

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("xm.redis.address", () -> System.getProperty("xm.it.redis"));
        registry.add("xm.battle.client-port", () -> CLIENT_PORT);
        registry.add("xm.battle.rpc-port", () -> RPC_PORT);
    }

    @BeforeEach
    void setUp() {
        if (battle == null) {
            clientModel = IsolatedDubboModule.create("xm-battle-e2e-match");
            battle = E2eSupport.tripleClient(clientModel, RPC_PORT);
            // 预热：先把 Triple 连接建好，计时敏感的用例（整场期限）不吃首连耗时
            try {
                battle.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(1).setPlayerId(1).build())
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Triple 预热失败", e);
            }
        }
        gate = new GatePushInbox(redis, ZONE, GATE_NODE, "e2e-gate-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        gate.close();
    }

    @AfterAll
    static void closeClient() {
        if (clientModel != null) {
            clientModel.close();
            clientModel = null;
            battle = null;
        }
    }

    // ===================================================================================== 用例

    @Test
    void 消息号与契约一致() {
        BattleMessageIds ids = BattleMessageIds.loadFromClasspath();
        assertThat(ids.id(Upstream.GET_BATTLE_STATE)).isEqualTo(GET_BATTLE_STATE);
        assertThat(ids.id(Upstream.SUBMIT_BATTLE_ACTION)).isEqualTo(SUBMIT_BATTLE_ACTION);
        assertThat(ids.id(Upstream.SET_AUTO_BATTLE)).isEqualTo(SET_AUTO_BATTLE);
        assertThat(ids.id(Upstream.STOP_WATCH_BATTLE)).isEqualTo(STOP_WATCH_BATTLE);
        assertThat(ids.id(Notify.BATTLE_ASSIGNED)).isEqualTo(NOTIFY_BATTLE_ASSIGNED);
        assertThat(ids.id(Notify.BATTLE_START)).isEqualTo(NOTIFY_BATTLE_START);
        assertThat(ids.id(Notify.TURN_RESULT)).isEqualTo(NOTIFY_TURN_RESULT);
        assertThat(ids.id(Notify.BATTLE_END)).isEqualTo(NOTIFY_BATTLE_END);
        assertThat(ids.id(Notify.SPECTATE_STATE)).isEqualTo(NOTIFY_SPECTATE_STATE);
        assertThat(ids.id(Notify.SPECTATE_TURN_RESULT)).isEqualTo(NOTIFY_SPECTATE_TURN_RESULT);
        assertThat(ids.id(Notify.SPECTATE_END)).isEqualTo(NOTIFY_SPECTATE_END);
    }

    @Test
    void PVE全流程_大厅先177后143_握手应答为首帧_非法行动1005_合法行动139先于应答_同票重连顶替_挂机打完150后FIN() throws Exception {
        long a = player(1);
        gate.online(a, 101);
        long battleId = nextBattleId();
        long deadline = System.currentTimeMillis() + 300_000;
        double settlementsBefore = count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "logged");
        double resultsBefore = count(meters, "xm.battle.results", "channel", "plain", "result", "logged");
        double lobbySentBefore = count(meters, "xm.battle.lobby.push.outcomes", "outcome", "sent");
        double replacedBefore = count(meters, "xm.battle.disconnects", "reason", "replaced");

        long beforeCreate = System.currentTimeMillis();
        CreateBattleResponse created = createAdmitted(battle, pve(battleId, deadline, hero(a, routing(gate, 101))));
        long afterCreate = System.currentTimeMillis();
        assertThat(created.getBattleId()).isEqualTo(battleId);

        // O1 / R5：大厅一条 GatePush{message_batch}，按序 177 → 143，寻址在线目录里的当前会话（玩家栅栏带 player_id）
        Delivery lobby = gate.next(a, Duration.ofSeconds(5));
        assertThat(lobby.push().getActionCase()).isEqualTo(GatePush.ActionCase.MESSAGE_BATCH);
        assertThat(lobby.push().getGateInstanceId()).isEqualTo(gate.gateInstanceId);
        assertThat(lobby.push().getTargetsList()).containsExactly(PushTarget.newBuilder().setSessionId(101).setPlayerId(a).build());
        assertThat(lobby.contents()).extracting(MessageContent::getMessageId).containsExactly(NOTIFY_BATTLE_ASSIGNED, NOTIFY_BATTLE_START);
        assertThat(lobby.contents()).allSatisfy(content -> {
            assertThat(content.getId()).as("推送形状 id = 0").isZero();
            assertThat(content.hasErrorMessage()).isFalse();
        });
        BattleAssignedS2C assigned = BattleAssignedS2C.parseFrom(lobby.contents().get(0).getSerializedMessage());
        assertThat(assigned.getBattleId()).isEqualTo(battleId);
        assertThat(assigned.getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        assertThat(assigned.getHost()).isEqualTo("127.0.0.1");
        assertThat(assigned.getPort()).isEqualTo(CLIENT_PORT);
        assertThat(assigned.getExpireAtMs()).isEqualTo(deadline);
        assertThat(assigned.getTokenSignature().toString(StandardCharsets.US_ASCII)).matches("[0-9a-f]{64}");
        BattleTicketPayload payload = BattleTicketPayload.parseFrom(assigned.getTokenPayload());
        BattleIdentity identity = node.identity().orElseThrow();
        assertThat(payload.getBattleId()).isEqualTo(battleId);
        assertThat(payload.getPlayerId()).isEqualTo(a);
        assertThat(payload.getBattleNodeId()).isEqualTo(identity.nodeId());
        assertThat(payload.getBattleInstanceId()).isEqualTo(node.instanceId());
        assertThat(payload.getExpireAtMs()).isEqualTo(deadline);
        assertThat(payload.getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        BattleStartS2C start = BattleStartS2C.parseFrom(lobby.contents().get(1).getSerializedMessage());
        assertThat(start.getBattleId()).isEqualTo(battleId);
        BattleStateS2C startState = start.getState();
        assertThat(startState.getBattleId()).isEqualTo(battleId);
        assertThat(startState.getRoundIndex()).isEqualTo(1);
        assertThat(startState.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
        assertThat(startState.getActionDeadlineMs()).as("第一回合恒为 6 s").isBetween(beforeCreate + 6000, afterCreate + 6000);
        assertThat(startState.getPendingActorIdsList()).containsExactly(a);
        assertThat(startState.getSelfItemsList()).containsExactly(BattleItemEntry.newBuilder().setItemTableId(ITEM_HEAL).setCount(3).build());
        assertThat(startState.getActorsList()).filteredOn(actor -> actor.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER)
                .hasSize(2);

        List<Frame> tail;
        try (DirectClient first = DirectClient.connectWith(assigned)) {
            // R1：握手应答是第一帧；参战者握手后服务端什么也不推
            first.expect("verify-ok:" + battleId);
            first.expectSilence(Duration.ofMillis(300));

            // 补拉 140：应答 id / message_id 回显；本人视角（本人道具）
            long stateId = first.request(GET_BATTLE_STATE, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
            Frame stateReply = first.expect("reply:" + GET_BATTLE_STATE);
            assertThat(stateReply.content().getId()).isEqualTo(stateId);
            BattleStateS2C state = BattleStateS2C.parseFrom(stateReply.content().getSerializedMessage());
            assertThat(state.getBattleId()).isEqualTo(battleId);
            assertThat(state.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
            assertThat(state.getSelfItemsList()).extracting(BattleItemEntry::getItemTableId).containsExactly(ITEM_HEAL);
            assertThat(state.getActorsList()).extracting(BattleActorState::getActorId).contains(a);
            List<Long> monsters = state.getActorsList().stream()
                    .filter(actor -> actor.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER)
                    .map(BattleActorState::getActorId).toList();
            assertThat(monsters).hasSize(2);

            // 非法行动（NONE）→ 应答体里 1005，不结算
            first.request(SUBMIT_BATTLE_ACTION, submit(battleId, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_NONE).build()));
            SubmitBattleActionResponse rejected = SubmitBattleActionResponse.parseFrom(
                    first.expect("reply:" + SUBMIT_BATTLE_ACTION).content().getSerializedMessage());
            assertThat(rejected.getErrorMessage().getId()).isEqualTo(TIP_INVALID_PARAMETER);
            first.expectSilence(Duration.ofMillis(200));

            // 合法行动：单人房全员就绪 → 当场结算；139 先于这条应答（R2 / O3）
            long submitId = first.request(SUBMIT_BATTLE_ACTION, submit(battleId, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(monsters.get(0)).build()));
            long beforeResolve = System.currentTimeMillis();
            TurnResultS2C round1 = TurnResultS2C.parseFrom(first.expect("push:" + NOTIFY_TURN_RESULT).content().getSerializedMessage());
            Frame submitReply = first.expect("reply:" + SUBMIT_BATTLE_ACTION);
            assertThat(submitReply.content().getId()).isEqualTo(submitId);
            assertThat(submitReply.content().getSerializedMessage()).as("成功应答体 0 字节").isEmpty();
            assertThat(round1.getBattleId()).isEqualTo(battleId);
            assertThat(round1.getRoundIndex()).isEqualTo(1);
            assertThat(round1.getState().getRoundIndex()).isEqualTo(2);
            assertThat(round1.getState().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
            assertThat(round1.getActionOrderList()).isNotEmpty();
            assertThat(round1.getState().getActionDeadlineMs()).as("139 带的是下一回合的截止（先装填再广播）")
                    .isBetween(beforeResolve - 1000 + 6000, System.currentTimeMillis() + 6000);

            // O7：同一张票再连一条：新连接握手成功，旧连接被强关且不收任何帧
            try (DirectClient second = DirectClient.connectWith(assigned)) {
                second.expect("verify-ok:" + battleId);
                assertThat(first.expectClosed(Duration.ofSeconds(3))).isIn("fin", "reset");

                // 挂机：「未就绪 → 全员就绪」翻转当场结算（139 先于 162 应答），之后每 2 s 一回合，直到 150，然后 FIN
                second.request(SET_AUTO_BATTLE, SetAutoBattleRequest.newBuilder().setBattleId(battleId).setEnabled(true).build());
                tail = second.untilClosed(Duration.ofSeconds(60));
            }
        }

        List<String> labels = tail.stream().map(Frame::label).toList();
        assertThat(labels.getFirst()).as("翻转当场结算：139 先于应答 %s", labels).isEqualTo("push:" + NOTIFY_TURN_RESULT);
        assertThat(labels.getLast()).as("终局之后是 FIN（不是 RST）%s", labels).isEqualTo("closed:fin");
        assertThat(labels).as("应答恰好一条 %s", labels).containsOnlyOnce("reply:" + SET_AUTO_BATTLE);
        int endAt = labels.indexOf("push:" + NOTIFY_BATTLE_END);
        int replyAt = labels.indexOf("reply:" + SET_AUTO_BATTLE);
        assertThat(endAt).as("有 150 %s", labels).isPositive();
        if (replyAt > endAt) {
            // O3：翻转那一回合就打完 → 139 → 150 → 应答 → FIN
            assertThat(labels.subList(endAt, labels.size()))
                    .containsExactly("push:" + NOTIFY_BATTLE_END, "reply:" + SET_AUTO_BATTLE, "closed:fin");
        } else {
            // 其后的回合由 2 s 计时器触发：150 之后就是 FIN
            assertThat(labels.subList(endAt, labels.size())).containsExactly("push:" + NOTIFY_BATTLE_END, "closed:fin");
            List<Long> timerTurns = new ArrayList<>();
            for (int i = replyAt + 1; i < endAt; i++) {
                assertThat(labels.get(i)).isEqualTo("push:" + NOTIFY_TURN_RESULT);
                timerTurns.add(tail.get(i).atNanos());
            }
            for (int i = 1; i < timerTurns.size(); i++) {
                long gapMs = TimeUnit.NANOSECONDS.toMillis(timerTurns.get(i) - timerTurns.get(i - 1));
                assertThat(gapMs).as("全自动节奏 2 s").isBetween(1500L, 3500L);
            }
        }
        TurnResultS2C lastTurn = TurnResultS2C.parseFrom(tail.get(endAt - 1).content().getSerializedMessage());
        assertThat(lastTurn.getState().getActionDeadlineMs()).as("分出胜负的回合截止为 0").isZero();
        assertThat(lastTurn.getState().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        BattleEndS2C end = BattleEndS2C.parseFrom(tail.get(endAt).content().getSerializedMessage());
        assertThat(end.getBattleId()).isEqualTo(battleId);
        assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(end.getSettlement().getPlayerId()).isEqualTo(a);
        assertThat(end.getSettlement().getBattleId()).isEqualTo(battleId);
        assertThat(end.getSettlement().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(end.getSettlement().getTotalRounds()).isGreaterThanOrEqualTo(2);
        assertThat(end.getSettlement().getExpGain()).as("打赢有经验").isPositive();
        assertThat(end.getSettlement().getDefeatedMonstersList()).isNotEmpty();

        // 房间已删：旧票重新握手 → 名单拒绝串，然后 FIN
        try (DirectClient late = DirectClient.connectWith(assigned)) {
            late.expect("verify-fail:battle not found or player not in this battle");
            assertThat(late.expectClosed(Duration.ofSeconds(2))).isEqualTo("fin");
        }
        // 补签 1005（客户端据此判 BattleGone）
        assertThat(issueTip(battleId, a)).isEqualTo(TIP_INVALID_PARAMETER);

        // match 房间照常走结算端口与普通结果端口（6.2 只记日志）；大厅公告经 gate 送达；顶替计数
        assertThat(count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "logged") - settlementsBefore).isEqualTo(1);
        assertThat(count(meters, "xm.battle.results", "channel", "plain", "result", "logged") - resultsBefore).isEqualTo(1);
        assertThat(count(meters, "xm.battle.lobby.push.outcomes", "outcome", "sent")).isGreaterThan(lobbySentBefore);
        assertThat(count(meters, "xm.battle.disconnects", "reason", "replaced") - replacedBefore).isEqualTo(1);
        assertThat(count(meters, "xm.battle.rounds", "trigger", "all_ready")).isPositive();
        assertThat(count(meters, "xm.battle.rounds", "trigger", "auto_flip")).isPositive();
    }

    @Test
    void PVP_补签与开局票逐字节相同_非成员1005_幂等建房不重推_两人都交才结算_Destroy后参战者只见FIN() throws Exception {
        long a = player(11);
        long b = player(12);
        long stranger = player(13);
        gate.online(a, 201);
        gate.online(b, 202);
        long battleId = nextBattleId();
        long deadline = System.currentTimeMillis() + 300_000;
        double settlementsBefore = count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "logged");
        double destroyedBefore = count(meters, "xm.battle.room.ends", "reason", "destroyed");

        createAdmitted(battle, pvp(battleId, deadline, tank(a, 0, routing(gate, 201)), tank(b, 1, routing(gate, 202))));
        BattleAssignedS2C assignedA = assignedFrom(gate.next(a, Duration.ofSeconds(5)));
        BattleAssignedS2C assignedB = assignedFrom(gate.next(b, Duration.ofSeconds(5)));

        // 补签：所有字段相同、HMAC 确定 → 与开局票逐字节相同（§2.4）
        assertThat(reissue(battle, battleId, a).toByteString()).isEqualTo(assignedA.toByteString());
        assertThat(reissue(battle, battleId, b).toByteString()).isEqualTo(assignedB.toByteString());
        // 非成员、player 0 → 1005，无 assignment
        assertThat(issueTip(battleId, stranger)).isEqualTo(TIP_INVALID_PARAMETER);
        assertThat(issueTip(battleId, 0)).isEqualTo(TIP_INVALID_PARAMETER);

        // 幂等：同 battle_id 再建（内容不同也不比较）→ 受理、无错误、零副作用（B7：不重推 177 / 143）
        CreateBattleResponse again = createAdmitted(battle, pvp(battleId, deadline, tank(stranger, 0, routing(gate, 203)),
                tank(b, 1, routing(gate, 202))));
        assertThat(again.getBattleId()).isEqualTo(battleId);
        gate.expectNone(a, Duration.ofMillis(1500));
        gate.expectNone(b, Duration.ofMillis(100));
        assertThat(issueTip(battleId, stranger)).as("幂等命中没有改名单").isEqualTo(TIP_INVALID_PARAMETER);

        try (DirectClient ca = DirectClient.connectWith(assignedA); DirectClient cb = DirectClient.connectWith(assignedB)) {
            ca.expect("verify-ok:" + battleId);
            cb.expect("verify-ok:" + battleId);

            // A 先交：B 没交，不结算
            ca.request(SUBMIT_BATTLE_ACTION, submit(battleId, attack(b)));
            assertThat(ca.expect("reply:" + SUBMIT_BATTLE_ACTION).content().getSerializedMessage()).isEmpty();
            ca.expectSilence(Duration.ofMillis(300));
            cb.expectSilence(Duration.ofMillis(10));

            // B 再交 → 全员就绪当场结算：两边各一条 139；B 的 139 先于 B 的应答
            cb.request(SUBMIT_BATTLE_ACTION, submit(battleId, attack(a)));
            TurnResultS2C turnB = TurnResultS2C.parseFrom(cb.expect("push:" + NOTIFY_TURN_RESULT).content().getSerializedMessage());
            cb.expect("reply:" + SUBMIT_BATTLE_ACTION);
            TurnResultS2C turnA = TurnResultS2C.parseFrom(ca.expect("push:" + NOTIFY_TURN_RESULT).content().getSerializedMessage());
            assertThat(turnA.getRoundIndex()).isEqualTo(1);
            assertThat(turnB.getRoundIndex()).isEqualTo(1);
            assertThat(turnA.getEventsList()).isEqualTo(turnB.getEventsList());
            assertThat(turnA.getState().getRoundIndex()).isEqualTo(2);
            assertThat(turnA.getState().getPendingActorIdsList()).containsExactlyInAnyOrder(a, b);

            // 140：成员拿到本人视角；没带道具 → self_items 为空
            ca.request(GET_BATTLE_STATE, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
            BattleStateS2C stateA = BattleStateS2C.parseFrom(ca.expect("reply:" + GET_BATTLE_STATE).content().getSerializedMessage());
            assertThat(stateA.getBattleId()).isEqualTo(battleId);
            assertThat(stateA.getSelfItemsList()).isEmpty();
            // 140 查别的房间（非成员）→ 默认状态（0 字节，battle_id = 0），不回错误码
            ca.request(GET_BATTLE_STATE, GetBattleStateRequest.newBuilder().setBattleId(battleId + 1_000_000).build());
            Frame foreign = ca.expect("reply:" + GET_BATTLE_STATE);
            assertThat(foreign.content().getSerializedMessage()).isEmpty();

            // O6：Destroy → 参战者什么帧都收不到（没有 150、没有终局 139），只看到 FIN
            battle.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(battleId).setReason("e2e_rollback").build())
                    .get(10, TimeUnit.SECONDS);
            assertThat(ca.expectClosed(Duration.ofSeconds(3))).isEqualTo("fin");
            assertThat(cb.expectClosed(Duration.ofSeconds(3))).isEqualTo("fin");
        }
        // Destroy 不结算、不发结果事件；之后补签 1005；重复 Destroy 幂等
        assertThat(count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "logged")).isEqualTo(settlementsBefore);
        assertThat(count(meters, "xm.battle.room.ends", "reason", "destroyed") - destroyedBefore).isEqualTo(1);
        assertThat(issueTip(battleId, a)).isEqualTo(TIP_INVALID_PARAMETER);
        battle.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(battleId).setReason("again").build()).get(10, TimeUnit.SECONDS);
    }

    @Test
    void 观战_dev接口补全观众路由_应答后161_每回合158_收尾166FINISHED_165应答后FIN无166_RemoveObserver推166REMOVED() throws Exception {
        long a = player(21);
        long watcher = player(23);
        gate.online(a, 301);
        gate.online(watcher, 303);
        double skippedBefore = count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "skipped");
        double settlementsBefore = count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "logged");
        double resultsBefore = count(meters, "xm.battle.results", "result", "logged");

        // dev 建房（DEV 房间：照常推送，永不投递结算与结果事件）
        long battleId = nextBattleId();
        HttpResponse<byte[]> created = admin(managementPort, ADMIN_TOKEN, DevBattleController.CREATE,
                pve(battleId, System.currentTimeMillis() + 300_000, hero(a, routing(gate, 301))).toByteArray());
        assertThat(created.statusCode()).as(new String(created.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        admitted(CreateBattleResult.parseFrom(created.body()));
        BattleAssignedS2C assignedA = assignedFrom(gate.next(a, Duration.ofSeconds(5)));

        // AddObserver 的判定：房间不在 1004；参战者 1005（单槽互斥）
        assertThat(addObserverTip(battleId + 1_000_000, watcher)).isEqualTo(TIP_ENTITY_IS_NULL);
        assertThat(addObserverTip(battleId, a)).isEqualTo(TIP_INVALID_PARAMETER);

        // dev 登记观众：路由留空，由接口按在线目录补全 gate 部分 → 大厅收到 177（role = OBSERVER）
        BattleAssignedS2C assignedW = devAddObserver(battleId, watcher, 303);
        assertThat(reissue(battle, battleId, watcher).toByteString()).as("观众补签与登记时的票逐字节相同")
                .isEqualTo(assignedW.toByteString());

        List<Frame> participantTail;
        List<Frame> watcherTail;
        try (DirectClient cw = DirectClient.connectWith(assignedW)) {
            // O2：握手应答 → 161（observer_count = 1，全员冷却清空、没有道具）
            cw.expect("verify-ok:" + battleId);
            SpectateStateS2C spectate = SpectateStateS2C.parseFrom(cw.expect("push:" + NOTIFY_SPECTATE_STATE).content().getSerializedMessage());
            assertThat(spectate.getObserverCount()).isEqualTo(1);
            assertThat(spectate.getState().getBattleId()).isEqualTo(battleId);
            assertThat(spectate.getState().getRoundIndex()).isEqualTo(1);
            assertThat(spectate.getState().getSelfItemsList()).isEmpty();
            assertThat(spectate.getState().getActorsList()).allSatisfy(actor -> assertThat(actor.getSkillCooldownRoundsMap()).isEmpty());

            // 观众不能提交 / 切自动（1005）
            cw.request(SET_AUTO_BATTLE, SetAutoBattleRequest.newBuilder().setBattleId(battleId).setEnabled(true).build());
            SetAutoBattleResponse denied = SetAutoBattleResponse.parseFrom(cw.expect("reply:" + SET_AUTO_BATTLE).content().getSerializedMessage());
            assertThat(denied.getErrorMessage().getId()).isEqualTo(TIP_INVALID_PARAMETER);

            try (DirectClient ca = DirectClient.connectWith(assignedA)) {
                ca.expect("verify-ok:" + battleId);
                ca.request(SET_AUTO_BATTLE, SetAutoBattleRequest.newBuilder().setBattleId(battleId).setEnabled(true).build());
                participantTail = ca.untilClosed(Duration.ofSeconds(60));
                watcherTail = cw.untilClosed(Duration.ofSeconds(5));
            }
        }
        List<String> aLabels = participantTail.stream().map(Frame::label).toList();
        List<String> wLabels = watcherTail.stream().map(Frame::label).toList();
        long turns = aLabels.stream().filter(("push:" + NOTIFY_TURN_RESULT)::equals).count();
        assertThat(turns).as("两只怪：翻转当场一回合 + 之后至少一回合 %s", aLabels).isGreaterThanOrEqualTo(2);
        assertThat(aLabels.getFirst()).isEqualTo("push:" + NOTIFY_TURN_RESULT);
        assertThat(aLabels.get(1)).as("翻转那一回合没打完：139 → 应答").isEqualTo("reply:" + SET_AUTO_BATTLE);
        assertThat(aLabels.subList(aLabels.size() - 2, aLabels.size())).containsExactly("push:" + NOTIFY_BATTLE_END, "closed:fin");
        // 翻转之后装填时已全员就绪（全自动）→ 每回合 2 s（§4.4.1）
        List<Long> turnTimes = participantTail.stream().filter(f -> f.label().equals("push:" + NOTIFY_TURN_RESULT))
                .map(Frame::atNanos).toList();
        for (int i = 1; i < turnTimes.size(); i++) {
            assertThat(TimeUnit.NANOSECONDS.toMillis(turnTimes.get(i) - turnTimes.get(i - 1))).as("全自动节奏 2 s").isBetween(1500L, 3500L);
        }
        // 观众：每回合一条 158（与参战者的 139 同样多），然后 166，然后 FIN
        List<String> expectedWatcher = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            expectedWatcher.add("push:" + NOTIFY_SPECTATE_TURN_RESULT);
        }
        expectedWatcher.add("push:" + NOTIFY_SPECTATE_END);
        expectedWatcher.add("closed:fin");
        assertThat(wLabels).isEqualTo(expectedWatcher);
        for (Frame frame : watcherTail.subList(0, (int) turns)) {
            TurnResultS2C spectateTurn = TurnResultS2C.parseFrom(frame.content().getSerializedMessage());
            assertThat(spectateTurn.getBattleId()).isEqualTo(battleId);
            assertThat(spectateTurn.getState().getSelfItemsList()).as("观众版不带道具").isEmpty();
            assertThat(spectateTurn.getState().getActorsList()).allSatisfy(actor -> assertThat(actor.getSkillCooldownRoundsMap()).isEmpty());
        }
        SpectateEndS2C finished = SpectateEndS2C.parseFrom(watcherTail.get((int) turns).content().getSerializedMessage());
        assertThat(finished.getBattleId()).isEqualTo(battleId);
        assertThat(finished.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED);
        assertThat(finished.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        // DEV 房间：结算端口跳过（计 skipped），不发结果事件
        assertThat(count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "skipped") - skippedBefore).isEqualTo(1);
        assertThat(count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "logged")).isEqualTo(settlementsBefore);
        assertThat(count(meters, "xm.battle.results", "result", "logged")).isEqualTo(resultsBefore);

        // 第二间房：165 → 应答 → FIN，没有 166（O8）；之后不在观众名单，补签 1005
        long second = nextBattleId();
        HttpResponse<byte[]> created2 = admin(managementPort, ADMIN_TOKEN, DevBattleController.CREATE,
                pve(second, System.currentTimeMillis() + 300_000, hero(a, routing(gate, 301))).toByteArray());
        admitted(CreateBattleResult.parseFrom(created2.body()));
        gate.next(a, Duration.ofSeconds(5));
        BattleAssignedS2C stopTicket = devAddObserver(second, watcher, 303);
        try (DirectClient cw = DirectClient.connectWith(stopTicket)) {
            cw.expect("verify-ok:" + second);
            cw.expect("push:" + NOTIFY_SPECTATE_STATE);
            long stopId = cw.request(STOP_WATCH_BATTLE, StopWatchBattleRequest.newBuilder().setBattleId(second).build());
            Frame stopReply = cw.expect("reply:" + STOP_WATCH_BATTLE);
            assertThat(stopReply.content().getId()).isEqualTo(stopId);
            assertThat(StopWatchBattleResponse.parseFrom(stopReply.content().getSerializedMessage()).hasErrorMessage()).isFalse();
            assertThat(cw.expectClosed(Duration.ofSeconds(3))).isEqualTo("fin");
        }
        assertThat(issueTip(second, watcher)).isEqualTo(TIP_INVALID_PARAMETER);

        // 同一间房再登记 → 直连 → RemoveObserver：166{REMOVED, ONGOING} → FIN
        BattleAssignedS2C removeTicket = devAddObserver(second, watcher, 303);
        try (DirectClient cw = DirectClient.connectWith(removeTicket)) {
            cw.expect("verify-ok:" + second);
            cw.expect("push:" + NOTIFY_SPECTATE_STATE);
            HttpResponse<byte[]> removed = admin(managementPort, ADMIN_TOKEN, DevBattleController.REMOVE_OBSERVER,
                    RemoveObserverRequest.newBuilder().setBattleId(second).setObserverPlayerId(watcher).setReason("e2e").build().toByteArray());
            assertThat(removed.statusCode()).isEqualTo(204);
            SpectateEndS2C kicked = SpectateEndS2C.parseFrom(cw.expect("push:" + NOTIFY_SPECTATE_END).content().getSerializedMessage());
            assertThat(kicked.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_REMOVED);
            assertThat(kicked.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
            assertThat(cw.expectClosed(Duration.ofSeconds(3))).isEqualTo("fin");
        }
        assertThat(admin(managementPort, ADMIN_TOKEN, DevBattleController.DESTROY,
                DestroyBattleRequest.newBuilder().setBattleId(second).setReason("e2e").build().toByteArray()).statusCode()).isEqualTo(204);
    }

    @Test
    void 整场期限_第一回合定时结算后到期_参战者150DRAW无奖励_观众166ABORTED加DRAW_没有终局139_然后FIN() throws Exception {
        long a = player(31);
        long b = player(32);
        long watcher = player(33);
        long battleId = nextBattleId();
        double settlementsBefore = count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "logged");
        double resultsBefore = count(meters, "xm.battle.results", "channel", "plain", "result", "logged");
        double deadlineEndsBefore = count(meters, "xm.battle.room.ends", "reason", "deadline");
        double timerRoundsBefore = count(meters, "xm.battle.rounds", "trigger", "timer");

        long t0 = System.currentTimeMillis();
        long deadline = t0 + 8_000;
        // 都不在线：大厅公告 OFFLINE（至多一次），票据经补签拿
        createAdmitted(battle, pvp(battleId, deadline, tank(a, 0, routing(gate, 401)), tank(b, 1, routing(gate, 402))));
        AddObserverResponse added = battle.addObserver(AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(watcher)
                .setObserverName("e2e-watcher").setRouting(routing(gate, 403)).build()).get(10, TimeUnit.SECONDS);
        assertThat(added.hasErrorMessage()).isFalse();
        BattleAssignedS2C ticketA = reissue(battle, battleId, a);
        BattleAssignedS2C ticketB = reissue(battle, battleId, b);
        BattleAssignedS2C ticketW = reissue(battle, battleId, watcher);
        assertThat(List.of(ticketA, ticketB, ticketW)).allSatisfy(t -> assertThat(t.getExpireAtMs()).isEqualTo(deadline));
        assertThat(ticketW.getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);

        try (DirectClient ca = DirectClient.connectWith(ticketA); DirectClient cb = DirectClient.connectWith(ticketB);
             DirectClient cw = DirectClient.connectWith(ticketW)) {
            ca.expect("verify-ok:" + battleId);
            cb.expect("verify-ok:" + battleId);
            cw.expect("verify-ok:" + battleId);
            cw.expect("push:" + NOTIFY_SPECTATE_STATE);

            // 没人出手：6 s 窗口到期按默认行动结算（O4：没有应答那一步）
            Frame turnA = ca.expect("push:" + NOTIFY_TURN_RESULT, Duration.ofSeconds(9));
            cb.expect("push:" + NOTIFY_TURN_RESULT, Duration.ofSeconds(2));
            cw.expect("push:" + NOTIFY_SPECTATE_TURN_RESULT, Duration.ofSeconds(2));
            long turnAtMs = System.currentTimeMillis();
            assertThat(turnAtMs - t0).as("第一回合恒为 6 s").isBetween(5_500L, 7_900L);
            TurnResultS2C turn = TurnResultS2C.parseFrom(turnA.content().getSerializedMessage());
            assertThat(turn.getRoundIndex()).isEqualTo(1);
            assertThat(turn.getState().getActionDeadlineMs()).as("下一回合截止已装填（期限会先到）").isGreaterThan(deadline);

            // O5：期限到 → 没有新的 139；150 DRAW → FIN
            List<Frame> tailA = ca.untilClosed(Duration.ofSeconds(5));
            List<Frame> tailB = cb.untilClosed(Duration.ofSeconds(2));
            List<Frame> tailW = cw.untilClosed(Duration.ofSeconds(2));
            assertThat(tailA.stream().map(Frame::label)).containsExactly("push:" + NOTIFY_BATTLE_END, "closed:fin");
            assertThat(tailB.stream().map(Frame::label)).containsExactly("push:" + NOTIFY_BATTLE_END, "closed:fin");
            assertThat(tailW.stream().map(Frame::label)).containsExactly("push:" + NOTIFY_SPECTATE_END, "closed:fin");
            long endAtMs = System.currentTimeMillis();
            assertThat(endAtMs).as("期限到点收尾").isGreaterThanOrEqualTo(deadline - 50);

            for (List<Frame> tail : List.of(tailA, tailB)) {
                BattleEndS2C end = BattleEndS2C.parseFrom(tail.getFirst().content().getSerializedMessage());
                assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
                assertThat(end.getSettlement().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
                assertThat(end.getSettlement().getExpGain()).isZero();
                assertThat(end.getSettlement().getGoldGain()).isZero();
                assertThat(end.getSettlement().getItemsGainedList()).isEmpty();
                assertThat(end.getSettlement().getTotalRounds()).isEqualTo(1);
                assertThat(end.getSettlement().getHealth()).as("HP 照常带出").isPositive();
            }
            SpectateEndS2C spectateEnd = SpectateEndS2C.parseFrom(tailW.getFirst().content().getSerializedMessage());
            assertThat(spectateEnd.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_ABORTED);
            assertThat(spectateEnd.getOutcome()).as("B6：期限路径给观众的是 DRAW，不是 ONGOING").isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        }
        // 照常发结算（逐人）与结果事件（DRAW）
        assertThat(count(meters, "xm.battle.scene.events", "kind", "settlement", "result", "logged") - settlementsBefore).isEqualTo(2);
        assertThat(count(meters, "xm.battle.results", "channel", "plain", "result", "logged") - resultsBefore).isEqualTo(1);
        assertThat(count(meters, "xm.battle.room.ends", "reason", "deadline") - deadlineEndsBefore).isEqualTo(1);
        assertThat(count(meters, "xm.battle.rounds", "trigger", "timer") - timerRoundsBefore).isEqualTo(1);
        assertThat(issueTip(battleId, a)).isEqualTo(TIP_INVALID_PARAMETER);
    }

    @Test
    void 直连面闸门_握手前_拒绝串逐字_信封错误1005与1010_限频1008_重复握手回旧battle_id_非法包到50断开() throws Exception {
        long a = player(41);
        long b = player(42);
        long battleId = nextBattleId();
        createAdmitted(battle, pvp(battleId, System.currentTimeMillis() + 300_000, tank(a, 0, routing(gate, 501)),
                tank(b, 1, routing(gate, 502))));
        BattleAssignedS2C ticket = reissue(battle, battleId, a);
        BattleTicketPayload good = BattleTicketPayload.parseFrom(ticket.getTokenPayload());
        double illegalBefore = count(meters, "xm.battle.disconnects", "reason", "illegal_packets");
        double beforeVerifyBefore = count(meters, "xm.battle.disconnects", "reason", "request_before_verify");

        // G4：握手前发 ClientRequest → 立即关，不回包
        try (DirectClient c = DirectClient.connect("127.0.0.1", CLIENT_PORT)) {
            c.request(GET_BATTLE_STATE, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
            c.expectClosed(Duration.ofSeconds(3));
        }
        // 首帧是大厅的 ClientTokenVerifyRequest（发错连接）→ 被关，无回包（N9：解码器立即关）
        try (DirectClient c = DirectClient.connect("127.0.0.1", CLIENT_PORT)) {
            c.send(ClientTokenVerifyRequest.newBuilder().setPayload(ticket.getTokenPayload()).setSignature(ticket.getTokenSignature()).build());
            c.expectClosed(Duration.ofSeconds(3));
        }
        // 坏校验和 → 被关
        try (DirectClient c = DirectClient.connect("127.0.0.1", CLIENT_PORT)) {
            byte[] frame = encode(DirectClient.verifyRequest(ticket));
            frame[frame.length - 1] ^= 0x5a;
            c.sendRaw(frame);
            c.expectClosed(Duration.ofSeconds(3));
        }

        // 握手拒绝串逐字照抄；应答之后 FIN（R4）
        byte[] badSignature = ticket.getTokenSignature().toByteArray();
        badSignature[0] = (byte) (badSignature[0] == 'a' ? 'b' : 'a');
        assertRejected(BattleTokenVerifyRequest.newBuilder().setPayload(ticket.getTokenPayload())
                .setSignature(ByteString.copyFrom(badSignature)).build(), "invalid ticket signature");
        assertRejected(BattleTokenVerifyRequest.newBuilder().setPayload(ticket.getTokenPayload())
                .setSignature(ByteString.copyFromUtf8(ticket.getTokenSignature().toStringUtf8().toUpperCase())).build(),
                "invalid ticket signature");
        assertRejected(signed(ByteString.copyFrom(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff})), "malformed ticket payload");
        assertRejected(signedWith(good, p -> p.setBattleId(0)), "ticket rejected: empty_identity");
        assertRejected(signedWith(good, p -> p.setBattleNodeId(p.getBattleNodeId() + 1)), "ticket rejected: node_mismatch");
        assertRejected(signedWith(good, p -> p.setBattleInstanceId("someone-else")), "ticket rejected: instance_mismatch");
        assertRejected(signedWith(good, p -> p.setExpireAtMs(System.currentTimeMillis() - 1)), "ticket rejected: expired");
        assertRejected(signedWith(good, p -> p.setRoleValue(3)), "ticket rejected: role_invalid");
        // 多个字段都坏：最便宜的先拒（节点不符先于过期）
        assertRejected(signedWith(good, p -> p.setBattleNodeId(p.getBattleNodeId() + 1).setExpireAtMs(1)), "ticket rejected: node_mismatch");
        // 参战票当观众票用（票不能换角色）→ 名单拒绝
        assertRejected(signedWith(good, p -> p.setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER)),
                "battle not found or player not in this battle");

        try (DirectClient c = DirectClient.connectWith(ticket)) {
            c.expect("verify-ok:" + battleId);

            // ③ 白名单外的号（157）→ 信封错误：id / message_id 回显，error 1005，没有应答体
            long id = c.request(157, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
            Frame notAllowed = c.expect("error:157:" + TIP_INVALID_PARAMETER);
            assertThat(notAllowed.content().getId()).isEqualTo(id);
            assertThat(notAllowed.content().getSerializedMessage()).isEmpty();
            // ① 整条 1025 B → 1010（在限频之前：不占 140 的额度）
            c.send(paddedRequest(77, GET_BATTLE_STATE, GetBattleStateRequest.newBuilder().setBattleId(battleId).build(), 1025));
            assertThat(c.expect("error:" + GET_BATTLE_STATE + ":" + TIP_MESSAGE_SIZE_EXCEEDED).content().getId()).isEqualTo(77);
            // 恰好 1024 B 照常处理；② 同号每秒 3 条：连发 4 条（1024 B 那条算第 1 条），第 4 条 → 1008
            c.send(paddedRequest(78, GET_BATTLE_STATE, GetBattleStateRequest.newBuilder().setBattleId(battleId).build(), 1024));
            for (int i = 0; i < 3; i++) {
                c.request(GET_BATTLE_STATE, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
            }
            assertThat(c.expect("reply:" + GET_BATTLE_STATE).content().getId()).isEqualTo(78);
            c.expect("reply:" + GET_BATTLE_STATE);
            c.expect("reply:" + GET_BATTLE_STATE);
            c.expect("error:" + GET_BATTLE_STATE + ":" + TIP_RATE_LIMIT_EXCEEDED);

            // B1：已验证后再握手（垃圾 payload）→ success + 原 battle_id，新票一个字节都不看
            c.send(BattleTokenVerifyRequest.newBuilder().setPayload(ByteString.copyFromUtf8("garbage"))
                    .setSignature(ByteString.copyFromUtf8("garbage")).build());
            c.expect("verify-ok:" + battleId);

            // 非法包阈值 50：已有 3 个（157 / 1010 / 1008）；再来 46 个仍在线，第 50 个断开（刚写的信封错误不保证送达，B5）
            for (int i = 0; i < 46; i++) {
                c.request(157, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
            }
            for (int i = 0; i < 46; i++) {
                assertThat(c.next().label()).isIn("error:157:" + TIP_INVALID_PARAMETER, "error:157:" + TIP_RATE_LIMIT_EXCEEDED);
            }
            c.expectSilence(Duration.ofMillis(200));
            c.request(157, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
            List<Frame> rest = c.untilClosed(Duration.ofSeconds(3));
            assertThat(rest.size()).as("至多一条来不及发的信封错误 %s", rest.stream().map(Frame::label).toList()).isLessThanOrEqualTo(2);
        }
        assertThat(count(meters, "xm.battle.disconnects", "reason", "illegal_packets") - illegalBefore).isEqualTo(1);
        assertThat(count(meters, "xm.battle.disconnects", "reason", "request_before_verify") - beforeVerifyBefore).isEqualTo(1);
        assertThat(count(meters, "xm.battle.handshakes", "result", "repeat")).isPositive();

        // 同一张票换一条新连接照常可用（每条连接自己的限频器与非法包计数）
        try (DirectClient c = DirectClient.connectWith(ticket)) {
            c.expect("verify-ok:" + battleId);
            c.request(GET_BATTLE_STATE, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
            c.expect("reply:" + GET_BATTLE_STATE);
        }
        battle.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(battleId).setReason("e2e").build()).get(10, TimeUnit.SECONDS);
    }

    // ===================================================================================== 工具

    private static long player(int n) {
        return PLAYER_BASE + n;
    }

    private static SubmitBattleActionRequest submit(long battleId, BattleAction action) {
        return SubmitBattleActionRequest.newBuilder().setBattleId(battleId).setAction(action).build();
    }

    private static BattleAction attack(long target) {
        return BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(target).build();
    }

    private static BattleAssignedS2C assignedFrom(Delivery delivery) throws IOException {
        assertThat(delivery.contents()).extracting(MessageContent::getMessageId).containsExactly(NOTIFY_BATTLE_ASSIGNED, NOTIFY_BATTLE_START);
        return BattleAssignedS2C.parseFrom(delivery.contents().getFirst().getSerializedMessage());
    }

    private int issueTip(long battleId, long playerId) throws Exception {
        IssueBattleTicketResponse response = battle.issueBattleTicket(IssueBattleTicketRequest.newBuilder()
                .setBattleId(battleId).setPlayerId(playerId).build()).get(10, TimeUnit.SECONDS);
        assertThat(response.hasAssignment()).isEqualTo(!response.hasErrorMessage());
        return response.getErrorMessage().getId();
    }

    private int addObserverTip(long battleId, long observer) throws Exception {
        AddObserverResponse response = battle.addObserver(AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observer)
                .setObserverName("e2e").setRouting(routing(gate, 999)).build()).get(10, TimeUnit.SECONDS);
        return response.getErrorMessage().getId();
    }

    /** dev 登记观众（路由留空，由接口按在线目录补全），返回大厅收到的观众票。 */
    private BattleAssignedS2C devAddObserver(long battleId, long observer, int expectedSession) throws Exception {
        HttpResponse<byte[]> response = admin(managementPort, ADMIN_TOKEN, DevBattleController.ADD_OBSERVER,
                AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observer).setObserverName("e2e-watcher")
                        .build().toByteArray());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(AddObserverResponse.parseFrom(response.body()).hasErrorMessage()).isFalse();
        Delivery lobby = gate.next(observer, Duration.ofSeconds(5));
        assertThat(lobby.push().getTargetsList()).containsExactly(
                PushTarget.newBuilder().setSessionId(expectedSession).setPlayerId(observer).build());
        assertThat(lobby.contents()).extracting(MessageContent::getMessageId).containsExactly(NOTIFY_BATTLE_ASSIGNED);
        BattleAssignedS2C assigned = BattleAssignedS2C.parseFrom(lobby.contents().getFirst().getSerializedMessage());
        assertThat(assigned.getBattleId()).isEqualTo(battleId);
        assertThat(assigned.getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        return assigned;
    }

    private BattleTokenVerifyRequest signed(ByteString payload) {
        return BattleTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tickets.sign(payload)).build();
    }

    private BattleTokenVerifyRequest signedWith(BattleTicketPayload base, UnaryOperator<BattleTicketPayload.Builder> change) {
        return signed(change.apply(base.toBuilder()).build().toByteString());
    }

    /** 新连接上发这次握手：收到逐字的拒绝串（battle_id 不在线上），然后 FIN。 */
    private void assertRejected(BattleTokenVerifyRequest verify, String error) throws Exception {
        try (DirectClient c = DirectClient.connect("127.0.0.1", CLIENT_PORT)) {
            c.send(verify);
            Frame frame = c.expect("verify-fail:" + error);
            assertThat(frame.verify().getBattleId()).isZero();
            assertThat(c.expectClosed(Duration.ofSeconds(2))).isEqualTo("fin");
        }
    }

    private static byte[] encode(com.google.protobuf.Message message) {
        ByteBuf buf = ClientFrames.encode(UnpooledByteBufAllocator.DEFAULT, message);
        try {
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    /** 整条序列化后恰好 {@code targetSize} 字节的请求：体后面用未知的 length-delimited 字段（号 15）补齐，解析照常通过。 */
    private static ClientRequest paddedRequest(long id, int messageId, MessageLite body, int targetSize) throws IOException {
        for (int pad = 0; pad < targetSize; pad++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            body.writeTo(bytes);
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            out.writeBytes(15, ByteString.copyFrom(new byte[pad]));
            out.flush();
            ClientRequest request = ClientRequest.newBuilder().setId(id).setMessageId(messageId)
                    .setBody(ByteString.copyFrom(bytes.toByteArray())).build();
            if (request.getSerializedSize() == targetSize) {
                return request;
            }
        }
        throw new IllegalArgumentException("凑不出 " + targetSize + " 字节");
    }
}
