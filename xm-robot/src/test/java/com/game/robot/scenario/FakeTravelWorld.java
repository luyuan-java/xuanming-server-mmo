package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AccountSimplePlayer;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.CurrencyComp;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.EnterSceneS2C;
import com.game.proto.GateTokenPayload;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.ListSkillsResponse;
import com.game.proto.MoveAckS2C;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.RedirectToGateNotify;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.proto.Transform;
import com.game.proto.TravelToZoneRequest;
import com.game.proto.TravelToZoneResponse;
import com.game.proto.Vector3;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.CreatePlayerResponse;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamView;
import com.game.robot.client.FakeGate;
import com.game.robot.client.FakeGateway;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RedirectTarget;
import com.game.robot.client.RobotClient;
import com.game.robot.flow.PlayerFlow;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * travel 场景的本机假服务端（批次 5.4 的测试替身）：两个区各一个 {@link FakeGate}（区 1 = 出发区、区 2 = 访客区），背后是同一份
 * 「login + scene」状态；配 {@link FakeGateway}（assign-gate 按区给 gate、区服列表）。只按客户端契约回帧，大意照 zone-travel-spec 的
 * Java 设计：226 的同步段与延迟应答、受理后推 124（票据钉目标区与持票者）并让旧会话 REDIRECTED、目标 gate 只认本区票据、
 * login 拒他人持票（2011）、GO-5（位置在别区且有节点就回 26 再推 124、票据钉住本区时不弹走）、待落点按目标图落地、断线留重连租约、
 * 17 登出清位置记录（可以拖后，模拟离场异步落定）、63 换图、区内移动。reconnect 的跨区重连也借它跑（两个区、GO-5、跟随都是同一套）。
 * 可以按 {@link Fault} 故意做错一处，看探针在对应的那一步失败。<b>它不是服务端行为的证明</b>，只钉探针这一侧的判定。
 *
 * <p>线程：每台假 gate 各一条 I/O 线程，延迟应答与「受理后」的动作在一条定时线程上；状态一律在本对象的锁里读写。
 */
final class FakeTravelWorld implements AutoCloseable {

    static final int HOME = 1;
    static final int VISIT = 2;
    static final int DEFAULT_CONF = 1;
    static final int OTHER_CONF = 2;
    /** World 表：第一张是默认主世界。 */
    static final List<Integer> WORLDS = List.of(DEFAULT_CONF, OTHER_CONF);
    static final Duration TIMEOUT = Duration.ofSeconds(3);
    static final Vec3 SPAWN = new Vec3(180, 200, 0);
    /** 区内移动的边界：上报的点任一分量超出 ±它就截断在原地并回 137（移动探针打到 100000），之内照收、不回 137。 */
    static final double WORLD_LIMIT = 50_000;
    /** 重定向票据与 124 里的签名：64 个小写 hex 字符（假 gate 不验签）。 */
    static final ByteString SIGNATURE = ByteString.copyFromUtf8("ab".repeat(32));

    /** 故意做错的地方（一次只开一处）。 */
    enum Fault {
        /** 受理之后先推 124、再回 226 的 {0}。 */
        REDIRECT_BEFORE_REPLY,
        /** 推 124 之前在旧连接上先推一条 79。 */
        SCENE_BEFORE_REDIRECT,
        /** 一次传送推两条 124。 */
        DOUBLE_REDIRECT,
        /** login 不核对持票者：持别人的票据也能进自己的角色。 */
        NO_TICKET_BINDING,
        /** gate 不核对票据的目标区：他区的票据也能握手。 */
        NO_ZONE_CHECK,
        /** 连发两条 226：第一条回 13000、第二条回 {0}（配对反了）。 */
        SWAPPED_DOUBLE_REPLY,
        /** login 没有 GO-5：位置在别区也就地进场。 */
        NO_GO5,
        /** 推出 124 之后旧会话照常处理请求。 */
        REDIRECTED_STILL_ANSWERS,
        /** gate 拒绝带 player_id 的票据（124 给的那种，握手回 token not for this gate）：跟随在握手这一步失败。 */
        REJECT_REDIRECT_TICKET
    }

    private enum Kind { ONLINE, LEASE, PENDING }

    /** 位置记录：在线 / 断线租约（有场景）或待落点（只有区与目标图）。 */
    private record Loc(Kind kind, int zone, int conf, long sceneId, long expiresNanos) {

