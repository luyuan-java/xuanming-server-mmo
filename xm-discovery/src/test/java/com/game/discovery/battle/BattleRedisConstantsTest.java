package com.game.discovery.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.discovery.RedisProperties;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link BattleRedis} 的跨进程常量、返回码、键形状与脚本的静态约束（scene-battle-spec §7.2 常量表、§10.4、§13.1；不连 Redis）。
 * 这里的不等式是跨批次的数值依赖：改任何一个常量都必须让它们继续成立。
 */
class BattleRedisConstantsTest {

    /** node-spec §10.4：battle 的确认补发窗口（秒）与 match 的备战期限上限（秒）。两个数分别属于 xm-battle / 6.4，这里只能按字面值钉住。 */
    private static final long CONFIRM_RESEND_WINDOW_SEC = 180;
    private static final long PREPARE_DEADLINE_MAX_SEC = 96;
    /** scene-battle-spec §8：{@code xm.battle.scene-rpc-timeout} 的缺省值（xm-battle 的配置，这里按字面值钉住）。 */
    private static final long SCENE_RPC_TIMEOUT_DEFAULT_MS = 5000;

    @Test
    void 常量逐值钉住_与规格常量表一致() {
        assertThat(BattleRedis.LOCK_EXTRA_TTL_SEC).isEqualTo(60);
        assertThat(BattleRedis.LOCK_HOLD_AFTER_APPLY_SEC).isEqualTo(180);
        assertThat(BattleRedis.SETTLEMENT_TTL_SEC).isEqualTo(604_800).isEqualTo(Duration.ofDays(7).toSeconds());
        assertThat(BattleRedis.SETTLEMENT_RETRY_INTERVAL).isEqualTo(Duration.ofSeconds(10));
        assertThat(BattleRedis.SETTLEMENT_RETRY_MAX).isEqualTo(12);
        assertThat(BattleRedis.ACTIVITY_RESULT_TTL_SEC).isEqualTo(604_800);
        assertThat(BattleRedis.ACTIVITY_RETRY_INTERVAL).isEqualTo(Duration.ofSeconds(10));
        assertThat(BattleRedis.ACTIVITY_RETRY_MAX).isEqualTo(30);
        assertThat(BattleRedis.REAPER_INTERVAL).isEqualTo(Duration.ofSeconds(30));
        assertThat(BattleRedis.FIGHTING_EXPIRY_GRACE).isEqualTo(Duration.ofSeconds(10));
        assertThat(BattleRedis.OUTBOX_MAX_AGE).isEqualTo(Duration.ofMinutes(10));
        assertThat(BattleRedis.SETTLED_TOMBSTONE_TTL_SEC).isEqualTo(600);
        assertThat(BattleRedis.SETTLEMENT_FIELDS_WARN).isEqualTo(16);
        assertThat(BattleRedis.LEDGER_CAPACITY).isEqualTo(64);
    }

    @Test
    void 锁字段名与阶段取值() {
        assertThat(BattleRedis.FIELD_BATTLE).isEqualTo("b");
        assertThat(BattleRedis.FIELD_NODE).isEqualTo("n");
        assertThat(BattleRedis.FIELD_STATE).isEqualTo("s");
        assertThat(BattleRedis.FIELD_DEADLINE).isEqualTo("d");
        assertThat(BattleRedis.FIELD_PREPARE_DEADLINE).isEqualTo("p");
        assertThat(BattleRedis.STATE_PREPARING).isEqualTo("P");
        assertThat(BattleRedis.STATE_FIGHTING).isEqualTo("F");
    }

