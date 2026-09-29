import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 把 mmorpg（C++/Go 版）里两版共享的契约同步进本仓库。
 *
 * <p>两版共享的只有客户端契约（mmorpg AGENTS.md §12）：proto 源、消息号 / 事件号注册表、
 * 导表器生成的配表 proto 与表数据。这些文件在本仓库里是<b>派生物</b>，不许手改，只能用本工具重新同步。
 *
 * <p>唯一的改写：给 {@code proto/} 下的文件注入 {@code java_package}（按目录区分）和
 * {@code java_multiple_files}。这两个选项只影响 Java 类的落点，不改 descriptor 全名，也不改线格式，
 * 所以与 C++/Go 端逐字节兼容。
 *
 * <p>用法（JDK 21 单文件运行）：
 * <pre>java tools/ContractSync.java --mmorpg D:/luyuan/wuxingqitan/mmorpg --commit 766cb037c</pre>
 * {@code --mmorpg} 可以是 git 工作树，也可以是 {@code git archive} 解出的快照目录；后者必须显式给
 * {@code --commit}。同步完成后把源 commit 与各注册表的 sha256 写进 {@code contract/SOURCE.properties}。
 */
public final class ContractSync {

    /** proto 源同步时排除的目录：etcd 的 proto 是 Go 端为 etcd 客户端生成用的，Java 版不连 etcd。 */
    private static final List<String> EXCLUDED_PROTO_DIRS = List.of("proto/etcd/");

    private static final String PROTO_JAVA_PACKAGE_ROOT = "com.game.proto";

    private ContractSync() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        Path source = Path.of(require(opts, "mmorpg")).toAbsolutePath().normalize();
        Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
        String commit = opts.containsKey("commit") ? opts.get("commit") : gitHead(source);

        Path protoOut = repo.resolve("xm-proto/src/main/proto");
        Path tableProtoOut = repo.resolve("xm-table/src/main/proto");
        Path tableJavaOut = repo.resolve("xm-table/src/main/java/com/game/table");
        Path registryOut = repo.resolve("xm-proto/src/main/resources/contract");
        Path tableDataOut = repo.resolve("config-data/tables");

        int protoCount = syncContractProtos(source.resolve("proto"), protoOut);
        int tableProtoCount = syncTableProtos(source.resolve("generated/code/proto"), tableProtoOut);
        int tableJavaCount = syncTableJava(source.resolve("generated/code/java"), tableJavaOut);
        syncRegistry(source.resolve("proto/message_id.txt"), registryOut.resolve("message_id.txt"));
        syncRegistry(source.resolve("proto/event_id.txt"), registryOut.resolve("event_id.txt"));
        int tableDataCount = syncTableData(source.resolve("generated/tables"), tableDataOut);

        Map<String, String> sourceInfo = new TreeMap<>();
        sourceInfo.put("mmorpg.commit", commit);
        sourceInfo.put("proto.files", Integer.toString(protoCount));
        sourceInfo.put("table.proto.files", Integer.toString(tableProtoCount));
        sourceInfo.put("table.java.files", Integer.toString(tableJavaCount));
        sourceInfo.put("table.data.files", Integer.toString(tableDataCount));
        sourceInfo.put("message_id.sha256", sha256(registryOut.resolve("message_id.txt")));
        sourceInfo.put("event_id.sha256", sha256(registryOut.resolve("event_id.txt")));
        writeSourceInfo(repo.resolve("contract/SOURCE.properties"), sourceInfo);

