package com.game.match.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.proto.BattleScript;
import com.game.proto.BattleScriptBinding;
import com.game.proto.BattleScriptCondition;
import com.game.proto.BattleScriptConfig;
import com.game.proto.BattleScriptRole;
import com.game.proto.BattleScriptRule;
import com.game.proto.BattleScriptTarget;
import com.game.proto.BattleScriptTargetFilter;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleScriptCondition;
import com.game.proto.eBattleScriptSide;
import com.game.proto.eBattleScriptTargetFilter;
import com.game.proto.eBattleScriptTargetOrder;
import com.game.proto.eBattleScriptTargetSide;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 官方战斗剧本只读目录（对齐 Go {@code go/match/internal/battlescript/catalog.go}，内置 {@code match/official_scripts.json}）。 */
public final class BattleScriptCatalog {

    public static final int MAX_OFFICIAL_SCRIPTS = 256;
    public static final int MAX_BINDINGS = 1024;
    public static final String DEFAULT_RESOURCE = "/match/official_scripts.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    public record ModeKey(int matchMode, int battleConfigId) {
    }

    private record BindingKey(int matchMode, int battleConfigId, eBattleScriptSide side) {
    }

    private final Map<Integer, BattleScript> scripts;
    private final List<BattleScript> orderedScripts;
    private final Map<BindingKey, BattleScript> bindings;

    private BattleScriptCatalog(Map<Integer, BattleScript> scripts, List<BattleScript> orderedScripts,
                                Map<BindingKey, BattleScript> bindings) {
        this.scripts = Collections.unmodifiableMap(scripts);
        this.orderedScripts = List.copyOf(orderedScripts);
        this.bindings = Collections.unmodifiableMap(bindings);
    }

    public static BattleScriptCatalog empty() {
        return new BattleScriptCatalog(Map.of(), List.of(), Map.of());
    }

    /** 加载并严格校验内置官方剧本；坏配置抛异常拒绝启动。 */
    public static BattleScriptCatalog loadOfficial(BattleScriptValidator.Tables tables) {
        try (InputStream in = BattleScriptCatalog.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing official battle scripts resource: " + DEFAULT_RESOURCE);
            }
            return parseJson(in.readAllBytes(), tables);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read official battle scripts", e);
        }
    }

    /** 严格解析官方配置 JSON（拒绝未知字段与非法枚举名）并校验。 */
    public static BattleScriptCatalog parseJson(byte[] raw, BattleScriptValidator.Tables tables) {
        try {
            JsonNode root = JSON.readTree(raw);
            BattleScriptConfig config = parseConfig(root);
            return build(config, tables);
        } catch (IOException e) {
            throw new IllegalArgumentException("battle script config is not valid JSON", e);
        }
    }

    public static BattleScriptCatalog build(BattleScriptConfig config, BattleScriptValidator.Tables tables) {
        if (config.getScriptsCount() > MAX_OFFICIAL_SCRIPTS || config.getBindingsCount() > MAX_BINDINGS) {
            throw new IllegalArgumentException("battle script config exceeds " + MAX_OFFICIAL_SCRIPTS
                    + " scripts or " + MAX_BINDINGS + " bindings");
        }
        Map<Integer, BattleScript> scripts = new HashMap<>();
        List<Integer> ids = new ArrayList<>(config.getScriptsCount());
        for (BattleScript s : config.getScriptsList()) {
            int id = s.getScriptId();
            if (id == 0 || scripts.containsKey(id)) {
                throw new IllegalArgumentException("script " + id + ": script_id must be non-zero and unique");
            }
            BattleScript normalized = BattleScriptValidator.normalize(s, tables);
            scripts.put(id, normalized);
            ids.add(id);
        }
        ids.sort(Integer::compareUnsigned);
        List<BattleScript> ordered = new ArrayList<>(ids.size());
        for (int id : ids) {
            ordered.add(scripts.get(id));
        }

        Map<BindingKey, BattleScript> bindings = new HashMap<>();
        for (BattleScriptBinding b : config.getBindingsList()) {
            String where = "binding (match_mode " + b.getMatchMode()
                    + ", battle_config_id " + b.getBattleConfigId() + ", side " + b.getSideValue() + ")";
            BattleScript script = scripts.get(b.getScriptId());
            if (script == null) {
                throw new IllegalArgumentException(where + ": unknown script " + b.getScriptId());
            }
            if (!BattleScriptValidator.isKnownMatchMode(b.getMatchMode())) {
                throw new IllegalArgumentException(where + ": unknown match_mode");
            }
            if (b.getBattleConfigId() != 0 && !tables.hasDungeon(b.getBattleConfigId())) {
                throw new IllegalArgumentException(where + ": unknown dungeon");
            }
            if (b.getSide() == eBattleScriptSide.BATTLE_SCRIPT_SIDE_MONSTER
                    && b.getMatchMode() != 0
                    && !BattleScriptValidator.isPveMatchMode(b.getMatchMode())) {
                throw new IllegalArgumentException(where + ": the monster side only exists in PVE modes");
            }
            BattleScriptValidator.checkSide(script, b.getSide(), tables);
            BindingKey key = new BindingKey(b.getMatchMode(), b.getBattleConfigId(), b.getSide());
            if (bindings.putIfAbsent(key, script) != null) {
                throw new IllegalArgumentException(where + ": duplicate binding");
            }
        }
        return new BattleScriptCatalog(scripts, ordered, bindings);
    }

    public Optional<BattleScript> official(int id) {
        return Optional.ofNullable(scripts.get(id));
    }

    public List<BattleScript> officialScripts() {
        return orderedScripts;
    }

    public Optional<BattleScript> bound(int matchMode, int battleConfigId, eBattleScriptSide side) {
        for (ModeKey key : lookupKeys(matchMode, battleConfigId)) {
            BattleScript s = bindings.get(new BindingKey(key.matchMode(), key.battleConfigId(), side));
            if (s != null) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public static List<ModeKey> lookupKeys(int matchMode, int battleConfigId) {
        return List.of(
                new ModeKey(matchMode, battleConfigId),
                new ModeKey(matchMode, 0),
                new ModeKey(0, battleConfigId),
                new ModeKey(0, 0));
    }

    // ---- 严格 protobuf-JSON 手工映射（零新增依赖，拒绝未知字段） ----

    private static BattleScriptConfig parseConfig(JsonNode node) {
        requireObject(node, "root");
        rejectUnknownFields(node, Set.of("scripts", "bindings"), "root");
        BattleScriptConfig.Builder b = BattleScriptConfig.newBuilder();
        if (node.has("scripts")) {
            for (JsonNode s : requireArray(node.get("scripts"), "scripts")) {
                b.addScripts(parseScript(s));
            }
        }
        if (node.has("bindings")) {
            for (JsonNode bind : requireArray(node.get("bindings"), "bindings")) {
                b.addBindings(parseBinding(bind));
            }
        }
        return b.build();
    }

    private static BattleScript parseScript(JsonNode node) {
        requireObject(node, "script");
        rejectUnknownFields(node, Set.of("schema_version", "script_id", "name", "description", "roles"), "script");
        BattleScript.Builder b = BattleScript.newBuilder();
        if (node.has("schema_version")) b.setSchemaVersion(node.get("schema_version").asInt());
        if (node.has("script_id")) b.setScriptId(node.get("script_id").asInt());
        if (node.has("name")) b.setName(node.get("name").asText());
        if (node.has("description")) b.setDescription(node.get("description").asText());
        if (node.has("roles")) {
            for (JsonNode r : requireArray(node.get("roles"), "roles")) {
                b.addRoles(parseRole(r));
            }
        }
        return b.build();
    }

    private static BattleScriptRole parseRole(JsonNode node) {
        requireObject(node, "role");
        rejectUnknownFields(node, Set.of("role_id", "order", "name", "actor_type", "class_id", "monster_id", "max_members", "rules"), "role");
        BattleScriptRole.Builder b = BattleScriptRole.newBuilder();
        if (node.has("role_id")) b.setRoleId(node.get("role_id").asInt());
        if (node.has("order")) b.setOrder(node.get("order").asInt());
        if (node.has("name")) b.setName(node.get("name").asText());
        if (node.has("actor_type")) b.setActorType(parseEnum(node.get("actor_type"), eBattleActorType::valueOf, eBattleActorType::forNumber));
        if (node.has("class_id")) b.setClassId(node.get("class_id").asInt());
        if (node.has("monster_id")) b.setMonsterId(node.get("monster_id").asInt());
        if (node.has("max_members")) b.setMaxMembers(node.get("max_members").asInt());
        if (node.has("rules")) {
            for (JsonNode r : requireArray(node.get("rules"), "rules")) {
                b.addRules(parseRule(r));
            }
        }
        return b.build();
    }

    private static BattleScriptRule parseRule(JsonNode node) {
        requireObject(node, "rule");
        rejectUnknownFields(node, Set.of("priority", "conditions", "action", "skill_id", "target", "note"), "rule");
        BattleScriptRule.Builder b = BattleScriptRule.newBuilder();
        if (node.has("priority")) b.setPriority(node.get("priority").asInt());
        if (node.has("conditions")) {
            for (JsonNode c : requireArray(node.get("conditions"), "conditions")) {
                b.addConditions(parseCondition(c));
            }
        }
        if (node.has("action")) b.setAction(parseEnum(node.get("action"), eBattleActionType::valueOf, eBattleActionType::forNumber));
        if (node.has("skill_id")) b.setSkillId(node.get("skill_id").asInt());
        if (node.has("target")) b.setTarget(parseTarget(node.get("target")));
        if (node.has("note")) b.setNote(node.get("note").asText());
        return b.build();
    }

    private static BattleScriptCondition parseCondition(JsonNode node) {
        requireObject(node, "condition");
        rejectUnknownFields(node, Set.of("kind", "p1", "p2", "negate"), "condition");
        BattleScriptCondition.Builder b = BattleScriptCondition.newBuilder();
        if (node.has("kind")) b.setKind(parseEnum(node.get("kind"), eBattleScriptCondition::valueOf, eBattleScriptCondition::forNumber));
        if (node.has("p1")) b.setP1(node.get("p1").asInt());
        if (node.has("p2")) b.setP2(node.get("p2").asInt());
        if (node.has("negate")) b.setNegate(node.get("negate").asBoolean());
        return b.build();
    }

    private static BattleScriptTarget parseTarget(JsonNode node) {
        requireObject(node, "target");
        rejectUnknownFields(node, Set.of("side", "filters", "order"), "target");
        BattleScriptTarget.Builder b = BattleScriptTarget.newBuilder();
        if (node.has("side")) b.setSide(parseEnum(node.get("side"), eBattleScriptTargetSide::valueOf, eBattleScriptTargetSide::forNumber));
        if (node.has("order")) b.setOrder(parseEnum(node.get("order"), eBattleScriptTargetOrder::valueOf, eBattleScriptTargetOrder::forNumber));
        if (node.has("filters")) {
            for (JsonNode f : requireArray(node.get("filters"), "filters")) {
                b.addFilters(parseFilter(f));
            }
        }
        return b.build();
    }

    private static BattleScriptTargetFilter parseFilter(JsonNode node) {
        requireObject(node, "filter");
        rejectUnknownFields(node, Set.of("kind", "p1", "negate"), "filter");
        BattleScriptTargetFilter.Builder b = BattleScriptTargetFilter.newBuilder();
        if (node.has("kind")) b.setKind(parseEnum(node.get("kind"), eBattleScriptTargetFilter::valueOf, eBattleScriptTargetFilter::forNumber));
        if (node.has("p1")) b.setP1(node.get("p1").asInt());
        if (node.has("negate")) b.setNegate(node.get("negate").asBoolean());
        return b.build();
    }

    private static BattleScriptBinding parseBinding(JsonNode node) {
        requireObject(node, "binding");
        rejectUnknownFields(node, Set.of("match_mode", "battle_config_id", "side", "script_id"), "binding");
        BattleScriptBinding.Builder b = BattleScriptBinding.newBuilder();
        if (node.has("match_mode")) b.setMatchMode(node.get("match_mode").asInt());
        if (node.has("battle_config_id")) b.setBattleConfigId(node.get("battle_config_id").asInt());
        if (node.has("side")) b.setSide(parseEnum(node.get("side"), eBattleScriptSide::valueOf, eBattleScriptSide::forNumber));
        if (node.has("script_id")) b.setScriptId(node.get("script_id").asInt());
        return b.build();
    }

    private static void requireObject(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(where + " must be a JSON object");
        }
    }

    private static JsonNode requireArray(JsonNode node, String where) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(where + " must be a JSON array");
        }
        return node;
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String where) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(where + ": unknown field " + field);
            }
        }
    }

    private static <E> E parseEnum(JsonNode node, java.util.function.Function<String, E> byName,
                                   java.util.function.IntFunction<E> byNumber) {
        if (node.isTextual()) {
            return byName.apply(node.asText());
        }
        if (node.isIntegralNumber()) {
            E val = byNumber.apply(node.asInt());
            if (val != null) {
                return val;
            }
        }
        throw new IllegalArgumentException("invalid enum value: " + node);
    }
}
