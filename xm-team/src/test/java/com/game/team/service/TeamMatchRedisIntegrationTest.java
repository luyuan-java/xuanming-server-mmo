package com.game.team.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;

/**
 * 整队开战的服务层用例在<b>真 Redis</b> 上再跑一遍（{@link TeamMatchScenarios}；开战锁钉版本提交、EVAL 重发后的按 token 确认、
 * EndMatch 的冲突循环走的都是真脚本）。要拨 Redis 时钟的两处（开战锁自然过期）在这里跳过，由缺省档的 {@link TeamServiceTest} 覆盖。
 *
 * <p>默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}（DB 12，随机 ≥ 2^63 的 id，只删自己的键）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class TeamMatchRedisIntegrationTest extends TeamMatchScenarios {

    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        redis = TeamServiceFixture.connect();
    }

    @AfterAll
    static void shutdown() {
        redis.shutdown();
    }

    @Override
    TeamMatchFixture.Backend backend() {
        return TeamMatchFixture.redis(redis);
    }
}
