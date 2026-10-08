package com.game.match.gather;

import com.game.match.proto.BattlePlacement;
import java.util.List;

/**
 * 开局管线留给观战（批次 6.5）的两个接缝（match-spec §0.3、§9.6 第 2.5 步与第 5 步；spectate-spec §4.6）。bean 由观战包提供
 * （{@code spectate.WatchableConfiguration}）：开局前清退正在观战的参战者、开局后把这一场登记进可观战索引；6.4 期间是 {@link #NOOP}。
 *
 * <p><b>契约</b>：两个方法都在 gather 的<b>虚拟线程</b>上被同步调用，可以阻塞（{@link #beforePrepare} 每名成员至多 3 s，这一项已经算在
 * matched TTL 的公式里），但不得在 {@code synchronized} 块里阻塞。实现<b>不得抛异常</b>——管线仍会把抛出的异常吞掉并记日志，
 * 钩子的任何失败都不影响开局。
 */
public interface GatherHooks {

    /** 什么都不做（6.4：还没有观众）。 */
    GatherHooks NOOP = new GatherHooks() {
        @Override
        public void beforePrepare(List<Long> members) {
        }

        @Override
        public void onStarted(BattlePlacement placement) {
        }
    };

    /**
     * 在第一次备战之前调用（选好 battle 节点、算好期限之后）：五个入口都经过这一步。gather 随后失败时，这里做过的事不回滚。
     *
     * @param members 参战名单（{@link GatherPlan#members()}，同一个不可变列表）
     */
    void beforePrepare(List<Long> members);

    /**
     * 开局成功之后调用：全员票据已置 ready、落点记录已按同一个 attempt 补写。<b>失败路径不调它</b>。
     *
     * @param placement 这一局最终的落点记录（与 Redis 里的逐字节相同）
     */
    void onStarted(BattlePlacement placement);
}