    @Test
    void 返回码取值_三组各自互不相同() {
        assertThat(BattleRedis.STORE_ALREADY_SETTLED).isEqualTo(-1);
        assertThat(BattleRedis.TOUCH_MISS).isEqualTo(0);
        assertThat(BattleRedis.TOUCH_HIT).isEqualTo(1);
        assertThat(BattleRedis.TOUCH_KEPT_FIGHTING).isEqualTo(2);
        assertThat(BattleRedis.PREPARING_DELETE_MISS).isEqualTo(0);
        assertThat(BattleRedis.PREPARING_DELETE_DONE).isEqualTo(1);
        assertThat(BattleRedis.PREPARING_DELETE_FIGHTING).isEqualTo(2);
    }

    @Test
    void 结算后续锁_不短于整个重投窗口加余量() {
        // §7.2：LOCK_HOLD ≥ INTERVAL × MAX + 60——重投窗口内锁一直在，迟到的重投按锁判得出「仍是本局」
        long retryWindowSec = BattleRedis.SETTLEMENT_RETRY_INTERVAL.toSeconds() * BattleRedis.SETTLEMENT_RETRY_MAX;
        assertThat(retryWindowSec).isEqualTo(120);
        assertThat(BattleRedis.LOCK_HOLD_AFTER_APPLY_SEC).isGreaterThanOrEqualTo(retryWindowSec + BattleRedis.LOCK_EXTRA_TTL_SEC);
    }

    @Test
    void 判废宽限加reaper间隔_严格小于锁余量() {
        // D21 / 评审修订 14：第一次 rescue 最晚在期限 + GRACE + 一个 reaper 间隔，此时锁（期限 + 60 s）一定还在
        long latestFirstRescueSec = BattleRedis.FIGHTING_EXPIRY_GRACE.toSeconds() + BattleRedis.REAPER_INTERVAL.toSeconds();
        assertThat(latestFirstRescueSec).isEqualTo(40);
        assertThat(latestFirstRescueSec).isLessThan(BattleRedis.LOCK_EXTRA_TTL_SEC);
    }

    @Test
    void 确认补发窗口_盖得住备战期限上限加锁余量() {
        // node-spec §10.4：180 ≥ 96 + LOCK_EXTRA_TTL_SEC——确认还在补发时备战锁不会先过期（6.2 的 ConfirmWindowConstraintTest 应引用同一个常量）
        assertThat(CONFIRM_RESEND_WINDOW_SEC).isGreaterThanOrEqualTo(PREPARE_DEADLINE_MAX_SEC + BattleRedis.LOCK_EXTRA_TTL_SEC);
        assertThat(BattleRedis.lockTtlSec(PREPARE_DEADLINE_MAX_SEC * 1000, 0)).isLessThanOrEqualTo(CONFIRM_RESEND_WINDOW_SEC);
    }

    @Test
    void scene一条脚本的最坏耗时_小于battle调scene的缺省超时() {
        // §7.2 / §8：Redisson retryAttempts = 1 → (1 + 1) × 2000 + 200 = 4.2 s；xm.battle.scene-rpc-timeout 缺省 5 s 必须更大
        long worst = new RedisProperties(null, null, null, null, null, null, null).worstCaseCommandMillis();
        assertThat(worst).isEqualTo(4200);
        assertThat(worst).isLessThan(SCENE_RPC_TIMEOUT_DEFAULT_MS);
    }

    @Test
    void 已销账墓碑_不短于发件箱条目的最长寿命() {
        // 发件箱里一份结算最多活 OUTBOX_MAX_AGE；墓碑比它短的话，条目还在重发时墓碑先没了，销账之后的落库又能把记录造出来
        assertThat(BattleRedis.SETTLED_TOMBSTONE_TTL_SEC).isGreaterThanOrEqualTo(BattleRedis.OUTBOX_MAX_AGE.toSeconds());
        // 也盖得住整个重投窗口（12 × 10 s）与 Redisson 单条命令的最坏耗时
        assertThat(BattleRedis.SETTLED_TOMBSTONE_TTL_SEC).isGreaterThan(
                BattleRedis.SETTLEMENT_RETRY_INTERVAL.toSeconds() * (BattleRedis.SETTLEMENT_RETRY_MAX + 1));
        // 远短于记录本身的 7 天：墓碑不会堆积
        assertThat(BattleRedis.SETTLED_TOMBSTONE_TTL_SEC).isLessThan(BattleRedis.SETTLEMENT_TTL_SEC);
    }

