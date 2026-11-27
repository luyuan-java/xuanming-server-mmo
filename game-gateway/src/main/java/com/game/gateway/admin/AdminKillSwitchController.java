package com.game.gateway.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.game.common.killswitch.KillSwitch;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运维 RPC 热关停管理入口（{@code GET/PUT/DELETE /admin/killswitch}，对齐主仓 {@code AdminKillSwitchController}）。
 * 规则读写 Redis 哈希 {@code <ns>:killswitch}，支持全部进程、{@code node_type} 与 {@code instance} 三级范围并在发现里核对在线节点。
 */
@RestController
@RequestMapping("/admin/killswitch")
public class AdminKillSwitchController {

    static final int MAX_TARGET_CHARS = 200;
    static final int MAX_REASON_CHARS = 256;
    static final int MAX_OPERATOR_CHARS = 64;
    static final int MAX_STATUS_CODE = 16;
    static final String OPERATOR_HEADER = "X-Admin-Operator";

    private static final Pattern TARGET_CHARS = Pattern.compile("[A-Za-z0-9_.$/*-]+");
    private static final Pattern NODE_TYPE = Pattern.compile("[A-Z][A-Za-z0-9]*NodeService");
    private static final Pattern NODE_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern OPERATOR_CHARS = Pattern.compile("[A-Za-z0-9_.@-]+");

