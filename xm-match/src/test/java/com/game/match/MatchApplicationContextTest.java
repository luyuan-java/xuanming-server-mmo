package com.game.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.RunMode;
import com.game.discovery.RedisProperties;
import com.game.match.admin.MatchAdminAuthFilter;
import com.game.match.dispatch.InlineHandlers;
import com.game.match.dispatch.MatchDispatchConfiguration;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchWorkers;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.id.MatchIds;
import com.game.match.lifecycle.MatchLeaseHealthIndicator;
import com.game.match.lifecycle.MatchLifecycle;
import com.game.match.lifecycle.MatchStartupChecks;
import com.game.match.lifecycle.MatcherControl;
import com.game.match.lifecycle.ResultConsumerControl;
import com.game.match.testing.LeaseOnlyRedis;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 启动门禁与装配（match-spec §9.8、§15.2「启动」）：用 {@code ApplicationContextRunner} 起 {@link MatchConfiguration} 加派发层，不连 Redis
 * （只应答发号租约的替身）、不开端口、不起 Dubbo。钉住：缺密钥、指纹模式非法、PVE 人数表的副本 id 不在 Dungeon 表里、Redis 预算断言不过、
 * 配置表读不到、占不到号——任何一条都拒绝启动，而且门禁没过就不去占号；Kafka 不可达、别的包还没接入则照常启动；以及整个上下文里的启停次序
 * （凑单 → 评分消费；停凑单 → 排空工作池 → 等 gather → 停评分消费 → 还租约）。
 *
 * <p>秘密都经属性显式给出，盖住开发机上可能已设置的同名环境变量。Dubbo 导出 / 撤导出与这些挂点的真实先后由 {@code MatchRpcLoopbackTest} 钉。
 */
@ExtendWith(OutputCaptureExtension.class)
class MatchApplicationContextTest {

