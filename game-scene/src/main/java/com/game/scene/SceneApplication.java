package com.game.scene;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 场景节点进程入口。gate 经节点链路（Netty，默认端口 21000）连进来；帮会 / 交易经资产通道的 Dubbo Triple 提供方（默认端口 21100，
 * 按节点直连、不进注册中心）调进来；另有一个只挂 actuator 的管理端口（默认 127.0.0.1:18104，health / prometheus，外加只收本机请求的
 * GM 签名停机 /gm/**），没有业务 HTTP 接口。从仓库根目录启动（配置表默认读 {@code config-data/tables}）。
 */
@SpringBootApplication
public class SceneApplication {

    /**
     * 不让 Dubbo 往 JVM 注册它自己的关停钩子：资产通道提供方的反导出排在 {@code SceneNode} 的停服顺序里（摘目录之后、逻辑线程停之前），
     * 由 Spring 的关停钩子驱动；Dubbo 的钩子会与它并行、一开始就把端口置只读并销毁提供方。本进程只有这一个 Dubbo 应用，系统属性只影响它。
     */
    static final String DUBBO_SHUTDOWN_HOOK_IGNORE = "dubbo.shutdownHook.listenIgnore";

    public static void main(String[] args) {
        System.setProperty(DUBBO_SHUTDOWN_HOOK_IGNORE, "true");
        SpringApplication.run(SceneApplication.class, args);
    }
}
