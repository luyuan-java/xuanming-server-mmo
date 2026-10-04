package com.game.login;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-login 业务配置（{@code xm.login.*}）。缺省值按 fail-closed 取：不写 {@code mode} 即 {@code prod}（关闭开发口令）。
 *
 * @param mode                  {@code dev} 启用开发口令认证；{@code prod} 关闭（口令登录一律失败）
 * @param devAccountPrefixes    开发口令认证允许的账号前缀
 * @param maxPlayersPerAccount  每账号角色上限（mmorpg 默认 5）
 * @param workerThreads         阻塞工作线程数（MySQL 等阻塞 I/O 都在这组线程上跑，不占 Dubbo 线程）
 * @param workerQueueCapacity   工作队列上限；满了直接拒绝（回「服务不可用」），不无限堆积
 * @param sceneAssignTimeout    进游戏时等 scene-manager 分配结果的兜底上限
 * @param ownerClaimWait        进游戏夺取玩家数据归属时，归属仍被上一个写者持有，最多等它释放多久（期间退避重试），之后回 2005；
 *                              加上场景分配的上限必须小于客户端 EnterGame 的 15s 预算
 * @param accessTokenTtl        access token 有效期（基线 2h）
 * @param refreshTokenTtl       refresh token 有效期（基线 720h）
 * @param maxDevicesPerAccount  每账号处于「已登录、未进游戏」窗口的连接上限（基线 3，超出回 2024）
 * @param deviceSessionTtl      一次设备登记的有效期（基线 SessionExpireMin = 30 分钟；没注销干净的按它自愈）
 */
@ConfigurationProperties("xm.login")
public record LoginProperties(
        String mode,
        List<String> devAccountPrefixes,
        Integer maxPlayersPerAccount,
        Integer workerThreads,
        Integer workerQueueCapacity,
        Duration sceneAssignTimeout,
        Duration ownerClaimWait,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Integer maxDevicesPerAccount,
        Duration deviceSessionTtl) {

    public static final String MODE_DEV = "dev";
    public static final String MODE_PROD = "prod";

    public LoginProperties {
        mode = mode == null || mode.isBlank() ? MODE_PROD : mode.strip();
        if (!MODE_DEV.equals(mode) && !MODE_PROD.equals(mode)) {
            throw new IllegalArgumentException("xm.login.mode 只能是 dev 或 prod: " + mode);
        }
        devAccountPrefixes = devAccountPrefixes == null || devAccountPrefixes.isEmpty()
                ? List.of("robot_", "dev_")
                : List.copyOf(devAccountPrefixes);
        maxPlayersPerAccount = positiveOr(maxPlayersPerAccount, 5, "max-players-per-account");
        workerThreads = positiveOr(workerThreads, 16, "worker-threads");
        workerQueueCapacity = positiveOr(workerQueueCapacity, 1024, "worker-queue-capacity");
        if (sceneAssignTimeout == null) {
            sceneAssignTimeout = Duration.ofSeconds(5);
        } else if (sceneAssignTimeout.isNegative() || sceneAssignTimeout.isZero()) {
            throw new IllegalArgumentException("xm.login.scene-assign-timeout 必须为正: " + sceneAssignTimeout);
        }
        if (ownerClaimWait == null) {
            ownerClaimWait = Duration.ofSeconds(3);
        } else if (ownerClaimWait.isNegative()) {
            throw new IllegalArgumentException("xm.login.owner-claim-wait 不能为负: " + ownerClaimWait);
        }
        accessTokenTtl = positiveOr(accessTokenTtl, Duration.ofHours(2), "access-token-ttl");
        refreshTokenTtl = positiveOr(refreshTokenTtl, Duration.ofHours(720), "refresh-token-ttl");
        maxDevicesPerAccount = positiveOr(maxDevicesPerAccount, 3, "max-devices-per-account");
        deviceSessionTtl = positiveOr(deviceSessionTtl, Duration.ofMinutes(30), "device-session-ttl");
    }

    public boolean devMode() {
        return MODE_DEV.equals(mode);
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("xm.login." + name + " 必须为正: " + value);
        }
        return value;
    }

    private static int positiveOr(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("xm.login." + name + " 必须为正: " + value);
        }
        return value;
    }
}
