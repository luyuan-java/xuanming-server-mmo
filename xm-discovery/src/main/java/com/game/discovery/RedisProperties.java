package com.game.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Java 版 Redis 连接配置（{@code xm.redis.*}）。默认 DB 12，与 mmorpg 开发数据隔离。
 * 密码只从环境变量 / 外部配置注入，不写进仓库。
 *
 * <p>超时与重试（全部可选）决定一次 Redis 命令最坏要阻塞调用线程多久。Redisson 自带默认值
 * （响应超时 3s、重试 4 次、重试间隔 0.5~2s 抖动）下，Redis 不可达时单条命令可以挂二十多秒，
 * 会让 xm-gateway 的 HTTP 请求远超客户端 5s 超时（robot 单次 {@code POST /api/assign-gate} 等 5s）。
 * 这里的默认值把单条命令的上界压到：
 * <pre>
 *   (retryAttempts + 1) × timeoutMs + retryAttempts × retryDelayMs = 2 × 2000 + 200 = 4.2s  &lt; 5s
 * </pre>
 * 连接已断、需要重连时，单次建连另受 {@code connectTimeoutMs} 约束。
 *
 * @param connectTimeoutMs 建立 TCP 连接的超时（毫秒），默认 2000
 * @param timeoutMs        命令发出后等响应的超时（毫秒），默认 2000
 * @param retryAttempts    命令失败后的重试次数（不含首次），默认 1；0 = 不重试
 * @param retryDelayMs     两次尝试之间的固定间隔（毫秒），默认 200
 */
@ConfigurationProperties("xm.redis")
public record RedisProperties(String address, Integer database, String password,
                              Integer connectTimeoutMs, Integer timeoutMs, Integer retryAttempts, Integer retryDelayMs) {

    public RedisProperties {
        if (address == null || address.isBlank()) {
            address = "redis://127.0.0.1:6379";
        }
        if (database == null) {
            database = 12;
        }
        if (password != null && password.isBlank()) {
            password = null;
        }
        connectTimeoutMs = connectTimeoutMs == null ? 2000 : connectTimeoutMs;
        timeoutMs = timeoutMs == null ? 2000 : timeoutMs;
        retryAttempts = retryAttempts == null ? 1 : retryAttempts;
        retryDelayMs = retryDelayMs == null ? 200 : retryDelayMs;
        if (connectTimeoutMs <= 0 || timeoutMs <= 0) {
            throw new IllegalArgumentException("xm.redis.connect-timeout-ms / timeout-ms 必须为正: "
                    + connectTimeoutMs + " / " + timeoutMs);
        }
        if (retryAttempts < 0 || retryDelayMs < 0) {
            throw new IllegalArgumentException("xm.redis.retry-attempts / retry-delay-ms 不能为负: "
                    + retryAttempts + " / " + retryDelayMs);
        }
    }

    /** 按上述公式算出的单条命令最坏阻塞时间（毫秒），供启动日志与测试使用。 */
    public long worstCaseCommandMillis() {
        return (long) (retryAttempts + 1) * timeoutMs + (long) retryAttempts * retryDelayMs;
    }
}
