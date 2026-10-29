package com.game.data;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.audit.AuditProperties;
import com.game.audit.AuditTopicContractException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 审计生产开关不控制data消费者，真实工厂必须独立校验其生产持久性。 */
class DataAuditPolicyTest {
    @Test void 生产消费者不因scene生产开关关闭而接受弱副本() {
        for (boolean enabled : new boolean[]{true, false}) {
            var audit = new AuditProperties(enabled, "127.0.0.1:1", 1, (short) 1, Duration.ofSeconds(1));
            assertThatThrownBy(() -> new DataConfiguration().dataNode(audit,
                    null, null, null, null, null, null, null, "prod"))
                    .isInstanceOf(AuditTopicContractException.class).hasMessageContaining("生产审计");
        }
    }
}
