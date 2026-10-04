package com.game.discovery.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.TeamInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 组队成员关系读（{@link TeamMembershipReader}：一段只读 Lua 读索引 + 投影）的真 Redis 集成测试（team-spec §10.2 末条）。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 11 与随机大号 id（含 ≥ 2^63）隔离，只删自己写的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class TeamMembershipReaderIntegrationTest {

    /** 玩家号与队伍号都取高位为 1（≥ 2^63）的值：键名、Lua 里的比较、解析都必须按无符号。 */
    private static final long PLAYER = Long.MIN_VALUE + (1L << 52) + ThreadLocalRandom.current().nextLong(1L << 40);
    private static final long TEAM = Long.MIN_VALUE + (1L << 53) + ThreadLocalRandom.current().nextLong(1L << 40);

    private static RedissonClient redis;
    private static TeamMembershipReader reader;
    private static final List<String> written = new ArrayList<>();

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(11);
        redis = Redisson.create(config);
        reader = new TeamMembershipReader(redis);
    }

    @AfterAll
    static void cleanup() {
        redis.getKeys().delete(written.toArray(String[]::new));
        redis.shutdown();
    }

    private static void index(long player, String tid, String epoch) {
        String key = RedisKeys.teamPlayer(player);
        written.add(key);
        var hash = redis.<String, String>getMap(key, StringCodec.INSTANCE);
        hash.delete();
        if (tid != null) {
            hash.put(TeamRedisFields.TID, tid);
        }
        if (epoch != null) {
            hash.put(TeamRedisFields.EPOCH, epoch);
        }
    }

    private static void projection(long team, byte[] value) {
        String key = RedisKeys.teamInfo(team);
        written.add(key);
        redis.getBucket(key, ByteArrayCodec.INSTANCE).set(value);
    }

    private static TeamMembership read(long player) throws Exception {
        return reader.readAsync(player).get(5, TimeUnit.SECONDS);
    }

    @Test
    void 索引键缺失_按无队() throws Exception {
        assertThat(read(PLAYER + 100)).isEqualTo(TeamMembership.KEY_MISSING);
    }

    @Test
    void tid为0_无队_保留epoch_不读投影() throws Exception {
        long player = PLAYER + 1;
        index(player, "0", "1712345678901");
        projection(0, TeamInfo.newBuilder().setTeamId(7).build().toByteArray());

        TeamMembership m = read(player);

        assertThat(m).isEqualTo(new TeamMembership(0, 1712345678901L, false, null));
        assertThat(m.inTeam()).isFalse();
    }

    @Test
    void 在队但投影缺失() throws Exception {
        long player = PLAYER + 2;
        long team = TEAM + 2;
        index(player, Long.toUnsignedString(team), "9");
        redis.getKeys().delete(RedisKeys.teamInfo(team));

        TeamMembership m = read(player);

        assertThat(m).isEqualTo(new TeamMembership(team, 9, false, null));
        assertThat(m.projectionMissing()).isTrue();
    }

    @Test
    void 正常_无符号大号_投影原样解码() throws Exception {
        long player = PLAYER + 3;
        long other = PLAYER + 4;
        long team = TEAM + 3;
        long epoch = Long.MIN_VALUE + 12345;   // ≥ 2^63 的 epoch 也按无符号读
        TeamInfo info = TeamInfo.newBuilder().setTeamId(team).setLeaderId(other).addMembers(other).addMembers(player).build();
        index(player, Long.toUnsignedString(team), Long.toUnsignedString(epoch));
        projection(team, info.toByteArray());

        TeamMembership m = read(player);

        assertThat(Long.toUnsignedString(m.teamId())).isEqualTo(Long.toUnsignedString(team));
        assertThat(m.teamId()).isNegative();
        assertThat(m.epoch()).isEqualTo(epoch);
        assertThat(m.keyMissing()).isFalse();
        assertThat(m.info()).isEqualTo(info);
        assertThat(RedisKeys.teamPlayer(player)).endsWith(":" + Long.toUnsignedString(player));
    }

    @Test
    void 半个hash_非十进制_投影损坏_都异常完成() throws Exception {
        long halfHash = PLAYER + 5;
        long badTid = PLAYER + 6;
        long badInfo = PLAYER + 7;
        long leadingZero = PLAYER + 8;
        long team = TEAM + 7;
        index(halfHash, Long.toUnsignedString(team), null);
        index(badTid, "-3", "4");
        index(badInfo, Long.toUnsignedString(team), "4");
        projection(team, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff});
        index(leadingZero, "07", "4");

        for (long player : List.of(halfHash, badTid, badInfo, leadingZero)) {
            assertThatThrownBy(() -> read(player)).as("player %s", Long.toUnsignedString(player))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 只读脚本_不改任何键() throws Exception {
        long player = PLAYER + 9;
        long team = TEAM + 9;
        index(player, Long.toUnsignedString(team), "3");
        long ttlBefore = redis.getKeys().remainTimeToLive(RedisKeys.teamPlayer(player));

        read(player);

        assertThat(redis.<String, String>getMap(RedisKeys.teamPlayer(player), StringCodec.INSTANCE).readAllMap())
                .isEqualTo(Map.of(TeamRedisFields.TID, Long.toUnsignedString(team), TeamRedisFields.EPOCH, "3"));
        assertThat(redis.getKeys().remainTimeToLive(RedisKeys.teamPlayer(player))).isEqualTo(ttlBefore);
        assertThat(redis.getKeys().countExists(RedisKeys.teamInfo(team))).isZero();
    }

    @Test
    void Redis不可用_异常完成_不同步抛出() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(11);
        RedissonClient closed = Redisson.create(config);
        closed.shutdown();

        var future = new TeamMembershipReader(closed).readAsync(PLAYER);
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }
}
