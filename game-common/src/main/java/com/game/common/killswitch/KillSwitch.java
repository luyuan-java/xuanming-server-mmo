package com.game.common.killswitch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 按方法热关停（mmorpg go/shared/killswitch 的 Java 版）：不改代码、不重启，运维写一条规则就能把某个方法 / 某个服务 / 全部方法秒级短路。
 *
 * <p><b>铁律 fail-open</b>：没有规则、规则写坏、规则源连不上超过 {@code staleAfter}（本地快照主动作废）——一律放行。
 * 开关系统自身故障绝不能拖垮业务。规则快照放在 {@link AtomicReference} 里，读路径零锁、零阻塞、不抛异常。
 *
 * <p>规则键（与基线同形）：{@code pkg.Service/Method}、{@code Service/Method}（短名）、{@code pkg.Service/*}、{@code Service/*}、
 * {@code *}（全局）。匹配按这个顺序取第一条命中的规则：精确规则写 {@code deny=false} 可以把自己从 {@code Service/*} 或 {@code *} 里豁免出来。
 * 规则值见 {@link #parseRule}。线程安全。
 */
public final class KillSwitch {

    /** 一条规则。{@code code} 是基线给 gRPC 状态码留的字段（Java 侧只记录，客户端一律看到「服务不可用」）。 */
    public record Rule(boolean deny, String reason, int code) {
    }

    static final String GLOBAL = "*";
    /** 同基线 json.Unmarshal：顶层值之后还有内容（多敲的括号、粘贴两遍）算写坏，整条忽略。 */
    private static final ObjectReader JSON = new ObjectMapper().reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 规则源失联多久后本地快照作废、整体放行（基线 StaleAfter 缺省 1 分钟）。 */
    public static final long DEFAULT_STALE_AFTER_NANOS = 60_000_000_000L;

    private record Snapshot(Map<String, Rule> rules, long syncedAtNanos) {
    }

    private static final AtomicReference<KillSwitch> GLOBAL_INSTANCE = new AtomicReference<>();

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(Map.of(), 0));
    private volatile Consumer<String> blockedListener = method -> { };
    private final long staleAfterNanos;
    private final LongSupplier nanoClock;

    public KillSwitch(long staleAfterNanos, LongSupplier nanoClock) {
        this.staleAfterNanos = staleAfterNanos;
        this.nanoClock = nanoClock;
    }

    /** 本进程的开关（Dubbo 过滤器经它取；没装就是永远放行）。 */
    public static Optional<KillSwitch> global() {
        return Optional.ofNullable(GLOBAL_INSTANCE.get());
    }

    public static void installGlobal(KillSwitch killSwitch) {
        GLOBAL_INSTANCE.set(killSwitch);
    }

    /** 被短路时的回调（指标）；{@code method} 必须是有界取值（客户端白名单方法、本仓库 Dubbo 接口方法）。 */
    public void onBlocked(Consumer<String> listener) {
        this.blockedListener = listener;
    }

    /** 记一次短路（调用方在真正拒绝时调用）。 */
    public void recordBlocked(String method) {
        try {
            blockedListener.accept(method);
        } catch (RuntimeException e) {
            // 指标故障不影响拒绝本身
        }
    }

    /** 换一份规则快照（同步成功时调用；同步时刻取单调时钟）。 */
    public void setRules(Map<String, Rule> rules) {
        snapshot.set(new Snapshot(Map.copyOf(rules), nanoClock.getAsLong()));
    }

    /** 当前（未作废的）规则条数。 */
    public int ruleCount() {
        Snapshot s = snapshot.get();
        return stale(s) ? 0 : s.rules().size();
    }

    public Map<String, Rule> rules() {
        return snapshot.get().rules();
    }

    /**
     * 这个方法被关停了吗？{@code fullMethod} 形如 {@code /pkg.Service/Method}（或 {@code pkg.Service/Method}）。
     * 命中的规则 deny 为真时返回它；没有规则、快照已作废、命中的是豁免规则都返回空。
     */
    public Optional<Rule> blocked(String fullMethod) {
        Snapshot s = snapshot.get();
        if (s.rules().isEmpty() || stale(s)) {
            return Optional.empty();
        }
        for (String key : matchKeys(fullMethod)) {
            Rule rule = s.rules().get(key);
            if (rule != null) {
                return rule.deny() ? Optional.of(rule) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private boolean stale(Snapshot s) {
        if (staleAfterNanos < 0 || s.syncedAtNanos() == 0) {
            return false;
        }
        return nanoClock.getAsLong() - s.syncedAtNanos() > staleAfterNanos;
    }

    /**
     * 规则键的规范形（基线 PatternFromKey 的容忍）：去首尾空白与开头的斜杠。各进程读规则、运维接口写 / 删规则都经它，
     * 保证「手写的 {@code /X}」与「接口写的 {@code X}」是同一条规则。
     */
    public static String normalizePattern(String field) {
        String p = field == null ? "" : field.strip();
        int i = 0;
        while (i < p.length() && p.charAt(i) == '/') {
            i++;
        }
        return p.substring(i);
    }

    /** 基线 MatchKeys：精确（全名 / 短名）→ 服务通配（全名 / 短名）→ 全局。 */
    public static List<String> matchKeys(String fullMethod) {
        String m = fullMethod.startsWith("/") ? fullMethod.substring(1) : fullMethod;
        if (m.isEmpty()) {
            return List.of(GLOBAL);
        }
        int slash = m.lastIndexOf('/');
        if (slash <= 0 || slash == m.length() - 1) {
            return List.of(m, GLOBAL);
        }
        String serviceFull = m.substring(0, slash);
        String method = m.substring(slash + 1);
        int dot = serviceFull.lastIndexOf('.');
        String serviceShort = dot >= 0 ? serviceFull.substring(dot + 1) : serviceFull;
        boolean hasShort = !serviceShort.equals(serviceFull) && !serviceShort.isEmpty();
        List<String> keys = new ArrayList<>(5);
        keys.add(serviceFull + "/" + method);
        if (hasShort) {
            keys.add(serviceShort + "/" + method);
        }
        keys.add(serviceFull + "/*");
        if (hasShort) {
            keys.add(serviceShort + "/*");
        }
        keys.add(GLOBAL);
        return keys;
    }

    /**
     * 解析规则值（基线 ParseRule）：空串 = 放行；{@code {"deny":true,"reason":"...","code":14}}；
     * {@code 1 / true / on / yes / deny} = 关停，{@code 0 / false / off / no / allow} = 放行。写坏的返回空（调用方忽略这条并记日志）。
     */
    public static Optional<Rule> parseRule(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) {
            return Optional.of(new Rule(false, "", 0));
        }
        if (s.charAt(0) == '{') {
            return parseJson(s);
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "1", "true", "on", "yes", "deny" -> Optional.of(new Rule(true, "", 0));
            case "0", "false", "off", "no", "allow" -> Optional.of(new Rule(false, "", 0));
            default -> Optional.empty();
        };
    }

    /** JSON 形式：只取 {@code deny}（布尔）、{@code reason}（字符串）、{@code code}（非负整数）；类型不对或不是对象算写坏，别的键忽略。 */
    static Optional<Rule> parseJson(String s) {
        JsonNode node;
        try {
            node = JSON.readTree(s);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode deny = node.get("deny");
        JsonNode reason = node.get("reason");
        JsonNode code = node.get("code");
        if ((deny != null && !deny.isBoolean() && !deny.isNull()) || (reason != null && !reason.isTextual() && !reason.isNull())
                || (code != null && !code.isNull() && (!code.canConvertToInt() || !code.isIntegralNumber() || code.asInt() < 0))) {
            return Optional.empty();
        }
        return Optional.of(new Rule(deny != null && deny.asBoolean(), reason == null || reason.isNull() ? "" : reason.asText(),
                code == null || code.isNull() ? 0 : code.asInt()));
    }
}
