package com.game.inbox.service;

import com.game.common.deadline.Deadline;
import com.game.inbox.rules.InboxLabels;
import com.game.inbox.rules.InboxRules;
import com.game.inbox.rules.InboxTips;
import com.game.inbox.store.InboxStore;
import com.game.proto.TipInfoMessage;
import com.game.proto.player_inbox.GmCurrencyDebtPayload;
import com.game.proto.player_inbox.GmSanctionPlayerRequest;
import com.game.proto.player_inbox.GmSanctionPlayerResponse;
import com.game.proto.player_inbox.PlayerInboxAuditAction;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.game.proto.player_inbox.PlayerInboxProducerKind;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GM 处置入箱（mmorpg {@code logic/gm_sanction_logic.go}，设计 §6.1）。v1 唯一的生产方。鉴权由运维接口的过滤器完成。顺序：
 * <ol>
 *   <li>字段校验：player_id / request_id / issued_at_ms 非 0，action 必选，operator（≤ 64B）与 reason（≤ 256B）必填；</li>
 *   <li>从 oneof 得到 msg_type 与载荷；attach_debt 先用服务端认定的 operator <b>覆盖</b> gm_operator，再对最终载荷做大小与解码校验
 *       （覆盖值随欠款永久进玩家存档，不能绕过上限）；</li>
 *   <li>{@link InboxStore#enqueue}：同一个事务里写审计（SANCTION：operator、对端地址、理由；内容列从条目行复制）；</li>
 *   <li>返回 seq / created / state。DUPLICATE 时 created = false，返回原 seq 与当前状态。</li>
 * </ol>
 * 载荷用确定性序列化：内容摘要包含载荷，同一请求的重试必须得到同一份字节，否则会被判成幂等冲突。业务拒绝回 tip；库 / 玩家表 / Redis 故障回
 * kPlayerInboxStoreUnavailable。线程安全。
 */
public final class GmSanctions {

    private static final Logger log = LoggerFactory.getLogger(GmSanctions.class);

    private final InboxStore store;

    public GmSanctions(InboxStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** 一次处置入箱。从不抛异常（故障折成 StoreUnavailable）。 */
    public GmSanctionPlayerResponse sanction(Deadline budget, GmSanctionPlayerRequest in, String peer) {
        if (in.getPlayerId() == 0 || in.getRequestId() == 0 || in.getIssuedAtMs() == 0) {
            return reject(InboxTips.INVALID_PARAMETER);
        }
        int text = InboxRules.validateText(in.getOperator(), InboxRules.MAX_OPERATOR_BYTES, true);
        if (text == 0) {
            text = InboxRules.validateText(in.getReason(), InboxRules.MAX_REASON_BYTES, true);
        }
        if (text != 0) {
            return reject(text);
        }
        PlayerInboxMessageType type;
        Message payload;
        switch (in.getActionCase()) {
            case BLOCK_GAIN -> {
                type = PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_BLOCK_CURRENCY_GAIN;
                payload = in.getBlockGain();
            }
            case UNBLOCK_GAIN -> {
                type = PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_UNBLOCK_CURRENCY_GAIN;
                payload = in.getUnblockGain();
            }
            case ATTACH_DEBT -> {
                type = PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_ATTACH_CURRENCY_DEBT;
                // 副本：gm_operator 用服务端认定的 operator 覆盖，不改调用方的请求对象
                payload = GmCurrencyDebtPayload.newBuilder(in.getAttachDebt()).setGmOperator(in.getOperator()).build();
            }
            default -> {
                return reject(InboxTips.INVALID_PARAMETER);
            }
        }
        byte[] bytes;
        try {
            bytes = deterministic(payload);
        } catch (IOException e) {
            return reject(InboxTips.PAYLOAD_INVALID);
        }
        InboxStore.EnqueueResult result;
        try {
            // 条目的 note 存处置理由：ListItems / LookupByRequest 不必再翻审计表就能看到「为什么」
            result = store.enqueue(budget, new InboxStore.EnqueueParams(in.getPlayerId(), type, PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_GM,
                    in.getRequestId(), in.getIssuedAtMs(), bytes, in.getOperator(), in.getReason(),
                    new InboxStore.Audit(PlayerInboxAuditAction.PLAYER_INBOX_AUDIT_SANCTION, in.getOperator(), peer, in.getReason(), null)));
        } catch (SQLException | RuntimeException e) {
            log.error("[PlayerInbox] GmSanctionPlayer 失败 player={} request_id={} type={} operator={} peer={}", Long.toUnsignedString(in.getPlayerId()),
                    Long.toUnsignedString(in.getRequestId()), InboxLabels.type(type), in.getOperator(), peer, e);
            return reject(InboxTips.STORE_UNAVAILABLE);
        }
        if (result.tip() != 0) {
            log.info("[PlayerInbox] GmSanctionPlayer 拒绝 tip={} player={} request_id={} type={} operator={} peer={}", result.tip(),
                    Long.toUnsignedString(in.getPlayerId()), Long.toUnsignedString(in.getRequestId()), InboxLabels.type(type), in.getOperator(), peer);
            return reject(result.tip());
        }
        log.info("[PlayerInbox] GmSanctionPlayer ok player={} request_id={} type={} seq={} created={} state={} operator={} peer={}",
                Long.toUnsignedString(in.getPlayerId()), Long.toUnsignedString(in.getRequestId()), InboxLabels.type(type),
                Long.toUnsignedString(result.seq()), result.created(), result.state(), in.getOperator(), peer);
        return GmSanctionPlayerResponse.newBuilder().setSeq(result.seq()).setCreated(result.created()).setState(result.state()).build();
    }

    /** 确定性序列化（同一消息总是同一份字节：map 字段按键排序；本域的载荷没有 map，仍按契约口径用确定性写法）。 */
    static byte[] deterministic(Message message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.getSerializedSize());
        CodedOutputStream coded = CodedOutputStream.newInstance(out);
        coded.useDeterministicSerialization();
        message.writeTo(coded);
        coded.flush();
        return out.toByteArray();
    }

    private static GmSanctionPlayerResponse reject(int tip) {
        return GmSanctionPlayerResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(tip)).build();
    }
}
