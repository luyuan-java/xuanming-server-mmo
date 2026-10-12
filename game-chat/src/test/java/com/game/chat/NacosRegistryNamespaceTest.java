package com.game.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.util.EnvironmentUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * nacos profile 下 Nacos namespace 的显式配置写法，钉住 Dubbo <b>实际</b>读到的注册中心配置（architecture.md §6.2「Nacos」）：Nacos namespace
 * 不随部署命名空间 {@code game.namespace} 自动推导，缺省落在 public；多个部署共用一个 Nacos 时由部署显式给。不连 Nacos、不起 Dubbo 服务。
 *
 * <p>生产进程里 Dubbo 从两条路径拿配置，两条都钉：
 * <ul>
 *   <li><b>Spring → Dubbo</b>：Dubbo 的 Spring 集成（{@code DubboContextPostProcessor}）用 {@link EnvironmentUtils#filterDubboProperties} 把 Spring
 *       Environment 里<b>键名以 {@code dubbo.} 开头</b>的属性交给 Dubbo。本类在同一个 JVM 里照这条路径建出 {@link RegistryConfig}，核对地址与
 *       {@code parameters.namespace}（{@code -D} / 命令行参数 / {@code SPRING_APPLICATION_JSON} 是键名原样的属性源，生效；环境变量名不以 {@code dubbo.}
 *       开头，在这条路径上被滤掉）。</li>
 *   <li><b>Dubbo 自己读进程的环境变量</b>（{@code EnvironmentConfiguration}，即 {@code System.getenv()}）：同一个 JVM 里改不了进程环境，所以起一个设了
 *       真实环境变量的子 JVM（{@link DubboRegistryEnvProbe}）核对。Dubbo 3.3.6 按 OS 风格键名能匹配到 {@code DUBBO_REGISTRY_PARAMETERS_NAMESPACE}
 *       （{@code dubbo.registry} 的子属性里有 {@code parameters-namespace} / {@code PARAMETERS_NAMESPACE}），但取 Map 型的 {@code parameters} 时第二层
 *       换算会再补 {@code DUBBO_} 前缀、对不上，得到空表：<b>Dubbo 不会把系统环境变量映射成 Map 型的 parameters</b>。同一个子进程以
 *       {@code -Ddubbo.registry.parameters.namespace} 启动时 parameters 带 namespace，作对照（说明探针本身看得见这个参数）。</li>
 * </ul>
 * 两条路径上环境变量写法都不生效（Dubbo 3.3.6，2026-10-12）；方括号写法
 * {@code dubbo.registry.parameters[namespace]} 被解析成空表，同样不生效。哪天 Dubbo 改了其中任一条路径，对应的用例会失败，届时同步改
 * architecture.md §6.2 与 env.example；升级 Dubbo 时这几例照常跑，不需要另外人工复核。
 */
class NacosRegistryNamespaceTest {

    @SpringBootConfiguration
    static class App {
    }

    /** 本机进程环境去掉会影响注册中心配置的变量（GAME_NACOS_ADDR、DUBBO_*），用例只看自己给的值。 */
    private static Map<String, Object> hermeticEnv() {
        Map<String, Object> env = new HashMap<>(System.getenv());
        env.keySet().removeIf(NacosRegistryNamespaceTest::affectsRegistry);
        return env;
    }

    private static boolean affectsRegistry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("GAME_NACOS_ADDR") || upper.startsWith("DUBBO");
    }

    /**
     * 带 nacos profile 起最小上下文（只加载 game-chat 的 application*.yaml，不装配任何 bean），按 Dubbo 的 Spring 集成同一条路径交给 Dubbo，
     * 返回 Dubbo 建出的唯一一个注册中心配置。{@code env} 换掉 Spring 看到的环境变量。
     */
    private static RegistryConfig dubboRegistry(Map<String, Object> env, String... args) {
        SpringApplication app = new SpringApplication(App.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setRegisterShutdownHook(false);
        app.setAdditionalProfiles("nacos");
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        app.setEnvironment(environment);
        String[] all = Stream.concat(Stream.of("--spring.main.web-application-type=none", "--spring.main.keep-alive=false"), Arrays.stream(args)).toArray(String[]::new);
        try (ConfigurableApplicationContext ctx = app.run(all)) {
            FrameworkModel framework = new FrameworkModel();
            try {
                ApplicationModel application = framework.newApplication();
                application.getModelEnvironment().getAppConfigMap().putAll(EnvironmentUtils.filterDubboProperties(ctx.getEnvironment()));
                application.getApplicationConfigManager().loadConfigsOfTypeFromProps(RegistryConfig.class);
                Collection<RegistryConfig> registries = application.getApplicationConfigManager().getRegistries();
                assertThat(registries).hasSize(1);
                return registries.iterator().next();
            } finally {
                framework.destroy();
            }
        }
    }

    private static Map<String, String> parameters(RegistryConfig registry) {
        return registry.getParameters() == null ? Map.of() : registry.getParameters();
    }

    @Test
    void 缺省不带namespace_落在public_与以前相同() {
        RegistryConfig registry = dubboRegistry(hermeticEnv());
        assertThat(registry.getAddress()).isEqualTo("nacos://127.0.0.1:8848");
        assertThat(parameters(registry)).doesNotContainKey("namespace");
    }

    @Test
    void 部署命名空间不自动推导Nacos_namespace() {
        assertThat(parameters(dubboRegistry(hermeticEnv(), "--game.namespace=t1"))).doesNotContainKey("namespace");
    }

    @Test
    void Dubbo配置项dubbo_registry_parameters_namespace_经属性给出时生效() {
        // 命令行参数、-D 系统属性、SPRING_APPLICATION_JSON 都是键名原样的属性源
        assertThat(parameters(dubboRegistry(hermeticEnv(), "--dubbo.registry.parameters.namespace=t1"))).containsEntry("namespace", "t1");
    }

    @Test
    void GAME_NACOS_ADDR里带namespace查询参数时_注册中心地址带上它() {
        Map<String, Object> env = hermeticEnv();
        env.put("GAME_NACOS_ADDR", "127.0.0.1:8848?namespace=t1");
        assertThat(dubboRegistry(env).getAddress()).isEqualTo("nacos://127.0.0.1:8848?namespace=t1");
    }

    @Test
    void 前提_环境变量宽松名DUBBO_REGISTRY_PARAMETERS_NAMESPACE在Spring到Dubbo这条路径上不生效_Spring认而Dubbo不认() {
        Map<String, Object> env = hermeticEnv();
        env.put("DUBBO_REGISTRY_PARAMETERS_NAMESPACE", "t1");
        ConfigurableEnvironment spring = new StandardEnvironment();
        spring.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        assertThat(spring.getProperty("dubbo.registry.parameters.namespace")).as("Spring 的宽松查找认得").isEqualTo("t1");
        assertThat(parameters(dubboRegistry(env))).as("Dubbo 的 Spring 集成只交出键名以 dubbo. 开头的属性：环境变量名对不上，静默落进 public")
                .doesNotContainKey("namespace");
    }

    /** 在子 JVM 里跑 {@link DubboRegistryEnvProbe}：{@code env} 加进（已去掉 GAME_NACOS_ADDR / DUBBO_* 的）进程环境，{@code jvmArgs} 放在主类之前。 */
    private static String probe(Path dir, Map<String, String> env, String... jvmArgs) throws IOException, InterruptedException {
        // 类路径经 @argfile 传（Windows 命令行长度有限）；argfile 里引号内的反斜杠是转义符，要双写
        Path argFile = dir.resolve("probe-" + System.nanoTime() + ".args");
        Files.writeString(argFile, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n", StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("@" + argFile);
        command.addAll(List.of(jvmArgs));
        command.add(DubboRegistryEnvProbe.class.getName());
        Path output = dir.resolve(argFile.getFileName() + ".out");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().keySet().removeIf(NacosRegistryNamespaceTest::affectsRegistry);
        builder.environment().putAll(env);
        Process process = builder.start();
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String text = Files.readString(output, StandardCharsets.UTF_8);
        assertThat(exited).as("子进程按时退出，输出：%s", text).isTrue();
        assertThat(process.exitValue()).as("子进程退出码，输出：%s", text).isZero();
        return text.lines().filter(line -> line.startsWith(DubboRegistryEnvProbe.PREFIX)).findFirst()
                .orElseThrow(() -> new AssertionError("子进程没有输出结果行：" + text));
    }

    @Test
    void 前提_Dubbo自己读进程环境变量时_DUBBO_REGISTRY_PARAMETERS_NAMESPACE同样不进parameters_对照的D系统属性生效(@TempDir Path dir)
            throws Exception {
        assertThat(probe(dir, Map.of())).as("什么都不给").isEqualTo(DubboRegistryEnvProbe.PREFIX + "address=nacos://127.0.0.1:8848 parameters={} env=null");
        assertThat(probe(dir, Map.of("DUBBO_REGISTRY_PARAMETERS_NAMESPACE", "t1"))).as("真实的进程环境变量：Dubbo 3.3.6 不把它映射成 Map 型的 parameters")
                .isEqualTo(DubboRegistryEnvProbe.PREFIX + "address=nacos://127.0.0.1:8848 parameters={} env=t1");
        assertThat(probe(dir, Map.of(), "-Ddubbo.registry.parameters.namespace=t2")).as("对照：同一个探针，-D 系统属性生效")
                .isEqualTo(DubboRegistryEnvProbe.PREFIX + "address=nacos://127.0.0.1:8848 parameters={namespace=t2} env=null");
    }

    @Test
    void 前提_方括号写法不生效() {
        assertThat(parameters(dubboRegistry(hermeticEnv(), "--dubbo.registry.parameters[namespace]=t1"))).doesNotContainKey("namespace");
    }
}
