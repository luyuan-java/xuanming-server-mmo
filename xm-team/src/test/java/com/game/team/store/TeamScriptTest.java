package com.game.team.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.team.TeamRedisFields;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 七段脚本的执行模式、回复类型与进程间字段名契约（team-spec §1.3、§6.5、§8.2 第 7 条）。纯静态检查，不起 Redis。 */
class TeamScriptTest {

    @Test
    void 会写的脚本全部READ_WRITE_含邀请列表() {
        EnumSet<TeamScript> writers = EnumSet.of(TeamScript.COMMIT, TeamScript.INVITE_LIST, TeamScript.INVITE_PRUNE,
                TeamScript.TOUCH, TeamScript.HEAL_ORPHAN);
        for (TeamScript s : TeamScript.values()) {
            assertThat(s.writes()).as(s.name()).isEqualTo(writers.contains(s));
        }
        // 反向核对：脚本正文里出现写命令的必须标成 writes
        Pattern write = Pattern.compile("redis\\.call\\(\"(HSET|DEL|SET|EXPIRE|ZADD|ZREM|ZREMRANGEBYSCORE)\"");
        for (TeamScript s : TeamScript.values()) {
            Matcher m = write.matcher(s.lua());
            assertThat(m.find()).as("%s 正文是否含写命令", s.name()).isEqualTo(s.writes());
        }
    }

    @Test
    void 回复类型() {
        EnumSet<TeamScript> multi = EnumSet.of(TeamScript.COMMIT, TeamScript.READ, TeamScript.READ_MEMBERS,
                TeamScript.INVITE_LIST);
        for (TeamScript s : TeamScript.values()) {
            assertThat(s.multi()).as(s.name()).isEqualTo(multi.contains(s));
        }
    }

    @Test
    void 字段名与xm_scene共享的常量一致() {
        assertThat(TeamRedisFields.VER).isEqualTo("ver");
        assertThat(TeamRedisFields.PB).isEqualTo("pb");
        assertThat(TeamRedisFields.TID).isEqualTo("tid");
        assertThat(TeamRedisFields.EPOCH).isEqualTo("epoch");
        assertThat(TeamScript.COMMIT.lua()).contains("\"" + TeamRedisFields.VER + "\"", "\"" + TeamRedisFields.PB + "\"",
                "\"" + TeamRedisFields.TID + "\"", "\"" + TeamRedisFields.EPOCH + "\"");
        assertThat(TeamScript.READ.lua()).contains("\"" + TeamRedisFields.TID + "\"", "\"" + TeamRedisFields.EPOCH + "\"",
                "\"" + TeamRedisFields.VER + "\", \"" + TeamRedisFields.PB + "\"");
        // 脚本里不得出现别的字段名
        Matcher m = Pattern.compile("\"(HGET|HMGET|HSET)\", (KEYS\\[[^\\]]+\\]|key), \"(\\w+)\"").matcher(
                String.join("\n", java.util.Arrays.stream(TeamScript.values()).map(TeamScript::lua).toList()));
        int fields = 0;
        while (m.find()) {
            assertThat(m.group(3)).isIn("ver", "pb", "tid", "epoch");
            fields++;
        }
        assertThat(fields).as("扫描到的哈希字段访问").isGreaterThan(10);
    }

    @Test
    void TTL常量与脚本字面量一致() {
        assertThat(new String(TeamStore.TTL_ARG, StandardCharsets.US_ASCII)).isEqualTo(Long.toString(TeamStore.IDLE_TTL_SECONDS))
                .isEqualTo("86400");
        assertThat(TeamScript.COMMIT.lua()).contains("redis.call(\"EXPIRE\", key, \"" + TeamStore.INVITE_INDEX_TTL_SECONDS + "\")");
        assertThat(TeamStore.TOUCH_THRESHOLD_SECONDS).isEqualTo(43_200);
        assertThat(TeamStore.COMMIT_RETRIES).isEqualTo(3);
        assertThat(TeamStore.FREE_READ_RETRIES).isEqualTo(3);
        assertThat(TeamStore.READ_MEMBERS_RETRIES).isEqualTo(3);
        assertThat(TeamStore.REPAIR_CAP).isEqualTo(5);
    }

    @Test
    void id只当字符串比较_从不tonumber() {
        for (TeamScript s : TeamScript.values()) {
            assertThat(s.lua()).as(s.name()).doesNotContain("tonumber(ARGV[5])", "tonumber(tid)", "tonumber(v)",
                    "tonumber(ARGV[1])", "tonumber(ARGV[4])");
        }
    }
}
