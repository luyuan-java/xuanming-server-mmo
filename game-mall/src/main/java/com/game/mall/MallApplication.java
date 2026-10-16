package com.game.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 本版本独立商城进程；玩家资产由既有权威通道负责，本批只推进到待交付。 */
@SpringBootApplication
@EnableConfigurationProperties(MallProperties.class)
public class MallApplication {
    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
