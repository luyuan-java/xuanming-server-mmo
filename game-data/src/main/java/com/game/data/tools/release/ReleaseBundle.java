package com.game.data.tools.release;

import static com.game.data.tools.release.ReleaseFailure.require;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 创建 / 验证只读离线原件。版本目录创建成功即占号，失败保留未封口目录，不自动回收或覆盖；
 * release-manifest.json 最后 CREATE_NEW，缺少或截断该文件均不能通过 verify。
 */
public final class ReleaseBundle {
    static final String MANIFEST = "release-manifest.json";
    static final String SUMS = "sha256sums.txt";
    public record Result(String version, String namespace, String manifestSha256,
                         String offline, String productionPreflight, java.util.List<String> notCovered) { }
    private final ReleaseSource.Repository repository;
    public ReleaseBundle() { this(ReleaseSource.GIT); }
    ReleaseBundle(ReleaseSource.Repository repository) { this.repository = java.util.Objects.requireNonNull(repository); }

    public Result create(Path source, Path specFile, Path releaseRoot, String namespace) throws IOException {
        ReleaseSpec spec = ReleaseJson.read(specFile, ReleaseSpec.class);
        ReleasePolicy.validate(spec);
        require(spec.namespace().equals(namespace), "NAMESPACE_MISMATCH");
        source = source.toAbsolutePath().normalize();
        releaseRoot = releaseRoot.toAbsolutePath().normalize();
        ReleaseFiles.noLinks(source);
        ReleaseFiles.noLinks(releaseRoot);
        require(Files.isDirectory(releaseRoot, LinkOption.NOFOLLOW_LINKS) && !releaseRoot.startsWith(source), "OUTPUT_ROOT_INVALID");
        ReleaseSource.Snapshot snapshot = repository.snapshot(source, spec.javaCommit());
        Facts facts = facts(snapshot.files(), spec);
        Path bundle = releaseRoot.resolve(spec.version());
        require(!Files.exists(bundle, LinkOption.NOFOLLOW_LINKS), "VERSION_EXISTS");
        // 与竞争的 create 互斥；从此之后任一失败都留下可识别的未封口目录，禁止重试覆盖。
        Files.createDirectory(bundle);
        Map<String, ReleaseManifest.FileEntry> inventory = new TreeMap<>();
        for (var entry : new TreeMap<>(snapshot.files()).entrySet()) {
            String path = "source/" + entry.getKey();
            ReleaseFiles.writeNew(bundle, path, entry.getValue());
            inventory.put(path, new ReleaseManifest.FileEntry(entry.getValue().length, ReleaseFiles.hash(entry.getValue()),
                    snapshot.blobs().get(entry.getKey())));
        }
        Map<String, Map<String, String>> builds = new TreeMap<>();
        for (String module : ReleaseService.modules()) {
            Path input = ReleaseFiles.safe(source, spec.jars().get(module));
            String before = ReleaseFiles.hash(input, ReleaseFiles.MAX_JAR_BYTES);
            String name = "jars/" + module + ".jar";
            ReleaseFiles.copyNew(bundle, name, input);
            Path copy = ReleaseFiles.safe(bundle, name);
            require(before.equals(ReleaseFiles.hash(copy, ReleaseFiles.MAX_JAR_BYTES))
                    && before.equals(ReleaseFiles.hash(input, ReleaseFiles.MAX_JAR_BYTES)), "JAR_CHANGED");
            builds.put(module, ReleaseJar.inspect(copy, module, spec, facts.maven(), facts.boot()));
            inventory.put(name, file(copy));
        }
        for (ReleaseService service : ReleaseService.values()) {
            String path = "planned-config/" + service.module() + ".properties";
            ReleaseFiles.writeNew(bundle, path, ReleasePolicy.properties(service, spec));
            inventory.put(path, file(ReleaseFiles.safe(bundle, path)));
        }
        ReleaseFiles.writeNew(bundle, "RELEASE_NOTES.md", facts.notes().getBytes(StandardCharsets.UTF_8));
        inventory.put("RELEASE_NOTES.md", file(ReleaseFiles.safe(bundle, "RELEASE_NOTES.md")));
        repository.unchanged(source, snapshot);
        ReleaseManifest manifest = new ReleaseManifest(1, ReleaseManifest.KIND, snapshot.createdAt(), spec,
                facts.maven(), "21", facts.boot(), facts.migration(), Map.copyOf(inventory), Map.copyOf(builds), ReleaseManifest.NOT_COVERED);
        byte[] bytes = ReleaseJson.bytes(manifest);
        String hash = ReleaseFiles.hash(bytes);
        ReleaseFiles.writeNew(bundle, SUMS, sums(inventory, hash));
        ReleaseFiles.writeNew(bundle, MANIFEST, bytes);
        return verify(bundle, hash, namespace);
    }

