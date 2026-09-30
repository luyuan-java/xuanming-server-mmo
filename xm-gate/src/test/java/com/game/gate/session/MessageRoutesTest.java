package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 用同步进来的真实契约核对路由：登录链路走 login，进场后的消息走 scene，内部服务消息客户端发不了。 */
class MessageRoutesTest {

    private static MessageIdRegistry registry;
    private static MessageRoutes routes;

    @BeforeAll
    static void load() {
        registry = MessageIdRegistry.loadFromClasspath();
        routes = MessageRoutes.of(registry);
    }

    @Test
    void 登录链路消息号路由到login域() {
        for (String method : new String[] {"Login", "CreatePlayer", "EnterGame", "LeaveGame", "Disconnect"}) {
            int id = registry.requireId("ClientPlayerLogin", method);
            assertThat(routes.clientRoute(id).domain()).as(method).isEqualTo(ClientDispatcher.DOMAIN_LOGIN);
        }
    }

    @Test
    void 是否回包由契约的应答类型决定_与应答是否为0字节无关() {
        // Login / CreatePlayer / EnterGame 有应答：新账号的 LoginResponse 全默认值、序列化为 0 字节，也必须回包。
        for (String method : new String[] {"Login", "CreatePlayer", "EnterGame"}) {
            int id = registry.requireId("ClientPlayerLogin", method);
            assertThat(routes.clientRoute(id).hasResponse()).as(method).isTrue();
        }
        // 应答类型为 Empty 的方法不回包。
        registry.all().stream()
                .filter(m -> m.clientService()
                        && m.responsePrototype().getDescriptorForType().getFullName().endsWith("Empty"))
                .forEach(m -> assertThat(routes.clientRoute(m.messageId()).hasResponse()).as(m.key()).isFalse());
    }

    @Test
    void 进场后的客户端消息路由到scene域() {
        int listSkills = registry.requireId("SceneSkillClientPlayer", "ListSkills");
        assertThat(routes.clientRoute(listSkills)).isEqualTo(new MessageRoute(listSkills, ClientDispatcher.DOMAIN_SCENE));
    }

    @Test
    void Java版未接入的客户端服务路由到unsupported() {
        int friendMessage = registry.all().stream()
                .filter(m -> m.clientService() && m.serviceName().equals("ClientPlayerFriend"))
                .findFirst().orElseThrow().messageId();
        assertThat(routes.clientRoute(friendMessage)).isEqualTo(new MessageRoute(friendMessage, MessageRoutes.BACKEND_UNSUPPORTED));
    }

    @Test
    void 非客户端协议服务与不存在的消息号不可路由() {
        MessageMethod internal = registry.all().stream().filter(m -> !m.clientService()).findFirst().orElseThrow();
        assertThat(routes.clientRoute(internal.messageId())).isNull();
        assertThat(routes.clientRoute(Integer.MAX_VALUE)).isNull();
    }

    @Test
    void 推tip用的消息号是23() {
        assertThat(registry.requireId("SceneClientPlayerCommon", "SendTipToClient")).isEqualTo(23);
    }
}
