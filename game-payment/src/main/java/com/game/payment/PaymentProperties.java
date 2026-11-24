package com.game.payment;

import com.game.common.RunMode;
import com.game.payment.channel.ChannelTypes;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 支付服务配置与启动期安全校验（对齐 Go {@code payment/internal/config/config.go}）：
 * 密钥只通过环境变量注入，非 dev/test 模式强制拒绝 devpay、HTTP 回调地址、微信小游戏沙箱与未开合规闸的 App 真钱渠道。
 */
@ConfigurationProperties(prefix = "game.payment")
public class PaymentProperties {

    public static final String GATE_NONE = "none";
    public static final String GATE_REALNAME = "realname";
    public static final String GATE_OVERSEAS = "overseas";

    /** 各发行形态允许挂接的渠道白名单（iOS 审核 3.1.1 仅允许 Apple IAP）。 */
    public static final Map<String, Set<String>> PLATFORM_ALLOWED_CHANNELS = Map.of(
            "android_app", Set.of("wechat_pay", "alipay", "google", "devpay"),
            "ios_app", Set.of("apple", "devpay"),
            "wechat_minigame", Set.of("wechat_minigame", "devpay"),
            "douyin_minigame", Set.of("douyin_minigame", "devpay"),
            "desktop", Set.of("devpay"));

    private boolean workersEnabled = true;
    private boolean verifySchema = true;
    private String notifyBaseUrl = "";
    private String minorSpendingGate = GATE_NONE;
    private MallProps mall = new MallProps();
    private List<PlatformProps> platforms = new ArrayList<>();
    private List<ProductProps> products = new ArrayList<>();
    private DevPayProps devpay = new DevPayProps();
    private WechatPayProps wechatPay = new WechatPayProps();
    private AlipayProps alipay = new AlipayProps();
    private WechatMinigameProps wechatMinigame = new WechatMinigameProps();
    private DouyinMinigameProps douyinMinigame = new DouyinMinigameProps();
    private AppleProps apple = new AppleProps();
    private GoogleProps google = new GoogleProps();

    public void validate(RunMode runMode) {
        boolean relaxed = runMode == RunMode.DEV || runMode == RunMode.TEST;
        checkBaseUrl("game.payment.mall.base-url", mall.getBaseUrl(), true);
        if (mall.getTimeoutMs() < 0 || (mall.getTimeoutMs() > 0 && (mall.getTimeoutMs() < 500 || mall.getTimeoutMs() > 15000))) {
            throw new IllegalStateException("game.payment.mall.timeout-ms must be 0 or between 500 and 15000");
        }
        if (wechatPay.isEnabled() || alipay.isEnabled()) {
            checkBaseUrl("game.payment.notify-base-url", notifyBaseUrl, relaxed);
        }
        if (devpay.isEnabled() && !relaxed) {
            throw new IllegalStateException("game.payment.devpay is only allowed in dev/test run-mode");
        }
        if ((!isBlank(wechatPay.getEndpoint()) || !isBlank(alipay.getGateway())) && !relaxed) {
            throw new IllegalStateException("overriding WeChat Pay endpoint or Alipay gateway is only allowed in dev/test run-mode");
        }
        if (wechatMinigame.isEnabled() && wechatMinigame.getEnv() == 1 && !relaxed) {
            throw new IllegalStateException("WeChat mini-game sandbox (env=1) is only allowed in dev/test run-mode");
        }
        switch (minorSpendingGate == null ? GATE_NONE : minorSpendingGate) {
            case GATE_REALNAME, GATE_OVERSEAS -> {}
            case GATE_NONE -> {
                if (!relaxed) {
                    for (String appCh : List.of("wechat_pay", "alipay", "apple", "google")) {
                        if (isChannelEnabled(appCh)) {
                            throw new IllegalStateException("channel " + appCh
                                    + " cannot be enabled in prod with minor-spending-gate=none; use realname or overseas");
                        }
                    }
                }
            }
            default -> throw new IllegalStateException("unknown game.payment.minor-spending-gate: " + minorSpendingGate);
        }
        Set<String> seenPlatforms = new HashSet<>();
        for (PlatformProps p : platforms) {
            Set<String> allowed = PLATFORM_ALLOWED_CHANNELS.get(p.getPlatform());
            if (allowed == null) {
                throw new IllegalStateException("unknown platform in game.payment.platforms: " + p.getPlatform());
            }
            if (!seenPlatforms.add(p.getPlatform())) {
                throw new IllegalStateException("duplicate platform in game.payment.platforms: " + p.getPlatform());
            }
            for (String ch : p.getChannels()) {
                if (!allowed.contains(ch)) {
                    throw new IllegalStateException("channel " + ch + " cannot be offered on platform " + p.getPlatform());
                }
                if (!isChannelEnabled(ch)) {
                    throw new IllegalStateException("platform " + p.getPlatform() + " lists disabled channel " + ch);
                }
            }
        }
        Set<Long> seenSkus = new HashSet<>();
        for (ProductProps prod : products) {
            if (prod.getSkuId() <= 0 || !seenSkus.add(prod.getSkuId())) {
                throw new IllegalStateException("zero or duplicate sku-id in game.payment.products: " + prod.getSkuId());
            }
        }
    }

