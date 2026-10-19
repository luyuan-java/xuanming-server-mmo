package com.game.inbox;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 玩家持久收件箱 v1（GM 处置域）进程（mmorpg {@code go/player_inbox} 的 Java 版；设计 {@code docs/design/player-inbox-service.md} §12）。
 * 全服无状态的 Dubbo 提供方（group inbox：scene 拉取 / 确认）+ 管理端口上的运维接口；后台循环只在领导者上跑。
 * 玩家表只读、不起 MyBatis，所以排除 game-player-store 的自动装配。
 */
@SpringBootApplication(excludeName = "com.game.player.store.PlayerStoreAutoConfiguration")
@EnableConfigurationProperties(InboxProperties.class)
@EnableDubbo(scanBasePackages = "com.game.inbox.rpc")
public class InboxApplication {

    public static void main(String[] args) {
        System.setProperty("dubbo.shutdownHook.listenIgnore", "true");
        SpringApplication.run(InboxApplication.class, args);
    }
}
