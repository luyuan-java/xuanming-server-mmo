package com.game.login;

import com.game.login.auth.ProductionPasswordAuthenticator;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证配置（{@code xm.login.auth.*}，同 mmorpg login.yaml 的 PasswordAuth / AuthProviders）。没写的一项 = 不启用（fail-closed）。
 * 秘密（微信 app secret、Sa-Token Redis 口令）只从环境变量读，不进配置文件。
 *
 * @param password 生产口令认证（读 {@code account.password_hash} 的 Argon2id）；与开发口令（{@code xm.login.mode=dev}）互斥
 * @param satoken  Sa-Token：读 Sa-Token 认证服务的 Redis
 * @param wechat   微信开放平台（app secret 取环境变量 {@value #WECHAT_SECRET_ENV}）
 * @param qq       QQ 互联
 * @param netease  网易（两版都是占位：恒失败）
 */
@ConfigurationProperties("xm.login.auth")
public record LoginAuthProperties(PasswordAuth password, SaToken satoken, WeChat wechat, Qq qq, Netease netease) {

    public static final String WECHAT_SECRET_ENV = "XM_WECHAT_APP_SECRET";
    public static final String SATOKEN_REDIS_PASSWORD_ENV = "XM_SATOKEN_REDIS_PASSWORD";

    public LoginAuthProperties {
        password = password == null ? new PasswordAuth(false, null, null) : password;
    }

    /**
     * @param enabled        启用生产口令认证
     * @param kdfConcurrency KDF 并发槽（缺省 2，1–8；每个占 64 MiB）
     * @param kdfWait        等槽上限（缺省 500 ms，最多 5 s）
     */
    public record PasswordAuth(boolean enabled, Integer kdfConcurrency, Duration kdfWait) {

        public PasswordAuth {
            kdfConcurrency = kdfConcurrency == null ? ProductionPasswordAuthenticator.DEFAULT_KDF_CONCURRENCY : kdfConcurrency;
            kdfWait = kdfWait == null ? ProductionPasswordAuthenticator.DEFAULT_KDF_WAIT : kdfWait;
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
                throw new IllegalArgumentException("xm.login.auth.satoken.redis-address 必填");
            }
            database = database == null ? 0 : database;
            tokenName = tokenName == null || tokenName.isBlank() ? "satoken" : tokenName;
            loginType = loginType == null || loginType.isBlank() ? "login" : loginType;
        }
    }

    /**
     * @param appId    微信开放平台应用号
     * @param endpoint 空 = 生产地址（https://api.weixin.qq.com）；可指向本地假服务
     */
    public record WeChat(String appId, String endpoint) {
    }

    /**
     * @param appId    QQ 互联应用号（校验应答的 client_id）
     * @param endpoint 空 = 生产地址（https://graph.qq.com）
     */
    public record Qq(String appId, String endpoint) {
    }

    /** @param enabled 注册网易占位（恒失败，同基线） */
    public record Netease(boolean enabled) {
    }
}
