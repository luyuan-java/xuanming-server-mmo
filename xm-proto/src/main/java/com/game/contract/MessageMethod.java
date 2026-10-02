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

    /**
     * GM 类指令：方法名是 {@code Gm} / {@code Debug} / {@code Test} 后跟大写字母（{@code GmAddCurrency}、{@code DebugXxx}）。
     * 按名字判定而不是维护一份消息号清单：Java 没有生成的消息号常量，写死的号表会随 mmorpg 重新生成而漂移；
     * 新增的 GM 方法也不会漏闸（基线 gate 的清单要手工登记）。
     */
    public boolean gmCommand() {
        return isGmName(methodName);
    }

    static boolean isGmName(String name) {
        for (String prefix : new String[] {"Gm", "Debug", "Test"}) {
            if (name.startsWith(prefix) && name.length() > prefix.length()
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return true;
            }
        }
        return false;
    }
}
