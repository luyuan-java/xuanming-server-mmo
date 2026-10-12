package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys.MatchQueueId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.connection.CRC16;

/**
 * 匹配的 Redis 键（match-spec §9.4、§15.1 的 QueueKeysTest；对照基线 {@code go/match/internal/logic/keys_test.go}，去掉 legacy 用例）：
 * 键形状逐字钉住（game-match 的 Lua 与 6.5 都按它拼 / 读）；队列键可以从注册集成员解析回来；全部 match 键同一个 hash tag、Cluster 下同槽。
 */
class RedisKeysMatchTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    /** ≥ 2^63 的玩家号 / 战斗号：键里必须是无符号十进制。 */
    private static final long BIG = Long.MIN_VALUE + 4301;
    private static final String BIG_TEXT = "9223372036854780109";

    @Test
    void 键形状逐字钉住() {
        assertThat(REDIS_KEYS.matchQueueIndex()).isEqualTo("game:{match}:index");
        assertThat(REDIS_KEYS.matchQueue(3, 0)).isEqualTo("game:{match}:queue:3:0");
        assertThat(REDIS_KEYS.matchQueue(5, 1)).isEqualTo("game:{match}:queue:5:1");
        assertThat(REDIS_KEYS.matchRank(1, 7)).isEqualTo("game:{match}:rank:1:7");
        assertThat(REDIS_KEYS.matchQueueLock(1, 7)).isEqualTo("game:{match}:lock:1:7");
        assertThat(REDIS_KEYS.matchTicket(1001)).isEqualTo("game:{match}:ticket:1001");
        assertThat(REDIS_KEYS.matchPopMarker("6f1c2d3e-aaaa")).isEqualTo("game:{match}:pop:6f1c2d3e-aaaa");
        assertThat(REDIS_KEYS.matchRequeueMarker("6f1c2d3e-bbbb")).isEqualTo("game:{match}:requeue:6f1c2d3e-bbbb");
        assertThat(REDIS_KEYS.matchChallenge(88)).isEqualTo("game:{match}:challenge:88");
        assertThat(REDIS_KEYS.matchChallengeTarget(1001)).isEqualTo("game:{match}:challenge-target:1001");
        assertThat(REDIS_KEYS.matchChallengeDone(88)).isEqualTo("game:{match}:challenge-done:88");
        assertThat(REDIS_KEYS.matchBattlePlacement(99)).isEqualTo("game:{match}:battle:99");
        assertThat(REDIS_KEYS.matchWatching(1001)).isEqualTo("game:{match}:watching:1001");
        assertThat(REDIS_KEYS.matchWatchable()).isEqualTo("game:{match}:watchable");
    }

    @Test
    void 玩家号与各种id按无符号十进制_配置号按uint32() {
        assertThat(REDIS_KEYS.matchTicket(BIG)).isEqualTo("game:{match}:ticket:" + BIG_TEXT);
        assertThat(REDIS_KEYS.matchChallenge(BIG)).isEqualTo("game:{match}:challenge:" + BIG_TEXT);
        assertThat(REDIS_KEYS.matchChallengeTarget(BIG)).isEqualTo("game:{match}:challenge-target:" + BIG_TEXT);
        assertThat(REDIS_KEYS.matchChallengeDone(BIG)).isEqualTo("game:{match}:challenge-done:" + BIG_TEXT);
        assertThat(REDIS_KEYS.matchBattlePlacement(BIG)).isEqualTo("game:{match}:battle:" + BIG_TEXT);
        assertThat(REDIS_KEYS.matchWatching(BIG)).isEqualTo("game:{match}:watching:" + BIG_TEXT);
        assertThat(REDIS_KEYS.matchWatching(-1L)).isEqualTo("game:{match}:watching:18446744073709551615");
        assertThat(REDIS_KEYS.matchTicket(-1L)).isEqualTo("game:{match}:ticket:18446744073709551615");
        assertThat(REDIS_KEYS.matchQueue(3, -1)).as("battle_config_id 是 uint32：0xFFFFFFFF").isEqualTo("game:{match}:queue:3:4294967295");
        assertThat(REDIS_KEYS.matchRank(3, Integer.MIN_VALUE)).isEqualTo("game:{match}:rank:3:2147483648");
        assertThat(REDIS_KEYS.matchQueueLock(3, -1)).isEqualTo("game:{match}:lock:3:4294967295");
    }

    @Test
    void 模式为负是调用方的错() {
        assertThatThrownBy(() -> REDIS_KEYS.matchQueue(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> REDIS_KEYS.matchRank(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> REDIS_KEYS.matchQueueLock(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchQueueId(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 队列键解析往返() {
        int[][] cases = {{0, 0}, {1, 0}, {3, 0}, {5, 1}, {6, 12345}, {3, -1}, {1, Integer.MIN_VALUE}, {Integer.MAX_VALUE, Integer.MAX_VALUE}};
        for (int[] c : cases) {
            assertThat(REDIS_KEYS.parseMatchQueue(REDIS_KEYS.matchQueue(c[0], c[1])))
                    .as("mode=%d config=%s", c[0], Integer.toUnsignedString(c[1]))
                    .contains(new MatchQueueId(c[0], c[1]));
        }
        assertThat(REDIS_KEYS.parseMatchQueue("game:{match}:queue:3:4294967295")).contains(new MatchQueueId(3, -1));
    }

    @Test
    void 不是规范队列键的一律解析为空() {
        for (String bad : List.of(
                "", "game:{match}:queue:", "game:{match}:queue:3", "game:{match}:queue:3:", "game:{match}:queue::0",
                "game:{match}:queue:3:0:1", "game:{match}:queue:03:0", "game:{match}:queue:3:00", "game:{match}:queue:+3:0",
                "game:{match}:queue:-1:0", "game:{match}:queue:3:-1", "game:{match}:queue:3:4294967296", "game:{match}:queue:2147483648:0",
                "game:{match}:queue:3:1e3", "game:{match}:queue:3: 1", "game:{match}:queue:a:0", "game:{match}:queue:3:99999999999",
                // 别的键、别的 tag、基线键、少了前缀
                "game:{match}:rank:3:0", "game:{match}:lock:3:0", "game:{match}:index", "game:match:queue:3:0", "match:{mq}:queue:3:0",
                "{match}:queue:3:0", "GAME:{match}:queue:3:0")) {
            assertThat(REDIS_KEYS.parseMatchQueue(bad)).as("'%s'", bad).isEmpty();
        }
        assertThat(REDIS_KEYS.parseMatchQueue(null)).isEmpty();
    }

    @Test
    void 同一队列的三把键只差中间一段() {
        assertThat(REDIS_KEYS.matchRank(3, 9)).isEqualTo(REDIS_KEYS.matchQueue(3, 9).replace(":queue:", ":rank:"));
        assertThat(REDIS_KEYS.matchQueueLock(3, 9)).isEqualTo(REDIS_KEYS.matchQueue(3, 9).replace(":queue:", ":lock:"));
        assertThat(REDIS_KEYS.matchQueue(3, 9)).isNotEqualTo(REDIS_KEYS.matchQueue(3, 10)).isNotEqualTo(REDIS_KEYS.matchQueue(1, 9));
    }

    @Test
    void 全部match键同一个hash_tag_Cluster下同槽() {
        List<String> keys = List.of(
                REDIS_KEYS.matchQueueIndex(),
                REDIS_KEYS.matchQueue(3, 0), REDIS_KEYS.matchQueue(1, 77), REDIS_KEYS.matchQueue(5, -1),
                REDIS_KEYS.matchRank(3, 0), REDIS_KEYS.matchRank(5, 1),
                REDIS_KEYS.matchQueueLock(3, 0), REDIS_KEYS.matchQueueLock(1, 2),
                REDIS_KEYS.matchTicket(1), REDIS_KEYS.matchTicket(BIG), REDIS_KEYS.matchTicket(-1L),
                REDIS_KEYS.matchPopMarker("a"), REDIS_KEYS.matchPopMarker("6f1c2d3e-0000-4000-8000-000000000001"),
                REDIS_KEYS.matchRequeueMarker("a"), REDIS_KEYS.matchRequeueMarker("6f1c2d3e-0000-4000-8000-000000000002"),
                REDIS_KEYS.matchChallenge(1), REDIS_KEYS.matchChallenge(BIG),
                REDIS_KEYS.matchChallengeTarget(2), REDIS_KEYS.matchChallengeTarget(BIG),
                REDIS_KEYS.matchChallengeDone(3), REDIS_KEYS.matchChallengeDone(BIG),
                REDIS_KEYS.matchBattlePlacement(4), REDIS_KEYS.matchBattlePlacement(BIG),
                REDIS_KEYS.matchWatching(5), REDIS_KEYS.matchWatching(BIG), REDIS_KEYS.matchWatching(-1L),
                REDIS_KEYS.matchWatchable());
        int expected = slotOf("{match}");
        assertThat(expected).as("tag 内容就是 match").isEqualTo(CRC16.crc16("match".getBytes(StandardCharsets.UTF_8)) % 16384);
        for (String key : keys) {
            assertThat(key).startsWith("game:{match}:");
            assertThat(slotOf(key)).as(key).isEqualTo(expected);
        }
    }

    @Test
    void match键不与战斗锁或组队键同槽_跨tag的键不得进同一段脚本() {
        // 战斗锁按玩家分 tag（6.3 拥有）、组队是 {team}：它们与 match 键不同槽，所以 match 的脚本里不读锁（锁检查在 Java 侧、靠复查兜底）。
        assertThat(slotOf(REDIS_KEYS.battleLock(1))).isNotEqualTo(slotOf(REDIS_KEYS.matchTicket(1)));
        assertThat(slotOf(REDIS_KEYS.teamPlayer(1))).isNotEqualTo(slotOf(REDIS_KEYS.matchTicket(1)));
    }

    /**
     * 观战的两类键（spectate-spec §4.2）必须与票据、落点记录同槽：「没有票据 ∧ 抢标记」「读落点 + 查是否已公开」「按 attempt 守护的剔除」
     * 都各是一段 Lua，键跨了槽脚本就发不出去。战斗锁不在这个槽里，所以锁检查不进脚本。
     */
    @Test
    void 观战标记与可观战索引_和票据_落点记录同一个hash_tag() {
        long player = BIG;
        long battle = Long.MIN_VALUE + 77;
        int ticketSlot = slotOf(REDIS_KEYS.matchTicket(player));

        assertThat(slotOf(REDIS_KEYS.matchWatching(player))).as("观战标记与同一玩家的票据").isEqualTo(ticketSlot);
        assertThat(slotOf(REDIS_KEYS.matchWatchable())).as("可观战索引与票据").isEqualTo(ticketSlot);
        assertThat(slotOf(REDIS_KEYS.matchWatchable())).as("可观战索引与落点记录").isEqualTo(slotOf(REDIS_KEYS.matchBattlePlacement(battle)));
        assertThat(slotOf(REDIS_KEYS.matchWatching(1))).as("不同玩家的标记也同槽（开局清退用一条 MGET 读全员）")
                .isEqualTo(slotOf(REDIS_KEYS.matchWatching(2)));
        assertThat(slotOf(REDIS_KEYS.matchWatching(player))).as("战斗锁不在 match 的槽里").isNotEqualTo(slotOf(REDIS_KEYS.battleLock(player)));
        assertThat(REDIS_KEYS.matchWatching(player)).isNotEqualTo(REDIS_KEYS.matchTicket(player));
        assertThat(REDIS_KEYS.matchWatchable()).as("索引是一把固定的键，不带任何 id").isEqualTo("game:{match}:watchable");
    }

    /** Redis Cluster 的键槽：有 {@code {…}} 且花括号里非空时只对花括号里的内容算 CRC16，否则对整个键算。 */
    private static int slotOf(String key) {
        int open = key.indexOf('{');
        if (open >= 0) {
            int close = key.indexOf('}', open + 1);
            if (close > open + 1) {
                key = key.substring(open + 1, close);
            }
        }
        return CRC16.crc16(key.getBytes(StandardCharsets.UTF_8)) % 16384;
    }
}
