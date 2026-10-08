package com.game.team.match;

import com.game.common.deadline.Deadline;
import com.game.team.rules.TeamTips;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * 整队开战的票据域端口（基线 go/match/internal/team/service.go:40-60 BattleStarter，team-spec §5.3；match-spec §7.5、§7.6）。
 * 与 {@code com.game.api.MatchTeamService} 的四个方法一一对应，生产实现是 {@link MatchTeamBattle}（Dubbo 客户端，调 xm-match）。
 *
 * <p>分工：编排全部在 {@code TeamService.startTeamMatch}（读记录、惰性转让、队长 / 锁校验、名单、开战锁钉版本提交与整轮重来、EndMatch、
 * 推送）；本端口只管「副本人数 + 逐成员预检」「建票 / 退票」与「gather」。端口用 team 自己的词汇说话：预检结论已经翻译成 team 段的 tip
 * （team 段 tip 只归 xm-team）。
 *
 * <p>契约：
 * <ul>
 *   <li><b>前三个方法阻塞</b>（在 {@code team-worker} / {@code team-match-end} 线程上等 xm-match 的应答），上界是 {@code deadline}；
 *       <b>从不抛异常</b>——调不通、超出预算、应答缺字段都折成各自返回值里的「故障 / 结果不明」。</li>
 *   <li>{@link #runTeamGather} <b>不阻塞</b>，返回的 stage 在 gather 结束时完成（最长约一个开战锁时长）；stage 异常完成 = 传输失败、
 *       gather 结果不明。回调在 Dubbo 的线程上触发，<b>不得阻塞</b>（服务层把收尾投到 {@code team-match-end} 执行器）。</li>
 *   <li>不重试：建票与 gather 都不幂等到可以盲目重发的程度（建票按 id 可重放，但重发会打乱请求预算）。</li>
 * </ul>
 */
public interface TeamBattlePort {

    /**
     * {@link #checkTeamMatch} 的结论。
     *
     * @param code           0 = 全员通过；否则是要回给客户端的 team 段 tip：4027 副本未开放组队 / 4028 超员 / 4024 离线 / 4025 战斗中 /
     *                       4026 未准备好 / 4030 xm-match 内部故障、调不通、超出请求预算或应答不可信
     * @param param          tip 的 parameters[0]：4024 / 4025 / 4026 时是出问题的成员；其余为 0
     * @param zones          通过时每人位置记录里的 zone（建票时原样带回；不可变）；否则为空
     * @param lockTtlSeconds 通过时的开战锁时长（秒，xm-match 按名单人数给出，范围已校验）；否则为 0
     */
    record Check(int code, long param, Map<Long, Integer> zones, int lockTtlSeconds) {

        public Check {
            zones = zones == null ? Map.of() : Map.copyOf(zones);
        }

        public static Check passed(Map<Long, Integer> zones, int lockTtlSeconds) {
            return new Check(TeamTips.OK, 0, zones, lockTtlSeconds);
        }

        public static Check rejected(int code, long param) {
            return new Check(code, param, Map.of(), 0);
        }

        public boolean ok() {
            return code == TeamTips.OK;
        }
    }

    /** {@link #createTeamTickets} 的结论。 */
    sealed interface Tickets {

        /** 全员的 matched 票已建成（或同一批 id 的重放）。 */
        record Created() implements Tickets {
        }

        /**
         * 没建成，且 xm-match 保证没有留下本次的票。
         *
         * @param playerId 出问题的成员（名单序第一个已有别的票据的人；xm-match 自己的 Redis 出错时是名单第一个人），非 0
         */
        record Failed(long playerId) implements Tickets {
        }

        /**
         * 结果不明：传输失败、超出请求预算、xm-match 回「到达时预算已过期」或应答缺字段。迟到的建票可能已经执行，
         * 调用方必须先 {@link #releaseTeamTickets} 再收尾。
         *
         * @param why 给日志看的原因
         */
        record Unknown(String why) implements Tickets {
        }
    }

    /**
     * gather 的结论（stage 正常完成时）。
     *
     * @param ok       true = 已建房（全员票据已置 ready，177 / 143 由 battle 推）；false = gather 失败（xm-match 已收尾票据）
     * @param outcome  xm-match 给的结局标签，只进日志
     * @param battleId 建成的战斗；失败为 0
     */
    record Gather(boolean ok, String outcome, long battleId) {
    }

    /**
     * 副本人数检查 + 逐成员预检（基线第 3、5 步合成一次调用，顺序不变：人数 → 按名单顺序逐人「在线 → 战斗锁 → 位置 → 票据」，
     * 第一个不满足的人即为结论）。预检整个在 xm-match 做，xm-team 这一侧不再读战斗锁——拆开做会改变「谁先被报出来」。
     *
     * @param battleConfigId DungeonTable id（uint32 位模式）
     * @param roster         参战名单：队长在前，其余按 join_seq
     * @param deadline       请求预算（每跳超时取 min(3 s, 剩余预算)，剩余预算随调用带给 xm-match）
     */
    Check checkTeamMatch(int battleConfigId, List<Long> roster, Deadline deadline);

    /**
     * 按调用方给的每人 ticket id 原子建全员的 matched 票（带 team_id、不入队）。
     *
     * @param roster    开战锁内的名单
     * @param zones     {@link Check#zones()}
     * @param ticketIds 每人一个 ticket id（覆盖 roster 的每个人）
     */
    Tickets createTeamTickets(int battleConfigId, long teamId, List<Long> roster, Map<Long, Integer> zones,
                              Map<Long, String> ticketIds, Deadline deadline);

    /**
     * 建票结果不明时的补偿：按 (player_id, ticket id) 逐个「id 一致才删」，幂等。<b>只用于</b> {@link #createTeamTickets} 回
     * {@link Tickets.Unknown}、且还没发出 {@link #runTeamGather} 的情形——gather 一旦发出，票据由 gather 自己收尾
     * （match-spec §12.1 第 19 条）。
     *
     * @return true = xm-match 已确认；false = 没调通（票据最终按 matched TTL 过期，调用方只记日志）
     */
    boolean releaseTeamTickets(Map<Long, String> ticketIds, Deadline deadline);

    /**
     * 整队 gather（名单原序）。不受调用方取消 / 超时影响：xm-match 照样把 gather 跑完。
     *
     * @param lockTtlSeconds 开战锁时长（{@link Check#lockTtlSeconds()}）：这次长挂调用的超时
     * @return gather 结束时完成；异常完成 = 传输失败（结果不明，调用方<b>不得</b>退票）
     */
    CompletionStage<Gather> runTeamGather(int battleConfigId, long teamId, List<Long> roster, Map<Long, String> ticketIds,
                                          int lockTtlSeconds);
}
