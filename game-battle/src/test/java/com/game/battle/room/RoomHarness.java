package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.BattleIdentity;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.port.ActivityResultSink;
import com.game.battle.port.BattleResultSink;
import com.game.battle.port.SceneBattleEvents;
import com.game.battle.port.SettlementSink;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.push.LobbyAnnouncer;
import com.game.battle.testing.FakeBattleData;
import com.game.battle.testing.ManualBattleScheduler;
import com.game.common.token.BattleTickets;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.MessageContent;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * 房间服务的组件测试装配（battle-node-spec §13.2）：真引擎 + {@link FakeBattleData}、{@link ManualBattleScheduler}（虚拟时间 + 同步墙钟）、
 * 假直连 {@link FakeLink}、记录型的大厅回落与四个出站端口。全部出站都按发生顺序记进同一份 {@link #log}，用来断言跨端口的先后（R6、O1）。
 *
 * <p>{@link #trace()} 把出站记录压成短串，便于整段比对：
 * {@code lobby:<pid>:177,143}、{@code frame:<pid>:<号>}、{@code close:<pid>:<原因>}（优雅关闭）、{@code kill:<pid>:<原因>}（立即强关）、
 * {@code confirm:<pid>}、{@code settlement:<pid>}、{@code result}、{@code activity-result}。
 */
final class RoomHarness {

    static final long T0 = 1_760_000_000_000L;
    static final int NODE_ID = 7;
    static final String INSTANCE = "battle-instance-1";
    static final String HOST = "battle.test";
    static final int PORT = 12000;
    static final String SECRET = "room-harness-ticket-secret-0123456789abcdef";
    static final String NODE_FINGERPRINT = "fp-node";

    static final long A = 5001;
    static final long B = 5002;
    static final long C = 5003;
    static final long D = 5004;
    static final long MONSTER = FakeBattleData.MONSTER_ID;

    static final int PVP_1V1 = 3;
    static final int PVE_SOLO = 4;
    static final int PVE_TEAM = 5;

    /** 出站记录的种类。 */
    enum Kind { LOBBY, FRAME, CLOSE, KILL, CONFIRM, SETTLEMENT, RESULT, ACTIVITY_RESULT }

    /** 一条出站记录（payload 随种类：帧 / 帧列表 / 断开原因 / 期限 / 结算 / 结果事件）。 */
    record Out(Kind kind, long playerId, int messageId, Object payload) {
        String brief() {
            return switch (kind) {
                case LOBBY -> {
                    @SuppressWarnings("unchecked")
                    List<MessageContent> contents = (List<MessageContent>) payload;
                    yield "lobby:" + playerId + ":" + String.join(",", contents.stream().map(c -> String.valueOf(c.getMessageId())).toList());
                }
                case FRAME -> "frame:" + playerId + ":" + messageId;
                case CLOSE -> "close:" + playerId + ":" + ((Disconnect) payload).name().toLowerCase(Locale.ROOT);
                case KILL -> "kill:" + playerId + ":" + ((Disconnect) payload).name().toLowerCase(Locale.ROOT);
                case CONFIRM -> "confirm:" + playerId;
                case SETTLEMENT -> "settlement:" + playerId;
                case RESULT -> "result";
                case ACTIVITY_RESULT -> "activity-result";
            };
        }
    }

    /** 打不死、几乎不掉血的怪（71：1e6 hp、strength 1、速度 1）；time_limit 3600 s → 600 回合，整场期限前回合上限不可达。 */
    static final int DUNGEON_TANK = 70;
    /** 一击必杀玩家的怪（81：1e6 hp、strength 5000、速度 1）。 */
    static final int DUNGEON_BRUTE = 80;

    final ManualBattleScheduler scheduler = new ManualBattleScheduler(T0);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final BattleMetrics metrics = new BattleMetrics(registry);
    final BattleMessageIds ids = BattleMessageIds.loadFromClasspath();
    final FakeBattleData data = FakeBattleData.standard();

    {
        data.addMonster(71).setHealth(1_000_000).setStrength(1).setSpeed(1);
        data.setDungeonMonsters(DUNGEON_TANK, 71);
        data.addDungeon(DUNGEON_TANK).setTimeLimit(3600);
        data.addMonster(81).setHealth(1_000_000).setStrength(5000).setSpeed(1);
        data.setDungeonMonsters(DUNGEON_BRUTE, 81);
        data.addDungeon(DUNGEON_BRUTE).setTimeLimit(3600);
    }
    final BattleIdentity identity = new BattleIdentity(NODE_ID, INSTANCE, HOST, PORT);
    final List<Out> log = new ArrayList<>();
    BattleTickets tickets = BattleTickets.ofUtf8(SECRET);
    FingerprintMode fingerprintMode = FingerprintMode.WARN;
    private BattleRoomServiceImpl service;

    final LobbyAnnouncer lobby = (playerId, contents) -> log.add(new Out(Kind.LOBBY, playerId, 0, List.copyOf(contents)));
    final SceneBattleEvents sceneEvents = (routing, playerId, battleId, deadlineMs) ->
            log.add(new Out(Kind.CONFIRM, playerId, 0, deadlineMs));
    final SettlementSink settlements = (routing, playerId, settlement) -> log.add(new Out(Kind.SETTLEMENT, playerId, 0, settlement));
    final ActivityResultSink activityResults = event -> log.add(new Out(Kind.ACTIVITY_RESULT, 0, 0, event));
    /** 普通局结果端口收到的通道（房间只该用 PLAIN；活动通道由发件箱另行发布）。 */
    final List<BattleResultSink.Channel> resultChannels = new ArrayList<>();
    /** 普通局结果端口：缺省是记录型的；第一次调 {@link #service()} 之前可以换成别的实现（如接着假 Kafka 的真传输）。 */
    BattleResultSink results = (event, channel) -> {
        resultChannels.add(channel);
        log.add(new Out(Kind.RESULT, 0, 0, event));
    };

    /** 房间服务（第一次调用时按当前的 tickets / fingerprintMode 构造）。 */
    BattleRoomServiceImpl service() {
        if (service == null) {
            service = new BattleRoomServiceImpl(new RoomDependencies(data, NODE_FINGERPRINT, fingerprintMode, scheduler,
                    scheduler.clock(), identity, tickets, ids, lobby, sceneEvents, settlements, activityResults, results, metrics));
        }
        return service;
    }

    long now() {
        return scheduler.nowMs();
    }

    // ---------------------------------------------------------------- 请求

    static BattleRouting routing(long playerId) {
        return BattleRouting.newBuilder()
                .setSessionId((int) (playerId % 1000) + 100)
                .setGateNodeId(1)
                .setGateInstanceId("gate-instance-1")
                .setSceneNodeId(2)
                .setSceneInstanceId("scene-instance-1")
                .setZoneId(1)
                .build();
    }

    /** 观众路由（scene 字段留 0）。 */
    static BattleRouting observerRouting(long playerId, int sessionId) {
        return BattleRouting.newBuilder()
                .setSessionId(sessionId)
                .setGateNodeId(1)
                .setGateInstanceId("gate-instance-1")
                .setZoneId(1)
                .build();
    }

    /** 参战快照：level 10，带技能 101 / 103 与 2 瓶药（301），strength 10、armor 0。 */
    static BattlePlayerSnapshot.Builder player(long playerId, int team, long health, long speed) {
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setPlayerName("玩家" + playerId)
                .setLevel(10)
                .setTeamIndex(team)
                .setMaxHealth(health)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(health).setStrength(10).setSpeed(speed))
                .addSkillTableIds(FakeBattleData.SKILL_DAMAGE)
                .addSkillTableIds(FakeBattleData.SKILL_NUKE)
                .addItems(BattleItemEntry.newBuilder().setItemTableId(FakeBattleData.ITEM_POTION).setCount(2))
                .setRouting(routing(playerId));
    }

    /** PVE 单人：A 打一只兜底怪（300 hp、速度 60）；A 速度 100 先手。期限 = now + 300 s。 */
    CreateBattleRequest.Builder pve(long battleId, long playerId) {
        return CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setBattleConfigId(FakeBattleData.DUNGEON_NONE)
                .setMatchMode(PVE_SOLO)
                .setSeed(42)
                .setDeadlineMs(now() + 300_000)
                .addPlayers(player(playerId, 0, 1000, 100));
    }

    /** PVE 组队：给定玩家都在 team 0，各对一只兜底怪。 */
    CreateBattleRequest.Builder pveTeam(long battleId, long... playerIds) {
        CreateBattleRequest.Builder request = CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setBattleConfigId(FakeBattleData.DUNGEON_NONE)
                .setMatchMode(PVE_TEAM)
                .setSeed(42)
                .setDeadlineMs(now() + 300_000);
        for (long playerId : playerIds) {
            request.addPlayers(player(playerId, 0, 1000, 100));
        }
        return request;
    }

    /** PVP 1V1：first 在 team 0、second 在 team 1，气血很高（打不死）。 */
    CreateBattleRequest.Builder pvp(long battleId, long first, long second) {
        return CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setBattleConfigId(0)
                .setMatchMode(PVP_1V1)
                .setSeed(7)
                .setDeadlineMs(now() + 300_000)
                .addPlayers(player(first, 0, 1_000_000, 100))
                .addPlayers(player(second, 1, 1_000_000, 90));
    }

    /** 建房并断言成功（无 error_message）。 */
    CreateBattleResponse create(CreateBattleRequest.Builder request) {
        CreateBattleResponse response = service().createBattle(request.build(), RoomOrigin.MATCH);
        assertThat(response.hasErrorMessage()).as("建房应当成功: %s", response).isFalse();
        return response;
    }

    static BattleAction action(eBattleActionType type, long target) {
        return BattleAction.newBuilder().setActionType(type).setTargetId(target).build();
    }

    static BattleAction skill(int skillId, long target) {
        return BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_SKILL).setSkillTableId(skillId)
                .setTargetId(target).build();
    }

    // ---------------------------------------------------------------- 直连

    /** 模拟一次成功的握手：挂接（此时连接还不是 live）→ 置为已验证 → onDirectVerified（观众推 161）。 */
    FakeLink connect(long battleId, long playerId, eBattleTicketRole role) {
        FakeLink link = new FakeLink(playerId);
        OptionalInt session = service().attachDirect(battleId, playerId, role, link);
        assertThat(session).as("挂接应当成功").isPresent();
        link.live = true;
        service().onDirectVerified(battleId, playerId, role);
        return link;
    }

    /** 假直连：记下写出的帧与关闭方式（同时记进全局 {@link #log}）。 */
    final class FakeLink implements DirectLink {
        final long playerId;
        boolean live;
        Disconnect closedWith;
        boolean killed;
        final List<MessageContent> frames = new ArrayList<>();

        FakeLink(long playerId) {
            this.playerId = playerId;
        }

        @Override
        public boolean isLive() {
            return live;
        }

        @Override
        public void send(MessageContent frame) {
            if (!live) {
                return;
            }
            frames.add(frame);
            log.add(new Out(Kind.FRAME, playerId, frame.getMessageId(), frame));
        }

        @Override
        public void closeGracefully(Disconnect reason) {
            if (closedWith != null) {
                return;
            }
            live = false;
            closedWith = reason;
            log.add(new Out(Kind.CLOSE, playerId, 0, reason));
        }

        @Override
        public void closeNow(Disconnect reason) {
            if (closedWith != null) {
                return;
            }
            live = false;
            killed = true;
            closedWith = reason;
            log.add(new Out(Kind.KILL, playerId, 0, reason));
        }

        @Override
        public String peer() {
            return "127.0.0.1:" + playerId;
        }

        List<Integer> messageIds() {
            return frames.stream().map(MessageContent::getMessageId).toList();
        }

        <T> List<T> parsed(int messageId, Parser<T> parser) {
            return frames.stream().filter(f -> f.getMessageId() == messageId).map(f -> parse(parser, f)).toList();
        }
    }

    // ---------------------------------------------------------------- 查询

    List<String> trace() {
        return log.stream().map(Out::brief).toList();
    }

    void clearLog() {
        log.clear();
    }

    List<Out> outs(Kind kind) {
        return log.stream().filter(o -> o.kind() == kind).toList();
    }

    /** 某玩家经大厅回落收到的全部帧（按顺序摊平）。 */
    @SuppressWarnings("unchecked")
    List<MessageContent> lobbyFrames(long playerId) {
        List<MessageContent> frames = new ArrayList<>();
        for (Out out : log) {
            if (out.kind() == Kind.LOBBY && out.playerId() == playerId) {
                frames.addAll((List<MessageContent>) out.payload());
            }
        }
        return frames;
    }

    List<BattleSettlementData> settlementsOut() {
        return outs(Kind.SETTLEMENT).stream().map(o -> (BattleSettlementData) o.payload()).toList();
    }

    List<BattleResultEvent> resultsOut() {
        return outs(Kind.RESULT).stream().map(o -> (BattleResultEvent) o.payload()).toList();
    }

    static <T> T parse(Parser<T> parser, MessageContent frame) {
        try {
            return parser.parseFrom(frame.getSerializedMessage());
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError("帧解析失败: " + frame.getMessageId(), e);
        }
    }

    double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }
}
