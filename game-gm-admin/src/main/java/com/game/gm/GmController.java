package com.game.gm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Web GM 后台 REST 入口：概览、玩家查询、个人/全服/活动邮件两阶段发放、战斗剧本审核与审计查询。 */
@RestController
@RequestMapping(value = "/gm", produces = "application/json;charset=UTF-8")
public class GmController {

    private static final Set<String> DRAFT_FIELDS = Set.of("clientRequestId", "playerId", "title", "body", "reason",
            "currencyType", "currencyAmount", "itemConfigId", "itemCount", "currencies", "items", "validDays");
    private static final Set<String> SYSTEM_MAIL_FIELDS = Set.of("clientRequestId", "title", "body", "reason", "validDays");
    private static final Set<String> CAMPAIGN_FIELDS = Set.of("clientRequestId", "title", "body", "reason",
            "currencies", "items", "recipientPlayerIds", "validDays");
    private static final Set<String> REVIEW_SCRIPT_FIELDS = Set.of("expectedRevision", "approve", "reason");
    private static final Set<String> CURRENCY_ENTRY_FIELDS = Set.of("currencyType", "amount");
    private static final Set<String> ITEM_ENTRY_FIELDS = Set.of("configId", "count");
    private static final int DEFAULT_MAX_BODY_BYTES = 16_384;
    private static final int CAMPAIGN_MAX_BODY_BYTES = 512 * 1024;
    private static final int MAX_CURRENCIES = 4;
    private static final int MAX_ITEMS = 16;
    private static final int MAX_TOTAL_ITEM_COUNT = 4096;

    private final ObjectMapper json;
    private final GmAdminAuth auth;
    private final GmGamePort port;
    private final GmCommandJournal journal;
    private final URI gateway;
    private final String gatewayKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GmController(ObjectMapper json, GmAdminAuth auth, GmGamePort port, GmCommandJournal journal,
                        GmAdminProperties properties) {
        this.json = json;
        this.auth = auth;
        this.port = port;
        this.journal = journal;
        this.gateway = URI.create(properties.gatewayUrl());
        this.gatewayKey = properties.gatewayAdminKey();
        String host = this.gateway.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
        if (host == null || this.gateway.getUserInfo() != null || this.gateway.getQuery() != null
                || this.gateway.getFragment() != null
                || !("https".equals(this.gateway.getScheme()) || ("http".equals(this.gateway.getScheme()) && loopback))) {
            throw new IllegalArgumentException("GM gateway requires HTTPS or loopback HTTP");
        }
    }

