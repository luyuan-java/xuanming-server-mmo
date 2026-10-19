package com.game.inbox.rules;

import com.game.table.CommonErrorTip;
import com.game.table.PlayerInboxErrorTip;

/**
 * 收件箱写进 {@code TipInfoMessage.id} 的码（mmorpg {@code go/player_inbox/internal/constants} 的 Java 对应）。码值一律引用导表器生成的枚举，
 * 不许手写数字（两版共享的客户端契约，mmorpg AGENTS §7.5；{@code InboxTipsTest} 守住）。只引用两条码轴：
 * {@code player_inbox_error}（base 29000）与 {@code common_error} 的 {@code kInvalidParameter}。
 *
 * <p>「哪个码算服务端故障」由 Tip.xlsx 的 fault 列决定：本段只有 {@link #STORE_UNAVAILABLE} 是故障，其余都是业务拒绝。
 */
public final class InboxTips {

    /** 请求字段缺失或越界（player_id / request_id 为 0、域未知、Ack 报告乱序等）。 */
    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    /** 通用成功码（scene 侧判回包 tip：0 与它都算成功，同 C++ {@code IsTipOk}）。 */
    public static final int SUCCESS = CommonErrorTip.common_error.kSuccess_VALUE;

    /** 类型不在入箱白名单里（入箱开关的唯一来源），或写前门禁判消费端没开。 */
    public static final int DISABLED = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxDisabled_VALUE;
    /** 类型未登记或不是持久类。 */
    public static final int UNKNOWN_TYPE = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxUnknownType_VALUE;
    /** 玩家不存在（输错的 player_id 不留永远 PENDING 的行）。 */
    public static final int PLAYER_NOT_FOUND = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxPlayerNotFound_VALUE;
    /** 载荷解码失败或不通过类型校验函数；scene 执行器对解码失败的载荷记 REJECTED(它)。 */
    public static final int PAYLOAD_INVALID = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxPayloadInvalid_VALUE;
    public static final int PAYLOAD_TOO_LARGE = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxPayloadTooLarge_VALUE;
    /** operator / reason / note 超长或不是合法 UTF-8。 */
    public static final int TEXT_TOO_LONG = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxTextTooLong_VALUE;
    /** issued_at_ms 不在 [now − 7d, now + 5min] 内。 */
    public static final int ISSUED_OUT_OF_WINDOW = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxIssuedOutOfWindow_VALUE;
    /** 该 (玩家, 域) 未终结条目已达 max_pending。 */
    public static final int FULL = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxFull_VALUE;
    /** 同一 (producer_kind, request_id) 已存在、内容摘要不同（IB-2）。 */
    public static final int IDEMPOTENCY_CONFLICT = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxIdempotencyConflict_VALUE;
    public static final int STALE_OWNER = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxStaleOwner_VALUE;
    public static final int EPOCH_REQUIRED = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxEpochRequired_VALUE;
    public static final int EPOCH_AHEAD = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxEpochAhead_VALUE;
    public static final int ITEM_NOT_FOUND = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxItemNotFound_VALUE;
    /** 运维接口要改的条目已是终态，或强制作废的账本证明不成立。 */
    public static final int ITEM_STATE_CONFLICT = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxItemStateConflict_VALUE;
    /** 服务端故障：库、玩家表 / 存档或 Redis 不可用（fault = 1，只用于真故障）。 */
    public static final int STORE_UNAVAILABLE = PlayerInboxErrorTip.player_inbox_error.kPlayerInboxStoreUnavailable_VALUE;

    private InboxTips() {
    }

    /** 回包 tip 是否表示成功（0 或通用成功码）。 */
    public static boolean ok(int tip) {
        return tip == 0 || tip == SUCCESS;
    }
}
