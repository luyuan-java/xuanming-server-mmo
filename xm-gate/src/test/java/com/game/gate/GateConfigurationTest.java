package com.game.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.gate.session.MessageRoutes;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

/**
 * gate 接后端的四处必须对得上（接入一个新后端要改齐，批次 6.4 接 xm-match 时补的钉子）：
 * {@link MessageRoutes#SERVICE_BACKENDS}（服务名 → 域）、{@link GateConfiguration} 里每个域一个 {@code @DubboReference}、
 * 交给 {@link GateNode} 的后端表（{@link GateConfiguration#backends}）、两份配置文件里的直连地址（local 直连、nacos 置空）。
 * 漏掉哪一处进程都照常起来，只有发那个服务的号才看得出（缺引用 / 缺后端表项 → 23 {1003}；nacos 下没置空 → 绕过注册中心去连本机）。
 *
 * <p>只做装配层面的核对，不起 Spring 上下文、不起 Dubbo（引用在后端不在 / 晚起 / 重启时的行为由 {@code BackendReconnectTest} 用真 Triple 钉）。
 */
class GateConfigurationTest {

    /** gate 现在接的全部客户端消息后端域（= Dubbo group）与它们在本机切片里的 Dubbo 端口。新增后端在这里加一行。 */
    private static final Map<String, Integer> LOCAL_PORTS = new TreeMap<>(Map.of(
            DubboGroups.LOGIN, 20881,
            DubboGroups.FRIEND, 20883,
            DubboGroups.CHAT, 20884,
            DubboGroups.TEAM, 20885,
            DubboGroups.GUILD, 20886,
            DubboGroups.TRADE, 20887,
            DubboGroups.MATCH, 20888));

    /** 只用来认「是哪一个」的后端替身。 */
    private record Named(String name) implements ClientMessageService {
        @Override
        public CompletableFuture<ClientReply> handle(ClientCall call) {
            throw new UnsupportedOperationException(name);
        }

        @Override
        public CompletableFuture<Ack> sessionClosed(SessionClosed event) {
            throw new UnsupportedOperationException(name);
        }

        @Override
        public CompletableFuture<Ack> abandonEnter(AbandonedEnter event) {
            throw new UnsupportedOperationException(name);
        }
    }

    @Test
    void 后端表覆盖路由表里login以外的每个域_每个域拿到的是自己的那个引用() {
        Named friend = new Named("friend");
        Named chat = new Named("chat");
        Named team = new Named("team");
        Named guild = new Named("guild");
        Named trade = new Named("trade");
        Named match = new Named("match");

        Map<String, ClientMessageService> backends = GateConfiguration.backends(friend, chat, team, guild, trade, match);

        assertThat(backends).containsOnly(
                Map.entry(DubboGroups.FRIEND, friend),
                Map.entry(DubboGroups.CHAT, chat),
                Map.entry(DubboGroups.TEAM, team),
                Map.entry(DubboGroups.GUILD, guild),
                Map.entry(DubboGroups.TRADE, trade),
                Map.entry(DubboGroups.MATCH, match));
        assertThat(backends.get("match")).as("6.4：MatchService 的 10 个号转给 xm-match 的引用").isSameAs(match);
        // 与路由表的关系：路由表指向的域里，除了 login（单独传给 GateNode）都必须在这张表里，也不能多
        Set<String> routed = new TreeSet<>(MessageRoutes.SERVICE_BACKENDS.values());
        assertThat(routed).contains(DubboGroups.LOGIN);
        routed.remove(DubboGroups.LOGIN);
        assertThat(backends.keySet()).containsExactlyInAnyOrderElementsOf(routed);
        assertThat(backends).as("login 不进这张表：它的调用占会话唯一的在途位、带会话指令").doesNotContainKey(DubboGroups.LOGIN);
        assertThat(backends).as("没有的域在 dispatcher 里走缺省分支，不能有人把它配成后端").doesNotContainKey(MessageRoutes.BACKEND_UNSUPPORTED);
    }