    private static final Logger log = LoggerFactory.getLogger(AdminKillSwitchController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KillSwitchRuleStore store;

    public AdminKillSwitchController(KillSwitchRuleStore store) {
        this.store = store;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RuleRequest(String target, String nodeType, String instance, Boolean deny, String reason, Integer code) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RuleView(String key, String scope, String scopeValue, String target, boolean valid, boolean deny,
                           String reason, int code, String raw) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeleteResult(String key, long deleted) {
    }

    @GetMapping
    public List<RuleView> list() {
        Map<String, String> raw = call(store::list);
        List<RuleView> views = new ArrayList<>(raw.size());
        for (Map.Entry<String, String> entry : new TreeMap<>(raw).entrySet()) {
            String relative = KillSwitch.normalizePattern(entry.getKey());
            Optional<KillSwitch.ParsedScope> scope = KillSwitch.splitScope(relative);
            Optional<KillSwitch.Rule> rule = scope.isEmpty() ? Optional.empty() : KillSwitch.parseRule(entry.getValue());
            String scopeName = scope.map(s -> switch (s.kind()) {
                case ALL -> "all";
                case NODE_TYPE -> "type";
                case INSTANCE -> "instance";
            }).orElse("malformed");
            views.add(new RuleView(
                    relative,
                    scopeName,
                    scope.map(KillSwitch.ParsedScope::value).orElse(""),
                    scope.map(KillSwitch.ParsedScope::target).orElse(""),
                    rule.isPresent(),
                    rule.map(KillSwitch.Rule::deny).orElse(false),
                    rule.map(KillSwitch.Rule::reason).orElse(""),
                    rule.map(KillSwitch.Rule::code).orElse(0),
                    entry.getValue()));
        }
        return views;
    }

    @PutMapping
    public RuleView put(@RequestBody RuleRequest body, HttpServletRequest request) {
        if (body == null) {
            throw badRequest("request body is required");
        }
        String target = validTarget(body.target());
        if (body.deny() == null) {
            throw badRequest("deny is required (true = disable, false = exempt)");
        }
        String reason = body.reason() == null ? "" : body.reason();
        if (reason.codePointCount(0, reason.length()) > MAX_REASON_CHARS
                || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw badRequest("reason must be at most " + MAX_REASON_CHARS + " characters without control characters");
        }
        int code = body.code() == null ? 0 : body.code();
        if (code < 0 || code > MAX_STATUS_CODE) {
            throw badRequest("code must be 0-" + MAX_STATUS_CODE);
        }

        String relative = scopedPrefix(body.nodeType(), body.instance()) + target;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("deny", body.deny());
        if (!reason.isEmpty()) {
            value.put("reason", reason);
        }
        if (code != 0) {
            value.put("code", code);
        }
        String raw;
        try {
            raw = JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        call(() -> {
            store.put(relative, raw);
            return null;
        });
        log.warn("AUDIT killswitch rule set key={} deny={} code={} reason=\"{}\" operator={} remote={}",
                relative, body.deny(), code, reason, operator(request), request.getRemoteAddr());
        KillSwitch.ParsedScope parsed = KillSwitch.splitScope(relative).orElseThrow();
        String scopeName = switch (parsed.kind()) {
            case ALL -> "all";
            case NODE_TYPE -> "type";
            case INSTANCE -> "instance";
        };
        return new RuleView(relative, scopeName, parsed.value(), parsed.target(), true, body.deny(), reason, code, raw);
    }

    @DeleteMapping
    public DeleteResult delete(@RequestParam("key") String key, HttpServletRequest request) {
        String relative = KillSwitch.normalizePattern(key);
        if (relative.isEmpty() || relative.length() > MAX_TARGET_CHARS + 64
                || !TARGET_CHARS.matcher(relative).matches()) {
            throw badRequest("key must be a relative rule key such as ClientPlayerChat/SendChat, "
                    + "type/SceneNodeService/* or instance/<uuid>/*");
        }
        long deleted = call(() -> store.delete(relative));
        log.warn("AUDIT killswitch rule removed key={} deleted={} operator={} remote={}",
                relative, deleted, operator(request), request.getRemoteAddr());
        return new DeleteResult(relative, deleted);
    }

    String scopedPrefix(String nodeType, String instance) {
        boolean hasType = nodeType != null && !nodeType.isBlank();
        boolean hasInstance = instance != null && !instance.isBlank();
        if (!hasType && !hasInstance) {
            return "";
        }
        if (!hasType) {
            throw badRequest("instance requires node_type, used to check that the node is registered");
        }
        String type = nodeType.strip();
        if (!NODE_TYPE.matcher(type).matches()) {
            throw badRequest("node_type must be an eNodeType name such as SceneNodeService or GateNodeService");
        }
        Set<String> registered = call(() -> store.registeredNodeUuids(type));
        if (!hasInstance) {
            if (registered.isEmpty()) {
                throw badRequest("no node is registered under " + type + "; check node_type");
            }
            return KillSwitch.typeScopePrefix(type);
        }
        String uuid = instance.strip().toLowerCase(Locale.ROOT);
        if (!NODE_UUID.matcher(uuid).matches()) {
            throw badRequest("instance must be a node_uuid (8-4-4-4-12 hex)");
        }
        if (!registered.contains(uuid)) {
            throw badRequest("no " + type + " node with node_uuid " + uuid + " is registered");
        }
        return KillSwitch.instanceScopePrefix(uuid);
    }

    static String validTarget(String rawTarget) {
        String target = KillSwitch.normalizePattern(rawTarget);
        if (target.isEmpty() || target.length() > MAX_TARGET_CHARS || !TARGET_CHARS.matcher(target).matches()) {
            throw badRequest("target must be 1-" + MAX_TARGET_CHARS + " characters of [A-Za-z0-9_.$/*-]");
        }
        if ("*".equals(target)) {
            return target;
        }
        if (target.startsWith(KillSwitch.FEATURE_SCOPE)) {
            String featureName = target.substring(KillSwitch.FEATURE_SCOPE.length());
            if (featureName.isEmpty() || featureName.contains("/") || featureName.contains("*")) {
                throw badRequest("feature target must be feature/<name>");
            }
            return target;
        }
        int slash = target.indexOf('/');
        if (slash <= 0 || slash != target.lastIndexOf('/') || slash == target.length() - 1
                || target.startsWith(KillSwitch.TYPE_SCOPE) || target.startsWith(KillSwitch.INSTANCE_SCOPE)) {
            throw badRequest("target must be *, feature/<name>, Service/* or Service/Method; use node_type / instance for scopes");
        }
        String method = target.substring(slash + 1);
        if (method.contains("*") && !"*".equals(method)) {
            throw badRequest("only a whole method may be a wildcard (Service/*)");
        }
        return target;
    }

    private static String operator(HttpServletRequest request) {
        String raw = request.getHeader(OPERATOR_HEADER);
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String text = raw.strip();
        if (text.length() > MAX_OPERATOR_CHARS || !OPERATOR_CHARS.matcher(text).matches()) {
            return "invalid";
        }
        return text;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Kill switch admin call failed: {}", e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "killswitch store unavailable", e);
        }
    }
}
