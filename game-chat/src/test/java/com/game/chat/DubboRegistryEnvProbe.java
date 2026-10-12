package com.game.chat;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;

/**
 * {@link NacosRegistryNamespaceTest} 起的子进程入口：不经 Spring，只让 Dubbo 自己从<b>本进程真实的</b>系统属性与环境变量（Dubbo 的
 * {@code SystemConfiguration} / {@code EnvironmentConfiguration}）加载注册中心配置，打印一行 {@code PROBE address=… parameters=…}。
 * 末尾带上子进程实际看到的 {@code DUBBO_REGISTRY_PARAMETERS_NAMESPACE}。注册中心地址经应用配置给出（与 nacos profile 的 yaml 同值），不连 Nacos、不起 Dubbo 服务。
 */
public final class DubboRegistryEnvProbe {

    /** 结果行的前缀（Dubbo 自己的日志也可能打到标准输出）。 */
    static final String PREFIX = "PROBE ";

    private DubboRegistryEnvProbe() {
    }

    public static void main(String[] args) {
        int code;
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = framework.newApplication();
            application.getModelEnvironment().getAppConfigMap().put("dubbo.registry.address", "nacos://127.0.0.1:8848");
            application.getApplicationConfigManager().loadConfigsOfTypeFromProps(RegistryConfig.class);
            Collection<RegistryConfig> registries = application.getApplicationConfigManager().getRegistries();
            if (registries.size() == 1) {
                RegistryConfig registry = registries.iterator().next();
                Map<String, String> parameters = registry.getParameters() == null ? Map.of() : new TreeMap<>(registry.getParameters());
                // 顺带打出子进程实际看到的环境变量：确认「不生效」不是因为变量没传进来
                System.out.println(PREFIX + "address=" + registry.getAddress() + " parameters=" + parameters + " env="
                        + System.getenv("DUBBO_REGISTRY_PARAMETERS_NAMESPACE"));
                code = 0;
            } else {
                System.out.println(PREFIX + "registries=" + registries.size());
                code = 3;
            }
        } finally {
            framework.destroy();
        }
        System.out.flush();
        System.exit(code);
    }
}
