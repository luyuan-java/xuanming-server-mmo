package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.robot.client.MatchAdminClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 匹配场景里随服务端走的取值，与服务端源码 / 配置 / 规格里的真值对上（做法同 {@link UpstreamConstantsTest}：读兄弟模块的文件文本）。
 * robot 是纯客户端、不依赖 xm-match，tip 的文案、时限、缺省的组队人数表、管理口路径只能各写一份；服务端改了而 robot 没跟上时，
 * 逐字节的文案断言会在切片上才红。单独构建 xm-robot（旁边没有那些文件）时跳过；文件在而内容找不到（改名 / 换了写法）则失败，提醒同步本测试。
 */
class MatchUpstreamTest {

    private static final String MATCH_TIP = "xm-match/src/main/java/com/game/match/support/MatchTip.java";
    private static final String MATCH_YAML = "xm-match/src/main/resources/application.yaml";
    private static final String MATCH_BUDGETS = "xm-api/src/main/java/com/game/api/match/MatchBudgets.java";
    private static final String MATCH_SPEC = "docs/porting/match-spec.md";
    private static final String RATING_CONTROLLER = "xm-match/src/main/java/com/game/match/admin/DevRatingController.java";
    private static final String ACTIVITY_CONTROLLER = "xm-match/src/main/java/com/game/match/admin/DevActivityBattleController.java";

