package com.game.team.service;

/**
 * 整队开战的服务层用例，缺省档（不连 Redis）：存储后端是内存替身 {@code InMemoryTeamRedis}（七段 Lua 的 Java 等价实现 + 手拨时钟，
 * 对应基线测试的 miniredis），xm-match 是 {@code FakeMatchTeamService}。用例本身在 {@link TeamMatchScenarios}；同一批用例在真 Redis 上
 * 由 {@code TeamMatchRedisIntegrationTest} 再跑一遍（带 {@code -Dxm.it.redis}）。
 */
class TeamServiceTest extends TeamMatchScenarios {

    @Override
    TeamMatchFixture.Backend backend() {
        return TeamMatchFixture.inMemory();
    }
}
