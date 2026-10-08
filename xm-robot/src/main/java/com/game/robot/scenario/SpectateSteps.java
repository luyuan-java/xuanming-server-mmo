package com.game.robot.scenario;

import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SpectateStateS2C;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.game.robot.client.BattleDirectConnection;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.table.MatchErrorTip;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 观战的客户端件（批次 6.5，spectate-spec §10.7 / §10.8），battle-smoke 的观战段与 battle-cross-zone 的 Z5 共用：
 * <ul>
 *   <li>163 WatchBattle / 164 ListWatchableBattles 的请求，与应答的纯判据（成功只带 {@code battle_id}、拒绝是
 *       {@code {battle_id = 0, error_message{id, [文案]}}}、列表按 {@code created_at_ms} 降序且都没过期）；</li>
 *   <li>观众的票：163 成功后 battle 经大厅推 177 {@code {role = OBSERVER(2)}}（新观众此刻没有直连，一定走大厅）；</li>
 *   <li>观众直连：握手应答是第一帧，<b>紧跟</b> 161（全员冷却清空、没有 {@code self_items}），之后每回合一条 158，收尾 166 再 FIN；</li>
 *   <li>165 主动退出：应答 → FIN，<b>不推</b> 166。</li>
 * </ul>
 * 判据都是纯函数（输入已解析的应答 / 帧序列，输出问题描述，没问题返回 null），由 {@code SpectateStepsTest} 覆盖；带网络的几个方法只负责收发与等待，
 * 全部等待都有上限。只在场景线程上使用。
 *
 * <p><b>文案是客户端契约</b>：{@code parameters[0]} 逐字节照搬 spectate-spec §3.2（逗号是半角）；{@code MatchUpstreamTest} 读 xm-match 的
 * {@code MatchTip} 把两边钉成一样。
 */
final class SpectateSteps {

    static final String REF = "spectate-spec §10.7";

    // ---- tip 码（导表生成的枚举，不手写数字；spectate-spec §3.1） ----
    static final int TIP_QUEUED = MatchErrorTip.match_error.kMatchSpectateWhileQueued_VALUE;
    static final int TIP_IN_BATTLE = MatchErrorTip.match_error.kMatchSpectateWhileInBattle_VALUE;
    static final int TIP_ALREADY_WATCHING = MatchErrorTip.match_error.kMatchAlreadyWatching_VALUE;
    static final int TIP_NO_BATTLE = MatchErrorTip.match_error.kMatchNoWatchableBattle_VALUE;
    /** 16018 有两种文案：{@link #TEXT_NOT_FOUND} 与 {@link #TEXT_NOT_WATCHABLE}。 */
    static final int TIP_NOT_WATCHABLE = MatchErrorTip.match_error.kMatchBattleNotWatchable_VALUE;
    static final int TIP_OFFLINE = MatchErrorTip.match_error.kMatchSpectateOffline_VALUE;

    // ---- parameters[0]（逐字节；半角逗号） ----
    static final String TEXT_QUEUED = "匹配中无法观战";
    static final String TEXT_IN_BATTLE = "战斗尚未结束,无法观战";
    static final String TEXT_ALREADY_WATCHING = "已在观战另一场战斗";
    static final String TEXT_NO_BATTLE = "当前没有可观战的战斗";
    static final String TEXT_NOT_FOUND = "该战斗不存在或已结束";
    static final String TEXT_NOT_WATCHABLE = "该战斗当前无法观战";
    static final String TEXT_OFFLINE = "会话不在线,无法观战";

    /** 164 的条数：{@code limit = 0} → 20，超过 50 → 50（spectate-spec §3.3）。 */
    static final int LIST_DEFAULT = 20;
    static final int LIST_MAX = 50;
    /** 可观战索引的过期分界：服务端按 Redis {@code TIME − 360 s} 判（已结束不足这么久的战斗仍在列表里，BW7）。 */
    static final long STALE_MS = 360_000;
    /** robot 墙钟与 Redis 时间的差：列表的时间窗两头各留这么多（spectate-spec §10.7 S0、评审 F4）。 */
    static final long LIST_CLOCK_SLACK_MS = 5_000;

