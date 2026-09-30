package com.game.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MessageIdRegistryTest {

    private static MessageIdRegistry registry;

    @BeforeAll
    static void load() {
        registry = MessageIdRegistry.loadFromClasspath();
    }

    @Test
    void 登录相关消息号与方法和类型对得上() {
        MessageMethod login = registry.byId(48).orElseThrow();
        assertThat(login.serviceName()).isEqualTo("ClientPlayerLogin");
        assertThat(login.methodName()).isEqualTo("Login");
        assertThat(login.clientService()).isTrue();
        assertThat(login.playerService()).isFalse();
        assertThat(login.requestPrototype().getDescriptorForType().getFullName()).isEqualTo("loginpb.LoginRequest");

        assertThat(registry.requireId("ClientPlayerLogin", "CreatePlayer")).isEqualTo(14);
        assertThat(registry.requireId("ClientPlayerLogin", "EnterGame")).isEqualTo(26);
        assertThat(registry.requireId("ClientPlayerLogin", "LeaveGame")).isEqualTo(17);
        assertThat(registry.requireId("ClientPlayerLogin", "Disconnect")).isEqualTo(58);
    }

    @Test
    void 场景下行与进场后的客户端消息是玩家服务_由_scene_处理() {
        MessageMethod notifyEnter = registry.byId(79).orElseThrow();
        assertThat(notifyEnter.key()).isEqualTo("SceneSceneClientPlayerNotifyEnterScene");
        assertThat(notifyEnter.playerService()).isTrue();

        MessageMethod listSkills = registry.byId(77).orElseThrow();
        assertThat(listSkills.key()).isEqualTo("SceneSkillClientPlayerListSkills");
        assertThat(listSkills.clientService()).isTrue();
        assertThat(listSkills.playerService()).isTrue();
    }

    @Test
    void 解析不到的只有未同步的_etcd_服务() {
        List<String> etcdServices = List.of("KV", "Watch", "Lease", "Cluster", "Maintenance", "Auth");
        assertThat(registry.unresolvedKeys()).isNotEmpty()
                .allSatisfy(key -> assertThat(etcdServices).anyMatch(key::startsWith));
    }

    @Test
    void 与_message_id_txt_的条目数一致() throws Exception {
        // 不写死条目数：契约每次同步都可能追加消息号
        int entries;
        try (var in = MessageIdRegistry.class.getClassLoader().getResourceAsStream(MessageIdRegistry.MESSAGE_ID_RESOURCE)) {
            entries = MessageIdRegistry.parseMessageIds(in).size();
        }
        assertThat(entries).isPositive();
        assertThat(registry.all().size() + registry.unresolvedKeys().size()).isEqualTo(entries);
    }

    @Test
    void 文件名转驼峰与_protoc_一致() {
        assertThat(MessageIdRegistry.underscoresToCamelCase("scene_manager_service")).isEqualTo("SceneManagerService");
        assertThat(MessageIdRegistry.underscoresToCamelCase("player_2d_pos")).isEqualTo("Player2DPos");
        assertThat(MessageIdRegistry.underscoresToCamelCase("s2s_player_scene")).isEqualTo("S2SPlayerScene");
    }

    @Test
    void 消息号或方法重复即拒绝() {
        assertThatThrownBy(() -> MessageIdRegistry.parseMessageIds(stream("1=AB\n1=CD\n")))
                .hasMessageContaining("消息号重复");
        assertThatThrownBy(() -> MessageIdRegistry.parseMessageIds(stream("1=AB\n2=AB\n")))
                .hasMessageContaining("方法重复");
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
