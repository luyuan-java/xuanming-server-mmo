package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys.MatchQueueId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.connection.CRC16;

/**
 * 匹配的 Redis 键（match-spec §9.4、§15.1 的 QueueKeysTest；对照基线 {@code go/match/internal/logic/keys_test.go}，去掉 legacy 用例）：
 * 键形状逐字钉住（xm-match 的 Lua 与 6.5 都按它拼 / 读）；队列键可以从注册集成员解析回来；全部 match 键同一个 hash tag、Cluster 下同槽。
 */
class RedisKeysMatchTest {

    /** ≥ 2^63 的玩家号 / 战斗号：键里必须是无符号十进制。 */
    private static final long BIG = Long.MIN_VALUE + 4301;
    private static final String BIG_TEXT = "9223372036854780109";

    @Test
    void 键形状逐字钉住() {
        assertThat(RedisKeys.matchQueueIndex()).isEqualTo("xm:{match}:index");
        assertThat(RedisKeys.matchQueue(3, 0)).isEqualTo("xm:{match}:queue:3:0");
        assertThat(RedisKeys.matchQueue(5, 1)).isEqualTo("xm:{match}:queue:5:1");
        assertThat(RedisKeys.matchRank(1, 7)).isEqualTo("xm:{match}:rank:1:7");
        assertThat(RedisKeys.matchQueueLock(1, 7)).isEqualTo("xm:{match}:lock:1:7");
        assertThat(RedisKeys.matchTicket(1001)).isEqualTo("xm:{match}:ticket:1001");
        assertThat(RedisKeys.matchPopMarker("6f1c2d3e-aaaa")).isEqualTo("xm:{match}:pop:6f1c2d3e-aaaa");
        assertThat(RedisKeys.matchRequeueMarker("6f1c2d3e-bbbb")).isEqualTo("xm:{match}:requeue:6f1c2d3e-bbbb");
        assertThat(RedisKeys.matchChallenge(88)).isEqualTo("xm:{match}:challenge:88");
        assertThat(RedisKeys.matchChallengeTarget(1001)).isEqualTo("xm:{match}:challenge-target:1001");
        assertThat(RedisKeys.matchChallengeDone(88)).isEqualTo("xm:{match}:challenge-done:88");
        assertThat(RedisKeys.matchBattlePlacement(99)).isEqualTo("xm:{match}:battle:99");
        assertThat(RedisKeys.matchWatching(1001)).isEqualTo("xm:{match}:watching:1001");
        assertThat(RedisKeys.matchWatchable()).isEqualTo("xm:{match}:watchable");
    }

    @Test
    void 玩家号与各种id按无符号十进制_配置号按uint32() {
        assertThat(RedisKeys.matchTicket(BIG)).isEqualTo("xm:{match}:ticket:" + BIG_TEXT);
        assertThat(RedisKeys.matchChallenge(BIG)).isEqualTo("xm:{match}:challenge:" + BIG_TEXT);
        assertThat(RedisKeys.matchChallengeTarget(BIG)).isEqualTo("xm:{match}:challenge-target:" + BIG_TEXT);
        assertThat(RedisKeys.matchChallengeDone(BIG)).isEqualTo("xm:{match}:challenge-done:" + BIG_TEXT);
        assertThat(RedisKeys.matchBattlePlacement(BIG)).isEqualTo("xm:{match}:battle:" + BIG_TEXT);
        assertThat(RedisKeys.matchWatching(BIG)).isEqualTo("xm:{match}:watching:" + BIG_TEXT);
        assertThat(RedisKeys.matchWatching(-1L)).isEqualTo("xm:{match}:watching:18446744073709551615");
        assertThat(RedisKeys.matchTicket(-1L)).isEqualTo("xm:{match}:ticket:18446744073709551615");
        assertThat(RedisKeys.matchQueue(3, -1)).as("battle_config_id 是 uint32：0xFFFFFFFF").isEqualTo("xm:{match}:queue:3:4294967295");
        assertThat(RedisKeys.matchRank(3, Integer.MIN_VALUE)).isEqualTo("xm:{match}:rank:3:2147483648");
        assertThat(RedisKeys.matchQueueLock(3, -1)).isEqualTo("xm:{match}:lock:3:4294967295");
    }

