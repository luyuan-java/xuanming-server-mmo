package com.game.gm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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

/** 七种在线玩家 GM 命令的网页入口；请求不能提供操作者、RPC 地址或任意方法。 */
@RestController
@RequestMapping(value = "/gm/player-commands", produces = "application/json;charset=UTF-8")
public class GmPlayerCommandController {

    private record Operation(String code, String label, String permission) {
    }

    private static final List<Operation> OPERATIONS = List.of(
            new Operation("currency.add", "增加货币", "gm:currency:add"),
            new Operation("currency.deduct", "扣除货币", "gm:currency:deduct"),
            new Operation("currency.block", "封禁货币获取", "gm:currency:block"),
            new Operation("currency.unblock", "解除货币封禁", "gm:currency:unblock"),
            new Operation("player.level", "设置等级", "gm:player:level"),
            new Operation("pet.grant", "发放宝宝", "gm:pet:grant"),
            new Operation("item.grant", "发放物品", "gm:item:grant"));

    private final ObjectMapper json;
    private final GmAdminAuth auth;
    private final GmGamePort port;
    private final GmCommandJournal journal;

    public GmPlayerCommandController(ObjectMapper json, GmAdminAuth auth, GmGamePort port, GmCommandJournal journal) {
        this.json = json;
        this.auth = auth;
        this.port = port;
        this.journal = journal;
    }

    @GetMapping("/catalog")
    public String catalog(HttpServletRequest req) {
        GmAdminAuth.Principal principal = auth.authenticate(req);
        if (!principal.isPermitted("gm:audit:read")
                && OPERATIONS.stream().noneMatch(op -> principal.isPermitted(op.permission))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有此 GM 操作权限");
        }
        boolean enabled = port.playerCommandsEnabled();
        ObjectNode result = json.createObjectNode().put("enabled", enabled);
        result.putObject("limits").put("maxItemCount", 99).put("maxLevel", GmGamePort.MAX_PLAYER_LEVEL);
        result.set("currencies", port.currencyTypes());
        var ops = result.putArray("operations");
        OPERATIONS.forEach(op -> ops.addObject()
                .put("code", op.code)
                .put("label", op.label)
                .put("permission", op.permission)
                .put("enabled", enabled && principal.isPermitted(op.permission))
                .put("description", "仅开发或测试服，目标玩家须在线且处于可操作状态；草稿5分钟内有效，执行结果未知时禁止重发。"));
        return ok(result);
    }

    @PostMapping("/drafts")
    public String prepare(HttpServletRequest req, @RequestBody String body) {
        auth.authenticate(req);
        ObjectNode input = parse(body);
        fields(input, Set.of("clientRequestId", "playerId", "operation", "arguments", "reason"));
        Operation operation = operation(text(input, "operation", 1, 32));
        String operator = auth.require(req, operation.permission);
        configured();
        String key = text(input, "clientRequestId", 36, 36);
        try {
            if (!UUID.fromString(key).toString().equals(key)) {
                bad("请求号无效");
            }
        } catch (IllegalArgumentException e) {
            bad("请求号无效");
        }
        String player = decimal(text(input, "playerId", 1, 19), "玩家 ID");
        String reason = text(input, "reason", 1, 191);
        if (reason.getBytes(StandardCharsets.UTF_8).length > 768
                || reason.codePoints().anyMatch(Character::isISOControl)) {
            bad("操作原因过长或包含控制字符");
        }
        if (!(input.get("arguments") instanceof ObjectNode args)) {
            bad("命令参数必须是对象");
            return null;
        }
        ObjectNode arguments = arguments(operation.code, args);
        ObjectNode target = port.player(player);
        if (!target.path("found").asBoolean() || !target.path("sessionFound").asBoolean()
                || !"SESSION_STATE_ONLINE".equals(target.path("session").path("state").asText())) {
            bad("目标玩家尚未在线，请核对玩家 ID 与当前会话");
        }
        ObjectNode frozen = json.createObjectNode().put("operation", operation.code);
        frozen.set("arguments", arguments);
        return ok(journal.preparePlayerCommand(operator, key, player, reason, frozen));
    }

