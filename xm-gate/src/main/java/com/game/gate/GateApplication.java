package com.game.gate;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * xm-gate：客户端接入进程。Netty 客户端端口 + 到 scene 的节点链路 + Dubbo 消费 login。
 * 从仓库根目录启动：{@code java -jar xm-gate/target/xm-gate-0.1.0-SNAPSHOT.jar}。
 */
@SpringBootApplication
@EnableDubbo
@EnableConfigurationProperties(GateProperties.class)
public class GateApplication {

    public static void main(String[] args) {
        SpringApplication.run(GateApplication.class, args);
    }
}
