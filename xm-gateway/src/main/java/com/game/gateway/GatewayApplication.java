package com.game.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * xm-gateway 进程入口：客户端的 HTTP 入口（区服列表、分配 gate 并签发 gate 令牌）。
 *
 * <p>装配集中在 {@link GatewayConfiguration}；本类不挂任何功能注解，免得切片测试（{@code @WebMvcTest}）把它们带进去。
 * 进程须从仓库根目录启动（与其他模块的相对路径约定一致）。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
