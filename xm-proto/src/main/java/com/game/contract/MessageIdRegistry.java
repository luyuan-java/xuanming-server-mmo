package com.game.contract;

import com.game.proto.db.ProtoOption;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.Message;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 消息号注册表：{@code message_id.txt}（唯一真源）↔ 服务方法 ↔ 请求 / 应答类型。
 *
 * <p>消息号是客户端可见契约，由 mmorpg 的生成器发号并同步进来；本类<b>只读不发号</b>。
 * 生成器会复用空洞号，所以不能按方法名自行推号，也不能缓存旧表。
 *
 * <p>构建方式：读 protoc 输出的 descriptor set（{@code contract/contract.desc}）枚举所有 proto 文件，
 * 按 protoc 的 Java 命名规则找到每个文件的生成类，取<b>生成类自带</b>的 {@link FileDescriptor}
 * （自定义 option 已解析），再按「服务裸名 + 方法名」与 message_id.txt 对上号。
 *
 * <p>线程安全：构建完成后只读。
 */
public final class MessageIdRegistry {

    public static final String MESSAGE_ID_RESOURCE = "contract/message_id.txt";
    public static final String DESCRIPTOR_SET_RESOURCE = "contract/contract.desc";

    private static final String CONTRACT_PROTO_PREFIX = "proto/";

    private final Map<Integer, MessageMethod> byId;
    private final Map<String, Integer> idByKey;
    private final List<String> unresolvedKeys;

    private MessageIdRegistry(Map<Integer, MessageMethod> byId, Map<String, Integer> idByKey, List<String> unresolvedKeys) {
        this.byId = Collections.unmodifiableMap(byId);
        this.idByKey = Collections.unmodifiableMap(idByKey);
        this.unresolvedKeys = List.copyOf(unresolvedKeys);
    }

    /** 从 classpath 上的同步产物构建。 */
    public static MessageIdRegistry loadFromClasspath() {
        ClassLoader loader = MessageIdRegistry.class.getClassLoader();
        try (InputStream ids = open(loader, MESSAGE_ID_RESOURCE); InputStream desc = open(loader, DESCRIPTOR_SET_RESOURCE)) {
            return build(ids, FileDescriptorSet.parseFrom(desc), loader);
        } catch (IOException e) {
            throw new UncheckedIOException("加载消息号注册表失败", e);
        }
    }

