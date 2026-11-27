package com.game.api.auth;

import com.game.common.token.DubboCallAuth;
import com.game.common.token.GateSessionIdentityAuth;
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
 * Dubbo 提供方过滤器：本仓库业务接口（{@code com.game.*}）的每次调用都必须带正确的鉴权附件，否则直接拒绝（fail-closed）。
 * 对于 {@code ClientMessageService}，配置了 {@link GateSessionIdentityAuth#PUBLIC_KEYS_ENV}（或开启强制校验
 * {@link GateSessionIdentityAuth#REQUIRED_ENV}=true）时，强验 gate 专属 Ed25519 非对称会话身份签名（支持轮换期多公钥并列），
 * 验证方只持公钥，任何后端节点均无法冒充 gate 伪造 {@code SessionContext}。
 */
@Activate(group = CommonConstants.PROVIDER, order = -9000)
public final class DubboAuthProviderFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DubboAuthProviderFilter.class);

    /** 拒绝时给调用方的唯一原因。 */
    static final String REJECT_REASON = "调用方鉴权失败";

    private final DubboCallAuth auth;
    private final GateSessionIdentityAuth gateVerifier;
    private final LongSupplier epochSeconds;
    private final AtomicLong rejections = new AtomicLong();

    /** SPI 用：密钥取环境变量 {@link DubboCallAuth#SECRET_ENV}，gate 公钥列表取 {@link GateSessionIdentityAuth#PUBLIC_KEYS_ENV}。 */
    public DubboAuthProviderFilter() {
        this(DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV)),
                loadGateVerifierFromEnv(),
                () -> Instant.now().getEpochSecond());
    }

    DubboAuthProviderFilter(DubboCallAuth auth, LongSupplier epochSeconds) {
        this(auth, null, epochSeconds);
    }

    DubboAuthProviderFilter(DubboCallAuth auth, GateSessionIdentityAuth gateVerifier, LongSupplier epochSeconds) {
        this.auth = auth;
        this.gateVerifier = gateVerifier;
        this.epochSeconds = epochSeconds;
    }

    private static GateSessionIdentityAuth loadGateVerifierFromEnv() {
        String pubs = System.getenv(GateSessionIdentityAuth.PUBLIC_KEYS_ENV);
        boolean required = "true".equalsIgnoreCase(System.getenv(GateSessionIdentityAuth.REQUIRED_ENV));
        if (pubs == null || pubs.isBlank()) {
            if (required) {
                throw new IllegalStateException("已启用 " + GateSessionIdentityAuth.REQUIRED_ENV
                        + "=true，但未配置 " + GateSessionIdentityAuth.PUBLIC_KEYS_ENV);
            }
            return null;
        }
        return GateSessionIdentityAuth.forVerifier(pubs);
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String service = invoker.getInterface().getName();
        if (DubboAuthScope.covers(service)) {
            long now = epochSeconds.getAsLong();
            DubboCallAuth.Verdict verdict = auth.verify(service, invocation.getMethodName(),
                    invocation.getAttachment(DubboCallAuth.TIMESTAMP_KEY), invocation.getAttachment(DubboCallAuth.MAC_KEY),
                    now);
            if (verdict != DubboCallAuth.Verdict.OK) {
                logRejection(service, invocation.getMethodName(), verdict.name());
                throw new RpcException(RpcException.AUTHORIZATION_EXCEPTION, REJECT_REASON);
            }
            if (gateVerifier != null && GateSessionPayload.isClientMessageService(service)) {
                String tsHeader = invocation.getAttachment(GateSessionIdentityAuth.TIMESTAMP_KEY);
                String sigHeader = invocation.getAttachment(GateSessionIdentityAuth.SIGNATURE_KEY);
                long ts = parseTimestampOrZero(tsHeader);
                byte[] payload = GateSessionPayload.build(invocation, ts);
                GateSessionIdentityAuth.Verdict idVerdict = gateVerifier.verify(payload, tsHeader, sigHeader, now);
                if (idVerdict != GateSessionIdentityAuth.Verdict.OK) {
                    logRejection(service, invocation.getMethodName(), "GATE_ED25519_" + idVerdict);
                    throw new RpcException(RpcException.AUTHORIZATION_EXCEPTION, REJECT_REASON);
                }
            }
        }
        return invoker.invoke(invocation);
    }

    private static long parseTimestampOrZero(String tsHeader) {
        if (tsHeader == null || tsHeader.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(tsHeader.strip());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 未鉴权流量由外部决定，逐条打 WARN 会被放大成日志 DoS：每 1024 次采样一条 WARN，其余 DEBUG。 */
    private void logRejection(String service, String method, String verdict) {
        long n = rejections.getAndIncrement();
        if ((n & 0x3FF) == 0) {
            log.warn("拒绝未通过鉴权的 Dubbo 调用（每 1024 次采样一条） service={} method={} 结果={} total={}",
                    service, method, verdict, n + 1);
        } else {
            log.debug("拒绝未通过鉴权的 Dubbo 调用 service={} method={} 结果={}", service, method, verdict);
        }
    }
}