    @GetMapping("/overview")
    public String overview(HttpServletRequest req) {
        GmAdminAuth.Principal principal = auth.authenticate(req);
        if (!principal.isPermittedAny(GmAdminAuth.ALL_PERMISSIONS)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有此 GM 操作权限");
        }
        ObjectNode result = json.createObjectNode()
                .put("mailEnabled", port.mailEnabled())
                .put("battleScriptReviewEnabled", port.battleScriptReviewEnabled());
        ArrayNode currencies = port.currencyTypes();
        result.set("currencies", currencies != null ? currencies : json.createArrayNode());
        result.putArray("zones");
        if (principal.isPermitted("gm:zone:read")) {
            try {
                String path = gateway.toString().replaceAll("/+$", "") + "/admin/zones";
                HttpRequest request = HttpRequest.newBuilder(URI.create(path))
                        .timeout(Duration.ofSeconds(5))
                        .header("X-Admin-Key", gatewayKey)
                        .GET().build();
                HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var stream = response.body()) {
                    byte[] bytes = stream.readNBytes(262_145);
                    if (response.statusCode() != 200 || bytes.length > 262_144) {
                        throw new IllegalStateException();
                    }
                    JsonNode zones = json.readTree(bytes);
                    if (!zones.isArray()) {
                        throw new IllegalStateException();
                    }
                    result.set("zones", zones);
                    result.put("zonesAvailable", true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.put("zonesAvailable", false);
            } catch (Exception e) {
                result.put("zonesAvailable", false);
            }
        }
        return ok(result);
    }

    @GetMapping("/players/{playerId}")
    public String player(HttpServletRequest req, @PathVariable("playerId") String playerId) {
        auth.require(req, "gm:player:read");
        decimal(playerId, BigInteger.valueOf(Long.MAX_VALUE), "玩家 ID");
        return ok(port.player(playerId));
    }

    @PostMapping("/mail/drafts")
    public String prepare(HttpServletRequest req, @RequestBody String body) {
        String actor = auth.require(req, "gm:mail:prepare");
        requireMailEnabled();
        ObjectNode input = parse(body, DEFAULT_MAX_BODY_BYTES);
        input.fieldNames().forEachRemaining(key -> {
            if (!DRAFT_FIELDS.contains(key)) {
                bad("存在未知字段");
            }
        });
        String clientId = uuid(input);
        String playerId = text(input, "playerId", 1, 19);
        decimal(playerId, BigInteger.valueOf(Long.MAX_VALUE), "玩家 ID");
        ObjectNode request = json.createObjectNode().put("senderKind", 1)
                .put("title", text(input, "title", 1, 128))
                .put("body", text(input, "body", 0, 4096))
                .put("validDays", integer(input, "validDays", 1, 30));
        request.set("attachments", buildAttachments(input, true));
        if (!port.player(playerId).path("found").asBoolean()) {
            bad("未找到目标玩家，请核对玩家 ID");
        }
        return ok(journal.prepare(actor, clientId, playerId, text(input, "reason", 1, 191), request));
    }

    @PostMapping("/mail/commands/{id}/send")
    public String send(HttpServletRequest req, @PathVariable("id") String id) {
        String actor = auth.require(req, "gm:mail:send");
        requireMailEnabled();
        decimal(id, BigInteger.valueOf(Long.MAX_VALUE), "命令 ID");
        return ok(journal.send(id, actor, port::sendMail));
    }

    @GetMapping("/mail/commands")
    public String commands(HttpServletRequest req,
                           @RequestParam(name = "page", defaultValue = "1") int page,
                           @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
                           @RequestParam(name = "playerId", required = false) String playerId,
                           @RequestParam(name = "status", required = false) String status) {
        auth.require(req, "gm:audit:read");
        return ok(journal.list(page, pageSize, playerId, status));
    }

    @GetMapping("/mail/commands/{id}")
    public String command(HttpServletRequest req, @PathVariable("id") String id) {
        auth.require(req, "gm:audit:read");
        decimal(id, BigInteger.valueOf(Long.MAX_VALUE), "命令 ID");
        return ok(journal.get(id, "MAIL"));
    }

    @GetMapping("/mail/commands/{id}/audit")
    public String commandAudit(HttpServletRequest req, @PathVariable("id") String id) {
        auth.require(req, "gm:audit:read");
        decimal(id, BigInteger.valueOf(Long.MAX_VALUE), "命令 ID");
        return ok(journal.auditHistory(id, "MAIL"));
    }

    @PostMapping("/system-mail/drafts")
    public String prepareSystemMail(HttpServletRequest req, @RequestBody String body) {
        String actor = auth.require(req, "gm:system-mail:prepare");
        requireMailEnabled();
        ObjectNode input = parse(body, DEFAULT_MAX_BODY_BYTES);
        input.fieldNames().forEachRemaining(key -> {
            if (!SYSTEM_MAIL_FIELDS.contains(key)) {
                bad("存在未知字段");
            }
        });
        String clientId = uuid(input);
        ObjectNode request = json.createObjectNode().put("senderKind", 1)
                .put("title", text(input, "title", 1, 128))
                .put("body", text(input, "body", 0, 4096))
                .put("validDays", integer(input, "validDays", 1, 30));
        return ok(journal.prepareSystemMail(actor, clientId, text(input, "reason", 1, 191), request));
    }

    @PostMapping("/system-mail/commands/{id}/send")
    public String sendSystemMail(HttpServletRequest req, @PathVariable("id") String id) {
        String actor = auth.require(req, "gm:system-mail:send");
        requireMailEnabled();
        decimal(id, BigInteger.valueOf(Long.MAX_VALUE), "命令 ID");
        return ok(journal.sendSystemMail(id, actor, port::sendSystemMail));
    }

    @GetMapping("/system-mail/commands")
    public String systemMailCommands(HttpServletRequest req,
                                     @RequestParam(name = "page", defaultValue = "1") int page,
                                     @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
                                     @RequestParam(name = "status", required = false) String status) {
        auth.require(req, "gm:audit:read");
        return ok(journal.listSystemMails(page, pageSize, status));
    }

    @GetMapping("/system-mail/commands/{id}")
    public String systemMailCommand(HttpServletRequest req, @PathVariable("id") String id) {
        auth.require(req, "gm:audit:read");
        decimal(id, BigInteger.valueOf(Long.MAX_VALUE), "命令 ID");
        return ok(journal.get(id, "SYSTEM_MAIL"));
    }

    @GetMapping("/system-mail/commands/{id}/audit")
    public String systemMailAudit(HttpServletRequest req, @PathVariable("id") String id) {
        auth.require(req, "gm:audit:read");
        decimal(id, BigInteger.valueOf(Long.MAX_VALUE), "命令 ID");
        return ok(journal.auditHistory(id, "SYSTEM_MAIL"));
    }

    @PostMapping("/mail-campaigns/drafts")
    public String prepareMailCampaign(HttpServletRequest req, @RequestBody String body) {
        String actor = auth.require(req, "gm:system-mail:prepare");
        requireMailEnabled();
        ObjectNode input = parse(body, CAMPAIGN_MAX_BODY_BYTES);
        input.fieldNames().forEachRemaining(key -> {
            if (!CAMPAIGN_FIELDS.contains(key)) {
                bad("存在未知字段");
            }
        });
        String clientId = uuid(input);
        JsonNode rawRecipients = input.get("recipientPlayerIds");
        if (rawRecipients == null || !rawRecipients.isArray() || rawRecipients.isEmpty()
                || rawRecipients.size() > MailCampaignManifest.MAX_WEB_RECIPIENTS * 2) {
            bad("收件人名单必须是非空玩家 ID 数组，且去重后不超过 " + MailCampaignManifest.MAX_WEB_RECIPIENTS + " 人");
        }
        List<String> recipientIds = new ArrayList<>(rawRecipients.size());
        for (JsonNode item : rawRecipients) {
            if (!item.isTextual()) {
                bad("收件人玩家 ID 必须是十进制字符串");
            }
            recipientIds.add(item.textValue());
        }
        MailCampaignManifest.Plan plan;
        try {
            plan = MailCampaignManifest.build(recipientIds);
        } catch (IllegalArgumentException e) {
            bad(e.getMessage());
            return null;
        }
        ObjectNode mail = json.createObjectNode().put("senderKind", 1)
                .put("title", text(input, "title", 1, 128))
                .put("body", text(input, "body", 0, 4096))
                .put("validDays", integer(input, "validDays", 1, 30))
                .put("expectedRecipientCount", Integer.toString(plan.recipientCount()))
                .put("expectedShardCount", plan.shards().size())
                .put("manifestHash", plan.manifestHash());
        mail.set("attachments", buildAttachments(input, false));
        ObjectNode campaign = json.createObjectNode();
        campaign.set("mail", mail);
        ArrayNode shardsNode = campaign.putArray("shards");
        for (List<String> shard : plan.shards()) {
            ArrayNode shardArray = shardsNode.addArray();
            shard.forEach(shardArray::add);
        }
        return ok(journal.prepareMailCampaign(actor, clientId, text(input, "reason", 1, 191), campaign));
    }

    @PostMapping("/mail-campaigns/commands/{id}/send")
    public String sendMailCampaign(HttpServletRequest req, @PathVariable("id") String id) {
        String actor = auth.require(req, "gm:system-mail:send");
        requireMailEnabled();
        decimal(id, BigInteger.valueOf(Long.MAX_VALUE), "命令 ID");
        return ok(journal.sendMailCampaign(id, actor, port::sendMailCampaign));
    }

    @GetMapping("/mail-campaigns/commands")
    public String mailCampaignCommands(HttpServletRequest req,
                                       @RequestParam(name = "page", defaultValue = "1") int page,
                                       @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
                                       @RequestParam(name = "status", required = false) String status) {
        auth.require(req, "gm:audit:read");
        return ok(journal.listMailCampaigns(page, pageSize, status));
    }

    @GetMapping("/mail-campaigns/commands/{id}")
    public String mailCampaignCommand(HttpServletRequest req, @PathVariable("id") String id) {
        auth.require(req, "gm:audit:read");
        decimal(id, BigInteger.valueOf(Long.MAX_VALUE), "命令 ID");
        return ok(journal.get(id, "MAIL_CAMPAIGN"));
    }

    @GetMapping("/mail-campaigns/commands/{id}/audit")
    public String mailCampaignAudit(HttpServletRequest req, @PathVariable("id") String id) {
        auth.require(req, "gm:audit:read");
        decimal(id, BigInteger.valueOf(Long.MAX_VALUE), "命令 ID");
        return ok(journal.auditHistory(id, "MAIL_CAMPAIGN"));
    }

    @GetMapping("/battle-scripts/pending")
    public String pendingBattleScripts(HttpServletRequest req,
                                       @RequestParam(name = "limit", defaultValue = "20") int limit) {
        auth.require(req, "gm:battle-script:review");
        if (!port.battleScriptReviewEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "战斗剧本审核通道未配置");
        }
        return ok(port.listPendingBattleScripts(limit));
    }

