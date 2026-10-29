package com.game.data.tools.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReleasePolicyTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"V1.2.3", "v01.2.3", "v1.2.3\n", "v１.2.3", "1.2.3", "v1.2", " v1.2.3", "v1.2.3+meta", "v1.2.3-rc..1"})
    void invalidVersionIsRejected(String version) { assertThat(ReleaseRules.version(version)).isFalse(); }

    @Test void versionHasBoundedImageTagHeadroom() {
        assertThat(ReleaseRules.version("v1.2.3-rc.1")).isTrue();
        assertThat(ReleaseRules.version("v1.2.3-" + "a".repeat(108))).isTrue();
        assertThat(ReleaseRules.version("v1.2.3-" + "a".repeat(109))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"# 无版本", "## [1.2.3]\n\n## [1.2.3]\n正文", "## [1.2.3]\n\n", "## [1.2.3]\n[x]: https://example.invalid/",
            "## [1.2.3-rc.1]\n正文", "## [11.2.3]\n正文"})
    void changelogMustHaveOneNonemptyExactSection(String changelog) {
        assertThatThrownBy(() -> ReleaseRules.notes(changelog, "v1.2.3")).isInstanceOf(ReleaseFailure.class);
    }

    @Test void changelogKeepsOnlyTheSelectedVersion() {
        assertThat(ReleaseRules.notes("## [1.2.3] - 2026-10-27\r\n### 新增\r\n- 正文\r\n## [1.2.2]\r\n旧正文\r\n", "v1.2.3"))
                .isEqualTo("## [1.2.3]\n### 新增\n- 正文\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.jar", "/outside.jar", "a\\b.jar", "a//b.jar", "a/./b", "a/../b", "a/b.", "a/NuL.txt", "C:/file", "x:y", "a/"})
    void unsafePathsAreRejected(String path) {
        assertThatThrownBy(() -> ReleaseFiles.relative(path)).hasMessage("PATH_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mysql://root:secret@db:4000/release1?sslMode=VERIFY_IDENTITY&connectTimeout=1&socketTimeout=1",
            "jdbc:mysql://db:4000/other?sslMode=VERIFY_IDENTITY&connectTimeout=1&socketTimeout=1",
            "jdbc:mysql://db:4000/release1?sslMode=REQUIRED&connectTimeout=1&socketTimeout=1",
            "jdbc:mysql://db:4000/release1?sslMode=VERIFY_IDENTITY&connectTimeout=1&socketTimeout=1&sslMode=DISABLED",
            "jdbc:mysql://db:4000/release1?sslMode=VERIFY_IDENTITY&connectTimeout=0&socketTimeout=1",
            "jdbc:mysql://db:4000/release1?sslMode=VERIFY_IDENTITY&connectTimeout=1&socketTimeout=60001",
            "jdbc:mysql://db/release1?sslMode=VERIFY_IDENTITY&connectTimeout=1&socketTimeout=1"})
    void dsnRequiresCorrectTenantTlsAndFiniteTimeouts(String dsn) {
        assertThatThrownBy(() -> ReleasePolicy.validateDsn(dsn, "release1")).isInstanceOf(ReleaseFailure.class);
    }

    @Test void productionPlanRejectsDevAndWeakDurability() {
        ReleaseSpec spec = ReleaseFixture.spec();
        ReleasePolicy.validate(spec);
        assertThatThrownBy(() -> ReleasePolicy.validate(ReleaseFixture.withSettings(spec, "game-gate", "game.run-mode", "dev"))).hasMessage("PROD_MODE:GATE");
        assertThatThrownBy(() -> ReleasePolicy.validate(ReleaseFixture.withSettings(spec, "game-mall", "game.mall.mock-payment-enabled", "true"))).hasMessage("MALL_MOCK_PAYMENT");
        assertThatThrownBy(() -> ReleasePolicy.validate(ReleaseFixture.withSettings(spec, "game-match", "game.match.global.enabled", "false"))).hasMessage("GLOBAL_MATCH_REQUIRED");
        assertThatThrownBy(() -> ReleasePolicy.validate(ReleaseFixture.withSettings(spec, "game-scene", "game.audit.replication-factor", "1"))).hasMessage("AUDIT_DURABILITY");
        assertThatThrownBy(() -> ReleasePolicy.validate(ReleaseFixture.withSettings(spec, "game-data", "game.audit.topic-generation", "2"))).hasMessage("AUDIT_GENERATION_MISMATCH");
        assertThatThrownBy(() -> ReleasePolicy.validate(ReleaseFixture.withSettings(spec, "game-login", "game.player-store.require-tidb", "false"))).hasMessage("TIDB_REQUIRED:LOGIN");
        assertThatThrownBy(() -> ReleasePolicy.validate(ReleaseFixture.withSettings(spec, "game-mail", "spring.sql.init.mode", "always"))).hasMessage("SQL_AUTO_INIT:MAIL");
    }

    @Test void planDefensivelyCopiesAllMaps() {
        ReleaseSpec original = ReleaseFixture.spec();
        Map<String, Map<String, String>> settings = new TreeMap<>();
        original.settings().forEach((name, values) -> settings.put(name, new TreeMap<>(values)));
        ReleaseSpec copy = new ReleaseSpec(original.format(), original.version(), original.javaCommit(), original.contractCommit(), original.mapsCommit(),
                original.namespace(), original.routeGeneration(), original.directoryProtocol(), original.rolloutStrategy(), original.jars(), settings,
                original.secretVersions(), original.migrationPlans());
        settings.get("game-gate").put("game.run-mode", "dev");
        assertThat(copy.settings().get("game-gate")).containsEntry("game.run-mode", "prod");
        assertThatThrownBy(() -> copy.settings().get("game-gate").put("game.run-mode", "dev")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void jsonRejectsDuplicateUnknownTrailingNullAndCoercedFields() throws Exception {
        String json = new String(ReleaseJson.bytes(ReleaseFixture.spec()), java.nio.charset.StandardCharsets.UTF_8);
        String[] invalid = {json.replace("\"format\":1", "\"format\":1,\"format\":1"),
                json.replace("\"format\":1", "\"format\":1,\"applied\":true"), json + "{}",
                json.replace("\"format\":1", "\"format\":null"), json.replace("\"format\":1", "\"format\":\"1\""),
                json.replace("\"format\":1", "\"format\":1.1"), json.replace("\"namespace\":\"release1\"", "\"namespace\":null")};
        Path input = root.resolve("invalid.json");
        for (String value : invalid) {
            Files.writeString(input, value);
            assertThatThrownBy(() -> ReleaseJson.read(input, ReleaseSpec.class)).hasMessage("JSON_INVALID");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "3.5", "true"})
    void jsonRejectsScalarCoercionToStringInFieldsAndNestedMaps(String scalar) throws Exception {
        String json = new String(ReleaseJson.bytes(ReleaseFixture.spec()), java.nio.charset.StandardCharsets.UTF_8);
        String[] fields = {"\"namespace\":\"release1\"", "\"game.audit.enabled\":\"true\"",
                "\"game.audit.replication-factor\":\"3\"", "\"GAME_MYSQL_PASSWORD\":\"secret-ref-v1\"",
                "\"game-data\":\"migration-plan-1\""};
        Path input = root.resolve("invalid-string-type.json");
        for (String field : fields) {
            assertThat(json).as("夹具必须实际含目标字段").contains(field);
            String invalid = json.replace(field, field.substring(0, field.indexOf(':') + 1) + scalar);
            Files.writeString(input, invalid);
            assertThatThrownBy(() -> ReleaseJson.read(input, ReleaseSpec.class))
                    .as("字符串字段 %s 不得接受 %s", field, scalar).hasMessage("JSON_INVALID");
        }
    }
}
