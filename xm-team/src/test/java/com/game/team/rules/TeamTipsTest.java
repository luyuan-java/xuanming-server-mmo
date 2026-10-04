package com.game.team.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.table.TeamErrorTip;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * tip 码护栏（基线 go/match/internal/team/errors_test.go）：码在 team 段内且被生成枚举识别、互不相同、
 * 只有 INTERNAL 是故障、{@link TeamTips} 里不手写码值。
 */
class TeamTipsTest {

    /** 段声明之外的 int 常量（不是 tip 码）。 */
    private static final Set<String> SEGMENT_CONSTANTS = Set.of("SEGMENT_BASE", "SEGMENT_WIDTH");

    private static Map<String, Integer> tipCodes() {
        Map<String, Integer> codes = new LinkedHashMap<>();
        codes.put("PLAYER_ID", TeamTips.PLAYER_ID);
        codes.put("MEMBERS_FULL", TeamTips.MEMBERS_FULL);
        codes.put("MEMBER_IN_TEAM", TeamTips.MEMBER_IN_TEAM);
        codes.put("MEMBER_NOT_IN_TEAM", TeamTips.MEMBER_NOT_IN_TEAM);
        codes.put("KICK_SELF", TeamTips.KICK_SELF);
        codes.put("KICK_NOT_LEADER", TeamTips.KICK_NOT_LEADER);
        codes.put("APPOINT_SELF", TeamTips.APPOINT_SELF);
        codes.put("APPOINT_NOT_LEADER", TeamTips.APPOINT_NOT_LEADER);
        codes.put("APPLICATION_NOT_FOUND", TeamTips.APPLICATION_NOT_FOUND);
        codes.put("NO_TEAM", TeamTips.NO_TEAM);
        codes.put("DISBAND_NOT_LEADER", TeamTips.DISBAND_NOT_LEADER);
        codes.put("TARGET_OFFLINE", TeamTips.TARGET_OFFLINE);
        codes.put("NOT_LEADER", TeamTips.NOT_LEADER);
        codes.put("HOME_ZONE_UNKNOWN", TeamTips.HOME_ZONE_UNKNOWN);
        codes.put("CROSS_ZONE_DENIED", TeamTips.CROSS_ZONE_DENIED);
        codes.put("INVITE_NOT_FOUND", TeamTips.INVITE_NOT_FOUND);
        codes.put("INVITE_LIMIT", TeamTips.INVITE_LIMIT);
        codes.put("IN_MATCH", TeamTips.IN_MATCH);
        codes.put("MEMBER_OFFLINE", TeamTips.MEMBER_OFFLINE);
        codes.put("MEMBER_IN_BATTLE", TeamTips.MEMBER_IN_BATTLE);
        codes.put("MEMBER_NOT_READY", TeamTips.MEMBER_NOT_READY);
        codes.put("DUNGEON_NOT_OPEN", TeamTips.DUNGEON_NOT_OPEN);
        codes.put("SIZE_EXCEEDED", TeamTips.SIZE_EXCEEDED);
        codes.put("STATE_CHANGED", TeamTips.STATE_CHANGED);
        codes.put("INTERNAL", TeamTips.INTERNAL);
        return codes;
    }

    @Test
    void 码都在team段内且被生成枚举识别() {
        tipCodes().forEach((name, code) -> {
            assertThat(code).as("%s 落在 team 段 [4000,5000) 之外", name).isBetween(4000, 4999);
            assertThat(TeamTips.isTeamCode(code)).as(name).isTrue();
            assertThat(TeamErrorTip.team_error.forNumber(code)).as("%s = %d 未被生成枚举识别", name, code).isNotNull();
        });
        assertThat(TeamTips.OK).isZero();
        assertThat(TeamTips.isTeamCode(TeamTips.OK)).isFalse();
        assertThat(TeamTips.isTeamCode(3999)).isFalse();
        assertThat(TeamTips.isTeamCode(4000)).isTrue();
        assertThat(TeamTips.isTeamCode(4999)).isTrue();
        assertThat(TeamTips.isTeamCode(5000)).isFalse();
    }

    @Test
    void 码互不相同() {
        Map<Integer, String> seen = new HashMap<>();
        tipCodes().forEach((name, code) -> {
            String previous = seen.put(code, name);
            assertThat(previous).as("tip 码 %d 被 %s 与 %s 同时使用", code, previous, name).isNull();
        });
    }

    /** 基线 fault 列只有 TeamInternal 标 1（faults.go:56）；Java 没有 fault 列，这里钉住写死的集合 = {4030}。 */
    @Test
    void 只有INTERNAL是故障() {
        tipCodes().forEach((name, code) ->
                assertThat(TeamTips.isFault(code)).as("%s = %d 的 fault 分类", name, code).isEqualTo(name.equals("INTERNAL")));
        assertThat(TeamTips.FAULTS).containsExactly(4030);
        assertThat(TeamTips.isFault(TeamTips.OK)).isFalse();
    }

    /** 每个码都必须直接写成 {@code TeamErrorTip.team_error.kTeamX_VALUE}，不能手写数字、别名或引用其他码轴（errors_test.go:92-132）。 */
    @Test
    void 不手写码值_常量与护栏表一一对应() throws IOException {
        Path source = Path.of("src/main/java/com/game/team/rules/TeamTips.java");
        assertThat(source).exists();
        String text = Files.readString(source, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("static\\s+final\\s+int\\s+(\\w+)\\s*=\\s*([^;]+);").matcher(text);
        Pattern generatedRef = Pattern.compile("TeamErrorTip\\.team_error\\.kTeam\\w+_VALUE");
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String name = m.group(1);
            if (SEGMENT_CONSTANTS.contains(name)) {
                continue;
            }
            seen.add(name);
            assertThat(generatedRef.matcher(m.group(2).trim()).matches())
                    .as("%s 必须直接写成 TeamErrorTip.team_error.kTeamX_VALUE，实际是 %s", name, m.group(2).trim())
                    .isTrue();
        }
        Set<String> want = new HashSet<>(tipCodes().keySet());
        want.add("OK");
        assertThat(seen).as("TeamTips 的码常量与护栏表必须一一对应（新增码要同步纳入 tipCodes）")
                .containsExactlyInAnyOrderElementsOf(want);

        // 反射再核一遍：公开的 int 常量（不论怎么写的）都在护栏表里
        Set<String> declared = new HashSet<>();
        for (Field f : TeamTips.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (f.getType() == int.class && Modifier.isStatic(mod) && Modifier.isFinal(mod)
                    && !SEGMENT_CONSTANTS.contains(f.getName())) {
                declared.add(f.getName());
            }
        }
        assertThat(declared).containsExactlyInAnyOrderElementsOf(want);
    }
}