    @PostMapping("/battle-scripts/{scriptId}/review")
    public String reviewBattleScript(HttpServletRequest req, @PathVariable("scriptId") String scriptId, @RequestBody String body) {
        String actor = auth.require(req, "gm:battle-script:review");
        if (!port.battleScriptReviewEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "战斗剧本审核通道未配置");
        }
        decimal(scriptId, BigInteger.valueOf(Long.MAX_VALUE), "剧本 ID");
        ObjectNode input = parse(body, DEFAULT_MAX_BODY_BYTES);
        input.fieldNames().forEachRemaining(k -> {
            if (!REVIEW_SCRIPT_FIELDS.contains(k)) {
                bad("存在未知字段");
            }
        });
        int expectedRevision = integer(input, "expectedRevision", 1, Integer.MAX_VALUE);
        JsonNode approveNode = input.get("approve");
        if (approveNode == null || !approveNode.isBoolean()) {
            bad("approve 必须是布尔值");
        }
        boolean approve = approveNode.booleanValue();
        String reason = input.hasNonNull("reason") ? text(input, "reason", 0, 191) : "";
        return ok(port.reviewBattleScript(Long.parseLong(scriptId), expectedRevision, approve, actor, reason));
    }

    private void requireMailEnabled() {
        if (!port.mailEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮件服务未配置，暂不能发放");
        }
    }

    private ObjectNode buildAttachments(ObjectNode input, boolean allowLegacyFlat) {
        ObjectNode attachments = json.createObjectNode();
        boolean hasCurrencyArray = input.hasNonNull("currencies");
        boolean hasItemArray = input.hasNonNull("items");
        boolean hasFlatCurrency = allowLegacyFlat && input.hasNonNull("currencyAmount") && !input.path("currencyAmount").asText().isBlank();
        boolean hasFlatItem = allowLegacyFlat && (input.hasNonNull("itemConfigId") || input.hasNonNull("itemCount"));
        if (hasCurrencyArray && (hasFlatCurrency || (allowLegacyFlat && input.hasNonNull("currencyType")))) {
            bad("货币附件不能同时混用数组和单字段格式");
        }
        if (hasItemArray && hasFlatItem) {
            bad("物品附件不能同时混用数组和单字段格式");
        }
        int currencyCount = 0;
        if (hasCurrencyArray) {
            JsonNode node = input.get("currencies");
            if (!node.isArray() || node.size() > MAX_CURRENCIES) {
                bad("货币附件数组格式非法或超过 4 种上限");
            }
            Set<Integer> seen = new HashSet<>();
            ArrayNode out = null;
            for (JsonNode entry : node) {
                if (!(entry instanceof ObjectNode obj)) {
                    bad("货币附件项必须是对象");
                    return null;
                }
                obj.fieldNames().forEachRemaining(k -> {
                    if (!CURRENCY_ENTRY_FIELDS.contains(k)) {
                        bad("货币附件存在未知字段");
                    }
                });
                int currencyType = integer(obj, "currencyType", 0, Integer.MAX_VALUE);
                if (!port.supportsCurrency(currencyType)) {
                    bad("未知币种");
                }
                if (!seen.add(currencyType)) {
                    bad("货币附件不能包含重复币种");
                }
                String amount = text(obj, "amount", 1, 19);
                decimal(amount, BigInteger.valueOf(Long.MAX_VALUE), "货币数量");
                if (out == null) {
                    out = attachments.putArray("currencies");
                }
                out.addObject().put("currencyType", currencyType).put("amount", amount);
                currencyCount++;
            }
        } else if (hasFlatCurrency) {
            String amount = text(input, "currencyAmount", 1, 19);
            decimal(amount, BigInteger.valueOf(Long.MAX_VALUE), "货币数量");
            int currencyType = input.hasNonNull("currencyType") ? integer(input, "currencyType", 0, Integer.MAX_VALUE) : 0;
            if (!port.supportsCurrency(currencyType)) {
                bad("未知币种");
            }
            attachments.putArray("currencies").addObject().put("currencyType", currencyType).put("amount", amount);
            currencyCount = 1;
        }
        int itemEntries = 0;
        int totalItems = 0;
        if (hasItemArray) {
            JsonNode node = input.get("items");
            if (!node.isArray() || node.size() > MAX_ITEMS) {
                bad("物品附件数组格式非法或超过 16 种上限");
            }
            Set<Integer> seen = new HashSet<>();
            ArrayNode out = null;
            for (JsonNode entry : node) {
                if (!(entry instanceof ObjectNode obj)) {
                    bad("物品附件项必须是对象");
                    return null;
                }
                obj.fieldNames().forEachRemaining(k -> {
                    if (!ITEM_ENTRY_FIELDS.contains(k)) {
                        bad("物品附件存在未知字段");
                    }
                });
                int configId = integer(obj, "configId", 1, Integer.MAX_VALUE);
                if (!seen.add(configId)) {
                    bad("物品附件不能包含重复配置 ID");
                }
                int count = integer(obj, "count", 1, MAX_TOTAL_ITEM_COUNT);
                totalItems += count;
                if (totalItems > MAX_TOTAL_ITEM_COUNT) {
                    bad("单封邮件物品总数不能超过 4096");
                }
                if (out == null) {
                    out = attachments.putArray("items");
                }
                out.addObject().put("configId", configId).put("count", count);
                itemEntries++;
            }
        } else if (hasFlatItem) {
            attachments.putArray("items")
                    .addObject()
                    .put("configId", integer(input, "itemConfigId", 1, Integer.MAX_VALUE))
                    .put("count", integer(input, "itemCount", 1, MAX_TOTAL_ITEM_COUNT));
            itemEntries = 1;
        }
        if (currencyCount == 0 && itemEntries == 0) {
            bad("至少填写一种货币或物品附件");
        }
        return attachments;
    }

    private static String uuid(ObjectNode input) {
        String clientId = text(input, "clientRequestId", 1, 36);
        try {
            if (!UUID.fromString(clientId).toString().equals(clientId)) {
                bad("请求号无效");
            }
        } catch (IllegalArgumentException e) {
            bad("请求号无效");
        }
        return clientId;
    }

    private ObjectNode parse(String body, int maxBytes) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            bad("请求体过大");
        }
        try (JsonParser parser = json.getFactory().createParser(body)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (!(node instanceof ObjectNode obj) || parser.nextToken() != null) {
                bad("请求 JSON 无效");
                return null;
            }
            return obj;
        } catch (IOException e) {
            bad("请求 JSON 无效");
            return null;
        }
    }

    private static String text(ObjectNode input, String field, int min, int max) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual()) {
            bad(field + " 必须是文本");
        }
        String result = value.textValue();
        int length = result.codePointCount(0, result.length());
        if (length < min || length > max || (min > 0 && result.isBlank())) {
            bad(field + " 长度无效");
        }
        return result;
    }

    private static int integer(ObjectNode input, String field, int min, int max) {
        JsonNode value = input.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            bad(field + " 超出允许范围");
        }
        return value.intValue();
    }

    private static void decimal(String value, BigInteger max, String label) {
        if (!value.matches("[1-9][0-9]{0,18}") || new BigInteger(value).compareTo(max) > 0) {
            bad(label + " 必须是正整数文本");
        }
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String ok(JsonNode result) {
        return json.createObjectNode().put("success", true).put("code", 200).put("message", "").set("result", result).toString();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> badParameter() {
        return error(400, "请求参数格式无效");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> rejected(ResponseStatusException e) {
        return error(e.getStatusCode().value(), e.getReason());
    }

    @ExceptionHandler(GmGamePort.CallFailure.class)
    public ResponseEntity<String> upstream(GmGamePort.CallFailure e) {
        return error(502, "游戏服务调用未完成：" + e.getCode());
    }

    private ResponseEntity<String> error(int code, String message) {
        return ResponseEntity.status(code)
                .body(json.createObjectNode().put("success", false).put("code", code).put("message", message == null ? "" : message).toString());
    }
}
