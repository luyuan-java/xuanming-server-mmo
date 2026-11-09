package com.game.gateway.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HotfixConfigurationTest {
    private static final String PREFIX = "game.gateway.hotfix.platforms.android.";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HotfixConfiguration.class);

    @Test
    void 未配置发布线也装配正式服务() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(HotfixCheckService.class);
            assertThat(context.getBean(HotfixProperties.class).platforms()).isEmpty();
        });
    }

    @Test
    void 绑定不可变发布线_不配强更线合法() {
        runner.withPropertyValues(PREFIX + "latest-version=1.2.0", PREFIX + "patch-url=https://cdn.example.invalid/a")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var response = context.getBean(HotfixCheckService.class).check(new HotfixCheckRequest("1", "android"));
                    assertThat(response.needUpdate()).isTrue();
                    assertThat(response.forceUpdate()).isFalse();
                    assertThat(response.latestVersion()).isEqualTo("1.2.0");
                    assertThat(response.changelog()).isNull();
                });
    }

    @Test
    void 无效最新版本或补丁地址让启动失败() {
        for (String latest : new String[] {"", "v1", "1.2.3.4.5", "1000000000"}) {
            runner.withPropertyValues(PREFIX + "latest-version=" + latest, PREFIX + "patch-url=https://cdn.example.invalid/a")
                    .run(context -> assertThat(context).hasFailed());
        }
        runner.withPropertyValues(PREFIX + "patch-url=https://cdn.example.invalid/a")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(PREFIX + "latest-version=1")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(PREFIX + "latest-version=1", PREFIX + "patch-url= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 强更线必须合法且不超过最新版本() {
        for (String minimum : new String[] {"", " ", "1-beta", "1.2.1", "2"}) {
            runner.withPropertyValues(PREFIX + "latest-version=1.2", PREFIX + "min-version=" + minimum,
                            PREFIX + "patch-url=https://cdn.example.invalid/a")
                    .run(context -> assertThat(context).hasFailed());
        }
        runner.withPropertyValues(PREFIX + "latest-version=1.2", PREFIX + "min-version=1.2.0",
                        PREFIX + "patch-url=https://cdn.example.invalid/a")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 不可寻址的空平台配置拒绝() {
        var release = new HotfixProperties.Release("1", null, "https://cdn.example.invalid/a", null);
        assertThatThrownBy(() -> new HotfixProperties(Map.of(" ", release)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("平台名");
    }
}
