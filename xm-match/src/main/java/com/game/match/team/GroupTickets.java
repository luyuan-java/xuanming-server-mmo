package com.game.match.team;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.support.MatchModes;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketStore;
import com.game.match.ticket.TicketStore.GroupMember;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 点名开局的整组建票与回滚（match-spec §7.2「建票」、§7.5；基线 {@code tb.go:92-150}、{@code act.go:265-322}）：整队开战与帮会活动开战共用。
 * 两个入口建的都是 PVE_TEAM 的 matched 票（不入队，TTL = matched TTL(人数)），区别只在票号谁生成、带不带 team_id，以及各自怎么把结局翻译给调用方。
 *
 * <p>建票是存储的一个原子操作（{@link TicketStore#createGroup}）：有人已有别的票就什么都不写。所以基线「逐人建、建到一半回滚」的中间态不存在，
 * 只剩一种要回滚的情形——<b>建票调用本身出错（结局不明）</b>：脚本可能已经执行。这时用<b>独立的</b> 3 s 预算
 * （不受请求截止约束：请求预算往往正是因为这次调用才用完的）按本次的票号逐个删。删不掉的票靠 matched TTL 过期。
 *
 * <p>阻塞（在工作线程上调）；无可变状态，线程安全。
 */
public final class GroupTickets {

    private static final Logger log = LoggerFactory.getLogger(GroupTickets.class);

    /** 整组建票的结局（三选一，调用方穷举）。 */
    public sealed interface Outcome {

        /** 全员建成（或同一批票号的重放）。 */
        record Created() implements Outcome {
        }

        /** 名单序第一个已有<b>别的</b>票据的人；什么都没写。 */
        record Conflict(long playerId) implements Outcome {
        }

        /** 建票调用出错（结局不明），已按本次票号回滚过一遍。 */
        record RolledBack() implements Outcome {
        }
    }

    private final TicketStore tickets;

    public GroupTickets(TicketStore tickets) {
        this.tickets = Objects.requireNonNull(tickets, "tickets");
    }

    /**
     * 给名单里的每个人各建一张 matched 票。
     *
     * @param members 名单顺序（决定「第一个冲突者」）；1..{@value MatchBudgets#MAX_TEAM_SIZE} 人，调用方已校验非 0、不重复
     * @param teamId  整队开战写队伍号；活动开战传 0
     * @param what    日志里的入口名（「整队开战」/「活动开战」）
     */
    public Outcome create(int battleConfigId, long teamId, List<GroupMember> members, Deadline d, String what) {
        long ttlMs = MatchBudgets.matchedTicketTtlSeconds(members.size()) * 1000L;
        OptionalLong conflict;
        try {
            conflict = tickets.createGroup(members, MatchModes.PVE_TEAM, battleConfigId, teamId, ttlMs, d);
        } catch (Deadline.DependencyException e) {
            log.error("[match] {}建票失败（结局不明，按已写入回滚） team={} members={}", what, Long.toUnsignedString(teamId), describe(members), e);
            rollback(members, what);
            return new Outcome.RolledBack();
        }
        if (conflict.isPresent()) {
            log.info("[match] {}建票冲突：成员已有票据 team={} player={}", what, Long.toUnsignedString(teamId),
                    Long.toUnsignedString(conflict.getAsLong()));
            return new Outcome.Conflict(conflict.getAsLong());
        }
        return new Outcome.Created();
    }

    /**
     * 按本次的票号逐个删票（票号一致才删，别人的票不动），用独立的 {@value MatchBudgets#TICKET_ROLLBACK_BUDGET_MS} ms 预算；
     * 单张失败只记日志、接着删下一张。幂等。
     */
    public void rollback(List<GroupMember> members, String what) {
        Deadline budget = Deadline.after(MatchBudgets.TICKET_ROLLBACK_BUDGET_MS);
        for (GroupMember member : members) {
            try {
                boolean deleted = tickets.delete(new TicketRef(member.playerId(), member.ticketId()), budget);
                if (!deleted) {
                    log.info("[match] {}回滚：票据不是本次写入或已不存在 player={}", what, Long.toUnsignedString(member.playerId()));
                }
            } catch (Deadline.DependencyException e) {
                log.error("[match] {}回滚删票失败（靠 matched TTL 自愈） player={}", what, Long.toUnsignedString(member.playerId()), e);
            }
        }
    }

    private static String describe(List<GroupMember> members) {
        return members.stream().map(m -> Long.toUnsignedString(m.playerId())).toList().toString();
    }
}
