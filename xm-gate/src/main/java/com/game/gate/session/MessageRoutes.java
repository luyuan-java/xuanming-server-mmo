package com.game.gate.session;

import com.game.api.DubboGroups;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端消息号白名单与路由：只有标了 {@code OptionIsClientProtocolService} 的服务方法客户端可以发。
 *
 * <p>路由按服务语义决定，<b>不看 proto 文件所在目录</b>（mmorpg 会整理目录，见 architecture.md §1）：
 * <ul>
 *   <li>标了 {@code OptionIsPlayerService} 的服务由玩家所在的 scene 处理；</li>
 *   <li>其余客户端服务查 {@link #SERVICE_BACKENDS}（服务裸名 → 后端 Dubbo group）；</li>
 *   <li>表里没有的是 Java 版尚未实现的服务，路由到 {@link #BACKEND_UNSUPPORTED}，由 dispatcher 回「服务不可用」。</li>
 * </ul>
 *
 * <p>单独成接口是为了让路由测试不依赖某个具体消息号的发号结果（消息号由 mmorpg 生成器发，会漂移）。
 */
@FunctionalInterface
public interface MessageRoutes {

    /** Java 版尚未接入的后端。 */
    String BACKEND_UNSUPPORTED = "unsupported";

    /** 非玩家服务的客户端服务 → 后端。新接入一个后端就在这里加一行。 */
    Map<String, String> SERVICE_BACKENDS = Map.of(
            "ClientPlayerLogin", DubboGroups.LOGIN);

    /** 客户端可发的消息号的路由；消息号不存在或不属于客户端协议服务时返回 null。 */
    MessageRoute clientRoute(int messageId);

    /** 从消息号注册表构建（启动时一次，之后只读，线程安全）。 */
    static MessageRoutes of(MessageIdRegistry registry) {
        Map<Integer, MessageRoute> table = new HashMap<>();
        for (MessageMethod method : registry.all()) {
            if (method.clientService()) {
                table.put(method.messageId(), new MessageRoute(method.messageId(), backendOf(method), hasResponse(method),
                        method.serviceName() + "." + method.methodName()));
            }
        }
        Map<Integer, MessageRoute> frozen = Map.copyOf(table);
        return frozen::get;
    }

    /** 应答类型是契约里的 {@code Empty}（无 package）或 {@code google.protobuf.Empty} 的方法不回包，其余一律回。 */
    static boolean hasResponse(MessageMethod method) {
        String type = method.responsePrototype().getDescriptorForType().getFullName();
        return !type.equals("Empty") && !type.equals("google.protobuf.Empty");
    }

    static String backendOf(MessageMethod method) {
        if (method.playerService()) {
            return ClientDispatcher.DOMAIN_SCENE;
        }
        return SERVICE_BACKENDS.getOrDefault(method.serviceName(), BACKEND_UNSUPPORTED);
    }
}