    /**
     * 属性类照 {@code MatchApplication} 的扫描结果登记。用 {@code @TestConfiguration}：起整个 {@code MatchApplication} 的测试会组件扫描到本包的测试类，
     * 普通的 {@code @Configuration} 会被扫进别人的上下文。
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MatchProperties.class, RedisProperties.class})
    static class Wiring {
    }

    private final LeaseOnlyRedis redis = new LeaseOnlyRedis();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ApplicationContextRunner runner = runner(redis.client);

    /** 被测装配：基础设施 + 派发层 + 当场回的四个号。 */
    private static ApplicationContextRunner runner(RedissonClient client) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Wiring.class, MatchConfiguration.class, MatchDispatchConfiguration.class, InlineHandlers.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(RedissonClient.class, () -> client)
                .withPropertyValues(
                        "xm.table-dir=../config-data/tables",
                        "xm.run-mode=test",
                        "XM_DUBBO_SECRET=dubbo-secret-for-context-tests",
                        "XM_ADMIN_TOKEN=");
    }

    /** 记事件的启停口；评分消费停下时顺手记下租约是否已交还。 */
    private ApplicationContextRunner withRecordingBackground(ApplicationContextRunner base) {
        return base
                .withBean(MatcherControl.class, () -> new MatcherControl() {
                    @Override
                    public void start() {
                        events.add("matcher.start");
                    }

                    @Override
                    public void stop() {
                        events.add("matcher.stop");
                    }
                })
                .withBean(ResultConsumerControl.class, () -> new ResultConsumerControl() {
                    @Override
                    public void start() {
                        events.add("consumer.start");
                    }

                    @Override
                    public void stop() {
                        events.add("consumer.stop lease_releases=" + redis.leaseReleases());
                    }
                });
    }

    private static void publishStarted(AssertableApplicationContext context) {
        context.publishEvent(new ApplicationStartedEvent(new SpringApplication(), new String[0], context.getSourceApplicationContext(), Duration.ZERO));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // ================================================================ 正常启动

    @Test
    void 配置齐全_启动成功_门禁先过再占号_四个当场回的号已登记(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(MatchLifecycle.class)
                    .hasSingleBean(MatchLeaseHealthIndicator.class)
                    .hasSingleBean(MatchStartupChecks.Passed.class)
                    .hasSingleBean(FilterRegistrationBean.class);
            assertThat(context.getBean(MatchStartupChecks.Passed.class)).isEqualTo(new MatchStartupChecks.Passed(4200, 1));
            assertThat(redis.leaseAcquisitions()).isEqualTo(1);
            assertThat(context.getBean(MatchIds.class).leaseValid()).isTrue();
            assertThat(context.getBean(MatchLeaseHealthIndicator.class).health().getStatus()).isEqualTo(Status.UP);
            assertThat(context.getBean(MatchDispatcher.class).handledMessageIds()).containsExactly(154, 156, 163, 164);
            assertThat(context.getBean(MatchDispatcher.class).unhandledMethods()).containsExactly("CancelQueue", "ChallengePlayer",
                    "GetQueueStatus", "JoinQueue", "RequestBattleTicket", "RespondChallenge");
            assertThat(context.getBean(RunMode.class)).isEqualTo(RunMode.TEST);
            MatchLifecycle lifecycle = context.getBean(MatchLifecycle.class);
            assertThat(lifecycle.isRunning()).as("容器已把它当生命周期 bean 启动").isTrue();
            assertThat(lifecycle.backgroundStarted()).as("应用已启动事件还没来：凑单与评分消费不起").isFalse();
        });
        assertThat(output.getOut()).contains("启动门禁通过").contains("Redis 单条命令最坏≈4200 ms")
                .contains("match 还有方法没有处理器，这些号一律回信封 1003");
        assertThat(output.getOut().indexOf("启动门禁通过")).as("第 3、4 步在第 5 步之前").isLessThan(output.getOut().indexOf("节点号租约已获取"));
        assertThat(redis.leaseReleases()).as("上下文关闭时交还了租约（释放脚本）").isEqualTo(1);
    }

    @Test
    void Kafka不可达_照常启动() {
        runner.withPropertyValues("xm.match.kafka.bootstrap-servers=127.0.0.1:1", "xm.match.kafka.init-timeout=1s").run(context -> {
            assertThat(context).hasNotFailed();
            publishStarted(context);
            assertThat(context.getBean(MatchLifecycle.class).backgroundStarted()).isTrue();
            assertThat(context.getBean(MatchProperties.class).kafka().bootstrapServers()).isEqualTo("127.0.0.1:1");
        });
    }

    @Test
    void 别的包还没接入_凑单_评分消费_开局管线各告警一次_照常启动与停机(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(output.getOut()).contains("开局管线尚未接入").doesNotContain("凑单尚未接入");

            publishStarted(context);

            assertThat(output.getOut()).contains("凑单尚未接入").contains("评分消费尚未接入").contains("match 已就绪");
        });
        assertThat(output.getOut()).contains("停机 3/5：match-worker 已排空");
    }

    // ================================================================ 拒绝启动

    @Test
    void 缺Dubbo调用鉴权密钥_拒启_不去占号() {
        runner.withPropertyValues("XM_DUBBO_SECRET=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalStateException.class).hasMessageContaining("XM_DUBBO_SECRET");
        });
        runner.withPropertyValues("XM_DUBBO_SECRET=   ").run(context -> assertThat(context).as("只有空白也算缺").hasFailed());
        assertThat(redis.leaseAcquisitions()).isZero();
    }

    @Test
    void 指纹模式写错_枚举绑定失败_拒启() {
        runner.withPropertyValues("xm.match.table-fingerprint-mode=reject").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("table-fingerprint-mode");
        });
        assertThat(redis.leaseAcquisitions()).isZero();
        runner.withPropertyValues("xm.match.table-fingerprint-mode=enforce").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void PVE组队人数表的副本id不在Dungeon表里_拒启_不去占号() {
        runner.withPropertyValues("xm.match.pve-team-size-by-config-id.1=5", "xm.match.pve-team-size-by-config-id.987654=5").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("启动门禁未通过").hasMessageContaining("副本 id 987654 ").hasMessageContaining("不在 Dungeon 表里");
        });
        assertThat(redis.leaseAcquisitions()).as("门禁没过：不去占雪花 worker").isZero();
    }

    @Test
    void PVE组队人数表的值小于1_拒启() {
        runner.withPropertyValues("xm.match.pve-team-size-by-config-id.1=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("pve-team-size-by-config-id[1] 必须 ≥ 1");
        });
    }

    @Test
    void Redis预算断言不过_单条命令最坏耗时超过6100毫秒_拒启_不去占号() {
        runner.withPropertyValues("xm.redis.timeout-ms=3000").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Redis 单条命令的最坏耗时 6200 ms").hasMessageContaining("6100 ms");
        });
        assertThat(redis.leaseAcquisitions()).isZero();
        runner.withPropertyValues("xm.redis.timeout-ms=3000", "xm.redis.retry-attempts=0").run(context ->
                assertThat(context).as("同样的超时、不重试：最坏 3000 ms，通过").hasNotFailed());
    }

    @Test
    void 两项门禁同时不过_一条异常里列全() {
        runner.withPropertyValues("xm.redis.timeout-ms=5000", "xm.match.pve-team-size-by-config-id.555555=3").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("副本 id 555555 ").hasMessageContaining("Redis 单条命令的最坏耗时 10200 ms");
        });
    }

    @Test
    void 配置表读不到_拒启_密钥的检查排在它之前() {
        runner.withPropertyValues("xm.table-dir=../config-data/no-such-dir").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("matchConfigTables");
        });
        runner.withPropertyValues("xm.table-dir=../config-data/no-such-dir", "XM_DUBBO_SECRET=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).as("第 2 步先于第 3 步").hasMessageContaining("XM_DUBBO_SECRET");
        });
        assertThat(redis.leaseAcquisitions()).isZero();
    }

    @Test
    void 占不到发号租约_Redis不可达_拒启() {
        RedissonClient down = mock(RedissonClient.class);
        when(down.getBucket(anyString(), any(Codec.class))).thenThrow(new IllegalStateException("redis 连不上"));

        runner(down).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("matchIdLease").hasStackTraceContaining("redis 连不上");
        });
    }

    @Test
    void 同一个启停口出现两个bean_拒启_不猜用哪个() {
        MatcherControl one = MatchLifecycle.matcherNotReady();
        MatcherControl two = MatchLifecycle.matcherNotReady();

        runner.withBean("matcherOne", MatcherControl.class, () -> one).withBean("matcherTwo", MatcherControl.class, () -> two).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(NoUniqueBeanDefinitionException.class);
        });
    }

    // ================================================================ 运行模式与管理口

    @Test
    void 运行模式写错_按prod并告警_不拒启(CapturedOutput output) {
        runner.withPropertyValues("xm.run-mode=develop").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RunMode.class)).isEqualTo(RunMode.PROD);
        });
        assertThat(output.getOut()).contains("xm.run-mode（XM_RUN_MODE）取值不认识，按 prod 运行");
    }

    @SuppressWarnings("unchecked")
    private static int adminStatus(AssertableApplicationContext context, String token) throws Exception {
        FilterRegistrationBean<MatchAdminAuthFilter> registration = context.getBean(FilterRegistrationBean.class);
        assertThat(registration.getUrlPatterns()).as("只挂在 /admin/* 上，不碰 actuator").containsExactly("/admin/*");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/match/dev/rating/1");
        request.setServletPath("/admin/match/dev/rating/1");
        if (token != null) {
            request.addHeader(MatchAdminAuthFilter.TOKEN_HEADER, token);
        }
        request.addHeader(MatchAdminAuthFilter.OPERATOR_HEADER, "tester");
        MockHttpServletResponse response = new MockHttpServletResponse();
        registration.getFilter().doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void 管理口过滤器_令牌取环境变量_没配503_配了按运行模式放行或403(CapturedOutput output) {
        runner.run(context -> assertThat(adminStatus(context, "anything")).as("XM_ADMIN_TOKEN 为空").isEqualTo(503));
        assertThat(output.getOut()).contains("运维令牌 XM_ADMIN_TOKEN 未配置：管理端口 /admin/** 一律 503");

        runner.withPropertyValues("XM_ADMIN_TOKEN=tok-for-context-test").run(context -> {
            assertThat(adminStatus(context, "tok-for-context-test")).as("运行模式 test：放行").isEqualTo(200);
            assertThat(adminStatus(context, "wrong")).isEqualTo(401);
            assertThat(adminStatus(context, null)).isEqualTo(401);
        });
        runner.withPropertyValues("XM_ADMIN_TOKEN=tok-for-context-test", "xm.run-mode=prod").run(context -> {
            assertThat(adminStatus(context, "tok-for-context-test")).as("prod：dev 口 403").isEqualTo(403);
            assertThat(adminStatus(context, "wrong")).as("令牌仍是第一道闸").isEqualTo(401);
        });
    }

    // ================================================================ 启停次序（整个上下文）

    @Test
    void 启停次序_应用已启动才起凑单再起评分消费_关闭时停凑单_排空工作池_等gather_停评分消费_最后还租约() {
        withRecordingBackground(runner)
                .withBean(GatherLauncher.class, () -> new GatherLauncher() {
                    @Override
                    public CompletableFuture<GatherResult> launch(GatherPlan plan) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int availablePermits() {
                        return 256;
                    }

                    @Override
                    public boolean awaitIdle(Duration timeout) {
                        events.add("gathers.awaitIdle(" + timeout.toSeconds() + "s)");
                        return true;
                    }
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(events).as("刷新完成、应用已启动事件之前：什么都没起").isEmpty();

                    publishStarted(context);
                    assertThat(events).containsExactly("matcher.start", "consumer.start");

                    MatchWorkers workers = context.getBean(MatchWorkers.class);
                    events.clear();
                    context.getSourceApplicationContext().close();

                    assertThat(events).containsExactly("matcher.stop", "gathers.awaitIdle(10s)", "consumer.stop lease_releases=0");
                    assertThat(redis.leaseReleases()).as("评分消费停下时租约还没还，全部停完之后才还").isEqualTo(1);
                    assertThatThrownBy(() -> workers.execute(() -> { })).as("工作池已排空并停止接收").isInstanceOf(RejectedExecutionException.class);
                });
    }

    @Test
    void 等gather之前工作池已经排空_之后不会再有人交新的gather() {
        List<String> seen = new CopyOnWriteArrayList<>();
        Object[] workersHolder = new Object[1];
        withRecordingBackground(runner)
                .withBean(GatherLauncher.class, () -> new GatherLauncher() {
                    @Override
                    public CompletableFuture<GatherResult> launch(GatherPlan plan) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int availablePermits() {
                        return 256;
                    }

                    @Override
                    public boolean awaitIdle(Duration timeout) {
                        try {
                            ((MatchWorkers) workersHolder[0]).execute(() -> { });
                            seen.add("workers.accepting");
                        } catch (RejectedExecutionException e) {
                            seen.add("workers.closed");
                        }
                        return true;
                    }
                })
                .run(context -> {
                    workersHolder[0] = context.getBean(MatchWorkers.class);
                    publishStarted(context);
                    CompletableFuture<String> ranBeforeClose = new CompletableFuture<>();
                    context.getBean(MatchWorkers.class).execute(() -> ranBeforeClose.complete(Thread.currentThread().getName()));

                    context.getSourceApplicationContext().close();

                    assertThat(seen).containsExactly("workers.closed");
                    assertThat(ranBeforeClose).as("关闭之前已受理的任务做完了").isCompletedWithValueMatching(name -> name.startsWith("match-worker-"));
                });
    }
}
