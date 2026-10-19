package com.game.inbox.rules;

import com.game.proto.player_inbox.PlayerInboxAckResult;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.game.proto.player_inbox.PlayerInboxVoidReason;
import java.util.Locale;

/**
 * 指标 label 与日志用的低基数名字（mmorpg {@code registry.go} 的 {@code DomainLabel} / {@code TypeLabel} / {@code AckResultLabel} /
 * {@code VoidReasonLabel}，取值逐字相同）：枚举名去掉前缀再转小写；未登记 / UNSPECIFIED / 不认识的值一律 {@code unknown}。
 * 不含 player_id 等高基数值。
 */
public final class InboxLabels {

    private InboxLabels() {
    }

    /** scene_gm_sanction 等。 */
    public static String domain(PlayerInboxDomain domain) {
        if (domain == null || domain == PlayerInboxDomain.UNRECOGNIZED || domain == PlayerInboxDomain.PLAYER_INBOX_DOMAIN_UNSPECIFIED) {
            return "unknown";
        }
        return suffix(domain.name(), "PLAYER_INBOX_DOMAIN_");
    }

    /** gm_block_currency_gain 等。 */
    public static String type(PlayerInboxMessageType type) {
        if (type == null || type == PlayerInboxMessageType.UNRECOGNIZED || type == PlayerInboxMessageType.PLAYER_INBOX_MSG_UNSPECIFIED) {
            return "unknown";
        }
        return suffix(type.name(), "PLAYER_INBOX_MSG_");
    }

    /** finalized / already_same / conflict / unknown_seq / not_delivered / skip_not_requested / mismatch。 */
    public static String ackResult(PlayerInboxAckResult result) {
        if (result == null || result == PlayerInboxAckResult.UNRECOGNIZED) {
            return "unknown";
        }
        return suffix(result.name(), "PLAYER_INBOX_ACK_");
    }

    /** cancelled / quarantined。 */
    public static String voidReason(PlayerInboxVoidReason reason) {
        if (reason == null || reason == PlayerInboxVoidReason.UNRECOGNIZED) {
            return "unknown";
        }
        return suffix(reason.name(), "PLAYER_INBOX_VOID_");
    }

    static String suffix(String name, String prefix) {
        return (name.startsWith(prefix) ? name.substring(prefix.length()) : name).toLowerCase(Locale.ROOT);
    }
}
