package com.game.contract.check;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.game.contract.check.ContractCompatibility.*;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Java 21 命令行入口；退出 0=无未接受 BREAK，1=兼容性拒绝，2=输入/放行清单/输出非法。 */
public final class ContractCheck {
    private ContractCheck() { }
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Set<String> OPTIONS = Set.of("old-desc", "new-desc", "old-ids", "new-ids", "old-source", "new-source",
            "old-external", "new-external", "accepted", "json", "text", "old-surface", "new-surface");

    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    public static int run(String[] args, PrintStream stdout, PrintStream stderr) {
        Map<String, String> opts = Map.of();
        try {
            opts = arguments(args);
            // 在所有读写前阻止输出覆盖任何输入、重复输出路径。
            validateOutputs(opts);
            Surface old = read(opts, "old");
            Surface next = read(opts, "new");
            List<Finding> findings = ContractCompatibility.compare(old, next);
            Map<String, String> accepted = accepted(optionalText(opts, "accepted"), old, next, findings);
            int exit = findings.stream().anyMatch(f -> f.severity() == Severity.ERROR
                    || f.severity() == Severity.BREAK && !accepted.containsKey(f.id())) ? 1 : 0;
            String text = human(old, next, findings, accepted, exit);
            writeOptional(opts, "json", json(old.fingerprint(), next.fingerprint(), findings, accepted, exit, old, next));
            writeOptional(opts, "text", text);
            writeOptional(opts, "old-surface", old.canonical());
            writeOptional(opts, "new-surface", next.canonical());
            stdout.print(text);
            return exit;
        } catch (IOException | IllegalArgumentException e) {
            Finding invalid = new Finding(Severity.ERROR, "INVALID_INPUT", "input", "", e.getMessage());
            stderr.println("ERROR INVALID_INPUT " + e.getMessage());
            // 参数合法且报告位置不冲突时，坏输入同样产出机器可读失败报告；绝不返回假绿。
            try {
                validateOutputs(opts);
                writeOptional(opts, "json", json("", "", List.of(invalid), Map.of(), 2));
            } catch (IOException | IllegalArgumentException reportError) {
                stderr.println("ERROR REPORT_WRITE_FAILED " + reportError.getMessage());
            }
            return 2;
        }
    }

    private static Surface read(Map<String, String> opts, String prefix) throws IOException {
        byte[] bytes = bytes(Path.of(required(opts, prefix + "-desc")));
        FileDescriptorSet descriptors = FileDescriptorSet.parseFrom(bytes);
        String ids = text(Path.of(required(opts, prefix + "-ids")));
        Map<String, String> source = source(text(Path.of(required(opts, prefix + "-source"))));
        String digest = source.get("message_id.sha256");
        ContractCompatibility.require(digest != null && digest.matches("[a-f0-9]{64}")
                && digest.equals(ContractCompatibility.sha256(ids.replace("\r\n", "\n"))), prefix + " 消息号 SHA 与 SOURCE.properties 不符");
        return ContractCompatibility.read(descriptors, ids, optionalText(opts, prefix + "-external"),
                required(source, "mmorpg.commit"), required(source, "maps.source.commit"));
    }

    /** SOURCE 是同步器生成的简单 key=value，不容忍重复键、续行或隐式覆盖。 */
    private static Map<String, String> source(String input) {
        Map<String, String> out = new TreeMap<>();
        for (String raw : input.split("\\R", -1)) {
            String s = raw.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            ContractCompatibility.require(eq > 0 && !s.endsWith("\\"), "来源文件行格式非法");
            String key = s.substring(0, eq), value = s.substring(eq + 1);
            ContractCompatibility.require(out.putIfAbsent(key, value) == null, "来源文件重复键 " + key);
        }
        return out;
    }

