package com.game.robot.scenario;

import com.game.proto.match.QueueState;
import com.game.table.CommonErrorTip;
import com.game.table.FriendErrorTip;
import com.game.table.GuildErrorTip;
import com.game.table.LoginErrorTip;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ordering-smoke（{@link OrderingScenario}）里不连服务端就能钉住的判定：每组一轮的回包码与权威状态 → 这一轮按发送顺序执行了、被颠倒了，
 * 还是与任何一种执行顺序都对不上；以及给外层脚本的结果行。判据逐条对应 mmorpg robot/ordering_smoke_scenario.go 与
 * ordering_smoke_groups.go 里的 switch（两版同一判据，PARITY「客户端请求按会话保序」行）。
 */
final class OrderingChecks {

    /** 结果行前缀（与 mmorpg 相同：{@code ORDERING_SMOKE_OK …} / {@code ORDERING_SMOKE_FAIL step=… reason=…}）。 */
    static final String MARKER = "ORDERING_SMOKE";

    static final int TIP_APPLICATION_NOT_FOUND = GuildErrorTip.guild_error.kGuildApplicationNotFound_VALUE;
    /** guild 写事务连续死锁或锁等待超时时回的码（GuildTip.WRITE_CONFLICT / OVERLOADED）。 */
    static final int TIP_GUILD_BUSY_RETRY = GuildErrorTip.guild_error.kGuildBusyRetry_VALUE;
    static final int TIP_NOT_IN_GUILD = GuildErrorTip.guild_error.kGuildNotInGuild_VALUE;
    static final int TIP_GUILD_NOT_FOUND = GuildErrorTip.guild_error.kGuildNotFound_VALUE;
    static final int TIP_HOME_ZONE_UNKNOWN = GuildErrorTip.guild_error.kGuildHomeZoneUnknown_VALUE;
    static final int TIP_FRIEND_NO_PENDING = FriendErrorTip.friend_error.kFriendNoPendingRequest_VALUE;
    static final int TIP_FRIEND_BLOCKED = FriendErrorTip.friend_error.kFriendBlocked_VALUE;
    /** EnterGame 时会话还没有登录态（EnterGameHandler：账号为空）。 */
    static final int TIP_NOT_LOGGED_IN = LoginErrorTip.login_error.kLoginSessionNotFound_VALUE;
    static final int TIP_LOGIN_IN_PROGRESS = LoginErrorTip.login_error.kLoginInProgress_VALUE;
    /** 业务配额（帮会 / 好友申请每分钟 10 次）回的码；与 gate 限频的信封 1008 不是一回事。 */
    static final int TIP_RATE_LIMITED = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;

    private OrderingChecks() {
    }

    /** 一轮的判定。 */
    enum Verdict {
        /** 符合发送顺序。 */
        IN_ORDER,
        /** 没按收包顺序串行执行（后发的先执行，或两条并发执行撞锁）：记一轮乱序，收尾后进下一轮。 */
        REORDERED,
        /** 回包码与状态组合不出任何一种执行顺序：不是顺序问题，立即失败。 */
        INCONSISTENT
    }

    /**
     * 一组（或 O3）登记下的乱序：全部轮次已跑完、每个乱序轮都已收尾到干净的起点。
     *
     * @param step   形如 {@code O5-order}
     * @param reason 给人看的原因（乱序轮次等）
     */
    record Reorder(String step, String reason) {
    }

    // ---------------------------------------------------------------- O3：JoinQueue → CancelQueue

    /**
     * 连发 157 → 148、再发 153 之后的队列状态。按序执行 = 加入后取消，不在队列里；仍 QUEUED = 148（或 153）先于前一条执行；
     * MATCHED / READY / ENTERING = 本机器人在 157 与 148 之间被别的玩家凑走（前置条件不满足，不是顺序问题）。
     */
    static Verdict queue(QueueState state) {
        return switch (state) {
            case QUEUE_STATE_NOT_QUEUED, QUEUE_STATE_UNSPECIFIED -> Verdict.IN_ORDER;
            case QUEUE_STATE_QUEUED -> Verdict.REORDERED;
            default -> Verdict.INCONSISTENT;
        };
    }

    // ---------------------------------------------------------------- O5：ApplyJoinGuild → CancelGuildApplication

    /**
     * 两条回包码本身合不合法：申请只有受理一种合法答复；撤回有受理（申请在、已删）与「申请不存在」（撤回执行时申请还没落库）两种；
     * 两条都可能回「忙，重试」（见 {@link #guildConcurrent}）。
     *
     * @return null = 合法；否则是哪一条被业务拒绝
     */
    static String guildTipsProblem(int applyTip, int cancelTip) {
        if (applyTip != 0 && applyTip != TIP_GUILD_BUSY_RETRY) {
            return "ApplyJoinGuild 业务拒绝 tip=" + applyTip + hint(applyTip);
        }
        if (cancelTip != 0 && cancelTip != TIP_APPLICATION_NOT_FOUND && cancelTip != TIP_GUILD_BUSY_RETRY) {
            return "CancelGuildApplication 业务拒绝 tip=" + cancelTip + hint(cancelTip);
        }
        return null;
    }

