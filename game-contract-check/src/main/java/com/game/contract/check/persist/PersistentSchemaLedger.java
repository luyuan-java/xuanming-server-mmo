package com.game.contract.check.persist;

import static com.game.contract.check.persist.PersistentSchemaSnapshot.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 有界、只追加的离线字段台账；摘要防误删/误改，不替代版本库审查或运行时写入围栏。 */
public final class PersistentSchemaLedger {
    public static final int MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_GENERATIONS = 1024;
    private static final String ZERO = "0".repeat(64);
    private PersistentSchemaLedger() { }

    public record Entry(int generation, String sha256, String previousSha256, PersistentSchemaSnapshot snapshot) { }
    public record Violation(String code, String path) { }

    /** baseline 必须来自受信任的已审查 Git 基线；候选自选基线不能证明历史只追加。 */
    public static void requirePrefix(List<Entry> baseline, List<Entry> candidate) {
        require(!baseline.isEmpty() && candidate.size() >= baseline.size(), "LEDGER_HISTORY_REMOVED");
        for (int i = 0; i < baseline.size(); i++) require(baseline.get(i).equals(candidate.get(i)), "LEDGER_HISTORY_REWRITTEN");
    }

    /** 两个锚均由调用方源码固定；尾部删除不能回退到一个仍能自校验的旧前缀。 */
    public static List<Entry> read(Path directory, String genesisSha256, String headSha256) throws IOException {
        require(digest(genesisSha256) && digest(headSha256), "LEDGER_PIN_REQUIRED");
        noLinks(directory);
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "LEDGER_REQUIRED");
        List<Path> files = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path file : entries) {
                require(files.size() < MAX_GENERATIONS, "LEDGER_GENERATION_LIMIT");
                require(file.getFileName().toString().matches("[0-9]{4}\\.schema"), "LEDGER_UNEXPECTED_FILE");
                files.add(file);
            }
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        require(!files.isEmpty(), "LEDGER_EMPTY");
        var history = new ArrayList<Entry>();
        String previous = ZERO;
        String root = null;
        long total = 0;
        for (Path file : files) {
            int generation = history.size() + 1;
            require(file.getFileName().toString().equals(fileName(generation)), "LEDGER_GENERATION_GAP");
            byte[] bytes = readBounded(file, MAX_SNAPSHOT_BYTES + 256);
            total += bytes.length;
            require(total <= 32L * 1024 * 1024, "LEDGER_TOTAL_SIZE");
            int newline = -1;
            for (int i = 0; i < Math.min(bytes.length, 256); i++) if (bytes[i] == '\n') { newline = i; break; }
            require(newline > 0, "LEDGER_HEADER");
            String header = new String(bytes, 0, newline, StandardCharsets.UTF_8);
            require(header.equals("persistent-ledger-entry-v1\t" + generation + "\t" + previous), "LEDGER_CHAIN");
            PersistentSchemaSnapshot snapshot = PersistentSchemaSnapshot.parse(Arrays.copyOfRange(bytes, newline + 1, bytes.length));
            if (root == null) root = snapshot.root();
            require(root.equals(snapshot.root()), "LEDGER_ROOT_CHANGED");
            String hash = sha256(bytes);
            if (generation == 1) require(hash.equals(genesisSha256), "LEDGER_GENESIS_CHANGED");
            history.add(new Entry(generation, hash, previous, snapshot));
            previous = hash;
        }
        require(previous.equals(headSha256), "LEDGER_HEAD_CHANGED");
        return List.copyOf(history);
    }

    /** 当前类型须与链尾精确相同；合法新增也必须通过显式 append 登记新世代。 */
    public static int verify(Path directory, String genesisSha256, String headSha256,
                             PersistentSchemaSnapshot current, Set<String> allowAdditions) throws IOException {
        List<Entry> history = read(directory, genesisSha256, headSha256);
        validateHistory(history, allowAdditions);
        require(history.getFirst().snapshot().root().equals(current.root()), "ROOT_CHANGED");
        List<Violation> failures = compare(history.stream().map(Entry::snapshot).toList(), current, allowAdditions);
        require(failures.isEmpty(), "SCHEMA_INCOMPATIBLE " + failures);
        require(Arrays.equals(history.getLast().snapshot().bytes(), current.bytes()), "SCHEMA_NOT_REGISTERED");
        return history.getLast().generation();
    }

    /** 不初始化空目录，不覆盖旧世代；同一代并发追加仅一个 CREATE_NEW 成功。 */
    public static Entry append(Path directory, String genesisSha256, String expectedHeadSha256,
                               PersistentSchemaSnapshot current, Set<String> allowAdditions) throws IOException {
        List<Entry> history = read(directory, genesisSha256, expectedHeadSha256);
        validateHistory(history, allowAdditions);
        List<Violation> failures = compare(history.stream().map(Entry::snapshot).toList(), current, allowAdditions);
        require(failures.isEmpty(), "SCHEMA_INCOMPATIBLE " + failures);
        require(!Arrays.equals(history.getLast().snapshot().bytes(), current.bytes()), "SCHEMA_UNCHANGED");
        int generation = history.size() + 1;
        require(generation <= MAX_GENERATIONS, "LEDGER_GENERATION_LIMIT");
        byte[] bytes = entryBytes(generation, expectedHeadSha256, current);
        long total = bytes.length;
        for (var entry : history) total += entryBytes(entry.generation(), entry.previousSha256(), entry.snapshot()).length;
        require(total <= 32L * 1024 * 1024, "LEDGER_TOTAL_SIZE");
        Path file = directory.resolve(fileName(generation));
        writeNew(file, bytes);
        return new Entry(generation, sha256(bytes), expectedHeadSha256, current);
    }

    /** 供一次性的人工审查基线导出；正常 verify/append 永远不会调用此方法初始化台账。 */
    public static byte[] genesisBytes(PersistentSchemaSnapshot snapshot) { return entryBytes(1, ZERO, snapshot); }

    private static byte[] entryBytes(int generation, String previous, PersistentSchemaSnapshot snapshot) {
        byte[] header = ("persistent-ledger-entry-v1\t" + generation + "\t" + previous + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] shape = snapshot.bytes();
        require(shape.length <= MAX_SNAPSHOT_BYTES, "SNAPSHOT_SIZE");
        byte[] result = Arrays.copyOf(header, header.length + shape.length);
        System.arraycopy(shape, 0, result, header.length, shape.length);
        return result;
    }

    private static void validateHistory(List<Entry> history, Set<String> allowAdditions) {
        for (int i = 1; i < history.size(); i++) {
            List<Violation> failures = compare(history.subList(0, i).stream().map(Entry::snapshot).toList(),
                    history.get(i).snapshot(), allowAdditions);
            require(failures.isEmpty(), "LEDGER_INCOMPATIBLE_HISTORY " + failures);
        }
    }

    /** 与全部历史比较：不可达的旧类型不丢台账，将来重新可达仍受过去字段号与 reserved 约束。 */
    public static List<Violation> compare(List<PersistentSchemaSnapshot> history, PersistentSchemaSnapshot current,
                                          Set<String> allowAdditions) {
        require(!history.isEmpty(), "LEDGER_EMPTY");
        Set<Violation> errors = new HashSet<>();
        for (var previous : history) {
            if (!previous.root().equals(current.root())) { errors.add(new Violation("ROOT_CHANGED", current.root())); continue; }
            previous.messages().forEach((name, old) -> {
                var next = current.messages().get(name);
                if (current.enums().containsKey(name)) errors.add(new Violation("TYPE_KIND_CHANGED", name));
                if (next == null) return;
                if (!old.syntax().equals(next.syntax()) || old.proto().getOptions().getMapEntry() != next.proto().getOptions().getMapEntry())
                    errors.add(new Violation("MESSAGE_SEMANTICS_CHANGED", name));
                var before = fields(old);
                var after = fields(next);
                before.forEach((number, field) -> {
                    var replacement = after.get(number);
                    if (replacement != null && !fieldIdentity(old, field).equals(fieldIdentity(next, replacement)))
                        errors.add(new Violation("FIELD_CHANGED", name + "." + number));
                    if (replacement == null && (!messageReserved(next, number) || !next.proto().getReservedNameList().contains(field.getName())))
                        errors.add(new Violation("FIELD_REMOVED_WITHOUT_RESERVED", name + "." + number));
                });
                var historicalOneofs = new HashSet<String>();
                before.values().forEach(field -> { String group = realOneof(old, field); if (!group.isEmpty()) historicalOneofs.add(group); });
                after.forEach((number, field) -> {
                    if (messageReserved(old, number) || old.proto().getReservedNameList().contains(field.getName()))
                        errors.add(new Violation("FIELD_RESERVED_REUSED", name + "." + number));
                    if (!before.containsKey(number)) {
                        if (!allowAdditions.contains(name)) errors.add(new Violation("FROZEN_MESSAGE_ADDITION", name + "." + number));
                        if (field.getLabel() == com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label.LABEL_REQUIRED)
                            errors.add(new Violation("REQUIRED_FIELD_ADDITION", name + "." + number));
                        if (historicalOneofs.contains(realOneof(next, field))) errors.add(new Violation("EXISTING_ONEOF_ADDITION", name + "." + number));
                    }
                });
                if (!next.proto().getReservedNameList().containsAll(old.proto().getReservedNameList())
                        || old.proto().getReservedRangeList().stream().anyMatch(r -> !messageRangeCovered(next, r.getStart(), r.getEnd())))
                    errors.add(new Violation("MESSAGE_RESERVED_REMOVED", name));
            });
            previous.enums().forEach((name, old) -> {
                var next = current.enums().get(name);
                if (current.messages().containsKey(name)) errors.add(new Violation("TYPE_KIND_CHANGED", name));
                if (next == null) return;
                if (!old.syntax().equals(next.syntax()) || !old.firstName().equals(next.firstName())
                        || old.proto().getOptions().getAllowAlias() != next.proto().getOptions().getAllowAlias())
                    errors.add(new Violation("ENUM_SEMANTICS_CHANGED", name));
                var values = new java.util.HashMap<String, Integer>();
                next.proto().getValueList().forEach(value -> values.put(value.getName(), value.getNumber()));
                old.proto().getValueList().forEach(value -> {
                    Integer number = values.get(value.getName());
                    if (number != null && number != value.getNumber()) errors.add(new Violation("ENUM_VALUE_CHANGED", name + "." + value.getName()));
                    if (number == null && (!enumReserved(next, value.getNumber()) || !next.proto().getReservedNameList().contains(value.getName())))
                        errors.add(new Violation("ENUM_VALUE_REMOVED_WITHOUT_RESERVED", name + "." + value.getName()));
                });
                next.proto().getValueList().forEach(value -> {
                    if (!allowAdditions.contains(name) && old.proto().getValueList().stream().noneMatch(v -> v.getName().equals(value.getName())))
                        errors.add(new Violation("FROZEN_ENUM_ADDITION", name + "." + value.getName()));
                    if (enumReserved(old, value.getNumber()) || old.proto().getReservedNameList().contains(value.getName()))
                        errors.add(new Violation("ENUM_RESERVED_REUSED", name + "." + value.getName()));
                    if (old.proto().getValueList().stream().anyMatch(v -> v.getNumber() == value.getNumber() && !v.getName().equals(value.getName()))
                            && old.proto().getValueList().stream().noneMatch(v -> v.getName().equals(value.getName())))
                        errors.add(new Violation("ENUM_NUMBER_REUSED", name + "." + value.getName()));
                });
                if (!next.proto().getReservedNameList().containsAll(old.proto().getReservedNameList())
                        || old.proto().getReservedRangeList().stream().anyMatch(r -> !enumRangeCovered(next, r.getStart(), r.getEnd())))
                    errors.add(new Violation("ENUM_RESERVED_REMOVED", name));
            });
        }
        return errors.stream().sorted(Comparator.comparing(Violation::path).thenComparing(Violation::code)).toList();
    }

    private static boolean messageRangeCovered(MessageShape next, int start, int end) {
        long cursor = start;
        for (var range : next.proto().getReservedRangeList().stream().sorted(Comparator.comparingInt(r -> r.getStart())).toList()) {
            if (range.getStart() > cursor) break;
            cursor = Math.max(cursor, range.getEnd());
            if (cursor >= end) return true;
        }
        return false;
    }

    private static boolean enumRangeCovered(EnumShape next, int start, int end) {
        long cursor = start;
        for (var range : next.proto().getReservedRangeList().stream().sorted(Comparator.comparingInt(r -> r.getStart())).toList()) {
            if (range.getStart() > cursor) break;
            cursor = Math.max(cursor, (long) range.getEnd() + 1);
            if (cursor > end) return true;
        }
        return false;
    }

    static byte[] readBounded(Path path, int limit) throws IOException {
        noLinks(path);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= limit, "FILE_REQUIRED_OR_TOO_LARGE");
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(limit + 1);
            require(bytes.length <= limit, "FILE_TOO_LARGE");
            return bytes;
        }
    }

    static void writeNew(Path path, byte[] bytes) throws IOException {
        noLinks(path);
        try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
    }

    private static void noLinks(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize().getRoot();
        for (Path part : path.toAbsolutePath().normalize()) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(!attributes.isSymbolicLink() && !attributes.isOther(), "LINK_OR_SPECIAL_FILE");
            }
        }
    }

    private static String fileName(int generation) { return String.format(java.util.Locale.ROOT, "%04d.schema", generation); }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
