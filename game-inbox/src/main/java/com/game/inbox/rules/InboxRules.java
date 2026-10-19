package com.game.inbox.rules;

import com.game.proto.player_inbox.PlayerInboxAckResult;
import com.game.proto.player_inbox.PlayerInboxDecisionReport;
import com.game.proto.player_inbox.PlayerInboxDirective;
import com.game.proto.player_inbox.PlayerInboxItem;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.game.proto.player_inbox.PlayerInboxOutcome;
import com.game.proto.player_inbox.player_inbox_item_row;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;

/**
 * 存储层的纯判定（mmorpg {@code go/player_inbox/internal/inbox/decide.go} 与 {@code seq.go}；设计 §4.3、§6.2、§7.8、§7.9）：不碰库、不碰时钟，
 * 单测直接对着设计表断言。{@code InboxStore} 只负责读写与事务，读出之后「怎么判、写回什么」一律在这里。
 * 所有 seq / epoch / request_id / 毫秒时刻都是 uint64，放在 long 里按无符号比较。线程安全（纯函数）。
 */
public final class InboxRules {

    /** operator / gm_operator 的字节上限（UTF-8 编码后）。 */
    public static final int MAX_OPERATOR_BYTES = 64;
    /** reason / note / 欠款载荷 reason 的字节上限。 */
    public static final int MAX_REASON_BYTES = 256;
    /** 任何类型规格的 max_payload_bytes 都不得超过它（注册表自检）。 */
    public static final int MAX_PAYLOAD_BYTES_HARD_CAP = 16 * 1024;
    /** issued_at_ms 的可接受窗口 [now − 7d, now + 5min]。终态行保留期必须大于它（配置校验守住）。 */
    public static final Duration ISSUED_AT_MAX_PAST = Duration.ofDays(7);
    public static final Duration ISSUED_AT_MAX_FUTURE = Duration.ofMinutes(5);
    /** Fetch 的 max_items：0 取 16，上限 16（消费方「环装得下」的论证依赖它，改它要连同 32 条环一起改）。 */
    public static final int FETCH_DEFAULT_ITEMS = 16;
    public static final int FETCH_MAX_ITEMS = 16;
    /** 一次 Ack 至多 32 条决定（= 消费方最近结局环的容量）。 */
    public static final int ACK_MAX_DECISIONS = 32;
    /** 运维 ListItems 的分页。 */
    public static final int LIST_DEFAULT_LIMIT = 50;
    public static final int LIST_MAX_LIMIT = 200;
    /** 同一条目被投递达到这么多次时记 ERROR（疑似毒消息，运维考虑 VoidItem(QUARANTINED)）。 */
    public static final int DELIVERY_WARN_THRESHOLD = 5;

    /** seq 的时间下界 = 当前毫秒左移 12 位（每毫秒 4096 个号，远大于 max_pending）。 */
    static final int SEQ_CLOCK_SHIFT = 12;
    /** 左移之后仍不越过 2^63 的最大毫秒值；超过它只可能是时钟坏了。 */
    static final long MAX_SEQ_CLOCK_MS = 1L << (63 - SEQ_CLOCK_SHIFT);

    private InboxRules() {
    }

    // ================================================================ 入箱

