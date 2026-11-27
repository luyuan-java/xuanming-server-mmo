package com.game.common.killswitch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * RPC 级热关停（对齐主仓 {@code go/shared/killswitch}，含节点类型 / 节点实例 / 服务 / 方法 / 功能组与受保护方法）：
 * 不改代码、不重启，运维写一条规则即可秒级短路目标调用。
 *
 * <p><b>铁律 fail-open</b>：没有规则、规则写坏、规则源连不上超过 {@code staleAfter}（本地快照主动作废）——一律放行。
 * 读路径经 {@link AtomicReference} 零锁、零阻塞、不抛异常。
 *
 * <p>规则键范围（docs/design/rpc-kill-switch.md §4）：
 * <ul>
 *   <li>{@code <目标>}：全部进程；</li>
 *   <li>{@code type/<eNodeType 名>/<目标>}：只对这一类节点生效；</li>
 *   <li>{@code instance/<节点 uuid>/<目标>}：只对这一个进程生效；</li>
 *   <li>{@code type} / {@code instance} 为第一段保留字，范围段残缺的键整条忽略。</li>
 * </ul>
 * 匹配顺序：范围「本进程实例 → 本进程类型 → 全部」，同一范围里「精确（全名/短名）→ 服务通配（全名/短名）→ feature/&lt;name&gt; → *」，
 * 第一条命中的规则说了算；本进程同层注册多身份且命中相反规则时取关停。
 * 受保护方法（握手、停机、会话收尾、资源收尾、在途补偿与回执）只认精确到方法的规则，{@code *} / {@code 服务/*} / {@code feature/*} 关不掉。
 */
public final class KillSwitch {

    /** 一条规则。{@code code} 是基线给 gRPC 状态码留的字段（Java 侧只记录，客户端一律看到「服务不可用」）。 */
    public record Rule(boolean deny, String reason, int code) {
    }

    /** 规则作用范围。 */
    public enum ScopeKind {
        ALL,
        NODE_TYPE,
        INSTANCE
    }

    /** 相对键拆出的范围、范围值与目标。 */
    public record ParsedScope(ScopeKind kind, String value, String target) {
    }

    static final String GLOBAL = "*";
    public static final String TYPE_SCOPE = "type/";
    public static final String INSTANCE_SCOPE = "instance/";
    public static final String FEATURE_SCOPE = "feature/";

    /** Java 侧受保护方法默认集合（会话/资源收尾、补偿与回执，通配与 feature 组均关不掉）。 */
    public static final Set<String> DEFAULT_PROTECTED_METHODS = Set.of(
            "/com.game.api.ClientMessageService/sessionClosed",
            "/com.game.api.ClientMessageService/abandonEnter",
            "/com.game.api.AccountLoginService/handlePassportEvent",
            "/com.game.api.GuildInternalService/purgeRemovedRole",
            "/com.game.api.MatchInternalService/purgeRemovedRole",
            "/com.game.api.SceneAssetOpService/abortDebit",
            "/com.game.api.SceneAssetOpService/ackReceipt",
            "/com.game.api.BattleRoomService/destroyBattle",
            "/com.game.api.BattleRoomService/cancelBattlePrepare",
            "/com.game.api.BattleRoomService/unsubscribeSpectate",
            "/com.game.api.SceneControlService/leaveScene",
            "/com.game.api.SceneControlService/destroyScene",
            "/com.game.api.SceneControlService/releaseOwner",
            "/com.game.api.GateControlService/kickPlayer",
            "/com.game.api.GateControlService/closeSession",
            "/com.game.api.RankAdminService/removeRankEntry",
            "/loginpb.ClientPlayerLogin/LeaveGame",
            "/gatepb.GateClientSpectate/UnsubscribeBattleSpectate");

    /** 同基线 json.Unmarshal：顶层值之后还有内容（多敲的括号、粘贴两遍）算写坏，整条忽略。 */
    private static final ObjectReader JSON = new ObjectMapper().reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 规则源失联多久后本地快照作废、整体放行（基线 StaleAfter 缺省 1 分钟）。 */
    public static final long DEFAULT_STALE_AFTER_NANOS = 60_000_000_000L;

    private record Snapshot(Map<String, Rule> rules, long syncedAtNanos) {
    }

    private record Identity(List<String> nodeTypes, List<String> instances, List<List<String>> layers) {
        static Identity of(Collection<String> nodeTypes, Collection<String> instances) {
            List<String> types = List.copyOf(nodeTypes);
            List<String> insts = List.copyOf(instances);
            List<String> instanceLayer = new ArrayList<>(insts.size());
            for (String uuid : insts) {
                instanceLayer.add(instanceScopePrefix(uuid));
            }
            List<String> typeLayer = new ArrayList<>(types.size());
            for (String nt : types) {
                typeLayer.add(typeScopePrefix(nt));
            }
            return new Identity(types, insts, List.of(
                    List.copyOf(instanceLayer),
                    List.copyOf(typeLayer),
                    List.of("")));
        }

        boolean applies(String pattern) {
            Optional<ParsedScope> parsed = splitScope(pattern);
            if (parsed.isEmpty()) {
                return false;
            }
            return switch (parsed.get().kind()) {
                case NODE_TYPE -> nodeTypes.contains(parsed.get().value());
                case INSTANCE -> instances.contains(parsed.get().value());
                case ALL -> true;
            };
        }
    }

    private static final AtomicReference<KillSwitch> GLOBAL_INSTANCE = new AtomicReference<>();

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(Map.of(), 0));
    private final AtomicReference<Identity> identity = new AtomicReference<>(Identity.of(List.of(), List.of()));
    private final AtomicReference<Set<String>> protectedMethods = new AtomicReference<>(Set.of());
    private final AtomicReference<Map<String, List<String>>> methodFeatures = new AtomicReference<>(Map.of());
    private final Object mutationLock = new Object();
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

    public static String typeScopePrefix(String nodeType) {
        return TYPE_SCOPE + nodeType + "/";
    }

    public static String instanceScopePrefix(String nodeUuid) {
        return INSTANCE_SCOPE + nodeUuid + "/";
    }

    /**
     * 把相对键拆成范围、范围值与目标；范围段残缺（如 {@code type/}、{@code type/X}、{@code instance//*}）返回空。
     */
    public static Optional<ParsedScope> splitScope(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Optional.empty();
        }
        ScopeKind kind;
        String rest;
        if (pattern.startsWith(TYPE_SCOPE)) {
            kind = ScopeKind.NODE_TYPE;
            rest = pattern.substring(TYPE_SCOPE.length());
        } else if (pattern.startsWith(INSTANCE_SCOPE)) {
            kind = ScopeKind.INSTANCE;
            rest = pattern.substring(INSTANCE_SCOPE.length());
        } else {
            return Optional.of(new ParsedScope(ScopeKind.ALL, "", pattern));
        }
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return Optional.empty();
        }
        String value = rest.substring(0, slash);
        String target = rest.substring(slash + 1);
        if (target.startsWith("/")) {
            return Optional.empty();
        }
        return Optional.of(new ParsedScope(kind, value, target));
    }

    /** 登记本进程节点类型（如 {@code SceneNodeService}），此后 {@code type/<名>/} 规则对本进程生效。 */
    public void addNodeType(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return;
        }
        String clean = nodeType.strip();
        synchronized (mutationLock) {
            Identity cur = identity.get();
            if (cur.nodeTypes().contains(clean)) {
                return;
            }
            List<String> next = new ArrayList<>(cur.nodeTypes());
            next.add(clean);
            identity.set(Identity.of(next, cur.instances()));
        }
    }

    /** 登记本进程节点实例 UUID，此后 {@code instance/<uuid>/} 规则对本进程生效。 */
    public void addInstance(String nodeUuid) {
        if (nodeUuid == null || nodeUuid.isBlank()) {
            return;
        }
        String clean = nodeUuid.strip();
        synchronized (mutationLock) {
            Identity cur = identity.get();
            if (cur.instances().contains(clean)) {
                return;
            }
            List<String> next = new ArrayList<>(cur.instances());
            next.add(clean);
            identity.set(Identity.of(cur.nodeTypes(), next));
        }
    }

    /** 登记受保护方法全名（自动补前导 {@code /}）；受保护方法只认精确到方法的规则。 */
    public void protectMethods(Collection<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return;
        }
        synchronized (mutationLock) {
            Set<String> next = new HashSet<>(protectedMethods.get());
            for (String method : methods) {
                if (method != null && !method.isBlank()) {
                    String m = method.strip();
                    next.add(m.startsWith("/") ? m : "/" + m);
                }
            }
            protectedMethods.set(Set.copyOf(next));
        }
    }

    /** 将一组方法归入功能组 {@code feature/<name>}（优先级在 {@code 服务/*} 之后、{@code *} 之前）。 */
    public void registerFeature(String name, Collection<String> methods) {
        if (name == null || methods == null || methods.isEmpty()) {
            return;
        }
        String cleanName = name.strip();
        if (cleanName.startsWith(FEATURE_SCOPE)) {
            cleanName = cleanName.substring(FEATURE_SCOPE.length()).strip();
        }
        if (cleanName.isEmpty()) {
            return;
        }
        String featureKey = FEATURE_SCOPE + cleanName;
        synchronized (mutationLock) {
            Map<String, List<String>> cur = methodFeatures.get();
            Map<String, TreeSet<String>> next = new HashMap<>();
            cur.forEach((k, v) -> next.put(k, new TreeSet<>(v)));
            for (String method : methods) {
                String m = normalizePattern(method);
                if (!m.isEmpty()) {
                    next.computeIfAbsent(m, ignored -> new TreeSet<>()).add(featureKey);
                }
            }
            Map<String, List<String>> frozen = new HashMap<>(next.size() * 2);
            next.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
            methodFeatures.set(Map.copyOf(frozen));
        }
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

    /** 当前（未作废且适用于本进程的）规则条数（别的类型、别的实例的规则不计）。 */
    public int ruleCount() {
        Snapshot s = snapshot.get();
        if (stale(s) || s.rules().isEmpty()) {
            return 0;
        }
        Identity id = identity.get();
        int count = 0;
        for (String pattern : s.rules().keySet()) {
            if (id.applies(pattern)) {
                count++;
            }
        }
        return count;
    }

    public Map<String, Rule> rules() {
        return snapshot.get().rules();
    }

    /**
     * 这个方法被关停了吗？{@code fullMethod} 形如 {@code /pkg.Service/Method}（或 {@code pkg.Service/Method}）。
     * 匹配顺序：范围「实例 → 类型 → 全部」，同范围「精确 → 服务通配 → feature/&lt;name&gt; → *」；
     * 受保护方法只认精确规则。命中的规则 deny 为真时返回它；其余返回空。
     */
    public Optional<Rule> blocked(String fullMethod) {
        Snapshot s = snapshot.get();
        if (s.rules().isEmpty() || stale(s)) {
            return Optional.empty();
        }
        boolean isProtected = isProtected(fullMethod);
        List<String> keys = isProtected ? exactKeys(fullMethod) : matchKeys(fullMethod);
        List<String> featureKeys = isProtected ? List.of() : featureKeysFor(fullMethod);
        for (List<String> layer : identity.get().layers()) {
            if (layer.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                if (GLOBAL.equals(key) && !featureKeys.isEmpty()) {
                    Rule featureRule = lookupFeatureLayer(s.rules(), layer, featureKeys);
                    if (featureRule != null) {
                        return featureRule.deny() ? Optional.of(featureRule) : Optional.empty();
                    }
                }
                Rule rule = lookupLayer(s.rules(), layer, key);
                if (rule != null) {
                    return rule.deny() ? Optional.of(rule) : Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private boolean isProtected(String fullMethod) {
        Set<String> set = protectedMethods.get();
        if (set.isEmpty() || fullMethod == null) {
            return false;
        }
        String normalized = fullMethod.startsWith("/") ? fullMethod : "/" + fullMethod;
        return set.contains(normalized);
    }

    private List<String> featureKeysFor(String fullMethod) {
        Map<String, List<String>> map = methodFeatures.get();
        if (map.isEmpty() || fullMethod == null) {
            return List.of();
        }
        String m = fullMethod.startsWith("/") ? fullMethod.substring(1) : fullMethod;
        return map.getOrDefault(m, List.of());
    }

    private static Rule lookupFeatureLayer(Map<String, Rule> rules, List<String> layer, List<String> featureKeys) {
        Rule allow = null;
        for (String fk : featureKeys) {
            Rule r = lookupLayer(rules, layer, fk);
            if (r == null) {
                continue;
            }
            if (r.deny()) {
                return r;
            }
            allow = r;
        }
        return allow;
    }

    /** 同层多身份命中相反规则时取关停（deny=true 优先）。 */
    private static Rule lookupLayer(Map<String, Rule> rules, List<String> layer, String key) {
        Rule allow = null;
        for (String scopePrefix : layer) {
            Rule r = rules.get(scopePrefix + key);
            if (r == null) {
                continue;
            }
            if (r.deny()) {
                return r;
            }
            allow = r;
        }
        return allow;
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

    /** 精确到方法的候选键（全限定服务名 → 短服务名），受保护方法只认它们。 */
    public static List<String> exactKeys(String fullMethod) {
        String m = fullMethod == null ? "" : (fullMethod.startsWith("/") ? fullMethod.substring(1) : fullMethod);
        if (m.isEmpty()) {
            return List.of();
        }
        int slash = m.lastIndexOf('/');
        if (slash <= 0 || slash == m.length() - 1) {
            return List.of(m);
        }
        String serviceFull = m.substring(0, slash);
        String method = m.substring(slash + 1);
        int dot = serviceFull.lastIndexOf('.');
        String serviceShort = dot >= 0 ? serviceFull.substring(dot + 1) : serviceFull;
        if (!serviceShort.equals(serviceFull) && !serviceShort.isEmpty()) {
            return List.of(serviceFull + "/" + method, serviceShort + "/" + method);
        }
        return List.of(serviceFull + "/" + method);
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
