package com.game.friend;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * xm-friend 进程入口：对外提供 Dubbo 服务 {@code ClientMessageService}（group {@code friend}，Triple，默认端口 20883），
 * gate 把 {@code ClientPlayerFriend} 的消息号转到这里。进程无状态、可多副本（权威数据在 MySQL，缓存与配额在 Redis）。
 *
 * <p>启动顺序即接客前提：MySQL 连接池（会话级 READ COMMITTED）→ xm-pbmysql 建表 / 只扩不缩地同步四张表 → Dubbo 暴露服务。
 * 任何一步失败进程都起不来。保活靠 {@code spring.main.keep-alive=true}；关闭时 Dubbo 先注销并等在途调用，再关工作线程池。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.friend")
public class FriendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FriendApplication.class, args);
    }
}
