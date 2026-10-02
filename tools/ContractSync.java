import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把 mmorpg（C++/Go 版）里两版共享的契约同步进本仓库。
 *
 * <p>两版共享的只有客户端契约（mmorpg AGENTS.md §12）：proto 源、消息号 / 事件号注册表、配置表 schema 与表数据。
 * 这些文件在本仓库里是<b>派生物</b>，不许手改，只能用本工具重新同步。
 *
 * <ul>
 *   <li>客户端契约 proto（{@code proto/**}）→ {@code xm-proto/src/main/proto/proto/**}：按源相对路径原样存放（import 里写死了路径），
 *       只注入 {@code java_package} / {@code java_outer_classname} / {@code java_multiple_files}——只影响 Java 类落点，
 *       不改 descriptor 全名与线格式。Java 代码不依赖这套目录（包名按 proto package，见 architecture.md §1）。</li>
 *   <li>配置表 schema：取导表器的<b>权威 schema</b> {@code data/schema/*_table.proto} 与 option 词表 {@code cfg_options.proto}
 *       （带主键 / 键 / 索引 / 外键语义），而不是导表器产出的无 option 副本。只给策划 / 客户端用的列
 *       （{@code cfg_owner} 不是 server / common）不进服务端产物，同步时改写成 {@code reserved}。
 *       表数据是导表器某次导出的产物，可能比 HEAD 的 schema 旧：以同批产出的 {@code generated/code/proto} 为准对齐，
 *       schema 新加、数据里还没有的字段改写成 reserved、还没导出的表不同步（都记进 SOURCE.properties 的 pending 项）。
 *       Java 的表访问代码由 xm-table-codegen 在编译期按这份 schema 生成，不再同步导表器产出的 Java 管理器。</li>
 *   <li>tip / operator 枚举 proto（导表器由 Tip.xlsx / Operator.xlsx 生成，没有别的出处）原样复制。</li>
 *   <li>具名行 id 常量（Excel 的 constants_name 列，不进 .pb）：取导表器产出的 C++ 常量头文件，
 *       生成 {@code com.game.table.TableConstants}。</li>
 *   <li>表数据 {@code generated/tables/*.pb} + {@code manifest.json}（行数与 sha256，运行时据此校验）+ {@code tip_text.json}。</li>
 * </ul>
 *
 * <p>文本产物一律写 LF 行尾、文件按相对路径字符串排序，跨平台同步结果逐字节一致（SOURCE.properties 里的 sha256 才稳定）。
 * 同步先完整写到临时目录、全部校验通过后才整体替换仓库里的受管路径，中途失败不会把仓库改到一半。
 *
 * <p>用法（JDK 21 单文件运行，在仓库根目录）：
 * <pre>
 * java tools/ContractSync.java --mmorpg ../mmorpg              # 同步
 * java tools/ContractSync.java --mmorpg ../mmorpg --check      # 只检查本仓库是否与 mmorpg 一致（CI 用），不一致退出码 1
 * java tools/ContractSync.java --mmorpg snapshot/ --commit 26ceb70ca   # 源目录不是 git 工作树时显式给 commit
 * </pre>
 */
public final class ContractSync {

    /** proto 源同步时排除的目录：etcd 的 proto 是 Go 端为 etcd 客户端生成用的，Java 版不连 etcd。 */
    private static final List<String> EXCLUDED_PROTO_DIRS = List.of("proto/etcd/");

    private static final String PROTO_JAVA_PACKAGE_ROOT = "com.game.proto";
    private static final String TABLE_JAVA_PACKAGE = "com.game.table";
    private static final String CFG_OPTIONS = "cfg_options.proto";
    private static final Set<String> SERVER_OWNERS = Set.of("server", "common");

    /** 本工具管理的目标路径（相对仓库根），--check 逐一比对。 */
    private static final String PROTO_OUT = "xm-proto/src/main/proto";
    private static final String REGISTRY_OUT = "xm-proto/src/main/resources/contract";
    private static final String TABLE_PROTO_OUT = "xm-table/src/main/proto";
    private static final String TABLE_CONSTANTS_OUT = "xm-table/src/main/java/com/game/table/TableConstants.java";
    private static final String TABLE_DATA_OUT = "config-data/tables";
    private static final String SOURCE_INFO_OUT = "contract/SOURCE.properties";
    private static final List<String> MANAGED = List.of(PROTO_OUT, REGISTRY_OUT, TABLE_PROTO_OUT, TABLE_CONSTANTS_OUT,
            TABLE_DATA_OUT, SOURCE_INFO_OUT);

    private ContractSync() {
    }

    public static void main(String[] args) throws Exception {
        if (System.console() == null) {
            // 输出被管道 / IDE 接走时 JVM 用平台编码，中文会变问号；统一 UTF-8。
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        }
        Map<String, String> opts = parseArgs(args);
        Path source = Path.of(require(opts, "mmorpg")).toAbsolutePath().normalize();
        Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
        String commit = opts.containsKey("commit") ? opts.get("commit") : gitHead(source);
        if (!Files.isRegularFile(repo.resolve("pom.xml")) || !Files.isDirectory(repo.resolve("xm-proto"))) {
            throw new IllegalArgumentException("--repo 不是本仓库根目录: " + repo);
        }

        // 先完整同步到临时目录：任何校验失败都发生在这一步，仓库不会被改到一半。
        Path scratch = Files.createTempDirectory("contract-sync");
        int exitCode = 0;
        try {
            Map<String, String> info = sync(source, scratch, commit);
            if (opts.containsKey("check")) {
                List<String> diffs = compare(scratch, repo);
                if (diffs.isEmpty()) {
                    System.out.printf("契约一致：mmorpg@%s（%s）%n", commit, summary(info));
                } else {
                    System.out.println("契约与 mmorpg@" + commit + " 不一致（运行不带 --check 的同步并提交）：");
                    diffs.forEach(d -> System.out.println("  " + d));
                    exitCode = 1;
                }
            } else {
                install(scratch, repo);
                System.out.printf("同步完成：mmorpg@%s（%s）%n同步后须 ./mvnw clean install（protoc 增量生成不清理改名 / 删除的类）%n",
                        commit, summary(info));
            }
            printPending(info);
        } finally {
            deleteTree(scratch);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** 把契约派生物写到 {@code out}（临时目录），返回写进 SOURCE.properties 的信息。 */
    static Map<String, String> sync(Path source, Path out, String commit) throws IOException {
        Path protoOut = out.resolve(PROTO_OUT);
        Path registryOut = out.resolve(REGISTRY_OUT);
        Path tableProtoOut = out.resolve(TABLE_PROTO_OUT);

        int protoCount = syncContractProtos(source.resolve("proto"), protoOut);
        cleanDir(tableProtoOut);
        TableSchemaReport tables = syncTableSchemas(source.resolve("data/schema"),
                source.resolve("generated/code/proto"), tableProtoOut);
        int enumCount = syncTableEnums(source.resolve("generated/code/proto"), tableProtoOut);
        int constantCount = syncTableConstants(source.resolve("cpp/generated/table/code/constants"),
                out.resolve(TABLE_CONSTANTS_OUT));
        cleanDir(registryOut);
        copyText(source.resolve("proto/message_id.txt"), registryOut.resolve("message_id.txt"));
        copyText(source.resolve("proto/event_id.txt"), registryOut.resolve("event_id.txt"));
        Path tableDataOut = out.resolve(TABLE_DATA_OUT);
        int tableDataCount = syncTableData(source.resolve("generated/tables"), tableDataOut);
        String manifest = readText(tableDataOut.resolve("manifest.json"));
        requireManifestTablesHaveSchema(manifest, tables.sheets());

        Map<String, String> info = new TreeMap<>();
        info.put("mmorpg.commit", commit);
        info.put("proto.files", Integer.toString(protoCount));
        info.put("table.schema.files", Integer.toString(tables.sheets().size()));
        info.put("table.enum.files", Integer.toString(enumCount));
        info.put("table.constants", Integer.toString(constantCount));
        info.put("table.data.files", Integer.toString(tableDataCount));
        // 表数据是导表器某一次导出的产物，可能比 mmorpg HEAD 旧（或来自脏工作树）：如实记下来源。
        info.put("table.data.commit", manifestValue(manifest, "commit"));
        info.put("table.data.dirty", manifestValue(manifest, "data_dirty"));
        info.put("table.pending.fields", String.join(",", tables.pendingFields()));
        info.put("table.pending.sheets", String.join(",", tables.pendingSheets()));
        info.put("message_id.sha256", sha256(registryOut.resolve("message_id.txt")));
        info.put("event_id.sha256", sha256(registryOut.resolve("event_id.txt")));
        writeSourceInfo(out.resolve(SOURCE_INFO_OUT), info);
        return info;
    }

    /** 用临时目录里的结果整体替换仓库里的受管路径。 */
    private static void install(Path scratch, Path repo) throws IOException {
        for (String managed : MANAGED) {
            Path from = scratch.resolve(managed);
            Path to = repo.resolve(managed);
            if (Files.isDirectory(from)) {
                deleteTree(to);
                for (Path file : listFiles(from, "")) {
                    Path target = to.resolve(from.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            } else {
                Files.createDirectories(to.getParent());
                Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String summary(Map<String, String> info) {
        return "proto=" + info.get("proto.files") + " 表schema=" + info.get("table.schema.files")
                + " tip/operator=" + info.get("table.enum.files") + " 常量=" + info.get("table.constants")
                + " 表数据=" + info.get("table.data.files") + "（导出自 " + info.get("table.data.commit")
                + ("true".equals(info.get("table.data.dirty")) ? "，脏工作树" : "") + "）";
    }

    private static void printPending(Map<String, String> info) {
        if (!info.get("table.pending.fields").isEmpty()) {
            System.out.println("schema 已声明、本批导表产物里还没有的字段（Java 侧暂按 reserved 处理，mmorpg 重新导表后再同步）："
                    + info.get("table.pending.fields"));
        }
        if (!info.get("table.pending.sheets").isEmpty()) {
            System.out.println("schema 已有、导表器尚未产出的表（暂不同步）：" + info.get("table.pending.sheets"));
        }
    }

    // ------------------------------------------------------------------ 客户端契约 proto

    private static int syncContractProtos(Path protoRoot, Path outRoot) throws IOException {
        cleanDir(outRoot);
        int count = 0;
        for (Path file : listFiles(protoRoot, ".proto")) {
            String rel = "proto/" + slash(protoRoot.relativize(file));
            if (EXCLUDED_PROTO_DIRS.stream().anyMatch(rel::startsWith)) {
                continue;
            }
            String text = readText(file);
            writeText(outRoot.resolve(rel), injectJavaOptions(text, javaPackageFor(text), outerClassNameFor(rel)));
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ 配置表 schema / 枚举 / 常量 / 数据

    /**
     * @param sheets        已同步的表（sheet 名）
     * @param pendingFields schema 已声明、本批导表产物里还没有的字段（{@code Sheet.field}），同步时改写成 reserved
     * @param pendingSheets schema 已有、导表器还没产出的表，不同步
     */
    record TableSchemaReport(Set<String> sheets, List<String> pendingFields, List<String> pendingSheets) {
    }

    private static final Pattern SHEET_OPTION = Pattern.compile("option\\s*\\(cfg_sheet\\)\\s*=\\s*\"([^\"]+)\"");

    /**
     * 权威 schema：{@code cfg_options.proto} + {@code *_table.proto}，平铺在 proto 根目录（schema 里 import 的就是 "cfg_options.proto"）。
     *
     * <p>表数据（.pb）是导表器某次导出的产物，可能比 HEAD 的 schema 旧。导表器每次导出同时产出 {@code generated/code/proto}
     * 下的产物 proto，它与 .pb 逐字段一致，所以以它为准对齐：
     * <ul>
     *   <li>schema 有、产物没有的字段（schema 已加、还没重新导表）→ 改写成 reserved，记为 pending（否则 Java 会把它读成恒 0）；</li>
     *   <li>产物有、schema 没有的字段，或同号字段名字 / 类型 / 标签不同 → 同步失败（schema 与数据矛盾，不能猜）；</li>
     *   <li>没有产物 proto 的表（schema 已加、导表器还没产出）→ 不同步，记为 pending。</li>
     * </ul>
     */
    private static TableSchemaReport syncTableSchemas(Path schemaRoot, Path productRoot, Path outRoot) throws IOException {
        Path options = schemaRoot.resolve(CFG_OPTIONS);
        writeText(outRoot.resolve(CFG_OPTIONS),
                injectJavaOptions(readText(options), TABLE_JAVA_PACKAGE, outerClassNameFor(CFG_OPTIONS)));
        Set<String> sheets = new TreeSet<>();
        List<String> pendingFields = new ArrayList<>();
        List<String> pendingSheets = new ArrayList<>();
        for (Path file : listFiles(schemaRoot, "_table.proto")) {
            if (!file.getParent().equals(schemaRoot)) {
                continue;
            }
            String name = file.getFileName().toString();
            String text = stripNonServerFields(name, readText(file));
            Matcher sheet = SHEET_OPTION.matcher(text);
            if (!sheet.find()) {
                throw new IllegalStateException(name + ": 找不到 option (cfg_sheet)");
            }
            Path product = productRoot.resolve(name);
            if (!Files.isRegularFile(product)) {
                pendingSheets.add(sheet.group(1));
                continue;
            }
            text = alignWithProduct(name, text, readText(product), sheet.group(1), pendingFields);
            writeText(outRoot.resolve(name), injectJavaOptions(text, TABLE_JAVA_PACKAGE, outerClassNameFor(name)));
            sheets.add(sheet.group(1));
        }
        if (sheets.isEmpty()) {
            throw new IllegalStateException("没有找到任何配置表 schema: " + schemaRoot);
        }
        return new TableSchemaReport(sheets, pendingFields, pendingSheets);
    }

    /** 一个字段的签名：标签 + 类型 + 名字（类型去空白，{@code map<a, b>} 与 {@code map<a,b>} 视为相同）。 */
    record ProtoField(String label, String type, String name) {
    }

    private static final Pattern MESSAGE_OPEN = Pattern.compile("^\\s*message\\s+(\\w+)\\s*\\{");
    private static final Pattern ENUM_OPEN = Pattern.compile("^\\s*enum\\s+(\\w+)\\s*\\{");
    private static final Pattern FIELD_DECL = Pattern.compile(
            "^(\\s*)(repeated\\s+|optional\\s+)?(map\\s*<[^>]+>|[\\w.]+)\\s+(\\w+)\\s*=\\s*(\\d+)\\s*(\\[.*])?\\s*;.*$");

    /**
     * 逐行跟踪当前所在的块（消息 / 枚举 / 其他花括号块），给字段声明定归属。只认本仓库会遇到的写法：
     * 块的开头独占一行（{@code message X {}），字段声明单行。
     */
    static final class BlockStack {
        private final List<String> stack = new ArrayList<>();   // 消息名；"#enum"；"#block"（其他花括号）

        /** 当前所在消息的路径（嵌套消息用点连接）；不在消息里返回 null；在枚举里也返回 null。 */
        String messagePath() {
            if (stack.isEmpty() || stack.get(stack.size() - 1).equals("#enum")) {
                return null;
            }
            List<String> names = stack.stream().filter(s -> !s.startsWith("#")).toList();
            return names.isEmpty() ? null : String.join(".", names);
        }

        /** 处理完一行（已去注释）后更新块栈。 */
        void advance(String code) {
            Matcher msg = MESSAGE_OPEN.matcher(code);
            Matcher en = ENUM_OPEN.matcher(code);
            String first = msg.find() ? msg.group(1) : en.find() ? "#enum" : "#block";
            boolean firstUsed = false;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') {
                    stack.add(firstUsed ? "#block" : first);
                    firstUsed = true;
                } else if (c == '}' && !stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            }
        }
    }

    private static String stripComment(String line) {
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
    }

    /** 解析消息字段：消息路径 → 字段号 → 签名。字段声明须单行。 */
    static Map<String, Map<Integer, ProtoField>> parseFields(String text) {
        Map<String, Map<Integer, ProtoField>> messages = new LinkedHashMap<>();
        BlockStack blocks = new BlockStack();
        for (String raw : text.split("\n")) {
            String code = stripComment(raw);
            String path = blocks.messagePath();
            Matcher f = FIELD_DECL.matcher(code);
            if (path != null && f.matches()) {
                String label = f.group(2) == null ? "" : f.group(2).strip();
                ProtoField field = new ProtoField(label, f.group(3).replaceAll("\s+", ""), f.group(4));
                if (messages.computeIfAbsent(path, k -> new LinkedHashMap<>()).put(Integer.parseInt(f.group(5)), field) != null) {
                    throw new IllegalStateException("字段号重复: " + path + "." + f.group(4));
                }
            }
            blocks.advance(code);
            String opened = blocks.messagePath();
            if (opened != null) {
                messages.computeIfAbsent(opened, k -> new LinkedHashMap<>());
            }
        }
        return messages;
    }

    /** 见 {@link #syncTableSchemas}：把 schema 对齐到本批导表产物。 */
    static String alignWithProduct(String fileName, String schemaText, String productText, String sheet,
                                   List<String> pendingFields) {
        Map<String, Map<Integer, ProtoField>> schema = parseFields(schemaText);
        Map<String, Map<Integer, ProtoField>> product = parseFields(productText);
        Set<String> toReserve = new TreeSet<>();   // "消息路径#字段号"
        for (Map.Entry<String, Map<Integer, ProtoField>> msg : schema.entrySet()) {
            Map<Integer, ProtoField> productFields = product.get(msg.getKey());
            if (productFields == null) {
                continue;   // 产物里没有的子消息：引用它的字段一定也不在产物里，会被改写成 reserved
            }
            for (Map.Entry<Integer, ProtoField> f : msg.getValue().entrySet()) {
                ProtoField inProduct = productFields.get(f.getKey());
                if (inProduct == null) {
                    toReserve.add(msg.getKey() + "#" + f.getKey());
                    pendingFields.add(sheet + "." + f.getValue().name());
                } else if (!inProduct.equals(f.getValue())) {
                    throw new IllegalStateException(fileName + ": " + msg.getKey() + " 字段 " + f.getKey()
                            + " schema 是 " + f.getValue() + "，导表产物是 " + inProduct + "（schema 与数据矛盾）");
                }
            }
            for (Map.Entry<Integer, ProtoField> f : productFields.entrySet()) {
                if (!msg.getValue().containsKey(f.getKey())) {
                    throw new IllegalStateException(fileName + ": 导表产物 " + msg.getKey() + " 有 schema 没有的字段 "
                            + f.getKey() + " " + f.getValue());
                }
            }
        }
        if (toReserve.isEmpty()) {
            return schemaText;
        }
        // 第二遍：按消息路径与字段号把对应声明行改写成 reserved。
        StringBuilder out = new StringBuilder();
        BlockStack blocks = new BlockStack();
        for (String line : schemaText.split("\n", -1)) {
            String code = stripComment(line);
            String path = blocks.messagePath();
            Matcher f = FIELD_DECL.matcher(code);
            if (path != null && f.matches() && toReserve.remove(path + "#" + f.group(5))) {
                line = f.group(1) + "reserved " + f.group(5) + "; // " + f.group(4)
                        + "：schema 已声明、本批导表产物里还没有（ContractSync 改写）";
            }
            out.append(line).append('\n');
            blocks.advance(code);
        }
        if (!toReserve.isEmpty()) {
            throw new IllegalStateException(fileName + ": 找不到要改写的字段声明（须单行）: " + toReserve);
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }

    /** manifest 登记的每张表都必须有同步过来的 schema（否则 Java 加载时会以「数据比 schema 新」拒绝）。 */
    private static void requireManifestTablesHaveSchema(String manifest, Set<String> sheets) {
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(manifest);
        Set<String> missing = new TreeSet<>();
        while (m.find()) {
            if (!sheets.contains(m.group(1))) {
                missing.add(m.group(1));
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("manifest.json 里有没有 schema 的表: " + missing);
        }
    }

    /** 从 manifest.json 取 source_rev 里的一个标量值（commit / data_dirty）；没有时记 unknown。 */
    private static String manifestValue(String manifest, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^\",}\\s]+)\"?").matcher(manifest);
        return m.find() ? m.group(1) : "unknown";
    }

    private static final Pattern FIELD_LINE = Pattern.compile(
            "^(\\s*)(?:repeated\\s+|optional\\s+)?(?:map\\s*<[^>]+>|[\\w.]+)\\s+(\\w+)\\s*=\\s*(\\d+)\\s*\\[(.*)]\\s*;.*$");
    private static final Pattern OWNER_OPTION = Pattern.compile("\\(cfg_owner\\)\\s*=\\s*\"([^\"]*)\"");

    /**
     * 把 {@code cfg_owner} 不是 server / common 的字段改写成 {@code reserved}（导表器不把这些列写进 .pb；
     * 留着它们会让服务端代码读到恒为默认值的「字段」）。字段号保留占位，不会被误复用。
     */
    static String stripNonServerFields(String fileName, String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!line.contains("(cfg_owner)")) {
                out.append(line).append('\n');
                continue;
            }
            Matcher field = FIELD_LINE.matcher(line);
            Matcher owner = OWNER_OPTION.matcher(line);
            if (!field.matches() || !owner.find()) {
                throw new IllegalStateException(fileName + ": 无法识别的 cfg_owner 字段声明（须单行）: " + line.strip());
            }
            if (SERVER_OWNERS.contains(owner.group(1).toLowerCase(Locale.ROOT))) {
                out.append(line).append('\n');
            } else {
                out.append(field.group(1)).append("reserved ").append(field.group(3)).append("; // ")
                        .append(field.group(2)).append("：cfg_owner = \"").append(owner.group(1))
                        .append("\"，不进服务端产物（ContractSync 改写）\n");
            }
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }

    /** tip / operator 枚举 proto：导表器产物，已自带 {@code java_package = "com.game.table"}，原样复制。 */
    private static int syncTableEnums(Path generatedProtoRoot, Path outRoot) throws IOException {
        int count = 0;
        for (String sub : List.of("tip", "operator")) {
            for (Path file : listFiles(generatedProtoRoot.resolve(sub), ".proto")) {
                copyText(file, outRoot.resolve(sub).resolve(file.getFileName().toString()));
                count++;
            }
        }
        return count;
    }

    private static final Pattern CPP_CONSTANT = Pattern.compile(
            "^constexpr\\s+uint32_t\\s+k([A-Za-z0-9]+)_(\\w+)\\s*=\\s*(\\d+)\\s*;\\s*$");

    /**
     * 具名行 id：{@code constexpr uint32_t kActorActionState_kActorActionUseSkill = 0;}
     * → {@code TableConstants.ActorActionState.ACTOR_ACTION_USE_SKILL = 0}。
     */
    private static int syncTableConstants(Path headerDir, Path outFile) throws IOException {
        Map<String, Map<String, Long>> byTable = new TreeMap<>();
        // 按文件名字符串排序（Path 的排序随平台而变：Windows 不区分大小写且 '_' 排在字母后），保证各平台产物一致。
        List<Path> headers = listFiles(headerDir, ".h").stream()
                .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString())).toList();
        for (Path header : headers) {
            for (String raw : readText(header).split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#pragma") || line.startsWith("//")) {
                    continue;
                }
                Matcher m = CPP_CONSTANT.matcher(line);
                if (!m.matches()) {
                    throw new IllegalStateException(header.getFileName() + ": 无法识别的常量行: " + line);
                }
                String name = screamingSnake(m.group(2));
                Long previous = byTable.computeIfAbsent(m.group(1), k -> new LinkedHashMap<>()).put(name, Long.parseLong(m.group(3)));
                if (previous != null) {
                    throw new IllegalStateException("常量重名: " + m.group(1) + "." + name);
                }
            }
        }
        StringBuilder src = new StringBuilder();
        src.append("package ").append(TABLE_JAVA_PACKAGE).append(";\n\n");
        src.append("/**\n * 配置表的具名行 id（Excel constants_name 列）。由 tools/ContractSync.java 从 mmorpg 导表器产物生成，不要手改。\n */\n");
        src.append("public final class TableConstants {\n\n    private TableConstants() {\n    }\n");
        int count = 0;
        for (Map.Entry<String, Map<String, Long>> table : byTable.entrySet()) {
            src.append("\n    /** 表 {@code ").append(table.getKey()).append("}。 */\n");
            src.append("    public static final class ").append(table.getKey()).append(" {\n\n");
            src.append("        private ").append(table.getKey()).append("() {\n        }\n\n");
            for (Map.Entry<String, Long> c : table.getValue().entrySet()) {
                long v = c.getValue();
                src.append("        public static final ").append(v > Integer.MAX_VALUE ? "long" : "int").append(' ')
                        .append(c.getKey()).append(" = ").append(v).append(v > Integer.MAX_VALUE ? "L" : "").append(";\n");
                count++;
            }
            src.append("    }\n");
        }
        src.append("}\n");
        writeText(outFile, src.toString());
        return count;
    }

    /** {@code kActorActionUseSkill} → {@code ACTOR_ACTION_USE_SKILL}；{@code Abnormal_logout} → {@code ABNORMAL_LOGOUT}。 */
    static String screamingSnake(String cppName) {
        String name = cppName.length() > 1 && cppName.charAt(0) == 'k' && Character.isUpperCase(cppName.charAt(1))
                ? cppName.substring(1) : cppName;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != '_') {
                    out.append('_');
                }
                continue;
            }
            if (Character.isUpperCase(c) && i > 0 && (Character.isLowerCase(name.charAt(i - 1)) || Character.isDigit(name.charAt(i - 1)))
                    && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
            out.append(Character.toUpperCase(c));
        }
        if (out.isEmpty() || !Character.isJavaIdentifierStart(out.charAt(0))) {
            throw new IllegalStateException("常量名转换失败: " + cppName);
        }
        return out.toString();
    }

    /** 表数据只要 .pb（与 C++ 一致）、manifest.json（运行时校验 sha256 / 行数）与 tip_text.json。 */
    private static int syncTableData(Path tablesRoot, Path outRoot) throws IOException {
        cleanDir(outRoot);
        int count = 0;
        try (Stream<Path> files = Files.list(tablesRoot)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".pb")) {
                    Files.copy(file, outRoot.resolve(name));
                    count++;
                } else if (name.equals("manifest.json") || name.equals("tip_text.json")) {
                    copyText(file, outRoot.resolve(name));
                }
            }
        }
        if (!Files.isRegularFile(outRoot.resolve("manifest.json"))) {
            throw new IllegalStateException("表数据目录缺 manifest.json: " + tablesRoot);
        }
        return count;
    }

    // ------------------------------------------------------------------ proto 改写规则

    private static final Pattern PROTO_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /**
     * Java 包名只由 proto 的 {@code package} 声明决定，<b>与 mmorpg 的目录结构无关</b>：
     * <ul>
     *   <li>无 package（绝大多数客户端消息）→ {@code com.game.proto}：proto 全名在同一次编译里全局唯一，平铺不会撞名；</li>
     *   <li>有 package → {@code com.game.proto.<package>}，末段的 Go 风格后缀 {@code pb} 去掉（{@code loginpb} → {@code login}）。</li>
     * </ul>
     */
    static String javaPackageFor(String protoText) {
        Matcher m = PROTO_PACKAGE.matcher(protoText);
        if (!m.find()) {
            return PROTO_JAVA_PACKAGE_ROOT;
        }
        String pkg = m.group(1).toLowerCase(Locale.ROOT);
        if (pkg.endsWith("pb") && pkg.length() > 2 && pkg.lastIndexOf('.') < pkg.length() - 3) {
            pkg = pkg.substring(0, pkg.length() - 2);
        }
        return PROTO_JAVA_PACKAGE_ROOT + "." + pkg;
    }

    /**
     * 外部类名 = 相对路径驼峰 + OuterClass（{@code proto/scene/scene.proto} → {@code SceneSceneOuterClass}），按构造唯一。
     * 不显式指定时 protoc 只检查同一文件内的重名，同 Java 包里另一个文件的同名类型会撞出
     * "Tried to write the same file twice"。外部类只被反射使用，手写代码不引用，所以名字带路径无妨。
     */
    static String outerClassNameFor(String relPath) {
        String path = relPath.startsWith("proto/") ? relPath.substring("proto/".length()) : relPath;
        String base = path.endsWith(".proto") ? path.substring(0, path.length() - ".proto".length()) : path;
        StringBuilder camel = new StringBuilder();
        boolean capNext = true;
        for (char c : base.toCharArray()) {
            if (Character.isLetter(c)) {
                camel.append(capNext ? Character.toUpperCase(c) : c);
                capNext = false;
            } else if (Character.isDigit(c)) {
                camel.append(c);
                capNext = true;
            } else {
                capNext = true;
            }
        }
        return camel + "OuterClass";
    }

    static String injectJavaOptions(String text, String javaPackage, String outerClass) {
        if (text.contains("option java_package") || text.contains("option java_outer_classname")) {
            throw new IllegalStateException("源 proto 已自带 java_package / java_outer_classname，需确认后再调整同步规则");
        }
        StringBuilder injected = new StringBuilder();
        injected.append("option java_package = \"").append(javaPackage).append("\";\n");
        injected.append("option java_outer_classname = \"").append(outerClass).append("\";\n");
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

    // ------------------------------------------------------------------ --check

    /** 逐个比对受管路径：文件集合与内容（文本已统一 LF，按字节比）。 */
    private static List<String> compare(Path expectedRoot, Path repo) throws IOException {
        List<String> diffs = new ArrayList<>();
        for (String managed : MANAGED) {
            Map<String, Path> expected = snapshot(expectedRoot.resolve(managed));
            Map<String, Path> actual = snapshot(repo.resolve(managed));
            Set<String> all = new TreeSet<>(expected.keySet());
            all.addAll(actual.keySet());
            for (String rel : all) {
                String shown = rel.isEmpty() ? managed : managed + "/" + rel;
                Path e = expected.get(rel);
                Path a = actual.get(rel);
                if (a == null) {
                    diffs.add("缺少  " + shown);
                } else if (e == null) {
                    diffs.add("多余  " + shown);
                } else if (!Arrays.equals(normalized(e), normalized(a))) {
                    diffs.add("不同  " + shown);
                }
            }
        }
        return diffs;
    }

    private static Map<String, Path> snapshot(Path root) throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        if (Files.isRegularFile(root)) {
            files.put("", root);
        } else if (Files.isDirectory(root)) {
            for (Path p : listFiles(root, "")) {
                files.put(slash(root.relativize(p)), p);
            }
        }
        return files;
    }

    /** 比对时容忍工作树的 CRLF（git autocrlf 检出）；.pb 按原字节。 */
    private static byte[] normalized(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (file.toString().endsWith(".pb")) {
            return bytes;
        }
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 文件工具

    private static String readText(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static void writeText(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, text.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    }

    private static void copyText(Path from, Path to) throws IOException {
        writeText(to, readText(from));
    }

    private static List<Path> listFiles(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("目录不存在: " + root);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    // 按相对路径字符串排序：Path 自身的排序随平台而变，产物顺序要与平台无关。
                    .sorted(java.util.Comparator.comparing(p -> slash(root.relativize(p))))
                    .toList();
        }
    }

    private static void cleanDir(Path dir) throws IOException {
        deleteTree(dir);
        Files.createDirectories(dir);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
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

    private static void writeSourceInfo(Path file, Map<String, String> info) throws IOException {
        StringBuilder text = new StringBuilder("# 由 tools/ContractSync.java 生成，不要手改。记录本仓库契约派生物来自 mmorpg 的哪个 commit。\n");
        info.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
        writeText(file, text.toString());
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

    /** {@code --key value} 与无值开关 {@code --check}。 */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new TreeMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("参数格式: --key value 或 --check，收到 " + args[i]);
            }
            String key = args[i].substring(2);
            if (key.equals("check")) {
                opts.put(key, "true");
            } else if (i + 1 < args.length) {
                opts.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("参数 --" + key + " 缺值");
            }
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