    /** 仓库里的文件（surefire 的工作目录是 xm-robot，从仓库根目录跑时是根目录）；找不到就跳过这条用例。 */
    private static String file(String path) throws IOException {
        Path file = Path.of("..", path);
        if (!Files.isRegularFile(file)) {
            file = Path.of(path);
        }
        Assumptions.assumeTrue(Files.isRegularFile(file), "找不到 " + path + "（单独构建 xm-robot，或这个文件所在的工作包还没合入）");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** {@code MatchTip} 枚举各常量的文案：{@code NAME(MatchTips.CODE, "文案")} → NAME → 文案（{@code null} 写作空串）。 */
    static Map<String, String> tipTexts(String source) {
        Map<String, String> texts = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^\\s*([A-Z][A-Z_]+)\\(MatchTips\\.[A-Z_]+,\\s*(?:\"([^\"]*)\"|null)\\)[,;]").matcher(source);
        while (m.find()) {
            texts.put(m.group(1), m.group(2) == null ? "" : m.group(2));
        }
        return texts;
    }

    /** yaml 里 {@code key:} 之下缩进更深的 {@code 整数: 整数} 行（到缩进回退为止）。 */
    static Map<Integer, Integer> intMap(String yaml, String key) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        List<String> lines = yaml.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.strip().equals(key + ":")) {
                continue;
            }
            int indent = line.indexOf(key);
            for (int j = i + 1; j < lines.size(); j++) {
                String next = lines.get(j);
                if (next.isBlank() || next.strip().startsWith("#")) {
                    continue;
                }
                Matcher entry = Pattern.compile("^(\\s+)(\\d+):\\s*(\\d+)\\s*(#.*)?$").matcher(next);
                if (!entry.matches() || entry.group(1).length() <= indent) {
                    break;
                }
                map.put(Integer.parseInt(entry.group(2)), Integer.parseInt(entry.group(3)));
            }
            return map;
        }
        throw new AssertionError("yaml 里没有 " + key + ":（改名了就同步本测试）");
    }

    @Test
    void 读文件的办法本身_枚举文案与yaml的整数表() {
        String source = """
                    /** 注释里的 FAKE(MatchTips.X, "不算") 不在行首 */
                    NO_IDENTITY(MatchTips.INTERNAL, "缺少玩家身份"),
                    JOIN_IN_BATTLE(MatchTips.IN_BATTLE, "战斗尚未结束,无法排队"),
                    FEATURE_UNAVAILABLE(MatchTips.FEATURE_UNAVAILABLE, null);
                    private final int code;
                """;
        assertThat(tipTexts(source)).containsExactly(Map.entry("NO_IDENTITY", "缺少玩家身份"), Map.entry("JOIN_IN_BATTLE", "战斗尚未结束,无法排队"),
                Map.entry("FEATURE_UNAVAILABLE", ""));

        String yaml = """
                xm:
                  match:
                    rating:
                      draw-round-cap: 30
                    # 注释
                    pve-team-size-by-config-id:
                      1: 5
                      # 夹着的注释
                      3: 10   # 行尾注释
                    table-fingerprint-mode: warn
                    other:
                      2: 9
                """;
        assertThat(intMap(yaml, "pve-team-size-by-config-id")).containsExactly(Map.entry(1, 5), Map.entry(3, 10));
        assertThat(intMap(yaml, "other")).containsExactly(Map.entry(2, 9));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> intMap(yaml, "missing")).isInstanceOf(AssertionError.class);
    }

    @Test
    void tip的文案_与xm_match的MatchTip逐字相同() throws Exception {
        Map<String, String> upstream = tipTexts(file(MATCH_TIP));
        assertThat(upstream).as("MatchTip 的常量写法变了就同步本测试").hasSizeGreaterThanOrEqualTo(20);

        Map<String, String> robot = new LinkedHashMap<>();
        robot.put("JOIN_IN_BATTLE", BattleSmokeChecks.TEXT_IN_BATTLE);
        robot.put("JOIN_ALREADY_QUEUED", BattleSmokeChecks.TEXT_ALREADY_QUEUED);
        robot.put("JOIN_MODE_NOT_OPEN", BattleSmokeChecks.TEXT_MODE_NOT_OPEN);
        robot.put("JOIN_TEAM_SIZE_NOT_CONFIGURED", BattleSmokeChecks.TEXT_TEAM_SIZE_NOT_CONFIGURED);
        robot.put("CHALLENGE_SELF", BattleSmokeChecks.TEXT_CHALLENGE_SELF);
        robot.put("CHALLENGE_SELF_BUSY", BattleSmokeChecks.TEXT_CHALLENGE_SELF_BUSY);
        robot.put("CHALLENGE_TARGET_BUSY", BattleSmokeChecks.TEXT_CHALLENGE_TARGET_BUSY);
        robot.put("CHALLENGE_TARGET_OFFLINE", BattleSmokeChecks.TEXT_CHALLENGE_TARGET_OFFLINE);
        robot.put("CHALLENGE_PENDING", BattleSmokeChecks.TEXT_CHALLENGE_PENDING);
        robot.put("CHALLENGE_EXPIRED", BattleSmokeChecks.TEXT_CHALLENGE_EXPIRED);
        robot.put("CHALLENGE_NOT_TARGET", BattleSmokeChecks.TEXT_CHALLENGE_NOT_TARGET);
        robot.put("REISSUE_BATTLE_GONE", BattleSmokeChecks.TEXT_BATTLE_GONE);
        // 163 观战的七条（批次 6.5，spectate-spec §3.2；两条 16004 复用别的号的 NO_IDENTITY / BUSY，robot 不断言它们）
        robot.put("WATCH_QUEUED", SpectateSteps.TEXT_QUEUED);
        robot.put("WATCH_IN_BATTLE", SpectateSteps.TEXT_IN_BATTLE);
        robot.put("WATCH_ALREADY", SpectateSteps.TEXT_ALREADY_WATCHING);
        robot.put("WATCH_NO_BATTLE", SpectateSteps.TEXT_NO_BATTLE);
        robot.put("WATCH_NOT_FOUND", SpectateSteps.TEXT_NOT_FOUND);
        robot.put("WATCH_NOT_WATCHABLE", SpectateSteps.TEXT_NOT_WATCHABLE);
        robot.put("WATCH_OFFLINE", SpectateSteps.TEXT_OFFLINE);
        assertThat(upstream).containsAllEntriesOf(robot);
        // 6.4 的临时应答（1006）随 M22 关闭删除：MatchTip 里不再有它，robot 的场景也不再断言它
        assertThat(upstream).doesNotContainKey("FEATURE_UNAVAILABLE");
        assertThat(upstream.keySet().stream().filter(k -> k.startsWith("WATCH_"))).as("MatchTip 加了新的观战文案就同步 SpectateSteps 与本测试")
                .containsExactlyInAnyOrder("WATCH_QUEUED", "WATCH_IN_BATTLE", "WATCH_ALREADY", "WATCH_NO_BATTLE", "WATCH_NOT_FOUND",
                        "WATCH_NOT_WATCHABLE", "WATCH_OFFLINE");
    }

    @Test
    void 观战码的常量名_与xm_match的MatchTips用的是同一组导表枚举() throws Exception {
        String tips = file("xm-match/src/main/java/com/game/match/support/MatchTips.java");
        // robot 与 xm-match 各自从导表生成的枚举取值；这里只钉「取的是同一个枚举常量」，数值由 SpectateStepsTest 钉
        for (String constant : List.of("kMatchSpectateWhileQueued_VALUE", "kMatchSpectateWhileInBattle_VALUE", "kMatchAlreadyWatching_VALUE",
                "kMatchNoWatchableBattle_VALUE", "kMatchBattleNotWatchable_VALUE", "kMatchSpectateOffline_VALUE")) {
            assertThat(tips).as("MatchTips 用到 %s", constant).contains("MatchErrorTip.match_error." + constant);
        }
    }

    @Test
    void 观战文案_规格里逐字写着() throws Exception {
        String spec = file("docs/porting/spectate-spec.md");
        for (String text : List.of(SpectateSteps.TEXT_QUEUED, SpectateSteps.TEXT_IN_BATTLE, SpectateSteps.TEXT_ALREADY_WATCHING, SpectateSteps.TEXT_NO_BATTLE,
                SpectateSteps.TEXT_NOT_FOUND, SpectateSteps.TEXT_NOT_WATCHABLE, SpectateSteps.TEXT_OFFLINE)) {
            assertThat(spec).as("spectate-spec 里逐字写着「%s」", text).contains(text);
        }
    }

    @Test
    void 列表的条数收口与过期分界_ready票据的TTL_与xm_match一致() throws Exception {
        String budgets = file(MATCH_BUDGETS);
        assertThat(SpectateSteps.LIST_DEFAULT).isEqualTo((int) UpstreamConstantsTest.number(budgets, "WATCHABLE_LIST_DEFAULT"));
        assertThat(SpectateSteps.LIST_MAX).isEqualTo((int) UpstreamConstantsTest.number(budgets, "WATCHABLE_LIST_MAX"));
        // 过期分界 = 落点记录的 TTL（一局的最长时长 + 60 s）：这两个常量在 MatchBudgets 里是用别的常量算出来的，按写法钉
        assertThat(budgets).contains("PLACEMENT_TTL_SECONDS = BATTLE_MAX_DURATION_SECONDS + 60;", "SPECTATE_STALE_MS = PLACEMENT_TTL_SECONDS * 1_000L;");
        assertThat(SpectateSteps.STALE_MS).isEqualTo((UpstreamConstantsTest.number(budgets, "BATTLE_MAX_DURATION_SECONDS") + 60) * 1000);

        Matcher ready = Pattern.compile("(?m)^\\s*ready-ticket-ttl:\\s*(\\d+)s\\b").matcher(file(MATCH_YAML));
        assertThat(ready.find()).as("application.yaml 里有 ready-ticket-ttl: Ns").isTrue();
        // S12 等上一局的 ready 票据过期：窗口 = 这个 TTL + 2 s
        assertThat(BattleSmokeChecks.READY_TICKET_TTL_MS).isEqualTo(Long.parseLong(ready.group(1)) * 1000);
        assertThat(SpectateSteps.Timing.STANDARD.readyResidue().toMillis()).isGreaterThan(BattleSmokeChecks.READY_TICKET_TTL_MS);
    }

    @Test
    void 回合超时的时长_与战斗引擎的常量一致() throws Exception {
        // 屏障期的战斗 X 不开自动，按回合超时一回合一回合地走：预算（Timing.liveBudget）与「X 提前结束」的提示都按它算
        String constants = file("xm-battle-engine/src/main/java/com/game/battle/engine/BattleConstants.java");
        assertThat(SpectateSteps.ROUND_TIMEOUT_MS).isEqualTo(UpstreamConstantsTest.number(constants, "ROUND_DURATION_MS"));
    }

    @Test
    void 观战指标的名字与标签值_与xm_match的MatchMetrics一致() throws Exception {
        String metrics = file("xm-match/src/main/java/com/game/match/metrics/MatchMetrics.java");
        // 场景的 S13 / Z10 按 Prometheus 名（点换下划线、计数器加 _total）与小写的枚举名取值
        assertThat(metrics).contains("WATCH_BATTLE = \"xm.match.watch.battle\"", "SPECTATE_EVICTIONS = \"xm.match.spectate.evictions\"",
                "GATHER_ZONE_MIX = \"xm.match.gather.zone.mix\"");
        assertThat(metrics).as("163 出口的枚举里有 ok / queued / in_battle / not_found").containsPattern(
                "enum WatchOutcome \\{[^}]*\\bOK\\b[^}]*\\bQUEUED\\b[^}]*\\bIN_BATTLE\\b[^}]*\\bNOT_FOUND\\b[^}]*}");
        assertThat(metrics).containsPattern("enum EvictReason \\{[^}]*\\bENTER_GATHER\\b[^}]*}").containsPattern("enum EvictResult \\{[^}]*\\bREMOVED\\b[^}]*}")
                .containsPattern("enum ZoneMix \\{[^}]*\\bCROSS\\b[^}]*}");
    }

    @Test
    void tip的文案_规格里逐字写着() throws Exception {
        String spec = file(MATCH_SPEC);
        for (String text : List.of(BattleSmokeChecks.TEXT_IN_BATTLE, BattleSmokeChecks.TEXT_ALREADY_QUEUED, BattleSmokeChecks.TEXT_MODE_NOT_OPEN,
                BattleSmokeChecks.TEXT_TEAM_SIZE_NOT_CONFIGURED, BattleSmokeChecks.TEXT_CHALLENGE_SELF, BattleSmokeChecks.TEXT_CHALLENGE_SELF_BUSY,
                BattleSmokeChecks.TEXT_CHALLENGE_TARGET_BUSY, BattleSmokeChecks.TEXT_CHALLENGE_TARGET_OFFLINE, BattleSmokeChecks.TEXT_CHALLENGE_PENDING,
                BattleSmokeChecks.TEXT_CHALLENGE_EXPIRED, BattleSmokeChecks.TEXT_CHALLENGE_NOT_TARGET, BattleSmokeChecks.TEXT_BATTLE_GONE)) {
            assertThat(spec).as("match-spec 里逐字写着「%s」", text).contains(text);
        }
    }

    @Test
    void 回合打满的阈值_切磋有效期_缺省的组队人数表_与xm_match的缺省配置一致() throws Exception {
        String yaml = file(MATCH_YAML);

        Matcher cap = Pattern.compile("(?m)^\\s*draw-round-cap:\\s*(\\d+)\\b").matcher(yaml);
        assertThat(cap.find()).as("application.yaml 里有 draw-round-cap: N").isTrue();
        // 第 8 步的评分判据「回合数 ≥ 30 按平局」按它算
        assertThat(BattleSmokeChecks.RATING_DRAW_ROUND_CAP).isEqualTo(Integer.parseInt(cap.group(1)));

        Matcher ttl = Pattern.compile("(?m)^\\s*challenge-ttl:\\s*(\\d+)s\\b").matcher(yaml);
        assertThat(ttl.find()).as("application.yaml 里有 challenge-ttl: Ns").isTrue();
        assertThat(BattleSmokeChecks.CHALLENGE_TTL_MS).isEqualTo(Long.parseLong(ttl.group(1)) * 1000);

        Map<Integer, Integer> teamSizes = intMap(yaml, "pve-team-size-by-config-id");
        // PVE 组队只开放 Dungeon 1：整队开战与活动开战用它；battle-smoke / team 拿「没配的副本」核对 16003 / 4027
        assertThat(teamSizes).containsKey(TeamMatchSteps.BATTLE_CONFIG_ID).containsKey(MatchActivityScenario.BATTLE_CONFIG)
                .doesNotContainKey(TeamMatchSteps.BATTLE_CONFIG_NOT_OPEN).doesNotContainKey(BattleSmokeScenario.TEAM_CONFIG_NOT_OPEN);
        assertThat(teamSizes.get(TeamMatchSteps.BATTLE_CONFIG_ID)).as("两个人的队伍放得下").isGreaterThanOrEqualTo(2);
    }

    @Test
    void 一局的最长时长_与MatchBudgets一致() throws Exception {
        long seconds = UpstreamConstantsTest.number(file(MATCH_BUDGETS), "BATTLE_MAX_DURATION_SECONDS");
        // 第 4 步核对 177 的 expire_at_ms ≈ 发起时刻 + 这么久
        assertThat(BattleSmokeChecks.BATTLE_DURATION_MS).isEqualTo(seconds * 1000);
        assertThat(MatchSupport.BATTLE_END_TIMEOUT.toSeconds()).as("等终局的预算不必超过一局的最长时长").isLessThanOrEqualTo(seconds);
    }

    @Test
    void 管理口的两条路径_与xm_match的控制器一致() throws Exception {
        // 这两个控制器分属评分与点名开局两个工作包；它们合入之前这条用例跳过
        assertThat(MatchAdminClient.ACTIVITY_BATTLE_PATH).isEqualTo("/admin/match/dev/activity-battle");
        assertThat(MatchAdminClient.RATING_PATH).isEqualTo("/admin/match/dev/rating/");
        assertThat(file(ACTIVITY_CONTROLLER)).contains("\"" + MatchAdminClient.ACTIVITY_BATTLE_PATH + "\"");
        assertThat(file(RATING_CONTROLLER)).contains("\"" + MatchAdminClient.RATING_PATH.substring(0, MatchAdminClient.RATING_PATH.length() - 1));
    }
}
