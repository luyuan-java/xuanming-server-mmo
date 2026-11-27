package com.game.gm;

import com.game.common.startup.ClockCheck;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GmAdminProperties.class)
public class GmAdminApplication {

    public static void main(String[] args) {
        ClockCheck.mustPass("game-gm-admin");
        SpringApplication.run(GmAdminApplication.class, args);
    }
}
