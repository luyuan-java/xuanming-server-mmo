package com.game.api.auth;

import com.game.common.token.DubboCallAuth;
import com.game.common.token.GateSessionIdentityAuth;
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
 * （{@link DubboCallAuth#TIMESTAMP_KEY} / {@link DubboCallAuth#MAC_KEY}）；
 * 当调用 {@code ClientMessageService} 且本进程持有 gate 专属 Ed25519 私钥（{@link GateSessionIdentityAuth#PRIVATE_KEY_ENV}）时，
 * 同步附加非对称会话身份签名（{@link GateSessionIdentityAuth#TIMESTAMP_KEY} / {@link GateSessionIdentityAuth#SIGNATURE_KEY}）。
 */
@Activate(group = CommonConstants.CONSUMER, order = -9000)
public final class DubboAuthConsumerFilter implements Filter {

    private final DubboCallAuth auth;
    private final GateSessionIdentityAuth gateSigner;
    private final LongSupplier epochSeconds;

    /** SPI 用：密钥取环境变量 {@link DubboCallAuth#SECRET_ENV}，gate 私钥（可选）取 {@link GateSessionIdentityAuth#PRIVATE_KEY_ENV}。 */
    public DubboAuthConsumerFilter() {
        this(DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV)),
                loadGateSignerFromEnv(),
                () -> Instant.now().getEpochSecond());
    }

    DubboAuthConsumerFilter(DubboCallAuth auth, LongSupplier epochSeconds) {
        this(auth, null, epochSeconds);
    }

    DubboAuthConsumerFilter(DubboCallAuth auth, GateSessionIdentityAuth gateSigner, LongSupplier epochSeconds) {
        this.auth = auth;
        this.gateSigner = gateSigner;
        this.epochSeconds = epochSeconds;
    }

    private static GateSessionIdentityAuth loadGateSignerFromEnv() {
        String priv = System.getenv(GateSessionIdentityAuth.PRIVATE_KEY_ENV);
        if (priv == null || priv.isBlank()) {
            return null;
        }
        return GateSessionIdentityAuth.forSigner(priv, System.getenv(GateSessionIdentityAuth.PUBLIC_KEYS_ENV));
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String service = invoker.getInterface().getName();
        if (DubboAuthScope.covers(service)) {
            long now = epochSeconds.getAsLong();
            invocation.setAttachment(DubboCallAuth.TIMESTAMP_KEY, Long.toString(now));
            invocation.setAttachment(DubboCallAuth.MAC_KEY, auth.sign(service, invocation.getMethodName(), now));
            if (gateSigner != null && GateSessionPayload.isClientMessageService(service)) {
                byte[] payload = GateSessionPayload.build(invocation, now);
                invocation.setAttachment(GateSessionIdentityAuth.TIMESTAMP_KEY, Long.toString(now));
                invocation.setAttachment(GateSessionIdentityAuth.SIGNATURE_KEY, gateSigner.sign(payload));
            }
        }
        return invoker.invoke(invocation);
    }
}