    @Test
    void 每个后端域恰好一个Dubbo引用_group等于域_不检查可用性_直连地址取同名配置项_handle不重试() {
        Map<String, Method> references = new TreeMap<>();
        for (Method method : GateConfiguration.class.getDeclaredMethods()) {
            DubboReference reference = method.getAnnotation(DubboReference.class);
            if (reference == null) {
                continue;
            }
            assertThat(references.put(reference.group(), method)).as("group %s 只有一个引用", reference.group()).isNull();
        }
        assertThat(references.keySet()).as("引用的 group 集合 = 路由表指向的域").containsExactlyElementsOf(LOCAL_PORTS.keySet())
                .containsExactlyInAnyOrderElementsOf(Set.copyOf(MessageRoutes.SERVICE_BACKENDS.values()));

        references.forEach((group, method) -> {
            DubboReference reference = method.getAnnotation(DubboReference.class);
            assertThat(method.getName()).as("bean 名（gateNode 按它 @Qualifier 注入）").isEqualTo(group + "ClientMessageService");
            assertThat(method.isAnnotationPresent(Bean.class)).as(method.getName() + " 是 @Bean").isTrue();
            assertThat(method.getReturnType()).isEqualTo(ReferenceBean.class);
            assertThat(((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments())
                    .as(method.getName() + " 引用的接口").containsExactly(ClientMessageService.class);
            // 后端没起来时 gate 照常启动（那个后端的消息回信封 1003，其余不受影响）
            assertThat(reference.check()).as(group + " check").isFalse();
            // local profile 直连、nacos profile 置空走注册中心：缺省值必须是空串（配置项整个没有时也走注册中心，不是连一个写死的地址）
            assertThat(reference.url()).as(group + " url").isEqualTo("${xm.dubbo." + group + "-url:}");
            // 客户端消息不幂等：Dubbo 缺省的 failover 会在超时后重发同一次调用
            assertThat(reference.methods()).as(group + " 的方法级配置").singleElement().satisfies(handle -> {
                assertThat(handle.name()).isEqualTo("handle");
                assertThat(handle.retries()).as(group + " handle 不重试").isZero();
            });
            assertThat(reference.timeout()).as(group + " 不单独设超时：取 dubbo.consumer.timeout").isEqualTo(-1);
        });
    }

    @Test
    void gateNode按bean名注入每个域的引用_匹配后端也在其中() throws NoSuchMethodException {
        Method gateNode = Arrays.stream(GateConfiguration.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("gateNode")).findFirst().orElseThrow(() -> new NoSuchMethodException("gateNode"));
        List<String> qualifiers = new ArrayList<>();
        for (Parameter parameter : gateNode.getParameters()) {
            if (parameter.getType() == ClientMessageService.class) {
                Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
                assertThat(qualifier).as("ClientMessageService 有 7 个 bean，参数 %s 必须按名注入", parameter.getName()).isNotNull();
                assertThat(parameter.getName()).as("形参名与 bean 名一致（交给 backends() 时不串位）").isEqualTo(qualifier.value());
                qualifiers.add(qualifier.value());
            }
        }
        assertThat(qualifiers).containsExactly("loginClientMessageService", "friendClientMessageService", "chatClientMessageService",
                "teamClientMessageService", "guildClientMessageService", "tradeClientMessageService", "matchClientMessageService");
        // backends() 的形参次序与 gateNode 里除 login 之外的注入次序相同（两边都是 friend、chat、team、guild、trade、match）
        Method backends = Arrays.stream(GateConfiguration.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("backends")).findFirst().orElseThrow(() -> new NoSuchMethodException("backends"));
        assertThat(Arrays.stream(backends.getParameters()).map(p -> p.getName() + "ClientMessageService"))
                .containsExactlyElementsOf(qualifiers.subList(1, qualifiers.size()));
    }

    @Test
    void 本机配置里每个域都有直连地址_端口与各后端的缺省一致_nacos配置把它们全部置空() {
        Properties local = yaml("application.yaml");
        Properties nacos = yaml("application-nacos.yaml");

        LOCAL_PORTS.forEach((group, port) -> {
            String key = "xm.dubbo." + group + "-url";
            assertThat(local.getProperty(key)).as("application.yaml 的 %s", key).isEqualTo("tri://127.0.0.1:" + port);
            assertThat(nacos).as("application-nacos.yaml 必须有 %s（不置空就会绕过注册中心直连本机）", key).containsKey(key);
            assertThat(nacos.getProperty(key)).as("application-nacos.yaml 的 %s", key).isEmpty();
        });
        assertThat(local.getProperty("xm.dubbo.match-url")).as("xm-match 的 Dubbo 端口（XM_MATCH_RPC_PORT 的缺省）").isEqualTo("tri://127.0.0.1:20888");
        // 两份文件里没有多出来的直连地址（改了域名而忘了删旧键时，这里看得出）
        for (Properties file : List.of(local, nacos)) {
            assertThat(file.stringPropertyNames().stream().filter(k -> k.startsWith("xm.dubbo.")))
                    .containsExactlyInAnyOrderElementsOf(LOCAL_PORTS.keySet().stream().map(g -> "xm.dubbo." + g + "-url").toList());
        }
        assertThat(LOCAL_PORTS.values()).as("同机各后端端口互不相同").doesNotHaveDuplicates();
        // gate 调后端的超时：5 s（同基线路由服 ForwardTimeoutMs；xm-match 的整请求预算 4.5 s 排在它之内，match-spec §8.4）
        assertThat(local.getProperty("dubbo.consumer.timeout")).isEqualTo("5000");
        assertThat(nacos.getProperty("dubbo.registry.address")).startsWith("nacos://");
        assertThat(local.getProperty("dubbo.registry.address")).isEqualTo("N/A");
    }

    /** 把类路径上的一份 yaml 摊平成属性（键用点号连接）；文件不存在直接失败。 */
    private static Properties yaml(String name) {
        ClassPathResource resource = new ClassPathResource(name);
        assertThat(resource.exists()).as("类路径上有 %s", name).isTrue();
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource);
        Properties properties = factory.getObject();
        assertThat(properties).as(name).isNotNull();
        return properties;
    }
}
