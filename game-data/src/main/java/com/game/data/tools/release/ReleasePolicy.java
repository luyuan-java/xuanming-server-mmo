package com.game.data.tools.release;

import static com.game.data.tools.release.ReleaseFailure.require;

import com.game.discovery.namespace.GameNamespaceBinder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 小而封闭的生产门禁配置：直接生成 Spring canonical properties，不解析任意脚本或猜测有效配置。 */
final class ReleasePolicy {
    static final Set<String> SECRETS = Set.of("GAME_MYSQL_PASSWORD", "GAME_DUBBO_SECRET", "GAME_GATE_TOKEN_SECRET",
            "GAME_NODE_LINK_SECRET", "GAME_BATTLE_TOKEN_SECRET", "GAME_ADMIN_TOKEN", "GAME_GM_ADMIN_SECRET",
            "GAME_ASSET_OP_SECRET_GUILD", "GAME_ASSET_OP_SECRET_MAIL", "GAME_ASSET_OP_SECRET_TRADE",
            "GAME_MALL_GATEWAY_SECRET", "GAME_MALL_PAYMENT_SECRET", "GAME_MALL_DELIVERY_SECRET", "GAME_MALL_ADMIN_SECRET");
    static final String PROTOCOL = "directory-v2-epoch-instance-retired";
    static final String STRATEGY = "maintenance-all-services";
    private ReleasePolicy() { }

    static void validate(ReleaseSpec spec) {
        require(spec != null && spec.format() == 1, "SPEC_FORMAT");
        require(ReleaseRules.version(spec.version()), "VERSION_INVALID");
        require(sha1(spec.javaCommit()) && sha1(spec.contractCommit()) && sha1(spec.mapsCommit()), "SOURCE_COMMIT_INVALID");
        try { GameNamespaceBinder.bind(Map.of("game.namespace", spec.namespace()), Map.of()); }
        catch (RuntimeException failure) { throw new ReleaseFailure("NAMESPACE_INVALID"); }
        try {
            UUID generation = UUID.fromString(spec.routeGeneration());
            require(generation.toString().equals(spec.routeGeneration()) && !generation.equals(new UUID(0, 0)), "ROUTE_GENERATION_INVALID");
        } catch (IllegalArgumentException | NullPointerException failure) { throw new ReleaseFailure("ROUTE_GENERATION_INVALID"); }
        require(PROTOCOL.equals(spec.directoryProtocol()) && STRATEGY.equals(spec.rolloutStrategy()), "DIRECTORY_UPGRADE_UNSUPPORTED");
        require(spec.jars() != null && spec.jars().keySet().equals(ReleaseService.modules()), "JAR_SERVICE_SET");
        spec.jars().values().forEach(path -> ReleaseFiles.relative(path));
        require(new HashSet<>(spec.jars().values()).size() == ReleaseService.values().length, "JAR_PATH_DUPLICATE");
        require(spec.settings() != null && spec.settings().keySet().equals(ReleaseService.modules()), "CONFIG_SERVICE_SET");
        require(spec.migrationPlans() != null && spec.migrationPlans().keySet().equals(ReleaseService.sqlModules()), "MIGRATION_PLAN_SET");
        require(spec.migrationPlans().values().stream().allMatch(ReleasePolicy::identifier), "MIGRATION_PLAN_REFERENCE");
        for (ReleaseService service : ReleaseService.values()) validateSettings(service, spec);
        require(spec.settings().get(ReleaseService.SCENE.module()).get("game.audit.topic-generation")
                .equals(spec.settings().get(ReleaseService.DATA.module()).get("game.audit.topic-generation")), "AUDIT_GENERATION_MISMATCH");
        require(spec.secretVersions() != null && spec.secretVersions().keySet().equals(SECRETS), "SECRET_REFERENCE_SET");
        require(spec.secretVersions().values().stream().allMatch(ReleasePolicy::identifier), "SECRET_VERSION_INVALID");
        // 这里只验证引用版本，不读取本机秘密冒充部署注入事实；在线检查恒为 UNKNOWN。
    }

