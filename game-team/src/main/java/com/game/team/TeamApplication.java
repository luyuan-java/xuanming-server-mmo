package com.game.team;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * game-team 进程入口：对外提供 Dubbo 服务 {@code ClientMessageService}（group {@code team}，Triple，默认端口 20885），
 * gate 把 {@code ClientPlayerTeam} 的消息号转到这里。全服可多副本、进程无状态，权威数据在同一TiDB事务域。
 * 独立进程、不并进 game-match（D1）：整队开战（211）的预检、建票与 gather 经 Dubbo 接口 {@code MatchTeamService}（group {@code match}）
 * 调 game-match，开战锁与编排留在本进程（match-spec §7.5、§7.6）；game-match 不在时 game-team 照常启动，只是 211 回 4030。
 *
 * <p>启动即接客前提：Redis（Redisson）→ team_id 雪花租约（{@code NodeTypes.TEAM}，作用域 0）→ MySQL 连接池 → Dubbo 暴露服务；
 * 缺 {@code GAME_DUBBO_SECRET} 拒绝启动。关闭时 Dubbo 先注销并等在途调用，再依次关工作线程池、整队开战收尾池与推送线程池
 * 未知开局保留永久原件，由其它副本或重启后的恢复器重放，关闭进程不释放业务名单。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.team")
public class TeamApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeamApplication.class, args);
    }
}
