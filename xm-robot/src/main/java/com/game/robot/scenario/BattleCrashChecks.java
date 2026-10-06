package com.game.robot.scenario;

import com.game.proto.eBattleOutcome;
import com.game.robot.CrashWindowOptions.Phase;
import com.game.robot.CrashWindowOptions.Variant;
import com.game.robot.client.RobotException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * battle-settle 故障变体（scene-battle-spec §13.8「故障变体」，{@link BattleCrashScenario}）的纯函数件：状态文件的编解码与原子写、
 * 「这一局见证得了恰好一次（打赢且 gold_gain &gt; 0）」「金币恰好增一次」「150 至多一份」「rescue 在期限 + 10 s 之后到账」「窗口确实撑开了」
 * 「arm 记下的结局是 rescued」的判定、汇总行。不碰网络，单测覆盖。
 */
final class BattleCrashChecks {

    /**
     * 故障变体的判定版本，也是状态文件的 {@code version}：不认识的版本拒读（两次调用的 robot 必须是同一版）。
     * <b>改了这里或 {@link BattleCrashScenario} 的判定（什么样的一局能写断点、verify 认哪种结局、状态文件的字段）就加一</b>，并同步
     * {@code tools/local/battle-crash-window.sh} 的 {@code ROBOT_CRASH_REVISION}（{@code SliceScriptsTest} 钉住两边相等）。
     * 脚本拿 robot 帮助里的 {@link #revisionMarker()} 拒绝判定过期的旧包：只看帮助里有没有 {@code --crash-window}，
     * 拦不住「认这个选项、判定却是旧的」的包（2026-10-06 实例：target 下的 jar 比本类早半小时，verify 会写出 {@code BATTLE_CRASH_OK … outcome=early}）。
     * <ul>
     *   <li>1：最初的版本（写断点不看胜负与 gold_gain，verify 不看 arm 记下的结局）；</li>
     *   <li>2：打赢且 gold_gain &gt; 0 才写断点（{@link #armedAt}）、verify 拒绝 gold_gain = 0 的状态文件（{@link #goldWitnessProblem}）、
     *       battle-after-store 的 verify 只认 rescued（{@link #observedProblem}）。</li>
     * </ul>
     */
    static final int STATE_VERSION = 2;

    private BattleCrashChecks() {
    }

    /**
     * 帮助文本里的判定版本标记 {@code [crash-window-rev=N]}：纯 ASCII、两头有定界符。编排脚本按子串匹配它——robot 的标准输出编码不定
     * （Windows 上不加 {@code -Dstdout.encoding=UTF-8} 时中文是乱码），所以不能靠中文措辞；有定界符，{@code 2} 才不会被 {@code 21} 蒙混。
     */
    static String revisionMarker() {
        return "[crash-window-rev=" + STATE_VERSION + "]";
    }

