package com.game.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.RunMode;
import com.game.discovery.RedisProperties;
import com.game.match.activity.ActivityBattleService;
import com.game.match.activity.ActivityConfiguration;
import com.game.match.activity.MatchInternalServiceImpl;
import com.game.match.admin.DevActivityBattleController;
import com.game.match.admin.DevRatingController;
import com.game.match.admin.MatchAdminAuthFilter;
import com.game.match.challenge.ChallengeConfiguration;
import com.game.match.challenge.ChallengeStore;
import com.game.match.challenge.RedissonChallengeStore;
import com.game.match.dispatch.InlineHandlers;
import com.game.match.dispatch.MatchClientMessageService;
import com.game.match.dispatch.MatchDispatchConfiguration;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchWorkers;
import com.game.match.gather.BattleNodes;
import com.game.match.gather.GatherConfiguration;
import com.game.match.gather.GatherHooks;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.gather.RedisBattleNodes;
import com.game.match.gather.VirtualThreadGatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.lifecycle.MatchLeaseHealthIndicator;
import com.game.match.lifecycle.MatchLifecycle;
import com.game.match.lifecycle.MatchStartupChecks;
import com.game.match.lifecycle.MatcherControl;
import com.game.match.lifecycle.ResultConsumerControl;
import com.game.match.matcher.MatcherConfiguration;
import com.game.match.matcher.MatcherRunner;
import com.game.match.placement.DirectPlacementDialer;
import com.game.match.placement.PlacementConfiguration;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementStore;
import com.game.match.placement.RedissonPlacementStore;
import com.game.match.precheck.DefaultMemberPrecheck;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.precheck.PrecheckConfiguration;
import com.game.match.queue.QueueConfiguration;
import com.game.match.rating.BattleResultIngest;
import com.game.match.rating.JdbcRatingReader;
import com.game.match.rating.MatchRatingTables;
import com.game.match.rating.RatingConfiguration;
import com.game.match.rating.RatingReader;
import com.game.match.rating.RatingTestDatabase;
import com.game.match.reissue.ReissueConfiguration;
import com.game.match.team.MatchTeamServiceImpl;
import com.game.match.testing.LeaseOnlyRedis;
import com.game.match.ticket.DefaultTicketHealing;
import com.game.match.ticket.RedissonTicketStore;
import com.game.match.ticket.TicketHealing;
import com.game.match.ticket.TicketReader;
import com.game.match.ticket.TicketStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import javax.sql.DataSource;
import org.apache.dubbo.config.annotation.DubboService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
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
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Component;

