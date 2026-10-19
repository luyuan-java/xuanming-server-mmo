package com.game.data.tools.playerbundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 导入的写前闸门一（本机地址）与闸门二（实例身份比对）。 */
class LocalTargetsTest {

    @Test
    void 回环地址的JDBC_URL放行() {
        for (String url : List.of("jdbc:mysql://127.0.0.1:3306/game", "jdbc:mysql://localhost/game?useSSL=false",
                "jdbc:mysql://LOCALHOST:4000/game", "jdbc:mysql://[::1]:3306/game", "jdbc:mysql://root:pw@127.0.0.1:3306/game",
                "jdbc:mysql://127.0.0.2:3306/game", "jdbc:h2:mem:x")) {
            assertThat(LocalTargets.checkJdbcUrl(url, Set.of())).as(url).isNull();
        }
    }

    @Test
    void 非本机_多主机_其他协议一律拒绝_报错不带口令() {
        String remote = LocalTargets.checkJdbcUrl("jdbc:mysql://root:secretpw@10.1.2.3:3306/game?password=secretpw", Set.of());
        assertThat(remote).contains("10.1.2.3").contains("--allow-target-host").doesNotContain("secretpw");
        // 主机名一律不做 DNS 解析：长得像回环的域名照样拒绝
        assertThat(LocalTargets.checkJdbcUrl("jdbc:mysql://127.0.0.1.example.com/game", Set.of())).isNotNull();
        assertThat(LocalTargets.checkJdbcUrl("jdbc:mysql://tidb.prod.svc:4000/game", Set.of())).isNotNull();
        assertThat(LocalTargets.checkJdbcUrl("jdbc:mysql://127.0.0.1,10.0.0.1/game", Set.of())).contains("恰好写一个主机");
        assertThat(LocalTargets.checkJdbcUrl("jdbc:mysql:loadbalance://127.0.0.1/game", Set.of())).contains("单主机");
        assertThat(LocalTargets.checkJdbcUrl("jdbc:mysql://address=(host=127.0.0.1)/game", Set.of())).isNotNull();
        assertThat(LocalTargets.checkJdbcUrl(null, Set.of())).isNotNull();
    }

    @Test
    void 本地docker服务名要显式放行_精确匹配() {
        assertThat(LocalTargets.checkJdbcUrl("jdbc:mysql://mysql:3306/game", Set.of())).contains("mysql");
        assertThat(LocalTargets.checkJdbcUrl("jdbc:mysql://mysql:3306/game", Set.of("MySQL"))).isNull();
        assertThat(LocalTargets.checkJdbcUrl("jdbc:mysql://mysql-prod:3306/game", Set.of("mysql"))).isNotNull();
        assertThat(LocalTargets.checkRedisAddress("redis://redis:6379", Set.of("redis"))).isNull();
    }

    @Test
    void Redis地址() {
        assertThat(LocalTargets.checkRedisAddress("redis://127.0.0.1:6379", Set.of())).isNull();
        assertThat(LocalTargets.checkRedisAddress("rediss://localhost:6380", Set.of())).isNull();
        assertThat(LocalTargets.checkRedisAddress("redis://10.0.0.8:6379", Set.of())).contains("10.0.0.8");
        assertThat(LocalTargets.checkRedisAddress("http://127.0.0.1:6379", Set.of())).isNotNull();
    }

    @Test
    void 记录来源只留主机端口不留账号口令() {
        assertThat(LocalTargets.describeJdbc("jdbc:mysql://u:p@10.0.0.1:4000/game?password=p")).isEqualTo("10.0.0.1:4000");
        assertThat(LocalTargets.describeJdbc("jdbc:h2:mem:abc")).isEqualTo("h2:mem");
        assertThat(LocalTargets.describeRedis("redis://:pw@10.0.0.9:6379")).isEqualTo("10.0.0.9:6379");
    }

    @Test
    void 实例身份_同类比对_任一侧取不到都拒绝_相同拒绝_不同放行() {
        assertThat(InstanceIdentity.checkNotExportSource("MySQL", null, "server_uuid:aaa")).contains("取不到实例身份");
        assertThat(InstanceIdentity.checkNotExportSource("MySQL", "server_uuid:ccc", null)).contains("没有记录 MySQL 的来源实例身份");
        assertThat(InstanceIdentity.checkNotExportSource("Redis", "run_id:x", " ")).contains("--skip-redis");
        assertThat(InstanceIdentity.checkNotExportSource("MySQL", "server_uuid:aaa", "server_uuid:aaa")).contains("同一个实例");
        assertThat(InstanceIdentity.checkNotExportSource("Redis", "run_id:bbb", "run_id:bbb")).contains("同一个实例");
        assertThat(InstanceIdentity.checkNotExportSource("MySQL", "server_uuid:ccc", "server_uuid:aaa")).isNull();
    }

    @Test
    void Redis身份取自INFO_server的run_id() {
        assertThat(InstanceIdentity.redis(Map.of("run_id", " abc ", "redis_version", "7.4"))).isEqualTo("run_id:abc");
        assertThat(InstanceIdentity.redis(Map.of("redis_version", "7.4"))).isNull();
        assertThat(InstanceIdentity.redis(null)).isNull();
    }
}
