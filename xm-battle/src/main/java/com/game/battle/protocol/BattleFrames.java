package com.game.battle.protocol;

import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientRequest;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.MessageLite;

/**
 * 直连面下行帧的形状（客户端可见，两版逐字节相同；battle-node-spec §3.2、§3.4）。下行只有两种类型：
 * {@code BattleTokenVerifyResponse} 与 {@code MessageContent}；客户端靠 {@code MessageContent} 的形状区分应答 / 信封错误 / 推送。
 * 纯函数，线程安全。
 */
public final class BattleFrames {

    /** 握手第 2 步：配了密钥且签名不符（采样日志 reason {@code ticket_hmac_mismatch}）。 */
    public static final String REJECT_INVALID_SIGNATURE = "invalid ticket signature";
    /** 握手第 3 步：payload 解析失败（reason {@code ticket_payload_parse_failed}）。 */
    public static final String REJECT_MALFORMED_PAYLOAD = "malformed ticket payload";
    /**
     * 握手第 5 步：房间不存在，或该玩家不在票上角色对应的名单上（reason {@code ticket_not_in_roster}）。
     * 第 4 步（字段判定）的拒绝串是 {@code "ticket rejected: " + 判定名}，见 {@code BattleTickets.Verdict#clientError()}。
     */
    public static final String REJECT_NOT_IN_ROSTER = "battle not found or player not in this battle";

    private BattleFrames() {
    }

    /** 推送：{@code message_id = N}，{@code serialized_message}；{@code id} 不填（= 0），没有 {@code error_message}。 */
    public static MessageContent push(int messageId, MessageLite body) {
        return MessageContent.newBuilder()
                .setMessageId(messageId)
                .setSerializedMessage(body.toByteString())
                .build();
    }

    /**
     * 应答：{@code id} 与 {@code message_id} 回显请求，{@code serialized_message} = 应答体字节（可以是 0 字节，例如成功时
     * error_message 不填的 {@code SubmitBattleActionResponse}），没有 {@code error_message}。
     */
    public static MessageContent reply(ClientRequest request, MessageLite body) {
        return MessageContent.newBuilder()
                .setId(request.getId())
                .setMessageId(request.getMessageId())
                .setSerializedMessage(body.toByteString())
                .build();
    }

    /** 信封错误：{@code id} 与 {@code message_id} 回显，{@code error_message{id = tip}}，没有 {@code serialized_message}。 */
    public static MessageContent envelopeError(ClientRequest request, int tipId) {
        return MessageContent.newBuilder()
                .setId(request.getId())
                .setMessageId(request.getMessageId())
                .setErrorMessage(TipInfoMessage.newBuilder().setId(tipId))
                .build();
    }

    /** 握手成功：{@code success = true, battle_id}，{@code error} 不填（重复握手时 battle_id 是已绑定的那个，B1）。 */
    public static BattleTokenVerifyResponse verifyAccepted(long battleId) {
        return BattleTokenVerifyResponse.newBuilder().setSuccess(true).setBattleId(battleId).build();
    }

    /** 握手失败：{@code success = false, error = 拒绝串}，{@code battle_id} 不填。 */
    public static BattleTokenVerifyResponse verifyRejected(String error) {
        return BattleTokenVerifyResponse.newBuilder().setSuccess(false).setError(error).build();
    }
}
