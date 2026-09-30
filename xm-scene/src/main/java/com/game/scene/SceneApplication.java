package com.game.scene;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 场景节点进程入口。非 Web 应用；gate 经节点链路（Netty，默认端口 21000）连进来。
 * 从仓库根目录启动（配置表默认读 {@code config-data/tables}）。
 */
@SpringBootApplication
public class SceneApplication {

    public static void main(String[] args) {
        SpringApplication.run(SceneApplication.class, args);
    }
}
