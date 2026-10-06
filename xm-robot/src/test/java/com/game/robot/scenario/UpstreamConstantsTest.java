package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * robot 里随服务端常量走的几个取值，与服务端源码里的真值对上。robot 是纯客户端，不依赖 xm-discovery / xm-player-store，这些值只能各写一份；
 * 服务端改了而 robot 没跟上时，rescue 的判定窗口（期限 + 宽限 − 1 s）、「解封必须赶在期限 + 宽限之前」的余量、慢用例的等待都会悄悄失效。
 * 做法同 {@code BattleSettleChecksTest} 核对 BattleGate 枚举：读兄弟模块的源码文本。单独构建 xm-robot（旁边没有那两个模块的源码）时跳过；
 * 源码在而常量找不到（改名 / 换了写法）则失败，提醒同步本测试。
 */
class UpstreamConstantsTest {

    private static final String BATTLE_REDIS = "xm-discovery/src/main/java/com/game/discovery/battle/BattleRedis.java";
    private static final String PLAYER_STORE = "xm-player-store/src/main/java/com/game/player/store/PlayerStore.java";

    /** 兄弟模块的源码（surefire 的工作目录是 xm-robot，从仓库根目录跑时是根目录）；找不到文件就跳过这条用例。 */
    private static String source(String path) throws IOException {
        Path file = Path.of("..", path);
        if (!Files.isRegularFile(file)) {
            file = Path.of(path);
        }
        Assumptions.assumeTrue(Files.isRegularFile(file), "找不到 " + path + "（单独构建 xm-robot）");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** {@code static final Duration NAME = Duration.ofXxx(N);} 的取值；没有这样的定义、或不止一处，都算失败。 */
    static Duration duration(String source, String name) {
        Matcher m = Pattern.compile("\\bstatic final Duration " + Pattern.quote(name) + "\\s*=\\s*Duration\\.of(Millis|Seconds|Minutes)\\(([0-9_]+)\\)\\s*;")
                .matcher(source);
        assertThat(m.find()).as("源码里有 static final Duration %s = Duration.ofXxx(N)（改名或换了写法就同步本测试）", name).isTrue();
        long n = Long.parseLong(m.group(2).replace("_", ""));
        Duration value = switch (m.group(1)) {
            case "Millis" -> Duration.ofMillis(n);
            case "Seconds" -> Duration.ofSeconds(n);
            default -> Duration.ofMinutes(n);
        };
        assertThat(m.find()).as("%s 只定义一次", name).isFalse();
        return value;
    }

    /** {@code static final int / long NAME = N;} 的取值；没有这样的定义、或不止一处，都算失败。 */
    static long number(String source, String name) {
        Matcher m = Pattern.compile("\\bstatic final (?:int|long) " + Pattern.quote(name) + "\\s*=\\s*([0-9_]+)L?\\s*;").matcher(source);
        assertThat(m.find()).as("源码里有 static final int / long %s = N（改名或换了写法就同步本测试）", name).isTrue();
        long value = Long.parseLong(m.group(1).replace("_", ""));
        assertThat(m.find()).as("%s 只定义一次", name).isFalse();
        return value;
    }

    @Test
    void 读常量的办法本身_三种Duration写法与整数都认_找不到或重复定义就失败() {
        String text = """
                    public static final Duration A = Duration.ofSeconds(10);
                    static final Duration B = Duration.ofMinutes(10);
                    private static final Duration C=Duration.ofMillis(1_500) ;
                    public static final long LOCK_EXTRA_TTL_SEC = 60;
                    public static final long TTL_SEC = 604_800L;
                    public static final int RETRY_MAX = 12;
                    public static final Duration AA = Duration.ofSeconds(99);
                    static final Duration TWICE = Duration.ofSeconds(1);
                    static final Duration TWICE = Duration.ofSeconds(2);
                    static final Duration COMPUTED = OTHER.plusSeconds(1);
                """;

        assertThat(duration(text, "A")).as("不会被同前缀的 AA 蒙混").isEqualTo(Duration.ofSeconds(10));
        assertThat(duration(text, "B")).isEqualTo(Duration.ofMinutes(10));
        assertThat(duration(text, "C")).isEqualTo(Duration.ofMillis(1_500));
        assertThat(number(text, "LOCK_EXTRA_TTL_SEC")).isEqualTo(60);
        assertThat(number(text, "TTL_SEC")).as("不会把 LOCK_EXTRA_TTL_SEC 当成它").isEqualTo(604_800);
        assertThat(number(text, "RETRY_MAX")).isEqualTo(12);
        assertThatThrownBy(() -> duration(text, "MISSING")).isInstanceOf(AssertionError.class).hasMessageContaining("MISSING");
        assertThatThrownBy(() -> duration(text, "TWICE")).isInstanceOf(AssertionError.class).hasMessageContaining("只定义一次");
        assertThatThrownBy(() -> duration(text, "COMPUTED")).as("不是字面量的写法读不了：失败，不猜").isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> number(text, "A")).isInstanceOf(AssertionError.class);
    }

