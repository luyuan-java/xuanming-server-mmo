package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.data.DataConfiguration;
import com.game.data.DataProperties;
import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleRedis;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 回档前战斗锁闸在真 Redis 上的往返（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 13，与 xm-data 其他 Redis 集成测试同库）：
 * 生产接线（{@link DataConfiguration#battleLockGate}）读的就是 scene 写的那把锁——用 scene 的同一段脚本（{@link BattleRedis#prepareLock}、
 * {@link BattleRedis#confirm}）造锁，备战中 / 战斗中都算在战；锁没了就不算。只用随机玩家号，只删自己写的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class BattleLockGateRedisIntegrationTest {

    private RedissonClient redis;
    private final List<Long> written = new ArrayList<>();

    @BeforeEach
    void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
    }

    @AfterEach
    void close() {
        for (long playerId : written) {
            redis.getKeys().delete(RedisKeys.battleLock(playerId));
        }
        redis.shutdown();
    }

    private long randomPlayer() {
        long id = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 60);
        written.add(id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private BattleLockGate productionGate() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(redis);
        DataProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("xm.data", DataProperties.class);
        return new DataConfiguration().battleLockGate(provider, props);
    }

    @Test
    void scene写的战斗锁_备战中与战斗中都判在战_没有锁的可写_锁删掉之后不再挡() throws Exception {
        long preparing = randomPlayer();
        long fighting = randomPlayer();
        long free = randomPlayer();
        long battleId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        long deadline = System.currentTimeMillis() + 120_000;
        BattleRedis scripts = new BattleRedis(redis);
        assertThat(scripts.prepareLock(preparing, battleId, 7, deadline, deadline - 60_000, 300).get()).isEqualTo("0");
        assertThat(scripts.prepareLock(fighting, battleId, 7, deadline, deadline - 60_000, 300).get()).isEqualTo("0");
        assertThat(scripts.confirm(fighting, battleId, deadline, 300).get()).containsEntry(BattleRedis.FIELD_STATE,
                BattleRedis.STATE_FIGHTING);
        assertThat(redis.<String, String>getMap(RedisKeys.battleLock(preparing), StringCodec.INSTANCE)
                .get(BattleRedis.FIELD_STATE)).isEqualTo(BattleRedis.STATE_PREPARING);
        BattleLockGate gate = productionGate();

        BattleLockGate.Result r = gate.check(List.of(preparing, free, fighting), () -> { });

        assertThat(r.checked()).isEqualTo(3);
        assertThat(r.inBattle()).containsExactly(preparing, fighting);
        assertThat(r.unknown()).isEmpty();
        assertThat(r.error()).isNull();
        assertThat(r.outcome(free)).isNull();
        assertThat(r.outcome(fighting)).isEqualTo("in_battle");

        // 锁没了（TTL 到期 / 结算销账）：同一名玩家不再被挡
        assertThat(redis.getKeys().delete(RedisKeys.battleLock(fighting))).isEqualTo(1);
        BattleLockGate.Result after = gate.check(List.of(preparing, free, fighting), () -> { });
        assertThat(after.inBattle()).containsExactly(preparing);
        assertThat(after.outcome(fighting)).isNull();
    }

    @Test
    void 整区规模_一千多人分块读完_只有持锁的被挡() throws Exception {
        long base = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 60);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 1201; i++) {
            ids.add(base + i);
        }
        long locked = base + 777;
        written.add(locked);
        long deadline = System.currentTimeMillis() + 120_000;
        assertThat(new BattleRedis(redis).prepareLock(locked, 42, 7, deadline, deadline - 60_000, 300).get()).isEqualTo("0");

        BattleLockGate.Result r = productionGate().check(ids, () -> { });

        assertThat(r.checked()).isEqualTo(1201);
        assertThat(r.inBattle()).containsExactly(locked);
        assertThat(r.unknown()).isEmpty();
    }
}
