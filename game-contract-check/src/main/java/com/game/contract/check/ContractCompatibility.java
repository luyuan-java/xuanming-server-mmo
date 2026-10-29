package com.game.contract.check;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 离线协议比较器。描述符由 protobuf 自己解析并完成引用校验；不依赖生成消息类，不解析 proto 源码。
 * 客户端/玩家服务是闭包根；全消息号目录另外做历史完整性校验，不能把内部 RPC 误称客户端契约。
 */
public final class ContractCompatibility {
    private ContractCompatibility() { }

    /** 该候选只提供下列 protobuf 子面，不宣称完整 7.5 契约治理完成。 */
    public static final List<String> NOT_COVERED = List.of("transport-frame-and-handshake", "http-endpoints",
            "event-id-history", "tip-codes-and-text", "table-schema-options-and-data", "navigation-bindings-and-artifacts",
            "java-persistent-schema-ledger", "released-client-entry-adapters", "custom-field-business-options",
            "unreachable-retired-type-ledger");

    public enum Severity { ERROR, BREAK, WARN, INFO }

    public record Finding(Severity severity, String code, String path, String before, String after) {
        public String id() { return sha256(line(severity, code, path, before, after)); }
    }

    public record IdEntry(int id, String key, boolean retired) { }
    public record Range(int start, int end) { // 闭区间，统一消息/枚举原始表示。
        public boolean contains(int n) { return n >= start && n <= end; }
    }
    public record Field(String name, String type, String cardinality, boolean map, boolean presence,
                        String oneof, boolean packed, String defaultValue, String jsonName) { }
    public record MessageShape(SortedMap<Integer, Field> fields, List<Range> reserved,
                               SortedSet<String> reservedNames, boolean mapEntry) { }
    public record EnumShape(SortedMap<Integer, SortedSet<String>> values, List<Range> reserved,
                            SortedSet<String> reservedNames, String syntax, String firstValue) { }
    public record Rpc(String fullName, String input, String output, boolean clientStreaming,
                      boolean serverStreaming, boolean client, boolean player, String options) { }
    public record ExternalMethod(int id, String key, String evidence) { }
    public record Coverage(int clientOrPlayerMethods, int resolvedBindings, int messageTypes, int enumTypes,
                           List<ExternalMethod> uncheckedMethods) { }
    public record Surface(SortedMap<Integer, IdEntry> ids, SortedMap<String, Rpc> bindings,
                          SortedMap<String, Rpc> roots, SortedMap<String, MessageShape> messages,
                          SortedMap<String, EnumShape> enums, SortedMap<Integer, String> external,
                          String sourceCommit, String mapsCommit) {
        /** 排序与转义固定，不受 descriptor 文件、字段或集合声明顺序影响。 */
        public String canonical() {
            StringBuilder out = new StringBuilder("contract-surface-v1\n");
            out.append(line("source", sourceCommit, mapsCommit));
            ids.forEach((id, e) -> out.append(line("id", id, e.key(), e.retired())));
            bindings.forEach((key, rpc) -> out.append(line("binding", key, signature(rpc))));
            roots.forEach((key, rpc) -> out.append(line("root", key, rpc)));
            messages.forEach((name, m) -> {
                out.append(line("message", name, m.mapEntry(), m.reserved(), m.reservedNames()));
                m.fields().forEach((number, field) -> out.append(line("field", name, number, field)));
            });
            enums.forEach((name, e) -> out.append(line("enum", name, e)));
            external.forEach((id, reason) -> out.append(line("external", id, reason)));
            return out.toString();
        }
        public String fingerprint() { return sha256(canonical()); }
    }

    /** 覆盖说明不是语义变化；同一输入的 compare 必须返回空列表。 */
    public static Coverage coverage(Surface surface) {
        List<ExternalMethod> methods = surface.external().entrySet().stream()
                .map(e -> new ExternalMethod(e.getKey(), surface.ids().get(e.getKey()).key(), e.getValue())).toList();
        return new Coverage(surface.roots().size(), surface.bindings().size(), surface.messages().size(), surface.enums().size(), methods);
    }

