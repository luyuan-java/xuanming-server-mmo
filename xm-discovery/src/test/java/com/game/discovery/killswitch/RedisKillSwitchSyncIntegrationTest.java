package com.game.discovery.killswitch;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.killswitch.KillSwitch;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/** 热关停规则从 Redis 哈希同步（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 13）。 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedisKillSwitchSyncIntegrationTest {

    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        redis.getKeys().delete(RedisKeys.killSwitch());
    }

    @AfterAll
    static void cleanup() {
        redis.getKeys().delete(RedisKeys.killSwitch());
        redis.shutdown();
    }

    @Test
    void 全量同步_字段名去空白与开头斜杠_写坏的值与空模式忽略_删除后放行() {
        KillSwitch ks = new KillSwitch(-1, System::nanoTime);
        RedisKillSwitchSync sync = new RedisKillSwitchSync(redis, ks, Duration.ofSeconds(1));
        redis.<String, String>getMap(RedisKeys.killSwitch(), StringCodec.INSTANCE).putAll(Map.of(
                " //ClientPlayerChat/* ", "{\"deny\":true,\"reason\":\"止血\"}",
                "ClientPlayerChat/PullChatHistory", "false",
                "broken/*", "关掉它",
                "///", "true"));
        sync.syncOnce();
        assertThat(ks.rules()).containsOnlyKeys("ClientPlayerChat/*", "ClientPlayerChat/PullChatHistory");
        assertThat(ks.blocked("/chatpb.ClientPlayerChat/SendChat")).contains(new KillSwitch.Rule(true, "止血", 0));
        assertThat(ks.blocked("/chatpb.ClientPlayerChat/PullChatHistory")).as("精确豁免").isEmpty();

        redis.getKeys().delete(RedisKeys.killSwitch());
        sync.syncOnce();
        assertThat(ks.rules()).isEmpty();
        assertThat(ks.blocked("/chatpb.ClientPlayerChat/SendChat")).isEmpty();
    }

    @Test
    void 几个字段规范化后同名_规范形字段胜出_与返回顺序无关() {
        KillSwitch ks = new KillSwitch(-1, System::nanoTime);
        RedisKillSwitchSync sync = new RedisKillSwitchSync(redis, ks, Duration.ofSeconds(1));
        redis.<String, String>getMap(RedisKeys.killSwitch(), StringCodec.INSTANCE).putAll(Map.of(
                "/ClientPlayerChat/SendChat", "true",
                " ClientPlayerChat/SendChat", "true",
                "ClientPlayerChat/SendChat", "false",
                "//ClientPlayerChat/*", "true",
                " ClientPlayerChat/*", "false"));
        sync.syncOnce();
        assertThat(ks.rules()).containsOnlyKeys("ClientPlayerChat/SendChat", "ClientPlayerChat/*");
        assertThat(ks.blocked("/chatpb.ClientPlayerChat/SendChat")).as("规范形字段（豁免）胜出").isEmpty();
        assertThat(ks.rules().get("ClientPlayerChat/*").deny()).as("都不是规范形：按字段名字典序取第一个（' ' < '/'）").isFalse();
        redis.getKeys().delete(RedisKeys.killSwitch());
    }
}
