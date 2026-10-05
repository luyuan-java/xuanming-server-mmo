import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 汇总 surefire 测试报告（CI 用，本机也能跑；docs/porting/deploy-ci-spec.md §4.8）。
 *
 * <p>读 {@code <模块>/target/surefire-reports/TEST-*.xml}（JDK 自带的 XML 解析器，无三方依赖）：
 * <ul>
 *   <li>{@code --summary <文件>}：把每个模块的用例 / 失败 / 错误 / 跳过数、失败用例清单、集成测试实际执行情况，
 *       以 Markdown 追加写进该文件（CI 里传 {@code $GITHUB_STEP_SUMMARY}）。</li>
 *   <li>{@code --annotate}：每个失败 / 出错的用例输出一行 {@code ::error file=…,line=…,title=…::…}（行号取栈里本测试类的第一帧）。
 *       GitHub 每个 step 最多显示 10 条 error annotation，超出的合并成最后一条「另有 N 个……」。
 *       job 日志匿名读不到，annotation 是匿名读取失败原因的唯一途径。</li>
 *   <li>{@code --require-it-executed}：防「集成测试标志传错、全部静默跳过、结果却是绿的」。扫描
 *       {@code <模块>/src/test/java} 里带 {@code @EnabledIfSystemProperty(named = "xm.it.…")} 的类（类级注解）与方法（方法级注解），
 *       每个门控单元都必须至少有一个用例真的执行了（没有报告、或者全被跳过都算没执行）；另外，任何因 {@code xm.it.*}
 *       没给而被跳过的用例（{@code assumeTrue(… "需要 -Dxm.it.mysql")} 这类不用注解的门控）也算。命中即退出码 1。</li>
 * </ul>
 *
 * <p>一个报告都没有时（编译阶段就失败了）只在 Summary 里写「无测试报告」，退出码 0，失败原因交给 Maven 问题匹配器；
 * 但带 {@code --require-it-executed} 时没有报告本身就说明测试没跑，退出码 1。
 *
 * <p>用法（JDK 21 单文件运行，在仓库根目录）：
 * <pre>
 * java tools/TestReport.java                                   # 只在标准输出打印汇总
 * java tools/TestReport.java --summary "$GITHUB_STEP_SUMMARY" --annotate [--require-it-executed]
 * java tools/TestReport.java --root &lt;另一份仓库副本&gt; ...       # 对别处的报告汇总
 * </pre>
 * 退出码：0 通过；1 有失败 / 出错的用例，或（{@code --require-it-executed} 时）有集成测试没执行；2 用法错误。
 */
public final class TestReport {

    /** GitHub 每个 step 最多显示的 error annotation 条数，超出的被丢弃。 */
    private static final int MAX_ERROR_ANNOTATIONS = 10;
    /** Summary 里最多列出的失败用例数（Step Summary 每步上限 1 MiB）。 */
    private static final int MAX_SUMMARY_FAILURES = 50;
    private static final int MAX_MESSAGE_LINES = 3;
    private static final int MAX_MESSAGE_CHARS = 400;

