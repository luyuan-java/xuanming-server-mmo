package com.game.data.tools;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.NamespaceDatabaseCheck;
import com.game.data.merge.ZoneMergeStore;
import com.game.data.merge.retire.RetiredZoneRedis;
import com.game.data.merge.retire.RetirementDeployment;
import com.game.discovery.RedisKeys;
import com.game.discovery.RedisProperties;
import com.game.discovery.namespace.GameNamespaceBinder;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/** 一次有界热状态退休；SQL原件锁、永久Redis屏障与版本CAS一起使用，不循环、不release、不重新开放。 */
public final class RetireZoneHotState {
    private static final Set<String> ARGUMENTS = Set.of(
            "--run", "--hash", "--generation", "--deployment", "--deployment-sha256", "--expect-database");

    private record Options(boolean apply, String run, String hash, String generation, Path deployment,
                           String deploymentDigest, String expectedDatabase) { }

    private RetireZoneHotState() {
    }

    public static void main(String[] args) throws Exception {
        Options options = parse(args);
        var namespace = GameNamespaceBinder.bind(System.getProperties(), System.getenv()).namespace();
        var deployment = RetirementDeployment.read(options.deployment(), options.deploymentDigest());
        if (!deployment.namespace().equals(namespace.value())) {
            throw new IllegalArgumentException("部署清单命名空间与当前配置不符");
        }

        Config config = redisConfig(requiredEnvironment("GAME_RETIRE_REDIS_ADDRESS"));
        try (var pool = dataSource()) {
            new StrongTiDbConnectionPolicy("退役区热状态维护", true)
                    .postProcessBeforeInitialization(pool, "retireZoneMaintenance");
            pool.init();
            try (var connection = pool.getConnection()) {
                StrongTiDbConnectionPolicy.verify(connection, true);
                String mismatch = NamespaceDatabaseCheck.verify(connection, options.expectedDatabase(), namespace,
                        "--expect-database <真实业务库>");
                if (mismatch != null) {
                    throw new IllegalStateException(mismatch);
                }
            }

            var redis = Redisson.create(config);
            try {
                var maintenance = new RetiredZoneRedis(redis, new RedisKeys(namespace));
                var result = new ZoneMergeStore(pool).withRetirementProof(options.run(), options.hash(),
                        options.generation(), (proof, budget) -> {
                            if (proof.getSourceZone() != deployment.sourceZone()) {
                                throw new IllegalArgumentException("部署清单源区与SQL原run不符");
                            }
                            return maintenance.execute(proof, deployment.digest(), deployment.nodeTypes(),
                                    options.apply(), budget);
                        });
                System.out.printf("run=%s zone=%s applied=%s candidates=%d deleted=%d remaining=%d version=%d deployment=%s%n",
                        options.run(), Integer.toUnsignedString(result.zone()), result.applied(), result.candidateKeys(),
                        result.deletedKeys(), result.remainingKeys(), result.version(), deployment.digest());
            } finally {
                redis.shutdown();
            }
        }
    }

    private static Options parse(String[] args) {
        if (args.length < 3 || args.length % 2 != 1 || !Set.of("plan", "apply").contains(args[0])) {
            throw new IllegalArgumentException("需要 <plan|apply> --run UUID --hash SHA256 --generation UUID "
                    + "--deployment 文件 --deployment-sha256 SHA256 [--expect-database 库名]");
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!ARGUMENTS.contains(args[i]) || values.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("未知或重复参数");
            }
        }
        return new Options("apply".equals(args[0]), required(values, "--run"), required(values, "--hash"),
                required(values, "--generation"), Path.of(required(values, "--deployment")),
                required(values, "--deployment-sha256"), values.get("--expect-database"));
    }

    private static DruidDataSource dataSource() {
        String url = requiredEnvironment("GAME_ZONE_MERGE_JDBC");
        String user = requiredEnvironment("GAME_MYSQL_USER");
        if (url.matches("(?is).*[?&](user|password|token)=.*")) {
            throw new IllegalArgumentException("JDBC地址不得夹带秘密");
        }
        var pool = new DruidDataSource();
        pool.setUrl(url);
        pool.setUsername(user);
        pool.setPassword(System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""));
        pool.setMaxActive(1);
        pool.setMaxWait(3000);
        return pool;
    }

    private static Config redisConfig(String address) {
        if (!address.matches("rediss?://[^/@?#]+:[0-9]+")) {
            throw new IllegalArgumentException("Redis必须是明确的redis[s]://host:port，地址不得夹带秘密");
        }
        var config = new Config();
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase(new StandardEnvironment()))
                .setPassword(System.getenv("GAME_REDIS_PASSWORD"))
                .setConnectTimeout(2000)
                .setTimeout(2000)
                .setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2);
        return config;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数 " + key);
        }
        return value;
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("必须显式设置 " + key);
        }
        return value;
    }

    /** 与服务进程共用标准绑定及默认DB12；不引入第三套维护工具数据库变量。 */
    static int redisDatabase(Environment environment) {
        int database = Binder.get(environment).bindOrCreate("game.redis", Bindable.of(RedisProperties.class)).database();
        if (database < 0) {
            throw new IllegalArgumentException("game.redis.database 不能为负");
        }
        return database;
    }
}
