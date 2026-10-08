package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.placement.PlacementRecords;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 观战存储 11 段 Lua 的静态约束（不连 Redis；行为由 {@code RedissonSpectateStoreIntegrationTest} 在真 Redis 上钉）：不在 Lua 里拼键名、键只从 KEYS 来；
 * 只读的六段没有写命令；要用「现在」的脚本自己取 {@code TIME}；写成字面量的落点字段名、剔除模式、返回值编码与 Java 侧的常量一致；
 * 玩家号 / 战斗号 / 成员 / 标记值从不 {@code tonumber}。
 */
class SpectateScriptsTest {

    private static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        ALL.put("S_W_ENTRY", SpectateScripts.ENTRY);
        ALL.put("S_W_ACQUIRE", SpectateScripts.ACQUIRE);
        ALL.put("S_W_RELEASE", SpectateScripts.RELEASE);
        ALL.put("S_W_MARKS", SpectateScripts.MARKS);
        ALL.put("S_W_READ", SpectateScripts.READ);
        ALL.put("S_W_RECORDS", SpectateScripts.RECORDS);
        ALL.put("S_W_PICK", SpectateScripts.PICK);
        ALL.put("S_W_EVICT", SpectateScripts.EVICT);
        ALL.put("S_W_PUBLISH", SpectateScripts.PUBLISH);
        ALL.put("S_W_SWEEP", SpectateScripts.SWEEP);
        ALL.put("S_W_LIST", SpectateScripts.LIST);
    }

    /** 规格 §4.3 的九段里只读的四段，加上两段批读。 */
    private static final Set<String> READ_ONLY = Set.of("S_W_ENTRY", "S_W_MARKS", "S_W_READ", "S_W_RECORDS", "S_W_PICK", "S_W_LIST");

    private static final Pattern LOWERCASE_LITERAL = Pattern.compile("'([a-z_]+)'");
    private static final Pattern REDIS_COMMAND = Pattern.compile("redis\\.p?call\\('([A-Z]+)'");
    private static final Pattern KEY_REFERENCE = Pattern.compile("KEYS\\[([^\\]]+)\\]");
    private static final Pattern ARGV_REFERENCE = Pattern.compile("ARGV\\[([^\\]]+)\\]");
    private static final Set<String> WRITE_COMMANDS = Set.of("DEL", "UNLINK", "SET", "SETEX", "PSETEX", "PEXPIRE", "EXPIRE", "HSET", "HDEL", "ZADD", "ZREM",
            "ZREMRANGEBYSCORE", "ZREMRANGEBYRANK", "ZINCRBY", "SADD", "SREM", "LPUSH", "RPUSH", "LREM");
    private static final String TIME = "redis.call('TIME')";

    private static Set<String> matches(Pattern pattern, String lua) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(lua);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Set<String> commandsOf(String lua) {
        return matches(REDIS_COMMAND, lua);
    }

    private static int count(String lua, String needle) {
        int n = 0;
        for (int at = lua.indexOf(needle); at >= 0; at = lua.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void 一共十一段_规格的九段加两段批读_互不相同() {
        assertThat(ALL).hasSize(11);
        assertThat(Set.copyOf(ALL.values())).hasSize(11);
        assertThat(ALL.values()).allSatisfy(lua -> assertThat(lua).isNotBlank());
        assertThat(ALL.keySet()).as("规格 §4.3 的九段一段不少").contains("S_W_ENTRY", "S_W_ACQUIRE", "S_W_RELEASE", "S_W_READ", "S_W_PICK", "S_W_EVICT",
                "S_W_PUBLISH", "S_W_SWEEP", "S_W_LIST");
    }

    @Test
    void 不在Lua里拼键名_键只从KEYS来() {
        ALL.forEach((name, lua) -> {
            assertThat(lua).as(name).doesNotContain("xm:").doesNotContain("{match}").doesNotContain("watching").doesNotContain("watchable");
            assertThat(lua).as("%s 不拼接字符串（造不出键名）", name).doesNotContain("..");
        });
    }

    @Test
    void 各段声明的KEYS与ARGV钉住_没有声明之外的下标() {
        assertThat(matches(KEY_REFERENCE, SpectateScripts.ENTRY)).containsExactly("1", "2");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.ACQUIRE)).containsExactly("1", "2");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.RELEASE)).containsExactly("1");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.MARKS)).containsExactly("i");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.READ)).containsExactly("1", "2");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.RECORDS)).containsExactly("i");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.PICK)).containsExactly("1");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.EVICT)).containsExactly("1", "2");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.PUBLISH)).containsExactly("1", "2");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.SWEEP)).containsExactly("1");
        assertThat(matches(KEY_REFERENCE, SpectateScripts.LIST)).containsExactly("1");

        assertThat(matches(ARGV_REFERENCE, SpectateScripts.ENTRY)).isEmpty();
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.ACQUIRE)).containsExactly("1", "2");
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.RELEASE)).containsExactly("1");
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.MARKS)).isEmpty();
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.READ)).containsExactly("1");
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.RECORDS)).isEmpty();
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.PICK)).containsExactly("1", "2");
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.EVICT)).containsExactly("1", "2", "3");
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.PUBLISH)).containsExactly("1", "2", "3");
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.SWEEP)).containsExactly("1");
        assertThat(matches(ARGV_REFERENCE, SpectateScripts.LIST)).containsExactly("1");
    }

    @Test
    void 只读的六段没有写命令_可变的五段各至少有一条() {
        ALL.forEach((name, lua) -> {
            Set<String> writes = new TreeSet<>(commandsOf(lua));
            writes.retainAll(WRITE_COMMANDS);
            if (READ_ONLY.contains(name)) {
                assertThat(writes).as(name).isEmpty();
            } else {
                assertThat(writes).as(name).isNotEmpty();
            }
        });
        Set<String> mutable = new TreeSet<>(ALL.keySet());
        mutable.removeAll(READ_ONLY);
        assertThat(mutable).containsExactly("S_W_ACQUIRE", "S_W_EVICT", "S_W_PUBLISH", "S_W_RELEASE", "S_W_SWEEP");
    }

    @Test
    void 各段用到的命令钉住() {
        assertThat(commandsOf(SpectateScripts.ENTRY)).containsExactly("EXISTS", "GET");
        assertThat(commandsOf(SpectateScripts.ACQUIRE)).containsExactly("EXISTS", "GET", "SET");
        assertThat(commandsOf(SpectateScripts.RELEASE)).containsExactly("DEL", "GET");
        assertThat(commandsOf(SpectateScripts.MARKS)).containsExactly("GET");
        assertThat(commandsOf(SpectateScripts.READ)).containsExactly("EXISTS", "HMGET", "TIME", "ZSCORE");
        assertThat(commandsOf(SpectateScripts.RECORDS)).containsExactly("EXISTS", "HMGET");
        assertThat(commandsOf(SpectateScripts.PICK)).containsExactly("TIME", "ZCOUNT", "ZRANGEBYSCORE");
        assertThat(commandsOf(SpectateScripts.EVICT)).containsExactly("DEL", "EXISTS", "HGET", "ZREM", "ZSCORE");
        assertThat(commandsOf(SpectateScripts.PUBLISH)).containsExactly("HGET", "ZADD");
        assertThat(commandsOf(SpectateScripts.SWEEP)).containsExactly("TIME", "ZREMRANGEBYSCORE");
        assertThat(commandsOf(SpectateScripts.LIST)).containsExactly("TIME", "ZREVRANGE");
    }

    @Test
    void 缺记录的剔除永不删落点_清扫只摘成员() {
        String evict = SpectateScripts.EVICT;
        int missing = evict.indexOf("if mode == 'missing' then");
        int dead = evict.indexOf("if mode == 'dead' then");
        assertThat(missing).isPositive();
        assertThat(dead).isGreaterThan(missing);
        assertThat(evict.substring(evict.indexOf("if mode == 'invalid' then"), missing)).as("invalid 分支").doesNotContain("'DEL'");
        assertThat(evict.substring(missing, dead)).as("missing 分支：读到落点缺失时只 ZREM").doesNotContain("'DEL'").contains("'ZREM'");
        assertThat(commandsOf(SpectateScripts.SWEEP)).doesNotContain("DEL");
        assertThat(commandsOf(SpectateScripts.PUBLISH)).as("公开不改落点").doesNotContain("DEL", "HSET", "PEXPIRE");
    }

    @Test
    void 要用现在的四段自己取TIME_恰好一处_其余各段不碰时钟() {
        Set<String> timed = Set.of("S_W_READ", "S_W_PICK", "S_W_SWEEP", "S_W_LIST");
        ALL.forEach((name, lua) -> assertThat(count(lua, TIME)).as(name).isEqualTo(timed.contains(name) ? 1 : 0));
        assertThat(count(SpectateScripts.NOW, TIME)).isEqualTo(1);
        // 拼进命令的毫秒数一律 %.0f：Lua 的 tostring 对大数会输出科学计数法
        assertThat(SpectateScripts.PICK).contains("string.format('%.0f', now - tonumber(ARGV[2]))");
        assertThat(SpectateScripts.SWEEP).contains("string.format('(%.0f', now - tonumber(ARGV[1]))").contains("'-inf'");
        assertThat(SpectateScripts.PICK).as("区间是闭的：恰在分界上的成员还算活着").contains("cutoff, '+inf'").doesNotContain("'('");
    }

    @Test
    void 脚本里的小写字面量只有落点的两个字段名与四种剔除模式_与Java侧的常量一致() {
        Set<String> used = new TreeSet<>();
        ALL.values().forEach(lua -> used.addAll(matches(LOWERCASE_LITERAL, lua)));

        assertThat(used).containsExactlyInAnyOrder(PlacementRecords.FIELD_ATTEMPT, PlacementRecords.FIELD_PLACEMENT, SpectateScripts.MODE_INVALID,
                SpectateScripts.MODE_MISSING, SpectateScripts.MODE_DEAD, SpectateScripts.MODE_STALE);
        assertThat(matches(LOWERCASE_LITERAL, SpectateScripts.EVICT)).containsExactlyInAnyOrder("a", "invalid", "missing", "dead", "stale");
        assertThat(matches(LOWERCASE_LITERAL, SpectateScripts.READ)).containsExactlyInAnyOrder("a", "pb");
        assertThat(matches(LOWERCASE_LITERAL, SpectateScripts.RECORDS)).containsExactlyInAnyOrder("a", "pb");
        assertThat(matches(LOWERCASE_LITERAL, SpectateScripts.PUBLISH)).containsExactly("a");
        for (String mode : Set.of(SpectateScripts.MODE_INVALID, SpectateScripts.MODE_MISSING, SpectateScripts.MODE_DEAD, SpectateScripts.MODE_STALE)) {
            assertThat(SpectateScripts.EVICT).contains("if mode == '" + mode + "' then");
        }
    }

    @Test
    void 返回值的编码与Java侧的常量一致() {
        assertThat(SpectateScripts.ACQUIRE_OK).isZero();
        assertThat(SpectateScripts.ACQUIRE).contains("if redis.call('EXISTS', KEYS[1]) == 1 then return " + SpectateScripts.ACQUIRE_QUEUED + " end")
                .contains("if cur == ARGV[1] then return " + SpectateScripts.ACQUIRE_OK + " end")
                .contains("return " + SpectateScripts.ACQUIRE_BUSY + "\n")
                .endsWith("return " + SpectateScripts.ACQUIRE_OK + "\n");
        for (String lua : new String[] {SpectateScripts.READ, SpectateScripts.RECORDS}) {
            assertThat(lua).contains("if f.err then return " + SpectateScripts.RECORD_WRONG_TYPE + ", '', '' end")
                    .contains("== 0 then return " + SpectateScripts.RECORD_ABSENT + ", '', '' end")
                    .contains("return " + SpectateScripts.RECORD_PRESENT + ", f[1] or '', f[2] or ''");
        }
        assertThat(Set.of(SpectateScripts.RECORD_ABSENT, SpectateScripts.RECORD_PRESENT, SpectateScripts.RECORD_WRONG_TYPE)).hasSize(3);
    }

    @Test
    void 玩家号_战斗号_成员_标记值从不tonumber_转成数字的只有分数_分界_随机数_条数与过期时长() {
        Pattern toNumber = Pattern.compile("tonumber\\(([^)]*)\\)");
        Set<String> converted = new TreeSet<>();
        ALL.forEach((name, lua) -> {
            Matcher matcher = toNumber.matcher(lua);
            while (matcher.find()) {
                converted.add(name + ":" + matcher.group(1));
            }
        });

        assertThat(converted).containsExactlyInAnyOrder("S_W_PICK:ARGV[1]", "S_W_PICK:ARGV[2]", "S_W_EVICT:score", "S_W_EVICT:ARGV[3]",
                "S_W_SWEEP:ARGV[1]", "S_W_LIST:ARGV[1]");
    }

    @Test
    void 回复数组里不放nil_缺的值用空串占位() {
        for (String lua : new String[] {SpectateScripts.ENTRY, SpectateScripts.READ, SpectateScripts.RECORDS}) {
            assertThat(lua).contains("''");
        }
        assertThat(SpectateScripts.ENTRY).contains("return {has_ticket, 1, mark}").contains("return {has_ticket, 0, ''}");
        assertThat(SpectateScripts.READ).contains("return {now, published, state, a, pb}");
        assertThat(SpectateScripts.PICK).contains("return {now}").contains("return {now, hit[1], hit[2]}");
    }
}