    /** 与 MessageIdRegistry 的活号/墓碑同名规则一致；本工具额外保留墓碑供跨版本比较。0 是合法号。 */
    public static SortedMap<Integer, IdEntry> parseIds(String text) {
        SortedMap<Integer, IdEntry> ids = new TreeMap<>();
        Set<String> liveKeys = new HashSet<>();
        int line = 0;
        for (String raw : text.split("\\R", -1)) {
            line++;
            String s = raw.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] parts = s.split("=", -1);
            require(parts.length == 2 && parts[0].strip().matches("0|[1-9][0-9]*"), "消息号第 " + line + " 行格式非法");
            int id;
            try { id = Integer.parseInt(parts[0].strip()); }
            catch (NumberFormatException e) { throw invalid("消息号越界，第 " + line + " 行"); }
            String key = parts[1].strip();
            boolean retired = key.startsWith("reserved:");
            if (retired) key = key.substring("reserved:".length());
            require(key.matches("[A-Za-z_][A-Za-z_0-9]*"), "消息号方法/墓碑原名非法，第 " + line + " 行");
            require(ids.putIfAbsent(id, new IdEntry(id, key, retired)) == null, "重复消息号 " + id);
            require(retired || liveKeys.add(key), "重复活方法 " + key);
        }
        require(!ids.isEmpty(), "消息号目录为空");
        return Collections.unmodifiableSortedMap(ids);
    }

    /** 每行 id TAB key TAB 证据及排除理由；必须精确覆盖目录中确实没有描述符的活方法。 */
    public static SortedMap<Integer, String> parseExternal(String text, Map<Integer, IdEntry> ids) {
        SortedMap<Integer, String> result = new TreeMap<>();
        for (String raw : text.split("\\R", -1)) {
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;
            String[] p = raw.split("\t", -1);
            require(p.length == 3 && p[0].matches("0|[1-9][0-9]*") && !p[2].isBlank(), "外部方法清单必须为 id TAB key TAB 证据理由");
            int id;
            try { id = Integer.parseInt(p[0]); }
            catch (NumberFormatException e) { throw invalid("外部方法号越界"); }
            IdEntry entry = ids.get(id);
            require(entry != null && !entry.retired() && entry.key().equals(p[1]), "外部方法清单不匹配消息号 " + id);
            require(result.putIfAbsent(id, p[2]) == null, "重复外部方法号 " + id);
        }
        return Collections.unmodifiableSortedMap(result);
    }

    public static Surface read(FileDescriptorSet set, String catalog, String exclusions,
                               String sourceCommit, String mapsCommit) {
        require(sourceCommit.matches("[0-9a-f]{40}") && mapsCommit.matches("[0-9a-f]{40}"), "两个来源必须是完整小写提交 SHA");
        SortedMap<Integer, IdEntry> ids = parseIds(catalog);
        SortedMap<Integer, String> external = parseExternal(exclusions, ids);
        SortedMap<String, FileDescriptor> files = buildFiles(set);
        Map<String, FieldDescriptor> flags = new HashMap<>();
        for (FileDescriptor f : files.values()) for (FieldDescriptor e : f.getExtensions()) {
            if (e.getName().equals("OptionIsClientProtocolService") || e.getName().equals("OptionIsPlayerService")) {
                require(e.getContainingType().getFullName().equals("google.protobuf.ServiceOptions")
                        && e.getType() == FieldDescriptor.Type.BOOL && !e.isRepeated(), "服务可见性扩展声明非法 " + e.getFullName());
                require(flags.putIfAbsent(e.getName(), e) == null, "服务可见性扩展名字冲突 " + e.getName());
            }
        }
        require(flags.size() == 2, "缺少客户端/玩家服务可见性扩展定义，拒绝生成空契约面");
        require(flags.values().stream().map(FieldDescriptor::getNumber).distinct().count() == 2, "服务可见性扩展号冲突");
        SortedMap<String, Rpc> methods = new TreeMap<>();
        SortedMap<String, Rpc> roots = new TreeMap<>();
        SortedMap<String, MessageShape> messages = new TreeMap<>();
        SortedMap<String, EnumShape> enums = new TreeMap<>();
        Deque<Descriptor> pending = new ArrayDeque<>();
        for (FileDescriptor f : files.values()) for (ServiceDescriptor s : f.getServices()) {
            require(s.getOptions().getUninterpretedOptionCount() == 0, "服务仍有未解析 option " + s.getFullName());
            boolean client = option(s.getOptions(), flags.get("OptionIsClientProtocolService"));
            boolean player = option(s.getOptions(), flags.get("OptionIsPlayerService"));
            for (MethodDescriptor method : s.getMethods()) {
                String key = s.getName() + method.getName();
                Rpc rpc = new Rpc(method.getFullName(), method.getInputType().getFullName(), method.getOutputType().getFullName(),
                        method.isClientStreaming(), method.isServerStreaming(), client, player,
                        HexFormat.of().formatHex(method.getOptions().toByteArray()));
                require(methods.putIfAbsent(key, rpc) == null, "裸服务名+方法名冲突 " + key);
                if (client || player) {
                    roots.put(key, rpc);
                    pending.add(method.getInputType());
                    pending.add(method.getOutputType());
                }
            }
        }
        require(!roots.isEmpty(), "客户端/玩家服务根为空，拒绝空契约面");
        SortedMap<String, Rpc> bindings = new TreeMap<>();
        Set<String> liveKeys = new HashSet<>();
        for (IdEntry id : ids.values()) if (!id.retired()) {
            liveKeys.add(id.key());
            Rpc rpc = methods.get(id.key());
            if (rpc == null) require(external.containsKey(id.id()), "活消息号缺方法描述符 " + id.id() + "=" + id.key());
            else {
                require(!external.containsKey(id.id()), "外部清单不能隐藏已有方法 " + id.key());
                bindings.put(id.key(), rpc);
            }
        }
        require(liveKeys.containsAll(roots.keySet()), "客户端/玩家根方法缺消息号: " + difference(roots.keySet(), liveKeys));
        while (!pending.isEmpty()) {
            Descriptor d = pending.removeFirst();
            if (messages.containsKey(d.getFullName())) continue;
            // 客户端扩展字段需要独立的扩展发现/闭包设计，不接受只看普通字段造成假绿。
            require(d.toProto().getExtensionRangeCount() == 0 && d.getExtensions().isEmpty(), "客户端消息含未支持的扩展字段范围 " + d.getFullName());
            SortedMap<Integer, Field> fields = new TreeMap<>();
            for (FieldDescriptor f : d.getFields()) {
                require(!f.toProto().getProto3Optional() || f.getContainingOneof() != null
                        && f.getContainingOneof().getFieldCount() == 1, "非法 proto3 optional " + f.getFullName());
                if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) pending.add(f.getMessageType());
                if (f.getJavaType() == FieldDescriptor.JavaType.ENUM) {
                    EnumDescriptor e = f.getEnumType();
                    enums.putIfAbsent(e.getFullName(), enumShape(e));
                }
                String type = f.getType().name();
                require(!f.isMapField() || f.isRepeated(), "map 字段必须 repeated " + f.getFullName());
                if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE && f.getMessageType().getOptions().getMapEntry())
                    require(f.isMapField(), "map entry 被非 map 字段引用 " + f.getFullName());
                if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) type += ":" + f.getMessageType().getFullName();
                if (f.getJavaType() == FieldDescriptor.JavaType.ENUM) type += ":" + f.getEnumType().getFullName();
                String oneof = f.getContainingOneof() == null || f.toProto().getProto3Optional() ? "" : f.getContainingOneof().getName();
                Field field = new Field(f.getName(), type, f.toProto().getLabel().name(), f.isMapField(), f.hasPresence(),
                        oneof, f.isPacked(), f.toProto().hasDefaultValue() ? f.toProto().getDefaultValue() : "<implicit>", f.getJsonName());
                require(fields.putIfAbsent(f.getNumber(), field) == null, "重复字段号 " + d.getFullName());
            }
            List<Range> ranges = d.toProto().getReservedRangeList().stream().map(r -> new Range(r.getStart(), r.getEnd() - 1)).toList();
            if (d.getOptions().getMapEntry()) validateMapEntry(d);
            messages.put(d.getFullName(), new MessageShape(Collections.unmodifiableSortedMap(fields), mergeRanges(ranges),
                    immutableSet(d.toProto().getReservedNameList()), d.getOptions().getMapEntry()));
        }
        return new Surface(ids, immutableMap(bindings), immutableMap(roots), immutableMap(messages), immutableMap(enums),
                external, sourceCommit, mapsCommit);
    }

    private static EnumShape enumShape(EnumDescriptor d) {
        SortedMap<Integer, SortedSet<String>> values = new TreeMap<>();
        d.getValues().forEach(v -> values.computeIfAbsent(v.getNumber(), n -> new TreeSet<>()).add(v.getName()));
        values.replaceAll((n, names) -> Collections.unmodifiableSortedSet(names));
        return new EnumShape(immutableMap(values), mergeRanges(d.toProto().getReservedRangeList().stream()
                .map(r -> new Range(r.getStart(), r.getEnd())).toList()), immutableSet(d.toProto().getReservedNameList()),
                d.getFile().toProto().getSyntax().isEmpty() ? "proto2" : d.getFile().toProto().getSyntax(),
                d.getValues().getFirst().getName() + "=" + d.getValues().getFirst().getNumber());
    }

    private static void validateMapEntry(Descriptor d) {
        FieldDescriptor key = d.findFieldByNumber(1), value = d.findFieldByNumber(2);
        require(d.getFields().size() == 2 && key != null && value != null && key.getName().equals("key")
                && value.getName().equals("value") && !key.isRepeated() && !value.isRepeated()
                && !key.isRequired() && !value.isRequired() && d.getOneofs().isEmpty(), "非法 map entry " + d.getFullName());
        require(Set.of(FieldDescriptor.Type.INT32, FieldDescriptor.Type.INT64, FieldDescriptor.Type.UINT32,
                FieldDescriptor.Type.UINT64, FieldDescriptor.Type.SINT32, FieldDescriptor.Type.SINT64,
                FieldDescriptor.Type.FIXED32, FieldDescriptor.Type.FIXED64, FieldDescriptor.Type.SFIXED32,
                FieldDescriptor.Type.SFIXED64, FieldDescriptor.Type.BOOL, FieldDescriptor.Type.STRING).contains(key.getType()),
                "非法 map key " + d.getFullName());
    }

    private static SortedMap<String, FileDescriptor> buildFiles(FileDescriptorSet set) {
        require(set.isInitialized() && set.getFileCount() > 0, "描述符集合为空或缺 required 字段");
        Map<String, FileDescriptorProto> protos = new TreeMap<>();
        for (FileDescriptorProto f : set.getFileList()) {
            require(!f.getName().isBlank(), "描述符文件名为空");
            // Editions 的 feature 继承需另做语义规范；遇到新格式拒绝而非猜默认值。
            require(Set.of("", "proto2", "proto3").contains(f.getSyntax()), "尚不支持描述符 syntax " + f.getSyntax());
            require(protos.putIfAbsent(f.getName(), f) == null, "重复描述符文件 " + f.getName());
            f.getMessageTypeList().forEach(d -> validateMessageProto(d, f.getName()));
            f.getEnumTypeList().forEach(e -> validateEnumProto(e, f.getName()));
        }
        SortedMap<String, FileDescriptor> built = new TreeMap<>();
        for (String name : protos.keySet()) buildFile(name, protos, built, new HashSet<>());
        Set<String> symbols = new HashSet<>();
        for (FileDescriptor f : built.values()) {
            f.getMessageTypes().forEach(d -> register(d, symbols));
            f.getEnumTypes().forEach(e -> symbol(e.getFullName(), symbols));
            f.getServices().forEach(s -> symbol(s.getFullName(), symbols));
            f.getExtensions().forEach(e -> symbol(e.getFullName(), symbols));
        }
        return built;
    }

    /** FileDescriptor 的运行时验证不代替 protoc 的所有源级约束，补齐本门禁依赖的保留号/oneof 不变量。 */
    private static void validateMessageProto(DescriptorProtos.DescriptorProto d, String parent) {
        String path = parent + "/" + d.getName();
        List<Range> ranges = mergeRanges(d.getReservedRangeList().stream().map(r -> new Range(r.getStart(), r.getEnd() - 1)).toList());
        Set<String> reservedNames = new HashSet<>(d.getReservedNameList());
        require(reservedNames.size() == d.getReservedNameCount(), "重复 reserved 名 " + path);
        Set<Integer> fieldNumbers = new HashSet<>();
        for (DescriptorProtos.FieldDescriptorProto f : d.getFieldList()) {
            require(fieldNumbers.add(f.getNumber()), "重复字段号 " + path + "/" + f.getNumber());
            require(!contains(ranges, f.getNumber()) && !reservedNames.contains(f.getName()), "字段使用 reserved 号/名 " + path + "/" + f.getName());
            require(!f.hasOneofIndex() || f.getOneofIndex() >= 0 && f.getOneofIndex() < d.getOneofDeclCount(), "字段 oneof 索引非法 " + path + "/" + f.getName());
            require(!f.getProto3Optional() || f.hasOneofIndex(), "proto3 optional 缺合成 oneof " + path + "/" + f.getName());
        }
        d.getNestedTypeList().forEach(n -> validateMessageProto(n, path));
        d.getEnumTypeList().forEach(e -> validateEnumProto(e, path));
    }

    private static void validateEnumProto(DescriptorProtos.EnumDescriptorProto e, String parent) {
        require(e.getValueCount() > 0, "空枚举 " + parent + "/" + e.getName());
        List<Range> ranges = mergeRanges(e.getReservedRangeList().stream().map(r -> new Range(r.getStart(), r.getEnd())).toList());
        Set<String> names = new HashSet<>(e.getReservedNameList());
        Set<Integer> numbers = new HashSet<>();
        for (DescriptorProtos.EnumValueDescriptorProto v : e.getValueList()) {
            require(!contains(ranges, v.getNumber()) && !names.contains(v.getName()), "枚举使用 reserved 号/名 " + parent + "/" + e.getName());
            require(numbers.add(v.getNumber()) || e.getOptions().getAllowAlias(), "枚举别名没有 allow_alias " + parent + "/" + e.getName());
        }
    }

    private static FileDescriptor buildFile(String name, Map<String, FileDescriptorProto> protos,
                                            Map<String, FileDescriptor> built, Set<String> visiting) {
        if (built.containsKey(name)) return built.get(name);
        FileDescriptorProto proto = protos.get(name);
        require(proto != null, "缺少 import 描述符 " + name + "（导出须 include_imports）");
        require(visiting.add(name), "循环 import " + name);
        require(visiting.size() <= 256, "import 深度超过 256，拒绝异常描述符图");
        FileDescriptor[] imports = new FileDescriptor[proto.getDependencyCount()];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < imports.length; i++) {
            String dep = proto.getDependency(i);
            require(seen.add(dep), "重复 import " + name + ":" + dep);
            imports[i] = buildFile(dep, protos, built, visiting);
        }
        try {
            FileDescriptor result = FileDescriptor.buildFrom(proto, imports); // 禁止 allowUnknownDependencies。
            built.put(name, result);
            visiting.remove(name);
            return result;
        } catch (DescriptorValidationException e) { throw invalid("描述符非法: " + e.getMessage()); }
    }

    private static void register(Descriptor d, Set<String> symbols) {
        symbol(d.getFullName(), symbols);
        d.getNestedTypes().forEach(n -> register(n, symbols));
        d.getEnumTypes().forEach(e -> symbol(e.getFullName(), symbols));
        d.getExtensions().forEach(e -> symbol(e.getFullName(), symbols));
    }
    private static void symbol(String name, Set<String> symbols) { require(symbols.add(name), "全局符号重复 " + name); }

    private static boolean option(Message options, FieldDescriptor extension) {
        for (Map.Entry<FieldDescriptor, Object> e : options.getAllFields().entrySet()) {
            if (e.getKey().isExtension() && e.getKey().getNumber() == extension.getNumber()) return Boolean.TRUE.equals(e.getValue());
        }
        UnknownFieldSet.Field value = options.getUnknownFields().getField(extension.getNumber());
        if (!options.getUnknownFields().hasField(extension.getNumber())) return Boolean.TRUE.equals(extension.getDefaultValue());
        require(value.getVarintList().size() == 1 && value.getFixed32List().isEmpty() && value.getFixed64List().isEmpty()
                && value.getLengthDelimitedList().isEmpty() && value.getGroupList().isEmpty(), "服务可见性 option 编码非法 " + extension.getName());
        long n = value.getVarintList().getFirst();
        require(n == 0 || n == 1, "服务可见性 option 不是布尔值 " + extension.getName());
        return n == 1;
    }

    public static List<Finding> compare(Surface old, Surface next) {
        List<Finding> out = new ArrayList<>();
        changed(out, Severity.INFO, "SOURCE_CHANGED", "source.mmorpg", old.sourceCommit(), next.sourceCommit());
        changed(out, Severity.INFO, "SOURCE_CHANGED", "source.maps", old.mapsCommit(), next.mapsCommit());
        for (IdEntry before : old.ids().values()) {
            IdEntry after = next.ids().get(before.id());
            String path = "message-id/" + before.id();
            if (after == null) add(out, Severity.ERROR, "ID_HISTORY_REMOVED", path, before, "<missing>");
            else if (before.retired() && !before.equals(after)) add(out, Severity.ERROR, "ID_TOMBSTONE_CHANGED", path, before, after);
            else if (!before.key().equals(after.key())) add(out, Severity.ERROR, "ID_REUSED", path, before, after);
            else if (!before.retired() && after.retired()) add(out, Severity.BREAK, "ID_RETIRED", path, before, after);
        }
        next.ids().forEach((id, e) -> { if (!old.ids().containsKey(id)) add(out, Severity.INFO, "ID_ADDED", "message-id/" + id, "", e); });
        for (var e : old.bindings().entrySet()) {
            Rpc after = next.bindings().get(e.getKey());
            if (after != null) {
                Rpc before = e.getValue();
                changed(out, Severity.BREAK, "RPC_BINDING_CHANGED", "rpc/" + e.getKey(),
                        signature(before), signature(after));
            } else if (next.ids().values().stream().anyMatch(id -> !id.retired() && id.key().equals(e.getKey()))) {
                add(out, Severity.ERROR, "RPC_BECAME_UNRESOLVED", "rpc/" + e.getKey(), e.getValue(), "external");
            }
        }
        next.bindings().forEach((key, rpc) -> {
            if (!old.bindings().containsKey(key)) add(out, Severity.INFO, "RPC_BINDING_DISCOVERED", "rpc/" + key, "", signature(rpc));
        });
        for (var e : old.roots().entrySet()) {
            Rpc after = next.roots().get(e.getKey());
            if (after == null) add(out, Severity.BREAK, "RPC_ROOT_REMOVED", "rpc/" + e.getKey(), e.getValue(), "<missing>");
            else {
                changed(out, Severity.BREAK, "RPC_VISIBILITY_CHANGED", "rpc/" + e.getKey(),
                        e.getValue().client() + "/" + e.getValue().player(), after.client() + "/" + after.player());
                changed(out, Severity.BREAK, "RPC_OPTIONS_CHANGED", "rpc/" + e.getKey(), e.getValue().options(), after.options());
            }
        }
        next.roots().forEach((key, rpc) -> { if (!old.roots().containsKey(key)) add(out, Severity.INFO, "RPC_ROOT_ADDED", "rpc/" + key, "", rpc); });
        for (var e : old.messages().entrySet()) {
            String path = "message/" + e.getKey();
            MessageShape after = next.messages().get(e.getKey());
            if (after == null) { add(out, Severity.BREAK, "MESSAGE_LEFT_SURFACE", path, e.getKey(), "<missing>"); continue; }
            MessageShape before = e.getValue();
            history(out, path, before.reserved(), after.reserved(), before.reservedNames(), after.reservedNames());
            changed(out, Severity.BREAK, "MAP_ENTRY_CHANGED", path, before.mapEntry(), after.mapEntry());
            for (var field : before.fields().entrySet()) {
                int n = field.getKey();
                Field a = field.getValue(), b = after.fields().get(n);
                String p = path + "/field/" + n;
                if (b == null) {
                    boolean reserved = contains(after.reserved(), n) && after.reservedNames().contains(a.name());
                    add(out, reserved && !a.cardinality().equals("LABEL_REQUIRED") ? Severity.WARN : Severity.BREAK,
                            reserved ? "FIELD_REMOVED_RESERVED" : "FIELD_REMOVED_UNRESERVED", p, a, "<missing>");
                    continue;
                }
                changed(out, Severity.BREAK, "FIELD_NUMBER_REUSED", p, a.name(), b.name());
                changed(out, Severity.BREAK, "FIELD_TYPE_CHANGED", p, a.type(), b.type());
                changed(out, Severity.BREAK, "FIELD_CARDINALITY_CHANGED", p, a.cardinality(), b.cardinality());
                changed(out, Severity.BREAK, "FIELD_MAP_CHANGED", p, a.map(), b.map());
                changed(out, Severity.BREAK, "FIELD_PRESENCE_CHANGED", p, a.presence(), b.presence());
                changed(out, Severity.BREAK, "FIELD_ONEOF_CHANGED", p, a.oneof(), b.oneof());
                changed(out, Severity.BREAK, "FIELD_DEFAULT_CHANGED", p, a.defaultValue(), b.defaultValue());
                changed(out, Severity.BREAK, "FIELD_JSON_NAME_CHANGED", p, a.jsonName(), b.jsonName());
                changed(out, Severity.WARN, "FIELD_PACKED_CHANGED", p, a.packed(), b.packed());
            }
            for (var field : after.fields().entrySet()) if (!before.fields().containsKey(field.getKey())) {
                Field f = field.getValue();
                boolean used = contains(before.reserved(), field.getKey()) || before.reservedNames().contains(f.name());
                boolean oneof = !f.oneof().isEmpty() && before.fields().values().stream().anyMatch(v -> v.oneof().equals(f.oneof()));
                Severity severity = used ? Severity.ERROR : f.cardinality().equals("LABEL_REQUIRED") || oneof ? Severity.BREAK : Severity.INFO;
                add(out, severity, used ? "RESERVED_FIELD_REUSED" : oneof ? "ONEOF_MEMBER_ADDED" : "FIELD_ADDED", path + "/field/" + field.getKey(), "", f);
            }
        }
        next.messages().forEach((name, message) -> {
            if (!old.messages().containsKey(name)) add(out, Severity.INFO, "MESSAGE_ENTERED_SURFACE", "message/" + name, "", message);
        });
        for (var e : old.enums().entrySet()) {
            String path = "enum/" + e.getKey();
            EnumShape a = e.getValue(), b = next.enums().get(e.getKey());
            if (b == null) { add(out, Severity.BREAK, "ENUM_LEFT_SURFACE", path, e.getKey(), "<missing>"); continue; }
            history(out, path, a.reserved(), b.reserved(), a.reservedNames(), b.reservedNames());
            changed(out, Severity.BREAK, "ENUM_OPENNESS_CHANGED", path, a.syntax(), b.syntax());
            changed(out, Severity.BREAK, "ENUM_DEFAULT_CHANGED", path, a.firstValue(), b.firstValue());
            for (var value : a.values().entrySet()) {
                SortedSet<String> names = b.values().get(value.getKey());
                if (names == null) {
                    boolean reserved = contains(b.reserved(), value.getKey()) && b.reservedNames().containsAll(value.getValue());
                    add(out, reserved ? Severity.WARN : Severity.BREAK, reserved ? "ENUM_REMOVED_RESERVED" : "ENUM_REMOVED_UNRESERVED",
                            path + "/value/" + value.getKey(), value.getValue(), "<missing>");
                } else if (!names.containsAll(value.getValue())) {
                    add(out, Severity.BREAK, "ENUM_NUMBER_REUSED", path + "/value/" + value.getKey(), value.getValue(), names);
                } else if (!names.equals(value.getValue())) {
                    add(out, Severity.INFO, "ENUM_ALIAS_ADDED", path + "/value/" + value.getKey(), value.getValue(), names);
                }
            }
            for (var value : b.values().entrySet()) {
                boolean used = contains(a.reserved(), value.getKey()) || value.getValue().stream().anyMatch(a.reservedNames()::contains);
                if (used) add(out, Severity.ERROR, "RESERVED_ENUM_REUSED", path + "/value/" + value.getKey(), "reserved", value.getValue());
                else if (!a.values().containsKey(value.getKey())) add(out, Severity.INFO, "ENUM_VALUE_ADDED", path + "/value/" + value.getKey(), "", value.getValue());
            }
        }
        next.enums().forEach((name, value) -> {
            if (!old.enums().containsKey(name)) add(out, Severity.INFO, "ENUM_ENTERED_SURFACE", "enum/" + name, "", value);
        });
        next.external().forEach((id, reason) -> changed(out, Severity.INFO, "EXTERNAL_EVIDENCE_CHANGED", "message-id/" + id,
                old.external().getOrDefault(id, ""), reason));
        out.sort(Comparator.comparing(Finding::path).thenComparing(Finding::code).thenComparing(Finding::id));
        return List.copyOf(out);
    }

    private static String signature(Rpc r) { return line(r.fullName(), r.input(), r.output(), r.clientStreaming(), r.serverStreaming()).stripTrailing(); }
    private static void history(List<Finding> out, String path, List<Range> a, List<Range> b, Set<String> an, Set<String> bn) {
        for (Range range : a) if (b.stream().noneMatch(r -> r.start() <= range.start() && r.end() >= range.end()))
            add(out, Severity.ERROR, "RESERVED_RANGE_REMOVED", path, range, b);
        for (String name : an) if (!bn.contains(name)) add(out, Severity.ERROR, "RESERVED_NAME_REMOVED", path + "/reserved/" + name, name, "<missing>");
        for (Range range : b) if (a.stream().noneMatch(r -> r.start() <= range.start() && r.end() >= range.end()))
            add(out, Severity.INFO, "RESERVED_RANGE_ADDED", path, a, range);
        for (String name : bn) if (!an.contains(name)) add(out, Severity.INFO, "RESERVED_NAME_ADDED", path + "/reserved/" + name, "", name);
    }
    private static List<Range> mergeRanges(List<Range> input) {
        List<Range> out = new ArrayList<>();
        for (Range r : input.stream().sorted(Comparator.comparingInt(Range::start)).toList()) {
            require(r.start() <= r.end(), "非法 reserved 范围");
            if (!out.isEmpty() && (long) out.getLast().end() + 1 >= r.start()) {
                Range last = out.removeLast();
                out.add(new Range(last.start(), Math.max(last.end(), r.end())));
            } else out.add(r);
        }
        return List.copyOf(out);
    }
    private static boolean contains(List<Range> ranges, int n) { return ranges.stream().anyMatch(r -> r.contains(n)); }
    private static void changed(List<Finding> out, Severity level, String code, String path, Object a, Object b) {
        if (!Objects.equals(a, b)) add(out, level, code, path, a, b);
    }
    private static void add(List<Finding> out, Severity level, String code, String path, Object a, Object b) {
        out.add(new Finding(level, code, path, String.valueOf(a), String.valueOf(b)));
    }
    private static Set<String> difference(Set<String> a, Set<String> b) { Set<String> out = new TreeSet<>(a); out.removeAll(b); return out; }
    private static <K, V> SortedMap<K, V> immutableMap(SortedMap<K, V> m) { return Collections.unmodifiableSortedMap(new TreeMap<>(m)); }
    private static SortedSet<String> immutableSet(Collection<String> c) { return Collections.unmodifiableSortedSet(new TreeSet<>(c)); }
    static void require(boolean ok, String message) { if (!ok) throw invalid(message); }
    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    static String line(Object... values) {
        return String.join("\t", Arrays.stream(values).map(String::valueOf).map(s -> s.replace("\\", "\\\\")
                .replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")).toList()) + "\n";
    }
    static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
