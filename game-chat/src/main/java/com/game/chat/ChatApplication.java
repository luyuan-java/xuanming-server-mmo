package com.game.chat;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * game-chat 进程入口：对外提供 Dubbo 服务 {@code ClientMessageService}（group {@code chat}，Triple，默认端口 20884），
 * gate 把 {@code ClientPlayerChat} 的消息号转到这里。全服可多副本；聊天原件、永久幂等和强读历史在同归属SQL域。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.chat")
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
