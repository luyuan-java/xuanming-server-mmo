package com.game.team;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.DubboGroups;
import com.game.api.MatchTeamService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.match.MatchRpcAttachments;
import com.game.api.proto.TeamGatherReply;
import com.game.api.proto.TeamGatherRequest;
import com.game.api.proto.TeamMatchCheckReply;
import com.game.api.proto.TeamMatchCheckRequest;
import com.game.api.proto.TeamMatchCheckResult;
import com.game.api.proto.TeamTicketsRelease;
import com.game.api.proto.TeamTicketsReply;
import com.game.api.proto.TeamTicketsRequest;
import com.game.api.proto.TeamTicketsStatus;
import com.game.common.deadline.Deadline;
import com.game.proto.Empty;
import com.game.team.match.MatchTeamBattle;
import com.game.team.match.TeamBattlePort;
import com.game.team.match.TeamBattlePort.Check;
import com.game.team.rules.TeamTips;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@link TeamDubboConfiguration} 的装配在 Spring 里真的起得来、调得通（缺省执行）：Dubbo 的 Spring 基础设施（{@code @EnableDubbo}，
 * 同 {@code TeamApplication}）+ 本配置类，引用由 {@code @DubboReference} 按配置键 {@code xm.dubbo.match-url} 直连一个假的 xm-match
 * （另一个 Dubbo 框架模型里的真 Triple 提供方，group {@code match}，调用方鉴权过滤器照常生效）。
 * 钉住：注入给 {@link MatchTeamBattle} 的就是这个引用；group、鉴权、预算附件都对得上；地址指错时不拖垮启动、调用落到 4030。
 */
class TeamDubboConfigurationTest {

    private static final long A = Long.MIN_VALUE + 301, B = 302;

    /** 只为带起 Dubbo 的 Spring 基础设施（扫描的包里没有任何 {@code @DubboService}）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableDubbo(scanBasePackages = "com.game.team.match")
    static class DubboInfrastructure {
    }

    /** 假的 xm-match：预检恒通过，并记下方法入口读到的预算附件。 */
    static final class Provider implements MatchTeamService {

        volatile String budget;

        @Override
        public CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request) {
            budget = RpcContext.getServerAttachment().getAttachment(MatchRpcAttachments.BUDGET_MS);
            TeamMatchCheckReply.Builder ok = TeamMatchCheckReply.newBuilder().setResult(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK)
                    .setLockTtlSeconds(74);
            request.getRosterList().forEach(pid -> ok.putZones(pid, 2));
            return CompletableFuture.completedFuture(ok.build());
        }

        @Override
        public CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request) {
            return CompletableFuture.completedFuture(TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_CREATED)
                    .build());
        }

        @Override
        public CompletableFuture<Empty> releaseTeamTickets(TeamTicketsRelease request) {
            return CompletableFuture.completedFuture(Empty.getDefaultInstance());
        }

        @Override
        public CompletableFuture<TeamGatherReply> runTeamGather(TeamGatherRequest request) {
            return CompletableFuture.completedFuture(TeamGatherReply.newBuilder().setOk(true).setBattleId(1).build());
        }
    }

    private static final Provider PROVIDER = new Provider();
    private static IsolatedDubboModule server;
    private static int port;

    @BeforeAll
    static void startProvider() throws IOException {
        port = freePort();
        server = IsolatedDubboModule.create("xm-team-wiring-test-match");
        ProtocolConfig protocol = new ProtocolConfig("tri", port);
        protocol.setHost("127.0.0.1");
        ServiceConfig<MatchTeamService> service = new ServiceConfig<>(server.module());
        service.setInterface(MatchTeamService.class);
        service.setRef(PROVIDER);
        service.setGroup(DubboGroups.MATCH);
        service.setRegister(false);
        service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        service.setProtocol(protocol);
        service.export();
    }

    @AfterAll
    static void stopProvider() {
        if (server != null) {
            server.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ApplicationContextRunner runner(String matchUrl) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DubboInfrastructure.class, TeamDubboConfiguration.class)
                // 关停时别等缺省的 10 s（没有在途调用）
                .withSystemProperties("dubbo.service.shutdown.wait=500")
                .withPropertyValues("dubbo.application.name=xm-team-wiring-test", "dubbo.application.qos-enable=false",
                        "dubbo.application.logger=slf4j", "dubbo.registry.address=N/A", "dubbo.consumer.check=false",
                        "xm.dubbo.match-url=" + matchUrl);
    }

    @Test
    void Spring装配出来的端口经真Triple调得通xm_match_group鉴权与预算附件都对得上() {
        runner("tri://127.0.0.1:" + port).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(TeamBattlePort.class).hasSingleBean(MatchTeamService.class);
            TeamBattlePort battle = ctx.getBean(TeamBattlePort.class);
            assertThat(battle).isInstanceOf(MatchTeamBattle.class);

            Check check = battle.checkTeamMatch(1, List.of(A, B), Deadline.after(2500));

            assertThat(check.ok()).as("code=%d（group 不对 / 鉴权不过 / 地址没解析都会落到 4030）", check.code()).isTrue();
            assertThat(check.zones()).isEqualTo(Map.of(A, 2, B, 2));
            assertThat(check.lockTtlSeconds()).isEqualTo(74);
            assertThat(PROVIDER.budget).as("预算附件（= 这一跳的超时）随调用过了线").isNotNull();
            assertThat(Long.parseLong(PROVIDER.budget)).isBetween(1L, 2500L);
        });
    }

    @Test
    void 地址指向没人监听的端口_上下文照常起来_预检落到4030() throws IOException {
        runner("tri://127.0.0.1:" + freePort()).run(ctx -> {
            assertThat(ctx).as("check = false：xm-match 不在不影响 xm-team 启动").hasNotFailed();

            Check check = ctx.getBean(TeamBattlePort.class).checkTeamMatch(1, List.of(A, B), Deadline.after(1500));

            assertThat(check.ok()).isFalse();
            assertThat(check.code()).isEqualTo(TeamTips.INTERNAL);
        });
    }
}