        System.out.printf("同步完成：mmorpg@%s proto=%d table-proto=%d table-data=%d%n",
                commit, protoCount, tableProtoCount, tableDataCount);
    }

    private static int syncContractProtos(Path protoRoot, Path outRoot) throws IOException {
        cleanDir(outRoot);
        List<Path> files = listFiles(protoRoot, ".proto");
        int count = 0;
        for (Path file : files) {
            String rel = "proto/" + slash(protoRoot.relativize(file));
            if (EXCLUDED_PROTO_DIRS.stream().anyMatch(rel::startsWith)) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String javaPackage = javaPackageFor(rel);
            Path target = outRoot.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.writeString(target, injectJavaOptions(text, javaPackage), StandardCharsets.UTF_8);
            count++;
        }
        return count;
    }

    /** 配表 proto 由导表器生成，已带 {@code java_package = "com.game.table"}，原样复制。 */
    private static int syncTableProtos(Path generatedProtoRoot, Path outRoot) throws IOException {
        cleanDir(outRoot);
        int count = 0;
        for (Path file : listFiles(generatedProtoRoot, ".proto")) {
            String rel = slash(generatedProtoRoot.relativize(file));
            // 只要顶层 *_table.proto 与 tip/、operator/；cpp/ java/ csharp/ python/ ue/ 是各语言的产物目录。
            boolean wanted = !rel.contains("/") || rel.startsWith("tip/") || rel.startsWith("operator/");
            if (!wanted) {
                continue;
            }
            Path target = outRoot.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.copy(file, target);
            count++;
        }
        return count;
    }

    /**
     * 导表器生成的 Java 表管理器（AllTable / *TableManager / constants / table_id）全部声明
     * {@code package com.game.table}，源目录却分了子目录；这里拍平到与包名一致的目录。
     */
    private static int syncTableJava(Path generatedJavaRoot, Path outDir) throws IOException {
        cleanDir(outDir);
        int count = 0;
        for (Path file : listFiles(generatedJavaRoot, ".java")) {
            Path target = outDir.resolve(file.getFileName().toString());
            if (Files.exists(target)) {
                throw new IllegalStateException("生成的 Java 表类重名: " + file.getFileName());
            }
            Files.copy(file, target);
            count++;
        }
        return count;
    }

    private static void syncRegistry(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** 表数据默认读 .pb（与 C++ 一致，也避开 4 张 Attribute* 表 JSON 文件名大小写不一的问题）。 */
    private static int syncTableData(Path tablesRoot, Path outRoot) throws IOException {
        cleanDir(outRoot);
        int count = 0;
        for (Path file : listFiles(tablesRoot, "")) {
            String name = file.getFileName().toString();
            boolean wanted = name.endsWith(".pb") || name.equals("manifest.json") || name.equals("tip_text.json");
            if (!wanted || !file.getParent().equals(tablesRoot)) {
                continue;
            }
            Files.copy(file, outRoot.resolve(name));
            count++;
        }
        return count;
    }

    static String javaPackageFor(String relPath) {
        // proto/common/base/tip.proto -> com.game.proto.common.base
        String dir = relPath.substring("proto/".length());
        int slash = dir.lastIndexOf('/');
        String sub = slash < 0 ? "" : dir.substring(0, slash).replace('/', '.');
        return sub.isEmpty() ? PROTO_JAVA_PACKAGE_ROOT : PROTO_JAVA_PACKAGE_ROOT + "." + sub;
    }

    static String injectJavaOptions(String text, String javaPackage) {
        if (text.contains("option java_package")) {
            throw new IllegalStateException("源 proto 已自带 java_package，需确认后再调整同步规则");
        }
        StringBuilder injected = new StringBuilder();
        injected.append("option java_package = \"").append(javaPackage).append("\";\n");
        if (!text.contains("option java_multiple_files")) {
            injected.append("option java_multiple_files = true;\n");
        }
        // 插在 syntax 行之后；没有 syntax 行（proto2 默认）就插在文件头。
        int syntaxAt = text.indexOf("syntax");
        if (syntaxAt < 0) {
            return injected + text;
        }
        int lineEnd = text.indexOf('\n', syntaxAt);
        if (lineEnd < 0) {
            return text + "\n" + injected;
        }
        return text.substring(0, lineEnd + 1) + injected + text.substring(lineEnd + 1);
    }

    private static List<Path> listFiles(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("目录不存在: " + root);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static void cleanDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        Files.createDirectories(dir);
    }

    private static void writeSourceInfo(Path file, Map<String, String> info) throws IOException {
        Files.createDirectories(file.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# 由 tools/ContractSync.java 生成，不要手改。记录本仓库契约派生物来自 mmorpg 的哪个 commit。");
        info.forEach((k, v) -> lines.add(k + "=" + v));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String gitHead(Path source) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "-C", source.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) {
            throw new IllegalArgumentException("--mmorpg 不是 git 工作树，请显式给 --commit: " + out);
        }
        return out;
    }

    private static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("参数格式: --key value，收到 " + args[i]);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) {
            throw new IllegalArgumentException("缺少参数 --" + key);
        }
        return v;
    }
}
