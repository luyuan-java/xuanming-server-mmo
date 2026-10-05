package com.game.battle.edge;

import com.game.common.token.BattleTickets;
import java.util.Locale;

/**
 * 直连面拒绝 / 断开的原因（采样日志的 reason；基线 {@code edge.cpp:32-49} 的 {@code LogRejectionSampled} 与 battle-node-spec §7.4）。
 * 有界枚举：取值全部来自进程内常量，不含攻击者输入，按原因分别计数不会被打爆。
 *
 * <p>与指标的关系：这里只管采样日志；计数器在 {@code BattleMetrics}（握手结局 / 请求结局 / 非法帧 / 主动断开）里另计。
 */
enum EdgeRejectReason {

    // ---------------------------------------------------------------- 握手之前
    /** 并发连接数已满（闸 G1）。 */
    AT_CAPACITY,
    /** 握手期限内没完成握手（闸 G3）。 */
    HANDSHAKE_TIMEOUT,
    /** 握手前发 {@code ClientRequest}（闸 G4）。 */
    REQUEST_BEFORE_VERIFY,
    /** 类型名合法但直连面不收（例：把大厅的 {@code ClientTokenVerifyRequest} 发错了连接；闸 G4′，§11 N9 解码器立即关）。 */
    UNKNOWN_FRAME,
    /** 其余解码失败：长度 / 校验和 / 类型名长度 / 消息体解析（解码器清空缓冲、立即关，不回包）。 */
    INVALID_FRAME,

    // ---------------------------------------------------------------- 握手（§3.4）
    /** 第 2 步：签名不符。 */
    TICKET_HMAC_MISMATCH,
    /** 第 3 步：payload 解析失败。 */
    TICKET_PAYLOAD_PARSE_FAILED,
    /** 第 4 步 ①：battle_id 或 player_id 为 0。 */
    EMPTY_IDENTITY,
    /** 第 4 步 ②：节点号不是本节点。 */
    NODE_MISMATCH,
    /** 第 4 步 ③：实例 id 不等或任一侧为空。 */
    INSTANCE_MISMATCH,
    /** 第 4 步 ④：{@code expire_at_ms ≤ now}。 */
    EXPIRED,
    /** 第 4 步 ⑤：role 不是 1 / 2。 */
    ROLE_INVALID,
    /** 第 5 步：房间不存在，或该玩家不在票上角色对应的名单上。 */
    TICKET_NOT_IN_ROSTER,

    // ---------------------------------------------------------------- 已验证之后（§3.5、§3.6）
    /** 整条 {@code ClientRequest} 超过 1024 B（信封 1010）。 */
    OVERSIZED,
    /** 按消息号限频（信封 1008）。 */
    RATE_LIMITED,
    /** 消息号不在四条上行白名单里（信封 1005）。 */
    MESSAGE_ID_NOT_ALLOWED,
    /** 请求体解析失败（信封 1005）。 */
    BODY_PARSE_FAILED,
    /** 非法包累计达阈值，断开。 */
    ILLEGAL_THRESHOLD,
    /** 输出缓冲越过高水位（2 MiB），断开。 */
    WRITE_BUFFER_FULL;

    /** 日志里的原因名（小写下划线，同基线 reason 串）。 */
    String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 字段判定失败对应的原因（名字与 {@link BattleTickets.Verdict#wireName()} 相同）。
     *
     * @throws IllegalArgumentException 对 {@link BattleTickets.Verdict#OK} 调用
     */
    static EdgeRejectReason of(BattleTickets.Verdict verdict) {
        return switch (verdict) {
            case EMPTY_IDENTITY -> EMPTY_IDENTITY;
            case NODE_MISMATCH -> NODE_MISMATCH;
            case INSTANCE_MISMATCH -> INSTANCE_MISMATCH;
            case EXPIRED -> EXPIRED;
            case ROLE_INVALID -> ROLE_INVALID;
            case OK -> throw new IllegalArgumentException("OK 不是拒绝原因");
        };
    }
}
