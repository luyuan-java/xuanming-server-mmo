package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class MallNonceStoreTest {

    @Test
    void 过期nonce按单条有界DELETE每次最多清128行_未过期行不受影响() {
        try (var db = new MallTestDatabase()) {
            var nonces = new MallNonceStore(db.jdbc, new DataSourceTransactionManager(db.source), MallTestDatabase.properties());
            long now = System.currentTimeMillis();
            for (int i = 0; i < 150; i++) {
                db.jdbc.update("INSERT INTO commerce_request_nonce(caller_role,nonce,expires_ms) VALUES (?,?,?)",
                        "gateway", ("expired-" + i).getBytes(StandardCharsets.US_ASCII), 1000L + i);
            }
            for (int i = 0; i < 5; i++) {
                db.jdbc.update("INSERT INTO commerce_request_nonce(caller_role,nonce,expires_ms) VALUES (?,?,?)",
                        "gateway", ("fresh-" + i).getBytes(StandardCharsets.US_ASCII), now + 600_000L + i);
            }

            // 第 0 次调用触发有界清理（删 128 条过期行）并插入 1 条新 nonce
            nonces.claim("gateway", "live-0", now);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_request_nonce WHERE expires_ms<?", Integer.class, now))
                    .isEqualTo(22);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_request_nonce WHERE expires_ms>=?", Integer.class, now))
                    .isEqualTo(6);

            // 重放被拒 409，超窗被拒 401
            assertThatThrownBy(() -> nonces.claim("gateway", "live-0", now))
                    .isInstanceOfSatisfying(MallException.class, e -> assertThat(e.status()).isEqualTo(409));
            assertThatThrownBy(() -> nonces.claim("gateway", "stale-ts", now - 600_000L))
                    .isInstanceOfSatisfying(MallException.class, e -> assertThat(e.status()).isEqualTo(401));

            // 推进到第 64 次调用（前 3 次调用已消费 0、1、2，再调 62 次到达 64）触发下一轮清理剩余 22 条过期行
            for (int i = 3; i <= 64; i++) {
                nonces.claim("gateway", "live-" + i, now);
            }
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_request_nonce WHERE expires_ms<?", Integer.class, now))
                    .isZero();
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_request_nonce WHERE expires_ms>=?", Integer.class, now))
                    .isEqualTo(68);
        }
    }
}
