package com.game.match.placement;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;

/**
 * 战斗落点记录的存取（Redis {@code RedisKeys.matchBattlePlacement(battle_id)}，HASH：{@code a} = attempt、{@code pb} = {@link BattlePlacement} 字节，
 * TTL {@code MatchBudgets.PLACEMENT_TTL_SECONDS}；match-spec §4.2、§4.3）。写者只有 gather；读者是 179 补签与 6.5 观战。
 *
 * <p>生命周期：建房<b>之前</b>写入（写不进去就不建房）→ 换节点重试时改写（attempt = 2）→ 成功后同值补写 → 确认房间没建成时在补偿之后删除 →
 * 只有「建房结局不明且回滚也失败」时保留。
 *
 * <p>线程：三个方法都阻塞，在 gather 的虚拟线程 / 工作线程上调（实现不得在 {@code synchronized} 块里阻塞）；线程安全。
 */
public interface PlacementStore {

    /**
     * 单调写：只收 {@code placement.attempt} ≥ 已存值的写（同值覆盖，更小的丢弃），并把 TTL 刷成 360 s。迟到落盘的旧写盖不掉换节点之后的新写。
     * 内部是<b>一次</b> Redis 调用，外层以 {@code MatchBudgets.PLACEMENT_WRITE_WORST_MS}（6.1 s）为截止——调用方不要再自己重试（会与 Redis 客户端的
     * 内部重发叠加，撑破 matched TTL 的预算）。可重放：同一条记录写两次结果相同。
     *
     * @param placement {@code battle_id ≠ 0}、{@code attempt ≥ 1}
     * @return true = 记录现在存在且 attempt ≥ 本次的（本次写入，或已有更新的写）；false = 写失败或结局不明（Redis 出错 / 超时，已记日志）——
     *         <b>永不抛异常</b>。false 时 gather 不建房（outcome {@code index_failed}）；成功后的补写返回 false 只记日志
     */
    boolean write(BattlePlacement placement);

    /**
     * 无条件删除，尽力而为：失败只记日志，<b>永不抛异常</b>。删了之后被迟到的写「复活」也无害——补签会直拨到 battle、由 battle 回「房间不存在」，
     * 记录随 TTL 自清。
     */
    void delete(long battleId);

    /** 一次读的结果（三选一，调用方穷举）。 */
    sealed interface Read {

        /** 记录存在且解析成功。 */
        record Found(BattlePlacement placement) implements Read {
        }

        /** 记录不存在（没开过这一局、已删除或 TTL 已过）：179 回 1005「该战斗不存在或已结束」。 */
        record Absent() implements Read {
        }

        /** 读失败、超出 {@code d}、或记录损坏（缺字段 / 解析失败 / battle_id 与键不符）：179 回 16004「服务器繁忙」。{@code why} 只进日志。 */
        record Failed(String why) implements Read {
        }
    }

    /** 读一条落点记录，至多等到 {@code d}。<b>永不抛异常</b>：故障走 {@link Read.Failed}，不折成「不存在」（那会让客户端永久放弃一场还在的战斗）。 */
    Read read(long battleId, Deadline d);
}
