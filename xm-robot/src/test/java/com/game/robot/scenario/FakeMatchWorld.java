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
import com.game.proto.SpectateEndS2C;
import com.game.proto.SpectateStateS2C;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.CreatePlayerResponse;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.CancelQueueRequest;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.QueueState;
import com.game.proto.match.RespondChallengeRequest;
import com.game.proto.match.RespondChallengeResponse;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.proto.match.WatchBattleRequest;
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
import com.game.table.MatchErrorTip;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *   <li>xm-team 的整队开战 211（队伍用 {@link #formTeam} 直接摆出来）：拒绝码与肇事者、回包 STARTING、MATCH_STARTED / MATCH_ENDED 推送；</li>
 *   <li>观战（批次 6.5，spectate-spec §3）：163 的判定顺序与文案、164 的收口与排序、观众票 177（role = 2；重看同一场时经活着的直连直写）、
 *       观众直连（握手应答之后紧跟 161，收尾 158 → 166 → FIN）、165（应答 → FIN、不推 166）、开局前清退（166 REMOVED）、
 *       已结束的战斗留在索引里直到被 163 懒剔除、ready 残留票挡住 163；残留场次与别人的活战斗用 {@link #seedFinishedBattles} /
 *       {@link #seedLiveBattle} 摆出来；</li>
 *   <li>第二个区（跨区 1V1）：assign-gate 按 {@code zone_id} 给不同端口的 gate、{@code GET /api/server-list}、玩家所在区 = 进来的 gate 的区、
 *       各局的区组成指标、结算落地后经大厅补推 150。</li>
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
        /**
         * 收到的第 {@link FakeMatchWorld#rejectAutoAt} 条 162（开挂机；缺省第一条）被拒（应答带 1005），这一局随后照样打到 150——模拟「挂机没开成，
         * 靠回合超时的默认行动打完」。
         */
        FIRST_AUTO_REJECTED,
        /** 150 里的 settlement 是别的局的（battle_id 对不上外层）。 */
        SETTLEMENT_OF_OTHER_BATTLE,

        // ---- 观战（批次 6.5；每条对应观战段 / 跨区场景里的一项检查） ----

        /** 重看同一场时，重推的 177 走了大厅而不是活着的直连（spectate-spec 评审 E-3 改掉的那个错误假设）。 */
        REWATCH_TICKET_TO_LOBBY,
        /** 重看同一场时重推的 177 与第一条不同（过期时刻多 1 ms：票不是确定性的）。 */
        REWATCH_TICKET_DIFFERS,
        /** 观众补签（179）给的票与 163 之后推的那张 177 不同（过期时刻多 1 ms）。 */
        OBSERVER_REISSUE_DIFFERS,
        /** 观众一去排队就被清退（契约：只排队不清退，进 gather 才清退）。 */
        QUEUE_EVICTS_OBSERVER,
        /** 开局前不清退观众（观众去排 PVE_SOLO 之后，原来那场的直连上没有 166 REMOVED）。 */
        NO_EVICT_ON_GATHER,
        /** 对已结束的战斗发 163 时不把它从可观战索引里剔除（之后的 164 里还有它）。 */
        ENDED_BATTLE_STAYS_LISTED,
        /** 161 / 158 没有按观众视角裁剪：actor 带着技能冷却。 */
        OBSERVER_SEES_COOLDOWNS,
        /** 165 主动退出时也推了 166。 */
        STOP_WATCH_PUSHES_END,
        /** 观众的 158 在大厅连接上也推了一份（战斗帧回落大厅）。 */
        SPECTATE_TURN_TO_LOBBY,
        /** 列表摘要的 player_names 用了账号名而不是角色名。 */
        SUMMARY_NAMES_ARE_ACCOUNTS,
        /**
         * 第一场单人 PVE 在参战者直连补拉 140 的时候就已经自己打完：直连上 139 → 150 先于 140 的应答到达，然后 FIN（模拟「屏障期的战斗 X
         * 提前结束」；150 排在场景等的那条应答之前，所以场景往下走时一定已经看得到它，用例是确定的）。
         */
        BARRIER_BATTLE_ENDS_EARLY,
        /**
         * 切磋局只有一个回合：参战者在局里发第一条 163 之后，这一局随即自己打完（模拟真服务端上「切磋双方带着上一局的残血进场，
         * 不开自动的切磋局 6 s 就结束」——S12 在局里只来得及发一条 163，等不了 ready 残留）。
         */
        CHALLENGE_ENDS_AFTER_FIRST_WATCH,
        /** 163 成功的应答带了一个 id = 0 的空 error_message。 */
        WATCH_OK_WITH_EMPTY_TIP,
        /** 1V1 把两名玩家的直连票签到了不同的节点实例上。 */
        DUEL_TICKETS_DIFFERENT_INSTANCE,
        /** 结算落地后不经大厅补推 150。 */
        NO_LOBBY_END,
        /** 跨区的一局记成了同区（{@code mix="single"}）。 */
        CROSS_ZONE_COUNTED_SINGLE
    }

    static final String TOKEN = "fake-admin-token";
    /**
     * 单测用的快节奏：假服务端不限频，「结算落地」只延后 {@link #SETTLE_DELAY_MS}；过渡态的上限 5 s、等开战的上限 3 s
     * （这两个上限只有故意做错的用例会耗满）。
     */
    static final Tempo FAST = new Tempo(Duration.ofMillis(2), Duration.ofMillis(40), Duration.ofSeconds(5), Duration.ofMillis(150),
            Duration.ofMillis(40), Duration.ofSeconds(3));
    /**
     * 观战段的快节奏：各种上限只有故意做错的用例会耗满；屏障期的预算与 ready 残留的窗口留得很宽（假服务端的战斗不会自己打完、
     * ready 残留按「被 153 查到的次数」过期，都不看墙钟）；等结算落地的上限 3 s（假服务端 {@link #SETTLE_DELAY_MS} 之后就推大厅 150）。
     */
    static final SpectateSteps.Timing FAST_SPECTATE = new SpectateSteps.Timing(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofSeconds(30),
            Duration.ofSeconds(3), 30);
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
        /** 角色名（与账号名不同：可观战列表下发的是它）。 */
        final String name;
        Channel lobby;
        boolean online;
        /** 进游戏时所在 gate 的区。 */
        int zone = 1;
        Ticket ticket;
        /** 持有战斗锁的那一局；0 = 没有。 */
        long lock;
        long pendingChallenge;
        /** 观战标记指向的那一场；0 = 没有。165 与战斗收尾都不清它（基线如此）。 */
        long watching;
        long ratingCenti = MatchAdminClient.DEFAULT_RATING_CENTI;
        long games;

        Player(long id, String account, String name) {
            this.id = id;
            this.account = account;
            this.name = name;
        }
    }

    private static final class Ticket {
        final String id = UUID.randomUUID().toString();
        final int mode;
        final int config;
        QueueState state;
        /** 这张票开出来的那一局；0 = 还没开局。 */
        long battleId;
        /** 那一局打完之后，这张 ready 票还要被 153 查到多少次（每次回 READY）才算过期（模拟 60 s 的 ready 窗口，不看墙钟）。 */
        int residuePollsLeft;

        Ticket(int mode, int config, QueueState state) {
            this.mode = mode;
            this.config = config;
            this.state = state;
        }
    }

    private static final class Battle {
        final long id;
        final int mode;
        final int config;
        final List<Long> members;
        final Map<Long, Integer> teams = new LinkedHashMap<>();
        final Map<Long, BattleAssignedS2C> tickets = new HashMap<>();
        final Map<Long, Channel> directs = new LinkedHashMap<>();
        final Set<Long> autos = new HashSet<>();
        /** 观众名单（按登记次序）与各自的直连（没连上的不在这张表里）。 */
        final Set<Long> observers = new LinkedHashSet<>();
        final Map<Long, Channel> observerDirects = new LinkedHashMap<>();
        long createdAtMs;
        long deadlineMs;
        boolean finished;
        /**
         * 这一局是自己打完的（不是最后一条 162 促成的）：打完时参战者的直连先不 FIN，之后到的 162 回拒绝、再 FIN
         * （同真服务端：房间没了之后才读到的 162，应答排在这一局的 150 之后）。
         */
        boolean lingering;

        Battle(long id, int mode, int config, List<Long> members) {
            this.id = id;
            this.mode = mode;
            this.config = config;
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
    private record DirectSession(Battle battle, long playerId, boolean observer) {
    }

    final EnumSet<Fault> faults = EnumSet.noneOf(Fault.class);
    /** PVP（1V1 / 5V5 / 切磋）的终局；缺省 0 队胜。 */
    volatile eBattleOutcome pvpOutcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
    /** 这名玩家的结算比别人晚落地这么久（模拟「队友的锁已放、他的还在」的过渡态）；0 = 没有这样的人。 */
    volatile long slowSettlePlayer;
    volatile long slowSettleExtraMs;
    /**
     * 一局打完之后，它留下的 ready 票据还要被 153 查到多少次才过期（真服务端是 60 s 的 ready 窗口；这里按次数，不看墙钟，
     * 用例因此是确定的）。票据在的时候 153 回 READY、163 一律回 16014（163 不让它过期，同真服务端的 BW1：163 对票据不自愈）。
     * 缺省 1：场景的 S12 在切磋开局之前先见到一次 READY 再见到 NOT_QUEUED。
     */
    volatile int readyResiduePolls = 1;
    /** 第几场打完的单人 PVE 以阵亡收场（SIDE_B_WIN；从 1 数起，按假服务端里打完的先后）；0 = 都打赢。 */
    volatile int soloPveLostAt;
    /**
     * 开局之后还要再收到多少条 164，这一局才登记进可观战索引（真服务端里开局公告先于公开，紧跟着发的 164 可能还看不到它）。按条数而不按墙钟，
     * 用例因此是确定的；0 = 开局即公开。
     */
    volatile int publishAfterLists;
    /** {@link Fault#FIRST_AUTO_REJECTED} 拒绝的是第几条 162（从 1 数起，按假服务端收到的先后）。 */
    volatile int rejectAutoAt = 1;

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
    /** 第二个区的 gate（跨区场景用；它与一区的 gate 端口不同，背后是同一个世界）。 */
    private final Channel gateServer2;
    private final Channel battleServer;
    private final List<RobotClient> clients = new ArrayList<>();
    /** 区服列表里 OPEN 的区（缺省两个区都开；用例可以摘掉二区模拟单 zone 切片）。 */
    private final Set<Integer> openZones = new TreeSet<>(List.of(1, 2));
    /** 可观战索引：已公开的战斗号。已结束的战斗留在里面，直到有人对它发 163 才被剔除（基线的懒剔除，BW7）。 */
    private final Set<Long> watchable = new LinkedHashSet<>();
    /** 已开局、还没登记进索引的战斗 → 还要再等几条 164（见 {@link #publishAfterLists}）。 */
    private final Map<Long, Integer> unpublished = new LinkedHashMap<>();
    /** {@link Fault#BARRIER_BATTLE_ENDS_EARLY} 只对第一场单人 PVE 生效。 */
    private boolean barrierEnded;
    /** 163 各出口的次数（指标 {@code xm_match_watch_battle_total{outcome}}）。 */
    private final Map<String, Integer> watchOutcomes = new TreeMap<>();
    /** 清退各结局的次数，键是 {@code reason|result}（指标 {@code xm_match_spectate_evictions_total{reason, result}}）。 */
    private final Map<String, Integer> evictions = new TreeMap<>();
    /** 各局的区组成，键是 {@code mode|mix}（指标 {@code xm_match_gather_zone_mix_total{mode, mix}}）。 */
    private final Map<String, Integer> zoneMix = new TreeMap<>();

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
    /** 至今收到的 162 条数（{@link Fault#FIRST_AUTO_REJECTED} 按它数到第几条）。 */
    private int autoRequests;
    /** 至今打完的单人 PVE 场数（{@link #soloPveLostAt} 按它数到第几场）。 */
    private int soloPveFinished;
    /** 发 LeaveGame 时还持着战斗锁（结算还没落地）的次数：场景该等到结算落地（大厅 150）再下线。 */
    private int leftWhileLocked;

    FakeMatchWorld() throws IOException {
        this.registry = MessageIdRegistry.loadFromClasspath();
        this.ids = MessageIds.resolve(registry);
        this.matchIds = MatchSupport.Ids.resolve(registry);
        this.battleIds = BattleIds.resolve(registry);
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.startTeamMatch = registry.requireId("ClientPlayerTeam", "StartTeamMatch");
        this.notifyTeamSnapshot = registry.requireId("ClientPlayerTeam", "NotifyTeamSnapshot");
        this.gateServer = listen(false);
        this.gateServer2 = listen(false);
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

    /** 连这个假世界一区的探针客户端（随 {@link #close} 一起关）。 */
    RobotClient newClient() {
        return newClient(1);
    }

    /** 连这个假世界某个区的探针客户端：assign-gate 按区给 gate（一区、二区各一个端口）。 */
    RobotClient newClient(int zone) {
        RobotClient client = new RobotClient(baseUrl(), zone, ids, TIMEOUT, TIMEOUT);
        clients.add(client);
        return client;
    }

    /** 把一个区从「OPEN」里摘掉：区服列表里它变成维护，assign-gate 不再给它分 gate（模拟只起了一个区的切片）。 */
    synchronized void closeZone(int zone) {
        openZones.remove(zone);
    }

    /** 两个区的 gate 端口（跨区用例核对「两人确实走了不同的 gate」）。 */
    int gatePort(int zone) {
        return port(zone == 2 ? gateServer2 : gateServer);
    }

    /**
     * 往可观战索引里摆 {@code count} 场<b>已结束</b>的残留战斗（上一遍 robot 留下的：落点还在、房间已经没了），都比之后新开的局老。
     * 随机观战先挑老的，所以每条 163(0) 会连着扑空两场、各剔掉一场，然后回 16017。
     */
    synchronized void seedFinishedBattles(int count) {
        for (int i = 0; i < count; i++) {
            Battle residue = new Battle(nextBattleId++, MODE_PVE_SOLO, 1, List.of());
            residue.finished = true;
            residue.createdAtMs = System.currentTimeMillis() - 120_000 - i;
            battles.put(residue.id, residue);
            watchable.add(residue.id);
        }
    }

    /** 往可观战索引里摆一场<b>别人的、还活着</b>的战斗（参战者不在这个假世界里登录）；返回它的战斗号。它比之后新开的局老，随机观战先挑中它。 */
    synchronized long seedLiveBattle() {
        Battle stranger = new Battle(nextBattleId++, MODE_PVE_SOLO, 1, List.of());
        stranger.createdAtMs = System.currentTimeMillis() - 30_000;
        stranger.deadlineMs = stranger.createdAtMs + BattleSmokeChecks.BATTLE_DURATION_MS;
        battles.put(stranger.id, stranger);
        watchable.add(stranger.id);
        return stranger.id;
    }

    /** 此刻可观战索引里的战斗数（含已结束、还没被懒剔除的）。 */
    synchronized int watchableCount() {
        return watchable.size();
    }

    /** 此刻各场战斗的观众总数（场景跑完应当为 0：收尾、165、清退都会摘掉）。 */
    synchronized long observerCount() {
        return battles.values().stream().mapToLong(b -> b.observers.size()).sum();
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

    /**
     * 至今有多少次 LeaveGame 是在玩家还持着战斗锁（他那一局的结算还没落地）时发的。真服务端上这样离场的玩家，battle 的结算发件箱要对着
     * 离线玩家重投约两分钟才放弃。
     */
    synchronized int leftWhileLocked() {
        return leftWhileLocked;
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
        gateServer2.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
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
        DirectSession direct = directSessions.remove(channel);
        if (direct != null && direct.observer()) {
            // 观众的直连断了只摘直连，名单里的人还在（同真服务端：下一次握手或重看再补首帧）
            direct.battle().observerDirects.remove(direct.playerId(), channel);
        }
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
            Player created = new Player(nextPlayerId++, loggedIn.get(ch), "侠客" + (byId.size() + 1));
            byAccount.put(created.account, created);
            byId.put(created.id, created);
            reply(ch, request, CreatePlayerResponse.newBuilder().addPlayers(wrapper(created.id)).build());
        } else if (messageId == ids.enterGame()) {
            Player entering = byId.get(EnterGameRequest.parseFrom(body).getPlayerId());
            entering.online = true;
            entering.lobby = ch;
            // 所在区 = 进来的那台 gate 的区（同真服务端：位置记录与在线目录的 zone 都取 gate 的）
            entering.zone = ch.parent() == gateServer2 ? 2 : 1;
            sessions.put(ch, entering);
            reply(ch, request, EnterGameResponse.newBuilder().setPlayerId(entering.id).build());
            push(ch, ids.notifyEnterScene(), EnterSceneS2C.newBuilder()
                    .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(1).setSceneId(1001)).build());
        } else if (messageId == leaveGame) {
            if (player != null) {
                if (player.lock != 0) {
                    leftWhileLocked++;
                }
                player.online = false;
                sessions.remove(ch);
            }
        } else if (messageId == matchIds.joinQueue()) {
            joinQueue(ch, request, player, JoinQueueRequest.parseFrom(body));
        } else if (messageId == matchIds.cancelQueue()) {
            cancelQueue(ch, request, player, CancelQueueRequest.parseFrom(body));
        } else if (messageId == matchIds.queueStatus()) {
            reply(ch, request, GetQueueStatusResponse.newBuilder().setState(queueState(player)).build());
        } else if (messageId == matchIds.requestTicket()) {
            reissue(ch, request, player, RequestBattleTicketRequest.parseFrom(body).getBattleId());
        } else if (messageId == matchIds.challenge()) {
            challenge(ch, request, player, ChallengePlayerRequest.parseFrom(body));
        } else if (messageId == matchIds.respondChallenge()) {
            respond(ch, request, player, RespondChallengeRequest.parseFrom(body));
        } else if (messageId == matchIds.watchBattle()) {
            watchBattle(ch, request, player, WatchBattleRequest.parseFrom(body).getBattleId());
        } else if (messageId == matchIds.listWatchable()) {
            reply(ch, request, listWatchable(ListWatchableBattlesRequest.parseFrom(body).getLimit()));
        } else if (messageId == startTeamMatch) {
            startTeamMatch(ch, request, player, StartTeamMatchRequest.parseFrom(body));
        } else {
            // 不认识的号：同 gate 对未接入域的做法，回信封 1003
            ch.writeAndFlush(MessageContent.newBuilder().setMessageId(messageId).setId(request.getId())
                    .setErrorMessage(TipInfoMessage.newBuilder().setId(1003)).build());
        }
    }

    /**
     * 153 的可见行为：没票 NOT_QUEUED，否则是票的状态。ready 残留（票是 ready、它那一局已经打完）按「被 153 查到的次数」过期：
     * 还剩次数时回 READY 并减一，次数用完即过期、回 NOT_QUEUED（模拟真服务端 60 s 的 ready 窗口，不看墙钟）。
     */
    private QueueState queueState(Player player) {
        if (player == null || player.ticket == null) {
            return QueueState.QUEUE_STATE_NOT_QUEUED;
        }
        if (readyResidue(player.ticket)) {
            if (player.ticket.residuePollsLeft <= 0) {
                player.ticket = null;
                return QueueState.QUEUE_STATE_NOT_QUEUED;
            }
            player.ticket.residuePollsLeft--;
        }
        return player.ticket.state;
    }

    /** 这张票是不是 ready 残留：状态是 ready，而它开出来的那一局已经打完。 */
    private boolean readyResidue(Ticket ticket) {
        Battle of = battles.get(ticket.battleId);
        return ticket.state == QueueState.QUEUE_STATE_READY && of != null && of.finished;
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
            startBattle(mode, config, List.of(player.id));
            return;
        }
        player.ticket = new Ticket(mode, config, QueueState.QUEUE_STATE_QUEUED);
        List<Long> queue = queues.computeIfAbsent(mode * 1_000_000 + config, k -> new ArrayList<>());
        queue.add(player.id);
        reply(ch, request, JoinQueueResponse.newBuilder().setQueueTicket(player.ticket.id).build());
        if (faults.contains(Fault.QUEUE_EVICTS_OBSERVER)) {
            evictSpectators(List.of(player.id));
        }
        int required = mode == MODE_5V5 ? 10 : mode == MODE_1V1 ? 2 : 5;
        if (queue.size() >= required) {
            List<Long> group = new ArrayList<>(queue.subList(0, required));
            queue.subList(0, required).clear();
            startBattle(mode, config, group);
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
        } else if (!battle.finished && battle.observers.contains(player.id)) {
            // 观众也能补签：按观众角色签，与 163 之后推的那张 177 逐字节相同
            BattleAssignedS2C ticket = observerTicket(battle, player.id);
            response.setAssignment(faults.contains(Fault.OBSERVER_REISSUE_DIFFERS)
                    ? ticket.toBuilder().setExpireAtMs(ticket.getExpireAtMs() + 1).build() : ticket);
            reissuesOk++;
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
        startBattle(MODE_CHALLENGE, 0, List.of(challenger.id, player.id));
    }

    // ---------------------------------------------------------------- 观战（163 / 164）

    /**
     * 163 的可见行为（spectate-spec §3.1 的判定顺序）：没有身份 16004 → 持票（任意状态）16014 → 有战斗锁 16015 → 已有观战标记<b>不拒绝</b>
     * （显式重看同一场只删标记；换场 / 随机先把人从旧场清退）→ 选场（指定一轮、随机两轮，随机挑索引里最老的一场，所以用例是确定的）→
     * 落点不存在 16018「不存在或已结束」→ 房间已收尾：剔除，随机换下一轮、指定回 16018 → 参战者看自己的局 16018「当前无法观战」→
     * 成功只回 battle_id，并推 177（role = 2）→ 两轮都没成 16017。
     */
    private void watchBattle(Channel ch, ClientRequest request, Player player, long requested) {
        if (player == null) {
            watchRejected(ch, request, "internal", MatchErrorTip.match_error.kMatchInternal_VALUE, "缺少玩家身份");
            return;
        }
        if (player.ticket != null) {
            // 任意状态的票都挡：ready 残留也一样，163 不让它过期（BW1；它只按 153 被查到的次数过期，见 queueState）
            watchRejected(ch, request, "queued", SpectateSteps.TIP_QUEUED, SpectateSteps.TEXT_QUEUED);
            endChallengeAfterFirstWatch(player);
            return;
        }
        if (player.lock != 0) {
            watchRejected(ch, request, "in_battle", SpectateSteps.TIP_IN_BATTLE, SpectateSteps.TEXT_IN_BATTLE);
            endChallengeAfterFirstWatch(player);
            return;
        }
        if (player.watching != 0) {
            Battle old = battles.get(player.watching);
            if (requested == 0 || requested != player.watching) {
                // 换场 / 随机：先从旧场清退（166 REMOVED），不管新场成不成
                evictions.merge("rewatch|" + (old == null ? "no_record" : "removed"), 1, Integer::sum);
                if (old != null) {
                    removeObserver(old, player.id);
                }
            }
            player.watching = 0;
        }
        int rounds = requested == 0 ? 2 : 1;
        for (int round = 0; round < rounds; round++) {
            Battle target;
            if (requested == 0) {
                target = watchable.stream().map(battles::get).min((x, y) -> Long.compare(x.createdAtMs, y.createdAtMs)).orElse(null);
                if (target == null) {
                    break;
                }
            } else {
                target = battles.get(requested);
                if (target == null) {
                    watchRejected(ch, request, "not_found", SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_FOUND);
                    return;
                }
            }
            if (target.finished) {
                // battle 回「房间不存在」：从索引里剔除（落点记录留着，179 还要用）
                if (!faults.contains(Fault.ENDED_BATTLE_STAYS_LISTED)) {
                    watchable.remove(target.id);
                }
                if (requested != 0) {
                    watchRejected(ch, request, "not_found", SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_FOUND);
                    return;
                }
                continue;
            }
            if (target.members.contains(player.id)) {
                watchRejected(ch, request, "rejected", SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_WATCHABLE);
                return;
            }
            player.watching = target.id;
            addObserver(target, player);
            watchOutcomes.merge("ok", 1, Integer::sum);
            WatchBattleResponse.Builder accepted = WatchBattleResponse.newBuilder().setBattleId(target.id);
            if (faults.contains(Fault.WATCH_OK_WITH_EMPTY_TIP)) {
                accepted.setErrorMessage(TipInfoMessage.getDefaultInstance());
            }
            reply(ch, request, accepted.build());
            return;
        }
        watchRejected(ch, request, "no_battle", SpectateSteps.TIP_NO_BATTLE, SpectateSteps.TEXT_NO_BATTLE);
    }

    private void watchRejected(Channel ch, ClientRequest request, String outcome, int code, String text) {
        watchOutcomes.merge(outcome, 1, Integer::sum);
        reply(ch, request, WatchBattleResponse.newBuilder().setErrorMessage(tip(code, text)).build());
    }

    /**
     * {@link Fault#CHALLENGE_ENDS_AFTER_FIRST_WATCH}：这名玩家正在一局还没打完的切磋里，他这条 163 答完之后这一局就自己打完——
     * 各直连收 139 → 150，但<b>先不 FIN</b>（同真服务端：房间没了之后连接还留一小会儿），之后到的 162 回拒绝再 FIN（见 onDirect）。
     */
    private void endChallengeAfterFirstWatch(Player player) {
        Battle own = battles.get(player.lock);
        if (faults.contains(Fault.CHALLENGE_ENDS_AFTER_FIRST_WATCH) && own != null && !own.finished && own.mode == MODE_CHALLENGE) {
            own.lingering = true;
            finish(own, null, null);
        }
    }

    /**
     * battle 的 AddObserver：新观众经大厅收 177（此刻一定没有直连）；已在名单里的人（重看同一场）有活直连时 177 经<b>直连</b>直写，
     * 随后再推一条 161，没有活直连才走大厅。票是确定性的：同一个人同一场每次签出来逐字节相同。
     */
    private void addObserver(Battle battle, Player observer) {
        battle.observers.add(observer.id);
        BattleAssignedS2C ticket = observerTicket(battle, observer.id);
        Channel direct = battle.observerDirects.get(observer.id);
        if (direct != null && direct.isActive() && !faults.contains(Fault.REWATCH_TICKET_TO_LOBBY)) {
            push(direct, battleIds.battleAssigned(), faults.contains(Fault.REWATCH_TICKET_DIFFERS)
                    ? ticket.toBuilder().setExpireAtMs(ticket.getExpireAtMs() + 1).build() : ticket);
            push(direct, battleIds.spectateState(), spectateState(battle));
        } else {
            pushIfOnline(observer, battleIds.battleAssigned(), ticket);
        }
    }

    private BattleAssignedS2C observerTicket(Battle battle, long observerId) {
        return BattleAssignedS2C.newBuilder().setBattleId(battle.id).setHost("127.0.0.1").setPort(port(battleServer))
                .setTokenPayload(BattleTicketPayload.newBuilder().setBattleId(battle.id).setPlayerId(observerId).setBattleNodeId(7)
                        .setBattleInstanceId("fake-battle").setExpireAtMs(battle.deadlineMs)
                        .setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER).build().toByteString())
                .setTokenSignature(ByteString.copyFromUtf8(SIGNATURE)).setExpireAtMs(battle.deadlineMs)
                .setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER).build();
    }

    /** 161：观众版的状态（冷却清空、没有 self_items）+ 当前观众数。 */
    private SpectateStateS2C spectateState(Battle battle) {
        return SpectateStateS2C.newBuilder().setState(observerView(battle)).setObserverCount(battle.observers.size()).build();
    }

    private BattleStateS2C observerView(Battle battle) {
        BattleStateS2C.Builder state = stateOf(battle).toBuilder();
        if (faults.contains(Fault.OBSERVER_SEES_COOLDOWNS)) {
            for (int i = 0; i < state.getActorsCount(); i++) {
                state.setActors(i, state.getActors(i).toBuilder().putSkillCooldownRounds(1, 2));
            }
        }
        return state.build();
    }

    /** battle 的 RemoveObserver（被动清退）：直连上先推 166 {ONGOING, REMOVED} 再 FIN；不在名单里是空操作。 */
    private void removeObserver(Battle battle, long observerId) {
        if (!battle.observers.remove(observerId)) {
            return;
        }
        Channel direct = battle.observerDirects.remove(observerId);
        if (direct != null) {
            push(direct, battleIds.spectateEnd(), SpectateEndS2C.newBuilder().setBattleId(battle.id)
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_ONGOING).setReason(eSpectateEndReason.SPECTATE_END_REMOVED).build());
            fin(direct);
        }
    }

    /** 开局之前的清退（gather 的 beforePrepare）：成员里正在观战的人，先从那一场的观众里摘掉；不阻断开局。 */
    private void evictSpectators(List<Long> members) {
        for (long member : members) {
            Player player = byId.get(member);
            if (player == null || player.watching == 0) {
                continue;
            }
            Battle watched = battles.get(player.watching);
            player.watching = 0;
            evictions.merge("enter_gather|" + (watched == null ? "no_record" : "removed"), 1, Integer::sum);
            if (watched != null) {
                removeObserver(watched, member);
            }
        }
    }

    /**
     * 164 的可见行为（spectate-spec §3.3）：{@code limit = 0} → 20、超过 50 → 50；按 {@code created_at_ms} 降序；不看身份；空列表照常回包。
     * 已结束的战斗还在索引里时照样列出（BW7）。
     */
    private ListWatchableBattlesResponse listWatchable(int limit) {
        int cap = limit == 0 ? SpectateSteps.LIST_DEFAULT : Math.min(limit, SpectateSteps.LIST_MAX);
        ListWatchableBattlesResponse.Builder response = ListWatchableBattlesResponse.newBuilder();
        watchable.stream().map(battles::get)
                .sorted((x, y) -> x.createdAtMs != y.createdAtMs ? Long.compare(y.createdAtMs, x.createdAtMs) : Long.compareUnsigned(y.id, x.id))
                .limit(cap)
                .forEach(battle -> {
                    BattleWatchSummary.Builder summary = BattleWatchSummary.newBuilder().setBattleId(battle.id).setModeValue(battle.mode)
                            .setBattleConfigId(battle.config).setCreatedAtMs(battle.createdAtMs);
                    for (long member : battle.members) {
                        Player p = byId.get(member);
                        summary.addPlayerNames(faults.contains(Fault.SUMMARY_NAMES_ARE_ACCOUNTS) ? p.account : p.name);
                    }
                    response.addBattles(summary);
                });
        // 还没公开的局：每来一条 164 倒数一次，数到 0 才进索引（这一条 164 自己还看不到它）
        unpublished.replaceAll((battleId, remaining) -> remaining - 1);
        unpublished.entrySet().removeIf(entry -> {
            if (entry.getValue() > 0) {
                return false;
            }
            watchable.add(entry.getKey());
            return true;
        });
        return response.build();
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
        startBattle(MODE_PVE_TEAM, start.getBattleConfigId(), team.members);
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

    /**
     * 建一局：先清退成员里正在观战的人，再全员落战斗锁、票据置 ready，按玩家号升序逐人推 177 → 143，最后登记进可观战索引、记区组成。
     */
    private Battle startBattle(int mode, int config, List<Long> members) {
        if (!faults.contains(Fault.NO_EVICT_ON_GATHER)) {
            evictSpectators(members);
        }
        Battle battle = new Battle(nextBattleId++, mode, config, members);
        battles.put(battle.id, battle);
        battle.createdAtMs = System.currentTimeMillis();
        long deadline = battle.createdAtMs + BattleSmokeChecks.BATTLE_DURATION_MS;
        battle.deadlineMs = deadline;
        for (int i = 0; i < members.size(); i++) {
            int team = switch (mode) {
                case MODE_1V1 -> faults.contains(Fault.SWAP_DUEL_SIDES) ? 1 - i : i;
                case MODE_CHALLENGE -> i;
                case MODE_5V5 -> faults.contains(Fault.BLOCK_TEAMS_5V5) ? i / 5 : BattleSmokeChecks.SNAKE_5V5.get(i);
                default -> 0;
            };
            battle.teams.put(members.get(i), team);
        }
        BattleStateS2C state = stateOf(battle);
        for (long member : members.stream().sorted(Long::compareUnsigned).toList()) {
            Player player = byId.get(member);
            player.lock = battle.id;
            // 只动这一局自己的票（queued / matched）。上一局留下的 ready 票不归这一局管：切磋不建票，A 带着 1V1 的 ready 残留进切磋局时，
            // 那张票仍然指向已经打完的 1V1
            if (player.ticket != null && player.ticket.state != QueueState.QUEUE_STATE_READY) {
                player.ticket.state = QueueState.QUEUE_STATE_READY;
                player.ticket.battleId = battle.id;
                player.ticket.residuePollsLeft = readyResiduePolls;
            }
            String instance = faults.contains(Fault.DUEL_TICKETS_DIFFERENT_INSTANCE) && mode == MODE_1V1 && member != members.get(0)
                    ? "fake-battle-other" : "fake-battle";
            BattleAssignedS2C assigned = BattleAssignedS2C.newBuilder().setBattleId(battle.id).setHost("127.0.0.1").setPort(port(battleServer))
                    .setTokenPayload(BattleTicketPayload.newBuilder().setBattleId(battle.id).setPlayerId(member).setBattleNodeId(7)
                            .setBattleInstanceId(instance).setExpireAtMs(deadline)
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
        // 区组成按各成员所在的区去重（真服务端按备战快照的 routing.zone_id）
        boolean cross = members.stream().map(member -> byId.get(member).zone).distinct().count() > 1;
        zoneMix.merge(MatchMode.forNumber(mode).name() + "|" + (cross && !faults.contains(Fault.CROSS_ZONE_COUNTED_SINGLE) ? "cross" : "single"),
                1, Integer::sum);
        // 公开在开局公告之后（真服务端：票据置 ready、补写落点之后才登记进索引）
        if (publishAfterLists <= 0) {
            watchable.add(battle.id);
        } else {
            unpublished.put(battle.id, publishAfterLists);
        }
        return battle;
    }

    private synchronized void onDirect(Channel ch, Message message) throws InvalidProtocolBufferException {
        if (message instanceof BattleTokenVerifyRequest verify) {
            BattleTicketPayload payload = BattleTicketPayload.parseFrom(verify.getPayload());
            Battle battle = battles.get(payload.getBattleId());
            boolean observer = payload.getRole() == eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER;
            // 按票上的角色核对名单：参战票只认参战名单，观众票只认观众名单
            boolean valid = battle != null && !battle.finished && verify.getSignature().toStringUtf8().equals(SIGNATURE)
                    && (observer ? battle.observers.contains(payload.getPlayerId()) : battle.members.contains(payload.getPlayerId()));
            if (!valid) {
                ch.writeAndFlush(BattleTokenVerifyResponse.newBuilder().setError("battle not found or player not in this battle").build())
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            directSessions.put(ch, new DirectSession(battle, payload.getPlayerId(), observer));
            ch.writeAndFlush(BattleTokenVerifyResponse.newBuilder().setSuccess(true).setBattleId(battle.id).build());
            if (observer) {
                // 观战首帧只随直连下发：握手应答之后紧跟 161
                battle.observerDirects.put(payload.getPlayerId(), ch);
                push(ch, battleIds.spectateState(), spectateState(battle));
            } else {
                battle.directs.put(payload.getPlayerId(), ch);
            }
            return;
        }
        ClientRequest request = (ClientRequest) message;
        DirectSession session = directSessions.get(ch);
        if (session == null) {
            ch.close();
            return;
        }
        Battle battle = session.battle();
        if (session.observer()) {
            if (request.getMessageId() == battleIds.stopWatch()) {
                // 165 主动退出：不推 166，应答之后 FIN；match 那边的观战标记不清（基线如此）
                battle.observers.remove(session.playerId());
                battle.observerDirects.remove(session.playerId(), ch);
                if (faults.contains(Fault.STOP_WATCH_PUSHES_END)) {
                    push(ch, battleIds.spectateEnd(), SpectateEndS2C.newBuilder().setBattleId(battle.id)
                            .setOutcome(eBattleOutcome.BATTLE_OUTCOME_ONGOING).setReason(eSpectateEndReason.SPECTATE_END_REMOVED).build());
                }
                reply(ch, request, StopWatchBattleResponse.getDefaultInstance());
                fin(ch);
            } else {
                // 观众不能发别的战斗上行
                reply(ch, request, SetAutoBattleResponse.newBuilder().setErrorMessage(tip(BattleSmokeChecks.TIP_INVALID_PARAMETER, "观众")).build());
            }
            return;
        }
        if (request.getMessageId() == battleIds.getBattleState()) {
            if (faults.contains(Fault.BARRIER_BATTLE_ENDS_EARLY) && !barrierEnded && battle.mode == MODE_PVE_SOLO && !battle.finished) {
                // 这一局已经自己打完了：139 → 150 → 140 的应答（空状态）→ FIN，都在这条直连上、按这个次序
                barrierEnded = true;
                finish(battle, ch, request);
                return;
            }
            reply(ch, request, stateOf(battle));
        } else if (request.getMessageId() == battleIds.setAutoBattle()) {
            autoRequests++;
            if (battle.finished && battle.lingering) {
                // 这一局已经自己打完：房间没了之后才读到的 162 回拒绝（排在这条直连的 150 之后），然后 FIN
                reply(ch, request, SetAutoBattleResponse.newBuilder()
                        .setErrorMessage(tip(BattleSmokeChecks.TIP_INVALID_PARAMETER, "战斗不存在")).build());
                fin(ch);
                return;
            }
            if (faults.contains(Fault.FIRST_AUTO_REJECTED) && autoRequests == rejectAutoAt) {
                // 拒绝在前；这一局随后照样「打完」（当作回合超时的默认行动），没有人再收到 162 的应答
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

    /** 一局的状态：每名成员一个 actor，名字是角色名（同真服务端：取自备战快照的 player_name）。 */
    private BattleStateS2C stateOf(Battle battle) {
        BattleStateS2C.Builder state = BattleStateS2C.newBuilder().setBattleId(battle.id).setRoundIndex(1);
        battle.teams.forEach((member, team) -> state.addActors(BattleActorState.newBuilder().setActorId(member)
                .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER).setTeamIndex(team).setName(byId.get(member).name)));
        return state.build();
    }

    /** 排空输出后半关闭：客户端看到的是 FIN。 */
    private static void fin(Channel direct) {
        direct.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(f -> ((SocketChannel) direct).shutdownOutput());
    }

    /**
     * 全员挂机：每条直连 139 → 150，最后开挂机的那位随后收到 162 的应答，然后服务端 FIN（{@link Battle#lingering} 的局先不 FIN，等各自的
     * 162）；观众的直连 158 → 166 {FINISHED} → FIN；锁与评分各延后一小段落地。
     */
    private void finish(Battle battle, Channel last, ClientRequest lastRequest) {
        battle.finished = true;
        boolean pve = battle.mode == MODE_PVE_SOLO || battle.mode == MODE_PVE_TEAM;
        boolean lost = battle.mode == MODE_PVE_SOLO && ++soloPveFinished == soloPveLostAt;
        eBattleOutcome outcome = lost ? eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN : pve ? eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN : pvpOutcome;
        int rounds = pve ? PVE_ROUNDS : PVP_ROUNDS;
        TurnResultS2C spectateTurn = TurnResultS2C.newBuilder().setBattleId(battle.id).setRoundIndex(rounds).setState(observerView(battle)).build();
        for (long observerId : battle.observers) {
            Channel direct = battle.observerDirects.get(observerId);
            if (direct != null) {
                push(direct, battleIds.spectateTurnResult(), spectateTurn);
                push(direct, battleIds.spectateEnd(), SpectateEndS2C.newBuilder().setBattleId(battle.id).setOutcome(outcome)
                        .setReason(eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED).build());
                fin(direct);
            }
            if (faults.contains(Fault.SPECTATE_TURN_TO_LOBBY)) {
                pushIfOnline(byId.get(observerId), battleIds.spectateTurnResult(), spectateTurn);
            }
        }
        battle.observers.clear();
        battle.observerDirects.clear();
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
            if (!battle.lingering) {
                fin(direct);
            }
        });
        for (long member : battle.members) {
            long delay = SETTLE_DELAY_MS + (member == slowSettlePlayer ? slowSettleExtraMs : 0);
            timer.schedule(() -> settle(battle, member, outcome, rounds), delay, TimeUnit.MILLISECONDS);
        }
        if (battle.mode == MODE_1V1 || battle.mode == MODE_5V5) {
            timer.schedule(() -> applyRatings(battle, outcome, rounds), RATING_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 一名成员的结算落地：放他的战斗锁（ready 票留着，下次排队 / 开战预检时自愈），然后经大厅再推一份 150
     * （真服务端：scene 应用结算之后推，证明结算回到了这名玩家所在区的 scene）。
     */
    private synchronized void settle(Battle battle, long member, eBattleOutcome outcome, int rounds) {
        Player player = byId.get(member);
        if (player.lock == battle.id) {
            player.lock = 0;
        }
        if (!faults.contains(Fault.NO_LOBBY_END)) {
            pushIfOnline(player, battleIds.battleEnd(), BattleEndS2C.newBuilder().setBattleId(battle.id).setOutcome(outcome)
                    .setSettlement(BattleSettlementData.newBuilder().setBattleId(battle.id).setPlayerId(member).setOutcome(outcome)
                            .setPlayerTeamIndex(battle.teams.get(member)).setTotalRounds(rounds)).build());
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
                respond(exchange, 200, "application/json", assignGate(new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/api/server-list")) {
                respond(exchange, 200, "application/json", serverList().getBytes(StandardCharsets.UTF_8));
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

    /** assign-gate 按请求的 {@code zone_id} 给那个区的 gate（不发令牌，robot 跳过握手）；没开的区不给。 */
    private synchronized String assignGate(String requestBody) {
        Matcher zone = Pattern.compile("\"zone_id\"\\s*:\\s*(\\d+)").matcher(requestBody);
        int zoneId = zone.find() ? Integer.parseInt(zone.group(1)) : 1;
        if (!openZones.contains(zoneId)) {
            return "{\"code\":404,\"error\":\"zone_not_found\"}";
        }
        return "{\"code\":0,\"gate_ip\":\"127.0.0.1\",\"gate_port\":" + gatePort(zoneId) + "}";
    }

    /** 区服列表：一区、二区；没开的区显示维护（形状同 xm-gateway 的 {@code GET /api/server-list}）。 */
    private synchronized String serverList() {
        StringBuilder out = new StringBuilder("{\"zones\":[");
        for (int zoneId = 1; zoneId <= 2; zoneId++) {
            out.append(zoneId == 1 ? "" : ",").append("{\"zone_id\":").append(zoneId).append(",\"name\":\"").append(zoneId).append(" 区\",\"status\":\"")
                    .append(openZones.contains(zoneId) ? "OPEN" : "MAINTENANCE").append("\"}");
        }
        return out.append("]}").toString();
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
        return StartActivityBattleResponse.newBuilder().setBattleId(startBattle(MODE_PVE_TEAM, request.getBattleConfigId(), members).id).build();
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
        // 观战（批次 6.5）：163 各出口、清退各结局、各局的区组成
        watchOutcomes.forEach((outcome, count) -> text.append("xm_match_watch_battle_total{").append(app).append("outcome=\"").append(outcome)
                .append("\"} ").append((double) count).append('\n'));
        evictions.forEach((key, count) -> {
            String[] parts = key.split("\\|");
            text.append("xm_match_spectate_evictions_total{").append(app).append("reason=\"").append(parts[0]).append("\",result=\"")
                    .append(parts[1]).append("\"} ").append((double) count).append('\n');
        });
        zoneMix.forEach((key, count) -> {
            String[] parts = key.split("\\|");
            text.append("xm_match_gather_zone_mix_total{").append(app).append("mix=\"").append(parts[1]).append("\",mode=\"").append(parts[0])
                    .append("\"} ").append((double) count).append('\n');
        });
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
