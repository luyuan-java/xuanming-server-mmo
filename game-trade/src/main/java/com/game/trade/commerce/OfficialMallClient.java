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
    static final class HttpFailure extends IllegalStateException {
        private final int status;
        HttpFailure(int status) { super("商城HTTP请求失败：" + status); this.status = status; }
        int status() { return status; }
    }
    private final OfficialMallProperties p;
    private final CommerceEngine e;
    private final ObjectMapper json;
    // 只由 CommerceRuntime 的恢复线程推进；进程重启从头重放，SQL 请求键保持原合同和 seq。
    private String deliveryCursor = "";
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
    JsonNode call(String role, String subject, String method, String path, Object input, Deadline d)
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
                throw new HttpFailure(response.statusCode());
            return raw.length == 0 ? json.createObjectNode() : json.readTree(raw);
        }
    }
    public Message client(String method, com.game.api.proto.SessionContext actor, Message input, Deadline d) throws Exception {
        if (actor == null || actor.getPlayerId() == 0 || actor.getAccount().isEmpty() || actor.getOwnerRevision() <= 0)
            throw new Refusal(INVALID);
        long player = actor.getPlayerId();
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
                Map.of("requestId", in.getClientRequestId(), "skuId", in.getSkuId(), "quantity", in.getQuantity(),
                    "buyerAccount",actor.getAccount(),"buyerOwnerRevision",Long.toUnsignedString(actor.getOwnerRevision())), d);
        } else {
            String id = method.equals("GetCommerceOrder") ? ((GetCommerceOrderRequest) input).getOrderId()
                                                          : ((CancelCommerceOrderRequest) input).getOrderId();
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new Refusal(INVALID);
            boolean cancel = method.equals("CancelCommerceOrder");
            order = call("gateway", subject, "POST", cancel ? "/api/orders/cancel" : "/api/orders/query",
                Map.of("orderId",id,"buyerAccount",actor.getAccount(),"buyerOwnerRevision",Long.toUnsignedString(actor.getOwnerRevision())), d);
        }
        return answer(order, player);
    }
    public List<CommerceOrderView> list(com.game.api.proto.SessionContext actor, String after, int limit, Deadline d) throws Exception {
        if (actor == null || actor.getPlayerId() == 0 || actor.getAccount().isEmpty() || actor.getOwnerRevision() <= 0)
            throw new Refusal(INVALID);
        long player = actor.getPlayerId();
        List<CommerceOrderView> result = new ArrayList<>();
        for (var node : call("gateway", Long.toUnsignedString(player), "POST", "/api/orders/list",
                 Map.of("afterOrderId", after, "limit", limit,"buyerAccount",actor.getAccount(),"buyerOwnerRevision",Long.toUnsignedString(actor.getOwnerRevision())), d))
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
        String account = RmbConsignmentClient.text(in,"buyerAccount"), rawRevision = RmbConsignmentClient.text(in,"buyerOwnerRevision");
        long revision = CommerceClientService.id(rawRevision);
        if (account.isEmpty()) throw new Refusal(INVALID);
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
                           .setBuyerIdentity(TradeRoleIdentity.newBuilder().setAccount(account).setOwnerRevision(revision))
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
                var accepted = row.toBuilder();
                try { com.game.common.player.PlayerSessionAuthority.lock(c,player,account,revision,2); }
                catch (SecurityException ownershipChanged) {
                    // 尚未发任何资产op，原身份已失效的已付款意图只能原单退款，不给当前新主。
                    accepted.setStatus(8).setWorkflowProto(workflow.setStage("official_refund").build().toByteString());
                }
                CommerceStore.insert(c, "trade_commerce", accepted.build());
                return accepted.build();
            });
        } catch (java.sql.SQLException raced) {
            var stored = e.store.byRequest(key);
            if (stored != null && stored.getRequestDigest().equals(digest))
                return stored;
            throw raced;
        }
    }
    public void tick(Deadline d) throws Exception {
        Exception firstFailure = null;
        var entries = deliveryPage(call("delivery", "trade", "POST", "/internal/deliveries/poll",
            Map.of("limit", 20, "cursor", deliveryCursor), d), deliveryCursor);
        if (entries.isEmpty()) {
            deliveryCursor = "";
            return;
        }
        for (var entry : entries) {
            if (d.remainingMillis() < 300)
                break;
            boolean interrupted = false;
            try {
                // 只把原 Intent 交给 accept；分页包装绝不进入已有请求摘要。
                deliverIntent(entry.intent(), Deadline.after(Math.min(2500, d.remainingMillis())));
            } catch (Exception failure) {
                if (firstFailure == null)
                    firstFailure = failure;
                else if (firstFailure != failure)
                    firstFailure.addSuppressed(failure);
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                }
            }
            // 只有实际尝试过的项才能推进；失败项仍由商城保留，下轮绕回重试。
            deliveryCursor = entry.cursor();
            if (interrupted)
                break;
        }
        if (firstFailure != null)
            throw firstFailure;
    }
    record DeliveryEntry(JsonNode intent, String cursor) {}
    private record DeliveryScan(long afterMs, String afterId, long upperMs, String upperId) {}
    private static int compareDeliveryKey(long firstMs, String firstId, long secondMs, String secondId) {
        int time = Long.compare(firstMs, secondMs);
        return time == 0 ? firstId.compareTo(secondId) : time;
    }
    private static DeliveryScan decodeDeliveryCursor(String cursor) {
        String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        String[] fields = cursor.split(":", -1);
        if (cursor.length() > 256 || fields.length != 5 || !fields[0].equals("v1")
            || !fields[1].matches("0|[1-9][0-9]{0,18}") || !fields[3].matches("0|[1-9][0-9]{0,18}")
            || !fields[2].matches(uuid) || !fields[4].matches(uuid))
            throw new IllegalStateException("商城交付页游标格式非法");
        try {
            var scan = new DeliveryScan(Long.parseLong(fields[1]), fields[2], Long.parseLong(fields[3]), fields[4]);
            if (compareDeliveryKey(scan.afterMs(), scan.afterId(), scan.upperMs(), scan.upperId()) > 0)
                throw new IllegalStateException("商城交付页游标超过扫描上界");
            return scan;
        } catch (NumberFormatException overflow) {
            throw new IllegalStateException("商城交付页游标毫秒越界", overflow);
        }
    }
    /** 先校验完整包装，再开始任何副作用；业务内容错误留给逐项尝试处理。 */
    static List<DeliveryEntry> deliveryPage(JsonNode page, String requestedCursor) {
        if (!page.isObject() || !page.path("entries").isArray() || page.path("entries").size() > 20)
            throw new IllegalStateException("商城交付页包装非法");
        DeliveryScan previous = requestedCursor.isEmpty() ? null : decodeDeliveryCursor(requestedCursor);
        List<DeliveryEntry> result = new ArrayList<>();
        var entries = page.path("entries");
        Set<String> orders = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (!entry.isObject() || !entry.path("intent").isObject() || !entry.path("cursor").isTextual())
                throw new IllegalStateException("商城交付项包装非法");
            String cursor = entry.path("cursor").textValue();
            String order = entry.path("intent").path("orderId").asText();
            if (!orders.add(order))
                throw new IllegalStateException("商城交付页重复订单");
            if (cursor.isEmpty()) {
                if (i != entries.size() - 1)
                    throw new IllegalStateException("商城交付页在末项前提前绕回");
            } else {
                DeliveryScan next = decodeDeliveryCursor(cursor);
                if (!next.afterId().equals(order) || previous != null
                    && (next.upperMs() != previous.upperMs() || !next.upperId().equals(previous.upperId())
                        || compareDeliveryKey(next.afterMs(), next.afterId(), previous.afterMs(), previous.afterId()) <= 0))
                    throw new IllegalStateException("商城交付页游标乱序或上界变化");
                previous = next;
            }
            result.add(new DeliveryEntry(entry.path("intent"), cursor));
        }
        return result;
    }
    private void deliverIntent(JsonNode in, Deadline d) throws Exception {
        var r = accept(in, d);
        String state = switch (r.getStatus()) {
            case SETTLED -> "DELIVERED";
            case 8 -> "REFUND_PENDING";
            case CANCELLED -> CommerceEngine.workflow(r).getStage().equals("official_refund") ? "REFUND_PENDING" : "";
            case MANUAL -> "MANUAL";
            default -> "";
        };
        if (state.isEmpty())
            return;
        call("delivery", "trade", "POST", "/internal/deliveries/receipt",
            Map.of("orderId", in.path("orderId").asText(), "tradeOrderId", Long.toUnsignedString(r.getCommerceId()),
                "state", state, "proof",
                HexFormat.of().formatHex(CommerceEngine.sha(r.getWorkflowProto().toByteArray()).toByteArray())), d);
        if (r.getStatus() == 8) {
            // Mall已持久接管原款退款。游戏资产合同终止，无后续credit；资金状态仍由Mall展示为退款中。
            // 保留原workflow原字节，重放时生成相同REFUND_PENDING/proof，不伪造已退款事实。
            e.store.transaction(d, c -> {
                var current=e.store.lock(c,r.getCommerceId());
                if(current != null && current.getStatus() == 8 && current.getWorkflowProto().equals(r.getWorkflowProto())) {
                    e.store.markConsumed(c,current.getCommerceId());
                    e.store.save(c,current.toBuilder().setStatus(CANCELLED).setUpdatedMs(e.clock.getAsLong()));
                }
                return null;
            });
        }
    }
}