    private static void validateSettings(ReleaseService service, ReleaseSpec spec) {
        Map<String, String> config = spec.settings().get(service.module());
        require(config != null && config.keySet().equals(expectedKeys(service)), "CONFIG_KEYS:" + service.name());
        require(config.values().stream().allMatch(value -> value != null && !value.contains("\n") && !value.contains("\r")
                && !value.contains("${") && value.length() <= 2048), "CONFIG_VALUE:" + service.name());
        require("prod".equals(config.get("game.run-mode")), "PROD_MODE:" + service.name());
        require(spec.namespace().equals(config.get("game.namespace")), "CONFIG_NAMESPACE:" + service.name());
        if (service.usesSql()) {
            validateDsn(config.get("spring.datasource.url"), spec.namespace());
            require((spec.namespace() + "_app").equals(config.get("spring.datasource.username")), "SQL_USER:" + service.name());
            require("never".equals(config.get("spring.sql.init.mode")), "SQL_AUTO_INIT:" + service.name());
        }
        for (String key : config.keySet()) if (key.endsWith("require-tidb")) require("true".equals(config.get(key)), "TIDB_REQUIRED:" + service.name());
        if (service == ReleaseService.LOGIN) require("prod".equals(config.get("game.login.mode")), "LOGIN_DEV_MODE");
        if (service == ReleaseService.MATCH) require("true".equals(config.get("game.match.global.enabled"))
                && "true".equals(config.get("game.match.global.require-ti-db")), "GLOBAL_MATCH_REQUIRED");
        if (service == ReleaseService.MALL) require("false".equals(config.get("game.mall.mock-payment-enabled")), "MALL_MOCK_PAYMENT");
        if (service == ReleaseService.SCENE || service == ReleaseService.DATA) {
            require("true".equals(config.get("game.audit.enabled")), "AUDIT_DISABLED");
            int generation = positive(config.get("game.audit.topic-generation"));
            int replicas = positive(config.get("game.audit.replication-factor"));
            int minimum = positive(config.get("game.audit.min-in-sync-replicas"));
            require(generation >= 1 && replicas >= 3 && replicas <= Short.MAX_VALUE && minimum >= 2 && minimum <= replicas, "AUDIT_DURABILITY");
        }
    }

    static Set<String> expectedKeys(ReleaseService service) {
        Set<String> keys = new HashSet<>(Set.of("game.namespace", "game.run-mode"));
        if (service.usesSql()) keys.addAll(Set.of("spring.datasource.url", "spring.datasource.username", "spring.sql.init.mode"));
        String tidb = switch (service) {
            case CHAT -> "game.chat.require-tidb"; case FRIEND -> "game.friend.require-tidb";
            case INBOX -> "game.inbox.require-tidb"; case TEAM -> "game.team.require-tidb";
            case MAIL -> "game.mail.require-tidb"; case RANK -> "game.rank.require-tidb";
            case MALL -> "game.mall.require-tidb"; case GUILD -> "game.guild.activity.require-tidb";
            default -> null;
        };
        if (tidb != null) keys.add(tidb);
        // player-store 在 login、scene、data 中形成玩家权威路径。
        if (Set.of(ReleaseService.LOGIN, ReleaseService.SCENE, ReleaseService.DATA).contains(service)) keys.add("game.player-store.require-tidb");
        if (service == ReleaseService.LOGIN) keys.add("game.login.mode");
        if (service == ReleaseService.MATCH) keys.addAll(Set.of("game.match.global.enabled", "game.match.global.require-ti-db"));
        if (service == ReleaseService.MALL) keys.add("game.mall.mock-payment-enabled");
        if (service == ReleaseService.SCENE || service == ReleaseService.DATA) keys.addAll(Set.of("game.audit.enabled",
                "game.audit.topic-generation", "game.audit.replication-factor", "game.audit.min-in-sync-replicas"));
        return Set.copyOf(keys);
    }

    static void validateDsn(String url, String namespace) {
        try {
            require(url != null && url.startsWith("jdbc:mysql://") && !url.contains("%") && !url.contains("\\"), "DSN_INVALID");
            URI uri = URI.create(url.substring(5));
            require(uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                    && uri.getPort() > 0 && uri.getPort() <= 65535 && ("/" + namespace).equals(uri.getRawPath()), "DSN_NAMESPACE_OR_AUTHORITY");
            Map<String, String> query = new HashMap<>();
            require(uri.getRawQuery() != null, "DSN_OPTIONS");
            for (String item : uri.getRawQuery().split("&", -1)) {
                String[] pair = item.split("=", -1);
                require(pair.length == 2 && query.putIfAbsent(pair[0], pair[1]) == null, "DSN_OPTIONS");
            }
            require(query.keySet().equals(Set.of("sslMode", "connectTimeout", "socketTimeout")), "DSN_OPTIONS");
            require("VERIFY_IDENTITY".equals(query.get("sslMode")), "DSN_TLS");
            require(positive(query.get("connectTimeout")) <= 10_000 && positive(query.get("socketTimeout")) <= 60_000, "DSN_TIMEOUT");
        } catch (IllegalArgumentException failure) { throw new ReleaseFailure("DSN_INVALID"); }
    }

    static byte[] properties(ReleaseService service, ReleaseSpec spec) {
        var values = new TreeMap<>(spec.settings().get(service.module()));
        if (service.usesSql()) values.put("spring.datasource.password", "${GAME_MYSQL_PASSWORD}");
        var text = new StringBuilder("# 发布原件的生产门禁配置；不能证明运行时无更高优先级覆盖。\n");
        values.forEach((key, value) -> text.append(key).append('=').append(value.replace("\\", "\\\\")).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
    static boolean sha1(String value) { return value != null && value.matches("[0-9a-f]{40}"); }
    static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"); }
    private static int positive(String value) {
        try { require(value != null && value.matches("[1-9][0-9]{0,8}"), "POSITIVE_INTEGER"); return Integer.parseInt(value); }
        catch (NumberFormatException failure) { throw new ReleaseFailure("POSITIVE_INTEGER"); }
    }
}
