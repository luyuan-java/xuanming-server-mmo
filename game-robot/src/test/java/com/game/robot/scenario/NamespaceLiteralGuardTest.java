package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 部署命名空间的常驻守卫（扫描仓库文件，与 SliceScriptsTest 同类）。命名空间是注入的：{@code game.namespace} 经 game-discovery 的
 * {@code GameNamespaceProperties} 绑定校验，自动配置注册 {@code GameNamespace} / {@code RedisKeys} / {@code KafkaNames} 三个 bean，经构造器注入给使用方；
 * MySQL 库名经 yaml 的 {@code ${game.namespace:game}}。共享中间件里的名字只经这三个出处生成，本测试守住绕过它们的写法：
 * <ul>
 *   <li>R1：main 代码（game-robot 除外：它是客户端）的非注释行——含 Lua 文本块、日志——不出现 {@code game:}（Redis 键 / 频道写死了缺省命名空间）；</li>
 *   <li>R2：完整字面量 {@code "game-…"} 只能是登记过的代码身份（{@link #CODE_IDENTITY}）；topic / 消费组 / client.id 不得写死；</li>
 *   <li>R3：只有装配类、命令行工具的取值入口 {@code GameNamespaceBinder}、两个自己构造键名类的命令行工具（以及定义它们的三个类）能构造命名空间、
 *       {@code RedisKeys}、{@code KafkaNames}（{@link #CONSTRUCTION_SITES}），
 *       别处一律构造器注入；</li>
 *   <li>R4：旧的进程级全局机制（静态 get / set、NamespaceHolder、EnvironmentPostProcessor / FailureAnalyzer、spring.factories、
 *       {@code @Tag("namespace")} 与 surefire 钉值）不回来；根 pom 的 surefire 排除会改变部署行为的环境变量（测试与开发者 shell 隔离，不是钉值）；</li>
 *   <li>R5：15 个SQL进程的 JDBC URL 按缺省命名空间解析后与正式配置逐字节相同（金样），别处的 yaml 不引用 {@code ${game.namespace}}，nacos profile 不配
 *       registry 的 namespace（缺省行为不变：由部署显式给，{@code GAME_NACOS_ADDR} 带 {@code ?namespace=} 或 {@code dubbo.registry.parameters.namespace}）；各 Spring Boot 进程都直接依赖 game-discovery；</li>
 *   <li>R6：compose 预建哪个库由独立的 {@code GAME_MYSQL_INIT_DATABASE} 决定（没设取 game，空值 = 不预建），不读进程租户 {@code GAME_NAMESPACE}。</li>
 * </ul>
 * 切片脚本的命名空间校验与读回核对（R7）在 {@link SliceScriptsTest}。配置键 {@code game.}、环境变量 {@code GAME_}、包名、指标名、日志文案不跟命名空间走。
 */
class NamespaceLiteralGuardTest {

    /** 本文件自己：规则里写着要禁的字面量，扫描时跳过。 */
    private static final String SELF = "game-robot/src/test/java/com/game/robot/scenario/NamespaceLiteralGuardTest.java";

    private static Path root;

    @BeforeAll
    static void 找仓库根() {
        root = Files.isDirectory(Path.of("../game-discovery")) ? Path.of("..") : Path.of(".");
        Assumptions.assumeTrue(Files.isDirectory(root.resolve("game-discovery/src/main/java")), "找不到仓库根（单独构建 game-robot）");
    }

    /** 全部 game-* 模块里 {@code sourceDir}（如 src/main/java）下的 Java 源文件（相对仓库根的路径 → 内容），不含本文件。 */
    private static Map<String, String> sources(String sourceDir) throws IOException {
        Map<String, String> sources = new TreeMap<>();
        for (Path module : modules()) {
            Path dir = module.resolve(sourceDir);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String path = root.relativize(file).toString().replace('\\', '/');
                    if (!path.equals(SELF)) {
                        sources.put(path, Files.readString(file, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        assertThat(sources).as("扫到了 %s 下的源码", sourceDir).hasSizeGreaterThan(300);
        return sources;
    }

    private static List<Path> modules() throws IOException {
        try (Stream<Path> modules = Files.list(root)) {
            return modules.filter(p -> p.getFileName().toString().startsWith("game-") && Files.isRegularFile(p.resolve("pom.xml")))
                    .sorted().toList();
        }
    }

    private static boolean commentLine(String line) {
        String t = line.strip();
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
    }

    private static String module(String path) {
        return path.substring(0, path.indexOf('/'));
    }

    // ================================================================ R1–R3：main 代码

    /** R1：非注释行里出现 {@code game:} 的（「文件:行: 代码」）。game-robot 不查。 */
    static List<String> redisLiteralOffenders(Map<String, String> mainSources) {
        List<String> offenders = new ArrayList<>();
        mainSources.forEach((file, source) -> {
            if (module(file).equals("game-robot")) {
                return;
            }
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!commentLine(lines[i]) && lines[i].contains("game:")) {
                    offenders.add(file + ":" + (i + 1) + ": " + lines[i].strip());
                }
            }
        });
        return offenders;
    }

    /**
     * R2 的白名单：代码身份（不跟命名空间走，architecture.md §6.2「不跟命名空间走的」），按「文件 → 字面量」登记。新增一条前先确认它不是共享中间件里的名字
     * （Redis 键 / 频道、Kafka topic / 消费组 / client.id / transactional.id、MySQL 库名）；是的话改经注入的 RedisKeys / KafkaNames。
     */
    static final Map<String, Set<String>> CODE_IDENTITY = Map.ofEntries(
            // Dubbo 调用的附件键（协议身份；租户之间靠各自的密钥隔离）
            Map.entry("game-api/src/main/java/com/game/api/match/MatchRpcAttachments.java", Set.of("game-budget-ms")),
            Map.entry("game-common/src/main/java/com/game/common/token/DubboCallAuth.java", Set.of("game-auth-ts", "game-auth-mac")),
            // HMAC 域分隔串（租户之间靠不同的密钥隔离）
            Map.entry("game-gateway/src/main/java/com/game/gateway/queue/QueueTokens.java", Set.of("game-login-queue")),
            // 隔离 Dubbo 模块的应用名（不配注册中心、按节点直连）
            Map.entry("game-scene/src/main/java/com/game/scene/rpc/SceneRpcServer.java", Set.of("game-scene-asset")),
            Map.entry("game-scene/src/main/java/com/game/scene/transfer/SceneManagerSwitchTargets.java", Set.of("game-scene-switch")),
            // 持久收件箱双方的NodeRpcClients隔离Dubbo应用名；不是Kafka topic/消费组/client.id，按文件精确登记。
            Map.entry("game-inbox/src/main/java/com/game/inbox/InboxConfiguration.java", Set.of("game-inbox-scene")),
            // 组队成员变更的 scene 通知客户端应用名；不是共享中间件名称。
            Map.entry("game-team/src/main/java/com/game/team/TeamConfiguration.java", Set.of("game-team-scene")),
            Map.entry("game-scene/src/main/java/com/game/scene/inbox/DubboInboxTransport.java", Set.of("game-scene-inbox")),
            Map.entry("game-battle/src/main/java/com/game/battle/admin/DevGatherConfiguration.java", Set.of("game-battle-dev-gather")),
            Map.entry("game-battle/src/main/java/com/game/battle/port/scene/SceneTransport.java", Set.of("game-battle-scene")),
            Map.entry("game-battle/src/main/java/com/game/battle/rpc/BattleRpcServer.java", Set.of("game-battle")),
            Map.entry("game-guild/src/main/java/com/game/guild/GuildConfiguration.java", Set.of("game-guild-asset")),
            // 资产与投榜客户端的隔离 Dubbo 应用名，经 IsolatedDubboModule 直连，不是 Kafka 身份或线程名。
            Map.entry("game-mail/src/main/java/com/game/mail/MailConfiguration.java", Set.of("game-mail-asset")),
            Map.entry("game-trade/src/main/java/com/game/trade/commerce/CommerceConfiguration.java", Set.of("game-trade-assets")),
            Map.entry("game-match/src/main/java/com/game/match/global/RatingRankOutbox.java", Set.of("game-match-rank-outbox")),
            Map.entry("game-match/src/main/java/com/game/match/MatchConfiguration.java", Set.of("game-match-scene-battle", "game-match-battle-node")),
            Map.entry("game-match/src/main/java/com/game/match/placement/PlacementClients.java", Set.of("game-match-battle-placement")),
            Map.entry("game-data/src/main/java/com/game/data/rollback/DubboGuildInternalClient.java", Set.of("game-data-guild-check")),
            // 离线发布原件的 Maven 模块 / jar 身份；不是 Redis / Kafka 名称，不随部署命名空间变化。
            Map.entry("game-data/src/main/java/com/game/data/tools/release/ReleaseService.java", Set.of(
                    "game-scene-manager", "game-login", "game-friend", "game-chat", "game-mail", "game-rank",
                    "game-match", "game-team", "game-guild", "game-mall", "game-trade", "game-data", "game-inbox",
                    "game-scene", "game-battle", "game-gate", "game-gateway", "game-robot")),
            // 存进去的值（发号租约的持有者 id 前缀），不是名字
            Map.entry("game-data/src/main/java/com/game/data/DataConfiguration.java", Set.of("game-data-")),
            Map.entry("game-data/src/main/java/com/game/data/tools/AuditFallbackReplay.java", Set.of("game-audit-replay-")));

    /** 完整字面量 {@code "game-…"}（引号之间只有名字字符）。拆行、先赋给局部变量再传给 client.id 的写法同样挡得住：看的是字面量本身。 */
    private static final Pattern GAME_DASH_LITERAL = Pattern.compile("\"(game-[A-Za-z0-9._-]*)\"");

    /** R2：非注释行里不在白名单上的 {@code "game-…"} 字面量。game-robot 不查。 */
    static List<String> kafkaLiteralOffenders(Map<String, String> mainSources) {
        List<String> offenders = new ArrayList<>();
        mainSources.forEach((file, source) -> {
            if (module(file).equals("game-robot")) {
                return;
            }
            Set<String> allowed = CODE_IDENTITY.getOrDefault(file, Set.of());
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (commentLine(lines[i])) {
                    continue;
                }
                Matcher m = GAME_DASH_LITERAL.matcher(lines[i]);
                while (m.find()) {
                    if (!allowed.contains(m.group(1))) {
                        offenders.add(file + ":" + (i + 1) + ": " + m.group());
                    }
                }
            }
        });
        return offenders;
    }

    /**
     * R3 的构造入口白名单：装配类；命令行工具取命名空间的唯一入口 {@code GameNamespaceBinder}（与服务进程同一个属性类型、同口径绑定后
     * {@code GameNamespace.of} 校验）；不起 Spring、拿到命名空间后自己 {@code new RedisKeys} 的命令行工具（回灌、assetopfix，以及玩家排障导出 / 导入——
     * 两者共用 PlayerBundleExport 里的一处构造；PasswordAdmin 只取命名空间核对库名，不构造任何名字类，不在名单上）；以及定义这几个类型的三个类。
     */
    static final Set<String> CONSTRUCTION_SITES = Set.of(
            "game-discovery/src/main/java/com/game/discovery/namespace/GameNamespaceAutoConfiguration.java",
            "game-discovery/src/main/java/com/game/discovery/namespace/GameNamespaceBinder.java",
            "game-data/src/main/java/com/game/data/tools/AuditFallbackReplay.java",
            "game-data/src/main/java/com/game/data/tools/RetireZoneHotState.java",
            "game-data/src/main/java/com/game/data/tools/playerbundle/PlayerBundleExport.java",
            "game-guild/src/main/java/com/game/guild/asset/fix/AssetOpFixEnvironment.java",
            "game-common/src/main/java/com/game/common/GameNamespace.java",
            "game-common/src/main/java/com/game/common/KafkaNames.java",
            "game-discovery/src/main/java/com/game/discovery/RedisKeys.java");

    private static final Pattern CONSTRUCTION =
            Pattern.compile("new RedisKeys\\(|new KafkaNames\\(|new GameNamespace\\(|GameNamespace\\.of\\(");

    /** R3：白名单以外的非注释行里构造命名空间 / RedisKeys / KafkaNames 的。 */
    static List<String> constructionOffenders(Map<String, String> mainSources) {
        List<String> offenders = new ArrayList<>();
        mainSources.forEach((file, source) -> {
            if (CONSTRUCTION_SITES.contains(file)) {
                return;
            }
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!commentLine(lines[i]) && CONSTRUCTION.matcher(lines[i]).find()) {
                    offenders.add(file + ":" + (i + 1) + ": " + lines[i].strip());
                }
            }
        });
        return offenders;
    }

    @Test
    void R1_Redis键与频道只经注入的RedisKeys_main非注释行没有game冒号() throws IOException {
        assertThat(redisLiteralOffenders(sources("src/main/java")))
                .as("写死缺省命名空间的 Redis 键 / 频道（含 Lua 文本块与日志）：改经注入的 RedisKeys 生成，日志里写 <ns>: 或 keys.prefix()").isEmpty();
    }

    @Test
    void R2_game横杠字面量只能是登记过的代码身份_白名单条目都还在用() throws IOException {
        Map<String, String> sources = sources("src/main/java");
        assertThat(kafkaLiteralOffenders(sources)).as("Kafka topic / 消费组 / client.id 改经注入的 KafkaNames；确实是代码身份的，在 CODE_IDENTITY 里按文件登记并写明理由")
                .isEmpty();
        // 白名单不悄悄变宽：删了代码就删登记
        CODE_IDENTITY.forEach((file, allowed) -> {
            assertThat(sources).as("白名单文件 %s 还在", file).containsKey(file);
            List<String> present = new ArrayList<>();
            Matcher m = GAME_DASH_LITERAL.matcher(sources.get(file));
            while (m.find()) {
                present.add(m.group(1));
            }
            assertThat(present).as("%s 里白名单字面量都还在", file).containsAll(allowed);
        });
    }

    @Test
    void R3_命名空间与键名类只在装配类和命令行工具的入口里构造_别处构造器注入() throws IOException {
        Map<String, String> sources = sources("src/main/java");
        assertThat(constructionOffenders(sources)).as("main 里不得自己 new RedisKeys / KafkaNames / GameNamespace：经构造器注入自动配置的 bean").isEmpty();
        assertThat(sources.keySet()).as("白名单文件都还在").containsAll(CONSTRUCTION_SITES);
        // 白名单不悄悄变宽：名单上的每个文件都还在构造（定义类型的三个类除外）
        CONSTRUCTION_SITES.stream().filter(file -> !file.startsWith("game-common/") && !file.endsWith("/RedisKeys.java")).forEach(file ->
                assertThat(CONSTRUCTION.matcher(sources.get(file)).find()).as("%s 还在构造名字类，否则从白名单删掉", file).isTrue());
        // 命令行工具取命名空间都经同一个入口（与服务进程同口径），不另写一套只认环境变量的取值
        for (String tool : List.of("game-data/src/main/java/com/game/data/tools/AuditFallbackReplay.java",
                "game-data/src/main/java/com/game/data/tools/playerbundle/PlayerBundleExport.java",
                "game-data/src/main/java/com/game/data/tools/playerbundle/PlayerBundleImport.java",
                "game-guild/src/main/java/com/game/guild/asset/fix/AssetOpFixEnvironment.java",
                "game-login/src/main/java/com/game/login/tools/PasswordAdmin.java")) {
            assertThat(sources.get(tool)).as(tool).contains("GameNamespaceBinder.bind(").doesNotContain("getenv(\"GAME_NAMESPACE\")")
                    .doesNotContain("get(GameNamespace.ENV)");
        }
    }

    @Test
    void 守卫挡得住评审指出的写法_拆行的client_id_Lua单引号_日志_自己new() {
        // 引入注入式之前与评审（C4）指出的真实写法：前两处旧守卫（只看同一行的 CLIENT_ID_CONFIG）漏掉了
        Map<String, String> before = new TreeMap<>(Map.of(
                "game-scene/src/main/java/com/game/scene/SceneNode.java",
                "class SceneNode {\n    void f() {\n        String clientId = \"game-scene-audit-z\" + zoneId + \"-n\" + nodeId;\n"
                        + "        p.put(CLIENT_ID_CONFIG, clientId);\n    }\n}\n",
                "game-match/src/main/java/com/game/match/rating/RatingConfiguration.java",
                "class R {\n    Object c = BattleResultIngest.kafkaConsumers(kafka.bootstrapServers(), group,\n"
                        + "            \"game-match-rating-\" + instance.id());\n    Object k = new KafkaNames(GameNamespace.of(\"game\"));\n}\n",
                "game-discovery/src/main/java/com/game/discovery/Lua.java",
                "class L {\n    // 注释里的 \"game:x\" 不算\n    String a = \"return redis.call('GET', 'game:nodes:' .. ARGV[1])\";\n"
                        + "    String b = \"\"\"\n        redis.call('DEL', 'game:lock')\n        \"\"\";\n"
                        + "    String d = \"game-auth-ts\";\n    RedisKeys keys = new RedisKeys(ns);\n}\n",
                "game-guild/src/main/java/com/game/guild/GuildConfiguration.java",
                "class G {\n    void f() {\n        log.info(\"定位 = game:location + scene 节点目录直连\");\n    }\n}\n",
                "game-robot/src/main/java/com/game/robot/client/AdminClient.java",
                "class A {\n    String agent = \"game-robot\";\n    String key = \"game:x\";\n}\n"));
        assertThat(redisLiteralOffenders(before)).containsExactly(
                "game-discovery/src/main/java/com/game/discovery/Lua.java:3: String a = \"return redis.call('GET', 'game:nodes:' .. ARGV[1])\";",
                "game-discovery/src/main/java/com/game/discovery/Lua.java:5: redis.call('DEL', 'game:lock')",
                "game-guild/src/main/java/com/game/guild/GuildConfiguration.java:3: log.info(\"定位 = game:location + scene 节点目录直连\");");
        assertThat(kafkaLiteralOffenders(before)).containsExactly(
                "game-discovery/src/main/java/com/game/discovery/Lua.java:7: \"game-auth-ts\"",
                "game-match/src/main/java/com/game/match/rating/RatingConfiguration.java:3: \"game-match-rating-\"",
                "game-scene/src/main/java/com/game/scene/SceneNode.java:3: \"game-scene-audit-z\"");
        assertThat(constructionOffenders(before)).containsExactly(
                "game-discovery/src/main/java/com/game/discovery/Lua.java:8: RedisKeys keys = new RedisKeys(ns);",
                "game-match/src/main/java/com/game/match/rating/RatingConfiguration.java:4: Object k = new KafkaNames(GameNamespace.of(\"game\"));");
    }

    // ================================================================ R4：旧的全局机制不回来

    @Test
    void R4_没有进程级全局状态与surefire钉值_旧机制的类不再被引用() throws IOException {
        Map<String, String> all = new TreeMap<>(sources("src/main/java"));
        all.putAll(sources("src/test/java"));
        List<String> offenders = new ArrayList<>();
        Pattern global = Pattern.compile("GameNamespace\\.get\\(|GameNamespace\\.set\\(|NamespaceHolder|@Tag\\(\"namespace\"\\)"
                + "|GameNamespaceEnvironmentPostProcessor|GameNamespaceFailureAnalyzer|InvalidGameNamespaceException");
        all.forEach((file, source) -> {
            Matcher m = global.matcher(source);
            while (m.find()) {
                offenders.add(file + ": " + m.group());
            }
        });
        // 注释也算：nacos profile 的 yaml 里还在讲「由 EPP 派生」就是过时的说明
        for (Path module : modules()) {
            Path resources = module.resolve("src/main/resources");
            if (!Files.isDirectory(resources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(resources)) {
                for (Path file : files.filter(Files::isRegularFile).filter(f -> f.toString().matches(".*\\.(ya?ml|properties|factories|imports)$"))
                        .toList()) {
                    Matcher m = global.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (m.find()) {
                        offenders.add(root.relativize(file).toString().replace('\\', '/') + ": " + m.group());
                    }
                }
            }
        }
        assertThat(offenders).as("命名空间是注入的：不用静态 get / set、NamespaceHolder、EnvironmentPostProcessor / FailureAnalyzer，测试直接构造实例、不打 namespace 标签")
                .isEmpty();
        assertThat(root.resolve("game-discovery/src/main/resources/META-INF/spring.factories")).as("game-discovery 不再登记 EPP / FailureAnalyzer")
                .doesNotExist();

        List<Path> poms = new ArrayList<>(List.of(root.resolve("pom.xml")));
        for (Path module : modules()) {
            poms.add(module.resolve("pom.xml"));
        }
        for (Path pom : poms) {
            assertThat(Files.readString(pom, StandardCharsets.UTF_8)).as("%s 不钉 game.namespace、不按 namespace 标签分 surefire 执行", root.relativize(pom))
                    .doesNotContain("<game.namespace>").doesNotContain("<groups>namespace").doesNotContain("excludedGroups>namespace")
                    .doesNotContain("<GAME_NAMESPACE>");
        }
        // 测试与开发者 shell 隔离（不是钉值）：export 过 GAME_NAMESPACE 等变量的 shell 里跑 mvn，断言缺省行为的 Spring 上下文用例不受影响
        assertThat(Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8)).as("根 pom 的 surefire 排除这几个环境变量")
                .contains("<excludedEnvironmentVariable>GAME_NAMESPACE</excludedEnvironmentVariable>")
                .contains("<excludedEnvironmentVariable>GAME_MYSQL_EXPECT_DATABASE</excludedEnvironmentVariable>")
                .contains("<excludedEnvironmentVariable>GAME_MYSQL_EXPECTDATABASE</excludedEnvironmentVariable>")
                .contains("<excludedEnvironmentVariable>GAME_NACOS_ADDR</excludedEnvironmentVariable>");
        for (Path pom : poms.subList(1, poms.size())) {
            assertThat(Files.readString(pom, StandardCharsets.UTF_8)).as("%s 不另写 excludedEnvironmentVariables（会盖掉根 pom 的那一份）", root.relativize(pom))
                    .doesNotContain("excludedEnvironmentVariables");
        }
    }

    // ================================================================ R5：yaml 与模块依赖

    /** JDBC 完整模板金样：保留原参数，登记新增SQL Chat/SceneManager及Team明确TiDB端点；只替换库名占位符。 */
    private static final Map<String, String> GOLDEN_JDBC_URLS = Map.ofEntries(
            Map.entry("game-chat", "jdbc:mysql://127.0.0.1:3306/game?useUnicode=true&characterEncoding=UTF-8"),
            Map.entry("game-data", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000"
                    + "&socketTimeout=30000&useAffectedRows=true"),
            Map.entry("game-friend", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=3"),
            Map.entry("game-gateway", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=3000&socketTimeout=10000"),
            Map.entry("game-guild", "jdbc:mysql://${GAME_MYSQL_HOST:127.0.0.1}:${GAME_MYSQL_PORT:4000}/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1"),
            Map.entry("game-inbox", "jdbc:mysql://${GAME_MYSQL_HOST:127.0.0.1}:${GAME_MYSQL_PORT:4000}/game?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=2000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1"),
            Map.entry("game-login", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=10000"),
            Map.entry("game-mail", "jdbc:mysql://${GAME_MYSQL_HOST:127.0.0.1}:${GAME_MYSQL_PORT:4000}/game?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=2000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1"),
            Map.entry("game-mall", "jdbc:mysql://${GAME_MYSQL_HOST:127.0.0.1}:${GAME_MYSQL_PORT:4000}/game?useSSL=false&characterEncoding=utf8"
                    + "&connectTimeout=3000&socketTimeout=6000&useAffectedRows=true"),
            Map.entry("game-match", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1"),
            Map.entry("game-rank", "jdbc:mysql://${GAME_MYSQL_HOST:127.0.0.1}:${GAME_MYSQL_PORT:4000}/game?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=2000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1"),
            Map.entry("game-scene", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=3000&socketTimeout=10000"),
            Map.entry("game-scene-manager", "jdbc:mysql://127.0.0.1:4000/game?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                    + "&connectTimeout=3000&socketTimeout=5000"),
            Map.entry("game-team", "jdbc:mysql://127.0.0.1:4000/game?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=3000"
                    + "&socketTimeout=4000"),
            Map.entry("game-trade", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=2"));

    private static final Pattern JDBC_URL_LINE = Pattern.compile("^\\s+url:\\s+\"?(jdbc:mysql://[^\"\\s]+)\"?\\s*$");

    /** 只识别已登记的完整URL覆盖入口；其它包装仍无法通过完整进程集合与elsewhere守卫。 */
    private static String jdbcDefaultLine(String line) {
        String prefix = "\"${GAME_MYSQL_URL:";
        if (line.stripLeading().startsWith("url: " + prefix) && line.endsWith("}\"")) {
            String unwrapped = line.replace(prefix, "\"");
            return unwrapped.substring(0, unwrapped.length() - 2) + "\"";
        }
        return line;
    }

    @Test
    void R5_只解已登记的完整URL覆盖_保留库名变量_未知覆盖不被当成JDBC模板() {
        String direct = "    url: \"jdbc:mysql://127.0.0.1:4000/${game.namespace:game}?useSSL=false\"";
        assertThat(jdbcDefaultLine("    url: \"${GAME_MYSQL_URL:jdbc:mysql://127.0.0.1:4000/${game.namespace:game}?useSSL=false}\""))
                .isEqualTo(direct);
        assertThat(JDBC_URL_LINE.matcher(jdbcDefaultLine(direct)).matches()).isTrue();
        assertThat(JDBC_URL_LINE.matcher(jdbcDefaultLine("    url: \"${UNKNOWN_SQL_URL:jdbc:mysql://127.0.0.1:4000/${game.namespace:game}?useSSL=false}\"")).matches())
                .as("未知包装不能静默绕过已登记SQL进程集合").isFalse();
    }

    /** 各模块 src/main/resources 下的 application*.yaml / .yml（相对仓库根的路径 → 内容）。 */
    private static Map<String, String> applicationYamls() throws IOException {
        Map<String, String> yamls = new TreeMap<>();
        for (Path module : modules()) {
            Path resources = module.resolve("src/main/resources");
            if (!Files.isDirectory(resources)) {
                continue;
            }
            try (Stream<Path> files = Files.list(resources)) {
                for (Path file : files.filter(f -> f.getFileName().toString().matches("application.*\\.ya?ml")).toList()) {
                    yamls.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        return yamls;
    }

    @Test
    void R5_十五个SQL进程的JDBC_URL库名用命名空间占位符_缺省解析与正式金样逐字节相同_别处yaml不引用命名空间() throws IOException {
        Map<String, String> found = new TreeMap<>();
        List<String> elsewhere = new ArrayList<>();
        applicationYamls().forEach((file, text) -> {
            assertThat(text).as("%s 不把库名写死成 game", file).doesNotContain("3306/game?");
            for (String line : text.split("\n", -1)) {
                Matcher m = JDBC_URL_LINE.matcher(jdbcDefaultLine(line));
                if (file.endsWith("/application.yaml") && m.matches() && !found.containsKey(module(file))) {
                    found.put(module(file), m.group(1));
                } else if (line.contains("${game.namespace")) {
                    elsewhere.add(file + ": " + line.strip());
                }
            }
        });
        assertThat(found.keySet()).containsExactlyInAnyOrderElementsOf(GOLDEN_JDBC_URLS.keySet());
        found.forEach((module, raw) -> {
            // 全模板逐字节钉住主机/端口/参数；已切 TiDB 的服务保留环境变量及显式URL覆盖入口。
            assertThat(raw).as(module)
                    .isEqualTo(GOLDEN_JDBC_URLS.get(module).replace("/game?", "/${game.namespace:game}?"));
            assertThat(raw.replace("${game.namespace:game}", "game")).as("%s 缺省解析后与以前逐字节相同", module)
                    .isEqualTo(GOLDEN_JDBC_URLS.get(module));
        });
        assertThat(elsewhere).as("除15条已登记JDBC URL之外yaml不引用game.namespace：缺省值只在代码里一个出处").isEmpty();
    }

    @Test
    void R5_nacos_profile不配registry的namespace_缺省行为不变() throws IOException {
        Map<String, String> nacos = new TreeMap<>();
        applicationYamls().forEach((file, text) -> {
            if (file.endsWith("/application-nacos.yaml")) {
                nacos.put(file, text);
            }
        });
        assertThat(nacos).as("各进程的 nacos profile").hasSizeGreaterThanOrEqualTo(10);
        nacos.forEach((file, text) -> {
            for (String line : text.split("\n", -1)) {
                if (!line.strip().startsWith("#")) {
                    assertThat(line).as("%s：Nacos namespace 由部署显式给（GAME_NACOS_ADDR 带 ?namespace= 或 dubbo.registry.parameters.namespace），yaml 不配", file)
                            .doesNotContainIgnoringCase("namespace");
                }
            }
        });
    }

    @Test
    void R5_每个SpringBoot进程都直接依赖game_discovery_命名空间校验键名bean与库名核对才生效() throws IOException {
        List<String> apps = new ArrayList<>();
        sources("src/main/java").forEach((file, source) -> {
            if (source.contains("@SpringBootApplication") && !apps.contains(module(file))) {
                apps.add(module(file));
            }
        });
        assertThat(apps).as("Spring Boot 进程模块").hasSizeGreaterThanOrEqualTo(13);
        for (String module : apps) {
            assertThat(Files.readString(root.resolve(module).resolve("pom.xml"), StandardCharsets.UTF_8)).as("%s/pom.xml", module)
                    .contains("<artifactId>game-discovery</artifactId>");
        }
    }

    // ================================================================ R6：compose

    @Test
    void R6_compose预建哪个库由独立变量决定_没设取game_空值不预建_不读进程租户GAME_NAMESPACE() throws IOException {
        String infra = Files.readString(root.resolve("deploy/compose/infra.yaml"), StandardCharsets.UTF_8);
        // 共享 infra 不属于任何部署：开发者为跑 t1 切片在 shell 里导出的 GAME_NAMESPACE 不能决定 infra 首次初始化预建哪个库；
        // 写 -game 不写 :-game：设了空值就是「不预建」（mysql 镜像自己的语义），不被换成 game
        assertThat(infra).contains("\n      MYSQL_DATABASE: ${GAME_MYSQL_INIT_DATABASE-game}\n").doesNotContain("${GAME_MYSQL_INIT_DATABASE:-");
        List<String> uses = new ArrayList<>();
        for (String line : infra.split("\n", -1)) {
            if (!line.strip().startsWith("#") && line.contains("GAME_NAMESPACE")) {
                uses.add(line.strip());
            }
        }
        assertThat(uses).as("infra.yaml 不读 GAME_NAMESPACE（它只表示进程租户）").isEmpty();
        String env = Files.readString(root.resolve("deploy/compose/env.example"), StandardCharsets.UTF_8);
        assertThat(env).as("env.example 列出这个变量").contains("# GAME_MYSQL_INIT_DATABASE=");
    }
}
