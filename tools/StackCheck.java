import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 整栈 compose 配置与运行态守卫（零第三方依赖 JDK 21 单文件工具，替代原 deploy/ci/check-stack.py）。
 *
 * <p>用法：
 * <pre>
 *   docker compose --profile tools config --format json | java tools/StackCheck.java [--root .]
 *   java tools/StackCheck.java --status ci-logs/stack-status.jsonl [--expected 17] [--root .]
 * </pre>
 * 不打印任何环境变量或密钥值。
 */
public final class StackCheck {

    private static final String POM_NS = "http://maven.apache.org/POM/4.0.0";
    private static final Pattern HEX_40 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9]{0,27}");
    private static final Set<String> RESERVED_NS = Set.of("mysql", "sys", "public");

    public static void main(String[] args) {
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path statusFile = null;
        int expectedRunning = -1;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--root" -> root = Path.of(requireArg(args, ++i, "--root")).toAbsolutePath().normalize();
                    case "--status" -> statusFile = Path.of(requireArg(args, ++i, "--status"));
                    case "--expected" -> expectedRunning = Integer.parseInt(requireArg(args, ++i, "--expected"));
                    case "--help", "-h" -> {
                        System.out.println("用法: java tools/StackCheck.java [--root <dir>] | --status <jsonl> [--expected <n>]");
                        return;
                    }
                    default -> throw new IllegalArgumentException("未知参数: " + args[i]);
                }
            }
            if (statusFile != null) {
                int count = checkStatus(statusFile, expectedRunning);
                System.out.println("整栈运行态守卫通过: " + count + " 个健康服务");
            } else {
                String json = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                int count = checkComposeConfig(root, asObject(Json.parse(json), "root"));
                System.out.println("整栈配置守卫通过: " + count + " 个 Boot 模块，含 robot");
            }
        } catch (Exception e) {
            System.err.println("整栈配置守卫失败: " + e.getMessage());
            System.exit(1);
        }
    }

    static int checkComposeConfig(Path root, Map<String, Object> config) throws Exception {
        Map<String, Object> services = asObject(config.get("services"), "services");
        Set<String> boot = findBootModules(root);
        Set<String> apps = new TreeSet<>();
        for (String name : services.keySet()) {
            if (name.startsWith("game-")) {
                apps.add(name);
            }
        }
        if (!apps.equals(boot)) {
            Set<String> missing = new TreeSet<>(boot);
            missing.removeAll(apps);
            Set<String> extra = new TreeSet<>(apps);
            extra.removeAll(boot);
            throw new IllegalArgumentException("Boot 模块与编排不一致: missing=" + missing + " extra=" + extra);
        }
        for (String name : apps) {
            Map<String, Object> svc = asObject(services.get(name), name);
            Map<String, Object> build = asObjectOrEmpty(svc.get("build"));
            Map<String, Object> buildArgs = asObjectOrEmpty(build.get("args"));
            if (!name.equals(buildArgs.get("MODULE"))) {
                throw new IllegalArgumentException(name + " 构建 MODULE 不一致");
            }
            String commit = asStringOrEmpty(buildArgs.get("GAME_BUILD_COMMIT"));
            String contract = asStringOrEmpty(buildArgs.get("GAME_BUILD_CONTRACT"));
            if (!HEX_40.matcher(commit).matches() || !HEX_40.matcher(contract).matches()) {
                throw new IllegalArgumentException(name + " 缺精确构建/契约版本");
            }
            String image = asStringOrEmpty(svc.get("image"));
            if (!image.endsWith(":" + commit.substring(0, 12))) {
                throw new IllegalArgumentException(name + " 镜像标签必须对应构建提交");
            }
            if ("game-robot".equals(name)) {
                List<Object> profiles = asListOrEmpty(svc.get("profiles"));
                if (profiles.size() != 1 || !"tools".equals(profiles.getFirst())) {
                    throw new IllegalArgumentException("robot 必须为显式工具 profile");
                }
                continue;
            }
            Map<String, Object> env = asObject(svc.get("environment"), name + ".environment");
            if (!"dev".equals(env.get("GAME_RUN_MODE"))) {
                throw new IllegalArgumentException("单节点开发夹具不得冒充生产形态");
            }
            String namespace = asStringOrEmpty(env.get("GAME_NAMESPACE"));
            if (!NAMESPACE.matcher(namespace).matches() || RESERVED_NS.contains(namespace)) {
                throw new IllegalArgumentException(name + " 部署命名空间非法");
            }
            Map<String, Object> healthcheck = asObjectOrEmpty(svc.get("healthcheck"));
            Object testObj = healthcheck.get("test");
            boolean hasTest = (testObj instanceof List<?> l && !l.isEmpty())
                    || (testObj instanceof String s && !s.isEmpty());
            if (!hasTest) {
                throw new IllegalArgumentException(name + " 缺少探针");
            }
            if (env.containsKey("SPRING_DATASOURCE_URL")) {
                String url = asStringOrEmpty(env.get("SPRING_DATASOURCE_URL"));
                if (!url.startsWith("jdbc:mysql://tidb:4000/" + namespace + "?")) {
                    throw new IllegalArgumentException(name + " 未使用同一 TiDB 权威域");
                }
                if (!(namespace + "_app").equals(env.get("SPRING_DATASOURCE_USERNAME"))) {
                    throw new IllegalArgumentException(name + " 账号未限定部署命名空间");
                }
            }
            for (Object portObj : asListOrEmpty(svc.get("ports"))) {
                Map<String, Object> port = asObjectOrEmpty(portObj);
                if (!"127.0.0.1".equals(port.get("host_ip"))) {
                    throw new IllegalArgumentException(name + " 开发端口必须限于本机");
                }
            }
            for (Map.Entry<String, Object> entry : env.entrySet()) {
                String key = entry.getKey();
                if (key.contains("SECRET") || "GAME_ADMIN_TOKEN".equals(key) || "GAME_LOGIN_DEV_PASSWORD".equals(key)) {
                    Object val = entry.getValue();
                    if (!(val instanceof String s) || s.getBytes(StandardCharsets.UTF_8).length < 32) {
                        throw new IllegalArgumentException(name + " 缺少有效密钥: " + key);
                    }
                }
            }
        }
        return apps.size();
    }

    static int checkStatus(Path statusFile, int expectedRunning) throws IOException {
        String raw = Files.readString(statusFile, StandardCharsets.UTF_8).strip();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (raw.startsWith("[")) {
            for (Object item : asListOrEmpty(Json.parse(raw))) {
                rows.add(asObject(item, "row"));
            }
        } else {
            for (String line : raw.split("\\R")) {
                if (!line.isBlank()) {
                    rows.add(asObject(Json.parse(line), "row"));
                }
            }
        }
        List<Map<String, Object>> apps = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (asStringOrEmpty(row.get("Service")).startsWith("game-")) {
                apps.add(row);
            }
        }
        if (expectedRunning >= 0 && apps.size() != expectedRunning) {
            throw new IllegalArgumentException("robot 后应有 " + expectedRunning + " 个健康服务，实得 " + apps.size());
        }
        for (Map<String, Object> row : apps) {
            if (!"running".equals(row.get("State")) || !"healthy".equals(row.get("Health"))) {
                throw new IllegalArgumentException("服务不健康: " + row.get("Service")
                        + " state=" + row.get("State") + " health=" + row.get("Health"));
            }
        }
        return apps.size();
    }

    private static Set<String> findBootModules(Path root) throws Exception {
        Set<String> boot = new TreeSet<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, "game-*")) {
            for (Path dir : dirs) {
                Path pom = dir.resolve("pom.xml");
                if (!Files.isRegularFile(pom)) {
                    continue;
                }
                Document doc = factory.newDocumentBuilder().parse(pom.toFile());
                if (hasSpringBootPlugin(doc.getDocumentElement())) {
                    boot.add(dir.getFileName().toString());
                }
            }
        }
        return boot;
    }

    private static boolean hasSpringBootPlugin(Element project) {
        for (Element build : childElements(project, "build")) {
            for (Element plugins : childElements(build, "plugins")) {
                for (Element plugin : childElements(plugins, "plugin")) {
                    for (Element artifactId : childElements(plugin, "artifactId")) {
                        if ("spring-boot-maven-plugin".equals(artifactId.getTextContent().trim())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e && localName.equals(e.getLocalName())
                    && (e.getNamespaceURI() == null || POM_NS.equals(e.getNamespaceURI()))) {
                out.add(e);
            }
        }
        return out;
    }

    private static String requireArg(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少参数值");
        }
        return args[idx];
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object v, String name) {
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("字段缺失或不是对象: " + name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectOrEmpty(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asListOrEmpty(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String asStringOrEmpty(Object v) {
        return v instanceof String s ? s : "";
    }

    /** 最小 RFC 8259 JSON 递归下降解析器（零外部依赖）。 */
    private static final class Json {
        private final String s;
        private int pos;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String input) {
            String clean = input != null && !input.isEmpty() && input.charAt(0) == '\uFEFF' ? input.substring(1) : input;
            Json p = new Json(clean);
            Object v = p.value();
            p.ws();
            if (p.pos != p.s.length()) {
                throw new IllegalArgumentException("JSON 末尾有多余字符 offset=" + p.pos);
            }
            return v;
        }

        private Object value() {
            ws();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            return switch (s.charAt(pos)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            pos++;
            ws();
            Map<String, Object> out = new LinkedHashMap<>();
            if (peek('}')) {
                pos++;
                return out;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                out.put(key, value());
                ws();
                if (peek('}')) {
                    pos++;
                    return out;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            pos++;
            ws();
            List<Object> out = new ArrayList<>();
            if (peek(']')) {
                pos++;
                return out;
            }
            while (true) {
                out.add(value());
                ws();
                if (peek(']')) {
                    pos++;
                    return out;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("未闭合转义");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("未闭合字符串");
        }

        private Object literal(String expected, Object result) {
            if (!s.startsWith(expected, pos)) {
                throw new IllegalArgumentException("非法字面量 offset=" + pos);
            }
            pos += expected.length();
            return result;
        }

        private Number number() {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            boolean floating = false;
            if (peek('.')) {
                floating = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (peek('e') || peek('E')) {
                floating = true;
                pos++;
                if (peek('+') || peek('-')) pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("非法值 offset=" + pos);
            }
            String token = s.substring(start, pos);
            return floating ? Double.valueOf(token) : Long.valueOf(token);
        }

        private void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException("期望 '" + c + "' offset=" + pos);
            }
            pos++;
        }
    }
}