    @Test
    void 模式为负是调用方的错() {
        assertThatThrownBy(() -> RedisKeys.matchQueue(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisKeys.matchRank(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisKeys.matchQueueLock(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchQueueId(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 队列键解析往返() {
        int[][] cases = {{0, 0}, {1, 0}, {3, 0}, {5, 1}, {6, 12345}, {3, -1}, {1, Integer.MIN_VALUE}, {Integer.MAX_VALUE, Integer.MAX_VALUE}};
        for (int[] c : cases) {
            assertThat(RedisKeys.parseMatchQueue(RedisKeys.matchQueue(c[0], c[1])))
                    .as("mode=%d config=%s", c[0], Integer.toUnsignedString(c[1]))
                    .contains(new MatchQueueId(c[0], c[1]));
        }
        assertThat(RedisKeys.parseMatchQueue("xm:{match}:queue:3:4294967295")).contains(new MatchQueueId(3, -1));
    }

    @Test
    void 不是规范队列键的一律解析为空() {
        for (String bad : List.of(
                "", "xm:{match}:queue:", "xm:{match}:queue:3", "xm:{match}:queue:3:", "xm:{match}:queue::0",
                "xm:{match}:queue:3:0:1", "xm:{match}:queue:03:0", "xm:{match}:queue:3:00", "xm:{match}:queue:+3:0",
                "xm:{match}:queue:-1:0", "xm:{match}:queue:3:-1", "xm:{match}:queue:3:4294967296", "xm:{match}:queue:2147483648:0",
                "xm:{match}:queue:3:1e3", "xm:{match}:queue:3: 1", "xm:{match}:queue:a:0", "xm:{match}:queue:3:99999999999",
                // 别的键、别的 tag、基线键、少了前缀
                "xm:{match}:rank:3:0", "xm:{match}:lock:3:0", "xm:{match}:index", "xm:match:queue:3:0", "match:{mq}:queue:3:0",
                "{match}:queue:3:0", "XM:{match}:queue:3:0")) {
            assertThat(RedisKeys.parseMatchQueue(bad)).as("'%s'", bad).isEmpty();
        }
        assertThat(RedisKeys.parseMatchQueue(null)).isEmpty();
    }

    @Test
    void 同一队列的三把键只差中间一段() {
        assertThat(RedisKeys.matchRank(3, 9)).isEqualTo(RedisKeys.matchQueue(3, 9).replace(":queue:", ":rank:"));
        assertThat(RedisKeys.matchQueueLock(3, 9)).isEqualTo(RedisKeys.matchQueue(3, 9).replace(":queue:", ":lock:"));
        assertThat(RedisKeys.matchQueue(3, 9)).isNotEqualTo(RedisKeys.matchQueue(3, 10)).isNotEqualTo(RedisKeys.matchQueue(1, 9));
    }

    @Test
    void 全部match键同一个hash_tag_Cluster下同槽() {
        List<String> keys = List.of(
                RedisKeys.matchQueueIndex(),
                RedisKeys.matchQueue(3, 0), RedisKeys.matchQueue(1, 77), RedisKeys.matchQueue(5, -1),
                RedisKeys.matchRank(3, 0), RedisKeys.matchRank(5, 1),
                RedisKeys.matchQueueLock(3, 0), RedisKeys.matchQueueLock(1, 2),
                RedisKeys.matchTicket(1), RedisKeys.matchTicket(BIG), RedisKeys.matchTicket(-1L),
                RedisKeys.matchPopMarker("a"), RedisKeys.matchPopMarker("6f1c2d3e-0000-4000-8000-000000000001"),
                RedisKeys.matchRequeueMarker("a"), RedisKeys.matchRequeueMarker("6f1c2d3e-0000-4000-8000-000000000002"),
                RedisKeys.matchChallenge(1), RedisKeys.matchChallenge(BIG),
                RedisKeys.matchChallengeTarget(2), RedisKeys.matchChallengeTarget(BIG),
                RedisKeys.matchChallengeDone(3), RedisKeys.matchChallengeDone(BIG),
                RedisKeys.matchBattlePlacement(4), RedisKeys.matchBattlePlacement(BIG),
                RedisKeys.matchWatching(5), RedisKeys.matchWatching(BIG), RedisKeys.matchWatching(-1L),
                RedisKeys.matchWatchable());
        int expected = slotOf("{match}");
        assertThat(expected).as("tag 内容就是 match").isEqualTo(CRC16.crc16("match".getBytes(StandardCharsets.UTF_8)) % 16384);
        for (String key : keys) {
            assertThat(key).startsWith("xm:{match}:");
            assertThat(slotOf(key)).as(key).isEqualTo(expected);
        }
    }

    @Test
    void match键不与战斗锁或组队键同槽_跨tag的键不得进同一段脚本() {
        // 战斗锁按玩家分 tag（6.3 拥有）、组队是 {team}：它们与 match 键不同槽，所以 match 的脚本里不读锁（锁检查在 Java 侧、靠复查兜底）。
        assertThat(slotOf(RedisKeys.battleLock(1))).isNotEqualTo(slotOf(RedisKeys.matchTicket(1)));
        assertThat(slotOf(RedisKeys.teamPlayer(1))).isNotEqualTo(slotOf(RedisKeys.matchTicket(1)));
    }

    /**
     * 观战的两类键（spectate-spec §4.2）必须与票据、落点记录同槽：「没有票据 ∧ 抢标记」「读落点 + 查是否已公开」「按 attempt 守护的剔除」
     * 都各是一段 Lua，键跨了槽脚本就发不出去。战斗锁不在这个槽里，所以锁检查不进脚本。
     */
    @Test
    void 观战标记与可观战索引_和票据_落点记录同一个hash_tag() {
        long player = BIG;
        long battle = Long.MIN_VALUE + 77;
        int ticketSlot = slotOf(RedisKeys.matchTicket(player));

        assertThat(slotOf(RedisKeys.matchWatching(player))).as("观战标记与同一玩家的票据").isEqualTo(ticketSlot);
        assertThat(slotOf(RedisKeys.matchWatchable())).as("可观战索引与票据").isEqualTo(ticketSlot);
        assertThat(slotOf(RedisKeys.matchWatchable())).as("可观战索引与落点记录").isEqualTo(slotOf(RedisKeys.matchBattlePlacement(battle)));
        assertThat(slotOf(RedisKeys.matchWatching(1))).as("不同玩家的标记也同槽（开局清退用一条 MGET 读全员）")
                .isEqualTo(slotOf(RedisKeys.matchWatching(2)));
        assertThat(slotOf(RedisKeys.matchWatching(player))).as("战斗锁不在 match 的槽里").isNotEqualTo(slotOf(RedisKeys.battleLock(player)));
        assertThat(RedisKeys.matchWatching(player)).isNotEqualTo(RedisKeys.matchTicket(player));
        assertThat(RedisKeys.matchWatchable()).as("索引是一把固定的键，不带任何 id").isEqualTo("xm:{match}:watchable");
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