    public boolean isChannelEnabled(String name) {
        return switch (name) {
            case ChannelTypes.NAME_DEVPAY -> devpay.isEnabled();
            case ChannelTypes.NAME_WECHAT_PAY -> wechatPay.isEnabled();
            case ChannelTypes.NAME_ALIPAY -> alipay.isEnabled();
            case ChannelTypes.NAME_WECHAT_MINIGAME -> wechatMinigame.isEnabled();
            case ChannelTypes.NAME_DOUYIN_MINIGAME -> douyinMinigame.isEnabled();
            case ChannelTypes.NAME_APPLE -> apple.isEnabled();
            case ChannelTypes.NAME_GOOGLE -> google.isEnabled();
            default -> false;
        };
    }

    private static void checkBaseUrl(String field, String raw, boolean allowHttp) {
        if (isBlank(raw) || raw.contains("${")) {
            throw new IllegalStateException(field + " must be a valid absolute URL");
        }
        URI u;
        try {
            u = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(field + " is not a valid URI", e);
        }
        if (u.getHost() == null || u.getHost().isEmpty() || u.getUserInfo() != null
                || u.getRawQuery() != null || u.getRawFragment() != null) {
            throw new IllegalStateException(field + " must be scheme://host[:port] without query/fragment/userinfo");
        }
        if ("https".equals(u.getScheme())) {
            return;
        }
        if ("http".equals(u.getScheme()) && allowHttp) {
            return;
        }
        throw new IllegalStateException(field + " must use https outside dev/test run-mode");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ---- getters & setters ----

    public boolean isWorkersEnabled() { return workersEnabled; }
    public void setWorkersEnabled(boolean workersEnabled) { this.workersEnabled = workersEnabled; }
    public boolean isVerifySchema() { return verifySchema; }
    public void setVerifySchema(boolean verifySchema) { this.verifySchema = verifySchema; }
    public String getNotifyBaseUrl() { return notifyBaseUrl; }
    public void setNotifyBaseUrl(String notifyBaseUrl) { this.notifyBaseUrl = notifyBaseUrl; }
    public String getMinorSpendingGate() { return minorSpendingGate; }
    public void setMinorSpendingGate(String minorSpendingGate) { this.minorSpendingGate = minorSpendingGate; }
    public MallProps getMall() { return mall; }
    public void setMall(MallProps mall) { this.mall = mall; }
    public List<PlatformProps> getPlatforms() { return platforms; }
    public void setPlatforms(List<PlatformProps> platforms) { this.platforms = platforms; }
    public List<ProductProps> getProducts() { return products; }
    public void setProducts(List<ProductProps> products) { this.products = products; }
    public DevPayProps getDevpay() { return devpay; }
    public void setDevpay(DevPayProps devpay) { this.devpay = devpay; }
    public WechatPayProps getWechatPay() { return wechatPay; }
    public void setWechatPay(WechatPayProps wechatPay) { this.wechatPay = wechatPay; }
    public AlipayProps getAlipay() { return alipay; }
    public void setAlipay(AlipayProps alipay) { this.alipay = alipay; }
    public WechatMinigameProps getWechatMinigame() { return wechatMinigame; }
    public void setWechatMinigame(WechatMinigameProps wechatMinigame) { this.wechatMinigame = wechatMinigame; }
    public DouyinMinigameProps getDouyinMinigame() { return douyinMinigame; }
    public void setDouyinMinigame(DouyinMinigameProps douyinMinigame) { this.douyinMinigame = douyinMinigame; }
    public AppleProps getApple() { return apple; }
    public void setApple(AppleProps apple) { this.apple = apple; }
    public GoogleProps getGoogle() { return google; }
    public void setGoogle(GoogleProps google) { this.google = google; }

    public static class MallProps {
        private String baseUrl = "http://127.0.0.1:18120";
        private String paymentSubject = "payment-adapter";
        private String paymentSecret = "";
        private int timeoutMs = 2500;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getPaymentSubject() { return paymentSubject; }
        public void setPaymentSubject(String paymentSubject) { this.paymentSubject = paymentSubject; }
        public String getPaymentSecret() { return paymentSecret; }
        public void setPaymentSecret(String paymentSecret) { this.paymentSecret = paymentSecret; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class PlatformProps {
        private String platform = "";
        private List<String> channels = new ArrayList<>();
        public String getPlatform() { return platform; }
        public void setPlatform(String platform) { this.platform = platform; }
        public List<String> getChannels() { return channels; }
        public void setChannels(List<String> channels) { this.channels = channels; }
    }

    public static class ProductProps {
        private long skuId;
        private String title = "";
        private String apple = "";
        private String google = "";
        private String wechatMinigame = "";
        public long getSkuId() { return skuId; }
        public void setSkuId(long skuId) { this.skuId = skuId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getApple() { return apple; }
        public void setApple(String apple) { this.apple = apple; }
        public String getGoogle() { return google; }
        public void setGoogle(String google) { this.google = google; }
        public String getWechatMinigame() { return wechatMinigame; }
        public void setWechatMinigame(String wechatMinigame) { this.wechatMinigame = wechatMinigame; }
    }

    public static class TestProps {
        private boolean accept;
        private List<Long> players = new ArrayList<>();
        public boolean isAccept() { return accept; }
        public void setAccept(boolean accept) { this.accept = accept; }
        public List<Long> getPlayers() { return players; }
        public void setPlayers(List<Long> players) { this.players = players; }
    }

    public static class DevPayProps {
        private boolean enabled;
        private String secret = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    public static class WechatPayProps {
        private boolean enabled;
        private String appId = "";
        private String mchId = "";
        private String merchantSerialNo = "";
        private String privateKey = "";
        private String apiV3Key = "";
        private String platformPublicKeyId = "";
        private String platformPublicKey = "";
        private String description = "游戏商品";
        private String endpoint = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getMchId() { return mchId; }
        public void setMchId(String mchId) { this.mchId = mchId; }
        public String getMerchantSerialNo() { return merchantSerialNo; }
        public void setMerchantSerialNo(String merchantSerialNo) { this.merchantSerialNo = merchantSerialNo; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getApiV3Key() { return apiV3Key; }
        public void setApiV3Key(String apiV3Key) { this.apiV3Key = apiV3Key; }
        public String getPlatformPublicKeyId() { return platformPublicKeyId; }
        public void setPlatformPublicKeyId(String platformPublicKeyId) { this.platformPublicKeyId = platformPublicKeyId; }
        public String getPlatformPublicKey() { return platformPublicKey; }
        public void setPlatformPublicKey(String platformPublicKey) { this.platformPublicKey = platformPublicKey; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }

    public static class AlipayProps {
        private boolean enabled;
        private String appId = "";
        private String privateKey = "";
        private String publicKey = "";
        private String sellerId = "";
        private String gateway = "";
        private String subject = "游戏商品";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
        public String getSellerId() { return sellerId; }
        public void setSellerId(String sellerId) { this.sellerId = sellerId; }
        public String getGateway() { return gateway; }
        public void setGateway(String gateway) { this.gateway = gateway; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
    }

    public static class WechatMinigameProps {
        private boolean enabled;
        private String appId = "";
        private String appSecret = "";
        private String offerId = "";
        private String appKey = "";
        private int env;
        private String zoneId = "1";
        private String pushToken = "";
        private String encodingAesKey = "";
        private TestProps test = new TestProps();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getOfferId() { return offerId; }
        public void setOfferId(String offerId) { this.offerId = offerId; }
        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }
        public int getEnv() { return env; }
        public void setEnv(int env) { this.env = env; }
        public String getZoneId() { return zoneId; }
        public void setZoneId(String zoneId) { this.zoneId = zoneId; }
        public String getPushToken() { return pushToken; }
        public void setPushToken(String pushToken) { this.pushToken = pushToken; }
        public String getEncodingAesKey() { return encodingAesKey; }
        public void setEncodingAesKey(String encodingAesKey) { this.encodingAesKey = encodingAesKey; }
        public TestProps getTest() { return test; }
        public void setTest(TestProps test) { this.test = test; }
    }

    public static class DouyinMinigameProps {
        private boolean enabled;
        private String appId = "";
        private String pushToken = "";
        private String zoneId = "1";
        private String goodName = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getPushToken() { return pushToken; }
        public void setPushToken(String pushToken) { this.pushToken = pushToken; }
        public String getZoneId() { return zoneId; }
        public void setZoneId(String zoneId) { this.zoneId = zoneId; }
        public String getGoodName() { return goodName; }
        public void setGoodName(String goodName) { this.goodName = goodName; }
    }

    public static class AppleProps {
        private boolean enabled;
        private String bundleId = "";
        private long appAppleId;
        private String issuerId = "";
        private String keyId = "";
        private String privateKey = "";
        private String rootCertificates = "";
        private boolean allowSandbox;
        private TestProps test = new TestProps();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBundleId() { return bundleId; }
        public void setBundleId(String bundleId) { this.bundleId = bundleId; }
        public long getAppAppleId() { return appAppleId; }
        public void setAppAppleId(long appAppleId) { this.appAppleId = appAppleId; }
        public String getIssuerId() { return issuerId; }
        public void setIssuerId(String issuerId) { this.issuerId = issuerId; }
        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getRootCertificates() { return rootCertificates; }
        public void setRootCertificates(String rootCertificates) { this.rootCertificates = rootCertificates; }
        public boolean isAllowSandbox() { return allowSandbox; }
        public void setAllowSandbox(boolean allowSandbox) { this.allowSandbox = allowSandbox; }
        public TestProps getTest() { return test; }
        public void setTest(TestProps test) { this.test = test; }
    }

    public static class GoogleProps {
        private boolean enabled;
        private String packageName = "";
        private String clientEmail = "";
        private String privateKey = "";
        private String pushAudience = "";
        private String pushServiceAccount = "";
        private TestProps test = new TestProps();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPackageName() { return packageName; }
        public void setPackageName(String packageName) { this.packageName = packageName; }
        public String getClientEmail() { return clientEmail; }
        public void setClientEmail(String clientEmail) { this.clientEmail = clientEmail; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getPushAudience() { return pushAudience; }
        public void setPushAudience(String pushAudience) { this.pushAudience = pushAudience; }
        public String getPushServiceAccount() { return pushServiceAccount; }
        public void setPushServiceAccount(String pushServiceAccount) { this.pushServiceAccount = pushServiceAccount; }
        public TestProps getTest() { return test; }
        public void setTest(TestProps test) { this.test = test; }
    }
}
