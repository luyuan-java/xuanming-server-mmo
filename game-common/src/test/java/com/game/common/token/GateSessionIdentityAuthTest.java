package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.player.PlayerSessionAuthority;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-11/12 gate Ed25519 会话身份签名、密钥轮换、配对拒启及只读归属核对。 */
class GateSessionIdentityAuthTest {

    @Test
    void Ed25519仅gate持有私钥_验证方只持公钥无法签发_支持轮换期新旧并列_配对错误拒启() {
        KeyPair oldPair = GateSessionIdentityAuth.generateKeyPair();
        KeyPair newPair = GateSessionIdentityAuth.generateKeyPair();
        KeyPair roguePair = GateSessionIdentityAuth.generateKeyPair();

        String oldPriv = GateSessionIdentityAuth.encodePrivateKeyBase64(oldPair.getPrivate());
        String oldPub = GateSessionIdentityAuth.encodePublicKeyBase64(oldPair.getPublic());
        String newPriv = GateSessionIdentityAuth.encodePrivateKeyBase64(newPair.getPrivate());
        String newPub = GateSessionIdentityAuth.encodePublicKeyBase64(newPair.getPublic());
        String roguePriv = GateSessionIdentityAuth.encodePrivateKeyBase64(roguePair.getPrivate());

        // 启动配对自检：私钥与公钥列表不配对直接拒启
        assertThatThrownBy(() -> GateSessionIdentityAuth.forSigner(roguePriv, oldPub + "," + newPub))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不配对");

        // 轮换期：验证方同时配置旧+新两把公钥，且只持公钥无法签名
        GateSessionIdentityAuth verifier = GateSessionIdentityAuth.forVerifier(oldPub + "," + newPub);
        assertThat(verifier.canSign()).isFalse();
        assertThat(verifier.publicKeyCount()).isEqualTo(2);
        byte[] payload = "gate-ed25519-v1|handle|1700000000|acc-1|1001|1".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.sign(payload)).isInstanceOf(UnsupportedOperationException.class);

        // 旧私钥与新私钥签出的声明均被验证方接受
        GateSessionIdentityAuth oldSigner = GateSessionIdentityAuth.forSigner(oldPriv, oldPub + "," + newPub);
        GateSessionIdentityAuth newSigner = GateSessionIdentityAuth.forSigner(newPriv, oldPub + "," + newPub);
        String oldSig = oldSigner.sign(payload);
        String newSig = newSigner.sign(payload);
        assertThat(verifier.verify(payload, "1700000000", oldSig, 1700000005L))
                .isEqualTo(GateSessionIdentityAuth.Verdict.OK);
        assertThat(verifier.verify(payload, "1700000000", newSig, 1700000005L))
                .isEqualTo(GateSessionIdentityAuth.Verdict.OK);

        // 篡改载荷（例如伪造 player_id）被拒；时间窗过期被拒；第三把未授权私钥伪造被拒
        byte[] tampered = "gate-ed25519-v1|handle|1700000000|acc-1|9999|1".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(tampered, "1700000000", newSig, 1700000005L))
                .isEqualTo(GateSessionIdentityAuth.Verdict.INVALID);
        assertThat(verifier.verify(payload, "1700000000", newSig, 1700000120L))
                .isEqualTo(GateSessionIdentityAuth.Verdict.EXPIRED);
        String rogueSig = GateSessionIdentityAuth.forSigner(roguePriv, "").sign(payload);
        assertThat(verifier.verify(payload, "1700000000", rogueSig, 1700000005L))
                .isEqualTo(GateSessionIdentityAuth.Verdict.INVALID);

        // 下线旧公钥后只认新公钥
        GateSessionIdentityAuth rotatedVerifier = GateSessionIdentityAuth.forVerifier(newPub);
        assertThat(rotatedVerifier.verify(payload, "1700000000", oldSig, 1700000005L))
                .isEqualTo(GateSessionIdentityAuth.Verdict.INVALID);
        assertThat(rotatedVerifier.verify(payload, "1700000000", newSig, 1700000005L))
                .isEqualTo(GateSessionIdentityAuth.Verdict.OK);
    }

    @Test
    void verifyReadOnly允许正常与冻结角色_拒绝已过户与已删除角色() throws Exception {
        java.util.Map<Long, String[]> table = java.util.Map.of(
                1001L, new String[]{"acc-a", "1"}, // 正常角色
                1002L, new String[]{"acc-a", "1"}, // 冻结角色（查询不看 trade_listing_id，照常允许只读）
                1003L, new String[]{"acc-b", "2"}  // 已过户角色
        );
        java.util.concurrent.atomic.AtomicReference<String> lastSql = new java.util.concurrent.atomic.AtomicReference<>();
        Connection c = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        String sql = (String) args[0];
                        lastSql.set(sql);
                        java.util.concurrent.atomic.AtomicLong boundId = new java.util.concurrent.atomic.AtomicLong();
                        return java.lang.reflect.Proxy.newProxyInstance(
                                java.sql.PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{java.sql.PreparedStatement.class},
                                (p2, m2, a2) -> switch (m2.getName()) {
                                    case "setObject" -> {
                                        boundId.set(((Number) a2[1]).longValue());
                                        yield null;
                                    }
                                    case "executeQuery" -> {
                                        String[] row = table.get(boundId.get());
                                        java.util.concurrent.atomic.AtomicBoolean moved = new java.util.concurrent.atomic.AtomicBoolean();
                                        yield java.lang.reflect.Proxy.newProxyInstance(
                                                java.sql.ResultSet.class.getClassLoader(),
                                                new Class<?>[]{java.sql.ResultSet.class},
                                                (p3, m3, a3) -> switch (m3.getName()) {
                                                    case "next" -> row != null && moved.compareAndSet(false, true);
                                                    case "getString" -> row[((Integer) a3[0]) - 1];
                                                    case "close" -> null;
                                                    default -> null;
                                                });
                                    }
                                    case "setQueryTimeout", "close" -> null;
                                    default -> null;
                                });
                    }
                    return null;
                });

        assertThat(PlayerSessionAuthority.verifyReadOnly(c, 1001L, "acc-a", 1L, 3)).isTrue();
        assertThat(lastSql.get()).doesNotContain("FOR UPDATE").doesNotContain("trade_listing_id");
        assertThat(PlayerSessionAuthority.verifyReadOnly(c, 1002L, "acc-a", 1L, 3)).isTrue();
        assertThat(PlayerSessionAuthority.verifyReadOnly(c, 1003L, "acc-a", 1L, 3)).isFalse();
        assertThat(PlayerSessionAuthority.verifyReadOnly(c, 1003L, "acc-b", 1L, 3)).isFalse();
        assertThat(PlayerSessionAuthority.verifyReadOnly(c, 1003L, "acc-b", 2L, 3)).isTrue();
        assertThat(PlayerSessionAuthority.verifyReadOnly(c, 9999L, "acc-a", 1L, 3)).isFalse();
    }
}
