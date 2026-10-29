package com.game.data.tools.release;

import static com.game.data.tools.release.ReleaseFailure.require;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Git 只读适配；选定文件还核对 Git blob ID，避免复制时读取到未提交字节。 */
final class ReleaseSource {
    record Snapshot(String commit, String createdAt, Map<String, String> blobs, Map<String, byte[]> files) { }
    interface Repository {
        Snapshot snapshot(Path root, String commit) throws IOException;
        void unchanged(Path root, Snapshot original) throws IOException;
    }
    static final Repository GIT = new GitRepository();
    private ReleaseSource() { }

    static boolean included(String path) {
        return path.equals("pom.xml") || path.equals("CHANGELOG.md") || path.equals("contract/SOURCE.properties")
                || path.equals("docs/design/db-migrations.md") || path.equals("deploy/docker/Dockerfile")
                || path.matches("game-[a-z-]+/pom\\.xml")
                || path.startsWith("config-data/tables/") || path.startsWith("config-data/navmesh/")
                || path.startsWith("game-proto/src/main/resources/contract/")
                || path.matches("game-[a-z-]+/src/main/proto/.+\\.proto")
                || path.matches("game-[a-z-]+/src/main/resources/.+\\.sql")
                || path.matches("game-[a-z-]+/src/main/resources/application[^/]*\\.yaml");
    }

    static String property(byte[] pom, String name) {
        Element root = xml(pom);
        var properties = root.getElementsByTagName("properties");
        require(properties.getLength() == 1, "POM_PROPERTIES");
        var value = ((Element) properties.item(0)).getElementsByTagName(name);
        require(value.getLength() == 1, "POM_PROPERTY");
        return value.item(0).getTextContent().strip();
    }

    static String mavenVersion(byte[] pom) {
        Element root = xml(pom);
        for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals("version")) return element.getTextContent().strip();
        }
        throw new ReleaseFailure("POM_VERSION");
    }

    static void modules(Map<String, byte[]> files) {
        Set<String> actual = new TreeSet<>();
        for (var entry : files.entrySet()) {
            if (!entry.getKey().matches("game-[a-z-]+/pom\\.xml")) continue;
            var plugins = xml(entry.getValue()).getElementsByTagName("plugin");
            for (int i = 0; i < plugins.getLength(); i++) {
                var artifacts = ((Element) plugins.item(i)).getElementsByTagName("artifactId");
                for (int j = 0; j < artifacts.getLength(); j++) {
                    if (artifacts.item(j).getTextContent().equals("spring-boot-maven-plugin")) actual.add(entry.getKey().split("/")[0]);
                }
            }
        }
        Set<String> expected = new TreeSet<>(ReleaseService.modules());
        expected.add(ReleaseService.ROBOT);
        require(actual.equals(expected), "BOOT_MODULE_DRIFT");
    }

    private static Element xml(byte[] bytes) {
        require(bytes != null, "POM_MISSING");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        } catch (Exception invalid) { throw new ReleaseFailure("POM_INVALID"); }
    }

    private static final class GitRepository implements Repository {
        @Override public Snapshot snapshot(Path root, String commit) throws IOException {
            clean(root, commit);
            String createdAt = git(root, "show", "-s", "--format=%cI", "HEAD").strip();
            try { createdAt = Instant.parse(createdAt).toString(); }
            catch (RuntimeException invalid) { throw new ReleaseFailure("COMMIT_TIME_INVALID"); }
            Map<String, String> blobs = new TreeMap<>();
            Map<String, byte[]> files = new TreeMap<>();
            long total = 0;
            for (String row : git(root, "ls-tree", "-r", "-z", "--full-tree", "HEAD").split(String.valueOf((char) 0))) {
                if (row.isEmpty()) continue;
                String[] fields = row.split("\t", 2);
                require(fields.length == 2, "GIT_TREE_INVALID");
                String path = fields[1];
                if (!included(path)) continue;
                String[] metadata = fields[0].split(" ");
                require(metadata.length == 3 && metadata[0].matches("100(644|755)") && metadata[1].equals("blob"), "SOURCE_FILE_MODE");
                byte[] bytes = ReleaseFiles.read(ReleaseFiles.safe(root, path), ReleaseFiles.MAX_SOURCE_BYTES);
                total += bytes.length;
                require(total <= 128L * 1024 * 1024 && files.size() < 10_000, "SOURCE_BUDGET");
                require(ReleaseFiles.gitBlob(bytes).equals(metadata[2]), "SOURCE_CHANGED");
                files.put(path, bytes);
                blobs.put(path, metadata[2]);
            }
            require(files.containsKey("contract/SOURCE.properties") && files.containsKey("CHANGELOG.md")
                    && files.containsKey("docs/design/db-migrations.md") && files.containsKey("deploy/docker/Dockerfile"), "SOURCE_REQUIRED");
            clean(root, commit);
            return new Snapshot(commit, createdAt, Map.copyOf(blobs), Map.copyOf(files));
        }

        @Override public void unchanged(Path root, Snapshot original) throws IOException {
            Snapshot current = snapshot(root, original.commit());
            require(original.blobs().equals(current.blobs()), "SOURCE_CHANGED");
        }

        private void clean(Path root, String commit) throws IOException {
            require(Path.of(git(root, "rev-parse", "--show-toplevel").strip()).toRealPath().equals(root.toRealPath()),
                    "SOURCE_NOT_REPOSITORY_ROOT");
            require(git(root, "rev-parse", "HEAD").strip().equals(commit), "SOURCE_COMMIT_MISMATCH");
            for (String item : git(root, "ls-files", "-v", "-z").split(String.valueOf((char) 0))) {
                if (!item.isEmpty()) require(item.startsWith("H "), "SOURCE_INDEX_FLAGS");
            }
            require(git(root, "status", "--porcelain=v1", "--untracked-files=all", "--ignore-submodules=none").isEmpty(), "SOURCE_DIRTY");
        }
    }

    private static String git(Path root, String... args) throws IOException {
        var command = new java.util.ArrayList<>(java.util.List.of("git", "--no-optional-locks", "-c", "core.fsmonitor=false",
                "-c", "core.untrackedCache=false", "-C", root.toString()));
        command.addAll(java.util.List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        // 不继承 GIT_DIR / GIT_INDEX_FILE / GIT_CONFIG_COUNT 等重定向，实际仓库只由 --source 指定。
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        Process process = builder.start();
        var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (var stream = process.getInputStream()) { return stream.readNBytes(8 * 1024 * 1024 + 1); }
            catch (IOException failed) { throw new java.util.concurrent.CompletionException(failed); }
        });
        try {
            require(process.waitFor(15, TimeUnit.SECONDS), "GIT_TIMEOUT");
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            require(process.exitValue() == 0 && bytes.length <= 8 * 1024 * 1024, "GIT_READ_FAILED");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ReleaseFailure("GIT_INTERRUPTED");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new ReleaseFailure("GIT_READ_FAILED");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
