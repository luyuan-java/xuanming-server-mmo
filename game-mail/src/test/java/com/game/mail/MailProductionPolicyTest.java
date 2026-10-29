package com.game.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 调用真实连接策略工厂，核对生产不可降级；不依赖外部 TiDB。 */
class MailProductionPolicyTest {
    @Test void 生产与默认及未知运行模式禁止关闭TiDB() {
        for (String mode : new String[]{"prod", "production", "mistyped", ""}) {
            var environment = new MockEnvironment().withProperty("game.mail.require-tidb", "false");
            if (!mode.isEmpty()) environment.withProperty("game.run-mode", mode);
            assertThatThrownBy(() -> MailConfiguration.connectionPolicy(environment))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("game.mail.require-tidb");
        }
    }

    @Test void 显式开发测试兼容开关与生产强读可以装配() {
        for (String mode : new String[]{"dev", "test"}) {
            var environment = new MockEnvironment().withProperty("game.mail.require-tidb", "false")
                    .withProperty("game.run-mode", mode);
            assertThat(MailConfiguration.connectionPolicy(environment)).isNotNull();
        }
        assertThat(MailConfiguration.connectionPolicy(new MockEnvironment())).isNotNull();
        assertThat(MailConfiguration.connectionPolicy(new MockEnvironment()
                .withProperty("game.run-mode", "prod").withProperty("game.mail.require-tidb", "true"))).isNotNull();
    }
}
