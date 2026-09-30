package com.game.login;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * xm-login 进程入口：非 Web 的 Spring Boot 应用，对外提供 Dubbo 服务
 * {@code ClientMessageService}（group {@code login}，Triple，默认端口 20881），并引用 scene-manager 的
 * {@code SceneDirectoryService}。
 *
 * <p>启动顺序即接客前提：配置表同步加载并自检 → 建表脚本（spring.sql.init）→ 节点号租约 → Dubbo 暴露服务。
 * 任何一步失败进程都起不来（fail-fast），不会带着坏配置对外服务。
 *
 * <p>进程保活靠 {@code spring.main.keep-alive=true}；SIGTERM / Ctrl+C 时 Spring 关闭钩子先让 Dubbo 注销并
 * 等在途调用，再按依赖逆序关闭工作线程池与节点号租约。约定从仓库根目录启动。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.login")
public class LoginApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoginApplication.class, args);
    }
}
