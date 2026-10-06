package com.game.match.gather;

import com.game.api.match.MatchBudgets;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketRef;
import com.game.proto.BattleActivityContext;
import com.game.proto.match.MatchMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 交给开局管线的一次 gather（match-spec §3.1 五个入口、§9.6）。五个入口各有一个工厂方法，入口不要自己拼字段——哪种入口配哪种失败策略、带不带票据、
 * battle_id 谁发，都由工厂钉住。不可变。
 *
 * @param mode            对局模式（写进建房请求与落点记录；决定分队：5V5 重读评分后蛇形、1V1 / 切磋按下标、PVE 全员一队）
 * @param battleConfigId  {@code battle_config_id}（uint32 的位模式；不校验，原样传给 battle）
 * @param members         参战名单，<b>顺序即站位顺序</b>（决定确定性战斗的结果，客户端可见）：凑单是「锚点在前、其余按选中顺序」，切磋是
 *                        [发起者, 应战者]，整队是队长在前，活动是发起人在前。管线逐人串行备战也按这个顺序。1..10 人、非 0、不重复
 * @param tickets         每名成员的票号（{@link FailPolicy#NO_TICKETS} 时为空；否则恰好覆盖全部成员）。成功时逐张置 ready，失败时按 {@code onFail} 处置；
 *                        一律带票号做 CAS
 * @param onFail          失败时的票据处置
 * @param presetBattleId  预先发好的 battle_id（只有活动入口：号要同步回给调用方）；0 = 由管线发号
 * @param activityContext 活动上下文，原样放进建房请求；非活动入口为 null
 */
public record GatherPlan(MatchMode mode, int battleConfigId, List<Long> members, Map<Long, String> tickets,
                         FailPolicy onFail, long presetBattleId, BattleActivityContext activityContext) {

    public GatherPlan {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(onFail, "onFail");
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(tickets, "tickets");
        if (mode == MatchMode.UNRECOGNIZED || mode == MatchMode.MATCH_MODE_UNSPECIFIED) {
            throw new IllegalArgumentException("gather 的模式必须是已知的具体模式: " + mode);
        }
        members = List.copyOf(members);
        if (members.isEmpty() || members.size() > MatchBudgets.MAX_GATHER_PLAYERS) {
            throw new IllegalArgumentException("gather 人数必须在 [1, " + MatchBudgets.MAX_GATHER_PLAYERS + "] 内: " + members.size());
        }
        Set<Long> seen = new HashSet<>();
        for (Long member : members) {
            if (member == 0 || !seen.add(member)) {
                throw new IllegalArgumentException("gather 名单含 0 或重复的玩家号: " + members);
            }
        }
        // 按名单顺序存，遍历票据时顺序确定
        Map<Long, String> ordered = new LinkedHashMap<>();
        if (onFail == FailPolicy.NO_TICKETS) {
            if (!tickets.isEmpty()) {
                throw new IllegalArgumentException("不带票据的 gather 不得给票号");
            }
        } else {
            if (tickets.size() != members.size()) {
                throw new IllegalArgumentException("票号必须恰好覆盖全部成员: members=" + members + " tickets=" + tickets.keySet());
            }
            for (Long member : members) {
                String ticketId = tickets.get(member);
                if (ticketId == null || ticketId.isEmpty()) {
                    throw new IllegalArgumentException("成员缺票号: " + Long.toUnsignedString(member));
                }
                ordered.put(member, ticketId);
            }
        }
        tickets = Collections.unmodifiableMap(ordered);
        if (onFail == FailPolicy.REQUEUE_SURVIVORS && !(mode == MatchMode.MATCH_MODE_1V1 || mode == MatchMode.MATCH_MODE_5V5
                || mode == MatchMode.MATCH_MODE_PVE_TEAM)) {
            throw new IllegalArgumentException("只有走队列的模式才能回队首: " + mode);
        }
    }

    // ---------------------------------------------------------------- 五个入口

    /**
     * 凑单弹组（1V1 / 5V5 / PVE_TEAM）：失败时肇事者删票、幸存者回 {@code queue} 的队首。
     *
     * @param membersInOrder 锚点在前，其余按选中顺序
     * @param tickets        弹组时核对过的票号
     * @throws IllegalArgumentException {@code queue.mode()} 不是走队列的模式
     */
    public static GatherPlan popped(QueueRef queue, List<Long> membersInOrder, Map<Long, String> tickets) {
        MatchMode mode = MatchMode.forNumber(queue.mode());
        if (mode == null) {
            throw new IllegalArgumentException("队列的模式不在契约里: " + queue);
        }
        return new GatherPlan(mode, queue.configId(), membersInOrder, tickets, FailPolicy.REQUEUE_SURVIVORS, 0, null);
    }

    /** PVE_SOLO：票据建成即 matched，失败只删票（不推送任何东西，客户端查状态看到未排队）。 */
    public static GatherPlan soloPve(int battleConfigId, long playerId, String ticketId) {
        return new GatherPlan(MatchMode.MATCH_MODE_PVE_SOLO, battleConfigId, List.of(playerId), Map.of(playerId, ticketId),
                FailPolicy.DELETE_ALL, 0, null);
    }

    /** 切磋：名单 [发起者, 应战者]，不带票据（也不检查两人有没有别的排队票，照搬基线）。 */
    public static GatherPlan challenge(int battleConfigId, long challengerId, long responderId) {
        return new GatherPlan(MatchMode.MATCH_MODE_PVP_CHALLENGE, battleConfigId, List.of(challengerId, responderId), Map.of(),
                FailPolicy.NO_TICKETS, 0, null);
    }

    /** 整队开战：名单原序（队长在前），失败全员删票。 */
    public static GatherPlan team(int battleConfigId, List<Long> roster, Map<Long, String> tickets) {
        return new GatherPlan(MatchMode.MATCH_MODE_PVE_TEAM, battleConfigId, roster, tickets, FailPolicy.DELETE_ALL, 0, null);
    }

    /**
     * 帮会活动开战：battle_id 已预先发好并同步回给了调用方；失败全员删票，这个号不会产生结果事件。
     *
     * @param presetBattleId  非 0
     * @param activityContext 非 null（protobuf 消息不可变，原样透传即等价于基线的深拷贝）
     */
    public static GatherPlan activity(int battleConfigId, List<Long> roster, Map<Long, String> tickets, long presetBattleId,
                                      BattleActivityContext activityContext) {
        if (presetBattleId == 0) {
            throw new IllegalArgumentException("活动开战必须预先发号");
        }
        Objects.requireNonNull(activityContext, "activityContext");
        return new GatherPlan(MatchMode.MATCH_MODE_PVE_TEAM, battleConfigId, roster, tickets, FailPolicy.DELETE_ALL, presetBattleId,
                activityContext);
    }

    // ---------------------------------------------------------------- 管线用的小工具

    /** 全员的票据引用，按名单顺序；不带票据的 gather 为空表。 */
    public List<TicketRef> ticketRefs() {
        List<TicketRef> refs = new ArrayList<>(tickets.size());
        tickets.forEach((playerId, ticketId) -> refs.add(new TicketRef(playerId, ticketId)));
        return refs;
    }

    /** 回队首用的队列（模式数值 + 副本号）。只对 {@link FailPolicy#REQUEUE_SURVIVORS} 有意义。 */
    public QueueRef queue() {
        return new QueueRef(mode.getNumber(), battleConfigId);
    }
}
