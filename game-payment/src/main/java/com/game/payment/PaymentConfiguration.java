package com.game.payment;

import com.game.api.DubboGroups;
import com.game.api.LoginAdminService;
import com.game.common.RunMode;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.payment.channel.AlipayChannel;
import com.game.payment.channel.AppleChannel;
import com.game.payment.channel.ChannelCrypto;
import com.game.payment.channel.ChannelTypes;
import com.game.payment.channel.DevPayChannel;
import com.game.payment.channel.DouyinMinigameChannel;
import com.game.payment.channel.GoogleChannel;
import com.game.payment.channel.PaymentChannel;
import com.game.payment.channel.WechatMinigameChannel;
import com.game.payment.channel.WechatPayChannel;
import com.game.payment.core.PaymentService;
import com.game.payment.core.PaymentService.Options;
import com.game.payment.core.PaymentService.ProductSpec;
import com.game.payment.core.PaymentService.TestPolicy;
import com.game.payment.core.PaymentWorkers;
import com.game.payment.core.RealNameReader;
import com.game.payment.mall.HttpMallClient;
import com.game.payment.mall.MallClient;
import com.game.payment.rpc.PaymentRpcService;
import com.game.payment.store.JdbcPaymentStore;
import com.game.payment.store.PaymentStore;
import com.game.proto.payment.PayChannel;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付微服务装配：配置启动期校验、SQL 存储、7 渠道装配、商城客户端、后台推进器与 Dubbo 入口。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfiguration {

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }

    @Bean
    public PaymentMetrics paymentMetrics(MeterRegistry registry) {
        return new PaymentMetrics(registry);
    }

    @Bean
    public PaymentStore paymentStore(DataSource dataSource, PaymentProperties props) {
        JdbcPaymentStore store = new JdbcPaymentStore(dataSource);
        if (props.isVerifySchema()) {
            store.verifySchema();
        }
        return store;
    }

    @Bean
    public MallClient mallClient(PaymentProperties props) {
        PaymentProperties.MallProps m = props.getMall();
        Duration timeout = m.getTimeoutMs() > 0 ? Duration.ofMillis(m.getTimeoutMs()) : Duration.ofMillis(2500);
        return new HttpMallClient(m.getBaseUrl(), m.getPaymentSubject(), m.getPaymentSecret(), timeout, Clock.systemUTC());
    }

    @Bean
    public PaymentService paymentService(PaymentProperties props, PaymentStore store, MallClient mall,
                                         ObjectProvider<RealNameReader> realNameReader, PaymentMetrics metrics,
                                         @Value("${game.run-mode:prod}") String runModeRaw) {
        RunMode runMode = RunMode.parse(runModeRaw);
        props.validate(runMode);
        Clock clock = Clock.systemUTC();
        List<PaymentChannel> channels = buildChannels(props, clock);

        Map<String, List<PayChannel>> platforms = new LinkedHashMap<>();
        for (PaymentProperties.PlatformProps p : props.getPlatforms()) {
            List<PayChannel> ids = new ArrayList<>();
            for (String chName : p.getChannels()) {
                PayChannel id = ChannelTypes.byName(chName);
                if (id != null) {
                    ids.add(id);
                }
            }
            platforms.put(p.getPlatform(), List.copyOf(ids));
        }

        Map<Long, ProductSpec> products = new HashMap<>();
        for (PaymentProperties.ProductProps p : props.getProducts()) {
            Map<String, String> chMap = new HashMap<>();
            if (!p.getApple().isEmpty()) chMap.put(ChannelTypes.NAME_APPLE, p.getApple());
            if (!p.getGoogle().isEmpty()) chMap.put(ChannelTypes.NAME_GOOGLE, p.getGoogle());
            if (!p.getWechatMinigame().isEmpty()) chMap.put(ChannelTypes.NAME_WECHAT_MINIGAME, p.getWechatMinigame());
            products.put(p.getSkuId(), new ProductSpec(p.getTitle(), chMap));
        }

        Map<String, TestPolicy> testPolicies = new HashMap<>();
        putTestPolicy(testPolicies, ChannelTypes.NAME_WECHAT_MINIGAME, props.getWechatMinigame().getTest());
        putTestPolicy(testPolicies, ChannelTypes.NAME_APPLE, props.getApple().getTest());
        putTestPolicy(testPolicies, ChannelTypes.NAME_GOOGLE, props.getGoogle().getTest());

        boolean realNameGate = PaymentProperties.GATE_REALNAME.equals(props.getMinorSpendingGate());
        Options opts = Options.defaults(platforms, products, testPolicies, realNameGate);
        return new PaymentService(store, mall, realNameReader.getIfAvailable(), channels, opts, metrics, clock);
    }

    private static void putTestPolicy(Map<String, TestPolicy> out, String chName, PaymentProperties.TestProps tp) {
        if (tp != null && tp.isAccept()) {
            out.put(chName, new TestPolicy(true, new HashSet<>(tp.getPlayers())));
        }
    }

    private static List<PaymentChannel> buildChannels(PaymentProperties props, Clock clock) {
        List<PaymentChannel> out = new ArrayList<>();
        String notifyBase = props.getNotifyBaseUrl() == null ? "" : props.getNotifyBaseUrl().replaceAll("/+$", "");
        if (props.getDevpay().isEnabled()) {
            out.add(new DevPayChannel(props.getDevpay().getSecret(), clock));
        }
        if (props.getWechatPay().isEnabled()) {
            var wp = props.getWechatPay();
            out.add(new WechatPayChannel(new WechatPayChannel.Config(
                    wp.getAppId(), wp.getMchId(), wp.getMerchantSerialNo(),
                    ChannelCrypto.parseRsaPrivateKey(wp.getPrivateKey()),
                    wp.getApiV3Key().getBytes(StandardCharsets.UTF_8),
                    wp.getPlatformPublicKeyId(),
                    ChannelCrypto.parseRsaPublicKey(wp.getPlatformPublicKey()),
                    notifyBase + "/notify/" + ChannelTypes.NAME_WECHAT_PAY,
                    wp.getDescription(), wp.getEndpoint(), null, clock)));
        }
        if (props.getAlipay().isEnabled()) {
            var ap = props.getAlipay();
            out.add(new AlipayChannel(new AlipayChannel.Config(
                    ap.getAppId(),
                    ChannelCrypto.parseRsaPrivateKey(ap.getPrivateKey()),
                    ChannelCrypto.parseRsaPublicKey(ap.getPublicKey()),
                    ap.getSellerId(), ap.getGateway(),
                    notifyBase + "/notify/" + ChannelTypes.NAME_ALIPAY,
                    ap.getSubject(), null, clock)));
        }
        if (props.getWechatMinigame().isEnabled()) {
            var wm = props.getWechatMinigame();
            out.add(new WechatMinigameChannel(new WechatMinigameChannel.Config(
                    wm.getAppId(), wm.getAppSecret(), wm.getOfferId(), wm.getAppKey(),
                    wm.getEnv(), wm.getZoneId(), wm.getPushToken(), wm.getEncodingAesKey(),
                    null, null, clock)));
        }
        if (props.getDouyinMinigame().isEnabled()) {
            var dy = props.getDouyinMinigame();
            out.add(new DouyinMinigameChannel(new DouyinMinigameChannel.Config(
                    dy.getAppId(), dy.getPushToken(), dy.getZoneId(), dy.getGoodName())));
        }
        if (props.getApple().isEnabled()) {
            var apple = props.getApple();
            out.add(new AppleChannel(new AppleChannel.Config(
                    apple.getBundleId(), apple.getAppAppleId(), apple.getIssuerId(), apple.getKeyId(),
                    ChannelCrypto.parseEcPrivateKey(apple.getPrivateKey()),
                    ChannelCrypto.parseCertificates(apple.getRootCertificates()),
                    apple.isAllowSandbox(), null, null, null, clock)));
        }
        if (props.getGoogle().isEnabled()) {
            var g = props.getGoogle();
            out.add(new GoogleChannel(new GoogleChannel.Config(
                    g.getPackageName(), g.getClientEmail(),
                    ChannelCrypto.parseRsaPrivateKey(g.getPrivateKey()),
                    g.getPushAudience(), g.getPushServiceAccount(),
                    null, null, null, null, clock)));
        }
        return out;
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService paymentRpcExecutor() {
        return new ThreadPoolExecutor(4, 16, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), r -> {
            Thread t = new Thread(r, "payment-rpc");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public PaymentRpcService paymentRpcService(PaymentService service, MessageIdRegistry registry,
                                               ExecutorService paymentRpcExecutor) {
        return new PaymentRpcService(service, registry, paymentRpcExecutor);
    }

    @Bean
    public PaymentWorkers paymentWorkers(PaymentService service, PaymentProperties props) {
        return new PaymentWorkers(service, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofMinutes(5), props.isWorkersEnabled());
    }

    /** 显式开启 minor-spending-gate=realname 时，经 Dubbo 接入 game-login 实时查询账号实名 pi。 */
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "game.payment.minor-spending-gate", havingValue = PaymentProperties.GATE_REALNAME)
    static class RealNameReaderConfiguration {

        @org.apache.dubbo.config.annotation.DubboReference(
                group = DubboGroups.LOGIN, check = false, url = "${game.dubbo.login-url:}", timeout = 2000, retries = 0)
        private LoginAdminService loginAdminService;

        @Bean
        RealNameReader realNameReader() {
            return playerId -> {
                String pi = loginAdminService.realNamePiOfPlayer(playerId).join();
                boolean verified = pi != null && !pi.isBlank();
                return new RealNameReader.RealNameStatus(verified, verified ? pi : "");
            };
        }
    }
}
