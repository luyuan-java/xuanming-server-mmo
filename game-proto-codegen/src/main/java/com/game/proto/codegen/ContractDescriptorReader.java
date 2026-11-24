package com.game.proto.codegen;

import com.game.proto.codegen.ContractModel.ContractSpec;
import com.game.proto.codegen.ContractModel.EventDef;
import com.game.proto.codegen.ContractModel.MethodDef;
import com.game.proto.codegen.ContractModel.NodeTypeDef;
import com.game.proto.codegen.ContractModel.RpcKind;
import com.game.proto.codegen.ContractModel.ServiceDef;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.UnknownFieldSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从 protoc 描述符集、{@code message_id.txt} 与 {@code event_id.txt} 读出 {@link ContractSpec}。
 *
 * <p>节点归属与 Java 类型完全由 proto 语义选项与 package/service 推出，绝不依赖 proto 文件所在目录（AGENTS.md §1）。
 */
final class ContractDescriptorReader {

    private static final String CONTRACT_PROTO_PREFIX = "proto/";
    private static final String RESERVED_PREFIX = "reserved:";
    private static final String FILE_OPTIONS = ".google.protobuf.FileOptions";
    private static final String SERVICE_OPTIONS = ".google.protobuf.ServiceOptions";
    private static final String METHOD_OPTIONS = ".google.protobuf.MethodOptions";
    private static final String NODE_SERVICE_SUFFIX = "NodeService";

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "record", "yield",
            // 避免与 java.lang.Object 公开方法撞名
            "wait", "notify", "notifyAll", "equals", "hashCode", "toString", "getClass", "clone", "finalize");

    private ContractDescriptorReader() {
    }

    static final class ContractSchemaException extends RuntimeException {
        ContractSchemaException(String message) {
            super(message);
        }
    }

    static ContractSpec read(FileDescriptorSet set, InputStream messageIdTxt, InputStream eventIdTxt) throws IOException {
        Map<String, Integer> fileExt = new HashMap<>();
        Map<String, Integer> serviceExt = new HashMap<>();
        Map<String, Integer> methodExt = new HashMap<>();
        indexExtensions(set, fileExt, serviceExt, methodExt);

        Map<Integer, String> optionNodeTypeNames = indexEnumByNumber(set, "", "NodeType");
        List<NodeTypeDef> nodeTypes = readNodeTypes(set);
        Map<String, NodeTypeDef> nodeByNormalized = new HashMap<>();
        NodeTypeDef unknownNode = null;
        NodeTypeDef sceneNode = null;
        for (NodeTypeDef nt : nodeTypes) {
            nodeByNormalized.put(normalizeKey(nt.enumConstant()), nt);
            if ("UNKNOWN".equals(nt.enumConstant())) {
                unknownNode = nt;
            } else if ("SCENE".equals(nt.enumConstant())) {
                sceneNode = nt;
            }
        }
        if (unknownNode == null || sceneNode == null) {
            throw new ContractSchemaException("描述符集缺少 common.base.eNodeType（必须含 UnknownNodeService 与 SceneNodeService）");
        }

        Map<String, String> messageJavaTypes = indexMessageJavaTypes(set);

        record RawMethod(
                FileDescriptorProto file,
                ServiceDescriptorProto service,
                MethodDescriptorProto method,
                NodeTypeDef nodeType,
                boolean clientService,
                boolean playerService,
                RpcKind rpcKind) {
        }

        Map<String, RawMethod> methodsByKey = new HashMap<>();
        for (FileDescriptorProto file : set.getFileList()) {
            if (!file.getName().startsWith(CONTRACT_PROTO_PREFIX) || file.getServiceCount() == 0) {
                continue;
            }
            int fileDefaultNodeNum = intOption(file.getOptions().getUnknownFields(), fileExt, "OptionFileDefaultNode");
            for (ServiceDescriptorProto service : file.getServiceList()) {
                boolean clientService = boolOption(service.getOptions().getUnknownFields(), serviceExt, "OptionIsClientProtocolService");
                boolean playerService = boolOption(service.getOptions().getUnknownFields(), serviceExt, "OptionIsPlayerService");
                NodeTypeDef nodeType = resolveNodeType(file, service, playerService, fileDefaultNodeNum,
                        optionNodeTypeNames, nodeByNormalized, sceneNode, unknownNode);
                if (clientService && nodeType == unknownNode) {
                    throw new ContractSchemaException("客户端协议服务无法解析目标节点类型（不依赖 proto 目录）: "
                            + service.getName() + "（file=" + file.getName() + ", package=" + file.getPackage() + "）");
                }
                for (MethodDescriptorProto method : service.getMethodList()) {
                    String key = service.getName() + method.getName();
                    int kindNum = intOption(method.getOptions().getUnknownFields(), methodExt, "OptionClientRpcKind");
                    RawMethod raw = new RawMethod(file, service, method, nodeType, clientService, playerService,
                            RpcKind.fromNumber(kindNum));
                    if (methodsByKey.putIfAbsent(key, raw) != null) {
                        throw new ContractSchemaException("服务裸名 + 方法名重复，消息号无法唯一对应: " + key);
                    }
                }
            }
        }

        Map<Integer, String> idTable = parseIdTable(messageIdTxt, "message_id.txt");
        List<MethodDef> methods = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Set<String> usedMessageConstants = new HashSet<>();
        Map<String, List<MethodDef>> methodsByService = new LinkedHashMap<>();

        for (Map.Entry<Integer, String> entry : idTable.entrySet()) {
            int id = entry.getKey();
            String key = entry.getValue();
            RawMethod raw = methodsByKey.get(key);
            if (raw == null) {
                unresolved.add(key);
                continue;
            }
            String reqType = requireMessageJavaType(messageJavaTypes, raw.method().getInputType(), key + " 请求类型");
            String respType = requireMessageJavaType(messageJavaTypes, raw.method().getOutputType(), key + " 应答类型");
            String respFull = stripLeadingDot(raw.method().getOutputType());
            boolean hasResponse = !respFull.equals("Empty") && !respFull.equals("google.protobuf.Empty");
            String svcFull = raw.file().getPackage().isEmpty()
                    ? raw.service().getName()
                    : raw.file().getPackage() + "." + raw.service().getName();
            String fullMethod = "/" + svcFull + "/" + raw.method().getName();
            String constantName = toUpperSnake(key);
            if (!usedMessageConstants.add(constantName)) {
                throw new ContractSchemaException("生成的消息号常量名冲突: " + constantName + "（来自 " + key + "）");
            }
            MethodDef def = new MethodDef(
                    id,
                    constantName,
                    raw.service().getName(),
                    raw.method().getName(),
                    toUpperSnake(raw.method().getName()),
                    toHandlerMethodName(raw.method().getName()),
                    fullMethod,
                    reqType,
                    respType,
                    raw.nodeType(),
                    raw.clientService(),
                    raw.playerService(),
                    hasResponse,
                    raw.rpcKind(),
                    isGmName(raw.method().getName()));
            methods.add(def);
            methodsByService.computeIfAbsent(def.serviceName(), ignored -> new ArrayList<>()).add(def);
        }

        methods.sort(Comparator.comparingInt(MethodDef::messageId));

        List<ServiceDef> services = new ArrayList<>();
        for (Map.Entry<String, List<MethodDef>> e : methodsByService.entrySet()) {
            List<MethodDef> svcMethods = new ArrayList<>(e.getValue());
            svcMethods.sort(Comparator.comparingInt(MethodDef::messageId));
            Set<String> seenMethodConst = new HashSet<>();
            Set<String> seenHandlerMethod = new HashSet<>();
            for (MethodDef m : svcMethods) {
                if (!seenMethodConst.add(m.methodConstantName())) {
                    throw new ContractSchemaException(e.getKey() + " 内部方法常量名冲突: " + m.methodConstantName());
                }
                if (!seenHandlerMethod.add(m.handlerMethodName())) {
                    throw new ContractSchemaException(e.getKey() + " 内部 Handler 方法名冲突: " + m.handlerMethodName());
                }
            }
            MethodDef first = svcMethods.getFirst();
            services.add(new ServiceDef(e.getKey(), first.nodeType(), first.clientService(), first.playerService(),
                    List.copyOf(svcMethods)));
        }
        services.sort(Comparator.comparing(ServiceDef::serviceName));

        Map<Integer, String> eventTable = parseIdTable(eventIdTxt, "event_id.txt");
        List<EventDef> events = new ArrayList<>();
        Set<String> usedEventConstants = new HashSet<>();
        for (Map.Entry<Integer, String> entry : eventTable.entrySet()) {
            String constantName = toUpperSnake(entry.getValue());
            if (!usedEventConstants.add(constantName)) {
                throw new ContractSchemaException("生成的事件号常量名冲突: " + constantName + "（来自 " + entry.getValue() + "）");
            }
            events.add(new EventDef(entry.getKey(), constantName, entry.getValue()));
        }
        events.sort(Comparator.comparingInt(EventDef::eventId));

        return new ContractSpec(List.copyOf(nodeTypes), List.copyOf(methods), List.copyOf(services),
                List.copyOf(events), List.copyOf(unresolved));
    }

    private static NodeTypeDef resolveNodeType(
            FileDescriptorProto file,
            ServiceDescriptorProto service,
            boolean playerService,
            int fileDefaultNodeNum,
            Map<Integer, String> optionNodeTypeNames,
            Map<String, NodeTypeDef> nodeByNormalized,
            NodeTypeDef sceneNode,
            NodeTypeDef unknownNode) {
        if (playerService) {
            return sceneNode;
        }
        if (fileDefaultNodeNum != 0) {
            String rawName = optionNodeTypeNames.get(fileDefaultNodeNum);
            if (rawName != null && rawName.startsWith("NODE_")) {
                NodeTypeDef matched = nodeByNormalized.get(normalizeKey(rawName.substring("NODE_".length())));
                if (matched != null) {
                    return matched;
                }
            }
        }
        NodeTypeDef byService = nodeByNormalized.get(normalizeKey(service.getName()));
        if (byService != null) {
            return byService;
        }
        String pkg = file.getPackage();
        if (!pkg.isEmpty()) {
            String stem = pkg.endsWith("pb") && pkg.length() > 2 ? pkg.substring(0, pkg.length() - 2) : pkg;
            NodeTypeDef byPkg = nodeByNormalized.get(normalizeKey(stem));
            if (byPkg != null) {
                return byPkg;
            }
            if (stem.endsWith("_node") && stem.length() > "_node".length()) {
                NodeTypeDef byStem = nodeByNormalized.get(normalizeKey(stem.substring(0, stem.length() - "_node".length())));
                if (byStem != null) {
                    return byStem;
                }
            }
        }
        return unknownNode;
    }

    private static List<NodeTypeDef> readNodeTypes(FileDescriptorSet set) {
        List<NodeTypeDef> out = new ArrayList<>();
        for (FileDescriptorProto file : set.getFileList()) {
            if (!"common.base".equals(file.getPackage())) {
                continue;
            }
            for (EnumDescriptorProto e : file.getEnumTypeList()) {
                if (!"eNodeType".equals(e.getName())) {
                    continue;
                }
                for (EnumValueDescriptorProto v : e.getValueList()) {
                    String protoName = v.getName();
                    String stem = protoName.endsWith(NODE_SERVICE_SUFFIX)
                            ? protoName.substring(0, protoName.length() - NODE_SERVICE_SUFFIX.length())
                            : protoName;
                    String constant = toUpperSnake(stem);
                    String dubboGroup = "UNKNOWN".equals(constant)
                            ? ""
                            : constant.toLowerCase(Locale.ROOT).replace('_', '-');
                    boolean directOnly = "BATTLE".equals(constant);
                    boolean gateLocal = "GATE".equals(constant);
                    out.add(new NodeTypeDef(constant, v.getNumber(), protoName, dubboGroup, directOnly, gateLocal));
                }
            }
        }
        out.sort(Comparator.comparingInt(NodeTypeDef::code));
        return out;
    }

    private static Map<Integer, String> indexEnumByNumber(FileDescriptorSet set, String protoPackage, String enumName) {
        Map<Integer, String> out = new HashMap<>();
        for (FileDescriptorProto file : set.getFileList()) {
            if (!protoPackage.equals(file.getPackage())) {
                continue;
            }
            for (EnumDescriptorProto e : file.getEnumTypeList()) {
                if (!enumName.equals(e.getName())) {
                    continue;
                }
                for (EnumValueDescriptorProto v : e.getValueList()) {
                    out.put(v.getNumber(), v.getName());
                }
            }
        }
        return out;
    }

    private static void indexExtensions(
            FileDescriptorSet set,
            Map<String, Integer> fileExt,
            Map<String, Integer> serviceExt,
            Map<String, Integer> methodExt) {
        for (FileDescriptorProto file : set.getFileList()) {
            for (FieldDescriptorProto ext : file.getExtensionList()) {
                Map<String, Integer> target = switch (ext.getExtendee()) {
                    case FILE_OPTIONS -> fileExt;
                    case SERVICE_OPTIONS -> serviceExt;
                    case METHOD_OPTIONS -> methodExt;
                    default -> null;
                };
                if (target != null) {
                    target.put(ext.getName(), ext.getNumber());
                }
            }
        }
    }

    private static Map<String, String> indexMessageJavaTypes(FileDescriptorSet set) {
        Map<String, String> out = new HashMap<>();
        for (FileDescriptorProto file : set.getFileList()) {
            FileOptions options = file.getOptions();
            String javaPkg = options.hasJavaPackage() ? options.getJavaPackage() : file.getPackage();
            String pkgPrefix = javaPkg.isEmpty() ? "" : javaPkg + ".";
            String javaPrefix;
            if (options.getJavaMultipleFiles()) {
                javaPrefix = pkgPrefix;
            } else {
                String outer = options.hasJavaOuterClassname()
                        ? options.getJavaOuterClassname()
                        : outerClassNameWithoutOption(file);
                javaPrefix = pkgPrefix + outer + ".";
            }
            String protoPrefix = file.getPackage().isEmpty() ? "." : "." + file.getPackage() + ".";
            for (DescriptorProto msg : file.getMessageTypeList()) {
                indexNestedMessages(protoPrefix, javaPrefix, msg, out);
            }
        }
        return out;
    }

    private static void indexNestedMessages(String protoPrefix, String javaPrefix, DescriptorProto msg, Map<String, String> out) {
        String protoFull = protoPrefix + msg.getName();
        String javaFull = javaPrefix + msg.getName();
        out.put(protoFull, javaFull);
        for (DescriptorProto nested : msg.getNestedTypeList()) {
            indexNestedMessages(protoFull + ".", javaFull + ".", nested, out);
        }
    }

    private static String requireMessageJavaType(Map<String, String> messageJavaTypes, String protoFullName, String context) {
        String key = protoFullName.startsWith(".") ? protoFullName : "." + protoFullName;
        String javaType = messageJavaTypes.get(key);
        if (javaType == null) {
            throw new ContractSchemaException("描述符集中找不到 " + context + ": " + protoFullName);
        }
        return javaType;
    }

    private static boolean boolOption(UnknownFieldSet fields, Map<String, Integer> ext, String name) {
        return intOption(fields, ext, name) != 0;
    }

    private static int intOption(UnknownFieldSet fields, Map<String, Integer> ext, String name) {
        Integer number = ext.get(name);
        if (number == null || !fields.hasField(number)) {
            return 0;
        }
        List<Long> varints = fields.getField(number).getVarintList();
        return varints.isEmpty() ? 0 : (int) (long) varints.getLast();
    }

    static Map<Integer, String> parseIdTable(InputStream in, String sourceName) throws IOException {
        Map<Integer, String> ids = new LinkedHashMap<>();
        Map<String, Integer> seenKeys = new HashMap<>();
        Set<Integer> reservedIds = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0 || eq == trimmed.length() - 1) {
                    throw new ContractSchemaException(sourceName + " 第 " + lineNo + " 行格式错误: " + line);
                }
                int id;
                try {
                    id = Integer.parseInt(trimmed.substring(0, eq).strip());
                } catch (NumberFormatException e) {
                    throw new ContractSchemaException(sourceName + " 第 " + lineNo + " 行号非整数: " + line);
                }
                String key = trimmed.substring(eq + 1).strip();
                if (key.startsWith(RESERVED_PREFIX)) {
                    if (key.length() == RESERVED_PREFIX.length()) {
                        throw new ContractSchemaException(sourceName + " 第 " + lineNo + " 行墓碑缺原名: " + line);
                    }
                    if (ids.containsKey(id) || !reservedIds.add(id)) {
                        throw new ContractSchemaException(sourceName + " 号码重复: " + id);
                    }
                    continue;
                }
                if (reservedIds.contains(id) || ids.putIfAbsent(id, key) != null) {
                    throw new ContractSchemaException(sourceName + " 号码重复: " + id);
                }
                if (seenKeys.putIfAbsent(key, id) != null) {
                    throw new ContractSchemaException(sourceName + " 键名重复: " + key);
                }
            }
        }
        return ids;
    }

    private static String outerClassNameWithoutOption(FileDescriptorProto fileProto) {
        String name = fileProto.getName();
        int slash = name.lastIndexOf('/');
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        if (base.endsWith(".proto")) {
            base = base.substring(0, base.length() - ".proto".length());
        }
        String camel = underscoresToCamelCase(base);
        boolean conflict = fileProto.getMessageTypeList().stream().anyMatch(m -> m.getName().equals(camel))
                || fileProto.getEnumTypeList().stream().anyMatch(e -> e.getName().equals(camel))
                || fileProto.getServiceList().stream().anyMatch(s -> s.getName().equals(camel));
        return conflict ? camel + "OuterClass" : camel;
    }

    private static String underscoresToCamelCase(String input) {
        StringBuilder out = new StringBuilder(input.length());
        boolean capNext = true;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 'a' && c <= 'z') {
                out.append(capNext ? Character.toUpperCase(c) : c);
                capNext = false;
            } else if (c >= 'A' && c <= 'Z') {
                out.append(c);
                capNext = false;
            } else if (c >= '0' && c <= '9') {
                out.append(c);
                capNext = true;
            } else {
                capNext = true;
            }
        }
        return out.toString();
    }

    static String toUpperSnake(String input) {
        StringBuilder out = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '_' || c == '-' || c == '.') {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != '_') {
                    out.append('_');
                }
                continue;
            }
            if (Character.isUpperCase(c) && !out.isEmpty() && out.charAt(out.length() - 1) != '_') {
                char prev = input.charAt(i - 1);
                char next = i + 1 < input.length() ? input.charAt(i + 1) : 0;
                if (Character.isLowerCase(prev) || (Character.isUpperCase(prev) && Character.isLowerCase(next))) {
                    out.append('_');
                }
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    static String toHandlerMethodName(String methodName) {
        if (methodName.isEmpty()) {
            throw new ContractSchemaException("RPC 方法名为空");
        }
        String candidate = Character.toLowerCase(methodName.charAt(0)) + methodName.substring(1);
        return JAVA_KEYWORDS.contains(candidate) ? candidate + "_" : candidate;
    }

    private static boolean isGmName(String name) {
        for (String prefix : new String[] {"Gm", "Debug", "Test"}) {
            if (name.startsWith(prefix) && name.length() > prefix.length()
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKey(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '_' && c != '-') {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    private static String stripLeadingDot(String fullName) {
        return fullName.startsWith(".") ? fullName.substring(1) : fullName;
    }
}
