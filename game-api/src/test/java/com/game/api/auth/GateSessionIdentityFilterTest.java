package com.game.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.ClientMessageService;
import com.game.api.proto.ClientCall;
import com.game.api.proto.SessionContext;
import com.game.common.token.DubboCallAuth;
import com.game.common.token.GateSessionIdentityAuth;
import java.security.KeyPair;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-11 gate Ed25519 会话身份签名在 Dubbo 过滤器链上的端到端行为。 */
class GateSessionIdentityFilterTest {

    private static final DubboCallAuth SHARED_DUBBO = DubboCallAuth.ofUtf8("0123456789abcdef0123456789abcdef");

    @Test
    void 仅gate私钥可签发ClientMessageService_验证方伪造被拒_轮换期新旧公钥均放行_篡改SessionContext被拒() {
        KeyPair oldPair = GateSessionIdentityAuth.generateKeyPair();
        KeyPair newPair = GateSessionIdentityAuth.generateKeyPair();
        String oldPriv = GateSessionIdentityAuth.encodePrivateKeyBase64(oldPair.getPrivate());
        String oldPub = GateSessionIdentityAuth.encodePublicKeyBase64(oldPair.getPublic());
        String newPriv = GateSessionIdentityAuth.encodePrivateKeyBase64(newPair.getPrivate());
        String newPub = GateSessionIdentityAuth.encodePublicKeyBase64(newPair.getPublic());

        long now = 1_700_000_000L;
        GateSessionIdentityAuth oldGate = GateSessionIdentityAuth.forSigner(oldPriv, oldPub + "," + newPub);
        GateSessionIdentityAuth newGate = GateSessionIdentityAuth.forSigner(newPriv, oldPub + "," + newPub);
        GateSessionIdentityAuth backendVerifier = GateSessionIdentityAuth.forVerifier(oldPub + "," + newPub);

        DubboAuthConsumerFilter oldGateConsumer = new DubboAuthConsumerFilter(SHARED_DUBBO, oldGate, () -> now);
        DubboAuthConsumerFilter newGateConsumer = new DubboAuthConsumerFilter(SHARED_DUBBO, newGate, () -> now);
        // 某在线服务只持有共享 Dubbo HMAC（没有 gate 私钥）企图伪造 ClientMessageService 调用
        DubboAuthConsumerFilter rogueBackendConsumer = new DubboAuthConsumerFilter(SHARED_DUBBO, null, () -> now);
        DubboAuthProviderFilter provider = new DubboAuthProviderFilter(SHARED_DUBBO, backendVerifier, () -> now + 2);

        ClientCall call = ClientCall.newBuilder()
                .setSession(SessionContext.newBuilder()
                        .setGateNodeId(1).setGateInstanceId("gate-a").setSessionId(42).setZoneId(1)
                        .setAccount("acc-1").setPlayerId(1001L).setOwnerRevision(3L).build())
                .setMessageId(200)
                .setRequestId(77L)
                .build();

        Invoker<ClientMessageService> downstream = stubInvoker(ClientMessageService.class, invocation -> new AppResponse("ok"));
        Invoker<ClientMessageService> providerInvoker = stubInvoker(ClientMessageService.class,
                invocation -> provider.invoke(downstream, invocation));

        // 1. 轮换期：旧 gate 与新 gate 签发的调用均放行
        assertThat(oldGateConsumer.invoke(providerInvoker, clientCallInvocation(call)).getValue()).isEqualTo("ok");
        assertThat(newGateConsumer.invoke(providerInvoker, clientCallInvocation(call)).getValue()).isEqualTo("ok");

        // 2. 验证方只持共享 HMAC（无 gate Ed25519 私钥）冒充 gate 被拒
        assertThatThrownBy(() -> rogueBackendConsumer.invoke(providerInvoker, clientCallInvocation(call)))
                .isInstanceOf(RpcException.class)
                .extracting(e -> ((RpcException) e).getCode())
                .isEqualTo(RpcException.AUTHORIZATION_EXCEPTION);

        // 3. 中间篡改 SessionContext（把 playerId 从 1001 改成 9999）被 Ed25519 验签拒绝
        RpcInvocation signed = clientCallInvocation(call);
        oldGateConsumer.invoke(stubInvoker(ClientMessageService.class, inv -> new AppResponse("captured")), signed);
        ClientCall tampered = call.toBuilder()
                .setSession(call.getSession().toBuilder().setPlayerId(9999L))
                .build();
        RpcInvocation tamperedInv = clientCallInvocation(tampered);
        tamperedInv.setObjectAttachments(signed.getObjectAttachments());
        assertThatThrownBy(() -> provider.invoke(downstream, tamperedInv))
                .isInstanceOf(RpcException.class)
                .extracting(e -> ((RpcException) e).getCode())
                .isEqualTo(RpcException.AUTHORIZATION_EXCEPTION);
    }

    private static RpcInvocation clientCallInvocation(ClientCall call) {
        return new RpcInvocation("handle", ClientMessageService.class.getName(), "",
                new Class<?>[]{ClientCall.class}, new Object[]{call});
    }

    private static <T> Invoker<T> stubInvoker(Class<T> iface, java.util.function.Function<Invocation, Result> handler) {
        return new Invoker<>() {
            @Override public Class<T> getInterface() { return iface; }
            @Override public Result invoke(Invocation invocation) { return handler.apply(invocation); }
            @Override public org.apache.dubbo.common.URL getUrl() { return null; }
            @Override public boolean isAvailable() { return true; }
            @Override public void destroy() {}
        };
    }
}
