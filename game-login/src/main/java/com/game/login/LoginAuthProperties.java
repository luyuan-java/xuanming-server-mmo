package com.game.login;

import com.game.login.auth.ProductionPasswordAuthenticator;
import com.game.login.auth.RedisPasswordFailureQuota;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 认证配置（{@code game.login.auth.*}，同 mmorpg login.yaml 的 PasswordAuth / AuthProviders）。没写的一项 = 不启用（fail-closed）。
 * 秘密（微信 app secret、Sa-Token Redis 口令）只从环境变量读，不进配置文件。
 *
 * @param password 生产口令认证（读 {@code account.password_hash} 的 Argon2id）；与开发口令（{@code game.login.mode=dev}）互斥
 * @param satoken  Sa-Token：读 Sa-Token 认证服务的 Redis
 * @param wechat   微信开放平台（app secret 取环境变量 {@value #WECHAT_SECRET_ENV}）
 * @param qq       QQ 互联
 * @param netease  网易（两版都是占位：恒失败）
 */
@ConfigurationProperties("game.login.auth")
public record LoginAuthProperties(PasswordAuth password, SaToken satoken, WeChat wechat, Qq qq, Netease netease,
                                  WeChatMinigame wechatMinigame, DouyinMinigame douyinMinigame,
                                  Apple apple, Google google, Passport passport) {

    public static final String WECHAT_SECRET_ENV = "GAME_WECHAT_APP_SECRET";
    public static final String WECHAT_MINIGAME_SECRET_ENV = "GAME_WECHAT_MINIGAME_APP_SECRET";
    public static final String DOUYIN_MINIGAME_SECRET_ENV = "GAME_DOUYIN_MINIGAME_APP_SECRET";
    public static final String APPLE_PRIVATE_KEY_ENV = "GAME_APPLE_PRIVATE_KEY_PEM";
    public static final String PASSPORT_ADMIN_SECRET_ENV = "GAME_PASSPORT_ADMIN_CLIENT_SECRET";
    public static final String SATOKEN_REDIS_PASSWORD_ENV = "GAME_SATOKEN_REDIS_PASSWORD";

    /** 兼容既有 5 参构造（单测用）。 */
    public LoginAuthProperties(PasswordAuth password, SaToken satoken, WeChat wechat, Qq qq, Netease netease) {
        this(password, satoken, wechat, qq, netease, null, null, null, null, null);
    }

    @ConstructorBinding
    public LoginAuthProperties {
        password = password == null ? new PasswordAuth(false, null, null) : password;
    }

    /**
     * @param enabled        启用生产口令认证
     * @param kdfConcurrency KDF 并发槽（缺省 2，1–8；每个占 64 MiB）
     * @param kdfWait        等槽上限（缺省 500 ms，最多 5 s）
     */
    public record PasswordAuth(boolean enabled, Integer kdfConcurrency, Duration kdfWait,
                               Integer failureLimit, Duration failureWindow, Duration failureLock) {

        public PasswordAuth(boolean enabled, Integer kdfConcurrency, Duration kdfWait) {
            this(enabled, kdfConcurrency, kdfWait, null, null, null);
        }

        @ConstructorBinding
        public PasswordAuth {
            kdfConcurrency = kdfConcurrency == null ? ProductionPasswordAuthenticator.DEFAULT_KDF_CONCURRENCY : kdfConcurrency;
            kdfWait = kdfWait == null ? ProductionPasswordAuthenticator.DEFAULT_KDF_WAIT : kdfWait;
            failureLimit = failureLimit == null ? 5 : failureLimit;
            failureWindow = failureWindow == null ? Duration.ofMinutes(15) : failureWindow;
            failureLock = failureLock == null ? Duration.ofMinutes(5) : failureLock;
            new RedisPasswordFailureQuota.Policy(failureLimit, failureWindow, failureLock);
        }
    }

    /**
     * @param redisAddress Sa-Token 用的 Redis（如 redis://127.0.0.1:6379）
     * @param database     库号（缺省 0）
     * @param tokenName    Sa-Token 键前缀（缺省 satoken）
     * @param loginType    Sa-Token 登录类型（缺省 login）
     */
    public record SaToken(String redisAddress, Integer database, String tokenName, String loginType) {

        public SaToken {
            if (redisAddress == null || redisAddress.isBlank()) {
                throw new IllegalArgumentException("game.login.auth.satoken.redis-address 必填");
            }
            database = database == null ? 0 : database;
            tokenName = tokenName == null || tokenName.isBlank() ? "satoken" : tokenName;
            loginType = loginType == null || loginType.isBlank() ? "login" : loginType;
        }
    }

    /**
     * @param appId          微信开放平台应用号
     * @param endpoint       空 = 生产地址（https://api.weixin.qq.com）；可指向本地假服务（http 只在 {@code game.login.mode=dev} 放行）
     * @param requireUnionId 缺省 true：微信没返回 unionid 就拒绝登录（故障码），不回落 openid（同 mmorpg {@code RequireUnionId}）。
     *                       openid 按应用区分，回落后同一玩家在第二个应用里会变成另一个账号且无法合并
     */
    public record WeChat(String appId, String endpoint, Boolean requireUnionId) {

        public WeChat {
            requireUnionId = requireUnionId == null || requireUnionId;
        }
    }

    /**
     * @param appId          QQ 互联应用号（校验应答的 client_id）
     * @param endpoint       空 = 生产地址（https://graph.qq.com）
     * @param requestUnionId 缺省 true：请求时带 unionid=1（应用须已申请 unionid 权限）
     * @param requireUnionId 缺省 true，语义同 {@link WeChat#requireUnionId()}；request-union-id=false 时必须显式写 false，否则拒绝启动
     */
    public record Qq(String appId, String endpoint, Boolean requestUnionId, Boolean requireUnionId) {

        public Qq {
            requestUnionId = requestUnionId == null || requestUnionId;
            requireUnionId = requireUnionId == null || requireUnionId;
            if (requireUnionId && !requestUnionId) {
                throw new IllegalArgumentException(
                        "game.login.auth.qq.request-union-id=false 时永远拿不到 unionid，require-union-id 必须同时显式写 false");
            }
        }
    }

    /** @param enabled 注册网易占位（恒失败，同基线） */
    public record Netease(boolean enabled) {
    }

    /** 微信小游戏登录配置（密钥取环境变量 {@value #WECHAT_MINIGAME_SECRET_ENV}，不得与微信 App 同 appId）。 */
    public record WeChatMinigame(String appId, String endpoint, Boolean requireUnionId) {
        public WeChatMinigame {
            requireUnionId = requireUnionId == null || requireUnionId;
        }
    }

    /** 抖音小游戏登录配置（密钥取环境变量 {@value #DOUYIN_MINIGAME_SECRET_ENV}）。 */
    public record DouyinMinigame(String appId, String endpoint, Boolean requireUnionId) {
        public DouyinMinigame {
            requireUnionId = requireUnionId == null || requireUnionId;
        }
    }

    /**
     * Sign in with Apple 登录与可选授权撤销配置（撤销私钥取环境变量 {@value #APPLE_PRIVATE_KEY_ENV}，PKCS#8 EC P-256 PEM）。
     */
    public record Apple(String clientId, String endpoint, String teamId, String keyId, String privateKeyEnv) {
        public Apple(String clientId, String endpoint) {
            this(clientId, endpoint, null, null, null);
        }

        @ConstructorBinding
        public Apple {
            privateKeyEnv = privateKeyEnv == null || privateKeyEnv.isBlank() ? APPLE_PRIVATE_KEY_ENV : privateKeyEnv;
        }

        public boolean revokeConfigured() {
            return (teamId != null && !teamId.isBlank()) || (keyId != null && !keyId.isBlank());
        }
    }

    /** Google OIDC 登录配置（{@code clientId} 必须以 {@code .apps.googleusercontent.com} 结尾）。 */
    public record Google(String clientId, String endpoint) {
    }

    /**
     * 官方通行证（Casdoor OIDC）登录与管理接口配置（管理密钥取环境变量，缺省 {@value #PASSPORT_ADMIN_SECRET_ENV}）。
     * 生产模式下必须配置 {@code adminClientId}（且不能等于 {@code clientId}）及对应管理密钥环境变量。
     */
    public record Passport(String issuer, String clientId, String endpoint,
                           String adminClientId, String adminClientSecretEnv) {
        public Passport(String issuer, String clientId, String endpoint) {
            this(issuer, clientId, endpoint, null, null);
        }

        @ConstructorBinding
        public Passport {
            adminClientSecretEnv = adminClientSecretEnv == null || adminClientSecretEnv.isBlank()
                    ? PASSPORT_ADMIN_SECRET_ENV : adminClientSecretEnv;
        }
    }
}