    /** 每条放行必须绑定两个完整 surface 摘要、精确 finding ID 和非空理由；不支持全局 explicit-break。 */
    static Map<String, String> accepted(String input, Surface old, Surface next, List<Finding> findings) {
        Map<String, Finding> byId = new HashMap<>();
        findings.forEach(f -> byId.put(f.id(), f));
        Map<String, String> out = new TreeMap<>();
        for (String raw : input.split("\\R", -1)) {
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;
            String[] p = raw.split("\t", -1);
            ContractCompatibility.require(p.length == 4 && !p[3].isBlank(), "放行清单必须为 oldSHA TAB newSHA TAB findingID TAB 理由");
            ContractCompatibility.require(p[0].equals(old.fingerprint()) && p[1].equals(next.fingerprint()), "放行清单未绑定当前两个契约面");
            Finding finding = byId.get(p[2]);
            ContractCompatibility.require(finding != null && finding.severity() == Severity.BREAK, "放行项不存在、已过期或不可豁免 " + p[2]);
            ContractCompatibility.require(out.putIfAbsent(p[2], p[3]) == null, "重复放行项 " + p[2]);
        }
        return Collections.unmodifiableMap(out);
    }

    private static String human(Surface old, Surface next, List<Finding> findings, Map<String, String> accepted, int exit) {
        StringBuilder out = new StringBuilder("contract-check-v1 exit=" + exit + "\nold=" + old.fingerprint() + "\nnew=" + next.fingerprint() + "\n");
        out.append("范围：客户端/玩家服务消息闭包；全消息号历史及已解析 RPC 绑定。外部方法没有消息语义保证。\n");
        out.append(ContractCompatibility.line("NOT_COVERED", String.join(",", ContractCompatibility.NOT_COVERED)));
        for (var e : Map.of("old", old, "new", next).entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            Coverage coverage = ContractCompatibility.coverage(e.getValue());
            out.append(ContractCompatibility.line("COVERAGE", e.getKey(), "roots=" + coverage.clientOrPlayerMethods(),
                    "bindings=" + coverage.resolvedBindings(), "messages=" + coverage.messageTypes(), "enums=" + coverage.enumTypes()));
            for (ExternalMethod m : coverage.uncheckedMethods())
                out.append(ContractCompatibility.line("UNCHECKED_EXTERNAL", e.getKey(), m.id(), m.key(), m.evidence()));
        }
        for (Finding f : findings) {
            out.append(ContractCompatibility.line(f.severity(), f.code(), f.path(), f.id(), f.before(), f.after(),
                    accepted.containsKey(f.id()) ? "ACCEPTED: " + accepted.get(f.id()) : ""));
        }
        out.append("findings=").append(findings.size()).append(" accepted=").append(accepted.size()).append('\n');
        return out.toString();
    }

    static String json(String old, String next, List<Finding> findings, Map<String, String> accepted, int exit) {
        return json(old, next, findings, accepted, exit, null, null);
    }

