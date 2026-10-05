package com.game.api.asset;

import java.time.Duration;
import java.util.Map;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编程式 Dubbo 用的独立模型（自己的 {@link FrameworkModel} → 应用 → 模块），资产通道的 scene 提供方与调用方客户端缓存各建一个：
 * 生命周期完全由持有者控制（导出 / 反导出的时机要排进 scene 的启停顺序，guild-economy-spec §4.6 第 6 条），
 * 不与进程里 Spring 管理的 Dubbo（xm-guild 的 GuildService）共用配置、也不被它的关停顺序牵着走。
 *
 * <p>注意 Dubbo 的「缺省框架模型」是进程里<b>第一个</b>创建的 {@link FrameworkModel}：在有 Spring Dubbo 的进程里，
 * 本类必须在 Spring 的 Dubbo 初始化之后才创建（客户端缓存因此在第一次调用时才惰性创建），否则 Spring 会误用它当缺省模型。
 * 只有本类的进程（xm-scene）不受影响。
 *
 * <p>QoS 端口关闭（同机多进程会抢 22222）；日志走 slf4j。线程安全：创建后只读，{@link #close} 幂等。
 */
public final class IsolatedDubboModule implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IsolatedDubboModule.class);
    private static final String SHUTDOWN_WAIT_KEY = "dubbo.service.shutdown.wait";

    private final FrameworkModel framework;
    private final ModuleModel module;

    private IsolatedDubboModule(FrameworkModel framework, ModuleModel module) {
        this.framework = framework;
        this.module = module;
    }

    /**
     * 缺省的 Dubbo 停服等待（{@code dubbo.service.shutdown.wait}）：反导出前等服务空闲至多它的 1/3。缺省值 10 s 太长——资产通道反导出时
     * 玩家已写回（之后的请求只会得到 NOT_HERE），在途应答只需要一点时间写完；调用方总会重投。
     */
    public static final Duration DEFAULT_SHUTDOWN_WAIT = Duration.ofMillis(1500);

    /** @param applicationName Dubbo 应用名（进 URL 的 application 参数，便于在对端日志里认出调用方） */
    public static IsolatedDubboModule create(String applicationName) {
        return create(applicationName, DEFAULT_SHUTDOWN_WAIT);
    }

    /** @param shutdownWait Dubbo 停服等待（反导出前等服务空闲至多它的 1/3） */
    public static IsolatedDubboModule create(String applicationName, Duration shutdownWait) {
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = framework.newApplication();
            application.modelEnvironment().updateAppConfigMap(
                    Map.of(SHUTDOWN_WAIT_KEY, Long.toString(Math.max(0, shutdownWait.toMillis()))));
            ApplicationConfig config = new ApplicationConfig(applicationName);
            config.setQosEnable(false);
            config.setLogger("slf4j");
            // 不导出 Dubbo 内置的 MetadataService（register-mode = interface 时 ExporterDeployListener 跳过它）：没有注册中心、
            // 也不做应用级服务发现，用不到；缺省会在 dubbo 协议的缺省端口 20880 上多开一个不经调用方鉴权的端口（同机多进程还会互抢）。
            config.setRegisterMode("interface");
            application.getApplicationConfigManager().setApplication(config);
            return new IsolatedDubboModule(framework, application.newModule());
        } catch (RuntimeException e) {
            framework.destroy();
            throw e;
        }
    }

    public ModuleModel module() {
        return module;
    }

    /** 销毁整个框架模型（服务端口、客户端连接、线程池一并释放）。幂等，不抛异常。 */
    @Override
    public void close() {
        try {
            framework.destroy();
        } catch (RuntimeException e) {
            log.warn("销毁资产通道的 Dubbo 模型时出错（忽略）", e);
        }
    }
}
