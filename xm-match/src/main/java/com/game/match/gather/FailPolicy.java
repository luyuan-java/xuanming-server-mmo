package com.game.match.gather;

/**
 * gather 失败时怎么处置参战者的排队票据（match-spec §3.1 的「失败时的票据处理」列、§3.3 补偿矩阵）。由入口决定，随 {@link GatherPlan} 交给管线。
 * 例外对三种策略都成立：结局是 {@link GatherOutcome#CREATE_FAILED_ROOM_ALIVE} 时<b>不动任何票据</b>（房间可能活着，票留在 matched 等 TTL）。
 */
public enum FailPolicy {

    /** 凑单弹组：肇事者删票，其余幸存者按原相对顺序回队首；没有肇事者时全员回队首（带退避）。 */
    REQUEUE_SURVIVORS,
    /** PVE_SOLO、整队开战、活动开战：全员按票号删票，不回队列。 */
    DELETE_ALL,
    /** 切磋：参战者没有票据（{@link GatherPlan#tickets()} 为空），不碰任何票。 */
    NO_TICKETS
}
