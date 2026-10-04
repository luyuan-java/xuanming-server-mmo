package com.game.api.killswitch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.ClientMessageService;
import com.game.common.killswitch.KillSwitch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 提供方热关停：本仓库接口的方法命中规则即拒绝（不进业务、记一次短名），没装开关、没命中、非本仓库接口一律放行。 */
class KillSwitchProviderFilterTest {

    private final KillSwitchProviderFilter filter = new KillSwitchProviderFilter();
    private final Result ok = new AppResponse("ok");
    private final List<String> blocked = new ArrayList<>();
    private Invoker<ClientMessageService> business;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        business = mock(Invoker.class);
        when(business.getInterface()).thenReturn(ClientMessageService.class);
        when(business.invoke(any())).thenReturn(ok);
    }

    @AfterEach
    void uninstall() {
        KillSwitch.installGlobal(null);
    }

    private static RpcInvocation call(String method) {
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName(method);
        return invocation;
    }

    private void install(Map<String, KillSwitch.Rule> rules) {
        KillSwitch ks = new KillSwitch(-1, System::nanoTime);
        ks.setRules(rules);
        ks.onBlocked(blocked::add);
        KillSwitch.installGlobal(ks);
    }

    @Test
    void 没装开关放行() {
        assertThat(filter.invoke(business, call("handle"))).isSameAs(ok);
    }

    @Test
    void 命中全名或短名规则即拒绝_不进业务_记短名() {
        install(Map.of("com.game.api.ClientMessageService/sessionClosed", new KillSwitch.Rule(true, "止血", 0)));
        RpcInvocation invocation = call("sessionClosed");
        assertThatThrownBy(() -> filter.invoke(business, invocation)).isInstanceOf(RpcException.class)
                .hasMessageContaining("disabled").hasMessageContaining("reason: \\u6b62\\u8840")
                .satisfies(e -> assertThat(((RpcException) e).getCode()).isEqualTo(RpcException.FORBIDDEN_EXCEPTION));
        verify(business, never()).invoke(any());
        assertThat(blocked).containsExactly("ClientMessageService/sessionClosed");

        install(Map.of("ClientMessageService/*", new KillSwitch.Rule(true, "", 0)));
        assertThatThrownBy(() -> filter.invoke(business, call("abandonEnter"))).isInstanceOf(RpcException.class);
    }

    @Test
    void 客户端消息转发通道handle不在这里查_全局规则也不会把gate豁免的方法再拦一次() {
        install(Map.of("*", new KillSwitch.Rule(true, "", 0),
                "com.game.api.ClientMessageService/handle", new KillSwitch.Rule(true, "", 0)));
        assertThat(filter.invoke(business, call("handle"))).isSameAs(ok);
        assertThatThrownBy(() -> filter.invoke(business, call("sessionClosed"))).as("同接口的其他方法照查")
                .isInstanceOf(RpcException.class);
    }

    @Test
    void 没命中_精确豁免_非本仓库接口_都放行() {
        install(Map.of("*", new KillSwitch.Rule(true, "", 0),
                "ClientMessageService/sessionClosed", new KillSwitch.Rule(false, "", 0)));
        assertThat(filter.invoke(business, call("sessionClosed"))).isSameAs(ok);

        @SuppressWarnings("unchecked")
        Invoker<Runnable> foreign = mock(Invoker.class);
        when(foreign.getInterface()).thenReturn(Runnable.class);
        when(foreign.invoke(any())).thenReturn(ok);
        assertThat(filter.invoke(foreign, call("run"))).isSameAs(ok);
        assertThat(blocked).isEmpty();
    }
}
