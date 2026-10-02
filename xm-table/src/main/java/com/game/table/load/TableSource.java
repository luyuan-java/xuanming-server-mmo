package com.game.table.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 导表器产物目录（mmorpg {@code generated/tables/}，本仓库 {@code config-data/tables/}）的只读访问，带完整性校验。
 *
 * <p>{@code manifest.json} 是这批产物的清单：每张表的行数与每个产物文件的 sha256。读一张表时：
 * <ol>
 *   <li>manifest 没登记这张表（schema 里有、导表器还没产出，例如新加的表）：当空表，记一条 WARNING；</li>
 *   <li>登记了：数据文件必须存在、sha256 与行数都对得上，否则拒绝——文件损坏、被手改、和 manifest 不是同一批；</li>
 *   <li>行里（含子消息）不许有 schema 没声明的字段——数据比 schema 新，说明契约同步不完整。</li>
 * </ol>
 * 数据文件格式是导表器的 {@code <Sheet>TableData { repeated <Sheet>Table data = 1; }}，这里直接按字段 1 逐条解析，
 * 不需要那个外层消息。
 *
 * <p>非线程安全：只在一次 {@code ConfigTables.load} 里用。
 */
public final class TableSource {

    public static final String MANIFEST = "manifest.json";

    private static final System.Logger LOG = System.getLogger(TableSource.class.getName());
    /** 外层消息字段 1（{@code repeated <Sheet>Table data = 1}）的 tag：字段号 1、长度定界。 */
    private static final int DATA_FIELD_TAG = (1 << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED;

    private final Path dir;
    private final Map<String, Entry> entries;
    private final Set<String> missing = new TreeSet<>();

    private record Entry(String file, String sha256, int rows) {
    }

    private TableSource(Path dir, Map<String, Entry> entries) {
        this.dir = dir;
        this.entries = entries;
    }

    /** @throws TableLoadException 目录或 manifest 不存在 / 格式不对 */
    public static TableSource open(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            throw new TableLoadException("配置表目录不存在: " + abs + "（进程需从仓库根目录启动，或设置 xm.table-dir）");
        }
        Path manifest = abs.resolve(MANIFEST);
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(manifest.toFile());
        } catch (IOException e) {
            throw new TableLoadException("读不了配置表清单 " + manifest, e);
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (JsonNode table : root.path("tables")) {
            String name = table.path("name").asText("");
            if (name.isEmpty()) {
                throw new TableLoadException(manifest + ": 有一项缺表名");
            }
            Entry entry = null;
            for (JsonNode artifact : table.path("artifacts")) {
                if ("binary".equals(artifact.path("kind").asText())) {
                    entry = new Entry(artifact.path("file").asText(""), artifact.path("sha256").asText(""),
                            table.path("rows").asInt(-1));
                }
            }
            if (entry == null || entry.file().isEmpty() || entry.sha256().isEmpty() || entry.rows() < 0) {
                throw new TableLoadException(manifest + ": 表 " + name + " 缺二进制产物、sha256 或行数");
            }
            if (entries.put(name, entry) != null) {
                throw new TableLoadException(manifest + ": 表 " + name + " 登记了两次");
            }
        }
        if (entries.isEmpty()) {
            throw new TableLoadException(manifest + ": 没有登记任何表");
        }
        return new TableSource(abs, entries);
    }

    /**
     * 读一张表的全部行（按文件顺序）。
     *
     * @throws TableLoadException 数据文件缺失 / sha256 或行数对不上 / 解析失败 / 含 schema 未声明的字段
     */
    public <T extends Message> List<T> rows(String sheet, String dataFile, Parser<T> parser) {
        Entry entry = entries.get(sheet);
        if (entry == null) {
            missing.add(sheet);
            LOG.log(Level.WARNING, "配置表 {0} 不在 manifest 里（导表器尚未产出），按空表处理", sheet);
            return List.of();
        }
        if (!entry.file().equals(dataFile)) {
            throw new TableLoadException("配置表 " + sheet + " 的数据文件在 manifest 里是 " + entry.file() + "，schema 约定为 " + dataFile);
        }
        Path file = dir.resolve(dataFile);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new TableLoadException("配置表数据文件不存在: " + file);
        } catch (IOException e) {
            throw new TableLoadException("读不了配置表数据文件 " + file, e);
        }
        String actual = sha256(bytes);
        if (!actual.equalsIgnoreCase(entry.sha256())) {
            throw new TableLoadException("配置表数据文件与 manifest 不符（损坏或被改动）: " + file
                    + " sha256=" + actual + " manifest=" + entry.sha256());
        }
        List<T> rows = parse(sheet, bytes, parser);
        if (rows.size() != entry.rows()) {
            throw new TableLoadException("配置表 " + sheet + " 行数 " + rows.size() + " 与 manifest 登记的 " + entry.rows() + " 不符");
        }
        for (T row : rows) {
            String unknown = unknownFields(row, sheet);
            if (unknown != null) {
                throw new TableLoadException("配置表 " + sheet + " 的数据含 schema 未声明的字段 " + unknown
                        + "：schema 与数据不是同一次契约同步");
            }
        }
        return rows;
    }

    /** 递归找未知字段（含子消息 / 结构列）；没有返回 null，有则返回「路径[字段号]」。 */
    private static String unknownFields(Message message, String path) {
        if (!message.getUnknownFields().asMap().isEmpty()) {
            return path + message.getUnknownFields().asMap().keySet();
        }
        for (Map.Entry<FieldDescriptor, Object> e : message.getAllFields().entrySet()) {
            FieldDescriptor field = e.getKey();
            if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                continue;
            }
            List<?> values = field.isRepeated() ? (List<?>) e.getValue() : List.of(e.getValue());
            for (Object value : values) {
                String found = unknownFields((Message) value, path + "." + field.getName());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** manifest 里有、schema 里没有的表：数据比 schema 新，拒绝。 */
    public void requireNoUnknownSheets(Set<String> knownSheets) {
        Set<String> unknown = new TreeSet<>(entries.keySet());
        unknown.removeAll(knownSheets);
        if (!unknown.isEmpty()) {
            throw new TableLoadException("manifest 里有 schema 不认识的表 " + unknown + "：schema 与数据不是同一次契约同步");
        }
    }

    /** schema 里有、manifest 没登记（按空表处理）的表。 */
    public Set<String> missingSheets() {
        return Set.copyOf(missing);
    }

    private static <T extends Message> List<T> parse(String sheet, byte[] bytes, Parser<T> parser) {
        List<T> rows = new ArrayList<>();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        try {
            while (true) {
                int tag = in.readTag();
                if (tag == 0) {
                    break;
                }
                if (tag == DATA_FIELD_TAG) {
                    rows.add(in.readMessage(parser, ExtensionRegistryLite.getEmptyRegistry()));
                } else {
                    throw new TableLoadException("配置表 " + sheet + " 的数据文件含未知顶层字段 tag=" + tag);
                }
            }
        } catch (IOException e) {
            throw new TableLoadException("配置表 " + sheet + " 的数据文件解析失败", e);
        }
        return rows;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
