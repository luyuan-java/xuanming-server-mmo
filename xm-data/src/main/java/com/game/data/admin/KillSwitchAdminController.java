package com.game.data.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.game.common.killswitch.KillSwitch;
import com.game.discovery.RedisKeys;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运维：按方法热关停（基线往 etcd {@code /mmorpg/killswitch/<规则键>} 写一条规则；Java 版写 Redis 哈希 {@code xm:killswitch}，
 * 接客的各进程每秒全量读一次，见 {@code RedisKillSwitchSync}）。
 * <ul>
 *   <li>{@code GET /admin/killswitch}：全部规则（含写坏、各进程会忽略的值，{@code valid=false}）；</li>
 *   <li>{@code PUT /admin/killswitch {pattern, deny, reason?, code?}}：写一条规则。规则键：客户端方法 {@code friendpb.ClientPlayerFriend/AddFriend}
 *       或 {@code ClientPlayerFriend/AddFriend}、整个服务 {@code ClientPlayerFriend/*}、本仓库 Dubbo 接口方法
 *       {@code com.game.api.AccountLoginService/login}、全局 {@code *}；精确规则写 {@code deny=false} 可以把自己从通配里豁免出来；</li>
 *   <li>{@code DELETE /admin/killswitch?pattern=...}：删一条规则：规范化后同名的字段全删（恒 200，回删掉的原始字段名）。</li>
 * </ul>
 * 被关停的客户端方法，客户端看到信封 1003；被关停的 Dubbo 方法，调用方拿到 RpcException。写操作进审计日志。
 */
@RestController
public class KillSwitchAdminController {

    public static final String PATH = "/admin/killswitch";
    static final int MAX_PATTERN_CHARS = 200;
    static final int MAX_REASON_CHARS = 256;
    /** 规则键只收这些字符：标识符、点、斜杠、星号（不收空白与控制字符：会进审计日志、也是 Redis 字段名）。 */
    static final Pattern PATTERN_CHARS = Pattern.compile("[A-Za-z0-9_.$/*-]+");

    private static final Logger audit = LoggerFactory.getLogger(AdminAuthFilter.AUDIT_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(KillSwitchAdminController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Redis 客户端懒加载（xm-data 的主业务是审计落库，Redis 不可达不能挡启动）。 */
    private final Supplier<RMap<String, String>> rules;

    public KillSwitchAdminController(ObjectProvider<RedissonClient> redis) {
        this.rules = () -> redis.getObject().getMap(RedisKeys.killSwitch(), StringCodec.INSTANCE);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RuleBody(String pattern, Boolean deny, String reason, Integer code) {
    }

    /** {@code pattern} 是规范形（DELETE 收它）；{@code field} 是 Redis 里的原始字段名（手写的规则可能带开头斜杠或空白）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RuleView(String pattern, String field, boolean valid, boolean deny, String reason, int code, String raw) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RemoveResult(String pattern, List<String> removedFields) {
    }

    @GetMapping(PATH)
    public List<RuleView> list() {
        Map<String, String> raw = redis(() -> rules.get().readAllMap());
        return raw.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(e -> {
                    Optional<KillSwitch.Rule> rule = KillSwitch.parseRule(e.getValue());
                    return new RuleView(KillSwitch.normalizePattern(e.getKey()), e.getKey(), rule.isPresent(),
                            rule.map(KillSwitch.Rule::deny).orElse(false), rule.map(KillSwitch.Rule::reason).orElse(""),
                            rule.map(KillSwitch.Rule::code).orElse(0), e.getValue());
                })
                .toList();
    }

    @PutMapping(PATH)
    public RuleView put(@RequestBody RuleBody body, HttpServletRequest request) {
        String pattern = validPattern(body.pattern());
        if (body.deny() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deny 必填（true = 关停，false = 豁免）");
        }
        String reason = body.reason() == null ? "" : body.reason();
        if (reason.codePointCount(0, reason.length()) > MAX_REASON_CHARS || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason 至多 " + MAX_REASON_CHARS + " 字符、不含控制字符");
        }
        int code = body.code() == null ? 0 : body.code();
        if (code < 0 || code > 16) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code 须在 0–16（gRPC 状态码，0 = 缺省 Unavailable）");
        }
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
        redis(() -> rules.get().fastPut(pattern, raw));
        audit.warn("killswitch set pattern={} deny={} reason={} code={} operator={}", pattern, body.deny(), AdminAuthFilter.printable(reason),
                code, ZoneAdminController.operator(request));
        return new RuleView(pattern, pattern, true, body.deny(), reason, code, raw);
    }

    /**
     * 删掉规范化后等于 {@code pattern} 的全部字段（含手写的 {@code /X}、{@code " X "}：各进程把它们读成同一条规则，只删规范形会删不掉）。
     * 恒 200，{@code removed_fields} 为空表示本来就没有。
     */
    @DeleteMapping(PATH)
    public RemoveResult delete(@RequestParam("pattern") String pattern, HttpServletRequest request) {
        String normalized = validPattern(pattern);
        List<String> fields = redis(() -> rules.get().readAllKeySet()).stream()
                .filter(f -> KillSwitch.normalizePattern(f).equals(normalized))
                .sorted()
                .toList();
        if (!fields.isEmpty()) {
            redis(() -> rules.get().fastRemove(fields.toArray(String[]::new)));
        }
        audit.warn("killswitch remove pattern={} fields={} operator={}", normalized,
                fields.stream().map(AdminAuthFilter::printable).toList(), ZoneAdminController.operator(request));
        return new RemoveResult(normalized, fields);
    }

    /** 去首尾空白与开头的斜杠（{@link KillSwitch#normalizePattern}，与各进程读规则时同一口径），再校验字符集与长度。 */
    static String validPattern(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pattern 必填");
        }
        String p = KillSwitch.normalizePattern(raw);
        if (p.isEmpty() || p.length() > MAX_PATTERN_CHARS || !PATTERN_CHARS.matcher(p).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pattern 须是 1–" + MAX_PATTERN_CHARS + " 个 [A-Za-z0-9_.$/*-] 字符（如 ClientPlayerFriend/AddFriend、ClientPlayerFriend/*、*）");
        }
        return p;
    }

    private static <T> T redis(Supplier<T> call) {
        try {
            return call.get();
        } catch (RedisException | BeanCreationException e) {
            log.warn("热关停运维接口：Redis 调用失败", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用", e);
        }
    }
}
