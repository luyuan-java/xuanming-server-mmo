package com.game.scenemanager;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * xm-scene-manager 进程入口：非 Web 的 Spring Boot 应用，对外只提供 Dubbo 服务 {@code SceneDirectoryService}（Triple，默认端口 20882）。
 *
 * <p>进程保活靠 {@code spring.main.keep-alive=true}（Dubbo / Netty 线程都是守护线程）；
 * 收到 SIGTERM / Ctrl+C 时由 Spring 关闭钩子关闭上下文，Dubbo 随之注销并等待在途调用。
 * 约定从仓库根目录启动（配置表默认目录 {@code config-data/tables} 是相对路径）。
 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.game.scenemanager")
public class SceneManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SceneManagerApplication.class, args);
    }
}
