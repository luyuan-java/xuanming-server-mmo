package com.game.proto.codegen;

import java.util.List;

/**
 * 从契约描述符集与号表解析出的不可变协议元数据模型。
 */
final class ContractModel {

    private ContractModel() {
    }

    /** 与 proto/common/base/node.proto 的 {@code eNodeType} 一一对应。 */
    record NodeTypeDef(
            String enumConstant,
            int code,
            String protoName,
            String dubboGroup,
            boolean directOnly,
            boolean gateLocal) {
    }

    /** 与 proto/db/proto_option.proto 的 {@code ClientRpcKind} 一一对应。 */
    enum RpcKind {
        REQUEST,
        ONE_WAY,
        PUSH,
        SERVER_ONLY;

        static RpcKind fromNumber(int number) {
            return switch (number) {
                case 0 -> REQUEST;
                case 1 -> ONE_WAY;
                case 2 -> PUSH;
                case 3 -> SERVER_ONLY;
                default -> throw new ContractDescriptorReader.ContractSchemaException("未知 OptionClientRpcKind 取值: " + number);
            };
        }
    }

    record MethodDef(
            int messageId,
            String constantName,
            String serviceName,
            String methodName,
            String methodConstantName,
            String handlerMethodName,
            String fullMethod,
            String requestJavaType,
            String responseJavaType,
            NodeTypeDef nodeType,
            boolean clientService,
            boolean playerService,
            boolean hasResponse,
            RpcKind rpcKind,
            boolean gmCommand) {
    }

    record ServiceDef(
            String serviceName,
            NodeTypeDef nodeType,
            boolean clientService,
            boolean playerService,
            List<MethodDef> methods) {
    }

    record EventDef(int eventId, String constantName, String keyName) {
    }

    record ContractSpec(
            List<NodeTypeDef> nodeTypes,
            List<MethodDef> methods,
            List<ServiceDef> services,
            List<EventDef> events,
            List<String> unresolvedMessageKeys) {
    }
}