    /**
     * 任一条回「忙，重试」：两条在 game-guild 上并发执行、两个事务撞锁，落败的一条回写冲突。同一会话的两条串行执行时互不争锁，
     * 所以这与颠倒一样是「没按收包顺序串行执行」，记乱序（不能按业务拒绝直接失败：那会挡住后面的组与 O3）。
     */
    static boolean guildConcurrent(int applyTip, int cancelTip) {
        return applyTip == TIP_GUILD_BUSY_RETRY || cancelTip == TIP_GUILD_BUSY_RETRY;
    }

    /**
     * 撤回的回包码 + 帮主侧待审名单里有没有申请人（不是并发撞锁的轮次）。按序 = 撤回受理且名单里没有；颠倒 = 撤回回「申请不存在」、
     * 申请随后落库留在名单里；其余组合（撤回受理却还在名单里、撤回回「申请不存在」名单里却也没有）对不上任何一种执行顺序。
     */
    static Verdict guild(int cancelTip, boolean pending) {
        if (!pending && cancelTip == 0) {
            return Verdict.IN_ORDER;
        }
        if (pending && cancelTip == TIP_APPLICATION_NOT_FOUND) {
            return Verdict.REORDERED;
        }
        return Verdict.INCONSISTENT;
    }

    // ---------------------------------------------------------------- O6：AcceptFriend → RemoveFriend

    /**
     * 两条都受理之后双方的好友列表（两种执行顺序下两条都受理，分不出顺序，只能看最终关系）。按序 = 成为好友后删除，互相看不到；
     * 颠倒 = 删除先执行（删非好友是幂等成功）、同意后执行，互为好友；只一方看得到 = 好友边单边残留，与顺序无关。
     */
    static Verdict friend(boolean accepterSees, boolean requesterSees) {
        if (!accepterSees && !requesterSees) {
            return Verdict.IN_ORDER;
        }
        if (accepterSees && requesterSees) {
            return Verdict.REORDERED;
        }
        return Verdict.INCONSISTENT;
    }

    // ---------------------------------------------------------------- O7：Login → EnterGame

    /**
     * 连发 48 → 26 时 26 的业务码（48 已受理）。0 = 26 在 48 之后执行；「未登录」= 26 先于 48 执行；2005（同一角色进场在途）也说明 26 在 48
     * 之后执行——EnterGameHandler 先判会话账号、再抢在途闸——由调用方按 PlayerFlow 的约定重发 26；其余码是前置条件或环境问题。
     */
    static Verdict login(int enterTip) {
        if (enterTip == 0 || enterTip == TIP_LOGIN_IN_PROGRESS) {
            return Verdict.IN_ORDER;
        }
        if (enterTip == TIP_NOT_LOGGED_IN) {
            return Verdict.REORDERED;
        }
        return Verdict.INCONSISTENT;
    }

    // ---------------------------------------------------------------- 文案与结果行

    /** 几种环境类业务拒绝码的可读原因，免得把前置条件问题读成顺序问题（只用于业务码；信封上的 1008 是 gate 限频）。 */
    static String hint(int tip) {
        if (tip == TIP_RATE_LIMITED) {
            return "（撞上每分钟申请配额：game-guild apply-per-minute / game-friend request-quota-per-minute 缺省 10，固定 60 s 窗口；"
                    + "本场景每次运行各用 5 次，同一 --run-tag 一分钟内最多连跑两次，等一分钟或换标签）";
        }
        if (tip == TIP_HOME_ZONE_UNKNOWN) {
            return "（角色归属区未确认：game.player.zone_id 为 0）";
        }
        if (tip == TIP_GUILD_NOT_FOUND) {
            return "（申请人与帮会不在同一归属区时也回这个码：两人都要在 --zone 建角）";
        }
        if (tip == TIP_FRIEND_BLOCKED) {
            return "（两人之间有拉黑关系：本场景从不拉黑，说明账号被别的流程用过，换 --run-tag 重跑）";
        }
        if (tip == TIP_LOGIN_IN_PROGRESS) {
            return "（同一角色的进场在途：上一条连接的进场链还没让出，或有别的进程在用这个账号）";
        }
        return "";
    }

    /** 乱序轮次的原因（四组同一写法）。 */
    static String reorderReason(int reorderedCount, int rounds, List<Integer> reorderedRounds, String what) {
        return reorderedCount + "/" + rounds + " 轮" + what + "，乱序轮次=" + reorderedRounds
                + "。Java gate 的会话后端 lane 与 login 队列已上线，出现即为缺陷（查 gate 何时放行同会话下一条：后端回包，或发出满 5 s）";
    }

    static String okLine(long playerId, int rounds) {
        return MARKER + "_OK player_id=" + Long.toUnsignedString(playerId) + " rounds=" + rounds;
    }

    static String failLine(String step, String reason) {
        return MARKER + "_FAIL step=" + step + " reason=" + StepTrack.oneLine(reason);
    }

    /**
     * 把登记的乱序拼成一行 FAIL：step 是按执行顺序逗号分隔的乱序步骤（如 {@code O5-order,O6-order,O3-order}），reason 逐条写「步骤: 原因」。
     * 一次运行就能看到每组各自是否抓到乱序（mmorpg orderingReorderSummary）。
     */
    static String reorderLine(List<Reorder> reorders) {
        if (reorders.isEmpty()) {
            throw new IllegalArgumentException("没有登记乱序");
        }
        String steps = reorders.stream().map(Reorder::step).collect(Collectors.joining(","));
        String reasons = reorders.stream().map(r -> r.step() + ": " + r.reason()).collect(Collectors.joining("；"));
        return failLine(steps, reasons);
    }
}
