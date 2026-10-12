package com.game.api.auth;

import com.game.common.token.DubboCallAuth;
import java.time.Instant;
import java.util.function.LongSupplier;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * Dubbo 调用方过滤器：给本仓库业务接口（{@code com.game.*}）的每次调用带上鉴权附件
 * （{@link DubboCallAuth#TIMESTAMP_KEY} / {@link DubboCallAuth#MAC_KEY}，算法见 {@link DubboCallAuth}）。
 *
 * <p>经 Dubbo SPI（{@code META-INF/dubbo/org.apache.dubbo.rpc.Filter}）自动激活，不需要在引用上配置。
 * 密钥只从环境变量 {@link DubboCallAuth#SECRET_ENV} 读；缺失时本过滤器实例化失败，引用建不起来（fail-closed）。
 */
@Activate(group = CommonConstants.CONSUMER, order = -9000)
public final class DubboAuthConsumerFilter implements Filter {

    private final DubboCallAuth auth;
    private final LongSupplier epochSeconds;

    /** SPI 用：密钥取环境变量 {@link DubboCallAuth#SECRET_ENV}。 */
    public DubboAuthConsumerFilter() {
        this(DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV)),
                () -> Instant.now().getEpochSecond());
    }

    DubboAuthConsumerFilter(DubboCallAuth auth, LongSupplier epochSeconds) {
        this.auth = auth;
        this.epochSeconds = epochSeconds;
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String service = invoker.getInterface().getName();
        if (DubboAuthScope.covers(service)) {
            long now = epochSeconds.getAsLong();
            invocation.setAttachment(DubboCallAuth.TIMESTAMP_KEY, Long.toString(now));
            invocation.setAttachment(DubboCallAuth.MAC_KEY, auth.sign(service, invocation.getMethodName(), now));
        }
        return invoker.invoke(invocation);
    }
}
