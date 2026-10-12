package com.game.discovery.namespace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;

/**
 * 部署命名空间拒启的标准报告：分析器给出带原值与来源的 Description、写合法取值的 Action；经 SpringApplication 启动时在 refresh 之前失败
 * （没有任何 bean 被创建），输出是 Spring Boot 的 APPLICATION FAILED TO START 格式。缺省 JVM（已定格为 game）。
 */
@ExtendWith(OutputCaptureExtension.class)
class GameNamespaceFailureAnalyzerTest {

    @Test
    void 非法值_描述带原值与来源_动作写合法取值() {
        InvalidGameNamespaceException e = new InvalidGameNamespaceException("Bad", "commandLineArgs",
                new IllegalArgumentException("部署命名空间 game.namespace（环境变量 GAME_NAMESPACE）的取值 \"Bad\"（来源 Spring Environment（commandLineArgs））不合法"));
        FailureAnalysis analysis = new GameNamespaceFailureAnalyzer().analyze(e);

        assertThat(analysis.getDescription()).contains("\"Bad\"").contains("commandLineArgs");
        assertThat(analysis.getAction()).contains("[a-z][a-z0-9]{0,31}").contains("mysql / sys / public").contains("GAME_NAMESPACE")
                .contains("${GAME_NAMESPACE:-game}");
        assertThat(analysis.getCause()).isSameAs(e);
    }

    @Test
    void 冲突_动作写所有入口同值与main的约束() {
        InvalidGameNamespaceException e = new InvalidGameNamespaceException("t1", "systemEnvironment",
                new IllegalStateException("已定格为 \"game\""));
        assertThat(e.isConflict()).isTrue();
        assertThat(new GameNamespaceFailureAnalyzer().analyze(e).getAction()).contains("SpringApplication.run").contains("GAME_NAMESPACE");
    }

    static final AtomicBoolean BEAN_CREATED = new AtomicBoolean();

    /** 有一个记录是否被创建的 bean：拒启发生在 refresh 之前，它不该被创建。 */
    @SpringBootConfiguration
    static class App {
        @Bean
        Object probe() {
            BEAN_CREATED.set(true);
            return new Object();
        }
    }

    @Test
    void 启动即失败_在建bean之前_输出标准格式(CapturedOutput output) {
        SpringApplication app = new SpringApplication(App.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setRegisterShutdownHook(false);
        BEAN_CREATED.set(false);

        assertThatThrownBy(() -> app.run("--game.namespace=Bad")).isInstanceOf(InvalidGameNamespaceException.class)
                .hasMessageContaining("\"Bad\"").hasMessageContaining("commandLineArgs");

        assertThat(BEAN_CREATED).as("拒启发生在 prepareEnvironment，没有任何 bean 被创建").isFalse();
        assertThat(output).contains("APPLICATION FAILED TO START").contains("game.namespace").contains("\"Bad\"")
                .contains("[a-z][a-z0-9]{0,31}");
    }
}