    /** arm 阶段走到了哪里。文件里写小写。 */
    enum Stage {
        /** 已到断点（脚本看到这个值就可以杀进程）。 */
        ARMED,
        /** battle-after-store 的 arm 阶段在同一条连接上看完了 rescue 的结局（{@link State#outcome} 有值）。 */
        OBSERVED;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Stage ofWire(String text) {
            for (Stage s : values()) {
                if (s.wire().equals(text)) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * 状态文件的内容：arm 阶段在断点处写出，verify 阶段（另一次 robot 调用，中间隔着 kill -9 与重启）读回。
     * 文件是 UTF-8 的 {@code 键=值} 文本，一行一项、LF 换行、没有多余空白，编排脚本用 {@code grep '^stage=armed$'} 这样的整行匹配读它。
     *
     * @param account        参战账号（verify 用它重登；不含换行）
     * @param goldBefore     开打之前的金币（54）
     * @param goldGain       直连 150 里的 {@code settlement.gold_gain}
     * @param deadlineMs     这一局的战斗期限（Unix 毫秒）
     * @param breakpointAtMs 到达断点的时刻
     * @param lobbyEndAtMs   大厅 150 到达的时刻；还没收到为 0
     * @param outcome        arm 阶段观察到的结局（{@link Landing} 的小写名）；没有为空串
     */
    record State(Variant variant, Stage stage, String account, long playerId, long battleId, long goldBefore, long goldGain,
                 long deadlineMs, long breakpointAtMs, long lobbyEndAtMs, String outcome) {

        State {
            if (variant == null || variant == Variant.NONE) {
                throw new IllegalArgumentException("状态文件必须属于一个故障变体");
            }
            if (stage == null || account == null || account.isEmpty() || account.indexOf('\n') >= 0 || account.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("状态文件的 stage / account 不合法");
            }
            if (outcome == null || outcome.indexOf('\n') >= 0 || outcome.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("状态文件的 outcome 不合法");
            }
        }

        /** arm 阶段看完结局之后的状态。 */
        State observed(long lobbyEndAtMs, String outcome) {
            return new State(variant, Stage.OBSERVED, account, playerId, battleId, goldBefore, goldGain, deadlineMs, breakpointAtMs,
                    lobbyEndAtMs, outcome);
        }

        /** 编码成文件内容。玩家号 / battle_id 按无符号十进制。 */
        String encode() {
            return "# xm-robot battle-settle 故障变体的状态文件（scene-battle-spec §13.8）：arm 阶段写，编排脚本等它出现，verify 阶段读\n"
                    + "version=" + STATE_VERSION + "\n"
                    + "variant=" + variant.wire() + "\n"
                    + "stage=" + stage.wire() + "\n"
                    + "account=" + account + "\n"
                    + "player_id=" + Long.toUnsignedString(playerId) + "\n"
                    + "battle_id=" + Long.toUnsignedString(battleId) + "\n"
                    + "gold_before=" + goldBefore + "\n"
                    + "gold_gain=" + goldGain + "\n"
                    + "deadline_ms=" + deadlineMs + "\n"
                    + "breakpoint_at_ms=" + breakpointAtMs + "\n"
                    + "lobby_end_at_ms=" + lobbyEndAtMs + "\n"
                    + "outcome=" + outcome + "\n";
        }

        /**
         * 解析文件内容：空行与 {@code #} 开头的行跳过，不认识的键忽略（留给以后加字段），值按第一个 {@code =} 切开（账号里可以有 {@code =}）。
         * 缺键、数字不合法、版本或枚举不认识 → 抛出，消息写明是哪一项。
         */
        static State decode(String text) throws RobotException {
            Map<String, String> fields = new HashMap<>();
            for (String raw : text.split("\n")) {
                String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    throw new RobotException("状态文件有一行不是「键=值」：" + line);
                }
                fields.put(line.substring(0, eq), line.substring(eq + 1));
            }
            String version = require(fields, "version");
            if (!version.equals(Integer.toString(STATE_VERSION))) {
                throw new RobotException("状态文件的 version=" + version + "，本版 robot 只认 " + STATE_VERSION + "（arm 与 verify 要用同一个 robot 包）");
            }
            Variant variant = Variant.ofWire(require(fields, "variant"));
            if (variant == null || variant == Variant.NONE) {
                throw new RobotException("状态文件的 variant 不认识：" + fields.get("variant"));
            }
            Stage stage = Stage.ofWire(require(fields, "stage"));
            if (stage == null) {
                throw new RobotException("状态文件的 stage 不认识：" + fields.get("stage"));
            }
            String account = require(fields, "account");
            if (account.isEmpty()) {
                throw new RobotException("状态文件的 account 为空");
            }
            long playerId = unsigned(fields, "player_id");
            long battleId = unsigned(fields, "battle_id");
            if (playerId == 0 || battleId == 0) {
                throw new RobotException("状态文件的 player_id / battle_id 为 0");
            }
            return new State(variant, stage, account, playerId, battleId, nonNegative(fields, "gold_before"), nonNegative(fields, "gold_gain"),
                    nonNegative(fields, "deadline_ms"), nonNegative(fields, "breakpoint_at_ms"), nonNegative(fields, "lobby_end_at_ms"),
                    require(fields, "outcome"));
        }

        private static String require(Map<String, String> fields, String key) throws RobotException {
            String value = fields.get(key);
            if (value == null) {
                throw new RobotException("状态文件缺少 " + key);
            }
            return value;
        }

        private static long unsigned(Map<String, String> fields, String key) throws RobotException {
            String value = require(fields, key);
            try {
                return Long.parseUnsignedLong(value);
            } catch (NumberFormatException e) {
                throw new RobotException("状态文件的 " + key + " 不是无符号整数：" + value);
            }
        }

        private static long nonNegative(Map<String, String> fields, String key) throws RobotException {
            String value = require(fields, key);
            try {
                long v = Long.parseLong(value);
                if (v < 0) {
                    throw new NumberFormatException("负数");
                }
                return v;
            } catch (NumberFormatException e) {
                throw new RobotException("状态文件的 " + key + " 不是非负整数：" + value);
            }
        }
    }

    /**
     * 原子地写状态文件：先写同目录的临时文件再改名。编排脚本以「文件出现」为断点信号，读到的必须是完整内容，不能是写了一半的。
     * 文件系统不支持原子改名时退回普通改名（本机文件系统都支持）。
     */
    static void write(Path file, State state) throws RobotException {
        Path absolute = file.toAbsolutePath();
        Path tmp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try {
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Files.writeString(tmp, state.encode(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RobotException("写状态文件 " + absolute + " 失败：" + e, e);
        }
    }

    static State read(Path file) throws RobotException {
        try {
            return State.decode(Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            throw new RobotException("没有状态文件 " + file.toAbsolutePath() + "：先跑 --crash-phase arm（由 tools/local/battle-crash-window.sh 编排）", e);
        } catch (IOException e) {
            throw new RobotException("读状态文件 " + file.toAbsolutePath() + " 失败：" + e, e);
        }
    }

    /** 上一轮留下的状态文件不能被这一轮的脚本当成断点信号：arm 开始前删掉。 */
    static void delete(Path file) throws RobotException {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RobotException("删不掉上一轮的状态文件 " + file.toAbsolutePath() + "：" + e, e);
        }
    }

    // ------------------------------------------------------------------ 见证量（两个变体共用）

    /**
     * 这一局能不能拿来见证「恰好一次」（写断点之前的前置检查，两个变体共用）：必须打赢（{@code SIDE_A_WIN}）而且 gold_gain &gt; 0。
     * 金币是从客户端看得见、又分得清「没到账 / 到账一次 / 到账两次」的那个量；引擎只在 {@code SIDE_A_WIN} 且本人存活、没逃跑时结金币，
     * 打输、平局、本人阵亡都是 0。gold_gain = 0 时 scene-after-150 的「结算整笔丢失」与「已落盘、重登不重发」现象完全相同
     * （0 条 150、金币不变），battle-after-store 则撑不开窗口——这样的一局不能写断点、不能让编排脚本杀进程。
     * 成立返回 null，否则返回原因。
     */
    static String witnessProblem(eBattleOutcome outcome, long goldGain) {
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN) {
            return "这一局没有打赢（outcome=" + outcome + "，gold_gain=" + goldGain + "）：只有 SIDE_A_WIN 且本人存活才结金币，"
                    + "没有金币就见证不了「恰好一次」";
        }
        return goldWitnessProblem(goldGain);
    }

    /** {@link #witnessProblem} 里只看金币的那一半：verify 阶段读回状态文件时也用它（防旧版 robot 留下的或手改的文件）。 */
    static String goldWitnessProblem(long goldGain) {
        if (goldGain > 0) {
            return null;
        }
        return "gold_gain=" + goldGain + "：金币见证不了「恰好一次」（结算整笔丢失与已落盘不重发的现象相同：0 条 150、金币不变）";
    }

    /**
     * 断点处的状态（stage = armed）。两个变体写断点都只走这里：这一局见证不了「恰好一次」（{@link #witnessProblem}）就抛出、不产生状态，
     * 于是没有断点文件，编排脚本不会杀进程。
     *
     * @param outcome  直连 150 的 {@code outcome}
     * @param goldGain 直连 150 里的 {@code settlement.gold_gain}
     */
    static State armedAt(Variant variant, String account, long playerId, long battleId, long goldBefore, eBattleOutcome outcome, long goldGain,
                         long deadlineMs, long breakpointAtMs, long lobbyEndAtMs) throws RobotException {
        String problem = witnessProblem(outcome, goldGain);
        if (problem != null) {
            throw new RobotException("不写断点：" + problem);
        }
        return new State(variant, Stage.ARMED, account, playerId, battleId, goldBefore, goldGain, deadlineMs, breakpointAtMs, lobbyEndAtMs, "");
    }

    // ------------------------------------------------------------------ scene-after-150

    /** scene 重启后重登看到的本局结算是怎么落地的。 */
    enum Replay {
        /** 0 条 150：kill 落在落盘之后，账本已经持久，重登不重发。 */
        DURABLE,
        /** 1 条 150：kill 落在落盘之前，库里没有这笔，进场恢复按 Redis 里的待结算记录重放了一次。 */
        RECOVERED,
        /** ≥ 2 条：重复应用。 */
        DUPLICATED;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 重登后这条连接上收到的本局 150 条数 → 落地方式。 */
    static Replay replayOf(long lobbyEnds) {
        if (lobbyEnds <= 0) {
            return Replay.DURABLE;
        }
        return lobbyEnds == 1 ? Replay.RECOVERED : Replay.DUPLICATED;
    }

    /**
     * 「金币恰好增一次」：现值应等于开打前 + gold_gain。符合返回 null，否则返回原因（分清没到账、到账两次与其它）。
     * gold_gain 为 0 时这条判据退化成「金币不变」、见证不了「恰好一次」：场景在写断点之前（{@link #armedAt}）与 verify 读状态文件时
     * （{@link #goldWitnessProblem}）就拒绝这样的一局，走不到这里。
     */
    static String goldProblem(long before, long gain, long now) {
        if (now == before + gain) {
            return null;
        }
        String shape = before + " → " + now + "，gold_gain=" + gain;
        if (gain > 0 && now == before) {
            return "没有到账（奖励丢失）：" + shape;
        }
        if (gain > 0 && now == before + 2 * gain) {
            return "到账两次（重复发奖）：" + shape;
        }
        return "金币与「恰好增一次」不符：" + shape + "，期望 " + (before + gain);
    }

    // ------------------------------------------------------------------ battle-after-store

    /** battle 被 kill 之后，这一局的结算在 scene 上的结局（按大厅 150 的到达时刻判）。 */
    enum Landing {
        /** 期限 + 宽限之后才到：scene 的 reaper 在判废前读到了本局记录并应用（D21 的 rescue）。 */
        RESCUED,
        /** 期限 + 宽限之前就到了：是别的路径投递的（battle 没在窗口内死掉，或封禁没挡住），没走到 rescue。 */
        EARLY,
        /** 等到期限 + 宽限 + reaper 间隔上界都没有：奖励丢了。 */
        MISSING;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param lobbyEndAtMs 大厅 150 到达的时刻（0 = 没收到）
     * @param deadlineMs   战斗期限
     * @param graceMs      FIGHTING 判废宽限（10 s，D21）
     * @param toleranceMs  robot 与 scene 取时刻的误差余量（同一台机器，取 1 s）
     */
    static Landing landingOf(long lobbyEndAtMs, long deadlineMs, long graceMs, long toleranceMs) {
        if (lobbyEndAtMs <= 0) {
            return Landing.MISSING;
        }
        return lobbyEndAtMs >= deadlineMs + graceMs - toleranceMs ? Landing.RESCUED : Landing.EARLY;
    }

    /**
     * battle-after-store 的 verify 阶段能不能接着 arm 记下的结局往下核对：结局必须是 {@link Landing#RESCUED}。{@code early}（150 在期限 + 宽限
     * 之前就到了）与 {@code missing} 时 arm 阶段已经失败；而 verify 自己的三步（重登、不重发、锁已放）在 {@code early} 时照样会过，
     * 不拦住的话 verify 的汇总行会写成 {@code BATTLE_CRASH_OK … outcome=early}。成立返回 null，否则返回原因（以 {@code outcome=…} 开头）。
     */
    static String observedProblem(String outcome) {
        if (Landing.RESCUED.wire().equals(outcome)) {
            return null;
        }
        String head = "outcome=" + outcome + "：";
        if (Landing.EARLY.wire().equals(outcome)) {
            return head + "arm 阶段看到 150 在期限 + 10 s 之前就到了，没有走到 rescue；arm 阶段已失败，不再往下核对";
        }
        if (Landing.MISSING.wire().equals(outcome)) {
            return head + "arm 阶段等到期限 + 10 s + reaper 间隔都没有 150，已落库的结算没有到账；arm 阶段已失败，不再往下核对";
        }
        return head + "不是 arm 阶段会写出的结局（rescued / early / missing）";
    }

    /**
     * battle-after-store 的窗口是不是确实撑开了（断点之前的前置检查）：结算已落库（battle 的 {@code stored} +1）、首投到了 scene 但被延后
     * （{@code delivery{deferred}} +1：金币被封，整笔延后、零副作用）、大厅还没有 150，而且 gold_gain &gt; 0（等于 0 时金币先行那一步不会失败，窗口撑不开）。
     * 成立返回 null，否则返回原因。
     */
    static String holdProblem(double storedDelta, double deferredDelta, boolean lobbyEndSeen, long goldGain) {
        if (goldGain <= 0) {
            return "这一局的 gold_gain=" + goldGain + "，封金币撑不开窗口（金币先行那一步不会失败）";
        }
        if (lobbyEndSeen) {
            return "大厅已经收到 150：封禁没有挡住入账，结算已按正常投递应用";
        }
        if (storedDelta < 1) {
            return "battle 的 xm_battle_settlement_outbox_total{event=\"stored\"} 没有增长（" + storedDelta + "）：结算没有落库，不是「SET 之后」的窗口";
        }
        if (deferredDelta < 1) {
            return "battle 的 xm_battle_settlement_delivery_total{result=\"deferred\"} 没有增长（" + deferredDelta
                    + "）：首投没有被 scene 延后（没投到，或 scene 给了别的应答）";
        }
        return null;
    }

    /** 一条下行的墙钟到达时刻：收件箱只记 {@code System.nanoTime()}，用「现在」的两个读数换算。 */
    static long wallClockMillis(long eventNanos, long nowNanos, long nowMillis) {
        return nowMillis - (nowNanos - eventNanos) / 1_000_000;
    }

    // ------------------------------------------------------------------ 汇总

    /**
     * 汇总行（编排脚本与人都看它）：{@code BATTLE_CRASH_OK variant=… phase=… battle_id=… outcome=…} 或
     * {@code BATTLE_CRASH_FAIL variant=… phase=… step=…}。
     *
     * @param outcome     这一阶段观察到的结局（{@link Replay} / {@link Landing} 的小写名；没有为空串）
     * @param failedSteps 失败的步骤（按第一次失败的先后）；有失败却一步都没登记时写 {@code ?}
     */
    static String summaryLine(boolean passed, Variant variant, Phase phase, long battleId, String outcome, List<String> failedSteps) {
        String head = " variant=" + variant.wire() + " phase=" + phase.wire();
        if (passed && failedSteps.isEmpty()) {
            return "BATTLE_CRASH_OK" + head + " battle_id=" + Long.toUnsignedString(battleId) + " outcome=" + (outcome.isEmpty() ? "-" : outcome);
        }
        return "BATTLE_CRASH_FAIL" + head + " step=" + (failedSteps.isEmpty() ? "?" : String.join(",", failedSteps));
    }
}