    @Test
    void FIGHTING判废宽限_与BattleRedis的真值相等() throws Exception {
        Duration grace = duration(source(BATTLE_REDIS), "FIGHTING_EXPIRY_GRACE");

        // battle-settle 第 10 步的判废时刻、故障变体 battle-after-store 的 rescued / early 分界（期限 + 宽限 − 取时误差）与
        // 「杀进程、解封必须赶在期限 + 宽限之前」都按它算：服务端调大而 robot 没跟上，早到的 150 会被当成 rescue；调小则真 rescue 被判成 early
        assertThat(BattleSettleScenario.FIGHTING_GRACE).isEqualTo(grace);
        assertThat(BattleCrashScenario.RESCUE_HEADROOM).as("解封的提前量要小于宽限本身").isLessThan(grace);
    }

    @Test
    void reaper间隔的上界_与BattleRedis的缺省间隔一致_再留2秒() throws Exception {
        Duration reaper = duration(source(BATTLE_REDIS), "REAPER_INTERVAL");

        // xm.scene.battle.reaper-interval 只许调小（SceneNodeProperties 校验 ≤ REAPER_INTERVAL），所以缺省值就是上界
        assertThat(BattleSettleScenario.REAPER_SLACK).isEqualTo(reaper.plusSeconds(2));
    }

    @Test
    void 备战锁的额外TTL_与BattleRedis的真值相等() throws Exception {
        long extraSec = number(source(BATTLE_REDIS), "LOCK_EXTRA_TTL_SEC");

        // 第 5 步慢用例：备战期限 + 这么久之后锁才过期、再备战才成功（断言「不早于它 − 2 s」，等待预算「它 + 15 s」）
        assertThat(BattleSettleScenario.PREPARE_LOCK_EXTRA_TTL).isEqualTo(Duration.ofSeconds(extraSec));
    }

    @Test
    void 结算重投的间隔与次数_确认窗口短于一个间隔_慢用例越过整个重投窗口() throws Exception {
        String battleRedis = source(BATTLE_REDIS);
        Duration interval = duration(battleRedis, "SETTLEMENT_RETRY_INTERVAL");
        long retries = number(battleRedis, "SETTLEMENT_RETRY_MAX");

        // battle-after-store：直连 150 之后等 HOLD_SETTLE 确认「首投被延后、大厅没有 150」，必须短于一个重投间隔，
        // 才分得清「首投被延后」与「第一次重投也到了」
        assertThat(BattleCrashScenario.HOLD_SETTLE).isLessThan(interval);
        // 写出断点后等脚本杀 battle 的上限盖得过几个重投间隔（期间每次重投都被延后，不影响结论），但不该比它短
        assertThat(BattleCrashScenario.KILL_WAIT).isGreaterThanOrEqualTo(interval);
        // battle-settle 第 9 步：快跑只等落库与首投登记（短于一个间隔）；--slow 要越过 retries 次重投、第 retries + 1 轮用尽
        Duration window = interval.multipliedBy(retries + 1);
        assertThat(BattleSettleScenario.OFFLINE_SETTLE_FAST).isLessThan(interval);
        assertThat(BattleSettleScenario.OFFLINE_SETTLE_SLOW).isGreaterThanOrEqualTo(window).isLessThan(window.plus(interval));
    }

    @Test
    void 重登预算盖得过归属租约_PlayerStore的真值() throws Exception {
        Duration lease = duration(source(PLAYER_STORE), "OWNER_LEASE");

        // scene 被 kill -9 后没有人释放归属：要等租约过期才夺得回来、登得进去
        assertThat(BattleCrashScenario.RELOGIN_BUDGET).isGreaterThan(lease);
    }
}
