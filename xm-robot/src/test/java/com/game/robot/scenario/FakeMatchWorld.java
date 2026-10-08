package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.net.client.ClientFrameDecoder;
import com.game.net.client.ClientFrameEncoder;
import com.game.net.client.ClientFrames;
import com.game.proto.AccountSimplePlayer;
import com.game.proto.BattleActorState;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStartS2C;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientRequest;
import com.game.proto.EnterSceneS2C;
import com.game.proto.MessageContent;
import com.game.proto.RequestBattleTicketRequest;
import com.game.proto.RequestBattleTicketResponse;
import com.game.proto.SceneInfoComp;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.CreatePlayerResponse;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.CancelQueueRequest;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.QueueState;
import com.game.proto.match.RespondChallengeRequest;
import com.game.proto.match.RespondChallengeResponse;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.proto.match.WatchBattleResponse;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamMatchState;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.proto.team.TeamView;
import com.game.robot.client.BattleIds;
import com.game.robot.client.MatchAdminClient;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RobotClient;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.scenario.MatchSupport.Tempo;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 本机假服务端（只给 xm-robot 的单测用）：把匹配类场景对面的几样东西各实现一小块，让 {@link BattleSmokeScenario} /
 * {@link MatchActivityScenario} / {@link Match5v5Scenario} 的编排（先后次序、mark、等待条件、过渡态重试）能在没有切片的情况下整条跑一遍：
 * <ul>
 *   <li>xm-gateway 的 {@code POST /api/assign-gate}（不发令牌，robot 跳过握手）；</li>
 *   <li>gate 大厅连接：登录 48 / 建角 14 / 进游戏 26 + 79 / LeaveGame，以及 xm-match 的十个号——按 match-spec §2、§4、§6、§8 的<b>可见行为</b>
 *       （判定顺序、tip 与文案、推送先后）；</li>
 *   <li>battle 直连面：握手、补拉 140、162 挂机——全员挂机时每条直连收 139 → 150，最后一个开挂机的人随后收到 162 的应答，然后服务端 FIN；
 *       「结算落地」（放战斗锁）与评分入账各延后一小段，场景因此真的会走到 16000 / 16010 的过渡态重试；</li>
 *   <li>xm-match 管理口：读评分（JSON）、活动开战（protobuf）、Prometheus 指标；</li>
 *   <li>xm-team 的整队开战 211（队伍用 {@link #formTeam} 直接摆出来）：拒绝码与肇事者、回包 STARTING、MATCH_STARTED / MATCH_ENDED 推送。</li>
 * </ul>
 * 它<b>不是</b> xm-match 的实现，更不能当成规格的证明：这里只钉 robot 这一侧的逻辑；服务端的行为由 xm-match 自己的测试与本机切片保证。
 * {@link Fault} 是故意做错的几处，用来验证场景的检查咬得住、失败时结果行指向正确的步骤。
 *
 * <p>全部状态以本对象为锁；Netty I/O 线程、HTTP 线程、定时线程都先拿锁再动状态，往连接上写不需要锁（Netty 自己串行）。
 */
final class FakeMatchWorld implements AutoCloseable {

    /** 故意做错的行为（每条对应场景里的一项检查）。 */
    enum Fault {
        /** 148 回一个空包（契约：应答是 Empty，成功不回包）。 */
        REPLY_TO_CANCEL,
        /** 不清上一局的 ready 票：打完再排回 16001。 */
        KEEP_READY_TICKET,
        /** 16000 的文案用了全角逗号。 */
        FULL_WIDTH_COMMA,
        /** 大厅上先 143 后 177。 */
        START_BEFORE_ASSIGNED,
        /** 拒绝切磋时 154 也推给了应答者。 */
        DECLINE_PUSH_TO_RESPONDER,
        /** 1V1 把后入队的人放进 0 队。 */
        SWAP_DUEL_SIDES,
        /** 5V5 按「前 5 后 5」分队（过时的文档写法），不是蛇形。 */
        BLOCK_TEAMS_5V5,
        /** 活动开战把不在线的成员报成 MEMBER_NOT_READY。 */
        ACTIVITY_OFFLINE_AS_NOT_READY,
        /** 补签给的票与开局的 177 不同（过期时刻多 1 ms）。 */
        REISSUE_DIFFERENT_TICKET,
        /** 整队开战时发起人也收到了 MATCH_STARTED（契约：他以 211 的回包为准，不收推送）。 */
        TEAM_STARTED_TO_LEADER,
        /** 211 的肇事者参数写成了有符号十进制（uint64 上半区的玩家号变成负数）。 */
        TEAM_SIGNED_OFFENDER,
        /** 整队开战受理之后 gather 失败：不开局，全员收 MATCH_FAILED（不带 tip）。 */
        TEAM_GATHER_FAILS,
        /** 切磋接受之后 gather 失败：不开局，双方在 154 true 之后各再收一次 154 false。 */
        CHALLENGE_GATHER_FAILS,
        /** 挑战别人一律回 16008（对方不在线）：切磋只走得到「挑战自己 → 16007」这一步，发起、应答、接受都没有发生。 */
        CHALLENGE_TARGET_ALWAYS_OFFLINE,
        /** 收到的第一条 162（开挂机）被拒（应答带 1005），这一局随后照样打到 150——模拟「挂机没开成，靠回合超时的默认行动打完」。 */
        FIRST_AUTO_REJECTED,
        /** 150 里的 settlement 是别的局的（battle_id 对不上外层）。 */
        SETTLEMENT_OF_OTHER_BATTLE
    }

    static final String TOKEN = "fake-admin-token";
    /**
     * 单测用的快节奏：假服务端不限频，「结算落地」只延后 {@link #SETTLE_DELAY_MS}；过渡态的上限 5 s、等开战的上限 3 s
     * （这两个上限只有故意做错的用例会耗满）。
     */
    static final Tempo FAST = new Tempo(Duration.ofMillis(2), Duration.ofMillis(40), Duration.ofSeconds(5), Duration.ofMillis(150),
            Duration.ofMillis(40), Duration.ofSeconds(3));
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** 战斗结束到放战斗锁的延迟（模拟结算经 battle → scene 落地的那一小段）。 */
    static final long SETTLE_DELAY_MS = 120;
    /** 战斗结束到评分入账的延迟（模拟 battle → Kafka → xm-match）。 */
    static final long RATING_DELAY_MS = 60;
    static final int PVE_ROUNDS = 2;
    static final int PVP_ROUNDS = 3;
    private static final String SIGNATURE = "0123456789abcdef".repeat(4);
    private static final int MODE_5V5 = 1;
    private static final int MODE_1V1 = 3;
    private static final int MODE_PVE_SOLO = 4;
    private static final int MODE_PVE_TEAM = 5;
    private static final int MODE_CHALLENGE = 6;

    private static final class Player {
        final long id;
        final String account;
        Channel lobby;
        boolean online;
        Ticket ticket;
        /** 持有战斗锁的那一局；0 = 没有。 */
        long lock;
        long pendingChallenge;
        long ratingCenti = MatchAdminClient.DEFAULT_RATING_CENTI;
        long games;

        Player(long id, String account) {
            this.id = id;
            this.account = account;
        }
    }

    private static final class Ticket {
        final String id = UUID.randomUUID().toString();
        final int mode;
        final int config;
        QueueState state;

        Ticket(int mode, int config, QueueState state) {
            this.mode = mode;
            this.config = config;
            this.state = state;
        }
    }

    private static final class Battle {
        final long id;
        final int mode;
        final List<Long> members;
        final Map<Long, Integer> teams = new LinkedHashMap<>();
        final Map<Long, BattleAssignedS2C> tickets = new HashMap<>();
        final Map<Long, Channel> directs = new LinkedHashMap<>();
        final Set<Long> autos = new HashSet<>();
        boolean finished;

        Battle(long id, int mode, List<Long> members) {
            this.id = id;
            this.mode = mode;
            this.members = List.copyOf(members);
        }
    }

    private record Challenge(long id, long challenger, long target) {
    }

    /** 一支队伍（只够整队开战用：队长、按入队序的成员、开战锁）。 */
    private static final class Team {
        final long id;
        final long leader;
        /** 队长在前，其余按入队序（就是开战的名单顺序）。 */
        final List<Long> members;
        boolean starting;

        Team(long id, long leader, List<Long> members) {
            this.id = id;
            this.leader = leader;
            this.members = List.copyOf(members);
        }
    }

    /** 直连连接上握手之后记下的身份。 */
    private record DirectSession(Battle battle, long playerId) {
    }

    final EnumSet<Fault> faults = EnumSet.noneOf(Fault.class);
    /** PVP（1V1 / 5V5 / 切磋）的终局；缺省 0 队胜。 */
    volatile eBattleOutcome pvpOutcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
    /** 这名玩家的结算比别人晚落地这么久（模拟「队友的锁已放、他的还在」的过渡态）；0 = 没有这样的人。 */
    volatile long slowSettlePlayer;
    volatile long slowSettleExtraMs;

    private final MessageIdRegistry registry;
    private final MessageIds ids;
    private final MatchSupport.Ids matchIds;
    private final BattleIds battleIds;
    private final int leaveGame;
    private final int startTeamMatch;
    private final int notifyTeamSnapshot;
    private final HttpServer http;
    private final NioEventLoopGroup group = new NioEventLoopGroup(2);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fake-match-world-timer");
        t.setDaemon(true);
        return t;
    });
    private final Channel gateServer;
    private final Channel battleServer;
    private final List<RobotClient> clients = new ArrayList<>();

    private final Map<String, Player> byAccount = new HashMap<>();
    private final Map<Long, Player> byId = new HashMap<>();
    private final Map<Channel, String> loggedIn = new HashMap<>();
    private final Map<Channel, Player> sessions = new HashMap<>();
    private final Map<Channel, DirectSession> directSessions = new HashMap<>();
    private final Map<Long, Battle> battles = new HashMap<>();
    private final Map<Integer, List<Long>> queues = new HashMap<>();
    private final Map<Long, Challenge> challenges = new HashMap<>();
    private final Map<Long, Team> teams = new HashMap<>();
    private long nextTeamId = 0xB000_0000_0000_0001L;
    /** 各消息号收到的请求数（用例据此核对「确实发了 / 没多发」）。 */
    private final Map<Integer, Integer> received = new HashMap<>();
    /** 玩家号 / 战斗号 / 切磋号都取 uint64 的上半区（按有符号看是负数）：场景里任何一处按有符号处理都会露馅。 */
    private long nextPlayerId = 0x8000_0000_0000_0001L;
    private long nextBattleId = 0x9000_0000_0000_0001L;
    private long nextChallengeId = 0xA000_0000_0000_0001L;
    /** 成功的开局数，按模式（指标 {@code xm_match_gathers_total{mode, outcome="success"}}）。 */
    private final Map<Integer, Integer> gathersSuccess = new TreeMap<>();
    private int reissuesOk;
    /** 切磋各出口的次数，键是 {@code stage|result}（指标 {@code xm_match_challenges_total{stage, result}}，取值同 xm-match 的小写枚举名）。 */
    private final Map<String, Integer> challengeExits = new TreeMap<>();
    private int ratingsApplied;
    private boolean autoRejectedOnce;

    FakeMatchWorld() throws IOException {
        this.registry = MessageIdRegistry.loadFromClasspath();
        this.ids = MessageIds.resolve(registry);
        this.matchIds = MatchSupport.Ids.resolve(registry);
        this.battleIds = BattleIds.resolve(registry);
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.startTeamMatch = registry.requireId("ClientPlayerTeam", "StartTeamMatch");
        this.notifyTeamSnapshot = registry.requireId("ClientPlayerTeam", "NotifyTeamSnapshot");
        this.gateServer = listen(false);
        this.battleServer = listen(true);
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", this::onHttp);
        http.start();
    }

    MessageIdRegistry registry() {
        return registry;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    /** 连这个假世界的探针客户端（随 {@link #close} 一起关）。 */
    RobotClient newClient() {
        RobotClient client = new RobotClient(baseUrl(), 1, ids, TIMEOUT, TIMEOUT);
        clients.add(client);
        return client;
    }

    PlayerFlow flow(RobotClient client) {
        return new PlayerFlow(client, "fake-password", TIMEOUT, TIMEOUT);
    }

    MatchAdminClient admin() {
        return new MatchAdminClient(baseUrl(), TOKEN, TIMEOUT);
    }

    /** 某个消息号至今收到的请求数。 */
    synchronized int receivedCount(int messageId) {
        return received.getOrDefault(messageId, 0);
    }

    /** 等一个条件成立（至多 3 s，20 ms 一查）：连接关闭前刚发出的请求，服务端要稍后才处理到。 */
    boolean eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 直接摆出一支队伍（建队 / 邀请那些步骤不归假服务端管）；返回队伍号。成员必须已经进过游戏。 */
    synchronized long formTeam(long leader, long... others) {
        List<Long> members = new ArrayList<>();
        members.add(leader);
        for (long other : others) {
            members.add(other);
        }
        Team team = new Team(nextTeamId++, leader, members);
        teams.put(team.id, team);
        return team.id;
    }

    /** 此刻还有战斗锁的玩家数（场景跑完应当为 0）。 */
    synchronized long lockedPlayers() {
        return byId.values().stream().filter(p -> p.lock != 0).count();
    }

    /** 此刻还在队列里的排队票数（场景跑完应当为 0）。 */
    synchronized long queuedTickets() {
        return byId.values().stream().filter(p -> p.ticket != null && p.ticket.state == QueueState.QUEUE_STATE_QUEUED).count();
    }

    @Override
    public void close() {
        clients.forEach(RobotClient::close);
        timer.shutdownNow();
        http.stop(0);
        gateServer.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        battleServer.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- 监听

    private Channel listen(boolean battle) {
        Map<String, Message> accepted = battle
                ? Map.of(BattleTokenVerifyRequest.getDescriptor().getFullName(), BattleTokenVerifyRequest.getDefaultInstance(),
                        ClientRequest.getDescriptor().getFullName(), ClientRequest.getDefaultInstance())
                : Map.of(ClientRequest.getDescriptor().getFullName(), ClientRequest.getDefaultInstance());
        ServerBootstrap bootstrap = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ClientFrameDecoder(accepted, ClientFrames.DEFAULT_MAX_LEN, (ctx, e) -> ctx.close()));
                        ch.pipeline().addLast(ClientFrameEncoder.INSTANCE);
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<Message>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
                                if (battle) {
                                    onDirect(ctx.channel(), msg);
                                } else {
                                    onLobby(ctx.channel(), (ClientRequest) msg);
                                }
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                onClosed(ctx.channel());
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                ctx.close();
                            }
                        });
                    }
                });
        return bootstrap.bind(InetAddress.getLoopbackAddress(), 0).syncUninterruptibly().channel();
    }

    private static int port(Channel server) {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    private synchronized void onClosed(Channel channel) {
        loggedIn.remove(channel);
        Player player = sessions.remove(channel);
        if (player != null && player.lobby == channel) {
            player.online = false;
            player.lobby = null;
        }
        directSessions.remove(channel);
    }

    // ---------------------------------------------------------------- 大厅

    private synchronized void onLobby(Channel ch, ClientRequest request) throws InvalidProtocolBufferException {
        int messageId = request.getMessageId();
        received.merge(messageId, 1, Integer::sum);
        ByteString body = request.getBody();
        Player player = sessions.get(ch);
        if (messageId == ids.login()) {
            String account = LoginRequest.parseFrom(body).getAccount();
            loggedIn.put(ch, account);
            LoginResponse.Builder response = LoginResponse.newBuilder();
            Player existing = byAccount.get(account);
            if (existing != null) {
                response.addPlayers(wrapper(existing.id));
            }
            reply(ch, request, response.build());
        } else if (messageId == ids.createPlayer()) {
            Player created = new Player(nextPlayerId++, loggedIn.get(ch));
            byAccount.put(created.account, created);
            byId.put(created.id, created);
            reply(ch, request, CreatePlayerResponse.newBuilder().addPlayers(wrapper(created.id)).build());
        } else if (messageId == ids.enterGame()) {
            Player entering = byId.get(EnterGameRequest.parseFrom(body).getPlayerId());
            entering.online = true;
            entering.lobby = ch;
            sessions.put(ch, entering);
            reply(ch, request, EnterGameResponse.newBuilder().setPlayerId(entering.id).build());
            push(ch, ids.notifyEnterScene(), EnterSceneS2C.newBuilder()
                    .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(1).setSceneId(1001)).build());
        } else if (messageId == leaveGame) {
            if (player != null) {
                player.online = false;
                sessions.remove(ch);
            }
        } else if (messageId == matchIds.joinQueue()) {
            joinQueue(ch, request, player, JoinQueueRequest.parseFrom(body));
        } else if (messageId == matchIds.cancelQueue()) {
            cancelQueue(ch, request, player, CancelQueueRequest.parseFrom(body));
        } else if (messageId == matchIds.queueStatus()) {
            reply(ch, request, GetQueueStatusResponse.newBuilder()
                    .setState(player == null || player.ticket == null ? QueueState.QUEUE_STATE_NOT_QUEUED : player.ticket.state).build());
        } else if (messageId == matchIds.requestTicket()) {
            reissue(ch, request, player, RequestBattleTicketRequest.parseFrom(body).getBattleId());
        } else if (messageId == matchIds.challenge()) {
            challenge(ch, request, player, ChallengePlayerRequest.parseFrom(body));
        } else if (messageId == matchIds.respondChallenge()) {
            respond(ch, request, player, RespondChallengeRequest.parseFrom(body));
        } else if (messageId == matchIds.watchBattle()) {
            reply(ch, request, WatchBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder()
                    .setId(BattleSmokeChecks.TIP_FEATURE_UNAVAILABLE)).build());
        } else if (messageId == matchIds.listWatchable()) {
            reply(ch, request, ListWatchableBattlesResponse.getDefaultInstance());
        } else if (messageId == startTeamMatch) {
            startTeamMatch(ch, request, player, StartTeamMatchRequest.parseFrom(body));
        } else {
            // 不认识的号：同 gate 对未接入域的做法，回信封 1003
            ch.writeAndFlush(MessageContent.newBuilder().setMessageId(messageId).setId(request.getId())
                    .setErrorMessage(TipInfoMessage.newBuilder().setId(1003)).build());
        }
    }

    private void joinQueue(Channel ch, ClientRequest request, Player player, JoinQueueRequest join) {
        int mode = join.getModeValue();
        int config = join.getBattleConfigId();
        if (mode == MODE_PVE_TEAM && config != 1) {
            reply(ch, request, rejected(BattleSmokeChecks.TIP_TEAM_SIZE_NOT_CONFIGURED, BattleSmokeChecks.TEXT_TEAM_SIZE_NOT_CONFIGURED, ""));
            return;
        }
        if (mode != MODE_5V5 && mode != MODE_1V1 && mode != MODE_PVE_SOLO && mode != MODE_PVE_TEAM) {
            reply(ch, request, rejected(BattleSmokeChecks.TIP_MODE_NOT_OPEN, BattleSmokeChecks.TEXT_MODE_NOT_OPEN, ""));
            return;
        }
        if (player.lock != 0) {
            String text = faults.contains(Fault.FULL_WIDTH_COMMA) ? BattleSmokeChecks.TEXT_IN_BATTLE.replace(',', '，') : BattleSmokeChecks.TEXT_IN_BATTLE;
            reply(ch, request, rejected(BattleSmokeChecks.TIP_IN_BATTLE, text, ""));
            return;
        }
        if (player.ticket != null) {
            // 自愈：已确认没有战斗锁，ready 的残留票删掉放行
            if (player.ticket.state == QueueState.QUEUE_STATE_READY && !faults.contains(Fault.KEEP_READY_TICKET)) {
                player.ticket = null;
            } else {
                reply(ch, request, rejected(BattleSmokeChecks.TIP_ALREADY_QUEUED, BattleSmokeChecks.TEXT_ALREADY_QUEUED, player.ticket.id));
                return;
            }
        }
        if (mode == MODE_PVE_SOLO) {
            player.ticket = new Ticket(mode, config, QueueState.QUEUE_STATE_MATCHED);
            reply(ch, request, JoinQueueResponse.newBuilder().setQueueTicket(player.ticket.id).build());
            startBattle(mode, List.of(player.id));
            return;
        }
        player.ticket = new Ticket(mode, config, QueueState.QUEUE_STATE_QUEUED);
        List<Long> queue = queues.computeIfAbsent(mode * 1_000_000 + config, k -> new ArrayList<>());
        queue.add(player.id);
        reply(ch, request, JoinQueueResponse.newBuilder().setQueueTicket(player.ticket.id).build());
        int required = mode == MODE_5V5 ? 10 : mode == MODE_1V1 ? 2 : 5;
        if (queue.size() >= required) {
            List<Long> group = new ArrayList<>(queue.subList(0, required));
            queue.subList(0, required).clear();
            startBattle(mode, group);
        }
    }

    private void cancelQueue(Channel ch, ClientRequest request, Player player, CancelQueueRequest cancel) {
        if (player != null && player.ticket != null && player.ticket.state == QueueState.QUEUE_STATE_QUEUED
                && (cancel.getQueueTicket().isEmpty() || cancel.getQueueTicket().equals(player.ticket.id))) {
            queues.values().forEach(queue -> queue.remove(player.id));
            player.ticket = null;
        }
        if (faults.contains(Fault.REPLY_TO_CANCEL)) {
            ch.writeAndFlush(MessageContent.newBuilder().setMessageId(request.getMessageId()).setId(request.getId()).build());
        }
    }

    private void reissue(Channel ch, ClientRequest request, Player player, long battleId) {
        Battle battle = battles.get(battleId);
        RequestBattleTicketResponse.Builder response = RequestBattleTicketResponse.newBuilder();
        if (battle == null) {
            response.setErrorMessage(TipInfoMessage.newBuilder().setId(BattleSmokeChecks.TIP_INVALID_PARAMETER)
                    .addParameters(BattleSmokeChecks.TEXT_BATTLE_GONE));
        } else if (battle.finished || !battle.members.contains(player.id)) {
            // battle 的裁决原样透传：房间不在 / 不是成员 → 1005，不带文案
            response.setErrorMessage(TipInfoMessage.newBuilder().setId(BattleSmokeChecks.TIP_INVALID_PARAMETER));
        } else {
            BattleAssignedS2C ticket = battle.tickets.get(player.id);
            response.setAssignment(faults.contains(Fault.REISSUE_DIFFERENT_TICKET)
                    ? ticket.toBuilder().setExpireAtMs(ticket.getExpireAtMs() + 1).build() : ticket);
            reissuesOk++;
        }
        reply(ch, request, response.build());
    }

    private void challenge(Channel ch, ClientRequest request, Player player, ChallengePlayerRequest challenge) {
        long targetId = challenge.getTargetPlayerId();
        Player target = byId.get(targetId);
        TipInfoMessage reject = null;
        String exit = "ok";
        if (targetId == 0 || targetId == player.id) {
            reject = tip(BattleSmokeChecks.TIP_CHALLENGE_SELF, BattleSmokeChecks.TEXT_CHALLENGE_SELF);
            exit = "self";
        } else if (faults.contains(Fault.CHALLENGE_TARGET_ALWAYS_OFFLINE)) {
            reject = tip(BattleSmokeChecks.TIP_CHALLENGE_TARGET_OFFLINE, BattleSmokeChecks.TEXT_CHALLENGE_TARGET_OFFLINE);
            exit = "target_offline";
        } else if (player.lock != 0) {
            reject = tip(BattleSmokeChecks.TIP_CHALLENGE_SELF_BUSY, BattleSmokeChecks.TEXT_CHALLENGE_SELF_BUSY);
            exit = "self_busy";
        } else if (target != null && target.lock != 0) {
            reject = tip(BattleSmokeChecks.TIP_CHALLENGE_TARGET_BUSY, BattleSmokeChecks.TEXT_CHALLENGE_TARGET_BUSY);
            exit = "target_busy";
        } else if (target == null || !target.online) {
            reject = tip(BattleSmokeChecks.TIP_CHALLENGE_TARGET_OFFLINE, BattleSmokeChecks.TEXT_CHALLENGE_TARGET_OFFLINE);
            exit = "target_offline";
        } else if (target.pendingChallenge != 0) {
            reject = tip(BattleSmokeChecks.TIP_CHALLENGE_PENDING, BattleSmokeChecks.TEXT_CHALLENGE_PENDING);
            exit = "pending";
        }
        // 同真服务端：每个出口都计数（挑战自己被拒也算一次 invite）
        challengeExits.merge("invite|" + exit, 1, Integer::sum);
        if (reject != null) {
            reply(ch, request, ChallengePlayerResponse.newBuilder().setErrorMessage(reject).build());
            return;
        }
        Challenge created = new Challenge(nextChallengeId++, player.id, targetId);
        challenges.put(created.id(), created);
        target.pendingChallenge = created.id();
        // 先推 156（推到了才算发起成功），再应答
        push(target.lobby, matchIds.challengeInvite(), ChallengeInviteS2C.newBuilder().setChallengeId(created.id()).setChallengerId(player.id)
                .setChallengerName(player.account).setBattleConfigId(challenge.getBattleConfigId())
                .setExpiresAtMs(System.currentTimeMillis() + BattleSmokeChecks.CHALLENGE_TTL_MS).build());
        reply(ch, request, ChallengePlayerResponse.newBuilder().setChallengeId(created.id()).build());
    }

    private void respond(Channel ch, ClientRequest request, Player player, RespondChallengeRequest respond) {
        Challenge pending = challenges.get(respond.getChallengeId());
        if (pending == null) {
            challengeExits.merge("respond|expired", 1, Integer::sum);
            reply(ch, request, RespondChallengeResponse.newBuilder()
                    .setErrorMessage(tip(BattleSmokeChecks.TIP_CHALLENGE_EXPIRED, BattleSmokeChecks.TEXT_CHALLENGE_EXPIRED)).build());
            return;
        }
        if (pending.target() != player.id) {
            challengeExits.merge("respond|not_target", 1, Integer::sum);
            reply(ch, request, RespondChallengeResponse.newBuilder()
                    .setErrorMessage(tip(BattleSmokeChecks.TIP_CHALLENGE_NOT_TARGET, BattleSmokeChecks.TEXT_CHALLENGE_NOT_TARGET)).build());
            return;
        }
        challenges.remove(pending.id());
        player.pendingChallenge = 0;
        Player challenger = byId.get(pending.challenger());
        ChallengeResultS2C.Builder result = ChallengeResultS2C.newBuilder().setChallengeId(pending.id()).setResponderId(player.id);
        challengeExits.merge(respond.getAccept() ? "respond|accepted" : "respond|declined", 1, Integer::sum);
        if (!respond.getAccept()) {
            pushIfOnline(challenger, matchIds.challengeResult(), result.setAccepted(false).build());
            if (faults.contains(Fault.DECLINE_PUSH_TO_RESPONDER)) {
                push(ch, matchIds.challengeResult(), result.setAccepted(false).build());
            }
            reply(ch, request, RespondChallengeResponse.getDefaultInstance());
            return;
        }
        // 接受：先推发起者、再推应答者，然后才开局
        pushIfOnline(challenger, matchIds.challengeResult(), result.setAccepted(true).build());
        push(ch, matchIds.challengeResult(), result.setAccepted(true).build());
        reply(ch, request, RespondChallengeResponse.getDefaultInstance());
        if (faults.contains(Fault.CHALLENGE_GATHER_FAILS)) {
            pushIfOnline(challenger, matchIds.challengeResult(), result.setAccepted(false).build());
            push(ch, matchIds.challengeResult(), result.setAccepted(false).build());
            return;
        }
        startBattle(MODE_CHALLENGE, List.of(challenger.id, player.id));
    }

    // ---------------------------------------------------------------- 整队开战（xm-team 的 211）

    /**
     * 211 的可见行为（match-spec §7.3）：非队长 4018 → 没配人数的副本 4027 → 按名单逐人预检（不在线 4024 / 有战斗锁 4025 / 票据在途 4026，
     * 都带肇事者）→ 回包 STARTING → 除发起人外推 MATCH_STARTED → 建票开局（177 / 143）→ 全员推 MATCH_ENDED。拒绝都带当前视图。
     */
    private void startTeamMatch(Channel ch, ClientRequest request, Player player, StartTeamMatchRequest start) {
        Team team = teams.values().stream().filter(t -> t.members.contains(player.id)).findFirst().orElse(null);
        if (team == null) {
            reply(ch, request, TeamResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(4013)).setTeam(TeamView.getDefaultInstance())
                    .build());
            return;
        }
        if (team.leader != player.id) {
            reply(ch, request, teamRejected(team, TeamMatchSteps.TIP_NOT_LEADER, 0));
            return;
        }
        if (start.getBattleConfigId() != TeamMatchSteps.BATTLE_CONFIG_ID) {
            reply(ch, request, teamRejected(team, TeamMatchSteps.TIP_DUNGEON_NOT_OPEN, 0));
            return;
        }
        for (long member : team.members) {
            Player p = byId.get(member);
            if (!p.online) {
                reply(ch, request, teamRejected(team, 4024, member));
                return;
            }
            if (p.lock != 0) {
                reply(ch, request, teamRejected(team, TeamMatchSteps.TIP_MEMBER_IN_BATTLE, member));
                return;
            }
            if (p.ticket != null) {
                if (p.ticket.state != QueueState.QUEUE_STATE_READY) {
                    reply(ch, request, teamRejected(team, TeamMatchSteps.TIP_MEMBER_NOT_READY, member));
                    return;
                }
                p.ticket = null;
            }
        }
        team.starting = true;
        reply(ch, request, TeamResponse.newBuilder().setTeam(view(team)).build());
        TeamSnapshotS2C startedPush = TeamSnapshotS2C.newBuilder().setTeam(view(team))
                .setReason(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED).setActorId(team.leader).build();
        for (long member : team.members) {
            if (member != team.leader || faults.contains(Fault.TEAM_STARTED_TO_LEADER)) {
                pushIfOnline(byId.get(member), notifyTeamSnapshot, startedPush);
            }
        }
        if (faults.contains(Fault.TEAM_GATHER_FAILS)) {
            team.starting = false;
            TeamSnapshotS2C failedPush = TeamSnapshotS2C.newBuilder().setTeam(view(team))
                    .setReason(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED).build();
            team.members.forEach(member -> pushIfOnline(byId.get(member), notifyTeamSnapshot, failedPush));
            return;
        }
        for (long member : team.members) {
            byId.get(member).ticket = new Ticket(MODE_PVE_TEAM, start.getBattleConfigId(), QueueState.QUEUE_STATE_MATCHED);
        }
        startBattle(MODE_PVE_TEAM, team.members);
        // 开局成功、开战锁释放后即推 MATCH_ENDED（早于战斗结束）
        team.starting = false;
        TeamSnapshotS2C endedPush = TeamSnapshotS2C.newBuilder().setTeam(view(team))
                .setReason(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED).build();
        for (long member : team.members) {
            pushIfOnline(byId.get(member), notifyTeamSnapshot, endedPush);
        }
    }

    private TeamResponse teamRejected(Team team, int tipId, long offender) {
        TipInfoMessage.Builder tip = TipInfoMessage.newBuilder().setId(tipId);
        if (offender != 0) {
            tip.addParameters(faults.contains(Fault.TEAM_SIGNED_OFFENDER) ? Long.toString(offender) : Long.toUnsignedString(offender));
        }
        return TeamResponse.newBuilder().setErrorMessage(tip).setTeam(view(team)).build();
    }

    private static TeamView view(Team team) {
        TeamView.Builder view = TeamView.newBuilder().setTeamId(team.id).setLeaderId(team.leader)
                .setMatchState(team.starting ? TeamMatchState.TEAM_MATCH_STATE_STARTING : TeamMatchState.TEAM_MATCH_STATE_IDLE);
        for (int i = 0; i < team.members.size(); i++) {
            view.addMembers(TeamMemberView.newBuilder().setPlayerId(team.members.get(i)).setJoinSeq(i + 1));
        }
        return view.build();
    }

    // ---------------------------------------------------------------- 开局与直连

    /** 建一局：全员落战斗锁、票据置 ready，按玩家号升序逐人推 177 → 143。 */
    private Battle startBattle(int mode, List<Long> members) {
        Battle battle = new Battle(nextBattleId++, mode, members);
        battles.put(battle.id, battle);
        long deadline = System.currentTimeMillis() + BattleSmokeChecks.BATTLE_DURATION_MS;
        for (int i = 0; i < members.size(); i++) {
            int team = switch (mode) {
                case MODE_1V1 -> faults.contains(Fault.SWAP_DUEL_SIDES) ? 1 - i : i;
                case MODE_CHALLENGE -> i;
                case MODE_5V5 -> faults.contains(Fault.BLOCK_TEAMS_5V5) ? i / 5 : BattleSmokeChecks.SNAKE_5V5.get(i);
                default -> 0;
            };
            battle.teams.put(members.get(i), team);
        }
        BattleStateS2C.Builder state = BattleStateS2C.newBuilder().setBattleId(battle.id).setRoundIndex(1);
        for (long member : members) {
            state.addActors(BattleActorState.newBuilder().setActorId(member).setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER)
                    .setTeamIndex(battle.teams.get(member)));
        }
        for (long member : members.stream().sorted(Long::compareUnsigned).toList()) {
            Player player = byId.get(member);
            player.lock = battle.id;
            if (player.ticket != null) {
                player.ticket.state = QueueState.QUEUE_STATE_READY;
            }
            BattleAssignedS2C assigned = BattleAssignedS2C.newBuilder().setBattleId(battle.id).setHost("127.0.0.1").setPort(port(battleServer))
                    .setTokenPayload(BattleTicketPayload.newBuilder().setBattleId(battle.id).setPlayerId(member).setBattleNodeId(7)
                            .setBattleInstanceId("fake-battle").setExpireAtMs(deadline)
                            .setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT).build().toByteString())
                    .setTokenSignature(ByteString.copyFromUtf8(SIGNATURE)).setExpireAtMs(deadline)
                    .setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT).build();
            battle.tickets.put(member, assigned);
            BattleStartS2C start = BattleStartS2C.newBuilder().setBattleId(battle.id).setState(state).build();
            if (faults.contains(Fault.START_BEFORE_ASSIGNED)) {
                pushIfOnline(player, battleIds.battleStart(), start);
                pushIfOnline(player, battleIds.battleAssigned(), assigned);
            } else {
                pushIfOnline(player, battleIds.battleAssigned(), assigned);
                pushIfOnline(player, battleIds.battleStart(), start);
            }
        }
        gathersSuccess.merge(mode, 1, Integer::sum);
        return battle;
    }

    private synchronized void onDirect(Channel ch, Message message) throws InvalidProtocolBufferException {
        if (message instanceof BattleTokenVerifyRequest verify) {
            BattleTicketPayload payload = BattleTicketPayload.parseFrom(verify.getPayload());
            Battle battle = battles.get(payload.getBattleId());
            boolean valid = battle != null && !battle.finished && battle.members.contains(payload.getPlayerId())
                    && verify.getSignature().toStringUtf8().equals(SIGNATURE);
            if (!valid) {
                ch.writeAndFlush(BattleTokenVerifyResponse.newBuilder().setError("battle not found or player not in this battle").build())
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            battle.directs.put(payload.getPlayerId(), ch);
            directSessions.put(ch, new DirectSession(battle, payload.getPlayerId()));
            ch.writeAndFlush(BattleTokenVerifyResponse.newBuilder().setSuccess(true).setBattleId(battle.id).build());
            return;
        }
        ClientRequest request = (ClientRequest) message;
        DirectSession session = directSessions.get(ch);
        if (session == null) {
            ch.close();
            return;
        }
        Battle battle = session.battle();
        if (request.getMessageId() == battleIds.getBattleState()) {
            reply(ch, request, stateOf(battle));
        } else if (request.getMessageId() == battleIds.setAutoBattle()) {
            if (faults.contains(Fault.FIRST_AUTO_REJECTED) && !autoRejectedOnce) {
                // 拒绝在前；这一局随后照样「打完」（当作回合超时的默认行动），没有人再收到 162 的应答
                autoRejectedOnce = true;
                reply(ch, request, SetAutoBattleResponse.newBuilder()
                        .setErrorMessage(tip(BattleSmokeChecks.TIP_INVALID_PARAMETER, "战斗不存在")).build());
                battle.autos.add(session.playerId());
                if (!battle.finished && battle.autos.containsAll(battle.members)) {
                    finish(battle, null, null);
                }
                return;
            }
            battle.autos.add(session.playerId());
            if (!battle.finished && battle.autos.containsAll(battle.members)) {
                finish(battle, ch, request);
            } else {
                reply(ch, request, SetAutoBattleResponse.getDefaultInstance());
            }
        }
    }

    private static BattleStateS2C stateOf(Battle battle) {
        BattleStateS2C.Builder state = BattleStateS2C.newBuilder().setBattleId(battle.id).setRoundIndex(1);
        battle.teams.forEach((member, team) -> state.addActors(BattleActorState.newBuilder().setActorId(member)
                .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER).setTeamIndex(team)));
        return state.build();
    }

    /** 全员挂机：每条直连 139 → 150，最后开挂机的那位随后收到 162 的应答，然后服务端 FIN；锁与评分各延后一小段落地。 */
    private void finish(Battle battle, Channel last, ClientRequest lastRequest) {
        battle.finished = true;
        boolean pve = battle.mode == MODE_PVE_SOLO || battle.mode == MODE_PVE_TEAM;
        eBattleOutcome outcome = pve ? eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN : pvpOutcome;
        int rounds = pve ? PVE_ROUNDS : PVP_ROUNDS;
        battle.directs.forEach((member, direct) -> {
            push(direct, battleIds.turnResult(), TurnResultS2C.newBuilder().setBattleId(battle.id).setRoundIndex(rounds).build());
            push(direct, battleIds.battleEnd(), BattleEndS2C.newBuilder().setBattleId(battle.id).setOutcome(outcome)
                    .setSettlement(BattleSettlementData.newBuilder()
                            .setBattleId(faults.contains(Fault.SETTLEMENT_OF_OTHER_BATTLE) ? battle.id + 1 : battle.id).setPlayerId(member)
                            .setOutcome(outcome)
                            .setPlayerTeamIndex(battle.teams.get(member)).setTotalRounds(rounds)).build());
            if (direct == last) {
                reply(direct, lastRequest, SetAutoBattleResponse.getDefaultInstance());
            }
            // 排空输出后半关闭：客户端看到的是 FIN
            direct.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(f -> ((SocketChannel) direct).shutdownOutput());
        });
        for (long member : battle.members) {
            long delay = SETTLE_DELAY_MS + (member == slowSettlePlayer ? slowSettleExtraMs : 0);
            timer.schedule(() -> settle(battle, member), delay, TimeUnit.MILLISECONDS);
        }
        if (battle.mode == MODE_1V1 || battle.mode == MODE_5V5) {
            timer.schedule(() -> applyRatings(battle, outcome, rounds), RATING_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** 一名成员的结算落地：放他的战斗锁（ready 票留着，下次排队 / 开战预检时自愈）。 */
    private synchronized void settle(Battle battle, long member) {
        Player player = byId.get(member);
        if (player.lock == battle.id) {
            player.lock = 0;
        }
    }

    private synchronized void applyRatings(Battle battle, eBattleOutcome outcome, int rounds) {
        long delta = BattleSmokeChecks.evenDeltaCenti(outcome, rounds);
        for (long member : battle.members) {
            Player player = byId.get(member);
            player.ratingCenti += battle.teams.get(member) == 0 ? delta : -delta;
            player.games++;
        }
        ratingsApplied++;
    }

    // ---------------------------------------------------------------- HTTP

    private void onHttp(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readAllBytes();
        try {
            if (path.equals("/api/assign-gate")) {
                respond(exchange, 200, "application/json",
                        ("{\"code\":0,\"gate_ip\":\"127.0.0.1\",\"gate_port\":" + port(gateServer) + "}").getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/actuator/prometheus")) {
                respond(exchange, 200, "text/plain", metrics().getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/admin/")) {
                if (!TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Xm-Admin-Token"))) {
                    respond(exchange, 401, "text/plain", new byte[0]);
                } else if (exchange.getRequestHeaders().getFirst("X-Xm-Operator") == null) {
                    respond(exchange, 400, "text/plain", "缺少操作人".getBytes(StandardCharsets.UTF_8));
                } else if (path.startsWith(MatchAdminClient.RATING_PATH) && exchange.getRequestMethod().equals("GET")) {
                    respond(exchange, 200, "application/json", rating(Long.parseUnsignedLong(path.substring(MatchAdminClient.RATING_PATH.length())))
                            .getBytes(StandardCharsets.UTF_8));
                } else if (path.equals(MatchAdminClient.ACTIVITY_BATTLE_PATH) && exchange.getRequestMethod().equals("POST")) {
                    respond(exchange, 200, MatchAdminClient.CONTENT_TYPE, activityBattle(StartActivityBattleRequest.parseFrom(body)).toByteArray());
                } else {
                    respond(exchange, 404, "text/plain", new byte[0]);
                }
            } else {
                respond(exchange, 404, "text/plain", new byte[0]);
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, "text/plain", String.valueOf(e).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private synchronized String rating(long playerId) {
        Player player = byId.get(playerId);
        long centi = player == null ? MatchAdminClient.DEFAULT_RATING_CENTI : player.ratingCenti;
        return "{\"player_id\":\"" + Long.toUnsignedString(playerId) + "\",\"rating\":\"" + BigDecimal.valueOf(centi, 2).toPlainString()
                + "\",\"games\":" + (player == null ? 0 : player.games) + "}";
    }

    private synchronized StartActivityBattleResponse activityBattle(StartActivityBattleRequest request) {
        List<Long> members = request.getMemberPlayerIdsList();
        if (members.isEmpty() || members.size() > 5 || request.getBattleConfigId() == 0 || !request.hasActivityContext()
                || request.getActivityContext().getKindValue() != 1 || request.getActivityContext().getGuildId() == 0
                || request.getActivityContext().getActivityId() == 0 || request.getActivityContext().getPeriodKey() == 0
                || request.getActivityContext().getGuildPeriodKey() == 0 || request.getActivityContext().getInitiatorPlayerId() != members.get(0)) {
            return StartActivityBattleResponse.newBuilder().setReject(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT).build();
        }
        for (long member : members) {
            Player player = byId.get(member);
            if (player == null || !player.online) {
                return StartActivityBattleResponse.newBuilder().setOffenderPlayerId(member)
                        .setReject(faults.contains(Fault.ACTIVITY_OFFLINE_AS_NOT_READY) ? ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY
                                : ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE).build();
            }
            if (player.lock != 0) {
                return StartActivityBattleResponse.newBuilder().setOffenderPlayerId(member)
                        .setReject(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE).build();
            }
            if (player.ticket != null && player.ticket.state != QueueState.QUEUE_STATE_READY) {
                return StartActivityBattleResponse.newBuilder().setOffenderPlayerId(member)
                        .setReject(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY).build();
            }
        }
        for (long member : members) {
            byId.get(member).ticket = new Ticket(MODE_PVE_TEAM, request.getBattleConfigId(), QueueState.QUEUE_STATE_MATCHED);
        }
        return StartActivityBattleResponse.newBuilder().setBattleId(startBattle(MODE_PVE_TEAM, members).id).build();
    }

    /**
     * 指标文本，形状照真服务端的 Prometheus 输出：每条序列都带公共标签 {@code application}，标签按名字排序；标签取值同 xm-match
     * （{@code mode} 是 {@code MatchMode} 的枚举名，切磋的 {@code stage} / {@code result} 是小写枚举名）。
     */
    private synchronized String metrics() {
        String app = "application=\"xm-match\",";
        StringBuilder text = new StringBuilder("# HELP xm_match_gathers_total fake\n# TYPE xm_match_gathers_total counter\n");
        gathersSuccess.forEach((mode, count) -> text.append("xm_match_gathers_total{").append(app).append("mode=\"")
                .append(MatchMode.forNumber(mode).name()).append("\",outcome=\"success\"} ").append((double) count).append('\n'));
        // 不该被算进去的序列：别的 outcome、别的 result
        text.append("xm_match_gathers_total{").append(app).append("mode=\"MATCH_MODE_PVE_SOLO\",outcome=\"prepare_failed\"} 5.0\n");
        text.append("xm_match_battle_ticket_reissues_total{").append(app).append("result=\"ok\"} ").append((double) reissuesOk).append('\n');
        text.append("xm_match_battle_ticket_reissues_total{").append(app).append("result=\"not_found\"} 7.0\n");
        challengeExits.forEach((exit, count) -> {
            String[] parts = exit.split("\\|");
            text.append("xm_match_challenges_total{").append(app).append("result=\"").append(parts[1]).append("\",stage=\"").append(parts[0])
                    .append("\"} ").append((double) count).append('\n');
        });
        text.append("xm_match_rating_updates_total{").append(app).append("mode=\"MATCH_MODE_1V1\",outcome=\"applied\"} ")
                .append((double) ratingsApplied).append('\n');
        text.append("xm_match_rating_updates_total{").append(app).append("mode=\"MATCH_MODE_PVE_SOLO\",outcome=\"ignored\"} 9.0\n");
        return text.toString();
    }

    // ---------------------------------------------------------------- 小件

    private static AccountSimplePlayerWrapper wrapper(long playerId) {
        return AccountSimplePlayerWrapper.newBuilder().setPlayer(AccountSimplePlayer.newBuilder().setPlayerId(playerId)).build();
    }

    private static JoinQueueResponse rejected(int code, String text, String ticket) {
        return JoinQueueResponse.newBuilder().setErrorCode(code).setErrorMessage(tip(code, text)).setQueueTicket(ticket).build();
    }

    private static TipInfoMessage tip(int code, String text) {
        return TipInfoMessage.newBuilder().setId(code).addParameters(text).build();
    }

    /** 应答：回显请求的消息号与 id。 */
    private static void reply(Channel ch, ClientRequest request, Message body) {
        ch.writeAndFlush(MessageContent.newBuilder().setMessageId(request.getMessageId()).setId(request.getId())
                .setSerializedMessage(body.toByteString()).build());
    }

    /** 推送：id = 0。 */
    private static void push(Channel ch, int messageId, Message body) {
        ch.writeAndFlush(MessageContent.newBuilder().setMessageId(messageId).setSerializedMessage(body.toByteString()).build());
    }

    private static void pushIfOnline(Player player, int messageId, Message body) {
        if (player != null && player.online && player.lobby != null) {
            push(player.lobby, messageId, body);
        }
    }
}
