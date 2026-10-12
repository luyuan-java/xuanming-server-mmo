package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 部署命名空间的常驻守卫（扫描仓库文件，与 SliceScriptsTest 同类）：共享中间件里的名字只经三个出处生成——Redis 经 {@code RedisKeys}（前缀 {@code <ns>:}），
 * Kafka 经 {@code KafkaNames}（前缀 {@code <ns>-}），MySQL 缺省库名经 yaml 的 {@code ${game.namespace:game}}。以后有人在别处写死
 * {@code "game:…"} 的键、{@code "game-…"} 的 topic / 消费组 / client.id、或把库名写回 {@code /game?}，本测试就会失败。
 *
 * <p>同时是 yaml 的金样：9 个进程的 JDBC URL 按缺省命名空间解析后与引入命名空间之前的整串逐字节相同。
 * 代码身份（Dubbo 隔离模块的应用名、鉴权附件键、HMAC 域分隔串、实例 id、日志文案）不跟命名空间走，不在检查范围内。
 */
class NamespaceLiteralGuardTest {

    private static Path root;

    @BeforeAll
    static void 找仓库根() {
        root = Files.isDirectory(Path.of("../game-discovery")) ? Path.of("..") : Path.of(".");
        Assumptions.assumeTrue(Files.isDirectory(root.resolve("game-discovery/src/main/java")), "找不到仓库根（单独构建 game-robot）");
    }