    /** 调用方必须提供从可信交付渠道取得的 manifest 摘要；包内自报摘要不能替代它。 */
    public Result verify(Path bundle, String expectedManifestHash, String namespace) throws IOException {
        require(expectedManifestHash != null && expectedManifestHash.matches("[0-9a-f]{64}"), "MANIFEST_PIN_REQUIRED");
        bundle = bundle.toAbsolutePath().normalize();
        Path manifestFile = ReleaseFiles.safe(bundle, MANIFEST);
        byte[] manifestBytes = ReleaseFiles.read(manifestFile, 8 * 1024 * 1024);
        require(ReleaseFiles.hash(manifestBytes).equals(expectedManifestHash), "MANIFEST_HASH_MISMATCH");
        ReleaseManifest manifest = ReleaseJson.read(manifestFile, ReleaseManifest.class);
        require(Arrays.equals(manifestBytes, ReleaseJson.bytes(manifest)), "MANIFEST_NOT_CANONICAL");
        require(manifest.format() == 1 && ReleaseManifest.KIND.equals(manifest.kind())
                && ReleaseManifest.NOT_COVERED.equals(manifest.notCovered()), "MANIFEST_PROFILE");
        try { Instant.parse(manifest.createdAt()); }
        catch (RuntimeException invalid) { throw new ReleaseFailure("MANIFEST_TIME"); }
        ReleaseSpec spec = manifest.plan();
        ReleasePolicy.validate(spec);
        require(spec.namespace().equals(namespace), "NAMESPACE_MISMATCH");
        require(bundle.getFileName().toString().equals(spec.version()), "BUNDLE_VERSION_DIRECTORY");
        require(manifest.files() != null && manifest.files().size() <= 20_000 && manifest.jarBuildInfo() != null
                && manifest.jarBuildInfo().keySet().equals(ReleaseService.modules()), "MANIFEST_INVENTORY");
        Set<String> expected = new TreeSet<>(manifest.files().keySet());
        require(!expected.contains(MANIFEST) && !expected.contains(SUMS), "MANIFEST_SELF_REFERENCE");
        expected.add(MANIFEST);
        expected.add(SUMS);
        require(ReleaseFiles.inventory(bundle).equals(expected), "BUNDLE_FILE_SET");
        Map<String, byte[]> sources = new TreeMap<>();
        long sourceBytes = 0;
        for (var entry : manifest.files().entrySet()) {
            Path path = ReleaseFiles.safe(bundle, entry.getKey());
            ReleaseManifest.FileEntry detail = entry.getValue();
            require(detail != null && detail.bytes() >= 0 && detail.sha256() != null && detail.sha256().matches("[0-9a-f]{64}")
                    && detail.gitBlob() != null && Files.size(path) == detail.bytes(), "FILE_ENTRY_INVALID");
            require(ReleaseFiles.hash(path, ReleaseFiles.MAX_JAR_BYTES).equals(detail.sha256()), "FILE_HASH_MISMATCH");
            if (entry.getKey().startsWith("source/")) {
                String relative = entry.getKey().substring(7);
                require(ReleaseSource.included(relative), "SOURCE_PATH_UNEXPECTED");
                byte[] bytes = ReleaseFiles.read(path, ReleaseFiles.MAX_SOURCE_BYTES);
                sourceBytes += bytes.length;
                require(sourceBytes <= 128L * 1024 * 1024 && sources.size() < 10_000, "SOURCE_BUDGET");
                require(ReleaseFiles.gitBlob(bytes).equals(detail.gitBlob()), "SOURCE_BLOB_MISMATCH");
                sources.put(relative, bytes);
            } else require(detail.gitBlob().isEmpty(), "UNEXPECTED_GIT_BLOB");
        }
        Facts facts = facts(sources, spec);
        require("21".equals(manifest.javaVersion()) && facts.maven().equals(manifest.mavenVersion())
                && facts.boot().equals(manifest.springBootVersion()) && facts.migration().equals(manifest.migrationDocumentHead()), "SOURCE_FACT_MISMATCH");
        Set<String> payload = new TreeSet<>();
        sources.keySet().forEach(path -> payload.add("source/" + path));
        for (ReleaseService service : ReleaseService.values()) {
            String jar = "jars/" + service.module() + ".jar";
            payload.add(jar);
            var build = ReleaseJar.inspect(ReleaseFiles.safe(bundle, jar), service.module(), spec, facts.maven(), facts.boot());
            require(build.equals(manifest.jarBuildInfo().get(service.module())), "JAR_BUILD_INFO_MISMATCH");
            String config = "planned-config/" + service.module() + ".properties";
            payload.add(config);
            require(Arrays.equals(ReleaseFiles.read(ReleaseFiles.safe(bundle, config), 65536), ReleasePolicy.properties(service, spec)), "PLANNED_CONFIG_MISMATCH");
        }
        payload.add("RELEASE_NOTES.md");
        require(payload.equals(manifest.files().keySet()), "PAYLOAD_PROFILE");
        require(Arrays.equals(ReleaseFiles.read(ReleaseFiles.safe(bundle, "RELEASE_NOTES.md"), ReleaseFiles.MAX_SOURCE_BYTES),
                facts.notes().getBytes(StandardCharsets.UTF_8)), "NOTES_MISMATCH");
        require(Arrays.equals(ReleaseFiles.read(ReleaseFiles.safe(bundle, SUMS), 4 * 1024 * 1024), sums(manifest.files(), expectedManifestHash)),
                "CHECKSUM_LIST_MISMATCH");
        return new Result(spec.version(), spec.namespace(), expectedManifestHash, "VERIFIED", "UNKNOWN", ReleaseManifest.NOT_COVERED);
    }

