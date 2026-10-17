package com.game.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Java 版 Redis 连接配置（{@code game.redis.*}）。默认 DB 12，与 mmorpg 开发数据隔离。
 * 密码只从环境变量 / 外部配置注入，不写进仓库。
 *
 * <p>超时与重试（全部可选）决定一次 Redis 命令最坏要阻塞调用线程多久。Redisson 自带默认值
 * （响应超时 3s、重试 4 次、重试间隔 0.5~2s 抖动）下，Redis 不可达时单条命令可以挂二十多秒，
 * 会让 game-gateway 的 HTTP 请求远超客户端 5s 超时（robot 单次 {@code POST /api/assign-gate} 等 5s）。
 * 这里的默认值把单条命令的上界压到：
 * <pre>
 *   (retryAttempts + 1) × timeoutMs + retryAttempts × retryDelayMs = 2 × 2000 + 200 = 4.2s  &lt; 5s
 * </pre>
 * 连接已断、需要重连时，单次建连另受 {@code connectTimeoutMs} 约束。
 *
 * <p>连接池（全部可选）决定本进程最多占共享 Redis 多少条连接，进 Redis 连接总账（architecture.md §6.1）。
 * Redisson 的一条命令从借到连接起一直占着它，应答回来才归还，所以命令池上限就是本进程同时在途的命令数上限；
 * 池满时命令排队等连接，等不到即 {@code RedisTimeoutException}（「Unable to acquire connection!」）。
 * 最小空闲是客户端创建时就建好、之后一直保有的连接数。缺省值与 Redisson 3.50.0 的库缺省相同（64 / 24 / 50）：
 * 显式写出是为了让连接总账读配置、不随库升级漂移，取值本身没有压测依据，不改变现有行为。订阅连接的最小空闲不开放，
 * 用库缺省 1，所以订阅池上限至少是 1。
 *
 * @param connectTimeoutMs               建立 TCP 连接的超时（毫秒），默认 2000
 * @param timeoutMs                      命令发出后等响应的超时（毫秒），默认 2000
 * @param retryAttempts                  命令失败后的重试次数（不含首次），默认 1；0 = 不重试
 * @param retryDelayMs                   两次尝试之间的固定间隔（毫秒），默认 200
 * @param connectionPoolSize             命令连接池上限，默认 64；至少 1
 * @param connectionMinimumIdleSize      命令连接最小空闲数，默认 24；0 ~ {@code connectionPoolSize}
 * @param subscriptionConnectionPoolSize 订阅（Pub/Sub）连接池上限，默认 50；至少 1
 */
@ConfigurationProperties("game.redis")
public record RedisProperties(String address, Integer database, String password,
                              Integer connectTimeoutMs, Integer timeoutMs, Integer retryAttempts, Integer retryDelayMs,
                              Integer connectionPoolSize, Integer connectionMinimumIdleSize,
                              Integer subscriptionConnectionPoolSize) {

    /** 命令连接池上限的缺省值（= Redisson 3.50.0 库缺省，RedisPropertiesTest 钉住）。 */
    public static final int DEFAULT_CONNECTION_POOL_SIZE = 64;
    /** 命令连接最小空闲数的缺省值（= Redisson 3.50.0 库缺省）。 */
    public static final int DEFAULT_CONNECTION_MINIMUM_IDLE_SIZE = 24;
    /** 订阅连接池上限的缺省值（= Redisson 3.50.0 库缺省）。 */
    public static final int DEFAULT_SUBSCRIPTION_CONNECTION_POOL_SIZE = 50;

    /** 记录有两个公开构造器，Spring Boot 不再自行推断绑定哪一个，在这里指明。 */
    @ConstructorBinding
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
        connectionPoolSize = connectionPoolSize == null ? DEFAULT_CONNECTION_POOL_SIZE : connectionPoolSize;
        connectionMinimumIdleSize = connectionMinimumIdleSize == null
                ? DEFAULT_CONNECTION_MINIMUM_IDLE_SIZE : connectionMinimumIdleSize;
        subscriptionConnectionPoolSize = subscriptionConnectionPoolSize == null
                ? DEFAULT_SUBSCRIPTION_CONNECTION_POOL_SIZE : subscriptionConnectionPoolSize;
        if (connectTimeoutMs <= 0 || timeoutMs <= 0) {
            throw new IllegalArgumentException("game.redis.connect-timeout-ms / timeout-ms 必须为正: "
                    + connectTimeoutMs + " / " + timeoutMs);
        }
        if (retryAttempts < 0 || retryDelayMs < 0) {
            throw new IllegalArgumentException("game.redis.retry-attempts / retry-delay-ms 不能为负: "
                    + retryAttempts + " / " + retryDelayMs);
        }
        if (connectionPoolSize <= 0 || subscriptionConnectionPoolSize <= 0) {
            throw new IllegalArgumentException("game.redis.connection-pool-size / subscription-connection-pool-size 必须为正: "
                    + connectionPoolSize + " / " + subscriptionConnectionPoolSize);
        }
        // Redisson 建客户端时也会拒绝最小空闲大于池上限，这里在绑定时就报出配置键。
        // 不把缺省最小空闲悄悄压到池上限：把池调到 24 以下的人要同时决定常驻几条
        if (connectionMinimumIdleSize < 0 || connectionMinimumIdleSize > connectionPoolSize) {
            throw new IllegalArgumentException("game.redis.connection-minimum-idle-size 必须在 0 与 connection-pool-size 之间"
                    + "（池上限调到 " + DEFAULT_CONNECTION_MINIMUM_IDLE_SIZE + " 以下时一并设它）: "
                    + connectionMinimumIdleSize + " / " + connectionPoolSize);
        }
    }

    /** 只给地址、超时与重试，连接池取缺省值：供只关心命令时延的调用点与测试使用。 */
    public RedisProperties(String address, Integer database, String password,
                           Integer connectTimeoutMs, Integer timeoutMs, Integer retryAttempts, Integer retryDelayMs) {
        this(address, database, password, connectTimeoutMs, timeoutMs, retryAttempts, retryDelayMs, null, null, null);
    }

    /** 按上述公式算出的单条命令最坏阻塞时间（毫秒），供启动日志与测试使用。 */
    public long worstCaseCommandMillis() {
        return (long) (retryAttempts + 1) * timeoutMs + (long) retryAttempts * retryDelayMs;
    }

    /** 本客户端两个连接池的上限之和（命令池 + 订阅池），即它在 Redis 连接总账里记的峰值，供启动日志与测试使用。 */
    public long maxConnections() {
        return (long) connectionPoolSize + subscriptionConnectionPoolSize;
    }
}
