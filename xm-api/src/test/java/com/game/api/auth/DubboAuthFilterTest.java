package com.game.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.ClientMessageService;
import com.game.common.token.DubboCallAuth;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 调用方 / 提供方过滤器成对工作：调用方签的附件提供方验得过；没带、带错、过期一律拒绝且不进入业务。 */
class DubboAuthFilterTest {

    private static final long NOW = 1_800_000_000L;
    private final DubboCallAuth auth = DubboCallAuth.ofUtf8("dubbo-secret");
    private final AtomicLong clock = new AtomicLong(NOW);
    private final DubboAuthConsumerFilter consumer = new DubboAuthConsumerFilter(auth, clock::get);
    private final DubboAuthProviderFilter provider = new DubboAuthProviderFilter(auth, clock::get);

    private Invoker<ClientMessageService> business;
    private final Result ok = new AppResponse("ok");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        business = mock(Invoker.class);
        when(business.getInterface()).thenReturn(ClientMessageService.class);
        when(business.invoke(any())).thenReturn(ok);
    }

    private static RpcInvocation call(String method) {
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName(method);
        return invocation;
    }

    @Test
    void 调用方签的附件_提供方验证通过并进入业务() {
        RpcInvocation invocation = call("handle");
        consumer.invoke(business, invocation);

        assertThat(invocation.getAttachment(DubboCallAuth.TIMESTAMP_KEY)).isEqualTo(Long.toString(NOW));
        assertThat(invocation.getAttachment(DubboCallAuth.MAC_KEY)).matches("[0-9a-f]{64}");

        assertThat(provider.invoke(business, invocation)).isSameAs(ok);
        verify(business, times(2)).invoke(invocation);
    }

    @Test
    void 没带附件的直连调用_拒绝且不进入业务() {
        RpcInvocation forged = call("handle");

        assertThatThrownBy(() -> provider.invoke(business, forged))
                .isInstanceOf(RpcException.class)
                .hasMessage(DubboAuthProviderFilter.REJECT_REASON)
                .satisfies(e -> assertThat(((RpcException) e).getCode()).isEqualTo(RpcException.AUTHORIZATION_EXCEPTION));
        verify(business, never()).invoke(any());
    }

    @Test
    void 密钥不对或附件挪到别的方法上_拒绝() {
        RpcInvocation signedElsewhere = call("sessionClosed");
        new DubboAuthConsumerFilter(DubboCallAuth.ofUtf8("wrong"), clock::get).invoke(business, signedElsewhere);
        assertThatThrownBy(() -> provider.invoke(business, signedElsewhere)).isInstanceOf(RpcException.class);

        RpcInvocation moved = call("sessionClosed");
        consumer.invoke(business, moved);
        moved.setMethodName("handle");
        assertThatThrownBy(() -> provider.invoke(business, moved)).isInstanceOf(RpcException.class);
    }

    @Test
    void 附件超过60秒_拒绝() {
        RpcInvocation old = call("handle");
        consumer.invoke(business, old);
        clock.addAndGet(DubboCallAuth.MAX_CLOCK_SKEW_SECONDS + 1);

        assertThatThrownBy(() -> provider.invoke(business, old)).isInstanceOf(RpcException.class);
    }

    @Test
    void Dubbo内置服务不校验() {
        @SuppressWarnings("unchecked")
        Invoker<Runnable> builtin = mock(Invoker.class);
        when(builtin.getInterface()).thenReturn(Runnable.class);
        when(builtin.invoke(any())).thenReturn(ok);
        RpcInvocation invocation = call("run");

        consumer.invoke(builtin, invocation);
        assertThat(invocation.getAttachment(DubboCallAuth.MAC_KEY)).isNull();
        assertThat(provider.invoke(builtin, invocation)).isSameAs(ok);
    }
}
