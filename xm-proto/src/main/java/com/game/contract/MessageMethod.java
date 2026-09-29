package com.game.contract;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Message;

/**
 * 一个消息号对应的 RPC 方法。
 *
 * @param messageId         {@code message_id.txt} 里的号，客户端可见契约
 * @param serviceName       服务裸名（不带 proto package），与 message_id.txt 的键一致
 * @param methodName        方法名
 * @param method            生成代码里的方法描述符
 * @param requestPrototype  请求消息的默认实例（用于解析请求体）
 * @param responsePrototype 应答消息的默认实例
 * @param clientService     所在服务是否标了 {@code OptionIsClientProtocolService}（客户端可直接发）
 * @param domain            proto 所在的一级目录（{@code proto/login/...} 为 {@code login}），决定由哪个后端处理
 */
public record MessageMethod(
        int messageId,
        String serviceName,
        String methodName,
        MethodDescriptor method,
        Message requestPrototype,
        Message responsePrototype,
        boolean clientService,
        String domain) {

    /** 与 message_id.txt 的键同形：服务裸名 + 方法名。 */
    public String key() {
        return serviceName + methodName;
    }
}