    private SpectateSteps() {
    }

    /**
     * 观战各步的时限（spectate-spec §10.7「节奏」、§10.8「时限」）。对真服务端一律用 {@link #STANDARD}；单测对着本机假服务端时调快。
     *
     * @param ticketTimeout     163 成功后等观众票 177 的上限（15 s，同基线 {@code battleSmokeSpectateTimeout}）
     * @param firstFrameTimeout 握手后等 161 的上限（15 s）
     * @param endTimeout        等收尾 166 的上限（120 s，覆盖挂机打满回合上限）
     * @param stopTimeout       发 165 后等「应答 → FIN」的上限
     * @param publishRetry      battle-smoke S2：开局公告（177 / 143）先于「登记进可观战索引」，列表里还没有这一场时重试的上限（5 s）
     * @param randomRetry       battle-smoke S8：随机观战遇 16017 的重试上限（10 s；每次失败都会懒剔除已结束的残留场）
     * @param evictTimeout      battle-smoke S11：观众去排队后等 166 REMOVED 的上限（10 s）
     * @param liveBudget        battle-smoke：S1 收到 177 到 S8 结束的合计预算（60 s；战斗 X 靠 6 s 回合超时活着，脚本慢了它会先打完）
     * @param readyResidue      battle-smoke S12：从上一局收到 177 起、ready 票据（60 s）必然过期的时长（62 s）
     * @param precleanRounds    battle-smoke：S1 之前清残留场次的轮数上限
     */
    record Timing(Duration ticketTimeout, Duration firstFrameTimeout, Duration endTimeout, Duration stopTimeout, Duration publishRetry,
                  Duration randomRetry, Duration evictTimeout, Duration liveBudget, Duration readyResidue, int precleanRounds) {

        static final Timing STANDARD = new Timing(Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(120), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(62), 30);
    }

    // ---------------------------------------------------------------- 163 / 164 的请求

    /** 163：{@code battleId = 0} 是随机观战。身份只取会话，请求体里的 player_id 被服务端忽略（照真实客户端填自己）。 */
    static WatchBattleResponse watch(MatchSupport.Caller bot, MatchSupport.Ids ids, long battleId) throws RobotException {
        return bot.call(ids.watchBattle(), WatchBattleRequest.newBuilder().setPlayerId(bot.id()).setBattleId(battleId).build(),
                WatchBattleResponse.parser());
    }

    /** 164：{@code limit = 0} 是服务端缺省条数。空列表也回包（0 字节的应答体）。 */
    static ListWatchableBattlesResponse list(MatchSupport.Caller bot, MatchSupport.Ids ids, int limit) throws RobotException {
        return bot.call(ids.listWatchable(), ListWatchableBattlesRequest.newBuilder().setPlayerId(bot.id()).setLimit(limit).build(),
                ListWatchableBattlesResponse.parser());
    }

    // ---------------------------------------------------------------- 163 的应答

    /** 163 成功：{@code battle_id ≠ 0}，且<b>不带</b> {@code error_message} 字段（哪怕 id = 0 的空 tip 也算带了）。 */
    static boolean accepted(WatchBattleResponse response) {
        return response.getBattleId() != 0 && !response.hasErrorMessage();
    }

    /**
     * 163 的成功应答。
     *
     * @param expectedBattleId 指定观战时就是请求的那一场；随机观战传 0（只要求回填了非 0 的 battle_id）
     * @return null = 没问题
     */
    static String acceptedProblem(WatchBattleResponse response, long expectedBattleId) {
        if (response.getErrorMessage().getId() != 0) {
            return "期望成功，实得 " + describe(response);
        }
        if (response.hasErrorMessage()) {
            return "成功的应答不得带 error_message 字段（哪怕 id = 0）：" + describe(response);
        }
        if (response.getBattleId() == 0) {
            return "成功的应答必须回填 battle_id（随机观战回填实际挑中的那一场），实得 0";
        }
        if (expectedBattleId != 0 && response.getBattleId() != expectedBattleId) {
            return "battle_id=" + uid(response.getBattleId()) + "（期望 " + uid(expectedBattleId) + "）";
        }
        return null;
    }

