package com.game.match.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 15 段 Lua 的静态约束（不连 Redis；行为由 {@code RedissonTicketStoreIntegrationTest} 在真 Redis 上钉）：脚本里写成字面量的字段名、状态值、
 * 剔除原因与 Java 侧一致；不在 Lua 里拼键名；只读的两段没有写命令；时间只取 Redis {@code TIME}。
 */
class TicketScriptsTest {

    private static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        ALL.put("S_STATUS", TicketScripts.STATUS);
        ALL.put("S_HEAL", TicketScripts.HEAL);
        ALL.put("S_JOIN", TicketScripts.JOIN);
        ALL.put("S_CREATE_GROUP", TicketScripts.CREATE_GROUP);
        ALL.put("S_CANCEL", TicketScripts.CANCEL);
        ALL.put("S_SNAPSHOT", TicketScripts.SNAPSHOT);
        ALL.put("S_DROP", TicketScripts.DROP);
        ALL.put("S_POP", TicketScripts.POP);
        ALL.put("S_READY", TicketScripts.READY);
        ALL.put("S_EXTEND", TicketScripts.EXTEND);
        ALL.put("S_DEL", TicketScripts.DEL);
        ALL.put("S_DEL_GROUP", TicketScripts.DEL_GROUP);
        ALL.put("S_REQUEUE", TicketScripts.REQUEUE);
        ALL.put("S_PRUNE", TicketScripts.PRUNE);
        ALL.put("S_LOCK_RELEASE", TicketScripts.LOCK_RELEASE);
    }

    private static final Pattern LOWERCASE_LITERAL = Pattern.compile("'([a-z_]+)'");
    private static final Pattern REDIS_COMMAND = Pattern.compile("redis\\.p?call\\('([A-Z]+)'");
    private static final Set<String> WRITE_COMMANDS = Set.of("DEL", "HSET", "HDEL", "PEXPIRE", "EXPIRE", "SET", "SADD", "SREM", "RPUSH", "LPUSH",
            "LREM", "ZADD", "ZREM");

    private static Set<String> commandsOf(String lua) {
        Set<String> commands = new TreeSet<>();
        Matcher matcher = REDIS_COMMAND.matcher(lua);
        while (matcher.find()) {
            commands.add(matcher.group(1));
        }
        return commands;
    }

    @Test
    void 一共十五段_互不相同() {
        assertThat(ALL).hasSize(15);
        assertThat(Set.copyOf(ALL.values())).hasSize(15);
        assertThat(ALL.values()).allSatisfy(lua -> assertThat(lua).isNotBlank());
    }

    @Test
    void 脚本里的小写字面量只有票据字段名_状态值_剔除原因与自愈模式() {
        Set<String> allowed = new TreeSet<>(TicketCodec.FIELDS);
        for (TicketState state : TicketState.values()) {
            if (state != TicketState.UNKNOWN) {
                allowed.add(state.wire());
            }
        }
        allowed.addAll(Set.of("invalid", "in_battle", "offline", "orphan", "table"));

        Set<String> used = new TreeSet<>();
        ALL.forEach((name, lua) -> {
            Matcher matcher = LOWERCASE_LITERAL.matcher(lua);
            while (matcher.find()) {
                assertThat(allowed).as("%s 里的字面量 '%s'", name, matcher.group(1)).contains(matcher.group(1));
                used.add(matcher.group(1));
            }
        });

        assertThat(used).as("每个票据字段都有脚本在读写（字段名两边一致）").containsAll(TicketCodec.FIELDS);
        assertThat(used).contains("queued", "matched", "ready");
    }

    @Test
    void 不在Lua里拼键名_键只从KEYS来() {
        ALL.forEach((name, lua) -> {
            assertThat(lua).as(name).doesNotContain("xm:").doesNotContain("{match}");
            assertThat(lua).as("%s 不拼接字符串造键", name).doesNotContain("..");
        });
    }

    @Test
    void 只读的两段没有写命令_其余每段至少有一条() {
        ALL.forEach((name, lua) -> {
            Set<String> writes = new TreeSet<>(commandsOf(lua));
            writes.retainAll(WRITE_COMMANDS);
            if (name.equals("S_STATUS") || name.equals("S_SNAPSHOT")) {
                assertThat(writes).as(name).isEmpty();
            } else {
                assertThat(writes).as(name).isNotEmpty();
            }
        });
    }

    @Test
    void 各段用到的命令钉住() {
        assertThat(commandsOf(TicketScripts.STATUS)).containsExactly("HGETALL", "TIME");
        assertThat(commandsOf(TicketScripts.HEAL)).containsExactly("DEL", "EXISTS", "HGET", "LPOS");
        assertThat(commandsOf(TicketScripts.JOIN)).containsExactly("EXISTS", "HGET", "HSET", "PEXPIRE", "RPUSH", "SADD", "TIME", "ZADD");
        assertThat(commandsOf(TicketScripts.CREATE_GROUP)).containsExactly("EXISTS", "HGET", "HSET", "PEXPIRE", "TIME");
        assertThat(commandsOf(TicketScripts.CANCEL)).containsExactly("DEL", "HGET", "LREM", "ZREM");
        assertThat(commandsOf(TicketScripts.SNAPSHOT)).containsExactly("LRANGE", "TIME", "ZSCORE");
        assertThat(commandsOf(TicketScripts.DROP)).containsExactly("DEL", "HMGET", "LREM", "ZREM");
        assertThat(commandsOf(TicketScripts.POP)).containsExactly("EXISTS", "HMGET", "HSET", "LPOS", "LREM", "PEXPIRE", "SET", "TIME", "ZREM");
        assertThat(commandsOf(TicketScripts.READY)).containsExactly("HGET", "HSET", "PEXPIRE");
        assertThat(commandsOf(TicketScripts.EXTEND)).containsExactly("HGET", "PEXPIRE");
        assertThat(commandsOf(TicketScripts.DEL)).containsExactly("DEL", "HGET");
        assertThat(commandsOf(TicketScripts.DEL_GROUP)).containsExactly("DEL", "HGET");
        assertThat(commandsOf(TicketScripts.REQUEUE)).containsExactly("DEL", "GET", "HDEL", "HMGET", "HSET", "LPUSH", "PEXPIRE", "SADD", "SET", "TIME",
                "ZADD");
        assertThat(commandsOf(TicketScripts.PRUNE)).containsExactly("DEL", "LLEN", "SREM", "ZCARD");
        assertThat(commandsOf(TicketScripts.LOCK_RELEASE)).containsExactly("DEL", "GET");
    }

    @Test
    void 要写时刻或判退避的脚本自己取TIME_不收调用方的时钟() {
        for (String name : Set.of("S_STATUS", "S_JOIN", "S_CREATE_GROUP", "S_SNAPSHOT", "S_POP", "S_REQUEUE")) {
            assertThat(ALL.get(name)).as(name).contains("redis.call('TIME')");
        }
        // 写回毫秒数一律 %.0f：Lua 的 tostring 对大数会输出科学计数法
        for (String name : Set.of("S_JOIN", "S_CREATE_GROUP", "S_REQUEUE")) {
            assertThat(ALL.get(name)).as(name).contains("string.format('%.0f'");
        }
    }

    @Test
    void 玩家号票号从不tonumber_只有TTL_退避_前缀长度_票里的退避时刻与回队首标记里的人数会转成数字() {
        Pattern toNumber = Pattern.compile("tonumber\\(([^)]*)\\)");
        Set<String> converted = new TreeSet<>();
        ALL.forEach((name, lua) -> {
            Matcher matcher = toNumber.matcher(lua);
            while (matcher.find()) {
                converted.add(name + ":" + matcher.group(1));
            }
        });

        assertThat(converted).containsExactlyInAnyOrder("S_SNAPSHOT:ARGV[1]", "S_POP:f[4]", "S_REQUEUE:ARGV[2]", "S_REQUEUE:marked");
    }
}
