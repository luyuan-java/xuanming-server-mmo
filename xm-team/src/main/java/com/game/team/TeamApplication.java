package com.game.team;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * xm-team 进程入口：对外提供 Dubbo 服务 {@code ClientMessageService}（group {@code team}，Triple，默认端口 20885），
 * gate 把 {@code ClientPlayerTeam} 的消息号转到这里。全服一份、可多副本、进程无状态（权威数据全在 Redis，只读 {@code xm_java.player}）。
 * 独立进程、不并进 xm-match（D1）：整队开战（211）的预检、建票与 gather 经 Dubbo 接口 {@code MatchTeamService}（group {@code match}）
 * 调 xm-match，开战锁与编排留在本进程（match-spec §7.5、§7.6）；xm-match 不在时 xm-team 照常启动，只是 211 回 4030。
 *
 * <p>启动即接客前提：Redis（Redisson）→ team_id 雪花租约（{@code NodeTypes.TEAM}，作用域 0）→ MySQL 连接池 → Dubbo 暴露服务；
 * 缺 {@code XM_DUBBO_SECRET} 拒绝启动。关闭时 Dubbo 先注销并等在途调用，再依次关工作线程池、整队开战收尾池与推送线程池
 * （还没清掉的开战锁靠自然过期，同基线「进程退出不等 EndMatch」）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.team")
public class TeamApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeamApplication.class, args);
    }
}
