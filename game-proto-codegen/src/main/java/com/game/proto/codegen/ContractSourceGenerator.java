package com.game.proto.codegen;

import com.game.proto.codegen.ContractModel.ContractSpec;
import com.game.proto.codegen.ContractModel.EventDef;
import com.game.proto.codegen.ContractModel.MethodDef;
import com.game.proto.codegen.ContractModel.NodeTypeDef;
import com.game.proto.codegen.ContractModel.RpcKind;
import com.game.proto.codegen.ContractModel.ServiceDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 {@link ContractSpec} 生成编译期 Java 21 源码（对照 C++ protogen 产出）：
 * <ul>
 *   <li>{@code ContractMessageIds} / {@code ContractEventIds}：编译期消息号与事件号常量；</li>
 *   <li>{@code ContractNodeType} / {@code ContractClientRpcKind} / {@code ContractRouteEntry} / {@code ContractRouteTable}：
 *       零反射静态路由表（对齐 {@code route_table.go} 与 {@code service_metadata.h}）；</li>
 *   <li>{@code ContractRpcMethod} 与 {@code rpc.<Service>Rpc}：类型化 RPC 描述常量（对齐 {@code robot_*.tmpl}）；</li>
 *   <li>{@code service.<Service>Handler} 与 {@code service.<Service>ContractDispatcher}：每服务接口与 switch 派发器
 *       （以 Java 接口替代 C++ 用户代码段）。</li>
 * </ul>
 */
final class ContractSourceGenerator {

    static final String PROCESSOR = "com.game.proto.codegen.ContractProtoProcessor";
    static final String BASE_PKG = "com.game.contract.generated";
    static final String RPC_PKG = BASE_PKG + ".rpc";
    static final String SERVICE_PKG = BASE_PKG + ".service";

    private ContractSourceGenerator() {
    }

