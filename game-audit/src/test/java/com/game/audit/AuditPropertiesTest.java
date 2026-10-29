package com.game.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.RunMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AuditPropertiesTest {
    private AuditProperties bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("game.audit", AuditProperties.class);
    }
    @Test void 缺省配置保持本机单broker但生产明确拒绝() {
        var props = bind(Map.of());
        assertThat(props.replicationFactor()).isOne();
        assertThat(props.minInSyncReplicas()).isOne();
        assertThatCode(() -> props.validateFor(RunMode.DEV)).doesNotThrowAnyException();
        assertThatCode(() -> props.validateFor(RunMode.TEST)).doesNotThrowAnyException();
        assertThatThrownBy(() -> props.validateFor(RunMode.PROD)).isInstanceOf(AuditTopicContractException.class)
                .hasMessageContaining("GAME_AUDIT_REPLICATION_FACTOR").hasMessageContaining("GAME_AUDIT_MIN_IN_SYNC_REPLICAS");
    }
    @Test void 显式生产配置绑定且通过_只配副本不够() {
        var props = bind(Map.of("game.audit.replication-factor", 3, "game.audit.min-in-sync-replicas", 2));
        assertThatCode(() -> props.validateFor(RunMode.PROD)).doesNotThrowAnyException();
        assertThatThrownBy(() -> bind(Map.of("game.audit.replication-factor", 3)).validateFor(RunMode.PROD))
                .isInstanceOf(AuditTopicContractException.class);
    }
    @Test void 消费者不能借生产开关关闭绕过强门禁且非法组合绑定失败() {
        assertThatThrownBy(() -> bind(Map.of("game.audit.enabled", false)).validateFor(RunMode.PROD))
                .isInstanceOf(AuditTopicContractException.class);
        for (int value : new int[]{0, -1, 4}) assertThatThrownBy(() -> bind(Map.of(
                "game.audit.replication-factor", 3, "game.audit.min-in-sync-replicas", value)))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }
}