    // ------------------------------------------------------------------ 键形状

    @Test
    void 键名_xm前缀_玩家号与战斗号按无符号十进制() {
        long player = Long.MIN_VALUE + 7;          // 2^63 + 7
        long battle = -1L;                         // 2^64 − 1
        assertThat(RedisKeys.battleLock(player)).isEqualTo("xm:battle:{9223372036854775815}:lock");
        assertThat(RedisKeys.battleSettlements(player)).isEqualTo("xm:battle:{9223372036854775815}:settlement");
        assertThat(RedisKeys.battleSettled(player, battle)).isEqualTo("xm:battle:{9223372036854775815}:settled:18446744073709551615");
        assertThat(RedisKeys.battleActivityResult(battle)).isEqualTo("xm:battle:activity-result:18446744073709551615");
        assertThat(RedisKeys.battleLock(42)).isEqualTo("xm:battle:{42}:lock").startsWith(RedisKeys.PREFIX);
    }

    @Test
    void 同一玩家的锁_记录_墓碑_同hash_tag同槽_不同玩家不同tag() {
        long player = Long.MIN_VALUE + 123_456_789L;
        String lock = RedisKeys.battleLock(player);
        String settlement = RedisKeys.battleSettlements(player);
        String settledA = RedisKeys.battleSettled(player, 1);
        String settledB = RedisKeys.battleSettled(player, Long.MIN_VALUE + 9);

        assertThat(hashTag(lock)).isEqualTo(Long.toUnsignedString(player));
        assertThat(hashTag(settlement)).isEqualTo(hashTag(lock));
        assertThat(hashTag(settledA)).isEqualTo(hashTag(lock));
        assertThat(hashTag(settledB)).isEqualTo(hashTag(lock));
        // CLUSTER KEYSLOT 的算法（CRC16-XMODEM 取 hash tag，模 16384）：销账 / 取代 / 落库三段多键 Lua 在 Cluster 下同槽
        assertThat(keySlot(settlement)).isEqualTo(keySlot(lock));
        assertThat(keySlot(settledA)).isEqualTo(keySlot(lock));
        assertThat(keySlot(settledB)).isEqualTo(keySlot(lock));
        // 墓碑按 (玩家, 战斗) 分键
        assertThat(settledA).isNotEqualTo(settledB);
        assertThat(hashTag(RedisKeys.battleLock(player + 1))).isNotEqualTo(hashTag(lock));
    }

    @Test
    void 槽位算法自检_与Redis文档的样例一致() {
        // Redis Cluster 规范的样例：CRC16("123456789") = 0x31C3；{user1000}.following 与 {user1000}.followers 同槽
        assertThat(crc16("123456789".getBytes(StandardCharsets.US_ASCII))).isEqualTo(0x31C3);
        assertThat(keySlot("{user1000}.following")).isEqualTo(keySlot("{user1000}.followers")).isEqualTo(keySlot("user1000"));
        assertThat(keySlot("foo{}{bar}")).isEqualTo(crc16("foo{}{bar}".getBytes(StandardCharsets.US_ASCII)) % 16384);
    }

    // ------------------------------------------------------------------ 脚本的静态约束

    @Test
    void 脚本清单_与规格表加本次补充逐段对上() {
        assertThat(scripts().keySet()).containsExactlyInAnyOrder(
                "PREPARE_LOCK", "CONFIRM", "CANCEL_OFFLINE", "DELETE_IF_MATCH", "TOUCH", "HOLD", "ACK", "ENTER_READ", "READ_IF_OURS",
                "READ_LOCK_BATTLE", "DELETE_SETTLEMENT_FIELD", "STORE_SETTLEMENT", "PROBE_SETTLEMENT", "ACK_IF_SUPERSEDED",
                "STORE_ACTIVITY_RESULT", "PROBE_ACTIVITY_RESULT");
    }