        boolean live() {
            return kind == Kind.ONLINE || System.nanoTime() < expiresNanos;
        }
    }

    private static final class Player {
        final long id;
        final String account;
        final int homeZone;
        long gold;
        long teamId;
        long entity;
        boolean travelling;
        boolean swapFirst;
        Vec3 position = SPAWN;
        Loc loc;
        Conn conn;

        Player(long id, String account, int homeZone) {
            this.id = id;
            this.account = account;
            this.homeZone = homeZone;
        }
    }

    /** 一条客户端连接（挂在 {@link FakeGate.Session#attachment} 上）。 */
    private static final class Conn {
        final int zone;
        final FakeGate.Session session;
        String account;
        long playerId;
        boolean redirected;
        long ticketPlayer;
        int ticketZone;

        Conn(int zone, FakeGate.Session session) {
            this.zone = zone;
            this.session = session;
        }
    }

    /** 一条 226 的应答（按发出先后记）。 */
    record TravelReply(long requestId, int tip) {
    }

    final Set<Fault> faults = EnumSet.noneOf(Fault.class);
    /** 断线租约（真服务端 30 s）。 */
    volatile Duration leaseTtl = Duration.ofSeconds(2);
    /** 世界地图（226 的 3007 按它判，第一张是首登落的默认主世界）；缺省 {@link #WORLDS}，读真 World 表的用例换成表里的。 */
    volatile List<Integer> worlds = WORLDS;
    /** 各地图的出生点（首登、换图落在这里）；没列的用 {@link #SPAWN}。读真 BaseScene 表的用例换成表里的。 */
    volatile Map<Integer, Vec3> spawns = Map.of();
    /** 226 的延迟应答（真服务端 ≤ 2 s，等组队读回来）。 */
    volatile Duration replyDelay = Duration.ofMillis(200);
    /**
     * 17 登出之后过多久才清位置记录（真服务端离场是异步的：gate → login → scene 逻辑线程处理离场 → 写登出墓碑）。缺省 0 = 立即；
     * 落定之前位置记录仍是「本区在线」，别的区的 login 读到它就走 GO-5。
     */
    volatile Duration leaveLag = Duration.ZERO;

