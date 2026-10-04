package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Argon2id 的 PHC 串格式、参数策略与 KDF 本身（参考实现的已知向量）。 */
class Argon2idTest {

    @Test
    void KDF与参考实现的已知向量一致() {
        // phc-winner-argon2 src/test.c：argon2id v=19, t=2, m=2^16 KiB, p=1, password "password", salt "somesalt"
        byte[] out = Argon2id.derive("password".getBytes(StandardCharsets.UTF_8),
                "somesalt".getBytes(StandardCharsets.UTF_8), 65536, 2, 1, 32);
        assertThat(HexFormat.of().formatHex(out))
                .isEqualTo("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7");
        assertThat(Base64.getEncoder().withoutPadding().encodeToString(out))
                .isEqualTo("CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8ArZWb2GRPPc");
    }

    @Test
    void 生成的串形如基线_参数m64MiB_t3_p2_往返校验() {
        String encoded = Argon2id.hash("口令 123", new SecureRandom());
        assertThat(encoded).matches("\\$argon2id\\$v=19\\$m=65536,t=3,p=2\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
        assertThat(Argon2id.verify("口令 123", encoded)).isTrue();
        assertThat(Argon2id.verify("口令 124", encoded)).isFalse();
        assertThat(Argon2id.hash("口令 123", new SecureRandom())).as("每次换盐").isNotEqualTo(encoded);
    }

    @Test
    void 口令为空或超过1024字节拒绝生成() {
        assertThatThrownBy(() -> Argon2id.hash("", new SecureRandom())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Argon2id.hash("a".repeat(1025), new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Argon2id.hash("汉".repeat(342), new SecureRandom())).as("按字节算：342 × 3 = 1026")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 解析拒绝_结构版本参数重复缺失越界_salt与digest长度_带填充() {
        String salt = Base64.getEncoder().withoutPadding().encodeToString(new byte[16]);
        String digest = Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
        String ok = "$argon2id$v=19$m=65536,t=3,p=2$" + salt + "$" + digest;
        assertThat(Argon2id.parse(ok).memoryKib()).isEqualTo(65536);
        // 同 Go ParseUint：前导 0 接受
        assertThat(Argon2id.parse("$argon2id$v=19$m=000000065536,t=03,p=2$" + salt + "$" + digest).iterations())
                .isEqualTo(3);
        String[] bad = {
                "$argon2id$v=19$m=٦٥٥٣٦,t=3,p=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=3,p=-0$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=,p=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=99999999999999999999999,t=3,p=2$" + salt + "$" + digest,
                "argon2id$v=19$m=65536,t=3,p=2$" + salt + "$" + digest,
                "$argon2i$v=19$m=65536,t=3,p=2$" + salt + "$" + digest,
                "$argon2id$v=16$m=65536,t=3,p=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=3$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,m=65536,p=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=3,x=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=19455,t=3,p=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=262145,t=3,p=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=1,p=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=11,p=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=3,p=0$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=3,p=17$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=3,p=+2$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=-3,p=2$" + salt + "$" + digest,
                "$argon2id$v=19$m=65536,t=3,p=2$" + "AAAA" + "$" + digest,
                "$argon2id$v=19$m=65536,t=3,p=2$" + salt + "$" + Base64.getEncoder().withoutPadding()
                        .encodeToString(new byte[65]),
                "$argon2id$v=19$m=65536,t=3,p=2$" + Base64.getEncoder().encodeToString(new byte[17]) + "$" + digest,
                "$argon2id$v=19$m=65536,t=3,p=2$" + salt + "$" + digest + "$extra",
                "$argon2id$v=19$m=65536,t=3,p=2$" + salt + "$!!notbase64!!",
        };
        for (String encoded : bad) {
            assertThatThrownBy(() -> Argon2id.parse(encoded)).as(encoded).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
