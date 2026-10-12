package com.game.match.ticket;

/**
 * 排队票据的状态（Redis 票据 HASH 的 {@code state} 字段；match-spec §1.3）：
 * <pre>
 * (无) ──排队──► queued(6 h) ──弹组──► matched(按人数) ──gather 成功──► ready(60 s) ──TTL──► (无)
 * PVE_SOLO / 整队开战 / 活动开战：直接建 matched，不入队
 * </pre>
 */
public enum TicketState {

    /** 在队列里等凑单。 */
    QUEUED("queued"),
    /** 已被弹出（或点名开局直接建成），gather 进行中；实例崩溃时靠短 TTL 自灭。 */
    MATCHED("matched"),
    /** gather 成功，已建房（{@code battle_id} 有效）；只留 60 s 给客户端查状态。 */
    READY("ready"),
    /** Redis 里是不认识的值（人为改数据 / 将来的新状态）：查询状态按「未排队」回，其余路径按「在途」对待、不碰它。不会被写进 Redis。 */
    UNKNOWN("");

    private final String wire;

    TicketState(String wire) {
        this.wire = wire;
    }

    /** Redis 里的取值（{@link #UNKNOWN} 为空串，不得写入）。 */
    public String wire() {
        return wire;
    }

    /** 从 Redis 的取值解析；不认识（含 null、空串）为 {@link #UNKNOWN}。 */
    public static TicketState ofWire(String wire) {
        if (wire != null) {
            for (TicketState state : values()) {
                if (state != UNKNOWN && state.wire.equals(wire)) {
                    return state;
                }
            }
        }
        return UNKNOWN;
    }
}
