package com.game.trade.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.common.deadline.Deadline;
import com.game.proto.trade.*;
import com.google.protobuf.ByteString;
import java.util.HexFormat;
import java.util.Map;

/** Mall 接缝只使用独立 delivery 密钥与固定 trade 主体；响应字段未知/类型不符即拒绝。 */
public final class RmbConsignmentClient {
    private final OfficialMallClient http;
    public RmbConsignmentClient(OfficialMallClient http) { this.http = http; }
    JsonNode call(String suffix, Object input, Deadline budget) throws Exception {
        return http.call("delivery", "trade", "POST", "/internal/consignments/" + suffix, input, budget);
    }
    static String text(JsonNode input, String key) {
        var field = input.get(key);
        if (field == null || !field.isTextual()) throw new IllegalStateException("商城应答字段非法：" + key);
        return field.textValue();
    }
    static long number(JsonNode input, String key) {
        var field = input.get(key);
        if (field == null || !field.isIntegralNumber() || !field.canConvertToLong() || field.longValue() < 0)
            throw new IllegalStateException("商城应答数值非法：" + key);
        return field.longValue();
    }
    static String nullableText(JsonNode input, String key) {
        var field = input.get(key);
        if (field != null && field.isNull()) return "";
        return text(input, key);
    }
    static JsonNode array(JsonNode input, String key) {
        var field = key.isEmpty() ? input : input.get(key);
        if (field == null || !field.isArray() || field.size() > 100) throw new IllegalStateException("商城分页越界");
        return field;
    }
    static RmbConsignmentView listing(JsonNode v) {
        return RmbConsignmentView.newBuilder().setListingId(CommerceClientService.id(text(v,"listingId")))
            .setSellerPlayerId(CommerceClientService.id(text(v,"sellerPlayerId")))
            .setMarketZone(Math.toIntExact(number(v,"marketZone"))).setAssetKind(text(v,"assetKind"))
            .setTitle(text(v,"title")).setPriceFen(number(v,"priceFen"))
            .setFeeBps(Math.toIntExact(number(v,"feeBps"))).setCoolingMs(number(v,"coolingMs"))
            .setNoticeEndMs(number(v,"noticeEndMs")).setSaleEndMs(number(v,"saleEndMs"))
            .setState(text(v,"state")).setPhase(text(v,"phase")).setOrderId(nullableText(v,"orderId")).build();
    }
    static CommerceOrderView order(JsonNode v) {
        int state = switch (text(v,"state")) {
            case "AWAITING_PAYMENT" -> 2; case "DELIVERY_PENDING" -> 4; case "DELIVERED" -> 5;
            case "CANCELLED" -> 7; case "REFUND_PENDING" -> 8; case "REFUNDED" -> 9; case "MANUAL" -> 10;
            default -> throw new IllegalStateException("商城订单状态未知");
        };
        if (!"CNY".equals(text(v,"currency"))) throw new IllegalStateException("寄售币种不符");
        return CommerceOrderView.newBuilder().setOrderId(text(v,"orderId")).setKindValue(6).setStateValue(state)
            .setBuyerPlayerId(CommerceClientService.id(text(v,"buyerId"))).setAmount(number(v,"amountMinor"))
            .setCurrency("CNY").setVersion(state).build();
    }
    static RmbWithdrawalView withdrawal(JsonNode v) {
        return RmbWithdrawalView.newBuilder().setWithdrawalId(text(v,"withdrawalId"))
            .setAmountFen(number(v,"amountFen")).setState(text(v,"state"))
            .setChannel(nullableText(v,"channel")).setTransactionId(nullableText(v,"transactionId")).build();
    }
    JsonNode get(long listing, Deadline d) throws Exception {
        return call("get", Map.of("listingId", Long.toUnsignedString(listing)), d);
    }
    void httpReceipt(String order, long contractId, ByteString proof, String state, Deadline d) throws Exception {
        http.call("delivery", "trade", "POST", "/internal/deliveries/receipt", Map.of("orderId", order,
            "tradeOrderId", Long.toUnsignedString(contractId), "state", state,
            "proof", HexFormat.of().formatHex(proof.toByteArray())), d);
    }
}