    private final MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
    private final MessageIds ids = MessageIds.resolve(registry);
    private final int travel = registry.requireId(TravelScenario.TRAVEL_SERVICE, TravelScenario.TRAVEL_METHOD);
    private final int redirect = RedirectTarget.messageId(registry);
    private final int getCurrencyList = registry.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
    private final int gmAdd = registry.requireId("SceneCurrencyClientPlayer", "GmAddCurrency");
    private final int createTeam = registry.requireId("ClientPlayerTeam", "CreateTeam");
    private final int disbandTeam = registry.requireId("ClientPlayerTeam", "DisbandTeam");
    private final int leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
    private final int enterScene = registry.requireId("SceneSceneClientPlayer", "EnterScene");
    private final FakeGate.Events events = new FakeGate.Events();
    /** 每个区的第一台 gate（124 指向它）。 */
    private final Map<Integer, FakeGate> gates = new HashMap<>();
    /** {@link #addGate} 加的第二台起的 gate。 */
    private final List<FakeGate> extraGates = new ArrayList<>();
    private final FakeGateway gateway;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fake-travel-timer");
        t.setDaemon(true);
        return t;
    });
    private final List<RobotClient> clients = new ArrayList<>();
    private final Map<String, List<Long>> accounts = new HashMap<>();
    private final Map<Long, Player> players = new HashMap<>();
    private final Map<Integer, Integer> received = new HashMap<>();
    private final List<TravelReply> travelReplies = new ArrayList<>();
    private long nextPlayerId = 0x8000_0000_0000_0101L;
    private long nextEntity = 5000;
    private long nextTeam = 70;
    private int creates;

    FakeTravelWorld() throws IOException {
        for (int zone : List.of(HOME, VISIT)) {
            gates.put(zone, new FakeGate("zone" + zone, events, new Script(zone)));
        }
        gateway = new FakeGateway();
        for (int zone : List.of(HOME, VISIT)) {
            gateway.assign(zone, gates.get(zone).endpoint(), gateToken(zone), SIGNATURE);
        }
    }

    /** assign-gate 给的普通令牌：钉本区、不带 player_id。 */
    private static ByteString gateToken(int zone) {
        return GateTokenPayload.newBuilder().setGateNodeId(1).setZoneId(zone).setExpireTimestamp(System.currentTimeMillis() / 1000 + 600)
                .setHmacSessionKey(ByteString.copyFrom(new byte[32])).setTargetZoneId(zone).build().toByteString();
    }

    /** 给这个区再加一台 gate：之后这个区的 assign-gate 在各台之间轮流给（第一次仍给第一台）；124 仍指向第一台。返回它的端口。 */
    int addGate(int zone) throws IOException {
        FakeGate gate = new FakeGate("zone" + zone + "-" + (extraGates.size() + 2), events, new Script(zone));
        synchronized (this) {
            extraGates.add(gate);
        }
        gateway.addGate(zone, gate.endpoint(), gateToken(zone), SIGNATURE);
        return gate.port();
    }

    MessageIdRegistry registry() {
        return registry;
    }

    String baseUrl() {
        return gateway.baseUrl();
    }

    FakeGateway gateway() {
        return gateway;
    }

    int gatePort(int zone) {
        return gates.get(zone).port();
    }

    RobotClient newClient(int zone) {
        RobotClient client = new RobotClient(baseUrl(), zone, ids, redirect, TIMEOUT, TIMEOUT);
        clients.add(client);
        return client;
    }

    PlayerFlow flow(RobotClient client) {
        return new PlayerFlow(client, "dev-secret", TIMEOUT, Duration.ofSeconds(5));
    }

    /** 某个消息号至今收到的请求数（两个区合计）。 */
    synchronized int receivedCount(int messageId) {
        return received.getOrDefault(messageId, 0);
    }

    /** 至今建了几个角色。 */
    synchronized int creates() {
        return creates;
    }

    /** 226 的应答，按发出先后。 */
    synchronized List<TravelReply> travelReplies() {
        return List.copyOf(travelReplies);
    }

    /** 某个账号第一个角色的金币。 */
    synchronized long gold(String account) {
        return players.get(accounts.get(account).get(0)).gold;
    }

    @Override
    public void close() {
        clients.forEach(RobotClient::close);
        timer.shutdownNow();
        gateway.close();
        gates.values().forEach(FakeGate::close);
        extraGates.forEach(FakeGate::close);
    }

    // ================================================================ 一个区的 gate 背后的脚本

    private final class Script implements FakeGate.Script {

        private final int zone;

        Script(int zone) {
            this.zone = zone;
        }

        @Override
        public ClientTokenVerifyResponse onVerify(FakeGate.Session session, ClientTokenVerifyRequest request) {
            Conn conn = new Conn(zone, session);
            session.attachment = conn;
            GateTokenPayload payload;
            try {
                payload = GateTokenPayload.parseFrom(request.getPayload());
            } catch (InvalidProtocolBufferException e) {
                return FakeGate.verifyRejected("malformed token payload");
            }
            synchronized (FakeTravelWorld.this) {
                if (payload.getTargetZoneId() != 0 && payload.getTargetZoneId() != zone && !faults.contains(Fault.NO_ZONE_CHECK)) {
                    return FakeGate.verifyRejected("token not for this zone");
                }
                if (payload.getExpireTimestamp() <= System.currentTimeMillis() / 1000) {
                    return FakeGate.verifyRejected("token expired");
                }
                if (payload.getPlayerId() != 0 && faults.contains(Fault.REJECT_REDIRECT_TICKET)) {
                    return FakeGate.verifyRejected("token not for this gate");
                }
                conn.ticketPlayer = payload.getPlayerId();
                conn.ticketZone = payload.getTargetZoneId();
            }
            return FakeGate.VERIFY_OK;
        }

        @Override
        public void onRequest(FakeGate.Session session, ClientRequest request) throws Exception {
            Conn conn = (Conn) session.attachment;
            synchronized (FakeTravelWorld.this) {
                received.merge(request.getMessageId(), 1, Integer::sum);
                if (conn == null || (conn.redirected && !faults.contains(Fault.REDIRECTED_STILL_ANSWERS))) {
                    return;
                }
                handle(conn, request);
            }
        }

        @Override
        public void onClosed(FakeGate.Session session) {
            Conn conn = (Conn) session.attachment;
            if (conn == null) {
                return;
            }
            synchronized (FakeTravelWorld.this) {
                Player player = conn.playerId == 0 ? null : players.get(conn.playerId);
                if (player != null && player.conn == conn) {
                    player.conn = null;
                    player.loc = new Loc(Kind.LEASE, player.loc.zone(), player.loc.conf(), player.loc.sceneId(),
                            System.nanoTime() + leaseTtl.toNanos());
                }
            }
        }
    }

    // ================================================================ 请求（都在锁里）

    private void handle(Conn conn, ClientRequest request) throws InvalidProtocolBufferException {
        int id = request.getMessageId();
        Player player = conn.playerId == 0 ? null : players.get(conn.playerId);
        if (id == ids.login()) {
            conn.account = LoginRequest.parseFrom(request.getBody()).getAccount();
            LoginResponse.Builder response = LoginResponse.newBuilder();
            for (long pid : accounts.getOrDefault(conn.account, List.of())) {
                response.addPlayers(wrap(players.get(pid)));
            }
            conn.session.reply(request, response.build());
        } else if (id == ids.createPlayer()) {
            Player created = new Player(nextPlayerId++, conn.account, conn.zone);
            players.put(created.id, created);
            accounts.computeIfAbsent(conn.account, k -> new ArrayList<>()).add(created.id);
            creates++;
            conn.session.reply(request, CreatePlayerResponse.newBuilder().addPlayers(wrap(created)).build());
        } else if (id == ids.enterGame()) {
            enterGame(conn, request, EnterGameRequest.parseFrom(request.getBody()).getPlayerId());
        } else if (id == leaveGame) {
            if (player != null && player.conn == conn) {
                player.conn = null;
                conn.playerId = 0;
                leave(player);
            }
        } else if (id == enterScene && player != null) {
            switchScene(conn, player, request);
        } else if (id == getCurrencyList && player != null) {
            conn.session.reply(request, GetCurrencyListResponse.newBuilder()
                    .setCurrency(CurrencyComp.newBuilder().addValues(player.gold).addValues(0)).build());
        } else if (id == gmAdd && player != null) {
            GmAddCurrencyRequest add = GmAddCurrencyRequest.parseFrom(request.getBody());
            player.gold += add.getAmount();
            conn.session.reply(request, GmAddCurrencyResponse.newBuilder().setBalanceAfter(player.gold).build());
        } else if (id == ids.listSkills()) {
            conn.session.reply(request, ListSkillsResponse.getDefaultInstance());
        } else if (id == createTeam && player != null) {
            player.teamId = nextTeam++;
            conn.session.reply(request, TeamResponse.newBuilder().setTeam(TeamView.newBuilder().setTeamId(player.teamId)
                    .setLeaderId(player.id).setZoneId(player.homeZone).addMembers(TeamMemberView.newBuilder().setPlayerId(player.id))).build());
        } else if (id == disbandTeam && player != null) {
            player.teamId = 0;
            conn.session.reply(request, TeamResponse.newBuilder().setTeam(TeamView.getDefaultInstance()).build());
        } else if ((id == ids.moveStart() || id == ids.moveStop()) && player != null) {
            int seq;
            Vec3 at;
            if (id == ids.moveStart()) {
                MoveStartC2S start = MoveStartC2S.parseFrom(request.getBody());
                seq = start.getInputSeq();
                at = Vec3.of(start.getStartLocation());
            } else {
                MoveStopC2S stop = MoveStopC2S.parseFrom(request.getBody());
                seq = stop.getInputSeq();
                at = Vec3.of(stop.getEndLocation());
            }
            if (Math.abs(at.x()) > WORLD_LIMIT || Math.abs(at.y()) > WORLD_LIMIT || Math.abs(at.z()) > WORLD_LIMIT) {
                // 上报的点远在地图外：截断在原地，回 137
                conn.session.push(ids.notifyMoveAck(), MoveAckS2C.newBuilder().setInputSeq(seq)
                        .setServerLocation(player.position.toLocation()).build());
            } else {
                player.position = at;
            }
        } else if (id == travel && player != null) {
            travel(conn, player, request, TravelToZoneRequest.parseFrom(request.getBody()));
        }
        // 其余不回包
    }

    private void enterGame(Conn conn, ClientRequest request, long pid) {
        Player player = players.get(pid);
        boolean holderMismatch = conn.ticketPlayer != 0 && conn.ticketPlayer != pid && !faults.contains(Fault.NO_TICKET_BINDING);
        if (holderMismatch || player == null || !player.account.equals(conn.account)) {
            conn.session.reply(request, EnterGameResponse.newBuilder()
                    .setErrorMessage(TipInfoMessage.newBuilder().setId(TravelScenario.TIP_TICKET_HOLDER)).build());
            return;
        }
        boolean pinned = conn.ticketPlayer != 0 && conn.ticketZone == conn.zone;
        Loc loc = player.loc != null && player.loc.live() ? player.loc : null;
        if (loc != null && loc.zone() != conn.zone && loc.kind() != Kind.PENDING && !pinned && !faults.contains(Fault.NO_GO5)) {
            // GO-5：只送连接，不夺权、不进场
            conn.session.reply(request, EnterGameResponse.newBuilder().setPlayerId(pid).build());
            conn.session.push(redirect, redirectTo(loc.zone(), pid));
            conn.redirected = true;
            return;
        }
        int conf;
        long sceneId;
        Vec3 position;
        if (loc != null && loc.zone() == conn.zone && loc.kind() == Kind.PENDING) {
            conf = loc.conf();
            sceneId = sceneId(conn.zone, conf);
            position = spawnOf(conf);
        } else if (loc != null && loc.zone() == conn.zone) {
            conf = loc.conf();
            sceneId = loc.sceneId();
            position = player.position;
        } else {
            conf = worlds.get(0);
            sceneId = sceneId(conn.zone, conf);
            position = spawnOf(conf);
        }
        if (player.conn != null && player.conn != conn) {
            player.conn.session.push(ids.sendTip(), TipInfoMessage.newBuilder().setId(2017).build());
            player.conn.playerId = 0;
        }
        player.conn = conn;
        conn.playerId = pid;
        player.entity = nextEntity++;
        player.position = position;
        player.loc = new Loc(Kind.ONLINE, conn.zone, conf, sceneId, 0);
        conn.session.reply(request, EnterGameResponse.newBuilder().setPlayerId(pid).build());
        conn.session.push(ids.notifyEnterScene(), enterScene(conf, sceneId));
        conn.session.push(ids.notifyActorCreate(), selfActor(player));
    }

    private static ActorCreateS2C selfActor(Player player) {
        Vec3 at = player.position;
        return ActorCreateS2C.newBuilder().setEntity(player.entity).setGuid(player.id)
                .setTransform(Transform.newBuilder().setLocation(Vector3.newBuilder().setX(at.x()).setY(at.y()).setZ(at.z()))).build();
    }

    private void travel(Conn conn, Player player, ClientRequest request, TravelToZoneRequest travelRequest) {
        int target = travelRequest.getTargetZoneId();
        int conf = travelRequest.getSceneConfigId();
        if (target == 0 || target == conn.zone) {
            replyTravel(conn, request, TravelScenario.TIP_ZONE_INVALID);
            return;
        }
        if (conf != 0 && !worlds.contains(conf)) {
            replyTravel(conn, request, TravelScenario.TIP_MAP_INVALID);
            return;
        }
        if (player.travelling) {
            if (faults.contains(Fault.SWAPPED_DOUBLE_REPLY)) {
                player.swapFirst = true;
                replyTravel(conn, request, 0);
            } else {
                replyTravel(conn, request, TravelScenario.TIP_IN_TRANSFER);
            }
            return;
        }
        player.travelling = true;
        timer.schedule(() -> {
            synchronized (this) {
                if (player.conn != conn) {
                    player.travelling = false;
                    return;
                }
                if (player.teamId != 0) {
                    player.travelling = false;
                    replyTravel(conn, request, TravelScenario.TIP_IN_TEAM);
                    return;
                }
                boolean deployed = gates.containsKey(target);
                if (deployed && faults.contains(Fault.REDIRECT_BEFORE_REPLY)) {
                    handOff(conn, player, target, conf);
                    replyTravel(conn, request, 0);
                    return;
                }
                replyTravel(conn, request, player.swapFirst ? TravelScenario.TIP_IN_TRANSFER : 0);
                player.swapFirst = false;
                if (!deployed) {
                    player.travelling = false;
                    conn.session.push(ids.sendTip(), TipInfoMessage.newBuilder().setId(TravelScenario.TIP_TRAVEL_FAILED).build());
                    return;
                }
                handOff(conn, player, target, conf);
            }
        }, replyDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 交出：旁人收 51、写待落点、旧会话推 124 并转 REDIRECTED。 */
    private void handOff(Conn conn, Player player, int target, int conf) {
        Loc source = player.loc;
        for (Player other : players.values()) {
            if (other != player && other.conn != null && other.loc != null && other.loc.kind() == Kind.ONLINE
                    && other.loc.zone() == source.zone() && other.loc.sceneId() == source.sceneId()) {
                other.conn.session.push(ids.notifyActorDestroy(), ActorDestroyS2C.newBuilder().setEntity(player.entity).build());
            }
        }
        if (faults.contains(Fault.SCENE_BEFORE_REDIRECT)) {
            conn.session.push(ids.notifyEnterScene(), enterScene(source.conf(), source.sceneId()));
        }
        player.loc = new Loc(Kind.PENDING, target, conf == 0 ? worlds.get(0) : conf, 0, System.nanoTime() + TimeUnit.SECONDS.toNanos(300));
        player.conn = null;
        player.travelling = false;
        conn.playerId = 0;
        conn.redirected = true;
        conn.session.push(redirect, redirectTo(target, player.id));
        if (faults.contains(Fault.DOUBLE_REDIRECT)) {
            conn.session.push(redirect, redirectTo(target, player.id));
        }
    }

    /** 17 登出：清位置记录（下次按首登落点）；{@link #leaveLag} 非 0 时拖后清，期间记录仍是「本区在线」。 */
    private void leave(Player player) {
        Duration lag = leaveLag;
        if (lag.isZero()) {
            player.loc = null;
            return;
        }
        Loc online = player.loc;
        timer.schedule(() -> {
            synchronized (this) {
                // 这期间又进了场（记录换了）就不动
                if (player.loc == online) {
                    player.loc = null;
                }
            }
        }, lag.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 63 换图（只按配置号）：应答 {0}，推 79 与自己的 21，落在那张图的出生点。 */
    private void switchScene(Conn conn, Player player, ClientRequest request) throws InvalidProtocolBufferException {
        int conf = EnterSceneC2SRequest.parseFrom(request.getBody()).getSceneInfo().getSceneConfigId();
        if (!worlds.contains(conf)) {
            conn.session.reply(request, EnterSceneC2SResponse.newBuilder()
                    .setErrorMessage(TipInfoMessage.newBuilder().setId(TravelScenario.TIP_MAP_INVALID)).build());
            return;
        }
        long sceneId = sceneId(conn.zone, conf);
        player.entity = nextEntity++;
        player.position = spawnOf(conf);
        player.loc = new Loc(Kind.ONLINE, conn.zone, conf, sceneId, 0);
        conn.session.reply(request, EnterSceneC2SResponse.getDefaultInstance());
        conn.session.push(ids.notifyEnterScene(), enterScene(conf, sceneId));
        conn.session.push(ids.notifyActorCreate(), selfActor(player));
    }

    private Vec3 spawnOf(int conf) {
        return spawns.getOrDefault(conf, SPAWN);
    }

    private void replyTravel(Conn conn, ClientRequest request, int tip) {
        travelReplies.add(new TravelReply(request.getId(), tip));
        conn.session.reply(request, TravelToZoneResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(tip)).build());
    }

    private RedirectToGateNotify redirectTo(int zone, long pid) {
        long expire = System.currentTimeMillis() / 1000 + 300;
        ByteString ticket = GateTokenPayload.newBuilder().setGateNodeId(1).setZoneId(zone).setExpireTimestamp(expire).setPlayerId(pid)
                .setTargetZoneId(zone).build().toByteString();
        return RedirectToGateNotify.newBuilder().setTargetIp("127.0.0.1").setTargetPort(gates.get(zone).port()).setTokenPayload(ticket)
                .setTokenSignature(SIGNATURE).setTokenDeadline(expire).build();
    }

    static long sceneId(int zone, int conf) {
        return zone * 1000L + conf;
    }

    private static EnterSceneS2C enterScene(int conf, long sceneId) {
        return EnterSceneS2C.newBuilder().setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(conf).setSceneId(sceneId)).build();
    }

    private static AccountSimplePlayerWrapper wrap(Player player) {
        return AccountSimplePlayerWrapper.newBuilder().setPlayer(AccountSimplePlayer.newBuilder().setPlayerId(player.id)
                .setZoneId(player.homeZone)).build();
    }
}
