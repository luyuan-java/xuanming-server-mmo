package com.game.contract.check.persist;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** 只从 protobuf 已校验的 descriptor 导出持久根闭包；不解析 proto 源码。 */
public record PersistentSchemaSnapshot(String root, SortedMap<String, MessageShape> messages,
                                       SortedMap<String, EnumShape> enums) {
    public record MessageShape(String syntax, DescriptorProto proto) { }
    public record EnumShape(String syntax, String firstName, EnumDescriptorProto proto) { }

    public PersistentSchemaSnapshot {
        require(name(root), "ROOT_NAME");
        messages = Collections.unmodifiableSortedMap(new TreeMap<>(messages));
        enums = Collections.unmodifiableSortedMap(new TreeMap<>(enums));
        require(messages.containsKey(root) && messages.size() + enums.size() <= 10_000, "ROOT_OR_TYPE_COUNT");
        var enumIndex = enums;
        messages.forEach((type, shape) -> {
            require(name(type) && !enumIndex.containsKey(type), "TYPE_NAME");
            require(syntax(shape.syntax()) && shape.proto().getUnknownFields().asMap().isEmpty(), "MESSAGE_FORMAT");
            require(shape.proto().getNestedTypeCount() == 0 && shape.proto().getEnumTypeCount() == 0
                    && shape.proto().getExtensionCount() == 0 && shape.proto().getExtensionRangeCount() == 0,
                    "NESTED_OR_EXTENSIONS_UNSUPPORTED");
        });
        enums.forEach((type, shape) -> require(name(type) && syntax(shape.syntax())
                && shape.proto().getValueList().stream().anyMatch(v -> v.getName().equals(shape.firstName()))
                && shape.proto().getUnknownFields().asMap().isEmpty(), "ENUM_FORMAT"));
        validate(root, messages, enums);
    }

    public static PersistentSchemaSnapshot capture(Descriptor root) {
        SortedMap<String, MessageShape> messages = new TreeMap<>();
        SortedMap<String, EnumShape> enums = new TreeMap<>();
        var pending = new ArrayDeque<Descriptor>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Descriptor message = pending.remove();
            if (messages.containsKey(message.getFullName())) continue;
            String syntax = message.getFile().toProto().getSyntax();
            require(syntax.isEmpty() || syntax.equals("proto2") || syntax.equals("proto3"), "EDITIONS_UNSUPPORTED");
            require(message.getExtensions().isEmpty() && message.toProto().getExtensionRangeCount() == 0, "EXTENSIONS_UNSUPPORTED");
            messages.put(message.getFullName(), new MessageShape(syntax.isEmpty() ? "proto2" : syntax, normalize(message)));
            for (FieldDescriptor field : message.getFields()) {
                if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) pending.add(field.getMessageType());
                if (field.getJavaType() == FieldDescriptor.JavaType.ENUM) {
                    EnumDescriptor enumeration = field.getEnumType();
                    String enumSyntax = enumeration.getFile().toProto().getSyntax();
                    require(enumSyntax.isEmpty() || enumSyntax.equals("proto2") || enumSyntax.equals("proto3"), "EDITIONS_UNSUPPORTED");
                    enums.put(enumeration.getFullName(), new EnumShape(enumSyntax.isEmpty() ? "proto2" : enumSyntax,
                            enumeration.getValues().getFirst().getName(), normalize(enumeration)));
                }
            }
        }
        return new PersistentSchemaSnapshot(root.getFullName(), messages, enums);
    }

    private static DescriptorProto normalize(Descriptor message) {
        var out = DescriptorProto.newBuilder().setName(message.getName())
                .setOptions(DescriptorProtos.MessageOptions.newBuilder().setMapEntry(message.getOptions().getMapEntry()));
        var oneofs = message.getOneofs().stream().sorted(Comparator.comparing(o -> o.getName())).toList();
        oneofs.forEach(o -> out.addOneofDecl(DescriptorProtos.OneofDescriptorProto.newBuilder().setName(o.getName())));
        for (var field : message.getFields().stream().sorted(Comparator.comparingInt(FieldDescriptor::getNumber)).toList()) {
            var value = field.toProto().toBuilder().clearOptions().clearOneofIndex()
                    .setProto3Optional(field.toProto().getProto3Optional())
                    .setOptions(DescriptorProtos.FieldOptions.newBuilder().setPacked(field.isPacked()));
            if (field.getContainingOneof() != null) value.setOneofIndex(oneofs.indexOf(field.getContainingOneof()));
            out.addField(value);
        }
        message.toProto().getReservedRangeList().stream().sorted(Comparator.comparingInt(DescriptorProto.ReservedRange::getStart))
                .forEach(out::addReservedRange);
        message.toProto().getReservedNameList().stream().sorted().forEach(out::addReservedName);
        return out.build();
    }

    private static EnumDescriptorProto normalize(EnumDescriptor enumeration) {
        var out = EnumDescriptorProto.newBuilder().setName(enumeration.getName())
                .setOptions(DescriptorProtos.EnumOptions.newBuilder().setAllowAlias(enumeration.getOptions().getAllowAlias()));
        enumeration.toProto().getValueList().stream().sorted(Comparator
                .comparingInt(DescriptorProtos.EnumValueDescriptorProto::getNumber)
                .thenComparing(DescriptorProtos.EnumValueDescriptorProto::getName))
                .map(v -> v.toBuilder().clearOptions().build()).forEach(out::addValue);
        enumeration.toProto().getReservedRangeList().stream()
                .sorted(Comparator.comparingInt(EnumDescriptorProto.EnumReservedRange::getStart)).forEach(out::addReservedRange);
        enumeration.toProto().getReservedNameList().stream().sorted().forEach(out::addReservedName);
        return out.build();
    }

    public byte[] bytes() {
        StringBuilder text = new StringBuilder("persistent-schema-v1\t" + root + "\n");
        messages.forEach((name, shape) -> text.append("message\t").append(name).append('\t').append(shape.syntax())
                .append('\t').append(Base64.getEncoder().encodeToString(shape.proto().toByteArray())).append('\n'));
        enums.forEach((name, shape) -> text.append("enum\t").append(name).append('\t').append(shape.syntax()).append('\t')
                .append(shape.firstName()).append('\t').append(Base64.getEncoder().encodeToString(shape.proto().toByteArray())).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String sha256() { return sha256(bytes()); }

    public static PersistentSchemaSnapshot parse(byte[] bytes) {
        require(bytes.length > 0 && bytes.length <= PersistentSchemaLedger.MAX_SNAPSHOT_BYTES, "SNAPSHOT_SIZE");
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        require(lines.length > 2 && lines[lines.length - 1].isEmpty(), "SNAPSHOT_LINES");
        String[] header = lines[0].split("\t", -1);
        require(header.length == 2 && header[0].equals("persistent-schema-v1"), "SNAPSHOT_VERSION");
        SortedMap<String, MessageShape> messages = new TreeMap<>();
        SortedMap<String, EnumShape> enums = new TreeMap<>();
        try {
            for (int i = 1; i < lines.length - 1; i++) {
                String[] parts = lines[i].split("\t", -1);
                if (parts.length == 4 && parts[0].equals("message")) {
                    var shape = new MessageShape(parts[2], DescriptorProto.parseFrom(Base64.getDecoder().decode(parts[3])));
                    require(messages.putIfAbsent(parts[1], shape) == null, "TYPE_DUPLICATE");
                } else if (parts.length == 5 && parts[0].equals("enum")) {
                    var shape = new EnumShape(parts[2], parts[3], EnumDescriptorProto.parseFrom(Base64.getDecoder().decode(parts[4])));
                    require(enums.putIfAbsent(parts[1], shape) == null, "TYPE_DUPLICATE");
                } else throw new IllegalArgumentException("SNAPSHOT_ROW");
            }
            var snapshot = new PersistentSchemaSnapshot(header[1], messages, enums);
            require(java.util.Arrays.equals(bytes, snapshot.bytes()), "SNAPSHOT_NON_CANONICAL");
            return snapshot;
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException("SNAPSHOT_PROTO", invalid);
        }
    }

    /** 包含字段名、精确类型、标签、presence、map 类型、默认值、packed 和真实 oneof 名。 */
    static String fieldIdentity(MessageShape owner, FieldDescriptorProto field) {
        String oneof = field.hasOneofIndex() ? owner.proto().getOneofDecl(field.getOneofIndex()).getName() : "";
        return Base64.getEncoder().encodeToString(field.toBuilder().clearOneofIndex().build().toByteArray()) + ":" + oneof;
    }

    static String realOneof(MessageShape owner, FieldDescriptorProto field) {
        return field.hasOneofIndex() && !field.getProto3Optional() ? owner.proto().getOneofDecl(field.getOneofIndex()).getName() : "";
    }

    static boolean messageReserved(MessageShape shape, int number) {
        return shape.proto().getReservedRangeList().stream().anyMatch(r -> number >= r.getStart() && number < r.getEnd());
    }

    static boolean enumReserved(EnumShape shape, int number) {
        return shape.proto().getReservedRangeList().stream().anyMatch(r -> number >= r.getStart() && number <= r.getEnd());
    }

    static Map<Integer, FieldDescriptorProto> fields(MessageShape shape) {
        var fields = new TreeMap<Integer, FieldDescriptorProto>();
        shape.proto().getFieldList().forEach(f -> require(fields.putIfAbsent(f.getNumber(), f) == null, "FIELD_DUPLICATE"));
        return fields;
    }

    /** 历史快照也必须是完整、可解释的闭包，不能让自报摘要掩盖伪造 oneof/悬空引用。 */
    private static void validate(String root, Map<String, MessageShape> messages, Map<String, EnumShape> enums) {
        var reachedMessages = new java.util.HashSet<String>();
        var reachedEnums = new java.util.HashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(root);
        while (!pending.isEmpty()) {
            String name = pending.remove();
            if (!reachedMessages.add(name)) continue;
            MessageShape shape = messages.get(name);
            require(shape != null && shape.proto().getName().equals(simple(name)), "MESSAGE_REFERENCE");
            var fieldNames = new java.util.HashSet<String>();
            var fieldNumbers = new java.util.HashSet<Integer>();
            var oneofNames = new java.util.HashSet<String>();
            var oneofCounts = new int[shape.proto().getOneofDeclCount()];
            for (var oneof : shape.proto().getOneofDeclList())
                require(simpleName(oneof.getName()) && oneofNames.add(oneof.getName()) && !oneof.hasOptions()
                        && oneof.getUnknownFields().asMap().isEmpty(), "ONEOF_FORMAT");
            require(new java.util.HashSet<>(shape.proto().getReservedNameList()).size() == shape.proto().getReservedNameCount()
                    && shape.proto().getReservedNameList().stream().allMatch(PersistentSchemaSnapshot::simpleName), "RESERVED_NAME");
            for (var range : shape.proto().getReservedRangeList())
                require(range.getStart() >= 1 && range.getEnd() > range.getStart() && range.getEnd() <= 536870912,
                        "MESSAGE_RESERVED_RANGE");
            for (var field : shape.proto().getFieldList()) {
                int number = field.getNumber();
                require(number > 0 && number <= 536870911 && (number < 19000 || number > 19999)
                        && fieldNumbers.add(number) && simpleName(field.getName()) && fieldNames.add(field.getName()), "FIELD_IDENTITY");
                require(!messageReserved(shape, number) && !shape.proto().getReservedNameList().contains(field.getName()), "FIELD_RESERVED");
                require(field.hasType() && field.hasLabel() && field.getType() != FieldDescriptorProto.Type.TYPE_GROUP
                        && field.getExtendee().isEmpty() && field.getUnknownFields().asMap().isEmpty(), "FIELD_FORMAT");
                if (shape.syntax().equals("proto3")) require(field.getLabel() != FieldDescriptorProto.Label.LABEL_REQUIRED
                        && !field.hasDefaultValue(), "PROTO3_FIELD_FORMAT");
                if (field.hasOneofIndex()) {
                    require(field.getOneofIndex() >= 0 && field.getOneofIndex() < oneofCounts.length
                            && field.getLabel() == FieldDescriptorProto.Label.LABEL_OPTIONAL, "ONEOF_FIELD");
                    oneofCounts[field.getOneofIndex()]++;
                }
                if (field.getProto3Optional()) require(shape.syntax().equals("proto3") && field.hasOneofIndex(), "OPTIONAL_PRESENCE");
                if (field.getType() == FieldDescriptorProto.Type.TYPE_MESSAGE) {
                    require(field.getTypeName().startsWith("."), "MESSAGE_REFERENCE");
                    String target = field.getTypeName().substring(1);
                    require(messages.containsKey(target), "MESSAGE_REFERENCE");
                    if (messages.get(target).proto().getOptions().getMapEntry())
                        require(field.getLabel() == FieldDescriptorProto.Label.LABEL_REPEATED && !field.hasOneofIndex(), "MAP_FIELD");
                    pending.add(target);
                } else if (field.getType() == FieldDescriptorProto.Type.TYPE_ENUM) {
                    require(field.getTypeName().startsWith(".") && enums.containsKey(field.getTypeName().substring(1)), "ENUM_REFERENCE");
                    reachedEnums.add(field.getTypeName().substring(1));
                } else require(field.getTypeName().isEmpty(), "SCALAR_TYPE_REFERENCE");
            }
            for (int count : oneofCounts) require(count > 0, "ONEOF_EMPTY");
            for (var field : shape.proto().getFieldList())
                if (field.getProto3Optional()) require(oneofCounts[field.getOneofIndex()] == 1, "OPTIONAL_ONEOF_MEMBERS");
            if (shape.proto().getOptions().getMapEntry()) {
                Map<Integer, FieldDescriptorProto> fields = fields(shape);
                require(fields.size() == 2 && fields.containsKey(1) && fields.containsKey(2)
                        && fields.get(1).getName().equals("key") && fields.get(2).getName().equals("value")
                        && fields.values().stream().allMatch(f -> f.getLabel() == FieldDescriptorProto.Label.LABEL_OPTIONAL && !f.hasOneofIndex()), "MAP_SHAPE");
                require(java.util.Set.of(FieldDescriptorProto.Type.TYPE_INT32, FieldDescriptorProto.Type.TYPE_INT64,
                        FieldDescriptorProto.Type.TYPE_UINT32, FieldDescriptorProto.Type.TYPE_UINT64,
                        FieldDescriptorProto.Type.TYPE_SINT32, FieldDescriptorProto.Type.TYPE_SINT64,
                        FieldDescriptorProto.Type.TYPE_FIXED32, FieldDescriptorProto.Type.TYPE_FIXED64,
                        FieldDescriptorProto.Type.TYPE_SFIXED32, FieldDescriptorProto.Type.TYPE_SFIXED64,
                        FieldDescriptorProto.Type.TYPE_BOOL, FieldDescriptorProto.Type.TYPE_STRING).contains(fields.get(1).getType()), "MAP_KEY");
            }
        }
        require(reachedMessages.equals(messages.keySet()) && reachedEnums.equals(enums.keySet()), "UNREACHABLE_TYPES");
        enums.forEach((name, shape) -> {
            require(shape.proto().getName().equals(simple(name)) && shape.proto().getValueCount() > 0, "ENUM_NAME");
            var valueNames = new java.util.HashSet<String>();
            var valueNumbers = new java.util.HashSet<Integer>();
            for (var value : shape.proto().getValueList()) {
                require(simpleName(value.getName()) && valueNames.add(value.getName()) && value.getUnknownFields().asMap().isEmpty()
                        && !value.hasOptions(), "ENUM_VALUE");
                require(valueNumbers.add(value.getNumber()) || shape.proto().getOptions().getAllowAlias(), "ENUM_ALIAS");
                require(!enumReserved(shape, value.getNumber()) && !shape.proto().getReservedNameList().contains(value.getName()), "ENUM_RESERVED");
                if (shape.syntax().equals("proto3") && value.getName().equals(shape.firstName())) require(value.getNumber() == 0, "ENUM_ZERO_VALUE");
            }
            for (var range : shape.proto().getReservedRangeList()) require(range.getEnd() >= range.getStart(), "ENUM_RESERVED_RANGE");
        });
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void require(boolean value, String code) { if (!value) throw new IllegalArgumentException(code); }
    private static boolean name(String name) { return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"); }
    private static boolean simpleName(String name) { return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*"); }
    private static String simple(String name) { return name.substring(name.lastIndexOf('.') + 1); }
    private static boolean syntax(String syntax) { return syntax.equals("proto2") || syntax.equals("proto3"); }
}
