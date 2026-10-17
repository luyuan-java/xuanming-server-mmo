package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.EnterSceneS2C;
import com.game.proto.friend.AcceptFriendRequest;
import com.game.proto.friend.AcceptFriendResponse;
import com.game.proto.friend.AddFriendRequest;
import com.game.proto.friend.AddFriendResponse;
import com.game.proto.friend.FriendRequest;
import com.game.proto.friend.FriendRequestStatus;
import com.game.proto.friend.GetFriendListRequest;
import com.game.proto.friend.GetFriendListResponse;
import com.game.proto.friend.GetPendingRequestsRequest;
import com.game.proto.friend.GetPendingRequestsResponse;
import com.game.proto.friend.RejectFriendRequest;
import com.game.proto.friend.RejectFriendResponse;
import com.game.proto.friend.RemoveFriendRequest;
import com.game.proto.friend.RemoveFriendResponse;
import com.game.proto.guild.ApplyJoinGuildRequest;
import com.game.proto.guild.ApplyJoinGuildResponse;
import com.game.proto.guild.CancelGuildApplicationRequest;
import com.game.proto.guild.CancelGuildApplicationResponse;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.DisbandGuildRequest;
import com.game.proto.guild.DisbandGuildResponse;
import com.game.proto.guild.GetPlayerGuildRequest;
import com.game.proto.guild.GetPlayerGuildResponse;
import com.game.proto.guild.GuildApplicationView;
import com.game.proto.guild.LeaveGuildRequest;
import com.game.proto.guild.LeaveGuildResponse;
import com.game.proto.guild.ListGuildApplicationsRequest;
import com.game.proto.guild.ListGuildApplicationsResponse;
import com.game.proto.guild.ListMyGuildApplicationsRequest;
import com.game.proto.guild.ListMyGuildApplicationsResponse;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.match.CancelQueueRequest;
import com.game.proto.match.GetQueueStatusRequest;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.QueueState;
import com.game.robot.client.GameConnection;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.OrderingChecks.Reorder;
import com.game.robot.scenario.OrderingChecks.Verdict;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 客户端请求按会话保序的冒烟（对应 mmorpg robot/ordering_smoke_scenario.go 与 ordering_smoke_groups.go；mmorpg
 * docs/design/client-request-ordering.md §12 批次 R）。每组每轮都是：<b>不等回包</b>连发有依赖的两条请求，等回包，再查权威状态，
 * 与「后发的那条最后执行」应得的状态比较，对不上即本轮乱序。步骤与 mmorpg 同号、同序（O4 留给 mmorpg 在途的「LeaveGame → JoinQueue」）：
 * <ol>
 *   <li>O5 帮会（帮主 {@code _gl}、申请人 {@code _ga}）：清场后帮主建临时帮；申请人连发 ApplyJoinGuild(218) → CancelGuildApplication(219)，
 *       帮主侧 ListGuildApplications(221，读 MySQL 申请行) 里没有申请人才算按序；撤回回 14018 且申请人仍在名单 = 颠倒；任一条回 14xxx 忙
 *       （并发撞锁）同样记乱序。乱序轮补一次撤回收尾；全部轮次后解散临时帮。</li>
 *   <li>O6 好友（接收方 {@code _fa}、申请方 {@code _fr}）：清场后每轮申请方先 AddFriend(234) 拿 request_id，接收方连发 AcceptFriend(238) →
 *       RemoveFriend(11)，双方 GetFriendList(12) 都看不到对方才算按序；互为好友 = 颠倒，补一次删除收尾；只一方看得到 = 单边残留，立即失败。</li>
 *   <li>O7 登录（{@code _lg}）：先完整登录一次拿到 player_id 并断开；此后每轮像真实客户端重连一样重走 assign-gate、新开连接，
 *       不等 Login(48) 回包连发 48 → EnterGame(26，带已知的 player_id)。26 回 2007 kLoginSessionNotFound = 26 先于 48 执行；
 *       26 受理后还要等到 79 才算这一轮通过。</li>
 *   <li>O1 主机器人 {@code _q} 登录进场；O2 清场：GetQueueStatus(153) 必须是 NOT_QUEUED（残留不自动取消，同 mmorpg）；
 *       O3 {@value #QUEUE_ROUNDS} 轮连发 JoinQueue(157，1V1) → CancelQueue(148，不带票号)，再查 153：仍 QUEUED = 乱序，带 157 回包里的票号补一次 148 收尾。</li>
 * </ol>
 * 判据同 mmorpg：各组与 O3 的乱序不当场退出，全部跑完后汇总成一行 {@code ORDERING_SMOKE_FAIL step=O5-order,O3-order reason=…}；
 * 登录、清场、业务拒绝、信封拒绝、超时、状态对不上任何一种执行顺序，立即以 {@code ORDERING_SMOKE_FAIL step=<那一步>} 结束（之前登记的乱序已进报告）。
 * 全过 → {@code ORDERING_SMOKE_OK player_id=… rounds=…}。判定本身在 {@link OrderingChecks}（单测钉住）。
 *
 * <p>与 mmorpg 的差异（Java 服务端现码与 Java 惯用决定，判据不变）：
 * <ul>
 *   <li>148 的应答是 Empty：Java gate 受理即不回包（{@code MessageRoutes.hasResponse}），只有被拒时回带请求 id 的信封。所以 O3 不等 148 的回包；
 *       153 在同一会话里排在 148 之后发出，按序执行时读到的就是 148 之后的状态，QUEUED 说明 157 / 148 / 153 里有一条没按发送顺序执行，同样记乱序。
 *       148 若回了信封（lane 满 1008、排队超时 1003 ……）立即失败。</li>
 *   <li>O7 的 26 回 2005（同一角色进场在途：上一条连接的进场链还没让出）时按 PlayerFlow 的约定隔 1 s 重发 26，至多 {@value #ENTER_MAX_ATTEMPTS} 次：
 *       2005 只在会话已有登录态之后才会回（{@link OrderingChecks#login}），说明 26 已在 48 之后执行，既不是乱序，也不必按前置失败退出。</li>
 *   <li>账号是 run-tag 新号 {@code 前缀 + od + 标签 + _q / _gl / _ga / _fa / _fr / _lg}（mmorpg 是固定的 robot_9901–9906）；清场照做，
 *       固定 {@code --run-tag} 也能重复跑，中途失败留下的临时帮 / 申请 / 好友关系由下一次运行的清场收掉。</li>
 *   <li>限频：Java gate 按会话、按消息号的真滑动 1 s 窗口（{@code MessageRateLimiter}，帮会 / 好友写 5 条、读 10 条，表外号缺省 3 条）。
 *       每个机器人发送前过「同号任意 {@code 1.1 s} 内至多 3 条」的节拍、不设相邻间隔（流水线的两条必须背靠背发出），每轮之前另隔
 *       {@link #ROUND_SPACING}，所以任何号都碰不到上限。</li>
 * </ul>
 *
 * <p>前置：环境里没有别的玩家在排 1V1（{@code battle_config_id = }{@link BattleSmokeScenario#PVP_CONFIG}，与 battle-smoke 不要同时跑）——
 * 否则主机器人可能在 157 与 148 之间被凑走，O3 按前置不满足失败。帮会 / 好友申请配额都是每人每分钟 10 次（固定 60 s 窗口）：O5 / O6
 * 每次运行各用 5 次，同一 {@code --run-tag} 一分钟内最多连跑两次。157 / 148 不带 {@code request_id}（同 battle-smoke）：带了的话 148 会
 * 永久阻止同号的 157 迟到入队，把乱序盖住。
 */
public final class OrderingScenario {

    private static final String REF = "PARITY「客户端请求按会话保序」行；mmorpg client-request-ordering.md §12 批次 R";
    private static final String GUILD_SERVICE = "GuildService";
    private static final String FRIEND_SERVICE = "ClientPlayerFriend";

    /** O3 轮数（mmorpg orderingSmokeRounds）：乱序是概率事件，轮数多才暴露得出来。 */
    static final int QUEUE_ROUNDS = 20;
    /** O5 / O6 轮数：受每分钟 10 次的申请配额约束，5 轮让同一分钟内连跑两次仍不超额。 */
    static final int GUILD_ROUNDS = 5;
    static final int FRIEND_ROUNDS = 5;
    /** O7 轮数：每轮新开连接、完整进场再断开，单轮数秒。 */
    static final int LOGIN_ROUNDS = 3;
    /** 每轮之前的间隔（mmorpg orderingSmokeRoundSpacing）：大于 gate 的 1 s 窗口，上一轮与清场里的同号请求先滑出窗口。 */
    static final Duration ROUND_SPACING = Duration.ofMillis(1100);
    /**
     * O7 每条连接断开之后、下一条连接登录之前的间隔（mmorpg orderingLoginRoundGap）：断线通知异步落到 game-login，
     * 上一条连接的进场链让出归属也晚于 79；不隔开，下一轮 26 多半撞 2005。
     */
    static final Duration LOGIN_ROUND_GAP = Duration.ofSeconds(2);
    /** 同号节拍：任意这么长的窗口里至多 {@link #SAME_ID_MAX_IN_WINDOW} 条（gate 真滑动 1 s 窗口，最严的表外号 3 条）。 */
    static final Duration SAME_ID_WINDOW = Duration.ofMillis(1100);
    static final int SAME_ID_MAX_IN_WINDOW = 3;
    /** O7 的 26 回 2005 时重发的间隔与总次数（同 PlayerFlow）。 */
    static final Duration ENTER_RETRY_DELAY = Duration.ofSeconds(1);
    static final int ENTER_MAX_ATTEMPTS = 4;
    /** O3 乱序轮收尾：带票号的 148 不回包，靠 153 确认落定；最多查这么多次、每次隔 {@link MatchSupport#REQUEST_SPACING}。 */
    static final int CLEANUP_STATUS_ATTEMPTS = 3;

    private final RobotClient client;
    private final PlayerFlow flow;
    private final MessageIds ids;
    private final MatchSupport.Ids match;
    private final String password;
    private final String accountQueue;
    private final String accountGuildLeader;
    private final String accountGuildApplicant;
    private final String accountFriendAccepter;
    private final String accountFriendRequester;
    private final String accountLogin;
    private final Duration requestTimeout;
    private final Duration enterSceneTimeout;
    private final int createGuild;
    private final int getPlayerGuild;
    private final int leaveGuild;
    private final int disbandGuild;
    private final int applyGuild;
    private final int cancelApplication;
    private final int listMyApplications;
    private final int listApplicants;
    private final int addFriend;
    private final int acceptFriend;
    private final int rejectFriend;
    private final int removeFriend;
    private final int friendList;
    private final int pendingRequests;
    private final CheckReport report = new CheckReport();
    private String resultLine;

    /**
     * @param password          开发口令：O7 自己拼 LoginRequest（不经 PlayerFlow，才能不等 48 的回包就发 26）；只放进请求，不打印
     * @param requestTimeout    每条请求等回包的上限
     * @param enterSceneTimeout O7 里 26 受理后等 79 的上限
     */
    public OrderingScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, String password, String accountPrefix,
                            String runTag, Duration requestTimeout, Duration enterSceneTimeout) {
        this.client = client;
        this.flow = flow;
        this.ids = client.ids();
        this.match = MatchSupport.Ids.resolve(registry);
        this.password = password;
        this.accountQueue = accountName(accountPrefix, runTag, "q");
        this.accountGuildLeader = accountName(accountPrefix, runTag, "gl");
        this.accountGuildApplicant = accountName(accountPrefix, runTag, "ga");
        this.accountFriendAccepter = accountName(accountPrefix, runTag, "fa");
        this.accountFriendRequester = accountName(accountPrefix, runTag, "fr");
        this.accountLogin = accountName(accountPrefix, runTag, "lg");
        this.requestTimeout = requestTimeout;
        this.enterSceneTimeout = enterSceneTimeout;
        this.createGuild = registry.requireId(GUILD_SERVICE, "CreateGuild");
        this.getPlayerGuild = registry.requireId(GUILD_SERVICE, "GetPlayerGuild");
        this.leaveGuild = registry.requireId(GUILD_SERVICE, "LeaveGuild");
        this.disbandGuild = registry.requireId(GUILD_SERVICE, "DisbandGuild");
        this.applyGuild = registry.requireId(GUILD_SERVICE, "ApplyJoinGuild");
        this.cancelApplication = registry.requireId(GUILD_SERVICE, "CancelGuildApplication");
        this.listMyApplications = registry.requireId(GUILD_SERVICE, "ListMyGuildApplications");
        this.listApplicants = registry.requireId(GUILD_SERVICE, "ListGuildApplications");
        this.addFriend = registry.requireId(FRIEND_SERVICE, "AddFriend");
        this.acceptFriend = registry.requireId(FRIEND_SERVICE, "AcceptFriend");
        this.rejectFriend = registry.requireId(FRIEND_SERVICE, "RejectFriend");
        this.removeFriend = registry.requireId(FRIEND_SERVICE, "RemoveFriend");
        this.friendList = registry.requireId(FRIEND_SERVICE, "GetFriendList");
        this.pendingRequests = registry.requireId(FRIEND_SERVICE, "GetPendingRequests");
    }

    /** 账号：{@code 前缀 + od + 标签 + _后缀}（od 不与其它子命令的标签撞，后缀至多两个字符）。 */
    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "od" + runTag + "_" + suffix;
    }

    public String accountQueue() {
        return accountQueue;
    }

    /** 给外层脚本按子串消费的一行结论；{@link #run} 之前为 null。 */
    public String resultLine() {
        return resultLine;
    }

    public CheckReport run() {
        List<Reorder> reorders = new ArrayList<>();
        Bot main = null;
        try {
            // ---- O5–O7：独立账号的三组，不碰主机器人，先于 O1 跑完；乱序只登记，其余失败立即结束 ----
            List<Group> groups = List.of(this::guildGroup, this::friendGroup, this::loginGroup);
            for (Group group : groups) {
                Optional<Reorder> reorder = group.run();
                reorder.ifPresent(r -> {
                    report.fail(r.step() + " 每轮按发送顺序执行", r.reason(), REF);
                    reorders.add(r);
                });
            }

            // ---- O1：主机器人登录进场 ----
            main = enterBot("O1-login", "主机器人", accountQueue);
            pass("O1-login", "主机器人登录进场", "player_id=" + uid(main.playerId) + " gate=" + main.connection.endpoint());

            // ---- O2 / O3：流水线连发 157 → 148 ----
            queueRounds(main).ifPresent(r -> {
                report.fail(r.step() + " 每轮按发送顺序执行", r.reason(), REF);
                reorders.add(r);
            });

            resultLine = reorders.isEmpty() ? OrderingChecks.okLine(main.playerId, QUEUE_ROUNDS) : OrderingChecks.reorderLine(reorders);
        } catch (StepFailed e) {
            report.fail(e.step, e.reason, REF);
            resultLine = OrderingChecks.failLine(e.step, e.reason);
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            report.fail("流程中断", reason, REF);
            resultLine = OrderingChecks.failLine("internal", reason);
        } finally {
            if (main != null) {
                main.connection.close();
            }
        }
        return report;
    }

    /** O5 / O6 / O7 之一：返回登记下的乱序（全部轮次已跑完、已收尾）；非乱序类失败抛 {@link StepFailed}。返回前已关掉本组的连接。 */
    @FunctionalInterface
    private interface Group {
        Optional<Reorder> run() throws StepFailed;
    }

    // ================================================================ O2 / O3：JoinQueue → CancelQueue

    private Optional<Reorder> queueRounds(Bot bot) throws StepFailed {
        String step = "O2-precheck";
        try {
            QueueState state = queueState(bot);
            if (OrderingChecks.queue(state) != Verdict.IN_ORDER) {
                throw fail(step, "进场后队列状态是 " + state + "，需要先清掉上一次运行的残留（QUEUED 时等票据 TTL 或带票号取消；"
                        + "MATCHED / READY 时等那一局收尾），或换 --run-tag");
            }
            pass(step, "主机器人不在任何队列里", "queue_state=" + state);

            List<Integer> reordered = new ArrayList<>();
            for (int round = 1; round <= QUEUE_ROUNDS; round++) {
                if (round > 1) {
                    sleep(ROUND_SPACING);
                }
                step = "O3-pipeline";
                int mark = bot.mark();
                Sent[] sent = bot.pipeline(match.joinQueue(),
                        MatchSupport.joinRequest(bot.playerId, MatchMode.MATCH_MODE_1V1, BattleSmokeScenario.PVP_CONFIG),
                        match.cancelQueue(), CancelQueueRequest.newBuilder().setPlayerId(bot.playerId).build());
                JoinQueueResponse joined = bot.reply(mark, sent[0], JoinQueueResponse.parser());
                if (!BattleSmokeChecks.accepted(joined)) {
                    throw fail(step, rounds(round, QUEUE_ROUNDS) + " JoinQueue 业务拒绝 " + BattleSmokeChecks.describe(joined)
                            + (joined.getErrorCode() == BattleSmokeChecks.TIP_ALREADY_QUEUED ? "（已在队列中：上一轮残留未清，见 O2）" : ""));
                }
                step = "O3-status";
                state = queueState(bot);
                requireNoEnvelope(bot, mark, sent[1], "CancelQueue");
                switch (OrderingChecks.queue(state)) {
                    case IN_ORDER -> {
                        // 符合发送顺序：加入后取消
                    }
                    case REORDERED -> {
                        // 148 先于 157（或 153 先于 148）执行：带 157 回包里的票号收尾，下一轮才是干净的起点
                        reordered.add(round);
                        step = "O3-cleanup";
                        cancelWithTicket(bot, joined.getQueueTicket());
                    }
                    case INCONSISTENT -> throw fail(step, rounds(round, QUEUE_ROUNDS) + " 队列状态 " + state
                            + "：环境里有别的玩家在排 1V1，本机器人在 157 与 148 之间被凑走（前置条件不满足，见类注释）");
                }
            }
            if (!reordered.isEmpty()) {
                return Optional.of(new Reorder("O3-order", OrderingChecks.reorderReason(reordered.size(), QUEUE_ROUNDS, reordered,
                        "执行顺序被颠倒（先发的 JoinQueue 晚于后发的 CancelQueue 执行，或 153 抢在 148 之前）")));
            }
            pass("O3-order", "JoinQueue → CancelQueue 每轮按发送顺序执行", "rounds=" + QUEUE_ROUNDS);
            return Optional.empty();
        } catch (RobotException e) {
            throw fail(step, e.getMessage());
        }
    }

    /** 153：查一次权威队列状态。 */
    private QueueState queueState(Bot bot) throws RobotException {
        GetQueueStatusResponse status = bot.call(match.queueStatus(), GetQueueStatusRequest.newBuilder().setPlayerId(bot.playerId).build(),
                GetQueueStatusResponse.parser());
        return status.getState();
    }

    /** 乱序轮的收尾：带票号发 148（受理不回包），再查 153 直到不在队列里。148 回了信封即失败。 */
    private void cancelWithTicket(Bot bot, String ticket) throws RobotException {
        int mark = bot.mark();
        Sent sent = bot.send(match.cancelQueue(), CancelQueueRequest.newBuilder().setPlayerId(bot.playerId).setQueueTicket(ticket).build());
        for (int attempt = 1; attempt <= CLEANUP_STATUS_ATTEMPTS; attempt++) {
            sleep(MatchSupport.REQUEST_SPACING);
            QueueState state = queueState(bot);
            requireNoEnvelope(bot, mark, sent, "收尾的 CancelQueue");
            if (state != QueueState.QUEUE_STATE_QUEUED) {
                return;
            }
        }
        throw new RobotException("带票号 " + ticket + " 取消之后查了 " + CLEANUP_STATUS_ATTEMPTS + " 次 153 仍是 QUEUED");
    }

    /** 应答 Empty 的请求：受理不回包；此刻已收到它的回包 = 被 gate / 后端在信封上拒绝了。 */
    private static void requireNoEnvelope(Bot bot, int mark, Sent sent, String what) throws RobotException {
        Optional<Received> reply = bot.find(mark, sent);
        if (reply.isPresent()) {
            throw new RobotException(what + "（message_id=" + sent.messageId() + "，应答 Empty、受理不回包）收到了回包：信封 tip="
                    + reply.get().envelopeTipId() + "（gate 限频 1008、lane 满、排队超时或后端不可用 1003，请求没到业务逻辑）");
        }
    }

    // ================================================================ O5：ApplyJoinGuild → CancelGuildApplication

    private Optional<Reorder> guildGroup() throws StepFailed {
        List<Bot> opened = new ArrayList<>();
        String step = "O5-login";
        try {
            Bot leader = enterBot(step, "帮主", accountGuildLeader);
            opened.add(leader);
            Bot applicant = enterBot(step, "申请人", accountGuildApplicant);
            opened.add(applicant);
            pass(step, "帮会组两人登录进场", "leader=" + uid(leader.playerId) + " applicant=" + uid(applicant.playerId));

            // 清场：两人都回到「不在任何帮会、没有待审申请」，再由帮主建本次运行专用的临时帮（帮名全服唯一，每次换新名）
            step = "O5-precheck";
            for (Bot bot : opened) {
                guildCancelAll(bot);
                guildLeaveAny(bot);
            }
            CreateGuildResponse created = expectTip(leader, createGuild,
                    CreateGuildRequest.newBuilder().setName("保序" + GuildScenario.nonce()).build(), CreateGuildResponse.parser(), 0);
            long guildId = created.getGuild().getGuildId();
            if (guildId == 0) {
                throw fail(step, "建帮受理了，回包里却没有帮会");
            }
            pass(step, "两人都不在别的帮、没有遗留申请，帮主建了临时帮", "guild_id=" + uid(guildId));

            List<Integer> reordered = new ArrayList<>();
            List<Integer> busyRounds = new ArrayList<>();
            for (int round = 1; round <= GUILD_ROUNDS; round++) {
                // 第 1 轮前也隔开：清场可能刚撤过申请（219），与本轮及乱序收尾同号
                sleep(ROUND_SPACING);
                step = "O5-pipeline";
                int mark = applicant.mark();
                Sent[] sent = applicant.pipeline(applyGuild, ApplyJoinGuildRequest.newBuilder().setGuildId(guildId).build(),
                        cancelApplication, CancelGuildApplicationRequest.newBuilder().setGuildId(guildId).build());
                int applyTip = GuildScenario.tipOf(applicant.reply(mark, sent[0], ApplyJoinGuildResponse.parser()));
                int cancelTip = GuildScenario.tipOf(applicant.reply(mark, sent[1], CancelGuildApplicationResponse.parser()));
                String problem = OrderingChecks.guildTipsProblem(applyTip, cancelTip);
                if (problem != null) {
                    throw fail(step, rounds(round, GUILD_ROUNDS) + " " + problem);
                }
                if (OrderingChecks.guildConcurrent(applyTip, cancelTip)) {
                    // 哪条落败决定申请在不在：收尾一律撤回再复核
                    reordered.add(round);
                    busyRounds.add(round);
                    step = "O5-cleanup";
                    guildCancelAndVerify(leader, applicant, guildId);
                    continue;
                }
                step = "O5-status";
                boolean pending = guildHasApplicant(leader, applicant.playerId);
                switch (OrderingChecks.guild(cancelTip, pending)) {
                    case IN_ORDER -> {
                        // 符合发送顺序：申请后撤回
                    }
                    case REORDERED -> {
                        // 撤回先于申请执行：撤回时还没有申请行，申请随后落库。撤掉它，下一轮才是干净的起点
                        reordered.add(round);
                        step = "O5-cleanup";
                        guildCancelAndVerify(leader, applicant, guildId);
                    }
                    case INCONSISTENT -> throw fail(step, rounds(round, GUILD_ROUNDS) + " CancelGuildApplication tip=" + cancelTip
                            + "、帮主侧待审名单里有申请人=" + pending + "：与任何一种执行顺序都对不上"
                            + "（撤回受理就不该还在名单里，撤回回「申请不存在」就该在），查 game-guild 日志");
                }
            }
            // 收尾：解散临时帮（连带删掉它的待审申请）。放在汇总乱序之前，乱序时也留下干净的起点
            step = "O5-cleanup";
            expectTip(leader, disbandGuild, DisbandGuildRequest.getDefaultInstance(), DisbandGuildResponse.parser(), 0);
            if (!reordered.isEmpty()) {
                return Optional.of(new Reorder("O5-order", OrderingChecks.reorderReason(reordered.size(), GUILD_ROUNDS, reordered,
                        "没有按发送顺序串行执行（其中两条并发撞锁、回 " + OrderingChecks.TIP_GUILD_BUSY_RETRY + " 的轮次=" + busyRounds
                                + "，其余是先发的 ApplyJoinGuild 晚于后发的 CancelGuildApplication 执行）")));
            }
            pass("O5-order", "ApplyJoinGuild → CancelGuildApplication 每轮按发送顺序执行", "rounds=" + GUILD_ROUNDS);
            return Optional.empty();
        } catch (RobotException e) {
            throw fail(step, e.getMessage());
        } finally {
            opened.forEach(bot -> bot.connection.close());
        }
    }

    /** 乱序轮的收尾：申请人撤回本帮申请，再按帮主侧待审名单复核已撤掉（「申请不存在」也接受：撞锁那轮若是申请落败，申请根本没落库）。 */
    private void guildCancelAndVerify(Bot leader, Bot applicant, long guildId) throws RobotException {
        int tip = GuildScenario.tipOf(applicant.call(cancelApplication, CancelGuildApplicationRequest.newBuilder().setGuildId(guildId).build(),
                CancelGuildApplicationResponse.parser()));
        if (tip != 0 && tip != OrderingChecks.TIP_APPLICATION_NOT_FOUND) {
            throw new RobotException("收尾的 CancelGuildApplication 回 tip=" + tip + OrderingChecks.hint(tip));
        }
        if (guildHasApplicant(leader, applicant.playerId)) {
            throw new RobotException("撤回（tip=" + tip + "）之后帮主侧待审名单里仍有申请人");
        }
    }

    /** 帮主侧 ListGuildApplications：申请人还在不在本帮待审名单里（game-guild 读 MySQL 申请行、只数未过期的，不经缓存）。 */
    private boolean guildHasApplicant(Bot leader, long applicant) throws RobotException {
        ListGuildApplicationsResponse list = expectTip(leader, listApplicants, ListGuildApplicationsRequest.getDefaultInstance(),
                ListGuildApplicationsResponse.parser(), 0);
        return GuildScenario.hasApplicant(list.getApplicantsList(), applicant);
    }

    /** 撤回本人全部待审申请，再列一次复核已清空。已入帮的人列不出自己的申请，但「在册」与「有待审申请」本就互斥，先撤后退不影响清理。 */
    private void guildCancelAll(Bot bot) throws RobotException {
        List<GuildApplicationView> applications = expectTip(bot, listMyApplications, ListMyGuildApplicationsRequest.getDefaultInstance(),
                ListMyGuildApplicationsResponse.parser(), 0).getApplicationsList();
        if (applications.isEmpty()) {
            return;
        }
        for (GuildApplicationView application : applications) {
            int tip = GuildScenario.tipOf(bot.call(cancelApplication, CancelGuildApplicationRequest.newBuilder()
                    .setGuildId(application.getGuildId()).build(), CancelGuildApplicationResponse.parser()));
            // 「申请不存在」：列出之后已过期或被处理，目的同样达到了
            if (tip != 0 && tip != OrderingChecks.TIP_APPLICATION_NOT_FOUND) {
                throw new RobotException(bot.name + " 撤回帮会 " + uid(application.getGuildId()) + " 的遗留申请回 tip=" + tip);
            }
        }
        int left = expectTip(bot, listMyApplications, ListMyGuildApplicationsRequest.getDefaultInstance(),
                ListMyGuildApplicationsResponse.parser(), 0).getApplicationsCount();
        if (left != 0) {
            throw new RobotException(bot.name + " 撤回之后仍列得出 " + left + " 条待审申请");
        }
    }

    /** 把机器人带回「不在任何帮会」：帮主解散、成员退帮，直到 GetPlayerGuild 回 14002。 */
    private void guildLeaveAny(Bot bot) throws RobotException {
        for (int attempt = 0; attempt < 3; attempt++) {
            GetPlayerGuildResponse mine = bot.call(getPlayerGuild, GetPlayerGuildRequest.getDefaultInstance(), GetPlayerGuildResponse.parser());
            int tip = GuildScenario.tipOf(mine);
            if (tip == OrderingChecks.TIP_NOT_IN_GUILD) {
                return;
            }
            if (tip != 0) {
                throw new RobotException(bot.name + " GetPlayerGuild tip=" + tip + OrderingChecks.hint(tip));
            }
            if (mine.getGuild().getLeaderId() == bot.playerId) {
                expectTip(bot, disbandGuild, DisbandGuildRequest.getDefaultInstance(), DisbandGuildResponse.parser(), 0);
            } else {
                expectTip(bot, leaveGuild, LeaveGuildRequest.getDefaultInstance(), LeaveGuildResponse.parser(), 0);
            }
        }
        throw new RobotException(bot.name + " 连续 3 次退帮 / 解散后仍在帮会里");
    }

    // ================================================================ O6：AcceptFriend → RemoveFriend

    private Optional<Reorder> friendGroup() throws StepFailed {
        List<Bot> opened = new ArrayList<>();
        String step = "O6-login";
        try {
            Bot accepter = enterBot(step, "接收方", accountFriendAccepter);
            opened.add(accepter);
            Bot requester = enterBot(step, "申请方", accountFriendRequester);
            opened.add(requester);
            pass(step, "好友组两人登录进场", "accepter=" + uid(accepter.playerId) + " requester=" + uid(requester.playerId));

            step = "O6-precheck";
            friendReset(accepter, requester);
            pass(step, "两人互不是好友，接收方没有来自申请方的待处理申请", "");

            List<Integer> reordered = new ArrayList<>();
            for (int round = 1; round <= FRIEND_ROUNDS; round++) {
                // 第 1 轮前也隔开：清场必发一次 RemoveFriend（11），与本轮及乱序收尾同号
                sleep(ROUND_SPACING);
                step = "O6-request";
                AddFriendResponse added = expectTip(requester, addFriend,
                        AddFriendRequest.newBuilder().setTargetPlayerId(accepter.playerId).build(), AddFriendResponse.parser(), 0);
                if (added.getRequestId() == 0) {
                    throw fail(step, rounds(round, FRIEND_ROUNDS) + " 好友申请受理了却没有返回 request_id");
                }
                step = "O6-pipeline";
                int mark = accepter.mark();
                Sent[] sent = accepter.pipeline(acceptFriend, AcceptFriendRequest.newBuilder().setRequestId(added.getRequestId())
                                .setFromPlayerId(requester.playerId).build(),
                        removeFriend, RemoveFriendRequest.newBuilder().setTargetPlayerId(requester.playerId).build());
                int acceptTip = GuildScenario.tipOf(accepter.reply(mark, sent[0], AcceptFriendResponse.parser()));
                int removeTip = GuildScenario.tipOf(accepter.reply(mark, sent[1], RemoveFriendResponse.parser()));
                if (acceptTip != 0 || removeTip != 0) {
                    throw fail(step, rounds(round, FRIEND_ROUNDS) + " 业务拒绝：AcceptFriend tip=" + acceptTip + OrderingChecks.hint(acceptTip)
                            + "，RemoveFriend tip=" + removeTip + OrderingChecks.hint(removeTip) + "（两种执行顺序下两条都该受理）");
                }
                step = "O6-status";
                boolean accepterSees = friendSees(accepter, requester.playerId);
                boolean requesterSees = friendSees(requester, accepter.playerId);
                switch (OrderingChecks.friend(accepterSees, requesterSees)) {
                    case IN_ORDER -> {
                        // 符合发送顺序：同意后删除
                    }
                    case REORDERED -> {
                        // 删除先于同意执行：删时还不是好友（幂等成功），同意随后建出双向好友边。删掉它，下一轮才是干净的起点
                        reordered.add(round);
                        step = "O6-cleanup";
                        friendRemoveAndVerify(accepter, requester);
                    }
                    case INCONSISTENT -> throw fail(step, rounds(round, FRIEND_ROUNDS) + " 好友边单边残留：接收方看得到申请方=" + accepterSees
                            + "，申请方看得到接收方=" + requesterSees + "。双向两行本该在同一事务里同增同减，与执行顺序无关，查 game-friend 日志");
                }
            }
            if (!reordered.isEmpty()) {
                return Optional.of(new Reorder("O6-order", OrderingChecks.reorderReason(reordered.size(), FRIEND_ROUNDS, reordered,
                        "执行顺序被颠倒（先发的 AcceptFriend 晚于后发的 RemoveFriend 执行）")));
            }
            pass("O6-order", "AcceptFriend → RemoveFriend 每轮按发送顺序执行", "rounds=" + FRIEND_ROUNDS);
            return Optional.empty();
        } catch (RobotException e) {
            throw fail(step, e.getMessage());
        } finally {
            opened.forEach(bot -> bot.connection.close());
        }
    }

    /**
     * 把好友组两人恢复成「互不是好友、接收方没有来自申请方的待处理申请」，再按双方好友列表复核。拉黑不在这里清：本场景从不拉黑，
     * 出现拉黑只能是账号被别的流程用过，AddFriend 会回 15007 并在失败原因里写明。
     */
    private void friendReset(Bot accepter, Bot requester) throws RobotException {
        GetPendingRequestsResponse pending = expectTip(accepter, pendingRequests, GetPendingRequestsRequest.getDefaultInstance(),
                GetPendingRequestsResponse.parser(), 0);
        for (FriendRequest request : pending.getRequestsList()) {
            if (request.getFromPlayerId() != requester.playerId || request.getRequestId() == 0
                    || request.getStatus() != FriendRequestStatus.FRIEND_REQUEST_PENDING) {
                continue;
            }
            int tip = GuildScenario.tipOf(accepter.call(rejectFriend, RejectFriendRequest.newBuilder().setRequestId(request.getRequestId()).build(),
                    RejectFriendResponse.parser()));
            // 「没有待处理申请」：列出之后已被处理或过期，目的同样达到了
            if (tip != 0 && tip != OrderingChecks.TIP_FRIEND_NO_PENDING) {
                throw new RobotException("拒绝遗留申请 request_id=" + uid(request.getRequestId()) + " 回 tip=" + tip);
            }
        }
        friendRemoveAndVerify(accepter, requester);
    }

    /** 接收方删掉与申请方的好友关系（不是好友时是幂等成功），再按双方好友列表复核。 */
    private void friendRemoveAndVerify(Bot accepter, Bot requester) throws RobotException {
        expectTip(accepter, removeFriend, RemoveFriendRequest.newBuilder().setTargetPlayerId(requester.playerId).build(),
                RemoveFriendResponse.parser(), 0);
        boolean accepterSees = friendSees(accepter, requester.playerId);
        boolean requesterSees = friendSees(requester, accepter.playerId);
        if (accepterSees || requesterSees) {
            throw new RobotException("删除受理之后仍是好友（接收方看得到申请方=" + accepterSees + "，申请方看得到接收方=" + requesterSees + "）");
        }
    }

    /** GetFriendList（game-friend 写后立刻读到新值）：好友列表里有没有 {@code target}。 */
    private boolean friendSees(Bot bot, long target) throws RobotException {
        GetFriendListResponse list = expectTip(bot, friendList, GetFriendListRequest.getDefaultInstance(), GetFriendListResponse.parser(), 0);
        return list.getFriendsList().stream().anyMatch(entry -> entry.getFriendPlayerId() == target);
    }

    // ================================================================ O7：Login → EnterGame

    private Optional<Reorder> loginGroup() throws StepFailed {
        // 引导：完整登录一次拿到 player_id（首次运行顺带建角），随即断开。流水线里发 26 时 48 的回包（角色列表）还没到，
        // 26 要带的 player_id 只能事先知道
        String step = "O7-bootstrap";
        long playerId;
        try {
            EnteredPlayer boot = flow.enter(accountLogin, new Timings());
            playerId = boot.playerId();
            boot.connection().close();
        } catch (RobotException e) {
            throw fail(step, "account=" + accountLogin + "：" + e.getMessage());
        }
        pass(step, "登录组引导登录拿到角色", "player_id=" + uid(playerId));

        List<Integer> reordered = new ArrayList<>();
        for (int round = 1; round <= LOGIN_ROUNDS; round++) {
            if (loginRound(playerId, round)) {
                reordered.add(round);
            }
        }
        if (!reordered.isEmpty()) {
            return Optional.of(new Reorder("O7-order", OrderingChecks.reorderReason(reordered.size(), LOGIN_ROUNDS, reordered,
                    "执行顺序被颠倒（后发的 EnterGame 先于 Login 执行，被判「未登录」tip=" + OrderingChecks.TIP_NOT_LOGGED_IN + "）")));
        }
        pass("O7-order", "Login → EnterGame 每轮按发送顺序执行（EnterGame 都在 Login 之后执行并收到 79）", "rounds=" + LOGIN_ROUNDS);
        return Optional.empty();
    }

    /**
     * 隔 {@link #LOGIN_ROUND_GAP} 后新开一条连接，不等 48 的回包连发 48 → 26。返回 true 表示 26 先于 48 执行（被判「未登录」）；
     * 按序时还要等到 79 才算这一轮通过。连接无论成败都在返回前断开。
     */
    private boolean loginRound(long playerId, int round) throws StepFailed {
        String step = "O7-connect";
        GameConnection connection;
        try {
            sleep(LOGIN_ROUND_GAP);
            connection = client.connect(client.assignGate());
        } catch (RobotException e) {
            throw fail(step, rounds(round, LOGIN_ROUNDS) + " " + e.getMessage());
        }
        try {
            step = "O7-pipeline";
            Bot bot = new Bot("登录组", connection, playerId);
            int mark = bot.mark();
            // password 路径只填 account + password，auth_type 保持空串（robot 契约 §1.3 第 4 条，同 PlayerFlow.tryLogin）
            Sent[] sent = bot.pipeline(ids.login(), LoginRequest.newBuilder().setAccount(accountLogin).setPassword(password).build(),
                    ids.enterGame(), EnterGameRequest.newBuilder().setPlayerId(playerId).build());
            LoginResponse login = bot.reply(mark, sent[0], LoginResponse.parser());
            EnterGameResponse enter = bot.reply(mark, sent[1], EnterGameResponse.parser());
            if (login.hasErrorMessage()) {
                // 登录阶段按 error_message 是否存在判失败（robot 契约 §3.4 第 3 条）
                int tip = login.getErrorMessage().getId();
                throw fail(step, rounds(round, LOGIN_ROUNDS) + " Login 被拒 tip=" + tip + OrderingChecks.hint(tip));
            }
            if (!listsPlayer(login, playerId)) {
                throw fail(step, rounds(round, LOGIN_ROUNDS) + " Login 回包的角色列表里没有引导登录拿到的 player_id=" + uid(playerId));
            }
            int enterTip = GuildScenario.tipOf(enter);
            switch (OrderingChecks.login(enterTip)) {
                case REORDERED -> {
                    return true;
                }
                case INCONSISTENT -> throw fail(step, rounds(round, LOGIN_ROUNDS) + " EnterGame 业务拒绝 tip=" + enterTip
                        + OrderingChecks.hint(enterTip));
                case IN_ORDER -> {
                    // 0 或 2005：26 已在 48 之后执行
                }
            }
            for (int attempt = 2; enterTip == OrderingChecks.TIP_LOGIN_IN_PROGRESS; attempt++) {
                if (attempt > ENTER_MAX_ATTEMPTS) {
                    throw fail(step, rounds(round, LOGIN_ROUNDS) + " EnterGame 连续 " + ENTER_MAX_ATTEMPTS + " 次回 2005"
                            + OrderingChecks.hint(enterTip));
                }
                sleep(ENTER_RETRY_DELAY);
                enter = bot.call(ids.enterGame(), EnterGameRequest.newBuilder().setPlayerId(playerId).build(), EnterGameResponse.parser());
                enterTip = GuildScenario.tipOf(enter);
                if (enterTip != 0 && enterTip != OrderingChecks.TIP_LOGIN_IN_PROGRESS) {
                    throw fail(step, rounds(round, LOGIN_ROUNDS) + " 重发的 EnterGame 业务拒绝 tip=" + enterTip + OrderingChecks.hint(enterTip));
                }
            }
            if (enter.getPlayerId() != playerId) {
                throw fail(step, rounds(round, LOGIN_ROUNDS) + " EnterGame 应答 player_id=" + uid(enter.getPlayerId()) + "，应等于请求的 "
                        + uid(playerId) + "（robot 契约 §4 步骤 D）");
            }
            // 26 无错只表示「已受理」，进场成功以收到 79 为准（79 可以早于 26 的应答到达：从发 48 之前的位置找起）
            step = "O7-enter";
            awaitEnterScene(connection, mark, round);
            return false;
        } catch (RobotException e) {
            throw fail(step, rounds(round, LOGIN_ROUNDS) + " " + e.getMessage());
        } finally {
            connection.close();
        }
    }

    /** 等 79（带完整的 scene_info）；先到的是 124 或超时都失败。 */
    private void awaitEnterScene(GameConnection connection, int mark, int round) throws RobotException, StepFailed {
        int enterScene = ids.notifyEnterScene();
        int redirect = client.redirectToGateId();
        Optional<Received> first = connection.await(mark, r -> r.messageId() == enterScene || r.messageId() == redirect, enterSceneTimeout);
        if (first.isEmpty()) {
            throw fail("O7-enter", rounds(round, LOGIN_ROUNDS) + " EnterGame 受理了，但 " + enterSceneTimeout.toMillis() + " ms 内没有收到 79"
                    + connection.describeSince(mark));
        }
        if (first.get().messageId() == redirect) {
            throw fail("O7-enter", rounds(round, LOGIN_ROUNDS) + " EnterGame 受理之后收到的是 124 RedirectToGate：这个角色正在别的区（登录期重定向），"
                    + "本场景不跟随；换 --run-tag 或等 30 s 重连租约过期");
        }
        EnterSceneS2C notify = first.get().parse(EnterSceneS2C.parser());
        if (!notify.hasSceneInfo() || notify.getSceneInfo().getSceneId() == 0 || notify.getSceneInfo().getSceneConfigId() == 0) {
            throw fail("O7-enter", rounds(round, LOGIN_ROUNDS) + " 79 的 scene_info 缺失或不完整（robot 契约 §4 步骤 E）");
        }
    }

    private static boolean listsPlayer(LoginResponse login, long playerId) {
        for (AccountSimplePlayerWrapper wrapper : login.getPlayersList()) {
            if (wrapper.getPlayer().getPlayerId() == playerId) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ 公共部分

    /** 登录进场一个账号；失败按 {@code step} 结束本场景（连接已由 PlayerFlow 关掉）。 */
    private Bot enterBot(String step, String name, String account) throws StepFailed {
        try {
            EnteredPlayer player = flow.enter(account, new Timings());
            return new Bot(name, player.connection(), player.playerId());
        } catch (RobotException e) {
            throw fail(step, name + " account=" + account + "：" + e.getMessage());
        }
    }

    /** 发一条、等回包，断言业务 tip（应答体 error_message.id）等于 {@code want}。 */
    private static <T extends Message> T expectTip(Bot bot, int messageId, Message request, Parser<T> parser, int want) throws RobotException {
        T response = bot.call(messageId, request, parser);
        int got = GuildScenario.tipOf(response);
        if (got != want) {
            throw new RobotException(bot.name + " message_id=" + messageId + " 期望 tip=" + want + "，实得 tip=" + got + OrderingChecks.hint(got));
        }
        return response;
    }

    private void pass(String step, String what, String detail) {
        report.pass(step + " " + what, detail, REF);
    }

    private static StepFailed fail(String step, String reason) {
        return new StepFailed(step, reason);
    }

    private static String rounds(int round, int total) {
        return "round=" + round + "/" + total;
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    private static void sleep(Duration duration) throws RobotException {
        BattleSupport.sleep(duration);
    }

    private static boolean matches(Received r, Sent sent) {
        return r.messageId() == sent.messageId() && r.requestId() == sent.requestId();
    }

    /** 非乱序类失败：立即结束本场景。{@code step} 是 mmorpg 同名的步骤号（如 {@code O5-pipeline}）。 */
    private static final class StepFailed extends Exception {

        final String step;
        final String reason;

        StepFailed(String step, String reason) {
            super(step + ": " + reason, null, false, false);
            this.step = step;
            this.reason = reason;
        }
    }

    /** 已发出的一条请求。 */
    private record Sent(int messageId, long requestId) {
    }

    /**
     * 一条连接上的机器人。只在场景线程上用。发送只过同号节拍、不设相邻间隔：流水线的两条必须背靠背发出，间隔一拉开，
     * 前一条多半已经执行完，顺序问题就测不出来了。
     */
    private final class Bot {

        final String name;
        final GameConnection connection;
        final long playerId;
        private final GuildScenario.Pacer pacer = new GuildScenario.Pacer(Duration.ZERO, SAME_ID_WINDOW, SAME_ID_MAX_IN_WINDOW);

        Bot(String name, GameConnection connection, long playerId) {
            this.name = name;
            this.connection = connection;
            this.playerId = playerId;
        }

        /** 收件箱此刻的位置：先取它再发请求，等回包时从它起找，就不会漏掉极快到达的回包。 */
        int mark() {
            return connection.inbox().size();
        }

        /** 发一条、等回包。信封拒绝与超时抛出：请求没到业务逻辑，不能当成业务码去断言。 */
        <T extends Message> T call(int messageId, Message body, Parser<T> parser) throws RobotException {
            int mark = mark();
            return reply(mark, send(messageId, body), parser);
        }

        /** 只发不等。 */
        Sent send(int messageId, Message body) throws RobotException {
            sleep(Duration.ofNanos(pacer.delayNanos(messageId, System.nanoTime())));
            pacer.record(messageId, System.nanoTime());
            return new Sent(messageId, connection.send(messageId, body));
        }

        /** 不等回包连发两条（模拟客户端连点）：先按两条各自的同号节拍等够，再背靠背发出。 */
        Sent[] pipeline(int firstId, Message first, int secondId, Message second) throws RobotException {
            long now = System.nanoTime();
            sleep(Duration.ofNanos(Math.max(pacer.delayNanos(firstId, now), pacer.delayNanos(secondId, now))));
            now = System.nanoTime();
            pacer.record(firstId, now);
            pacer.record(secondId, now);
            Sent a = new Sent(firstId, connection.send(firstId, first));
            Sent b = new Sent(secondId, connection.send(secondId, second));
            return new Sent[] {a, b};
        }

        /** 从 {@code mark} 起等 {@code sent} 的回包（按 (message_id, id) 对上），解出业务回包体。 */
        <T extends Message> T reply(int mark, Sent sent, Parser<T> parser) throws RobotException {
            Optional<Received> reply = connection.await(mark, r -> matches(r, sent), requestTimeout);
            if (reply.isEmpty()) {
                throw new RobotException(name + "：" + requestTimeout.toMillis() + " ms 内没有收到 message_id=" + sent.messageId() + " id="
                        + sent.requestId() + " 的回包" + connection.describeSince(mark));
            }
            Received received = reply.get();
            if (received.envelopeTipId() != 0) {
                throw new RobotException(name + "：message_id=" + sent.messageId() + " 被 gate 在信封上拒绝（tip=" + received.envelopeTipId()
                        + "：gate 限频 1008、lane 满、排队超时或后端不可用 1003，请求没到业务逻辑）");
            }
            return received.parse(parser);
        }

        /** 此刻已收到的 {@code sent} 的回包（不等）。 */
        Optional<Received> find(int mark, Sent sent) {
            return connection.inbox().snapshot(mark).stream().filter(r -> matches(r, sent)).findFirst();
        }
    }
}
