package com.game.guild;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * xm-guild 进程入口：对外提供 Dubbo 服务 {@code ClientMessageService}（group {@code guild}，Triple，默认端口 20886），gate 把
 * {@code guildpb.GuildService} 的全部 28 个消息号转到这里。全服一份、可多副本、进程无状态（权威数据在 MySQL {@code xm_java} 的四张帮会表，
 * 缓存、排行与申请推送冷却在 Redis；只读 {@code xm_java.player} 取展示名与归属区）。
 *
 * <p>启动即接客前提（guild-spec §7.11，照基线 guild.go:85-375）：配表校验 → xm-pbmysql 建表 / 只扩不缩地同步 → 数据库版本检查 →
 * 全局插入守卫哨兵行 → guild_id 雪花租约（{@code NodeTypes.GUILD}，作用域 0）→ 从 MySQL 全量重建排行 → 最后才暴露 Dubbo；
 * 任何一步失败进程都起不来；缺 {@code XM_DUBBO_SECRET} 拒绝启动。关闭时 Dubbo 先注销并等在途调用，再排空工作线程池，最后关后台调度器与租约。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.guild")
public class GuildApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuildApplication.class, args);
    }
}