    /** 全限定类名 → Java 源码。 */
    static Map<String, String> generate(ContractSpec spec) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(BASE_PKG + ".ContractNodeType", generateNodeType(spec.nodeTypes()));
        files.put(BASE_PKG + ".ContractClientRpcKind", generateRpcKind());
        files.put(BASE_PKG + ".ContractMessageIds", generateMessageIds(spec.methods()));
        files.put(BASE_PKG + ".ContractEventIds", generateEventIds(spec.events()));
        files.put(BASE_PKG + ".ContractRouteEntry", generateRouteEntry());
        files.put(BASE_PKG + ".ContractRouteTable", generateRouteTable(spec));
        files.put(BASE_PKG + ".ContractRpcMethod", generateRpcMethodRecord());
        for (ServiceDef service : spec.services()) {
            files.put(RPC_PKG + "." + service.serviceName() + "Rpc", generateServiceRpc(service));
            files.put(SERVICE_PKG + "." + service.serviceName() + "Handler", generateServiceHandler(service));
            files.put(SERVICE_PKG + "." + service.serviceName() + "ContractDispatcher", generateServiceDispatcher(service));
        }
        return files;
    }

    private static String generateNodeType(List<NodeTypeDef> nodeTypes) {
        Src s = new Src();
        s.line("package " + BASE_PKG + ";");
        s.line();
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/** 由 game-proto-codegen 从 {@code common.base.eNodeType} 编译期生成，不要手改。 */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.open("public enum ContractNodeType");
        for (int i = 0; i < nodeTypes.size(); i++) {
            NodeTypeDef n = nodeTypes.get(i);
            String sep = i + 1 < nodeTypes.size() ? "," : ";";
            s.line(n.enumConstant() + "(" + n.code() + ", " + quote(n.protoName()) + ", " + quote(n.dubboGroup())
                    + ", " + n.directOnly() + ", " + n.gateLocal() + ")" + sep);
        }
        s.line();
        s.line("private final int code;");
        s.line("private final String protoName;");
        s.line("private final String dubboGroup;");
        s.line("private final boolean directOnly;");
        s.line("private final boolean gateLocal;");
        s.line();
        s.open("ContractNodeType(int code, String protoName, String dubboGroup, boolean directOnly, boolean gateLocal)");
        s.line("this.code = code;");
        s.line("this.protoName = protoName;");
        s.line("this.dubboGroup = dubboGroup;");
        s.line("this.directOnly = directOnly;");
        s.line("this.gateLocal = gateLocal;");
        s.close();
        s.line();
        s.line("public int code() { return code; }");
        s.line("public String protoName() { return protoName; }");
        s.line("public String dubboGroup() { return dubboGroup; }");
        s.line("public boolean directOnly() { return directOnly; }");
        s.line("public boolean gateLocal() { return gateLocal; }");
        s.line();
        s.open("public static ContractNodeType byCode(int code)");
        s.open("return switch (code)");
        for (NodeTypeDef n : nodeTypes) {
            s.line("case " + n.code() + " -> " + n.enumConstant() + ";");
        }
        s.line("default -> UNKNOWN;");
        s.closeSemicolon();
        s.close();
        s.close();
        return s.toString();
    }

    private static String generateRpcKind() {
        Src s = new Src();
        s.line("package " + BASE_PKG + ";");
        s.line();
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/** 对齐 {@code proto/db/proto_option.proto} 的 {@code ClientRpcKind}，不要手改。 */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.open("public enum ContractClientRpcKind");
        s.line("REQUEST,");
        s.line("ONE_WAY,");
        s.line("PUSH,");
        s.line("SERVER_ONLY;");
        s.close();
        return s.toString();
    }

    private static String generateMessageIds(List<MethodDef> methods) {
        Src s = new Src();
        s.line("package " + BASE_PKG + ";");
        s.line();
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/** 契约 {@code message_id.txt} 中全部活跃消息号的编译期常量，由 game-proto-codegen 生成，不要手改。 */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.open("public final class ContractMessageIds");
        s.line("private ContractMessageIds() {}");
        s.line();
        for (MethodDef m : methods) {
            s.line("/** " + m.serviceName() + "." + m.methodName() + " (" + m.fullMethod() + ") */");
            s.line("public static final int " + m.constantName() + " = " + m.messageId() + ";");
        }
        s.line();
        s.line("/** 按消息号取契约键名（服务裸名 + 方法名），未知或已退役返回 null。 */");
        s.open("public static String keyOf(int messageId)");
        s.open("return switch (messageId)");
        for (MethodDef m : methods) {
            s.line("case " + m.messageId() + " -> " + quote(m.serviceName() + m.methodName()) + ";");
        }
        s.line("default -> null;");
        s.closeSemicolon();
        s.close();
        s.close();
        return s.toString();
    }

    private static String generateEventIds(List<EventDef> events) {
        Src s = new Src();
        s.line("package " + BASE_PKG + ";");
        s.line();
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/** 契约 {@code event_id.txt} 中全部活跃事件号的编译期常量，由 game-proto-codegen 生成，不要手改。 */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.open("public final class ContractEventIds");
        s.line("private ContractEventIds() {}");
        s.line();
        for (EventDef e : events) {
            s.line("public static final int " + e.constantName() + " = " + e.eventId() + ";");
        }
        s.line();
        s.line("/** 按事件号取事件名，未知或已退役返回 null。 */");
        s.open("public static String nameOf(int eventId)");
        s.open("return switch (eventId)");
        for (EventDef e : events) {
            s.line("case " + e.eventId() + " -> " + quote(e.keyName()) + ";");
        }
        s.line("default -> null;");
        s.closeSemicolon();
        s.close();
        s.close();
        return s.toString();
    }

    private static String generateRouteEntry() {
        Src s = new Src();
        s.line("package " + BASE_PKG + ";");
        s.line();
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/** 契约 RPC 静态路由元数据条目（对齐 C++/Go {@code route_table.go} 的 {@code RouteEntry}）。 */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.line("public record ContractRouteEntry(");
        s.line("        int messageId,");
        s.line("        String serviceName,");
        s.line("        String methodName,");
        s.line("        String fullMethod,");
        s.line("        ContractNodeType nodeType,");
        s.line("        boolean clientService,");
        s.line("        boolean playerService,");
        s.line("        boolean hasResponse,");
        s.line("        ContractClientRpcKind rpcKind,");
        s.open("        boolean gmCommand)");
        s.line("public String key() { return serviceName + methodName; }");
        s.line("public String qualifiedMethod() { return serviceName + \".\" + methodName; }");
        s.line("public boolean directOnly() { return nodeType.directOnly(); }");
        s.line("public boolean gateLocal() { return nodeType.gateLocal(); }");
        s.close();
        return s.toString();
    }

    private static String generateRouteTable(ContractSpec spec) {
        Src s = new Src();
        s.line("package " + BASE_PKG + ";");
        s.line();
        s.line("import java.util.List;");
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/**");
        s.line(" * 编译期静态路由表（由 game-proto-codegen 从契约描述符集与 {@code message_id.txt} 生成，不要手改）。");
        s.line(" * 零反射 O(1) 查找，覆盖全部已同步且有活消息号的 RPC 方法。");
        s.line(" */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.open("public final class ContractRouteTable");
        s.line("private ContractRouteTable() {}");
        s.line();
        for (MethodDef m : spec.methods()) {
            s.line("private static final ContractRouteEntry E_" + m.messageId() + " = new ContractRouteEntry("
                    + m.messageId() + ", " + quote(m.serviceName()) + ", " + quote(m.methodName()) + ", "
                    + quote(m.fullMethod()) + ", ContractNodeType." + m.nodeType().enumConstant() + ", "
                    + m.clientService() + ", " + m.playerService() + ", " + m.hasResponse() + ", "
                    + "ContractClientRpcKind." + m.rpcKind().name() + ", " + m.gmCommand() + ");");
        }
        s.line();
        s.line("/** 全部活跃路由条目（按消息号升序，不可变）。 */");
        s.line("private static final List<ContractRouteEntry> ALL = List.of(" + entryRefs(spec.methods(), false) + ");");
        s.line("/** 全部客户端协议服务路由条目（按消息号升序，不可变）。 */");
        s.line("private static final List<ContractRouteEntry> CLIENT = List.of(" + entryRefs(spec.methods(), true) + ");");
        s.line("/** message_id.txt 中存在、但当前同步契约未包含的键（预期仅为未同步的 etcd 服务）。 */");
        s.line("public static final List<String> UNRESOLVED_KEYS = List.of(" + quotedList(spec.unresolvedMessageKeys()) + ");");
        s.line();
        s.line("/** 按消息号 O(1) 查全部活跃方法；未知返回 null。 */");
        s.open("public static ContractRouteEntry lookup(int messageId)");
        s.open("return switch (messageId)");
        for (MethodDef m : spec.methods()) {
            s.line("case " + m.messageId() + " -> E_" + m.messageId() + ";");
        }
        s.line("default -> null;");
        s.closeSemicolon();
        s.close();
        s.line();
        s.line("/** 按消息号 O(1) 查客户端可发方法；未知或非客户端协议服务返回 null。 */");
        s.open("public static ContractRouteEntry clientRoute(int messageId)");
        s.line("ContractRouteEntry entry = lookup(messageId);");
        s.line("return entry != null && entry.clientService() ? entry : null;");
        s.close();
        s.line();
        s.line("public static List<ContractRouteEntry> allRoutes() { return ALL; }");
        s.line("public static List<ContractRouteEntry> clientRoutes() { return CLIENT; }");
        s.line();
        s.line("/** 按服务裸名取该服务的全部活跃路由条目（按消息号升序）；未登记返回空列表。 */");
        s.open("public static List<ContractRouteEntry> methodsOf(String serviceName)");
        s.open("return switch (serviceName)");
        for (ServiceDef svc : spec.services()) {
            s.line("case " + quote(svc.serviceName()) + " -> List.of(" + entryRefs(svc.methods(), false) + ");");
        }
        s.line("default -> List.of();");
        s.closeSemicolon();
        s.close();
        s.close();
        return s.toString();
    }

    private static String generateRpcMethodRecord() {
        Src s = new Src();
        s.line("package " + BASE_PKG + ";");
        s.line();
        s.line("import com.google.protobuf.Message;");
        s.line("import com.google.protobuf.Parser;");
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/** 编译期类型化 RPC 方法描述（供 game-robot、测试与调用方直接使用，免手写消息号与 Parser）。 */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.line("public record ContractRpcMethod<Q extends Message, R extends Message>(");
        s.line("        int messageId,");
        s.line("        String serviceName,");
        s.line("        String methodName,");
        s.line("        String fullMethod,");
        s.line("        Parser<Q> requestParser,");
        s.line("        Parser<R> responseParser,");
        s.line("        boolean hasResponse,");
        s.open("        ContractClientRpcKind rpcKind)");
        s.line("public String key() { return serviceName + methodName; }");
        s.close();
        return s.toString();
    }

    private static String generateServiceRpc(ServiceDef svc) {
        Src s = new Src();
        s.line("package " + RPC_PKG + ";");
        s.line();
        s.line("import " + BASE_PKG + ".ContractClientRpcKind;");
        s.line("import " + BASE_PKG + ".ContractMessageIds;");
        s.line("import " + BASE_PKG + ".ContractNodeType;");
        s.line("import " + BASE_PKG + ".ContractRpcMethod;");
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/** 服务 {@code " + svc.serviceName() + "} 的类型化 RPC 桩常量（由 game-proto-codegen 生成，不要手改）。 */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.open("public final class " + svc.serviceName() + "Rpc");
        s.line("public static final String SERVICE = " + quote(svc.serviceName()) + ";");
        s.line("public static final ContractNodeType NODE_TYPE = ContractNodeType." + svc.nodeType().enumConstant() + ";");
        s.line();
        s.line("private " + svc.serviceName() + "Rpc() {}");
        s.line();
        for (MethodDef m : svc.methods()) {
            s.line("public static final ContractRpcMethod<" + m.requestJavaType() + ", " + m.responseJavaType() + "> "
                    + m.methodConstantName() + " = new ContractRpcMethod<>(");
            s.line("        ContractMessageIds." + m.constantName() + ", SERVICE, " + quote(m.methodName()) + ", "
                    + quote(m.fullMethod()) + ",");
            s.line("        " + m.requestJavaType() + ".parser(), " + m.responseJavaType() + ".parser(), "
                    + m.hasResponse() + ", ContractClientRpcKind." + m.rpcKind().name() + ");");
        }
        s.close();
        return s.toString();
    }

    private static String generateServiceHandler(ServiceDef svc) {
        Src s = new Src();
        s.line("package " + SERVICE_PKG + ";");
        s.line();
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/**");
        s.line(" * 服务 {@code " + svc.serviceName() + "} 的类型化业务处理器接口（对齐 C++ protogen 的 handler 骨架，采用 Java 接口隔离模式）。");
        s.line(" * 推送占位（PUSH）与仅服务端方法（SERVER_ONLY）提供默认空实现，业务请求方法由实现类显式覆盖。");
        s.line(" */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.open("public interface " + svc.serviceName() + "Handler<C>");
        for (MethodDef m : svc.methods()) {
            if (m.rpcKind() == RpcKind.PUSH || m.rpcKind() == RpcKind.SERVER_ONLY) {
                s.open("default " + m.responseJavaType() + " " + m.handlerMethodName()
                        + "(C context, " + m.requestJavaType() + " request) throws Exception");
                s.line("return " + m.responseJavaType() + ".getDefaultInstance();");
                s.close();
            } else {
                s.line(m.responseJavaType() + " " + m.handlerMethodName()
                        + "(C context, " + m.requestJavaType() + " request) throws Exception;");
            }
        }
        s.close();
        return s.toString();
    }

    private static String generateServiceDispatcher(ServiceDef svc) {
        Src s = new Src();
        s.line("package " + SERVICE_PKG + ";");
        s.line();
        s.line("import " + BASE_PKG + ".ContractMessageIds;");
        s.line("import com.google.protobuf.ByteString;");
        s.line("import com.google.protobuf.Message;");
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/**");
        s.line(" * 服务 {@code " + svc.serviceName() + "} 的零反射编译期派发器（由 game-proto-codegen 生成，不要手改）。");
        s.line(" * 按编译期消息号常量 O(1) switch 解析请求体并调用 {@link " + svc.serviceName() + "Handler}；非本服务消息号返回 null。");
        s.line(" */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.open("public final class " + svc.serviceName() + "ContractDispatcher");
        s.line("private " + svc.serviceName() + "ContractDispatcher() {}");
        s.line();
        s.open("public static boolean handles(int messageId)");
        s.open("return switch (messageId)");
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < svc.methods().size(); i++) {
            if (i > 0) {
                cases.append(", ");
            }
            cases.append("ContractMessageIds.").append(svc.methods().get(i).constantName());
        }
        s.line("case " + cases + " -> true;");
        s.line("default -> false;");
        s.closeSemicolon();
        s.close();
        s.line();
        s.open("public static <C> Message dispatch(int messageId, C context, ByteString body, "
                + svc.serviceName() + "Handler<C> handler) throws Exception");
        s.open("return switch (messageId)");
        for (MethodDef m : svc.methods()) {
            s.line("case ContractMessageIds." + m.constantName() + " -> handler." + m.handlerMethodName()
                    + "(context, " + m.requestJavaType() + ".parseFrom(body));");
        }
        s.line("default -> null;");
        s.closeSemicolon();
        s.close();
        s.close();
        return s.toString();
    }

    private static String entryRefs(List<MethodDef> methods, boolean clientOnly) {
        StringBuilder sb = new StringBuilder();
        for (MethodDef m : methods) {
            if (clientOnly && !m.clientService()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append("E_").append(m.messageId());
        }
        return sb.toString();
    }

    private static String quotedList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(items.get(i)));
        }
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static final class Src {
        private final StringBuilder sb = new StringBuilder(2048);
        private int indent;

        void open(String header) {
            line(header + " {");
            indent++;
        }

        void close() {
            indent--;
            line("}");
        }

        void closeSemicolon() {
            indent--;
            line("};");
        }

        void line() {
            sb.append('\n');
        }

        void line(String text) {
            sb.append("    ".repeat(indent)).append(text).append('\n');
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
