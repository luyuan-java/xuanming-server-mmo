package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleStartS2C;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.eBattleTicketRole;
import com.game.proto.match.CancelQueueRequest;
import com.game.proto.match.GetQueueStatusRequest;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.MatchMode;
import com.game.robot.client.BattleDirectConnection;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.GameConnection;
import com.game.robot.client.MatchAdminClient;
import com.game.robot.client.MatchAdminClient.Rating;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.scenario.BattleSupport.Direct;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 匹配各场景（battle-smoke / match-activity / match-5v5 / team 的开战段）共用的件（match-spec §15.5「节奏与时限」）：
 * <ul>
 *   <li>match 的十个消息号（按「服务裸名 + 方法名」解析，不写死数字）；</li>
 *   <li>大厅上的机器人：同一会话相邻请求至少隔 {@link #REQUEST_SPACING}（gate 对 match 的十个号都是缺省每秒 3 条，超频回 1008 并计非法包）；</li>
 *   <li>排队 / 查状态 / 取消；遇到「上一局还没结算落地」的过渡态按 {@link #SETTLE_RETRY_INTERVAL} 重试、上限 {@link #SETTLE_TIMEOUT}；</li>
 *   <li>等开局公告（大厅先 177 后 143）、凭票直连（握手 + 补拉 140）、开挂机、等终局 150 与 FIN。</li>
 * </ul>
 * 只在场景线程上使用。全部等待都有上限。
 */
final class MatchSupport {

    static final String SERVICE = "MatchService";
    /** 同一会话相邻请求的最小间隔（规格要求同号 ≥ 350 ms；这里不分号一律 400 ms，任意 1 s 内同号至多 3 条）。 */
    static final Duration REQUEST_SPACING = Duration.ofMillis(400);
    /** gate 的滑动窗口（1 s）加 200 ms 余量：同号任意这么长的窗口里至多 {@link #SAME_ID_MAX_IN_WINDOW} 条。 */
    static final Duration SAME_ID_WINDOW = Duration.ofMillis(1200);
    static final int SAME_ID_MAX_IN_WINDOW = 3;
    /** 过渡态（结算落地前战斗锁仍在、ready 票未清）的等待上限与重试间隔（基线 {@code teamSmokeSettleTimeout} / {@code …RetryInterval}）。 */
    static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(20);
    static final Duration SETTLE_RETRY_INTERVAL = Duration.ofSeconds(1);
    /** 等开战（177 / 143）：凑单 + 备战 + 建房的预算（基线 {@code battleSmokeStartTimeout}）。 */
    static final Duration BATTLE_START_TIMEOUT = Duration.ofSeconds(30);
    /** 等终局（150）：覆盖挂机打满回合上限的最坏情况（基线 {@code battleSmokeEndTimeout}）。 */
    static final Duration BATTLE_END_TIMEOUT = Duration.ofSeconds(120);
    /** 终局包之后等服务端 FIN 的上限（battle 1.5 s 内 FIN、1 s 强关兜底；多留余量，超了只记一条失败不中断）。 */
    static final Duration FIN_TIMEOUT = Duration.ofSeconds(5);
    /** 等 match / team 推送（156 / 154 / 213）的上限。 */
    static final Duration PUSH_TIMEOUT = Duration.ofSeconds(10);
    /** 「不该有回包 / 推送」的静默窗口（§15.5 第 3 步：148 发出后 1 s 内无回包）。 */
    static final Duration SILENCE = Duration.ofSeconds(1);
    /** 收到 150 之后等评分落账的上限（battle → Kafka → xm-match 入账；§15.5 第 8 步）与轮询间隔。 */
    static final Duration RATING_WAIT = Duration.ofSeconds(10);
    static final Duration RATING_POLL = Duration.ofMillis(500);

    private MatchSupport() {
    }

    static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    /**
     * 场景的节奏。对真服务端一律用 {@link #STANDARD}（规格 §15.5 的取值）；单测对着本机假服务端（不限频、开局与结算几乎瞬时）时调快，
     * 免得一条用例睡上几十秒。其余的「上限」（等终局 120 s、评分 10 s、等推送 10 s）不在这里：它们只在出错时才耗满，单测不去耗它们。
     *
     * @param requestSpacing     同一会话相邻请求的最小间隔
     * @param retryInterval      过渡态的重试间隔
     * @param settleTimeout      过渡态的等待上限（到了仍是过渡态就按失败报）
     * @param silence            「不该有回包 / 推送」的静默窗口
     * @param ratingPoll         等评分落账的轮询间隔
     * @param battleStartTimeout 等开战（大厅 177 / 143）的上限
     */
    record Tempo(Duration requestSpacing, Duration retryInterval, Duration settleTimeout, Duration silence, Duration ratingPoll,
                 Duration battleStartTimeout) {

        static final Tempo STANDARD = new Tempo(REQUEST_SPACING, SETTLE_RETRY_INTERVAL, SETTLE_TIMEOUT, SILENCE, RATING_POLL, BATTLE_START_TIMEOUT);
    }

    /**
     * match 客户端服务的十个消息号（match-spec §1.4）。
     *
     * @param joinQueue        157 JoinQueue
     * @param cancelQueue      148 CancelQueue（应答 Empty：成功不回包）
     * @param queueStatus      153 GetQueueStatus
     * @param challenge        152 ChallengePlayer
     * @param respondChallenge 151 RespondChallenge
     * @param challengeInvite  156 推送 ChallengeInviteS2C
     * @param challengeResult  154 推送 ChallengeResultS2C
     * @param watchBattle      163 WatchBattle（6.4 临时回 1006）
     * @param listWatchable    164 ListWatchableBattles（6.4 临时回空列表）
     * @param requestTicket    179 RequestBattleTicket（补签）
     */
    record Ids(int joinQueue, int cancelQueue, int queueStatus, int challenge, int respondChallenge, int challengeInvite, int challengeResult,
               int watchBattle, int listWatchable, int requestTicket) {

        static Ids resolve(MessageIdRegistry registry) {
            return new Ids(
                    registry.requireId(SERVICE, "JoinQueue"),
                    registry.requireId(SERVICE, "CancelQueue"),
                    registry.requireId(SERVICE, "GetQueueStatus"),
                    registry.requireId(SERVICE, "ChallengePlayer"),
                    registry.requireId(SERVICE, "RespondChallenge"),
                    registry.requireId(SERVICE, "NotifyChallengeInvite"),
                    registry.requireId(SERVICE, "NotifyChallengeResult"),
                    registry.requireId(SERVICE, "WatchBattle"),
                    registry.requireId(SERVICE, "ListWatchableBattles"),
                    registry.requireId(SERVICE, "RequestBattleTicket"));
        }
    }

    /**
     * 大厅上能发 match 请求的一方：本类的 {@link Bot}，或别的场景自己的机器人（team 场景的机器人另带场景信息与它自己的节拍）。
     * 实现必须自带发送节拍（同号每秒不超过 3 条）。
     */
    interface Caller {

        /** 报告里的称呼（A / B / …）。 */
        String name();

        /** 会话绑定的玩家号。 */
        long id();

        /** 大厅连接（等推送、看收件箱用）。 */
        GameConnection connection();

        /** 收件箱此刻的位置：先取它再发请求，等推送时从它起找。 */
        int mark();

        /** 发请求等应答；信封错误与超时抛出。 */
        <T extends Message> T call(int messageId, Message body, Parser<T> parser) throws RobotException;

        /** 只发不等；返回请求 id。 */
        long send(int messageId, Message body) throws RobotException;
    }

    /** 一个已进场的机器人（大厅连接）。每次发送先过节拍。 */
    static final class Bot implements Caller {

        final String name;
        final EnteredPlayer player;
        private final Duration requestTimeout;
        private final GuildScenario.Pacer pacer;

        Bot(String name, EnteredPlayer player, Duration requestTimeout, Tempo tempo) {
            this.name = name;
            this.player = player;
            this.requestTimeout = requestTimeout;
            // 相邻请求隔 requestSpacing；同号的滑动窗口上限随间隔等比例缩放（标准节奏下就是 1.2 s 内至多 3 条）
            this.pacer = new GuildScenario.Pacer(tempo.requestSpacing(), tempo.requestSpacing().multipliedBy(SAME_ID_MAX_IN_WINDOW),
                    SAME_ID_MAX_IN_WINDOW);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long id() {
            return player.playerId();
        }

        String account() {
            return player.account();
        }

        @Override
        public GameConnection connection() {
            return player.connection();
        }

        @Override
        public int mark() {
            return connection().inbox().size();
        }

        String describeSince(int mark) {
            return connection().describeSince(mark);
        }

        /** 发请求等应答；信封错误（限频 1008、后端不可用 1003 ……）与超时抛出：它们说明请求没有得到 match 的业务应答。 */
        @Override
        public <T extends Message> T call(int messageId, Message body, Parser<T> parser) throws RobotException {
            pace(messageId);
            try {
                return connection().call(messageId, body, parser, requestTimeout);
            } catch (RobotException e) {
                throw new RobotException(name + "：" + e.getMessage(), e);
            }
        }

        /** 只发不等；返回请求 id。 */
        @Override
        public long send(int messageId, Message body) throws RobotException {
            pace(messageId);
            try {
                return connection().send(messageId, body);
            } catch (RobotException e) {
                throw new RobotException(name + "：" + e.getMessage(), e);
            }
        }

        private void pace(int messageId) throws RobotException {
            long waitNanos = pacer.delayNanos(messageId, System.nanoTime());
            if (waitNanos > 0) {
                BattleSupport.sleep(Duration.ofNanos(waitNanos));
            }
            pacer.record(messageId, System.nanoTime());
        }
    }

    // ---------------------------------------------------------------- 排队

    /** 157：身份只取会话，请求体里的 player_id 被服务端忽略（照真实客户端填自己）。 */
    static JoinQueueResponse join(Caller bot, Ids ids, MatchMode mode, int battleConfigId) throws RobotException {
        return bot.call(ids.joinQueue(), joinRequest(bot.id(), mode, battleConfigId), JoinQueueResponse.parser());
    }

    static JoinQueueRequest joinRequest(long playerId, MatchMode mode, int battleConfigId) {
        return JoinQueueRequest.newBuilder().setPlayerId(playerId).setMode(mode).setBattleConfigId(battleConfigId).build();
    }

    /** 153。 */
    static GetQueueStatusResponse status(Caller bot, Ids ids) throws RobotException {
        return bot.call(ids.queueStatus(), GetQueueStatusRequest.newBuilder().setPlayerId(bot.id()).build(), GetQueueStatusResponse.parser());
    }

    /** 148：只发不等（成功不回包）；返回请求 id。{@code ticket} 为空串 = 取消当前那张票。 */
    static long cancel(Caller bot, Ids ids, String ticket) throws RobotException {
        return bot.send(ids.cancelQueue(), CancelQueueRequest.newBuilder().setPlayerId(bot.id()).setQueueTicket(ticket).build());
    }

    /**
     * 排队直到受理的一次尝试序列。
     *
     * @param mark     最后一次发 157 之前的收件箱位置（受理时从它起找这一局的 177 / 143）
     * @param response 最后一次的应答（受理，或不可重试的拒绝，或过渡态到了上限仍在）
     * @param retried  此前各次被拒的码（都是过渡态），按先后
     */
    record JoinAttempts(int mark, JoinQueueResponse response, List<Integer> retried) {

        JoinAttempts {
            retried = List.copyOf(retried);
        }

        boolean accepted() {
            return BattleSmokeChecks.accepted(response);
        }

        /** 整个序列里出现过这个码（含最后一次）。 */
        boolean saw(int code) {
            return response.getErrorCode() == code || retried.contains(code);
        }

        String describe() {
            return BattleSmokeChecks.describe(response) + (retried.isEmpty() ? "" : "（此前按过渡态重试 " + retried + "）");
        }
    }

    /**
     * 发 157；应答是 {@code transientCodes} 里的码（上一局结算落地前战斗锁仍在等过渡态）时隔 {@code tempo.retryInterval()} 重试，
     * 直到受理、遇到别的码、或超过 {@code tempo.settleTimeout()}。不抛「被拒」：结局由调用方判定。
     */
    static JoinAttempts joinRetrying(Caller bot, Ids ids, MatchMode mode, int battleConfigId, Set<Integer> transientCodes, Tempo tempo)
            throws RobotException {
        long deadline = System.nanoTime() + tempo.settleTimeout().toNanos();
        List<Integer> retried = new ArrayList<>();
        while (true) {
            int mark = bot.mark();
            JoinQueueResponse response = join(bot, ids, mode, battleConfigId);
            if (!shouldRetry(response.getErrorCode(), transientCodes, System.nanoTime(), deadline)) {
                return new JoinAttempts(mark, response, retried);
            }
            retried.add(response.getErrorCode());
            BattleSupport.sleep(tempo.retryInterval());
        }
    }

    /** 过渡态重试的判据：码在过渡态集合里（0 = 受理，从不重试），且还没到期限。 */
    static boolean shouldRetry(int code, Set<Integer> transientCodes, long nowNanos, long deadlineNanos) {
        return code != 0 && transientCodes.contains(code) && nowNanos - deadlineNanos < 0;
    }

    // ---------------------------------------------------------------- 开局公告

    /**
     * 大厅上一局的开局公告。
     *
     * @param assignedAt 177 那一条
     * @param startAt    143 那一条
     */
    record Started(Received assignedAt, Received startAt, BattleAssignedS2C assigned, BattleStartS2C start) {

        long battleId() {
            return assigned.getBattleId();
        }

        /** 大厅上先 177 后 143（battle-node-spec §5.8 O1）。 */
        boolean assignedFirst() {
            return assignedAt.index() < startAt.index();
        }
    }

    /** 一条参战票的 177（推送形状、能解析、role = PARTICIPANT、battle_id 非 0）；不是则为 null。 */
    static BattleAssignedS2C participantTicket(Received r, BattleIds ids) {
        if (r.messageId() != ids.battleAssigned() || r.requestId() != 0) {
            return null;
        }
        BattleAssignedS2C assigned = r.parseOrNull(BattleAssignedS2C.parser());
        return assigned != null && assigned.getRole() == eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT && assigned.getBattleId() != 0
                ? assigned : null;
    }

    /** 这一局的 143（推送形状、battle_id 对上）；不是则为 null。 */
    static BattleStartS2C startOf(Received r, BattleIds ids, long battleId) {
        if (r.messageId() != ids.battleStart() || r.requestId() != 0) {
            return null;
        }
        BattleStartS2C start = r.parseOrNull(BattleStartS2C.parser());
        return start != null && start.getBattleId() == battleId ? start : null;
    }

    /**
     * 从 {@code from} 起等一局的开局公告：第一条参战票 177（{@code battleId} 非 0 时还要是这一局的），再等同一 battle_id 的 143
     * （143 也从 {@code from} 起找：先后次序由调用方用 {@link Started#assignedFirst} 判，不在这里假定）。两步共用一个期限。
     */
    static Started awaitBattle(String name, GameConnection lobby, int from, long battleId, BattleIds ids, Duration timeout) throws RobotException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Predicate<Received> ticket = r -> {
            BattleAssignedS2C assigned = participantTicket(r, ids);
            return assigned != null && (battleId == 0 || assigned.getBattleId() == battleId);
        };
        Received r177 = lobby.await(from, ticket, timeout).orElseThrow(() -> new RobotException(name + "：" + timeout.toSeconds()
                + " s 内大厅没有收到" + (battleId == 0 ? "" : " battle_id=" + uid(battleId) + " 的") + "参战票 177（凑单 / 备战 / 建房没走通：看 xm-match 的 "
                + "xm_match_gathers_total{outcome} 与日志；切片里要有 xm-battle 与 scene 的 SceneBattleService）" + lobby.describeSince(from)));
        BattleAssignedS2C assigned = r177.parse(BattleAssignedS2C.parser());
        long id = assigned.getBattleId();
        Duration remaining = Duration.ofNanos(Math.max(TimeUnit.SECONDS.toNanos(1), deadline - System.nanoTime()));
        Received r143 = lobby.await(from, r -> startOf(r, ids, id) != null, remaining).orElseThrow(() -> new RobotException(name + "：收到了 battle_id="
                + uid(id) + " 的 177，但 " + timeout.toSeconds() + " s 内没有它的 143" + lobby.describeSince(from)));
        return new Started(r177, r143, assigned, r143.parse(BattleStartS2C.parser()));
    }

    /** 某条下行到达时的墙钟毫秒（收件箱只记单调时刻，这里按「现在」折算；核对服务端给的 Unix 毫秒用）。 */
    static long wallClockMillis(Received r) {
        return wallClockMillis(r.receivedNanos(), System.nanoTime(), System.currentTimeMillis());
    }

    static long wallClockMillis(long eventNanos, long nowNanos, long nowMillis) {
        return nowMillis - TimeUnit.NANOSECONDS.toMillis(nowNanos - eventNanos);
    }

    // ---------------------------------------------------------------- 直连

    /**
     * 凭参战票直连：建连 → 握手（必须成功且 battle_id 与票一致）→ 等补拉的 140 应答。开战后就该立刻连上——回合结算只走直连，
     * 没有活直连的回合直接丢弃（同基线「开战即直连」）。失败时连接已关闭。
     */
    static Direct connect(RobotClient client, String name, BattleAssignedS2C ticket, BattleIds ids, Duration replyTimeout) throws RobotException {
        Direct direct = Direct.open(client, name, ticket, ids);
        try {
            BattleDirectConnection.Handshake hs = direct.handshake(ticket);
            if (!hs.success()) {
                throw new RobotException(name + "：直连握手被拒「" + hs.response().getError() + "」（battle_id=" + uid(ticket.getBattleId()) + "）");
            }
            if (hs.response().getBattleId() != ticket.getBattleId()) {
                throw new RobotException(name + "：直连握手回的 battle_id=" + uid(hs.response().getBattleId()) + " 与票据的 "
                        + uid(ticket.getBattleId()) + " 不一致");
            }
            // 参战票握手成功后自动补拉了一条 140（观众票不补拉，stateRequestId 为 0）
            if (hs.stateRequestId() != 0) {
                BattleFrame state = direct.awaitReply(0, ids.getBattleState(), hs.stateRequestId(), replyTimeout);
                if (state.isEnvelopeError()) {
                    throw new RobotException(name + "：握手后补拉 140 的应答是信封错误 tip=" + state.envelopeTipId());
                }
            }
            return direct;
        } catch (RobotException | RuntimeException e) {
            direct.close();
            throw e;
        }
    }

    /**
     * 直连上开挂机（162，只发不等：应答与随后的回合都进收件箱）。多人局里别人先开挂机时这一局可能已经打完、连接已被服务端 FIN——
     * 那不算失败（终局由 {@link #awaitEnd} 判）；其余发送失败照常抛出。
     */
    static void enableAuto(Direct direct, long battleId, BattleIds ids) throws RobotException {
        try {
            direct.request(ids.setAutoBattle(), SetAutoBattleRequest.newBuilder().setBattleId(battleId).setEnabled(true).build());
        } catch (RobotException e) {
            if (endOf(direct.since(0), ids, battleId) == null) {
                throw e;
            }
        }
    }

    /**
     * 一局在直连上的收尾。
     *
     * @param end    150
     * @param turns  这条直连上收到的 139 条数
     * @param closed 150 之后连接的关闭方式（{@value BattleFrame#FIN} 才是契约；{@link #FIN_TIMEOUT} 内没关则为 null）
     * @param labels 这条直连上按到达顺序的全部帧标签（报告用）
     */
    record Finished(BattleEndS2C end, int turns, String closed, List<String> labels) {

        Finished {
            labels = List.copyOf(labels);
        }

        /** 终局包之后服务端正常关闭（FIN，不是 RST、不是超时）。 */
        boolean fin() {
            return BattleFrame.FIN.equals(closed);
        }
    }

    /** 帧序列里这一局的 150；没有为 null。 */
    static BattleEndS2C endOf(List<BattleFrame> frames, BattleIds ids, long battleId) {
        for (BattleFrame frame : frames) {
            if (frame.isPush(ids.battleEnd())) {
                BattleEndS2C end = frame.parseOrNull(BattleEndS2C.parser());
                if (end != null && end.getBattleId() == battleId) {
                    return end;
                }
            }
        }
        return null;
    }

    /** 帧序列里 139 的条数。 */
    static int turnCount(List<BattleFrame> frames, BattleIds ids) {
        return (int) frames.stream().filter(f -> f.isPush(ids.turnResult())).count();
    }

    /**
     * 等这一局在直连上的 150（上限 {@code timeout}），再等服务端关闭连接（上限 {@link #FIN_TIMEOUT}，没等到不抛、由调用方按
     * {@link Finished#fin} 记一条检查）。150 之前连接就被关、或超时都抛出。
     */
    static Finished awaitEnd(Direct direct, long battleId, BattleIds ids, Duration timeout) throws RobotException {
        Optional<BattleFrame> end = direct.await(0, f -> f.isPush(ids.battleEnd()) && endOf(List.of(f), ids, battleId) != null, timeout);
        if (end.isEmpty()) {
            boolean closed = direct.since(0).stream().anyMatch(BattleFrame::isClosed);
            throw new RobotException(direct.name + "：" + (closed ? "直连在 150 之前就被关闭" : timeout.toSeconds() + " s 内直连上没有 150")
                    + "（battle_id=" + uid(battleId) + "）；收到 " + direct.labelsSince(0));
        }
        Optional<BattleFrame> closed = direct.await(end.get().index() + 1, BattleFrame::isClosed, FIN_TIMEOUT);
        List<BattleFrame> all = direct.since(0);
        return new Finished(endOf(all, ids, battleId), turnCount(all, ids), closed.map(BattleFrame::closedBy).orElse(null), BattleSupport.labels(all));
    }

    // ---------------------------------------------------------------- 评分

    /**
     * 轮询评分（{@code GET /admin/match/dev/rating/{pid}}）直到每人的 {@code games} 都比赛前多，或到 {@link #RATING_WAIT}；
     * 返回最后一次读到的值（与 {@code before} 同下标），是否落账、增量对不对由调用方判定。
     */
    static List<Rating> awaitRatings(MatchAdminClient admin, List<Rating> before, Tempo tempo) throws RobotException {
        long deadline = System.nanoTime() + RATING_WAIT.toNanos();
        while (true) {
            List<Rating> now = new ArrayList<>();
            boolean all = true;
            for (Rating r : before) {
                Rating current = admin.rating(r.playerId());
                now.add(current);
                all &= current.games() > r.games();
            }
            if (all || System.nanoTime() - deadline >= 0) {
                return now;
            }
            BattleSupport.sleep(tempo.ratingPoll());
        }
    }

    // ---------------------------------------------------------------- 推送

    /** 从 {@code mark} 起等第一条消息号为 {@code messageId}、推送形状（id = 0）且满足条件的下行。 */
    static <T extends Message> Optional<T> awaitPush(GameConnection connection, int mark, int messageId, Parser<T> parser, Predicate<T> match,
                                                     Duration timeout) throws RobotException {
        Optional<Received> push = connection.await(mark, r -> {
            if (r.messageId() != messageId || r.requestId() != 0) {
                return false;
            }
            T body = r.parseOrNull(parser);
            return body != null && match.test(body);
        }, timeout);
        return push.map(r -> r.parseOrNull(parser));
    }
}
