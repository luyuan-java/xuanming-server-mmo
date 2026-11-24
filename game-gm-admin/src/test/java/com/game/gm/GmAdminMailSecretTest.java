package com.game.gm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 核验 GM 后台优先读取与 game-mail 对齐的 MAIL_PRODUCER_SECRET_ADMIN，兼容回退旧名。 */
class GmAdminMailSecretTest {

    @Test
    void 优先读标准环境变量MAIL_PRODUCER_SECRET_ADMIN并兼容旧变量() {
        assertThat(GmAdminConfiguration.resolveMailAdminSecret(Map.of(
                "MAIL_PRODUCER_SECRET_ADMIN", " canonical-secret ",
                "MMORPG_MAIL_ADMIN_SECRET", "legacy-secret")::get))
                .isEqualTo("canonical-secret");
        assertThat(GmAdminConfiguration.resolveMailAdminSecret(Map.of(
                "MMORPG_MAIL_ADMIN_SECRET", " legacy-only ")::get))
                .isEqualTo("legacy-only");
        assertThat(GmAdminConfiguration.resolveMailAdminSecret(k -> null)).isEmpty();
    }
}