    static MessageIdRegistry build(InputStream messageIdTxt, FileDescriptorSet descriptorSet, ClassLoader loader) throws IOException {
        Map<String, MethodDescriptor> methodsByKey = indexContractMethods(descriptorSet, loader);

        Map<Integer, MessageMethod> byId = new LinkedHashMap<>();
        Map<String, Integer> idByKey = new HashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : parseMessageIds(messageIdTxt).entrySet()) {
            int id = entry.getKey();
            String key = entry.getValue();
            MethodDescriptor method = methodsByKey.get(key);
            if (method == null) {
                // proto/etcd 未同步进 Java 版（Java 版不连 etcd），那几个号在这里解析不到，属预期。
                unresolved.add(key);
                continue;
            }
            byId.put(id, toMessageMethod(id, method, loader));
            idByKey.put(key, id);
        }
        return new MessageIdRegistry(byId, idByKey, unresolved);
    }

    public Optional<MessageMethod> byId(int messageId) {
        return Optional.ofNullable(byId.get(messageId));
    }

    public OptionalInt idOf(String serviceName, String methodName) {
        Integer id = idByKey.get(serviceName + methodName);
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    /** 按方法取号；契约里必须存在的方法用它，缺号即是同步产物与代码不一致，直接失败。 */
    public int requireId(String serviceName, String methodName) {
        return idOf(serviceName, methodName).orElseThrow(() ->
                new IllegalStateException("message_id.txt 中没有 " + serviceName + "." + methodName));
    }

    public Collection<MessageMethod> all() {
        return byId.values();
    }

    /** message_id.txt 里有、但当前同步的 proto 中找不到方法的键（预期只有 etcd 相关服务）。 */
    public List<String> unresolvedKeys() {
        return unresolvedKeys;
    }

    // ---------------------------------------------------------------- 构建细节

    private static Map<String, MethodDescriptor> indexContractMethods(FileDescriptorSet set, ClassLoader loader) {
        Map<String, MethodDescriptor> byKey = new HashMap<>();
        for (FileDescriptorProto fileProto : set.getFileList()) {
            if (!fileProto.getName().startsWith(CONTRACT_PROTO_PREFIX) || fileProto.getServiceCount() == 0) {
                continue;
            }
            FileDescriptor file = generatedFileDescriptor(fileProto, loader);
            for (ServiceDescriptor service : file.getServices()) {
                for (MethodDescriptor method : service.getMethods()) {
                    String key = service.getName() + method.getName();
                    MethodDescriptor previous = byKey.putIfAbsent(key, method);
                    if (previous != null) {
                        throw new IllegalStateException("服务裸名 + 方法名重复，消息号无法唯一对应: " + key
                                + " (" + previous.getFullName() + " / " + method.getFullName() + ")");
                    }
                }
            }
        }
        return byKey;
    }

    private static MessageMethod toMessageMethod(int id, MethodDescriptor method, ClassLoader loader) {
        ServiceDescriptor service = method.getService();
        boolean client = service.getOptions().getExtension(ProtoOption.optionIsClientProtocolService);
        return new MessageMethod(
                id,
                service.getName(),
                method.getName(),
                method,
                defaultInstance(method.getInputType(), loader),
                defaultInstance(method.getOutputType(), loader),
                client,
                domainOf(service.getFile().getName()));
    }

    /** {@code proto/login/login.proto} → {@code login}。 */
    static String domainOf(String protoFileName) {
        String rest = protoFileName.substring(CONTRACT_PROTO_PREFIX.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? "" : rest.substring(0, slash);
    }

    /**
     * 找到 proto 文件的生成外部类并取其 FileDescriptor。protoc 规则：有 java_outer_classname 用之；
     * 否则文件名转驼峰，若与文件内类型重名则追加 OuterClass。这里依次尝试两个候选，以生成类自报的文件名确认。
     */
    private static FileDescriptor generatedFileDescriptor(FileDescriptorProto fileProto, ClassLoader loader) {
        FileOptions options = fileProto.getOptions();
        String pkg = options.getJavaPackage();
        List<String> candidates = new ArrayList<>();
        if (options.hasJavaOuterClassname()) {
            candidates.add(options.getJavaOuterClassname());
        } else {
            String base = baseName(fileProto.getName());
            String camel = underscoresToCamelCase(base);
            candidates.add(camel);
            candidates.add(camel + "OuterClass");
        }
        for (String simpleName : candidates) {
            String className = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
            try {
                Object result = Class.forName(className, true, loader).getMethod("getDescriptor").invoke(null);
                if (result instanceof FileDescriptor fd && fd.getName().equals(fileProto.getName())) {
                    return fd;
                }
            } catch (ReflectiveOperationException ignored) {
                // 下一个候选
            }
        }
        throw new IllegalStateException("找不到 " + fileProto.getName() + " 的生成类，候选: " + candidates);
    }

    /** 取生成消息类的默认实例。类名按 java_package / java_multiple_files / 嵌套关系推出。 */
    private static Message defaultInstance(Descriptor type, ClassLoader loader) {
        String className = javaClassName(type);
        try {
            return (Message) Class.forName(className, true, loader).getMethod("getDefaultInstance").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("找不到消息 " + type.getFullName() + " 的生成类 " + className, e);
        }
    }

    static String javaClassName(Descriptor type) {
        FileDescriptor file = type.getFile();
        FileOptions options = file.getOptions();
        String pkg = options.hasJavaPackage() ? options.getJavaPackage() : file.getPackage();
        List<String> chain = new ArrayList<>();
        for (Descriptor d = type; d != null; d = d.getContainingType()) {
            chain.add(0, d.getName());
        }
        String nested = String.join("$", chain);
        String prefix = pkg.isEmpty() ? "" : pkg + ".";
        if (options.getJavaMultipleFiles()) {
            return prefix + nested;
        }
        FileDescriptorProto fileProto = file.toProto();
        String outer = options.hasJavaOuterClassname()
                ? options.getJavaOuterClassname()
                : outerClassNameWithoutOption(fileProto);
        return prefix + outer + "$" + nested;
    }

    private static String outerClassNameWithoutOption(FileDescriptorProto fileProto) {
        String camel = underscoresToCamelCase(baseName(fileProto.getName()));
        boolean conflict = fileProto.getMessageTypeList().stream().anyMatch(m -> m.getName().equals(camel))
                || fileProto.getEnumTypeList().stream().anyMatch(e -> e.getName().equals(camel))
                || fileProto.getServiceList().stream().anyMatch(s -> s.getName().equals(camel));
        return conflict ? camel + "OuterClass" : camel;
    }

    private static String baseName(String protoFileName) {
        String name = protoFileName.substring(protoFileName.lastIndexOf('/') + 1);
        return name.endsWith(".proto") ? name.substring(0, name.length() - ".proto".length()) : name;
    }

    /** 与 protoc Java 生成器的 UnderscoresToCamelCase(input, cap_first=true) 一致。 */
    static String underscoresToCamelCase(String input) {
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

    /** 解析 {@code N=服务裸名方法名}；空行与 # 注释跳过；号或键重复即失败。 */
    static Map<Integer, String> parseMessageIds(InputStream in) throws IOException {
        Map<Integer, String> ids = new LinkedHashMap<>();
        Map<String, Integer> seenKeys = new HashMap<>();
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
                    throw new IllegalStateException("message_id.txt 第 " + lineNo + " 行格式错误: " + line);
                }
                int id = Integer.parseInt(trimmed.substring(0, eq).strip());
                String key = trimmed.substring(eq + 1).strip();
                if (ids.putIfAbsent(id, key) != null) {
                    throw new IllegalStateException("message_id.txt 消息号重复: " + id);
                }
                if (seenKeys.putIfAbsent(key, id) != null) {
                    throw new IllegalStateException("message_id.txt 方法重复: " + key);
                }
            }
        }
        return ids;
    }

    private static InputStream open(ClassLoader loader, String resource) {
        InputStream in = loader.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("classpath 上缺少 " + resource + "（先跑 tools/ContractSync.java 并构建 xm-proto）");
        }
        return in;
    }
}
