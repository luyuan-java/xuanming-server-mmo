package com.game.contract;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Message;

/**
 * 一个消息号对应的 RPC 方法。
 *
 * <p>只暴露协议语义（服务名、是否客户端服务、是否玩家服务），不暴露 proto 文件所在目录：
 * mmorpg 会整理目录，Java 代码不能依赖它（见 docs/design/architecture.md §1）。
 *
 * @param messageId         {@code message_id.txt} 里的号，客户端可见契约
 * @param serviceName       服务裸名（不带 proto package），与 message_id.txt 的键一致
 * @param methodName        方法名
 * @param method            生成代码里的方法描述符
 * @param requestPrototype  请求消息的默认实例（用于解析请求体）
 * @param responsePrototype 应答消息的默认实例
 * @param clientService     所在服务标了 {@code OptionIsClientProtocolService}（客户端可直接发）
 * @param playerService     所在服务标了 {@code OptionIsPlayerService}（由玩家所在的 scene 处理）
 */
public record MessageMethod(
        int messageId,
        String serviceName,
        String methodName,
        MethodDescriptor method,
        Message requestPrototype,
        Message responsePrototype,
        boolean clientService,
        boolean playerService) {

    /** 与 message_id.txt 的键同形：服务裸名 + 方法名。 */
    public String key() {
        return serviceName + methodName;
    }
}