    @PostMapping("/{id}/send")
    public String send(HttpServletRequest req, @PathVariable("id") String id) {
        GmAdminAuth.Principal principal = auth.authenticate(req);
        if (OPERATIONS.stream().noneMatch(op -> principal.isPermitted(op.permission))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有此 GM 操作权限");
        }
        decimal(id, "命令 ID");
        ObjectNode command = journal.get(id);
        if (!"PLAYER".equals(command.path("commandType").asText())) {
            bad("命令类型不符");
        }
        String actor = auth.require(req, operation(command.path("operation").asText()).permission);
        configured();
        return ok(journal.sendPlayerCommand(id, actor, port::sendPlayerCommand));
    }

    @GetMapping
    public String list(HttpServletRequest req,
                       @RequestParam(name = "page", defaultValue = "1") int page,
                       @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
                       @RequestParam(name = "playerId", required = false) String playerId,
                       @RequestParam(name = "status", required = false) String status) {
        auth.require(req, "gm:audit:read");
        return ok(journal.listPlayerCommands(page, pageSize, playerId, status));
    }

    @GetMapping("/{id}")
    public String get(HttpServletRequest req, @PathVariable("id") String id) {
        auth.require(req, "gm:audit:read");
        decimal(id, "命令 ID");
        return ok(journal.get(id, "PLAYER"));
    }

    @GetMapping("/{id}/audit")
    public String audit(HttpServletRequest req, @PathVariable("id") String id) {
        auth.require(req, "gm:audit:read");
        decimal(id, "命令 ID");
        return ok(journal.auditHistory(id, "PLAYER"));
    }

    private ObjectNode arguments(String code, ObjectNode args) {
        ObjectNode result = json.createObjectNode();
        switch (code) {
            case "currency.add", "currency.deduct", "currency.block", "currency.unblock" -> {
                boolean amountRequired = code.equals("currency.add") || code.equals("currency.deduct");
                fields(args, amountRequired ? Set.of("currencyType", "amount") : Set.of("currencyType"));
                int currency = integer(args, "currencyType", 0, Integer.MAX_VALUE);
                if (!port.supportsCurrency(currency)) {
                    bad("未知币种");
                }
                result.put("currencyType", currency);
                if (amountRequired) {
                    result.put("amount", decimal(text(args, "amount", 1, 19), "货币数量"));
                }
            }
            case "player.level" -> {
                fields(args, Set.of("level"));
                result.put("level", integer(args, "level", 1, GmGamePort.MAX_PLAYER_LEVEL));
            }
            case "pet.grant" -> {
                fields(args, Set.of("petTableId"));
                result.put("petTableId", integer(args, "petTableId", 1, Integer.MAX_VALUE));
            }
            case "item.grant" -> {
                fields(args, Set.of("configId", "count"));
                result.put("configId", integer(args, "configId", 1, Integer.MAX_VALUE))
                        .put("count", integer(args, "count", 1, 99));
            }
            default -> bad("未知 GM 操作");
        }
        return result;
    }

    private void configured() {
        if (!port.playerCommandsEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "玩家 GM 管理通道未配置或未完成服务部署");
        }
    }

    private Operation operation(String code) {
        return OPERATIONS.stream().filter(op -> op.code.equals(code)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知 GM 操作"));
    }

    private ObjectNode parse(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 16_384) {
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

    private static void fields(ObjectNode node, Set<String> names) {
        node.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) {
                bad("存在未知字段");
            }
        });
    }

    private static String text(ObjectNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            bad(field + " 必须是文本");
        }
        String result = value.textValue();
        int length = result.codePointCount(0, result.length());
        if (length < min || length > max || result.isBlank()) {
            bad(field + " 长度无效");
        }
        return result;
    }

    private static int integer(ObjectNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            bad(field + " 超出允许范围");
        }
        return value.intValue();
    }

    private static String decimal(String value, String label) {
        if (!value.matches("[1-9][0-9]{0,18}") || new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            bad(label + " 必须是正整数文本");
        }
        return value;
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
