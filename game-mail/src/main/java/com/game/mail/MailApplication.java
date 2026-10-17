package com.game.mail;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** 全服无状态提供方，权威写在同一TiDB事务；后台投递按SQL意图恢复。 */
@SpringBootApplication(excludeName = "com.game.player.store.PlayerStoreAutoConfiguration")
@EnableDubbo(scanBasePackages = "com.game.mail")
public class MailApplication {
    public static void main(String[] args) {
        System.setProperty("dubbo.shutdownHook.listenIgnore", "true");
        SpringApplication.run(MailApplication.class, args);
    }
}
