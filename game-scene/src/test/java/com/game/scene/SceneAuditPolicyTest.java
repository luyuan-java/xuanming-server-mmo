package com.game.scene;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.audit.AuditProperties;
import com.game.audit.AuditTopicContractException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 真实装配入口在创建节点、触碰存储与网络前拒绝弱生产审计配置。 */
class SceneAuditPolicyTest {
    @Test void 生产与未知模式都不能装配单副本Kafka审计() {
        for (String mode : new String[]{"prod", "mistyped"}) {
            var props = mock(SceneNodeProperties.class);
            when(props.runMode()).thenReturn(mode);
            var audit = new AuditProperties(true, "127.0.0.1:1", 1, (short) 1, Duration.ofSeconds(1));
            assertThatThrownBy(() -> new SceneNodeConfiguration().sceneNode(props,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    audit, null, null, null, null, null, null, null))
                    .isInstanceOf(AuditTopicContractException.class).hasMessageContaining("生产审计");
        }
    }
}