    /**
     * 内容摘要 sha256(msg_type ‖ issued_at_ms ‖ payload)（IB-2）：整数按 big-endian 定长编码（msg_type 4 字节、issued_at_ms 8 字节），
     * 拼接无歧义；与 mmorpg Go {@code ContentSHA256} 逐字节相同。
     */
    public static byte[] contentSha256(PlayerInboxMessageType type, long issuedAtMs, byte[] payload) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(ByteBuffer.allocate(12).putInt(type.getNumber()).putLong(issuedAtMs).array());
            sha.update(payload);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 没有 SHA-256", e);
        }
    }

    /** 已存在的幂等键：同摘要 = DUPLICATE（true），不同 = CONFLICT（false）。存量摘要不是 32 字节也按冲突（fail-closed）。 */
    public static boolean sameContent(ByteString stored, byte[] requested) {
        return stored.size() == 32 && stored.equals(ByteString.copyFrom(requested));
    }

    /** issued_at_ms ∈ [now − 7d, now + 5min]（设计 §6.2 第 5 步）。now 早于 7 天（时钟极端）时只判上界。 */
    public static boolean issuedWithinWindow(long issuedAtMs, long nowMs) {
        long past = ISSUED_AT_MAX_PAST.toMillis();
        long future = ISSUED_AT_MAX_FUTURE.toMillis();
        if (Long.compareUnsigned(issuedAtMs, nowMs + future) > 0) {
            return false;
        }
        return Long.compareUnsigned(nowMs, past) < 0 || Long.compareUnsigned(issuedAtMs, nowMs - past) >= 0;
    }

    /**
     * 流行锁下分配 seq：max(next_seq, now_ms &lt;&lt; 12, 1)（IB-6）。同一条流内的单调性由 next_seq 保证（调用方随后写回 next_seq = seq + 1），
     * 不受时钟回拨影响；时间下界让收件箱库按备份恢复之后新号也不会落回恢复前已分配的号段。
     *
     * @throws IllegalStateException 时钟不可用（0 或大得离谱）：调用方按故障处理，不入箱
     */
    public static long nextSeq(long nextSeq, long nowMs) {
        if (nowMs <= 0 || nowMs >= MAX_SEQ_CLOCK_MS) {
            throw new IllegalStateException("时钟不可用于 seq 分配 now_ms=" + nowMs);
        }
        long floor = nowMs << SEQ_CLOCK_SHIFT;
        long seq = Long.compareUnsigned(nextSeq, floor) > 0 ? nextSeq : floor;
        return Long.compareUnsigned(seq, 1) > 0 ? seq : 1;
    }

    /** CursorAhead：消费方水位 ≥ next_seq 说明收件箱库被恢复过。 */
    public static boolean cursorAhead(long nextSeq, long consumerDecidedThrough) {
        return Long.compareUnsigned(consumerDecidedThrough, nextSeq) >= 0;
    }

    /** CursorAhead 时把 next_seq 抬到水位 + 1（只升不降）；不领先时原样返回。 */
    public static long raiseNextSeq(long nextSeq, long consumerDecidedThrough) {
        return cursorAhead(nextSeq, consumerDecidedThrough) ? consumerDecidedThrough + 1 : nextSeq;
    }

    /**
     * 自由文本字段：必须是合法 UTF-8 且不超过 maxBytes 字节；required 时不得为空。库里的 string 列建成 MEDIUMTEXT、长度无约束，
     * 所以必须在这里显式检查（设计 §3.4）。Java 的 String 来自 protobuf 解码，非法 UTF-8 已被替换成 U+FFFD，这里按编码后的字节数判长度、
     * 并拒绝含孤立代理项的串（编码不出合法 UTF-8）。
     *
     * @return 0 = 通过；空 → kInvalidParameter；超长 / 非法 → kPlayerInboxTextTooLong
     */
    public static int validateText(String text, int maxBytes, boolean required) {
        if (text == null || text.isEmpty()) {
            return required ? InboxTips.INVALID_PARAMETER : 0;
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
            return encoded.remaining() > maxBytes ? InboxTips.TEXT_TOO_LONG : 0;
        } catch (CharacterCodingException e) {
            return InboxTips.TEXT_TOO_LONG;
        }
    }

    // ================================================================ Fetch / Ack

    /** Fetch 的 max_items / List 的 limit 归一到 [1, max]：0 取默认值。 */
    public static int clampItems(int requested, int defaultValue, int max) {
        if (requested == 0) {
            return defaultValue;
        }
        return Integer.compareUnsigned(requested, max) > 0 ? max : requested;
    }

    /** 把一行折成 Fetch 回包条目。directive ≠ NONE 时<b>不带载荷</b>：作废路径不解码载荷。delivery_count 含本次。 */
    public static PlayerInboxItem deliveredItem(player_inbox_item_row row) {
        PlayerInboxItem.Builder item = PlayerInboxItem.newBuilder()
                .setSeq(row.getSeq())
                .setMsgTypeValue(row.getMsgType())
                .setDirectiveValue(row.getDirective())
                .setVoidReasonValue(row.getVoidReason())
                .setRequestId(row.getRequestId())
                .setDeliveryCount(row.getDeliveryCount() + 1)
                .setCreatedMs(row.getCreatedMs());
        if (row.getDirective() == PlayerInboxDirective.PLAYER_INBOX_DIRECTIVE_NONE_VALUE) {
            item.setPayload(row.getPayload());
        }
        return item.build();
    }

    /** 报告列表：1..32 条、seq 非 0 且严格升序、结局已知。 */
    public static boolean validAckReports(List<PlayerInboxDecisionReport> reports) {
        if (reports.isEmpty() || reports.size() > ACK_MAX_DECISIONS) {
            return false;
        }
        long previous = 0;
        for (PlayerInboxDecisionReport report : reports) {
            if (report.getSeq() == 0 || Long.compareUnsigned(report.getSeq(), previous) <= 0) {
                return false;
            }
            if (outcomeToState(report.getOutcomeValue()) == PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED) {
                return false;
            }
            previous = report.getSeq();
        }
        return true;
    }

    /** 消费方报告的结局 → 终态。非法结局返回 UNSPECIFIED。 */
    public static PlayerInboxItemState outcomeToState(int outcome) {
        return switch (outcome) {
            case PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED_VALUE -> PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED;
            case PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_REJECTED_VALUE -> PlayerInboxItemState.PLAYER_INBOX_ITEM_REJECTED;
            case PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_SKIPPED_VALUE -> PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED;
            case PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_UNKNOWN_VALUE -> PlayerInboxItemState.PLAYER_INBOX_ITEM_SETTLED_UNKNOWN;
            default -> PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED;
        };
    }

    /** 是否终态（只有 PENDING 不是）。 */
    public static boolean terminal(int state) {
        return state == PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED_VALUE
                || state == PlayerInboxItemState.PLAYER_INBOX_ITEM_REJECTED_VALUE
                || state == PlayerInboxItemState.PLAYER_INBOX_ITEM_VOIDED_VALUE
                || state == PlayerInboxItemState.PLAYER_INBOX_ITEM_SETTLED_UNKNOWN_VALUE;
    }

    /**
     * 「终态与一条结局一致」的唯一定义：结局映射后的终态与 state 相同；REJECTED 另要求原因码相同。Ack 的 ALREADY_SAME 与对账的环内核对共用它。
     *
     * @param stateReason 行上的 outcome_reason
     */
    public static boolean sameOutcome(int state, int stateReason, int outcome, int reason) {
        PlayerInboxItemState want = outcomeToState(outcome);
        if (want == PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED || state != want.getNumber()) {
            return false;
        }
        return want != PlayerInboxItemState.PLAYER_INBOX_ITEM_REJECTED || stateReason == reason;
    }

    /**
     * 单条 Ack 报告的判定结果。
     *
     * @param finalizes 为真时把行从 PENDING 改成 {@code state}（写 finalized_by = CONSUMER、decided_epoch、outcome_reason）
     * @param state    处理后的行状态（回给消费方）；行不存在时为 UNSPECIFIED
     */
    public record AckDecision(PlayerInboxAckResult result, boolean finalizes, PlayerInboxItemState state) {
    }

    /**
     * 按设计 §7.8 的 Ack 表判定一条报告（row = null 表示行不存在；报告本身的合法性由调用方先校验）：
     * <pre>
     * | 行的现状                    | 报告                                    | 结果                          |
     * | 不存在                      | 任意                                    | UNKNOWN_SEQ（告警，不改）      |
     * | request_id 与报告不一致     | 任意                                    | MISMATCH（告警，不改；IB-9）   |
     * | PENDING 且 delivery_count=0 | 任意                                    | NOT_DELIVERED（告警，不改）    |
     * | PENDING，没有指令            | SKIPPED                                 | SKIP_NOT_REQUESTED（告警）     |
     * | PENDING                     | APPLIED / REJECTED / 有指令时的 SKIPPED / UNKNOWN | 改成对应终态，FINALIZED |
     * | 终态，与报告一致             | —                                       | ALREADY_SAME                  |
     * | 终态，与报告不一致           | —                                       | CONFLICT（告警，永不翻转；IB-4）|
     * </pre>
     * 挂了作废指令、消费方却报 APPLIED：照收（管理员的取消晚了一步，以消费方已落盘的事实为准）。
     */
    public static AckDecision decideAck(player_inbox_item_row row, PlayerInboxDecisionReport report) {
        if (row == null) {
            return new AckDecision(PlayerInboxAckResult.PLAYER_INBOX_ACK_UNKNOWN_SEQ, false,
                    PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED);
        }
        PlayerInboxItemState state = stateOf(row.getState());
        if (row.getRequestId() != report.getRequestId()) {
            return new AckDecision(PlayerInboxAckResult.PLAYER_INBOX_ACK_MISMATCH, false, state);
        }
        if (state == PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING) {
            if (row.getDeliveryCount() == 0) {
                return new AckDecision(PlayerInboxAckResult.PLAYER_INBOX_ACK_NOT_DELIVERED, false, state);
            }
            if (report.getOutcomeValue() == PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_SKIPPED_VALUE
                    && row.getDirective() != PlayerInboxDirective.PLAYER_INBOX_DIRECTIVE_VOID_VALUE) {
                return new AckDecision(PlayerInboxAckResult.PLAYER_INBOX_ACK_SKIP_NOT_REQUESTED, false, state);
            }
            PlayerInboxItemState finalState = outcomeToState(report.getOutcomeValue());
            return new AckDecision(PlayerInboxAckResult.PLAYER_INBOX_ACK_FINALIZED, true, finalState);
        }
        if (sameOutcome(row.getState(), row.getOutcomeReason(), report.getOutcomeValue(), report.getReason())) {
            return new AckDecision(PlayerInboxAckResult.PLAYER_INBOX_ACK_ALREADY_SAME, false, state);
        }
        return new AckDecision(PlayerInboxAckResult.PLAYER_INBOX_ACK_CONFLICT, false, state);
    }

    /** 终结时写进 outcome_reason：REJECTED 写消费方给的 tip id；SKIPPED 写消费方回报的作废原因；其余为 0。 */
    public static int ackOutcomeReason(PlayerInboxDecisionReport report) {
        int outcome = report.getOutcomeValue();
        return outcome == PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_REJECTED_VALUE
                || outcome == PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_SKIPPED_VALUE ? report.getReason() : 0;
    }

    /** 一条 Ack 结果是不是协议异常（消费方会停用这个域，服务端记 ERROR）：FINALIZED 与 ALREADY_SAME 之外的一切。 */
    public static boolean ackAnomaly(PlayerInboxAckResult result) {
        return result != PlayerInboxAckResult.PLAYER_INBOX_ACK_FINALIZED && result != PlayerInboxAckResult.PLAYER_INBOX_ACK_ALREADY_SAME;
    }

    /** 以 UNKNOWN 收尾为 SETTLED_UNKNOWN 的 FINALIZED 单列 label（warning 告警按它取），其余取结果名。 */
    public static String ackMetricLabel(PlayerInboxAckResult result, PlayerInboxItemState state) {
        if (result == PlayerInboxAckResult.PLAYER_INBOX_ACK_FINALIZED && state == PlayerInboxItemState.PLAYER_INBOX_ITEM_SETTLED_UNKNOWN) {
            return "finalized_settled_unknown";
        }
        return InboxLabels.ackResult(result);
    }

    /** open_count 减去刚终结的条数；小于终结数只可能是缺陷：钳到 0（调用方大声记录，不让事务失败）。 */
    public static int subtractOpenCount(int openCount, int finalized) {
        return Integer.compareUnsigned(finalized, openCount) > 0 ? 0 : openCount - finalized;
    }

    // ================================================================ 作废

    /** 运维作废一条条目的动作（设计 §7.9、IB-5）。 */
    public enum VoidAction {
        /** 条目不存在 → kPlayerInboxItemNotFound。 */
        NOT_FOUND,
        /** 已是终态 → kPlayerInboxItemStateConflict。 */
        TERMINAL,
        /** 从未投递 → 在流行锁下直接改 VOIDED（finalized_by = ADMIN），载荷从未离开过收件箱。 */
        DIRECT,
        /** 已投递 → 只挂 directive = VOID，由消费方裁决。 */
        DIRECTIVE,
        /** 已投递且已挂过 VOID 指令 → 幂等成功，不改行。 */
        ALREADY_REQUESTED,
        /** 强制模式下账本证明成立 → 在流行锁下改 VOIDED（finalized_by = ADMIN）。 */
        FORCED,
        /** 强制模式下行仍 PENDING、但账本证明不成立 → kPlayerInboxItemStateConflict，不改行。 */
        FORCE_REFUSED
    }

    /** 普通模式的作废判定。 */
    public static VoidAction decideVoid(player_inbox_item_row row) {
        if (row == null) {
            return VoidAction.NOT_FOUND;
        }
        if (row.getState() != PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING_VALUE) {
            return VoidAction.TERMINAL;
        }
        if (row.getDeliveryCount() == 0) {
            return VoidAction.DIRECT;
        }
        return row.getDirective() == PlayerInboxDirective.PLAYER_INBOX_DIRECTIVE_VOID_VALUE ? VoidAction.ALREADY_REQUESTED
                : VoidAction.DIRECTIVE;
    }

    /** 作废动作在回包上的 tip（0 = 成功）。 */
    public static int voidTip(VoidAction action) {
        return switch (action) {
            case NOT_FOUND -> InboxTips.ITEM_NOT_FOUND;
            case TERMINAL, FORCE_REFUSED -> InboxTips.ITEM_STATE_CONFLICT;
            default -> 0;
        };
    }

    /** 作废动作在 {@code game_inbox_void_total{path}} 上的路径；null = 不记（未改动任何东西的拒绝）。 */
    public static String voidPath(VoidAction action) {
        return switch (action) {
            case DIRECT -> "direct";
            case DIRECTIVE -> "directive";
            case ALREADY_REQUESTED -> "already";
            case FORCED -> "force";
            default -> null;
        };
    }

    /** 枚举值 → 状态；不认识的值按 UNRECOGNIZED。 */
    public static PlayerInboxItemState stateOf(int value) {
        PlayerInboxItemState state = PlayerInboxItemState.forNumber(value);
        return state == null ? PlayerInboxItemState.UNRECOGNIZED : state;
    }
}
