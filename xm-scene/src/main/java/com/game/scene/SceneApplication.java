package com.game.scene;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 场景节点进程入口。gate 经节点链路（Netty，默认端口 21000）连进来；另有一个只挂 actuator 的管理端口
 * （默认 127.0.0.1:18104，health / prometheus），没有业务 HTTP 接口。
 * 从仓库根目录启动（配置表默认读 {@code config-data/tables}）。
 */
@SpringBootApplication
public class SceneApplication {

    public static void main(String[] args) {
        SpringApplication.run(SceneApplication.class, args);
    }
}