    /**
     * 163 的拒绝应答：{@code battle_id = 0}，{@code error_message.id == code}，{@code parameters} 恰好一项且逐字节等于 {@code text}。
     *
     * @return null = 没问题
     */
    static String rejectedProblem(WatchBattleResponse response, int code, String text) {
        List<String> problems = new ArrayList<>();
        String tip = BattleSmokeChecks.tipProblem(response.getErrorMessage(), code, text);
        if (tip != null) {
            problems.add(tip);
        }
        if (response.getBattleId() != 0) {
            problems.add("拒绝的应答 battle_id 应为 0，实得 " + uid(response.getBattleId()));
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    /** 这条应答是不是恰好这个拒绝（码与文案都对上）。 */
    static boolean isRejection(WatchBattleResponse response, int code, String text) {
        return rejectedProblem(response, code, text) == null;
    }

    static String describe(WatchBattleResponse response) {
        TipInfoMessage tip = response.getErrorMessage();
        return "battle_id=" + uid(response.getBattleId()) + (response.hasErrorMessage() ? " " + BattleSmokeChecks.describe(tip) : " 无 error_message");
    }

    // ---------------------------------------------------------------- 164 的应答

    /** 服务端对 {@code limit} 的收口：0 → {@value #LIST_DEFAULT}，超过 {@value #LIST_MAX} → {@value #LIST_MAX}。 */
    static int listCap(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit 是 uint32，robot 只发非负值：" + limit);
        }
        return limit == 0 ? LIST_DEFAULT : Math.min(limit, LIST_MAX);
    }

    /**
     * 164 应答的形状（spectate-spec §3.3、§10.7 S0）：条数不超过收口后的 {@code limit}（服务端不补齐，可以更少）；{@code created_at_ms} 不增
     * （降序）；每条的 battle_id 非 0 且不重复；{@code created_at_ms} 都在 {@code [now − 360 s − 5 s, now + 5 s]} 之内——过期的场次不该出现，
     * 分界在服务端按 Redis 时间判，robot 的墙钟与它有毫秒到秒级的差，所以两头各留 5 s。
     *
     * @param nowMs 收到应答时 robot 的墙钟毫秒
     * @return null = 没问题
     */
    static String listProblem(ListWatchableBattlesResponse list, int limit, long nowMs) {
        int cap = listCap(limit);
        List<String> problems = new ArrayList<>();
        if (list.getBattlesCount() > cap) {
            problems.add("回了 " + list.getBattlesCount() + " 条，超过 limit=" + limit + " 收口后的 " + cap + " 条");
        }
        long oldest = nowMs - STALE_MS - LIST_CLOCK_SLACK_MS;
        long newest = nowMs + LIST_CLOCK_SLACK_MS;
        Set<Long> seen = new HashSet<>();
        long previous = Long.MAX_VALUE;
        for (int i = 0; i < list.getBattlesCount(); i++) {
            BattleWatchSummary summary = list.getBattles(i);
            String at = "第 " + (i + 1) + " 条（battle_id=" + uid(summary.getBattleId()) + "）";
            if (summary.getBattleId() == 0) {
                problems.add(at + " battle_id 为 0");
            } else if (!seen.add(summary.getBattleId())) {
                problems.add(at + " 重复出现");
            }
            long created = summary.getCreatedAtMs();
            if (created > previous) {
                problems.add(at + " created_at_ms=" + created + " 比前一条的 " + previous + " 大（应按 created_at_ms 降序）");
            }
            previous = created;
            if (created < oldest || created > newest) {
                problems.add(at + " created_at_ms=" + created + " 不在 [now − 365 s, now + 5 s] = [" + oldest + ", " + newest + "] 内（相对 now "
                        + (created - nowMs) + " ms；过期的场次不该出现在列表里，robot 与服务端不同机时先对时钟）");
            }
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    /** 列表里这一场的摘要；没有为空。 */
    static Optional<BattleWatchSummary> find(ListWatchableBattlesResponse list, long battleId) {
        return list.getBattlesList().stream().filter(s -> s.getBattleId() == battleId).findFirst();
    }

    /**
     * 一条摘要逐字段（spectate-spec §3.3、§10.7 S2）：{@code mode}（按数值比，契约里没有的值也原样下发）、{@code battle_config_id}、
     * {@code player_names}（角色名，顺序 = gather 的成员顺序）、{@code created_at_ms ∈ [发 157 的时刻 − 5 s, 收到 177 的时刻 + 5 s]}
     * （服务端在全员备战之后、建房之前取 Redis 时间）。
     *
     * @return null = 没问题
     */
    static String summaryProblem(BattleWatchSummary summary, int modeValue, int battleConfigId, List<String> playerNames, long sentAtMs,
                                 long assignedAtMs) {
        List<String> problems = new ArrayList<>();
        if (summary.getModeValue() != modeValue) {
            problems.add("mode=" + summary.getModeValue() + "（期望 " + modeValue + "）");
        }
        if (summary.getBattleConfigId() != battleConfigId) {
            problems.add("battle_config_id=" + summary.getBattleConfigId() + "（期望 " + battleConfigId + "）");
        }
        if (!summary.getPlayerNamesList().equals(playerNames)) {
            problems.add("player_names=" + summary.getPlayerNamesList() + "（期望 " + playerNames + "：各成员的角色名，按成员顺序）");
        }
        long low = sentAtMs - LIST_CLOCK_SLACK_MS;
        long high = assignedAtMs + LIST_CLOCK_SLACK_MS;
        if (summary.getCreatedAtMs() < low || summary.getCreatedAtMs() > high) {
            problems.add("created_at_ms=" + summary.getCreatedAtMs() + " 不在 [发 157 − 5 s, 收到 177 + 5 s] = [" + low + ", " + high + "] 内（相对发 157 "
                    + (summary.getCreatedAtMs() - sentAtMs) + " ms；客户端用它算「已开局时长」，必须是 Unix 毫秒）");
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    static String describe(ListWatchableBattlesResponse list) {
        StringBuilder out = new StringBuilder().append(list.getBattlesCount()).append(" 条");
        if (list.getBattlesCount() > 0) {
            out.append(" [");
            for (int i = 0; i < Math.min(list.getBattlesCount(), 5); i++) {
                out.append(i == 0 ? "" : ", ").append(uid(list.getBattles(i).getBattleId()));
            }
            out.append(list.getBattlesCount() > 5 ? ", …]" : "]");
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 观众的票（大厅 177，role = 2）

    /** 一条观众票的 177（推送形状、能解析、role = OBSERVER、battle_id 非 0；{@code battleId} 非 0 时还要是这一场的）；不是则为 null。 */
    static BattleAssignedS2C observerTicket(Received r, BattleIds ids, long battleId) {
        if (r.messageId() != ids.battleAssigned() || r.requestId() != 0) {
            return null;
        }
        BattleAssignedS2C assigned = r.parseOrNull(BattleAssignedS2C.parser());
        if (assigned == null || assigned.getRole() != eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER || assigned.getBattleId() == 0) {
            return null;
        }
        return battleId == 0 || assigned.getBattleId() == battleId ? assigned : null;
    }

    /**
     * 从 {@code from} 起等大厅上的观众票 177（{@code battleId = 0}：随机观战，接受任何一场的）。163 的应答与 177 分别来自 match 与 battle，
     * 两者没有先后保证，所以 {@code from} 要取在发 163 <b>之前</b>。
     */
    static Received awaitObserverTicket(String name, GameConnection lobby, int from, long battleId, BattleIds ids, Duration timeout)
            throws RobotException {
        return lobby.await(from, r -> observerTicket(r, ids, battleId) != null, timeout).orElseThrow(() -> new RobotException(name + "："
                + timeout.toSeconds() + " s 内大厅没有收到" + (battleId == 0 ? "" : " battle_id=" + uid(battleId) + " 的") + "观众票 177（role = 2；"
                + "163 成功后由 xm-battle 按在线目录经 gate 推：看 xm-battle 的 xm_battle_lobby_push_outcomes_total 与 AddObserver 日志）"
                + lobby.describeSince(from)));
    }

    /**
     * 观众票的形状（spectate-spec §10.7 S3；battle-node-spec §5.5）：{@code role = 2}、battle_id 对上、通告地址非空、
     * {@code token_signature} 是 64 位小写 hex、{@code expire_at_ms} 等于房间期限（参战者那张 177 的同名字段）、payload 指向本人本局且角色是观众。
     *
     * @param roomExpireAtMs 参战者那张 177 的 {@code expire_at_ms}；不知道（随机观战看到别人的战斗）传 0，只要求非 0 且与 payload 一致
     * @return null = 没问题
     */
    static String observerTicketProblem(BattleAssignedS2C ticket, long battleId, long observerId, long roomExpireAtMs) {
        List<String> problems = new ArrayList<>();
        if (ticket.getRole() != eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER) {
            problems.add("role=" + ticket.getRole().getNumber() + "（期望 2 OBSERVER）");
        }
        if (ticket.getBattleId() != battleId) {
            problems.add("battle_id=" + uid(ticket.getBattleId()) + "（期望 " + uid(battleId) + "）");
        }
        if (ticket.getHost().isEmpty() || ticket.getPort() <= 0 || ticket.getPort() > 65535) {
            problems.add("通告地址 " + ticket.getHost() + ":" + ticket.getPort() + " 不可用");
        }
        if (!BattleFixtures.isLowerHex64(ticket.getTokenSignature())) {
            problems.add("token_signature 不是 64 位小写 hex");
        }
        if (roomExpireAtMs != 0 ? ticket.getExpireAtMs() != roomExpireAtMs : ticket.getExpireAtMs() == 0) {
            problems.add("expire_at_ms=" + ticket.getExpireAtMs() + (roomExpireAtMs != 0 ? "（期望房间期限 " + roomExpireAtMs + "，与参战者的 177 同值）"
                    : "（期望非 0）"));
        }
        try {
            BattleTicketPayload payload = BattleTicketPayload.parseFrom(ticket.getTokenPayload());
            if (payload.getBattleId() != ticket.getBattleId() || payload.getPlayerId() != observerId
                    || payload.getRole() != eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER || payload.getExpireAtMs() != ticket.getExpireAtMs()) {
                problems.add("payload {battle_id=" + uid(payload.getBattleId()) + ", player_id=" + uid(payload.getPlayerId()) + ", role="
                        + payload.getRole().getNumber() + ", expire_at_ms=" + payload.getExpireAtMs() + "} 没有指向本人（" + uid(observerId)
                        + "）本局的观众票");
            }
        } catch (InvalidProtocolBufferException e) {
            problems.add("token_payload 解析不了：" + e.getMessage());
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    // ---------------------------------------------------------------- 观众直连

    /**
     * 一条已就绪的观战直连。
     *
     * @param direct     直连（握手已成功、首帧 161 已到）
     * @param firstFrame 握手之后的第一条 161
     * @param state      它的内容
     */
    record Watching(Direct direct, BattleFrame firstFrame, SpectateStateS2C state) {
    }

    /**
     * 凭观众票直连：建连 → 握手（必须成功且 battle_id 与票一致）→ 等 161。观众票握手后不补拉 140，由服务端在握手应答之后紧跟着推 161
     * （「紧跟」由调用方用 {@link #firstFrameProblem} 判，这里只等到它）。失败时连接已关闭。
     */
    static Watching connectObserver(RobotClient client, String name, BattleAssignedS2C ticket, BattleIds ids, Duration firstFrameTimeout)
            throws RobotException {
        Direct direct = Direct.open(client, name, ticket, ids);
        try {
            BattleDirectConnection.Handshake hs = direct.handshake(ticket);
            if (!hs.success()) {
                throw new RobotException(name + "：观战直连握手被拒「" + hs.response().getError() + "」（battle_id=" + uid(ticket.getBattleId())
                        + "；battle 的观众名单里没有这个人，或票已过期）");
            }
            if (hs.response().getBattleId() != ticket.getBattleId()) {
                throw new RobotException(name + "：观战直连握手回的 battle_id=" + uid(hs.response().getBattleId()) + " 与票据的 "
                        + uid(ticket.getBattleId()) + " 不一致");
            }
            BattleFrame first = direct.await(0, f -> f.isPush(ids.spectateState()), firstFrameTimeout).orElseThrow(() -> new RobotException(name
                    + "：握手后 " + firstFrameTimeout.toSeconds() + " s 内直连上没有 161（观战首帧只随直连下发）；收到 " + direct.labelsSince(0)));
            return new Watching(direct, first, first.parse(SpectateStateS2C.parser()));
        } catch (RobotException | RuntimeException e) {
            direct.close();
            throw e;
        }
    }

    /**
     * 观众直连开头两帧（battle-node-spec §5.8 O2、§5.6）：第一帧是成功的握手应答，<b>第二帧</b>就是 161；161 指向本局、{@code observer_count}
     * 对上、状态是观众版（全员冷却清空、没有 {@code self_items}）。
     *
     * @param frames            这条直连从头起的帧
     * @param expectedObservers 期望的观众数；不确定（看的是别人的战斗）传 -1，只要求 ≥ 1
     * @return null = 没问题
     */
    static String firstFrameProblem(List<BattleFrame> frames, BattleIds ids, long battleId, int expectedObservers) {
        if (frames.size() < 2 || !frames.get(0).isVerify() || !frames.get(0).verify().getSuccess()) {
            return "直连的第一帧应是成功的握手应答，实际 " + BattleSupport.labels(frames);
        }
        if (!frames.get(1).isPush(ids.spectateState())) {
            return "握手应答之后应紧跟 161，实际 " + BattleSupport.labels(frames.subList(0, Math.min(frames.size(), 6)));
        }
        SpectateStateS2C first = frames.get(1).parseOrNull(SpectateStateS2C.parser());
        if (first == null) {
            return "161 解析不了";
        }
        List<String> problems = new ArrayList<>();
        if (first.getState().getBattleId() != battleId) {
            problems.add("161 的 state.battle_id=" + uid(first.getState().getBattleId()) + "（期望 " + uid(battleId) + "）");
        }
        if (expectedObservers >= 0 ? first.getObserverCount() != expectedObservers : first.getObserverCount() < 1) {
            problems.add("observer_count=" + first.getObserverCount() + "（期望 " + (expectedObservers >= 0 ? expectedObservers : "≥ 1") + "）");
        }
        String redacted = redactedProblem(first.getState());
        if (redacted != null) {
            problems.add("161 " + redacted);
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    /** 观众版的状态：所有 actor 的冷却为空、没有 {@code self_items}（battle-node-spec §5.6）。null = 没问题。 */
    static String redactedProblem(BattleStateS2C state) {
        List<String> problems = new ArrayList<>();
        if (state.getSelfItemsCount() != 0) {
            problems.add("带了 " + state.getSelfItemsCount() + " 项 self_items（观众不该看到任何人的道具）");
        }
        long withCooldowns = state.getActorsList().stream().filter(a -> !a.getSkillCooldownRoundsMap().isEmpty()).count();
        if (withCooldowns != 0) {
            problems.add(withCooldowns + " 个 actor 的技能冷却没有清空");
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    /** 帧序列里 158 的条数。 */
    static int spectateTurns(List<BattleFrame> frames, BattleIds ids) {
        return (int) frames.stream().filter(f -> f.isPush(ids.spectateTurnResult())).count();
    }

    /** 帧序列里这一场的第一条 166；没有为 null。 */
    static SpectateEndS2C spectateEndOf(List<BattleFrame> frames, BattleIds ids, long battleId) {
        for (BattleFrame frame : frames) {
            if (frame.isPush(ids.spectateEnd())) {
                SpectateEndS2C end = frame.parseOrNull(SpectateEndS2C.parser());
                if (end != null && end.getBattleId() == battleId) {
                    return end;
                }
            }
        }
        return null;
    }

    /**
     * 一条观战直连的收尾。
     *
     * @param end    166
     * @param turns  这条直连上收到的 158 条数
     * @param closed 166 之后连接的关闭方式（{@value BattleFrame#FIN} 才是契约；等待期内没关则为 null）
     * @param frames 这条直连上按到达顺序的全部帧（含关闭标记）
     */
    record Ended(SpectateEndS2C end, int turns, String closed, List<BattleFrame> frames) {

        Ended {
            frames = List.copyOf(frames);
        }

        List<String> labels() {
            return BattleSupport.labels(frames);
        }
    }

    /**
     * 等这条观战直连上本场的 166（上限 {@code timeout}），再等服务端关闭连接（上限 {@link MatchSupport#FIN_TIMEOUT}，没等到不抛，由
     * {@link #endProblem} 判）。166 之前连接就被关、或超时都抛出。
     */
    static Ended awaitEnd(Direct direct, long battleId, BattleIds ids, Duration timeout) throws RobotException {
        Optional<BattleFrame> end = direct.await(0, f -> f.isPush(ids.spectateEnd()) && spectateEndOf(List.of(f), ids, battleId) != null, timeout);
        if (end.isEmpty()) {
            boolean closed = direct.since(0).stream().anyMatch(BattleFrame::isClosed);
            throw new RobotException(direct.name + "：" + (closed ? "观战直连在 166 之前就被关闭" : timeout.toSeconds() + " s 内观战直连上没有 166")
                    + "（battle_id=" + uid(battleId) + "）；收到 " + direct.labelsSince(0));
        }
        Optional<BattleFrame> closed = direct.await(end.get().index() + 1, BattleFrame::isClosed, MatchSupport.FIN_TIMEOUT);
        List<BattleFrame> all = direct.since(0);
        return new Ended(spectateEndOf(all, ids, battleId), spectateTurns(all, ids), closed.map(BattleFrame::closedBy).orElse(null), all);
    }

    /**
     * 观战收尾的判据（battle-node-spec §5.8 O5 / O6、§4.10；spectate-spec §3.5）：
     * <ul>
     *   <li>166 恰好一条，{@code reason} 与 {@code outcome} 对上（正常收尾 {@code {FINISHED, 参战者那条 150 的 outcome}}；被清退
     *       {@code {REMOVED, ONGOING}}）；</li>
     *   <li>166 之后紧跟 FIN（不是 RST、不是超时），中间没有别的帧；</li>
     *   <li>直连上至少 {@code minTurns} 条 158，每条都是观众版（冷却清空、没有 {@code self_items}）；观众的直连上没有 139 / 150（那是参战者的帧）。</li>
     * </ul>
     *
     * @return null = 没问题
     */
    static String endProblem(Ended ended, BattleIds ids, eSpectateEndReason reason, eBattleOutcome outcome, int minTurns) {
        List<String> problems = new ArrayList<>();
        SpectateEndS2C end = ended.end();
        if (end.getReason() != reason || end.getOutcome() != outcome) {
            problems.add("166 {reason=" + end.getReason() + ", outcome=" + end.getOutcome() + "}（期望 {" + reason + ", " + outcome + "}）");
        }
        List<BattleFrame> frames = ended.frames();
        long ends = frames.stream().filter(f -> f.isPush(ids.spectateEnd())).count();
        if (ends != 1) {
            problems.add("166 应恰好一条，实际 " + ends + " 条");
        }
        int endAt = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).isPush(ids.spectateEnd())) {
                endAt = i;
                break;
            }
        }
        List<String> tail = BattleSupport.labels(frames.subList(Math.max(0, endAt), frames.size()));
        if (!tail.equals(List.of("push:" + ids.spectateEnd(), BattleOrder.FIN))) {
            problems.add("166 之后应紧跟 FIN（不是 RST / 超时，中间没有别的帧），实际 " + tail);
        }
        if (ended.turns() < minTurns) {
            problems.add("直连上只有 " + ended.turns() + " 条 158（期望 ≥ " + minTurns + "）");
        }
        for (BattleFrame frame : frames) {
            if (frame.isPush(ids.spectateTurnResult())) {
                TurnResultS2C turn = frame.parseOrNull(TurnResultS2C.parser());
                String redacted = turn == null ? "解析不了" : redactedProblem(turn.getState());
                if (redacted != null) {
                    problems.add("158 #" + frame.index() + " " + redacted);
                    break;
                }
            }
        }
        long participantFrames = frames.stream().filter(f -> f.isPush(ids.turnResult()) || f.isPush(ids.battleEnd())).count();
        if (participantFrames != 0) {
            problems.add("观众的直连上出现了 " + participantFrames + " 条参战者的帧（139 / 150）");
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    // ---------------------------------------------------------------- 165 主动退出

    /**
     * 一次 165 的结局。
     *
     * @param requestId 165 的请求 id
     * @param tail      从发 165 之前的位置起、直到关闭标记（含）的帧
     */
    record Stopped(long requestId, List<BattleFrame> tail) {

        Stopped {
            tail = List.copyOf(tail);
        }
    }

    /** 在观战直连上发 165，等到连接被服务端关闭（上限 {@code timeout}）。 */
    static Stopped stopWatching(Direct direct, long battleId, BattleIds ids, Duration timeout) throws RobotException {
        int from = direct.mark();
        long requestId = direct.request(ids.stopWatch(), StopWatchBattleRequest.newBuilder().setBattleId(battleId).build());
        return new Stopped(requestId, direct.untilClosed(from, timeout));
    }

    /**
     * 165 的判据（battle-node-spec §5.8 O8；spectate-spec §3.5）：成功应答（回显请求 id、不带 {@code error_message}）→ FIN，<b>没有</b> 166。
     * 应答之前可以夹着这一场正常推进的 158（退出与回合结算没有先后保证），别的帧都不该有。
     *
     * @return null = 没问题
     */
    static String stopProblem(Stopped stopped, BattleIds ids) {
        List<BattleFrame> tail = stopped.tail();
        List<String> labels = BattleSupport.labels(tail);
        if (labels.contains("push:" + ids.spectateEnd())) {
            return "主动退出不该推 166，实际 " + labels;
        }
        List<BattleFrame> rest = tail.stream().filter(f -> !f.isPush(ids.spectateTurnResult())).toList();
        if (rest.size() != 2 || rest.get(0).content() == null || rest.get(0).messageId() != ids.stopWatch()
                || rest.get(0).requestId() != stopped.requestId() || !BattleOrder.FIN.equals(rest.get(1).label())) {
            return "应依次收到 165 的应答（id=" + stopped.requestId() + "）与 FIN，实际 " + labels;
        }
        if (rest.get(0).index() + 1 != rest.get(1).index()) {
            return "165 的应答之后应紧跟 FIN，实际 " + labels;
        }
        BattleFrame reply = rest.get(0);
        if (reply.isEnvelopeError()) {
            return "165 的应答是信封错误 tip=" + reply.envelopeTipId();
        }
        StopWatchBattleResponse body = reply.parseOrNull(StopWatchBattleResponse.parser());
        if (body == null) {
            return "165 的应答解析不了";
        }
        if (body.hasErrorMessage()) {
            return "165 的应答带 error_message " + body.getErrorMessage().getId() + body.getErrorMessage().getParametersList();
        }
        return null;
    }

    // ---------------------------------------------------------------- 大厅上不该出现的战斗帧

    /**
     * 大厅连接上战斗帧（139 / 158 / 161 / 166）的条数：这四种只走直连，没有活直连时直接丢弃、<b>不回落</b>大厅（battle-node-spec §5.7；
     * spectate-spec §10.7 S9）。
     */
    static long lobbyBattleFrames(List<Received> lobby, BattleIds ids) {
        return lobby.stream().filter(r -> r.messageId() == ids.turnResult() || r.messageId() == ids.spectateTurnResult()
                || r.messageId() == ids.spectateState() || r.messageId() == ids.spectateEnd()).count();
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }
}