    private static String json(String old, String next, List<Finding> findings, Map<String, String> accepted, int exit,
                               Surface oldSurface, Surface newSurface) {
        StringBuilder out = new StringBuilder("{\"format\":\"contract-check-v1\",\"exitCode\":" + exit
                + ",\"oldSurface\":" + quote(old) + ",\"newSurface\":" + quote(next)
                + ",\"scope\":\"client-and-player-service-protobuf-subset\",\"notCovered\":["
                + String.join(",", ContractCompatibility.NOT_COVERED.stream().map(ContractCheck::quote).toList()) + "]"
                + ",\"coverage\":{\"old\":" + coverageJson(oldSurface) + ",\"new\":" + coverageJson(newSurface) + "},\"findings\":[");
        boolean first = true;
        for (Finding f : findings) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"id\":").append(quote(f.id())).append(",\"severity\":").append(quote(f.severity().name()))
                    .append(",\"code\":").append(quote(f.code())).append(",\"path\":").append(quote(f.path()))
                    .append(",\"before\":").append(quote(f.before())).append(",\"after\":").append(quote(f.after()))
                    .append(",\"accepted\":").append(accepted.containsKey(f.id()))
                    .append(",\"reason\":").append(quote(accepted.getOrDefault(f.id(), ""))).append('}');
        }
        return out.append("]}\n").toString();
    }

    private static String coverageJson(Surface surface) {
        if (surface == null) return "null";
        Coverage c = ContractCompatibility.coverage(surface);
        StringBuilder out = new StringBuilder("{\"rootMethods\":" + c.clientOrPlayerMethods() + ",\"resolvedBindings\":" + c.resolvedBindings()
                + ",\"messageTypes\":" + c.messageTypes() + ",\"enumTypes\":" + c.enumTypes() + ",\"uncheckedMethods\":[");
        boolean first = true;
        for (ExternalMethod m : c.uncheckedMethods()) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"id\":").append(m.id()).append(",\"key\":").append(quote(m.key()))
                    .append(",\"evidence\":").append(quote(m.evidence())).append('}');
        }
        return out.append("]}").toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    private static Map<String, String> arguments(String[] args) {
        ContractCompatibility.require(args.length > 0 && args.length % 2 == 0, "参数必须为 --key value；见客户端契约门禁文档");
        Map<String, String> out = new TreeMap<>();
        for (int i = 0; i < args.length; i += 2) {
            ContractCompatibility.require(args[i].startsWith("--"), "参数须以 -- 开头");
            String key = args[i].substring(2);
            ContractCompatibility.require(OPTIONS.contains(key), "未知参数 --" + key + "；不支持裸 explicit-break 放行");
            ContractCompatibility.require(!args[i + 1].isBlank() && out.putIfAbsent(key, args[i + 1]) == null, "参数重复或空值 --" + key);
        }
        return out;
    }
    private static String required(Map<String, String> map, String key) {
        String value = map.get(key);
        ContractCompatibility.require(value != null && !value.isBlank(), "缺少 " + key);
        return value;
    }
    private static byte[] bytes(Path path) throws IOException {
        ContractCompatibility.require(Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES, "缺输入文件或文件超过 64 MiB: " + path);
        return Files.readAllBytes(path);
    }
    private static String text(Path path) throws IOException {
        // Files.readString 会拒绝损坏 UTF-8，而不是替换为 U+FFFD 后继续比较。
        ContractCompatibility.require(Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES, "缺文本文件或文件超过 64 MiB: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
    private static String optionalText(Map<String, String> opts, String key) throws IOException { return opts.containsKey(key) ? text(Path.of(opts.get(key))) : ""; }
    private static void writeOptional(Map<String, String> opts, String key, String text) throws IOException {
        if (!opts.containsKey(key)) return;
        Path path = Path.of(opts.get(key));
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
    private static void validateOutputs(Map<String, String> opts) throws IOException {
        Set<String> outputs = Set.of("json", "text", "old-surface", "new-surface");
        List<Path> inputs = new ArrayList<>(), targets = new ArrayList<>();
        for (var e : opts.entrySet()) {
            Path p = Path.of(e.getValue()).toAbsolutePath().normalize();
            if (Files.exists(p)) p = p.toRealPath();
            else if (p.getParent() != null && Files.exists(p.getParent())) p = p.getParent().toRealPath().resolve(p.getFileName());
            if (outputs.contains(e.getKey())) targets.add(p); else inputs.add(p);
        }
        Set<Path> unique = new HashSet<>();
        for (Path target : targets) {
            ContractCompatibility.require(unique.add(target) && !inputs.contains(target), "输出路径重复或覆盖输入 " + target);
            for (Path input : inputs) if (Files.exists(target) && Files.exists(input))
                ContractCompatibility.require(!Files.isSameFile(target, input), "输出通过硬链接覆盖输入 " + target);
            for (Path other : unique) if (!other.equals(target) && Files.exists(target) && Files.exists(other))
                ContractCompatibility.require(!Files.isSameFile(target, other), "多个输出通过硬链接指向同一文件 " + target + " / " + other);
        }
    }
}