    /** 全部模块的 src/main/java 下的 Java 源文件（相对仓库根的路径 → 内容）。 */
    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("game-")).toList()) {
                Path java = module.resolve("src/main/java");
                if (!Files.isDirectory(java)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(java)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                        sources.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        assertThat(sources).as("扫到了源码").hasSizeGreaterThan(500);
        return sources;
    }

    /** 去掉注释行（javadoc / 行注释）之后的代码行。 */
    private static List<String> codeLines(String source) {
        List<String> lines = new ArrayList<>();
        for (String line : source.split("\n")) {
            String t = line.strip();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) {
                continue;
            }
            lines.add(line);
        }
        return lines;
    }

    @Test
    void Redis键只经RedisKeys生成_main里没有game冒号开头的字符串字面量() throws IOException {
        List<String> offenders = new ArrayList<>();
        mainSources().forEach((file, source) -> codeLines(source).forEach(line -> {
            if (line.contains("\"game:")) {
                offenders.add(file + ": " + line.strip());
            }
        }));
        assertThat(offenders).as("写死缺省命名空间的 Redis 键 / 频道：改经 RedisKeys 生成").isEmpty();
    }

    @Test
    void Kafka名字只经KafkaNames生成_topic消费组client_id不写死game横杠() throws IOException {
        Pattern kafkaName = Pattern.compile("CLIENT_ID_CONFIG|GROUP_ID_CONFIG|TRANSACTIONAL_ID_CONFIG|GROUP_INSTANCE_ID_CONFIG|new KafkaTopicAdmin\\(");
        List<String> offenders = new ArrayList<>();
        mainSources().forEach((file, source) -> codeLines(source).forEach(line -> {
            if (kafkaName.matcher(line).find() && line.contains("\"game-")) {
                offenders.add(file + ": " + line.strip());
            }
            if (line.contains("@DefaultValue(\"game-")) {
                offenders.add(file + ": " + line.strip());
            }
        }));
        assertThat(offenders).as("Kafka 的 client.id / 消费组 / topic 改经 KafkaNames").isEmpty();

        Map<String, String> sources = mainSources();
        for (String file : List.of("game-audit/src/main/java/com/game/audit/AuditTopics.java",
                "game-audit/src/main/java/com/game/audit/BattleResultTopics.java", "game-match/src/main/java/com/game/match/MatchProperties.java",
                "game-data/src/main/java/com/game/data/DataProperties.java")) {
            assertThat(sources).containsKey(file);
            assertThat(codeLines(sources.get(file))).as("%s 里不写死 game- 开头的 topic 基名 / 消费组", file)
                    .noneMatch(line -> line.contains("\"game-"));
        }
        assertThat(sources.get("game-audit/src/main/java/com/game/audit/AuditTopics.java"))
                .contains("KafkaNames.of(\"transaction-log\")").contains("KafkaNames.of(\"player-snapshot\")");
        assertThat(sources.get("game-audit/src/main/java/com/game/audit/BattleResultTopics.java")).contains("KafkaNames.of(\"battle-result\")");
        assertThat(sources.get("game-discovery/src/main/java/com/game/discovery/RedisKeys.java"))
                .contains("public static final String PREFIX = GameNamespace.get() + \":\";");
    }

    /** 引入命名空间之前 9 个进程 application.yaml 里的 JDBC URL（金样）。 */
    private static final Map<String, String> GOLDEN_JDBC_URLS = Map.of(
            "game-data", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000"
                    + "&socketTimeout=30000&useAffectedRows=true",
            "game-friend", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=3",
            "game-gateway", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=3000&socketTimeout=10000",
            "game-guild", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1",
            "game-login", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=10000",
            "game-match", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1",
            "game-scene", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=3000&socketTimeout=10000",
            "game-team", "jdbc:mysql://127.0.0.1:3306/game?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=3000"
                    + "&socketTimeout=4000",
            "game-trade", "jdbc:mysql://127.0.0.1:3306/game?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=3000&socketTimeout=4000&useAffectedRows=true"
                    + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=2");

    @Test
    void 九个进程的JDBC_URL库名用命名空间占位符_缺省解析后与以前逐字节相同() throws IOException {
        Pattern url = Pattern.compile("(?m)^\\s+url:\\s+\"?(jdbc:mysql://[^\"\\s]+)\"?\\s*$");
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("game-")).toList()) {
                Path yaml = module.resolve("src/main/resources/application.yaml");
                if (!Files.isRegularFile(yaml)) {
                    continue;
                }
                String text = Files.readString(yaml, StandardCharsets.UTF_8);
                assertThat(text).as("%s 不把库名写死成 game", yaml).doesNotContain("3306/game?");
                Matcher m = url.matcher(text);
                if (m.find()) {
                    found.put(module.getFileName().toString(), m.group(1));
                }
            }
        }
        assertThat(found.keySet()).containsExactlyInAnyOrderElementsOf(GOLDEN_JDBC_URLS.keySet());
        found.forEach((module, raw) -> {
            assertThat(raw).as(module).startsWith("jdbc:mysql://127.0.0.1:3306/${game.namespace:game}?");
            assertThat(raw.replace("${game.namespace:game}", "game")).as("%s 缺省解析后与以前逐字节相同", module)
                    .isEqualTo(GOLDEN_JDBC_URLS.get(module));
        });
    }

    @Test
    void compose预建库与match的消费组随命名空间() throws IOException {
        String infra = Files.readString(root.resolve("deploy/compose/infra.yaml"), StandardCharsets.UTF_8);
        assertThat(infra).contains("\n      MYSQL_DATABASE: ${GAME_NAMESPACE:-game}\n").doesNotContain("${GAME_NAMESPACE:-}");
        String match = Files.readString(root.resolve("game-match/src/main/resources/application.yaml"), StandardCharsets.UTF_8);
        assertThat(match).contains("\n      consumer-group: ${game.namespace:game}-match-rating\n");
    }

    @Test
    void 切片脚本带缺省值导出命名空间() throws IOException {
        for (String script : List.of("tools/local/start-slice.sh", "tools/local/battle-crash-window.sh")) {
            assertThat(Files.readString(root.resolve(script), StandardCharsets.UTF_8)).as(script)
                    .contains("\nexport GAME_NAMESPACE=\"${GAME_NAMESPACE:-game}\"\n");
        }
    }
}
