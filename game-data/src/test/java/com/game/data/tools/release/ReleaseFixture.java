package com.game.data.tools.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 小型独立原件；假仓库只用于文件协议测试，不冒充真实 Git 来源证明。 */
final class ReleaseFixture {
    static final String JAVA_COMMIT = "a".repeat(40);
    static final String CONTRACT_COMMIT = "b".repeat(40);
    static final String MAPS_COMMIT = "c".repeat(40);
    static final String MAVEN = "0.1.0-SNAPSHOT";
    static final String BOOT = "3.5.16";
    final Path source;
    final Path output;
    final Path specFile;
    final ReleaseSpec spec;
    final Map<String, byte[]> sources = new TreeMap<>();

    ReleaseFixture(Path root) throws IOException {
        source = Files.createDirectories(root.resolve("source"));
        output = Files.createDirectories(root.resolve("releases"));
        specFile = root.resolve("plan.json");
        spec = spec();
        add("pom.xml", "<project><version>" + MAVEN + "</version><properties><java.version>21</java.version>"
                + "<spring-boot.version>" + BOOT + "</spring-boot.version></properties></project>");
        String modulePom = "<project><build><plugins><plugin><artifactId>spring-boot-maven-plugin</artifactId>"
                + "</plugin></plugins></build></project>";
        for (String module : ReleaseService.modules()) {
            add(module + "/pom.xml", modulePom);
            jar(source.resolve(spec.jars().get(module)), module, "", "");
        }
        add(ReleaseService.ROBOT + "/pom.xml", modulePom);
        add("contract/SOURCE.properties", "mmorpg.commit=" + CONTRACT_COMMIT + "\nmaps.source.commit=" + MAPS_COMMIT + "\n");
        add("CHANGELOG.md", "# 变更\n\n## [2.10.0] - 2026-10-27\n\n### 新增\n- 离线原件工具。\n\n## [2.9.0]\n- 旧版。\n");
        add("docs/design/db-migrations.md", "# 迁移\n## M1：初始\n说明\n## M14：候选\n未提供 live 证据。\n");
        add("deploy/docker/Dockerfile", "FROM example.invalid/jre@sha256:" + "d".repeat(64) + "\n");
        add("config-data/tables/table.json", "[]\n");
        add("config-data/navmesh/current_maps/navmesh.bin", "固定测试字节\n");
        add("game-data/src/main/resources/db/schema.sql", "CREATE TABLE example (id BIGINT PRIMARY KEY);\n");
        add("game-proto/src/main/proto/example.proto", "syntax = \"proto3\";\n");
        Files.write(specFile, ReleaseJson.bytes(spec));
    }

    void add(String path, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        sources.put(path, bytes);
        Path file = source.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    ReleaseSource.Snapshot snapshot() {
        Map<String, String> blobs = new TreeMap<>();
        sources.forEach((path, bytes) -> blobs.put(path, ReleaseFiles.gitBlob(bytes)));
        return new ReleaseSource.Snapshot(JAVA_COMMIT, "2026-10-27T00:00:00Z", Map.copyOf(blobs), Map.copyOf(sources));
    }
    ReleaseBundle tool() {
        return new ReleaseBundle(new ReleaseSource.Repository() {
            @Override public ReleaseSource.Snapshot snapshot(Path root, String commit) { return ReleaseFixture.this.snapshot(); }
            @Override public void unchanged(Path root, ReleaseSource.Snapshot original) { }
        });
    }
    Path bundle() { return output.resolve(spec.version()); }
    ReleaseBundle.Result create() throws IOException { return tool().create(source, specFile, output, "release1"); }

    static ReleaseSpec spec() {
        Map<String, String> jars = new TreeMap<>();
        Map<String, Map<String, String>> settings = new TreeMap<>();
        Map<String, String> migrations = new TreeMap<>();
        for (ReleaseService service : ReleaseService.values()) {
            jars.put(service.module(), service.module() + "/target/" + service.module() + ".jar");
            Map<String, String> config = new TreeMap<>();
            for (String key : ReleasePolicy.expectedKeys(service)) {
                config.put(key, switch (key) {
                    case "game.namespace" -> "release1";
                    case "game.run-mode", "game.login.mode" -> "prod";
                    case "spring.datasource.url" -> "jdbc:mysql://db.example:4000/release1?sslMode=VERIFY_IDENTITY&connectTimeout=3000&socketTimeout=10000";
                    case "spring.datasource.username" -> "release1_app";
                    case "spring.sql.init.mode" -> "never";
                    case "game.mall.mock-payment-enabled" -> "false";
                    case "game.audit.topic-generation" -> "1";
                    case "game.audit.replication-factor" -> "3";
                    case "game.audit.min-in-sync-replicas" -> "2";
                    default -> "true";
                });
            }
            settings.put(service.module(), config);
            if (service.usesSql()) migrations.put(service.module(), "migration-plan-1");
        }
        Map<String, String> secrets = new TreeMap<>();
        ReleasePolicy.SECRETS.forEach(key -> secrets.put(key, "secret-ref-v1"));
        return new ReleaseSpec(1, "v2.10.0", JAVA_COMMIT, CONTRACT_COMMIT, MAPS_COMMIT, "release1",
                "11000000-0000-0000-0000-000000000001", ReleasePolicy.PROTOCOL, ReleasePolicy.STRATEGY, jars, settings, secrets, migrations);
    }

    static ReleaseSpec withSettings(ReleaseSpec spec, String module, String key, String value) {
        Map<String, Map<String, String>> settings = new TreeMap<>(spec.settings());
        Map<String, String> changed = new TreeMap<>(settings.get(module));
        changed.put(key, value);
        settings.put(module, changed);
        return new ReleaseSpec(spec.format(), spec.version(), spec.javaCommit(), spec.contractCommit(), spec.mapsCommit(),
                spec.namespace(), spec.routeGeneration(), spec.directoryProtocol(), spec.rolloutStrategy(), spec.jars(), settings,
                spec.secretVersions(), spec.migrationPlans());
    }

    static void jar(Path target, String module, String propertyTail, String extraName) throws IOException {
        Files.createDirectories(target.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\r\n"
                    + "Start-Class: example.Main\r\nSpring-Boot-Version: " + BOOT + "\r\n\r\n");
            zip(zip, "BOOT-INF/classes/META-INF/build-info.properties", "build.artifact=" + module + "\nbuild.group=com.game\n"
                    + "build.commit=" + JAVA_COMMIT + "\nbuild.contract=" + CONTRACT_COMMIT + "\nbuild.version=" + MAVEN
                    + "\nbuild.time=2026-10-27T00:00:00Z\n" + propertyTail);
            zip.putNextEntry(new ZipEntry("org/springframework/boot/loader/launch/JarLauncher.class"));
            zip.write(classHeader());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("BOOT-INF/classes/example/Main.class"));
            zip.write(classHeader());
            zip.closeEntry();
            if (!extraName.isEmpty()) zip(zip, extraName, "irrelevant");
        }
    }
    static byte[] classHeader() { return java.nio.ByteBuffer.allocate(8).putInt(0xCAFEBABE).putShort((short) 0).putShort((short) 65).array(); }
    private static void zip(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