/**
 * 启动门禁与<b>真实装配</b>（match-spec §9.8、§15.2「启动」）：用 {@code ApplicationContextRunner} 起 xm-match 进程的全部装配类（{@link #PROCESS}：
 * 基础设施、派发层与十个号的处理器、票据 / 排队、凑单、开局管线、落点、补签、评分、切磋、预检、整队与活动两个提供方、两个 dev 管理口），
 * 只把外部连接换掉——Redis 是只应答发号租约的替身、MySQL 是 H2 内存库、不开端口、不起 Dubbo。钉住：
 * <ul>
 *   <li>装起来的都是各包的真实现，十个号都有处理器，凑单与评分消费的启停口是真的调度器与真的消费者；</li>
 *   <li>缺密钥、指纹模式非法、PVE 人数表的副本 id 不在 Dungeon 表里、Redis 预算断言不过、配置表读不到、占不到号——任何一条都拒绝启动，
 *       而且门禁没过就不去占号；</li>
 *   <li><b>少装任何一包都拒绝启动</b>（凑单、评分、开局管线、任何一个号的处理器）；同一个启停口出现两个 bean 也拒；</li>
 *   <li>Kafka 不可达照常启动（启动第 9 步至多多等一个 init-timeout）；分区数与契约不符由 {@code BattleResultIngestTest} 钉；</li>
 *   <li>整个上下文里的启停次序（凑单 → 评分消费；停凑单 → 排空工作池 → 等 gather → 停评分消费 → 还租约）。</li>
 * </ul>
 *
 * <p>秘密都经属性显式给出，盖住开发机上可能已设置的同名环境变量。除「Kafka 不可达」那一条外都关着评分开关：本机若正好有 Kafka，测试进程不该以生产的
 * 消费组去读真的结果 topic。Dubbo 导出 / 撤导出与这些挂点的真实先后由 {@code MatchRpcLoopbackTest} 钉；组件扫描出来的进程（真 Triple、真管理端口）
 * 由 {@code MatchSkeletonContextTest} 钉。
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

    /**
     * xm-match 进程的全部组件（{@code MatchApplication} 组件扫描 + Dubbo 提供方扫描到的类；与扫描结果的一致性由
     * {@link #装配清单与主代码里组件扫描得到的类一致} 钉，新增或删掉一个装配类而忘了改这里，那条用例会失败）。
     */
    static final List<Class<?>> PROCESS = List.of(
            MatchConfiguration.class, MatchDispatchConfiguration.class, InlineHandlers.class, MatchClientMessageService.class,
            QueueConfiguration.class, MatcherConfiguration.class, GatherConfiguration.class, PlacementConfiguration.class,
            ReissueConfiguration.class, RatingConfiguration.class, ChallengeConfiguration.class, PrecheckConfiguration.class,
            ActivityConfiguration.class, MatchTeamServiceImpl.class, MatchInternalServiceImpl.class,
            DevRatingController.class, DevActivityBattleController.class);

    private final LeaseOnlyRedis redis = new LeaseOnlyRedis();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ApplicationContextRunner runner = runner(redis.client, PROCESS);

    /** 被测装配：{@code components} 里的类 + 外部连接的替身。 */
    private static ApplicationContextRunner runner(RedissonClient client, List<Class<?>> components) {
        List<Class<?>> classes = new ArrayList<>(components);
        classes.add(Wiring.class);
        String database = "xm-match-context-" + UUID.randomUUID();
        return new ApplicationContextRunner()
                .withUserConfiguration(classes.toArray(Class<?>[]::new))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(RedissonClient.class, () -> client)
                .withBean(DataSource.class, () -> RatingTestDatabase.h2DataSource(database))
                .withBean(MatchRatingTables.SchemaSync.class, () -> RatingTestDatabase.H2_SCHEMA)
                .withPropertyValues(
                        "xm.table-dir=../config-data/tables",
                        "xm.run-mode=test",
                        "xm.match.rating.enabled=false",
                        "XM_DUBBO_SECRET=dubbo-secret-for-context-tests",
                        "XM_ADMIN_TOKEN=");
    }

    private ApplicationContextRunner without(Class<?> component) {
        List<Class<?>> rest = new ArrayList<>(PROCESS);
        assertThat(rest.remove(component)).isTrue();
        return runner(redis.client, rest);
    }

    /** 记事件的启停口（标成首选，盖过真的调度器与消费者）；评分消费停下时顺手记下租约是否已交还。 */
    private ApplicationContextRunner withRecordingBackground(ApplicationContextRunner base) {
        return base
                .withBean("recordingMatcherControl", MatcherControl.class, () -> new MatcherControl() {
                    @Override
                    public void start() {
                        events.add("matcher.start");
                    }

                    @Override
                    public void stop() {
                        events.add("matcher.stop");
                    }
                }, definition -> definition.setPrimary(true))
                .withBean("recordingResultConsumerControl", ResultConsumerControl.class, () -> new ResultConsumerControl() {
                    @Override
                    public void start() {
                        events.add("consumer.start");
                    }

                    @Override
                    public void stop() {
                        events.add("consumer.stop lease_releases=" + redis.leaseReleases());
                    }
                }, definition -> definition.setPrimary(true));
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

    // ================================================================ 正常启动：真实装配

    @Test
    void 配置齐全_启动成功_门禁先过再占号_装起来的都是各包的真实现_十个号都有处理器(CapturedOutput output) {
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
            assertThat(context.getBean(RunMode.class)).isEqualTo(RunMode.TEST);

            // 各包的接口各有恰好一个实现，而且是生产实现
            assertThat(context).hasSingleBean(TicketStore.class).hasSingleBean(TicketHealing.class).hasSingleBean(RatingReader.class)
                    .hasSingleBean(GatherLauncher.class).hasSingleBean(BattleNodes.class).hasSingleBean(GatherHooks.class)
                    .hasSingleBean(PlacementStore.class).hasSingleBean(PlacementDialer.class).hasSingleBean(MemberPrecheck.class)
                    .hasSingleBean(ChallengeStore.class).hasSingleBean(MatcherControl.class).hasSingleBean(ResultConsumerControl.class)
                    .hasSingleBean(ActivityBattleService.class).hasSingleBean(MatchTeamServiceImpl.class)
                    .hasSingleBean(MatchInternalServiceImpl.class).hasSingleBean(MatchClientMessageService.class);
            assertThat(context.getBean(TicketStore.class)).isInstanceOf(RedissonTicketStore.class);
            assertThat(context.getBean(TicketReader.class)).as("只读口与存储是同一个对象").isSameAs(context.getBean(TicketStore.class));
            assertThat(context.getBean(TicketHealing.class)).isInstanceOf(DefaultTicketHealing.class);
            assertThat(context.getBean(RatingReader.class)).isInstanceOf(JdbcRatingReader.class);
            assertThat(context.getBean(GatherLauncher.class)).isInstanceOf(VirtualThreadGatherLauncher.class);
            assertThat(context.getBean(GatherLauncher.class).availablePermits()).as("在途上限的缺省值").isEqualTo(256);
            assertThat(context.getBean(BattleNodes.class)).isInstanceOf(RedisBattleNodes.class);
            assertThat(context.getBean(GatherHooks.class)).as("6.4 的观战钩子是空实现").isSameAs(GatherHooks.NOOP);
            assertThat(context.getBean(PlacementStore.class)).isInstanceOf(RedissonPlacementStore.class);
            assertThat(context.getBean(PlacementDialer.class)).isInstanceOf(DirectPlacementDialer.class);
            assertThat(context.getBean(MemberPrecheck.class)).isInstanceOf(DefaultMemberPrecheck.class);
            assertThat(context.getBean(ChallengeStore.class)).isInstanceOf(RedissonChallengeStore.class);
            assertThat(context.getBean(MatcherControl.class)).as("凑单的启停口就是调度器").isInstanceOf(MatcherRunner.class);
            assertThat(context.getBean(ResultConsumerControl.class)).as("评分消费的启停口就是消费者").isInstanceOf(BattleResultIngest.class);

            MatchDispatcher dispatcher = context.getBean(MatchDispatcher.class);
            assertThat(dispatcher.handledMessageIds()).as("契约 MatchService 的十个号").containsExactly(148, 151, 152, 153, 154, 156, 157, 163, 164, 179);
            assertThat(dispatcher.unhandledMethods()).isEmpty();

            MatchLifecycle lifecycle = context.getBean(MatchLifecycle.class);
            assertThat(lifecycle.isRunning()).as("容器已把它当生命周期 bean 启动").isTrue();
            assertThat(lifecycle.backgroundStarted()).as("应用已启动事件还没来：凑单与评分消费不起").isFalse();
            assertThat(context.getBean(MatcherRunner.class).isRunning()).as("凑单自己不带生命周期").isFalse();
            assertThat(context.getBean(BattleResultIngest.class).isRunning()).isFalse();
        });
        assertThat(output.getOut()).contains("启动门禁通过").contains("Redis 单条命令最坏≈4200 ms").contains("评分表已同步")
                .doesNotContain("没有处理器");
        assertThat(output.getOut().indexOf("启动门禁通过")).as("第 3、4 步在第 5 步之前").isLessThan(output.getOut().indexOf("节点号租约已获取"));
        assertThat(output.getOut().indexOf("节点号租约已获取")).as("第 5 步（占号）在第 6 步（建评分表）之前")
                .isLessThan(output.getOut().indexOf("评分表已同步"));
        assertThat(redis.leaseReleases()).as("上下文关闭时交还了租约（释放脚本）").isEqualTo(1);
    }

    @Test
    void 应用已启动_真的凑单调度器起来了_就绪日志是切片脚本等的那一行_关闭上下文时凑单先停_租约最后还(CapturedOutput output) {
        MatcherRunner[] matcher = new MatcherRunner[1];
        runner.run(context -> {
            matcher[0] = context.getBean(MatcherRunner.class);

            publishStarted(context);

            assertThat(context.getBean(MatchLifecycle.class).backgroundStarted()).isTrue();
            assertThat(matcher[0].isRunning()).as("启动第 8 步：凑单循环在跑").isTrue();
            assertThat(context.getBean(BattleResultIngest.class).isRunning()).as("评分开关关着：启动第 9 步是空操作").isFalse();
            // tools/local/start-slice.sh 的 wait_match_ready 按这个子串判 xm-match 就绪（xm-gate 的 LocalSliceOrderTest 钉脚本一侧）
            assertThat(output.getOut()).contains("match 已就绪");
            assertThat(output.getOut().indexOf("凑单循环已启动")).isLessThan(output.getOut().indexOf("match 已就绪"));
        });
        assertThat(matcher[0].isRunning()).as("上下文关闭：MatchLifecycle 把凑单停了").isFalse();
        assertThat(output.getOut()).contains("停机 1/5：凑单已停").contains("停机 3/5：match-worker 已排空").contains("停机 4/5：在途 gather 已全部结束")
                .contains("停机 5/5：评分消费已停");
        assertThat(output.getOut().indexOf("停机 1/5")).isLessThan(output.getOut().indexOf("停机 3/5"));
        assertThat(output.getOut().indexOf("停机 3/5")).isLessThan(output.getOut().indexOf("停机 4/5"));
        assertThat(output.getOut().indexOf("停机 4/5")).isLessThan(output.getOut().indexOf("停机 5/5"));
        assertThat(redis.leaseReleases()).isEqualTo(1);
    }

    @Test
    void Kafka不可达_照常启动_启动第9步至多多等一个init_timeout_消费在后台等重试_关闭时停掉() {
        BattleResultIngest[] ingest = new BattleResultIngest[1];
        runner.withPropertyValues("xm.match.rating.enabled=true", "xm.match.kafka.bootstrap-servers=127.0.0.1:1",
                "xm.match.kafka.topic-generation=9644", "xm.match.kafka.init-timeout=1s").run(context -> {
            assertThat(context).hasNotFailed();
            ingest[0] = context.getBean(BattleResultIngest.class);
            assertThat(context.getBean(MatchProperties.class).kafka().bootstrapServers()).isEqualTo("127.0.0.1:1");
            long started = System.nanoTime();

            publishStarted(context);

            long startMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(context.getBean(MatchLifecycle.class).backgroundStarted()).isTrue();
            assertThat(context.getBean(MatcherRunner.class).isRunning()).as("凑单不受 Kafka 影响").isTrue();
            assertThat(ingest[0].isRunning()).as("消费者在后台每 30 s 重试核对 topic").isTrue();
            assertThat(ingest[0].topic()).isEqualTo("xm-battle-result-g9644");
            assertThat(startMs).as("至多等 init-timeout（这里 1 s）外加客户端的建连开销，不是无限等").isLessThan(20_000);
        });
        assertThat(ingest[0].isRunning()).as("上下文关闭：MatchLifecycle 把它停了").isFalse();
    }

    // ================================================================ 装配清单

    @Test
    void 装配清单与主代码里组件扫描得到的类一致() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));       // @Configuration / @RestController 都以它为元注解
        scanner.addIncludeFilter(new AnnotationTypeFilter(DubboService.class));    // Dubbo 提供方由 @EnableDubbo 的扫描登记
        String mainClasses = MatchApplication.class.getProtectionDomain().getCodeSource().getLocation().toString();
        Set<String> scanned = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(MatchApplication.class.getPackageName())) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            // 只看主代码（target/classes）；测试目录里的 @TestConfiguration 不算进程的一部分
            if (type.getProtectionDomain().getCodeSource().getLocation().toString().equals(mainClasses) && type != MatchApplication.class) {
                scanned.add(type.getName());
            }
        }

        assertThat(scanned).as("PROCESS 必须与 MatchApplication 实际扫描到的组件一致：新增 / 删掉装配类时同步改它")
                .containsExactlyInAnyOrderElementsOf(PROCESS.stream().map(Class::getName).toList());
    }

    // ================================================================ 拒绝启动：门禁

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

        runner(down, PROCESS).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("matchIdLease").hasStackTraceContaining("redis 连不上");
        });
    }

    @Test
    void 评分表建不出来_拒启_占号在它之前所以号会被交还() {
        runner.withBean("brokenSchemaSync", MatchRatingTables.SchemaSync.class, () -> dataSource -> {
            throw new java.sql.SQLException("建表失败（测试注入）");
        }, definition -> definition.setPrimary(true)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("matchRatingSchema").hasStackTraceContaining("建表失败（测试注入）");
        });
        assertThat(redis.leaseAcquisitions()).as("第 5 步（占号）先于第 6 步（建表）").isEqualTo(1);
        assertThat(redis.leaseReleases()).as("启动失败：已占的号交还").isEqualTo(1);
    }

    // ================================================================ 拒绝启动：少装一包

    @Test
    void 少装凑单这一包_拒启_不让排队照收而永不成局() {
        without(MatcherConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("matchLifecycle").hasStackTraceContaining(MatcherControl.class.getName());
        });
    }

    @Test
    void 少装评分这一包_拒启() {
        without(RatingConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasMessageContaining("No qualifying bean of type 'com.game.match.");
        });
    }

    @Test
    void 少装开局管线这一包_拒启() {
        without(GatherConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasMessageContaining("No qualifying bean of type 'com.game.match.gather.");
        });
    }

    @Test
    void 少装任何一个号的处理器_拒启_报错列出没有处理器的方法() {
        without(ReissueConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("拒绝启动").hasMessageContaining("没有处理器").hasMessageContaining("[RequestBattleTicket]");
        });
        without(InlineHandlers.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[ListWatchableBattles, NotifyChallengeInvite, NotifyChallengeResult, WatchBattle]");
        });
        without(ChallengeConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).hasMessageContaining("[ChallengePlayer, RespondChallenge]");
        });
    }

    @Test
    void 同一个启停口出现两个bean_拒启_不猜用哪个() {
        MatcherControl second = new MatcherControl() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }
        };

        runner.withBean("secondMatcherControl", MatcherControl.class, () -> second).run(context -> {
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
    private static int adminStatus(AssertableApplicationContext context, String path, String token) throws Exception {
        FilterRegistrationBean<MatchAdminAuthFilter> registration = context.getBean(FilterRegistrationBean.class);
        assertThat(registration.getUrlPatterns()).as("只挂在 /admin/* 上，不碰 actuator").containsExactly("/admin/*");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        if (token != null) {
            request.addHeader(MatchAdminAuthFilter.TOKEN_HEADER, token);
        }
        request.addHeader(MatchAdminAuthFilter.OPERATOR_HEADER, "tester");
        MockHttpServletResponse response = new MockHttpServletResponse();
        registration.getFilter().doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void 管理口过滤器_令牌取环境变量_没配503_配了按运行模式放行或403_两个dev口的路径都在它管辖之内(CapturedOutput output) {
        String rating = DevRatingController.PATH_PREFIX + "/1";
        String activity = DevActivityBattleController.PATH;
        assertThat(List.of(rating, activity)).as("两个控制器的路径都在过滤器的 dev 前缀之下")
                .allSatisfy(path -> assertThat(path).startsWith(MatchAdminAuthFilter.DEV_PREFIX));
        assertThat(rating).startsWith(MatchAdminAuthFilter.DEV_RATING_PREFIX);
        assertThat(activity).isEqualTo(MatchAdminAuthFilter.DEV_ACTIVITY_BATTLE_PATH);

        runner.run(context -> {
            assertThat(adminStatus(context, rating, "anything")).as("XM_ADMIN_TOKEN 为空").isEqualTo(503);
            assertThat(adminStatus(context, activity, "anything")).isEqualTo(503);
        });
        assertThat(output.getOut()).contains("运维令牌 XM_ADMIN_TOKEN 未配置：管理端口 /admin/** 一律 503");

        runner.withPropertyValues("XM_ADMIN_TOKEN=tok-for-context-test").run(context -> {
            for (String path : List.of(rating, activity)) {
                assertThat(adminStatus(context, path, "tok-for-context-test")).as("运行模式 test：放行 %s", path).isEqualTo(200);
                assertThat(adminStatus(context, path, "wrong")).isEqualTo(401);
                assertThat(adminStatus(context, path, null)).isEqualTo(401);
            }
        });
        runner.withPropertyValues("XM_ADMIN_TOKEN=tok-for-context-test", "xm.run-mode=prod").run(context -> {
            for (String path : List.of(rating, activity)) {
                assertThat(adminStatus(context, path, "tok-for-context-test")).as("prod：dev 口 403 %s", path).isEqualTo(403);
                assertThat(adminStatus(context, path, "wrong")).as("令牌仍是第一道闸").isEqualTo(401);
            }
        });
    }

    // ================================================================ 启停次序（整个上下文）

    /** 只记停机时被等了多久的开局管线（标成首选，盖过真的）。 */
    private ApplicationContextRunner withRecordingGathers(ApplicationContextRunner base, java.util.function.Function<Duration, Boolean> onAwaitIdle) {
        return base.withBean("recordingGatherLauncher", GatherLauncher.class, () -> new GatherLauncher() {
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
                return onAwaitIdle.apply(timeout);
            }
        }, definition -> definition.setPrimary(true));
    }

    @Test
    void 启停次序_应用已启动才起凑单再起评分消费_关闭时停凑单_排空工作池_等gather_停评分消费_最后还租约() {
        withRecordingGathers(withRecordingBackground(runner), timeout -> {
            events.add("gathers.awaitIdle(" + timeout.toSeconds() + "s)");
            return true;
        }).run(context -> {
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
        withRecordingGathers(withRecordingBackground(runner), timeout -> {
            try {
                ((MatchWorkers) workersHolder[0]).execute(() -> { });
                seen.add("workers.accepting");
            } catch (RejectedExecutionException e) {
                seen.add("workers.closed");
            }
            return true;
        }).run(context -> {
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