    private static final String IT_PROPERTY_PREFIX = "xm.it.";
    private static final Pattern IT_ANNOTATION = Pattern.compile("@EnabledIfSystemProperty\\s*\\(");
    private static final Pattern IT_NAMED = Pattern.compile("named\\s*=\\s*\"(xm\\.it\\.[^\"]*)\"");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE_DECLARATION =
            Pattern.compile("\\b(?:class|record|interface|enum)\\s+([\\p{L}\\p{N}_$]+)");
    private static final Pattern NAME_BEFORE_PAREN = Pattern.compile("([\\p{L}\\p{N}_$]+)\\s*\\(");
    /** 栈帧：{@code at [加载器/模块@版本/]类.方法(文件:行)}；类名、方法名可以是中文。 */
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+(?:\\S*/)?([^\\s/(]+)\\(([^()]*)\\)");

    private TestReport() {
    }

    enum Outcome { PASSED, FAILED, ERROR, SKIPPED }

    record TestCase(String module, String className, String name, Outcome outcome, String message, String detail) {
    }

    record ModuleStats(String module, int tests, int failures, int errors, int skipped, double seconds) {
    }

    /** 一个集成测试门控单元：类级注解时 method 为 null（整类），方法级注解时是那个方法。 */
    record ItUnit(String module, String className, String method, Set<String> properties) {

        String label() {
            return (method == null ? className : className + "#" + method) + "（" + String.join("、", properties) + "）";
        }
    }

    record Reports(List<ModuleStats> modules, List<TestCase> cases, List<String> unreadable) {
    }

    record ItStatus(List<ItUnit> units, List<ItUnit> missing, List<TestCase> gateSkipped, int classes,
                    int executedClasses) {
    }

    public static void main(String[] args) throws Exception {
        if (System.console() == null) {
            // 输出被管道 / CI 接走时 JVM 可能用平台编码，中文会变问号；统一 UTF-8。
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        }
        Map<String, String> opts = parseArgs(args);
        if (opts == null) {
            usage();
            System.exit(2);
        }
        Path root = Path.of(opts.getOrDefault("root", ".")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            System.err.println("--root 不是仓库根目录（没有 pom.xml）: " + root);
            System.exit(2);
        }
        boolean requireIt = opts.containsKey("require-it-executed");

        Reports reports = readReports(root);
        ItStatus it = itStatus(root, reports.cases());
        List<TestCase> failed = reports.cases().stream()
                .filter(c -> c.outcome() == Outcome.FAILED || c.outcome() == Outcome.ERROR).toList();
        boolean noReports = reports.modules().isEmpty();
        boolean itProblem = requireIt && (noReports || !it.missing().isEmpty() || !it.gateSkipped().isEmpty());

        printConsole(reports, failed, it, requireIt);
        if (opts.containsKey("summary")) {
            writeSummary(Path.of(opts.get("summary")), reports, failed, it, requireIt);
        }
        if (opts.containsKey("annotate")) {
            annotate(root, reports, failed, it, itProblem, noReports);
        }
        boolean fail = !failed.isEmpty() || !reports.unreadable().isEmpty() || itProblem;
        System.exit(fail ? 1 : 0);
    }

    // ---------------------------------------------------------------- 读报告

    static Reports readReports(Path root) throws IOException {
        List<ModuleStats> modules = new ArrayList<>();
        List<TestCase> cases = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        DocumentBuilder builder = newDocumentBuilder();
        for (Path moduleDir : childDirs(root)) {
            Path reportDir = moduleDir.resolve("target/surefire-reports");
            if (!Files.isDirectory(reportDir)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> list = Files.list(reportDir)) {
                files = list.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("TEST-") && n.endsWith(".xml");
                }).sorted().toList();
            }
            if (files.isEmpty()) {
                continue;
            }
            String module = moduleDir.getFileName().toString();
            int tests = 0;
            int failures = 0;
            int errors = 0;
            int skipped = 0;
            double seconds = 0;
            for (Path file : files) {
                Document doc;
                try {
                    builder.reset();
                    doc = builder.parse(file.toFile());
                } catch (Exception e) {
                    unreadable.add(module + "/target/surefire-reports/" + file.getFileName() + "：" + e.getMessage());
                    continue;
                }
                Element suite = doc.getDocumentElement();
                tests += intAttr(suite, "tests");
                failures += intAttr(suite, "failures");
                errors += intAttr(suite, "errors");
                skipped += intAttr(suite, "skipped");
                seconds += doubleAttr(suite, "time");
                String suiteName = suite.getAttribute("name");
                NodeList testcases = suite.getElementsByTagName("testcase");
                for (int i = 0; i < testcases.getLength(); i++) {
                    cases.add(toCase(module, suiteName, (Element) testcases.item(i)));
                }
            }
            modules.add(new ModuleStats(module, tests, failures, errors, skipped, seconds));
        }
        return new Reports(modules, cases, unreadable);
    }

    private static TestCase toCase(String module, String suiteName, Element testcase) {
        String className = testcase.getAttribute("classname");
        if (className.isEmpty()) {
            className = suiteName;
        }
        String name = testcase.getAttribute("name");
        // 只看直接子元素：flakyFailure / rerunFailure 是重跑后通过的历史，不算失败
        for (Node n = testcase.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element child)) {
                continue;
            }
            Outcome outcome = switch (child.getTagName()) {
                case "failure" -> Outcome.FAILED;
                case "error" -> Outcome.ERROR;
                case "skipped" -> Outcome.SKIPPED;
                default -> null;
            };
            if (outcome != null) {
                String message = child.getAttribute("message");
                String detail = child.getTextContent();
                if (outcome == Outcome.SKIPPED && message.isBlank()) {
                    // assume 失败的跳过：surefire 不写 message 属性，原因在正文第一行（TestAbortedException: Assumption failed: …）
                    message = detail.strip().lines().findFirst().orElse("");
                }
                return new TestCase(module, className, name, outcome, message, detail);
            }
        }
        return new TestCase(module, className, name, Outcome.PASSED, "", "");
    }

    private static DocumentBuilder newDocumentBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("XML 解析器初始化失败", e);
        }
    }

    private static int intAttr(Element e, String name) {
        String v = e.getAttribute(name).trim();
        try {
            return v.isEmpty() ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static double doubleAttr(Element e, String name) {
        String v = e.getAttribute(name).trim().replace(",", "");
        try {
            return v.isEmpty() ? 0 : Double.parseDouble(v);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 集成测试门控

    static ItStatus itStatus(Path root, List<TestCase> cases) throws IOException {
        List<ItUnit> units = new ArrayList<>();
        for (Path moduleDir : childDirs(root)) {
            Path testRoot = moduleDir.resolve("src/test/java");
            if (!Files.isDirectory(testRoot)) {
                continue;
            }
            List<Path> sources;
            try (Stream<Path> walk = Files.walk(testRoot)) {
                sources = walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path source : sources) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                if (text.contains("@EnabledIfSystemProperty")) {
                    units.addAll(itUnits(moduleDir.getFileName().toString(), source, text));
                }
            }
        }

        Map<String, List<TestCase>> byClass = new LinkedHashMap<>();
        for (TestCase c : cases) {
            byClass.computeIfAbsent(c.className(), k -> new ArrayList<>()).add(c);
        }
        List<ItUnit> missing = new ArrayList<>();
        Set<String> classes = new LinkedHashSet<>();
        Set<String> missingClasses = new LinkedHashSet<>();
        for (ItUnit unit : units) {
            classes.add(unit.className());
            boolean executed = casesOf(unit, byClass).stream().anyMatch(c -> c.outcome() != Outcome.SKIPPED);
            if (!executed) {
                missing.add(unit);
                missingClasses.add(unit.className());
            }
        }
        // 不用注解的门控（assumeTrue(… "需要 -Dxm.it.mysql")）：跳过原因里带 xm.it. 的用例，去掉已经按单元算过的
        List<TestCase> gateSkipped = new ArrayList<>();
        for (TestCase c : cases) {
            if (c.outcome() == Outcome.SKIPPED && c.message().contains(IT_PROPERTY_PREFIX)
                    && units.stream().noneMatch(u -> covers(u, c))) {
                gateSkipped.add(c);
            }
        }
        return new ItStatus(units, missing, gateSkipped, classes.size(), classes.size() - missingClasses.size());
    }

    /** 从一个源文件里找出全部 {@code @EnabledIfSystemProperty(named = "xm.it.…")}，判定是类级还是方法级。 */
    static List<ItUnit> itUnits(String module, Path source, String original) {
        String text = blankComments(original);   // 注释里提到的注解不算
        String fileName = source.getFileName().toString();
        String primary = fileName.substring(0, fileName.length() - ".java".length());
        Matcher pkg = PACKAGE.matcher(text);
        String prefix = pkg.find() ? pkg.group(1) + "." : "";

        Map<String, ItUnit> units = new LinkedHashMap<>();
        Matcher m = IT_ANNOTATION.matcher(text);
        int from = 0;
        while (m.find(from)) {
            int close = closeParen(text, m.end() - 1);
            from = close + 1;
            Matcher named = IT_NAMED.matcher(text.substring(m.end(), close));
            if (!named.find()) {
                continue;
            }
            String header = declarationHeader(text, close + 1);
            Matcher type = TYPE_DECLARATION.matcher(header);
            String className = prefix + primary;
            String method = null;
            if (type.find()) {
                // 只处理一层嵌套（Outer$Inner），本仓库没有更深的门控嵌套类
                if (!type.group(1).equals(primary)) {
                    className = prefix + primary + "$" + type.group(1);
                }
            } else {
                Matcher name = NAME_BEFORE_PAREN.matcher(header);
                if (name.find()) {
                    method = name.group(1);
                } else {
                    System.err.println("警告：看不出 " + source + " 里的 @EnabledIfSystemProperty 修饰的是什么，按整类计");
                }
            }
            ItUnit unit = new ItUnit(module, className, method, new LinkedHashSet<>());
            units.computeIfAbsent(className + "#" + (method == null ? "" : method), k -> unit).properties()
                    .add(named.group(1));
        }
        return new ArrayList<>(units.values());
    }

    /** 把行注释与块注释换成等长空白（跳过字符串与字符字面量），下标不变。 */
    static String blankComments(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < text.length() && text.charAt(i) != c && text.charAt(i) != '\n'; i++) {
                    if (text.charAt(i) == '\\') {
                        i++;
                    }
                }
                i++;
            } else if (text.startsWith("//", i) || text.startsWith("/*", i)) {
                int end = text.startsWith("//", i) ? text.indexOf('\n', i) : text.indexOf("*/", i + 2);
                end = end < 0 ? text.length() : text.startsWith("/*", i) ? end + 2 : end;
                for (int k = i; k < end; k++) {
                    if (out.charAt(k) != '\n') {
                        out.setCharAt(k, ' ');
                    }
                }
                i = end;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /** 跳过注解后面紧跟的其它注解、空白与注释，取声明头（到 { 或 ; 为止）。 */
    private static String declarationHeader(String text, int i) {
        while (true) {
            i = skipTrivia(text, i);
            if (i < text.length() && text.charAt(i) == '@') {
                i++;
                while (i < text.length()
                        && (Character.isLetterOrDigit(text.charAt(i)) || "_$.".indexOf(text.charAt(i)) >= 0)) {
                    i++;
                }
                int j = skipTrivia(text, i);
                if (j < text.length() && text.charAt(j) == '(') {
                    i = closeParen(text, j) + 1;
                }
                continue;
            }
            break;
        }
        int end = i;
        while (end < text.length() && text.charAt(end) != '{' && text.charAt(end) != ';') {
            end++;
        }
        return text.substring(Math.min(i, end), end);
    }

    private static int skipTrivia(String text, int i) {
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("//", i)) {
                int nl = text.indexOf('\n', i);
                i = nl < 0 ? text.length() : nl + 1;
            } else if (text.startsWith("/*", i)) {
                int endComment = text.indexOf("*/", i + 2);
                i = endComment < 0 ? text.length() : endComment + 2;
            } else {
                break;
            }
        }
        return i;
    }

    /** {@code open} 处是 '('，返回与之配对的 ')' 的下标；跳过字符串与字符字面量里的括号。 */
    private static int closeParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < text.length() && text.charAt(i) != c; i++) {
                    if (text.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return text.length() - 1;
    }

    private static List<TestCase> casesOf(ItUnit unit, Map<String, List<TestCase>> byClass) {
        List<TestCase> out = new ArrayList<>();
        for (Map.Entry<String, List<TestCase>> e : byClass.entrySet()) {
            for (TestCase c : e.getValue()) {
                if (covers(unit, c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static boolean covers(ItUnit unit, TestCase c) {
        if (unit.method() == null) {
            return c.className().equals(unit.className()) || c.className().startsWith(unit.className() + "$");
        }
        return c.className().equals(unit.className()) && nameMatches(c.name(), unit.method());
    }

    /** surefire 的用例名是方法名；参数化用例形如 {@code 方法(参数类型)[序号]}。 */
    private static boolean nameMatches(String caseName, String method) {
        return caseName.equals(method) || caseName.startsWith(method + "(") || caseName.startsWith(method + "[");
    }

    // ---------------------------------------------------------------- 输出

    private static void printConsole(Reports reports, List<TestCase> failed, ItStatus it, boolean requireIt) {
        if (reports.modules().isEmpty()) {
            System.out.println("无测试报告（*/target/surefire-reports/TEST-*.xml 一个都没有）");
        } else {
            ModuleStats total = total(reports.modules());
            System.out.printf("测试报告：%d 个模块，用例 %d，失败 %d，错误 %d，跳过 %d%n", reports.modules().size(),
                    total.tests(), total.failures(), total.errors(), total.skipped());
            int width = reports.modules().stream().mapToInt(s -> s.module().length()).max().orElse(10);
            for (ModuleStats s : reports.modules()) {
                System.out.printf("  %-" + width + "s  用例 %4d  失败 %3d  错误 %3d  跳过 %3d  %8.1f s%n", s.module(),
                        s.tests(), s.failures(), s.errors(), s.skipped(), s.seconds());
            }
        }
        for (String u : reports.unreadable()) {
            System.out.println("无法解析的报告：" + u);
        }
        for (TestCase c : failed) {
            System.out.printf("%s：%s.%s%n    %s%n", c.outcome() == Outcome.ERROR ? "出错" : "失败", simpleName(c.className()),
                    c.name(), String.join("\n    ", messageLines(c)));
        }
        System.out.printf("集成测试（@EnabledIfSystemProperty(named = \"xm.it.*\")）：%d 个类中实际执行 %d 个（门控单元 %d 个）%n",
                it.classes(), it.executedClasses(), it.units().size());
        if (requireIt) {
            for (ItUnit u : it.missing()) {
                System.out.println("  未执行：" + u.label());
            }
            for (TestCase c : it.gateSkipped()) {
                System.out.println("  因缺 -Dxm.it.* 跳过：" + c.className() + "#" + c.name() + "（" + oneLine(c.message()) + "）");
            }
        }
    }

    private static void writeSummary(Path file, Reports reports, List<TestCase> failed, ItStatus it, boolean requireIt)
            throws IOException {
        StringBuilder md = new StringBuilder("### 测试报告\n\n");
        if (reports.modules().isEmpty()) {
            md.append("无测试报告（编译阶段就失败了，或者测试被跳过）。失败原因见 Maven 问题匹配器的 annotation。\n\n");
        } else {
            md.append("| 模块 | 用例 | 失败 | 错误 | 跳过 | 耗时 (s) |\n|---|---:|---:|---:|---:|---:|\n");
            for (ModuleStats s : reports.modules()) {
                md.append(row(s.module(), s));
            }
            md.append(row("**合计**", total(reports.modules()))).append('\n');
        }
        if (!reports.unreadable().isEmpty()) {
            md.append("#### 无法解析的报告\n\n");
            reports.unreadable().forEach(u -> md.append("- ").append(mdEscape(u)).append('\n'));
            md.append('\n');
        }
        if (!failed.isEmpty()) {
            md.append("#### 失败 / 出错的用例（").append(failed.size()).append("）\n\n");
            failed.stream().limit(MAX_SUMMARY_FAILURES).forEach(c -> md.append("- `").append(c.module()).append("` `")
                    .append(simpleName(c.className())).append('.').append(c.name()).append("`：")
                    .append(mdEscape(String.join(" / ", messageLines(c)))).append('\n'));
            if (failed.size() > MAX_SUMMARY_FAILURES) {
                md.append("- ……另有 ").append(failed.size() - MAX_SUMMARY_FAILURES).append(" 个，见制品里的 surefire 报告\n");
            }
            md.append('\n');
        }
        md.append("#### 集成测试\n\n带 `@EnabledIfSystemProperty(named = \"xm.it.*\")` 的类：")
                .append(it.classes()).append(" 个，实际执行 ").append(it.executedClasses()).append(" 个（门控单元 ")
                .append(it.units().size()).append(" 个）")
                .append(requireIt ? "；本次要求全部执行（`--require-it-executed`）" : "")
                .append("。\n\n");
        if (requireIt && reports.modules().isEmpty()) {
            md.append("- **没有测试报告**：要求集成测试执行，但一个用例都没跑\n");
        }
        if (requireIt) {
            it.missing().forEach(u -> md.append("- 未执行：`").append(u.label()).append("`\n"));
            it.gateSkipped().forEach(c -> md.append("- 因缺 `-Dxm.it.*` 跳过：`").append(c.className()).append('#')
                    .append(c.name()).append("`（").append(mdEscape(oneLine(c.message()))).append("）\n"));
        }
        Files.writeString(file, md.append('\n').toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String row(String name, ModuleStats s) {
        return String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %.1f |%n", name, s.tests(), s.failures(), s.errors(),
                s.skipped(), s.seconds());
    }

    /** 汇总成 error annotation，总数不超过 {@link #MAX_ERROR_ANNOTATIONS}（集成测试缺失占一条）。 */
    private static void annotate(Path root, Reports reports, List<TestCase> failed, ItStatus it, boolean itProblem,
                                 boolean noReports) {
        List<String> lines = new ArrayList<>();
        for (String u : reports.unreadable()) {
            lines.add(command("error", null, 0, "测试报告无法解析", u));
        }
        for (TestCase c : failed) {
            String file = sourceFile(root, c);
            int line = file == null ? 0 : frameLine(c);
            lines.add(command("error", file, line, simpleName(c.className()) + "." + c.name(),
                    String.join("\n", messageLines(c))));
        }
        int budget = MAX_ERROR_ANNOTATIONS - (itProblem ? 1 : 0);
        if (lines.size() > budget) {
            int shown = budget - 1;
            List<String> head = new ArrayList<>(lines.subList(0, shown));
            head.add(command("error", null, 0, "更多失败用例",
                    "另有 " + (lines.size() - shown) + " 个失败用例，见 Summary / 制品（surefire-reports）"));
            lines = head;
        }
        if (itProblem) {
            StringBuilder msg = new StringBuilder();
            if (noReports) {
                msg.append("没有任何测试报告：要求集成测试执行（--require-it-executed），但一个用例都没跑");
            } else {
                msg.append("以下集成测试没有执行（-Dxm.it.* 没传到、或依赖没起来被跳过）：");
                it.missing().forEach(u -> msg.append('\n').append(u.label()));
                it.gateSkipped().forEach(c -> msg.append('\n').append(c.className()).append('#').append(c.name())
                        .append("（").append(oneLine(c.message())).append("）"));
            }
            lines.add(command("error", null, 0, "集成测试被静默跳过", msg.toString()));
        }
        lines.forEach(System.out::println);
    }

    /** 测试类源文件（仓库相对路径）；嵌套类取外层类的文件，找不到时返回 null（annotation 不带文件）。 */
    private static String sourceFile(Path root, TestCase c) {
        String outer = c.className().contains("$") ? c.className().substring(0, c.className().indexOf('$'))
                : c.className();
        String rel = c.module() + "/src/test/java/" + outer.replace('.', '/') + ".java";
        return Files.isRegularFile(root.resolve(rel)) ? rel : null;
    }

    /** 栈里本测试类（含其内部类 / lambda）的第一帧的行号；没有时返回 0。 */
    static int frameLine(TestCase c) {
        String outer = c.className().contains("$") ? c.className().substring(0, c.className().indexOf('$'))
                : c.className();
        for (String line : c.detail().split("\\R")) {
            Matcher m = FRAME.matcher(line);
            if (!m.find()) {
                continue;
            }
            String qualified = m.group(1);
            int dot = qualified.lastIndexOf('.');
            if (dot < 0) {
                continue;
            }
            String frameClass = qualified.substring(0, dot);
            if (frameClass.equals(outer) || frameClass.startsWith(outer + "$")) {
                String location = m.group(2);
                int colon = location.lastIndexOf(':');
                if (colon > 0) {
                    try {
                        return Integer.parseInt(location.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }

    /** 失败消息的前几行非空行（AssertJ 的消息常以换行开头、分多行）；没有 message 属性时取栈的第一行。 */
    private static List<String> messageLines(TestCase c) {
        String source = c.message() == null || c.message().isBlank() ? c.detail() : c.message();
        List<String> out = new ArrayList<>();
        int chars = 0;
        for (String line : source.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (chars + t.length() > MAX_MESSAGE_CHARS) {
                out.add(t.substring(0, Math.max(0, Math.min(t.length(), MAX_MESSAGE_CHARS - chars))) + "…");
                break;
            }
            out.add(t);
            chars += t.length();
            if (out.size() == MAX_MESSAGE_LINES) {
                break;
            }
        }
        if (out.isEmpty()) {
            out.add(c.outcome() == Outcome.ERROR ? "（出错，无消息）" : "（失败，无消息）");
        }
        return out;
    }

    /** GitHub workflow 命令：{@code ::error file=…,line=…,title=…::消息}，按官方规则转义。 */
    static String command(String level, String file, int line, String title, String message) {
        List<String> props = new ArrayList<>();
        if (file != null) {
            props.add("file=" + escapeProperty(file));
            if (line > 0) {
                props.add("line=" + line);
            }
        }
        if (title != null) {
            props.add("title=" + escapeProperty(title));
        }
        return "::" + level + (props.isEmpty() ? "" : " " + String.join(",", props)) + "::" + escapeData(message);
    }

    static String escapeData(String s) {
        return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    static String escapeProperty(String s) {
        return escapeData(s).replace(":", "%3A").replace(",", "%2C");
    }

    private static String oneLine(String s) {
        String t = s.strip().replaceAll("\\s*\\R\\s*", " / ");
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private static String mdEscape(String s) {
        return s.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static ModuleStats total(List<ModuleStats> modules) {
        return new ModuleStats("合计", modules.stream().mapToInt(ModuleStats::tests).sum(),
                modules.stream().mapToInt(ModuleStats::failures).sum(), modules.stream().mapToInt(ModuleStats::errors).sum(),
                modules.stream().mapToInt(ModuleStats::skipped).sum(),
                modules.stream().mapToDouble(ModuleStats::seconds).sum());
    }

    private static List<Path> childDirs(Path root) throws IOException {
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    // ---------------------------------------------------------------- 参数

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new TreeMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--annotate", "--require-it-executed" -> opts.put(args[i].substring(2), "");
                case "--summary", "--root" -> {
                    if (i + 1 >= args.length || args[i + 1].isBlank()) {
                        return null;
                    }
                    opts.put(args[i].substring(2), args[++i]);
                }
                default -> {
                    return null;
                }
            }
        }
        return opts;
    }

    private static void usage() {
        System.err.println("""
                用法：java tools/TestReport.java [--root <仓库根>] [--summary <文件>] [--annotate] [--require-it-executed]
                  --summary <文件>         把汇总以 Markdown 追加进该文件（CI：$GITHUB_STEP_SUMMARY）
                  --annotate               为失败用例输出 ::error annotation（每步最多 10 条，超出合并）
                  --require-it-executed    带 @EnabledIfSystemProperty(named = "xm.it.*") 的测试必须真的执行，否则退出码 1
                退出码：0 通过；1 有失败用例或集成测试没执行；2 用法错误""");
    }
}
