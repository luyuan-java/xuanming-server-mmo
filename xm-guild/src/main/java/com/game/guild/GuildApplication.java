package com.game.guild;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * xm-guild 进程入口：对外提供 Dubbo 服务 {@code ClientMessageService}（group {@code guild}，Triple，默认端口 20886），gate 把
 * {@code guildpb.GuildService} 的全部 28 个消息号转到这里；同一端口上另有服务间内部查询 {@code GuildInternalService}（回档闸用，4.5 只交付提供方）。
 * 全服一份、可多副本、进程无状态（权威数据在 MySQL {@code xm_java} 的七张帮会表，缓存、排行与申请推送冷却在 Redis；只读
 * {@code xm_java.player} 取展示名与归属区、只读 {@code xm_java.player_state} 离线读已落盘账本）。
 *
 * <p>启动即接客前提（guild-spec §7.11、guild-economy-spec §7.7，照基线 guild.go:85-393）：配表校验（含经济表）→ xm-pbmysql 建表 →
 * 数据库版本 / 影响行数语义检查 → 全局插入守卫哨兵行 → 雪花租约（guild_id 与 op_id 共用，E6）→ 资产通道（开启时：交叉校验 → 签名器
 * （缺 {@code XM_ASSET_OP_SECRET_GUILD} 拒启）→ 定位 → 调用方 → 重投循环）→ 从 MySQL 全量重建排行 → 暴露 Dubbo → 启动重投循环与清理。
 * 任何一步失败进程都起不来；缺 {@code XM_DUBBO_SECRET} 拒绝启动。关闭时 Dubbo 先注销并等在途调用，再停重投循环与清理（等 worker 落库），
 * 再排空工作线程池与落库执行器，最后关资产通道客户端、后台调度器、租约、Redis 与连接池。
 *
 * <p>排除 {@code PlayerStoreAutoConfiguration}：本进程只用 xm-player-store 的已落盘账本只读件（用自己的数据源构造），不要它的 MyBatis
 * 与 {@code PlayerStore}（guild-economy-spec Q3a、§9.2 第 10 条）。人工终结 CLI 是同一个 jar 里的第二个主类
 * {@code com.game.guild.asset.fix.AssetOpFixMain}（不起 Spring / Dubbo）。
 */
@SpringBootApplication(excludeName = GuildApplication.PLAYER_STORE_AUTO_CONFIGURATION)
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.guild")
public class GuildApplication {

    /** 按名字排除（不 import：本进程排除了 MyBatis，那个类的注解引用的 MyBatis 类型在类路径上不存在）。 */
    static final String PLAYER_STORE_AUTO_CONFIGURATION = "com.game.player.store.PlayerStoreAutoConfiguration";

    /**
     * 关停只由 Spring 的关停钩子驱动（同 xm-scene 的 SceneApplication）：Dubbo 自己的 JVM 钩子会与它并行，一开始就把资产通道的独立
     * Dubbo 模块（{@code IsolatedDubboModule}）里的客户端引用销毁，打乱「先停重投循环、等 worker 落库，最后才关资产通道客户端」的顺序
     * （guild-economy-spec §7.7）。Spring 集成的 Dubbo 提供方照样随上下文关闭注销。
     */
    static final String DUBBO_SHUTDOWN_HOOK_IGNORE = "dubbo.shutdownHook.listenIgnore";

    public static void main(String[] args) {
        System.setProperty(DUBBO_SHUTDOWN_HOOK_IGNORE, "true");
        SpringApplication.run(GuildApplication.class, args);
    }
}
