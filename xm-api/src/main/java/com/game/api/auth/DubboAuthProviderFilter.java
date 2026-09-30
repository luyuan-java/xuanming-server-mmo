package com.game.api.auth;

import com.game.common.token.DubboCallAuth;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dubbo 提供方过滤器：本仓库业务接口（{@code com.game.*}）的每次调用都必须带正确的鉴权附件，否则直接拒绝，
 * 不进入业务代码（fail-closed）。对外只回一句固定原因，不说缺附件、MAC 错还是时间戳过期（细节只进本地日志，且采样）。
 *
 * <p>Dubbo 内置服务（元数据、健康检查等，接口不在 {@code com.game.} 下）不校验：它们不改任何业务状态，
 * 且注册中心模式下由 Dubbo 框架自己在进程间调用。
 *
 * <p>经 Dubbo SPI 自动激活。密钥只从环境变量 {@link DubboCallAuth#SECRET_ENV} 读；缺失时本过滤器实例化失败，
 * 服务暴露不出来（fail-closed）。
 */
@Activate(group = CommonConstants.PROVIDER, order = -9000)
public final class DubboAuthProviderFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DubboAuthProviderFilter.class);

    /** 拒绝时给调用方的唯一原因。 */
    static final String REJECT_REASON = "调用方鉴权失败";

    private final DubboCallAuth auth;
    private final LongSupplier epochSeconds;
    private final AtomicLong rejections = new AtomicLong();

    /** SPI 用：密钥取环境变量 {@link DubboCallAuth#SECRET_ENV}。 */
    public DubboAuthProviderFilter() {
        this(DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV)),
                () -> Instant.now().getEpochSecond());
    }

    DubboAuthProviderFilter(DubboCallAuth auth, LongSupplier epochSeconds) {
        this.auth = auth;
        this.epochSeconds = epochSeconds;
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String service = invoker.getInterface().getName();
        if (DubboAuthScope.covers(service)) {
            DubboCallAuth.Verdict verdict = auth.verify(service, invocation.getMethodName(),
                    invocation.getAttachment(DubboCallAuth.TIMESTAMP_KEY), invocation.getAttachment(DubboCallAuth.MAC_KEY),
                    epochSeconds.getAsLong());
            if (verdict != DubboCallAuth.Verdict.OK) {
                logRejection(service, invocation.getMethodName(), verdict);
                throw new RpcException(RpcException.AUTHORIZATION_EXCEPTION, REJECT_REASON);
            }
        }
        return invoker.invoke(invocation);
    }

    /** 未鉴权流量由外部决定，逐条打 WARN 会被放大成日志 DoS：每 1024 次采样一条 WARN，其余 DEBUG。 */
    private void logRejection(String service, String method, DubboCallAuth.Verdict verdict) {
        long n = rejections.getAndIncrement();
        if ((n & 0x3FF) == 0) {
            log.warn("拒绝未通过鉴权的 Dubbo 调用（每 1024 次采样一条） service={} method={} 结果={} total={}",
                    service, method, verdict, n + 1);
        } else {
            log.debug("拒绝未通过鉴权的 Dubbo 调用 service={} method={} 结果={}", service, method, verdict);
        }
    }
}
