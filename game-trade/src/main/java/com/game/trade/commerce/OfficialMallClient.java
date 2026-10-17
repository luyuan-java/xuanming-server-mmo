package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.fasterxml.jackson.databind.*;
import com.game.common.deadline.Deadline;
import com.game.proto.AssetBundle;
import com.game.proto.ItemGrant;
import com.game.proto.trade.*;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.Message;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Mall官方库存与游戏资产之间的可信接缝；HTTP未知时原订单与seq原样保留。 */
public final class OfficialMallClient {
    private final OfficialMallProperties p;
    private final CommerceEngine e;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
                                        .connectTimeout(Duration.ofSeconds(2))
                                        .followRedirects(HttpClient.Redirect.NEVER)
                                        .build();
    public OfficialMallClient(OfficialMallProperties p, CommerceEngine e, ObjectMapper json) {
        this.p = p;
        this.e = e;
        this.json = json;
        URI base = URI.create(p.url());
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
            || base.getRawQuery() != null || base.getFragment() != null || !Set.of("", "/").contains(base.getPath()))
            throw new IllegalArgumentException("商城HTTP地址非法");
    }
    private JsonNode call(String role, String subject, String method, String path, Object input, Deadline d)
        throws Exception {
        CommerceStore.active(d);
        byte[] body = input == null ? new byte[0] : json.writeValueAsBytes(input);
        String stamp = Long.toString(System.currentTimeMillis()), nonce = UUID.randomUUID().toString().replace("-", "");
        String canonical = String.join("\n", "v1", role, subject, stamp, nonce, method, path,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
            (role.equals("delivery") ? p.deliverySecret() : p.gatewaySecret()).getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"));
        var request = HttpRequest.newBuilder(URI.create(p.url().replaceAll("/$", "") + path))
                          .timeout(Duration.ofMillis(Math.max(1, Math.min(2500, d.remainingMillis()))))
                          .header("Content-Type", "application/json")
                          .header("X-Commerce-Subject", subject)
                          .header("X-Commerce-Timestamp", stamp)
                          .header("X-Commerce-Nonce", nonce)
                          .header("X-Commerce-Signature",
                              HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))))
                          .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                          .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var in = response.body()) {
            byte[] raw = in.readNBytes(262145);
            if (raw.length > 262144)
                throw new IllegalStateException("商城应答越界");
            if (response.statusCode() != 200)
                throw new IllegalStateException("商城HTTP请求失败：" + response.statusCode());
            return raw.length == 0 ? json.createObjectNode() : json.readTree(raw);
        }
    }
    public Message client(String method, long player, Message input, Deadline d) throws Exception {
        String subject = Long.toUnsignedString(player);
        if (method.equals("ListOfficialSkus")) {
            var in = (ListOfficialSkusRequest) input;
            var out = ListOfficialSkusResponse.newBuilder();
            for (var sku : call("gateway", subject, "POST", "/api/skus/list",
                     Map.of("afterSkuId", in.getAfterSkuId(), "limit", Math.min(Math.max(1, in.getLimit()), 100)), d)
                     .path("skus")) {
                long id = sku.path("skuId").asLong();
                if (Long.compareUnsigned(id, in.getAfterSkuId()) <= 0)
                    continue;
                out.addSkus(OfficialSkuView.newBuilder()
                                .setSkuId(id)
                                .setTitle(sku.path("title").asText())
                                .setPriceMinor(sku.path("priceMinor").asLong())
                                .setCurrency(sku.path("currency").asText())
                                .setAvailable(sku.path("available").asLong()))
                    .setNextSkuId(id);
                if (out.getSkusCount() >= Math.min(Math.max(1, in.getLimit()), 100))
                    break;
            }
            return out.build();
        }
        JsonNode order;
        if (method.equals("CreateOfficialOrder")) {
            var in = (CreateOfficialOrderRequest) input;
            order = call("gateway", subject, "POST", "/api/orders",
                Map.of("requestId", in.getClientRequestId(), "skuId", in.getSkuId(), "quantity", in.getQuantity()), d);
        } else {
            String id = method.equals("GetCommerceOrder") ? ((GetCommerceOrderRequest) input).getOrderId()
                                                          : ((CancelCommerceOrderRequest) input).getOrderId();
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new Refusal(INVALID);
            boolean cancel = method.equals("CancelCommerceOrder");
            order = call(
                "gateway", subject, cancel ? "POST" : "GET", "/api/orders/" + id + (cancel ? "/cancel" : ""), null, d);
        }
        return answer(order, player);
    }
    public List<CommerceOrderView> list(long player, String after, int limit, Deadline d) throws Exception {
        List<CommerceOrderView> result = new ArrayList<>();
        for (var node : call("gateway", Long.toUnsignedString(player), "POST", "/api/orders/list",
                 Map.of("afterOrderId", after, "limit", limit), d))
            result.add(answer(node, player).getOrder());
        return result;
    }
    private CommerceOrderResponse answer(JsonNode order, long player) {
        if (!order.path("buyerId").asText().equals(Long.toUnsignedString(player)))
            throw new IllegalStateException("商城订单主体不匹配");
        int state = switch (order.path("state").asText()) {
            case "AWAITING_PAYMENT" -> 2;
            case "DELIVERY_PENDING" -> 4;
            case "DELIVERED" -> 5;
            case "CANCELLED" -> 7;
            case "REFUND_PENDING" -> 8;
            case "REFUNDED" -> 9;
            case "MANUAL" -> 10;
            default -> throw new IllegalStateException("商城状态未知");
        };
        return CommerceOrderResponse.newBuilder()
            .setOrder(CommerceOrderView.newBuilder()
                    .setOrderId(order.path("orderId").asText())
                    .setKindValue(1)
                    .setStateValue(state)
                    .setVersion(state)
                    .setBuyerPlayerId(player)
                    .setAmount(order.path("amountMinor").asLong())
                    .setCurrency(order.path("currency").asText())
                    .setRelatedId(order.path("skuId").asText()))
            .setServerNowMs(System.currentTimeMillis())
            .build();
    }
    private TradeCommerceRow accept(JsonNode in, Deadline d) throws Exception {
        long player = Long.parseUnsignedLong(in.path("buyerId").asText());
        String order = in.path("orderId").asText();
        int config = in.path("itemConfigId").asInt(), count = in.path("itemCount").asInt();
        if (player == 0 || !order.matches("[0-9a-f-]{36}") || config <= 0 || count < 1 || count > 4096)
            throw new Refusal(INVALID);
        String key = CommerceEngine.key("official", player, order);
        var digest = CommerceEngine.sha(json.writeValueAsBytes(in));
        var old = e.store.byRequest(key);
        if (old != null) {
            if (!old.getRequestDigest().equals(digest))
                throw new Refusal(REQUEST_CONFLICT);
            return old;
        }
        long now = e.clock.getAsLong();
        var workflow = TradeCommerceWorkflow.newBuilder()
                           .setStage("official_deliver")
                           .setExternalOrderSn(order)
                           .setSkuId(in.path("skuId").asLong())
                           .setSkuQuantity(in.path("quantity").asInt())
                           .setExternalAmount(in.path("amountMinor").asLong())
                           .setExternalCurrency(in.path("currency").asText())
                           .setOfficialBundle(AssetBundle.newBuilder().addItems(
                               ItemGrant.newBuilder().setConfigId(config).setCount(count)));
        var row = TradeCommerceRow.newBuilder()
                      .setCommerceId(e.ids.getAsLong())
                      .setRequestKey(key)
                      .setRequestDigest(digest)
                      .setKind(1)
                      .setStatus(DELIVERING)
                      .setBuyerPlayerId(player)
                      .setCreatedMs(now)
                      .setUpdatedMs(now)
                      .setVersion(1)
                      .setWorkflowProto(workflow.build().toByteString())
                      .build();
        try {
            return e.store.transaction(d, c -> {
                CommerceStore.insert(c, "trade_commerce", row);
                return row;
            });
        } catch (java.sql.SQLException raced) {
            var stored = e.store.byRequest(key);
            if (stored != null && stored.getRequestDigest().equals(digest))
                return stored;
            throw raced;
        }
    }
    public void tick(Deadline d) throws Exception {
        for (var in : call("delivery", "trade", "POST", "/internal/deliveries/poll", Map.of("limit", 20), d)) {
            if (d.remainingMillis() < 300)
                break;
            var r = accept(in, d);
            String state = switch (r.getStatus()) {
                case SETTLED -> "DELIVERED";
                case 8 -> "REFUND_PENDING";
                case MANUAL -> "MANUAL";
                default -> "";
            };
            if (state.isEmpty())
                continue;
            call("delivery", "trade", "POST", "/internal/deliveries/receipt",
                Map.of("orderId", in.path("orderId").asText(), "tradeOrderId", Long.toUnsignedString(r.getCommerceId()),
                    "state", state, "proof",
                    HexFormat.of().formatHex(CommerceEngine.sha(r.getWorkflowProto().toByteArray()).toByteArray())),
                d);
        }
    }
}