    private record Facts(String maven, String boot, String migration, String notes) { }
    private static Facts facts(Map<String, byte[]> source, ReleaseSpec spec) {
        require(source.containsKey("contract/SOURCE.properties") && source.containsKey("CHANGELOG.md")
                && source.containsKey("docs/design/db-migrations.md") && source.containsKey("deploy/docker/Dockerfile"), "SOURCE_REQUIRED");
        ReleaseSource.modules(source);
        require("21".equals(ReleaseSource.property(source.get("pom.xml"), "java.version")), "SOURCE_JAVA_VERSION");
        Properties properties = ReleaseJar.properties(source.get("contract/SOURCE.properties"));
        require(spec.contractCommit().equals(properties.getProperty("mmorpg.commit"))
                && spec.mapsCommit().equals(properties.getProperty("maps.source.commit")), "SOURCE_CONTRACT_MISMATCH");
        require(source.keySet().stream().anyMatch(path -> path.startsWith("config-data/tables/"))
                && source.keySet().stream().anyMatch(path -> path.startsWith("config-data/navmesh/"))
                && source.keySet().stream().anyMatch(path -> path.endsWith(".sql"))
                && source.keySet().stream().anyMatch(path -> path.endsWith(".proto")), "SOURCE_DOMAINS_MISSING");
        String migrations = new String(source.get("docs/design/db-migrations.md"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("(?m)^## M([1-9][0-9]{0,5})(?:[：（ :].*)?$").matcher(migrations);
        int last = 0;
        Set<Integer> versions = new java.util.HashSet<>();
        while (matcher.find()) {
            int version = Integer.parseInt(matcher.group(1));
            require(versions.add(version), "MIGRATION_HEADING_DUPLICATE");
            last = Math.max(last, version);
        }
        require(last > 0, "MIGRATION_DOCUMENT_HEAD_MISSING");
        String notes = ReleaseRules.notes(new String(source.get("CHANGELOG.md"), StandardCharsets.UTF_8), spec.version());
        return new Facts(ReleaseSource.mavenVersion(source.get("pom.xml")), ReleaseSource.property(source.get("pom.xml"), "spring-boot.version"),
                "M" + last, notes + "\n本包仅验证离线原件自洽；生产 preflight 为 UNKNOWN。详见 release-manifest.json 的 notCovered。\n");
    }
    private static ReleaseManifest.FileEntry file(Path path) throws IOException {
        return new ReleaseManifest.FileEntry(Files.size(path), ReleaseFiles.hash(path, ReleaseFiles.MAX_JAR_BYTES), "");
    }
    private static byte[] sums(Map<String, ReleaseManifest.FileEntry> files, String manifestHash) {
        Map<String, String> hashes = new TreeMap<>();
        files.forEach((path, detail) -> hashes.put(path, detail.sha256()));
        hashes.put(MANIFEST, manifestHash);
        StringBuilder text = new StringBuilder();
        hashes.forEach((path, hash) -> text.append(hash).append("  ").append(path).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
