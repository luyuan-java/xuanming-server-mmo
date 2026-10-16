package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.namespace.GameNamespaceAutoConfiguration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MallNamespaceTest {
    @Test
    void 非法namespace在打开商城连接之前拒绝() {
        AtomicBoolean opened = new AtomicBoolean();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                ValidationAutoConfiguration.class, GameNamespaceAutoConfiguration.class))
                .withPropertyValues("game.namespace=mysql")
                .withBean(DataSource.class, () -> { opened.set(true); throw new AssertionError("不应连接保留库"); })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(opened).isFalse();
                });
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                ValidationAutoConfiguration.class, GameNamespaceAutoConfiguration.class))
                .withPropertyValues("game.namespace=mallit20261015")
                .run(context -> assertThat(context.getBean(GameNamespace.class).value()).isEqualTo("mallit20261015"));
    }
}
