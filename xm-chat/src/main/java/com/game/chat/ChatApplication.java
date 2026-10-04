package com.game.chat;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * xm-chat 进程入口：对外提供 Dubbo 服务 {@code ClientMessageService}（group {@code chat}，Triple，默认端口 20884），
 * gate 把 {@code ClientPlayerChat} 的消息号转到这里。全服一份、可多副本、进程无状态（历史与幂等键都在 Redis）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.chat")
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