    @Test
    void 每段脚本只碰KEYS传入的键_不在Lua里拼键() {
        Pattern call = Pattern.compile("redis\\.call\\('([A-Z]+)',\\s*([^,)]+)");
        scripts().forEach((name, script) -> {
            assertThat(script).as("%s 不许有字符串拼接（拼键）", name).doesNotContain("..");
            assertThat(script).as("%s 不许写死键名", name).doesNotContain("xm:").doesNotContain("battle:");
            Matcher m = call.matcher(script);
            int calls = 0;
            while (m.find()) {
                calls++;
                assertThat(m.group(2).trim()).as("%s 的 %s 第一个参数必须是 KEYS[n]", name, m.group(1)).matches("KEYS\\[[123]\\]");
            }
            assertThat(calls).as("%s 至少有一次 redis.call", name).isPositive();
        });
    }

    @Test
    void 墓碑经KEYS传入_销账写_落库读() {
        Map<String, String> scripts = scripts();
        // ACK：KEYS = lock, settlement, 墓碑；无条件 SET EX（脚本末尾、不在任何 if 里）
        assertThat(scripts.get("ACK")).contains("redis.call('SET', KEYS[3], '1', 'EX', ARGV[2])\nreturn r");
        // ACK_IF_SUPERSEDED：只在删记录的分支写
        assertThat(scripts.get("ACK_IF_SUPERSEDED"))
                .contains("redis.call('HDEL', KEYS[2], ARGV[1])\nredis.call('SET', KEYS[3], '1', 'EX', ARGV[2])\nreturn 2");
        // STORE_SETTLEMENT：第一步就看墓碑，见到就回 -1、后面的 HSET / EXPIRE 不执行
        assertThat(scripts.get("STORE_SETTLEMENT")).startsWith("if redis.call('EXISTS', KEYS[2]) == 1 then\n  return -1\nend\n");
        // 其余脚本不碰第三个键
        scripts.forEach((name, script) -> {
            if (!name.equals("ACK") && !name.equals("ACK_IF_SUPERSEDED")) {
                assertThat(script).as(name).doesNotContain("KEYS[3]");
            }
        });
    }

    @Test
    void 只读脚本没有写命令() {
        Pattern write = Pattern.compile("'(HSET|HDEL|DEL|SET|EXPIRE|PEXPIRE|HINCRBY|INCR)'");
        for (String name : new String[] {"ENTER_READ", "READ_IF_OURS", "READ_LOCK_BATTLE", "PROBE_SETTLEMENT", "PROBE_ACTIVITY_RESULT"}) {
            assertThat(write.matcher(scripts().get(name)).find()).as("%s 必须只读", name).isFalse();
        }
    }

    /** 反射收集 {@link BattleRedis} 里全部 Lua 脚本常量（含 redis.call 的 static final String），新加的脚本自动进检查。 */
    private static Map<String, String> scripts() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field field : BattleRedis.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                String value = (String) field.get(null);
                if (value != null && value.contains("redis.call")) {
                    out.put(field.getName(), value);
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return out;
    }

    /** Redis Cluster 的 hash tag 规则：第一个 {@code {} 与其后第一个 {@code }} 之间的非空内容；没有就是整个键。 */
    private static String hashTag(String key) {
        int open = key.indexOf('{');
        if (open >= 0) {
            int close = key.indexOf('}', open + 1);
            if (close > open + 1) {
                return key.substring(open + 1, close);
            }
        }
        return key;
    }

    private static int keySlot(String key) {
        return crc16(hashTag(key).getBytes(StandardCharsets.UTF_8)) % 16384;
    }

    /** CRC16-CCITT（XMODEM，多项式 0x1021，初值 0），Redis Cluster 用的那一个。 */
    private static int crc16(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }
}
