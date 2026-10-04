package com.game.data.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.discovery.RedisKeys;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 热关停运维接口：规则键去空白与开头斜杠后校验字符集、写成与各进程同一口径的 JSON、列表标出写坏的值、Redis 不可用 503。Redis 是替身。
 */
class KillSwitchAdminControllerTest {

    @SuppressWarnings("unchecked")
    private final RMap<String, String> map = mock(RMap.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private KillSwitchAdminController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedissonClient redis = mock(RedissonClient.class);
        when(redis.<String, String>getMap(RedisKeys.killSwitch(), StringCodec.INSTANCE)).thenReturn(map);
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(redis);
        controller = new KillSwitchAdminController(provider);
    }

    @Test
    void 规则键去空白与开头斜杠_只收标识符点斜杠星号() {
        assertThat(KillSwitchAdminController.validPattern(" //friendpb.ClientPlayerFriend/AddFriend ")).isEqualTo(
                "friendpb.ClientPlayerFriend/AddFriend");
        assertThat(KillSwitchAdminController.validPattern("*")).isEqualTo("*");
        assertThat(KillSwitchAdminController.validPattern("com.game.api.ClientMessageService/handle"))
                .isEqualTo("com.game.api.ClientMessageService/handle");
        for (String bad : new String[] {null, "", "  ", "///", "a b/c", "a\n/b", "好友/*", "x".repeat(201)}) {
            assertThatThrownBy(() -> KillSwitchAdminController.validPattern(bad)).as(String.valueOf(bad))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        }
    }

    @Test
    void 写规则_JSON只带非缺省字段_校验deny_reason_code() {
        KillSwitchAdminController.RuleView view = controller.put(
                new KillSwitchAdminController.RuleBody("/ClientPlayerChat/*", true, "止血", null), request);
        assertThat(view.pattern()).isEqualTo("ClientPlayerChat/*");
        verify(map).fastPut("ClientPlayerChat/*", "{\"deny\":true,\"reason\":\"止血\"}");
        controller.put(new KillSwitchAdminController.RuleBody("ClientPlayerChat/PullChatHistory", false, null, null), request);
        verify(map).fastPut("ClientPlayerChat/PullChatHistory", "{\"deny\":false}");
        controller.put(new KillSwitchAdminController.RuleBody("*", true, "", 9), request);
        verify(map).fastPut("*", "{\"deny\":true,\"code\":9}");

        assertThatThrownBy(() -> controller.put(new KillSwitchAdminController.RuleBody("*", null, null, null), request))
                .hasMessageContaining("deny");
        assertThatThrownBy(() -> controller.put(new KillSwitchAdminController.RuleBody("*", true, null, 17), request))
                .hasMessageContaining("code");
        assertThatThrownBy(() -> controller.put(new KillSwitchAdminController.RuleBody("*", true, "a\u0000b", null), request))
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> controller.put(new KillSwitchAdminController.RuleBody("*", true, "x".repeat(257), null),
                request)).hasMessageContaining("reason");
        verify(map, times(3)).fastPut(anyString(), any());
    }

    @Test
    void 列表按键排序_标出写坏的值() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("b/*", "关掉它");
        raw.put("a/M", "{\"deny\":true,\"reason\":\"r\",\"code\":9}");
        raw.put("/c/*", "true");
        when(map.readAllMap()).thenReturn(raw);
        List<KillSwitchAdminController.RuleView> views = controller.list();
        assertThat(views).extracting(KillSwitchAdminController.RuleView::field).containsExactly("/c/*", "a/M", "b/*");
        assertThat(views.get(0).pattern()).as("手写字段带开头斜杠：pattern 给规范形").isEqualTo("c/*");
        assertThat(views.get(1)).isEqualTo(new KillSwitchAdminController.RuleView("a/M", "a/M", true, true, "r", 9, raw.get("a/M")));
        assertThat(views.get(2).valid()).isFalse();
        assertThat(views.get(2).raw()).isEqualTo("关掉它");
    }

    @Test
    void 删除_规范化后同名的原始字段全删_没有也成功_Redis不可用503() {
        when(map.readAllKeySet()).thenReturn(Set.of("ClientPlayerChat/*", "/ClientPlayerChat/*", " ClientPlayerChat/* ",
                "ClientPlayerChat/SendChat"));
        KillSwitchAdminController.RemoveResult removed = controller.delete("//ClientPlayerChat/*", request);
        assertThat(removed.pattern()).isEqualTo("ClientPlayerChat/*");
        assertThat(removed.removedFields()).containsExactly(" ClientPlayerChat/* ", "/ClientPlayerChat/*", "ClientPlayerChat/*");
        verify(map).fastRemove(" ClientPlayerChat/* ", "/ClientPlayerChat/*", "ClientPlayerChat/*");

        assertThat(controller.delete("Nothing/*", request).removedFields()).isEmpty();
        when(map.readAllMap()).thenThrow(new RedisConnectionException("down"));
        assertThatThrownBy(controller::list).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
    }
}
